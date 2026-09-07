package top.imsyy.splayer_next.android.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ExoPlayer 解码链 FFT 处理器，输出 fftBins[256] + lowFreq。
 *
 * <p>lowFreq 对齐 PC 端 AudioEffectManager：0–280Hz 均值→threshold=180→pow(x,2)→EMA(0.28)。
 * fftBins 为 80–2000Hz 紧凑频谱。PCM 原样透传；除 setListener 外都在音频线程调用。
 */
@UnstableApi
class FftAudioProcessor : BaseAudioProcessor() {
  companion object {
    // FFT 参数对齐 Web Audio AnalyserNode：fftSize=2048, minDecibels=-100, maxDecibels=-30。
    // lowFreq 语义对齐 PC 端 AudioEffectManager（0–280Hz 均值 + threshold + pow + EMA）。

    /** 频谱算法方案：PC 对齐 vs Android 原生 */
    const val ALGORITHM_PC = "pc"
    const val ALGORITHM_ANDROID = "android"

    /** FFT 窗口大小（必须 2 的幂）。2048 点 ~23Hz/bin@48kHz，足以分辨 低频 0–280Hz 频带。 */
    private const val FFT_SIZE = 2048
    private const val FFT_BIN_COUNT = FFT_SIZE / 2

    /** STFT hop size，50% overlap。 */
    private const val HOP_SIZE = FFT_SIZE / 2

    /** 对外频谱 bin 数，保持 256 兼容前端。 */
    private const val OUTPUT_BIN_COUNT = 256

    /** 回调节流 ~50ms（20Hz），对齐 PC fft.rs 的 50ms 定时器节奏。 */
    private const val FRAME_INTERVAL_NS = 50_000_000L

    /** 目标等效采样率，贴近 PC AudioContext。 */
    private const val TARGET_EFFECTIVE_RATE = 48000f

    /** 频谱柱频段（Hz）。 */
    private const val SPECTRUM_FREQ_LOW = 80f
    private const val SPECTRUM_FREQ_HIGH = 2000f

    /** AMLL 低频驱动频带上限（Hz）。起点为 bin 1（跳过 DC），对应 PC 端前 3 bin @ fftSize=512。 */
    private const val LOW_FREQ_BAND_END_HZ = 280f

    /** dB 归一化窗口（对齐 AnalyserNode 默认 minDecibels/maxDecibels）。 */
    private const val MIN_DB = -100f
    private const val MAX_DB = -30f

    /** FFT 输入增益，略低于 1 以压低整体频谱响应幅度。 */
    private const val INPUT_GAIN = 0.85f

    /** PC 对齐方案 dB 归一化：以 0 dB 为顶、向下 60 dB 截断（对齐 fft.rs 的 ((db+60)/60).clamp(0,1)） */
    private const val PC_MIN_DB = -60f
    private const val PC_MAX_DB = 0f

    /** PC 对齐方案输入增益（无衰减） */
    private const val PC_INPUT_GAIN = 1.0f

    /** PC 对齐方案 hop size：保持 50% 重叠避免清空缓冲导致推送频率降低（PC fft.rs 用 8192 大缓冲取最新 2048 实现隐式重叠，Android 用显式重叠近似） */
    private const val PC_HOP_SIZE = HOP_SIZE

    /** 低频底噪阈值（0–255 域）：低于此视为常态、不推动鼓点。 */
    private const val LOW_FREQ_THRESHOLD = 180f

    /** lowFreq EMA 平滑系数（对齐 PC 端 AudioEffectManager），raw 权重。 */
    private const val LOW_FREQ_SMOOTHING = 0.28f

    /** 预计算 Hann 窗系数。 */
    private val HANN_WINDOW = buildHannWindow(FFT_SIZE)

    private fun buildHannWindow(size: Int): FloatArray {
      val w = FloatArray(size)
      for (i in 0 until size) {
        w[i] = (0.5 * (1.0 - cos(2.0 * Math.PI * i / (size - 1)))).toFloat()
      }
      return w
    }

    /** 预计算 Hamming 窗系数（PC 对齐方案使用）。 */
    private val HAMMING_WINDOW = buildHammingWindow(FFT_SIZE)

    private fun buildHammingWindow(size: Int): FloatArray {
      val w = FloatArray(size)
      for (i in 0 until size) {
        w[i] = (0.54 - 0.46 * cos(2.0 * Math.PI * i / (size - 1))).toFloat()
      }
      return w
    }
  }

