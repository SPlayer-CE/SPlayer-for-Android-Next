package top.imsyy.splayer_next.android.playback

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FmFetcher 解析测试：字段兼容 / exclude 过滤 / 批内去重。 */
class FmFetcherTest {
  private val fetcher = FmFetcher()

  @Test
  fun `parse handles ar-al-dt netease shape`() {
    val body =
      JSONObject()
        .put(
          "data",
          org.json
            .JSONArray()
            .put(
              JSONObject()
                .put("id", 100L)
                .put("name", "Song A")
                .put(
                  "ar",
                  org.json
                    .JSONArray()
                    .put(JSONObject().put("id", 1L).put("name", "Artist One"))
                    .put(JSONObject().put("id", 2L).put("name", "Artist Two")),
                ).put("al", JSONObject().put("id", 9L).put("name", "Album X").put("picUrl", "http://a/1.jpg"))
                .put("dt", 180000L),
            ),
        ).toString()
    val tracks = fetcher.parseResponse(body, emptySet())
    assertEquals(1, tracks.size)
    val t = tracks[0]
    assertEquals(100L, t.songId)
    assertEquals("netease", t.source)
    assertEquals("100", t.sourceId)
    assertEquals("Song A", t.title)
    assertEquals("Artist One/Artist Two", t.artist)
    assertEquals("Album X", t.album)
    assertEquals("9", t.albumId)
    assertEquals("http://a/1.jpg", t.coverUrl)
    assertEquals(180000L, t.durationMs)
    assertEquals(-1, t.playListIndex)
    assertTrue(t.canLike)
  }

  @Test
  fun `parse handles artists-album-duration legacy shape`() {
    val body =
      JSONObject()
        .put(
          "data",
          org.json
            .JSONArray()
            .put(
              JSONObject()
                .put("id", 200L)
                .put("name", "Song B")
                .put("artists", org.json.JSONArray().put(JSONObject().put("name", "Solo")))
                .put("album", JSONObject().put("id", 8L).put("name", "Album Y"))
                .put("duration", 240000L),
            ),
        ).toString()
    val tracks = fetcher.parseResponse(body, emptySet())
    assertEquals(1, tracks.size)
    val t = tracks[0]
    assertEquals("Solo", t.artist)
    assertEquals("Album Y", t.album)
    assertEquals(240000L, t.durationMs)
  }

  @Test
  fun `parse filters exclude ids and in-batch duplicates`() {
    val song = JSONObject().put("id", 300L).put("name", "Dup")
    val body =
      JSONObject()
        .put(
          "data",
          org.json
            .JSONArray()
            .put(JSONObject().put("id", 100L).put("name", "A"))
            .put(JSONObject().put("id", 200L).put("name", "Excluded"))
            .put(song)
            .put(JSONObject().put("id", 300L).put("name", "Dup Again"))
            .put(JSONObject().put("id", 0L).put("name", "Invalid")),
        ).toString()
    val tracks = fetcher.parseResponse(body, setOf(200L))
    assertEquals(listOf(100L, 300L), tracks.map { it.songId })
  }

  @Test
  fun `parse returns empty when data missing`() {
    assertTrue(fetcher.parseResponse("{}", emptySet()).isEmpty())
    assertTrue(fetcher.parseResponse(JSONObject().put("code", 301).toString(), emptySet()).isEmpty())
  }

  @Test(expected = org.json.JSONException::class)
  fun `parse throws on malformed body`() {
    fetcher.parseResponse("not json", emptySet())
  }
}
