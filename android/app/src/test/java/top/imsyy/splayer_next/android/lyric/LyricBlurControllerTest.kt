package top.imsyy.splayer_next.android.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricBlurControllerTest {
  @Test
  fun quantizesBlurKeyTo2pxSteps() {
    assertEquals(0, LyricBlurController.quantizeBlurKey(0f))
    assertEquals(1, LyricBlurController.quantizeBlurKey(2.4f))
    assertEquals(2, LyricBlurController.quantizeBlurKey(3f))
    assertEquals(3, LyricBlurController.quantizeBlurKey(6.9f))
  }

  @Test
  fun crossfadeProgressClampsToUnitRange() {
    // 渐强：0 → 10 途中，当前 5 → 目标档图权重 0.5
    assertEquals(0.5f, LyricBlurController.crossfadeProgress(5f, 0f, 10f), 0.001f)
    // 渐清：15 → 10 途中，当前 12 → 目标档图权重 0.6
    assertEquals(0.6f, LyricBlurController.crossfadeProgress(12f, 15f, 10f), 0.001f)
    // 越过两端时钳制
    assertEquals(1f, LyricBlurController.crossfadeProgress(12f, 0f, 10f), 0.001f)
    assertEquals(0f, LyricBlurController.crossfadeProgress(16f, 15f, 10f), 0.001f)
    // 起点与终点逼近（量化同档）：直接显示目标档
    assertEquals(1f, LyricBlurController.crossfadeProgress(10f, 10.1f, 10.2f), 0.001f)
  }

  @Test
  fun resolvesStaticDrawPlanByCurrentTargetAndSettled() {
    // 双零：清晰位图（收敛与否都优先于其他分支）
    assertEquals(
      LyricBlurController.StaticDrawPlan.CLEAR_ONLY,
      LyricBlurController.resolveStaticDrawPlan(0f, 0f, true),
    )
    assertEquals(
      LyricBlurController.StaticDrawPlan.CLEAR_ONLY,
      LyricBlurController.resolveStaticDrawPlan(0f, 0f, false),
    )
    // 收敛到非零目标：单张目标档模糊图
    assertEquals(
      LyricBlurController.StaticDrawPlan.SINGLE_BLUR,
      LyricBlurController.resolveStaticDrawPlan(10f, 10f, true),
    )
    // 渐清途中：起点档 + 目标档双图叠化（与渐强统一，不逐档重建）
    assertEquals(
      LyricBlurController.StaticDrawPlan.CROSSFADE,
      LyricBlurController.resolveStaticDrawPlan(15f, 10f, false),
    )
    // 渐强途中：起点档 + 目标档双图叠化
    assertEquals(
      LyricBlurController.StaticDrawPlan.CROSSFADE,
      LyricBlurController.resolveStaticDrawPlan(5f, 10f, false),
    )
  }

  @Test
  fun updateLineReturnsZeroWhenBlurDisabled() {
    val controller = LyricBlurController(density = 2f)
    controller.enableBlur = false
    val radius =
      controller.updateLine(
        index = 5,
        distanceIndex = 5,
        lineCount = 10,
        anchorIndex = 2,
        latestHighlightIndex = 2,
        active = false,
        isUserScrolling = false,
        inViewport = true,
        viewportCssPx = 400f,
        deltaMs = 16f,
      )
    assertEquals(0f, radius, 0.001f)
    assertFalse(controller.isAnimating())
  }

  @Test
  fun updateLineConvergesToBlurTargetExponentially() {
    val controller = LyricBlurController(density = 2f)
    controller.enableBlur = true
    // 窄视口（≤1024）档位 0.8 折：distance=|5-2|=3 → level=4 → 3.2 档 → 3.2×1.5×2=9.6px
    val targetPx = 3.2f * 1.5f * 2f
    var previous = -1f
    var radius: Float
    var frames = 0
    do {
      radius =
        controller.updateLine(
          index = 5,
          distanceIndex = 5,
          lineCount = 10,
          anchorIndex = 2,
          latestHighlightIndex = 2,
          active = false,
          isUserScrolling = false,
          inViewport = true,
          viewportCssPx = 400f,
          deltaMs = 16f,
        )
      // 渐强过程单调递增
      assertTrue(radius >= previous)
      previous = radius
      frames++
    } while (controller.isAnimating() && frames < 200)
    assertTrue(frames > 1)
    assertFalse(controller.isAnimating())
    // settled 阈值为 0.3 档（×1.5×density = 0.9px），收敛帧容差按 1px 计
    assertEquals(targetPx, radius, 1.0f)
    assertEquals(targetPx, controller.currentRadiusPx(5), 1.0f)
  }

  @Test
  fun updateLineRecordsSettledValueAsCrossfadeStartWhenTargetChanges() {
    val controller = LyricBlurController(density = 2f)
    controller.enableBlur = true
    // 先收敛到 distance=3 的目标档（level=4，窄视口 0.8 折 → 3.2 档 → 9.6px）
    var frames = 0
    do {
      controller.updateLine(
        index = 5,
        distanceIndex = 5,
        lineCount = 10,
        anchorIndex = 2,
        latestHighlightIndex = 2,
        active = false,
        isUserScrolling = false,
        inViewport = true,
        viewportCssPx = 400f,
        deltaMs = 16f,
      )
      if (frames == 0) {
        // 首帧：目标从 0 突变到 3.2 档，渐变起点记录为 0（从清晰图开始叠化）
        assertEquals(0f, controller.startRadiusPx(5), 0.001f)
      }
      frames++
    } while (controller.isAnimating() && frames < 200)
    val settledPx = controller.currentRadiusPx(5)
    assertEquals(9.6f, settledPx, 1.0f)
    // 锚点推进一行：distance 变 2 → 目标变 2.4 档，渐变起点必须记录为上一收敛值，
    // 否则叠化近端退回清晰图，已模糊行升档/降档时清晰分量会在渐变期闪入
    controller.updateLine(
      index = 5,
      distanceIndex = 5,
      lineCount = 10,
      anchorIndex = 3,
      latestHighlightIndex = 3,
      active = false,
      isUserScrolling = false,
      inViewport = true,
      viewportCssPx = 400f,
      deltaMs = 16f,
    )
    assertEquals(settledPx, controller.startRadiusPx(5), 0.001f)
  }

  @Test
  fun updateLineTargetsZeroWhileUserScrolling() {
    val controller = LyricBlurController(density = 2f)
    controller.enableBlur = true
    val radius =
      controller.updateLine(
        index = 5,
        distanceIndex = 5,
        lineCount = 10,
        anchorIndex = 2,
        latestHighlightIndex = 2,
        active = false,
        isUserScrolling = true,
        inViewport = true,
        viewportCssPx = 400f,
        deltaMs = 16f,
      )
    assertEquals(0f, radius, 0.001f)
  }
}
