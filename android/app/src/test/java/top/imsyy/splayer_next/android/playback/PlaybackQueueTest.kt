package top.imsyy.splayer_next.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PlaybackQueue 全量队列模式测试：推进 / wrap / 后退 / 追加 / 索引边界。 */
class PlaybackQueueTest {
  private fun track(
    id: Long,
    index: Int,
    skip: Boolean = false,
    url: String? = null,
  ): PlaybackQueue.Track {
    val t = PlaybackQueue.Track()
    t.songId = id
    t.source = "netease"
    t.sourceId = id.toString()
    t.playListIndex = index
    t.skipSong = skip
    t.url = url
    return t
  }

  @Test
  fun `advance at queue end wraps to first non-skip track`() {
    val queue = PlaybackQueue()
    queue.replace(
      listOf(track(1, 0), track(2, 1, skip = true), track(3, 2)),
      2,
      PlaybackQueue.RepeatMode.OFF,
      false,
    )
    val next = queue.advanceRaw(false)
    assertEquals(1L, next?.songId)
  }

  @Test
  fun `advance returns null at end in personal fm mode instead of wrapping`() {
    val queue = PlaybackQueue()
    queue.replace(
      listOf(track(1, 0), track(2, 1)),
      1,
      PlaybackQueue.RepeatMode.OFF,
      true,
    )
    // FM 批次耗尽必须返回 null 让上层触发续池，回绕会永远重播同批
    assertNull(queue.advanceRaw(false))
    assertNull(queue.advanceRaw(true))
  }

  @Test
  fun `back returns null at front in personal fm mode instead of wrapping`() {
    val queue = PlaybackQueue()
    queue.replace(
      listOf(track(1, 0), track(2, 1)),
      0,
      PlaybackQueue.RepeatMode.OFF,
      true,
    )
    assertNull(queue.backRaw())
  }

  @Test
  fun `advance skips skipSong tracks`() {
    val queue = PlaybackQueue()
    queue.replace(
      listOf(track(1, 0), track(2, 1, skip = true), track(3, 2)),
      0,
      PlaybackQueue.RepeatMode.OFF,
      false,
    )
    val next = queue.advanceRaw(false)
    assertEquals(3L, next?.songId)
  }

  @Test
  fun `advance respects repeat one only when asked`() {
    val queue = PlaybackQueue()
    queue.replace(
      listOf(track(1, 0), track(2, 1)),
      0,
      PlaybackQueue.RepeatMode.ONE,
      false,
    )
    assertEquals(1L, queue.advanceRaw(true)?.songId)
    assertEquals(2L, queue.advanceRaw(false)?.songId)
  }

  @Test
  fun `advance returns null when all tracks skipped`() {
    val queue = PlaybackQueue()
    queue.replace(
      listOf(track(1, 0, skip = true), track(2, 1, skip = true)),
      -1,
      PlaybackQueue.RepeatMode.OFF,
      false,
    )
    assertNull(queue.advanceRaw(false))
  }

  @Test
  fun `back wraps to last non-skip track at front edge`() {
    val queue = PlaybackQueue()
    queue.replace(
      listOf(track(1, 0), track(2, 1, skip = true), track(3, 2)),
      0,
      PlaybackQueue.RepeatMode.OFF,
      false,
    )
    val prev = queue.backRaw()
    assertEquals(3L, prev?.songId)
  }

  @Test
  fun `append adds tracks and advance continues into them`() {
    val queue = PlaybackQueue()
    queue.replace(
      listOf(track(1, 0)),
      0,
      PlaybackQueue.RepeatMode.OFF,
      true,
    )
    val firstAppended = queue.append(listOf(track(2, -1), track(3, -1)))
    assertEquals(1, firstAppended)
    assertEquals(2L, queue.advanceRaw(false)?.songId)
    assertEquals(3L, queue.advanceRaw(false)?.songId)
  }

  @Test
  fun `empty queue advance and back return null`() {
    val queue = PlaybackQueue()
    queue.replace(null, -1, PlaybackQueue.RepeatMode.OFF, false)
    assertNull(queue.advanceRaw(false))
    assertNull(queue.backRaw())
    assertTrue(queue.isEmpty())
  }

  @Test
  fun `currentIndex is clamped to valid range`() {
    val queue = PlaybackQueue()
    queue.replace(
      listOf(track(1, 0), track(2, 1)),
      5,
      PlaybackQueue.RepeatMode.OFF,
      false,
    )
    assertEquals(2L, queue.current()?.songId)
  }

  @Test
  fun `trackAt respects bounds`() {
    val queue = PlaybackQueue()
    queue.replace(
      listOf(track(1, 0), track(2, 1)),
      0,
      PlaybackQueue.RepeatMode.OFF,
      false,
    )
    assertEquals(1L, queue.trackAt(0)?.songId)
    assertNull(queue.trackAt(-1))
    assertNull(queue.trackAt(2))
  }

  @Test
  fun `songIdSnapshot collects positive ids`() {
    val queue = PlaybackQueue()
    queue.replace(
      listOf(track(1, 0), track(0, 1), track(3, 2)),
      0,
      PlaybackQueue.RepeatMode.OFF,
      false,
    )
    assertEquals(setOf(1L, 3L), queue.songIdSnapshot())
  }
}
