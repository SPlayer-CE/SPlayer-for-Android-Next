package top.imsyy.splayer_next.android.playback

import java.util.ArrayList

/**
 * Java 端权威播放队列（全量）。WebView 冻结时仍能自主切歌。
 *
 * <p>JS 端推全量队列（`updateQueueContext`，含增删移/洗牌/恢复），Java 端独立完成推进、
 * wrap 与 FM 续池，不再依赖 JS 回填。不线程安全，依赖 PlaybackManager 的 synchronized。
 */
class PlaybackQueue {
  /** 完整队列（已按 shuffleMode 排过序） */
  private var tracks: List<Track> = emptyList()

  /** 当前正在播的曲目在 tracks 中的索引；-1 表示队列空 */
  private var currentIndex = -1

  /** 循环模式：跨歌曲行为由此决定 */
  private var repeatMode = RepeatMode.OFF

  /** personalFM 模式：队列耗尽时由上层调 FmFetcher 续池 */
  private var personalFmMode = false

  enum class RepeatMode {
    OFF,
    ALL,
    ONE,
    ;

    companion object {
      fun fromString(value: String?): RepeatMode {
        if (value == null) return OFF
        return when (value) {
          "all" -> ALL
          "one" -> ONE
          else -> OFF
        }
      }
    }
  }

  /** 单首曲目元数据 + 已解析 URL。 */
  class Track {
    /** 内部 songId。必须 long：2024+ 部分 ID 超过 Integer.MAX_VALUE，int 会溢出为负。 */
    var songId: Long = 0
    var source: String = ""
    var sourceId: String = ""
    var path: String? = null
    var serverId: String? = null
    var originalId: String? = null
    var albumId: String? = null
    var durationMs: Long = 0
    var canLike: Boolean = false
    var liked: Boolean = false
    var title: String = ""
    var artist: String = ""
    var album: String = ""
    var coverUrl: String = ""

    /** 已解析的播放 URL；null 表示尚未解析（Java 端会按需通过 UrlResolver 补齐）。 */
    var url: String? = null

    /** 该曲目在 JS 端 playList 中的实际索引，回调时让 JS 直接定位 */
    var playListIndex: Int = -1

    /**
     * 显式跳过标志：JS 端 shouldSkipSong（Fuck DJ Mode）置 true。
     *
     * <p>语义与 url==null 严格区分：
     * <ul>
     *   <li>url==null + skipSong=false → 还没 prefetch，Java 应主动解析后播放
     *   <li>skipSong=true → 用户屏蔽，Java 在 advance/back/peek 时直接跳过，不调上游解析接口
     * </ul>
     */
    var skipSong: Boolean = false

    fun copy(): Track {
      val c = Track()
      c.songId = songId
      c.source = source
      c.sourceId = sourceId
      c.path = path
      c.serverId = serverId
      c.originalId = originalId
      c.albumId = albumId
      c.durationMs = durationMs
      c.canLike = canLike
      c.liked = liked
      c.title = title
      c.artist = artist
      c.album = album
      c.coverUrl = coverUrl
      c.url = url
      c.playListIndex = playListIndex
      c.skipSong = skipSong
      return c
    }

    fun playable(): Boolean = !url.isNullOrEmpty()
  }

  /** 替换整个队列（来自 JS 端 updateQueueContext）。 */
  @Synchronized
  fun replace(
    newTracks: List<Track>?,
    newCurrentIndex: Int,
    mode: RepeatMode?,
    personalFm: Boolean,
  ) {
    // 拷贝传入列表防御性隔离，避免外部 mutation 影响内部状态
    if (newTracks.isNullOrEmpty()) {
      tracks = emptyList()
      currentIndex = -1
    } else {
      val copy = ArrayList<Track>(newTracks.size)
      for (t in newTracks) {
        copy.add(t.copy())
      }
      tracks = copy
      // -1 透传：表示 JS 端尚未确定 current（如列表瞬时清空），不强制视为 0；
      // 否则 favorite/同步等会作用在错误的"队列首曲"上。
      currentIndex =
        when {
          newCurrentIndex < 0 -> -1
          newCurrentIndex >= copy.size -> copy.size - 1
          else -> newCurrentIndex
        }
    }
    repeatMode = mode ?: RepeatMode.OFF
    personalFmMode = personalFm
  }

  /** 追加曲目（FM 续池场景），返回追加后的尾部首个新曲目索引。 */
  @Synchronized
  fun append(newTracks: List<Track>): Int {
    if (newTracks.isEmpty()) return tracks.size
    val copy = ArrayList(tracks)
    val firstAppended = copy.size
    for (t in newTracks) {
      copy.add(t.copy())
    }
    tracks = copy
    return firstAppended
  }

  /** 当前曲目（可能为 null：队列空 / index 越界）。 */
  @Synchronized
  fun current(): Track? {
    if (currentIndex < 0 || currentIndex >= tracks.size) return null
    return tracks[currentIndex]
  }

  /** 指定索引的曲目（越界返回 null），供 playIndexAt 使用。 */
  @Synchronized
  fun trackAt(index: Int): Track? {
    if (index < 0 || index >= tracks.size) return null
    return tracks[index]
  }

  /**
   * 锚定当前游标到指定索引（点击任意歌曲入口）。
   * 不锚定会导致后续 advanceRaw 跳曲、预取 upcoming 仍以旧曲目为基准：
   * 解析失败跳错目标、next/prev 从旧位置推进。
   */
  @Synchronized
  fun setCurrentIndex(index: Int) {
    if (index < 0 || index >= tracks.size) return
    currentIndex = index
  }

