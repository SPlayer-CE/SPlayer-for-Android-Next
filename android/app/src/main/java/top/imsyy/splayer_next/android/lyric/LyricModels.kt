package top.imsyy.splayer_next.android.lyric

/**
 * 主播放器歌词数据模型。
 *
 * 对齐 AMLL LyricLine/Word 数据结构；原为 MainPlayerLyricOverlayView 的嵌套类，
 * 提升为顶层后 Timeline/分词/插件可直接引用，View 内保留同名别名兼容。
 */
data class NativeLyricSpan(
  val word: String,
  val startTime: Long,
  val endTime: Long,
)

data class NativeLyricWord(
  val word: String,
  val startTime: Long,
  val endTime: Long,
  val romanWord: String = "",
  val obscene: Boolean = false,
  val ruby: List<NativeLyricSpan> = emptyList(),
) {
  val renderCacheKey = Any()
  val isCJK: Boolean by
    lazy(LazyThreadSafetyMode.NONE) {
      word.any { ch ->
        val type = Character.getType(ch)
        type == Character.OTHER_LETTER.toInt() || type == Character.MODIFIER_LETTER.toInt()
      }
    }

  // chunk 强调动画按全局字符序分配 charDelay，需要逐词 grapheme 数；lazy 避免每帧重切分
  val graphemeCount: Int by
    lazy(LazyThreadSafetyMode.NONE) {
      AndroidLyricWordSegmentation.splitGraphemeClusters(word).size
    }

  // 对齐 AMLL rubyCharCount：注音各段长度之和（UTF-16 语义），遮罩分段与 char 强调锚定共用
  val rubyCharCount: Int by
    lazy(LazyThreadSafetyMode.NONE) {
      if (ruby.isEmpty()) 0 else ruby.sumOf { span -> span.word.length }
    }

  // 注音拼接文本：布局测量与逐帧绘制共用，避免每帧 joinToString 分配
  val rubyText: String by
    lazy(LazyThreadSafetyMode.NONE) {
      if (ruby.isEmpty()) "" else ruby.joinToString("") { it.word }
    }

  // 对齐 AMLL anchorCharCount：char 强调锚定字符数优先取注音字符数
  val emphasizeCharCount: Int by
    lazy(LazyThreadSafetyMode.NONE) {
      if (rubyCharCount > 0) rubyCharCount else graphemeCount
    }
  val shouldEmphasize: Boolean by
    lazy(LazyThreadSafetyMode.NONE) {
      val duration = endTime - startTime
      if (duration < 1000) {
        false
      } else if (isCJK) {
        true
      } else {
        val len = word.trim().length
        len in 2..7
      }
    }
}

data class NativeLyricLine(
  val words: List<NativeLyricWord>,
  val translatedLyric: String,
  val romanLyric: String,
  val startTime: Long,
  val endTime: Long,
  val isBG: Boolean,
  val isDuet: Boolean,
  val language: String? = null,
) {
  val mainText: String by
    lazy(LazyThreadSafetyMode.NONE) {
      buildString { for (word in words) append(word.word) }
    }
  val displayWords: List<SegmentedDisplayWord> by
    lazy(LazyThreadSafetyMode.NONE) {
      AndroidLyricWordSegmentation.buildDisplayWords(words)
    }
}
