package top.imsyy.splayer_next.android.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidLocalLyricPathMapperTest {
  @Test
  fun mapsMediaStoreRelativePathInsideTree() {
    assertEquals(
      "Album",
      AndroidLocalLyricPathMapper.mapMediaParentToTreeRelativePath(
        "primary:Music",
        "Music/Album/",
      ),
    )
  }

  @Test
  fun mapsMediaStoreRelativePathAtTreeRoot() {
    assertEquals(
      "",
      AndroidLocalLyricPathMapper.mapMediaParentToTreeRelativePath(
        "primary:Music/Album",
        "Music/Album/",
      ),
    )
  }

  @Test
  fun mapsMediaStoreRelativePathWhenTreeIsStorageRoot() {
    assertEquals(
      "Music/Album",
      AndroidLocalLyricPathMapper.mapMediaParentToTreeRelativePath(
        "primary:",
        "Music/Album/",
      ),
    )
  }

  @Test
  fun rejectsMediaStoreRelativePathOutsideTree() {
    assertNull(
      AndroidLocalLyricPathMapper.mapMediaParentToTreeRelativePath(
        "primary:Lyrics",
        "Music/Album/",
      ),
    )
  }

  @Test
  fun rejectsBlankMediaStoreRelativePath() {
    assertNull(
      AndroidLocalLyricPathMapper.mapMediaParentToTreeRelativePath(
        "primary:",
        "",
      ),
    )
  }
}
