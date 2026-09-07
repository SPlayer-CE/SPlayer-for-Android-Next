package top.imsyy.splayer_next.android.lyric

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.text.Regex

internal data class StaticBreakUnit(
  val text: String,
  val width: Float,
  val isSpace: Boolean,
)

internal data class ActiveState(
  val activeLineIndices: Set<Int>,
  val anchorIndex: Int,
  val bufferedLineIndices: Set<Int> = emptySet(),
  /** 最靠后的高亮主行索引（视图索引空间），无高亮行时回退为 anchorIndex；对齐 AMLL visualFrame.latestIndex */
  val latestHighlightIndex: Int = -1,
) {
  private val scrollToIndex = bufferedLineIndices.minOrNull()
  private val latestIndex = bufferedLineIndices.maxOrNull()

  /**
   * 对齐 AMLL computeGroupPresentation.isActive = hasBuffered || inRange：
   * 行在 buffered 集合中，或在 [scrollToIndex, latestIndex) 范围内，均视为已呈现。
   * 注意：与 anchorIndex 不同，scrollToIndex 是 buffered 集合的最小索引（含 hot 行），
   * 这确保了当 A、C 同时在 buffered 中而 B 不在时，B 因索引落在 [A, C) 范围内而保持激活外观。
   */
  fun isPresented(index: Int): Boolean {
    if (activeLineIndices.contains(index) || bufferedLineIndices.contains(index)) return true
    val scrollToIndex = scrollToIndex ?: return false
    val latestIndex = latestIndex ?: return false
    return index >= scrollToIndex && index < latestIndex
  }
}

internal object AndroidLyricTimeline {
  // M-3: 句末标点正则编译开销大,提升为单例复用(原每行每函数重复编译)
  internal val PUNCTUATION_RE = Regex("[,.;:!?，。；：！？、）】》」』’”)\\]}>~…]$")

  // 对齐 AMLL base/spring.ts getPosYSpringPolicy：缓慢/中速档与间隔映射常量
  private const val POSY_SLOW_STIFFNESS = 90f
  private const val POSY_SLOW_DAMPING = 15f
  private const val POSY_MEDIUM_STIFFNESS = 140f
  private const val POSY_MEDIUM_DAMPING = 22f
  private const val POSY_MIN_INTERVAL_MS = 100f
  private const val POSY_MAX_INTERVAL_MS = 800f
  private const val POSY_MIN_STIFFNESS = 170f
  private const val POSY_MAX_STIFFNESS = 220f
  private const val POSY_DAMPING_MULTIPLIER = 2.2f
  private const val POSY_INTERVAL_EXPONENT = 0.2f

  /**
   * 歌词行纵向滚动的弹簧物理参数策略（对齐 AMLL getPosYSpringPolicy）
   *
   * Seek 和间奏用缓慢弹簧，歌曲结束用中速弹簧；正常播放按当前行与上一行的
   * 时间间隔动态映射：间隔越短歌词推进越急，弹簧越硬（170~220），
   * 阻尼取刚度的平方根 × 2.2 保持观感一致；间隔取 0.2 次方根偏向更快的速度。
   * 仅返回 stiffness/damping，mass 为 null 时由 SpringParams.merge 保留现有值（对齐 Partial 语义）
   *
   * @param isSeeking - 当前是否处于跳转状态
   * @param isInterludeActive - 当前是否命中间奏区间
   * @param intervalMs - 当前歌词行与上一行的开始时间差，首尾行无法提供时传 null
   * @param isEndOfSong - 歌曲是否播放完毕
   * @returns 弹簧参数（仅 stiffness/damping 生效）
   */
  fun resolvePosYSpringPolicy(
    isSeeking: Boolean,
    isInterludeActive: Boolean,
    intervalMs: Long?,
    isEndOfSong: Boolean,
  ): SpringParams {
    if (isSeeking || isInterludeActive) {
      return SpringParams(stiffness = POSY_SLOW_STIFFNESS, damping = POSY_SLOW_DAMPING)
    }
    if (isEndOfSong) {
      return SpringParams(stiffness = POSY_MEDIUM_STIFFNESS, damping = POSY_MEDIUM_DAMPING)
    }
    if (intervalMs == null) {
      return SpringParams(stiffness = POSY_SLOW_STIFFNESS, damping = POSY_SLOW_DAMPING)
    }
    val clampedInterval = intervalMs.toFloat().coerceIn(POSY_MIN_INTERVAL_MS, POSY_MAX_INTERVAL_MS)
    var ratio = 1f - (clampedInterval - POSY_MIN_INTERVAL_MS) / (POSY_MAX_INTERVAL_MS - POSY_MIN_INTERVAL_MS)
    ratio = ratio.pow(POSY_INTERVAL_EXPONENT)
    val stiffness = POSY_MIN_STIFFNESS + ratio * (POSY_MAX_STIFFNESS - POSY_MIN_STIFFNESS)
    val damping = sqrt(stiffness) * POSY_DAMPING_MULTIPLIER
    return SpringParams(stiffness = stiffness, damping = damping)
  }

