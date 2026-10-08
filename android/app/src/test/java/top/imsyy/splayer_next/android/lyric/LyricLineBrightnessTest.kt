package top.imsyy.splayer_next.android.lyric

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricLineBrightnessTest {
  @Test
  fun windowCoversFocusAndHighlightedPlusTwo() {
    val range = LyricLineBrightness.windowRange(anchorIndex = 5, highlightedIndices = setOf(5, 7))
    assertEquals(3..9, range)
  }

  @Test
  fun paletteMatchesPreAlignmentStops() {
    val brightness = LyricLineBrightness()
    // 静息行：played 与 unplayed 相等，整行均匀 inactiveAlpha
    assertEquals(0.2f, brightness.dimOf(0f), 1e-6f)
    assertEquals(0.2f, brightness.playedAlpha(1f, 0f), 1e-6f)
    assertEquals(0.2f, brightness.unplayedAlpha(1f), 1e-6f)
    // 激活行：亮部回到行级 lineAlpha，暗层归零
    assertEquals(0f, brightness.dimOf(1f), 1e-6f)
    assertEquals(1f, brightness.playedAlpha(1f, 1f), 1e-6f)
    // 呈现中 0.85 档与 BG 0.4 档：暗部固定 inactiveAlpha × lineAlpha
    assertEquals(0.85f, brightness.playedAlpha(0.85f, 1f), 1e-6f)
    assertEquals(0.17f, brightness.unplayedAlpha(0.85f), 1e-6f)
    assertEquals(0.4f, brightness.playedAlpha(0.4f, 1f), 1e-6f)
    assertEquals(0.08f, brightness.unplayedAlpha(0.4f), 1e-6f)
  }

  @Test
  fun inactiveAlphaSettingDrivesBothDimAndUnplayed() {
    val brightness = LyricLineBrightness()
    brightness.inactiveAlpha = 0.5f
    // 未激活行的颜色与激活行暗部随设置项一起变
    assertEquals(0.5f, brightness.playedAlpha(1f, 0f), 1e-6f)
    assertEquals(0.5f, brightness.unplayedAlpha(1f), 1e-6f)
    // 激活行亮部不受该设置影响
    assertEquals(1f, brightness.playedAlpha(1f, 1f), 1e-6f)
  }

  @Test
  fun maskContrastGrowsWithProgress() {
    val brightness = LyricLineBrightness()
    // p = 0：两端相等，整行均匀；p = 1：暗部收窄到 inactiveAlpha 倍，扫光对比最大
    assertEquals(0f, brightness.playedAlpha(1f, 0f) - brightness.unplayedAlpha(1f), 1e-6f)
    assertEquals(0.8f, brightness.playedAlpha(1f, 1f) - brightness.unplayedAlpha(1f), 1e-6f)
  }

  @Test
  fun highlightEnterAndExitFollowCrossfadeDurations() {
    val brightness = LyricLineBrightness()
    // 进入：300ms 补满
    var p = brightness.update(0, inWindow = true, highlighted = true, deltaMs = 150f, nowMs = 0L)
    assertEquals(0.5f, p, 1e-6f)
    p = brightness.update(0, inWindow = true, highlighted = true, deltaMs = 150f, nowMs = 150L)
    assertEquals(1f, p, 1e-6f)
    // 退出：淡出当帧即开始（200ms 交叉淡出），500ms 保留期只推迟窗口外硬回收，不冻结淡出
    p = brightness.update(0, inWindow = true, highlighted = false, deltaMs = 100f, nowMs = 160L)
    assertEquals(0.5f, p, 1e-6f)
    p = brightness.update(0, inWindow = true, highlighted = false, deltaMs = 100f, nowMs = 260L)
    assertEquals(0f, p, 1e-6f)
  }

  @Test
  fun outOfWindowRecyclesAfterRetainPeriod() {
    val brightness = LyricLineBrightness()
    brightness.update(0, inWindow = true, highlighted = true, deltaMs = 300f, nowMs = 0L)
    // 高亮退出进入 500ms 保留期：窗口外也不硬回收，让退出淡出完整播放
    assertEquals(1f, brightness.update(0, inWindow = true, highlighted = false, deltaMs = 0f, nowMs = 0L), 1e-6f)
    assertEquals(1f, brightness.update(0, inWindow = false, highlighted = false, deltaMs = 0f, nowMs = 400L), 1e-6f)
    // 保留期结束且窗口外：直接回收为静息暗层
    assertEquals(0f, brightness.update(0, inWindow = false, highlighted = false, deltaMs = 0f, nowMs = 600L), 1e-6f)
  }

  @Test
  fun snapFallsBackToRestingDimWithoutRetention() {
    val brightness = LyricLineBrightness()
    brightness.update(0, inWindow = true, highlighted = true, deltaMs = 300f, nowMs = 0L)
    // 冷同步不开启保留期：非高亮行直接回到静息暗层，避免 seek 后残留高亮尾迹
    brightness.snap(0, highlighted = false)
    val p = brightness.update(0, inWindow = true, highlighted = false, deltaMs = 0f, nowMs = 100L)
    assertEquals(0f, p, 1e-6f)
    assertEquals(0.2f, brightness.playedAlpha(1f, p), 1e-6f)
    brightness.snap(0, highlighted = true)
    // 瞬移后亮层进度立即为 1，played 端回到全亮
    val restored = brightness.update(0, inWindow = true, highlighted = true, deltaMs = 0f, nowMs = 100L)
    assertEquals(1f, restored, 1e-6f)
    assertEquals(1f, brightness.playedAlpha(1f, restored), 1e-6f)
  }
}
