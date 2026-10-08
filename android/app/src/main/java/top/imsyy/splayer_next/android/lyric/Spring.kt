package top.imsyy.splayer_next.android.lyric

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 弹簧参数,对齐 AMLL SpringParams。
 * 所有字段可空,支持部分更新(等价于 AMLL 的 Partial<SpringParams>)。
 */
data class SpringParams(
  val mass: Float? = null,
  val damping: Float? = null,
  val stiffness: Float? = null,
  val soft: Boolean? = null,
)

private data class QueuedUpdate<T>(
  val value: T,
  var time: Float,
)

/**
 * 弹簧解析解曲线:位置、速度、加速度的闭式表达式。
 * 由 [Spring.buildSpringSolution] 一次性生成,消除数值微分开销。
 * 以普通字段+方法直接求值替代函数类型,规避逐帧装箱与每次重定向的闭包分配;
 * 公式与原闭包实现逐字一致,输出逐位相同。
 */
private class SpringSolution private constructor(
  /** 静止解标记:position 恒为 to,velocity/acceleration 恒为 0 */
  private val constant: Boolean,
  /** 构建时起始位置,负时间分支(delay 语义)使用;静止解需原地平移,故为可变 */
  private var from: Float,
  /** 目标位置;静止解需原地平移,故为可变 */
  private var to: Float,
  /** true 为欠阻尼,false 为临界阻尼/soft */
  private val underDamped: Boolean,
  // 临界阻尼/soft 系数: x(t)=to-(delta+t*leftover)*e^{at}
  private val angularFreq: Float,
  private val criticalLeftover: Float,
  // 欠阻尼系数
  private val underLeftover: Float,
  private val dfm: Float,
  private val dm: Float,
) {
  fun position(t: Float): Float {
    if (constant) return to
    val elapsed = t
    if (elapsed < 0f) return from
    val delta = to - from
    if (underDamped) {
      val e = exp(elapsed * dm)
      val c = cos(elapsed * dfm)
      val s = sin(elapsed * dfm)
      return to - (delta * c + underLeftover * s) * e
    }
    return to - (delta + elapsed * criticalLeftover) * exp(elapsed * angularFreq)
  }

  fun velocity(t: Float): Float {
    if (constant) return 0f
    val elapsed = t
    if (elapsed < 0f) return 0f
    val delta = to - from
    if (underDamped) {
      val e = exp(elapsed * dm)
      val c = cos(elapsed * dfm)
      val s = sin(elapsed * dfm)
      val p = delta * c + underLeftover * s
      val q = underLeftover * c - delta * s
      return -e * (dm * p + dfm * q)
    }
    return -exp(elapsed * angularFreq) * (criticalLeftover + angularFreq * (delta + elapsed * criticalLeftover))
  }

  fun acceleration(t: Float): Float {
    if (constant) return 0f
    val elapsed = t
    if (elapsed < 0f) return 0f
    val delta = to - from
    if (underDamped) {
      val e = exp(elapsed * dm)
      val c = cos(elapsed * dfm)
      val s = sin(elapsed * dfm)
      val p = delta * c + underLeftover * s
      val q = underLeftover * c - delta * s
      return e * ((dfm * dfm - dm * dm) * p - 2f * dm * dfm * q)
    }
    val e = exp(elapsed * angularFreq)
    val u = criticalLeftover + angularFreq * (delta + elapsed * criticalLeftover)
    return -e * angularFreq * (criticalLeftover + u)
  }

  /** 是否为静止解(常数曲线)：静止解可原地平移而不必重建，见 [translate] */
  val isConstant: Boolean get() = constant

  /**
   * 静止解原地平移：常数曲线只有目标值参与求值(position 恒返回 to，速度与加速度恒为 0)，
   * 平移后仍是同一条曲线整体位移，只需同步移动 from/to，无需重建对象。
   *
   * 仅静止解可调用：非静止解的闭式解依赖 delta 与 v0，平移后必须由
   * [Spring.buildSpringSolution] 重建。
   */
  fun translate(delta: Float) {
    from += delta
    to += delta
  }

  companion object {
    /** 静止解:位置恒为目标位置,速度与加速度恒为 0 */
    fun constant(target: Float): SpringSolution = SpringSolution(true, target, target, false, 0f, 0f, 0f, 0f, 0f)

    /** 临界阻尼/soft 解 */
    fun critical(
      from: Float,
      to: Float,
      angularFreq: Float,
      leftover: Float,
    ): SpringSolution = SpringSolution(false, from, to, false, angularFreq, leftover, 0f, 0f, 0f)

    /** 欠阻尼解 */
    fun underDamped(
      from: Float,
      to: Float,
      leftover: Float,
      dfm: Float,
      dm: Float,
    ): SpringSolution = SpringSolution(false, from, to, true, 0f, 0f, leftover, dfm, dm)
  }
}