  // 对齐 AMLL base/index.ts resolveBlurLevel / lyric-group.ts renderStyles：模糊上限与窄视口折扣
  private const val BLUR_MAX_LEVEL = 5f
  private const val BLUR_NARROW_VIEWPORT_FACTOR = 0.8f

  /**
   * 歌词行模糊档位策略（对齐 AMLL resolveBlurLevel + renderStyles 的 min(5, blur)）
   *
   * 视口外直接给到最大档位，行滚入视口时从模糊渐入；
   * 用户触摸滚动期间与焦点行不施加模糊；
   * 焦点之前的行距离额外 +1（唱完的行比未唱的行更模糊），焦点之后按距最新高亮行计算；
   * 档位为 1+距离，窄视口（CSS 宽 ≤1024）整体 0.8 折，上限 5
   *
   * @param enableBlur - 是否启用模糊
   * @param inViewport - 行是否在视口内
   * @param isUserScrolling - 用户是否正在触摸滚动
   * @param isFocused - 行是否为焦点行（对齐 AMLL resolveIsActive 判定）
   * @param index - 行索引
   * @param scrollToIndex - 自动滚动对齐的焦点行索引
   * @param latestIndex - 最靠后的高亮行索引
   * @param isNarrowViewport - 是否窄视口
   * @returns 模糊档位（CSS 像素语义，调用方负责换算物理像素）
   */
  fun resolveBlurTarget(
    enableBlur: Boolean,
    inViewport: Boolean,
    isUserScrolling: Boolean,
    isFocused: Boolean,
    index: Int,
    scrollToIndex: Int,
    latestIndex: Int,
    isNarrowViewport: Boolean,
  ): Float {
    if (!enableBlur) return 0f
    if (!inViewport) return BLUR_MAX_LEVEL
    if (isUserScrolling || isFocused) return 0f
    val distance =
      if (index < scrollToIndex) {
        abs(scrollToIndex - index) + 1f
      } else {
        abs(index - latestIndex).toFloat()
      }
    val level = 1f + distance
    val scaled = if (isNarrowViewport) level * BLUR_NARROW_VIEWPORT_FACTOR else level
    return min(BLUR_MAX_LEVEL, scaled)
  }

