package top.imsyy.splayer_next.android.lyric

import com.getcapacitor.JSObject
import java.text.BreakIterator
import java.util.Locale
import java.util.regex.Pattern

data class SegmentedDisplayWord(
  val word: NativeLyricWord,
  val leadingSpace: Boolean,
  val chunkId: Int,
  val chunkShouldEmphasize: Boolean,
)

internal object AndroidLyricWordSegmentation {
  // M-3: BreakIterator 非线程安全,主线程单例复用,避免每行新建实例
  private val wordBoundaryIterator = BreakIterator.getWordInstance()

  private val WHITESPACE_RE = Regex("\\s")
  private val TOKEN_RE = Regex("\\s+|\\S+")

  fun buildDisplayWords(words: List<NativeLyricWord>): List<SegmentedDisplayWord> {
    val atoms = splitToAtoms(words)
    if (atoms.isEmpty()) return emptyList()

    // 对齐 PC 端 Intl.Segmenter：用 BreakIterator 按词边界重新分组，
    // 使 CJK 多字词（如"光芒"）能合并为同一 chunk，触发整体 emphasize 高光
    val chunks = mergeByWordBoundary(atoms)

    val result = mutableListOf<SegmentedDisplayWord>()
    var chunkId = 0
    var pendingSpace = false

    for (chunk in chunks) {
      val rawText = chunk.joinToString("") { it.word.word }
      if (rawText.isBlank()) {
        pendingSpace = true
        chunkId += 1
        continue
      }

      val trimmedWords =
        chunk.mapNotNull { atom ->
          val trimmed = atom.word.word.trim()
          if (trimmed.isEmpty()) {
            null
          } else {
            atom.word.copy(word = trimmed)
          }
        }
      if (trimmedWords.isEmpty()) {
        chunkId += 1
        continue
      }

      val chunkShouldEmphasize = shouldChunkEmphasize(trimmedWords)
      val hasLeadingSpace = pendingSpace || rawText != rawText.trimStart()

      // 对齐 AMLL：高光扫光逐源词进行，未演唱到的源词保持暗部；chunk 只作强调动画
      // 的时序单位（merged 时长 + 跨词全局 charDelay），不合并扫光单位
      for ((idx, atom) in trimmedWords.withIndex()) {
        result +=
          SegmentedDisplayWord(
            word = atom,
            leadingSpace = if (idx == 0) hasLeadingSpace else false,
            chunkId = chunkId,
            chunkShouldEmphasize = chunkShouldEmphasize,
          )
      }
      chunkId += 1
      pendingSpace = rawText != rawText.trimEnd()
    }

    return normalizeMonotonicWindows(result)
  }

  /**
   * 规整时窗为空间序单调不重叠：少数语种自动对齐音节的时窗常乱序、重叠、倒挂，
   * 直接驱动逐词扫光会出现后段先亮、多词同时播放。这里把每个词的时窗整体平移，
   * 使其开始不早于前一个词的结束（时长保留），保证扫光严格按从左到右推进；
   * 时窗本就单调的正常歌词不受影响
   */
  internal fun normalizeMonotonicWindows(words: List<SegmentedDisplayWord>): List<SegmentedDisplayWord> {
    if (words.size <= 1) return words
    var prevEnd = Long.MIN_VALUE
    return words.map { displayWord ->
      val word = displayWord.word
      val duration = word.endTime - word.startTime
      val start = if (word.startTime < prevEnd) prevEnd else word.startTime
      val end = if (start + duration > start) start + duration else start
      prevEnd = end
      if (start == word.startTime && end == word.endTime) {
        displayWord
      } else {
        displayWord.copy(word = word.copy(startTime = start, endTime = end))
      }
    }
  }

  private data class Atom(
    val word: NativeLyricWord,
    val isSpace: Boolean,
    val isRuby: Boolean,
    val isCjk: Boolean,
  )

