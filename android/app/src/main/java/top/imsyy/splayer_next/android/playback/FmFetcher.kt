package top.imsyy.splayer_next.android.playback

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * 私人 FM 拉取器：调嵌入式 API 的 personal_fm 路由解析一批推荐曲目。
 *
 * <p>拉取与解析分离：[parseResponse] 为纯函数便于 JVM 单测；
 * [fetch] 阻塞执行 HTTP，必须在后台线程调用。WebView 冻结时由原生
 * PlaybackManager 直接续池，脱离 JS 依赖。
 */
class FmFetcher {
  companion object {
    private const val TAG = "FmFetcher"
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 30000
  }

  /**
   * 拉取一批 FM 曲目（过滤 exclude 与批内重复）；任何失败返回空列表。
   *
   * @param baseUrl 嵌入式 netease API 基址（含 /api/netease 前缀）
   * @param cookieValue 登录 Cookie（空串表示匿名）
   * @param exclude 需过滤的 songId 集合（队列已有 + 已播去重）
   */
  fun fetch(
    baseUrl: String,
    cookieValue: String,
    exclude: Set<Long>,
  ): List<PlaybackQueue.Track> {
    if (baseUrl.isEmpty()) {
      Log.w(TAG, "fetch skipped: apiBaseUrl empty")
      return emptyList()
    }
    var connection: HttpURLConnection? = null
    return try {
      val endpoint = "$baseUrl/personal_fm?timestamp=${System.currentTimeMillis()}"
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
        Log.w(TAG, "fetch http=${connection.responseCode}")
        return emptyList()
      }
      val reader = BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8))
      val sb = StringBuilder()
      var line: String?
      while (reader.readLine().also { line = it } != null) {
        sb.append(line)
      }
      parseResponse(sb.toString(), exclude)
    } catch (e: Exception) {
      Log.w(TAG, "fetch failed", e)
      emptyList()
    } finally {
      connection?.disconnect()
    }
  }

  /**
   * 解析 personal_fm 响应体并过滤；字段兼容 ar|artists / al|album / dt|duration
   * （对齐 JS songToTrack 的兼容逻辑）。
   *
   * @throws org.json.JSONException 响应体非法（由 fetch 统一捕获记录）
   */
  fun parseResponse(
    body: String,
    exclude: Set<Long>,
  ): List<PlaybackQueue.Track> {
    val out = ArrayList<PlaybackQueue.Track>()
    val seen = HashSet<Long>()
    val data = JSONObject(body).optJSONArray("data") ?: return out
    for (i in 0 until data.length()) {
      val song = data.optJSONObject(i) ?: continue
      val songId = song.optLong("id", 0L)
      if (songId <= 0 || songId in exclude || songId in seen) continue

      val track = PlaybackQueue.Track()
      track.songId = songId
      track.source = "netease"
      track.sourceId = songId.toString()
      track.title = song.optString("name", "")
      track.artist = readArtistNames(song)
      val album = song.optJSONObject("al") ?: song.optJSONObject("album")
      if (album != null) {
        track.albumId = album.optString("id", "")
        track.album = album.optString("name", "")
        track.coverUrl = album.optString("picUrl", "")
      }
      track.durationMs = if (!song.isNull("dt")) song.optLong("dt", 0L) else song.optLong("duration", 0L)
      track.canLike = true
      track.playListIndex = -1
      seen.add(songId)
      out.add(track)
    }
    return out
  }

  /** ar|artists 数组的歌手名以 / 连接（对齐 JS readArtists）。 */
  private fun readArtistNames(song: JSONObject): String {
    val artists = song.optJSONArray("ar") ?: song.optJSONArray("artists") ?: return ""
    val names = ArrayList<String>()
    for (i in 0 until artists.length()) {
      val artist = artists.optJSONObject(i) ?: continue
      val name = artist.optString("name", "").trim()
      if (name.isNotEmpty()) names.add(name)
    }
    return names.joinToString("/")
  }
}
