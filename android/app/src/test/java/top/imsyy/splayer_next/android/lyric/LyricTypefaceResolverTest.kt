package top.imsyy.splayer_next.android.lyric

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合成加粗判定的纯函数口径。
 *
 * 可用字重由调用方给出（导入字体 = 文件自身字重，系统字体族 = Bold(700)），本文件的断言只针对
 * 传入值本身，与 [LyricTypefaceResolver] 里 SYSTEM_FAMILY_MAX_WEIGHT 的实际取值无关：
 * 该常量改动只需改产品口径，不需要同步这里。
 */
class LyricTypefaceResolverTest {
  @Test
  fun systemFamilySynthesizesBoldWhenRequestExceedsHeaviestFace() {
    // 请求超过系统族上限 Bold(700) 时才补合成——非可变字体族给不出比 Bold 更重的档位
    assertTrue(LyricTypefaceResolver.resolveSyntheticBold(1000, 700))
    assertTrue(LyricTypefaceResolver.resolveSyntheticBold(800, 700))
  }

  @Test
  fun systemFamilyKeepsRealFaceWithoutSynthesis() {
    // 700 即当前口径下系统族的可用上限（SYSTEM_FAMILY_MAX_WEIGHT）：600/700 都交给真 Bold，
    // 再叠合成会把已加粗的笔画描厚一档（大字号 CJK 密笔画字粘连）
    assertFalse(LyricTypefaceResolver.resolveSyntheticBold(700, 700))
    assertFalse(LyricTypefaceResolver.resolveSyntheticBold(600, 700))
    assertFalse(LyricTypefaceResolver.resolveSyntheticBold(400, 700))
  }

  @Test
  fun belowBoldThresholdNeverSynthesizes() {
    // Blink BoldThreshold：请求低于 600 时不合成，即使族内只有更细的字形
    assertFalse(LyricTypefaceResolver.resolveSyntheticBold(500, 400))
    assertFalse(LyricTypefaceResolver.resolveSyntheticBold(400, 400))
  }

  @Test
  fun importedFontSynthesizesFromItsOwnWeight() {
    // 导入字体：文件字重即族内唯一档位，请求更粗时才合成
    assertTrue(LyricTypefaceResolver.resolveSyntheticBold(1000, 400))
    assertTrue(LyricTypefaceResolver.resolveSyntheticBold(600, 400))
    assertFalse(LyricTypefaceResolver.resolveSyntheticBold(600, 700))
  }
}
