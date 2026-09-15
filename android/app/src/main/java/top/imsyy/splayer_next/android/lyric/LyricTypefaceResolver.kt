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
 * 3. 对齐 Blink 的 font-synthesis：单字重字体在请求字重 ≥ 600 时标记为需要合成加粗
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
        // 单字重字体无法通过 create 变粗（族内无更粗档位），对齐 Blink 的合成条件
        val syntheticBold = weight >= SYNTHETIC_BOLD_MIN_WEIGHT && imported.weight < BOLD_FACE_WEIGHT
        return ResolvedTypeface(Typeface.create(imported, weight, false), syntheticBold)
      }
      systemTypeface(name)?.let { system ->
        // 系统字体族由 create 完成族内字重匹配，不再叠加合成加粗
        return ResolvedTypeface(Typeface.create(system, weight, false), false)
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

  private fun fallback(weight: Int): ResolvedTypeface = ResolvedTypeface(Typeface.create(Typeface.DEFAULT, weight, false), false)

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

    // SkTypeface.isBold() 的判定线：字体文件自身达到该字重时视为已有粗体
    private const val BOLD_FACE_WEIGHT = 700
  }
}