  fun resolveWordProgress(
    startTime: Long,
    endTime: Long,
    currentTimeMs: Long,
  ): Float {
    val duration = maxOf(1L, endTime - startTime)
    return ((currentTimeMs - startTime).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
  }

  fun isProgressJump(
    incomingTimeMs: Long,
    visibleTimeMs: Long,
  ): Boolean = incomingTimeMs < visibleTimeMs - 100L || incomingTimeMs > visibleTimeMs + 2000L

  fun resolveProgressAnchor(
    incomingTimeMs: Long,
    visibleTimeMs: Long,
    wasPlaying: Boolean,
    playing: Boolean,
    isJump: Boolean,
  ): Long =
    if (wasPlaying && playing && !isJump) {
      maxOf(incomingTimeMs, visibleTimeMs)
    } else {
      incomingTimeMs
    }

  /**
   * 对齐 Web 引擎 word-builder.ts 的逐词独立遮罩模型：由该段自身已推进行程推导渐变带左缘。
   *
   * Web 中每个词的 mask-position 是一条独立表达式，行程窗口前钳全暗、窗口后钳全亮，
   * 换算到行坐标系后渐变带左缘为 `wordX - fadeWidth + segmentTravel`。
   * 段行程只依赖自身时间窗（见 [computeSegmentTravel]），行内时窗乱序、
   * 重叠的词各自独立推进，互不干扰。
   *
   * @param wordX - 渐变段首词文本左缘 x（行坐标；每段对应一个歌词文件词或合并段）
   * @param fadeWidth - 渐变过渡带像素宽度
   * @param segmentTravel - 该段当前已推进的独立行程（见 [computeSegmentTravel]）
   * @returns 渐变带左端 x，配合调用方的全暗/全亮边界判定
   */
  fun resolveWordGradientStartX(
    wordX: Float,
    fadeWidth: Float,
    segmentTravel: Float,
  ): Float = wordX - fadeWidth + segmentTravel

  /**
   * 对齐 AMLL ruby 遮罩分支：注音词第 j 个字符取 rubySegment[min(j, last)] 的时间窗，
   * clamp 进词区间并强制单调递增（后段开始不早于前段结束），
   * 供逐段独立遮罩行程（computeSegmentTravel）按序消费
   *
   * @param rubySpans - 词的注音分段
   * @param wordStartMs - 词开始时间（毫秒）
   * @param wordEndMs - 词结束时间（毫秒）
   * @param charCount - 注音字符总数（各段长度之和）
   * @returns 按字符序排列的 (段开始, 段结束) 时间窗列表，毫秒
   */
  internal fun buildRubySweepWindows(
    rubySpans: List<NativeLyricSpan>,
    wordStartMs: Long,
    wordEndMs: Long,
    charCount: Int,
  ): List<Pair<Long, Long>> {
    if (charCount <= 0 || rubySpans.isEmpty()) return emptyList()
    val result = ArrayList<Pair<Long, Long>>(charCount)
    var prevEnd = wordStartMs
    for (j in 0 until charCount) {
      val span = rubySpans[min(j, rubySpans.lastIndex)]
      val start = maxOf(wordStartMs, minOf(prevEnd, span.startTime))
      val end = maxOf(start, minOf(wordEndMs, maxOf(prevEnd, span.endTime)))
      result += start to end
      prevEnd = end
    }
    return result
  }

  /**
   * 对齐 Web 引擎 word-builder.ts measureAndApplyWordMasks 的逐词独立遮罩推进。
   *
   * Web 中每个词是一条独立的 CSS mask-position 表达式：
   * `clamp(startPos, startPos + (t - adjustedStart) * speed, endPos)`，
   * speed = (词宽 + 渐变宽) / 词时长。这里求其闭式解：
   * - 行程 d(t) = (t - adjustedStart) × speed，钳制在 [0, amount]；
   * - 词播完钳在 amount（全亮保持），窗口前钳 0（全暗）；
   * - preRoll = min(80ms, 词长 × 0.3)，让渐变带在词开始前提前进入，
   *   相邻词亮区平滑衔接而非硬切（对齐 Web）；
   * - 零时长段窗口内瞬时完成。
   *
   * 与整行统一行程模型的区别：段的行程只依赖自身时间窗，行内时窗乱序、
   * 重叠、倒挂（少数语种自动对齐音节的常见数据形态）均不影响彼此推进。
   *
   * @param startMs - 段开始时间（毫秒）
   * @param endMs - 段结束时间（毫秒）
   * @param currentTimeMs - 当前播放时间（毫秒）
   * @param amount - 段总行程（段宽 + 渐变宽，像素）
   * @param lineStartMs - 行开始时间，preRoll 钳制起扫点不早于行开始
   * @returns 该段当前已推进的行程，范围 [0, amount]
   */
  fun computeSegmentTravel(
    startMs: Long,
    endMs: Long,
    currentTimeMs: Long,
    amount: Float,
    lineStartMs: Long,
  ): Float {
    if (amount <= 0f) return 0f
    val wordDuration = if (endMs - startMs > 0L) endMs - startMs else 1L
    val preRollMs = min(80f, wordDuration * 0.3f).toLong()
    val adjustedStart = maxOf(lineStartMs, startMs - preRollMs)
    val adjustedDuration = maxOf(1L, endMs - adjustedStart)
    val speed = amount / adjustedDuration
    return ((currentTimeMs - adjustedStart) * speed).coerceIn(0f, amount)
  }

  /**
   * 对静态文本（翻译、罗马音）做均衡换行。
   * 基于累计宽度前缀和 DP，优先在空格/标点处断开，避免行尾空白；
   * CJK 字符允许逐字换行，英文按词换行。
   */
  fun calcBalancedBreaks(
    units: List<StaticBreakUnit>,
    maxWidth: Float,
  ): List<Int> {
    val count = units.size
    if (count == 0 || maxWidth <= 0f) return emptyList()
    val prefixWidth = FloatArray(count + 1)
    for (i in 0 until count) prefixWidth[i + 1] = prefixWidth[i] + units[i].width
    if (prefixWidth[count] <= maxWidth) return emptyList()

    val dp = FloatArray(count + 1) { Float.POSITIVE_INFINITY }
    val nextBreak = IntArray(count + 1) { -1 }
    dp[count] = 0f
    val cjkPenalty = Math.pow((maxWidth * 0.15f).toDouble(), 2.0).toFloat()
    val normalPenalty = Math.pow((maxWidth * 0.5f).toDouble(), 2.0).toFloat()
    val spaceReward = Math.pow((maxWidth * 0.4f).toDouble(), 2.0).toFloat()
    val punctuationReward = Math.pow((maxWidth * 0.6f).toDouble(), 2.0).toFloat()
    val punctuationRe = PUNCTUATION_RE
    for (i in count - 1 downTo 0) {
      for (j in i + 1..count) {
        val lineWidth = prefixWidth[j] - prefixWidth[i]
        val lineCost =
          if (lineWidth > maxWidth) {
            if (j == i + 1) Math.pow((lineWidth - maxWidth).toDouble(), 2.0).toFloat() * 1000f else break
          } else {
            Math.pow((maxWidth - lineWidth).toDouble(), 2.0).toFloat()
          }
        val previous = units[j - 1]
        val breakCost =
          when {
            j >= count -> 0f
            punctuationRe.containsMatchIn(previous.text) -> -punctuationReward
            previous.isSpace -> -spaceReward
            j < count && AndroidLyricWordSegmentation.isCjkText(units[j].text) -> cjkPenalty
            else -> normalPenalty
          }
        val total = lineCost + breakCost + dp[j]
        if (total < dp[i]) {
          dp[i] = total
          nextBreak[i] = j
        }
      }
    }

    val breaks = mutableListOf<Int>()
    var current = 0
    while (current < count) {
      current = nextBreak[current]
      if (current <= 0) break
      if (current < count) breaks += current
    }
    return breaks
  }
}

/**
 * 判定为间奏所需的最小空隙时长
 */
internal const val MIN_INTERLUDE_GAP_MS = 4000L

/**
 * 用于进度计算的最小歌词数据
 *
 * @param startTime - 行开始时间（毫秒）
 * @param endTime - 行结束时间（毫秒）
 */
internal data class TimeBounds(
  val startTime: Long,
  val endTime: Long,
)

/**
 * 当前命中的间奏区间信息
 *
 * @param startTime - 间奏开始时间，即此前全部歌词行中最晚的结束时间
 * @param endTime - 间奏结束时间，即间奏后第一行歌词的开始时间
 * @param anchorLineIndex - 间奏点应插入的位置基准，即显示顺序上间奏前最后一行的索引，`-1` 表示第一句之前
 */
internal data class PlayerInterlude(
  val startTime: Long,
  val endTime: Long,
  val anchorLineIndex: Int,
)

/**
 * 当前播放时间线状态的只读快照
 *
 * 用于给 UI 执行排版和计算各种歌词行的效果。
 * 在获取快照后，必须在同一帧内消费完毕，切勿保留其引用，因为下一帧就会被原地覆写；
 * 集合字段是控制器内部可变集合的只读视图，随 sync 原地更新。
 */
internal class TimelineSnapshot {
  /** 当前时间推导所依据的绝对播放时间，供间奏点动画等作为基准时间戳 */
  var currentTime: Long = 0L

