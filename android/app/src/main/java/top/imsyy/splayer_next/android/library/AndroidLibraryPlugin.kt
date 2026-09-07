package top.imsyy.splayer_next.android.library

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
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
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 本地音乐库 Capacitor 插件
 * <p>暴露与 Electron 端 `LibraryApi` 对齐的接口，前端通过 `registerPlugin("AndroidLibrary")` 调用。
 * 数据存储在 Java 端 SQLite（[LibraryDatabase]），扫描由 [LibraryScanner] 执行。
 */
@CapacitorPlugin(name = "AndroidLibrary")
class AndroidLibraryPlugin : Plugin() {
  companion object {
    private const val READ_FLAGS =
      Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    private const val READ_WRITE_FLAGS =
      Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
  }

  private val executor: ExecutorService = Executors.newSingleThreadExecutor()
  private var database: LibraryDatabase? = null
  private var scanner: LibraryScanner? = null

  @Volatile
  private var scanning = false

  override fun handleOnDestroy() {
    executor.shutdownNow()
  }

  /** 获取数据库实例（懒加载） */
  private fun getDatabase(): LibraryDatabase {
    if (database == null) {
      database = LibraryDatabase.getInstance(context)
    }
    return database!!
  }

  /** 获取扫描器实例（懒加载） */
  private fun getScanner(): LibraryScanner {
    if (scanner == null) {
      scanner = LibraryScanner(context, getDatabase())
    }
    return scanner!!
  }

  // ── SAF 目录选择 ────────────────────────────────────────────────────────

  /** SAF 选取本地音乐目录，持久化 URI 读写权限，存入 scan_dirs 表 */
  @PluginMethod
  fun pickMusicDirectory(call: PluginCall) {
    if (activity == null) {
      call.reject("Activity unavailable")
      return
    }
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    intent.addFlags(READ_WRITE_FLAGS)
    startActivityForResult(call, intent, "onPickMusicDirectoryResult")
  }

  @ActivityCallback
  private fun onPickMusicDirectoryResult(
    call: PluginCall?,
    result: ActivityResult,
  ) {
    if (call == null) return
    val data = result.data
    val uri = data?.data
    if (uri == null) {
      val cancelled = JSObject()
      cancelled.put("success", false)
      cancelled.put("error", "SCAN_DIR_NOT_SELECTED")
      call.resolve(cancelled)
      return
    }
    try {
      var flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
      if (flags == 0) flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
      context.contentResolver.takePersistableUriPermission(uri, flags)
    } catch (error: SecurityException) {
      val response = JSObject()
      response.put("success", false)
      response.put("error", "LOCAL_MUSIC_DIRECTORY_PERMISSION_FAILED")
      call.resolve(response)
      return
    }
    val directory = DocumentFile.fromTreeUri(context, uri)
    val name = if (directory != null && directory.name != null) directory.name else "Local Music"
    val uriText = uri.toString()
    // 存入 scan_dirs 表
    getDatabase().addScanDir(uriText, name)
    val response = JSObject()
    response.put("success", true)
    response.put("data", uriText)
    response.put("name", name)
    call.resolve(response)
  }

  // ── 扫描 ────────────────────────────────────────────────────────────────

  /** 开始扫描 */
  @PluginMethod
  fun scan(call: PluginCall) {
    if (scanning) {
      val response = JSObject()
      response.put("success", false)
      response.put("error", "ALREADY_SCANNING")
      call.resolve(response)
      return
    }
    val incremental = call.getBoolean("incremental", true)!!
    val dirs = getDatabase().getScanDirs()
    if (dirs.isEmpty()) {
      val response = JSObject()
      response.put("success", false)
      response.put("error", "SCAN_NO_DIRS")
      call.resolve(response)
      return
    }
    scanning = true
    val uriList = ArrayList<String>()
    for (entry in dirs) {
      uriList.add(entry.uri)
    }
    executor.execute {
      try {
        getScanner().scan(
          uriList,
          incremental,
          object : LibraryScanner.ProgressCallback {
            override fun onProgress(
              phase: String,
              total: Int,
              scanned: Int,
              current: String?,
              error: String?,
            ) {
              val progress = JSObject()
              progress.put("phase", phase)
              progress.put("total", total)
              progress.put("scanned", scanned)
              if (current != null) progress.put("current", current)
              if (error != null) progress.put("error", error)
              notifyListeners("library:scanProgress", progress)
              if ("done" == phase || "error" == phase) {
                scanning = false
              }
            }
          },
        )
        val response = JSObject()
        response.put("success", true)
        call.resolve(response)
      } catch (error: Exception) {
        scanning = false
        val progress = JSObject()
        progress.put("phase", "error")
        progress.put("total", 0)
        progress.put("scanned", 0)
        progress.put("error", error.message)
        notifyListeners("library:scanProgress", progress)
        val response = JSObject()
        response.put("success", false)
        response.put("error", "UNKNOWN")
        call.resolve(response)
      }
    }
  }

