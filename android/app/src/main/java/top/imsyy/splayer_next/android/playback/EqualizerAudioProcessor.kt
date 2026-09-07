package top.imsyy.splayer_next.android.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 10 频段参数化均衡器（peaking biquad）+ 前级增益。
 *
 * <p>算法对齐桌面端 `native/audio-engine/src/equalizer.rs`：
 * - RBJ Audio EQ Cookbook peaking 公式
 * - Direct Form II Transposed 实现（数值稳定）
 * - 10 频段中心频率 [31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k] Hz
 * - Q 值 1.4，对齐 lx-music Web Audio BiquadFilterNode 默认 Q
 * - 频段增益 ±15dB，前级增益 ±12dB
 *
 * <p>线程模型：setEnabled/setBands/setPreamp 在主线程调用，queueInput 在音频线程调用。
 * 通过 @Volatile 的 [EqSettings] 原子替换保证无锁线程安全；[zStates] 仅音频线程访问，无竞争。
 */
@UnstableApi
class EqualizerAudioProcessor : BaseAudioProcessor() {
  companion object {
    /** 频段中心频率（Hz），对齐桌面端 EQ_FREQUENCIES */
    private val EQ_FREQUENCIES =
      floatArrayOf(
        31f,
        62f,
        125f,
        250f,
        500f,
        1000f,
        2000f,
        4000f,
        8000f,
        16000f,
      )

    /** 滤波器 Q 值，对齐桌面端 EQ_Q */
    private const val EQ_Q = 1.4f
    private const val BAND_COUNT = 10
    private const val CHANNEL_COUNT = 2

    /** 每段增益限制（dB），对齐桌面端 BAND_GAIN_LIMIT_DB */
    private const val BAND_GAIN_LIMIT_DB = 15f

    /** 前级增益限制（dB），对齐桌面端 PREAMP_LIMIT_DB */
    private const val PREAMP_LIMIT_DB = 12f
  }

  /** 单个 biquad 的系数快照（不可变） */
  private class BiquadCoeffs(
    val b0: Float,
    val b1: Float,
    val b2: Float,
    val a1: Float,
    val a2: Float,
  )

  /** EQ 整体配置快照（不可变），原子替换保证线程安全 */
  private class EqSettings(
    val enabled: Boolean,
    val preampDb: Float,
    val sampleRate: Int,
    val bandGainsDb: FloatArray,
    val coeffs: Array<BiquadCoeffs>,
  ) {
    /** dB → 线性增益，对齐桌面端 db_to_linear */
    val preampLinear: Float = 10.0.pow(preampDb / 20.0).toFloat()
  }

  @Volatile
  private var settings: EqSettings = buildSettings(false, FloatArray(BAND_COUNT), 0f, 48000)

  /** 滤波器状态 z1/z2，[声道][频段][0..1]，仅音频线程 queueInput 访问 */
  private val zStates = Array(CHANNEL_COUNT) { Array(BAND_COUNT) { FloatArray(2) } }

  /** 参数跨线程切换后由音频线程消费，避免主线程直接重置 zStates 造成竞争 */
  @Volatile
  private var pendingStateReset = false

  /** 根据 gains/preamp/sampleRate 构建配置快照（含系数重算） */
  private fun buildSettings(
    enabled: Boolean,
    gainsDb: FloatArray,
    preampDb: Float,
    sampleRate: Int,
  ): EqSettings {
    val clampedGains =
      FloatArray(BAND_COUNT) { i ->
        val raw = if (gainsDb.size > i) gainsDb[i] else 0f
        val v = if (raw.isFinite()) raw else 0f
        v.coerceIn(-BAND_GAIN_LIMIT_DB, BAND_GAIN_LIMIT_DB)
      }
    val clampedPreamp = if (preampDb.isFinite()) preampDb.coerceIn(-PREAMP_LIMIT_DB, PREAMP_LIMIT_DB) else 0f
    val coeffs =
      Array(BAND_COUNT) { i ->
        buildBiquad(EQ_FREQUENCIES[i], sampleRate.toFloat(), EQ_Q, clampedGains[i])
      }
    return EqSettings(enabled, clampedPreamp, sampleRate, clampedGains, coeffs)
  }

  /** RBJ Audio EQ Cookbook peaking 公式，gain_db = 0 时退化为直通 */
  private fun buildBiquad(
    freq: Float,
    sampleRate: Float,
    q: Float,
    gainDb: Float,
  ): BiquadCoeffs {
    if (abs(gainDb) < 1e-3f) {
      return BiquadCoeffs(1f, 0f, 0f, 0f, 0f)
    }
    val amp = 10.0.pow(gainDb / 40.0).toFloat()
    val omega = (2.0 * Math.PI * freq / sampleRate).toFloat()
    val sinOmega = sin(omega)
    val cosOmega = cos(omega)
    val alpha = sinOmega / (2f * q)

    val b0 = 1f + alpha * amp
    val b1 = -2f * cosOmega
    val b2 = 1f - alpha * amp
    val a0 = 1f + alpha / amp
    val a1 = -2f * cosOmega
    val a2 = 1f - alpha / amp

    val invA0 = 1f / a0
    return BiquadCoeffs(b0 * invA0, b1 * invA0, b2 * invA0, a1 * invA0, a2 * invA0)
  }

