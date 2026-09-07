package top.imsyy.splayer_next.android

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.webkit.WebView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

object NativeLogConsoleBridge {
  private const val CONSOLE_PREFIX = "[native:error]"
  private const val LOGCAT_BACKLOG_LINES = "200"
  private const val RECENT_LINE_LIMIT = 256

  /** Chromium C++ 层日志格式：[ERROR:file.cc(123)]（nodejs-mobile stderr 转发后 tag 为 NODEJS-MOBILE，tag 过滤拦不住，含调试构建的 SharedImage 生命周期跟踪） */
  private val CHROMIUM_CPP_LOG = Regex("""\[ERROR:[\w.]+\.(?:cc|cpp|h)\(\d+\)\]""")

  /** 致命错误特征：命中后跳过后续噪音过滤，始终透传到 WebView 控制台。 */
  private val FATAL_LOG =
    Regex(
      "FATAL EXCEPTION|Fatal signal|beginning of crash|Process .* has died|Abort message|" +
        "SIGSEGV|SIGABRT|SIGBUS|SIGILL|SIGFPE|SIGTRAP|tombstone|native crash|" +
        "\\[embedded-api\\] fatal|mobile-server import failure|uncaughtException",
    )

  /** nodejs-mobile stderr 重定向进来的 Chromium/Skia GPU 生命周期统计，与业务无关。 */
  private val NODEJS_MOBILE_CHROMIUM_TRACE =
    Regex(
      "\\bnd=\\d+\\b|\\bd=\\d+ has_scoped_access=\\d+\\b|\\bted=\\d+ has_scoped_access=\\d+\\b|" +
        "presentation=0x[0-9a-fA-F]+ image_count=\\d+|image_count=\\d+|" +
        "\\bhas_scoped_access=\\d+\\b|\\bst_ref=\\d+\\b",
    )

/** 调试构建 Chromium 的 SharedImage 生命周期跟踪（含跨行续行）。GPU 纹理统计，与业务无关。 */
  private val SHARED_IMAGE_TRACE =
    Regex(
      "SharedImageLifetimeDebug|shared_image_manager|shared_image_backing|shared_image_mana|" +
        "EGLImageBacking|WrappedSkImage|image_refs|refs_before|refs_after|has_refs|as_refs_before|" +
        "representation=0x|backing=0x|mailbox=",
    )

  private val mainHandler = Handler(Looper.getMainLooper())
  private val running = AtomicBoolean(false)
  private val recentLines = LinkedHashSet<String>()

  @Volatile
  private var logcatProcess: java.lang.Process? = null

  @Volatile
  private var worker: Thread? = null

  fun start(webView: WebView?) {
    if (webView == null || !running.compareAndSet(false, true)) return
    val webViewRef = WeakReference(webView)
    worker =
      Thread { runLogcat(webViewRef) }.apply {
        name = "NativeLogConsoleBridge"
        isDaemon = true
        start()
      }
  }

  fun stop() {
    running.set(false)
    logcatProcess?.destroy()
    logcatProcess = null
    worker?.interrupt()
    worker = null
  }

