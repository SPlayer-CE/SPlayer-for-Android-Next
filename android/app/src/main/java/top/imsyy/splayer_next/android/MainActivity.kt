package top.imsyy.splayer_next.android

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.getcapacitor.BridgeActivity
import top.imsyy.splayer_next.android.cache.AndroidCachePlugin
import top.imsyy.splayer_next.android.cache.AndroidSongCachePlugin
import top.imsyy.splayer_next.android.cache.AudioPrefetchTtlIndex
import top.imsyy.splayer_next.android.download.AndroidDownloadPlugin
import top.imsyy.splayer_next.android.library.AndroidLibraryPlugin
import top.imsyy.splayer_next.android.lyric.AndroidLocalLyricPlugin
import top.imsyy.splayer_next.android.lyric.AndroidMainLyricPlugin
import top.imsyy.splayer_next.android.playback.AndroidNativePlaybackPlugin
import top.imsyy.splayer_next.android.server.AndroidLanSharePlugin
import top.imsyy.splayer_next.android.server.ApiServerPlugin
import top.imsyy.splayer_next.android.server.ExternalApiPlugin

class MainActivity : BridgeActivity() {
  companion object {
    // 供 plugin 跨类引用，避免双源硬编码
    const val PREF_SHOW_STATUS_BAR = "androidShowStatusBar"

    /** 横屏沉浸式：同时隐藏状态栏与全面屏导航手势条 */
    const val PREF_IMMERSIVE_LANDSCAPE = "immersiveLandscape"
    const val PREF_HIDE_NAVIGATION_BAR = "hideNavigationBar"

    /** 上次启动时的 versionCode，用于检测应用升级以触发 WebView 资源缓存清理 */
    const val PREF_LAST_VERSION_CODE = "lastVersionCode"

    /** 进程级 sweep 启动标记：Activity 重建（旋屏 / 配置变更）时不再重复 post Runnable。 */
    @Volatile
    private var sweepBootstrapped = false
  }

  override fun attachBaseContext(newBase: Context) {
    // 强制 fontScale = 1.0f，忽略系统字体大小设置，避免 WebView 文字跟随系统缩放
    val config = Configuration(newBase.resources.configuration)
    config.fontScale = 1.0f
    super.attachBaseContext(newBase.createConfigurationContext(config))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    registerPlugin(AndroidNativePlaybackPlugin::class.java)
    registerPlugin(AndroidClipboardPlugin::class.java)
    registerPlugin(AndroidAppIconPlugin::class.java)
    registerPlugin(AndroidLocalLyricPlugin::class.java)
    registerPlugin(AndroidMainLyricPlugin::class.java)
    registerPlugin(AndroidDownloadPlugin::class.java)
    registerPlugin(AndroidCachePlugin::class.java)
    registerPlugin(AndroidSongCachePlugin::class.java)
    registerPlugin(AndroidLanSharePlugin::class.java)
    registerPlugin(AndroidLibraryPlugin::class.java)
    registerPlugin(ApiServerPlugin::class.java)
    registerPlugin(ExternalApiPlugin::class.java)
    super.onCreate(savedInstanceState)
    NativeLogConsoleBridge.start(bridge?.webView)

    // 版本升级时清除 WebView 资源缓存，避免旧 JS/CSS/HTML 残留导致误判问题
    clearWebViewCacheOnUpgrade(savedInstanceState == null)

    // 冷启动重置沉浸式 pref，避免强杀残留隐藏导航栏；旋屏重建（savedInstanceState != null）保留
    if (savedInstanceState == null) {
      try {
        val prefs = getDefaultPrefs()
        if (prefs.getBoolean(PREF_IMMERSIVE_LANDSCAPE, false)) {
          prefs.edit().putBoolean(PREF_IMMERSIVE_LANDSCAPE, false).apply()
        }
      } catch (ignored: Exception) {
        // pref 读写失败不阻塞启动
      }
    }
    applyImmersiveMode()
    applyWebViewTextZoom()
    applyHighRefreshRate()

    // 音频预载 TTL：推迟到首帧后再初始化（构造期会同步读 SharedPreferences，冷启动加密磁盘可能耗百毫秒）。
    // 这里 post 2s，startPeriodicSweep 内部再 postDelayed 5s，首次 sweep 实际 ≈7s 后开始；
    // 之后每 30min 周期清理；TTL=50min；期间用户重复播放该 url 会通过 PlaybackManager.load 续期。
    if (!sweepBootstrapped) {
      sweepBootstrapped = true
      Handler(Looper.getMainLooper()).postDelayed(
        { AudioPrefetchTtlIndex.getInstance(applicationContext).startPeriodicSweep() },
        2_000L,
      )
    }
  }