  /** 启用/禁用 EQ。禁用时透传 PCM，不应用滤波。 */
  fun setEnabled(enabled: Boolean) {
    val current = settings
    if (current.enabled == enabled) return
    settings = EqSettings(enabled, current.preampDb, current.sampleRate, current.bandGainsDb, current.coeffs)
    pendingStateReset = true
  }

  /** 更新 10 频段增益（dB），长度不足 10 时按 0dB 补齐，非有限值按 0dB 处理 */
  fun setBands(gainsDb: FloatArray) {
    val current = settings
    settings = buildSettings(current.enabled, gainsDb, current.preampDb, current.sampleRate)
    pendingStateReset = true
  }

  /** 更新前级增益（dB），非有限值按 0dB 处理 */
  fun setPreamp(preampDb: Float) {
    val current = settings
    settings = buildSettings(current.enabled, current.bandGainsDb, preampDb, current.sampleRate)
    pendingStateReset = true
  }

  @Throws(AudioProcessor.UnhandledAudioFormatException::class)
  override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
    // 仅接受 16bit PCM，其他 encoding 交上游 ToInt16PcmAudioProcessor 转换
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
      throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }
    // 输出设备切换导致采样率变化时重算系数，对齐桌面端 set_sample_rate
    val current = settings
    if (current.sampleRate != inputAudioFormat.sampleRate) {
      settings = buildSettings(current.enabled, current.bandGainsDb, current.preampDb, inputAudioFormat.sampleRate)
      pendingStateReset = true
    }
    return inputAudioFormat
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val bytesRemaining = inputBuffer.remaining()
    if (bytesRemaining == 0) return

    val cfg = settings
    val output = replaceOutputBuffer(bytesRemaining)
    // output 默认字节序为 big-endian，putShort 需设为 nativeOrder 与下游 AudioTrack 对齐
    // 否则高低字节互换会产生刺耳电流声
    output.order(ByteOrder.nativeOrder())

    // EQ 关闭或非立体声：直接透传（EQ 仅支持立体声，对齐桌面端 EQ_CHANNEL_COUNT）
    if (!cfg.enabled || inputAudioFormat.channelCount != CHANNEL_COUNT) {
      output.put(inputBuffer)
      output.flip()
      return
    }

    val view = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
    val preamp = cfg.preampLinear
    val coeffs = cfg.coeffs
    val channelCount = inputAudioFormat.channelCount
    val bytesPerFrame = channelCount * 2 // 16bit = 2 bytes/channel
    val frames = bytesRemaining / bytesPerFrame

    if (pendingStateReset) {
      pendingStateReset = false
      resetFilterStates()
    }

    // 处理交错立体声 PCM（L R L R ...），preamp + 10 级级联 biquad
    for (frame in 0 until frames) {
      val byteOffset = frame * bytesPerFrame
      val leftInt = view.getShort(view.position() + byteOffset).toInt()
      val rightInt = view.getShort(view.position() + byteOffset + 2).toInt()

      // 16bit PCM → float [-1, 1)，应用 preamp
      var left = (leftInt / 32768f) * preamp
      var right = (rightInt / 32768f) * preamp

      // 10 级级联 biquad（Direct Form II Transposed）
      for (band in 0 until BAND_COUNT) {
        val c = coeffs[band]
        // 左声道
        val zLeft = zStates[0][band]
        val yLeft = c.b0 * left + zLeft[0]
        zLeft[0] = c.b1 * left - c.a1 * yLeft + zLeft[1]
        zLeft[1] = c.b2 * left - c.a2 * yLeft
        left = yLeft
        // 右声道
        val zRight = zStates[1][band]
        val yRight = c.b0 * right + zRight[0]
        zRight[0] = c.b1 * right - c.a1 * yRight + zRight[1]
        zRight[1] = c.b2 * right - c.a2 * yRight
        right = yRight
      }

      // 软削波 + float → 16bit PCM
      // 桌面端 Rust 输出浮点无 clamp，由系统音频管线处理；Android 端转 16bit 需限制范围
      // 硬 clamp 高增益时产生方波形失真（爆音），softClip 超阈值平滑压缩避免刺耳
      val outLeft = (softClip(left) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
      val outRight = (softClip(right) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
      output.putShort(outLeft)
      output.putShort(outRight)
    }

    // EQ 分支用 absolute getShort 读取，未移动 inputBuffer.position，需手动消费
    // 对齐 BaseAudioProcessor 契约：queueInput 必须消费输入 buffer
    inputBuffer.position(inputBuffer.limit())
    output.flip()
  }

  override fun onFlush() {
    // seek/flush 重置滤波器状态，避免上一段尾音残留导致瞬态不稳定，对齐桌面端 reset_state
    resetFilterStates()
  }

  override fun onReset() {
    resetFilterStates()
  }

  private fun resetFilterStates() {
    for (ch in zStates) {
      for (z in ch) {
        z[0] = 0f
        z[1] = 0f
      }
    }
  }

  /**
   * 软削波：信号在阈值内线性通过，超过时用 tanh 平滑压缩
   * 避免硬 clamp 在高增益时产生方波形失真（刺耳爆音）
   */
  private fun softClip(x: Float): Float {
    val threshold = 0.9f
    val absX = abs(x)
    if (absX <= threshold) return x
    val sign = if (x >= 0f) 1f else -1f
    val over = absX - threshold
    val range = 1f - threshold
    return sign * (threshold + range * tanh(over / range))
  }
}
