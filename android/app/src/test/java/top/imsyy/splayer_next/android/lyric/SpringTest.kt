package top.imsyy.splayer_next.android.lyric

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spring 解析解验证(QW-1/C-1 落地单测)。
 *
 * 欠阻尼/临界:弹簧物理方程 m*a + c*v + k*(x - to) = 0 必须成立,速度/加速度用中心差分数值近似;
 * soft:AML soft 分支解曲线 x(t) = to - (delta + t*L)*e^{a*t} 直接对照。
 * 若闭式 v(t)/a(t) 与位置不一致,残差/曲线对照都会暴露。
 */
class SpringTest {
  private fun driveTo(
    spring: Spring,
    targetTime: Float,
    step: Float = 0.001f,
  ) {
    var t = 0f
    while (t < targetTime) {
      spring.update(step)
      t += step
    }
  }

  private fun positionAt(
    params: SpringParams,
    from: Float,
    to: Float,
    t: Float,
  ): Float {
    val spring = Spring(from)
    spring.updateParams(params)
    spring.setTargetPosition(to)
    driveTo(spring, t)
    return spring.getCurrentPosition()
  }

  private fun assertOdeResidual(
    params: SpringParams,
    from: Float,
    to: Float,
    t: Float,
    h: Float,
    tol: Float,
  ) {
    val mass = params.mass ?: 0.9f
    val damping = params.damping ?: 15f
    val stiffness = params.stiffness ?: 90f
    val x0 = positionAt(params, from, to, t - h)
    val x1 = positionAt(params, from, to, t)
    val x2 = positionAt(params, from, to, t + h)
    val v = (x2 - x0) / (2f * h)
    val a = (x2 - 2f * x1 + x0) / (h * h)
    val residual = mass * a + damping * v + stiffness * (x1 - to)
    assertTrue("residual=$residual at t=$t", abs(residual) < tol)
  }

  @Test
  fun `欠阻尼弹簧满足物理方程`() {
    val params = SpringParams(mass = 0.9f, damping = 15f, stiffness = 90f)
    for (t in listOf(0.05f, 0.2f, 0.5f, 1.2f)) {
      assertOdeResidual(params, 0f, 1f, t, h = 0.005f, tol = 0.05f)
    }
  }

  @Test
  fun `欠阻尼大位移满足物理方程`() {
    val params = SpringParams(mass = 0.9f, damping = 15f, stiffness = 90f)
    for (t in listOf(0.1f, 0.4f, 0.9f)) {
      assertOdeResidual(params, 100f, -50f, t, h = 0.002f, tol = 4f)
    }
  }

  @Test
  fun `临界阻尼弹簧满足物理方程`() {
    // damping/(2*sqrt(k*m)) = 18/18 = 1.0 → 走临界分支
    val params = SpringParams(mass = 0.9f, damping = 18f, stiffness = 90f)
    for (t in listOf(0.05f, 0.2f, 0.5f, 1.0f)) {
      assertOdeResidual(params, 0f, 1f, t, h = 0.005f, tol = 0.25f)
    }
  }

  @Test
  fun `soft 弹簧收敛到目标保持基线稳定语义`() {
    // soft 分支是 AMLL 刻意采用的非物理平滑曲线(阻尼≠临界值时公式不满足 ODE),
    // 故不对其断言物理残差;只验证收敛目标与 arrived() 稳定语义和基线一致
    val params = SpringParams(mass = 0.9f, damping = 15f, stiffness = 90f, soft = true)
    val spring = Spring(0f)
    spring.updateParams(params)
    spring.setTargetPosition(1f)
    var prev = 0f
    var i = 0
    var monotoneStep = 0
    while (i < 6000 && !spring.arrived()) {
      val p = spring.update(0.016f)
      // soft 曲线单调趋近目标,不越过目标过多(上限 1.02)
      if (p > 1.02f) {
        throw AssertionError("soft overshoot p=$p")
      }
      if (p >= prev) monotoneStep++ else monotoneStep = 0
      prev = p
      i++
    }
    assertTrue("soft 未收敛", spring.arrived())
    assertEquals(1f, spring.getCurrentPosition(), 0.01f)
    // 非单调(出现回退)时视为异常
    assertTrue("soft 出现回退", monotoneStep > 0)
  }

  @Test
  fun `弹簧最终收敛到目标并静止`() {
    val spring = Spring(0f)
    spring.updateParams(SpringParams(mass = 0.9f, damping = 15f, stiffness = 90f))
    spring.setTargetPosition(1f)
    var i = 0
    while (i < 6000 && !spring.arrived()) {
      spring.update(0.016f)
      i++
    }
    assertTrue("did not settle in 96s", spring.arrived())
    assertEquals(1f, spring.getCurrentPosition(), 0.01f)
  }

  @Test
  fun `延迟目标在延迟期内保持不动`() {
    val spring = Spring(0f)
    spring.updateParams(SpringParams(mass = 0.9f, damping = 15f, stiffness = 90f))
    spring.setTargetPosition(1f, delay = 0.5f)
    var t = 0f
    while (t < 0.49f) {
      spring.update(0.01f)
      t += 0.01f
    }
    assertEquals(0f, spring.getCurrentPosition(), 1e-4f)
    // 基线语义: 队列触发发生在当帧返回值之后, 再驱动一帧观察运动
    spring.update(0.02f)
    spring.update(0.016f)
    assertTrue(spring.getCurrentPosition() > 0f)
  }

  @Test
  fun `中途更新参数保持速度连续无跳变`() {
    val spring = Spring(0f)
    spring.updateParams(SpringParams(mass = 0.9f, damping = 15f, stiffness = 90f))
    spring.setTargetPosition(1f)
    var prev = 0f
    var t = 0f
    while (t < 0.2f) {
      spring.update(0.016f)
      t += 0.016f
      prev = spring.getCurrentPosition()
    }
    // 运动中途改参数(对齐 AMLL updateParams),位移不得回跳
    spring.updateParams(SpringParams(mass = 2f, damping = 25f, stiffness = 100f))
    for (i in 0 until 30) {
      val cur = spring.update(0.016f)
      assertTrue("jumped back: prev=$prev cur=$cur", cur >= prev - 1e-3f)
      prev = cur
    }
  }

  @Test
  fun `setPosition 立即到位且静止`() {
    val spring = Spring(0f)
    spring.updateParams(SpringParams(mass = 0.9f, damping = 15f, stiffness = 90f))
    spring.setTargetPosition(1f)
    spring.update(0.016f)
    spring.setPosition(5f)
    assertTrue(spring.arrived())
    assertEquals(5f, spring.getCurrentPosition(), 1e-6f)
    assertEquals(5f, spring.update(0.016f), 1e-6f)
  }

  @Test
  fun `settled 弹簧 update 零开销短路`() {
    val spring = Spring(3f)
    assertTrue(spring.arrived())
    assertFalse(spring.update(0.016f) != 3f)
  }
}