  /** 当前进度命中的、正在播放的歌词组（控制器索引空间） */
  var playingGroups: Set<Int> = emptySet()
    internal set

  /** 正在高亮的歌词组：唱完的行不会自行熄灭，而是保持高亮直到下一行开始播放 */
  var highlightedGroups: Set<Int> = emptySet()
    internal set

  /**
   * 自动滚动应该对齐到哪一行歌词（高亮行中最靠前的）
   * 仅在 [isFocusOnInterlude] 为 `false` 时有效；间奏期间仍指向间奏前的那一组
   */
  var scrollToIndex: Int = 0

  /**
   * 处于高亮状态的歌词行中最靠后的一行；`-1` 表示当前没有任何高亮行
   * （首行开始之前、间奏区间内、歌曲播放完毕、或已开始的行均为零时长的情况）
   */
  var latestHighlightedIndex: Int = -1

  /** 标识歌曲是否播放完毕，即当前时间已越过全部歌词行中最晚的结束时间 */
  var isEndOfSong: Boolean = false

  /** 当前命中的间奏区间数据，未命中时为 `null` */
  var activeInterlude: PlayerInterlude? = null

  /** 当前是否应当聚焦在间奏点上（处于间奏区域且没有任何歌词高亮） */
  var isFocusOnInterlude: Boolean = false
}

/**
 * 时间线增量变化
 *
 * 让 UI 层能够以 O(1) 到 O(K) 的开销知道当前这一帧相比上一帧改变了什么，
 * 而不需要遍历或比对全量状态。列表字段同样是复用的内部缓冲，必须在同一帧内消费完毕。
 */
internal class TimelineDiff {
  /** 当前帧是否有任何实质性变更（播放行更替、高亮增删、间奏/焦点/滚动目标切换或跳转） */
  var hasChanged: Boolean = false

  /**
   * 本次同步是否发生了跳转：显式跳转（sync 的 forceSeek）或时间倒退 / 停滞时为 `true`
   * 例如跳转时使用更缓慢的弹簧参数，以及在非触摸状态下重置滚动坐标系
   */
  var isTimeJumped: Boolean = false

  /** 最新被命中、正在播放的歌词索引列表 */
  val addedPlaying: List<Int>
    get() = addedPlayingIds

  /** 刚刚脱离播放状态的歌词索引列表 */
  val removedPlaying: List<Int>
    get() = removedPlayingIds

  /** 本帧需要变成高亮行的歌词索引列表 */
  val addedHighlighted: List<Int>
    get() = addedHighlightedIds

  /** 本帧不再是高亮行的歌词索引列表 */
  val removedHighlighted: List<Int>
    get() = removedHighlightedIds

  /** 间奏状态是否发生切换（进入 / 离开间奏区间，或从一个间奏跳到另一个） */
  var isInterludeChanged: Boolean = false

