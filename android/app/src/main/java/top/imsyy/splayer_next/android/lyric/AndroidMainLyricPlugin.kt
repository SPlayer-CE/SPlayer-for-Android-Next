package top.imsyy.splayer_next.android.lyric

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import top.imsyy.splayer_next.android.playback.PlaybackManager

@CapacitorPlugin(name = "AndroidMainLyric")
class AndroidMainLyricPlugin : Plugin() {
  private val executor: ExecutorService = Executors.newSingleThreadExecutor()
  private var overlayView: MainPlayerLyricOverlayView? = null
  private var overlayActivity: Activity? = null

  override fun handleOnDestroy() {
    executor.shutdownNow()
    val currentActivity = activity
    if (currentActivity != null) {
      currentActivity.runOnUiThread {
        detachOverlayView()
      }
    } else {
      // activity 已销毁时 runOnUiThread 是 no-op，直接 detach，
      // 避免单例 PlaybackManager 持有已销毁 view 的时钟监听器造成泄漏
      detachOverlayView()
    }
  }

  @PluginMethod
  fun show(call: PluginCall) {
    runOnMainThread(call) {
      ensureOverlayView().setRendererVisible(true)
      call.resolve()
    }
  }

  @PluginMethod
  fun hide(call: PluginCall) {
    runOnMainThread(call) {
      overlayView?.setRendererVisible(false)
      call.resolve()
    }
  }

  @PluginMethod
  fun clear(call: PluginCall) {
    runOnMainThread(call) {
      overlayView?.clearLyrics()
      overlayView?.setRendererVisible(false)
      call.resolve()
    }
  }

  @PluginMethod
  fun setTouchEnabled(call: PluginCall) {
    val enabled = call.getBoolean("enabled", true) ?: true
    runOnMainThread(call) {
      overlayView?.setTouchEnabled(enabled)
      call.resolve()
    }
  }

  @PluginMethod
  fun setViewport(call: PluginCall) {
    val left = call.getDouble("left", 0.0)?.roundToInt() ?: 0
    val top = call.getDouble("top", 0.0)?.roundToInt() ?: 0
    val width = call.getDouble("width", 0.0)?.roundToInt() ?: 0
    val height = call.getDouble("height", 0.0)?.roundToInt() ?: 0
    val bottomExclusionHeight = call.getDouble("bottomExclusionHeight", 0.0)?.roundToInt() ?: 0
    val cssWidth = call.getFloat("cssWidth")
    runOnMainThread(call) {
      val view = ensureOverlayView()
      val params =
        (view.layoutParams as? FrameLayout.LayoutParams)
          ?: FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
      params.width = FrameLayout.LayoutParams.MATCH_PARENT
      params.height = FrameLayout.LayoutParams.MATCH_PARENT
      params.leftMargin = 0
      params.topMargin = 0
      view.layoutParams = params
      (view.parent as? ViewGroup)?.bringChildToFront(view)
      view.bringToFront()
      view.setViewport(left, top, width, height, bottomExclusionHeight, cssWidth)
      call.resolve()
    }
  }

  @PluginMethod
  fun setLyrics(call: PluginCall) {
    val linesJson = call.getString("linesJson", "[]") ?: "[]"
    val currentActivity = activity
    if (currentActivity == null) {
      call.reject("Activity unavailable")
      return
    }
    executor.execute {
      val lines = MainPlayerLyricOverlayView.parseLyricLines(linesJson)
      currentActivity.runOnUiThread {
        try {
          ensureOverlayView().setLyrics(lines)
          call.resolve()
        } catch (error: Exception) {
          call.reject(error.message, error)
        }
      }
    }
  }