  private fun splitToAtoms(words: List<NativeLyricWord>): List<Atom> {
    val result = mutableListOf<Atom>()

    for (w in words) {
      val content = w.word.trim()
      val isSpace = content.isEmpty()
      val hasRuby = w.ruby.isNotEmpty()

      if (isSpace || hasRuby) {
        result +=
          Atom(
            word = w.copy(),
            isSpace = isSpace,
            isRuby = hasRuby,
            isCjk = false,
          )
        continue
      }

      val parts = TOKEN_RE.findAll(w.word).map { it.value }.toList()
      val totalLength =
        w.word
          .replace(WHITESPACE_RE, "")
          .length
          .coerceAtLeast(1)
      val timeSpan = w.endTime - w.startTime
      val timePerUnit = timeSpan.toFloat() / totalLength.toFloat()

      var currentOffset = 0

      for (part in parts) {
        if (part.isBlank()) {
          val startTime = w.startTime + (currentOffset * timePerUnit).toLong()
          result +=
            Atom(
              word =
                NativeLyricWord(
                  word = part,
                  startTime = startTime,
                  endTime = startTime,
                  romanWord = "",
                  obscene = w.obscene,
                  ruby = emptyList(),
                ),
              isSpace = true,
              isRuby = false,
              isCjk = false,
            )
          continue
        }

        if (isCjkText(part) && part.length > 1 && w.romanWord.isBlank()) {
          for (char in part) {
            val startTime = w.startTime + (currentOffset * timePerUnit).toLong()
            result +=
              Atom(
                word =
                  NativeLyricWord(
                    word = char.toString(),
                    startTime = startTime,
                    endTime = startTime + timePerUnit.toLong(),
                    romanWord = "",
                    obscene = w.obscene,
                    ruby = emptyList(),
                  ),
                isSpace = false,
                isRuby = false,
                isCjk = true,
              )
            currentOffset += 1
          }
        } else {
          val partRealLen = part.length
          val startTime = w.startTime + (currentOffset * timePerUnit).toLong()
          val duration = (partRealLen * timePerUnit).toLong()
          result +=
            Atom(
              word =
                NativeLyricWord(
                  word = part,
                  startTime = startTime,
                  endTime = startTime + duration,
                  romanWord = w.romanWord,
                  obscene = w.obscene,
                  ruby = w.ruby,
                ),
              isSpace = false,
              isRuby = w.ruby.isNotEmpty(),
              isCjk = isCjkText(part),
            )
          currentOffset += partRealLen
        }
      }
    }

    return result
  }

  /**
   * 使用 BreakIterator 按词边界重新分组 atoms，对齐 PC 端 Intl.Segmenter 行为。
   *
   * BreakIterator.getWordInstance() 基于 ICU 词典，能识别 CJK 词汇边界
   * （如"光芒"识别为一个词），使 CJK 多字词拆分后能重新合并为同一 chunk，
   * 触发整体 emphasize 高光，与 PC 端行为一致。
   */
  private fun mergeByWordBoundary(atoms: List<Atom>): List<List<Atom>> {
    if (atoms.isEmpty()) return emptyList()

    val fullText = atoms.joinToString("") { it.word.word }
    // BreakIterator 非线程安全，同步访问单例
    val boundaries =
      synchronized(wordBoundaryIterator) {
        wordBoundaryIterator.setText(fullText)
        val list = mutableListOf<Pair<Int, Int>>()
        var segStart = wordBoundaryIterator.first()
        var segEnd = wordBoundaryIterator.next()
        while (segEnd != BreakIterator.DONE) {
          list.add(segStart to segEnd)
          segStart = segEnd
          segEnd = wordBoundaryIterator.next()
        }
        list
      }

    val result = mutableListOf<List<Atom>>()
    val group = mutableListOf<Atom>()

    var atomIdx = 0
    var actualLen = 0
    var expectedLen = 0

    for ((segStart, segEnd) in boundaries) {
      expectedLen += (segEnd - segStart)

      while (actualLen < expectedLen && atomIdx < atoms.size) {
        group += atoms[atomIdx]
        actualLen += atoms[atomIdx].word.word.length
        atomIdx++
      }

      if (actualLen == expectedLen) {
        // 将前导空白从分组中提出，对齐 PC 端逻辑
        while (group.size > 1 && group[0].word.word.isBlank()) {
          result += listOf(group.removeAt(0))
        }
        val chunk = if (group.size == 1) listOf(group[0]) else group.toList()
        result += chunk
        group.clear()
      }
    }

    // 处理剩余 atoms
    while (atomIdx < atoms.size) {
      result += listOf(atoms[atomIdx])
      atomIdx++
    }
    if (group.isNotEmpty()) {
      result += if (group.size == 1) listOf(group[0]) else group.toList()
    }

    return result
  }

  private fun shouldChunkEmphasize(words: List<NativeLyricWord>): Boolean {
    if (words.any { it.shouldEmphasize }) return true
    if (words.size <= 1) return false
    val mergedWord =
      NativeLyricWord(
        word = words.joinToString("") { it.word },
        startTime = words.minOf { it.startTime },
        endTime = words.maxOf { it.endTime },
      )
    return !isCjkText(mergedWord.word) && mergedWord.shouldEmphasize
  }

  internal fun isCjkText(text: String): Boolean {
    if (text.isEmpty()) return false
    var index = 0
    while (index < text.length) {
      val codePoint = text.codePointAt(index)
      if (!isCjkCodePoint(codePoint)) return false
      index += Character.charCount(codePoint)
    }
    return true
  }

  private fun isCjkCodePoint(codePoint: Int): Boolean {
    if (Character.isIdeographic(codePoint)) return true
    // CJK 部首/标点/假名/注音/统一表意文字 + Hangul 音节；兼容表意文字由 isIdeographic 覆盖，
    // 不能用宽区间，否则天城文/泰文/越南语带调字母（U+1E00 区）会被误判为 CJK 拆散组合符号
    return codePoint in 0x2E80..0x9FFF || codePoint in 0xAC00..0xD7A3
  }

