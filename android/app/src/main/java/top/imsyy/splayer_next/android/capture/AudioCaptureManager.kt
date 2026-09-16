package top.imsyy.splayer_next.android.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** 采集事件（镜像桌面 audio-capture 的 JsCaptureEvent） */
sealed class CaptureEvent {
  /** 音量（RMS，0-1） */
  data class Level(
    val level: Float,
  ) : CaptureEvent()

  /** 采集完成；pcmBase64 为 8 kHz 单声道 Int16 小端 PCM，取消时为 null */
  data class Done(
    val pcmBase64: String?,
  ) : CaptureEvent()

  /** 采集失败（结构化错误码 + 可读信息） */
  data class Error(
    val code: String,
    val message: String,
  ) : CaptureEvent()
}

/**
 * Android 音频采集：麦克风（AudioRecord MIC）或系统声音回采（AudioPlaybackCapture + MediaProjection），
 * 统一降采样到 8 kHz 单声道 Int16 PCM，按 100ms 间隔推送 RMS 音量，完成后回传 base64。
 * 对齐桌面 Rust audio-capture 的 CaptureSink 设计。
 */
class AudioCaptureManager {
  companion object {
    /** AFP 需要的目标采样率 */
    private const val TARGET_SAMPLE_RATE = 8000

    /** 音量推送间隔（毫秒） */
    private const val LEVEL_INTERVAL_MS = 100

    /** 墙钟兜底：超过目标时长仍未采满则停止并补静音，避免设备异常时无限阻塞 */
    private const val WALL_CLOCK_SLACK_MS = 3000

    /** Int16 采样的字节掩码与高低位偏移 */
    private const val BYTE_MASK = 0xFF
    private const val BYTE_SHIFT = 8

    /** 候选采集采样率，按序取首个设备支持者 */
    private val CANDIDATE_RATES = intArrayOf(44100, 48000, 16000)
  }

  private val cancelled = AtomicBoolean(false)

  @Volatile
  private var record: AudioRecord? = null

  /**
   * 麦克风采集（仅需 RECORD_AUDIO）
   * @param durationMs 目标采集时长
   * @param onEvent 事件回调（在采集线程触发）
   */
  fun startMicrophone(
    durationMs: Int,
    onEvent: (CaptureEvent) -> Unit,
  ) {
    begin(durationMs, onEvent) { sampleRate, bufferBytes -> buildMicRecord(sampleRate, bufferBytes) }
  }

  /**
   * 系统声音回采（需已授权的 MediaProjection，且调用方运行于 mediaProjection 前台服务）
   * @param projection 已获取的 MediaProjection
   * @param durationMs 目标采集时长
   * @param onEvent 事件回调（在采集线程触发）
   */
  fun startSystem(
    projection: MediaProjection,
    durationMs: Int,
    onEvent: (CaptureEvent) -> Unit,
  ) {
    begin(durationMs, onEvent) { sampleRate, bufferBytes ->
      buildSystemRecord(projection, sampleRate, bufferBytes)
    }
  }

  /** 取消进行中的采集：置标记并停止 AudioRecord，令读循环退出 */
  fun cancel() {
    cancelled.set(true)
    val current = record ?: return
    try {
      if (current.recordingState == AudioRecord.RECORDSTATE_RECORDING) current.stop()
    } catch (ignored: IllegalStateException) {
      // 停止与读取的竞态可忽略
    }
  }

  private fun begin(
    durationMs: Int,
    onEvent: (CaptureEvent) -> Unit,
    buildRecord: (Int, Int) -> AudioRecord?,
  ) {
    cancel()
    cancelled.set(false)
    Thread({ runCapture(durationMs, onEvent, buildRecord) }, "audio-capture").start()
  }

  @SuppressLint("MissingPermission")
  private fun runCapture(
    durationMs: Int,
    onEvent: (CaptureEvent) -> Unit,
    buildRecord: (Int, Int) -> AudioRecord?,
  ) {
    var sampleRate = 0
    var minBuffer = 0
    for (rate in CANDIDATE_RATES) {
      val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
      if (min > 0) {
        sampleRate = rate
        minBuffer = min
        break
      }
    }
    if (minBuffer <= 0) {
      onEvent(CaptureEvent.Error("no-device", "无可用采集设备"))
      return
    }
    val audioRecord = buildRecord(sampleRate, minBuffer * 2)
    if (audioRecord == null) {
      onEvent(CaptureEvent.Error("capture-failed", "采集设备初始化失败"))
      return
    }
    record = audioRecord
    try {
      audioRecord.startRecording()
      val pcm = collect(audioRecord, sampleRate, durationMs, onEvent)
      when {
        cancelled.get() -> onEvent(CaptureEvent.Done(null))
        pcm == null -> onEvent(CaptureEvent.Error("capture-failed", "音频读取失败"))
        else -> onEvent(CaptureEvent.Done(encodePcm(pcm, sampleRate)))
      }
    } catch (error: Exception) {
      onEvent(CaptureEvent.Error("capture-failed", error.message ?: "采集失败"))
    } finally {
      try {
        if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop()
      } catch (ignored: IllegalStateException) {
        // 停止竞态可忽略
      }
      audioRecord.release()
      record = null
    }
  }