  /** 取消扫描 */
  @PluginMethod
  fun cancelScan(call: PluginCall) {
    scanner?.cancel()
    scanning = false
    val response = JSObject()
    response.put("success", true)
    call.resolve(response)
  }

  /** 获取扫描状态 */
  @PluginMethod
  fun isScanning(call: PluginCall) {
    val response = JSObject()
    response.put("success", true)
    response.put("data", scanning)
    call.resolve(response)
  }

  // ── 查询方法 ────────────────────────────────────────────────────────────

  /** 获取全部曲目 */
  @PluginMethod
  fun getTracks(call: PluginCall) {
    executor.execute {
      val tracks = JSArray()
      for (row in getDatabase().getAllTracks()) {
        tracks.put(LibraryDatabase.rowToTrackJson(row))
      }
      val response = JSObject()
      response.put("success", true)
      response.put("data", tracks)
      call.resolve(response)
    }
  }

  /** 获取专辑聚合列表 */
  @PluginMethod
  fun getAlbums(call: PluginCall) {
    executor.execute {
      val albums = JSArray()
      for (summary in getDatabase().getAlbumList()) {
        val album = JSObject()
        album.put("name", summary.name)
        val cover = LibraryDatabase.normalizeCoverPath(summary.cover)
        if (cover != null) album.put("cover", cover)
        album.put("artist", summary.artist)
        album.put("trackCount", summary.trackCount)
        albums.put(album)
      }
      val response = JSObject()
      response.put("success", true)
      response.put("data", albums)
      call.resolve(response)
    }
  }

  /** 获取歌手聚合列表 */
  @PluginMethod
  fun getArtists(call: PluginCall) {
    executor.execute {
      val artists = JSArray()
      for (summary in getDatabase().getArtistList()) {
        val artist = JSObject()
        artist.put("name", summary.name)
        artist.put("trackCount", summary.trackCount)
        val cover = LibraryDatabase.normalizeCoverPath(summary.cover)
        if (cover != null) artist.put("cover", cover)
        artists.put(artist)
      }
      val response = JSObject()
      response.put("success", true)
      response.put("data", artists)
      call.resolve(response)
    }
  }

  /** 按专辑名获取全部曲目 */
  @PluginMethod
  fun getAlbumTracks(call: PluginCall) {
    val albumName = call.getString("albumName", "")
    executor.execute {
      val tracks = JSArray()
      for (row in getDatabase().getAlbumTracks(albumName)) {
        tracks.put(LibraryDatabase.rowToTrackJson(row))
      }
      val response = JSObject()
      response.put("success", true)
      response.put("data", tracks)
      call.resolve(response)
    }
  }

  /** 按歌手名获取全部曲目 */
  @PluginMethod
  fun getArtistTracks(call: PluginCall) {
    val artistName = call.getString("artistName", "")
    executor.execute {
      val tracks = JSArray()
      for (row in getDatabase().getArtistTracks(artistName)) {
        tracks.put(LibraryDatabase.rowToTrackJson(row))
      }
      val response = JSObject()
      response.put("success", true)
      response.put("data", tracks)
      call.resolve(response)
    }
  }

  /** 按 ID 批量获取曲目 */
  @PluginMethod
  fun getTracksByIds(call: PluginCall) {
    val idsArray = call.getArray("ids")
    executor.execute {
      val ids = ArrayList<String>()
      if (idsArray != null) {
        for (i in 0 until idsArray.length()) {
          try {
            ids.add(idsArray.getString(i))
          } catch (ignored: JSONException) {
          }
        }
      }
      val tracks = JSArray()
      for (row in getDatabase().getTracksByIds(ids)) {
        tracks.put(LibraryDatabase.rowToTrackJson(row))
      }
      val response = JSObject()
      response.put("success", true)
      response.put("data", tracks)
      call.resolve(response)
    }
  }

