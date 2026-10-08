package top.imsyy.splayer_next.android.update

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import org.json.JSONObject

/**
 * Web 资源覆盖层（M1.3）：维护 filesDir/web-updates 下的版本目录布局，
 * 负责 staging → active 的原子切换与生效基线（当前版本 / 已应用清单）的持久化。
 *
 * 目录布局：
 *   filesDir/web-updates/
 *     staging/<version>/public    下载器暂存（校验通过前对外不可见）
 *     <version>/public            生效中 bundle（WebView 资源根）
 *     current.txt                 生效版本记录（与各版本目录同级）
 *     applied-manifest.json       已应用清单（下次差量比对的本地基准）
 *
 * 资源切换原理：加载根由 Capacitor 官方扩展点 Bridge.setServerBasePath(path) 切到 files
 * 目录，同时保持 https://localhost origin，localStorage / IndexedDB 与内置版同源共享；
 * 切换后需写入 CapWebViewSettings/serverBasePath 持久化，冷启动时 Bridge.loadWebView
 * 会自动恢复该路径（首次加载前恢复，无闪屏）；APK 升级时 Capacitor 自动清除该键并回退
 * 内置资源，与「原生变更只走整包更新」的约束天然对齐。
 */
object WebAssetOverlay {
  private const val DIR_NAME = "web-updates"
  private const val DIR_STAGING = "staging"
  private const val DIR_PUBLIC = "public"
  private const val FILE_CURRENT = "current.txt"
  private const val FILE_APPLIED_MANIFEST = "applied-manifest.json"

  /** 与 Capacitor WebView 插件持久化同款键（persistServerBasePath 同源实现） */
  private const val CAP_WEBVIEW_PREFS = "CapWebViewSettings"
  private const val CAP_SERVER_PATH = "serverBasePath"

  /** 设备侧更新根目录 filesDir/web-updates */
  fun deviceRoot(context: Context): File = File(context.filesDir, DIR_NAME)

  /**
   * 生效 bundle 的 public 目录。
   *
   * @param root 设备侧更新根目录
   * @param version 清单版本号（内部做目录名净化）
   * @return 生效 bundle 的 public 目录
   */
  fun activePublicDir(
    root: File,
    version: String,
  ): File = File(File(root, sanitizeVersionSegment(version)), DIR_PUBLIC)

  /**
   * 下载暂存 bundle 的 public 目录。
   *
   * @param root 设备侧更新根目录
   * @param version 清单版本号（内部做目录名净化）
   * @return 暂存 bundle 的 public 目录
   */
  fun stagingPublicDir(
    root: File,
    version: String,
  ): File = File(File(File(root, DIR_STAGING), sanitizeVersionSegment(version)), DIR_PUBLIC)

  /**
   * 读取当前生效版本号；无记录返回 null。
   *
   * @param root 设备侧更新根目录
   * @return 生效版本号（目录名段），无记录时为 null
   */
  fun readActiveVersion(root: File): String? {
    val file = File(root, FILE_CURRENT)
    if (!file.isFile) return null
    return file.readText().trim().ifEmpty { null }
  }

  /**
   * 读取已应用清单（差量比对的本地基准）；无记录或解析失败返回 null。
   *
   * @param root 设备侧更新根目录
   * @return 已应用清单
   */
  fun readAppliedManifest(root: File): UpdateManifest? {
    val file = File(root, FILE_APPLIED_MANIFEST)
    if (!file.isFile) return null
    return try {
      UpdateManifest.parse(file.readText())
    } catch (_: Exception) {
      // 基准文件损坏不阻塞更新流程：视为首次热更（差量即全量）
      null
    }
  }

  /**
   * 原子应用暂存 bundle：校验 staging 存在 → 删除同名旧目录（幂等重放）→
   * rename(staging/<v> → <v>) → 写 current.txt 与 applied-manifest.json。
   *
   * @param root 设备侧更新根目录
   * @param version 清单版本号
   * @param manifest 已通过校验的远端清单
   * @return 生效后的 public 目录（供 Bridge.setServerBasePath 使用）
   * @throws IllegalStateException staging 缺失或 rename 失败（跨实例可重试）
   */
  fun applyStaged(
    root: File,
    version: String,
    manifest: UpdateManifest,
  ): File {
    val versionSegment = sanitizeVersionSegment(version)
    val stagingBundle = File(File(root, DIR_STAGING), versionSegment)
    check(stagingBundle.isDirectory) { "staging bundle not found: ${stagingBundle.absolutePath}" }
    val activeBundle = File(root, versionSegment)
    // 同版本重复应用先清旧目录，保证 rename 目标不存在（幂等）
    if (activeBundle.exists()) {
      activeBundle.deleteRecursively()
    }
    // rename 在 filesDir 内同卷执行，系统保证原子性；失败保留 staging 以便重试
    check(stagingBundle.renameTo(activeBundle)) { "atomic rename failed: ${stagingBundle.absolutePath}" }
    File(root, FILE_CURRENT).writeText(versionSegment)
    // 优先持久化 manifest 原文；仅当构造方未携带原文时回退序列化
    File(root, FILE_APPLIED_MANIFEST).writeText(manifest.rawJson ?: toManifestJson(manifest))
    return File(activeBundle, DIR_PUBLIC)
  }

  /**
   * 应用后清理：删除非当前版本的历史 bundle 目录与残留暂存。
   * 仅精确匹配本布局（含 public 子目录）的旧 bundle 才删除，避免误删其它受管内容。
   *
   * @param root 设备侧更新根目录
   * @param keepVersion 需保留的版本号
   */
  fun cleanupOldVersions(
    root: File,
    keepVersion: String,
  ) {
    val keep = sanitizeVersionSegment(keepVersion)
    root.listFiles()?.forEach { child ->
      if (!child.isDirectory) return@forEach
      val name = child.name
      if (name == keep || name == DIR_STAGING) return@forEach
      if (File(child, DIR_PUBLIC).isDirectory) {
        child.deleteRecursively()
      }
    }
    // 暂存目录整体兜底清空（下载失败场景由下载器自清，这里双保险）
    File(root, DIR_STAGING).deleteRecursively()
  }

  /**
   * 持久化 WebView 资源根路径，使冷启动由 Capacitor 自动恢复
   * （与本项目依赖的 CapWebViewSettings/serverBasePath 键一致）。
   *
   * @param context 应用上下文
   * @param serverBasePath https://localhost 的资源根（files 目录）
   */
  fun persistServerBasePath(
    context: Context,
    serverBasePath: String,
  ) {
    val prefs: SharedPreferences =
      context.getSharedPreferences(CAP_WEBVIEW_PREFS, Context.MODE_PRIVATE)
    prefs.edit().putString(CAP_SERVER_PATH, serverBasePath).apply()
  }

  /** 将清单序列化为 JSON 文本（仅 rawJson 缺失时的回退路径） */
  private fun toManifestJson(manifest: UpdateManifest): String {
    val filesJson = JSONObject()
    for ((path, entry) in manifest.files) {
      val fileJson =
        JSONObject()
          .put("path", entry.path)
          .put("sha256", entry.sha256)
          .put("size", entry.size)
      filesJson.put(path, fileJson)
    }
    return JSONObject()
      .put("version", manifest.version)
      .put("minNativeVersion", manifest.minNativeVersion)
      .put("channel", manifest.channel)
      .put("timestamp", manifest.timestamp)
      .put("files", filesJson)
      .toString()
  }
}
