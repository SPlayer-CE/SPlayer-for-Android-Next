package top.imsyy.splayer_next.android.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidLyricFilenameIdTest {
  @Test
  fun extractsIdFromPureNumericName() {
    assertEquals("123456", extractLyricFilenameId("123456.ttml"))
  }

  @Test
  fun extractsIdFromNameWithIdSuffix() {
    assertEquals("123456", extractLyricFilenameId("SongName.123456.lrc"))
  }

  @Test
  fun extractsIdFromMultiDotName() {
    assertEquals("123456", extractLyricFilenameId("Song.Name.123456.yrc"))
  }

  @Test
  fun returnsNullWithoutNumericId() {
    assertNull(extractLyricFilenameId("plain.lrc"))
    assertNull(extractLyricFilenameId("SongName.abc.lrc"))
  }

  @Test
  fun returnsNullWhenIdTooShort() {
    assertNull(extractLyricFilenameId("Song.1.lrc"))
  }
}
