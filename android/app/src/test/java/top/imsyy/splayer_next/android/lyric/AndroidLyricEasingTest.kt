package top.imsyy.splayer_next.android.lyric

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H-2 查找表误差验证:查表值与牛顿迭代参考实现误差必须 < 0.005,
 * 保证缓动曲线肉眼无差异(视效不变约束)。
 */
class AndroidLyricEasingTest {
  private fun assertTableError(
    tabled: (Float) -> Float,
    control: (Float) -> Float,
    tol: Float = 0.005f,
  ) {
    // 0.5 附近为曲率最大处,加密采样
    val samples = mutableListOf<Float>()
    for (i in 0..100) samples.add(i / 100f)
    for (i in 0..20) samples.add(0.48f + i * 0.002f)
    for (x in samples) {
      val err = abs(tabled(x) - control(x))
      assertTrue("x=$x err=$err", err < tol)
    }
  }

  @Test
  fun `empIn 查表与牛顿迭代一致`() {
    assertTableError(
      { AndroidLyricEasing.empIn(it) },
      { AndroidLyricEasing.cubicBezier(0.2f, 0.4f, 0.58f, 1f, it) },
    )
  }

  @Test
  fun `empOut 查表与牛顿迭代一致`() {
    assertTableError(
      { AndroidLyricEasing.empOut(it) },
      { AndroidLyricEasing.cubicBezier(0.3f, 0f, 0.58f, 1f, it) },
    )
  }

  @Test
  fun `easeOut58 查表与牛顿迭代一致`() {
    assertTableError(
      { AndroidLyricEasing.easeOut58(it) },
      { AndroidLyricEasing.cubicBezier(0f, 0f, 0.58f, 1f, it) },
    )
  }

  @Test
  fun `端点精确`() {
    assertEquals(0f, AndroidLyricEasing.empIn(0f), 0f)
    assertEquals(1f, AndroidLyricEasing.empIn(1f), 0f)
    assertEquals(0f, AndroidLyricEasing.empOut(0f), 0f)
    assertEquals(1f, AndroidLyricEasing.empOut(1f), 0f)
    assertEquals(0f, AndroidLyricEasing.easeOut58(0f), 0f)
    assertEquals(1f, AndroidLyricEasing.easeOut58(1f), 0f)
  }

  @Test
  fun `缓动曲线单调递增`() {
    var prevIn = -1f
    var prevOut = -1f
    for (i in 0..256) {
      val x = i / 256f
      val vIn = AndroidLyricEasing.empIn(x)
      val vOut = AndroidLyricEasing.empOut(x)
      assertTrue(vIn >= prevIn - 1e-5f)
      assertTrue(vOut >= prevOut - 1e-5f)
      prevIn = vIn
      prevOut = vOut
    }
  }

  @Test
  fun `牛顿迭代参考实现端点正确`() {
    assertEquals(0f, AndroidLyricEasing.cubicBezier(0.2f, 0.4f, 0.58f, 1f, 0f), 0f)
    assertEquals(1f, AndroidLyricEasing.cubicBezier(0.2f, 0.4f, 0.58f, 1f, 1f), 0f)
  }
}
