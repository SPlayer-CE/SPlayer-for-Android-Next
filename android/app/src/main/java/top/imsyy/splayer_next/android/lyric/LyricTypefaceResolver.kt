package top.imsyy.splayer_next.android.lyric

import android.content.Context
import android.graphics.Typeface
import android.util.LruCache
import java.io.File

/**
 * 歌词字体解析器：把设置里的 CSS 字体链解析成原生绘制可用的 Typeface。
 *
 * Web 端由 WebView 负责 font-family 回退，并能命中 FontFace 注册的导入字体；
 * 原生绘制没有这两条能力，不补齐就会静默落回系统默认字体（表现为字形与字重和 Web 端不一致）：
 * 1. 解析字体链（剥引号、按逗号切分）后按顺序回退：App 私有字体目录 → 系统字体族 → 默认字体
 * 2. 导入字体文件只能用 [Typeface.createFromFile] 加载，原生侧没有任何注册入口
 * 3. 对齐 Blink 的 font-synthesis: weight：请求字重 ≥ 600 且超过字体族能真实提供的档位时合成加粗
 *    （WebView 侧由 .lyrics-container 的 font-synthesis 承担，原生侧必须用
 *    [android.graphics.Paint.setFakeBoldText] 自行补齐，详见 [resolveSyntheticBold]）
 *
 * @param context - 用于定位 App 私有字体目录
 */
