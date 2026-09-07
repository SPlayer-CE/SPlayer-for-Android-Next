package top.imsyy.splayer_next.android.playback

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Android MediaSession 同步管理器。
 *
 * <p>接收前端推送的 Track / 队列上下文，转换为原生播放层使用的模型。前端仍负责读取
 * Pinia 状态，Kotlin 负责 MediaSession 相关 payload 的解析、去重与下发。
 */
class AndroidMediaSessionManager(
  context: Context,
) {
  private val appContext = context.applicationContext
  private var lastApiContextKey: String? = null

  private fun playbackManager(): PlaybackManager = PlaybackManager.getInstance(appContext)

  fun updateMetadata(payload: JSONObject) {
    playbackManager().updateMetadata(readTrackMetadata(payload))
  }

  fun updateQueueContext(payload: JSONObject) {
    playbackManager().updateQueueContext(
      payload.optBoolean("liked", false),
      payload.optBoolean("canSkipPrevious", true),
      payload.optBoolean("personalFmMode", false),
      payload.optBoolean("controllerEnabled", true),
      payload.optBoolean("desktopLyricButtonEnabled", false),
      payload.optBoolean("desktopLyricEnabled", false),
      readQueueTracks(payload),
      payload.optInt("currentIndex", -1),
      normalizeRepeatMode(safeOptString(payload, "repeatMode", "off")),
      readLongArray(payload, "fmRecentSongIds"),
    )
  }

  fun updateNotificationPrefs(payload: JSONObject) {
    playbackManager().updateNotificationPrefs(
      payload.optBoolean("controllerEnabled", true),
      payload.optBoolean("desktopLyricButtonEnabled", false),
    )
  }

  fun setAllowMixWithOthers(payload: JSONObject) {
    playbackManager().setAllowMixWithOthers(payload.optBoolean("allow", true))
  }

  fun syncApiContext(payload: JSONObject) {
    val apiBaseUrl = safeOptString(payload, "apiBaseUrl")
    val cookie = safeOptString(payload, "cookie")
    val rawLevel = safeOptString(payload, "songLevel", "hq")
    val songLevel = normalizeSongLevel(rawLevel)
    val disableAiAudio = payload.optBoolean("disableAiAudio", false)
    val playSongDemo = payload.optBoolean("playSongDemo", false)
    val songCacheEnabled = payload.optBoolean("songCacheEnabled", false)
    val pluginSources = readPluginSources(payload)
    val key = "$apiBaseUrl|$cookie|$songLevel|$rawLevel|$disableAiAudio|$playSongDemo|$songCacheEnabled|$pluginSources"
    if (!payload.optBoolean("force", false) && key == lastApiContextKey) return
    lastApiContextKey = key
    playbackManager().syncApiContext(
      apiBaseUrl,
      cookie,
      songLevel,
      rawLevel,
      disableAiAudio,
      playSongDemo,
      songCacheEnabled,
      pluginSources,
    )
  }

  fun syncRemoteState(payload: JSONObject) {
    playbackManager().syncRemoteState(
      payload.optDouble("playing", 0.0).let { payload.optBoolean("playing", false) },
      payload.optDouble("positionMs", 0.0).toLong(),
      payload.optDouble("durationMs", 0.0).toLong(),
    )
  }

  private fun readTrackMetadata(payload: JSONObject): PlaybackManager.TrackMetadata {
    val track = payload.optJSONObject("track")
    if (track != null) {
      return readTrackMetadataFromTrack(track, payload.optBoolean("liked", false))
    }

    val metadata = PlaybackManager.TrackMetadata()
    metadata.songId = payload.optLong("songId", 0L)
    metadata.title = safeOptString(payload, "title")
    metadata.artist = safeOptString(payload, "artist")
    metadata.album = safeOptString(payload, "album")
    metadata.coverUrl = safeOptString(payload, "coverUrl")
    metadata.durationMs = payload.optDouble("durationMs", 0.0).toLong()
    metadata.canLike = payload.optBoolean("canLike", false)
    metadata.liked = payload.optBoolean("liked", false)
    return metadata
  }

  private fun readTrackMetadataFromTrack(
    track: JSONObject,
    liked: Boolean,
  ): PlaybackManager.TrackMetadata {
    val metadata = PlaybackManager.TrackMetadata()
    metadata.songId = toNativeSongId(safeOptString(track, "id"))
    metadata.title = safeOptString(track, "title")
    metadata.artist = readArtists(track.optJSONArray("artists"))
    metadata.album = readAlbumName(track.optJSONObject("album"))
    metadata.coverUrl = safeOptString(track, "cover")
    metadata.durationMs = track.optDouble("duration", 0.0).toLong()
    metadata.canLike = isLikableSource(safeOptString(track, "source"))
    metadata.liked = liked
    return metadata
  }

  private fun readQueueTracks(payload: JSONObject): List<PlaybackQueue.Track>? {
    val array = payload.optJSONArray("tracks") ?: return null
    if (array.length() == 0) return null

    val tracks = ArrayList<PlaybackQueue.Track>(array.length())
    for (i in 0 until array.length()) {
      try {
        tracks.add(readQueueTrack(array.getJSONObject(i)))
      } catch (ignored: JSONException) {
      }
    }
    return tracks
  }

  private fun readQueueTrack(obj: JSONObject): PlaybackQueue.Track {
    val rawTrack = obj.optJSONObject("track")
    if (rawTrack != null) {
      val track = PlaybackQueue.Track()
      track.source = safeOptString(obj, "source", safeOptString(rawTrack, "source"))
      track.sourceId = safeOptString(obj, "sourceId", safeOptString(rawTrack, "id"))
      track.path = readNullableString(obj, "path") ?: readNullableString(rawTrack, "path")
      track.serverId = readNullableString(obj, "serverId") ?: readNullableString(rawTrack, "serverId")
      track.originalId = readNullableString(obj, "originalId") ?: readNullableString(rawTrack, "originalId")
      track.albumId = readNullableString(rawTrack.optJSONObject("album"), "id")
      track.songId = toNativeSongId(track.sourceId)
      track.title = safeOptString(rawTrack, "title")
      track.artist = readArtists(rawTrack.optJSONArray("artists"))
      track.album = readAlbumName(rawTrack.optJSONObject("album"))
      track.coverUrl = safeOptString(rawTrack, "cover")
      track.durationMs = rawTrack.optDouble("duration", 0.0).toLong()
      track.canLike = isLikableSource(track.source)
      track.liked = obj.optBoolean("liked", false)
      track.playListIndex = obj.optInt("playListIndex", -1)
      track.skipSong = obj.optBoolean("skipSong", false)
      track.url = readNullableString(obj, "url") ?: readDirectUrl(track)
      return track
    }

    val track = PlaybackQueue.Track()
    track.source = safeOptString(obj, "source")
    track.sourceId = safeOptString(obj, "sourceId", safeOptString(obj, "songId"))
    track.path = readNullableString(obj, "path")
    track.serverId = readNullableString(obj, "serverId")
    track.originalId = readNullableString(obj, "originalId")
    track.albumId = readNullableString(obj, "albumId")
    track.songId = obj.optLong("songId", 0L)
    track.title = safeOptString(obj, "title")
    track.artist = safeOptString(obj, "artist")
    track.album = safeOptString(obj, "album")
    track.coverUrl = safeOptString(obj, "coverUrl")
    track.durationMs = obj.optDouble("durationMs", 0.0).toLong()
    track.canLike = obj.optBoolean("canLike", false)
    track.liked = obj.optBoolean("liked", false)
    track.playListIndex = obj.optInt("playListIndex", -1)
    track.skipSong = obj.optBoolean("skipSong", false)
    track.url = readNullableString(obj, "url")
    return track
  }

  /** 解析 fmRecentSongIds 数组为 Long 列表（FM 播种去重用），缺失返回空列表。 */
  private fun readLongArray(
    payload: JSONObject,
    key: String,
  ): List<Long> {
    val array = payload.optJSONArray(key) ?: return emptyList()
    val out = ArrayList<Long>(array.length())
    for (i in 0 until array.length()) {
      val value = array.optDouble(i, Double.NaN)
      if (value.isFinite() && value > 0) {
        out.add(value.toLong())
      }
    }
    return out
  }

  /** 解析 pluginSources 映射（wy/tx/kg → 可用插件 ID 有序列表），缺失返回空映射。 */
  private fun readPluginSources(payload: JSONObject): Map<String, List<String>> {
    val obj = payload.optJSONObject("pluginSources") ?: return emptyMap()
    val out = HashMap<String, List<String>>()
    for (key in obj.keys()) {
      val array = obj.optJSONArray(key) ?: continue
      val ids = ArrayList<String>(array.length())
      for (i in 0 until array.length()) {
        val id = array.optString(i, "")
        if (id.isNotEmpty()) ids.add(id)
      }
      if (ids.isNotEmpty()) out[key] = ids
    }
    return out
  }

  private fun readDirectUrl(track: PlaybackQueue.Track): String? =
    when (track.source) {
      "local" -> track.path
      else -> null
    }

  private fun readArtists(artists: JSONArray?): String {
    if (artists == null || artists.length() == 0) return ""
    val names = ArrayList<String>()
    for (i in 0 until artists.length()) {
      val artist = artists.optJSONObject(i) ?: continue
      val name = safeOptString(artist, "name").trim()
      if (name.isNotEmpty()) names.add(name)
    }
    return names.joinToString("/")
  }

  private fun readAlbumName(album: JSONObject?): String {
    if (album == null) return ""
    return safeOptString(album, "name")
  }

  private fun safeOptString(
    obj: JSONObject,
    key: String,
    fallback: String = "",
  ): String {
    if (obj.isNull(key)) return fallback
    return obj.optString(key, fallback)
  }

  private fun readNullableString(
    obj: JSONObject,
    key: String,
  ): String? {
    if (obj.isNull(key)) return null
    val value = obj.optString(key, "")
    return if (value.isEmpty() || value == "null") null else value
  }

  private fun toNativeSongId(id: String): Long {
    if (id.isEmpty()) return 0L
    return try {
      val value = id.toDouble()
      if (value.isFinite() && value > 0) value.toLong() else 0L
    } catch (ignored: NumberFormatException) {
      0L
    }
  }

  private fun isLikableSource(source: String): Boolean = source.isNotEmpty() && source != "local" && source != "streaming"

  private fun normalizeSongLevel(level: String): String =
    when (level) {
      "lq" -> "standard"
      "sq" -> "higher"
      "hq" -> "exhigh"
      "hi-res" -> "hires"
      else -> level.ifEmpty { "exhigh" }
    }

  private fun normalizeRepeatMode(mode: String): String =
    when (mode) {
      "list", "all" -> "all"
      "one" -> "one"
      else -> "off"
    }
}