  /** 自动对齐的目标歌词行索引是否发生变化 */
  var isScrollToChanged: Boolean = false

  internal val addedPlayingIds = ArrayList<Int>()
  internal val removedPlayingIds = ArrayList<Int>()
  internal val addedHighlightedIds = ArrayList<Int>()
  internal val removedHighlightedIds = ArrayList<Int>()
}

/**
 * 对齐 AMLL packages/core/src/lyric-player/base/timeline.ts 的 TimelineController 忠实移植：
 * 快照 + 增量（diff）架构的时间线推导器。
 *
 * 歌词行的高亮生命周期：
 * - 命中 `[startTime, endTime)` 时高亮
 * - 唱完后不会自行熄灭，而是继续保持高亮，直到出现下列任一情况：
 有新的歌词行开始播放（旧高亮一起熄灭）、进入间奏区间（清空全部并把焦点交给间奏点）、
 歌曲播放完毕（清空全部并置 isEndOfSong）、发生跳转（按跳转后时间重新推导）
 *
 * 内存纪律：所有集合与列表均为实例级复用缓冲，每次 sync 原地清空重填，
 * 不产生逐帧分配；快照与 diff 对象跨帧复用、原地覆写。
 * 所有时间均为 Long 毫秒（TS 侧为 MediaTime）。
 */
internal class AndroidLyricTimelineController {
  private var lyricBounds: List<TimeBounds> = emptyList()

  /** 全部歌词行中最晚的结束时间，用于判定歌曲是否播放完毕 */
  private var maxEndTime = 0L

  /** 预先计算的间奏区域 */
  private var precalculatedInterludes: List<PlayerInterlude> = emptyList()

  /** 上次顺序扫描停止的位置，避免每次都从头遍历所有歌词 */
  private var playbackCursor = 0
  private var interludeCursor = 0

  private val playingGroupsSet = LinkedHashSet<Int>()
  private val highlightedGroupsSet = LinkedHashSet<Int>()

  // seek 重建用的目标集合，与当前集合作对称差后输出 diff
  private val nextPlayingSet = LinkedHashSet<Int>()
  private val nextHighlightedSet = LinkedHashSet<Int>()

  // 迭代中需要删除的元素先收集到暂存列表，规避 Kotlin 集合迭代中的结构性修改异常
  private val expiredPlayingIds = ArrayList<Int>()
  private val expiredHighlightedIds = ArrayList<Int>()
  private val staleIdsScratch = ArrayList<Int>()

  // 增量列表直接别名引用 diff 内部缓冲：推导过程原地填充，sync 返回后即被消费
  private val addedPlayingIds: MutableList<Int> get() = diff.addedPlayingIds
  private val removedPlayingIds: MutableList<Int> get() = diff.removedPlayingIds
  private val addedHighlightedIds: MutableList<Int> get() = diff.addedHighlightedIds
  private val removedHighlightedIds: MutableList<Int> get() = diff.removedHighlightedIds

  private val playingGroupsView: Set<Int> = playingGroupsSet
  private val highlightedGroupsView: Set<Int> = highlightedGroupsSet

  internal val snapshot = TimelineSnapshot()
  internal val diff = TimelineDiff()

  init {
    // 快照的集合字段固定指向控制器内部集合的只读视图，sync 时原地更新、不换引用
    snapshot.playingGroups = playingGroupsView
    snapshot.highlightedGroups = highlightedGroupsView
  }

  /**
   * 提前设置好歌词的时间数据，内部会根据此数据来进行时间线推导，同时预计算间奏区间
   *
   * @param bounds - 歌词行的时间边界，必须按 `startTime` 升序排列，否则时间推导可能出现意外情况
   */
  fun setTimeBounds(bounds: List<TimeBounds>) {
    lyricBounds = bounds
    precalculatedInterludes = calculateInterludes(bounds)
    // 歌词行按开始时间排序，末行的结束时间不一定是最大值
    // 例如末尾存在时间上被前一行包住的重叠行，因此单独预计算一次
    var maxEnd = 0L
    for (bound in bounds) {
      if (bound.endTime > maxEnd) maxEnd = bound.endTime
    }
    maxEndTime = maxEnd
    reset()
  }

  /**
   * 获取当前播放时间线状态的只读快照
   *
   * 在获取快照后，必须在同一帧内消费完毕，切勿保留其引用，因为下一帧就会被原地覆写
   * @returns 时间线快照
   */
  fun getSnapshot(): TimelineSnapshot = snapshot

