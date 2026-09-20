package top.imsyy.splayer_next.android.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** 用户取消下载时抛出（不参与重试，与网络/校验失败区分） */
class UpdateCancelledException : Exception("live update download cancelled")

/**
 * 差量下载器（M1.4）：按本地已应用清单与远端清单的差异，仅下载变更文件到 staging 目录，
 * 逐文件 sha256 + size 校验，任一文件失败即弃整个 staging（半包必弃）。
 * 网络栈沿用项目现有 HttpURLConnection，支持进度回调与取消。
 */
class WebUpdateDownloader(
  private val connectTimeoutMs: Int = 15_000,
  private val readTimeoutMs: Int = 30_000,
  private val maxRetries: Int = 2,
) {
  /** 取消标记：置位后当前与后续下载立即中止 */
  private val cancelled = AtomicBoolean(false)

  /** 取消进行中的下载（幂等） */
  fun cancel() {
    cancelled.set(true)
  }

  /** 差量下载结果 */
  data class DownloadResult(
    /** 清单版本号 */
    val version: String,
    /** 完成文件数 */
    val completedFiles: Int,
    /** 差量文件总数 */
    val totalFiles: Int,
    /** 实际下载字节数 */
    val downloadedBytes: Long,
    /** 暂存完成后可直接切换的 public 目录 */
    val stagingPublicDir: File,
  )

  /**
   * 下载差异文件到 staging 目录。
   *
   * @param manifest 已通过验签的远端清单
   * @param localHashes 本地已应用哈希表（path → sha256；空表视为首次热更全量下载）
   * @param baseUrl 文件下载基址，与清单内 relPath 拼接为完整 URL（末尾斜杠可省）
   * @param stagingPublicRoot 暂存 public 目录（WebAssetOverlay.stagingPublicDir 提供）
   * @param onProgress 进度回调 (totalBytes, downloadedBytes, totalFiles, completedFiles)
   * @return 下载结果（成功后 staging 已可通过 WebAssetOverlay.applyStaged 原子启用）
   * @throws UpdateCancelledException 用户取消（staging 已清理）
   * @throws IOException 网络失败 / 大小不符（staging 已清理，可重试）
   * @throws SecurityException 文件 sha256 与清单不符（staging 已清理，拒绝启用）
   * @throws IllegalStateException 差量为空（应由上游在进入下载前拦截）
   */
  fun downloadDiffToStaging(
    manifest: UpdateManifest,
    localHashes: Map<String, String>,
    baseUrl: String,
    stagingPublicRoot: File,
    onProgress: (totalBytes: Long, downloadedBytes: Long, totalFiles: Int, completedFiles: Int) -> Unit,
  ): DownloadResult {
    cancelled.set(false)
    val localEntries =
      localHashes
        .map { (path, sha256) ->
          path to UpdateFileEntry(path = path, sha256 = sha256, size = 0L)
        }.toMap()
    val diff = computeDiff(manifest.files, localEntries)
    val totalFiles = diff.size
    check(totalFiles > 0) { "no diff files found" }
    val totalBytes = diff.sumOf { it.size }
    // 磁盘预算不在此设体积上限：差量本身 ≤ 整包体积，且 staging 单一目录、应用后清理旧版本
    // （WebAssetOverlay.cleanupOldVersions），磁盘占用天然有界

    val baseUrlTrimmed = baseUrl.trimEnd('/')
    val stagingRootCanonical = stagingPublicRoot.canonicalFile
    var downloadedBytes = 0L
    var completedFiles = 0
    try {
      for (entry in diff) {
        checkCancelled()
        val target = File(stagingPublicRoot, entry.path)
        // 防御不可信清单（密钥体系落地前验签非强制）的路径穿越：目标必须落在 staging 根内
        check(target.canonicalFile.path.startsWith(stagingRootCanonical.path)) {
          "path traversal rejected: ${entry.path}"
        }
        if (target.exists()) {
          target.delete()
        }
        target.parentFile?.mkdirs()
        downloadFileWithRetry(
          url = "$baseUrlTrimmed/${entry.path}",
          target = target,
          expectedSha256 = entry.sha256,
          expectedSize = entry.size,
        )
        downloadedBytes += entry.size
        completedFiles += 1
        onProgress(totalBytes, downloadedBytes, totalFiles, completedFiles)
      }
    } catch (e: Exception) {
      // 半包必弃：任一步失败都清理整个 staging，保证下一次应用不会吃到残缺包
      stagingPublicRoot.deleteRecursively()
      throw e
    }
    return DownloadResult(
      version = manifest.version,
      completedFiles = completedFiles,
      totalFiles = totalFiles,
      downloadedBytes = downloadedBytes,
      stagingPublicDir = stagingPublicRoot,
    )
  }

  /** 单文件下载，带重试（网络/大小不符可重试；校验失败与取消不重试） */
  private fun downloadFileWithRetry(
    url: String,
    target: File,
    expectedSha256: String,
    expectedSize: Long,
  ) {
    var attempt = 0
    while (true) {
      try {
        fetchToFile(url, target)
        verifyDownloaded(url, target, expectedSha256, expectedSize)
        return
      } catch (e: UpdateCancelledException) {
        throw e
      } catch (e: IOException) {
        attempt += 1
        if (attempt > maxRetries) throw e
        target.delete()
        // 简单退避后重试；下载器运行在后台线程，阻塞安全
        Thread.sleep(RETRY_BACKOFF_BASE_MS * attempt)
      }
    }
  }

  /** 校验下载结果：大小与 sha256 均需与清单一致（校验失败不重试，直接拒收） */
  private fun verifyDownloaded(
    url: String,
    target: File,
    expectedSha256: String,
    expectedSize: Long,
  ) {
    val actualSize = target.length()
    if (actualSize != expectedSize) {
      throw IOException("size mismatch: $url expect=$expectedSize actual=$actualSize")
    }
    val actualSha256 = sha256Of(target)
    if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
      throw SecurityException("sha256 mismatch: $url")
    }
  }

  /** 流式下载到目标文件（边写边算 digest，中途可取消） */
  private fun fetchToFile(
    urlStr: String,
    target: File,
  ) {
    val connection =
      URL(urlStr).openConnection() as HttpURLConnection
    try {
      connection.apply {
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        // 部分 CDN（含 GitHub 资产）对无 UA 请求返回 403
        setRequestProperty("User-Agent", "SPlayer-Next/Android")
        instanceFollowRedirects = true
      }
      connection.connect()
      val responseCode = connection.responseCode
      if (responseCode !in 200..299) {
        throw IOException("HTTP $responseCode for $urlStr")
      }
      val input: InputStream = connection.inputStream
      input.use { src ->
        target.outputStream().use { out ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            checkCancelled()
            val read = src.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
          }
        }
      }
    } finally {
      connection.disconnect()
    }
  }

  private fun checkCancelled() {
    if (cancelled.get()) throw UpdateCancelledException()
  }

  companion object {
    private const val RETRY_BACKOFF_BASE_MS = 500L

    /**
     * 计算文件 sha256（hex 小写）。
     *
     * @param file 目标文件
     * @return 64 位十六进制摘要
     */
    fun sha256Of(file: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          digest.update(buffer, 0, read)
        }
      }
      val digestBytes = digest.digest()
      val hexChars = CHAR_ARRAY_HEX
      return buildString(digestBytes.size * 2) {
        digestBytes.forEach { byte ->
          val unsigned = byte.toInt() and 0xFF
          append(hexChars[unsigned ushr 4])
          append(hexChars[unsigned and 0x0F])
        }
      }
    }

    private val CHAR_ARRAY_HEX = "0123456789abcdef".toCharArray()
  }
}