  private fun runLogcat(webViewRef: WeakReference<WebView>) {
    try {
      val process =
        ProcessBuilder(
          "logcat",
          "--pid=${Process.myPid()}",
          "-T",
          LOGCAT_BACKLOG_LINES,
          "-v",
          "threadtime",
          "*:E",
        ).redirectErrorStream(true).start()
      logcatProcess = process
      BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
        for (line in lines) {
          if (!running.get()) break
          val webView = webViewRef.get() ?: break
          if (shouldForward(line)) {
            forwardToConsole(webView, line)
          }
        }
      }
    } catch (e: Exception) {
      webViewRef.get()?.let { webView ->
        forwardToConsole(webView, "logcat bridge stopped: ${e.message ?: e.javaClass.name}")
      }
    } finally {
      logcatProcess?.destroy()
      logcatProcess = null
      running.set(false)
    }
  }

  @Suppress("ReturnCount")
  private fun shouldForward(line: String): Boolean {
    if (line.isBlank() || line.contains(CONSOLE_PREFIX)) return false
    // 致命错误须先于级别过滤判定：logcat 拉取的 *:E 含 F 级（Fatal signal/SIGSEGV 等行级别标记为
    // " F " 而非 " E "），若先做 " E " 检查会把这些 native crash 行全部拦下，FATAL_LOG 永远不生效
    if (FATAL_LOG.containsMatchIn(line)) return true
    if (!line.contains(" E ")) return false
    if (CHROMIUM_CPP_LOG.containsMatchIn(line)) return false
    // 调试构建 Chromium 的 SharedImage 逐行续行（as_refs=.../backing=0x...）不带 [ERROR:*.cc(N)] 前缀，
    // CPP_LOG 拦不住，成片灌进 WebView 控制台。这些 GPU 纹理引用统计与业务无关，统一过滤。
    if (SHARED_IMAGE_TRACE.containsMatchIn(line)) return false
    val tag = extractTag(line)
    if (tag.equals("chromium", ignoreCase = true) || tag.startsWith("cr_", ignoreCase = true)) {
      return false
    }
    // nodejs-mobile 会以 E 级透传 Chromium/Skia 的 SharedImage 续行，单独按 tag 过滤。
    if (tag.equals("NODEJS-MOBILE", ignoreCase = true) &&
      NODEJS_MOBILE_CHROMIUM_TRACE.containsMatchIn(line)
    ) {
      return false
    }

    // 过滤部分系统级的良性错误，例如 AudioTrack 找不到低功耗配置的问题
    if (tag.equals("AudioTrack", ignoreCase = true) && line.contains("audio_lowpower_app_list.xml")) {
      return false
    }
    // 过滤 Android Surface 缓冲队列瞬时拥塞日志，常见于输入法/横竖屏/弹窗过渡期间，不影响业务
    if (tag.equals("BLASTBufferQueue", ignoreCase = true) &&
      line.contains("acquireNextBufferLocked") &&
      line.contains("Already acquired max frames")
    ) {
      return false
    }
    // 过滤 MediaCodec 初始化时查询系统媒体质量服务失败的良性日志，部分 ROM 未提供该服务
    if (tag.equals("MediaCodec", ignoreCase = true) &&
      line.contains("Media Quality Service not found")
    ) {
      return false
    }
    // 过滤编解码组件查询系统资源接口失败的环境噪音，常见于厂商裁剪 ROM，不影响正常播放
    if (line.contains("Failed to query component interface for required system resources")) {
      return false
    }
    // 过滤 LB 底层库的文件打开报错
    if (tag.equals("LB", ignoreCase = true) && line.contains("fail to open")) {
      return false
    }
    // 过滤 Capacitor 本地回调状态无法保存的警告（在不杀死进程时回调仍然可用）
    if (tag.equals("Capacitor", ignoreCase = true) && line.contains("Couldn't save last")) {
      return false
    }
    // 过滤隐藏 API 访问告警（方法与字段两种文案，MIUI contentcatcher 反射失败为框架 bug，仅匹配 MIUI 相关 token，避免误伤业务 Interceptor）
    if (line.contains("hiddenapi: Accessing hidden") ||
      line.contains("SettingTrigger") ||
      line.contains("contentcatcher") ||
      line.contains("InterceptorProxy") ||
      line.contains("InterceptorHandler") ||
      line.contains("InterceptorFactory") ||
      line.contains("mContentExtensionEnabled")
    ) {
      return false
    }
    // 过滤厂商 QoS/渲染/性能系统噪音，与 Web 侧 bridgeLogFilter.ts 对齐，避免启动期 backlog 在 JS 过滤器安装前透传
    if (line.contains("setTidQosLevel") ||
      line.contains("XQos") ||
      line.contains("/dev/xr_fbt") ||
      line.contains("QoS device not available") ||
      line.contains("MI-PreRender") ||
      line.contains("perfctl") ||
      line.contains("FPSGO") ||
      line.contains("GuiExtAuxCheckAuxPath") ||
      line.contains("Not drawing due to screen off") ||
      line.contains("VRI[")
    ) {
      return false
    }
    // 过滤播放器接收标志位的常规信息
    if (line.contains("legacy_receive_flag:")) {
      return false
    }
    // 过滤系统资源管理器回收 MediaCodec 的常规生命周期日志
    if (tag.equals("MediaCodec", ignoreCase = true) && line.contains("Released by resource manager")) {
      return false
    }
    // 过滤 ActivityThread 交付结果时的 Bundle 空指针告警（系统级捕获但不影响业务）
    if (tag.equals("ActivityThread", ignoreCase = true) &&
      line.contains("fail in deliverResultsIfNeeded") &&
      line.contains("android.os.Bundle.getString")
    ) {
      return false
    }

    synchronized(recentLines) {
      if (!recentLines.add(line)) return false
      while (recentLines.size > RECENT_LINE_LIMIT) {
        val first = recentLines.iterator().next()
        recentLines.remove(first)
      }
    }
    return true
  }

  private fun extractTag(line: String): String {
    val body = line.substringAfter(" E ", "")
    if (body.isEmpty()) return ""
    return body.substringBefore(":").trim()
  }

  private fun forwardToConsole(
    webView: WebView,
    line: String,
  ) {
    val message = JSONObject.quote("$CONSOLE_PREFIX $line")
    mainHandler.post {
      try {
        webView.evaluateJavascript("console.error($message);", null)
      } catch (ignored: Exception) {
        // WebView 销毁期间的日志无法投递，避免反向制造新错误。
      }
    }
  }
}