  /**
   * 将播放进度推进到指定时间，并返回相对上一帧的增量变化
   *
   * @param timeMs - 当前播放时间（毫秒）
   * @param forceSeek - 这次时间变化是否由跳转触发
   * @returns 相对上一帧的增量变化
   */
  fun sync(
    timeMs: Long,
    forceSeek: Boolean = false,
  ): TimelineDiff {
    addedPlayingIds.clear()
    removedPlayingIds.clear()
    addedHighlightedIds.clear()
    removedHighlightedIds.clear()

    val prevInterlude = snapshot.activeInterlude
    val prevFocusOnInterlude = snapshot.isFocusOnInterlude
    val prevScrollToIndex = snapshot.scrollToIndex
    val prevEndOfSong = snapshot.isEndOfSong

    // 时间不再前进时一律按跳转处理，这里有两个各自独立的理由：
    // 倒退是机制上的必需：performPlayback 会保存上次扫描停止的位置，若时间倒退，
    // 倒退到的行可能位于扫描位置之前，只能重新推导；
    // 停滞则是语义上的约定：正常播放不会让进度停在原地，推送同一个时间表达的是把
    // 逐字遮罩这类自行推进的动画重新对齐到该时间的意图，因此也走跳转路径
    val isTimeNotAdvancing = timeMs <= snapshot.currentTime
    val isJump = forceSeek || isTimeNotAdvancing

    // 间奏命中情况需要先于歌词状态确定
    // Seek 时要按同样的规则决定是否保留已经唱完的行，需要提前知道结果
    val activeInterlude = resolveActiveInterlude(timeMs, isJump)
    snapshot.activeInterlude = activeInterlude

    val isPastLastLine = lyricBounds.isNotEmpty() && timeMs >= maxEndTime

    if (isJump) {
      performSeek(timeMs, activeInterlude != null || isPastLastLine)
    } else {
      performPlayback(timeMs)
    }

    // 高亮行本身不会因为唱完而熄灭，这里处理两个需要清空的场景：
    // 进入间奏区间时间奏点接过焦点，不应该再有亮着的旧歌词；
    // 歌曲播放完毕后不会再有新歌词接续，需要主动熄灭并把焦点交给底栏
    if (activeInterlude != null && playingGroupsSet.isEmpty()) {
      flushAllHighlighted()
    }
    if (isPastLastLine) {
      flushAllHighlighted()
    }

    updateInterludeFocus(activeInterlude)

    val isInterludeChanged = prevInterlude !== activeInterlude
    val isFocusChanged = prevFocusOnInterlude != snapshot.isFocusOnInterlude
    val isScrollToChanged = prevScrollToIndex != snapshot.scrollToIndex

    val hasChanged =
      isJump ||
        addedPlayingIds.isNotEmpty() ||
        removedPlayingIds.isNotEmpty() ||
        addedHighlightedIds.isNotEmpty() ||
        removedHighlightedIds.isNotEmpty() ||
        isInterludeChanged ||
        isFocusChanged ||
        isScrollToChanged ||
        isPastLastLine != prevEndOfSong

    snapshot.currentTime = timeMs

    if (highlightedGroupsSet.isNotEmpty()) {
      var maxIndex = -1
      for (id in highlightedGroupsSet) {
        if (id > maxIndex) maxIndex = id
      }
      snapshot.latestHighlightedIndex = maxIndex
    } else {
      snapshot.latestHighlightedIndex = -1
    }

    snapshot.isEndOfSong = isPastLastLine

    diff.hasChanged = hasChanged
    diff.isTimeJumped = isJump
    diff.isInterludeChanged = isInterludeChanged
    diff.isScrollToChanged = isScrollToChanged

    return diff
  }

  /**
   * 处理正常播放时的时间线推导
   *
   * 播放行：当前时间落在 `[startTime, endTime)` 内的行，是高亮行的子集；
   * 高亮行：UI 层真正看到的高亮状态。一行唱完后立刻退出播放状态但保持高亮，
   * 直到下一行开始播放才熄灭，保证多行重叠与句间空隙内的可读性
   */
  private fun performPlayback(timeMs: Long) {
    // 清理不再播放的行
    expiredPlayingIds.clear()
    for (lastPlayingId in playingGroupsSet) {
      val bound = lyricBounds.getOrNull(lastPlayingId)
      if (bound == null || timeMs < bound.startTime || bound.endTime <= timeMs) {
        expiredPlayingIds.add(lastPlayingId)
      }
    }
    for (id in expiredPlayingIds) {
      playingGroupsSet.remove(id)
      removedPlayingIds.add(id)
    }

    // 从 playbackCursor 开始顺序查找并激活新的播放中的行，跳过已经唱完的歌词以提高性能
    var cursor = playbackCursor.coerceAtLeast(0)
    val len = lyricBounds.size
    while (cursor < len) {
      val bound = lyricBounds[cursor]
      if (bound.startTime > timeMs) {
        break
      }
      if (bound.startTime <= timeMs && bound.endTime > timeMs && !playingGroupsSet.contains(cursor)) {
        playingGroupsSet.add(cursor)
        addedPlayingIds.add(cursor)
      }
      cursor++
    }
    playbackCursor = cursor

    // 找出那些已经唱完但仍处于高亮状态的歌词行
    // 它们会保持高亮，直到有新歌词开始播放才被一起熄灭
    expiredHighlightedIds.clear()
    for (id in highlightedGroupsSet) {
      if (!playingGroupsSet.contains(id)) {
        expiredHighlightedIds.add(id)
      }
    }

    val addedPlayingCount = addedPlayingIds.size
    val expiredCount = expiredHighlightedIds.size

    // 只要有新歌词开始播放，将其存入 highlightedGroupsSet 并向 diff 输出
    for (i in 0 until addedPlayingCount) {
      val id = addedPlayingIds[i]
      highlightedGroupsSet.add(id)
      addedHighlightedIds.add(id)
    }

    // 清理旧高亮的唯一条件是「有新歌词进入播放状态」：
    // 一行唱完时若没有新行接续，它会继续保持高亮以维持可读性
    if (addedPlayingCount > 0) {
      for (i in 0 until expiredCount) {
        val id = expiredHighlightedIds[i]
        highlightedGroupsSet.remove(id)
        removedHighlightedIds.add(id)
      }

      var minHighlighted = Int.MAX_VALUE
      for (id in highlightedGroupsSet) {
        if (id < minHighlighted) minHighlighted = id
      }

      // 只在歌词更替时更新，以便在播放完毕后保持聚焦在这一组歌词
      snapshot.scrollToIndex = minHighlighted
    }
  }