  /**
   * 按字素簇拆分文本：组合标记（Mn/Mc/Me，如藏文元音符号、泰/越文声调符号）附着到前一簇，
   * 保证基字与组合符号作为一个整体参与 char 强调动画。
   * 若按码点拆开，孤立组合标记会单独测量（宽度趋近 0、渲染出虚线圆圈），
   * 且与基字分别拥有各自的缩放/浮动动画相位，音节部件互相抖动跳变
   */
  internal fun splitGraphemeClusters(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    val cluster = StringBuilder()
    var index = 0
    while (index < text.length) {
      val codePoint = text.codePointAt(index)
      if (cluster.isNotEmpty() && isCombiningMark(codePoint)) {
        cluster.appendCodePoint(codePoint)
      } else {
        if (cluster.isNotEmpty()) {
          result += cluster.toString()
          cluster.clear()
        }
        cluster.appendCodePoint(codePoint)
      }
      index += Character.charCount(codePoint)
    }
    if (cluster.isNotEmpty()) result += cluster.toString()
    return result
  }

  private fun isCombiningMark(codePoint: Int): Boolean {
    val type = Character.getType(codePoint)
    return type == Character.NON_SPACING_MARK.toInt() ||
      type == Character.COMBINING_SPACING_MARK.toInt() ||
      type == Character.ENCLOSING_MARK.toInt()
  }
}

internal object AndroidLyricMetadataParser {
  private val AMLL_META_PATTERN = Pattern.compile("<\\s*amll:meta\\b[^>]*>", Pattern.CASE_INSENSITIVE)
  private val LRC_META_PATTERN = Pattern.compile("^\\s*\\[([a-zA-Z]+):([^\\]]*)]\\s*$", Pattern.MULTILINE)
  private val AMLL_KEY_ATTR_PATTERN = Pattern.compile("\\bkey\\s*=\\s*(['\"])(.*?)\\1")
  private val AMLL_VALUE_ATTR_PATTERN = Pattern.compile("\\bvalue\\s*=\\s*(['\"])(.*?)\\1")

  /** meta 标签长度上限：超长标签内属性懒惰回溯最坏 O(n²)，超过上限直接跳过 */
  private const val MAX_META_TAG_LENGTH = 4096

  fun extractTtmlMetadata(ttml: String): LyricMetadata {
    val metadata = LyricMetadata()
    val metaMatcher = AMLL_META_PATTERN.matcher(ttml)
    while (metaMatcher.find()) {
      val tag = metaMatcher.group()
      if (tag.length > MAX_META_TAG_LENGTH) continue
      val key = readAttribute(tag, AMLL_KEY_ATTR_PATTERN)
      val value = readAttribute(tag, AMLL_VALUE_ATTR_PATTERN)
      if (key == null || value == null) continue
      metadata.put(key.trim(), decodeXmlAttribute(value).trim())
    }
    return metadata
  }

  fun extractLrcMetadata(lrc: String): LyricMetadata {
    val metadata = LyricMetadata()
    val matcher = LRC_META_PATTERN.matcher(lrc)
    while (matcher.find()) {
      val key = matcher.group(1)
      val value = matcher.group(2)
      if (key == null || value == null) continue
      metadata.putLrcTag(key.trim(), value.trim())
    }
    return metadata
  }

  private fun readAttribute(
    tag: String,
    attrPattern: Pattern,
  ): String? {
    val matcher = attrPattern.matcher(tag)
    if (!matcher.find()) return null
    return matcher.group(2)
  }

  private fun decodeXmlAttribute(value: String): String =
    value
      .replace("&quot;", "\"")
      .replace("&apos;", "'")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&amp;", "&")

  class LyricMetadata {
    var album: String? = null
    var musicName: String? = null
    var artists: String? = null
    var ncmMusicId: String? = null

    fun putLrcTag(
      key: String,
      value: String,
    ) {
      if (value.isEmpty()) return
      when (key.lowercase(Locale.ROOT)) {
        "ti" -> musicName = value
        "ar" -> artists = value
        "al" -> album = value
      }
    }

    fun put(
      key: String,
      value: String,
    ) {
      if (value.isEmpty()) return
      when (key) {
        "album" -> album = value
        "musicName" -> musicName = value
        "artists" -> artists = value
        "ncmMusicId" -> ncmMusicId = value
      }
    }

    fun toJSObject(): JSObject {
      val jsObject = JSObject()
      if (!album.isNullOrEmpty()) jsObject.put("album", album)
      if (!musicName.isNullOrEmpty()) jsObject.put("musicName", musicName)
      if (!artists.isNullOrEmpty()) jsObject.put("artists", artists)
      if (!ncmMusicId.isNullOrEmpty()) jsObject.put("ncmMusicId", ncmMusicId)
      return jsObject
    }
  }
}
