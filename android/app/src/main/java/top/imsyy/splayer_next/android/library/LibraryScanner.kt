package top.imsyy.splayer_next.android.library

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 本地音乐库扫描器
 * <p>递归遍历 SAF 目录，用 [MediaMetadataRetriever] 提取元数据，
 * 嵌入封面压缩落盘到 `cacheDir/local-covers/`，扫描结果写入 [LibraryDatabase]。
 * 支持增量扫描（比对 mtime + size）和取消。
 */
class LibraryScanner(
  private val context: Context,
  private val database: LibraryDatabase,
) {
  companion object {
    /** 支持的音频扩展名 */
    private val AUDIO_EXTENSIONS =
      arrayOf(
        ".mp3",
        ".flac",
        ".wav",
        ".ogg",
        ".m4a",
        ".aac",
        ".ape",
        ".wma",
        ".opus",
      )

    /** 封面压缩最大边长 */
    private const val COVER_MAX_SIZE = 1024

    /** 封面 JPEG 质量 */
    private const val COVER_JPEG_QUALITY = 80

    /** 判断文件名是否为音频文件 */
    private fun isAudioFile(name: String): Boolean {
      val lower = name.lowercase()
      for (ext in AUDIO_EXTENSIONS) {
        if (lower.endsWith(ext)) return true
      }
      return false
    }

    /** 生成稳定的 Track ID（URI 哈希 + 偏移，避免与在线歌曲 ID 冲突） */
    private fun generateTrackId(path: String): String = sha256Hex(path).substring(0, 16)

    /** SHA-256 哈希，返回十六进制字符串 */
    private fun sha256Hex(value: String): String =
      try {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(value.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(hash.size * 2)
        for (b in hash) {
          sb.append(String.format(java.util.Locale.US, "%02x", b))
        }
        sb.toString()
      } catch (ignored: Exception) {
        ""
      }

    /** 计算 BitmapFactory 的 inSampleSize */
    private fun calculateInSampleSize(
      options: BitmapFactory.Options,
      maxSize: Int,
    ): Int {
      val height = options.outHeight
      val width = options.outWidth
      if (height <= 0 || width <= 0) return 1
      var inSampleSize = 1
      while (height / inSampleSize > maxSize || width / inSampleSize > maxSize) {
        inSampleSize *= 2
      }
      return inSampleSize
    }

    /** 转义 JSON 字符串中的特殊字符 */
    private fun escapeJsonString(value: String?): String {
      if (value == null) return ""
      val sb = java.lang.StringBuilder(value.length)
      for (i in 0 until value.length) {
        val c = value[i]
        when (c) {
          '"' -> sb.append("\\\"")
          '\\' -> sb.append("\\\\")
          '\n' -> sb.append("\\n")
          '\r' -> sb.append("\\r")
          '\t' -> sb.append("\\t")
          else -> {
            if (c.code < 0x20) {
              sb.append(String.format(java.util.Locale.US, "\\u%04x", c.code))
            } else {
              sb.append(c)
            }
          }
        }
      }
      return sb.toString()
    }
  }

  /** 扫描进度回调 */
  interface ProgressCallback {
    /**
     * 推送进度
     * @param phase 阶段：scanning / done / error
     * @param total 总文件数
     * @param scanned 已扫描文件数
     * @param current 当前正在处理的文件名
     * @param error 错误信息（仅 error 阶段）
     */
    fun onProgress(
      phase: String,
      total: Int,
      scanned: Int,
      current: String?,
      error: String?,
    )
  }

  private val cancelled = AtomicBoolean(false)

  /** 取消正在进行的扫描 */
  fun cancel() {
    cancelled.set(true)
  }

  /** 是否已取消 */
  fun isCancelled(): Boolean = cancelled.get()

  /**
   * 扫描多个 SAF 目录
   * @param directories 目录 URI 列表
   * @param incremental 是否增量扫描
   * @param callback 进度回调
   */
  fun scan(
    directories: List<String>,
    incremental: Boolean,
    callback: ProgressCallback?,
  ) {
    cancelled.set(false)

    // 第一阶段：收集全部音频文件
    val audioFiles = ArrayList<DocumentFile>()
    for (uriText in directories) {
      if (cancelled.get()) {
        notifyDone(callback, 0, 0)
        return
      }
      try {
        val uri = Uri.parse(uriText)
        val directory = DocumentFile.fromTreeUri(context, uri)
        if (directory == null || !directory.exists() || !directory.canRead()) continue
        collectAudioFiles(directory, audioFiles)
      } catch (ignored: SecurityException) {
        // 目录权限失效，跳过
      }
    }

    val total = audioFiles.size
    if (total == 0) {
      // 空目录：增量模式下清空 DB，全量模式下保持空
      if (incremental) {
        database.deleteTracksByPaths(emptyList())
      }
      notifyDone(callback, 0, 0)
      return
    }

    // 增量扫描：加载现有文件记录用于比对
    var existingRecords: Map<String, LongArray>? = null
    if (incremental) {
      existingRecords = loadExistingRecords()
    }

    // 第二阶段：逐个提取元数据并入库
    val upserts = ArrayList<ContentValues>()
    val seenPaths = ArrayList<String>()
    var scanned = 0

    for (file in audioFiles) {
      if (cancelled.get()) {
        notifyDone(callback, total, scanned)
        return
      }

      val path = file.uri.toString()
      val mtime = file.lastModified()
      val size = file.length()
      val fileName = file.name ?: continue
      scanned++

      // 增量比对：mtime 和 size 都未变化则跳过
      if (existingRecords != null) {
        val existing = existingRecords[path]
        if (existing != null && existing[0] == mtime && existing[1] == size) {
          seenPaths.add(path)
          continue
        }
      }

      notifyProgress(callback, total, scanned, fileName)

      val values = buildTrackValues(file, path, fileName, mtime, size)
      if (values != null) {
        upserts.add(values)
        seenPaths.add(path)
      }
    }

    // 批量入库
    if (upserts.isNotEmpty()) {
      database.upsertTracks(upserts)
    }

    // 增量扫描：删除 DB 中不再存在的文件
    if (incremental && existingRecords != null) {
      deleteStaleTracks(existingRecords, seenPaths)
    }

    notifyDone(callback, total, scanned)
  }

  /** 递归收集目录下的全部音频文件 */
  private fun collectAudioFiles(
    directory: DocumentFile,
    output: MutableList<DocumentFile>,
  ) {
    val children: Array<DocumentFile> =
      try {
        directory.listFiles()
      } catch (ignored: SecurityException) {
        return
      }
    for (child in children) {
      if (cancelled.get()) return
      if (child.isDirectory) {
        collectAudioFiles(child, output)
        continue
      }
      if (!child.isFile) continue
      val name = child.name
      if (name == null || !isAudioFile(name)) continue
      output.add(child)
    }
  }

  /** 从 DocumentFile 提取元数据并构建 ContentValues */
  private fun buildTrackValues(
    file: DocumentFile,
    path: String,
    fileName: String,
    mtime: Long,
    size: Long,
  ): ContentValues? {
    // 默认从文件名解析（"Artist - Title.ext" 或 "Title.ext"）
    var baseName = fileName
    val dotIdx = baseName.lastIndexOf('.')
    if (dotIdx > 0) baseName = baseName.substring(0, dotIdx)

    var fallbackTitle = baseName
    var fallbackArtist = "未知歌手"
    val sepIdx = baseName.indexOf(" - ")
    if (sepIdx > 0) {
      fallbackArtist = baseName.substring(0, sepIdx).trim()
      fallbackTitle = baseName.substring(sepIdx + 3).trim()
      if (fallbackTitle.isEmpty()) fallbackTitle = baseName
    }

    var title = fallbackTitle
    var artist = fallbackArtist
    var album = ""
    var duration = 0L
    var bitrate = 0L
    var cover = ""

    var retriever: MediaMetadataRetriever? = null
    try {
      retriever = MediaMetadataRetriever()
      retriever.setDataSource(context, file.uri)

      val mTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
      val mArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
      val mAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
      val mDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
      val mBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)

      if (!mTitle.isNullOrBlank()) title = mTitle.trim()
      if (!mArtist.isNullOrBlank()) artist = mArtist.trim()
      if (!mAlbum.isNullOrBlank()) album = mAlbum.trim()
      if (mDuration != null) {
        try {
          duration = mDuration.toLong()
        } catch (ignored: NumberFormatException) {
        }
      }
      if (mBitrate != null) {
        try {
          bitrate = mBitrate.toLong()
        } catch (ignored: NumberFormatException) {
        }
      }

      // 提取嵌入封面，压缩后写入 cacheDir/local-covers/<idHash>.jpg
      val embeddedPicture = retriever.embeddedPicture
      if (embeddedPicture != null) {
        cover = writeEmbeddedCoverToCache(path, embeddedPicture)
      }
    } catch (ignored: Exception) {
      // 文件不可解析，使用文件名兜底
    } finally {
      try {
        retriever?.release()
      } catch (ignored: Exception) {
      }
    }

    // ID 用 URI 哈希生成（确保稳定 + 不与在线歌曲 ID 冲突）
    val id = generateTrackId(path)

    val values = ContentValues()
    values.put("id", id)
    values.put("path", path)
    values.put("title", title)
    // artists 存为 JSON 数组
    values.put("artists", "[{\"name\":\"${escapeJsonString(artist)}\"}]")
    // album 存为 JSON 对象（空专辑存 null）
    if (album.isNotEmpty()) {
      values.put("album", "{\"name\":\"${escapeJsonString(album)}\"}")
    } else {
      values.putNull("album")
    }
    values.put("duration", duration)
    if (cover.isNotEmpty()) {
      values.put("cover", cover)
    } else {
      values.putNull("cover")
    }
    // codec 等高级字段暂不提取（MediaMetadataRetriever 不直接提供）
    values.putNull("codec")
    values.putNull("sample_rate")
    if (bitrate > 0) values.put("bit_rate", bitrate) else values.putNull("bit_rate")
    values.putNull("channels")
    values.putNull("bits_per_sample")
    values.put("file_size", size)
    values.put("file_mtime", mtime)
    values.put("file_ctime", mtime)
    values.put("scanned_at", System.currentTimeMillis())
    return values
  }

  /** 加载 DB 中现有的文件记录（path → [mtime, size]）用于增量比对 */
  private fun loadExistingRecords(): Map<String, LongArray> {
    val records = HashMap<String, LongArray>()
    for (row in database.getAllTracks()) {
      val mtime = row.fileMtime ?: 0L
      val size = row.fileSize
      records[row.path] = longArrayOf(mtime, size)
    }
    return records
  }

  /** 删除 DB 中不再存在的文件记录 */
  private fun deleteStaleTracks(
    existingRecords: Map<String, LongArray>,
    seenPaths: List<String>,
  ) {
    val toDelete = ArrayList<String>()
    for (path in existingRecords.keys) {
      if (!seenPaths.contains(path)) {
        toDelete.add(path)
      }
    }
    if (toDelete.isNotEmpty()) {
      database.deleteTracksByPaths(toDelete)
    }
  }

  // ── 封面落盘 ────────────────────────────────────────────────────────────

  /**
   * 嵌入封面落盘：按源 URI hash 成名，写入 `cacheDir/local-covers/`。返回 file:// URI。
   * 已存在则复用。压缩到 1024px 内 JPEG quality 80。完全失败返回 ""。
   */
  private fun writeEmbeddedCoverToCache(
    sourceUri: String,
    pictureBytes: ByteArray,
  ): String {
    var original: Bitmap? = null
    var scaled: Bitmap? = null
    try {
      val coverDir = File(context.cacheDir, "local-covers")
      if (!coverDir.exists() && !coverDir.mkdirs()) return ""
      val idHash = sha256Hex(sourceUri)
      if (idHash.isEmpty()) return ""
      val coverFile = File(coverDir, "$idHash.jpg")
      // 已存在则复用
      if (coverFile.isFile && coverFile.length() > 0) {
        return "file://" + coverFile.absolutePath
      }
      // 解码边界
      val boundsOption = BitmapFactory.Options()
      boundsOption.inJustDecodeBounds = true
      BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.size, boundsOption)
      val decodeOption = BitmapFactory.Options()
      decodeOption.inSampleSize = calculateInSampleSize(boundsOption, COVER_MAX_SIZE)
      original = BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.size, decodeOption)
      if (original == null) return ""
      // 缩放
      val w = original.width
      val h = original.height
      val scale = min(COVER_MAX_SIZE.toFloat() / w, COVER_MAX_SIZE.toFloat() / h)
      scaled = original
      if (scale < 1.0f) {
        scaled =
          Bitmap.createScaledBitmap(
            original,
            (w * scale).roundToInt(),
            (h * scale).roundToInt(),
            true,
          )
      }
      // 原子写：tmp → rename
      ByteArrayOutputStream().use { baos ->
        scaled.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, baos)
        val jpegBytes = baos.toByteArray()
        val tmp = File(coverDir, "$idHash.jpg.tmp")
        try {
          FileOutputStream(tmp).use { fos ->
            fos.write(jpegBytes)
            fos.fd.sync()
          }
        } catch (e: IOException) {
          tmp.delete()
          return ""
        }
        if (coverFile.exists()) {
          coverFile.delete()
        }
        if (!tmp.renameTo(coverFile)) {
          tmp.delete()
          return ""
        }
      }
      return "file://" + coverFile.absolutePath
    } catch (ignored: Throwable) {
      return ""
    } finally {
      if (scaled != null && scaled !== original) scaled.recycle()
      original?.recycle()
    }
  }

  // ── 进度通知 ────────────────────────────────────────────────────────────

  private fun notifyProgress(
    callback: ProgressCallback?,
    total: Int,
    scanned: Int,
    current: String,
  ) {
    callback?.onProgress("scanning", total, scanned, current, null)
  }

  private fun notifyDone(
    callback: ProgressCallback?,
    total: Int,
    scanned: Int,
  ) {
    callback?.onProgress("done", total, scanned, null, null)
  }
}
