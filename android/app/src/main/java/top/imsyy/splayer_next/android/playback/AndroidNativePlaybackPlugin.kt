package top.imsyy.splayer_next.android.playback

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import top.imsyy.splayer_next.android.MainActivity
import top.imsyy.splayer_next.android.cache.AudioCacheProvider

@CapacitorPlugin(
  name = "AndroidNativePlayback",
  permissions = [
    Permission(
      alias = "notifications",
      strings = [Manifest.permission.POST_NOTIFICATIONS],
    ),
  ],
)
class AndroidNativePlaybackPlugin : Plugin() {
  private val mediaSessionManager: AndroidMediaSessionManager by lazy {
    AndroidMediaSessionManager(context)
  }

  override fun load() {
    PlaybackManager.getInstance(context).attachPlugin(this)
  }

  override fun handleOnPause() {
    PlaybackManager.getInstance(context).setWebViewVisible(false)
  }

  override fun handleOnResume() {
    PlaybackManager.getInstance(context).setWebViewVisible(true)
  }

  override fun handleOnDestroy() {
    PlaybackManager.getInstance(context).detachPlugin(this)
  }

  @PluginMethod
  fun load(call: PluginCall) {
    val url = call.getString("url", "") ?: ""
    // Capacitor 从 JS 传 number 时底层是 Double，getDouble 转 long
    val positionMs = call.getDouble("positionMs", 0.0)?.toLong() ?: 0L
    val autoPlay = call.getBoolean("autoPlay", false) ?: false
    resolveOnPlayback(call) {
      PlaybackManager.getInstance(context).load(url, positionMs, autoPlay)
    }
  }

  @PluginMethod
  fun play(call: PluginCall) {
    resolveOnPlayback(call) { PlaybackManager.getInstance(context).play() }
  }

  /** 切下一首（原生队列权威推进，前台 UI 入口）。 */
  @PluginMethod
  fun next(call: PluginCall) {
    resolveOnPlayback(call) {
      PlaybackManager.getInstance(context).handleNotificationAction(PlaybackConstants.ACTION_NEXT)
      JSObject()
    }
  }

  /** 切上一首（原生队列权威推进，前台 UI 入口）。 */
  @PluginMethod
  fun previous(call: PluginCall) {
    resolveOnPlayback(call) {
      PlaybackManager.getInstance(context).handleNotificationAction(PlaybackConstants.ACTION_PREVIOUS)
      JSObject()
    }
  }

  /** 播放指定索引的曲目（JS 点击任意歌曲的原生入口）。 */
  @PluginMethod
  fun playIndex(call: PluginCall) {
    val index = call.getInt("index", -1) ?: -1
    val positionMs = call.getDouble("positionMs", 0.0)?.toLong() ?: 0L
    resolveOnPlayback(call) {
      PlaybackManager.getInstance(context).playIndexAt(index, positionMs)
      JSObject()
    }
  }

  @PluginMethod
  fun pause(call: PluginCall) {
    resolveOnPlayback(call) { PlaybackManager.getInstance(context).pause() }
  }

  @PluginMethod
  fun stop(call: PluginCall) {
    resolveOnPlayback(call) { PlaybackManager.getInstance(context).stop() }
  }

  @PluginMethod
  fun cleanup(call: PluginCall) {
    resolveOnPlayback(call) { PlaybackManager.getInstance(context).cleanup() }
  }

  /** 关闭界面但保留进程，让嵌入式服务继续在后台运行。 */
  @PluginMethod
  fun moveTaskToBack(call: PluginCall) {
    val activity = activity
    runOnMainThread(call) {
      if (activity == null) {
        call.reject("Activity unavailable")
        return@runOnMainThread
      }
      activity.moveTaskToBack(true)
      call.resolve()
    }
  }

