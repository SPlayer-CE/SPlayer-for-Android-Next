package top.imsyy.splayer_next.android.lyric

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 激活行 HDR 的三态聚合真值表：任一态不成立都必须回退 SDR */
class LyricHdrControllerTest {
  @Test
  fun requiresAllThreeStates() {
    val controller = LyricHdrController()
    controller.updateCapability(HdrCapability.Supported)
    controller.enabled = true
    controller.foreground = true
    assertTrue(controller.isHdrActive)

    // 面板不支持：即便开关与前台都成立也不申请 HDR 输出
    controller.updateCapability(HdrCapability.UnsupportedDisplay)
    assertFalse(controller.isHdrActive)

    controller.updateCapability(HdrCapability.Supported)
    // 开关关闭
    controller.enabled = false
    controller.foreground = true
    assertFalse(controller.isHdrActive)

    // 覆盖层退到后台
    controller.enabled = true
    controller.foreground = false
    assertFalse(controller.isHdrActive)
  }

  @Test
  fun defaultsToInactive() {
    val controller = LyricHdrController()
    assertFalse(controller.enabled)
    assertFalse(controller.foreground)
    assertFalse(controller.isHdrActive)
  }

  @Test
  fun gainFallsBackWhenHeadroomUnavailable() {
    val controller = LyricHdrController()
    val fallback = controller.resolveHighlightGain(null)
    // null / 非有限 / ≤1 三种「问不到或没有余量」都收敛到同一固定增益
    assertEquals(fallback, controller.resolveHighlightGain(Float.NaN), 0.0001f)
    assertEquals(fallback, controller.resolveHighlightGain(0f), 0.0001f)
    assertEquals(fallback, controller.resolveHighlightGain(1f), 0.0001f)
    assertTrue("回落增益必须高于 SDR", fallback > 1f)
    // 未定标前实例就绪即为可用的固定增益
    assertEquals(fallback, controller.highlightGain, 0.0001f)
  }

  @Test
  fun gainRisesWithHeadroomAndStopsAtCeiling() {
    val controller = LyricHdrController()
    val modest = controller.resolveHighlightGain(2f)
    val typical = controller.resolveHighlightGain(8f)
    val ceiling = controller.resolveHighlightGain(400f)
    assertTrue("余量越大增益越高", modest < typical)
    assertTrue("高余量必须顶到上限", typical <= ceiling)
    // 上限是显式旋钮：爆掉的余量与常规高余量收敛到同一个天花板
    assertEquals(ceiling, controller.resolveHighlightGain(30f), 0.0001f)
    assertTrue("上限本身必须高于任何未截断的增益", ceiling > typical)
  }

  @Test
  fun gainMatchesSrgbTransferInversion() {
    val controller = LyricHdrController()
    val headroom = 6f
    val gain = controller.resolveHighlightGain(headroom)
    // 增益是编码域分量，反解回线性亮度应还原出原始余量
    val linear = Math.pow(((gain + 0.055) / 1.055).toDouble(), 2.4).toFloat()
    assertEquals(headroom, linear, 0.01f)
  }

  @Test
  fun quantizeAlphaKeepsEndpointsAndRgb() {
    val controller = LyricHdrController()
    // 静息行与激活行的色标正好落在两端：量化必须原样保留，否则满亮度字会被压暗
    assertEquals(0, controller.quantizeAlpha(0x00000000))
    assertEquals(0xFFFFFFFF.toInt(), controller.quantizeAlpha(0xFFFFFFFF.toInt()))
    // RGB 原样保留在键里，换字色必然换键（否则 HDR 渐变缓存会串色）
    assertEquals(0xFF00FF00.toInt(), controller.quantizeAlpha(0xFF00FF00.toInt()))
    assertTrue(
      "不同 RGB 必须换键",
      controller.quantizeAlpha(0x80336699.toInt()) != controller.quantizeAlpha(0x803366AA.toInt()),
    )
  }

  @Test
  fun quantizeAlphaCollapsesOneStepAndIsIdempotent() {
    val controller = LyricHdrController()
    // 相邻 alpha 落到同一档：交叉淡入淡出逐帧变化才可能命中缓存
    val bucket = controller.quantizeAlpha(0x80000000.toInt())
    assertEquals(bucket, controller.quantizeAlpha(0x81000000.toInt()))
    // 再量化不再变化，缓存键不会在同一档内漂移
    assertEquals(bucket, controller.quantizeAlpha(bucket))
  }

  @Test
  fun windowColorModeStaysInsideLegalEnum() {
    // COLOR_MODE_* 是 @IntDef 互斥枚举而非位标志：HDR(2) or WIDE_COLOR_GAMUT(1) 会得到 3，
    // 即 @hide 的 COLOR_MODE_HDR10（仅内部测试用），窗口侧静默拒绝会让 HDR 开关形同虚设
    assertEquals(2, LyricHdrController.HDR_COLOR_MODE)
    assertEquals(ActivityInfo.COLOR_MODE_HDR, LyricHdrController.HDR_COLOR_MODE)
  }
}