  /**
   * 处理 Seek 时的时间线推导
   *
   * 直接按目标时间重建播放与高亮行集合，结果与正常播放到该时刻时一致
   *
   * @param timeMs - 跳转到的时间
   * @param dropLingeringWhenIdle - 在没有任何行正在播放时，是否丢弃那些已经唱完、
   * 但在正常播放中仍会保持高亮的行，用于在间奏和播放完时清空高亮行
   */
  private fun performSeek(
    timeMs: Long,
    dropLingeringWhenIdle: Boolean,
  ) {
    nextPlayingSet.clear()
    nextHighlightedSet.clear()

    // 二分法找出第一个开始时间晚于目标时间的歌词行，排除所有尚未开始唱的歌词
    var left = 0
    var right = lyricBounds.size - 1
    var firstGreater = lyricBounds.size
    while (left <= right) {
      val mid = (left + right) ushr 1
      if (lyricBounds[mid].startTime > timeMs) {
        firstGreater = mid
        right = mid - 1
      } else {
        left = mid + 1
      }
    }

    // 往前找到最后一个真正开始播放过的歌词行作为锚点，有意跳过时长为 0 的歌词行
    var anchorIndex = -1
    for (i in firstGreater - 1 downTo 0) {
      val bound = lyricBounds[i]
      if (bound.endTime > bound.startTime) {
        anchorIndex = i
        break
      }
    }

    if (anchorIndex == -1) {
      // 目标时间在第一行歌词开始之前，或此前所有已开始的行都是零时长
      // 正常播放时尚未有任何歌词行进入播放状态，scrollToIndex 保持为 0
      playbackCursor = firstGreater
      snapshot.scrollToIndex = 0
      commitSeekDiff()
      return
    }

    // 还原「歌词唱完不熄灭，直到下一句开始才更替」的状态
    // 锚点之后的歌词行要么开始时间晚于目标时间、要么时长为零，都不可能命中，无需遍历
    val anchorStart = lyricBounds[anchorIndex].startTime
    var minPlaying = -1
    var minHighlighted = -1
    for (i in anchorIndex downTo 0) {
      val bound = lyricBounds[i]

      // 锚点时刻尚未唱完的行，在正常播放中会一直亮到下一行开始
      // 正在播放的行必然满足此条件，因为其结束时间晚于目标时间，而目标时间不早于锚点
      if (bound.endTime <= anchorStart) continue

      if (bound.startTime <= timeMs && bound.endTime > timeMs) {
        nextPlayingSet.add(i)
        minPlaying = i
      }

      nextHighlightedSet.add(i)
      minHighlighted = i
    }

    // 间奏与曲末清空高亮行，如果没有行在播放的话
    if (dropLingeringWhenIdle && nextPlayingSet.isEmpty()) {
      nextHighlightedSet.clear()
    }

    playbackCursor = if (minPlaying == -1) firstGreater else minPlaying
    snapshot.scrollToIndex = minHighlighted

    commitSeekDiff()
  }