  /** 用户确认退出：停服务 + finishAndRemoveTask + System.exit */
  @PluginMethod
  fun shutdownApp(call: PluginCall) {
    val activity = activity
    runOnPlayback(call) {
      PlaybackManager.getInstance(context).shutdownAll()
      call.resolve()
      // 等桥消息送达 JS 后再退，给 100ms 余量
      Handler(Looper.getMainLooper()).postDelayed({
        if (activity != null) {
          try {
            activity.finishAndRemoveTask()
          } catch (ignored: Exception) {
          }
        }
        System.exit(0)
      }, 100L)
    }
  }

  @PluginMethod
  fun seek(call: PluginCall) {
    // Capacitor 从 JS 传 number 时底层是 Double
    val positionMs = call.getDouble("positionMs", 0.0)?.toLong() ?: 0L
    resolveOnPlayback(call) { PlaybackManager.getInstance(context).seek(positionMs) }
  }

  @PluginMethod
  fun setVolume(call: PluginCall) {
    val volume = call.getFloat("volume", 1f) ?: 1f
    runOnPlayback(call) {
      PlaybackManager.getInstance(context).setVolume(volume)
      call.resolve()
    }
  }

  @PluginMethod
  fun setSpeed(call: PluginCall) {
    val speed = call.getFloat("speed", 1f) ?: 1f
    runOnPlayback(call) {
      PlaybackManager.getInstance(context).setRate(speed)
      call.resolve()
    }
  }

  @PluginMethod
  fun updateMetadata(call: PluginCall) {
    runOnPlayback(call) {
      mediaSessionManager.updateMetadata(call.data)
      call.resolve()
    }
  }

  @PluginMethod
  fun updateQueueContext(call: PluginCall) {
    runOnPlayback(call) {
      mediaSessionManager.updateQueueContext(call.data)
      call.resolve()
    }
  }

  @PluginMethod
  fun updateNotificationPrefs(call: PluginCall) {
    runOnPlayback(call) {
      mediaSessionManager.updateNotificationPrefs(call.data)
      call.resolve()
    }
  }

  @PluginMethod
  fun setAllowMixWithOthers(call: PluginCall) {
    runOnPlayback(call) {
      mediaSessionManager.setAllowMixWithOthers(call.data)
      call.resolve()
    }
  }

