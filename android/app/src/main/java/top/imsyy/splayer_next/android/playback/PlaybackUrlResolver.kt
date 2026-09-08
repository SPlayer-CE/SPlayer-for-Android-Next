package top.imsyy.splayer_next.android.playback

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import top.imsyy.splayer_next.android.cache.SongCacheFetcher

/** 下载 URL 解析结果 */
data class DownloadUrlResult(
  val url: String,
  val format: String? = null,
  val size: Long = 0L,
)

/**
 * Java 端 URL 解析器。WebView 冻结时仍可通过本地 embedded API（127.0.0.1）拿到播放地址。
 *
 * <p>解析链对齐 JS audioSource.ts 顺序：缓存 → 官方 /song/url/v1（trial 捕获）→
 * 插件路由 /api/plugins/resolveUrl → trial。[resolveDownloadUrlSync] 调
 * /song/download/url/v1 对齐 PC 端下载接口。内置 64 项 LRU 缓存，2 线程并发。
 */
class PlaybackUrlResolver(
  private val appContext: Context,
) {
  companion object {
    private const val TAG = "UrlResolver"
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 8000

    /** 插件沙箱解析耗时不可控（脚本内多次上游请求），读超时放宽到 30s。 */
    private const val PLUGIN_READ_TIMEOUT_MS = 30000
    private const val CACHE_SIZE = 64

    /** 失败 songId 短期负缓存窗口（毫秒）：跨多次 prefetch 周期同一首失败时避免持续打上游 /song/url 接口。 */
    private const val NEGATIVE_CACHE_TTL_MS = 30_000L

    /** 在线平台 → 插件源名（对齐 JS PLATFORM_TO_PLUGIN_SOURCE）。 */
    private val PLUGIN_SOURCES = mapOf("netease" to "wy", "qqmusic" to "tx", "kugou" to "kg")

    private fun normalizeLevel(
      level: String?,
      disableAiAudio: Boolean,
    ): String {
      if (level.isNullOrEmpty()) return "exhigh"
      if (disableAiAudio &&
        (level == "jymaster" || level == "sky" || level == "jyeffect" || level == "vivid")
      ) {
        return "hires"
      }
      return level
    }

    private fun getRequestLevels(level: String): Array<String> =
      when (level) {
        "dolby" -> arrayOf("dolby", "hires", "lossless", "exhigh")
        "standard" -> arrayOf(level)
        "higher" -> arrayOf("higher", "exhigh")
        "hires" -> arrayOf("hires", "lossless", "exhigh")
        "lossless" -> arrayOf("lossless", "exhigh")
        "exhigh" -> arrayOf("exhigh")
        else -> arrayOf(level, "hires", "lossless", "exhigh")
      }

    private fun qualityKeyForLevel(level: String): String =
      when (level) {
        "standard" -> "l"
        "higher" -> "m"
        "exhigh" -> "h"
        "lossless" -> "sq"
        "hires" -> "hr"
        "jyeffect" -> "je"
        "sky" -> "sk"
        "jymaster" -> "jm"
        "dolby" -> "db"
        else -> ""
      }

    private fun isPlayableQualityLevel(
      qualityData: JSONObject?,
      level: String,
    ): Boolean {
      if (qualityData == null || "exhigh" == level) return true
      val key = qualityKeyForLevel(level)
      if (key.isEmpty()) return true
      val quality = qualityData.optJSONObject(key)
      return quality != null && quality.optLong("br", 0L) > 0L
    }

    private fun filterRequestLevels(
      levels: Array<String>,
      qualityData: JSONObject?,
    ): Array<String> {
      if (qualityData == null) return levels
      val out = ArrayList<String>()
      for (level in levels) {
        if (isPlayableQualityLevel(qualityData, level)) out.add(level)
      }
      return out.toTypedArray()
    }

    @Throws(Exception::class)
    private fun readBody(connection: HttpURLConnection): String {
      BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
        val sb = java.lang.StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) sb.append(line)
        return sb.toString()
      }
    }

    /** 读非 200 响应体前 200 字符；打进日志用于区分代理层 500（空体）与 NetEase 业务错误（JSON 体）。 */
    private fun readErrorSnippet(connection: HttpURLConnection): String =
      try {
        connection.errorStream?.bufferedReader()?.use { it.readText().take(200) } ?: ""
      } catch (_: Exception) {
        ""
      }

    private fun fetchQualityData(
      baseUrl: String,
      cookieValue: String,
      songId: Long,
    ): JSONObject? {
      var connection: HttpURLConnection? = null
      try {
        // cookie 只走请求头：query 再带一份会让请求行+Cookie 头越过 nanohttpd 8192 上限，代理层直接 500
        val endpoint = "$baseUrl/song/music/detail?id=$songId&timestamp=${System.currentTimeMillis()}"
        connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        if (cookieValue.isNotEmpty()) {
          connection.setRequestProperty("Cookie", cookieValue)
        }
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
          Log.w(TAG, "fetchQualityData songId=$songId http=${connection.responseCode} body=${readErrorSnippet(connection)}")
          return null
        }
        val root = JSONObject(readBody(connection))
        return root.optJSONObject("data")
      } catch (e: Exception) {
        Log.w(TAG, "fetchQualityData failed songId=$songId", e)
        return null
      } finally {
        connection?.disconnect()
      }
    }
  }

  /** 2 线程：避免「播放刚起 + 用户立刻 NEXT」被串行。上游接口有节流，不宜调高。 */
  private val executor: ExecutorService = Executors.newFixedThreadPool(2)

  /**
   * 播放关键路径专用线程池：submitResolve（点击切歌/下一首）与 prefetchAsync（预取）隔离，
   * 防止点击解析排在最多 3 个预取任务（插件源单次可达 30s）之后，
   * 出现「音频停留在上一首、元信息已切走」的长窗口。
   */
  private val playExecutor: ExecutorService = Executors.newFixedThreadPool(2)
  private val cacheLock = Any()

  /** cacheKey("songId:level") → URL。level 不同 → 文件/码率/endpoint 都可能不同，必须分键。 */
  private val cache =
    object : LinkedHashMap<String, String>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > CACHE_SIZE
    }

  /** 正在解析的 track 去重（LinkedHashMap 充当 set）。 */
  private val inFlight = LinkedHashMap<String, AtomicBoolean>()

  /**
   * Android 端禁明文；上游返回的资源链接偶尔仍是 http，这里统一升到 https。
   */
  private fun normalizeMediaUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return try {
      val parsed = URI(url)
      val host = parsed.host ?: return url
      if (parsed.scheme.equals("http", ignoreCase = true) && host.endsWith(".music.126.net")) {
        URI(
          "https",
          parsed.userInfo,
          host,
          parsed.port,
          parsed.path,
          parsed.query,
          parsed.fragment,
        ).toString()
      } else {
        url
      }
    } catch (_: Exception) {
      url
    }
  }

  /** cacheKey("songId:level") → 失败时间戳；TTL 内重复请求直接返 null，避免 API 打风暴。 */
  private val negativeCache =
    object : LinkedHashMap<String, Long>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: Map.Entry<String, Long>): Boolean = size > CACHE_SIZE
    }

  /** 下载 URL 缓存：cacheKey("dl:songId:level") → DownloadUrlResult */
  private val downloadCache =
    object : LinkedHashMap<String, DownloadUrlResult>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: Map.Entry<String, DownloadUrlResult>): Boolean = size > CACHE_SIZE
    }

  @Volatile
  private var apiBaseUrl = ""

  @Volatile
  private var cookie = ""

  @Volatile
  private var songLevel = "exhigh"

  /** 项目原始档位（lq/sq/hq/lossless/hi-res），歌曲缓存 key 与插件 quality 参数使用。 */
  @Volatile
  private var songLevelRaw = "hq"

  @Volatile
  private var disableAiAudio = false

  @Volatile
  private var playSongDemo = false

  /** 歌曲缓存回退总开关（对齐 JS settings.system.cache.songCache.enabled）。 */
  @Volatile
  private var songCacheEnabled = false

  /** 各插件源（wy/tx/kg）的可用插件 ID 有序列表，由 JS 端推送。 */
  @Volatile
  private var pluginSources: Map<String, List<String>> = emptyMap()

  @Synchronized
  fun updateContext(
    baseUrl: String?,
    cookieValue: String?,
    level: String?,
    rawLevel: String?,
    disableAiAudioState: Boolean,
    playSongDemoState: Boolean,
    songCacheEnabledState: Boolean,
    pluginSourcesState: Map<String, List<String>>,
  ) {
    val newBaseUrl = baseUrl?.trim() ?: ""
    val newCookie = cookieValue?.trim() ?: ""
    val newLevel = normalizeLevel(if (!level.isNullOrEmpty()) level else this.songLevel, disableAiAudioState)
    val newRawLevel = if (!rawLevel.isNullOrEmpty()) rawLevel else this.songLevelRaw

    // 任一上下文变化都要清缓存：
    // - level 变 → URL 文件/码率/endpoint 不同；rawLevel 变 → 缓存 key 不同
    // - cookie 变 → 换账号，旧账号缓存的 VIP URL / 失败记录都不再适用
    // - baseUrl 变 → embedded API 地址变更（极罕见），缓存失效
    // - songCacheEnabled / pluginSources 变 → 回退链可用性变化
    val contextChanged =
      newBaseUrl != this.apiBaseUrl ||
        newCookie != this.cookie ||
        newLevel != this.songLevel ||
        newRawLevel != this.songLevelRaw ||
        disableAiAudioState != this.disableAiAudio ||
        playSongDemoState != this.playSongDemo ||
        songCacheEnabledState != this.songCacheEnabled ||
        pluginSourcesState != this.pluginSources

    this.apiBaseUrl = newBaseUrl
    this.cookie = newCookie
    this.songLevel = newLevel
    this.songLevelRaw = newRawLevel
    this.disableAiAudio = disableAiAudioState
    this.playSongDemo = playSongDemoState
    this.songCacheEnabled = songCacheEnabledState
    this.pluginSources = pluginSourcesState

    if (contextChanged) {
      synchronized(cacheLock) {
        cache.clear()
        negativeCache.clear()
        downloadCache.clear()
      }
    }
  }

  fun clear(songId: Long) {
    if (songId <= 0) return
    val prefix = "$songId:"
    val dlPrefix = "dl:$songId:"
    synchronized(cacheLock) {
      val cacheIterator = cache.entries.iterator()
      while (cacheIterator.hasNext()) {
        if (cacheIterator.next().key.startsWith(prefix)) {
          cacheIterator.remove()
        }
      }
      val negativeIterator = negativeCache.entries.iterator()
      while (negativeIterator.hasNext()) {
        if (negativeIterator.next().key.startsWith(prefix)) {
          negativeIterator.remove()
        }
      }
      val downloadIterator = downloadCache.entries.iterator()
      while (downloadIterator.hasNext()) {
        if (downloadIterator.next().key.startsWith(dlPrefix)) {
          downloadIterator.remove()
        }
      }
    }
  }

  /**
   * 阻塞解析下载 URL，对齐 PC 端 [resolveNeteaseDownloadUrl] 流程：
   * 1. usePlayback=false 时先调 /song/download/url/v1（官方下载接口，占用每日下载次数）
   * 2. 下载接口无果或 usePlayback=true 时回落 /song/url/v1（播放接口，不占次数）
   *
   * @param songId 网易云 songId
   * @param usePlayback true 时跳过下载接口、直接用播放接口（模拟播放下载）
   * @return 下载 URL 结果（含格式/体积）；无可用源返 null
   */
  fun resolveDownloadUrlSync(
    songId: Long,
    usePlayback: Boolean,
  ): DownloadUrlResult? {
    if (songId <= 0) return null
    val level: String
    val baseUrl: String
    val cookieValue: String
    synchronized(this) {
      level = normalizeLevel(songLevel, disableAiAudio)
      baseUrl = apiBaseUrl
      cookieValue = cookie
    }
    if (baseUrl.isEmpty()) {
      Log.w(TAG, "resolveDownloadUrlSync skipped: apiBaseUrl empty")
      return null
    }

    val dlCacheKey = "dl:$songId:$level"
    synchronized(cacheLock) {
      downloadCache[dlCacheKey]?.let { return it }
    }

    // 1. 官方下载接口
    if (!usePlayback) {
      val downloadResult = fetchDownloadUrl(baseUrl, cookieValue, songId, level)
      if (downloadResult != null) {
        synchronized(cacheLock) {
          downloadCache[dlCacheKey] = downloadResult
        }
        return downloadResult
      }
    }

    // 2. 回落播放接口（复用 resolveSync 的完整回落链）
    val playUrl = resolveSync(songId)
    if (playUrl != null) {
      // 播放接口不返回格式/体积，仅 URL
      val result = DownloadUrlResult(url = playUrl)
      synchronized(cacheLock) {
        downloadCache[dlCacheKey] = result
      }
      return result
    }

    return null
  }

  /**
   * 调 /song/download/url/v1 获取下载地址
   * 响应 data 为单对象（非数组）：{ id, url, br, size, type, level }
   */
  private fun fetchDownloadUrl(
    baseUrl: String,
    cookieValue: String,
    songId: Long,
    level: String,
  ): DownloadUrlResult? {
    var connection: HttpURLConnection? = null
    try {
      // cookie 只走请求头：query 再带一份会让请求行+Cookie 头越过 nanohttpd 8192 上限，代理层直接 500
      val endpoint =
        "$baseUrl/song/download/url/v1?id=$songId&level=${URLEncoder.encode(
          level,
          StandardCharsets.UTF_8.name(),
        )}&timestamp=${System.currentTimeMillis()}"
      connection = URL(endpoint).openConnection() as HttpURLConnection
      connection.requestMethod = "GET"
      connection.connectTimeout = CONNECT_TIMEOUT_MS
      connection.readTimeout = READ_TIMEOUT_MS
      connection.setRequestProperty("Accept", "application/json")
      if (cookieValue.isNotEmpty()) {
        connection.setRequestProperty("Cookie", cookieValue)
      }
      connection.connect()
      if (connection.responseCode != HttpURLConnection.HTTP_OK) {
        Log.w(TAG, "fetchDownloadUrl songId=$songId level=$level http=${connection.responseCode} body=${readErrorSnippet(connection)}")
        return null
      }
      val root = JSONObject(readBody(connection))
      val data = root.optJSONObject("data") ?: return null
      val url = normalizeMediaUrl(data.optString("url", ""))
      if (url.isNullOrEmpty() || "null" == url) return null
      val format = if (!data.isNull("type")) data.optString("type", "") else null
      val size = data.optLong("size", 0L)
      return DownloadUrlResult(url = url, format = format, size = size)
    } catch (e: Exception) {
      Log.w(TAG, "fetchDownloadUrl failed songId=$songId level=$level", e)
      return null
    } finally {
      connection?.disconnect()
    }
  }

  /**
   * 解析 track 的播放 URL，链序对齐 JS audioSource.resolveTrackSource：
   * url 就绪 → local → 歌曲缓存 → netease 官方（trial 捕获）→ 插件 → trial。
   */
  fun resolveTrack(track: PlaybackQueue.Track?): String? {
    if (track == null) return null
    if (!track.url.isNullOrEmpty()) return track.url
    if (track.source == "local") return track.path
    lookupSongCache(track)?.let { return it }
    val pluginSource = PLUGIN_SOURCES[track.source]
    if (track.source == "netease" && track.songId > 0) {
      val outcome = resolveSyncInternal(track.songId)
      if (outcome.resolvedUrl != null) return outcome.resolvedUrl
      resolveByPlugin(track, pluginSource)?.let { return it }
      if (outcome.trialUrl != null && playSongDemo) return outcome.trialUrl
      return null
    }
    resolveByPlugin(track, pluginSource)?.let { return it }
    return null
  }

  /** 歌曲缓存 key（对齐 JS cacheKeyForTrack）；无法生成返回 null。 */
  fun songCacheKeyFor(track: PlaybackQueue.Track): String? =
    when {
      track.source == "streaming" && !track.serverId.isNullOrEmpty() && !track.originalId.isNullOrEmpty() ->
        "s:${track.serverId}:${track.originalId}:"
      track.source == "netease" && track.sourceId.isNotEmpty() ->
        "o:netease:${track.sourceId}:$songLevelRaw"
      PLUGIN_SOURCES.containsKey(track.source) && track.sourceId.isNotEmpty() ->
        "o:${track.source}:${track.sourceId}:"
      else -> null
    }

  /** 查本地歌曲缓存；未开启缓存 / 无 key / 未命中 / 内容不像音频时返回 null。 */
  private fun lookupSongCache(track: PlaybackQueue.Track): String? {
    if (!songCacheEnabled) return null
    val key = songCacheKeyFor(track) ?: return null
    val path = SongCacheFetcher.lookup(appContext, key) ?: return null
    if (!SongCacheFetcher.looksLikeAudio(java.io.File(path))) return null
    Log.d(TAG, "song cache hit songId=${track.songId}")
    return path
  }

  /**
   * 经插件路由解析 URL（POST {apiOrigin}/api/plugins/resolveUrl）。
   *
   * <p>按 JS 推送的 pluginSources 候选顺序逐个尝试，Node 端还会在其后追加
   * 系统优先级与全部就绪插件作为兜底候选。
   */
  private fun resolveByPlugin(
    track: PlaybackQueue.Track,
    pluginSource: String?,
  ): String? {
    if (pluginSource == null) return null
    val candidates = pluginSources[pluginSource] ?: return null
    val origin = apiOrigin()
    if (origin.isEmpty() || candidates.isEmpty()) return null
    val musicInfo = buildPluginMusicInfo(track, pluginSource) ?: return null
    for (pluginId in candidates) {
      val url = fetchPluginUrl(origin, pluginId, pluginSource, musicInfo)
      if (url != null) {
        Log.d(TAG, "plugin resolve ok songId=${track.songId} plugin=$pluginId")
        return url
      }
    }
    Log.w(TAG, "plugin resolve failed songId=${track.songId} source=$pluginSource")
    return null
  }

  /** 构造插件 musicInfo（对齐 JS resolveByPlugin 的 MusicInfoBase 形状）。 */
  private fun buildPluginMusicInfo(
    track: PlaybackQueue.Track,
    pluginSource: String,
  ): org.json.JSONObject? {
    if (track.sourceId.isEmpty()) return null
    val info = org.json.JSONObject()
    info.put("id", track.sourceId)
    info.put("songmid", track.sourceId)
    info.put("songId", track.sourceId)
    info.put("name", track.title)
    info.put("singer", track.artist)
    info.put("source", pluginSource)
    val totalSec = if (track.durationMs > 0) (track.durationMs / 1000).toInt() else 0
    if (totalSec > 0) {
      info.put("interval", String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60))
    }
    val meta = org.json.JSONObject()
    meta.put("songId", track.sourceId)
    meta.put("albumName", track.album)
    if (!track.albumId.isNullOrEmpty()) {
      meta.put("albumId", track.albumId)
    }
    if (track.coverUrl.isNotEmpty()) {
      meta.put("picUrl", track.coverUrl)
    }
    info.put("meta", meta)
    return info
  }

  /** 调单个插件的 resolveUrl 路由；失败/空 URL 返回 null。 */
  private fun fetchPluginUrl(
    origin: String,
    pluginId: String,
    pluginSource: String,
    musicInfo: org.json.JSONObject,
  ): String? {
    var connection: HttpURLConnection? = null
    return try {
      val body = org.json.JSONObject()
      body.put("pluginId", pluginId)
      body.put("source", pluginSource)
      body.put("quality", songLevelRaw)
      body.put("musicInfo", musicInfo)
      val payload = body.toString().toByteArray(StandardCharsets.UTF_8)
      connection = URL("$origin/api/plugins/resolveUrl").openConnection() as HttpURLConnection
      connection.requestMethod = "POST"
      connection.connectTimeout = CONNECT_TIMEOUT_MS
      connection.readTimeout = PLUGIN_READ_TIMEOUT_MS
      connection.setRequestProperty("Accept", "application/json")
      connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
      if (cookie.isNotEmpty()) {
        connection.setRequestProperty("Cookie", cookie)
      }
      connection.doOutput = true
      connection.setFixedLengthStreamingMode(payload.size)
      connection.outputStream.use { it.write(payload) }
      if (connection.responseCode != HttpURLConnection.HTTP_OK) {
        Log.w(TAG, "plugin resolve http=${connection.responseCode} plugin=$pluginId body=${readErrorSnippet(connection)}")
        return null
      }
      val root = JSONObject(readBody(connection))
      val url = root.optString("url", "")
      if (url.isEmpty() || "null" == url) null else url
    } catch (e: Exception) {
      Log.w(TAG, "plugin resolve failed plugin=$pluginId", e)
      null
    } finally {
      connection?.disconnect()
    }
  }

  /** embedded API origin（apiBaseUrl 去掉 /api/netease 前缀）。 */
  private fun apiOrigin(): String = apiBaseUrl.removeSuffix("/api/netease")

  private fun resolveKey(track: PlaybackQueue.Track): String {
    if (track.playListIndex >= 0) return "i:${track.playListIndex}"
    if (track.source.isNotEmpty() || track.sourceId.isNotEmpty()) return "s:${track.source}:${track.sourceId}"
    return "n:${track.songId}"
  }

  /** 官方接口解析结果：resolvedUrl 为正式可播 URL；trialUrl 为试听 URL（可能同时为 null）。 */
  private class ResolveOutcome(
    val resolvedUrl: String?,
    val trialUrl: String?,
  )

  /** 阻塞解析 songId 的正式 URL（不含试听）；命中缓存 / 失败 / 未配置返 null。 */
  fun resolveSync(songId: Long): String? = resolveSyncInternal(songId).resolvedUrl

  /**
   * 阻塞解析 songId，trial URL 一并捕获但不立即采用（由 resolveTrack 按链序决策）。
   *
   * @return ResolveOutcome：resolvedUrl 优先正式 URL，trialUrl 仅为试听降级可用
   */
  private fun resolveSyncInternal(songId: Long): ResolveOutcome {
    if (songId <= 0) return ResolveOutcome(null, null)
    val level: String
    val baseUrl: String
    val cookieValue: String
    synchronized(this) {
      level = normalizeLevel(songLevel, disableAiAudio)
      baseUrl = apiBaseUrl
      cookieValue = cookie
    }
    var cacheKey = "$songId:$level"
    val originalCacheKey = cacheKey
    synchronized(cacheLock) {
      val hit = cache[cacheKey]
      if (hit != null) return ResolveOutcome(hit, null)
      // 负缓存命中：TTL 内跳过重复网络调用。仅业务层“无可播 URL”入负缓存，
      // 网络异常/超时/HTTP 非 200 不入负缓存，以免临时故障吃掉后续 retry 机会。
      val failedAt = negativeCache[cacheKey]
      if (failedAt != null) {
        if (System.currentTimeMillis() - failedAt < NEGATIVE_CACHE_TTL_MS) {
          return ResolveOutcome(null, null)
        }
        negativeCache.remove(cacheKey)
      }
    }
    if (baseUrl.isEmpty()) {
      Log.w(TAG, "resolveSync skipped: apiBaseUrl empty")
      return ResolveOutcome(null, null)
    }

    var qualityData: JSONObject? = null
    if (level == "dolby" || level == "jymaster" || level == "sky" || level == "jyeffect") {
      qualityData = fetchQualityData(baseUrl, cookieValue, songId)
      if (qualityData != null && !isPlayableQualityLevel(qualityData, level)) {
        cacheKey = "$songId:hires"
      }
    }

    var resolvedUrl: String? = null
    var trialUrl: String? = null
    var hadBusinessResponse = false
    var resolvedLevel = if (qualityData != null && !isPlayableQualityLevel(qualityData, level)) "hires" else level
    val requestLevels = filterRequestLevels(getRequestLevels(resolvedLevel), qualityData)

    if (requestLevels.isEmpty()) {
      synchronized(cacheLock) {
        negativeCache[originalCacheKey] = System.currentTimeMillis()
        negativeCache[cacheKey] = System.currentTimeMillis()
      }
      return ResolveOutcome(null, null)
    }
    for (requestLevel in requestLevels) {
      var connection: HttpURLConnection? = null
      try {
        // dolby 走旧版接口，其余走 /song/url/v1
        // cookie 只走请求头：query 再带一份会让请求行+Cookie 头越过 nanohttpd 8192 上限，代理层直接 500
        val endpoint =
          if ("dolby" == requestLevel) {
            "$baseUrl/song/url?id=$songId&br=999000&immerseType=c51&timestamp=${System.currentTimeMillis()}"
          } else {
            "$baseUrl/song/url/v1?id=$songId&level=${URLEncoder.encode(
              requestLevel,
              StandardCharsets.UTF_8.name(),
            )}&timestamp=${System.currentTimeMillis()}"
          }

        connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        if (cookieValue.isNotEmpty()) {
          connection.setRequestProperty("Cookie", cookieValue)
        }
        connection.connect()

        val httpCode = connection.responseCode
        if (httpCode != HttpURLConnection.HTTP_OK) {
          Log.w(TAG, "resolveSync songId=$songId level=$requestLevel http=$httpCode body=${readErrorSnippet(connection)}")
        } else {
          hadBusinessResponse = true
          val body = readBody(connection)
          val root = JSONObject(body)
          val data = root.optJSONArray("data")
          if (data != null && data.length() > 0) {
            val first = data.optJSONObject(0)
            if (first != null) {
              val url = normalizeMediaUrl(first.optString("url", ""))
              if (url != null && url.isNotEmpty() && "null" != url) {
                if (!first.isNull("freeTrialInfo")) {
                  // trial 捕获后留给链尾决策，不缓存也不视为失败
                  if (trialUrl == null) trialUrl = url
                  continue
                }
                resolvedUrl = url
                resolvedLevel = requestLevel
                break
              }
            }
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "resolveSync failed songId=$songId level=$requestLevel", e)
      } finally {
        connection?.disconnect()
      }
    }

    if (resolvedUrl != null) {
      synchronized(cacheLock) {
        cache[originalCacheKey] = resolvedUrl
        cache[cacheKey] = resolvedUrl
        cache["$songId:$resolvedLevel"] = resolvedUrl
        negativeCache.remove(originalCacheKey)
        negativeCache.remove(cacheKey)
        negativeCache.remove("$songId:$resolvedLevel")
      }
      return ResolveOutcome(resolvedUrl, null)
    }
    if (hadBusinessResponse && trialUrl == null) {
      synchronized(cacheLock) {
        negativeCache[originalCacheKey] = System.currentTimeMillis()
        negativeCache[cacheKey] = System.currentTimeMillis()
      }
    }
    return ResolveOutcome(null, trialUrl)
  }

  /**
   * 在内部线程池上异步解析 songId 的播放 URL，结果通过回调返回（运行在池线程，调用方自行 post 主线程）。
   *
   * <p>用于 NEXT/PREV 等需要立即播放的场景，避免共享 PlaybackManager 单线程 networkExecutor 与
   * favorite 请求互锁，同时享用 inFlight 去重 + cache 命中。
   *
   * @param songId 待解析的 songId
   * @param callback 解析回调（参数为 URL 或 null）
   */
  fun submitResolve(
    track: PlaybackQueue.Track?,
    callback: java.util.function.Consumer<String?>?,
  ) {
    if (track == null) {
      callback?.accept(null)
      return
    }
    playExecutor.submit {
      val url = resolveTrack(track)
      callback?.accept(url)
    }
  }

  fun submitResolve(
    songId: Long,
    callback: java.util.function.Consumer<String?>?,
  ) {
    if (songId <= 0) {
      callback?.accept(null)
      return
    }
    playExecutor.submit {
      val url = resolveSync(songId)
      callback?.accept(url)
    }
  }

  /**
   * 后台解析 track.url，成功同步写回队列。
   *
   * @param onResolved 解析完成回调（运行在池线程，需自行 post 到主线程）
   */
  fun prefetchAsync(
    track: PlaybackQueue.Track?,
    queue: PlaybackQueue?,
    onResolved: Runnable?,
  ) {
    if (track == null || track.playable()) return
    val key = resolveKey(track)
    var flag: AtomicBoolean?
    synchronized(inFlight) {
      flag = inFlight[key]
      if (flag != null && flag!!.get()) return // 已有进行中的 task
      flag = AtomicBoolean(true)
      inFlight[key] = flag!!
    }
    val flagRef = flag!!
    executor.submit {
      try {
        val url = resolveTrack(track)
        if (url != null) {
          track.url = url
          queue?.updateTrackUrl(track, url)
        }
        onResolved?.run()
      } finally {
        synchronized(inFlight) {
          flagRef.set(false)
          if (inFlight[key] === flagRef) {
            inFlight.remove(key)
          }
        }
      }
    }
  }
}
