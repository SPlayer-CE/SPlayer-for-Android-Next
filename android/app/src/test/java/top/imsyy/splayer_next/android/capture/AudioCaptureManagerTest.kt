package top.imsyy.splayer_next.android.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AudioCaptureManager 降采样纯逻辑单元测试（O4）：覆盖空输入/不降采样直通、
 * 整数比例窗口平均、交替信号低通抵消、以及非整数比例的长度与均值。
 */
class AudioCaptureManagerTest {
  private val manager = AudioCaptureManager()

  @Test
  fun downsampleReturnsInputWhenEmptyOrNotUpsampling() {
    assertEquals(0, manager.downsample(FloatArray(0), 48000, 8000).size)
    val same = floatArrayOf(1f, 2f, 3f)
    assertArrayEquals(same, manager.downsample(same, 8000, 8000), 0f)
  }

  @Test
  fun downsampleAveragesWholeWindowsAtIntegerRatio() {
    val input = FloatArray(12) { if (it < 6) 1f else 2f }
    val out = manager.downsample(input, 48000, 8000)
    assertEquals(2, out.size)
    assertEquals(1f, out[0], 0.0001f)
    assertEquals(2f, out[1], 0.0001f)
  }

  @Test
  fun downsampleActsAsLowPassOnAlternatingSignal() {
    val input = FloatArray(12) { if (it % 2 == 0) 1f else -1f }
    val out = manager.downsample(input, 48000, 8000)
    assertEquals(2, out.size)
    assertEquals(0f, out[0], 0.0001f)
    assertEquals(0f, out[1], 0.0001f)
  }

  @Test
  fun downsampleHandlesNonIntegerRatio() {
    val input = FloatArray(11) { 3f }
    val out = manager.downsample(input, 44100, 8000)
    assertEquals(1, out.size)
    assertEquals(3f, out[0], 0.0001f)
  }
}