  /** 搜索曲目 */
  @PluginMethod
  fun searchTracks(call: PluginCall) {
    val query = call.getString("query", "")
    executor.execute {
      val tracks = JSArray()
      for (row in getDatabase().searchTracks(query)) {
        tracks.put(LibraryDatabase.rowToTrackJson(row))
      }
      val response = JSObject()
      response.put("success", true)
      response.put("data", tracks)
      call.resolve(response)
    }
  }

  /** 获取曲目总数 */
  @PluginMethod
  fun getTrackCount(call: PluginCall) {
    executor.execute {
      val response = JSObject()
      response.put("success", true)
      response.put("data", getDatabase().getTrackCount())
      call.resolve(response)
    }
  }

  /** 随机取一首曲目 */
  @PluginMethod
  fun getRandomTrack(call: PluginCall) {
    executor.execute {
      val row = getDatabase().getRandomTrack()
      val response = JSObject()
      response.put("success", true)
      response.put("data", if (row != null) LibraryDatabase.rowToTrackJson(row) else JSONObject.NULL)
      call.resolve(response)
    }
  }

  /** 随机取多首曲目 */
  @PluginMethod
  fun getRandomTracks(call: PluginCall) {
    val limit = call.getInt("limit", 10)!!
    executor.execute {
      val tracks = JSArray()
      for (row in getDatabase().getRandomTracks(limit)) {
        tracks.put(LibraryDatabase.rowToTrackJson(row))
      }
      val response = JSObject()
      response.put("success", true)
      response.put("data", tracks)
      call.resolve(response)
    }
  }

  // ── 扫描目录管理 ────────────────────────────────────────────────────────

  /** 获取扫描目录列表（URI 字符串数组，与 LibraryApi 接口一致） */
  @PluginMethod
  fun getScanDirs(call: PluginCall) {
    executor.execute {
      val dirs = JSArray()
      for (entry in getDatabase().getScanDirs()) {
        dirs.put(entry.uri)
      }
      val response = JSObject()
      response.put("success", true)
      response.put("data", dirs)
      call.resolve(response)
    }
  }

  /** 获取扫描目录详情（带名称，用于 UI 显示） */
  @PluginMethod
  fun getScanDirDetails(call: PluginCall) {
    executor.execute {
      val dirs = JSArray()
      for (entry in getDatabase().getScanDirs()) {
        val dir = JSObject()
        dir.put("uri", entry.uri)
        dir.put("name", entry.name)
        dirs.put(dir)
      }
      val response = JSObject()
      response.put("success", true)
      response.put("data", dirs)
      call.resolve(response)
    }
  }

  /** 移除扫描目录及其下曲目 */
  @PluginMethod
  fun removeScanDir(call: PluginCall) {
    val dir = call.getString("dir", "")
    if (dir.isNullOrEmpty()) {
      val response = JSObject()
      response.put("success", false)
      response.put("error", "SCAN_DIR_NOT_FOUND")
      call.resolve(response)
      return
    }
    executor.execute {
      // 取消正在进行的扫描
      scanner?.cancel()
      scanning = false
      // 从 scan_dirs 表移除
      getDatabase().removeScanDir(dir)
      // 删除该目录下的所有曲目
      getDatabase().deleteTracksByDir(dir)
      val response = JSObject()
      response.put("success", true)
      call.resolve(response)
    }
  }

  // ── 删除曲目 ────────────────────────────────────────────────────────────