  @PluginMethod
  fun setConfig(call: PluginCall) {
    val fontSizePx = call.getFloat("fontSizePx", 34f) ?: 34f
    val fontWeight = call.getInt("fontWeight", 700) ?: 700
    val fontFamily = call.getString("fontFamily")
    val fontFamilyChinese = call.getString("fontFamilyChinese")
    val fontFamilyJapanese = call.getString("fontFamilyJapanese")
    val fontFamilyKorean = call.getString("fontFamilyKorean")
    val fontFamilyLatin = call.getString("fontFamilyLatin")
    val textColor = MainPlayerLyricOverlayView.parseColor(call.getString("textColor")) ?: 0xFFFFFFFF.toInt()
    val inactiveAlpha = call.getFloat("inactiveAlpha", 0.2f) ?: 0.2f
    val alignPosition = call.getFloat("alignPosition", 0.35f) ?: 0.35f
    val wordFadeWidth = call.getFloat("wordFadeWidth", 0.5f) ?: 0.5f
    val hidePassedLines = call.getBoolean("hidePassedLines", false) ?: false
    val enableBlur = call.getBoolean("enableBlur", false) ?: false
    val enableWordHighlight = call.getBoolean("enableWordHighlight", true) ?: true
    val enableFloatAnimation = call.getBoolean("enableFloatAnimation", false) ?: false
    val enableEmphasizeEffect = call.getBoolean("enableEmphasizeEffect", false) ?: false
    val enableWordBlockSegmentation =
      call.getBoolean("enableWordBlockSegmentation", false) ?: false
    val showTranslation = call.getBoolean("showTranslation", true) ?: true
    val showRomanization = call.getBoolean("showRomanization", true) ?: true
    val springMass = call.getFloat("springMass")
    val springDamping = call.getFloat("springDamping")
    val springStiffness = call.getFloat("springStiffness")
    val alwaysPostpositionBackground = call.getBoolean("alwaysPostpositionBackground", false) ?: false

    runOnMainThread(call) {
      ensureOverlayView().setConfig(
        fontSizePx = fontSizePx,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        fontFamilyChinese = fontFamilyChinese,
        fontFamilyJapanese = fontFamilyJapanese,
        fontFamilyKorean = fontFamilyKorean,
        fontFamilyLatin = fontFamilyLatin,
        textColor = textColor,
        inactiveAlpha = inactiveAlpha,
        alignPosition = alignPosition,
        wordFadeWidth = wordFadeWidth,
        hidePassedLines = hidePassedLines,
        enableBlur = enableBlur,
        enableWordHighlight = enableWordHighlight,
        enableFloatAnimation = enableFloatAnimation,
        enableEmphasizeEffect = enableEmphasizeEffect,
        enableWordBlockSegmentation = enableWordBlockSegmentation,
        showTranslation = showTranslation,
        showRomanization = showRomanization,
        springMass = springMass,
        springDamping = springDamping,
        springStiffness = springStiffness,
        alwaysPostpositionBackground = alwaysPostpositionBackground,
      )
      call.resolve()
    }
  }

  @PluginMethod
  fun updateProgress(call: PluginCall) {
    val timeMs = call.getDouble("timeMs", 0.0)?.toLong() ?: 0L
    val playing = call.getBoolean("playing", false) ?: false
    runOnMainThread(call) {
      overlayView?.updateProgress(timeMs, playing)
      call.resolve()
    }
  }

  @PluginMethod
  fun setTimeOffset(call: PluginCall) {
    val timeOffsetMs = call.getDouble("timeOffsetMs", 0.0)?.toLong() ?: 0L
    runOnMainThread(call) {
      overlayView?.setTimeOffset(timeOffsetMs)
      call.resolve()
    }
  }

  @PluginMethod
  fun freeze(call: PluginCall) {
    runOnMainThread(call) {
      overlayView?.freezeRenderer()
      call.resolve()
    }
  }

  @PluginMethod
  fun resume(call: PluginCall) {
    runOnMainThread(call) {
      overlayView?.resumeRenderer()
      call.resolve()
    }
  }

  @PluginMethod
  fun refreshLayout(call: PluginCall) {
    runOnMainThread(call) {
      overlayView?.refreshLayoutState()
      call.resolve()
    }
  }

  @PluginMethod
  fun suppressTapSeek(call: PluginCall) {
    runOnMainThread(call) {
      overlayView?.suppressTapSeek()
      call.resolve()
    }
  }

  private fun ensureOverlayView(): MainPlayerLyricOverlayView {
    val currentActivity = activity ?: throw IllegalStateException("Activity unavailable")
    overlayView?.let { view ->
      if (overlayActivity === currentActivity) return view
      detachOverlayView()
    }
    val root =
      currentActivity.findViewById<ViewGroup>(android.R.id.content)
        ?: throw IllegalStateException("Content root unavailable")
    val playbackManager = PlaybackManager.getInstance(currentActivity)
    return MainPlayerLyricOverlayView(
      context = currentActivity,
      playbackPositionProvider = playbackManager::getLyricPositionMs,
      playbackRateProvider = playbackManager::getLyricPlaybackRate,
      onSeekRequested = { timeMs ->
        val payload = JSObject()
        payload.put("timeMs", timeMs)
        notifyListeners("seek", payload, false)
      },
    ).also { view ->
      root.addView(view, FrameLayout.LayoutParams(0, 0))
      overlayView = view
      overlayActivity = currentActivity
      playbackManager.setMainLyricClockListener(view::notifyPlaybackClockChanged)
    }
  }

  private fun detachOverlayView() {
    val view = overlayView ?: return
    overlayActivity?.let { currentActivity ->
      PlaybackManager.getInstance(currentActivity).setMainLyricClockListener(null)
    }
    (view.parent as? ViewGroup)?.removeView(view)
    overlayView = null
    overlayActivity = null
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
}
