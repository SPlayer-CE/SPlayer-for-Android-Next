package top.imsyy.splayer_next.android.lyric

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.Log
import android.view.Display
import android.view.Window
import kotlin.math.pow

/**
 * 激活行 HDR 显示的三态聚合与颜色工具。
 *
 * 仅在「用户开关开 + 面板支持 + 覆盖层前台」三者同时成立时才申请窗口 HDR 输出，
 * 其余情况一律回退 [ActivityInfo.COLOR_MODE_DEFAULT]；绘制侧也由 [isHdrActive] 单点收口，
 * 关闭时颜色仍走原有 Int 路径，绘制调用序列与开启前逐帧一致。
 */
internal class LyricHdrController {
  /** 用户开关 */
  var enabled: Boolean = false

  /** 覆盖层是否已挂到窗口且可见 */
  var foreground: Boolean = false

  /** 面板能力缓存；Display 变化时由调用方重新 [updateCapability] */
  var capability: HdrCapability = HdrCapability.Unknown
    private set

  /** HDR 是否真正生效，是绘制路径唯一的收口开关 */
  val isHdrActive: Boolean
    get() = enabled && capability.isSupported && foreground

  /** 已应用到窗口的色域模式，用于保证切换幂等、不进绘制循环 */
  private var appliedColorMode: Int? = null

  /**
   * 逐词扫光高光端的编码域增益，由 [refreshCapability] 按面板余量定标。
   *
   * 只应在状态变化点读取，绘制循环里直接取用。
   */
  var highlightGain: Float = FALLBACK_HIGHLIGHT_GAIN
    private set

  fun updateCapability(capability: HdrCapability) {
    this.capability = capability
  }

  /** 重新探测面板能力，Display 对象变化时调用 */
  fun refreshCapability(
    sdkInt: Int,
    display: Display?,
  ) {
    val resolved =
      LyricHdrSupport.resolve(sdkInt) { LyricHdrSupport.readSupportedHdrTypes(display) }
    val headroom = LyricHdrSupport.readHdrSdrRatio(display)
    val gain = resolveHighlightGain(headroom)
    if (resolved != this.capability || gain != this.highlightGain) {
      Log.i(
        TAG,
        "HDR 能力=${LyricHdrSupport.reasonOf(resolved)} 余量=$headroom 增益=$gain " +
          "isHdrOutput=${LyricHdrSupport.isHdrOutput(display)} sdk=$sdkInt",
      )
    }
    updateCapability(resolved)
    highlightGain = gain
  }

  /**
   * 由面板的 HDR/SDR 亮度余量定标出编码域增益。
   *
   * 传给 [android.graphics.Color.pack] 的是 sRGB **编码域**分量，而 [Display.getHdrSdrRatio]
   * 报的是**线性亮度**比值，两者差一个 sRGB 传输函数的 2.4 次幂，故需先开方再反解：
   * `编码值 = 1.055 × 余量^(1/2.4) − 0.055`。
   *
   * 余量问不到（[headroomRatio] 为 null、非有限或不大于 1）时回落到固定增益，
   * 而不是当成「设备只能到 SDR」把高光压回 1.0。
   */
  fun resolveHighlightGain(headroomRatio: Float?): Float {
    if (headroomRatio == null || !headroomRatio.isFinite() || headroomRatio <= 1f) {
      return FALLBACK_HIGHLIGHT_GAIN
    }
    val encoded =
      SDR_TRANSFER_SLOPE * headroomRatio.toDouble().pow(1.0 / SDR_GAMMA) - SDR_TRANSFER_OFFSET
    return encoded.toFloat().coerceIn(1f, MAX_HIGHLIGHT_GAIN)
  }

  /**
   * 按当前三态幂等申请或回退窗口色域模式。
   *
   * 只应在状态变化点（开关切换、前后台切换、可见性变化、能力变化）调用，绝不进入绘制循环；
   * 异常时静默并把标记置空，让下一次状态变化重试，而不是误标成「已回退」。
   */
  fun syncWindowColorMode(window: Window?) {
    val target = if (isHdrActive) HDR_COLOR_MODE else ActivityInfo.COLOR_MODE_DEFAULT
    if (appliedColorMode == target) return
    try {
      window?.setColorMode(target)
      appliedColorMode = if (window == null) null else target
    } catch (error: Exception) {
      Log.w(TAG, "窗口色域模式切换失败，下次状态变化重试", error)
      // 置空而非记成 DEFAULT：请求抛异常说明大概率没切过去，标成「已回退」会让幂等早退
      // 永远不再纠正窗口色域
      appliedColorMode = null
    }
  }