  /**
   * 把 Seek 重建出的目标集合与上一帧的集合求对称差，输出发生变化的部分
   */
  private fun commitSeekDiff() {
    staleIdsScratch.clear()
    for (id in playingGroupsSet) {
      if (!nextPlayingSet.contains(id)) staleIdsScratch.add(id)
    }
    for (id in staleIdsScratch) {
      playingGroupsSet.remove(id)
      removedPlayingIds.add(id)
    }
    for (id in nextPlayingSet) {
      if (!playingGroupsSet.contains(id)) {
        playingGroupsSet.add(id)
        addedPlayingIds.add(id)
      }
    }

    staleIdsScratch.clear()
    for (id in highlightedGroupsSet) {
      if (!nextHighlightedSet.contains(id)) staleIdsScratch.add(id)
    }
    for (id in staleIdsScratch) {
      highlightedGroupsSet.remove(id)
      removedHighlightedIds.add(id)
    }
    for (id in nextHighlightedSet) {
      if (!highlightedGroupsSet.contains(id)) {
        highlightedGroupsSet.add(id)
        addedHighlightedIds.add(id)
      }
    }
  }

  /**
   * 立即熄灭当前全部高亮歌词行
   *
   * 用于间奏与曲末这两个没有下一行接续、但必须清空高亮的场景
   */
  private fun flushAllHighlighted() {
    if (highlightedGroupsSet.isEmpty()) return
    for (id in highlightedGroupsSet) {
      removedHighlightedIds.add(id)
    }
    highlightedGroupsSet.clear()
  }

  /**
   * 预计算全部间奏区间
   *
   * 歌词行只保证按 `startTime` 升序，前一行的 `endTime` 并不等于此前所有行的最晚结束时间
   * （例如多行高亮的情况），所以按前缀最大结束时间做一次区间并集扫描，
   * 保证产出的区间与任何歌词行都不重叠
   *
   * @param bounds - 按 `startTime` 升序排列的歌词时间边界
   * @returns 按时间升序排列、互不重叠的间奏区间，供二分查找与游标推进使用
   */
  private fun calculateInterludes(bounds: List<TimeBounds>): List<PlayerInterlude> {
    val interludes = ArrayList<PlayerInterlude>()

    // 已扫描过的歌词行中最晚的结束时间
    var maxEnd = 0L

    for (i in -1 until bounds.size - 1) {
      if (i >= 0) {
        val previousEnd = bounds[i].endTime
        if (previousEnd > maxEnd) maxEnd = previousEnd
      }

      val nextStart = bounds[i + 1].startTime
      val gapEnd = if (nextStart > maxEnd) nextStart else maxEnd

      if (gapEnd - maxEnd >= MIN_INTERLUDE_GAP_MS) {
        interludes.add(PlayerInterlude(startTime = maxEnd, endTime = gapEnd, anchorLineIndex = i))
      }
    }

    return interludes
  }

  /**
   * 查找当前时间命中的间奏区间，并顺带推进或重定位间奏游标
   *
   * @param timeMs - 当前播放时间
   * @param isSeek - 当前帧是否为跳转
   * @returns 命中的间奏区间，未命中时为 `null`
   */
  private fun resolveActiveInterlude(
    timeMs: Long,
    isSeek: Boolean,
  ): PlayerInterlude? {
    if (precalculatedInterludes.isEmpty()) return null

    if (isSeek) {
      var cursor = precalculatedInterludes.size
      var left = 0
      var right = precalculatedInterludes.size - 1
      while (left <= right) {
        val mid = (left + right) ushr 1
        val inter = precalculatedInterludes[mid]
        if (inter.endTime > timeMs) {
          cursor = mid
          right = mid - 1
        } else {
          left = mid + 1
        }
      }

      interludeCursor = cursor

      if (cursor < precalculatedInterludes.size) {
        val inter = precalculatedInterludes[cursor]
        if (timeMs >= inter.startTime && timeMs < inter.endTime) {
          return inter
        }
      }

      return null
    }

    while (interludeCursor < precalculatedInterludes.size) {
      val inter = precalculatedInterludes[interludeCursor]
      if (timeMs >= inter.startTime && timeMs < inter.endTime) {
        return inter
      }
      if (timeMs >= inter.endTime) {
        interludeCursor++
      } else {
        break
      }
    }

    return null
  }

  /**
   * 根据间奏命中情况和当前高亮状态推导是否应当聚焦间奏点
   *
   * 处于间奏区域且没有任何歌词高亮时聚焦间奏点，否则交还给歌词行
   *
   * @param activeInterlude - 当前命中的间奏区间
   */
  private fun updateInterludeFocus(activeInterlude: PlayerInterlude?) {
    snapshot.isFocusOnInterlude = activeInterlude != null && highlightedGroupsSet.isEmpty()
  }

  /**
   * 清空全部推导状态，回到时间原点
   */
  private fun reset() {
    playbackCursor = 0
    interludeCursor = 0

    playingGroupsSet.clear()
    highlightedGroupsSet.clear()
    nextPlayingSet.clear()
    nextHighlightedSet.clear()

    snapshot.currentTime = 0L
    snapshot.scrollToIndex = 0
    snapshot.latestHighlightedIndex = -1
    snapshot.isEndOfSong = false
    snapshot.activeInterlude = null
    snapshot.isFocusOnInterlude = false
  }
}
