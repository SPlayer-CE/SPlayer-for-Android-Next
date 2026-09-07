package top.imsyy.splayer_next.android.download

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.documentfile.provider.DocumentFile
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import org.json.JSONObject
import top.imsyy.splayer_next.android.playback.PlaybackManager

@CapacitorPlugin(name = "AndroidDownload")
class AndroidDownloadPlugin : Plugin() {
  companion object {
    private const val READ_FLAGS =
      Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    private const val WRITE_FLAGS =
      Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
  }

  private val executor: ExecutorService = Executors.newFixedThreadPool(2)
  private val activeDownloads = ConcurrentHashMap<Long, PluginCall>()
  private val cancelledTasks = ConcurrentHashMap.newKeySet<String>()
  private val coverWriteLocks = ConcurrentHashMap<String, Any>()

  override fun handleOnDestroy() {
    executor.shutdownNow()
  }

  @PluginMethod
  fun pickDownloadDirectory(call: PluginCall) {
    if (activity == null) {
      call.reject("Activity unavailable")
      return
    }

    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    intent.addFlags(WRITE_FLAGS)
    startActivityForResult(call, intent, "onPickDownloadDirectoryResult")
  }

  @ActivityCallback
  private fun onPickDownloadDirectoryResult(
    call: PluginCall?,
    result: ActivityResult,
  ) {
    if (call == null) return

    val data = result.data
    val uri = data?.data
    if (uri == null) {
      val cancelled = JSObject()
      cancelled.put("cancelled", true)
      call.resolve(cancelled)
      return
    }

    try {
      var flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
      if (flags == 0) flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
      context.contentResolver.takePersistableUriPermission(uri, flags)
    } catch (error: SecurityException) {
      call.reject("DOWNLOAD_DIRECTORY_PERMISSION_FAILED", error)
      return
    }

    val directory = DocumentFile.fromTreeUri(context, uri)
    val response = JSObject()
    response.put("cancelled", false)
    response.put("uri", uri.toString())
    response.put("name", if (directory != null && directory.name != null) directory.name else "Download")
    call.resolve(response)
  }

  @PluginMethod
  fun cancelDownload(call: PluginCall) {
    val taskId = call.getString("taskId", "") ?: ""
    if (taskId.isNotEmpty()) {
      cancelledTasks.add(taskId)
    }
    call.resolve()
  }