  override fun onDestroy() {
    NativeLogConsoleBridge.stop()
    super.onDestroy()
  }

  public override fun onResume() {
    super.onResume()
    applyImmersiveMode()
    // 重新获取焦点后系统可能把刷新率降回默认，这里再请求一次高刷新率
    applyHighRefreshRate()
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      applyImmersiveMode()
      // 焦点变化后重新请求高刷新率，避免从后台返回时被系统降回 60Hz
      applyHighRefreshRate()
    }
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    // 系统字体缩放变化时强制保持 fontScale = 1.0f，避免 WebView 跟随系统字体缩放
    val config = Configuration(resources.configuration)
    if (config.fontScale != 1.0f) {
      config.fontScale = 1.0f
      @Suppress("DEPRECATION")
      resources.updateConfiguration(config, resources.displayMetrics)
    }
    // 重新锁定 WebView textZoom，防止部分 ROM 在配置变更后重置
    applyWebViewTextZoom()
  }

  private fun getDefaultPrefs(): SharedPreferences = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)

  private fun shouldShowStatusBar(): Boolean =
    try {
      val prefs = getDefaultPrefs()
      prefs.getBoolean(PREF_SHOW_STATUS_BAR, false)
    } catch (e: Exception) {
      false
    }

  private fun isImmersiveLandscape(): Boolean =
    try {
      val prefs = getDefaultPrefs()
      prefs.getBoolean(PREF_IMMERSIVE_LANDSCAPE, false)
    } catch (e: Exception) {
      false
    }

  private fun shouldHideNavigationBar(): Boolean =
    try {
      val prefs = getDefaultPrefs()
      prefs.getBoolean(PREF_HIDE_NAVIGATION_BAR, false)
    } catch (e: Exception) {
      false
    }

  fun applyImmersiveMode() {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    // 始终允许内容延伸到刘海/挖孔区域，避免摄像头区域出现黑条
    val lp = window.attributes
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    } else {
      lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    window.attributes = lp

    val decorView = window.decorView
    val controller = WindowCompat.getInsetsController(window, decorView)
    val immersiveLandscape = isImmersiveLandscape()
    val hideNavigationBar = immersiveLandscape || shouldHideNavigationBar()
    val showStatusBar = shouldShowStatusBar() && !immersiveLandscape

    // 注意：不能根据「值未变」跳过 controller.hide(navigationBars)，因为 onResume /
    // onWindowFocusChanged 后系统会主动恢复显示导航栏（transient sticky 依赖反复 hide），
    // 跳过会导致竖屏底栏不再隐藏
    if (controller != null) {
      if (showStatusBar) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        controller.show(WindowInsetsCompat.Type.statusBars())
      } else {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
      }
      // 沉浸式才隐藏导航栏，其他场景始终显示
      if (hideNavigationBar) {
        controller.hide(WindowInsetsCompat.Type.navigationBars())
      } else {
        controller.show(WindowInsetsCompat.Type.navigationBars())
      }
    }

    @Suppress("DEPRECATION")
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
      if (!showStatusBar) {
        flags = flags or (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
      }
      if (hideNavigationBar) {
        flags = flags or (
          View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
      }
      decorView.systemUiVisibility = flags
    }
  }

  private fun applyWebViewTextZoom() {
    val webView = bridge?.webView ?: return
    val settings = webView.settings
    settings.textZoom = 100
    // 允许 HTTPS 页面加载 HTTP 图片（网易云封面等）
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
  }

  /**
   * 请求屏幕支持的最高刷新率，让 WebView 的 rAF 能跑到 90/120fps。
   */
  private fun applyHighRefreshRate() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    try {
      val display = window.decorView.display
      if (display == null) {
        // DecorView 还没 attach（onCreate 首次调用常见），等下一帧再试一次
        window.decorView.post { applyHighRefreshRateNow() }
        return
      }
      applyHighRefreshRateNow()
    } catch (ignored: Exception) {
      // 高刷请求失败不阻塞播放
    }
  }

  private fun applyHighRefreshRateNow() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    try {
      val display = window.decorView.display ?: return

      val current = display.mode
      val modes = display.supportedModes
      var bestMode = current
      var bestRefreshRate = current.refreshRate

      // 只在与当前分辨率一致的模式里挑最高刷新率，避免顺带改分辨率
      for (mode in modes) {
        val sameResolution =
          mode.physicalWidth == current.physicalWidth &&
            mode.physicalHeight == current.physicalHeight
        if (sameResolution && mode.refreshRate > bestRefreshRate) {
          bestMode = mode
          bestRefreshRate = mode.refreshRate
        }
      }
      // 没有高于 60Hz 的可用模式，说明设备本身不支持，无需继续
      if (bestRefreshRate <= 60f) return

      val attrs = window.attributes
      var changed = false
      if (bestMode.modeId != current.modeId) {
        attrs.preferredDisplayModeId = bestMode.modeId
        changed = true
      }
      // 保险：同时设置 preferredRefreshRate，部分 ROM 只认这个字段
      if (attrs.preferredRefreshRate != bestRefreshRate) {
        attrs.preferredRefreshRate = bestRefreshRate
        changed = true
      }
      if (changed) {
        window.attributes = attrs
      }

      // WebView 自身 BLAST surface 的帧率提示
      val webView = bridge?.webView
      if (webView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
          // FRAME_RATE_COMPATIBILITY_DEFAULT 在 API 30 是 Surface 常量值 0
          val compatibilityDefault = 0
          webView.javaClass
            .getMethod("setFrameRate", Float::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(webView, bestRefreshRate, compatibilityDefault)
        } catch (ignored: Exception) {
          // Window preferredDisplayModeId 已覆盖主路径
        }
      }
    } catch (ignored: Exception) {
      // 高刷请求失败不阻塞播放
    }
  }

  /**
   * 检测应用版本升级，首次启动或升级后清除 WebView 资源缓存（JS/CSS/HTML/图片等磁盘文件）。
   */
  private fun clearWebViewCacheOnUpgrade(isColdStart: Boolean) {
    if (!isColdStart) return
    try {
      val prefs = getDefaultPrefs()
      val packageInfo = packageManager.getPackageInfo(packageName, 0)
      val currentVersionCode =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          packageInfo.longVersionCode
        } else {
          @Suppress("DEPRECATION")
          packageInfo.versionCode.toLong()
        }
      val lastVersionCode = prefs.getLong(PREF_LAST_VERSION_CODE, -1L)
      if (lastVersionCode != currentVersionCode) {
        val webView = bridge?.webView
        if (webView != null) {
          // includeDiskFiles=true：同时清除内存与磁盘缓存文件，确保旧资源不残留
          webView.clearCache(true)
        }
        prefs.edit().putLong(PREF_LAST_VERSION_CODE, currentVersionCode).apply()
      }
    } catch (ignored: Exception) {
      // 缓存清理失败不阻塞启动
    }
  }
}
