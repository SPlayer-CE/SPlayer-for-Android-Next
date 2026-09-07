package top.imsyy.splayer_next.android.cache

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 歌曲缓存下载/查询共享助手。
 *
 * <p>文件命名（SHA-1 前 8 字节 hex + `.bin`）、songs 目录、下载校验流程在此唯一维护，
 * Capacitor 插件（AndroidSongCachePlugin）与原生播放层（PlaybackUrlResolver /
 * PlaybackManager 缓存调度）共用，避免两份实现漂移。
 */
object SongCacheFetcher {
  private const val TAG = "SongCacheFetcher"
  private val REJECTED_MIME_PREFIXES = listOf("text/html", "application/json", "application/xml", "text/xml")

  /** 进行中下载的去重表：cacheKey → 取消标志。 */
  private val inFlight = ConcurrentHashMap<String, AtomicBoolean>()

  /** 歌曲缓存目录（{cacheRoot}/songs）。 */
  fun cacheDir(context: Context): File {
    val rootDir = CacheStorage.getInstance(context).getRootDir()
    val dir = File(rootDir, "songs")
    if (!dir.exists()) {
      dir.mkdirs()
    }
    return dir
  }

  /** cacheKey → 缓存文件名（SHA-1 前 8 字节 hex + `.bin`）。 */
  fun filenameFor(cacheKey: String): String {
    try {
      val md = MessageDigest.getInstance("SHA-1")
      val digest = md.digest(cacheKey.toByteArray(Charsets.UTF_8))
      val sb = StringBuilder()
      for (i in 0 until 8) { // first 16 chars = 8 bytes
        val hex = Integer.toHexString(digest[i].toInt() and 0xFF)
        if (hex.length == 1) sb.append('0')
        sb.append(hex)
      }
      return "$sb.bin"
    } catch (e: Exception) {
      return "${cacheKey.hashCode()}.bin"
    }
  }

  /** 已缓存文件绝对路径；未缓存返回 null。 */
  fun lookup(
    context: Context,
    cacheKey: String,
  ): String? {
    val file = File(cacheDir(context), filenameFor(cacheKey))
    return if (file.exists()) file.absolutePath else null
  }

  /** 缓存文件是否像音频（前 4 字节非 HTML/JSON 起始符），供播放层回退前校验。 */
  fun looksLikeAudio(file: File): Boolean {
    if (!file.exists() || file.length() < 4) return false
    var fis: FileInputStream? = null
    try {
      fis = FileInputStream(file)
      val buf = ByteArray(4)
      val read = fis.read(buf)
      if (read < 4) return false
      // '<' = HTML/XML, '{' = JSON object, '[' = JSON array
      if (buf[0].toInt() == 0x3c || buf[0].toInt() == 0x7b || buf[0].toInt() == 0x5b) {
        return false
      }
      return true
    } catch (e: Exception) {
      return false
    } finally {
      fis?.close()
    }
  }

  /** 取消指定 key 的进行中下载。 */
  fun cancel(cacheKey: String) {
    inFlight[cacheKey]?.set(true)
  }

  /**
   * 阻塞下载歌曲缓存；必须在后台线程调用。
   *
   * @return 下载成功（或已存在）的缓存文件；参数非法、同 key 下载进行中、
   *         HTTP/MIME/内容校验失败、被取消时返回 null
   */
  fun download(
    context: Context,
    cacheKey: String,
    streamUrl: String,
  ): File? {
    if (cacheKey.isEmpty() || streamUrl.isEmpty()) return null
    val dir = cacheDir(context)
    val finalFile = File(dir, filenameFor(cacheKey))
    val partFile = File(dir, "${filenameFor(cacheKey)}.part")

    if (finalFile.exists()) return finalFile

    val cancelFlag = AtomicBoolean(false)
    // 同 key 并发下载直接返回 null，复用由调用方在下载完成后触发
    if (inFlight.putIfAbsent(cacheKey, cancelFlag) != null) return null

    try {
      val urlObj = URL(streamUrl)
      val connection = urlObj.openConnection() as HttpURLConnection
      connection.requestMethod = "GET"
      connection.connectTimeout = 30000
      connection.readTimeout = 60000
      connection.setRequestProperty("User-Agent", "SPlayer-for-Android")

      connection.connect()

      if (cancelFlag.get()) {
        connection.disconnect()
        return null
      }

      val responseCode = connection.responseCode
      if (responseCode < 200 || responseCode >= 300) {
        connection.disconnect()
        return null
      }

      val mime = connection.contentType
      if (isRejectedMime(mime)) {
        connection.disconnect()
        return null
      }

      val input = connection.inputStream
      val output = FileOutputStream(partFile)
      val buffer = ByteArray(8192)
      var bytesRead: Int

      while (input.read(buffer).also { bytesRead = it } != -1) {
        if (cancelFlag.get()) {
          break
        }
        output.write(buffer, 0, bytesRead)
      }

      output.close()
      input.close()
      connection.disconnect()

      if (cancelFlag.get()) {
        partFile.delete()
        return null
      }

      if (!partFile.exists() || partFile.length() == 0L) {
        partFile.delete()
        return null
      }

      if (!looksLikeAudio(partFile)) {
        partFile.delete()
        return null
      }

      partFile.renameTo(finalFile)
      return finalFile
    } catch (e: Exception) {
      Log.e(TAG, "Fetch failed for $cacheKey", e)
      partFile.delete()
      return null
    } finally {
      inFlight.remove(cacheKey)
    }
  }

  private fun isRejectedMime(mime: String?): Boolean {
    if (mime == null) return false
    val lower = mime.lowercase()
    return REJECTED_MIME_PREFIXES.any { lower.startsWith(it) }
  }
}