  /**
   * 组装 extended-sRGB 颜色，分量可超过 1.0 以承载 HDR 高光。
   *
   * gain 为 1 时与 [android.graphics.Paint] 的 Int 颜色路径等价（alpha 原样透传）。
   */
  fun hdrColor(
    baseArgb: Int,
    gain: Float,
  ): Long {
    val safeGain = gain.coerceIn(1f, MAX_HIGHLIGHT_GAIN)
    return Color.pack(
      Color.red(baseArgb) / 255f * safeGain,
      Color.green(baseArgb) / 255f * safeGain,
      Color.blue(baseArgb) / 255f * safeGain,
      Color.alpha(baseArgb) / 255f,
      EXTENDED_SRGB,
    )
  }

  /** 构造携带 >1.0 分量的 played→unplayed 扫光渐变 */
  fun hdrWordGradient(
    fadeWidth: Float,
    playedColor: Long,
    unplayedColor: Long,
  ): LinearGradient = LinearGradient(0f, 0f, fadeWidth, 0f, playedColor, unplayedColor, Shader.TileMode.CLAMP)

  /** 构造携带 >1.0 分量的单色渐变 */
  fun hdrSolidShader(color: Long): LinearGradient = LinearGradient(0f, 0f, 1f, 0f, color, color, Shader.TileMode.CLAMP)

  /**
   * 把颜色按 alpha 量化到固定档位，用作单色渐变缓存的键。
   *
   * 扫光的 played/unplayed 色标只随行亮度逐帧变化 alpha、RGB 恒定：直接用整色做键，
   * 交叉淡入淡出期间每帧都是新键，缓存恒定 miss 并逐词新建渐变；量化后档位有限，
   * 缓存能跨帧命中，且同一档位内 alpha 差不超过一个档（1/[ALPHA_STEPS]），肉眼不可见。
   * RGB 原样保留在键里，换字色会自然换键，不会串色。
   */
  fun quantizeAlpha(color: Int): Int {
    // alpha 走位运算而不调 android.graphics.Color：纯 JVM 单测里 framework 方法体是 stub，
    // 走 Color.alpha 会让这条量化逻辑在单测里恒为 0、无法验证
    val alpha = (color ushr ALPHA_SHIFT) and ALPHA_MAX
    val bucket = (alpha * ALPHA_STEPS + ALPHA_MAX / 2) / ALPHA_MAX
    return (color and RGB_MASK) or ((bucket * ALPHA_MAX / ALPHA_STEPS) shl ALPHA_SHIFT)
  }

  companion object {
    private const val TAG = "LyricHdrController"

    /** 单色渐变缓存键的 alpha 量化档数：覆盖 0~255，且能让两份 32 条缓存刚好装下全部档位 */
    private const val ALPHA_STEPS = 32

    /** 8 位色标的 alpha 上界（量化端点与四舍五入共用） */
    private const val ALPHA_MAX = 255

    /** ARGB 里 alpha 字节的移位位数 */
    private const val ALPHA_SHIFT = 24

    /** 只保留 RGB 三字节、置零 alpha 的掩码 */
    private const val RGB_MASK = 0x00FFFFFF

    /**
     * 面板余量问不到时（API 33 及以下，或查询失败）使用的固定编码域增益，
     * 约合 5.3 倍线性亮度。
     */
    private const val FALLBACK_HIGHLIGHT_GAIN = 2.0f

    /**
     * 编码域增益上限，约合 8.4 倍线性亮度，是留给真机调的旋钮。
     *
     * 之所以不直接吃满 [Display.getHdrSdrRatio]：余量极限是面板为镜面高光预留的，
     * 白字顶到极限会连笔画轮廓一起糊掉。多数机型的余量落在 6~10，换算后本来就撞上这个上限。
     */
    private const val MAX_HIGHLIGHT_GAIN = 2.5f

    /** sRGB 传输函数参数，用于在「线性亮度比」与「编码域分量」之间换算 */
    private const val SDR_TRANSFER_SLOPE = 1.055

    private const val SDR_TRANSFER_OFFSET = 0.055

    private const val SDR_GAMMA = 2.4

    /**
     * 申请窗口 HDR 输出用的色域模式。
     *
     * [ActivityInfo] 的 COLOR_MODE_* 是 `@IntDef` 互斥枚举（DEFAULT=0 / WIDE_COLOR_GAMUT=1 / HDR=2），
     * 不是可组合的位标志；写成 `HDR or WIDE_COLOR_GAMUT` 会得到 3，而 3 是 `@hide` 的
     * COLOR_MODE_HDR10（标注「仅内部测试用」）并不在合法取值集合内，窗口侧会静默拒绝导致 HDR 不生效。
     * HDR 本身已隐含宽色域，单独取 [ActivityInfo.COLOR_MODE_HDR] 即可。
     */
    internal val HDR_COLOR_MODE: Int = ActivityInfo.COLOR_MODE_HDR

    // 惰性解析：避免类初始化阶段就触碰 framework，纯 JVM 单测只测三态聚合时不会被 stub 打断
    private val EXTENDED_SRGB: ColorSpace by lazy { ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB) }
  }
}
