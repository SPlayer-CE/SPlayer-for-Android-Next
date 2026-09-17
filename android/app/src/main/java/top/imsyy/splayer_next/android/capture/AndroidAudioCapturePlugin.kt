package top.imsyy.splayer_next.android.capture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.result.ActivityResult
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

/**
 * 听歌识曲音频采集插件：麦克风经插件内 AudioCaptureManager 直接采集；
 * 系统声音回采先弹 MediaProjection 授权，再交由 MediaProjectionCaptureService 前台服务采集。
 * 两路事件统一经 AudioCaptureEvents 回传，镜像桌面 audio-capture 的 JsCaptureEvent。
 */
@CapacitorPlugin(
  name = "AndroidAudioCapture",
  permissions = [
    Permission(
      alias = AndroidAudioCapturePlugin.ALIAS_MICROPHONE,
      strings = [Manifest.permission.RECORD_AUDIO],
    ),
  ],
)
class AndroidAudioCapturePlugin : Plugin() {
  companion object {
    const val ALIAS_MICROPHONE = "microphone"
    private const val EVENT_NAME = "captureEvent"
    private const val DEFAULT_DURATION_MS = 8000
    private const val SOURCE_SYSTEM = "system"
    private const val PROJECTION_CALLBACK = "onMediaProjectionResult"
  }

  private val captureManager by lazy { AudioCaptureManager() }

  override fun load() {
    AudioCaptureEvents.setListener { event -> emitEvent(event) }
  }

  override fun handleOnDestroy() {
    AudioCaptureEvents.setListener(null)
  }

  @PluginMethod
  fun startCapture(call: PluginCall) {
    if (getPermissionState(ALIAS_MICROPHONE) == PermissionState.GRANTED) {
      beginCapture(call)
      return
    }
    requestPermissionForAlias(ALIAS_MICROPHONE, call, "onCapturePermissionResult")
  }

  @PluginMethod
  fun cancelCapture(call: PluginCall) {
    captureManager.cancel()
    context.stopService(Intent(context, MediaProjectionCaptureService::class.java))
    call.resolve()
  }

  @PermissionCallback
  @Suppress("UnusedPrivateMember")
  private fun onCapturePermissionResult(call: PluginCall?) {
    if (call == null) return
    if (getPermissionState(ALIAS_MICROPHONE) != PermissionState.GRANTED) {
      AudioCaptureEvents.emit(CaptureEvent.Error("permission-denied", "缺少麦克风权限，请在系统设置中授权"))
      call.resolve()
      return
    }
    beginCapture(call)
  }

  private fun beginCapture(call: PluginCall) {
    val source = call.getString("source", "microphone") ?: "microphone"
    val durationMs = call.getInt("durationMs", DEFAULT_DURATION_MS) ?: DEFAULT_DURATION_MS
    if (source != SOURCE_SYSTEM) {
      captureManager.startMicrophone(durationMs) { event -> AudioCaptureEvents.emit(event) }
      call.resolve()
      return
    }
    val manager = context.getSystemService(MediaProjectionManager::class.java)
    if (manager == null) {
      AudioCaptureEvents.emit(CaptureEvent.Error("unsupported", "设备不支持系统声音采集"))
      call.resolve()
      return
    }
    // 系统声音需先取得用户授权，call 透传给 @ActivityCallback，授权后再起前台服务并 resolve
    startActivityForResult(call, manager.createScreenCaptureIntent(), PROJECTION_CALLBACK)
  }

  @ActivityCallback
  @Suppress("UnusedPrivateMember")
  private fun onMediaProjectionResult(
    call: PluginCall?,
    result: ActivityResult,
  ) {
    if (call == null) return
    val data = result.data
    if (result.resultCode != Activity.RESULT_OK || data == null) {
      AudioCaptureEvents.emit(CaptureEvent.Error("permission-denied", "用户取消了系统声音采集授权"))
      call.resolve()
      return
    }
    val durationMs = call.getInt("durationMs", DEFAULT_DURATION_MS) ?: DEFAULT_DURATION_MS
    val serviceIntent =
      Intent(context, MediaProjectionCaptureService::class.java).apply {
        putExtra(MediaProjectionCaptureService.EXTRA_RESULT_CODE, result.resultCode)
        putExtra(MediaProjectionCaptureService.EXTRA_DATA, data)
        putExtra(MediaProjectionCaptureService.EXTRA_DURATION_MS, durationMs)
      }
    ContextCompat.startForegroundService(context, serviceIntent)
    call.resolve()
  }

  /**
   * 派发采集事件到 JS。retainUntilConsumed 一律用 false：监听器在 startCapture 前已注册，
   * 会话内事件不会丢；若用 true，取消/关弹窗后原生派发的终止事件会驻留队列，
   * 被下次会话的新监听器立即消费，导致新识别被上次残留的 Done(null) 秒退。
   */
  private fun emitEvent(event: CaptureEvent) {
    val payload = JSObject()
    when (event) {
      is CaptureEvent.Level -> {
        payload.put("eventType", "level")
        payload.put("level", event.level.toDouble())
        notifyListeners(EVENT_NAME, payload)
      }
      is CaptureEvent.Done -> {
        payload.put("eventType", "done")
        event.pcmBase64?.let { payload.put("data", it) }
        notifyListeners(EVENT_NAME, payload)
      }
      is CaptureEvent.Error -> {
        payload.put("eventType", "error")
        payload.put("errorCode", event.code)
        payload.put("error", event.message)
        notifyListeners(EVENT_NAME, payload)
      }
    }
  }
}
