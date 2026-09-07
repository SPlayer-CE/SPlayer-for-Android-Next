package top.imsyy.splayer_next.android.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLyricWordSegmentationTest {
  @Test
  fun splitsWholeLineEnglishWordIntoDisplayWords() {
    val words =
      listOf(
        NativeLyricWord(
          word = "Hello world",
          startTime = 0L,
          endTime = 1000L,
        ),
      )

    val result = AndroidLyricWordSegmentation.buildDisplayWords(words)

    assertEquals(listOf("Hello", "world"), result.map { it.word.word })
    assertFalse(result[0].leadingSpace)
    assertTrue(result[1].leadingSpace)
    assertEquals(0L, result[0].word.startTime)
    assertEquals(500L, result[0].word.endTime)
    assertEquals(500L, result[1].word.startTime)
    assertEquals(1000L, result[1].word.endTime)
  }

  @Test
  fun splitsCjkWordIntoCharacters() {
    val words =
      listOf(
        NativeLyricWord(
          word = "我们",
          startTime = 100L,
          endTime = 900L,
        ),
      )

    val result = AndroidLyricWordSegmentation.buildDisplayWords(words)

    assertEquals(listOf("我", "们"), result.map { it.word.word })
    assertEquals(listOf(false, false), result.map { it.leadingSpace })
    assertEquals(100L, result[0].word.startTime)
    assertEquals(500L, result[0].word.endTime)
    assertEquals(500L, result[1].word.startTime)
    assertEquals(900L, result[1].word.endTime)
  }

  @Test
  fun keepsCjkWordWithRomanWordUnsplitted() {
    val words =
      listOf(
        NativeLyricWord(
          word = "我们",
          startTime = 0L,
          endTime = 1000L,
          romanWord = "wo men",
        ),
      )

    val result = AndroidLyricWordSegmentation.buildDisplayWords(words)

    assertEquals(listOf("我们"), result.map { it.word.word })
    assertEquals("wo men", result[0].word.romanWord)
  }

  @Test
  fun keepsRubyWordUnsplitted() {
    val words =
      listOf(
        NativeLyricWord(
          word = "今日",
          startTime = 0L,
          endTime = 1000L,
          ruby =
            listOf(
              NativeLyricSpan("きょう", 0L, 1000L),
            ),
        ),
      )

    val result = AndroidLyricWordSegmentation.buildDisplayWords(words)

    assertEquals(listOf("今日"), result.map { it.word.word })
    assertEquals(1, result[0].word.ruby.size)
  }

  @Test
  fun keepsSameChunkLatinWordsSeparateForPerWordSweep() {
    val words =
      listOf(
        NativeLyricWord(
          word = "su",
          startTime = 0L,
          endTime = 600L,
        ),
        NativeLyricWord(
          word = "gar",
          startTime = 600L,
          endTime = 1200L,
        ),
      )

    val result = AndroidLyricWordSegmentation.buildDisplayWords(words)

    // 对齐 AMLL：chunk 内每个文件词保持独立 display word，扫光/悬浮按词各自的时窗推进；
    // chunk 只聚合强调时序（chunkId / chunkShouldEmphasize），不合并词元
    assertEquals(listOf("su", "gar"), result.map { it.word.word })
    assertTrue(result.all { it.chunkShouldEmphasize })
    assertEquals(1, result.map { it.chunkId }.distinct().size)
    assertEquals(0L, result[0].word.startTime)
    assertEquals(600L, result[0].word.endTime)
    assertEquals(600L, result[1].word.startTime)
    assertEquals(1200L, result[1].word.endTime)
    assertFalse(result[1].leadingSpace)
  }

  @Test
  fun keepsNoSpaceLongToneLineOnPerWordSweep() {
    // 对齐 AMLL：无空格长音行（如 "fucklife~"）不做整行扫光合并，
    // 每个文件词保持独立 display word 与独立时窗，扫光逐段匀速、词间隙停顿
    val line =
      NativeLyricLine(
        words =
          listOf(
            NativeLyricWord("fuck", 0L, 800L),
            NativeLyricWord("life~", 800L, 4000L),
          ),
        translatedLyric = "",
        romanLyric = "",
        startTime = 0L,
        endTime = 4000L,
        isBG = false,
        isDuet = false,
      )

    val result = AndroidLyricWordSegmentation.buildDisplayWords(line.words)
    assertEquals(listOf("fuck", "life~"), result.map { it.word.word })
    assertEquals(0L, result[0].word.startTime)
    assertEquals(800L, result[0].word.endTime)
    assertEquals(800L, result[1].word.startTime)
    assertEquals(4000L, result[1].word.endTime)
    assertFalse(result[1].leadingSpace)
  }

  @Test
  fun normalizesDisorderedWindowsToMonotonicSpatialOrder() {
    // 少数语种自动对齐音节的脏数据形态：时窗乱序（后段先唱）、重叠、倒挂，
    // 规整后每个词的开始不早于前一个词的结束，时长保留，扫光按空间序推进
    val words =
      listOf(
        NativeLyricWord("ཀ", 1000L, 2000L),
        NativeLyricWord("ུ", 1200L, 1800L),
        NativeLyricWord("ན", 800L, 1400L),
        NativeLyricWord("ད", 2000L, 1500L),
      )

    val result = AndroidLyricWordSegmentation.buildDisplayWords(words)

    assertEquals(4, result.size)
    assertEquals(1000L, result[0].word.startTime)
    assertEquals(2000L, result[0].word.endTime)
    // 词 1 与词 0 重叠：平移到 [2000, 2600]
    assertEquals(2000L, result[1].word.startTime)
    assertEquals(2600L, result[1].word.endTime)
    // 词 2 开始早于词 1 结束：平移到 [2600, 3200]
    assertEquals(2600L, result[2].word.startTime)
    assertEquals(3200L, result[2].word.endTime)
    // 词 3 倒挂（时长为负）：钳为零时长 [3200, 3200]
    assertEquals(3200L, result[3].word.startTime)
    assertEquals(3200L, result[3].word.endTime)
  }

  @Test
  fun keepsMonotonicWindowsUntouched() {
    // 时窗本就单调的正常歌词：规整不改变任何时窗
    val words =
      listOf(
        NativeLyricWord("光", 0L, 600L),
        NativeLyricWord("芒", 600L, 1200L),
      )

    val result = AndroidLyricWordSegmentation.buildDisplayWords(words)

    assertEquals(0L, result[0].word.startTime)
    assertEquals(600L, result[0].word.endTime)
    assertEquals(600L, result[1].word.startTime)
    assertEquals(1200L, result[1].word.endTime)
  }

  @Test
  fun keepsTibetanCombiningMarksAttachedToBaseChar() {
    // ཀ (U+0F40) + ུ (U+0F74 组合元音) 必须保持同一字素簇，
    // 否则 char 强调动画把元音符号与基字分开驱动，音节部件互相抖动
    val clusters = AndroidLyricWordSegmentation.splitGraphemeClusters("\u0F40\u0F74")

    assertEquals(listOf("\u0F40\u0F74"), clusters)
    // 基字与基字之间正常拆分
    assertEquals(
      listOf("\u0F40", "\u0F42"),
      AndroidLyricWordSegmentation.splitGraphemeClusters("\u0F40\u0F42"),
    )
  }

  @Test
  fun attachesCombiningMarksAcrossScripts() {
    // 越南语/泰文等带调符号（Mn）同样附着到前一簇
    assertEquals(
      listOf("e\u0301", "a"),
      AndroidLyricWordSegmentation.splitGraphemeClusters("e\u0301a"),
    )
    // 拉丁/CJK 无组合符号：逐字符拆分，行为不变
    assertEquals(listOf("a", "b"), AndroidLyricWordSegmentation.splitGraphemeClusters("ab"))
    assertEquals(listOf("光", "芒"), AndroidLyricWordSegmentation.splitGraphemeClusters("光芒"))
  }

  @Test
  fun keepsLeadingCombiningMarkAsOwnCluster() {
    // 前导组合标记（无基字可附着）独立成簇，不崩溃不吞字
    assertEquals(
      listOf("\u0F74", "\u0F40"),
      AndroidLyricWordSegmentation.splitGraphemeClusters("\u0F74\u0F40"),
    )
  }
}