  /**
   * 推进下一首；跳过 skipSong=true 的曲目（Fuck DJ 等用户级屏蔽），
   * 但不跳过 url==null（让 UrlResolver 后台解析）。
   *
   * <p>队列右缘耗尽时：普通模式无条件 wrap 到首个非 skip 曲目（对齐 JS nextTrack
   * 手动/自动到末尾均回列表开头的行为）；FM 模式返回 null 让上层调 FmFetcher 续池，
   * 否则会永远循环重播同一个小批次。
   *
   * @param respectRepeatOne ENDED 调用传 true（响应单曲循环），用户 NEXT 传 false
   */
  @Synchronized
  fun advanceRaw(respectRepeatOne: Boolean): Track? {
    if (tracks.isEmpty()) return null
    if (respectRepeatOne && repeatMode == RepeatMode.ONE) {
      return current()
    }
    var probe = currentIndex + 1
    while (probe < tracks.size) {
      val t = tracks[probe]
      if (!t.skipSong) {
        currentIndex = probe
        return t
      }
      probe++
    }
    if (personalFmMode) return null
    // 普通队列右缘耗尽：wrap 从头扫描首个非 skip 曲目（skipSong 全屏蔽时返回 null 终止）
    for (i in tracks.indices) {
      val t = tracks[i]
      if (!t.skipSong) {
        currentIndex = i
        return t
      }
    }
    return null
  }

  /**
   * 后退一首；同样跳过 skipSong=true 的曲目。
   *
   * <p>队列前缘耗尽时：普通模式 wrap 到尾部（对齐 JS prevTrack 的 queueLength-1 回绕）；
   * FM 模式返回 null（FM 无上一首语义，上层忽略 previous）。
   */
  @Synchronized
  fun backRaw(): Track? {
    if (tracks.isEmpty()) return null
    var probe = currentIndex - 1
    while (probe >= 0) {
      val t = tracks[probe]
      if (!t.skipSong) {
        currentIndex = probe
        return t
      }
      probe--
    }
    if (personalFmMode) return null
    // 前缘耗尽：wrap 到尾部（对齐 JS prevTrack 的 queueLength-1 回绕）
    for (i in tracks.indices.reversed()) {
      val t = tracks[i]
      if (!t.skipSong) {
        currentIndex = i
        return t
      }
    }
    return null
  }

  /** 从当前 index 之后取 N 首 url==null 且 !skipSong 的曲目；UrlResolver 据此后台批量预解析。 */
  @Synchronized
  fun peekUpcomingUnresolved(count: Int): List<Track> {
    val out = ArrayList<Track>(count)
    if (tracks.isEmpty() || currentIndex < 0) return out
    for (i in currentIndex + 1 until tracks.size) {
      if (out.size >= count) break
      val t = tracks[i]
      if (t.url == null && !t.skipSong) out.add(t)
    }
    return out
  }

  /**
   * 取队列内当前曲目之后 N 首已 resolved 的 URL 列表（!skipSong）。
   * 供 Java 端音频字节预载用：锁屏 / WebView 冻结时仍能保证下一首切歌秒响。
   */
  @Synchronized
  fun peekUpcomingResolvedUrls(count: Int): List<String> {
    val out = ArrayList<String>(count)
    if (tracks.isEmpty() || currentIndex < 0) return out
    for (i in currentIndex + 1 until tracks.size) {
      if (out.size >= count) break
      val t = tracks[i]
      if (!t.url.isNullOrEmpty() && !t.skipSong) out.add(t.url!!)
    }
    return out
  }

  /** 用 songId 在队列里查找 Track 的当前 URL；找不到 / 未解析返 null。 */
  @Synchronized
  fun findUrlBySongId(songId: Long): String? {
    for (t in tracks) {
      if (t.songId == songId) {
        return t.url
      }
    }
    return null
  }

  @Synchronized
  fun findUrlForTrack(track: Track): String? {
    if (track.playListIndex >= 0) {
      for (t in tracks) {
        if (t.playListIndex == track.playListIndex) return t.url
      }
    }
    if (track.songId > 0) return findUrlBySongId(track.songId)
    return null
  }

  /** 用 songId 在队列里查找 Track 并就地写回 url（UrlResolver 解析完成回调用）。 */
  @Synchronized
  fun updateTrackUrl(
    songId: Long,
    url: String?,
  ): Boolean {
    if (url.isNullOrEmpty()) return false
    for (t in tracks) {
      if (t.songId == songId) {
        t.url = url
        return true
      }
    }
    return false
  }

  @Synchronized
  fun updateTrackUrl(
    track: Track,
    url: String?,
  ): Boolean {
    if (url.isNullOrEmpty()) return false
    if (track.playListIndex >= 0) {
      for (t in tracks) {
        if (t.playListIndex == track.playListIndex) {
          t.url = url
          return true
        }
      }
    }
    if (track.songId > 0) return updateTrackUrl(track.songId, url)
    return false
  }

  @Synchronized
  fun isPersonalFm(): Boolean = personalFmMode

  @Synchronized
  fun getRepeatMode(): RepeatMode = repeatMode

  @Synchronized
  fun isEmpty(): Boolean = tracks.isEmpty()

  @Synchronized
  fun size(): Int = tracks.size

  /** 队列内全部 songId 快照（FM 续池去重用）。 */
  @Synchronized
  fun songIdSnapshot(): Set<Long> {
    val out = HashSet<Long>(tracks.size)
    for (t in tracks) {
      if (t.songId > 0) out.add(t.songId)
    }
    return out
  }
}
