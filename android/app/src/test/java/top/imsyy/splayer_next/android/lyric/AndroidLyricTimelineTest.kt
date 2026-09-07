package top.imsyy.splayer_next.android.lyric

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLyricTimelineTest {
  @Test
  fun resolvesSlowSpringPolicyOnSeekAndInterlude() {
    // 对齐 AMLL getPosYSpringPolicy：跳转与间奏用缓慢档 {90,15}
    val seekPolicy = AndroidLyricTimeline.resolvePosYSpringPolicy(true, false, 400L, false)
    val interludePolicy = AndroidLyricTimeline.resolvePosYSpringPolicy(false, true, 400L, false)
    assertEquals(90f, seekPolicy.stiffness!!, 0.001f)
    assertEquals(15f, seekPolicy.damping!!, 0.001f)
    assertEquals(90f, interludePolicy.stiffness!!, 0.001f)
    assertEquals(15f, interludePolicy.damping!!, 0.001f)
  }

  @Test
  fun resolvesMediumSpringPolicyOnEndOfSong() {
    val policy = AndroidLyricTimeline.resolvePosYSpringPolicy(false, false, 400L, true)
    assertEquals(140f, policy.stiffness!!, 0.001f)
    assertEquals(22f, policy.damping!!, 0.001f)
  }

  @Test
  fun fallsBackToSlowPolicyWithoutInterval() {
    val policy = AndroidLyricTimeline.resolvePosYSpringPolicy(false, false, null, false)
    assertEquals(90f, policy.stiffness!!, 0.001f)
    assertEquals(15f, policy.damping!!, 0.001f)
  }

  @Test
  fun mapsIntervalToStiffnessWithExponentRatio() {
    // 间隔 100ms（最短）：ratio=1，刚度上限 220，阻尼=sqrt(220)*2.2
    val fastest = AndroidLyricTimeline.resolvePosYSpringPolicy(false, false, 100L, false)
    assertEquals(220f, fastest.stiffness!!, 0.001f)
    assertEquals(kotlin.math.sqrt(220f) * 2.2f, fastest.damping!!, 0.001f)
    // 间隔 800ms（最长）：ratio=0，刚度下限 170
    val slowest = AndroidLyricTimeline.resolvePosYSpringPolicy(false, false, 800L, false)
    assertEquals(170f, slowest.stiffness!!, 0.001f)
    // 间隔超出范围被钳制：50ms 按 100ms、2000ms 按 800ms
    assertEquals(220f, AndroidLyricTimeline.resolvePosYSpringPolicy(false, false, 50L, false).stiffness!!, 0.001f)
    assertEquals(170f, AndroidLyricTimeline.resolvePosYSpringPolicy(false, false, 2000L, false).stiffness!!, 0.001f)
    // 间隔 450ms：ratio=(1-0.5)^0.2≈0.87055，刚度≈213.53
    val middle = AndroidLyricTimeline.resolvePosYSpringPolicy(false, false, 450L, false)
    val expectedRatio = 0.5f.pow(0.2f)
    assertEquals(170f + expectedRatio * 50f, middle.stiffness!!, 0.01f)
    // 策略只下发 stiffness/damping，mass 保持 null（对齐 AMLL Partial 语义，由 merge 保留现有 mass）
    assertEquals(null, middle.mass)
  }

  @Test
  fun keepsFastAdjacentWordTimingExact() {
    val previousProgress = AndroidLyricTimeline.resolveWordProgress(1000L, 1100L, 1100L)
    val nextProgress = AndroidLyricTimeline.resolveWordProgress(1100L, 1200L, 1100L)

    assertEquals(1f, previousProgress, 0.001f)
    assertEquals(0f, nextProgress, 0.001f)
    assertEquals(0.5f, AndroidLyricTimeline.resolveWordProgress(1100L, 1200L, 1150L), 0.001f)
  }

  @Test
  fun resolvesGradientStartXFromIndependentSegmentTravel() {
    // 对齐 Web：渐变带左缘 = 词左缘 - fadeWidth + 该段独立行程
    val fadeWidth = 20f
    // 段未开始（行程 0）：带左缘 = -20，词面全暗
    assertEquals(-20f, AndroidLyricTimeline.resolveWordGradientStartX(0f, fadeWidth, 0f), 0.001f)
    // 段行程 60：带左缘 = 40，词面 [0,100] 内 [40,60] 渐变
    assertEquals(40f, AndroidLyricTimeline.resolveWordGradientStartX(0f, fadeWidth, 60f), 0.001f)
    // 段行程满（词宽 + fadeWidth）：带左缘 = 词右缘，词面全亮
    assertEquals(100f, AndroidLyricTimeline.resolveWordGradientStartX(0f, fadeWidth, 120f), 0.001f)
    // 第二词（wordX=100）行程独立推进，位置由自身行程决定
    assertEquals(130f, AndroidLyricTimeline.resolveWordGradientStartX(100f, fadeWidth, 50f), 0.001f)
  }

  @Test
  fun preventsDelayedPlaybackPushFromRewindingVisibleTime() {
    val incomingTimeMs = 1016L
    val visibleTimeMs = 1033L
    val isJump = AndroidLyricTimeline.isProgressJump(incomingTimeMs, visibleTimeMs)

    assertEquals(false, isJump)
    assertEquals(
      visibleTimeMs,
      AndroidLyricTimeline.resolveProgressAnchor(
        incomingTimeMs,
        visibleTimeMs,
        wasPlaying = true,
        playing = true,
        isJump = isJump,
      ),
    )
  }

  @Test
  fun preservesBackwardSeekProgress() {
    val incomingTimeMs = 800L
    val visibleTimeMs = 1100L
    val isJump = AndroidLyricTimeline.isProgressJump(incomingTimeMs, visibleTimeMs)

    assertEquals(true, isJump)
    assertEquals(
      incomingTimeMs,
      AndroidLyricTimeline.resolveProgressAnchor(
        incomingTimeMs,
        visibleTimeMs,
        wasPlaying = true,
        playing = true,
        isJump = isJump,
      ),
    )
  }

  // ── buildRubySweepWindows 测试 ──

  @Test
  fun buildsRubySweepWindowsPerRubySegment() {
    // 对齐 AMLL ruby 分支：第 j 字符取 rubySegment[min(j, last)] 的时间窗
    val rubySpans =
      listOf(
        NativeLyricSpan("きょ", 0L, 400L),
        NativeLyricSpan("う", 400L, 1000L),
      )

    val windows = AndroidLyricTimeline.buildRubySweepWindows(rubySpans, 0L, 1000L, 3)

    assertEquals(3, windows.size)
    assertEquals(0L to 400L, windows[0])
    assertEquals(400L to 1000L, windows[1])
    // 超出段数的字符沿用最后一段
    assertEquals(400L to 1000L, windows[2])
  }

  @Test
  fun clampsRubySweepWindowsIntoWordRangeMonotonically() {
    // 段时间越出词区间时 clamp 进 [wordStart, wordEnd]，并保证后段开始不早于前段结束
    val rubySpans =
      listOf(
        NativeLyricSpan("a", 500L, 900L),
        NativeLyricSpan("b", 800L, 2000L),
      )

    val windows = AndroidLyricTimeline.buildRubySweepWindows(rubySpans, 1000L, 1500L, 2)

    assertEquals(2, windows.size)
    assertEquals(1000L to 1000L, windows[0])
    assertEquals(1000L to 1500L, windows[1])
  }

  @Test
  fun returnsEmptyRubySweepWindowsWithoutRubyOrChars() {
    val rubySpans = listOf(NativeLyricSpan("き", 0L, 100L))

    assertTrue(AndroidLyricTimeline.buildRubySweepWindows(rubySpans, 0L, 100L, 0).isEmpty())
    assertTrue(AndroidLyricTimeline.buildRubySweepWindows(emptyList(), 0L, 100L, 2).isEmpty())
  }

  // ── calcBalancedBreaks 测试 ──

  @Test
  fun wrapsLongCjkTranslationText() {
    // 模拟"我以为看见了你的车"按字符切分，每字宽度 20px
    val text = "我以为看见了你的车"
    val units =
      text.map { char ->
        StaticBreakUnit(char.toString(), 20f, isSpace = false)
      }
    // maxWidth = 100px，总宽 220px，必须换行
    val breaks = AndroidLyricTimeline.calcBalancedBreaks(units, 100f)
    assert(breaks.isNotEmpty()) { "Expected breaks for overflowing CJK text, got $breaks" }
  }

  @Test
  fun returnsEmptyWhenTextFits() {
    val units =
      listOf(
        StaticBreakUnit("Short", 50f, isSpace = false),
        StaticBreakUnit(" ", 10f, isSpace = true),
        StaticBreakUnit("text", 40f, isSpace = false),
      )
    val breaks = AndroidLyricTimeline.calcBalancedBreaks(units, 200f)
    assertEquals(emptyList<Int>(), breaks)
  }

  @Test
  fun prefersBreakingAtSpaces() {
    val units =
      listOf(
        StaticBreakUnit("Hello", 50f, isSpace = false),
        StaticBreakUnit(" ", 10f, isSpace = true),
        StaticBreakUnit("World", 60f, isSpace = false),
        StaticBreakUnit(" ", 10f, isSpace = true),
        StaticBreakUnit("Wide", 50f, isSpace = false),
      )
    // 总宽 180，maxWidth=100，且任何两个非空格词无法同处一行（Hello+space+World=120>100）
    // 因此必须分成 3 行，断点应在两个空格后：Hello | World | Wide
    val breaks = AndroidLyricTimeline.calcBalancedBreaks(units, 100f)
    assertEquals("Expected 2 breaks, got $breaks", 2, breaks.size)
    // 空格处断开有 reward，断点应在空格 unit 之后
    assertTrue("Expected breaks after spaces, got $breaks", breaks.all { it % 2 == 0 })
  }

  @Test
  fun forcesBreakWhenSingleUnitTooWide() {
    val units =
      listOf(
        StaticBreakUnit("SuperLongWord", 200f, isSpace = false),
        StaticBreakUnit(" ", 10f, isSpace = true),
        StaticBreakUnit("Another", 50f, isSpace = false),
      )
    // 第一个 unit 已超过 maxWidth，DP 仍允许单 unit 成行并付出高代价
    val breaks = AndroidLyricTimeline.calcBalancedBreaks(units, 100f)
    assertEquals(listOf(1), breaks)
  }

  @Test
  fun returnsEmptyForSingleUnit() {
    val units = listOf(StaticBreakUnit("A", 10f, isSpace = false))
    val breaks = AndroidLyricTimeline.calcBalancedBreaks(units, 5f)
    assertEquals(emptyList<Int>(), breaks)
  }

  @Test
  fun returnsEmptyWhenTotalWidthJustBelowMaxWidth() {
    // 总宽 95px < maxWidth 100px：measureTextMetrics 触发换行后传入 calcBalancedBreaks，
    // 因总宽未超硬约束 maxWidth，应返回空 breaks，让上层回退到单行显示。
    val units =
      listOf(
        StaticBreakUnit("Hello", 30f, isSpace = false),
        StaticBreakUnit(" ", 5f, isSpace = true),
        StaticBreakUnit("world", 30f, isSpace = false),
        StaticBreakUnit(" ", 5f, isSpace = true),
        StaticBreakUnit("text", 25f, isSpace = false),
      )
    val breaks = AndroidLyricTimeline.calcBalancedBreaks(units, 100f)
    assertEquals(emptyList<Int>(), breaks)
  }

  // ── AndroidLyricTimelineController 测试 ──

  private fun newController(vararg bounds: Pair<Long, Long>): AndroidLyricTimelineController =
    AndroidLyricTimelineController().also { controller ->
      controller.setTimeBounds(bounds.map { (start, end) -> TimeBounds(start, end) })
    }

  @Test
  fun excludesInterludesBelowMinimumGap() {
    // 空隙 3500ms < 4000ms：不构成间奏
    val controller = newController(0L to 1000L, 4500L to 5000L)

    controller.sync(2000L)

    assertEquals(null, controller.snapshot.activeInterlude)
    assertEquals(false, controller.snapshot.isFocusOnInterlude)
  }

  @Test
  fun mergesOverlappingEndTimesViaPrefixMaxWhenCalculatingInterludes() {
    // 行 1 [3000,4000] 被行 0 [0,5000] 时间上包住：前缀最大结束时间为 5000，
    // 间奏应为 [5000,9000]，锚点为间奏前最后一行的控制器索引 1
    val controller = newController(0L to 5000L, 3000L to 4000L, 9000L to 9500L)

    controller.sync(6000L)
    val interlude = controller.snapshot.activeInterlude

    assertEquals(5000L, interlude?.startTime)
    assertEquals(9000L, interlude?.endTime)
    assertEquals(1, interlude?.anchorLineIndex)
    // 无播放且无高亮时聚焦间奏点
    assertEquals(true, controller.snapshot.isFocusOnInterlude)
  }

  @Test
  fun keepsLineHighlightedAfterEndTimeUntilNextLineStarts() {
    val controller = newController(1000L to 3000L, 5000L to 6000L)

    controller.sync(1500L)
    assertEquals(setOf(0), controller.snapshot.playingGroups)
    assertEquals(setOf(0), controller.snapshot.highlightedGroups)

    // 唱完后退出播放状态但保持高亮，句间空隙内不熄灭
    controller.sync(3500L)
    assertEquals(emptySet<Int>(), controller.snapshot.playingGroups)
    assertEquals(setOf(0), controller.snapshot.highlightedGroups)
    assertEquals(0, controller.snapshot.latestHighlightedIndex)

    // 新一行开始播放：旧行一起熄灭，滚动目标更替为新组
    val diff = controller.sync(5100L)
    assertEquals(listOf(1), diff.addedPlaying)
    assertEquals(listOf(0), diff.removedHighlighted)
    assertEquals(setOf(1), controller.snapshot.playingGroups)
    assertEquals(setOf(1), controller.snapshot.highlightedGroups)
    assertEquals(1, controller.snapshot.scrollToIndex)
  }

  @Test
  fun seekRebuildMatchesSequentialPlaybackSets() {
    // 重叠行场景：跳转重建与顺序播放到同一时刻应得到相同的播放/高亮集合
    val sequential = newController(1000L to 3000L, 2500L to 5000L)
    sequential.sync(1200L)
    sequential.sync(2600L)

    val seeked = newController(1000L to 3000L, 2500L to 5000L)
    seeked.sync(2600L, forceSeek = true)

    assertEquals(sequential.snapshot.playingGroups, seeked.snapshot.playingGroups)
    assertEquals(sequential.snapshot.highlightedGroups, seeked.snapshot.highlightedGroups)
    assertEquals(setOf(0, 1), seeked.snapshot.playingGroups)
    assertEquals(setOf(0, 1), seeked.snapshot.highlightedGroups)
    assertEquals(0, seeked.snapshot.scrollToIndex)
  }

  @Test
  fun seekAnchorWalkSkipsZeroDurationLines() {
    // 中间夹一个零时长行 [500,500]：锚点回溯必须跳过它，落到行 0
    val controller = newController(0L to 500L, 500L to 500L, 800L to 1500L)

    controller.sync(600L, forceSeek = true)

    assertEquals(emptySet<Int>(), controller.snapshot.playingGroups)
    assertEquals(setOf(0), controller.snapshot.highlightedGroups)
    assertEquals(0, controller.snapshot.scrollToIndex)

    // 首行之前全部是零时长行：无锚点，回到时间原点状态
    val allZero = newController(0L to 0L, 800L to 1500L)
    allZero.sync(400L, forceSeek = true)

    assertEquals(emptySet<Int>(), allZero.snapshot.playingGroups)
    assertEquals(emptySet<Int>(), allZero.snapshot.highlightedGroups)
    assertEquals(-1, allZero.snapshot.latestHighlightedIndex)
  }

  @Test
  fun latestHighlightedIndexAndEndOfSongTransitions() {
    val controller = newController(1000L to 2000L, 3000L to 4000L)

    controller.sync(500L)
    assertEquals(-1, controller.snapshot.latestHighlightedIndex)
    assertEquals(false, controller.snapshot.isEndOfSong)

    controller.sync(1500L)
    assertEquals(0, controller.snapshot.latestHighlightedIndex)
    assertEquals(false, controller.snapshot.isEndOfSong)

    // 越过最晚结束时间：清空全部高亮并置 isEndOfSong
    controller.sync(4500L)
    assertEquals(true, controller.snapshot.isEndOfSong)
    assertEquals(emptySet<Int>(), controller.snapshot.highlightedGroups)
    assertEquals(-1, controller.snapshot.latestHighlightedIndex)
  }

  @Test
  fun stalledOrBackwardTimeTreatedAsSeek() {
    val controller = newController(1000L to 2000L, 3000L to 4000L)

    assertEquals(false, controller.sync(1500L).isTimeJumped)
    // 停滞（重复推送同一时间）与倒退都按跳转处理，重新对齐逐字遮罩动画
    assertEquals(true, controller.sync(1500L).isTimeJumped)
    assertEquals(true, controller.sync(1400L).isTimeJumped)
  }

  // ── computeSegmentTravel 测试（对齐 Web 逐词独立遮罩模型）──

  @Test
  fun segmentTravelAdvancesLinearlyWithinOwnWindow() {
    // 词 [1000,2000]，amount = 词宽100 + fadeWidth20 = 120，lineStart=0：
    // preRoll = min(80, 1000×0.3) = 80 → adjustedStart = 920，speed = 120/1080
    assertEquals(0f, AndroidLyricTimeline.computeSegmentTravel(1000L, 2000L, 919L, 120f, 0L), 0.001f)
    assertEquals(
      80f * 120f / 1080f,
      AndroidLyricTimeline.computeSegmentTravel(1000L, 2000L, 1000L, 120f, 0L),
      0.001f,
    )
    assertEquals(60f, AndroidLyricTimeline.computeSegmentTravel(1000L, 2000L, 1460L, 120f, 0L), 0.001f)
    // 窗口后钳在 amount（词面全亮保持）
    assertEquals(120f, AndroidLyricTimeline.computeSegmentTravel(1000L, 2000L, 2000L, 120f, 0L), 0.001f)
    assertEquals(120f, AndroidLyricTimeline.computeSegmentTravel(1000L, 2000L, 9999L, 120f, 0L), 0.001f)
  }

  @Test
  fun segmentTravelClampsPreRollToLineStart() {
    // 词 [5000,5600]，preRoll 起点越出行开始时钳到 lineStart=4950：
    // adjustedDuration = 650，speed = 120/650
    assertEquals(0f, AndroidLyricTimeline.computeSegmentTravel(5000L, 5600L, 4949L, 120f, 4950L), 0.001f)
    assertEquals(60f, AndroidLyricTimeline.computeSegmentTravel(5000L, 5600L, 5275L, 120f, 4950L), 0.001f)
    assertEquals(120f, AndroidLyricTimeline.computeSegmentTravel(5000L, 5600L, 5600L, 120f, 4950L), 0.001f)
  }

  @Test
  fun segmentTravelJumpsInstantlyOnZeroDurationSegment() {
    // 零时长词：1ms 内完成，近似瞬时（对齐 Web adjustedDuration = max(1, 0)）
    assertEquals(0f, AndroidLyricTimeline.computeSegmentTravel(1000L, 1000L, 999L, 120f, 0L), 0.001f)
    assertEquals(0f, AndroidLyricTimeline.computeSegmentTravel(1000L, 1000L, 1000L, 120f, 0L), 0.001f)
    assertEquals(120f, AndroidLyricTimeline.computeSegmentTravel(1000L, 1000L, 1001L, 120f, 0L), 0.001f)
  }

  @Test
  fun segmentTravelPreRollEntersBeforeWordStart() {
    // 短词 [1000,1100]：preRoll = min(80, 100×0.3) = 30 → adjustedStart = 970
    assertEquals(0f, AndroidLyricTimeline.computeSegmentTravel(1000L, 1100L, 969L, 120f, 0L), 0.001f)
    assertEquals(
      30f * 120f / 130f,
      AndroidLyricTimeline.computeSegmentTravel(1000L, 1100L, 1000L, 120f, 0L),
      0.001f,
    )
    assertEquals(60f, AndroidLyricTimeline.computeSegmentTravel(1000L, 1100L, 1035L, 120f, 0L), 0.001f)
  }

  @Test
  fun segmentTravelIgnoresOverlappingWindowsOfNeighborSegments() {
    // 少数语种自动对齐音节的常见形态：段 A [1000,2000] 与段 B [1200,1800] 时窗重叠，
    // 各段行程只依赖自身时间窗，重叠期间各自独立推进互不干扰
    // A 与单独计算完全一致
    assertEquals(60f, AndroidLyricTimeline.computeSegmentTravel(1000L, 2000L, 1460L, 120f, 0L), 0.001f)
    // B：preRoll=80 → adjustedStart=1120，adjustedDuration=680，speed=120/680
    assertEquals(
      80f * 120f / 680f,
      AndroidLyricTimeline.computeSegmentTravel(1200L, 1800L, 1200L, 120f, 0L),
      0.001f,
    )
    assertEquals(60f, AndroidLyricTimeline.computeSegmentTravel(1200L, 1800L, 1460L, 120f, 0L), 0.001f)
    assertEquals(120f, AndroidLyricTimeline.computeSegmentTravel(1200L, 1800L, 1800L, 120f, 0L), 0.001f)
  }

  @Test
  fun segmentTravelHandlesDisorderedWindowsSafely() {
    // 时窗倒挂（start > end，自动对齐音节的脏数据）：不崩溃，1ms 内瞬时完成
    assertEquals(0f, AndroidLyricTimeline.computeSegmentTravel(2000L, 1500L, 1999L, 120f, 0L), 0.001f)
    assertEquals(120f, AndroidLyricTimeline.computeSegmentTravel(2000L, 1500L, 2001L, 120f, 0L), 0.001f)
  }

  @Test
  fun longSustainMergedSegmentSweepsSlowly() {
    // 长音「あーー」合并段 [1000,4000]（3000ms），preRoll 被钳到 lineStart：
    // speed = 120/3000 = 0.04 px/ms，中点时刻行程过半（匀速慢扫）
    assertEquals(0f, AndroidLyricTimeline.computeSegmentTravel(1000L, 4000L, 1000L, 120f, 1000L), 0.001f)
    assertEquals(60f, AndroidLyricTimeline.computeSegmentTravel(1000L, 4000L, 2500L, 120f, 1000L), 0.001f)
    assertEquals(120f, AndroidLyricTimeline.computeSegmentTravel(1000L, 4000L, 4000L, 120f, 1000L), 0.001f)
  }

  @Test
  fun gradientStartXComposesWithPreRollAcrossAdjacentWords() {
    // 相邻词 [1000,2000]/[2000,3000]，wordX=0/100，fade=20，lineStart=1000：
    // 用 computeSegmentTravel 驱动 resolveWordGradientStartX 验证 Web 模型下的衔接
    val fadeWidth = 20f
    // 词 0 在 t=1460：行程 55.2，带左缘 = 0 - 20 + 55.2 = 35.2
    val firstMid =
      AndroidLyricTimeline.resolveWordGradientStartX(
        wordX = 0f,
        fadeWidth = fadeWidth,
        segmentTravel = AndroidLyricTimeline.computeSegmentTravel(1000L, 2000L, 1460L, 120f, 1000L),
      )
    assertEquals(35.2f, firstMid, 0.001f)
    // 词 0 唱完：带左缘 = 词右缘 → 全亮
    val firstDone =
      AndroidLyricTimeline.resolveWordGradientStartX(
        wordX = 0f,
        fadeWidth = fadeWidth,
        segmentTravel = AndroidLyricTimeline.computeSegmentTravel(1000L, 2000L, 2000L, 120f, 1000L),
      )
    assertEquals(100f, firstDone, 0.001f)
    // 词 1 未开始（preRoll 尚未到）：行程 0，带左缘 = 80，带右缘 100 = 词 1 左缘 → 全暗
    val secondBefore =
      AndroidLyricTimeline.resolveWordGradientStartX(
        wordX = 100f,
        fadeWidth = fadeWidth,
        segmentTravel = AndroidLyricTimeline.computeSegmentTravel(2000L, 3000L, 1460L, 120f, 1000L),
      )
    assertEquals(80f, secondBefore, 0.001f)
    // 词 1 在 t=2000：preRoll 已提前起扫，带左缘 88.89 —— 词 1 左缘处已有渐变，亮区平滑衔接
    val secondStart =
      AndroidLyricTimeline.resolveWordGradientStartX(
        wordX = 100f,
        fadeWidth = fadeWidth,
        segmentTravel = AndroidLyricTimeline.computeSegmentTravel(2000L, 3000L, 2000L, 120f, 1000L),
      )
    assertEquals(80f * 120f / 1080f + 80f, secondStart, 0.001f)
  }
}
