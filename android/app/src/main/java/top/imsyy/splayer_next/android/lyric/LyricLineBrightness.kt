package top.imsyy.splayer_next.android.lyric

/**
 * 歌词行亮度层状态机（对齐 AMLL dom/line-brightness.ts 的生命周期）。
 *
 * 亮层保留逐词遮罩，暗层为无遮罩的均匀静息态，两者以加法方式叠加。暗层系数取用户的
 * 未激活透明度 [inactiveAlpha]（对齐旧实现「暗部固定使用 inactiveAlpha」），设 p 为亮层进度、
 * dim = inactiveAlpha × (1 − p)，则单像素的合成结果为 `lineAlpha × (mask(t) × p + dim)`，
 * 其中 t 为词内渐变带位置、mask(t) 由 1 过渡到 [inactiveAlpha]。该式是两端点的线性插值：
 *
 *     played   = lineAlpha × (p + dim)      随 p 由静息档升到 lineAlpha
 *     unplayed = lineAlpha × inactiveAlpha  与 p 无关：暗部恒为未激活档
 *
 * 因此单趟绘制即可表达双图层：行包络取 played 端、遮罩 unplayed 端取恒定值。p = 0 时两端
 * 相等（整行均匀静息），p = 1 时与旧实现的色标完全一致，p 只决定交叉淡入的过程。
 *
 * 生命周期语义（对齐 AMLL）：
 * - 进入高亮（GRADIENT）用 300ms 交叉淡入，退出用 200ms 交叉淡出（短于 AMLL 的 0.45s，见 [EXIT_MS]）；
 * - 移出活跃窗口且退出高亮满 500ms 后才硬回收（p 归零）；保留期只推迟回收，
 *   不冻结淡出，让退出淡出完整播放（对齐 BRIGHTNESS_SETTLE_MS 仅约束副本回收）；
 * - 冷启动（窗口外 p 已归零后重新高亮）由进入斜坡补 300ms 淡入。
 *
 * 内存纪律：按行索引的 FloatArray/LongArray/BooleanArray 实例级缓冲，随行数扩容、切歌时重置，
 * 绘制路径零分配；nowMs 由调用方传入，便于单测注入时钟。
 */
internal class LyricLineBrightness {
  /**
   * 未激活透明度系数（对应设置项 inactiveAlpha）。
   *
   * 同时充当暗层静息态与逐词遮罩的 unplayed 端，因此「未激活行的颜色」与
   * 「激活行里尚未唱到的词的暗部」始终是同一档。
   */
  var inactiveAlpha = 0.2f

  private var progress = FloatArray(0)
  private var exitedAt = LongArray(0)
  private var wasActive = BooleanArray(0)

  /** 推进一行的亮层进度，返回当前帧亮层进度 p ∈ [0,1] */
  fun update(
    index: Int,
    inWindow: Boolean,
    highlighted: Boolean,
    deltaMs: Float,
    nowMs: Long,
  ): Float {
    ensureCapacity(index + 1)
    var p = progress[index]
    if (highlighted) {
      exitedAt[index] = NO_EXIT
      p = moveToward(p, 1f, deltaMs, ENTER_MS)
    } else {
      if (wasActive[index]) exitedAt[index] = nowMs
      val exited = exitedAt[index]
      // 保留期只推迟窗口外的硬回收（对齐 BRIGHTNESS_SETTLE_MS 仅约束副本回收），
      // 淡出本身从退出高亮当帧就开始，与 AMLL 的 CSS 交叉淡入同步
      val withinRetain = exited != NO_EXIT && nowMs - exited < RETAIN_MS
      if (!inWindow && !withinRetain) {
        // 窗口外且淡出已播完，可安全回收，对应 AMLL 副本回收后的无亮度层单层形态
        p = 0f
      } else {
        p = moveToward(p, 0f, deltaMs, EXIT_MS)
      }
    }
    wasActive[index] = highlighted
    progress[index] = p
    return p
  }

  /** 由亮层进度派生暗层不透明度 dim = inactiveAlpha × (1 − p) */
  fun dimOf(p: Float): Float = inactiveAlpha * (1f - p)