class LyricTypefaceResolver(
  context: Context,
) {
  /**
   * 字体解析结果
   *
   * @property typeface - 匹配到的字体，已按目标字重在族内取最近档位
   * @property syntheticBold - 是否需要 [android.graphics.Paint.setFakeBoldText] 合成加粗
   */
  class ResolvedTypeface(
    val typeface: Typeface,
    val syntheticBold: Boolean,
  )

  private val fontDir: File? = context.getExternalFilesDir(null)?.let { File(it, FONT_DIR_NAME) }

  // 导入字体加载结果：createFromFile 不参与系统 Typeface 缓存，逐行解析时必须复用，否则每行都读盘
  private val importedTypefaces = LruCache<String, Typeface>(IMPORTED_FONT_CACHE_SIZE)

  // 命中结果缓存：未命中的回退结果不入缓存，保证用户新导入字体后无需重启即可生效
  private val hitCache = LruCache<String, ResolvedTypeface>(RESOLUTION_CACHE_SIZE)

  // Typeface.create 对未知族名会静默返回默认字体；用一个必然不存在的名字取得该回退实例做识别
  private val missingProbe: Typeface by lazy { Typeface.create(MISSING_FONT_PROBE, Typeface.NORMAL) }

  /**
   * 解析字体链并按目标字重匹配字体
   *
   * @param familyChain - 设置里的 CSS 字体链，空值表示系统默认字体
   * @param weight - 目标字重（100-1000，超出范围时钳制）
   * @returns 匹配到的字体与合成加粗标记
   */
  fun resolve(
    familyChain: String?,
    weight: Int,
  ): ResolvedTypeface {
    // 使用 API 28+ 细粒度字重 API 呈现 100-1000 的中间档（500/600/800 等）；
    // Typeface.create 的 weight 硬限制为 1-1000，超过会抛 IllegalArgumentException，渲染前钳制
    val targetWeight = weight.coerceIn(100, 1000)
    val chain = familyChain?.trim().orEmpty()
    if (chain.isEmpty()) return fallback(targetWeight)
    val cacheKey = "$chain|$targetWeight"
    hitCache.get(cacheKey)?.let { return it }
    val resolved = resolveChain(chain, targetWeight)
    if (resolved == null) return fallback(targetWeight)
    hitCache.put(cacheKey, resolved)
    return resolved
  }

  /**
   * 按顺序尝试字体链上的每个候选名
   *
   * @param chain - CSS 字体链原文
   * @param weight - 已钳制的目标字重
   * @returns 命中结果，全部候选失败时返回 null
   */
  private fun resolveChain(
    chain: String,
    weight: Int,
  ): ResolvedTypeface? {
    for (name in parseFontChain(chain)) {
      loadImportedTypeface(name)?.let { imported ->
        // 导入字体：文件自身字重即族内唯一档位，create 无法凭空变粗，请求更粗时才合成
        return ResolvedTypeface(
          Typeface.create(imported, weight, false),
          resolveSyntheticBold(weight, imported.weight),
        )
      }
      systemTypeface(name)?.let { system ->
        // 系统字体族：create 只在族内取最近档位，请求超出 [SYSTEM_FAMILY_MAX_WEIGHT] 时补合成加粗，
        // 否则同一份设置下会比 WebView 细一截（WebView 有 font-synthesis: weight）
        return ResolvedTypeface(
          Typeface.create(system, weight, false),
          resolveSyntheticBold(weight, SYSTEM_FAMILY_MAX_WEIGHT),
        )
      }
    }
    return null
  }

  /**
   * 加载 App 私有字体目录里的导入字体
   *
   * @param name - 字体名（等于导入时的文件名去扩展名）
   * @returns 加载到的字体，文件不存在或损坏时返回 null
   */
  private fun loadImportedTypeface(name: String): Typeface? {
    val dir = fontDir ?: return null
    importedTypefaces.get(name)?.let { return it }
    for (extension in FONT_EXTENSIONS) {
      val file = File(dir, name + extension)
      if (!file.isFile) continue
      val typeface =
        try {
          Typeface.createFromFile(file)
        } catch (_: Exception) {
          // 字体文件损坏时继续尝试下一个候选，不阻断整条回退链
          null
        }
      if (typeface != null) {
        importedTypefaces.put(name, typeface)
        return typeface
      }
    }
    return null
  }

  /**
   * 按系统字体族名取字体
   *
   * @param name - 字体族名
   * @returns 系统字体，未命中时返回 null（由调用方继续回退）
   */
  private fun systemTypeface(name: String): Typeface? {
    val typeface =
      try {
        Typeface.create(name, Typeface.NORMAL)
      } catch (_: Exception) {
        null
      } ?: return null
    return typeface.takeIf { it != missingProbe }
  }

  /** 字体链为空或全部候选未命中时回退默认字体族；合成加粗判定与系统字体族一致 */
  private fun fallback(weight: Int): ResolvedTypeface =
    ResolvedTypeface(
      Typeface.create(Typeface.DEFAULT, weight, false),
      resolveSyntheticBold(weight, SYSTEM_FAMILY_MAX_WEIGHT),
    )

  /**
   * 解析 CSS 字体链：剥掉引号、按逗号切分，保留原始顺序
   *
   * @param chain - CSS font-family 字符串
   * @returns 候选字体名列表
   */
  private fun parseFontChain(chain: String): List<String> {
    val names = ArrayList<String>(2)
    val buffer = StringBuilder()
    var quote: Char? = null
    for (char in chain) {
      when {
        quote != null -> if (char == quote) quote = null else buffer.append(char)
        char == '"' || char == '\'' -> quote = char
        char == ',' -> {
          names.add(buffer.toString().trim())
          buffer.setLength(0)
        }
        else -> buffer.append(char)
      }
    }
    names.add(buffer.toString().trim())
    return names.filter { it.isNotEmpty() }
  }

  companion object {
    private const val FONT_DIR_NAME = "fonts"
    private const val IMPORTED_FONT_CACHE_SIZE = 16
    private const val RESOLUTION_CACHE_SIZE = 32

    // 该名字必然不存在于系统字体族表，仅用于取得「未命中回退实例」做等值识别
    private const val MISSING_FONT_PROBE = "\u0000__splayer_missing_font__"

    // 与 AndroidLocalLyricPlugin 导入时落盘的扩展名一致；SAF 可能返回大写扩展名，一并枚举
    private val FONT_EXTENSIONS = arrayOf(".ttf", ".otf", ".TTF", ".OTF")

    // Blink BoldThreshold：请求字重达到该值即期望粗体
    private const val SYNTHETIC_BOLD_MIN_WEIGHT = 600

    // 系统字体族按 Bold(700) 视为族内可提供的上限：600/700 的请求交给真 Bold 字形，
    // 只有超过 Bold 的请求（800 及以上）才由渲染层合成加粗。
    //
    // 「族内到底有哪几档」没有任何公开 API 可查（[Typeface.getWeight] 返回的是创建时写入的请求值
    // 而非命中档位），因此只能在两种失败模式里选一侧：
    // - 按 700 记（当前口径）：中日韩族有真 Bold 的机型完全正确；只有 Regular 的机型，600/700
    //   会取到 Regular 且不合成，表现为比 WebView 细一档（WebView 侧有 font-synthesis）。偏差单向、
    //   文字清晰可读，退化方向是“细”而不是“糊”。
    // - 按 400 记（曾用口径，恒合成）：只有 Regular 的机型上与 WebView 一致，但在有真 Bold 的机型上
    //   等于“真 Bold 再描一圈边”——中大字号下密笔画字（囊/曦）会粘连糊成一团，且正好命中 600/700
    //   这个最常用的设置区间，属不可接受的观感缺陷。
    // 取 700 即“宁可偏细，不可糊”。若真机核实某机型的中日韩回退确实只有 Regular、且偏细不可接受
    // （`adb shell cat /system/etc/fonts.xml` 查 zh-Hans 族有无 weight="700" 条目），把这个常量改回
    // 400 即可恢复恒合成：前端已按原样下发设置字重，口径只由这一个常量决定。
    private const val SYSTEM_FAMILY_MAX_WEIGHT = 700

    /**
     * 对齐 Blink 的 font-synthesis: weight —— 是否需要由渲染层合成加粗。
     *
     * Blink 的规则是「请求字重达到粗体阈值且命中的字形比请求更细时合成」；
     * Android 侧 [Typeface.getWeight] 返回的是创建时写入的请求值而非命中档位，
     * 没有任何公开 API 能反查字体族可用字重，因此可用字重由调用方给出：
     * 导入字体用文件自身字重（精确），系统字体族与默认字体用 [SYSTEM_FAMILY_MAX_WEIGHT]。
     *
     * @param weight - 请求字重（App 直接下发设置字重，与桌面 WebView 一致，不再做放大）
     * @param availableWeight - 该字体族能真实提供的字重
     * @returns true 表示需要合成加粗
     */
    internal fun resolveSyntheticBold(
      weight: Int,
      availableWeight: Int,
    ): Boolean = weight >= SYNTHETIC_BOLD_MIN_WEIGHT && availableWeight < weight
  }
}