  @SuppressLint("MissingPermission")
  private fun buildMicRecord(
    sampleRate: Int,
    bufferBytes: Int,
  ): AudioRecord? =
    runCatching {
      AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferBytes,
      )
    }.getOrNull()
      ?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }

  @SuppressLint("MissingPermission")
  private fun buildSystemRecord(
    projection: MediaProjection,
    sampleRate: Int,
    bufferBytes: Int,
  ): AudioRecord? =
    runCatching {
      val format =
        AudioFormat
          .Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(sampleRate)
          .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
          .build()
      val config =
        AudioPlaybackCaptureConfiguration
          .Builder(projection)
          .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
          .addMatchingUsage(AudioAttributes.USAGE_GAME)
          .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
          .build()
      AudioRecord
        .Builder()
        .setAudioPlaybackCaptureConfig(config)
        .setAudioFormat(format)
        .setBufferSizeInBytes(bufferBytes)
        .build()
    }.getOrNull()
      ?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }

  /**
   * 阻塞读取直到采满目标时长、被取消或触发墙钟兜底；未采满的尾部保持静音（0）
   * @returns 目标长度的单声道 Float 样本；硬读取错误返回 null
   */
  private fun collect(
    audioRecord: AudioRecord,
    sampleRate: Int,
    durationMs: Int,
    onEvent: (CaptureEvent) -> Unit,
  ): FloatArray? {
    val targetSamples = sampleRate * durationMs / 1000
    val deadline = System.nanoTime() + (durationMs + WALL_CLOCK_SLACK_MS).toLong() * 1_000_000L
    val mono = FloatArray(targetSamples)
    val buffer = ShortArray(sampleRate / 10)
    val level = LevelEmitter(sampleRate * LEVEL_INTERVAL_MS / 1000) { onEvent(CaptureEvent.Level(it)) }
    var collected = 0
    while (collected < targetSamples && !cancelled.get()) {
      if (System.nanoTime() > deadline) break
      val read = audioRecord.read(buffer, 0, buffer.size)
      if (read < 0) return null
      if (read == 0) continue
      for (i in 0 until read) {
        if (collected >= targetSamples) break
        val sample = buffer[i] / 32768f
        mono[collected++] = sample
        level.push(sample)
      }
    }
    return mono
  }

  /** 设备原始采样率降采样到 8 kHz 后转 Int16 小端并 base64 编码 */
  private fun encodePcm(
    mono: FloatArray,
    sampleRate: Int,
  ): String {
    val down = downsample(mono, sampleRate, TARGET_SAMPLE_RATE)
    val bytes = ByteArray(down.size * 2)
    for (i in down.indices) {
      val sample = (down[i] * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
      bytes[i * 2] = (sample and BYTE_MASK).toByte()
      bytes[i * 2 + 1] = ((sample shr BYTE_SHIFT) and BYTE_MASK).toByte()
    }
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
  }

  /** 单声道降采样：按输入/输出比例做窗口平均（低通 + 抽取），对齐桌面 downsample_mono */
  private fun downsample(
    input: FloatArray,
    inRate: Int,
    outRate: Int,
  ): FloatArray {
    if (input.isEmpty() || inRate <= outRate) return input
    val ratio = inRate.toDouble() / outRate.toDouble()
    val outLen = floor(input.size / ratio).toInt()
    val out = FloatArray(outLen)
    for (i in 0 until outLen) {
      val start = (i * ratio).toInt()
      val end = ceil((i + 1) * ratio).toInt().coerceAtMost(input.size)
      var sum = 0f
      var count = 0
      for (j in start until end) {
        sum += input[j]
        count++
      }
      out[i] = if (count > 0) sum / count else 0f
    }
    return out
  }

  /** 按固定样本间隔累计 RMS 并推送音量，隔离读循环的嵌套深度 */
  private class LevelEmitter(
    private val interval: Int,
    private val onLevel: (Float) -> Unit,
  ) {
    private var energy = 0.0
    private var count = 0
    private var pending = 0

    fun push(sample: Float) {
      energy += (sample * sample).toDouble()
      count++
      if (++pending < interval) return
      val level = if (count == 0) 0f else sqrt(energy / count).toFloat()
      energy = 0.0
      count = 0
      pending = 0
      onLevel(level)
    }
  }
}
