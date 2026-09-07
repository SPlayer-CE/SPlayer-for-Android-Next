package top.imsyy.splayer_next.android.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 歌词缓存 SQLite 数据库
 *
 * <p>schema 与 Electron 端 `electron/main/database/lyricCache.ts`、`lyricTtmlCache.ts`、
 * `lyricMatchCache.ts` 三表对齐，保证两端数据结构一致。
 *
 * <p>三张表：
 * <ul>
 *   <li>`lyric_cache` —— 平台歌词接口返回的完整 JSON，PK (platform, platform_id)
 *   <li>`lyric_ttml_cache` —— AMLL TTML DB 文本，含 72h 负缓存 TTL，PK (platform, id)
 *   <li>`lyric_match_cache` —— fuzzy 匹配指纹 → 平台 ID 映射，30 天 TTL，PK (fingerprint, platform)
 * </ul>
 *
 * <p>数据库文件位于 `{filesDir}/splayer-cache.db`，App 卸载随系统清理。
 */
class DbCacheHelper private constructor(
  context: Context,
) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
  companion object {
    private const val TAG = "DbCacheHelper"
    private const val DB_NAME = "splayer-cache.db"
    private const val DB_VERSION = 1

    /** TTML 负缓存 72h TTL（毫秒） */
    private const val TTML_NEGATIVE_TTL_MS = 72L * 60 * 60 * 1000

    /** lyric_match_cache 30 天 TTL（毫秒） */
    private const val MATCH_TTL_MS = 30L * 24 * 60 * 60 * 1000

    private const val SQL_CREATE_LYRIC_CACHE =
      "CREATE TABLE IF NOT EXISTS lyric_cache (" +
        "platform TEXT NOT NULL," +
        "platform_id TEXT NOT NULL," +
        "data TEXT NOT NULL," +
        "fetched_at INTEGER NOT NULL," +
        "PRIMARY KEY (platform, platform_id)" +
        ")"

    private const val SQL_CREATE_LYRIC_TTML_CACHE =
      "CREATE TABLE IF NOT EXISTS lyric_ttml_cache (" +
        "platform TEXT NOT NULL," +
        "id TEXT NOT NULL," +
        "content TEXT," +
        "fetched_at INTEGER NOT NULL," +
        "PRIMARY KEY (platform, id)" +
        ")"

    private const val SQL_CREATE_LYRIC_MATCH_CACHE =
      "CREATE TABLE IF NOT EXISTS lyric_match_cache (" +
        "fingerprint TEXT NOT NULL," +
        "platform TEXT NOT NULL," +
        "platform_id TEXT NOT NULL," +
        "extra TEXT," +
        "matched_at INTEGER NOT NULL," +
        "PRIMARY KEY (fingerprint, platform)" +
        ")"

    @Volatile
    private var instance: DbCacheHelper? = null

    @JvmStatic
    fun init(context: Context): DbCacheHelper =
      instance ?: synchronized(this) {
        instance ?: DbCacheHelper(context).also { instance = it }
      }

    @JvmStatic
    fun getInstance(): DbCacheHelper = instance ?: error("DbCacheHelper 未初始化，请先调用 init(context)")
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(SQL_CREATE_LYRIC_CACHE)
    db.execSQL(SQL_CREATE_LYRIC_TTML_CACHE)
    db.execSQL(SQL_CREATE_LYRIC_MATCH_CACHE)
  }

  override fun onUpgrade(
    db: SQLiteDatabase,
    oldVersion: Int,
    newVersion: Int,
  ) {
    // 当前为 V1，未来升级在此处加迁移
    db.execSQL("DROP TABLE IF EXISTS lyric_cache")
    db.execSQL("DROP TABLE IF EXISTS lyric_ttml_cache")
    db.execSQL("DROP TABLE IF EXISTS lyric_match_cache")
    onCreate(db)
  }

  // ==================== lyric_cache ====================

  /**
   * 命中原始接口返回 JSON，未命中返回 null
   * @param platform - 平台标识
   * @param platformId - 平台歌曲 id
   * @returns 缓存的 JSON 字符串，未命中返回 null
   */
  fun getLyricCache(
    platform: String,
    platformId: String,
  ): String? {
    val db = readableDatabase
    val cursor =
      db.query(
        "lyric_cache",
        arrayOf("data"),
        "platform = ? AND platform_id = ?",
        arrayOf(platform, platformId),
        null,
        null,
        null,
      )
    return cursor.use {
      if (it.moveToFirst()) it.getString(0) else null
    }
  }

  /**
   * upsert 原始接口返回；fetched_at 每次写入都刷新
   * @param platform - 平台标识
   * @param platformId - 平台歌曲 id
   * @param data - 完整 JSON 字符串
   */
  fun setLyricCache(
    platform: String,
    platformId: String,
    data: String,
  ) {
    val db = writableDatabase
    val values =
      ContentValues().apply {
        put("platform", platform)
        put("platform_id", platformId)
        put("data", data)
        put("fetched_at", System.currentTimeMillis())
      }
    db.insertWithOnConflict(
      "lyric_cache",
      null,
      values,
      SQLiteDatabase.CONFLICT_REPLACE,
    )
  }

  /** 清空全部歌词缓存 */
  fun clearLyricCache() {
    writableDatabase.execSQL("DELETE FROM lyric_cache")
  }

  // ==================== lyric_ttml_cache ====================

  /**
   * 命中时返回 content（正缓存）或 null（负缓存）
   * 未命中或负缓存已过期返回 "miss"
   * @param platform - 平台标识（netease / qqmusic）
   * @param id - 平台歌曲 id（NCM 用数字 id；QM 用 mid）
   */
  fun getLyricTtmlCache(
    platform: String,
    id: String,
  ): String? {
    val db = readableDatabase
    val cursor =
      db.query(
        "lyric_ttml_cache",
        arrayOf("content", "fetched_at"),
        "platform = ? AND id = ?",
        arrayOf(platform, id),
        null,
        null,
        null,
      )
    return cursor.use {
      if (!it.moveToFirst()) return@use "miss"
      val content = if (it.isNull(0)) null else it.getString(0)
      val fetchedAt = it.getLong(1)
      if (content !== null) return@use content
      // 负缓存：超过 TTL 视为 miss
      if (System.currentTimeMillis() - fetchedAt > TTML_NEGATIVE_TTL_MS) return@use "miss"
      null
    }
  }

  /**
   * upsert TTML；content 为 null 表示负缓存
   * @param platform - 平台标识
   * @param id - 平台歌曲 id
   * @param content - TTML 文本，null 表示负缓存
   */
  fun setLyricTtmlCache(
    platform: String,
    id: String,
    content: String?,
  ) {
    val db = writableDatabase
    val values =
      ContentValues().apply {
        put("platform", platform)
        put("id", id)
        put("content", content)
        put("fetched_at", System.currentTimeMillis())
      }
    db.insertWithOnConflict(
      "lyric_ttml_cache",
      null,
      values,
      SQLiteDatabase.CONFLICT_REPLACE,
    )
  }

  /** 清空全部 TTML 缓存 */
  fun clearLyricTtmlCache() {
    writableDatabase.execSQL("DELETE FROM lyric_ttml_cache")
  }

  /** 清理超过 72h 的负缓存记录 */
  fun cleanExpiredLyricTtmlCache(): Int {
    val db = writableDatabase
    val cutoff = System.currentTimeMillis() - TTML_NEGATIVE_TTL_MS
    // 仅清理负缓存（content IS NULL）；正缓存永久保留
    return db.delete(
      "lyric_ttml_cache",
      "content IS NULL AND fetched_at < ?",
      arrayOf(cutoff.toString()),
    )
  }

  // ==================== lyric_match_cache ====================

  /**
   * 命中且未过期返回记录；过期/未命中返回 null
   * @param fingerprint - track 指纹
   * @param platform - 平台标识
   * @returns [MatchedRecord] 或 null
   */
  fun getLyricMatchCache(
    fingerprint: String,
    platform: String,
  ): MatchedRecord? {
    val db = readableDatabase
    val cursor =
      db.query(
        "lyric_match_cache",
        arrayOf("platform_id", "extra", "matched_at"),
        "fingerprint = ? AND platform = ?",
        arrayOf(fingerprint, platform),
        null,
        null,
        null,
      )
    return cursor.use {
      if (!it.moveToFirst()) return@use null
      val matchedAt = it.getLong(2)
      if (System.currentTimeMillis() - matchedAt > MATCH_TTL_MS) return@use null
      val platformId = it.getString(0)
      val extra = if (it.isNull(1)) null else it.getString(1)
      MatchedRecord(platformId, extra)
    }
  }

  /**
   * upsert 映射；matched_at 每次写入都刷新
   * @param fingerprint - track 指纹
   * @param platform - 平台标识
   * @param platformId - 平台歌曲 id
   * @param extra - 平台额外字段 JSON 字符串，可为 null
   */
  fun setLyricMatchCache(
    fingerprint: String,
    platform: String,
    platformId: String,
    extra: String?,
  ) {
    val db = writableDatabase
    val values =
      ContentValues().apply {
        put("fingerprint", fingerprint)
        put("platform", platform)
        put("platform_id", platformId)
        put("extra", extra)
        put("matched_at", System.currentTimeMillis())
      }
    db.insertWithOnConflict(
      "lyric_match_cache",
      null,
      values,
      SQLiteDatabase.CONFLICT_REPLACE,
    )
  }

  /** 清空全部映射缓存 */
  fun clearLyricMatchCache() {
    writableDatabase.execSQL("DELETE FROM lyric_match_cache")
  }

  /** 清理超过 30 天的记录 */
  fun cleanExpiredLyricMatchCache(): Int {
    val db = writableDatabase
    val cutoff = System.currentTimeMillis() - MATCH_TTL_MS
    return db.delete(
      "lyric_match_cache",
      "matched_at < ?",
      arrayOf(cutoff.toString()),
    )
  }

  // ==================== 统计 ====================

  /** lyric_cache 占用字节数（SUM(LENGTH(data))） */
  fun getLyricCacheSize(): Long = tableSize("lyric_cache", arrayOf("data"))

  /** lyric_ttml_cache 占用字节数（SUM(LENGTH(content))） */
  fun getLyricTtmlCacheSize(): Long = tableSize("lyric_ttml_cache", arrayOf("content"))

  /** lyric_match_cache 占用字节数（SUM(LENGTH(fingerprint) + LENGTH(platform_id) + LENGTH(extra))） */
  fun getLyricMatchCacheSize(): Long = tableSize("lyric_match_cache", arrayOf("fingerprint", "platform_id", "extra"))

  /**
   * sqlite 单表占用：取指定列 length 之和
   * @param table - 表名
   * @param columns - 列名列表
   * @returns 占用字节数
   */
  private fun tableSize(
    table: String,
    columns: Array<String>,
  ): Long {
    val expr = columns.joinToString(" + ") { "COALESCE(length($it), 0)" }
    val cursor =
      readableDatabase.rawQuery(
        "SELECT SUM($expr) AS total FROM $table",
        null,
      )
    return cursor.use {
      if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
    }
  }

  /** 命中记录 */
  data class MatchedRecord(
    val platformId: String,
    val extra: String?,
  )
}