  @PluginMethod
  fun downloadFile(call: PluginCall) {
    val taskId = call.getString("taskId", "") ?: ""
    val fileUrl = call.getString("url", "") ?: ""
    val fileName = call.getString("fileName", "") ?: ""
    val directoryUri = call.getString("directoryUri", "") ?: ""
    val subPath = call.getString("subPath", "") ?: ""

    if (fileUrl.isEmpty() || fileName.isEmpty() || directoryUri.isEmpty()) {
      call.reject("url, fileName, and directoryUri are required")
      return
    }

    executor.execute {
      try {
        val dirUri = Uri.parse(directoryUri)
        val directory =
          DocumentFile.fromTreeUri(context, dirUri)
            ?: run {
              call.reject("DOWNLOAD_DIRECTORY_NOT_WRITABLE")
              return@execute
            }
        if (!directory.exists() || !directory.canWrite()) {
          call.reject("DOWNLOAD_DIRECTORY_NOT_WRITABLE")
          return@execute
        }

        // 处理子目录
        var targetDir: DocumentFile = directory
        if (subPath.isNotEmpty()) {
          val parts = subPath.split("/")
          for (part in parts) {
            if (part.isEmpty()) continue
            val existing = findChild(targetDir, part)
            if (existing != null && existing.isDirectory) {
              targetDir = existing
            } else {
              val createdDir = targetDir.createDirectory(part)
              if (createdDir == null) {
                call.reject("FAILED_TO_CREATE_SUBDIRECTORY: $part")
                return@execute
              }
              targetDir = createdDir
            }
          }
        }

        // 检查文件是否已存在
        val extension = getFileExtension(fileName)
        val existingFile = findChild(targetDir, fileName)
        if (existingFile != null && existingFile.exists()) {
          // 如果存在 .downloading 后缀的临时文件说明上一次中断了，不跳过
          val downloadingFile = findChild(targetDir, "$fileName.downloading")
          if (downloadingFile == null || !downloadingFile.exists()) {
            val skipResult = JSObject()
            skipResult.put("status", "skipped")
            skipResult.put("path", existingFile.uri.toString())
            call.resolve(skipResult)
            return@execute
          }
        }

        // 创建临时目标文件
        val mimeType = guessMimeType(extension)
        // 现将原文件（如果有损坏的）删除
        existingFile?.delete()
        findChild(targetDir, "$fileName.downloading")?.delete()

        val targetFile = targetDir.createFile(mimeType, "$fileName.downloading")
        if (targetFile == null) {
          call.reject("FAILED_TO_CREATE_FILE")
          return@execute
        }

        // 下载文件
        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        var output: OutputStream? = null
        try {
          val url = URL(fileUrl)
          connection = url.openConnection() as HttpURLConnection
          connection.connectTimeout = 30000
          connection.readTimeout = 60000
          connection.setRequestProperty("User-Agent", "SPlayer-for-Android")
          connection.connect()

          val responseCode = connection.responseCode
          if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
            targetFile.delete()
            call.reject("HTTP_ERROR_$responseCode")
            return@execute
          }

          val contentLength = connection.contentLength
          input = connection.inputStream
          output = context.contentResolver.openOutputStream(targetFile.uri)

          if (output == null) {
            targetFile.delete()
            call.reject("FAILED_TO_OPEN_OUTPUT_STREAM")
            return@execute
          }

          val buffer = ByteArray(8192)
          var bytesRead: Int
          var totalRead: Long = 0
          var lastReportedPercent = -1

          while (input.read(buffer).also { bytesRead = it } != -1) {
            if (taskId.isNotEmpty() && cancelledTasks.contains(taskId)) {
              throw Exception("DOWNLOAD_CANCELLED")
            }

            output.write(buffer, 0, bytesRead)
            totalRead += bytesRead

            if (contentLength > 0) {
              val percent = ((totalRead * 100) / contentLength).toInt()
              if (percent != lastReportedPercent && percent % 5 == 0) {
                lastReportedPercent = percent
                val progress = JSObject()
                progress.put("taskId", taskId)
                progress.put("bytesRead", totalRead)
                progress.put("contentLength", contentLength)
                progress.put("percent", percent / 100.0)
                notifyListeners("downloadProgress", progress)
              }
            }
          }

          output.flush()
          try {
            output.close()
          } catch (ignored: IOException) {
          }
          output = null

          // 重命名为正式文件
          val finalFile = targetDir.createFile(mimeType, fileName)
          if (finalFile == null) {
            targetFile.delete()
            call.reject("FAILED_TO_RENAME_FILE")
            return@execute
          }

          var renameOutput: OutputStream? = null
          var tempInput: InputStream? = null
          try {
            tempInput = context.contentResolver.openInputStream(targetFile.uri)
            renameOutput = context.contentResolver.openOutputStream(finalFile.uri)
            if (tempInput != null && renameOutput != null) {
              tempInput.copyTo(renameOutput)
            }
          } finally {
            try {
              tempInput?.close()
            } catch (ignored: IOException) {
            }
            try {
              renameOutput?.close()
            } catch (ignored: IOException) {
            }
            targetFile.delete()
          }

          val result = JSObject()
          result.put("status", "success")
          result.put("path", finalFile.uri.toString())
          result.put("fileName", fileName)
          call.resolve(result)
        } finally {
          try {
            input?.close()
          } catch (ignored: IOException) {
          }
          try {
            output?.close()
          } catch (ignored: IOException) {
          }
          connection?.disconnect()
          if (taskId.isNotEmpty()) {
            cancelledTasks.remove(taskId)
          }
        }
      } catch (error: Exception) {
        // 清理可能遗留的下载中文件
        try {
          val dirUri = Uri.parse(directoryUri)
          val directory = DocumentFile.fromTreeUri(context, dirUri)
          if (directory != null) {
            var targetDir: DocumentFile = directory
            if (subPath.isNotEmpty()) {
              val parts = subPath.split("/")
              for (part in parts) {
                if (part.isEmpty()) continue
                val existing = findChild(targetDir, part)
                if (existing != null && existing.isDirectory) {
                  targetDir = existing
                }
              }
            }
            findChild(targetDir, "$fileName.downloading")?.delete()
          }
        } catch (ignored: Exception) {
        }

        if (error.message == "DOWNLOAD_CANCELLED") {
          call.reject("CANCELLED")
        } else {
          call.reject("DOWNLOAD_FAILED", error)
        }
      }
    }
  }

  @PluginMethod
  fun writeTextFile(call: PluginCall) {
    val fileName = call.getString("fileName", "") ?: ""
    val content = call.getString("content", "") ?: ""
    val directoryUri = call.getString("directoryUri", "") ?: ""
    val subPath = call.getString("subPath", "") ?: ""

    if (fileName.isEmpty() || directoryUri.isEmpty()) {
      call.reject("fileName and directoryUri are required")
      return
    }

    executor.execute {
      try {
        val dirUri = Uri.parse(directoryUri)
        val directory =
          DocumentFile.fromTreeUri(context, dirUri)
            ?: run {
              call.reject("DOWNLOAD_DIRECTORY_NOT_WRITABLE")
              return@execute
            }
        if (!directory.exists() || !directory.canWrite()) {
          call.reject("DOWNLOAD_DIRECTORY_NOT_WRITABLE")
          return@execute
        }

        var targetDir: DocumentFile = directory
        if (subPath.isNotEmpty()) {
          val parts = subPath.split("/")
          for (part in parts) {
            if (part.isEmpty()) continue
            val existing = findChild(targetDir, part)
            if (existing != null && existing.isDirectory) {
              targetDir = existing
            } else {
              val createdDir = targetDir.createDirectory(part)
              if (createdDir == null) {
                call.reject("FAILED_TO_CREATE_SUBDIRECTORY")
                return@execute
              }
              targetDir = createdDir
            }
          }
        }

        val extension = getFileExtension(fileName)
        val mimeType = guessMimeType(extension)

        // 删除已存在的文件
        val existingFile = findChild(targetDir, fileName)
        existingFile?.delete()

        val targetFile = targetDir.createFile(mimeType, fileName)
        if (targetFile == null) {
          call.reject("FAILED_TO_CREATE_FILE")
          return@execute
        }

        var output: OutputStream? = null
        try {
          output = context.contentResolver.openOutputStream(targetFile.uri)
          if (output == null) {
            call.reject("FAILED_TO_OPEN_OUTPUT_STREAM")
            return@execute
          }
          output.write(content.toByteArray(StandardCharsets.UTF_8))
          output.flush()

          val result = JSObject()
          result.put("status", "success")
          result.put("path", targetFile.uri.toString())
          call.resolve(result)
        } finally {
          try {
            output?.close()
          } catch (ignored: IOException) {
          }
        }
      } catch (error: Exception) {
        call.reject("WRITE_FILE_FAILED", error)
      }
    }
  }

  /**
   * 保存二进制文件到公共下载目录（MediaStore Downloads，API 29+ 无需权限）。
   * 重名时由 MediaStore 自动追加序号，与桌面端 saveFile 行为对齐。
   */
  @PluginMethod
  fun saveFile(call: PluginCall) {
    val data = call.getString("data", "") ?: ""
    val fileName = call.getString("fileName", "") ?: ""
    if (fileName.isEmpty()) {
      call.reject("fileName is required")
      return
    }
    // data 为空通常是前端编码异常（如空 ArrayBuffer 被 base64 成空串），与参数缺失分开报
    if (data.isEmpty()) {
      call.reject("EMPTY_DATA")
      return
    }

    executor.execute {
      try {
        // 与桌面端 system:saveFile 相同的文件名清洗规则
        val safeName =
          fileName
            .substringAfterLast('/')
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .trim()
        if (safeName.isEmpty() || safeName == "." || safeName == "..") {
          call.reject("INVALID_FILE_NAME")
          return@execute
        }

        val values =
          ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, guessImageMimeType(safeName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
          }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
          call.reject("FAILED_TO_CREATE_FILE")
          return@execute
        }

        try {
          val output =
            resolver.openOutputStream(uri)
              ?: throw IOException("FAILED_TO_OPEN_OUTPUT_STREAM")
          output.use { stream ->
            stream.write(Base64.decode(data, Base64.NO_WRAP))
            stream.flush()
          }
          values.clear()
          values.put(MediaStore.MediaColumns.IS_PENDING, 0)
          resolver.update(uri, values, null, null)
        } catch (error: Exception) {
          resolver.delete(uri, null, null)
          throw error
        }

        // 查回最终文件名（重名被追加序号后与传入名不同）
        var savedName = safeName
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
          if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            if (nameIndex >= 0) savedName = cursor.getString(nameIndex) ?: savedName
          }
        }

        val result = JSObject()
        result.put("status", "success")
        result.put("path", uri.toString())
        result.put("fileName", savedName)
        call.resolve(result)
      } catch (error: Exception) {
        call.reject("SAVE_FILE_FAILED", error)
      }
    }
  }

  private fun guessImageMimeType(fileName: String): String =
    when (getFileExtension(fileName)) {
      "png" -> "image/png"
      "jpg", "jpeg" -> "image/jpeg"
      "webp" -> "image/webp"
      "gif" -> "image/gif"
      else -> "application/octet-stream"
    }

  @PluginMethod
  fun getDownloadDirectoryInfo(call: PluginCall) {
    val directoryUri = call.getString("directoryUri", "") ?: ""
    if (directoryUri.isEmpty()) {
      val result = JSObject()
      result.put("exists", false)
      call.resolve(result)
      return
    }

    try {
      val dirUri = Uri.parse(directoryUri)
      val directory = DocumentFile.fromTreeUri(context, dirUri)
      val result = JSObject()
      result.put("exists", directory != null && directory.exists())
      result.put("canWrite", directory != null && directory.canWrite())
      result.put("name", if (directory != null && directory.name != null) directory.name else "")
      call.resolve(result)
    } catch (error: Exception) {
      val result = JSObject()
      result.put("exists", false)
      call.resolve(result)
    }
  }

  /**
   * 列出下载目录下的全部音乐文件（递归扫描），并提取基础元数据。
   * 用于"下载完成"页面渲染下载历史。
   */
  @PluginMethod
  fun listDownloadedSongs(call: PluginCall) {
    val directoryUri = call.getString("directoryUri", "") ?: ""
    if (directoryUri.isEmpty()) {
      val empty = JSObject()
      empty.put("songs", JSArray())
      call.resolve(empty)
      return
    }

    executor.execute {
      try {
        val dirUri = Uri.parse(directoryUri)
        val directory = DocumentFile.fromTreeUri(context, dirUri)
        if (directory == null || !directory.exists() || !directory.canRead()) {
          val empty = JSObject()
          empty.put("songs", JSArray())
          call.resolve(empty)
          return@execute
        }

        val songs = JSArray()
        scanAudioFiles(directory, songs)

        val response = JSObject()
        response.put("songs", songs)
        call.resolve(response)
      } catch (error: SecurityException) {
        call.reject("DOWNLOAD_DIRECTORY_PERMISSION_EXPIRED", error)
      } catch (error: Exception) {
        call.reject("LIST_DOWNLOADED_SONGS_FAILED", error)
      }
    }
  }

  /**
   * 递归扫描目录中的音乐文件，并提取元数据
   */
  private fun scanAudioFiles(
    directory: DocumentFile,
    output: JSArray,
  ) {
    val children: Array<DocumentFile> =
      try {
        directory.listFiles()
      } catch (ignored: SecurityException) {
        return
      }

    for (child in children) {
      if (child.isDirectory) {
        scanAudioFiles(child, output)
        continue
      }
      if (!child.isFile) continue

      val name = child.name
      if (name == null || !isAudioFile(name)) continue

      val song = buildSongMetadata(child, name)
      if (song != null) output.put(song)
    }
  }

  /**
   * 从 DocumentFile 提取歌曲元数据（艺术家、专辑、时长等）
   */
  private fun buildSongMetadata(
    file: DocumentFile,
    fileName: String,
  ): JSObject? {
    val uri = file.uri.toString()
    val size = file.length()
    val lastModified = file.lastModified()

    // 默认从文件名解析（"Artist - Title.ext" 或 "Title.ext"）
    var baseName = fileName
    val dotIdx = baseName.lastIndexOf('.')
    val extension = if (dotIdx > 0) baseName.substring(dotIdx + 1).lowercase() else ""
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
    var album = "未知专辑"
    var duration = 0L
    var bitrate = 0L
    var cover = ""

    // 尝试用 MediaMetadataRetriever 读取标签信息
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

      // 提取嵌入封面，压缩后写入 cacheDir/local-covers/<idHash>.jpg 并返回 file:// URI。
      // 用 file:// 而非 base64 data URL：避免上千首歌每首 ~30KB 字符串经 Capacitor 桥响应造成 IPC 肨胀 / OOM。
      val embeddedPicture = retriever.embeddedPicture
      if (embeddedPicture != null) {
        cover = writeEmbeddedCoverToCache(uri, embeddedPicture)
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
    val id = abs(uri.hashCode().toLong()) + 1_000_000_000L

    val song = JSObject()
    song.put("id", id)
    song.put("name", title)
    song.put("artists", artist)
    song.put("album", album)
    song.put("duration", duration)
    song.put("size", size)
    song.put("quality", bitrate)
    song.put("path", uri)
    song.put("fileName", fileName)
    song.put("ext", extension)
    song.put("lastModified", lastModified)
    song.put("cover", cover)
    return song
  }

  /**
   * 嵌入封面落盘：按源 URI hash 成名，写入 `cacheDir/local-covers/`。返回 file:// URI。
   * 已存在则复用。压缩到 1024px 内 JPEG quality 80。完全失败返回 ""。
   */
  private fun writeEmbeddedCoverToCache(
    sourceUri: String,
    pictureBytes: ByteArray,
  ): String {
    val ctx = context ?: return ""
    var original: Bitmap? = null
    var scaled: Bitmap? = null
    try {
      val coverDir = File(ctx.cacheDir, "local-covers")
      if (!coverDir.exists() && !coverDir.mkdirs()) {
        return ""
      }
      val idHash = sha256Hex(sourceUri)
      if (idHash.isEmpty()) return ""
      val coverFile = File(coverDir, "$idHash.jpg")
      val writeLock = coverWriteLocks.computeIfAbsent(idHash) { Any() }
      try {
        synchronized(writeLock) {
          if (coverFile.isFile && coverFile.length() > 0) {
            return "file://" + coverFile.absolutePath
          }
          val boundsOptions = BitmapFactory.Options()
          boundsOptions.inJustDecodeBounds = true
          BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.size, boundsOptions)
          val maxSize = 1024
          val decodeOptions = BitmapFactory.Options()
          decodeOptions.inSampleSize = calculateInSampleSize(boundsOptions, maxSize)
          original = BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.size, decodeOptions)
          if (original == null) return ""
          val w = original!!.width
          val h = original!!.height
          val scale = min(maxSize.toFloat() / w, maxSize.toFloat() / h)
          scaled = original
          if (scale < 1.0f) {
            scaled = Bitmap.createScaledBitmap(original!!, (w * scale).roundToInt(), (h * scale).roundToInt(), true)
          }
          ByteArrayOutputStream().use { baos ->
            scaled!!.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val jpegBytes = baos.toByteArray()
            // 原子写：tmp → rename，避免进程被杀产生半截断文件
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
        }
      } finally {
        coverWriteLocks.remove(idHash, writeLock)
      }
      return "file://" + coverFile.absolutePath
    } catch (ignored: Throwable) {
      return ""
    } finally {
      if (scaled != null && scaled !== original) {
        scaled!!.recycle()
      }
      original?.recycle()
    }
  }

  private fun sha256Hex(value: String): String =
    try {
      val digest = MessageDigest.getInstance("SHA-256")
      val hash = digest.digest(value.toByteArray(StandardCharsets.UTF_8))
      val builder = StringBuilder(hash.size * 2)
      for (b in hash) {
        builder.append(String.format(java.util.Locale.US, "%02x", b))
      }
      builder.toString()
    } catch (ignored: Exception) {
      ""
    }

  private fun calculateInSampleSize(
    options: BitmapFactory.Options,
    maxSize: Int,
  ): Int {
    val height = options.outHeight
    val width = options.outWidth
    if (height <= 0 || width <= 0) return maxSize
    var inSampleSize = 1
    while (height / inSampleSize > maxSize || width / inSampleSize > maxSize) {
      inSampleSize *= 2
    }
    return inSampleSize
  }

  private fun isAudioFile(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".mp3") ||
      lower.endsWith(".flac") ||
      lower.endsWith(".wav") ||
      lower.endsWith(".ogg") ||
      lower.endsWith(".m4a") ||
      lower.endsWith(".aac") ||
      lower.endsWith(".ape") ||
      lower.endsWith(".wma") ||
      lower.endsWith(".opus")
  }

  /**
   * SAF 选取本地音乐目录（与下载目录使用相同 ACTION_OPEN_DOCUMENT_TREE）
   */
  @PluginMethod
  fun pickLocalMusicDirectory(call: PluginCall) {
    if (activity == null) {
      call.reject("Activity unavailable")
      return
    }

    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    intent.addFlags(READ_FLAGS)
    startActivityForResult(call, intent, "onPickLocalMusicDirectoryResult")
  }

  @ActivityCallback
  private fun onPickLocalMusicDirectoryResult(
    call: PluginCall?,
    result: ActivityResult,
  ) {
    if (call == null) return

    val data = result.data
    val uri = data?.data
    if (uri == null) {
      val cancelled = JSObject()
      cancelled.put("cancelled", true)
      call.resolve(cancelled)
      return
    }

    try {
      var flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
      if (flags == 0) flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
      context.contentResolver.takePersistableUriPermission(uri, flags)
    } catch (error: SecurityException) {
      call.reject("LOCAL_MUSIC_DIRECTORY_PERMISSION_FAILED", error)
      return
    }

    val directory = DocumentFile.fromTreeUri(context, uri)
    val response = JSObject()
    response.put("cancelled", false)
    response.put("uri", uri.toString())
    response.put("name", if (directory != null && directory.name != null) directory.name else "Local Music")
    call.resolve(response)
  }

  /**
   * 扫描本地音乐目录列表（多目录递归），返回歌曲数组
   */
  @PluginMethod
  fun scanLocalMusic(call: PluginCall) {
    val directories = call.getArray("directories")
    if (directories == null || directories.length() == 0) {
      val empty = JSObject()
      empty.put("songs", JSArray())
      call.resolve(empty)
      return
    }

    executor.execute {
      val songs = JSArray()
      var failedDirs = 0

      for (i in 0 until directories.length()) {
        try {
          val item = directories.get(i)
          val uriText =
            when (item) {
              is JSObject -> item.getString("uri", "") ?: ""
              is JSONObject -> item.optString("uri", "")
              else -> item.toString()
            }
          if (uriText.isEmpty()) {
            failedDirs++
            continue
          }

          val uri = Uri.parse(uriText)
          val directory = DocumentFile.fromTreeUri(context, uri)
          if (directory == null || !directory.exists() || !directory.canRead()) {
            failedDirs++
            continue
          }
          scanAudioFiles(directory, songs)
        } catch (error: SecurityException) {
          failedDirs++
        } catch (error: Exception) {
          failedDirs++
        }
      }

      val response = JSObject()
      response.put("songs", songs)
      response.put("failedDirectories", failedDirs)
      call.resolve(response)
    }
  }

  private fun findChild(
    parent: DocumentFile,
    name: String,
  ): DocumentFile? {
    for (child in parent.listFiles()) {
      if (name == child.name) return child
    }
    return null
  }

  private fun getFileExtension(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    return if (dot > 0) fileName.substring(dot + 1).lowercase() else ""
  }

  private fun guessMimeType(extension: String): String =
    when (extension) {
      "mp3" -> "audio/mpeg"
      "flac" -> "audio/flac"
      "wav" -> "audio/wav"
      "ogg" -> "audio/ogg"
      "m4a" -> "audio/mp4"
      "aac" -> "audio/aac"
      "lrc", "yrc", "ass" -> "text/plain"
      "ttml" -> "application/xml"
      "json" -> "application/json"
      else -> "application/octet-stream"
    }

  /**
   * 将封面/元信息/歌词内嵌到已下载音频文件的标签中。
   * SAF 不支持直接随机写，需先拷贝到临时 java.io.File，写标签后覆盖回 SAF。
   */
  @PluginMethod
  fun embedTags(call: PluginCall) {
    val filePath = call.getString("filePath", "") ?: ""
    val coverUrl = call.getString("coverUrl", "") ?: ""
    val title = call.getString("title", "") ?: ""
    val artist = call.getString("artist", "") ?: ""
    val album = call.getString("album", "") ?: ""
    val lyrics = call.getString("lyrics", "") ?: ""
    val embedCover = call.getBoolean("embedCover", false) ?: false
    val embedMeta = call.getBoolean("embedMeta", false) ?: false
    val embedLyric = call.getBoolean("embedLyric", false) ?: false

    if (filePath.isEmpty()) {
      call.reject("filePath is required")
      return
    }

    executor.execute {
      var tempFile: File? = null
      try {
        // SAF → 临时文件，JAudioTagger 需要随机读写
        tempFile = File.createTempFile("embed_", ".tmp", context.cacheDir)
        context.contentResolver.openInputStream(Uri.parse(filePath)).use { input ->
          if (input == null) {
            call.reject("FAILED_TO_OPEN_SOURCE_FILE")
            return@execute
          }
          FileOutputStream(tempFile).use { output ->
            input.copyTo(output)
          }
        }

        // 下载封面
        var coverBytes: ByteArray? = null
        if (embedCover && coverUrl.isNotEmpty()) {
          coverBytes = downloadCoverBytes(coverUrl)
          if (coverBytes == null) {
            Log.w("AndroidDownload", "封面下载失败，跳过封面: $coverUrl")
          }
        }

        // JAudioTagger 写标签
        val audioFile = AudioFileIO.read(tempFile)
        val tag = audioFile.getTagOrCreateAndSetDefault()

        if (embedMeta) {
          if (title.isNotEmpty()) tag.setField(FieldKey.TITLE, title)
          if (artist.isNotEmpty()) tag.setField(FieldKey.ARTIST, artist)
          if (album.isNotEmpty()) tag.setField(FieldKey.ALBUM, album)
        }
        if (embedLyric && lyrics.isNotEmpty()) {
          tag.setField(FieldKey.LYRICS, lyrics)
        }
        if (coverBytes != null) {
          tag.deleteArtworkField()
          val artwork = AndroidArtwork()
          artwork.setBinaryData(coverBytes)
          artwork.setMimeType("image/jpeg")
          artwork.setPictureType(3)
          tag.setField(artwork)
        }

        audioFile.commit()

        // 覆盖回 SAF
        context.contentResolver.openOutputStream(Uri.parse(filePath), "wt").use { output ->
          if (output == null) {
            call.reject("FAILED_TO_WRITE_TAGGED_FILE")
            return@execute
          }
          FileInputStream(tempFile).use { input ->
            input.copyTo(output)
          }
          output.flush()
        }

        val result = JSObject()
        result.put("status", "success")
        call.resolve(result)
      } catch (error: Exception) {
        call.reject("EMBED_TAGS_FAILED", error)
      } finally {
        tempFile?.delete()
      }
    }
  }

  /** 下载封面图片字节，失败返回 null */
  private fun downloadCoverBytes(coverUrl: String): ByteArray? {
    if (!coverUrl.startsWith("http://") && !coverUrl.startsWith("https://")) return null
    var connection: HttpURLConnection? = null
    return try {
      val url = URL(coverUrl)
      connection = url.openConnection() as HttpURLConnection
      connection.connectTimeout = 15000
      connection.readTimeout = 15000
      connection.setRequestProperty("User-Agent", "SPlayer-for-Android")
      connection.connect()
      if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
      connection.inputStream.readBytes()
    } catch (e: Exception) {
      null
    } finally {
      connection?.disconnect()
    }
  }

  @PluginMethod
  fun resolveDownloadUrl(call: PluginCall) {
    val songId = call.getLong("songId", 0) ?: 0L
    val usePlayback = call.getBoolean("usePlayback", false) ?: false
    if (songId <= 0) {
      call.reject("songId is required and must be > 0")
      return
    }
    val manager = PlaybackManager.getInstance(context)
    val result = manager.resolveDownloadUrl(songId, usePlayback)
    if (result != null) {
      val response = JSObject()
      response.put("url", result.url)
      if (result.format != null) {
        response.put("format", result.format)
      }
      if (result.size > 0) {
        response.put("size", result.size)
      }
      call.resolve(response)
    } else {
      call.resolve(JSObject())
    }
  }
}