  /**
   * 行包络（played 端绝对 alpha）：lineAlpha × (p + dim)
   *
   * 无遮罩的绘制（静态行、非单词行）直接以该值作为整行透明度，逐词遮罩的 played 端同样取该值。
   *
   * @param lineAlpha - 行级透明度（激活行 1、呈现中 0.85 等）
   * @param p - 亮层进度
   */
  fun playedAlpha(
    lineAlpha: Float,
    p: Float,
  ): Float = lineAlpha * (p + dimOf(p))

  /**
   * 遮罩 unplayed 端绝对 alpha：lineAlpha × inactiveAlpha
   *
   * 恒为未激活档、与亮层进度无关：p = 0 时与 [playedAlpha] 相等（整行均匀静息），
   * p = 1 时即旧实现的暗部色标。
   *
   * @param lineAlpha - 行级透明度
   */
  fun unplayedAlpha(lineAlpha: Float): Float = lineAlpha * inactiveAlpha

  /** 瞬移到目标态（seek/可见性恢复用，对齐 snapVisualState） */
  fun snap(
    index: Int,
    highlighted: Boolean,
  ) {
    ensureCapacity(index + 1)
    if (highlighted) {
      exitedAt[index] = NO_EXIT
      progress[index] = 1f
    } else {
      // 冷同步不开启保留期：保留期只属于实时观测到的高亮退出（update 路径记录退出时刻）
      exitedAt[index] = NO_EXIT
      progress[index] = 0f
    }
    wasActive[index] = highlighted
  }

  fun reset(lineCount: Int) {
    progress = FloatArray(lineCount)
    exitedAt = LongArray(lineCount) { NO_EXIT }
    wasActive = BooleanArray(lineCount)
  }

  private fun ensureCapacity(count: Int) {
    if (progress.size >= count) return
    val oldSize = progress.size
    progress = progress.copyOf(count)
    exitedAt = exitedAt.copyOf(count)
    wasActive = wasActive.copyOf(count)
    // 新扩容槽位必须填哨兵值（copyOf 对 LongArray 默认填 0，需纠正）
    exitedAt.fill(NO_EXIT, oldSize, count)
  }

  private fun moveToward(
    current: Float,
    target: Float,
    deltaMs: Float,
    durationMs: Float,
  ): Float {
    val step = deltaMs / durationMs
    return when {
      target > current -> (current + step).coerceAtMost(target)
      target < current -> (current - step).coerceAtLeast(target)
      else -> target
    }
  }

  companion object {
    /** 活跃窗口向前后扩展的行数（对齐 AMLL BRIGHTNESS_WINDOW_LINES） */
    const val WINDOW_LINES = 2

    /** 进入高亮的交叉淡入时长（对齐 gradientMask 下 transition-duration 0.3s） */
    const val ENTER_MS = 300f

    /**
     * 退出高亮的交叉淡出时长。
     *
     * 刻意短于 AMLL 的 0.45s：亮层进度决定的是「这一行还像不像激活行」，折合后的色标跨度是
     * lineAlpha → lineAlpha × inactiveAlpha（默认 1 → 0.2，5 倍差），线性 450ms 会让离场行在切换后
     * 仍有近半秒保持明显更亮，成为持续牵引视线的运动源（关掉模糊后尤其明显）。
     * 200ms 落回静息档，保留线性推进但读作「立刻松开」。
     */
    const val EXIT_MS = 200f

    /** 退出高亮后的副本保留时长（对齐 BRIGHTNESS_SETTLE_MS） */
    const val RETAIN_MS = 500L

    private const val NO_EXIT = Long.MIN_VALUE

    /**
     * 计算亮度活跃窗口（对齐 AMLL base/index.ts：焦点行与全部高亮行前后各扩 2 行）。
     *
     * @param anchorIndex 焦点行（视图索引）
     * @param highlightedIndices 全部高亮行（视图索引，含 BG 伙伴）
     */
    fun windowRange(
      anchorIndex: Int,
      highlightedIndices: Collection<Int>,
    ): IntRange {
      var earliest = anchorIndex
      var latest = anchorIndex
      for (i in highlightedIndices) {
        if (i < earliest) earliest = i
        if (i > latest) latest = i
      }
      return (earliest - WINDOW_LINES)..(latest + WINDOW_LINES)
    }
  }
}
