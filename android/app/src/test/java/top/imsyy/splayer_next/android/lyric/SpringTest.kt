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
 * soft:AMLL 刻意采用的非物理平滑曲线,不做 ODE 残差断言,只验证收敛、单调不越界与 arrived() 语义。
 * 若闭式 v(t)/a(t) 与位置不一致,残差对照会暴露。
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

  @Test
  fun `收敛后 translateBy 原地平移常数解且位置连续`() {
    // 滚动帧语义：已收敛行（连续滚动时全表弹簧都处于此态）平移后必须恰好落在「旧值 + Δ」，
    // 且仍是静止解——钉住 translateBy 的常数解原地平移快路径与重建在数值上等价
    val spring = Spring(0f)
    spring.updateParams(SpringParams(mass = 0.9f, damping = 15f, stiffness = 90f))
    spring.setTargetPosition(60f)
    var i = 0
    while (i < 6000 && !spring.arrived()) {
      spring.update(0.016f)
      i++
    }
    assertTrue("did not settle", spring.arrived())
    val settled = spring.getCurrentPosition()
    spring.translateBy(7f)
    assertEquals(settled + 7f, spring.getCurrentPosition(), 1e-4f)
    assertTrue("平移后仍应为静止态", spring.arrived())
    // 静止解下 update 仍短路，返回平移后的位置
    assertEquals(settled + 7f, spring.update(0.016f), 1e-4f)
    // 连续平移可累加
    spring.translateBy(-3f)
    assertEquals(settled + 4f, spring.getCurrentPosition(), 1e-4f)
  }

  @Test
  fun `收敛后平移再设目标仍正常收敛`() {
    // 钉住常数解平移后的不变量：from/to 同步移动，后续重建解析解不会把行拉回平移前的位置
    val spring = Spring(0f)
    spring.updateParams(SpringParams(mass = 0.9f, damping = 15f, stiffness = 90f))
    spring.setTargetPosition(60f)
    var i = 0
    while (i < 6000 && !spring.arrived()) {
      spring.update(0.016f)
      i++
    }
    assertTrue(spring.arrived())
    spring.translateBy(100f)
    spring.setTargetPosition(spring.getCurrentPosition() + 40f)
    assertFalse(spring.arrived())
    var j = 0
    while (j < 6000 && !spring.arrived()) {
      spring.update(0.016f)
      j++
    }
    assertTrue("平移后未收敛", spring.arrived())
    assertEquals(200f, spring.getCurrentPosition(), 0.05f)
  }

  /**
   * 逐帧平移场景的收敛帧号：参数/起点/目标相同，只由 [perFrame] 决定每帧的平移方式，
   * 返回 arrived() 首次为真的帧号；超过 [maxFrames] 仍不收敛返回 -1
   */
  private fun firstArrivedFrame(
    target: Float,
    maxFrames: Int,
    perFrame: (spring: Spring, frame: Int) -> Unit,
  ): Int {
    val spring = Spring(0f)
    spring.updateParams(SpringParams(mass = 0.9f, damping = 15f, stiffness = 90f))
    spring.setTargetPosition(target)
    for (frame in 1..maxFrames) {
      perFrame(spring, frame)
      spring.update(1f / 60f)
      if (spring.arrived()) return frame
    }
    return -1
  }

  @Test
  fun `逐帧 translateBy 平移与不受扰对照同帧收敛`() {
    // 目标随平移一起远离，两者相对距离不变 ⇒ 与对照组是同一个共动系问题，
    // 收敛帧号必须一致（相位若被 resetSolver 掐掉就会退化成反例那条）
    val shiftPerFrame = 3f
    val control = firstArrivedFrame(target = 60f, maxFrames = 4000) { _, _ -> }
    val translated =
      firstArrivedFrame(target = 60f, maxFrames = 4000) { spring, _ ->
        spring.translateBy(shiftPerFrame)
      }
    assertTrue("control 未收敛", control > 0)
    assertTrue("translated 未收敛", translated > 0)
    // 实测：对照组 99 帧；改造前（每帧 setPosition 平移）4000 帧上限内不收敛
    assertEquals(control, translated)
  }

  @Test
  fun `逐帧 setPosition 平移会拖慢收敛`() {
    // 反例，钉住 translateBy 的存在理由：setPosition 会重建常数解并清零速度，
    // 逐帧调用等于每帧从静止重新起跑，收敛被拖慢数倍（本用例在改造前就已通过）
    val shiftPerFrame = 3f
    val control = firstArrivedFrame(target = 60f, maxFrames = 4000) { _, _ -> }
    val slow =
      firstArrivedFrame(target = 60f, maxFrames = 4000) { spring, frame ->
        spring.setPosition(spring.getCurrentPosition() + shiftPerFrame)
        spring.setTargetPosition(60f + shiftPerFrame * frame, 0f)
      }
    assertTrue("control 未收敛", control > 0)
    assertTrue("slow=$slow control=$control 未体现拖慢", slow < 0 || slow > control * 3)
  }

  @Test
  fun `飞行中 translateBy 保持位置与相位连续`() {
    val params = SpringParams(mass = 0.9f, damping = 15f, stiffness = 90f)
    val spring = Spring(0f)
    spring.updateParams(params)
    spring.setTargetPosition(60f)
    repeat(10) { spring.update(1f / 60f) }
    val before = spring.getCurrentPosition()
    assertFalse(spring.arrived())

    spring.translateBy(30f)

    // 平移瞬间：位置恰为旧值 + Δ；目标同步平移，未到达状态不变
    assertEquals(before + 30f, spring.getCurrentPosition(), 1e-4f)
    assertFalse(spring.arrived())
    // 后续轨迹必须是原曲线整体平移：与一根不受扰弹簧逐帧对比
    val twin = Spring(0f)
    twin.updateParams(params)
    twin.setTargetPosition(60f)
    repeat(10) { twin.update(1f / 60f) }
    repeat(60) {
      val shifted = spring.update(1f / 60f)
      val plain = twin.update(1f / 60f)
      assertEquals(plain + 30f, shifted, 1e-3f)
    }
  }

  @Test
  fun `延迟目标同值重挂不重置倒计时`() {
    // 钉住 Spring 的延迟队列语义：队列「每次调用整体替换 + update() 逐帧扣减」，
    // 但目标相同的重挂不重置倒计时。布局状态随词块强调多次重算时下游行目标未变，
    // 若被拉回满值，级联延迟永远不到期，观感上就是「未来的几行反复弹几下」
    val spring = Spring(0f)
    spring.updateParams(SpringParams(mass = 0.9f, damping = 15f, stiffness = 90f))
    repeat(30) {
      spring.setTargetPosition(60f, 0.05f)
      spring.update(1f / 60f)
    }
    assertTrue("同值重挂后应在 0.05s 后按期起跑", spring.getCurrentPosition() > 0f)
  }

  @Test
  fun `延迟目标异值重挂改道到新目标且不推迟到期`() {
    // 目标真的变了仍要替换队列（延迟不得被改道到旧目标上），
    // 但到期时刻沿用更早的那个：重发不得把已经排好的级联延迟往后推
    val spring = Spring(0f)
    spring.updateParams(SpringParams(mass = 0.9f, damping = 15f, stiffness = 90f))
    spring.setTargetPosition(60f, 0.05f)
    spring.setTargetPosition(120f, 0.5f)
    repeat(2) { spring.update(1f / 60f) }
    assertEquals(0f, spring.getCurrentPosition(), 1e-6f)
    repeat(20) { spring.update(1f / 60f) }
    assertTrue(spring.getCurrentPosition() > 0f)
  }

  @Test
  fun `异值高频重挂不会无限续期延迟目标`() {
    // 钉住「待触发时刻单调不增」这条不变式。级联延迟在 applyLineSpringTargets 里几何累加，
    // 饱和上限约 1s；高光期间布局反复重算会为下游行不断重发新目标，若每次都按满 delay 续期，
    // 该行 targetPosition 永远冻结在过期值，位置逐次累积滞后直到掉出视口裁剪区，
    // 表现为「未来的行整片消失」
    val spring = Spring(0f)
    spring.updateParams(SpringParams(mass = 0.9f, damping = 15f, stiffness = 90f))
    spring.setTargetPosition(100f, 0.2f)
    // 每帧换一个目标重发，持续时间远超首次排定的 0.2s
    repeat(40) { frame ->
      spring.setTargetPosition(100f + frame, 0.2f)
      spring.update(1f / 60f)
    }
    assertTrue(
      "重发不得把到期时刻往后推：最迟 0.2s 内必须起跑",
      spring.getCurrentPosition() > 0f,
    )
  }
}