  @PluginMethod
  fun setShowStatusBar(call: PluginCall) {
    val show = call.getBoolean("show", false) ?: false
    runOnMainThread(call) {
      val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
      prefs.edit().putBoolean(MainActivity.PREF_SHOW_STATUS_BAR, show).apply()
      val activity = activity
      if (activity is MainActivity) {
        activity.applyImmersiveMode()
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun setHideNavigationBar(call: PluginCall) {
    val hide = call.getBoolean("hide", false) ?: false
    runOnMainThread(call) {
      val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
      prefs.edit().putBoolean(MainActivity.PREF_HIDE_NAVIGATION_BAR, hide).apply()
      val activity = activity
      if (activity == null) {
        call.reject("Activity unavailable")
        return@runOnMainThread
      }
      if (activity is MainActivity) {
        activity.applyImmersiveMode()
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun setImmersiveLandscape(call: PluginCall) {
    val active = call.getBoolean("active", false) ?: false
    runOnMainThread(call) {
      val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
      prefs.edit().putBoolean(MainActivity.PREF_IMMERSIVE_LANDSCAPE, active).apply()
      val activity = activity
      if (activity == null) {
        call.reject("Activity unavailable")
        return@runOnMainThread
      }
      activity.requestedOrientation =
        if (active) {
          ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
          ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
      if (activity is MainActivity) {
        activity.applyImmersiveMode()
      }
      call.resolve()
    }
  }

  @PluginMethod
  fun syncApiContext(call: PluginCall) {
    runOnPlayback(call) {
      mediaSessionManager.syncApiContext(call.data)
      call.resolve()
    }
  }

  @PluginMethod
  fun getStatus(call: PluginCall) {
    resolveOnPlayback(call) { PlaybackManager.getInstance(context).buildState() }
  }

  @PluginMethod
  fun syncRemoteState(call: PluginCall) {
    runOnPlayback(call) {
      mediaSessionManager.syncRemoteState(call.data)
      call.resolve()
    }
  }

  @PluginMethod
  fun requestNotificationPermission(call: PluginCall) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      call.resolve(permissionResult(true))
      return
    }

    if (getPermissionState("notifications") == PermissionState.GRANTED) {
      call.resolve(permissionResult(true))
      return
    }

    requestPermissionForAlias("notifications", call, "onNotificationPermissionResult")
  }

  @PermissionCallback
  @Suppress("UnusedPrivateMember")
  private fun onNotificationPermissionResult(call: PluginCall?) {
    if (call == null) return
    call.resolve(permissionResult(getPermissionState("notifications") == PermissionState.GRANTED))
  }

  // ========== 灵动岛歌词相关 ==========

  @PluginMethod
  fun showDynamicIsland(call: PluginCall) {
    if (!Settings.canDrawOverlays(context)) {
      call.reject("OVERLAY_PERMISSION_DENIED")
      return
    }
    runOnPlayback(call) {
      PlaybackManager.getInstance(context).showDynamicIsland()
      val payload = JSObject()
      payload.put("open", true)
      emitEvent("dynamicIslandVisibilityChange", payload, false)
      call.resolve()
    }
  }

  @PluginMethod
  fun hideDynamicIsland(call: PluginCall) {
    runOnPlayback(call) {
      PlaybackManager.getInstance(context).hideDynamicIsland()
      val payload = JSObject()
      payload.put("open", false)
      emitEvent("dynamicIslandVisibilityChange", payload, false)
      call.resolve()
    }
  }

  @PluginMethod
  fun isDynamicIslandRunning(call: PluginCall) {
    val result = JSObject()
    result.put("running", PlaybackManager.getInstance(context).isDynamicIslandRunning())
    call.resolve(result)
  }

  @PluginMethod
  fun updateDynamicIslandData(call: PluginCall) {
    val lrcJson = call.getString("lrcData", "[]") ?: "[]"
    val yrcJson = call.getString("yrcData", "[]") ?: "[]"
    runOnPlayback(call) {
      PlaybackManager.getInstance(context).updateDynamicIslandData(lrcJson, yrcJson)
      call.resolve()
    }
  }

  @PluginMethod
  fun updateDynamicIslandProgress(call: PluginCall) {
    val timeMs = call.getDouble("timeMs", 0.0)?.toLong() ?: 0L
    val playing = call.getBoolean("playing", false) ?: false
    runOnPlayback(call) {
      PlaybackManager.getInstance(context).updateDynamicIslandProgress(timeMs, playing)
      call.resolve()
    }
  }

  @PluginMethod
  fun updateDynamicIslandSongInfo(call: PluginCall) {
    val name = call.getString("name", "") ?: ""
    val artist = call.getString("artist", "") ?: ""
    runOnPlayback(call) {
      PlaybackManager.getInstance(context).updateDynamicIslandSongInfo(name, artist)
      call.resolve()
    }
  }

  @PluginMethod
  fun updateDynamicIslandConfig(call: PluginCall) {
    val data = call.getObject("config")
    val payload = data ?: call.data
    runOnPlayback(call) {
      PlaybackManager.getInstance(context).updateDynamicIslandConfig(payload)
      call.resolve()
    }
  }

  @PluginMethod
  fun checkOverlayPermission(call: PluginCall) {
    val result = JSObject()
    result.put("granted", Settings.canDrawOverlays(context))
    call.resolve(result)
  }

  @PluginMethod
  fun prefetchAudio(call: PluginCall) {
    val url = call.getString("url", "") ?: ""
    if (url.isEmpty()) {
      call.resolve()
      return
    }
    AudioCacheProvider.prefetchUrl(context, url)
    call.resolve()
  }

  @PluginMethod
  fun isPromotedAudioReady(call: PluginCall) {
    val url = call.getString("url", "") ?: ""
    val result = JSObject()
    result.put("ready", AudioCacheProvider.isPromotedAudioReady(context, url))
    call.resolve(result)
  }

  @PluginMethod
  fun setFftEnabled(call: PluginCall) {
    val enabled = call.getBoolean("enabled", false) ?: false
    runOnPlayback(call) {
      val ok = PlaybackManager.getInstance(context).enableVisualizer(enabled)
      call.resolve(permissionResult(ok))
    }
  }

  @PluginMethod
  fun setSpectrumAlgorithm(call: PluginCall) {
    val mode = call.getString("mode", "pc") ?: "pc"
    runOnPlayback(call) {
      val ok = PlaybackManager.getInstance(context).setSpectrumAlgorithm(mode)
      call.resolve(permissionResult(ok))
    }
  }

  @PluginMethod
  fun setEqualizerEnabled(call: PluginCall) {
    val enabled = call.getBoolean("enabled", false) ?: false
    runOnPlayback(call) {
      PlaybackManager.getInstance(context).setEqualizerEnabled(enabled)
      call.resolve()
    }
  }

  @PluginMethod
  fun setEqualizerBands(call: PluginCall) {
    val arr = call.getArray("gainsDb", JSArray()) ?: JSArray()
    val gains = FloatArray(10) { 0f }
    val len = minOf(arr.length(), 10)
    for (i in 0 until len) {
      val value = arr.optDouble(i, 0.0)
      gains[i] = if (value.isFinite()) value.toFloat() else 0f
    }
    runOnPlayback(call) {
      PlaybackManager.getInstance(context).setEqualizerBands(gains)
      call.resolve()
    }
  }

  @PluginMethod
  fun setPreampGain(call: PluginCall) {
    val preampDb = call.getDouble("preampDb", 0.0)?.toFloat() ?: 0f
    runOnPlayback(call) {
      PlaybackManager.getInstance(context).setEqualizerPreamp(preampDb)
      call.resolve()
    }
  }

  @PluginMethod
  fun requestOverlayPermission(call: PluginCall) {
    if (Settings.canDrawOverlays(context)) {
      call.resolve(permissionResult(true))
      return
    }
    val intent =
      Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:" + context.packageName),
      )
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    call.resolve(permissionResult(false))
  }

  fun emitEvent(
    eventName: String,
    payload: JSObject,
    retainUntilConsumed: Boolean,
  ) {
    notifyListeners(eventName, payload, retainUntilConsumed)
  }

  /** 引擎方法派发到播放线程（与 ExoPlayer/PreloadManager 的 application looper 一致）。 */
  private fun resolveOnPlayback(
    call: PluginCall,
    action: () -> JSObject,
  ) {
    PlaybackManager.getInstance(context).runOnPlaybackThread {
      try {
        call.resolve(action())
      } catch (error: Exception) {
        call.reject(error.message, error)
      }
    }
  }

  private fun runOnPlayback(
    call: PluginCall,
    action: () -> Unit,
  ) {
    PlaybackManager.getInstance(context).runOnPlaybackThread {
      try {
        action()
      } catch (error: Exception) {
        call.reject(error.message, error)
      }
    }
  }

  private fun runOnMainThread(
    call: PluginCall,
    action: () -> Unit,
  ) {
    val currentActivity = activity
    if (currentActivity == null) {
      call.reject("Activity unavailable")
      return
    }

    currentActivity.runOnUiThread {
      try {
        action()
      } catch (error: Exception) {
        call.reject(error.message, error)
      }
    }
  }

  private fun permissionResult(granted: Boolean): JSObject {
    val result = JSObject()
    result.put("granted", granted)
    return result
  }
}