  /** 删除曲目文件并从数据库移除 */
  @PluginMethod
  fun deleteTracks(call: PluginCall) {
    val pathsArray = call.getArray("paths")
    executor.execute {
      val paths = ArrayList<String>()
      if (pathsArray != null) {
        for (i in 0 until pathsArray.length()) {
          try {
            paths.add(pathsArray.getString(i))
          } catch (ignored: JSONException) {
          }
        }
      }
      var deleted = 0
      var failed = 0
      val deletedPaths = ArrayList<String>()
      for (path in paths) {
        try {
          val uri = Uri.parse(path)
          var file: DocumentFile? = null
          if ("content".equals(uri.scheme, ignoreCase = true)) {
            file = DocumentFile.fromSingleUri(context, uri)
          } else if ("file".equals(uri.scheme, ignoreCase = true)) {
            file = DocumentFile.fromFile(File(uri.path!!))
          }
          if (file != null && file.exists() && file.delete()) {
            deletedPaths.add(path)
            deleted++
          } else {
            failed++
          }
        } catch (ignored: Exception) {
          failed++
        }
      }
      // 从数据库移除已删除文件的记录
      if (deletedPaths.isNotEmpty()) {
        getDatabase().deleteTracksByPaths(deletedPaths)
      }
      val data = JSObject()
      data.put("deleted", deleted)
      data.put("failed", failed)
      val response = JSObject()
      response.put("success", true)
      response.put("data", data)
      call.resolve(response)
    }
  }

  // ── 封面图片选择 ────────────────────────────────────────────────────────

  /** SAF 选取封面图片，返回路径与预览 dataUrl */
  @PluginMethod
  fun pickCoverImage(call: PluginCall) {
    if (activity == null) {
      call.reject("Activity unavailable")
      return
    }
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
    intent.addCategory(Intent.CATEGORY_OPENABLE)
    intent.type = "image/*"
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivityForResult(call, intent, "onPickCoverImageResult")
  }