  /** FFT 实例 + 蓄水池 + 工作数组。均为 thread-confined，仅音频线程访问。 */
  private val fft = Fft(FFT_SIZE)
  private val sampleBuffer = FloatArray(FFT_SIZE)
  private var sampleBufferPos = 0
  private val fftReal = FloatArray(FFT_SIZE)
  private val fftImag = FloatArray(FFT_SIZE)

  /** FFT 原始 bin 强度（0-255 域），bin k 对应频率 k * effectiveRate / FFT_SIZE。仅用于 lowFreq 计算。 */
  private val fftBins = IntArray(FFT_BIN_COUNT)

  /** FFT 原始 bin 归一化幅度（mag/FFT_SIZE[/32768]），用于 outputBins 映射做先均值后 dB，对齐 PC fft.rs。 */
  private val normalizedBins = FloatArray(FFT_BIN_COUNT)

  /** 对外输出紧凑频谱：80-2000Hz bin 线性拉伸到 256；outputBins[0]=高频, [255]=低频。 */
  private val outputBins = IntArray(OUTPUT_BIN_COUNT)

  /** 上次回调时间戳，用于节流。 */
  private var lastCallbackNs = 0L

  /** 固定 stride，onConfigure 计算。 */
  private var frameStride = 1

  /** 当前等效采样率。 */
  private var effectiveRate = TARGET_EFFECTIVE_RATE

  /** 频谱柱 bin 范围。 */
  private var spectrumBinStart = 1
  private var spectrumBinEnd = 1

  /** AMLL 低频驱动 bin 范围（0–280Hz，bin 1 起跳过 DC）。 */
  private var lowFreqBandStart = 1
  private var lowFreqBandEnd = 1

  /** 低频包络平滑状态。 */
  private var lowFreqSmoothed = 0f

  @Volatile
  private var listener: DataListener? = null

  /** setListener 跨线程重置令牌：主线程只置 flag，音频线程在 analyze() 入口消费，避免状态字段 race */
  @Volatile
  private var pendingReset = false

  /** 当前算法方案，主线程 setAlgorithm 写、音频线程 runFftAndCallback 读 */
  @Volatile
  private var algorithmMode: String = ALGORITHM_PC

  fun interface DataListener {
    /**
     * @param fftBins 长度 256 的 0-255 频段能量（80-2000Hz 紧凑映射，bin[0]=高频, bin[255]=低频）
     * @param lowFreq 0–280Hz 频带阈值超出量的平滑值 [0, 1]，驱动 AMLL 鼓点跳动
     */
    fun onData(
      fftBins: IntArray,
      lowFreq: Float,
    )
  }

  fun setListener(listener: DataListener?) {
    this.listener = listener
    // 仅设 flag，不从主线程直写状态字段；音频线程在下一次 analyze 入口消费。
    pendingReset = true
  }

  /**
   * 切换算法方案。仅主线程调用；音频线程在 runFftAndCallback 入口读取 algorithmMode。
   * @param mode "pc" 对齐桌面端 fft.rs；"android" 保留原 Hann+50%重叠方案
   */
  fun setAlgorithm(mode: String) {
    if (mode != ALGORITHM_PC && mode != ALGORITHM_ANDROID) return
    this.algorithmMode = mode
  }

  /** 重置运行时状态。 */
  private fun resetVisualState() {
    sampleBufferPos = 0
    lastCallbackNs = 0L
    lowFreqSmoothed = 0f
  }

  /** 根据 effectiveRate 重算 bin 下标。 */
  private fun recomputeBinRanges() {
    val er = this.effectiveRate
    if (er <= 0f) return
    spectrumBinStart = kotlin.math.max(1, (SPECTRUM_FREQ_LOW * FFT_SIZE / er).toInt())
    spectrumBinEnd = kotlin.math.min(FFT_BIN_COUNT - 1, (SPECTRUM_FREQ_HIGH * FFT_SIZE / er + 0.999f).toInt())
    if (spectrumBinEnd < spectrumBinStart) spectrumBinEnd = spectrumBinStart
    // 0–280Hz 对齐 PC 端前 3 bin@fftSize=512；bin 1 起跳过 DC 避免直流偏置污染。
    lowFreqBandStart = 1
    lowFreqBandEnd = kotlin.math.min(FFT_BIN_COUNT - 1, (LOW_FREQ_BAND_END_HZ * FFT_SIZE / er + 0.999f).toInt())
    if (lowFreqBandEnd < lowFreqBandStart) lowFreqBandEnd = lowFreqBandStart
  }

