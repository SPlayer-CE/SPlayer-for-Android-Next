package top.imsyy.splayer_next.android.lyric

import android.os.Build
import android.util.Log
import android.view.Display

/**
 * 激活行 HDR 显示的设备能力判定结果。
 *
 * 只有 [Supported] 才允许进入 HDR 路径；版本不足、面板不支持、查询异常一律降级为不支持，
 * 保证异常情况下与开启前的 SDR 输出逐帧一致。
 */
enum class HdrCapability {
  /** 面板声明支持至少一种 HDR 类型 */
  Supported,

  /** 系统版本低于策略门槛 */
  UnsupportedApi,

  /** 面板未声明任何 HDR 类型 */
  UnsupportedDisplay,

  /** 能力查询抛异常，无法判定 */
  Unknown,
  ;

  val isSupported: Boolean get() = this == Supported
}

/**
 * HDR 面板能力判定。
 *
 * 判定逻辑抽成不依赖框架的纯函数，Display 能力经 lambda 注入以便单测；
 * 实际查询按 API 分级：34 起走 [Display.getMode]，33 走 [Display.getHdrCapabilities]。
 */
object LyricHdrSupport {
  /**
   * 策略门槛：API 33 起才稳定提供可用的 HDR 面板能力查询。
   * 29~32 的机型机制本身支持（[android.graphics.Paint.setColor] 的 ColorLong 重载与
   * ColorLong 版 LinearGradient 均为 29+），此处按已确认口径不放开，日后放开只需改这一个常量。
   */
  const val MIN_SDK_FOR_HDR = Build.VERSION_CODES.TIRAMISU

  private const val TAG = "LyricHdrSupport"

  /**
   * 判定面板是否支持 HDR。
   *
   * @param sdkInt 系统版本号
   * @param supportedHdrTypes 面板支持的 HDR 类型列表查询，异常由本函数统一兜底
   */
  fun resolve(
    sdkInt: Int,
    supportedHdrTypes: () -> IntArray,
  ): HdrCapability {
    if (sdkInt < MIN_SDK_FOR_HDR) return HdrCapability.UnsupportedApi
    return try {
      if (supportedHdrTypes().isNotEmpty()) HdrCapability.Supported else HdrCapability.UnsupportedDisplay
    } catch (error: Exception) {
      Log.w(TAG, "HDR 面板能力查询失败，按不支持处理", error)
      HdrCapability.Unknown
    }
  }

  /**
   * 读取面板当前的 HDR 相对 SDR 亮度余量（线性亮度比值）。
   *
   * 这是 Android 上唯一能在运行时量到「这台设备最亮能到多少」旋钮，API 34 起才提供；
   * 问不到时返回 null 而不是 1f，让调用方能区分「设备只到 SDR」与「问不出来」两种情况。
   */
  fun readHdrSdrRatio(display: Display?): Float? {
    if (display == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
    return try {
      display.hdrSdrRatio.takeIf { it.isFinite() && it > 0f }
    } catch (error: Exception) {
      Log.w(TAG, "HDR/SDR 余量查询失败", error)
      null
    }
  }

  /** 面板当前是否真的在输出 HDR，仅作诊断打点，不作为启用门槛 */
  fun isHdrOutput(display: Display?): Boolean =
    display?.let { target ->
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) target.isHdr else false
      } catch (error: Exception) {
        Log.w(TAG, "isHdr 查询失败", error)
        false
      }
    } ?: false

  /**
   * 读取面板支持的 HDR 类型列表，按 API 分级：
   * 34+ 走 [Display.getMode] 的 Mode#getSupportedHdrTypes，33 走 [Display.getHdrCapabilities]。
   */
  @Suppress("DEPRECATION")
  fun readSupportedHdrTypes(display: Display?): IntArray =
    display?.let { target ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        target.mode?.supportedHdrTypes
      } else {
        target.hdrCapabilities?.supportedHdrTypes
      }
    } ?: IntArray(0)

  /** 能力结论对应的稳定原因标识，供设置项展示 */
  fun reasonOf(capability: HdrCapability): String =
    when (capability) {
      HdrCapability.Supported -> "supported"
      HdrCapability.UnsupportedApi -> "unsupportedApi"
      HdrCapability.UnsupportedDisplay -> "unsupportedDisplay"
      HdrCapability.Unknown -> "unknown"
    }
}