  @ActivityCallback
  private fun onPickCoverImageResult(
    call: PluginCall?,
    result: ActivityResult,
  ) {
    if (call == null) return
    val data = result.data
    val uri = data?.data
    if (uri == null) {
      val response = JSObject()
      response.put("success", false)
      response.put("error", "FILE_NOT_SELECTED")
      call.resolve(response)
      return
    }
    executor.execute {
      try {
        val input = context.contentResolver.openInputStream(uri)
        if (input == null) {
          val response = JSObject()
          response.put("success", false)
          response.put("error", "TAG_WRITE_FAILED")
          call.resolve(response)
          return@execute
        }
        // 读取图片字节
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
          baos.write(buffer, 0, read)
        }
        input.close()
        val imageBytes = baos.toByteArray()
        // 上限 5MB
        if (imageBytes.size > 5 * 1024 * 1024) {
          val response = JSObject()
          response.put("success", false)
          response.put("error", "TAG_WRITE_FAILED")
          call.resolve(response)
          return@execute
        }
        // 压缩为 300px 缩略图
        val thumb = makeThumbnail(imageBytes, 300)
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(thumb, Base64.NO_WRAP)
        val coverData = JSObject()
        coverData.put("path", uri.toString())
        coverData.put("dataUrl", dataUrl)
        val response = JSObject()
        response.put("success", true)
        response.put("data", coverData)
        call.resolve(response)
      } catch (error: Exception) {
        val response = JSObject()
        response.put("success", false)
        response.put("error", "TAG_WRITE_FAILED")
        call.resolve(response)
      }
    }
  }

  /** 将图片字节压缩为指定最大边长的 JPEG 缩略图 */
  private fun makeThumbnail(
    imageBytes: ByteArray,
    maxSize: Int,
  ): ByteArray {
    var original: Bitmap? = null
    var scaled: Bitmap? = null
    try {
      val bounds = BitmapFactory.Options()
      bounds.inJustDecodeBounds = true
      BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
      val decode = BitmapFactory.Options()
      decode.inSampleSize = calculateInSampleSize(bounds, maxSize)
      original = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decode)
      if (original == null) return imageBytes
      val w = original.width
      val h = original.height
      val scale = min(maxSize.toFloat() / w, maxSize.toFloat() / h)
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
      val baos = ByteArrayOutputStream()
      scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
      return baos.toByteArray()
    } catch (ignored: Exception) {
      return imageBytes
    } finally {
      if (scaled != null && scaled !== original) scaled.recycle()
      original?.recycle()
    }
  }

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

  // ── 标签读写 ──────────────────────────────────────────────────────────

  /** 读取本地文件标签（支持 content:// SAF URI） */
  @PluginMethod
  fun readTags(call: PluginCall) {
    val path = call.getString("path")
    if (path.isNullOrBlank()) {
      call.resolve(JSObject().put("success", false).put("error", "TAG_READ_FAILED"))
      return
    }
    executor.execute {
      var tempFile: File? = null
      try {
        tempFile = copyToTempFile(path)
        val audioFile = AudioFileIO.read(tempFile)
        val tag = audioFile.getTagOrCreateAndSetDefault()
        val data = JSObject()
        data.put("title", tag.getFirst(FieldKey.TITLE) ?: "")
        data.put("artist", tag.getFirst(FieldKey.ARTIST) ?: "")
        data.put("album", tag.getFirst(FieldKey.ALBUM) ?: "")
        data.put("albumArtist", tag.getFirst(FieldKey.ALBUM_ARTIST) ?: "")
        data.put("genre", tag.getFirst(FieldKey.GENRE) ?: "")
        val yearStr = tag.getFirst(FieldKey.YEAR)
        if (!yearStr.isNullOrBlank()) {
          try {
            data.put("year", yearStr.toInt())
          } catch (ignored: NumberFormatException) {
          }
        }
        val trackStr = tag.getFirst(FieldKey.TRACK)
        if (!trackStr.isNullOrBlank()) {
          try {
            data.put("trackNumber", trackStr.toInt())
          } catch (ignored: NumberFormatException) {
          }
        }
        val discStr = tag.getFirst(FieldKey.DISC_NO)
        if (!discStr.isNullOrBlank()) {
          try {
            data.put("discNumber", discStr.toInt())
          } catch (ignored: NumberFormatException) {
          }
        }
        // 歌词字段（M4A 用 LYRICS，MP3 用 LYRICS）
        val lyrics = tag.getFirst(FieldKey.LYRICS)
        data.put("lyrics", lyrics ?: "")
        // 是否有内嵌封面
        val coverArt = tag.getFirstArtwork()
        data.put("hasCover", coverArt != null)
        call.resolve(JSObject().put("success", true).put("data", data))
      } catch (error: Exception) {
        call.resolve(JSObject().put("success", false).put("error", "TAG_READ_FAILED"))
      } finally {
        tempFile?.delete()
      }
    }
  }

  /** 写入本地文件标签（支持 content:// SAF URI） */
  @PluginMethod
  fun writeTags(call: PluginCall) {
    val editsArr = call.getArray("edits")
    if (editsArr == null || editsArr.length() == 0) {
      call.resolve(JSObject().put("success", false).put("error", "NO_EDITS"))
      return
    }
    executor.execute {
      val outcomes = JSArray()
      for (i in 0 until editsArr.length()) {
        val edit = editsArr.getJSONObject(i)
        val path = edit.optString("path", "")
        if (path.isBlank()) {
          outcomes.put(JSObject().put("path", "").put("success", false).put("error", "EMPTY_PATH"))
          continue
        }
        try {
          val track = writeSingleTag(edit)
          val outcome = JSObject()
          outcome.put("path", path)
          outcome.put("success", true)
          outcome.put("track", track)
          outcomes.put(outcome)
        } catch (error: Exception) {
          val outcome = JSObject()
          outcome.put("path", path)
          outcome.put("success", false)
          outcome.put("error", error.message ?: "TAG_WRITE_FAILED")
          outcomes.put(outcome)
        }
      }
      call.resolve(JSObject().put("success", true).put("data", outcomes))
    }
  }

  /** 写入单曲标签：复制到临时文件 → jaudiotagger 写入 → 复制回 SAF → 更新数据库 */
  private fun writeSingleTag(edit: JSONObject): JSONObject {
    val path = edit.getString("path")
    // 检查写权限（旧版仅持久化了读权限的目录需要重新选取）
    if (!hasWritePermission(path)) {
      throw SecurityException("WRITABLE_PERMISSION_REQUIRED")
    }
    var tempFile: File? = null
    try {
      tempFile = copyToTempFile(path)
      val audioFile = AudioFileIO.read(tempFile)
      val tag = audioFile.getTagOrCreateAndSetDefault()

      // 文本字段：有 key 表示需要修改
      applyTagIfPresent(edit, "title", tag, FieldKey.TITLE)
      applyTagIfPresent(edit, "artist", tag, FieldKey.ARTIST)
      applyTagIfPresent(edit, "album", tag, FieldKey.ALBUM)
      applyTagIfPresent(edit, "albumArtist", tag, FieldKey.ALBUM_ARTIST)
      applyTagIfPresent(edit, "genre", tag, FieldKey.GENRE)
      applyTagIfPresent(edit, "lyrics", tag, FieldKey.LYRICS)
      if (edit.has("year")) {
        val year = edit.optInt("year", 0)
        if (year > 0) tag.setField(FieldKey.YEAR, year.toString()) else tag.deleteField(FieldKey.YEAR)
      }
      if (edit.has("trackNumber")) {
        val track = edit.optInt("trackNumber", 0)
        if (track > 0) tag.setField(FieldKey.TRACK, track.toString()) else tag.deleteField(FieldKey.TRACK)
      }
      if (edit.has("discNumber")) {
        val disc = edit.optInt("discNumber", 0)
        if (disc > 0) tag.setField(FieldKey.DISC_NO, disc.toString()) else tag.deleteField(FieldKey.DISC_NO)
      }
      // 封面处理
      val coverPath = edit.optString("coverPath", "")
      val coverUrl = edit.optString("coverUrl", "")
      if (coverPath.isNotBlank()) {
        val coverBytes = readCoverBytes(coverPath)
        if (coverBytes != null) {
          tag.deleteArtworkField()
          val artwork = AndroidArtwork()
          artwork.setBinaryData(coverBytes)
          artwork.setMimeType("image/jpeg")
          artwork.setPictureType(3)
          tag.setField(artwork)
        }
      } else if (coverUrl.isNotBlank()) {
        val coverBytes = downloadBytes(coverUrl)
        if (coverBytes != null) {
          tag.deleteArtworkField()
          val artwork = AndroidArtwork()
          artwork.setBinaryData(coverBytes)
          artwork.setMimeType("image/jpeg")
          artwork.setPictureType(3)
          tag.setField(artwork)
        }
      }

      audioFile.commit()

      // 复制回 SAF 目标
      copyTempToSaf(tempFile, path)

      // 更新数据库中的记录
      return updateDatabaseTrack(path, tempFile)
    } finally {
      tempFile?.delete()
    }
  }

  /** 检查对指定 URI 是否拥有写权限（content:// 才需要检查） */
  private fun hasWritePermission(path: String): Boolean {
    if (!path.startsWith("content://")) return true
    return try {
      val uri = Uri.parse(path)
      // 用 DocumentsContract 从 document URI 正确提取 tree URI
      val treeId = DocumentsContract.getTreeDocumentId(uri)
      val treeUri = DocumentsContract.buildTreeDocumentUri(uri.authority, treeId)
      val perms = context.contentResolver.persistedUriPermissions
      val perm = perms.find { it.uri == treeUri }
      perm?.isWritePermission == true
    } catch (ignored: Exception) {
      false
    }
  }

  /** 从 content:// 或 file:// URI 复制到临时文件 */
  private fun copyToTempFile(path: String): File {
    val uri = Uri.parse(path)
    val suffix =
      try {
        path.substringAfterLast(".", "tmp")
      } catch (ignored: Exception) {
        "tmp"
      }
    val tempFile = File.createTempFile("tagedit_", ".$suffix", context.cacheDir)
    context.contentResolver.openInputStream(uri).use { input ->
      if (input == null) throw java.io.IOException("Cannot open input stream for $path")
      tempFile.outputStream().use { output ->
        input.copyTo(output)
      }
    }
    return tempFile
  }

  /** 将临时文件复制回 SAF 目标 */
  private fun copyTempToSaf(
    tempFile: File,
    path: String,
  ) {
    val uri = Uri.parse(path)
    context.contentResolver.openOutputStream(uri, "w").use { output ->
      if (output == null) throw java.io.IOException("Cannot open output stream for $path")
      java.io.FileInputStream(tempFile).use { input ->
        input.copyTo(output)
      }
    }
  }

  /** 读取封面图片字节（支持 content:// 和 file://） */
  private fun readCoverBytes(path: String): ByteArray? {
    return try {
      val uri = Uri.parse(path)
      context.contentResolver.openInputStream(uri).use { input ->
        if (input == null) return null
        val baos = java.io.ByteArrayOutputStream()
        input.copyTo(baos)
        baos.toByteArray()
      }
    } catch (ignored: Exception) {
      null
    }
  }

  /** 下载远程 URL 的字节 */
  private fun downloadBytes(url: String): ByteArray? {
    return try {
      val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
      connection.connectTimeout = 10000
      connection.readTimeout = 10000
      connection.instanceFollowRedirects = true
      if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) return null
      connection.inputStream.use { input ->
        val baos = java.io.ByteArrayOutputStream()
        input.copyTo(baos)
        baos.toByteArray()
      }
    } catch (ignored: Exception) {
      null
    }
  }

  /** 如果 edit 中包含指定 key，则设置 tag 字段 */
  private fun applyTagIfPresent(
    edit: JSONObject,
    key: String,
    tag: org.jaudiotagger.tag.Tag,
    fieldKey: FieldKey,
  ) {
    if (edit.has(key)) {
      val value = edit.optString(key, "")
      if (value.isNotEmpty()) tag.setField(fieldKey, value) else tag.deleteField(fieldKey)
    }
  }

  /** 写入标签后更新数据库记录并返回前端 Track JSON */
  private fun updateDatabaseTrack(
    path: String,
    tempFile: File,
  ): JSONObject {
    val db = getDatabase()
    val id = generateTrackId(path)
    val existing = db.getTracksByIds(listOf(id)).firstOrNull()

    // 用 MediaMetadataRetriever 重新提取元数据
    var title = existing?.title ?: path.substringAfterLast("/").substringBeforeLast(".")
    var artist = existing?.let { parseFirstArtist(it.artistsJson) } ?: "未知歌手"
    var album = existing?.albumJson?.let { parseAlbumName(it) } ?: ""
    var duration = existing?.duration ?: 0L
    var cover = existing?.cover ?: ""

    var retriever: MediaMetadataRetriever? = null
    try {
      retriever = MediaMetadataRetriever()
      retriever.setDataSource(tempFile.absolutePath)
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let {
        if (it.isNotBlank()) title = it.trim()
      }
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let {
        if (it.isNotBlank()) artist = it.trim()
      }
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let {
        if (it.isNotBlank()) album = it.trim()
      }
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.let {
        try {
          duration = it.toLong()
        } catch (ignored: NumberFormatException) {
        }
      }
      // 提取内嵌封面写入缓存
      val embedded = retriever.embeddedPicture
      if (embedded != null) {
        cover = writeEmbeddedCoverToCache(path, embedded)
      }
    } catch (ignored: Exception) {
      // 回退到旧数据
    } finally {
      try {
        retriever?.release()
      } catch (ignored: Exception) {
      }
    }

    // 用 JSONObject/JSONArray 安全构建 JSON（自动转义控制字符）
    val artistsJson = JSONArray().put(JSONObject().put("name", artist)).toString()
    val albumJson = if (album.isNotEmpty()) JSONObject().put("name", album).toString() else null
    val values = ContentValues()
    values.put("id", id)
    values.put("path", path)
    values.put("title", title)
    values.put("artists", artistsJson)
    if (albumJson != null) values.put("album", albumJson) else values.putNull("album")
    values.put("duration", duration)
    if (cover.isNotEmpty()) values.put("cover", cover) else values.putNull("cover")
    // 保留旧记录的音质字段（扫描阶段提取，标签编辑不涉及）
    existing?.codec?.let { values.put("codec", it) } ?: values.putNull("codec")
    existing?.sampleRate?.let { values.put("sample_rate", it) } ?: values.putNull("sample_rate")
    existing?.bitRate?.let { values.put("bit_rate", it) } ?: values.putNull("bit_rate")
    existing?.channels?.let { values.put("channels", it) } ?: values.putNull("channels")
    existing?.bitsPerSample?.let { values.put("bits_per_sample", it) } ?: values.putNull("bits_per_sample")
    // NOT NULL 列
    values.put("file_size", tempFile.length())
    // 与 LibraryScanner 一致：毫秒级时间戳
    val mtime = System.currentTimeMillis()
    values.put("file_mtime", mtime)
    values.put("file_ctime", existing?.fileCtime ?: mtime)
    values.put("scanned_at", existing?.scannedAt ?: System.currentTimeMillis())
    // 文件已写入无法回滚，数据库失败仅记录日志不中断返回
    try {
      db.upsertTracks(listOf(values))
    } catch (dbError: Exception) {
      android.util.Log.w("AndroidLibrary", "DB upsert failed after tag write: ${dbError.message}")
    }

    // 返回前端 Track JSON
    val track = JSONObject()
    track.put("id", id)
    track.put("source", "local")
    track.put("path", path)
    track.put("title", title)
    track.put("artists", JSONArray(artistsJson))
    if (albumJson != null) track.put("album", JSONObject(albumJson))
    track.put("duration", duration)
    if (cover.isNotEmpty()) track.put("cover", cover)
    track.put("fileSize", tempFile.length())
    track.put("mtime", mtime)
    return track
  }

  /** 从 artists JSON 中提取第一个歌手名 */
  private fun parseFirstArtist(artistsJson: String): String =
    try {
      val arr = JSONArray(artistsJson)
      if (arr.length() > 0) {
        arr.getJSONObject(0).optString("name", "未知歌手")
      } else {
        "未知歌手"
      }
    } catch (ignored: Exception) {
      "未知歌手"
    }

  /** 从 album JSON 中提取专辑名 */
  private fun parseAlbumName(albumJson: String): String =
    try {
      JSONObject(albumJson).optString("name", "")
    } catch (ignored: Exception) {
      ""
    }

  /** 生成稳定的 Track ID（与 LibraryScanner 一致：URI 的 SHA-256 哈希前 16 位） */
  private fun generateTrackId(path: String): String = sha256Hex(path).substring(0, 16)

  /** 将内嵌封面字节写入缓存目录（与 LibraryScanner 一致：file:// URI + 完整 hash 命名） */
  private fun writeEmbeddedCoverToCache(
    path: String,
    imageBytes: ByteArray,
  ): String {
    val cacheDir = File(context.cacheDir, "local-covers")
    if (!cacheDir.exists() && !cacheDir.mkdirs()) return ""
    val fullHash = sha256Hex(path)
    if (fullHash.isEmpty()) return ""
    val coverFile = File(cacheDir, "$fullHash.jpg")
    // 已存在则复用（与 LibraryScanner 一致）
    if (coverFile.isFile && coverFile.length() > 0) {
      return "file://" + coverFile.absolutePath
    }
    // 压缩到 1024px 内 JPEG quality 80
    var original: Bitmap? = null
    var scaled: Bitmap? = null
    try {
      val bounds = BitmapFactory.Options()
      bounds.inJustDecodeBounds = true
      BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
      val decode = BitmapFactory.Options()
      decode.inSampleSize = calculateInSampleSize(bounds, 1024)
      original = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decode)
      if (original == null) {
        java.io.FileOutputStream(coverFile).use { it.write(imageBytes) }
        return "file://" + coverFile.absolutePath
      }
      val maxSide = maxOf(original.width, original.height)
      scaled =
        if (maxSide > 1024) {
          val scale = 1024f / maxSide
          Bitmap.createScaledBitmap(original, (original.width * scale).roundToInt(), (original.height * scale).roundToInt(), true)
        } else {
          original
        }
      java.io.FileOutputStream(coverFile).use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
      return "file://" + coverFile.absolutePath
    } catch (ignored: Exception) {
      return ""
    } finally {
      if (scaled != null && scaled !== original) scaled.recycle()
      original?.recycle()
    }
  }

  /** SHA-256 哈希，返回十六进制字符串（与 LibraryScanner 一致：UTF-8 编码） */
  private fun sha256Hex(value: String): String =
    try {
      val digest = java.security.MessageDigest.getInstance("SHA-256")
      val hash = digest.digest(value.toByteArray(StandardCharsets.UTF_8))
      val sb = StringBuilder(hash.size * 2)
      for (b in hash) {
        sb.append(String.format(java.util.Locale.US, "%02x", b))
      }
      sb.toString()
    } catch (ignored: Exception) {
      ""
    }

  /** 获取歌手头像（Android 端暂不支持，返回 null） */
  @PluginMethod
  fun fetchArtistAvatar(call: PluginCall) {
    val response = JSObject()
    response.put("success", true)
    response.put("data", JSONObject.NULL)
    call.resolve(response)
  }

  /** 批量预取歌手头像（Android 端暂不支持，返回空对象） */
  @PluginMethod
  fun prefetchArtistAvatars(call: PluginCall) {
    val response = JSObject()
    response.put("success", true)
    response.put("data", JSObject())
    call.resolve(response)
  }
}