/**
 * 弹簧物理引擎,移植自 AMLL spring.ts。
 *
 * 构造时仅传入初始位置,参数通过 [updateParams] 设置。
 * 延迟队列支持延迟更新位置和参数,对齐 AMLL 的 pendingPosition / pendingParams。
 * [settled] 标志用于短路 [update] 和 [arrived],避免静止弹簧的冗余计算。
 * 性能:resetSolver() 一次性求解解析解,每帧 update() 仅 3 次解方法直接求值,无数值微分、无装箱分配。
 */
class Spring(
  currentPosition: Float = 0f,
) {
  private var currentPosition = currentPosition
  private var targetPosition = currentPosition
  private var currentTime = 0f
  private var params: SpringParams = SpringParams()
  private var solution: SpringSolution = SpringSolution.constant(targetPosition)
  private var queueParams: QueuedUpdate<SpringParams>? = null
  private var queuePosition: QueuedUpdate<Float>? = null

  /** 是否已稳定(对齐 AMLL settled 字段) */
  private var settled = true

  /** 位置延迟队列是否为空（卡住归位安全网用：排队中的行豁免） */
  fun isQueueEmpty(): Boolean = queuePosition == null

  /**
   * 根据当前参数一次性构建解析解曲线。
   * 对齐 solveSpring 的系数公式,直接推导 position/velocity/acceleration 闭式表达式。
   * 欠阻尼: d=-c/(2m), w=sqrt(4mk-c^2)/(2m), L=(c*delta-2m*v0)/sqrt(4mk-c^2)
   *   P=delta*cos(wt)+L*sin(wt), Q=L*cos(wt)-delta*sin(wt)
   *   x(t)=to-e^{dt}*P, v(t)=-e^{dt}*(dP+wQ), a(t)=e^{dt}*((w^2-d^2)P-2dwQ)
   * 临界/soft: a=-sqrt(k/m), L=-a*delta-v0
   *   x(t)=to-(delta+tL)e^{at}, v(t)=-e^{at}[L+a(delta+tL)], a(t)=-e^{at}*a[2L+a(delta+tL)]
   * elapsed<0 时返回 from/0/0 保持 delay 语义。
   */
  private fun buildSpringSolution(): SpringSolution {
    val soft = params.soft ?: false
    // 对齐 AMLL solveSpring 缺省值：未显式给出的参数回落到 100/10/1
    val stiffness = params.stiffness ?: 100f
    val damping = params.damping ?: 10f
    val mass = params.mass ?: 1f
    val from = currentPosition
    val to = targetPosition
    val delta = to - from
    val v0 = solution.velocity(currentTime)

    return if (soft || 1f <= damping / (2f * sqrt(stiffness * mass))) {
      // 临界阻尼 / soft
      val angularFreq = -sqrt(stiffness / mass)
      val leftover = -angularFreq * delta - v0
      SpringSolution.critical(from, to, angularFreq, leftover)
    } else {
      // 欠阻尼
      val dampedFreq = sqrt(4f * mass * stiffness - damping * damping)
      val leftover = (damping * delta - 2f * mass * v0) / dampedFreq
      val dfm = (0.5f * dampedFreq) / mass
      val dm = (-0.5f * damping) / mass
      SpringSolution.underDamped(from, to, leftover, dfm, dm)
    }
  }

  private fun resetSolver() {
    solution = buildSpringSolution()
    currentTime = 0f
    settled = false
  }

  /**
   * 弹簧是否已到达目标并静止(对齐 AMLL arrived)
   *
   * settled 为 true 时直接返回;
   * 否则检查位置/速度/加速度差值及队列状态,满足条件时标记 settled
   */
  fun arrived(): Boolean {
    if (settled) return true
    if (queueParams != null || queuePosition != null) return false
    val sol = solution
    val isSettled =
      abs(targetPosition - currentPosition) < 0.01f &&
        abs(sol.velocity(currentTime)) < 0.01f &&
        abs(sol.acceleration(currentTime)) < 0.01f
    if (isSettled) {
      settled = true
      currentPosition = targetPosition
      // 收敛后归一到常数解：位置恒等于目标（等价于旧解在 t→∞ 的极限，被丢弃的残余速度/加速度
      // 已由上面的判据限制在 0.01 以内，远低于一个像素），且后续滚动帧的 translateBy 能走
      // 原地平移快路径；不归一的话非常数解会迫使每帧重建解析解
      solution = SpringSolution.constant(targetPosition)
    }
    return isSettled
  }

  /**
   * 瞬间设置位置,跳过弹簧动画(对齐 AMLL setPosition)
   *
   * 同时清空所有排队中的更新,将弹簧标记为稳定状态
   */
  fun setPosition(targetPosition: Float) {
    this.targetPosition = targetPosition
    this.currentPosition = targetPosition
    this.solution = SpringSolution.constant(targetPosition)
    this.settled = true
    this.queueParams = null
    this.queuePosition = null
  }

  /**
   * 设置目标位置,delay > 0 时延迟生效(单位:秒)
   *
   * 对齐 AMLL:无延迟且目标与当前目标相差不足 0.001 时,仅丢弃排队中的位置更新并直接返回,
   * 不重启求解器,避免相同目标反复 resetSolver 打断弹簧运动的连续性
   *
   * 不变式:带延迟的重复排队**不得推迟**待触发时刻。同值沿用原倒计时,异值沿用更早的到期时刻,
   * 使队列到期时间单调不增。调用方可以任意频繁地重发目标(布局重算),最迟仍在首次排定的那次
   * 延迟内生效;否则级联延迟会被无限续期,行位置冻结在过期目标上并逐次累积滞后
   */
  fun setTargetPosition(
    targetPosition: Float,
    delay: Float = 0f,
  ) {
    if (delay <= 0f && abs(this.targetPosition - targetPosition) < 0.001f) {
      queuePosition = null
      return
    }
    if (delay > 0f) {
      // 延迟队列的 time 是逐帧扣减的倒计时,重新挂载会把它拉回满值。布局重算(锚点前移、
      // 活跃行集合变化)会为全部下游行重发目标,若每次都按满 delay 重挂,级联延迟(几何累加,
      // 饱和上限约 1s)永远归不了零,该行的 targetPosition 就此冻结在过期值上,位置逐次累积
      // 滞后直到掉出视口裁剪区——表现就是「未来的行整片消失」。
      //
      // 两个收口:
      // - 目标相同:直接沿用原倒计时,重发不推迟;
      // - 目标不同:沿用更早的那个到期时刻,使待触发时刻单调不增,保证最迟在首次排定的那次
      //   延迟内必定生效,与重算次数无关。
      val pending = queuePosition
      if (pending != null) {
        if (abs(pending.value - targetPosition) < 0.001f) return
        queuePosition = QueuedUpdate(targetPosition, min(pending.time, delay))
      } else {
        queuePosition = QueuedUpdate(targetPosition, delay)
      }
      settled = false
    } else {
      queuePosition = null
      this.targetPosition = targetPosition
      resetSolver()
    }
  }

  /**
   * 平移当前位置与目标位置,保持「位置与速度」连续,不打断正在进行的弹簧运动
   *
   * 用于外部增量平移,例如滚动跟手时把本帧的偏移增量瞬时加到所有行上。
   * 不能用 [setPosition] 代替:它会重建常数解并清空速度与排队目标,逐帧调用等于每帧从静止
   * 重新起跑,过渡时间被拉长数倍(SpringTest 有与不受扰对照的用例)。
   *
   * 实现要点,改动前先读:
   * - 飞行中(非静止解)必须重建曲线:平移后「当前值 → 目标」的距离与 v0 都变了,旧的闭式解不再适用;
   * - 重建必须同时把 [currentTime] 归零:解曲线按"相对构建起点的时间"求值
   *   (SpringSolution.position 内 elapsed = t),只重建不归零会让新曲线被当成已经走了
   *   currentTime 那么久,位置瞬间冲出(SpringTest 有相位连续用例钉住);
   * - 不改动 [settled]:已收敛行平移后 delta 与 v0 均为 0,重建出的曲线恒等于目标值,
   *   settled 保持 true 才能继续短路 [update],纯滚动帧的零延迟同目标早退也才成立;
   * - 静止解走原地平移快路径:纯滚动帧全表弹簧都处于静止态,逐行重建会在每帧产生与行数
   *   同量的 SpringSolution 分配(数百行歌词可达每秒上万次),原地平移与其数值等价。
   *
   * @param delta - 平移量(正负均可),0 时不产生任何副作用
   */
  fun translateBy(delta: Float) {
    if (delta == 0f) return
    currentPosition += delta
    targetPosition += delta
    if (solution.isConstant) {
      // 常数曲线平移后形状不变,直接同步 from/to 即为重建结果(delta 与 v0 均为 0)
      solution.translate(delta)
    } else {
      // buildSpringSolution 以「当前字段 + 旧解在 currentTime 的速度」为参数重建闭式解,
      // 因此必须先平移字段、再重建;重建后时钟归零,新曲线以此刻为原点
      solution = buildSpringSolution()
    }
    currentTime = 0f
    // 排队的延迟目标同样是绝对位置,必须一并平移,否则到点生效时会把行拉回 delta
    queuePosition = queuePosition?.let { QueuedUpdate(it.value + delta, it.time) }
  }

  /**
   * 更新弹簧参数,delay > 0 时延迟生效(单位:秒)
   *
   * 对齐 AMLL:立即生效时丢弃的是排队中的位置更新而非参数更新
   * (AMLL 此处即如此,延迟参数到点生效时会顺带取消排队位置,歌词场景不使用延迟调用,保持语义一致)
   */
  fun updateParams(
    params: SpringParams,
    delay: Float = 0f,
  ) {
    if (delay > 0f) {
      queueParams = QueuedUpdate(params, delay)
      settled = false
    } else {
      queuePosition = null
      this.params = this.params.merge(params)
      resetSolver()
    }
  }

  /** 推进一帧,返回当前位置(对齐 AMLL update) */
  fun update(delta: Float): Float {
    if (settled) return currentPosition
    currentTime += delta
    currentPosition = solution.position(currentTime)

    queueParams?.let { qp ->
      qp.time -= delta
      if (qp.time <= 0f) {
        updateParams(qp.value)
      }
    }
    queuePosition?.let { qp ->
      qp.time -= delta
      if (qp.time <= 0f) {
        setTargetPosition(qp.value)
      }
    }
    if (arrived()) {
      currentPosition = targetPosition
    }
    return currentPosition
  }

  fun getCurrentPosition(): Float = currentPosition

  private fun SpringParams.merge(other: SpringParams): SpringParams =
    SpringParams(
      mass = other.mass ?: this.mass,
      damping = other.damping ?: this.damping,
      stiffness = other.stiffness ?: this.stiffness,
      soft = other.soft ?: this.soft,
    )
}

