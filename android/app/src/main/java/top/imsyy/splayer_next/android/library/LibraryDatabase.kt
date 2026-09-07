package top.imsyy.splayer_next.android.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import java.io.File
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 本地音乐库 SQLite 数据库
 * <p>schema 与 Electron 端 `electron/main/database/index.ts` 的 tracks 表对齐，
 * 保证两端数据结构一致。Android 端独立管理数据库文件，不与嵌入式 Node.js 共享。
 */
class LibraryDatabase private constructor(
  context: Context,
) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
  companion object {
    private const val DB_NAME = "library.db"
    private const val DB_VERSION = 1

    // ── 表结构 ──────────────────────────────────────────────────────────────

    private const val SQL_CREATE_TRACKS =
      "CREATE TABLE IF NOT EXISTS tracks (" +
        "id TEXT PRIMARY KEY," +
        "path TEXT NOT NULL UNIQUE," +
        "title TEXT NOT NULL," +
        "artists TEXT NOT NULL DEFAULT '[]'," +
        "album TEXT," +
        "duration INTEGER NOT NULL," +
        "cover TEXT," +
        "codec TEXT," +
        "sample_rate INTEGER," +
        "bit_rate INTEGER," +
        "channels INTEGER," +
        "bits_per_sample INTEGER," +
        "file_size INTEGER NOT NULL," +
        "file_mtime INTEGER," +
        "file_ctime INTEGER," +
        "scanned_at INTEGER NOT NULL" +
        ")"
    private const val SQL_CREATE_IDX_TITLE =
      "CREATE INDEX IF NOT EXISTS idx_tracks_title ON tracks(title)"
    private const val SQL_CREATE_IDX_ALBUM =
      "CREATE INDEX IF NOT EXISTS idx_tracks_album ON tracks(album)"

    private const val SQL_CREATE_SCAN_DIRS =
      "CREATE TABLE IF NOT EXISTS scan_dirs (" +
        "uri TEXT PRIMARY KEY," +
        "name TEXT NOT NULL," +
        "added_at INTEGER NOT NULL" +
        ")"

    @Volatile
    private var instance: LibraryDatabase? = null

    /** 获取单例实例 */
    @JvmStatic
    fun getInstance(context: Context): LibraryDatabase =
      instance ?: synchronized(this) {
        instance ?: LibraryDatabase(context.applicationContext).also { instance = it }
      }

    /** 将 TrackRow 转为前端 Track 格式的 JSONObject */
    @JvmStatic
    fun rowToTrackJson(row: TrackRow): JSONObject {
      val track = JSONObject()
      try {
        track.put("id", row.id)
        track.put("source", "local")
        track.put("path", row.path)
        track.put("title", row.title)
        track.put("artists", JSONArray(row.artistsJson))
        row.albumJson?.let { albumJson ->
          track.put("album", JSONObject(albumJson))
        }
        track.put("duration", row.duration)
        val cover = normalizeCoverPath(row.cover)
        if (cover != null) track.put("cover", cover)
        track.put("fileSize", row.fileSize)
        if (row.fileMtime != null) track.put("mtime", row.fileMtime)
        if (row.fileCtime != null) track.put("ctime", row.fileCtime)

        // 音质信息
        if (row.codec != null) {
          val quality = JSONObject()
          quality.put("codec", row.codec)
          quality.put("sampleRate", row.sampleRate ?: 0L)
          quality.put("bitRate", row.bitRate ?: 0L)
          quality.put("channels", row.channels ?: 0L)
          quality.put("bitsPerSample", row.bitsPerSample ?: 0L)
          track.put("quality", quality)
        }
      } catch (error: JSONException) {
        // 不会发生：所有 key 都是字面量
      }
      return track
    }

    /**
     * 过滤已失效的本地封面路径，避免缓存目录被系统清理后前端持续请求 404。
     */
    @JvmStatic
    fun normalizeCoverPath(cover: String?): String? {
      if (cover.isNullOrBlank()) return null
      return try {
        val uri = Uri.parse(cover)
        if (uri.scheme == "file") {
          val path = uri.path ?: return null
          if (!File(path).isFile) return null
        }
        cover
      } catch (_: Exception) {
        null
      }
    }

    // ── JSON 辅助方法 ──────────────────────────────────────────────────────

    /** 从 album JSON 提取 name 字段 */
    private fun extractAlbumName(albumJson: String?): String? {
      if (albumJson.isNullOrEmpty()) return null
      return try {
        val album = JSONObject(albumJson)
        album.optString("name").takeIf { it.isNotEmpty() }
      } catch (ignored: JSONException) {
        null
      }
    }

    /** 从 artists JSON 数组提取歌手名字符串（用 " / " 连接） */
    private fun extractArtistNames(artistsJson: String?): String {
      val names = extractAllArtistNames(artistsJson)
      return if (names.isEmpty()) "" else names.joinToString(" / ")
    }

    /** 从 artists JSON 数组提取全部歌手名 */
    private fun extractAllArtistNames(artistsJson: String?): List<String> {
      val names = ArrayList<String>()
      if (artistsJson.isNullOrEmpty()) return names
      try {
        val artists = JSONArray(artistsJson)
        for (i in 0 until artists.length()) {
          val artist = artists.optJSONObject(i)
          if (artist != null) {
            val name = artist.optString("name", "")
            if (name.trim().isNotEmpty()) names.add(name.trim())
          }
        }
      } catch (ignored: JSONException) {
        // 解析失败返回空列表
      }
      return names
    }

    /** 判断 artists JSON 数组中是否包含指定歌手名（大小写不敏感） */
    private fun containsArtist(
      artistsJson: String?,
      lowerTarget: String?,
    ): Boolean {
      if (lowerTarget.isNullOrEmpty()) return false
      for (name in extractAllArtistNames(artistsJson)) {
        if (name.lowercase() == lowerTarget) return true
      }
      return false
    }

    /** 构建 SQL 占位符字符串 */
    private fun buildPlaceholders(count: Int): String {
      val sb = StringBuilder(count * 2)
      for (i in 0 until count) {
        if (i > 0) sb.append(",")
        sb.append("?")
      }
      return sb.toString()
    }
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(SQL_CREATE_TRACKS)
    db.execSQL(SQL_CREATE_IDX_TITLE)
    db.execSQL(SQL_CREATE_IDX_ALBUM)
    db.execSQL(SQL_CREATE_SCAN_DIRS)
  }

  override fun onUpgrade(
    db: SQLiteDatabase,
    oldVersion: Int,
    newVersion: Int,
  ) {
    // 后续版本升级时在此处添加迁移逻辑
  }

  // ── Track 数据行 ────────────────────────────────────────────────────────

  /** 数据库行对应的 Track 数据结构 */
  class TrackRow {
    lateinit var id: String
    lateinit var path: String
    lateinit var title: String
    lateinit var artistsJson: String
    var albumJson: String? = null
    var duration: Long = 0
    var cover: String? = null
    var codec: String? = null
    var sampleRate: Long? = null
    var bitRate: Long? = null
    var channels: Long? = null
    var bitsPerSample: Long? = null
    var fileSize: Long = 0
    var fileMtime: Long? = null
    var fileCtime: Long? = null
    var scannedAt: Long = 0
  }

  /** 从 Cursor 解析一行 */
  private fun cursorToRow(cursor: Cursor): TrackRow {
    val row = TrackRow()
    row.id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
    row.path = cursor.getString(cursor.getColumnIndexOrThrow("path"))
    row.title = cursor.getString(cursor.getColumnIndexOrThrow("title"))
    row.artistsJson = cursor.getString(cursor.getColumnIndexOrThrow("artists"))
    val albumIdx = cursor.getColumnIndex("album")
    row.albumJson = if (albumIdx >= 0) cursor.getString(albumIdx) else null
    row.duration = cursor.getLong(cursor.getColumnIndexOrThrow("duration"))
    val coverIdx = cursor.getColumnIndex("cover")
    row.cover = if (coverIdx >= 0) cursor.getString(coverIdx) else null
    val codecIdx = cursor.getColumnIndex("codec")
    row.codec = if (codecIdx >= 0) cursor.getString(codecIdx) else null
    row.sampleRate = getNullableLong(cursor, "sample_rate")
    row.bitRate = getNullableLong(cursor, "bit_rate")
    row.channels = getNullableLong(cursor, "channels")
    row.bitsPerSample = getNullableLong(cursor, "bits_per_sample")
    row.fileSize = cursor.getLong(cursor.getColumnIndexOrThrow("file_size"))
    row.fileMtime = getNullableLong(cursor, "file_mtime")
    row.fileCtime = getNullableLong(cursor, "file_ctime")
    row.scannedAt = cursor.getLong(cursor.getColumnIndexOrThrow("scanned_at"))
    return row
  }

  private fun getNullableLong(
    cursor: Cursor,
    columnName: String,
  ): Long? {
    val idx = cursor.getColumnIndex(columnName)
    if (idx < 0 || cursor.isNull(idx)) return null
    return cursor.getLong(idx)
  }

  // ── 查询方法 ────────────────────────────────────────────────────────────

  /** 查询全部曲目 */
  fun getAllTracks(): List<TrackRow> {
    val db = readableDatabase
    val result = ArrayList<TrackRow>()
    db.rawQuery("SELECT * FROM tracks", null).use { cursor ->
      while (cursor.moveToNext()) {
        result.add(cursorToRow(cursor))
      }
    }
    return result
  }

  /** 获取曲目总数 */
  fun getTrackCount(): Int {
    val db = readableDatabase
    db.rawQuery("SELECT COUNT(*) FROM tracks", null).use { cursor ->
      if (cursor.moveToFirst()) return cursor.getInt(0)
    }
    return 0
  }

  /** 随机取一首曲目，库为空时返回 null */
  fun getRandomTrack(): TrackRow? {
    val db = readableDatabase
    db.rawQuery("SELECT * FROM tracks ORDER BY RANDOM() LIMIT 1", null).use { cursor ->
      if (cursor.moveToFirst()) return cursorToRow(cursor)
    }
    return null
  }

  /** 随机取多首曲目 */
  fun getRandomTracks(limit: Int): List<TrackRow> {
    val safe = limit.coerceIn(0, 500)
    val result = ArrayList<TrackRow>()
    if (safe == 0) return result
    val db = readableDatabase
    db
      .rawQuery(
        "SELECT * FROM tracks ORDER BY RANDOM() LIMIT ?",
        arrayOf(safe.toString()),
      ).use { cursor ->
        while (cursor.moveToNext()) {
          result.add(cursorToRow(cursor))
        }
      }
    return result
  }

  /** 按 ID 批量获取曲目 */
  fun getTracksByIds(ids: List<String>?): List<TrackRow> {
    val result = ArrayList<TrackRow>()
    if (ids.isNullOrEmpty()) return result
    val db = readableDatabase
    // 分批查询，避免 SQL 参数过多
    val batchSize = 500
    for (start in ids.indices step batchSize) {
      val end = kotlin.math.min(start + batchSize, ids.size)
      val batch = ids.subList(start, end)
      val placeholders = buildPlaceholders(batch.size)
      val args = batch.toTypedArray()
      db
        .rawQuery(
          "SELECT * FROM tracks WHERE id IN ($placeholders)",
          args,
        ).use { cursor ->
          while (cursor.moveToNext()) {
            result.add(cursorToRow(cursor))
          }
        }
    }
    return result
  }

  /** 模糊搜索曲目（title / artists / album） */
  fun searchTracks(query: String?): List<TrackRow> {
    val result = ArrayList<TrackRow>()
    if (query.isNullOrBlank()) return result
    val pattern = "%${query.trim()}%"
    val db = readableDatabase
    db
      .rawQuery(
        "SELECT * FROM tracks WHERE title LIKE ? OR artists LIKE ? OR album LIKE ?",
        arrayOf(pattern, pattern, pattern),
      ).use { cursor ->
        while (cursor.moveToNext()) {
          result.add(cursorToRow(cursor))
        }
      }
    return result
  }

  /** 按专辑名获取全部曲目 */
  fun getAlbumTracks(albumName: String?): List<TrackRow> {
    val result = ArrayList<TrackRow>()
    if (albumName == null) return result
    val db = readableDatabase
    db.rawQuery("SELECT * FROM tracks WHERE album IS NOT NULL", null).use { cursor ->
      while (cursor.moveToNext()) {
        val row = cursorToRow(cursor)
        val albumDisplay = extractAlbumName(row.albumJson)
        if (albumName == albumDisplay) {
          result.add(row)
        }
      }
    }
    return result
  }

  /** 按歌手名获取全部曲目（大小写不敏感） */
  fun getArtistTracks(artistName: String?): List<TrackRow> {
    val result = ArrayList<TrackRow>()
    if (artistName == null) return result
    val lowerTarget = artistName.trim().lowercase()
    val db = readableDatabase
    db.rawQuery("SELECT * FROM tracks", null).use { cursor ->
      while (cursor.moveToNext()) {
        val row = cursorToRow(cursor)
        if (containsArtist(row.artistsJson, lowerTarget)) {
          result.add(row)
        }
      }
    }
    return result
  }

  // ── 聚合查询 ────────────────────────────────────────────────────────────

  /** 专辑聚合项 */
  class AlbumSummary {
    lateinit var name: String
    var cover: String? = null
    lateinit var artist: String
    var trackCount: Int = 0
  }

  /** 专辑列表（对齐 Electron 端 getAlbumList） */
  fun getAlbumList(): List<AlbumSummary> {
    val db = readableDatabase
    // 按 album name 分组：取第一个非空 cover、第一个 artists、计数
    val map = LinkedHashMap<String, AlbumSummary>()
    db
      .rawQuery(
        "SELECT * FROM tracks WHERE album IS NOT NULL ORDER BY scanned_at",
        null,
      ).use { cursor ->
        while (cursor.moveToNext()) {
          val row = cursorToRow(cursor)
          val albumName = extractAlbumName(row.albumJson)
          if (albumName.isNullOrEmpty()) continue
          var summary = map[albumName]
          if (summary == null) {
            summary = AlbumSummary()
            summary.name = albumName
            summary.cover = row.cover
            summary.artist = extractArtistNames(row.artistsJson)
            summary.trackCount = 1
            map[albumName] = summary
          } else {
            summary.trackCount++
            if (summary.cover == null && row.cover != null) {
              summary.cover = row.cover
            }
          }
        }
      }
    return ArrayList(map.values)
  }

  /** 歌手聚合项 */
  class ArtistSummary {
    lateinit var name: String
    var cover: String? = null
    var trackCount: Int = 0
  }

  /** 歌手列表（对齐 Electron 端 getArtistList） */
  fun getArtistList(): List<ArtistSummary> {
    val db = readableDatabase
    // 按 artist name 分组（小写 key 去重），统计 trackCount，取第一个非空 cover
    val map = LinkedHashMap<String, ArtistSummary>()
    db.rawQuery("SELECT * FROM tracks ORDER BY scanned_at", null).use { cursor ->
      while (cursor.moveToNext()) {
        val row = cursorToRow(cursor)
        val artistNames = extractAllArtistNames(row.artistsJson)
        for (name in artistNames) {
          if (name.trim().isEmpty()) continue
          val key = name.trim().lowercase()
          var summary = map[key]
          if (summary == null) {
            summary = ArtistSummary()
            summary.name = name.trim()
            summary.cover = row.cover
            summary.trackCount = 1
            map[key] = summary
          } else {
            summary.trackCount++
            if (summary.cover == null && row.cover != null) {
              summary.cover = row.cover
            }
          }
        }
      }
    }
    return ArrayList(map.values)
  }

  // ── 写入方法 ────────────────────────────────────────────────────────────

  /** 批量插入/更新曲目（事务） */
  fun upsertTracks(tracks: List<ContentValues>?) {
    if (tracks.isNullOrEmpty()) return
    val db = writableDatabase
    db.beginTransaction()
    try {
      for (values in tracks) {
        db.insertWithOnConflict("tracks", null, values, SQLiteDatabase.CONFLICT_REPLACE)
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  /** 批量删除曲目（按路径） */
  fun deleteTracksByPaths(paths: List<String>?): Int {
    if (paths.isNullOrEmpty()) return 0
    val db = writableDatabase
    var deleted = 0
    db.beginTransaction()
    try {
      for (path in paths) {
        deleted += db.delete("tracks", "path = ?", arrayOf(path))
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    return deleted
  }

  /** 删除指定目录下的所有曲目（按路径前缀匹配） */
  fun deleteTracksByDir(dirPrefix: String?): Int {
    if (dirPrefix.isNullOrEmpty()) return 0
    val prefix = if (dirPrefix.endsWith("/")) dirPrefix else "$dirPrefix/"
    val db = writableDatabase
    return db.delete("tracks", "path LIKE ?", arrayOf("$prefix%"))
  }

  // ── 扫描目录管理 ────────────────────────────────────────────────────────

  /** 扫描目录项 */
  class ScanDirEntry(
    val uri: String,
    val name: String,
  )

  /** 获取全部扫描目录 */
  fun getScanDirs(): List<ScanDirEntry> {
    val result = ArrayList<ScanDirEntry>()
    val db = readableDatabase
    db.rawQuery("SELECT uri, name FROM scan_dirs ORDER BY added_at", null).use { cursor ->
      while (cursor.moveToNext()) {
        result.add(ScanDirEntry(cursor.getString(0), cursor.getString(1)))
      }
    }
    return result
  }

  /** 添加扫描目录 */
  fun addScanDir(
    uri: String?,
    name: String?,
  ) {
    if (uri.isNullOrEmpty()) return
    val db = writableDatabase
    val values = ContentValues()
    values.put("uri", uri)
    values.put("name", name ?: "")
    values.put("added_at", System.currentTimeMillis())
    db.insertWithOnConflict("scan_dirs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
  }

  /** 移除扫描目录 */
  fun removeScanDir(uri: String?) {
    if (uri.isNullOrEmpty()) return
    val db = writableDatabase
    db.delete("scan_dirs", "uri = ?", arrayOf(uri))
  }
}
