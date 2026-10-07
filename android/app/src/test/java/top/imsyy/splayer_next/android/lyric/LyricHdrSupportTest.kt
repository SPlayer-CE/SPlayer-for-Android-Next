package top.imsyy.splayer_next.android.lyric

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HDR 能力判定的分支覆盖。
 *
 * 只测纯逻辑：Display 查询以 lambda 注入，因此不需要真机环境。
 * hdrColor / 渐变构造依赖 Android framework，在纯 JVM 单测里是 stub，不在此断言。
 */
class LyricHdrSupportTest {
  @Test
  fun belowStrategyGateIsRejectedByApiLevel() {
    // 29~32 的机制其实可用（Paint.setColor(long) 与 ColorLong 渐变都是 29+），
    // 但当前口径是 Android 13 起才放开
    assertEquals(
      HdrCapability.UnsupportedApi,
      LyricHdrSupport.resolve(Build.VERSION_CODES.P) { intArrayOf(1) },
    )
  }

  @Test
  fun nonEmptyHdrTypesMeansSupported() {
    assertEquals(
      HdrCapability.Supported,
      LyricHdrSupport.resolve(Build.VERSION_CODES.TIRAMISU) { intArrayOf(1) },
    )
  }

  @Test
  fun emptyHdrTypesMeansDisplayUnsupported() {
    assertEquals(
      HdrCapability.UnsupportedDisplay,
      LyricHdrSupport.resolve(Build.VERSION_CODES.TIRAMISU) { IntArray(0) },
    )
  }

  @Test
  fun displayQueryFailureDegradesToUnknown() {
    // 异常必须降级而不是抛出：能力未知时前端置灰开关，不应让整页设置崩掉
    assertEquals(
      HdrCapability.Unknown,
      LyricHdrSupport.resolve(Build.VERSION_CODES.TIRAMISU) { throw IllegalStateException("boom") },
    )
  }

  @Test
  fun reasonIdentifiersAreStable() {
    // 这些字符串会经 Capacitor 传到前端做文案分支，不能随手改
    assertEquals("supported", LyricHdrSupport.reasonOf(HdrCapability.Supported))
    assertEquals("unsupportedApi", LyricHdrSupport.reasonOf(HdrCapability.UnsupportedApi))
    assertEquals("unsupportedDisplay", LyricHdrSupport.reasonOf(HdrCapability.UnsupportedDisplay))
    assertEquals("unknown", LyricHdrSupport.reasonOf(HdrCapability.Unknown))
  }
}