/**
 * H-2: cubic-bezier 缓动查找表。
 * 对逐词动画用到的三组固定控制点预计算 256 点表,消除逐字符逐帧的牛顿迭代;
 * 查表 + 线性插值,与 [cubicBezier] 参考实现误差 < 0.005,肉眼不可辨。
 */
internal object AndroidLyricEasing {
  private const val TABLE_SIZE = 256

  /** empEasing 前半段 cubic-bezier(0.2, 0.4, 0.58, 1) */
  private val empInTable = buildTable(0.2f, 0.4f, 0.58f, 1f)

  /** empEasing 后半段 cubic-bezier(0.3, 0, 0.58, 1) */
  private val empOutTable = buildTable(0.3f, 0f, 0.58f, 1f)

  /** float 抬升 ease-out cubic-bezier(0, 0, 0.58, 1) */
  private val easeOut58Table = buildTable(0f, 0f, 0.58f, 1f)

  /**
   * CSS cubic-bezier 缓动求值(固定端点 P0=(0,0)、P3=(1,1)),给定时间分量 x,
   * 用牛顿迭代反解贝塞尔参数 t 再求 y,与浏览器 timing-function 行为一致;供查表构建与单测对照。
   *
   * @param x1 - 控制点 P1 的 x
   * @param y1 - 控制点 P1 的 y
   * @param x2 - 控制点 P2 的 x
   * @param y2 - 控制点 P2 的 y
   * @param x - 时间分量(0~1)
   * @returns 缓动后的进度 y(0~1)
   */
  fun cubicBezier(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    x: Float,
  ): Float {
    if (x <= 0f) return 0f
    if (x >= 1f) return 1f
    val cx = 3f * x1
    val bx = 3f * (x2 - x1) - cx
    val ax = 1f - cx - bx
    val cy = 3f * y1
    val by = 3f * (y2 - y1) - cy
    val ay = 1f - cy - by
    var t = x
    for (i in 0 until 8) {
      val xEst = ((ax * t + bx) * t + cx) * t - x
      val d = (3f * ax * t + 2f * bx) * t + cx
      if (abs(xEst) < 1e-4f || abs(d) < 1e-6f) break
      t -= xEst / d
    }
    return ((ay * t + by) * t + cy) * t
  }

  /** empEasing 前半段(x in [0, 0.5) 归一化后)查表 */
  fun empIn(x: Float): Float = sample(empInTable, x)

  /** empEasing 后半段(x in [0.5, 1] 归一化后)查表 */
  fun empOut(x: Float): Float = sample(empOutTable, x)

  /** float 抬升缓动查表 */
  fun easeOut58(x: Float): Float = sample(easeOut58Table, x)

  private fun buildTable(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
  ): FloatArray = FloatArray(TABLE_SIZE + 1) { i -> cubicBezier(x1, y1, x2, y2, i.toFloat() / TABLE_SIZE) }

  private fun sample(
    table: FloatArray,
    x: Float,
  ): Float {
    if (x <= 0f) return 0f
    if (x >= 1f) return 1f
    val scaled = x * TABLE_SIZE
    val index = scaled.toInt()
    val frac = scaled - index
    return table[index] + (table[index + 1] - table[index]) * frac
  }
}