  @Throws(AudioProcessor.UnhandledAudioFormatException::class)
  override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
    // 仅接受 16bit PCM，其他 encoding 交上游 ToInt16PcmAudioProcessor 转换
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
      throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }

    // 44.1/48kHz 不降采样，高采样率才降到接近 48kHz
    frameStride = kotlin.math.max(1, kotlin.math.round(inputAudioFormat.sampleRate / TARGET_EFFECTIVE_RATE).toInt())
    effectiveRate = inputAudioFormat.sampleRate.toFloat() / frameStride

    recomputeBinRanges()

    // 透传上游格式
    return inputAudioFormat
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val bytesRemaining = inputBuffer.remaining()
    if (bytesRemaining == 0) {
      return
    }

    // 分析 PCM
    analyze(inputBuffer)

    // 透传到下游
    val output = replaceOutputBuffer(bytesRemaining)
    output.put(inputBuffer)
    output.flip()
  }

  /** mono 化 + 写入累积缓冲，满后触发 FFT。用 duplicate() 不影响透传。 */
  private fun analyze(inputBuffer: ByteBuffer) {
    // setListener 可能在主线程设了 pendingReset，这里是唯一由音频线程消费点，
    // 以避免跨线程同时读写 sampleBufferPos / lastCallbackNs / lowFreqSmoothed 的 race。
    if (pendingReset) {
      pendingReset = false
      resetVisualState()
    }

    val cb = listener ?: return // 无消费者时跳过 FFT

    val channelCount = inputAudioFormat.channelCount
    if (channelCount <= 0) {
      return
    }

    val view = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
    val bytesPerFrame = channelCount * 2 // 16bit = 2 bytes/channel
    val totalFrames = view.remaining() / bytesPerFrame

    val strideFrames = frameStride

    // 降采样时连续 stride 个采样求平均，抓高频混叠
    var frame = 0
    while (frame + strideFrames <= totalFrames) {
      var monoSum = 0f
      for (sub in 0 until strideFrames) {
        val byteOffset = (frame + sub) * bytesPerFrame
        var chSum = 0
        for (ch in 0 until channelCount) {
          chSum += view.getShort(view.position() + byteOffset + ch * 2).toInt()
        }
        monoSum += chSum.toFloat() / channelCount
      }
      val mono = monoSum / strideFrames

      val inputGain = if (algorithmMode == ALGORITHM_PC) PC_INPUT_GAIN else INPUT_GAIN
      sampleBuffer[sampleBufferPos++] = mono * inputGain
      if (sampleBufferPos >= FFT_SIZE) {
        // 节流前置：未达 30Hz 间隔时不进入 FFT 计算，仅走 overlap shift，
        // 避免函数命名误导阅读者认为窗内已经做了 FFT。
        val now = System.nanoTime()
        if (now - lastCallbackNs >= FRAME_INTERVAL_NS) {
          lastCallbackNs = now
          runFftAndCallback(cb)
        }
        // overlap shift：把后部 (FFT_SIZE - hopSize) 个样本移到开头，下次从 (FFT_SIZE - hopSize) 位置续写。
        // PC 模式 hopSize = FFT_SIZE，keepCount = 0 → 清空缓冲（对齐 PC fft.rs 无重叠）。
        // 通用公式不再假设 HOP = FFT/2。
        val hopSize = if (algorithmMode == ALGORITHM_PC) PC_HOP_SIZE else HOP_SIZE
        val keepCount = FFT_SIZE - hopSize
        if (keepCount > 0) {
          System.arraycopy(sampleBuffer, hopSize, sampleBuffer, 0, keepCount)
          sampleBufferPos = keepCount
        } else {
          sampleBufferPos = 0
        }
      }
      frame += strideFrames
    }
  }

  private fun runFftAndCallback(cb: DataListener) {
    // 去 DC：避免直流偏置经窗函数泄漏到低频 bin
    var dcSum = 0f
    for (i in 0 until FFT_SIZE) {
      dcSum += sampleBuffer[i]
    }
    val dcMean = dcSum / FFT_SIZE

    // 去 DC + 加窗（PC: Hamming / Android: Hann）
    val window = if (algorithmMode == ALGORITHM_PC) HAMMING_WINDOW else HANN_WINDOW
    for (i in 0 until FFT_SIZE) {
      fftReal[i] = (sampleBuffer[i] - dcMean) * window[i]
      fftImag[i] = 0f
    }

    fft.transform(fftReal, fftImag)

    // 计算关心范围内的 bin，范围外置 0；FFT 自然顺序 bin k = k * effectiveRate / FFT_SIZE，无需镜像
    val (normMinDb, normMaxDb) =
      if (algorithmMode == ALGORITHM_PC) {
        PC_MIN_DB to PC_MAX_DB
      } else {
        MIN_DB to MAX_DB
      }
    val dbRange = normMaxDb - normMinDb
    val fftCalcStart = kotlin.math.min(lowFreqBandStart, spectrumBinStart)
    val fftCalcEnd = kotlin.math.max(lowFreqBandEnd, spectrumBinEnd)
    for (k in 0 until FFT_BIN_COUNT) {
      if (k < fftCalcStart || k > fftCalcEnd) {
        fftBins[k] = 0
        normalizedBins[k] = 0f
        continue
      }
      val real = fftReal[k]
      val imag = fftImag[k]
      val mag = sqrt(real * real + imag * imag)
      // PC 对齐方案：等价 fft.rs 的 mag / FFT_SIZE（sampleBuffer 为 16-bit PCM，额外除 32768 转 f32）
      // Android 原生方案保留 (FFT_SIZE / 2) 归一化，与历史行为一致
      val normalized =
        if (algorithmMode == ALGORITHM_PC) {
          mag / FFT_SIZE.toFloat() / 32768f
        } else {
          mag / (FFT_SIZE / 2f) / 32768f
        }
      // 保存归一化幅度，供 outputBins 映射做"先均值后 dB"对齐 PC fft.rs
      normalizedBins[k] = normalized
      // fftBins 仅用于 lowFreq 阈值计算，保留单 bin dB → 0-255 路径
      val db = if (normalized <= 1e-7f) normMinDb else 20f * log10(normalized)
      var t = (db - normMinDb) / dbRange
      if (t < 0f) {
        t = 0f
      } else if (t > 1f) {
        t = 1f
      }
      fftBins[k] = (t * 255f).toInt()
    }

    // AMLL 低频音量：对齐 PC 端 AudioEffectManager.getLowFrequencyVolume()
    //   1) 0–280Hz 均值   2) threshold=180 过滤底噪   3) pow(x, 2) 扩展动态   4) EMA 平滑
    var lowFreqSum = 0
    var lowFreqCount = 0
    for (i in lowFreqBandStart..lowFreqBandEnd) {
      lowFreqSum += fftBins[i]
      lowFreqCount++
    }
    val lowFreqAvg = if (lowFreqCount > 0) lowFreqSum.toFloat() / lowFreqCount else 0f
    var overThreshold = (lowFreqAvg - LOW_FREQ_THRESHOLD) / (255f - LOW_FREQ_THRESHOLD)
    if (overThreshold < 0f) overThreshold = 0f
    val lowFreqRaw = overThreshold * overThreshold
    lowFreqSmoothed += LOW_FREQ_SMOOTHING * (lowFreqRaw - lowFreqSmoothed)
    if (lowFreqSmoothed < 0f) {
      lowFreqSmoothed = 0f
    } else if (lowFreqSmoothed > 1f) {
      lowFreqSmoothed = 1f
    }

    // 紧凑映射：80-2000Hz bin 按对数间距聚合到 outputBins，对齐 PC 端 fft.rs
    // 对数映射让低频段占据更多 bin，配合渲染层空间邻域平滑，避免低音/人声混合时少数 bin 剧烈跳动
    // 输出按 outputBins[0]=高频, [255]=低频 反转，匹配前端 SKIP_BINS 跳过高频噪声的可视化布局
    if (spectrumBinEnd <= spectrumBinStart) {
      for (k in 0 until OUTPUT_BIN_COUNT) outputBins[k] = 0
    } else {
      val logMin = kotlin.math.ln(SPECTRUM_FREQ_LOW.toDouble())
      val logMax = kotlin.math.ln(SPECTRUM_FREQ_HIGH.toDouble())
      for (k in 0 until OUTPUT_BIN_COUNT) {
        // 对数频段边界 → bin 下标范围
        val freqLo = kotlin.math.exp(logMin + (logMax - logMin) * k / OUTPUT_BIN_COUNT)
        val freqHi = kotlin.math.exp(logMin + (logMax - logMin) * (k + 1) / OUTPUT_BIN_COUNT)
        val binLo = kotlin.math.max(spectrumBinStart, (freqLo * FFT_SIZE / effectiveRate).toInt())
        val binHi = kotlin.math.min(spectrumBinEnd, (freqHi * FFT_SIZE / effectiveRate + 0.999f).toInt())
        if (binLo >= binHi) {
          outputBins[OUTPUT_BIN_COUNT - 1 - k] = 0
          continue
        }
        // 先做归一化幅度均值，再转 dB，对齐 PC fft.rs 的 avg = mean(mag/FFT_SIZE); db = 20*log10(avg)
        // 旧实现"先 dB 后均值"因 log 凹性 mean(dB(x)) <= dB(mean(x)) 导致频谱柱偏矮
        var sum = 0f
        for (j in binLo until binHi) sum += normalizedBins[j]
        val avg = sum / (binHi - binLo)
        val db = if (avg <= 1e-7f) normMinDb else 20f * log10(avg)
        var t = (db - normMinDb) / dbRange
        if (t < 0f) {
          t = 0f
        } else if (t > 1f) {
          t = 1f
        }
        outputBins[OUTPUT_BIN_COUNT - 1 - k] = (t * 255f).toInt()
      }
    }

    cb.onData(outputBins, lowFreqSmoothed)
  }

  override fun onFlush() {
    // seek/flush 重置蓄水池，避免跨片段污染
    resetVisualState()
  }

  override fun onReset() {
    resetVisualState()
    // listener 由 PlaybackManager 管理，不清
  }

  /**
   * 原地 radix-2 Cooley-Tukey FFT。
   *
   * 仅支持 size 为 2 的幂；构造时预计算旋转因子（W = e^(-i*2π*k/N)）的 cos/sin 表，
   * 避免每次 FFT 都重算三角函数。
   *
   * 用法：
   * <pre>
   *   Fft fft = new Fft(2048);
   *   float[] real = new float[2048];
   *   float[] imag = new float[2048]; // 全零
   *   // ... 填入 PCM 样本到 real ...
   *   fft.transform(real, imag);
   *   // 频谱幅度 = sqrt(real[k]^2 + imag[k]^2)，k = 0..N/2-1
   * </pre>
   *
   * 不是线程安全：调用方应在单一线程（音频处理线程）内重用。
   */
  private class Fft(
    val size: Int,
  ) {
    private val log2Size: Int
    private val cosTable: FloatArray
    private val sinTable: FloatArray
    private val bitReverseTable: IntArray

    init {
      require(size > 0 && (size and (size - 1)) == 0) { "FFT size must be a power of 2, got: $size" }
      this.log2Size = Integer.numberOfTrailingZeros(size)

      // 预计算旋转因子表（半周期足够，二象限对称由 sign 决定）
      val half = size / 2
      cosTable = FloatArray(half)
      sinTable = FloatArray(half)
      for (i in 0 until half) {
        val angle = -2.0 * Math.PI * i / size
        cosTable[i] = cos(angle).toFloat()
        sinTable[i] = sin(angle).toFloat()
      }

      // 预计算位反转索引表
      bitReverseTable = IntArray(size)
      for (i in 0 until size) {
        bitReverseTable[i] = reverseBits(i, log2Size)
      }
    }

    /**
     * 原地 FFT。real / imag 长度必须等于构造时的 size。输出位置 k 的频段对应频率 k * sampleRate / size。
     */
    fun transform(
      real: FloatArray,
      imag: FloatArray,
    ) {
      require(real.size == size && imag.size == size) {
        "FFT input length mismatch: expected $size, got real=${real.size}, imag=${imag.size}"
      }

      // 位反转排序：经典 Cooley-Tukey 第一步
      for (i in 0 until size) {
        val j = bitReverseTable[i]
        if (j > i) {
          val tmpR = real[i]
          real[i] = real[j]
          real[j] = tmpR
          val tmpI = imag[i]
          imag[i] = imag[j]
          imag[j] = tmpI
        }
      }

      // 蝶形运算：从 size=2 开始倍增
      for (stage in 1..log2Size) {
        val m = 1 shl stage // 当前蝶形段长度
        val mHalf = m shr 1
        val twiddleStep = size / m // 旋转因子在 cosTable/sinTable 中的步长

        for (k in 0 until size step m) {
          for (j in 0 until mHalf) {
            val twiddleIndex = j * twiddleStep
            val wR = cosTable[twiddleIndex]
            val wI = sinTable[twiddleIndex]

            val idx = k + j
            val idxPair = idx + mHalf
            val tR = wR * real[idxPair] - wI * imag[idxPair]
            val tI = wR * imag[idxPair] + wI * real[idxPair]

            real[idxPair] = real[idx] - tR
            imag[idxPair] = imag[idx] - tI
            real[idx] = real[idx] + tR
            imag[idx] = imag[idx] + tI
          }
        }
      }
    }

    private fun reverseBits(
      x: Int,
      bits: Int,
    ): Int {
      var result = 0
      var tempX = x
      for (i in 0 until bits) {
        result = (result shl 1) or (tempX and 1)
        tempX = tempX ushr 1
      }
      return result
    }
  }
}
