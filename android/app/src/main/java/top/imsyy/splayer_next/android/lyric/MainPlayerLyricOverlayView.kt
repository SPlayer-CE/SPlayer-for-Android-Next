package top.imsyy.splayer_next.android.lyric

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONArray

/**
 * Android 主播放器页内原生逐词歌词层。
 *
 * 以 AMLL 歌词引擎为对齐基准，尽量在 Kotlin 侧复用相同的视觉参数和行为。
 */
class MainPlayerLyricOverlayView
  @JvmOverloads
  constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val playbackPositionProvider: (() -> Long)? = null,
    private val playbackRateProvider: (() -> Float)? = null,
    private val onSeekRequested: (Long) -> Unit = {},
  ) : View(context, attrs) {
    private data class LineLayout(
      val top: Float,
      val height: Float,
      val centerY: Float,
      val subLines: Int,
      val flowBottom: Float,
    )

    private data class TextMetrics(
      val width: Float,
      val textSize: Float,
      val height: Float,
      val positionedWords: List<PositionedWord> = emptyList(),
      val staticLines: List<String> = emptyList(),
      val staticLineWidths: FloatArray? = null,
    )

    private data class PositionedWord(
      val word: NativeLyricWord,
      val width: Float,
      val textWidth: Float,
      val x: Float,
      val baselineOffset: Float,
      val chunkId: Int,
      val chunkShouldEmphasize: Boolean,
    )

    private data class ContentBlockMetrics(
      val startX: Float,
      val contentWidth: Float,
      val maxWidth: Float,
      val main: TextMetrics,
      val subs: List<TextMetrics>,
    ) {
      val endX: Float
        get() = startX + contentWidth
    }

    private data class LineInsets(
      val left: Float,
      val right: Float,
      val maxWidth: Float,
    )

    private data class InterludeInfo(
      val startTime: Long,
      val endTime: Long,
      val prevLineIndex: Int,
      val nextLineIsDuet: Boolean,
    )

    private data class InterludeState(
      val isActive: Boolean = false,
      val startTime: Long = 0L,
      val endTime: Long = 0L,
      val x: Float = 0f,
      val y: Float = 0f,
      val alignRight: Boolean = false,
      val anchorIndex: Int = -1,
      val anchorOffset: Float = 0f,
      val fontSizePx: Float = 16f,
    )

    private data class LineSpringState(
      val position: Spring,
      val scale: Spring,
      /** BG 滑动弹簧（对齐 AMLL bgSlideY）：仅 BG 行持有，值为隐藏偏移百分比，非 BG 行为 null */
      val bgSlide: Spring? = null,
    )

    private val mainPaint =
      Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
    private val subPaint =
      Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)

    // 注音（ruby）绘制 paint：0.5em 字号，复用词的扫光 shader 随遮罩一起着色（对齐 AMLL rubyWord）
    private val rubyPaint =
      Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)

    // char-emphasis / float 动画专用：去掉 SUBPIXEL/LINEAR flag。
    // 每帧变化的 scale/translate 下子像素光栅化会让 CJK 字形 AA 帧间跳变（抖动）；
    // 对齐 AMLL matrix3d 合成层语义——不随变换重光栅化，仅做整体 GPU 变换
    private val emphasisPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotsPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    private var lyricLines: List<NativeLyricLine> = emptyList()
    private var lineLayouts: List<LineLayout> = emptyList()
    private var lineHitTops = FloatArray(0)
    private var lineHitBottoms = FloatArray(0)
    private var contentHeight = 0f
    private var lineBrightAlphas = FloatArray(0)
    private var linePassAlphas = FloatArray(0)
    private var lineBlurValues = FloatArray(0)

    // 模糊值是否已稳定（abs(target - current) < 阈值），用于帧调度：渐变期需要连续帧驱动
    private var lineBlurSettled = BooleanArray(0)

    // lineFloatFadeProgress：逐词浮动退场进度，1 = 保持当前浮动量，0 = 完全落回
    private var lineFloatFadeProgress = FloatArray(0)
    private var interludeState = InterludeState()

    private var hasDuetLinesCached = false
    private var mainTypeface: Typeface = Typeface.DEFAULT
    private var subTypeface: Typeface = Typeface.DEFAULT

    // 每行主文本字体：按行 language（ja/ko/zh-CN/und-Latn）匹配分语种字体，对齐 Web 端 :lang 选择
    private var lineMainTypefaces: Array<Typeface> = emptyArray()
    private var lineSubLinesCache: Array<List<String>> = emptyArray()
    private var lineInsetsCache: Array<LineInsets> = emptyArray()
    private var lineMetricsCache: Array<ContentBlockMetrics> = emptyArray()
    private var lineHeightsCache: FloatArray = FloatArray(0)
    private var lineHasRomanCache: BooleanArray = BooleanArray(0)
    private var lineWordEffectEndCache: LongArray = LongArray(0)
    private var lineBgAboveCache: BooleanArray = BooleanArray(0)
    private var layoutCacheDirty = true

    // 非激活行位图缓存：把整行内容（含模糊）栅格化到 Bitmap，每帧只做 drawBitmap + translate/scale，
    // 让弹簧亚像素位移由 Skia 双线性采样平滑处理，避免 Canvas 重新栅格化文字带来的 AA 帧间漂移
    private data class LineBitmap(
      val bitmap: Bitmap,
      val pad: Int,
      val blurKey: Int,
    )

    // ALPHA_8 字形位图：只存 alpha 掩码。drawBitmap + paint.shader 时 Skia 对 alpha-only 位图做
    // DST_IN 组合（渐变 × 字形alpha），单次 GPU pass 无离屏，且字形栅格化一次、每帧只做矩阵变换，
    // 对齐 AMLL matrix3d 合成层「不重光栅化」语义——亚像素位移/缩放不跳锚点、无 AA 帧间跳变。
    // glowBitmap：同字形带 BlurMaskFilter 软件模糊的辉光位图（硬件 Canvas 对 drawBitmap 的
    // setShadowLayer/BlurMaskFilter 不生效，辉光只能栅格化时预模糊），glowPad 为模糊外扩像素
    private data class AlphaGlyphBitmap(
      val bitmap: Bitmap,
      val leftPad: Int,
      val baselineY: Float,
      val glowBitmap: Bitmap?,
      val glowPad: Int,
    )

    // char-emphasis 词的字符切分：文本 + 宽度 + ALPHA_8 字形位图
    private data class EmphasisWordMetrics(
      val chars: List<String>,
      val widths: FloatArray,
      val glyphs: List<AlphaGlyphBitmap?>,
    )

    // 整词 ALPHA_8 位图：逐词高亮 + float 路径用 drawBitmap + shader 直绘
    private data class AlphaWordBitmap(
      val bitmap: Bitmap,
      val leftPad: Int,
      val baselineY: Float,
    )

    // QW-2: 热路径零分配复用缓冲(容量只增不减,主线程逐帧 clear 复用)
    private val tmpChunkMergedStartMs = HashMap<Int, Long>()
    private val tmpChunkMergedEndMs = HashMap<Int, Long>()
    private val tmpChunkCharCounts = HashMap<Int, Int>()
    private val tmpChunkCharCursor = HashMap<Int, Int>()
    private var tmpTextWidths = FloatArray(0)
    private var tmpWidths = FloatArray(0)
    private var tmpWordXPositions = FloatArray(0)

    // H-1: 词文本宽度缓存，按字体分桶（rebuildLayoutCache 时随布局一并失效）——
    // 分语种字体下同一文本在不同行宽度不同，不能共用单一映射
    private val wordTextWidthByFont = IdentityHashMap<Typeface, HashMap<String, Float>>()

    // H-1: mainPaint.fontMetrics 缓存(textSize/typeface 变化时重取)
    private var cachedMainFmTextSize = -1f
    private var cachedMainFmTypeface: Typeface? = null
    private var mainFontMetricsCache = Paint.FontMetrics()

    // H-1: subPaint/rubyPaint.fontMetrics 缓存(同样按 textSize/typeface 失效)
    private var cachedSubFmTextSize = -1f
    private var cachedSubFmTypeface: Typeface? = null
    private var subFontMetricsCache = Paint.FontMetrics()
    private var cachedRubyFmTextSize = -1f
    private var cachedRubyFmTypeface: Typeface? = null
    private var rubyFontMetricsCache = Paint.FontMetrics()

    // H-3: 配额从 maxMemory/6(上限 96MB)收紧到 maxMemory/8(上限 48MB),行位图超限走直绘,视效不变
    private val lineBitmapCacheMaxKb =
      ((Runtime.getRuntime().maxMemory() / 8L) / 1024L)
        .coerceIn(16L * 1024L, 48L * 1024L)
        .toInt()
    private val lineBitmapCache =
      object : LruCache<Int, LineBitmap>(lineBitmapCacheMaxKb) {
        override fun sizeOf(
          key: Int,
          value: LineBitmap,
        ): Int = (value.bitmap.byteCount / 1024).coerceAtLeast(1)

        override fun entryRemoved(
          evicted: Boolean,
          key: Int,
          oldValue: LineBitmap,
          newValue: LineBitmap?,
        ) {
          if (oldValue !== newValue && !oldValue.bitmap.isRecycled) oldValue.bitmap.recycle()
        }
      }
    private val emphasisWordMetricsCache =
      object : LruCache<Any, EmphasisWordMetrics>(8 * 1024) {
        // sizeOf 须同时计入主字形位图和辉光位图：glowBitmap 外扩 glowPad 后字节远大于主位图，
        // 漏算会让 LruCache 配额估算偏低、辉光位图内存不受控。
        // 位图实际由字符级缓存持有（跨条目共享），淘汰时不得 recycle，统一交由 GC 回收
        override fun sizeOf(
          key: Any,
          value: EmphasisWordMetrics,
        ): Int =
          value.glyphs
            .sumOf {
              val base = it?.bitmap?.byteCount ?: 0
              val glow = it?.glowBitmap?.byteCount ?: 0
              ((base + glow) / 1024).coerceAtLeast(1)
            }.coerceAtLeast(1)
      }

    // 字符级字形缓存：词实例每行都是新对象，仅按词缓存会让每行激活、字符开始运动的
    // 首帧批量重建字形+辉光（软画布 BlurMaskFilter，每个辉光 1~3ms）造成掉帧尖峰。
    // base 只依赖字符、字号与行字体；glow 追加 σ 的 1px 分桶（同桶复用，视觉无差）。
    // key 必须含 Typeface：分语种字体下同一汉字在 zh/ja 行用不同字体栅格化，
    // TypeFace 实例由系统缓存保证同 family+weight 稳定，data class 按实例相等比较。
    // 淘汰时不 recycle：位图被词级 metrics 与其他条目共享引用，内存交由 GC 回收
    private val baseGlyphCache =
      object : LruCache<GlyphCacheKey, AlphaGlyphBitmap>(8 * 1024) {
        override fun sizeOf(
          key: GlyphCacheKey,
          value: AlphaGlyphBitmap,
        ): Int = (value.bitmap.byteCount / 1024).coerceAtLeast(1)
      }

    private val glowGlyphCache =
      object : LruCache<GlyphCacheKey, CachedGlow>(16 * 1024) {
        override fun sizeOf(
          key: GlyphCacheKey,
          value: CachedGlow,
        ): Int = (value.bitmap.byteCount / 1024).coerceAtLeast(1)
      }

    private data class GlyphCacheKey(
      val text: String,
      val sizePx: Int,
      val sigmaPx: Int,
      val typeface: Typeface,
    )

    private data class CachedGlow(
      val bitmap: Bitmap,
      val pad: Int,
    )

    private val alphaWordBitmapCache =
      object : LruCache<Any, AlphaWordBitmap>(4 * 1024) {
        override fun sizeOf(
          key: Any,
          value: AlphaWordBitmap,
        ): Int = (value.bitmap.byteCount / 1024).coerceAtLeast(1)

        override fun entryRemoved(
          evicted: Boolean,
          key: Any,
          oldValue: AlphaWordBitmap,
          newValue: AlphaWordBitmap?,
        ) {
          if (oldValue !== newValue && !oldValue.bitmap.isRecycled) oldValue.bitmap.recycle()
        }
      }

    // 逐词音译位图缓存：音译随 float 逐帧亚像素位移，live drawText 会逐帧重栅格化抖动
    private val alphaRomanBitmapCache =
      object : LruCache<Any, AlphaWordBitmap>(2 * 1024) {
        override fun sizeOf(
          key: Any,
          value: AlphaWordBitmap,
        ): Int = (value.bitmap.byteCount / 1024).coerceAtLeast(1)

        override fun entryRemoved(
          evicted: Boolean,
          key: Any,
          oldValue: AlphaWordBitmap,
          newValue: AlphaWordBitmap?,
        ) {
          if (oldValue !== newValue && !oldValue.bitmap.isRecycled) oldValue.bitmap.recycle()
        }
      }
    private val romanWordWidthCache = IdentityHashMap<NativeLyricWord, Float>()
    private val rubyWordWidthCache = IdentityHashMap<NativeLyricWord, Float>()

    // H-3: 注音扫掠时间窗缓存(词不可变数据的纯函数结果,随词宽缓存一并失效)
    private val rubySweepWindowsCache = IdentityHashMap<NativeLyricWord, LongArray>()
    private val blurMaskFilterCache = object : LruCache<Int, BlurMaskFilter>(64) {}
    private val solidWordShaderCache = object : LruCache<Int, LinearGradient>(32) {}

    // 复用逐词扫光渐变：固定宽度的 played→unplayed 渐变 + localMatrix 平移到任意 gradientStartX，
    // 避免激活行每帧每词新建 LinearGradient 对象造成 GC 压力（掉帧 / 功耗主因），视觉完全一致。
    // localMatrix 把 shader 的「固定窗口 [0,fadeWidth]」映射到「词的实际扫光窗口」。
    private var reusableFadeWidth = -1f
    private var reusableFadePlayedColor = 0
    private var reusableFadeUnplayedColor = 0
    private var reusableWordShader: LinearGradient? = null
    private val reusableWordShaderMatrix = Matrix()

    // 复用 shader 在 char-emphasis 路径需叠加 canvas 逆矩阵：用 scratch 合成 base 平移 × 逆矩阵，
    // 避免 setLocalMatrix(inverseMatrix) 整体覆盖导致扫光平移丢失
    private val reusableShaderLocalScratch = Matrix()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // ALPHA_8 字形位图直绘专用 paint：FILTER_BITMAP 让双线性采样平滑亚像素变换；
    // shader 由调用方设置（alpha-only 位图与 paint shader 做 DST_IN 组合，单次 GPU pass 无离屏）
    private val alphaGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val emphasisInverseMatrix = Matrix()

    private var fontSizePx = 34f
    private var fontWeight = 700
    private var fontFamily: String? = null
    private var fontFamilyChinese: String? = null
    private var fontFamilyJapanese: String? = null
    private var fontFamilyKorean: String? = null
    private var fontFamilyLatin: String? = null
    private var textColor = Color.WHITE
    private var inactiveAlpha = 0.2f
    private var alphaAttackSpeed = 50f
    private var alphaReleaseSpeed = 7f

    // 逐词浮动退场衰减速度（指数衰减时间常数的倒数，越小回落越慢）；
    // 对齐 Web 引擎 line-animations 旧行浮动反向播放的语义，行失活后浮动量平滑落回
    private val floatFadeSpeed = 6f

    private var alignPosition = 0.35f
    private var wordFadeWidth = 0.5f
    private var hidePassedLines = false
    private var enableBlur = false
    private var enableWordHighlight = true
    private var enableFloatAnimation = false
    private var enableEmphasizeEffect = false
    private var enableWordBlockSegmentation = false
    private var showTranslation = true
    private var showRomanization = true

    // 对齐 AMLL `getAlwaysPostpositionBackground`：为 true 时强制将背景行置于主行之后，
    // 忽略「BG 首词开始时间早于主行首词」这一 AMLL 前置背景条件。默认 false（允许前置）。
    private var alwaysPostpositionBackground = false

    private var baseTimeMs = 0L
    private var anchorNano = System.nanoTime()
    private var timeOffsetMs = 0L
    private var playbackRate = 1f
    private var playing = false
    private var frozen = false
    private var visibleState = false
    private var suppressNextTapSeek = false

    // 用户滚动偏移：0 = 激活行居中，正值 = 上滑看后续行，负值 = 下滑看前面行
    private var userScrollOffset = 0f
    private var inertialVelocity = 0f
    private var scrollResetNano = 0L
    private var isDragging = false
    private var dragStartY = 0f
    private var dragStartUserScroll = 0f

    // 对齐 Web 引擎 line-builder.ts: 整首歌任一行 >1 词时，单词行也走逐词路径
    private var hasMultiWordLine = false

    // 逐行弹簧：同一行的位置与缩放绑定在一起，避免平行数组状态错位。
    private var lineSprings: Array<LineSpringState> = emptyArray()
    private val activeLineSpringIndices = linkedSetOf<Int>()

    // 上次提交的布局状态，仅在状态变化时复制集合，避免 onDraw 每帧构造签名字符串。
    private var hasCommittedLayoutState = false
    private var lastLayoutAnchorIndex = -1
    private var lastLayoutActiveLineIndices: Set<Int> = emptySet()
    private var lastLayoutBufferedLineIndices: Set<Int> = emptySet()
    private var lastLayoutLyricCount = 0
    private var lastLayoutInterlude: InterludeInfo? = null
    private var lastLayoutUserScrollOffset = 0f
    private var lastViewportWidth = -1
    private var lastViewportHeight = -1

    // 对齐 Web 引擎 calculateLayout 的 noCascade：seek 重布局时跳过级联延迟，全员同步运动
    private var noCascadeNextLayout = false

    // 对齐 AMLL 0.5.x TimelineController：快照 + 增量架构的时间线推导器，
    // playing/highlighted 集合、scrollToIndex、间奏区间均由控制器内部维护
    private val timelineController = AndroidLyricTimelineController()

    // 控制器索引（非 BG 行序号）→ lyricLines 索引的映射；BG 行随主行激活，不参与时间推导
    private var mainLineIndices = IntArray(0)

    // 跳转 / 歌词重置后强制下一次 sync 走 seek 重建路径
    private var pendingForceSeek = true

    // 最近一次 sync 物化的间奏信息（与 cachedActiveState 属同一时间区间内有效）
    private var cachedInterludeInfo: InterludeInfo? = null

    private var timelineBoundaries = LongArray(0)
    private var timelineIntervalStartMs = Long.MIN_VALUE
    private var timelineIntervalEndMs = Long.MAX_VALUE
    private var timelineStateValid = false
    private var cachedActiveState = ActiveState(emptySet(), -1)

    // 对齐 AMLL scrollToIndex：无高亮行时保持上一次锚点，避免旧行 top 跳变导致 positionSpring 振荡。
    private var persistentAnchorIndex: Int = -1

    // 遮罩扫光段的复用缓冲：每段对应一个文件词（注音词再拆字符子段），容量只增不减，避免逐帧分配
    private var sweepSegFirstX = FloatArray(0)
    private var sweepSegWidths = FloatArray(0)
    private var sweepSegStarts = LongArray(0)
    private var sweepSegEnds = LongArray(0)
    private var sweepWordSegIndex = IntArray(0)
    private var sweepWordSegLast = IntArray(0)
    private var sweepSegCount = 0

    // 用户配置的弹簧参数缓存
    private var springMass = 0.9f
    private var springDamping = 15f
    private var springStiffness = 90f

    /** 上次弹簧策略应用的歌曲结束标记，用于检测 isEndOfSong 变化（TimelineDiff 无此标志） */
    private var lastPolicyEndOfSong = false

    private var bottomExclusionHeightPx = 0f
    private var touchEnabled = true
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastTouchTime = 0L
    private var touchVelocity = 0f
    private var lastDrawNano = 0L
    private var lastObservedTimeMs = Long.MIN_VALUE
    private var lastObservedNano = 0L

    // 上一次 updateProgress 收到的权威时间（provider/推送时间轴），用于推送间的 seek 检测
    private var lastIncomingTimeMs = Long.MIN_VALUE

    // 对齐 Web 引擎 snapNextSeek：仅冻结/可见性恢复时瞬移布局，常规 seek 走弹簧过渡
    private var snapNextLineSpring = false

    init {
      setWillNotDraw(false)
      alpha = 1f
      visibility = GONE
      mainPaint.hinting = Paint.HINTING_OFF
      subPaint.hinting = Paint.HINTING_OFF
      mainPaint.color = textColor
      subPaint.color = textColor
    }

    fun setRendererVisible(visible: Boolean) {
      visibleState = visible
      visibility = if (visible) VISIBLE else GONE
      if (visible) postInvalidateOnAnimation()
    }

    fun setLyrics(lines: List<NativeLyricLine>) {
      lyricLines = lines
      hasMultiWordLine = lyricLines.any { it.words.size > 1 }
      rebuildTimelineBoundaries()
      rebuildControllerTimeline()
      invalidateLayoutCache()
      resetLineVisualStates()
      resetTimelineState()
      // 对齐 Web 引擎 setLyrics → handleSeek → resetUserScrollState：新歌词不继承上一首的滚动偏移
      userScrollOffset = 0f
      inertialVelocity = 0f
      scrollResetNano = 0L
      val activeState = resolveActiveState(baseTimeMs)
      if (viewportWidth > 0 && viewportHeight > 0 && lyricLines.isNotEmpty()) {
        ensureLayoutCache()
        val (layouts, frameContentHeight) =
          computeFrameLayouts(activeState, cachedInterludeInfo)
        lineLayouts = layouts
        contentHeight = frameContentHeight
      }
      rebuildLineSprings()
      playEntranceAnimation()
      if (lyricLines.isEmpty()) {
        userScrollOffset = 0f
      }
      invalidate()
    }

    fun clearLyrics() {
      lyricLines = emptyList()
      lineLayouts = emptyList()
      lineHitTops = FloatArray(0)
      lineHitBottoms = FloatArray(0)
      contentHeight = 0f
      lineBrightAlphas = FloatArray(0)
      linePassAlphas = FloatArray(0)
      lineBlurValues = FloatArray(0)
      lineBlurSettled = BooleanArray(0)
      interludeState = InterludeState()
      lineSprings = emptyArray()
      activeLineSpringIndices.clear()
      emphasisWordMetricsCache.evictAll()
      baseGlyphCache.evictAll()
      glowGlyphCache.evictAll()
      lineBgAboveCache = BooleanArray(0)
      invalidateCommittedLayoutState()
      snapNextLineSpring = false
      timelineBoundaries = LongArray(0)
      rebuildControllerTimeline()
      userScrollOffset = 0f
      inertialVelocity = 0f
      scrollResetNano = 0L
      resetTimelineState()
      invalidateAllLineBitmaps()
      // 重置视口，避免退出后重新进入时残留旧视口导致歌词位置错误或触摸拦截
      viewportLeft = 0
      viewportTop = 0
      viewportWidth = 0
      viewportHeight = 0
      viewportCssWidth = 0f
      invalidateLayoutCache()
      invalidate()
    }

    fun setConfig(
      fontSizePx: Float,
      fontWeight: Int,
      fontFamily: String?,
      fontFamilyChinese: String?,
      fontFamilyJapanese: String?,
      fontFamilyKorean: String?,
      fontFamilyLatin: String?,
      textColor: Int,
      inactiveAlpha: Float,
      alignPosition: Float,
      wordFadeWidth: Float,
      hidePassedLines: Boolean,
      enableBlur: Boolean,
      enableWordHighlight: Boolean,
      enableFloatAnimation: Boolean,
      enableEmphasizeEffect: Boolean,
      enableWordBlockSegmentation: Boolean,
      showTranslation: Boolean,
      showRomanization: Boolean,
      springMass: Float?,
      springDamping: Float?,
      springStiffness: Float?,
      alwaysPostpositionBackground: Boolean = false,
    ) {
      this.fontSizePx = fontSizePx.coerceAtLeast(12f)
      this.fontWeight = fontWeight.coerceIn(100, 1500)
      this.fontFamily = fontFamily?.takeIf { it.isNotBlank() }
      this.fontFamilyChinese = fontFamilyChinese?.takeIf { it.isNotBlank() }
      this.fontFamilyJapanese = fontFamilyJapanese?.takeIf { it.isNotBlank() }
      this.fontFamilyKorean = fontFamilyKorean?.takeIf { it.isNotBlank() }
      this.fontFamilyLatin = fontFamilyLatin?.takeIf { it.isNotBlank() }
      this.textColor = textColor
      this.inactiveAlpha = inactiveAlpha.coerceIn(0.02f, 1f)
      this.alignPosition = alignPosition.coerceIn(0.05f, 0.95f)
      this.wordFadeWidth = wordFadeWidth.coerceIn(0.05f, 1f)
      this.hidePassedLines = hidePassedLines
      this.enableBlur = enableBlur
      // 模糊通过位图缓存的 Canvas(bitmap)（软件画布）隔离实现，不依赖 View 的 layerType。
      // 强制软件层会让激活行的 LinearGradient 渐变高光也走软件光栅化，造成不必要的性能损耗。
      setLayerType(LAYER_TYPE_NONE, null)
      this.enableWordHighlight = enableWordHighlight
      this.enableFloatAnimation = enableFloatAnimation
      this.enableEmphasizeEffect = enableEmphasizeEffect
      this.enableWordBlockSegmentation = enableWordBlockSegmentation
      this.showTranslation = showTranslation
      this.showRomanization = showRomanization
      this.springMass = springMass ?: 0.9f
      this.springDamping = springDamping ?: 15f
      this.springStiffness = springStiffness ?: 90f
      this.alwaysPostpositionBackground = alwaysPostpositionBackground
      applySpringParams()
      emphasisWordMetricsCache.evictAll()
      baseGlyphCache.evictAll()
      glowGlyphCache.evictAll()
      invalidateLayoutCache()
      recalculateLayouts()
      invalidate()
    }

    fun updateProgress(
      timeMs: Long,
      playing: Boolean,
    ) {
      val playbackPositionMs = playbackPositionProvider?.invoke()
      val incomingTimeMs = (playbackPositionMs?.plus(timeOffsetMs) ?: timeMs).coerceAtLeast(0L)
      // 时钟改为逐帧直读 provider 后，当前帧时间与 incoming 恒等，
      // 跳转检测改用上一次推送的权威时间：后退 >100ms 或前进超出推送间隔合理增速（+2000ms）
      // 视为 seek，容限语义与原「incoming 对比锚点插值时钟」一致
      val prevIncomingTimeMs = lastIncomingTimeMs
      lastIncomingTimeMs = incomingTimeMs
      val visibleTimeMs = resolveCurrentTimeMs()
      val wasPlaying = this.playing
      val isJump =
        prevIncomingTimeMs != Long.MIN_VALUE &&
          AndroidLyricTimeline.isProgressJump(incomingTimeMs, prevIncomingTimeMs)
      baseTimeMs =
        AndroidLyricTimeline.resolveProgressAnchor(
          incomingTimeMs = incomingTimeMs,
          visibleTimeMs = visibleTimeMs,
          wasPlaying = wasPlaying,
          playing = playing,
          isJump = isJump,
        )
      anchorNano = System.nanoTime()
      playbackRate = playbackRateProvider?.invoke()?.coerceIn(0.25f, 3f) ?: 1f
      this.playing = playing
      // 对齐 Web 引擎 setPlaying → calculateLayout(false)：播放状态变化时重算布局，
      // 暂停后非激活行缩放目标回到 100，恢复播放后再弹簧缩回 97/75
      if (wasPlaying != playing) invalidateCommittedLayoutState()
      if (isJump) {
        handleProgressJump()
      }
      if (visibleState && shouldInvalidateForProgress(baseTimeMs)) postInvalidateOnAnimation()
    }

    fun setTimeOffset(timeOffsetMs: Long) {
      if (this.timeOffsetMs == timeOffsetMs) return
      if (frozen) baseTimeMs += timeOffsetMs - this.timeOffsetMs
      this.timeOffsetMs = timeOffsetMs
      if (!frozen) updateProgress(baseTimeMs, playing)
    }

    fun notifyPlaybackClockChanged() {
      updateProgress(baseTimeMs, playing)
    }

    fun freezeRenderer() {
      baseTimeMs = resolveCurrentTimeMs()
      frozen = true
      lastObservedTimeMs = Long.MIN_VALUE
      lastObservedNano = 0L
    }

    fun resumeRenderer() {
      frozen = false
      updateProgress(baseTimeMs, playing)
      snapNextLineSpring = true
      lastObservedTimeMs = Long.MIN_VALUE
      lastObservedNano = 0L
      invalidateCommittedLayoutState()
      resetTimelineState()
      if (visibleState) postInvalidateOnAnimation()
    }

    fun refreshLayoutState() {
      recalculateLayouts()
      invalidate()
    }

    fun suppressTapSeek() {
      suppressNextTapSeek = true
    }

    fun setTouchEnabled(enabled: Boolean) {
      if (touchEnabled == enabled) return
      touchEnabled = enabled
      if (!enabled) {
        isDragging = false
        touchVelocity = 0f
        inertialVelocity = 0f
        parent?.requestDisallowInterceptTouchEvent(false)
      }
    }

    private var viewportLeft = 0
    private var viewportTop = 0
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var viewportCssWidth = 0f

    fun setViewport(
      left: Int,
      top: Int,
      width: Int,
      height: Int,
      bottomExclusionHeight: Int,
      cssWidth: Float?,
    ) {
      viewportLeft = left
      viewportTop = top
      viewportWidth = width.coerceAtLeast(0)
      viewportHeight = height.coerceAtLeast(0)
      viewportCssWidth = cssWidth?.coerceAtLeast(0f) ?: 0f
      this.bottomExclusionHeightPx = bottomExclusionHeight.coerceAtLeast(0).toFloat()
      invalidateLayoutCache()
      recalculateLayouts()
      invalidate()
    }

    override fun onSizeChanged(
      w: Int,
      h: Int,
      oldw: Int,
      oldh: Int,
    ) {
      super.onSizeChanged(w, h, oldw, oldh)
      invalidateLayoutCache()
      recalculateLayouts()
    }

    override fun onDetachedFromWindow() {
      super.onDetachedFromWindow()
      // M-5: 清理已投递的延迟帧与惯性/时钟状态,避免离屏后仍被唤醒一次或复用时带入旧动量
      handler?.removeCallbacksAndMessages(null)
      inertialVelocity = 0f
      scrollResetNano = 0L
      lastDrawNano = 0L
      invalidateAllLineBitmaps()
      emphasisWordMetricsCache.evictAll()
      baseGlyphCache.evictAll()
      glowGlyphCache.evictAll()
      alphaWordBitmapCache.evictAll()
      alphaRomanBitmapCache.evictAll()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
      if (!touchEnabled) return false
      return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
      if (!touchEnabled) return false
      if (!visibleState || lyricLines.isEmpty()) return false
      if (viewportWidth <= 0 || viewportHeight <= 0) return false
      if (event.actionMasked == MotionEvent.ACTION_DOWN) {
        if (event.y < viewportTop || event.y >= viewportTop + viewportHeight - bottomExclusionHeightPx
        ) {
          return false
        }
        if (event.x < viewportLeft || event.x > viewportLeft + viewportWidth) return false
      }
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          isDragging = true
          dragStartY = event.y
          dragStartUserScroll = userScrollOffset
          lastTouchX = event.x
          lastTouchY = event.y
          touchStartX = event.x
          touchStartY = event.y
          lastTouchTime = event.eventTime
          touchVelocity = 0f
          inertialVelocity = 0f
          scrollResetNano = 0L
          parent?.requestDisallowInterceptTouchEvent(true)
          return true
        }
        MotionEvent.ACTION_MOVE -> {
          if (isDragging) {
            val dy = event.y - dragStartY
            val dt = max(1L, event.eventTime - lastTouchTime).toFloat()
            val velocity = (lastTouchY - event.y) / dt
            touchVelocity = touchVelocity * 0.65f + velocity * 0.35f
            lastTouchY = event.y
            lastTouchTime = event.eventTime
            // 手指上滑 dy<0 → offset 增大 → 内容上移
            userScrollOffset = dragStartUserScroll - dy
            invalidate()
          }
          return true
        }
        MotionEvent.ACTION_UP -> {
          isDragging = false
          parent?.requestDisallowInterceptTouchEvent(false)

          val dx = event.x - touchStartX
          val dy = event.y - touchStartY
          if (dx * dx + dy * dy > (16f * density) * (16f * density)) {
            // 拖动松手：惯性 + 5 秒后自动回弹到激活行；轻点不进入用户滚动状态，避免抑制淡出/模糊
            // 惯性：touchVelocity 正值 = 上滑，继续增大 offset
            if (kotlin.math.abs(touchVelocity) > 0.015f) {
              inertialVelocity = touchVelocity.coerceIn(-2.4f, 2.4f)
            }
            scrollResetNano = System.nanoTime() + 5_000_000_000L
            return true
          }
          if (suppressNextTapSeek) {
            suppressNextTapSeek = false
            return true
          }
          val seekTarget = findSeekTarget(event.y)
          if (seekTarget != null) {
            // 对齐 Web 引擎 handleLineClick → seek → handleSeek：所有 seek 统一走弹簧过渡（noCascade），
            // findSeekTarget 返回歌词行原始时间；seek 作用于播放时钟（视图时间 = 播放 + 偏移），需减去偏移
            val seekTargetMs = (seekTarget - timeOffsetMs).coerceAtLeast(0L)
            invalidateCommittedLayoutState()
            onSeekRequested(seekTargetMs)
            postInvalidateOnAnimation()
          }
          return true
        }
        MotionEvent.ACTION_CANCEL -> {
          // 对齐 Web 引擎 handleTouchCancel：清除速度，不触发惯性/回弹/tap seek
          isDragging = false
          parent?.requestDisallowInterceptTouchEvent(false)
          touchVelocity = 0f
          inertialVelocity = 0f
          return true
        }
      }
      return super.onTouchEvent(event)
    }

    override fun onVisibilityChanged(
      changedView: View,
      visibility: Int,
    ) {
      super.onVisibilityChanged(changedView, visibility)
      if (visibility == VISIBLE && visibleState && playing && !frozen) {
        lastDrawNano = 0L
        postInvalidateOnAnimation()
      }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
      super.onWindowVisibilityChanged(visibility)
      if (visibility == VISIBLE && visibleState && playing && !frozen) {
        lastDrawNano = 0L
        postInvalidateOnAnimation()
      }
    }

    override fun onDraw(canvas: Canvas) {
      super.onDraw(canvas)
      if (!visibleState || width <= 0 || height <= 0 || lyricLines.isEmpty()) return
      if (viewportWidth <= 0 || viewportHeight <= 0) return

      val currentTimeMs = resolveCurrentTimeMs()
      val currentNano = System.nanoTime()
      if (isNativeClockJump(currentTimeMs, currentNano)) handleProgressJump()
      lastObservedTimeMs = currentTimeMs
      lastObservedNano = currentNano
      val deltaMs = if (lastDrawNano == 0L) 16f else (currentNano - lastDrawNano) / 1_000_000f
      lastDrawNano = currentNano
      // 单帧大 hitch（GC/位图重建）只应推进有限的动画时间：退场计时、惯性、回弹统一用钉扎值，
      // 避免一帧卡顿后动画状态瞬间跳变
      val clampedDeltaMs = deltaMs.coerceAtMost(100f)
      val frameDeltaSec = (deltaMs.coerceAtMost(100f)) / 1000f
      val attackFactor = 1f - Math.exp((-alphaAttackSpeed * frameDeltaSec).toDouble()).toFloat()
      val releaseFactor = 1f - Math.exp((-alphaReleaseSpeed * frameDeltaSec).toDouble()).toFloat()

      ensureLayoutCache()
      val activeState = resolveActiveState(currentTimeMs)
      val candidateInterlude = cachedInterludeInfo
      // 对齐 Web 引擎 processTime + calculateLayout：仅在激活行或间奏状态变化时触发布局重算，
      // 避免每帧重复设置弹簧目标导致振荡。
      val layoutStateChanged = isLayoutStateChanged(activeState, candidateInterlude)
      val viewportChanged = viewportWidth != lastViewportWidth || viewportHeight != lastViewportHeight

      // 对齐 Web 引擎 consumeUserScrollMotion：滚动增量在布局前消费，
      // 拖拽/惯性期间每帧重设弹簧目标，行弹簧「追逐」手指而非 1:1 跟手
      if (!isDragging) {
        if (kotlin.math.abs(inertialVelocity) > 0.015f) {
          val frameDelta = (inertialVelocity * clampedDeltaMs).coerceIn(-96f, 96f)
          userScrollOffset += frameDelta
          inertialVelocity *= Math.exp((-(clampedDeltaMs / 280f)).toDouble()).toFloat()
        } else {
          inertialVelocity = 0f
        }
        if (scrollResetNano > 0 && currentNano >= scrollResetNano) {
          // 对齐 Web 引擎 resetUserScrollToActiveLine：置零偏移后由布局块重设弹簧目标，
          // 行弹簧动画回位（可能带轻微弹性），而非指数衰减
          userScrollOffset = 0f
          inertialVelocity = 0f
          scrollResetNano = 0L
        }
      }
      // 对齐 Web 引擎：userScrollOffset 参与布局目标计算，偏移变化即触发重定位
      val scrollChanged = userScrollOffset != lastLayoutUserScrollOffset

      if (layoutStateChanged || snapNextLineSpring || scrollChanged) {
        val (frameLayouts, frameContentHeight) = computeFrameLayouts(activeState, candidateInterlude)
        lineLayouts = frameLayouts
        contentHeight = frameContentHeight
        // 对齐 AMLL LayoutStrategy.snapPosY：冻结/可见性恢复/视口变化/拖拽与惯性滑动
        // (ContinuousScroll) 期间行位置瞬移跟手；常规行切换、seek、暂停缩放均走弹簧过渡
        val syncImmediate =
          frozen ||
            snapNextLineSpring ||
            viewportChanged ||
            isDragging ||
            kotlin.math.abs(inertialVelocity) > 0.015f
        val noCascade = noCascadeNextLayout
        noCascadeNextLayout = false
        applyLineSpringTargets(frameLayouts, activeState, syncImmediate, noCascade)
        if (snapNextLineSpring) {
          // 对齐 Web 引擎 snapVisualState：恢复/可见性变化时瞬移 alpha/pass 到目标值
          snapVisualState(activeState)
        }
        snapNextLineSpring = false
        commitLayoutState(activeState, candidateInterlude)
        commitViewportState()
      } else if (viewportChanged) {
        val (frameLayouts, frameContentHeight) = computeFrameLayouts(activeState, candidateInterlude)
        lineLayouts = frameLayouts
        contentHeight = frameContentHeight
        applyLineSpringTargets(frameLayouts, activeState, syncImmediate = true, noCascade = false)
        commitViewportState()
      }

      // 每帧推进仍在运动的逐行弹簧
      // 对齐 Web 引擎：clamp deltaMs 防止大 dt（GC / 位图重建等卡顿）导致弹簧单帧跳变
      val springDeltaSec = deltaMs.coerceAtMost(100f) / 1000f
      val activeSpringIterator = activeLineSpringIndices.iterator()
      while (activeSpringIterator.hasNext()) {
        val spring = lineSprings.getOrNull(activeSpringIterator.next())
        if (spring == null) {
          activeSpringIterator.remove()
          continue
        }
        spring.position.update(springDeltaSec)
        spring.scale.update(springDeltaSec)
        spring.bgSlide?.update(springDeltaSec)
        if (spring.position.arrived() &&
          spring.scale.arrived() &&
          spring.bgSlide?.arrived() != false
        ) {
          activeSpringIterator.remove()
        }
      }

      val maxVerticalEffectPadding = max(8f * density, fontSizePx * 0.4f)
      canvas.save()
      canvas.clipRect(
        viewportLeft.toFloat(),
        viewportTop.toFloat(),
        (viewportLeft + viewportWidth).toFloat(),
        (viewportTop + viewportHeight).toFloat(),
      )
      canvas.translate(viewportLeft.toFloat(), viewportTop.toFloat())
      // 对齐 Web 引擎 isUserScrolling：拖拽中或回弹计时器未到期均视为用户滚动
      val isUserScrolling = isDragging || scrollResetNano > 0
      if (interludeState.isActive) {
        val anchorSpring = lineSprings.getOrNull(interludeState.anchorIndex)?.position
        val interludeY =
          anchorSpring?.getCurrentPosition()?.plus(interludeState.anchorOffset)
            ?: interludeState.y
        drawInterludeDots(canvas, currentTimeMs, interludeY)
      }

      if (lineHitTops.size != lyricLines.size) {
        lineHitTops = FloatArray(lyricLines.size)
        lineHitBottoms = FloatArray(lyricLines.size)
      }
      lineHitTops.fill(Float.NaN)
      lineHitBottoms.fill(Float.NaN)

      // 对齐 AMLL 分组绘制顺序：BG 和声行（bgWrapper z-index -1 语义）先于其主行绘制，
      // 滑入/滑出期间从主行文字背后穿过；其余行保持索引序
      fun processLyricLine(index: Int) {
        val layout = lineLayouts.getOrNull(index) ?: return
        val springState = lineSprings.getOrNull(index)
        val springTop = springState?.position?.getCurrentPosition() ?: layout.top
        val line = lyricLines[index]
        var drawTop = springTop
        var drawScale = (springState?.scale?.getCurrentPosition() ?: 100f) / 100f
        if (line.isBG) {
          // 对齐 AMLL bgSlideY：隐藏位 ±80（自身高度百分比），激活或暂停时滑到 0；
          // 滑动期间叠加 bgWrapper 缩放 0.8→1.0，随滑动进度推进
          val slide = springState?.bgSlide?.getCurrentPosition() ?: 0f
          drawTop += slide / 100f * layout.height
          drawScale *= 0.8f + (1f - abs(slide) / 80f).coerceIn(0f, 1f) * 0.2f
        }
        // 对齐 Web 引擎 transform: translateY + scale 的默认中心变换原点
        val scaleOriginY = drawTop + layout.height * 0.5f
        val transformedTop = scaleOriginY + (drawTop - scaleOriginY) * drawScale
        val transformedBottom = scaleOriginY + (drawTop + layout.height - scaleOriginY) * drawScale
        lineHitTops[index] = min(transformedTop, transformedBottom)
        lineHitBottoms[index] = max(transformedTop, transformedBottom)
        val blurPadding = (lineBlurValues.getOrNull(index) ?: 0f) * 1.5f * density
        val effectPadding = max(maxVerticalEffectPadding, blurPadding)
        val inViewport =
          max(transformedTop, transformedBottom) + effectPadding >= 0f &&
            min(transformedTop, transformedBottom) - effectPadding <= viewportHeight
        // 对齐 Web 引擎 activeLineSet：仅当前时间窗行（含 BG 配对）视为激活，
        // 旧行时间窗结束即进入退场过渡
        val hotActive = activeState.activeLineIndices.contains(index)
        // 对齐 AMLL resolveIsActive：高亮行（唱完未熄灭）与 [scrollTo, latest) 范围内的
        // 中间行均视为已呈现，保持 0.85 亮度档与全尺寸
        val presented = hotActive || activeState.isPresented(index)
        // 对齐 AMLL resolveBlurLevel：模糊更新先于视口剔除，视口外行目标为最大档位，
        // 行滚入视口时从模糊渐入，而非清晰突变到模糊；
        // 距离按主行索引计算（BG 行紧跟主行存储于 index+1，其主行在 index-1），
        // 对齐 AMLL 组级模糊：主行与 BG 行共享同组档位
        val blurDistanceIndex = if (line.isBG && index > 0) index - 1 else index
        val blurRadius =
          updateLineBlurRadius(
            index = index,
            distanceIndex = blurDistanceIndex,
            activeState = activeState,
            active = presented,
            isUserScrolling = isUserScrolling,
            inViewport = inViewport,
            deltaMs = deltaMs,
          )
        if (!inViewport) {
          return
        }
        // 逐词效果仍活跃期间（retainedWordEffect）不衰减浮动，对齐 Web 引擎旧行动画反向播放的保持语义
        val retainedWordEffect = hasRetainedWordEffect(index, line, currentTimeMs)
        val wordEffectActive = hotActive || retainedWordEffect
        updateLineFloatFade(index, presented || retainedWordEffect, clampedDeltaMs)
        // 对齐 Web 引擎：用户滚动时抑制已播放行淡出
        val passed =
          hidePassedLines &&
            playing &&
            !presented &&
            !isUserScrolling &&
            isLinePassed(index, activeState.anchorIndex)
        val brightAlpha =
          updateLineBrightAlpha(
            index,
            line,
            hotActive,
            presented && !hotActive,
            passed,
            attackFactor,
            releaseFactor,
          )
        val passAlpha = updateLinePassAlpha(index, passed, releaseFactor)
        // BG 仅在有效 alpha 接近 0 时跳过绘制，避免不可见行进入绘制路径
        if (line.isBG && brightAlpha * passAlpha <= 0.001f) {
          return
        }
        drawLine(
          canvas,
          line,
          layout,
          drawTop,
          drawScale,
          currentTimeMs,
          brightAlpha * passAlpha,
          wordEffectActive,
          blurRadius,
          index,
        )
      }
      var drawCursor = 0
      while (drawCursor < lyricLines.size) {
        val line = lyricLines[drawCursor]
        val hasBgPartner = !line.isBG && lyricLines.getOrNull(drawCursor + 1)?.isBG == true
        if (hasBgPartner) {
          processLyricLine(drawCursor + 1)
          processLyricLine(drawCursor)
          drawCursor += 2
        } else {
          processLyricLine(drawCursor)
          drawCursor += 1
        }
      }

      canvas.restore()

      val frameAnimationActive =
        hasActiveFrameAnimation(activeState, currentTimeMs) || interludeState.isActive
      if ((playing && !frozen && frameAnimationActive) ||
        lineSpringsActive() ||
        lineBlurAnimating() ||
        kotlin.math.abs(userScrollOffset) > 0.5f ||
        kotlin.math.abs(inertialVelocity) > 0.015f
      ) {
        postInvalidateOnAnimation()
      } else if (playing && !frozen) {
        nextVisualBoundaryMs(activeState, currentTimeMs)?.let { nextTimeMs ->
          // QW-3: 16ms 下界避免过度唤醒,对齐 60fps 帧边界；
          // 歌词时钟间隔按播放速率换算成墙钟延迟，非 1x 速率下扫光/高亮不迟到
          val wakeDelayMs = ((nextTimeMs - currentTimeMs) / playbackRate).toLong().coerceAtLeast(16L)
          postInvalidateDelayed(wakeDelayMs)
        }
      }
    }

    private fun drawLine(
      canvas: Canvas,
      line: NativeLyricLine,
      layout: LineLayout,
      springTop: Float,
      scale: Float,
      currentTimeMs: Long,
      baseAlpha: Float,
      active: Boolean,
      blurRadius: Float,
      index: Int,
    ) {
      val top = springTop
      val mainSize = if (line.isBG) fontSizePx * 0.75f else fontSizePx
      val lineAlpha = baseAlpha

      val subLines = lineSubLinesCache.getOrNull(index) ?: emptyList()
      val contentMetrics = lineMetricsCache.getOrNull(index) ?: return

      // 非激活行走位图缓存：把整行栅格化到 Bitmap，亚像素的 translate/scale 由 Skia 平滑采样，
      // 避免每帧重栅格化文字产生 AA 漂移。激活行因为有逐词渐变/浮动/字符强调等逐帧状态，仍走直绘。
      // 模糊必须走位图缓存：硬件加速 Canvas 对 drawText 的 BlurMaskFilter 不生效，只有
      // Canvas(bitmap) 软件画布能栅格化出模糊。渐变期同样进缓存，blurKey 按 2px 档量化，
      // 一次 0→目标 的渐变仅触发数次重建，分摊到多帧，主线程开销可控。
      // 对齐 AMLL 退场动画：逐词浮动衰减期间（lineFloatFadeProgress > 0）也不进入位图缓存，
      // 让浮动通过直绘平滑落回，衰减完成后才缓存
      val floatFade = lineFloatFadeProgress.getOrNull(index) ?: 0f
      val isFloatFading =
        !active && floatFade > 0.001f && (enableFloatAnimation || enableEmphasizeEffect)
      if (!active && !isFloatFading) {
        val cache =
          getOrBuildLineBitmap(index, line, mainSize, subLines, contentMetrics, blurRadius)
        if (cache != null) {
          canvas.save()
          if (scale != 1f) {
            val originX = if (line.isDuet) viewportWidth.toFloat() else 0f
            // 对齐 AMLL bgWrapper transform-origin：下方 BG left top，置顶 BG left bottom
            val originY =
              if (line.isBG) {
                if (isBgAbove(index)) top + layout.height else top
              } else {
                top + layout.height * 0.5f
              }
            canvas.scale(scale, scale, originX, originY)
          }
          bitmapPaint.alpha = (lineAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
          // 位图顶部留了 cache.pad 像素吸收模糊溢出，绘制时把 Y 上推同等距离让正文落在 top
          canvas.drawBitmap(cache.bitmap, 0f, top - cache.pad, bitmapPaint)
          canvas.restore()
          return
        }
        // 缓存构建失败（如尺寸非法/超限），回退直绘保持显示不丢失
      } else {
        // 激活行会一直在变（逐词高亮 + 浮动），其缓存对相邻帧无意义；行变激活的瞬间立即释放
        if (lineBitmapCache.get(index) != null) invalidateLineBitmap(index)
      }

      canvas.save()
      // 行缩放：对齐 Web 引擎 transform-origin
      // 主行 left center，对唱行 right center，BG 行 left/right top（置顶 BG 为 bottom）
      if (scale != 1f) {
        val originX = if (line.isDuet) viewportWidth.toFloat() else 0f
        val originY =
          if (line.isBG) {
            if (isBgAbove(index)) top + layout.height else top
          } else {
            top + layout.height * 0.5f
          }
        canvas.scale(scale, scale, originX, originY)
      }
      val scaleOriginY = if (line.isBG) top else top + layout.height * 0.5f

      val mainBottom =
        drawMainText(
          canvas = canvas,
          line = line,
          top = top,
          mainSize = mainSize,
          lineAlpha = lineAlpha,
          currentTimeMs = currentTimeMs,
          // 对齐 AMLL 退场动画：浮动衰减期间需走逐词直绘路径以渲染衰减中的浮动
          active = active || isFloatFading,
          // lineActive 区别于 active：isFloatFading 时 active=true（走逐词路径）但
          // lineActive=false（浮动衰减）
          lineActive = active,
          blurRadius = blurRadius,
          contentMetrics = contentMetrics,
          index = index,
        )
      drawSubTexts(
        canvas = canvas,
        line = line,
        subLines = subLines,
        mainBottom = mainBottom,
        lineAlpha = lineAlpha,
        blurRadius = blurRadius,
        contentMetrics = contentMetrics,
        scale = scale,
        scaleOriginY = scaleOriginY,
      )

      canvas.restore()
    }

    private fun drawMainText(
      canvas: Canvas,
      line: NativeLyricLine,
      top: Float,
      mainSize: Float,
      lineAlpha: Float,
      currentTimeMs: Long,
      active: Boolean,
      lineActive: Boolean = active,
      blurRadius: Float,
      contentMetrics: ContentBlockMetrics,
      index: Int = -1,
    ): Float {
      mainPaint.textSize = contentMetrics.main.textSize
      mainPaint.typeface = lineMainTypeface(index)
      val useStaticAlphaLayer = enableWordHighlight && !isWordByWordLine(line)
      // H-4: 行透明度统一烤进 paint,无模糊时不再需要整行离屏层；
      // 启用离屏层时烤全亮，由层 alpha 一次性乘行透明度，避免与 paint alpha 相乘成 alpha²
      val staticLayerEnabled = useStaticAlphaLayer && blurRadius > 0f
      mainPaint.color = applyAlpha(textColor, if (staticLayerEnabled) 1f else lineAlpha)
      mainPaint.shader = null
      // 激活行 blurRadius 从非零值衰减到 0，残留值在硬件加速 Canvas 上 BlurMaskFilter 不生效；
      // 阈值 0.5px 以下直接按 0 处理，避免设置无效的 maskFilter
      if (blurRadius > 0.5f) {
        mainPaint.maskFilter = getBlurMaskFilter(blurRadius)
      } else {
        mainPaint.maskFilter = null
      }

      val fm = mainFontMetrics()
      val verticalPadding = mainSize * 0.4f
      val baseline = top + verticalPadding - fm.ascent
      val drawX =
        if (line.isDuet) {
          // 修复：确保右对唱行的绘制位置不会为负，避免文本超出屏幕左侧
          (contentMetrics.endX - contentMetrics.main.width).coerceAtLeast(0f)
        } else {
          contentMetrics.startX
        }
      val hasWordRoman =
        lineHasRomanCache.getOrNull(index)
          ?: line.displayWords.any { it.word.romanWord.isNotBlank() }
      val wordRomanHeight =
        if (isWordByWordLine(line) && hasWordRoman) {
          measureWordRomanLineHeight(contentMetrics.main.textSize, mainPaint.typeface)
        } else {
          0f
        }
      if (contentMetrics.main.positionedWords.isNotEmpty()) {
        val positionedStartX = if (line.isDuet) drawX else contentMetrics.startX
        if (active && enableWordHighlight) {
          drawPositionedWordHighlightLine(
            canvas = canvas,
            positionedWords = contentMetrics.main.positionedWords,
            baseline = top + verticalPadding,
            startX = positionedStartX,
            lineAlpha = lineAlpha,
            currentTimeMs = currentTimeMs,
            lineActive = lineActive,
            index = index,
          )
        } else {
          for (positioned in contentMetrics.main.positionedWords) {
            val wordX = positionedStartX + positioned.x
            val textX = wordX + (positioned.width - positioned.textWidth) * 0.5f
            canvas.drawText(
              positioned.word.word,
              textX,
              top + verticalPadding + positioned.baselineOffset,
              mainPaint,
            )
            // 逐词罗马音（非激活行统一使用 lineAlpha，无 played/unplayed 区分）
            drawRomanBelow(
              canvas,
              positioned.word.romanWord,
              positioned.width,
              wordX,
              top + verticalPadding + positioned.baselineOffset,
              mainPaint.textSize,
              applyAlpha(textColor, lineAlpha),
              applyAlpha(textColor, lineAlpha),
              1f,
              wordKey = positioned.word,
            )
          }
        }
        mainPaint.maskFilter = null
        mainPaint.clearShadowLayer()
        return top + verticalPadding + contentMetrics.main.height + wordRomanHeight
      }
      if (contentMetrics.main.staticLines.isNotEmpty()) {
        val layerSaveCount =
          beginStaticAlphaLayer(
            canvas = canvas,
            top = top,
            bottom = top + verticalPadding + contentMetrics.main.height,
            lineAlpha = lineAlpha,
            // H-4: 仅模糊时保留离屏层(maskFilter 与层 alpha 协同),否则烤 paint alpha 即可
            enabled = staticLayerEnabled,
          )
        var lineBaseline = baseline
        for ((lineIndex, text) in contentMetrics.main.staticLines.withIndex()) {
          val lineWidth =
            contentMetrics.main.staticLineWidths?.getOrNull(lineIndex)
              ?: mainPaint.measureText(text)
          // 修复：确保右对唱行的绘制位置不会为负，避免文本超出屏幕左侧
          val lineX =
            if (line.isDuet) {
              (contentMetrics.endX - lineWidth).coerceAtLeast(0f)
            } else {
              contentMetrics.startX
            }
          canvas.drawText(text, lineX, lineBaseline, mainPaint)
          lineBaseline += contentMetrics.main.height / contentMetrics.main.staticLines.size
        }
        restoreStaticAlphaLayer(canvas, layerSaveCount)
        mainPaint.maskFilter = null
        mainPaint.clearShadowLayer()
        return top + verticalPadding + contentMetrics.main.height
      }

      if (active && enableWordHighlight && isWordByWordLine(line)) {
        drawWordHighlightLine(
          canvas = canvas,
          line = line,
          baseline = baseline,
          lineAlpha = lineAlpha,
          currentTimeMs = currentTimeMs,
          startX = contentMetrics.startX,
          contentWidth = contentMetrics.contentWidth,
          lineActive = lineActive,
          lineIndex = index,
        )
        mainPaint.maskFilter = null
        mainPaint.clearShadowLayer()
        return top + verticalPadding + contentMetrics.main.height + wordRomanHeight
      }

      val layerSaveCount =
        beginStaticAlphaLayer(
          canvas = canvas,
          top = top,
          bottom = top + verticalPadding + contentMetrics.main.height,
          lineAlpha = lineAlpha,
          // H-4: 仅模糊时保留离屏层(maskFilter 与层 alpha 协同),否则烤 paint alpha 即可
          enabled = staticLayerEnabled,
        )
      canvas.drawText(line.mainText, drawX, baseline, mainPaint)
      restoreStaticAlphaLayer(canvas, layerSaveCount)
      mainPaint.maskFilter = null
      mainPaint.clearShadowLayer()
      return top + verticalPadding + contentMetrics.main.height
    }

    private fun beginStaticAlphaLayer(
      canvas: Canvas,
      top: Float,
      bottom: Float,
      lineAlpha: Float,
      enabled: Boolean,
    ): Int? {
      if (!enabled) return null
      // 对齐 Web 静态行 mask：先按原色栅格化，再整体乘上行透明度，避免把 alpha 直接烤进 Paint。
      val alpha = (lineAlpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
      return canvas.saveLayerAlpha(0f, top, viewportWidth.toFloat(), bottom, alpha)
    }

    private fun restoreStaticAlphaLayer(
      canvas: Canvas,
      saveCount: Int?,
    ) {
      if (saveCount == null) return
      canvas.restoreToCount(saveCount)
    }

    private fun drawPositionedWordHighlightLine(
      canvas: Canvas,
      positionedWords: List<PositionedWord>,
      baseline: Float,
      startX: Float,
      lineAlpha: Float,
      currentTimeMs: Long,
      lineActive: Boolean = true,
      index: Int = -1,
    ) {
      // 对齐 engine/index.ts：最终可见透明度 = 行亮度 × 单词 mask alpha
      // 亮部使用 lineAlpha，暗部固定使用 inactiveAlpha，避免已播区域被额外压暗成灰
      val playedColor = applyAlpha(textColor, lineAlpha)
      val unplayedColor = applyAlpha(textColor, (inactiveAlpha * lineAlpha).coerceAtMost(1f))
      // 对齐 Web 引擎：fadeWidth = clientHeight × fadeRatio，clientHeight 含行高（约 fontSize × 1.2）
      // Kotlin 使用 fontMetrics 的 descent - ascent 作为实际文本高度，等价于 CSS 的 contentHeight
      val fm = mainFontMetrics()
      val textHeight = fm.descent - fm.ascent
      val fadeMinPx = max(1f * density, textHeight * wordFadeWidth)

      // char 强调动画的 chunk 级时序数据（对齐 AMLL initEmphasizeAnimation）：
      // du/amount/blur 按 chunk merged 时长，charDelay 按 chunk 内全局字符序
      val lastChunkId = positionedWords.lastOrNull()?.chunkId
      // QW-2: 复用成员 HashMap 避免逐帧分配
      val chunkMergedStartMs = tmpChunkMergedStartMs
      val chunkMergedEndMs = tmpChunkMergedEndMs
      val chunkCharCounts = tmpChunkCharCounts
      chunkMergedStartMs.clear()
      chunkMergedEndMs.clear()
      chunkCharCounts.clear()
      for (positioned in positionedWords) {
        val startMs = chunkMergedStartMs[positioned.chunkId]
        if (startMs == null || positioned.word.startTime < startMs) {
          chunkMergedStartMs[positioned.chunkId] = positioned.word.startTime
        }
        val endMs = chunkMergedEndMs[positioned.chunkId]
        if (endMs == null || positioned.word.endTime > endMs) {
          chunkMergedEndMs[positioned.chunkId] = positioned.word.endTime
        }
        chunkCharCounts[positioned.chunkId] =
          (chunkCharCounts[positioned.chunkId] ?: 0) + positioned.word.emphasizeCharCount
      }
      val chunkCharCursor = tmpChunkCharCursor
      chunkCharCursor.clear()

      // 对齐 AMLL 遮罩关键帧：扫光段逐词独立时间窗推进（computeSegmentTravel），
      // 行内时窗乱序、重叠的词互不干扰；长音/拖腔不做整行或整 chunk 合并，
      // 每个词元各在自己的时窗内匀速扫过自身宽度，词间隙自然停顿
      val lineStartMs = lyricLines.getOrNull(index)?.startTime ?: 0L
      val segmentCapacity =
        positionedWords.sumOf { positioned ->
          if (positioned.word.ruby.isEmpty()) 1 else positioned.word.rubyCharCount.coerceAtLeast(1)
        }
      beginMaskSweep(positionedWords.size, segmentCapacity)
      for (wordIndex in positionedWords.indices) {
        val positioned = positionedWords[wordIndex]
        recordMaskSweepWord(
          wordIndex = wordIndex,
          word = positioned.word,
          wordX = startX + positioned.x,
          width = positioned.width,
          startMs = positioned.word.startTime,
          endMs = positioned.word.endTime,
          rubySpans = positioned.word.ruby,
        )
      }

      for ((wordIndex, positioned) in positionedWords.withIndex()) {
        val word = positioned.word
        val progress =
          AndroidLyricTimeline.resolveWordProgress(
            word.startTime,
            word.endTime,
            currentTimeMs,
          )
        val duration = max(1L, word.endTime - word.startTime)
        val elapsed = currentTimeMs - word.startTime
        val wordX = startX + positioned.x
        val drawX = wordX + (positioned.width - positioned.textWidth) * 0.5f
        val drawBaseline = baseline + positioned.baselineOffset
        // 对齐 Web：渐变带位置由该段自身独立行程推导（computeSegmentTravel），
        // 段行程只依赖自身时间窗，行内时窗乱序、重叠的词互不干扰
        val segIndex = resolveSweepSegIndex(wordIndex, currentTimeMs)
        val segmentTravel =
          AndroidLyricTimeline.computeSegmentTravel(
            startMs = sweepSegStarts[segIndex],
            endMs = sweepSegEnds[segIndex],
            currentTimeMs = currentTimeMs,
            amount = sweepSegWidths[segIndex] + fadeMinPx,
            lineStartMs = lineStartMs,
          )
        val gradientStartX =
          AndroidLyricTimeline.resolveWordGradientStartX(
            wordX = sweepSegFirstX[segIndex],
            fadeWidth = fadeMinPx,
            segmentTravel = segmentTravel,
          )
        val shader =
          resolveWordGradient(
            gradientStartX,
            wordX,
            positioned.width,
            fadeMinPx,
            playedColor,
            unplayedColor,
          )
        mainPaint.shader = null
        if (enableFloatAnimation || enableEmphasizeEffect) {
          val chunkStartMs = chunkMergedStartMs[positioned.chunkId] ?: word.startTime
          drawEmphasizeWord(
            canvas = canvas,
            word = word,
            wordWidth = positioned.textWidth,
            x = drawX,
            baseline = drawBaseline,
            playedColor = playedColor,
            unplayedColor = unplayedColor,
            progress = progress,
            fadeMinPx = fadeMinPx,
            duration = duration,
            elapsed = elapsed,
            chunkElapsedMs = currentTimeMs - chunkStartMs,
            chunkDurationMs =
              (
                chunkMergedEndMs[positioned.chunkId]
                  ?: word.endTime
              ) - chunkStartMs,
            chunkCharIndexBase = chunkCharCursor[positioned.chunkId] ?: 0,
            chunkCharCount = (chunkCharCounts[positioned.chunkId] ?: 1).coerceAtLeast(1),
            isLastChunk = positioned.chunkId == lastChunkId,
            isBG = false,
            lineActive = lineActive,
            forceCharEmphasis = positioned.chunkShouldEmphasize,
            lineIndex = index,
            highlightShader = shader,
          )
        } else {
          // 复用 shader 的 base 平移已带扫光位置，不能 setLocalMatrix(null)；仅 solid shader 才清矩阵
          if (shader !== reusableWordShader) shader.setLocalMatrix(null)
          // drawText + 复用渐变是单次 GPU pass；位图 + DST_IN 需 saveLayer 离屏，每帧每词一次离屏反而更慢
          mainPaint.shader = shader
          mainPaint.color = Color.WHITE
          canvas.drawText(word.word, drawX, drawBaseline, mainPaint)
          drawRomanBelow(
            canvas,
            word.romanWord,
            positioned.width,
            wordX,
            drawBaseline,
            mainPaint.textSize,
            playedColor,
            unplayedColor,
            progress,
            fadeMinPx,
            shader,
            wordKey = word,
          )
          drawRubyAbove(
            canvas,
            word,
            wordX,
            positioned.width,
            drawBaseline,
            mainPaint.textSize,
            shader,
          )
        }
        chunkCharCursor[positioned.chunkId] =
          (chunkCharCursor[positioned.chunkId] ?: 0) + word.emphasizeCharCount
      }
      mainPaint.shader = null
    }

    private fun drawWordHighlightLine(
      canvas: Canvas,
      line: NativeLyricLine,
      baseline: Float,
      lineAlpha: Float,
      currentTimeMs: Long,
      startX: Float,
      contentWidth: Float,
      lineActive: Boolean = true,
      lineIndex: Int = -1,
    ) {
      val displayWords = line.displayWords
      if (displayWords.isEmpty()) {
        canvas.drawText(line.mainText, startX, baseline, mainPaint)
        return
      }
      // QW-2: 复用成员 FloatArray 避免逐帧分配
      ensureReusableFloats(displayWords.size)
      val textWidths = tmpTextWidths
      val widths = tmpWidths
      val spaceWidth = mainPaint.measureText(" ")
      val hasWordRoman = displayWords.any { it.word.romanWord.isNotBlank() }
      val romanPadding = if (hasWordRoman) mainPaint.textSize * 0.15f else 0f
      if (hasWordRoman) {
        subPaint.textSize = mainPaint.textSize * 0.5f
        // 词级罗马音在 Web 端位于主行 DOM 内，继承行字体而非全局副歌词字体
        subPaint.typeface = mainPaint.typeface
      }
      val hasWordRuby = displayWords.any { it.word.ruby.isNotEmpty() }
      var totalWidth = 0f
      val widthCache = wordTextWidthByFont.getOrPut(mainPaint.typeface) { HashMap() }
      for (i in displayWords.indices) {
        val word = displayWords[i].word
        textWidths[i] =
          widthCache[word.word]
            ?: mainPaint.measureText(word.word).also { widthCache[word.word] = it }
        val romanWidth =
          if (hasWordRoman) {
            romanWordWidthCache[word]
              ?: subPaint
                .measureText(
                  word.romanWord.takeIf { it.isNotBlank() } ?: "\u00A0",
                ).also { width -> romanWordWidthCache[word] = width }
          } else {
            0f
          }
        var width = max(textWidths[i], romanWidth + romanPadding)
        if (hasWordRuby && word.ruby.isNotEmpty()) {
          width = max(width, measureRubyTextWidth(word, mainPaint.textSize))
        }
        widths[i] = width
        totalWidth += width
        if (displayWords[i].leadingSpace && i > 0) totalWidth += spaceWidth
      }

      // 修复：确保右对唱行的起始位置不会为负，避免文本超出屏幕左侧
      var x = if (line.isDuet) (startX + contentWidth - totalWidth).coerceAtLeast(0f) else startX
      // 对齐 engine/index.ts：最终可见透明度 = 行亮度 × 单词 mask alpha
      // 亮部使用 lineAlpha，暗部固定使用 inactiveAlpha，避免已播区域被额外压暗成灰
      val playedColor = applyAlpha(textColor, lineAlpha)
      val unplayedColor = applyAlpha(textColor, (inactiveAlpha * lineAlpha).coerceAtMost(1f))
      // 对齐 Web 引擎：fadeWidth = clientHeight × fadeRatio，clientHeight 含行高（约 fontSize × 1.2）
      // Kotlin 使用 fontMetrics 的 descent - ascent 作为实际文本高度，等价于 CSS 的 contentHeight
      val fm = mainFontMetrics()
      val textHeight = fm.descent - fm.ascent
      val fadeMinPx = max(1f * density, textHeight * wordFadeWidth)

      val wordXPositions = tmpWordXPositions
      var pos = x
      for (i in displayWords.indices) {
        if (displayWords[i].leadingSpace && i > 0) pos += spaceWidth
        wordXPositions[i] = pos
        pos += widths[i]
      }

      // char 强调动画的 chunk 级时序数据（对齐 AMLL initEmphasizeAnimation）：
      // du/amount/blur 按 chunk merged 时长，charDelay 按 chunk 内全局字符序
      val lastChunkId = displayWords.lastOrNull()?.chunkId
      // QW-2: 复用成员 HashMap 避免逐帧分配
      val chunkMergedStartMs = tmpChunkMergedStartMs
      val chunkMergedEndMs = tmpChunkMergedEndMs
      val chunkCharCounts = tmpChunkCharCounts
      chunkMergedStartMs.clear()
      chunkMergedEndMs.clear()
      chunkCharCounts.clear()
      for (i in displayWords.indices) {
        val displayWord = displayWords[i]
        val startMs = chunkMergedStartMs[displayWord.chunkId]
        if (startMs == null || displayWord.word.startTime < startMs) {
          chunkMergedStartMs[displayWord.chunkId] = displayWord.word.startTime
        }
        val endMs = chunkMergedEndMs[displayWord.chunkId]
        if (endMs == null || displayWord.word.endTime > endMs) {
          chunkMergedEndMs[displayWord.chunkId] = displayWord.word.endTime
        }
        chunkCharCounts[displayWord.chunkId] =
          (chunkCharCounts[displayWord.chunkId] ?: 0) + displayWord.word.emphasizeCharCount
      }
      val chunkCharCursor = tmpChunkCharCursor
      chunkCharCursor.clear()

      // 对齐 AMLL 遮罩关键帧：扫光段逐词独立时间窗推进（computeSegmentTravel），
      // 行内时窗乱序、重叠的词互不干扰；长音/拖腔不做整行或整 chunk 合并，
      // 每个词元各在自己的时窗内匀速扫过自身宽度，词间隙自然停顿
      val lineStartMs = line.startTime
      val segmentCapacity =
        displayWords.sumOf { displayWord ->
          if (displayWord.word.ruby.isEmpty()) 1 else displayWord.word.rubyCharCount.coerceAtLeast(1)
        }
      beginMaskSweep(displayWords.size, segmentCapacity)
      for (wordIndex in displayWords.indices) {
        val displayWord = displayWords[wordIndex]
        recordMaskSweepWord(
          wordIndex = wordIndex,
          word = displayWord.word,
          wordX = wordXPositions[wordIndex],
          width = widths[wordIndex],
          startMs = displayWord.word.startTime,
          endMs = displayWord.word.endTime,
          rubySpans = displayWord.word.ruby,
        )
      }

      for (i in displayWords.indices) {
        val displayWord = displayWords[i]
        val wordX = wordXPositions[i]
        val word = displayWord.word
        val wordWidth = widths[i]
        val textWidth = textWidths[i]
        val drawX = wordX + (wordWidth - textWidth) * 0.5f
        val progress =
          AndroidLyricTimeline.resolveWordProgress(
            word.startTime,
            word.endTime,
            currentTimeMs,
          )
        mainPaint.shader = null
        val duration = max(1L, word.endTime - word.startTime)
        val elapsed = currentTimeMs - word.startTime
        // 对齐 Web：渐变带位置由该段自身独立行程推导（computeSegmentTravel），
        // 段行程只依赖自身时间窗，行内时窗乱序、重叠的词互不干扰
        val segIndex = resolveSweepSegIndex(i, currentTimeMs)
        val segmentTravel =
          AndroidLyricTimeline.computeSegmentTravel(
            startMs = sweepSegStarts[segIndex],
            endMs = sweepSegEnds[segIndex],
            currentTimeMs = currentTimeMs,
            amount = sweepSegWidths[segIndex] + fadeMinPx,
            lineStartMs = lineStartMs,
          )
        val gradientStartX =
          AndroidLyricTimeline.resolveWordGradientStartX(
            wordX = sweepSegFirstX[segIndex],
            fadeWidth = fadeMinPx,
            segmentTravel = segmentTravel,
          )
        val shader =
          resolveWordGradient(
            gradientStartX,
            wordX,
            wordWidth,
            fadeMinPx,
            playedColor,
            unplayedColor,
          )

        if (enableFloatAnimation || enableEmphasizeEffect) {
          val chunkStartMs = chunkMergedStartMs[displayWord.chunkId] ?: word.startTime
          drawEmphasizeWord(
            canvas = canvas,
            word = word,
            wordWidth = textWidth,
            x = drawX,
            baseline = baseline,
            playedColor = playedColor,
            unplayedColor = unplayedColor,
            progress = progress,
            fadeMinPx = fadeMinPx,
            duration = duration,
            elapsed = elapsed,
            chunkElapsedMs = currentTimeMs - chunkStartMs,
            chunkDurationMs =
              (
                chunkMergedEndMs[displayWord.chunkId]
                  ?: word.endTime
              ) - chunkStartMs,
            chunkCharIndexBase = chunkCharCursor[displayWord.chunkId] ?: 0,
            chunkCharCount = (chunkCharCounts[displayWord.chunkId] ?: 1).coerceAtLeast(1),
            isLastChunk = displayWord.chunkId == lastChunkId,
            isBG = line.isBG,
            lineActive = lineActive,
            forceCharEmphasis = displayWord.chunkShouldEmphasize,
            lineIndex = lineIndex,
            highlightShader = shader,
          )
        } else {
          // 复用 shader 的 base 平移已带扫光位置，不能 setLocalMatrix(null)；仅 solid shader 才清矩阵
          if (shader !== reusableWordShader) shader.setLocalMatrix(null)
          // drawText + 复用渐变是单次 GPU pass；位图 + DST_IN 需 saveLayer 离屏，每帧每词一次离屏反而更慢
          mainPaint.shader = shader
          mainPaint.color = Color.WHITE
          canvas.drawText(word.word, drawX, baseline, mainPaint)
          drawRomanBelow(
            canvas,
            word.romanWord,
            wordWidth,
            wordX,
            baseline,
            mainPaint.textSize,
            playedColor,
            unplayedColor,
            progress,
            fadeMinPx,
            shader,
            wordKey = word,
          )
          drawRubyAbove(
            canvas,
            word,
            wordX,
            wordWidth,
            baseline,
            mainPaint.textSize,
            shader,
          )
        }
        chunkCharCursor[displayWord.chunkId] =
          (chunkCharCursor[displayWord.chunkId] ?: 0) + word.emphasizeCharCount
      }
      mainPaint.shader = null
    }

    /** QW-2: 确保复用 FloatArray 容量足够,只增不减避免逐帧重分配 */
    private fun ensureReusableFloats(size: Int) {
      if (tmpTextWidths.size < size) tmpTextWidths = FloatArray(size)
      if (tmpWidths.size < size) tmpWidths = FloatArray(size)
      if (tmpWordXPositions.size < size) tmpWordXPositions = FloatArray(size)
    }

    /** H-1: 帧级缓存的 mainPaint.fontMetrics,textSize/typeface 变化时重取 */
    private fun mainFontMetrics(): Paint.FontMetrics {
      if (cachedMainFmTextSize != mainPaint.textSize || cachedMainFmTypeface !== mainPaint.typeface) {
        mainFontMetricsCache = mainPaint.fontMetrics
        cachedMainFmTextSize = mainPaint.textSize
        cachedMainFmTypeface = mainPaint.typeface
      }
      return mainFontMetricsCache
    }

    /** H-1: 帧级缓存的 subPaint.fontMetrics,textSize/typeface 变化时重取 */
    private fun subFontMetrics(): Paint.FontMetrics {
      if (cachedSubFmTextSize != subPaint.textSize || cachedSubFmTypeface !== subPaint.typeface) {
        subFontMetricsCache = subPaint.fontMetrics
        cachedSubFmTextSize = subPaint.textSize
        cachedSubFmTypeface = subPaint.typeface
      }
      return subFontMetricsCache
    }

    /** H-1: 帧级缓存的 rubyPaint.fontMetrics,textSize/typeface 变化时重取 */
    private fun rubyFontMetrics(): Paint.FontMetrics {
      if (cachedRubyFmTextSize != rubyPaint.textSize || cachedRubyFmTypeface !== rubyPaint.typeface) {
        rubyFontMetricsCache = rubyPaint.fontMetrics
        cachedRubyFmTextSize = rubyPaint.textSize
        cachedRubyFmTypeface = rubyPaint.typeface
      }
      return rubyFontMetricsCache
    }

    /**
     * 开始一轮遮罩扫光段的构建：确保复用缓冲容量并清空段计数，容量只增不减，避免逐帧分配。
     * @param wordCount - 本行词数
     * @param segmentCapacity - 预留的扫光段总容量（注音词拆字符子段后可能超过词数）
     */
    private fun beginMaskSweep(
      wordCount: Int,
      segmentCapacity: Int = wordCount,
    ) {
      if (sweepWordSegIndex.size < wordCount) {
        sweepWordSegIndex = IntArray(max(wordCount, 16))
      }
      if (sweepWordSegLast.size < wordCount) {
        sweepWordSegLast = IntArray(max(wordCount, 16))
      }
      // 注音词会被拆成逐字符子段，段数可能超过词数，容量按调用方传入的总段数预留
      if (sweepSegFirstX.size < segmentCapacity) {
        val capacity = max(segmentCapacity, 16)
        sweepSegFirstX = FloatArray(capacity)
        sweepSegWidths = FloatArray(capacity)
        sweepSegStarts = LongArray(capacity)
        sweepSegEnds = LongArray(capacity)
      }
      sweepSegCount = 0
    }

    /**
     * 把一个词登记为独立扫光段：对齐 AMLL generateWebAnimationBasedMaskImage，
     * 遮罩主时间线按歌词文件词逐词推进（每段用词自己的时窗与宽度，渐变带在词间按各自速度衔接），
     * chunk 只作用于强调时序，不合并扫光段。
     * 含注音的词对齐 AMLL ruby 分支：拆成逐字符子段，每字符独立推进遮罩。
     *
     * @param wordIndex - 词在行内的序号
     * @param word - 当前词对象，用作注音扫掠时间窗的缓存键
     * @param wordX - 词文本左缘 x（行坐标）
     * @param width - 词布局宽度
     * @param startMs - 词开始时间（毫秒）
     * @param endMs - 词结束时间（毫秒）
     * @param rubySpans - 词的注音分段，空表示普通词
     */
    private fun recordMaskSweepWord(
      wordIndex: Int,
      word: NativeLyricWord,
      wordX: Float,
      width: Float,
      startMs: Long,
      endMs: Long,
      rubySpans: List<NativeLyricSpan> = emptyList(),
    ) {
      if (rubySpans.isEmpty()) {
        val segIndex = appendSweepSegment(wordX, width, startMs, endMs)
        sweepWordSegIndex[wordIndex] = segIndex
        sweepWordSegLast[wordIndex] = segIndex
        return
      }
      // 对齐 AMLL ruby 分支：第 j 字符取 rubySegment[min(j, last)] 的时间窗，
      // 每字符宽度 = 词宽 / 注音字符数，子段时间强制单调递增以适配整行行程模型
      val charCount = rubySpans.sumOf { span -> span.word.length }.coerceAtLeast(1)
      val perCharWidth = width / charCount
      // 时间窗是词不可变数据的纯函数结果，按词缓存避免逐帧重复构建
      var windows = rubySweepWindowsCache[word]
      if (windows == null) {
        val pairs =
          AndroidLyricTimeline.buildRubySweepWindows(
            rubySpans = rubySpans,
            wordStartMs = startMs,
            wordEndMs = endMs,
            charCount = charCount,
          )
        windows = LongArray(pairs.size * 2)
        for (i in pairs.indices) {
          windows[i * 2] = pairs[i].first
          windows[i * 2 + 1] = pairs[i].second
        }
        rubySweepWindowsCache[word] = windows
      }
      var cursorX = wordX
      var firstSeg = -1
      var lastSeg = -1
      for (i in windows.indices step 2) {
        val segIndex = appendSweepSegment(cursorX, perCharWidth, windows[i], windows[i + 1])
        if (firstSeg < 0) firstSeg = segIndex
        lastSeg = segIndex
        cursorX += perCharWidth
      }
      sweepWordSegIndex[wordIndex] = firstSeg
      sweepWordSegLast[wordIndex] = lastSeg
    }

    /**
     * 追加一个扫光段：每段对应一个文件词（或注音字符子段），
     * 段行程由 computeSegmentTravel 按段自己的时窗独立推进。
     */
    private fun appendSweepSegment(
      wordX: Float,
      width: Float,
      startMs: Long,
      endMs: Long,
    ): Int {
      val count = sweepSegCount
      sweepSegFirstX[count] = wordX
      sweepSegWidths[count] = width
      sweepSegStarts[count] = startMs
      sweepSegEnds[count] = endMs
      sweepSegCount = count + 1
      return count
    }

    /**
     * 取词当前帧应使用的扫光段索引：普通词即唯一段；
     * 注音词拆为多个字符子段，取最后一个已开始的子段，
     * 使渐变带在词内按注音字符逐段推进（对齐 AMLL ruby 遮罩观感）
     */
    private fun resolveSweepSegIndex(
      wordIndex: Int,
      currentTimeMs: Long,
    ): Int {
      val first = sweepWordSegIndex[wordIndex]
      val last = sweepWordSegLast[wordIndex]
      if (first >= last) return first
      var chosen = first
      for (seg in first..last) {
        if (sweepSegStarts[seg] > currentTimeMs) break
        chosen = seg
      }
      return chosen
    }

    private fun normalize(
      min: Float,
      max: Float,
      x: Float,
    ): Float = min(1f, max(0f, (x - min) / (max - min)))

    // H-2: 固定控制点缓动走 256 点查找表,消除逐字符逐帧牛顿迭代;normalize 归一化内联为乘法
    private fun empEasing(x: Float): Float =
      if (x < 0.5f) {
        AndroidLyricEasing.empIn(x * 2f)
      } else {
        1f - AndroidLyricEasing.empOut((x - 0.5f) * 2f)
      }

    private fun clamp01(value: Float): Float = min(1f, max(0f, value))

    private fun easeOutExpo(progress: Float): Float = if (progress >= 1f) 1f else 1f - Math.pow(2.0, (-10f * progress).toDouble()).toFloat()

    private fun easeInOutBack(progress: Float): Float {
      val overshoot = 1.70158f * 1.525f
      return if (progress < 0.5f) {
        val p = 2f * progress
        (p * p * ((overshoot + 1f) * p - overshoot)) / 2f
      } else {
        val p = 2f * progress - 2f
        (p * p * ((overshoot + 1f) * p + overshoot) + 2f) / 2f
      }
    }

    private fun drawInterludeDots(
      canvas: Canvas,
      currentTimeMs: Long,
      drawY: Float,
    ) {
      val state = interludeState
      if (!state.isActive) return
      val totalDuration = (state.endTime - state.startTime).coerceAtLeast(1L).toFloat()
      val elapsed = (currentTimeMs - state.startTime).toFloat()
      if (elapsed < 0f || elapsed > totalDuration) return

      val breatheCycleTarget = 1500f
      val breatheCycle =
        totalDuration / kotlin.math.ceil(totalDuration / breatheCycleTarget).coerceAtLeast(1f)
      var scale = Math.sin(1.5 * Math.PI - (elapsed / breatheCycle) * 2.0).toFloat() / 20f + 1f
      var opacity = 1f
      if (elapsed < 2000f) scale *= easeOutExpo(clamp01(elapsed / 2000f))
      if (elapsed < 500f) {
        opacity = 0f
      } else if (elapsed < 1000f) {
        opacity *= (elapsed - 500f) / 500f
      }
      val remaining = totalDuration - elapsed
      if (remaining < 750f) scale *= 1f - easeInOutBack(clamp01((750f - remaining) / 750f / 2f))
      if (remaining < 375f) opacity *= clamp01(remaining / 375f)
      // 对齐 AMLL：最终缩放系数 0.7
      scale = max(0f, scale) * 0.7f

      // 间奏点直径与歌词字号保持比例（0.6em），与 AMLL 圆点尺寸风格一致
      val dotSize = state.fontSizePx * 0.6f
      val dotRadius = dotSize * 0.5f
      val dotGap = state.fontSizePx * 0.3f
      val contentWidth = dotSize * 3f + dotGap * 2f
      val paddingX = state.fontSizePx
      val paddingY = state.fontSizePx * 0.2f
      val containerHeight = dotSize + paddingY * 2f
      val originX = if (state.alignRight) state.x + contentWidth + paddingX * 2f else state.x
      val originY = drawY + containerHeight * 0.5f
      val activeDuration = max(0f, totalDuration - 750f)

      canvas.save()
      canvas.scale(scale, scale, originX, originY)
      dotsPaint.color = textColor
      for (i in 0 until 3) {
        val delay = activeDuration / 3f * i
        val dotOpacity =
          if (activeDuration <= 0f) {
            opacity
          } else {
            opacity * clamp01(max(0.25f, (((elapsed - delay) * 3f) / activeDuration) * 0.75f))
          }
        dotsPaint.alpha = (dotOpacity * 255f).roundToInt().coerceIn(0, 255)
        val cx = state.x + paddingX + dotRadius + i * (dotSize + dotGap)
        val cy = drawY + paddingY + dotRadius
        canvas.drawCircle(cx, cy, dotRadius, dotsPaint)
      }
      canvas.restore()
    }

    /**
     * 在主歌词下方绘制逐词罗马音（对齐 AMLL romanWord 样式）
     * - 字号为主歌词的 0.5 倍
     * - 居中对齐于主歌词
     * - 对齐 AMLL：使用 LinearGradient 渐变扫光与主歌词同步过渡，而非纯色阈值切换
     * - 不参与字符级 emphasis 缩放，由调用方传入主歌词 baseline 决定垂直位置
     * @param romanWord
     * - 罗马音文本
     * @param wordWidth
     * - 主歌词宽度
     * @param x
     * - 主歌词左边缘 x
     * @param mainBaseline
     * - 主歌词 baseline（已含浮动偏移）
     * @param mainTextSize
     * - 主歌词字号
     * @param playedColor
     * - 已播颜色
     * @param unplayedColor
     * - 未播颜色
     * @param progress
     * - 词进度 0..1
     * @param fadeMinPx
     * - 渐变宽度（与主歌词一致，对齐 AMLL mask 作用于父元素的行为）
     * @param highlightShader
     * - 与主歌词共享的渐变 Shader
     * @param wordKey
     * - 当前歌词词对象，用于复用罗马音宽度
     */
    private fun drawRomanBelow(
      canvas: Canvas,
      romanWord: String,
      wordWidth: Float,
      x: Float,
      mainBaseline: Float,
      mainTextSize: Float,
      playedColor: Int,
      unplayedColor: Int,
      progress: Float,
      fadeMinPx: Float = 0f,
      highlightShader: LinearGradient? = null,
      wordKey: NativeLyricWord? = null,
    ) {
      if (romanWord.isBlank()) return
      val romanSize = mainTextSize * 0.5f
      subPaint.textSize = romanSize
      // 词级罗马音位于主行内，继承行字体（对齐 Web mainDiv 的 :lang 字体），mainPaint.typeface 由 drawMainText 按行设置
      subPaint.typeface = mainPaint.typeface
      subPaint.shader = null
      subPaint.maskFilter = null
      subPaint.clearShadowLayer()
      val romanWidth =
        if (wordKey == null) {
          subPaint.measureText(romanWord)
        } else {
          romanWordWidthCache[wordKey]
            ?: subPaint.measureText(romanWord).also { width ->
              romanWordWidthCache[wordKey] = width
            }
        }
      val romanEndPadding = mainTextSize * 0.15f
      val romanX = x + wordWidth / 2f - (romanWidth + romanEndPadding) / 2f
      val romanY = mainBaseline + mainFontMetrics().descent - subFontMetrics().ascent
      val shader =
        highlightShader
          ?: run {
            val gradientWidth =
              if (fadeMinPx > 0f) {
                fadeMinPx
              } else {
                max(1f * density, romanSize * wordFadeWidth)
              }
            val fadeHalf = gradientWidth * 0.5f
            val totalMaskWidth = romanWidth + gradientWidth
            val startPos = romanX - fadeHalf
            val edgeX = startPos + totalMaskWidth * progress
            resolveWordGradient(
              edgeX - fadeHalf,
              romanX,
              romanWidth,
              gradientWidth,
              playedColor,
              unplayedColor,
            )
          }
      // 复用 shader 的 base 平移已带扫光位置，不能 setLocalMatrix(null)；仅独立/缓存 shader 才清矩阵
      if (shader !== reusableWordShader) shader.setLocalMatrix(null)
      if (wordKey != null) {
        // 音译随 float 逐帧亚像素位移：live drawText 每帧重栅格化 + hinting 取整会轻微抖动，
        // 与主歌词一致走 ALPHA_8 位图 + 双线性采样（对齐 AMLL 合成层语义，栅格化一次）
        val romanBitmap = getOrBuildAlphaRomanBitmap(wordKey, romanWord)
        if (romanBitmap != null) {
          drawAlphaBitmap(
            canvas,
            romanBitmap.bitmap,
            romanX - romanBitmap.leftPad,
            romanY - romanBitmap.baselineY,
            shader,
          )
          subPaint.shader = null
          return
        }
      }
      subPaint.shader = shader
      subPaint.color = Color.WHITE
      canvas.drawText(romanWord, romanX, romanY, subPaint)
      subPaint.shader = null
    }

    private fun measureWordRomanLineHeight(
      mainTextSize: Float,
      typeface: Typeface,
    ): Float {
      subPaint.textSize = mainTextSize * 0.5f
      subPaint.typeface = typeface
      val metrics = subFontMetrics()
      return max(mainTextSize * 0.5f, metrics.descent - metrics.ascent)
    }

    private fun resolveWordGradient(
      gradientStartX: Float,
      wordX: Float,
      wordWidth: Float,
      fadeWidth: Float,
      playedColor: Int,
      unplayedColor: Int,
    ): LinearGradient =
      when {
        gradientStartX + fadeWidth <= wordX -> getSolidWordShader(unplayedColor)
        gradientStartX >= wordX + wordWidth -> getSolidWordShader(playedColor)
        else -> getReusableWordGradient(gradientStartX, fadeWidth, playedColor, unplayedColor)
      }

    /**
     * 复用一个固定 played→unplayed 渐变实例：仅在 fadeWidth/颜色变化时重建（同一行内恒定）， 每帧只更新 localMatrix 把渐变窗口平移到
     * gradientStartX。原实现每帧每词新建 LinearGradient， 是激活行逐词高亮时的主要 GC 来源。
     */
    private fun getReusableWordGradient(
      gradientStartX: Float,
      fadeWidth: Float,
      playedColor: Int,
      unplayedColor: Int,
    ): LinearGradient {
      val shader = reusableWordShader
      if (shader == null ||
        reusableFadeWidth != fadeWidth ||
        reusableFadePlayedColor != playedColor ||
        reusableFadeUnplayedColor != unplayedColor
      ) {
        reusableWordShader =
          LinearGradient(
            0f,
            0f,
            fadeWidth,
            0f,
            playedColor,
            unplayedColor,
            Shader.TileMode.CLAMP,
          )
        reusableFadeWidth = fadeWidth
        reusableFadePlayedColor = playedColor
        reusableFadeUnplayedColor = unplayedColor
      }
      reusableWordShaderMatrix.reset()
      reusableWordShaderMatrix.setTranslate(gradientStartX, 0f)
      reusableWordShader!!.setLocalMatrix(reusableWordShaderMatrix)
      return reusableWordShader!!
    }

    private fun getSolidWordShader(color: Int): LinearGradient =
      solidWordShaderCache.get(color)
        ?: LinearGradient(
          0f,
          0f,
          1f,
          0f,
          color,
          color,
          Shader.TileMode.CLAMP,
        ).also { solidWordShaderCache.put(color, it) }

    /**
     * 测量词注音文本宽度（0.5em 字号），带词级缓存，供布局取 max(主文, 罗马, 注音) 词宽
     */
    private fun measureRubyTextWidth(
      word: NativeLyricWord,
      mainTextSize: Float,
    ): Float =
      rubyWordWidthCache[word]
        ?: run {
          rubyPaint.textSize = mainTextSize * 0.5f
          rubyPaint.typeface = mainPaint.typeface
          rubyPaint.measureText(word.rubyText)
        }.also { width -> rubyWordWidthCache[word] = width }

    /**
     * 绘制词上方注音，对齐 AMLL rubyWord 样式：0.5em 字号、1em 行高、词盒内居中，
     * 注音底边紧贴主文本顶部；复用词的扫光 shader，使注音随遮罩一起着色
     *
     * @param canvas - 目标画布
     * @param word - 当前词
     * @param wordX - 词盒左缘 x
     * @param wordWidth - 词盒宽度，注音在其中居中
     * @param mainBaseline - 主文本 baseline y（已含 float 抬升）
     * @param mainTextSize - 主文本字号（px）
     * @param shader - 词的扫光渐变 shader
     */
    private fun drawRubyAbove(
      canvas: Canvas,
      word: NativeLyricWord,
      wordX: Float,
      wordWidth: Float,
      mainBaseline: Float,
      mainTextSize: Float,
      shader: Shader?,
    ) {
      if (word.ruby.isEmpty()) return
      val rubyText = word.rubyText
      if (rubyText.isEmpty()) return
      rubyPaint.textSize = mainTextSize * 0.5f
      rubyPaint.typeface = mainPaint.typeface
      // 宽度复用布局期缓存(同字号同字体的纯函数结果)，未命中时测量并回填
      val textWidth =
        rubyWordWidthCache[word]
          ?: rubyPaint.measureText(rubyText).also { width -> rubyWordWidthCache[word] = width }
      val x = wordX + (wordWidth - textWidth) * 0.5f
      val rubyFm = rubyFontMetrics()
      val baseline = mainBaseline + mainFontMetrics().ascent - rubyFm.descent
      rubyPaint.shader = shader
      rubyPaint.color = Color.WHITE
      canvas.drawText(rubyText, x, baseline, rubyPaint)
      rubyPaint.shader = null
    }

    private fun drawEmphasizeWord(
      canvas: Canvas,
      word: NativeLyricWord,
      wordWidth: Float,
      x: Float,
      baseline: Float,
      playedColor: Int,
      unplayedColor: Int,
      progress: Float,
      fadeMinPx: Float,
      duration: Long,
      elapsed: Long,
      chunkElapsedMs: Long,
      chunkDurationMs: Long,
      chunkCharIndexBase: Int,
      chunkCharCount: Int,
      isLastChunk: Boolean,
      isBG: Boolean,
      lineActive: Boolean,
      forceCharEmphasis: Boolean = false,
      lineIndex: Int = -1,
      highlightShader: LinearGradient,
    ) {
      // float 抬升逐源词进行：duration = max(1000, 词时长)，对齐 AMLL initFloatAnimation（无末词加成）
      val activeDuration = max(1000f, duration.toFloat())
      val elapsedFloat = elapsed.toFloat()
      // 对齐 Web 引擎 createFloatAnimation 的 ease-out 缓动 (cubic-bezier(0,0,0.58,1))：
      // 前段斜率更缓、整体减速更均匀，避免 ease-out-cubic 前段过陡导致的「窜起后停顿」不顺滑观感
      // 对齐 AMLL 退场动画：行退场时浮动不 snap 到 0，而是按 lineFloatFadeProgress 指数衰减平滑落回
      val rawFloatProgress =
        if (!lineActive) {
          // 退场期间：用衰减进度乘以当前词已达到的浮动量
          val fadeProgress = lineFloatFadeProgress.getOrNull(lineIndex) ?: 0f
          val t = normalize(0f, activeDuration, elapsedFloat)
          AndroidLyricEasing.easeOut58(t) * fadeProgress
        } else {
          val t = normalize(0f, activeDuration, elapsedFloat)
          AndroidLyricEasing.easeOut58(t)
        }
      val baseFloatLift =
        if (enableFloatAnimation) {
          // 用户偏好：浮动高度系数保持 0.07/0.14（比 AMLL 基准 0.05/0.1 再轻微大一点）
          (if (isBG) 0.14f else 0.07f) * mainPaint.textSize * rawFloatProgress
        } else {
          0f
        }

      val shader = highlightShader

      val useCharEmphasis = enableEmphasizeEffect && (forceCharEmphasis || word.shouldEmphasize)
      if (!useCharEmphasis) {
        // 复用 shader 的 base 平移已带扫光位置，不能 setLocalMatrix(null)；仅 solid shader 才清矩阵
        if (shader !== reusableWordShader) shader.setLocalMatrix(null)
        // 整词 ALPHA_8 位图 + shader 直绘：DST_IN 组合（渐变 × 字形alpha），单次 GPU pass 无离屏。
        // float 亚像素位移由双线性采样平滑，不重新光栅化——对齐 AMLL 合成层语义，消除 drawText 重栅格化的 AA 漂移
        val wordBitmap = getOrBuildAlphaWordBitmap(word)
        if (wordBitmap != null) {
          drawAlphaBitmap(
            canvas,
            wordBitmap.bitmap,
            x - wordBitmap.leftPad,
            baseline - baseFloatLift - wordBitmap.baselineY,
            shader,
          )
        } else {
          mainPaint.shader = shader
          mainPaint.color = Color.WHITE
          canvas.drawText(word.word, x, baseline - baseFloatLift, mainPaint)
          mainPaint.shader = null
        }
        mainPaint.clearShadowLayer()
        drawRomanBelow(
          canvas,
          word.romanWord,
          wordWidth,
          x,
          baseline - baseFloatLift,
          mainPaint.textSize,
          playedColor,
          unplayedColor,
          progress,
          fadeMinPx,
          shader,
          wordKey = word,
        )
        drawRubyAbove(
          canvas,
          word,
          x,
          wordWidth,
          baseline - baseFloatLift,
          mainPaint.textSize,
          shader,
        )
        return
      }

      // char 强调动画按 chunk merged 时序，对齐 AMLL initEmphasizeAnimation：
      // du/amount/blur 由 chunk 合并时长（而非单词时长）决定
      var charDuration = max(1000f, chunkDurationMs.toFloat())
      val chunkElapsedFloat = chunkElapsedMs.toFloat()
      var amount = charDuration / 2000f
      amount =
        if (amount > 1f) {
          java.lang.Math
            .sqrt(amount.toDouble())
            .toFloat()
        } else {
          amount * amount * amount
        }
      var blur = charDuration / 3000f
      blur =
        if (blur > 1f) {
          java.lang.Math
            .sqrt(blur.toDouble())
            .toFloat()
        } else {
          blur * blur * blur
        }
      amount *= 0.6f
      blur *= 0.5f

      if (isLastChunk) {
        amount *= 1.6f
        blur *= 1.5f
        // 对齐 AMLL：行末 chunk 的强调持续时间放大 1.2 倍，影响后续 charDelay 和 float 时序
        charDuration *= 1.2f
      }
      amount = min(1.2f, amount)
      blur = min(0.8f, blur)
      // CSS 等效辉光半径在词内恒定（blur 由 charDuration 决定），对齐 AMLL textShadow 的 min(0.3, blur*0.3)em
      val cssGlowRadiusPx = min(0.3f, blur * 0.3f) * mainPaint.textSize
      // σ 校准：CSS 模糊半径 B 对应高斯 σ=B/2，而 Android BlurMaskFilter 入参直接作为 σ 使用，
      // 因此栅格化辉光位图前需将 CSS 等效半径按 glowSigmaPerCssRadius 换算，保证辉光扩散范围与 AMLL Web 观感一致
      val glowSigmaPx = cssGlowRadiusPx * glowSigmaPerCssRadius

      // 按字符绘制 Emphasize（glow 位图随 glyph 一并栅格化）
      val metrics = getEmphasisWordMetrics(word, glowSigmaPx)
      val chars = metrics.chars
      val charWidths = metrics.widths
      var currentX = x

      // 对齐 Web 引擎：useCharEmphasis 路径也使用像素级 shader 渐变
      // shader 坐标基于词绝对坐标，canvas translate+scale 后用 setLocalMatrix 抵消变换
      val inverseMatrix = emphasisInverseMatrix
      // char-emphasis 用无子像素 flag 的 paint：每帧变化的 scale 下子像素光栅化会让 CJK AA 帧间跳变（抖动），
      // 对齐 AMLL matrix3d 合成层「不重光栅化、仅整体变换」的语义
      emphasisPaint.textSize = mainPaint.textSize
      emphasisPaint.typeface = lineMainTypeface(lineIndex)
      emphasisPaint.hinting = Paint.HINTING_OFF

      for (i in chars.indices) {
        val ch = chars[i]
        val cWidth = charWidths[i]

        // 字符延迟按 chunk 内全局字符序分配（跨源词连续），对齐 AMLL characterElements 全序
        val globalCharIndex = chunkCharIndexBase + i
        val charDelay = (charDuration / 2.5f / chunkCharCount) * globalCharIndex
        // 对齐 AMLL：强调动画纯按 chunk 时间轴推进、不做词级门控——同一 chunk 的词（如 "reason" 的 rea/son）
        // 必须共享同一条棉花糖波，否则晚开唱的词会被 clamp 成本高词级的块式起落，词组观感分裂
        val charElapsed = chunkElapsedFloat - charDelay
        val charProgress = normalize(0f, charDuration, charElapsed)

        val transX = empEasing(charProgress)
        val glowLevel = empEasing(charProgress) * blur
        val scale = 1f + transX * 0.1f * amount
        val offsetX =
          -transX *
            0.03f *
            amount *
            (chunkCharCount / 2f - globalCharIndex) *
            mainPaint.textSize
        // 对齐 Web engine emphasize.ts：垂直位移由三部分叠加
        // 1. glow offsetY：-transX * 0.025 * amount（em → px），随 glow 钟形曲线升降
        // 2. emphasize-float 正弦浮动：-sin(x·π) * 0.07em（主行）/ 0.14em（BG 行）
        //    duration = animDu * 1.4，delay = charDelay - 400（AMLL 刻意比 glow 提前 400ms 启动，
        //    与辉光一致不做词级门控，让浮动波沿 chunk 时间轴连续跨越词界）
        // 3. 整词 baseFloatLift：单次 ease-out 抬升（循环外计算）
        val glowOffsetY = -transX * 0.025f * amount * mainPaint.textSize
        val floatDuration = charDuration * 1.4f
        val floatDelay = charDelay - 400f
        val floatElapsed = chunkElapsedFloat - floatDelay
        val floatProgress = normalize(0f, floatDuration, floatElapsed)
        val sinY = Math.sin((floatProgress * Math.PI).toDouble()).toFloat()
        // 用户偏好：浮动高度系数保持 0.07/0.14（比 AMLL 基准 0.05/0.1 再轻微大一点）
        val floatAmplitude = if (isBG) 0.14f else 0.07f
        val floatLift = sinY * floatAmplitude * mainPaint.textSize
        val offsetY = -baseFloatLift + glowOffsetY - floatLift

        canvas.save()
        canvas.translate(currentX + cWidth / 2f + offsetX, baseline + offsetY)
        canvas.scale(scale, scale)

        // 设置 shader 的 localMatrix 为 canvas 变换矩阵的逆矩阵，
        // 使 shader 坐标回到词的绝对坐标系，与非 emphasis 路径行为一致
        // canvas 变换 M = Translate(tx,ty) * Scale(s,s)，逆矩阵 M⁻¹ = Scale(1/s) * Translate(-tx,-ty)
        // 验证：M * M⁻¹ * (x,y) = Translate(tx,ty)*Scale(s,s)*Scale(1/s)*Translate(-tx,-ty)*(x,y) =
        // (x,y)
        val tx = currentX + cWidth / 2f + offsetX
        val ty = baseline + offsetY
        val invScale = if (scale != 0f) 1f / scale else 1f
        inverseMatrix.reset()
        inverseMatrix.postTranslate(-tx, -ty)
        inverseMatrix.postScale(invScale, invScale)

        // 复用 shader 的 base 平移不能丢：合成 base 平移 × canvas 逆矩阵，而非整体覆盖。
        // 否则 setLocalMatrix(inverseMatrix) 会抹掉 getReusableWordGradient 设置的扫光平移。
        val composedLocal =
          if (shader === reusableWordShader) {
            reusableShaderLocalScratch.set(reusableWordShaderMatrix)
            reusableShaderLocalScratch.postConcat(inverseMatrix)
            reusableShaderLocalScratch
          } else {
            inverseMatrix
          }
        shader.setLocalMatrix(composedLocal)
        val glyph = metrics.glyphs[i]
        if (glyph != null && !glyph.bitmap.isRecycled) {
          // 字符锚点在原点（字符中心 + baseline），glyph 位图内字符从 (leftPad, baselineY) 起：
          // 位图左上局部坐标 = (-cWidth/2 - leftPad, -baselineY)
          val charLeft = -cWidth / 2f - glyph.leftPad
          val charTop = -glyph.baselineY

          // 1. 辉光层：drawBitmap(glyph.glowBitmap) + 纯色 paint
          //    预模糊的 ALPHA_8 alpha 掩码（buildAlphaGlyph 时软件 BlurMaskFilter 栅格化）× paint.color
          //    = 白色辉光晕色。硬件 Canvas 直接画，绕开 setShadowLayer 对 drawBitmap 的 API 限制（API 29+ 才生效）
          //    glowBitmap 已外扩 glowPad：位图左上 = 字符位图左上 - (glowPad, glowPad)
          // alpha 由 glowLevel × 当前行 alpha 控制，对齐 AMLL textShadow 的 rgba(255,255,255,glow) alpha
          val glowBmp = glyph.glowBitmap
          if (glowLevel > 0.01f && glowBmp != null && !glowBmp.isRecycled) {
            val currentLineAlpha = Color.alpha(playedColor) / 255f
            val glowAlpha = (glowLevel * currentLineAlpha * 255).toInt().coerceIn(0, 255)
            if (glowAlpha > 0) {
              alphaGlyphPaint.shader = null
              alphaGlyphPaint.color = Color.WHITE
              alphaGlyphPaint.alpha = glowAlpha
              canvas.drawBitmap(
                glowBmp,
                charLeft - glyph.glowPad,
                charTop - glyph.glowPad,
                alphaGlyphPaint,
              )
            }
          }

          // 2. 主字形层：drawBitmap(glyph.bitmap) + paint.shader
          //    ALPHA_8 位图与 paint shader 组合 = 渐变色 × 字形 alpha，单次 GPU pass 无离屏
          //    字形已栅格化一次，scale/translate 由 FILTER_BITMAP 双线性采样平滑——对齐 AMLL matrix3d 不重光栅化
          alphaGlyphPaint.shader = shader
          alphaGlyphPaint.color = Color.WHITE
          alphaGlyphPaint.alpha = 255
          canvas.drawBitmap(glyph.bitmap, charLeft, charTop, alphaGlyphPaint)
          alphaGlyphPaint.shader = null
        } else {
          // OOM 回退路径：glyph 位图构建失败时仅 drawText 绘主字形（保留扫光，辉光残缺）
          emphasisPaint.shader = shader
          emphasisPaint.color = Color.WHITE
          emphasisPaint.maskFilter = null
          emphasisPaint.clearShadowLayer()
          canvas.drawText(ch, -cWidth / 2f, 0f, emphasisPaint)
          emphasisPaint.shader = null
        }
        canvas.restore()

        currentX += cWidth
      }
      // char 循环把复用 shader 的矩阵合成到了末字符的 canvas 逆变换上，
      // 音译/注音绘制在词坐标系（canvas 已 restore，无字符变换），
      // 需恢复 base 平移，否则渐变边界随末字符动画逐帧晃动；solid shader 才清矩阵
      if (shader === reusableWordShader) {
        shader.setLocalMatrix(reusableWordShaderMatrix)
      } else {
        shader.setLocalMatrix(null)
      }
      // 逐词罗马音（跟随 baseFloatLift，不参与字符级缩放）
      drawRomanBelow(
        canvas,
        word.romanWord,
        wordWidth,
        x,
        baseline - baseFloatLift,
        mainPaint.textSize,
        playedColor,
        unplayedColor,
        progress,
        fadeMinPx,
        shader,
        wordKey = word,
      )
      // 逐词注音（跟随 baseFloatLift，不参与字符级 emphasize 变换，对齐 AMLL 结构）
      drawRubyAbove(
        canvas,
        word,
        x,
        wordWidth,
        baseline - baseFloatLift,
        mainPaint.textSize,
        shader,
      )
      // 复位 shader 的 localMatrix，避免影响后续绘制
      mainPaint.shader = null
      mainPaint.clearShadowLayer()
    }

    private fun drawSubTexts(
      canvas: Canvas,
      line: NativeLyricLine,
      subLines: List<String>,
      mainBottom: Float,
      lineAlpha: Float,
      blurRadius: Float,
      contentMetrics: ContentBlockMetrics,
      scale: Float,
      scaleOriginY: Float,
    ) {
      if (subLines.isEmpty()) return

      subPaint.typeface = subTypeface
      // 对齐 Web 引擎 .lp-sub: opacity = pass × 0.3，随 passAlpha 淡出
      subPaint.color = applyAlpha(textColor, lineAlpha * 0.3f)
      subPaint.shader = null
      // 同 drawMainText：阈值 0.5px 以下不设置 BlurMaskFilter
      if (blurRadius > 0.5f) {
        subPaint.maskFilter = getBlurMaskFilter(blurRadius * 0.6f)
      } else {
        subPaint.maskFilter = null
      }

      // 主行与第一副行紧贴（无罗马音时翻译贴近主词）；副行之间保留 0.15em 行主字号盒间距；
      // 副行 line-height 1.2em，字形在行盒内垂直居中（1.5em 会让翻译/罗马音之间空出近一行）。
      // 布局预算见 lineHeightsCache（n × 1.2×subSize + (n-1) × 0.15×mainSize）
      val subGap = contentMetrics.main.textSize * 0.15f
      var subTop = mainBottom
      for ((idx, text) in subLines.withIndex()) {
        val metrics = contentMetrics.subs.getOrNull(idx) ?: continue
        subPaint.textSize = metrics.textSize
        val subFm = subFontMetrics()
        val lineBoxHeight = metrics.textSize * 1.2f
        val contentHeight = subFm.descent - subFm.ascent
        // 使用 measureTextMetrics 换行后的 staticLines 逐行绘制，避免原始长文本单行溢出
        if (metrics.staticLines.isEmpty()) {
          val rawBaseline = subTop + (lineBoxHeight - contentHeight) * 0.5f - subFm.ascent
          val baseline = alignScaledYToPixel(rawBaseline, scale, scaleOriginY)
          val lineWidth = subPaint.measureText(text)
          val drawX =
            if (line.isDuet) {
              // 修复：确保右对唱行的绘制位置不会为负，避免文本超出屏幕左侧
              (contentMetrics.endX - lineWidth).coerceAtLeast(0f)
            } else {
              contentMetrics.startX
            }
          canvas.drawText(text, drawX, baseline, subPaint)
          subTop += lineBoxHeight
        } else {
          for ((lineIndex, lineText) in metrics.staticLines.withIndex()) {
            val rawBaseline = subTop + (lineBoxHeight - contentHeight) * 0.5f - subFm.ascent
            val baseline = alignScaledYToPixel(rawBaseline, scale, scaleOriginY)
            val lineWidth =
              metrics.staticLineWidths?.getOrNull(lineIndex) ?: subPaint.measureText(lineText)
            val drawX =
              if (line.isDuet) {
                // 修复：确保右对唱行的绘制位置不会为负，避免文本超出屏幕左侧
                (contentMetrics.endX - lineWidth).coerceAtLeast(0f)
              } else {
                contentMetrics.startX
              }
            canvas.drawText(lineText, drawX, baseline, subPaint)
            subTop += lineBoxHeight
          }
        }
        subTop += subGap
      }
      subPaint.maskFilter = null
      subPaint.clearShadowLayer()
    }

    private fun alignScaledYToPixel(
      y: Float,
      scale: Float,
      originY: Float,
    ): Float {
      if (scale == 0f) return y.roundToInt().toFloat()
      val screenY = originY + (y - originY) * scale
      return originY + (screenY.roundToInt().toFloat() - originY) / scale
    }

    private fun computeContentBlockMetrics(
      line: NativeLyricLine,
      mainSize: Float,
      subLines: List<String>,
      insets: LineInsets,
      lineTypeface: Typeface,
    ): ContentBlockMetrics {
      mainPaint.textSize = mainSize
      mainPaint.typeface = lineTypeface
      // 手机窄屏（CSS 宽度 <= 600）降低换行阈值到 90% 可用宽度，让短翻译优先单行显示
      val viewportCssPx = viewportCssWidth.takeIf { it > 0f } ?: (viewportWidth / density)
      val isMobile = viewportCssPx <= 600f
      val wrapThreshold = if (isMobile) insets.maxWidth * 0.9f else insets.maxWidth
      val mainMetrics = measureMainTextMetrics(line, mainPaint, insets.maxWidth, wrapThreshold)

      val subBaseSize = max(mainSize * 0.5f, 10f * density)
      subPaint.textSize = subBaseSize
      subPaint.typeface = subTypeface
      val subMetrics =
        subLines.map { text ->
          measureTextMetrics(subPaint, text, insets.maxWidth, wrapThreshold)
        }

      val contentWidth =
        max(
          mainMetrics.width,
          subMetrics.maxOfOrNull { it.width } ?: 0f,
        ).coerceAtMost(insets.maxWidth)
      val startX = if (line.isDuet) viewportWidth - insets.right - contentWidth else insets.left
      return ContentBlockMetrics(
        startX = startX,
        contentWidth = contentWidth,
        maxWidth = insets.maxWidth,
        main = mainMetrics,
        subs = subMetrics,
      )
    }

    /**
     * 对齐 Web renderer.css：
     * - FullPlayerMobile 覆盖 lp-inner: left/right 0.34em
     * - FullPlayerMobile 覆盖 lp-line: left/right 0.34em
     * - lp-line.bg: left/right calc(0.6em / 0.75)，换算后绝对像素仍是基准字号的 0.6em
     * - lp-has-duet: 在行盒子宽度上追加 15%/8% 的 padding-inline-start/end
     */
    private fun computeLineInsets(
      line: NativeLyricLine,
      mainSize: Float,
    ): LineInsets {
      val innerLeft = fontSizePx * 0.34f
      val innerRight = fontSizePx * 0.34f
      val lineLeftRightPadding =
        if (line.isBG) {
          mainSize * (0.6f / 0.75f)
        } else {
          mainSize * 0.34f
        }
      val lineBoxWidth = (viewportWidth - innerLeft - innerRight).coerceAtLeast(mainSize)
      // Web 参考实现用 CSS 媒体查询切 600px，不能在原生侧用 density 反推。
      val viewportCssPx = viewportCssWidth.takeIf { it > 0f } ?: (viewportWidth / density)
      val duetIndent =
        if (hasDuetLines() && !line.isBG) {
          lineBoxWidth * if (viewportCssPx <= 600f) 0.08f else 0.15f
        } else {
          0f
        }

      var leftInset = innerLeft + lineLeftRightPadding
      var rightInset = innerRight + lineLeftRightPadding
      if (duetIndent > 0f) {
        if (line.isDuet) leftInset += duetIndent else rightInset += duetIndent
      }

      return LineInsets(
        left = leftInset,
        right = rightInset,
        maxWidth = (viewportWidth - leftInset - rightInset).coerceAtLeast(mainSize),
      )
    }

    private fun measureTextMetrics(
      paint: Paint,
      text: String,
      maxWidth: Float,
      wrapThreshold: Float = maxWidth,
    ): TextMetrics {
      val originalSize = paint.textSize
      var width = paint.measureText(text)
      // 手机窄屏下使用更保守的换行阈值（如 90% 可用宽度），避免文本贴边；平板保持默认 maxWidth
      if (width > wrapThreshold && maxWidth > 0f) {
        measureWrappedStaticText(paint, text, maxWidth)?.let {
          return it
        }
        // 修复：即使无法换行，也应该将宽度限制为 maxWidth，避免绘制超出屏幕
        width = maxWidth
      }
      val fm = paint.fontMetrics
      val height = fm.descent - fm.ascent
      return TextMetrics(width = width, textSize = originalSize, height = height)
    }

    private fun measureWrappedStaticText(
      paint: Paint,
      text: String,
      maxWidth: Float,
    ): TextMetrics? {
      val units = buildStaticBreakUnits(paint, text)
      if (units.size <= 1) return null
      val breaks = AndroidLyricTimeline.calcBalancedBreaks(units, maxWidth)
      if (breaks.isEmpty()) return null
      val lines = mutableListOf<String>()
      val breakSet = breaks.toSet()
      val builder = StringBuilder()
      for (i in units.indices) {
        if (breakSet.contains(i) && builder.isNotEmpty()) {
          lines += builder.toString().trimEnd()
          builder.clear()
        }
        builder.append(units[i].text)
      }
      if (builder.isNotEmpty()) lines += builder.toString().trimEnd()
      if (lines.isEmpty()) return null

      val fm = paint.fontMetrics
      val lineHeight = fm.descent - fm.ascent
      val lineWidths = FloatArray(lines.size) { index -> paint.measureText(lines[index]) }
      val width = (lineWidths.maxOrNull() ?: 0f).coerceAtMost(maxWidth)
      return TextMetrics(
        width = width,
        textSize = paint.textSize,
        height = lineHeight * lines.size,
        staticLines = lines,
        staticLineWidths = lineWidths,
      )
    }

    private fun buildStaticBreakUnits(
      paint: Paint,
      text: String,
    ): List<StaticBreakUnit> {
      val units = mutableListOf<StaticBreakUnit>()
      var index = 0
      while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val char = String(Character.toChars(codePoint))
        if (char.isBlank()) {
          var end = index + Character.charCount(codePoint)
          while (end < text.length) {
            val next = String(Character.toChars(text.codePointAt(end)))
            if (!next.isBlank()) break
            end += Character.charCount(text.codePointAt(end))
          }
          val segment = text.substring(index, end)
          units += StaticBreakUnit(segment, paint.measureText(segment), isSpace = true)
          index = end
        } else if (AndroidLyricWordSegmentation.isCjkText(char)) {
          units += StaticBreakUnit(char, paint.measureText(char), isSpace = false)
          index += Character.charCount(codePoint)
        } else {
          var end = index + Character.charCount(codePoint)
          while (end < text.length) {
            val next = String(Character.toChars(text.codePointAt(end)))
            if (next.isBlank() || AndroidLyricWordSegmentation.isCjkText(next)) break
            end += Character.charCount(text.codePointAt(end))
          }
          val segment = text.substring(index, end)
          units += StaticBreakUnit(segment, paint.measureText(segment), isSpace = false)
          index = end
        }
      }
      return units
    }

    private fun measureMainTextMetrics(
      line: NativeLyricLine,
      paint: Paint,
      maxWidth: Float,
      wrapThreshold: Float = maxWidth,
    ): TextMetrics {
      if (line.displayWords.isEmpty() || !isWordByWordLine(line)) {
        return measureTextMetrics(paint, line.mainText, maxWidth, wrapThreshold)
      }

      // 有逐词时间轴时，基于 displayWords 生成 positionedWords，确保逐词高亮路径可用。
      measureWrappedWordLayout(
        displayWords = line.displayWords,
        paint = paint,
        maxWidth = maxWidth,
        alignRight = line.isDuet,
      )?.let {
        return it
      }

      return measureTextMetrics(paint, line.mainText, maxWidth, wrapThreshold)
    }

    private fun measureWrappedWordLayout(
      displayWords: List<SegmentedDisplayWord>,
      paint: Paint,
      maxWidth: Float,
      alignRight: Boolean,
    ): TextMetrics? {
      if (displayWords.isEmpty()) return null

      val fm = paint.fontMetrics
      val lineHeight = fm.descent - fm.ascent
      // 逐词罗马音绘制在每行主文下方（baseline + 0.5em），换行时需为每一行预留罗马音高度，
      // 否则上一行的罗马音会落入行间距、与下一行主文重叠。与 lineHeightsCache 的 romanHeight 同步。
      val hasWordRoman = displayWords.any { it.word.romanWord.isNotBlank() }
      val romanGap = if (hasWordRoman) measureWordRomanLineHeight(paint.textSize, paint.typeface) else 0f
      // 对齐 AMLL rubyWord：注音绘制在主文上方（0.5em 字号、1em 行高、词内居中），
      // 含注音的行需在主文上方预留注音高度，否则注音会与上一行主文重叠
      val hasWordRuby = displayWords.any { it.word.ruby.isNotEmpty() }
      val rubyGap = if (hasWordRuby) paint.textSize * 0.5f else 0f
      val spaceWidth = paint.measureText(" ")

      val count = displayWords.size
      val wordTextWidths = FloatArray(count)
      val wordWidths = FloatArray(count)
      val prefixWidth = FloatArray(count + 1)
      var totalWidth = 0f
      val chunkStarts = mutableListOf(0)
      val romanPadding = if (hasWordRoman) paint.textSize * 0.15f else 0f
      if (hasWordRoman) {
        subPaint.textSize = paint.textSize * 0.5f
        subPaint.typeface = paint.typeface
      }

      for (i in 0 until count) {
        val word = displayWords[i].word
        wordTextWidths[i] = paint.measureText(word.word)
        val romanWidth =
          if (hasWordRoman) {
            romanWordWidthCache[word]
              ?: subPaint
                .measureText(
                  word.romanWord.takeIf { it.isNotBlank() } ?: "\u00A0",
                ).also { width -> romanWordWidthCache[word] = width }
          } else {
            0f
          }
        var width = max(wordTextWidths[i], romanWidth + romanPadding)
        if (hasWordRuby && word.ruby.isNotEmpty()) {
          width = max(width, measureRubyTextWidth(word, paint.textSize))
        }
        wordWidths[i] = width
        // 第一词的 leadingSpace 通常为 false，即使为 true 也被忽略
        val extra = if (i > 0 && displayWords[i].leadingSpace) spaceWidth else 0f
        totalWidth += wordWidths[i] + extra
        prefixWidth[i + 1] = totalWidth
        val nextChunkId = displayWords.getOrNull(i + 1)?.chunkId
        if (nextChunkId != null && nextChunkId != displayWords[i].chunkId) {
          chunkStarts += i + 1
        }
      }

      // 任意单词长度如果超过 maxWidth，回退缩放
      for (w in wordWidths) {
        if (w > maxWidth) return null
      }

      // 如果单行能放下，直接排布
      if (totalWidth <= maxWidth) {
        val positioned = mutableListOf<PositionedWord>()
        var x = 0f
        for (i in 0 until count) {
          if (i > 0 && displayWords[i].leadingSpace) x += spaceWidth
          positioned +=
            PositionedWord(
              word = displayWords[i].word,
              width = wordWidths[i],
              textWidth = wordTextWidths[i],
              x = x,
              baselineOffset = rubyGap - fm.ascent,
              chunkId = displayWords[i].chunkId,
              chunkShouldEmphasize = displayWords[i].chunkShouldEmphasize,
            )
          x += wordWidths[i]
        }
        return TextMetrics(
          width = x.coerceAtMost(maxWidth),
          textSize = paint.textSize,
          height = lineHeight + rubyGap,
          positionedWords = positioned,
        )
      }

      // DP 均衡换行
      val chunkCount = chunkStarts.size
      val dp = FloatArray(chunkCount + 1) { Float.POSITIVE_INFINITY }
      val nextBreak = IntArray(chunkCount + 1) { -1 }
      dp[chunkCount] = 0f

      val cjkPenalty = Math.pow((maxWidth * 0.15f).toDouble(), 2.0).toFloat()
      val normalPenalty = Math.pow((maxWidth * 0.5f).toDouble(), 2.0).toFloat()
      val spaceReward = Math.pow((maxWidth * 0.4f).toDouble(), 2.0).toFloat()
      val punctuationReward = Math.pow((maxWidth * 0.6f).toDouble(), 2.0).toFloat()
      val overflowMultiplier = 1000f
      val punctuationRe = AndroidLyricTimeline.PUNCTUATION_RE
      for (chunkIndex in chunkCount - 1 downTo 0) {
        val wordStart = chunkStarts[chunkIndex]
        for (nextChunkIndex in chunkIndex + 1..chunkCount) {
          val wordEnd = if (nextChunkIndex < chunkCount) chunkStarts[nextChunkIndex] else count
          // 行首词块不继承上一词块的 leadingSpace，与 Web 的 block 换行一致。
          val extraSpaceAtStart =
            if (wordStart > 0 && displayWords[wordStart].leadingSpace) spaceWidth else 0f
          val width = prefixWidth[wordEnd] - prefixWidth[wordStart] - extraSpaceAtStart

          val lineCost =
            if (width > maxWidth) {
              if (nextChunkIndex == chunkIndex + 1) {
                Math.pow((width - maxWidth).toDouble(), 2.0).toFloat() * overflowMultiplier
              } else {
                break
              }
            } else {
              Math.pow((maxWidth - width).toDouble(), 2.0).toFloat()
            }

          var breakCost = 0f
          if (nextChunkIndex < chunkCount) {
            val breakWordIndex = chunkStarts[nextChunkIndex]
            val prevText = displayWords[wordEnd - 1].word.word
            breakCost =
              when {
                punctuationRe.containsMatchIn(prevText) -> -punctuationReward
                displayWords[breakWordIndex].leadingSpace -> -spaceReward
                AndroidLyricWordSegmentation.isCjkText(
                  displayWords[breakWordIndex].word.word,
                ) -> cjkPenalty
                else -> normalPenalty
              }
          }

          val totalCost = lineCost + breakCost + dp[nextChunkIndex]
          if (totalCost < dp[chunkIndex]) {
            dp[chunkIndex] = totalCost
            nextBreak[chunkIndex] = nextChunkIndex
          }
        }
      }

      val positioned = mutableListOf<PositionedWord>()
      var widest = 0f
      var currentChunk = 0
      var lineCount = 0
      var baselineOffset = rubyGap - fm.ascent

      while (currentChunk < chunkCount) {
        val nextChunk = nextBreak[currentChunk].takeIf { it > currentChunk } ?: chunkCount
        val currentIdx = chunkStarts[currentChunk]
        val nextIdx = if (nextChunk < chunkCount) chunkStarts[nextChunk] else count
        var x = 0f
        for (i in currentIdx until nextIdx) {
          if (i > currentIdx && displayWords[i].leadingSpace) x += spaceWidth
          positioned +=
            PositionedWord(
              word = displayWords[i].word,
              width = wordWidths[i],
              textWidth = wordTextWidths[i],
              x = x,
              baselineOffset = baselineOffset,
              chunkId = displayWords[i].chunkId,
              chunkShouldEmphasize = displayWords[i].chunkShouldEmphasize,
            )
          x += wordWidths[i]
        }
        widest = max(widest, x)
        currentChunk = nextChunk
        lineCount += 1
        if (currentChunk < chunkCount) {
          baselineOffset += lineHeight + romanGap + rubyGap
        }
      }

      if (alignRight && widest > 0f) {
        var lineStart = 0
        while (lineStart < positioned.size) {
          val baseline = positioned[lineStart].baselineOffset
          var lineEnd = lineStart + 1
          var lineWidth = positioned[lineStart].x + positioned[lineStart].width
          while (lineEnd < positioned.size && positioned[lineEnd].baselineOffset == baseline) {
            lineWidth = positioned[lineEnd].x + positioned[lineEnd].width
            lineEnd += 1
          }
          val lineOffsetX = widest - lineWidth
          if (lineOffsetX > 0f) {
            for (idx in lineStart until lineEnd) {
              val word = positioned[idx]
              positioned[idx] = word.copy(x = word.x + lineOffsetX)
            }
          }
          lineStart = lineEnd
        }
      }

      return TextMetrics(
        width = widest.coerceAtMost(maxWidth),
        textSize = paint.textSize,
        // 行间已为前 lineCount-1 行各预留一段罗马音高度；最后一行的罗马音由 lineHeightsCache 的 romanHeight 承担。
        // 注音高度已在每行上方预留（含最后一行），随 height 一起进入 lineHeightsCache
        height = (lineHeight + rubyGap) * lineCount + romanGap * (lineCount - 1),
        positionedWords = positioned,
      )
    }

    /** 对齐 Web 引擎 line-builder.ts isStatic 判定： 0 词 → 静态；1 词且整首歌无多词行 → 静态；否则逐词。 */
    private fun isWordByWordLine(line: NativeLyricLine): Boolean {
      if (line.words.isEmpty()) return false
      if (line.words.size == 1 && !hasMultiWordLine) return false
      return true
    }

    private fun hasRetainedWordEffect(
      index: Int,
      line: NativeLyricLine,
      currentTimeMs: Long,
    ): Boolean {
      if (!enableWordHighlight || (!enableFloatAnimation && !enableEmphasizeEffect)) return false
      if (!isWordByWordLine(line)) return false
      return currentTimeMs < (lineWordEffectEndCache.getOrNull(index) ?: Long.MIN_VALUE)
    }

    private fun shouldInvalidateForProgress(currentTimeMs: Long): Boolean {
      if (frozen || snapNextLineSpring || lyricLines.isEmpty()) {
        return true
      }
      val activeState = resolveActiveState(currentTimeMs)
      val interlude = cachedInterludeInfo
      if (!playing) {
        // 暂停时词级效果与间奏进度均随播放时间冻结：
        // 仅实时驱动的动画(浮动衰减/行弹簧/模糊渐变/滚动惯性)或布局变化(seek/切行/暂停缩放)需要帧，
        // 跳过相同像素的空转重绘
        return isLayoutStateChanged(activeState, interlude) ||
          hasActiveFloatFade() ||
          lineSpringsActive() ||
          lineBlurAnimating() ||
          kotlin.math.abs(userScrollOffset) > 0.5f ||
          kotlin.math.abs(inertialVelocity) > 0.015f
      }
      return isLayoutStateChanged(activeState, interlude) ||
        hasActiveFrameAnimation(activeState, currentTimeMs) ||
        interlude != null ||
        lineSpringsActive() ||
        lineBlurAnimating() ||
        kotlin.math.abs(userScrollOffset) > 0.5f ||
        kotlin.math.abs(inertialVelocity) > 0.015f
    }

    private fun isLayoutStateChanged(
      activeState: ActiveState,
      interlude: InterludeInfo?,
    ): Boolean =
      !hasCommittedLayoutState ||
        activeState.anchorIndex != lastLayoutAnchorIndex ||
        activeState.activeLineIndices != lastLayoutActiveLineIndices ||
        activeState.bufferedLineIndices != lastLayoutBufferedLineIndices ||
        lyricLines.size != lastLayoutLyricCount ||
        interlude != lastLayoutInterlude

    private fun commitLayoutState(
      activeState: ActiveState,
      interlude: InterludeInfo?,
    ) {
      hasCommittedLayoutState = true
      lastLayoutAnchorIndex = activeState.anchorIndex
      lastLayoutActiveLineIndices = activeState.activeLineIndices.toSet()
      lastLayoutBufferedLineIndices = activeState.bufferedLineIndices.toSet()
      lastLayoutLyricCount = lyricLines.size
      lastLayoutInterlude = interlude
      lastLayoutUserScrollOffset = userScrollOffset
    }

    private fun invalidateCommittedLayoutState() {
      hasCommittedLayoutState = false
    }

    private fun commitViewportState() {
      lastViewportWidth = viewportWidth
      lastViewportHeight = viewportHeight
    }

    private fun wordEffectEndTime(
      displayWord: SegmentedDisplayWord,
      displayWords: List<SegmentedDisplayWord>,
    ): Long {
      val word = displayWord.word
      val duration = max(1000L, word.endTime - word.startTime)
      val emphasizeEnd =
        if (enableEmphasizeEffect && (displayWord.chunkShouldEmphasize || word.shouldEmphasize)
        ) {
          // 对齐 drawEmphasizeWord 的 chunk 级时序：du 与 charDelay 按 chunk 合并时长 + 跨词全局字符序
          // 计算，display word 逐词化后需聚合 chunk 数据才能正确估计该词末字符的动画结束时间
          val chunkWords = displayWords.filter { it.chunkId == displayWord.chunkId }
          val chunkStart = chunkWords.minOf { it.word.startTime }
          val chunkDuration = (chunkWords.maxOf { it.word.endTime } - chunkStart).coerceAtLeast(1L)
          val chunkCharCount = max(1, chunkWords.sumOf { it.word.emphasizeCharCount })
          var charIndexBase = 0
          for (chunkWord in chunkWords) {
            if (chunkWord === displayWord) break
            charIndexBase += chunkWord.word.emphasizeCharCount
          }
          val lastCharIndex = charIndexBase + max(1, word.emphasizeCharCount) - 1
          val activeDuration =
            max(1000f, chunkDuration.toFloat()) *
              if (displayWord.chunkId == displayWords.lastOrNull()?.chunkId) 1.2f else 1f
          val charDelay = activeDuration / 2.5f / chunkCharCount * lastCharIndex
          (chunkStart + charDelay - 400f + activeDuration * 1.4f).toLong()
        } else {
          word.endTime
        }
      val floatEnd = if (enableFloatAnimation) word.startTime + duration else word.endTime
      return max(floatEnd, emphasizeEnd)
    }

    /** 对齐 Web 引擎 snapVisualState：将所有行的 alpha/pass 瞬移到目标值 */
    private fun snapVisualState(activeState: ActiveState) {
      ensureLineVisualStateCapacity()
      val activeIdx = activeState.anchorIndex
      val isUserScrolling = isDragging || scrollResetNano > 0
      for (i in lyricLines.indices) {
        val line = lyricLines[i]
        // 对齐 Web 引擎 activeLineSet：仅当前时间窗行（含 BG 配对）视为激活
        val isActive = activeState.activeLineIndices.contains(i)
        // 对齐 AMLL resolveIsActive：呈现中（高亮未熄灭 + 范围内中间行）保持 0.85 档
        val bufferedTier = !isActive && activeState.isPresented(i)
        val passed =
          hidePassedLines &&
            playing &&
            !isActive &&
            !bufferedTier &&
            !isUserScrolling &&
            isLinePassed(i, activeIdx)
        val targetBright =
          when {
            passed -> 0.0001f
            line.isBG && isActive -> 0.4f
            isActive -> 1f
            line.isBG && bufferedTier -> 0.4f * 0.85f
            bufferedTier -> 0.85f
            line.isBG -> 0.0001f
            else -> inactiveAlpha
          }
        lineBrightAlphas[i] = targetBright
        linePassAlphas[i] = if (passed) 0.0001f else 1f
        lineFloatFadeProgress[i] = if (isActive || bufferedTier) 1f else 0f
      }
    }

    /**
     * 更新逐词浮动衰减进度（对齐 Web 引擎旧行动画反向播放的语义）
     *
     * - 行激活（或逐词效果仍活跃）时浮动量恢复满值
     * - 行失活后 lineFloatFadeProgress 从 1 指数衰减到 0，让逐词浮动平滑落回而非 snap
     */
    private fun updateLineFloatFade(
      index: Int,
      presented: Boolean,
      deltaMs: Float,
    ) {
      ensureLineVisualStateCapacity()
      if (presented) {
        lineFloatFadeProgress[index] = 1f
        return
      }
      // 逐词浮动衰减：从 1 指数衰减到 0
      val deltaSec = deltaMs.coerceAtMost(100f) / 1000f
      val fadeFactor = 1f - Math.exp((-floatFadeSpeed * deltaSec).toDouble()).toFloat()
      val currentFade = lineFloatFadeProgress[index]
      lineFloatFadeProgress[index] =
        if (currentFade < 0.001f) 0f else currentFade + (0f - currentFade) * fadeFactor
    }

    private fun updateLineBrightAlpha(
      index: Int,
      line: NativeLyricLine,
      active: Boolean,
      buffered: Boolean,
      passed: Boolean,
      attackFactor: Float,
      releaseFactor: Float,
    ): Float {
      ensureLineVisualStateCapacity()
      // 对齐 AMLL resolveIsActive/resolveOpacity：激活行全亮、呈现中（唱完未熄灭与
      // 范围内中间行）保持 0.85 档，其余非激活行回落 inactiveAlpha
      val target =
        when {
          passed -> 0.0001f
          line.isBG && active -> 0.4f
          active -> 1f
          line.isBG && buffered -> 0.4f * 0.85f
          buffered -> 0.85f
          line.isBG -> 0.0001f
          else -> inactiveAlpha
        }
      val current = lineBrightAlphas[index]
      // 对齐 Web 引擎：低于半非激活亮度的升亮走 release 速度，其余按目标方向选 attack/release
      val halfInactive = inactiveAlpha * 0.5f
      val effectiveFactor =
        if (!passed && current < halfInactive) {
          releaseFactor
        } else if (target > current) {
          attackFactor
        } else {
          releaseFactor
        }
      val next =
        if (abs(target - current) < 0.001f) {
          target
        } else {
          current + (target - current) * effectiveFactor
        }
      lineBrightAlphas[index] = next
      return next
    }

    private fun updateLinePassAlpha(
      index: Int,
      passed: Boolean,
      releaseFactor: Float,
    ): Float {
      ensureLineVisualStateCapacity()
      val target = if (passed) 0.0001f else 1f
      val current = linePassAlphas[index]
      val next =
        if (abs(target - current) < 0.001f) {
          target
        } else {
          current + (target - current) * releaseFactor
        }
      linePassAlphas[index] = next
      return next
    }

    /**
     * 更新并返回一行的模糊半径（物理像素）
     *
     * 目标档位对齐 AMLL resolveBlurLevel；渐变采用指数逼近（factor 12），
     * 对应 AMLL 中 CSS filter 的 0.4s ease 过渡
     *
     * @param index - 行索引（模糊值的存储槽位）
     * @param distanceIndex - 距离计算所用索引；BG 行传其主行索引（AMLL 模糊为组级，主行与 BG 共享档位）
     * @param activeState - 当前激活状态（提供焦点行与最新高亮行索引）
     * @param active - 行是否为焦点行（对齐 AMLL resolveIsActive 判定）
     * @param isUserScrolling - 用户是否正在触摸滚动
     * @param inViewport - 行是否在视口内（视口外目标直接为最大档位，滚入时从模糊渐入）
     * @param deltaMs - 帧间隔（毫秒）
     * @returns 当前帧模糊半径（物理像素）
     */
    private fun updateLineBlurRadius(
      index: Int,
      distanceIndex: Int,
      activeState: ActiveState,
      active: Boolean,
      isUserScrolling: Boolean,
      inViewport: Boolean,
      deltaMs: Float,
    ): Float {
      ensureLineVisualStateCapacity()
      val viewportCssPx = viewportCssWidth.takeIf { it > 0f } ?: (viewportWidth / density)
      val target =
        AndroidLyricTimeline.resolveBlurTarget(
          enableBlur = enableBlur,
          inViewport = inViewport,
          isUserScrolling = isUserScrolling,
          isFocused = active,
          index = distanceIndex,
          scrollToIndex = activeState.anchorIndex,
          latestIndex = activeState.latestHighlightIndex,
          isNarrowViewport = viewportCssPx <= 1024f,
        )
      val current = lineBlurValues[index]
      val factor = 1f - Math.exp((-12f * (deltaMs.coerceAtMost(100f) / 1000f)).toDouble()).toFloat()
      val next = if (abs(target - current) < 0.01f) target else current + (target - current) * factor
      lineBlurValues[index] = next
      // 模糊是否已逼近目标：仅用于帧调度（渐变期间需要连续帧驱动），稳定后即可停帧；
      // 位图缓存不再等待该标记，渐变期随 blurKey 档位重建
      lineBlurSettled[index] = abs(target - next) < 0.3f
      return next * 1.5f * density
    }

    private fun ensureLineVisualStateCapacity() {
      if (lineBrightAlphas.size == lyricLines.size &&
        linePassAlphas.size == lyricLines.size &&
        lineBlurValues.size == lyricLines.size &&
        lineBlurSettled.size == lyricLines.size &&
        lineFloatFadeProgress.size == lyricLines.size
      ) {
        return
      }
      resetLineVisualStates()
    }

    private fun resetLineVisualStates() {
      lineBrightAlphas =
        FloatArray(lyricLines.size) { index ->
          if (lyricLines.getOrNull(index)?.isBG == true) 0.0001f else inactiveAlpha
        }
      linePassAlphas = FloatArray(lyricLines.size) { 1f }
      lineBlurValues = FloatArray(lyricLines.size)
      lineBlurSettled = BooleanArray(lyricLines.size) { true }
      lineFloatFadeProgress = FloatArray(lyricLines.size)
    }

    private fun resetTimelineState() {
      pendingForceSeek = true
      cachedInterludeInfo = null
      timelineIntervalStartMs = Long.MIN_VALUE
      timelineIntervalEndMs = Long.MAX_VALUE
      timelineStateValid = false
      cachedActiveState = ActiveState(emptySet(), -1)
      persistentAnchorIndex = -1
    }

    /**
     * 重建控制器时间线：仅非 BG 行参与时间推导（BG 行随主行激活，不独立驱动高亮生命周期），
     * 同时记录控制器索引到歌词行索引的映射，供快照回映射使用。
     */
    private fun rebuildControllerTimeline() {
      val mainCount = lyricLines.count { !it.isBG }
      mainLineIndices = IntArray(mainCount)
      val bounds = ArrayList<TimeBounds>(mainCount)
      var cursor = 0
      for (index in lyricLines.indices) {
        if (lyricLines[index].isBG) continue
        mainLineIndices[cursor] = index
        bounds += TimeBounds(startTime = lyricLines[index].startTime, endTime = lyricLines[index].endTime)
        cursor++
      }
      timelineController.setTimeBounds(bounds)
    }

    /**
     * 将控制器快照映射回歌词行索引空间并物化为 ActiveState：
     * playing 展开时复现原 hot 集合的 BG 配对规则（pairEnd 判定），
     * highlighted 展开时保留已唱完行的主 + BG 组合（对齐原 buffered 的视觉保留语义），
     * 锚点解析顺序与原 resolveAnchorIndex 一致：非激活缓冲行最小值 → 激活行最小值 → 上次锚点 → 快照滚动目标。
     */
    private fun buildActiveStateFromSnapshot(
      snapshot: TimelineSnapshot,
      currentTimeMs: Long,
    ): ActiveState {
      val hot = expandPlaybackIndices(snapshot.playingGroups, currentTimeMs)
      val buffered = expandHighlightedIndices(snapshot.highlightedGroups)
      val effectiveBuffered = buffered.filter { it !in hot }
      val anchorIndex =
        if (effectiveBuffered.isNotEmpty()) {
          effectiveBuffered.min()
        } else if (hot.isNotEmpty()) {
          hot.min()
        } else if (persistentAnchorIndex >= 0) {
          persistentAnchorIndex
        } else {
          val mappedScrollTo =
            snapshot.scrollToIndex.takeIf { it >= 0 }?.let { mainLineIndices.getOrNull(it) }
          mappedScrollTo ?: run {
            val futureIdx = lyricLines.indexOfFirst { it.startTime >= currentTimeMs }
            if (futureIdx >= 0) futureIdx else lyricLines.size
          }
        }
      cachedInterludeInfo =
        snapshot.activeInterlude?.let { inter ->
          val prevLineIndex =
            if (inter.anchorLineIndex >= 0) {
              mainLineIndices.getOrElse(inter.anchorLineIndex) { -1 }
            } else {
              -1
            }
          val nextLine = lyricLines.getOrNull(prevLineIndex + 1)
          InterludeInfo(
            startTime = inter.startTime,
            endTime = inter.endTime,
            prevLineIndex = prevLineIndex,
            nextLineIsDuet = nextLine?.isDuet ?: false,
          )
        }
      // 对齐 AMLL visualFrame.latestIndex：最靠后的高亮主行（控制器索引转视图索引），无高亮时回退锚点
      val latestHighlightIndex =
        snapshot.latestHighlightedIndex
          .takeIf { it >= 0 }
          ?.let { mainLineIndices.getOrNull(it) }
          ?: anchorIndex
      return ActiveState(
        activeLineIndices = hot,
        anchorIndex = anchorIndex,
        bufferedLineIndices = buffered,
        latestHighlightIndex = latestHighlightIndex,
      )
    }

    /** 正在播放的主行展开为 hot 集合：跟随主行激活的 BG 行按 pairEnd 规则一并加入 */
    private fun expandPlaybackIndices(
      controllerIndices: Set<Int>,
      currentTimeMs: Long,
    ): Set<Int> {
      val out = LinkedHashSet<Int>(controllerIndices.size * 2)
      for (controllerIndex in controllerIndices) {
        val index = mainLineIndices.getOrElse(controllerIndex) { -1 }
        if (index < 0) continue
        val line = lyricLines.getOrNull(index) ?: continue
        val bgLine = lyricLines.getOrNull(index + 1)
        if (bgLine != null && bgLine.isBG) {
          val nextMainLine = lyricLines.getOrNull(index + 2)
          val nextMainStart = nextMainLine?.startTime ?: Long.MAX_VALUE
          val pairEnd =
            minOf(
              maxOf(line.endTime, nextMainStart),
              maxOf(line.endTime, bgLine.endTime),
            )
          if (line.startTime <= currentTimeMs && pairEnd > currentTimeMs) {
            out.add(index)
            // 对齐 Web 引擎 processTime：主行激活时其 BG 配对行立即加入激活集合。
            // BG 的逐词高亮由 drawLine 中的 currentTimeMs 独立控制，不会提前高亮。
            out.add(index + 1)
          }
        } else {
          out.add(index)
        }
      }
      return out
    }

    /** 高亮主行展开为 buffered 集合：唱完未熄灭的行连同其 BG 伙伴保持激活外观 */
    private fun expandHighlightedIndices(controllerIndices: Set<Int>): Set<Int> {
      val out = LinkedHashSet<Int>(controllerIndices.size * 2)
      for (controllerIndex in controllerIndices) {
        val index = mainLineIndices.getOrElse(controllerIndex) { -1 }
        if (index < 0) continue
        out.add(index)
        if (lyricLines.getOrNull(index + 1)?.isBG == true) out.add(index + 1)
      }
      return out
    }

    private fun rebuildTimelineBoundaries() {
      if (lyricLines.isEmpty()) {
        timelineBoundaries = LongArray(0)
        return
      }
      val boundaries = LongArray(lyricLines.size * 2)
      var count = 0
      for (line in lyricLines) {
        boundaries[count++] = line.startTime
        boundaries[count++] = line.endTime
      }
      boundaries.sort()
      var uniqueCount = 0
      for (index in 0 until count) {
        if (uniqueCount == 0 || boundaries[index] != boundaries[uniqueCount - 1]) {
          boundaries[uniqueCount++] = boundaries[index]
        }
      }
      timelineBoundaries = boundaries.copyOf(uniqueCount)
    }

    private fun buildSubLines(line: NativeLyricLine): List<String> {
      val subLines = mutableListOf<String>()
      if (showTranslation && line.translatedLyric.isNotBlank()) subLines += line.translatedLyric
      // 对齐 AMLL：有逐词罗马音时不重复显示行级 romanLyric
      val hasWordRoman = line.displayWords.any { it.word.romanWord.isNotBlank() }
      if (showRomanization && line.romanLyric.isNotBlank() && !hasWordRoman) {
        subLines += line.romanLyric
      }
      return subLines
    }

    private fun resolveCurrentTimeMs(): Long {
      if (!playing || frozen) return baseTimeMs
      // 丝滑时钟：逐帧直读原生媒体时钟（getLyricPositionMs = ExoPlayer 内部按速率插值的
      // currentPosition，自带 pendingSeek 防回跳），替代 5Hz 推送锚点插值——
      // 消除推送抖动、暂停/缓冲过冲与下一拍拽回造成的全局锯齿；provider 缺失时回退锚点插值
      playbackPositionProvider?.let { provider ->
        return (provider.invoke() + timeOffsetMs).coerceAtLeast(0L)
      }
      val elapsedMs = (System.nanoTime() - anchorNano) / 1_000_000L
      return baseTimeMs + (elapsedMs * playbackRate).toLong()
    }

    /**
     * 取当前时间对应的 ActiveState：同一时间区间内直接复用缓存，
     * 跨区间时驱动控制器 sync 一次（跳转帧带 forceSeek 走 seek 重建路径），
     * 再把快照物化为歌词行索引空间的 ActiveState 与间奏信息。
     */
    private fun resolveActiveState(currentTimeMs: Long): ActiveState {
      if (lyricLines.isEmpty()) {
        resetTimelineState()
        return ActiveState(emptySet(), -1)
      }
      if (timelineStateValid &&
        currentTimeMs >= timelineIntervalStartMs &&
        currentTimeMs < timelineIntervalEndMs
      ) {
        return cachedActiveState
      }
      val forceSeek = pendingForceSeek
      pendingForceSeek = false
      timelineController.sync(currentTimeMs, forceSeek)
      applyPosYSpringPolicyFromTimeline(forceSeek)
      val activeState = buildActiveStateFromSnapshot(timelineController.getSnapshot(), currentTimeMs)
      persistentAnchorIndex = activeState.anchorIndex
      val boundaryIndex = timelineBoundaries.binarySearch(currentTimeMs)
      val nextBoundaryIndex = if (boundaryIndex >= 0) boundaryIndex + 1 else -boundaryIndex - 1
      timelineIntervalStartMs =
        if (boundaryIndex >= 0) {
          timelineBoundaries[boundaryIndex]
        } else {
          timelineBoundaries.getOrNull(nextBoundaryIndex - 1) ?: Long.MIN_VALUE
        }
      timelineIntervalEndMs = timelineBoundaries.getOrNull(nextBoundaryIndex) ?: Long.MAX_VALUE
      timelineStateValid = true
      cachedActiveState = activeState
      return cachedActiveState
    }

    private fun handleProgressJump() {
      // 对齐 Web 引擎 handleSeek：清滚动状态 + 重建激活集合 + calculateLayout(false, noCascade=true)，
      // 所有 seek（点击行、进度条、程序跳转）统一弹簧过渡、全员无级联
      userScrollOffset = 0f
      inertialVelocity = 0f
      scrollResetNano = 0L
      resetTimelineState()
      noCascadeNextLayout = true
      invalidateCommittedLayoutState()
    }

    private fun isNativeClockJump(
      currentTimeMs: Long,
      currentNano: Long,
    ): Boolean {
      if (lastObservedTimeMs == Long.MIN_VALUE || lastObservedNano == 0L) return false
      val elapsedRealMs = ((currentNano - lastObservedNano) / 1_000_000L).coerceAtLeast(0L)
      val elapsedPlaybackMs = currentTimeMs - lastObservedTimeMs
      return elapsedPlaybackMs < -100L || elapsedPlaybackMs > elapsedRealMs * 3L + 2000L
    }

    private fun nextVisualBoundaryMs(
      activeState: ActiveState,
      currentTimeMs: Long,
    ): Long? {
      var nextTimeMs = timelineIntervalEndMs.takeIf { it > currentTimeMs && it != Long.MAX_VALUE }
      if (enableWordHighlight && !enableFloatAnimation && !enableEmphasizeEffect) {
        for (index in activeState.activeLineIndices) {
          val line = lyricLines.getOrNull(index) ?: continue
          if (!isWordByWordLine(line)) continue
          for (displayWord in line.displayWords) {
            val startTime = displayWord.word.startTime
            if (startTime > currentTimeMs && (nextTimeMs == null || startTime < nextTimeMs)) {
              nextTimeMs = startTime
            }
          }
        }
      }
      return nextTimeMs
    }

    private fun isLinePassed(
      index: Int,
      anchorIndex: Int,
    ): Boolean {
      if (anchorIndex < 0) return false
      val line = lyricLines.getOrNull(index) ?: return false
      return if (line.isBG) index - 1 < anchorIndex else index < anchorIndex
    }

    private fun findSeekTarget(tapY: Float): Long? {
      if (lineLayouts.isEmpty()) return null
      val localY = tapY - viewportTop
      val touchPadding = fontSizePx * 0.25f
      var nearestIndex = -1
      var nearestDistance = Float.MAX_VALUE
      for (index in lyricLines.indices) {
        val top = lineHitTops.getOrNull(index) ?: Float.NaN
        val bottom = lineHitBottoms.getOrNull(index) ?: Float.NaN
        if (top.isNaN() || bottom.isNaN()) continue
        val targetIndex = seekTargetLineIndex(index) ?: continue
        val distance =
          if (localY < top - touchPadding) {
            top - touchPadding - localY
          } else if (localY > bottom + touchPadding) {
            localY - bottom - touchPadding
          } else {
            0f
          }
        if (distance < nearestDistance) {
          nearestDistance = distance
          nearestIndex = targetIndex
        }
      }
      if (nearestIndex < 0) {
        val contentY = localY + userScrollOffset
        for (index in lineLayouts.indices) {
          val layout = lineLayouts[index]
          val targetIndex = seekTargetLineIndex(index) ?: continue
          val distance =
            if (contentY < layout.top) {
              layout.top - contentY
            } else if (contentY > layout.top + layout.height) {
              contentY - (layout.top + layout.height)
            } else {
              0f
            }
          if (distance < nearestDistance) {
            nearestDistance = distance
            nearestIndex = targetIndex
          }
        }
      }
      return lyricLines.getOrNull(nearestIndex)?.startTime
    }

    /**
     * 解析点击命中行对应的 seek 目标行
     * @param index - 点击命中矩形对应的歌词行下标
     * @returns seek 目标行下标；BG 行归并到主行，主文本与副歌词均为空的纯时间戳空行
     *          对齐 Web 端内容零尺寸不可点击的行为，返回 null 以免定位到不可见空行
     */
    private fun seekTargetLineIndex(index: Int): Int? {
      val targetIndex = if (lyricLines[index].isBG) (index - 1).coerceAtLeast(0) else index
      val target = lyricLines.getOrNull(targetIndex) ?: return null
      return if (target.mainText.isBlank() && target.translatedLyric.isBlank() && target.romanLyric.isBlank()) {
        null
      } else {
        targetIndex
      }
    }

    private fun invalidateLayoutCache() {
      layoutCacheDirty = true
    }

    private fun ensureLayoutCache() {
      if (layoutCacheDirty) rebuildLayoutCache()
    }

    private fun invalidateAllLineBitmaps() {
      if (lineBitmapCache.size() == 0) return
      lineBitmapCache.evictAll()
    }

    private fun invalidateLineBitmap(index: Int) {
      lineBitmapCache.remove(index)
    }

    private fun rebuildLayoutCache() {
      layoutCacheDirty = false
      // 内容/字号/视口/字体变化都会让位图缓存对应不上新的几何，统一在此清空
      invalidateAllLineBitmaps()
      emphasisWordMetricsCache.evictAll()
      baseGlyphCache.evictAll()
      glowGlyphCache.evictAll()
      alphaWordBitmapCache.evictAll()
      romanWordWidthCache.clear()
      rubyWordWidthCache.clear()
      rubySweepWindowsCache.clear()
      // H-1: 词宽缓存随布局失效（按 Typeface 分桶，逐行字体后桶数会增长）
      wordTextWidthByFont.clear()
      hasDuetLinesCached = lyricLines.any { it.isDuet }
      mainTypeface = createTypeface(fontFamily, fontWeight)
      // 修复：翻译和罗马音也应该使用用户设置的字重，而不是固定 500
      subTypeface = createTypeface(fontFamily, fontWeight)
      if (lyricLines.isEmpty() || viewportWidth <= 0) {
        lineSubLinesCache = emptyArray()
        lineInsetsCache = emptyArray()
        lineMetricsCache = emptyArray()
        lineHeightsCache = FloatArray(0)
        lineHasRomanCache = BooleanArray(0)
        lineMainTypefaces = emptyArray()
        lineWordEffectEndCache = LongArray(0)
        lineBgAboveCache = BooleanArray(0)
        return
      }
      val count = lyricLines.size
      lineMainTypefaces =
        Array(count) { i -> createTypeface(resolveLineFontFamily(lyricLines[i].language), fontWeight) }
      lineSubLinesCache = Array(count) { i -> buildSubLines(lyricLines[i]) }
      lineHasRomanCache =
        BooleanArray(count) { i ->
          lyricLines[i].displayWords.any { it.word.romanWord.isNotBlank() }
        }
      lineInsetsCache =
        Array(count) { i ->
          val mainSize = if (lyricLines[i].isBG) fontSizePx * 0.75f else fontSizePx
          computeLineInsets(lyricLines[i], mainSize)
        }
      lineMetricsCache =
        Array(count) { i ->
          val mainSize = if (lyricLines[i].isBG) fontSizePx * 0.75f else fontSizePx
          computeContentBlockMetrics(
            lyricLines[i],
            mainSize,
            lineSubLinesCache[i],
            lineInsetsCache[i],
            lineMainTypefaces[i],
          )
        }
      lineHeightsCache =
        FloatArray(count) { i ->
          val line = lyricLines[i]
          val mainSize = if (line.isBG) fontSizePx * 0.75f else fontSizePx
          val verticalPadding = mainSize * 0.4f
          // 纯时间戳空行对齐 Web 端内容零高度：仅保留上下 padding，不再撑起一整行主文字号
          val isEmptyLine =
            line.mainText.isBlank() && line.translatedLyric.isBlank() && line.romanLyric.isBlank()
          if (isEmptyLine) {
            verticalPadding * 2f
          } else {
            val subSize = max(mainSize * 0.5f, 10f * density)
            // 对齐 AMLL：有逐词罗马音时增加罗马音行高（font-size: 0.5em, line-height: 1em）
            val romanHeight =
              if (lineHasRomanCache[i]) measureWordRomanLineHeight(mainSize, lineMainTypefaces[i]) else 0f
            // 主行与第一副行紧贴，副行之间各 1 个 0.15em 盒间距；与 drawSubTexts 的行盒/间距保持一致
            val subCount = lineSubLinesCache[i].size
            val subBlockHeight =
              if (subCount == 0) {
                0f
              } else {
                subCount * subSize * 1.2f + (subCount - 1) * mainSize * 0.15f
              }
            verticalPadding +
              lineMetricsCache[i].main.height +
              romanHeight +
              verticalPadding +
              subBlockHeight
          }
        }
      lineWordEffectEndCache =
        LongArray(count) { i ->
          val line = lyricLines[i]
          if (!isWordByWordLine(line)) {
            Long.MIN_VALUE
          } else {
            line.displayWords.maxOfOrNull { word -> wordEffectEndTime(word, line.displayWords) }
              ?: Long.MIN_VALUE
          }
        }
      lineBgAboveCache =
        BooleanArray(count) { index ->
          if (alwaysPostpositionBackground) {
            false
          } else {
            val bg = lyricLines[index]
            val main = lyricLines.getOrNull(index - 1)
            if (!bg.isBG || main == null || main.isBG) {
              false
            } else {
              val bgStart = bg.words.firstOrNull()?.startTime ?: bg.startTime
              val mainStart = main.words.firstOrNull()?.startTime ?: main.startTime
              bgStart < mainStart
            }
          }
        }
    }

    /**
     * 取/建非激活行的位图缓存。blurRadius 量化到整像素作为 key，亚像素差异不触发重建。 位图顶部/底部各预留 pad = ceil(blurRadius)+2
     * 像素吸收模糊光晕，调用方需把绘制 Y 上推同等距离
     */
    private fun getOrBuildLineBitmap(
      index: Int,
      line: NativeLyricLine,
      mainSize: Float,
      subLines: List<String>,
      contentMetrics: ContentBlockMetrics,
      blurRadius: Float,
    ): LineBitmap? {
      // 量化到 2px 档位而非每 1px 一档，减少模糊渐变收敛后因微小漂移触发的重建
      val blurKey = (blurRadius / 2f).roundToInt()
      val existing = lineBitmapCache.get(index)
      if (existing != null && existing.blurKey == blurKey) return existing

      val contentHeight = lineHeightsCache.getOrNull(index)?.coerceAtLeast(1f) ?: return null
      val width = viewportWidth
      if (width <= 0 || contentHeight <= 0f) return null
      val pad = (kotlin.math.ceil(blurRadius).toInt() + 2).coerceAtLeast(0)
      val bmpHeight = (contentHeight.roundToInt() + pad * 2).coerceAtLeast(1)
      val bitmapKb = ((width.toLong() * bmpHeight.toLong() * 4L) / 1024L).coerceAtLeast(1L)
      if (bitmapKb > lineBitmapCacheMaxKb / 3L) return null
      val bitmap =
        try {
          Bitmap.createBitmap(width, bmpHeight, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
          return null
        }
      val bmpCanvas = Canvas(bitmap)

      val mainBottom =
        drawMainText(
          canvas = bmpCanvas,
          line = line,
          top = pad.toFloat(),
          mainSize = mainSize,
          lineAlpha = 1f,
          currentTimeMs = 0L,
          active = false,
          blurRadius = blurRadius,
          contentMetrics = contentMetrics,
        )
      drawSubTexts(
        canvas = bmpCanvas,
        line = line,
        subLines = subLines,
        mainBottom = mainBottom,
        lineAlpha = 1f,
        blurRadius = blurRadius,
        contentMetrics = contentMetrics,
        scale = 1f,
        scaleOriginY = 0f,
      )

      val cache = LineBitmap(bitmap = bitmap, pad = pad, blurKey = blurKey)
      lineBitmapCache.put(index, cache)
      if (lineBitmapCache.get(index) !== cache) return null
      return cache
    }

    private fun recalculateLayouts() {
      if (viewportWidth <= 0 || viewportHeight <= 0 || lyricLines.isEmpty()) {
        lineLayouts = emptyList()
        contentHeight = 0f
        return
      }
      ensureLayoutCache()
      val currentTimeMs = resolveCurrentTimeMs()
      val activeState = resolveActiveState(currentTimeMs)
      val (layouts, frameContentHeight) =
        computeFrameLayouts(
          activeState,
          cachedInterludeInfo,
        )
      lineLayouts = layouts
      contentHeight = frameContentHeight
      // 强制下次 onDraw 重新设置逐行弹簧目标
      invalidateCommittedLayoutState()
    }

    /**
     * CSS 模糊半径到高斯 σ 的换算系数。
     * AMLL 的 textShadow 模糊值是 CSS 半径（对应高斯 σ = 半径 / 2），而 Android BlurMaskFilter
     * 的入参在新版 Skia 中直接作为 σ 使用，因此栅格化辉光位图前必须换算，否则辉光扩散范围约为 Web 观感的两倍。
     */
    private val glowSigmaPerCssRadius = 0.5f

    /**
     * 取/建单字符强调度量（字形位图 + 辉光位图随 glyph 一并栅格化）。
     * 词级缓存按 word.renderCacheKey（实例身份）索引：σ 由该词所在 chunk 的时长/末词标记与当前字号唯一决定，
     * 词实例不变则 σ 不变；字号等配置变更路径已有 emphasisWordMetricsCache.evictAll() 兜底。
     * 词未命中时逐字符走 baseGlyphCache/glowGlyphCache 字符级缓存——同一字符跨词/跨行实例复用，
     * 避免每行激活首帧批量栅格化（尤其软画布 BlurMaskFilter 辉光）造成的掉帧尖峰。
     * @param word - 目标歌词词
     * @param glowSigmaPx - 辉光高斯 σ（已由 CSS 等效半径换算，px）
     * @returns 字符级度量，含每个字符的主字形与预模糊辉光位图
     */
    private fun getEmphasisWordMetrics(
      word: NativeLyricWord,
      glowSigmaPx: Float = 0f,
    ): EmphasisWordMetrics {
      emphasisWordMetricsCache.get(word.renderCacheKey)?.let {
        return it
      }
      val chars = AndroidLyricWordSegmentation.splitGraphemeClusters(word.word)
      val widths = FloatArray(chars.size) { index -> mainPaint.measureText(chars[index]) }
      val sizePx = mainPaint.textSize.roundToInt()
      val typeface = mainPaint.typeface
      val glyphs =
        List(chars.size) { index ->
          val text = chars[index]
          val baseKey = GlyphCacheKey(text, sizePx, 0, typeface)
          val base =
            baseGlyphCache.get(baseKey)
              ?: buildBaseAlphaGlyph(text, widths[index])?.also { baseGlyphCache.put(baseKey, it) }
          when {
            base == null -> null
            glowSigmaPx > 0.25f -> {
              val glowKey = GlyphCacheKey(text, sizePx, glowSigmaPx.roundToInt(), typeface)
              val cachedGlow =
                glowGlyphCache.get(glowKey)
                  ?: buildGlowAlphaGlyph(text, base, glowSigmaPx)?.also { glowGlyphCache.put(glowKey, it) }
              AlphaGlyphBitmap(
                bitmap = base.bitmap,
                leftPad = base.leftPad,
                baselineY = base.baselineY,
                glowBitmap = cachedGlow?.bitmap,
                glowPad = cachedGlow?.pad ?: 0,
              )
            }
            else -> base
          }
        }
      val metrics = EmphasisWordMetrics(chars = chars, widths = widths, glyphs = glyphs)
      emphasisWordMetricsCache.put(word.renderCacheKey, metrics)
      return metrics
    }

    /**
     * 构建单字符 ALPHA_8 主字形位图（只存 alpha 掩码，白色字形）。 栅格化一次，绘制时 drawBitmap + paint.shader 做 DST_IN 组合，单次 GPU
     * pass 无离屏。辉光位图由 [buildGlowAlphaGlyph] 独立构建并缓存。
     * @param text - 待栅格化的单字符文本
     * @param width - 字符排版宽度
     * @returns 字形位图封装，内存不足时返回 null
     */
    private fun buildBaseAlphaGlyph(
      text: String,
      width: Float,
    ): AlphaGlyphBitmap? {
      if (width <= 0f) return null
      val fm = mainPaint.fontMetrics
      val padX = (kotlin.math.ceil(mainPaint.textSize * 0.15f).toInt() + 2)
      val padY = (kotlin.math.ceil(mainPaint.textSize * 0.2f).toInt() + 2)
      val bmpWidth = (kotlin.math.ceil(width).toInt() + padX * 2).coerceAtLeast(1)
      val bmpHeight = (kotlin.math.ceil(fm.descent - fm.ascent).toInt() + padY * 2).coerceAtLeast(1)
      val bitmap =
        try {
          Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ALPHA_8)
        } catch (_: OutOfMemoryError) {
          return null
        }
      val baselineY = padY - fm.ascent
      val glyphPaint =
        Paint(mainPaint).apply {
          shader = null
          maskFilter = null
          color = Color.WHITE
          alpha = 255
          clearShadowLayer()
        }
      Canvas(bitmap).drawText(text, padX.toFloat(), baselineY, glyphPaint)
      return AlphaGlyphBitmap(
        bitmap = bitmap,
        leftPad = padX,
        baselineY = baselineY,
        glowBitmap = null,
        glowPad = 0,
      )
    }

    /**
     * 构建带 BlurMaskFilter 软件模糊的辉光位图（硬件 Canvas 对 drawBitmap 的
     * setShadowLayer/BlurMaskFilter 不生效，辉光只能栅格化时预模糊，每帧 drawBitmap + 动态 alpha）。
     * 入参为高斯 σ：对齐 AMLL textShadow 的 CSS 半径语义（σ = CSS 半径 / 2），调用方需先做 glowSigmaPerCssRadius 换算。
     * @param text - 待栅格化的单字符文本
     * @param base - 主字形位图，辉光在其尺寸上按 3σ 外扩
     * @param glowSigmaPx - 辉光高斯 σ（px）
     * @returns 辉光位图与外扩像素，内存不足时返回 null
     */
    private fun buildGlowAlphaGlyph(
      text: String,
      base: AlphaGlyphBitmap,
      glowSigmaPx: Float,
    ): CachedGlow? {
      // 高斯辉光可见范围约为 ±3σ（覆盖 99.7% 能量），pad 按 3σ 外扩避免截断
      val glowPad = kotlin.math.ceil(glowSigmaPx * 3f).toInt()
      val gw = base.bitmap.width + glowPad * 2
      val gh = base.bitmap.height + glowPad * 2
      val gb =
        try {
          Bitmap.createBitmap(gw, gh, Bitmap.Config.ALPHA_8)
        } catch (_: OutOfMemoryError) {
          return null
        }
      // 软件画布上 BlurMaskFilter 生效：画出带辉光晕的字形 alpha 掩码（入参即高斯 σ）
      val glowPaint =
        Paint(mainPaint).apply {
          shader = null
          color = Color.WHITE
          alpha = 255
          clearShadowLayer()
          maskFilter = BlurMaskFilter(glowSigmaPx, BlurMaskFilter.Blur.NORMAL)
        }
      Canvas(gb).drawText(text, (base.leftPad + glowPad).toFloat(), base.baselineY + glowPad, glowPaint)
      return CachedGlow(bitmap = gb, pad = glowPad)
    }

    /** 取/建整词 ALPHA_8 字形位图（逐词高亮 + float 路径用） */
    private fun getOrBuildAlphaWordBitmap(word: NativeLyricWord): AlphaWordBitmap? {
      alphaWordBitmapCache.get(word.renderCacheKey)?.let {
        return it
      }
      val textWidth = mainPaint.measureText(word.word)
      if (textWidth <= 0f) return null
      val fm = mainPaint.fontMetrics
      val padX = (kotlin.math.ceil(mainPaint.textSize * 0.15f).toInt() + 2)
      val padY = (kotlin.math.ceil(mainPaint.textSize * 0.2f).toInt() + 2)
      val bmpWidth = (kotlin.math.ceil(textWidth).toInt() + padX * 2).coerceAtLeast(1)
      val bmpHeight = (kotlin.math.ceil(fm.descent - fm.ascent).toInt() + padY * 2).coerceAtLeast(1)
      val bitmap =
        try {
          Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ALPHA_8)
        } catch (_: OutOfMemoryError) {
          return null
        }
      val baselineY = padY - fm.ascent
      val glyphPaint =
        Paint(mainPaint).apply {
          shader = null
          maskFilter = null
          color = Color.WHITE
          alpha = 255
          clearShadowLayer()
        }
      Canvas(bitmap).drawText(word.word, padX.toFloat(), baselineY, glyphPaint)
      val cache = AlphaWordBitmap(bitmap = bitmap, leftPad = padX, baselineY = baselineY)
      alphaWordBitmapCache.put(word.renderCacheKey, cache)
      return if (alphaWordBitmapCache.get(word.renderCacheKey) === cache) cache else null
    }

    /**
     * 取/建逐词音译 ALPHA_8 位图：音译跟随主歌词 float 逐帧亚像素位移，
     * live drawText 每帧重栅格化 + hinting 取整会造成轻微视觉抖动，
     * 与主歌词整词位图一致栅格化一次后由 drawBitmap 双线性采样平滑位移。
     * 调用方需已将 subPaint 配置为目标字号（主字号 × 0.5）与行字体
     */
    private fun getOrBuildAlphaRomanBitmap(
      word: NativeLyricWord,
      romanWord: String,
    ): AlphaWordBitmap? {
      alphaRomanBitmapCache.get(word.renderCacheKey)?.let {
        return it
      }
      val textWidth = subPaint.measureText(romanWord)
      if (textWidth <= 0f) return null
      val fm = subPaint.fontMetrics
      val padX = (kotlin.math.ceil(subPaint.textSize * 0.3f).toInt() + 2)
      val padY = (kotlin.math.ceil(subPaint.textSize * 0.4f).toInt() + 2)
      val bmpWidth = (kotlin.math.ceil(textWidth).toInt() + padX * 2).coerceAtLeast(1)
      val bmpHeight = (kotlin.math.ceil(fm.descent - fm.ascent).toInt() + padY * 2).coerceAtLeast(1)
      val bitmap =
        try {
          Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ALPHA_8)
        } catch (_: OutOfMemoryError) {
          return null
        }
      val baselineY = padY - fm.ascent
      val glyphPaint =
        Paint(subPaint).apply {
          shader = null
          maskFilter = null
          color = Color.WHITE
          alpha = 255
          clearShadowLayer()
        }
      Canvas(bitmap).drawText(romanWord, padX.toFloat(), baselineY, glyphPaint)
      val cache = AlphaWordBitmap(bitmap = bitmap, leftPad = padX, baselineY = baselineY)
      alphaRomanBitmapCache.put(word.renderCacheKey, cache)
      return if (alphaRomanBitmapCache.get(word.renderCacheKey) === cache) cache else null
    }

    /**
     * 直绘 ALPHA_8 字形位图 + 渐变：drawBitmap + paint.shader 对 alpha-only 位图做 DST_IN 组合， 输出 = 渐变色 ×
     * 字形alpha，单次 GPU pass 无离屏。调用方需保证 shader localMatrix 已就位。
     */
    private fun drawAlphaBitmap(
      canvas: Canvas,
      bitmap: Bitmap,
      left: Float,
      top: Float,
      shader: LinearGradient,
    ) {
      if (bitmap.isRecycled) return
      alphaGlyphPaint.shader = shader
      canvas.drawBitmap(bitmap, left, top, alphaGlyphPaint)
      alphaGlyphPaint.shader = null
    }

    private fun getBlurMaskFilter(radius: Float): BlurMaskFilter {
      val key = (radius.coerceAtLeast(0.1f) * 10f).roundToInt().coerceAtLeast(1)
      blurMaskFilterCache.get(key)?.let {
        return it
      }
      val filter = BlurMaskFilter(key / 10f, BlurMaskFilter.Blur.NORMAL)
      blurMaskFilterCache.put(key, filter)
      return filter
    }

    /** 屏幕外初始位置，用于 positionSpring 初始化与入场动画 */
    private fun offScreenPosition(): Float = max(viewportHeight * 2f, 2000f * density)

    /**
     * 入场动画：对齐 Web 引擎 playEntranceAnimation
     * - 所有行从目标位置下方 containerHeight*0.6 处升起
     * - 缩放从目标 * 0.9 恢复到目标
     * - 错峰：index × 40ms 自上而下顺序入场
     */
    private fun playEntranceAnimation() {
      if (viewportHeight <= 0 || lyricLines.isEmpty()) return
      val offset = viewportHeight * 0.6f
      val activeState = resolveActiveState(baseTimeMs)
      for (i in lineSprings.indices) {
        val lineSpring = lineSprings[i]
        val targetY = lineLayouts.getOrNull(i)?.top ?: continue
        val line = lyricLines.getOrNull(i)
        // 对齐 Web 引擎：入场缩放目标按激活判定（正在演唱行，暂停态全 100）
        val isActive = activeState.activeLineIndices.contains(i)
        val targetScale =
          if (!isActive && playing) (if (line?.isBG == true) 75f else 97f) else 100f
        lineSpring.position.setPosition(targetY + offset)
        lineSpring.scale.setPosition(targetScale * 0.9f)
        val delay = i * 40f / 1000f
        lineSpring.position.setTargetPosition(targetY, delay)
        lineSpring.scale.setTargetPosition(targetScale, delay)
        // 对齐 Web 引擎 setTransform：入场时 BG 滑动弹簧同步追逐激活目标
        lineSpring.bgSlide?.let { slide ->
          val targetSlide = bgSlideTarget(i, isActive)
          slide.setTargetPosition(targetSlide, delay)
        }
        activeLineSpringIndices.add(i)
      }
      // 提交布局状态，避免 onDraw 覆盖入场动画目标
      commitLayoutState(activeState, cachedInterludeInfo)
      commitViewportState()
    }

    /**
     * BG 滑动弹簧的目标值（对齐 Web 引擎 calculateLayout）
     * @param index - BG 行下标
     * @param isActive - 该行是否正在演唱
     * @returns 激活时为 0（展开位），否则为隐藏位：置顶 BG +80、下方 BG -80
     */
    private fun bgSlideTarget(
      index: Int,
      isActive: Boolean,
    ): Float {
      if (isActive) return 0f
      return if (isBgAbove(index)) 80f else -80f
    }

    /** 重建逐行弹簧（歌词变化时），对齐 Web 引擎 setLyrics 的弹簧初始化 */
    private fun rebuildLineSprings() {
      val offScreen = offScreenPosition()
      val posYParams =
        SpringParams(mass = springMass, damping = springDamping, stiffness = springStiffness)
      // 对齐 AMLL：主行缩放弹簧 {2,25,100}，BG 行用更软的专用参数 {1,20,50}
      val scaleParams = SpringParams(mass = 2f, damping = 25f, stiffness = 100f)
      val bgScaleParams = SpringParams(mass = 1f, damping = 20f, stiffness = 50f)
      lineSprings =
        Array(lyricLines.size) { index ->
          val position =
            Spring(lineLayouts.getOrNull(index)?.top ?: offScreen).also { spring ->
              spring.updateParams(posYParams)
            }
          val isBG = lyricLines.getOrNull(index)?.isBG == true
          val scale =
            Spring(97f).also { spring ->
              spring.updateParams(if (isBG) bgScaleParams else scaleParams)
            }
          // 对齐 AMLL bgSlideY：初始即处于隐藏位（置顶 +80 / 下方 -80），共用位置弹簧参数
          val bgSlide =
            if (isBG) {
              Spring(if (isBgAbove(index)) 80f else -80f).also { spring ->
                spring.updateParams(posYParams)
              }
            } else {
              null
            }
          LineSpringState(position = position, scale = scale, bgSlide = bgSlide)
        }
      activeLineSpringIndices.clear()
      invalidateCommittedLayoutState()
    }

    /**
     * 将用户配置的弹簧参数下发到逐行位置弹簧（作为策略覆盖前的基值，
     * 对齐 AMLL props 行为：设置即生效，下一次时间轴 diff 后 stiffness/damping
     * 交由 [applyPosYSpringPolicyFromTimeline] 的 AMLL 策略接管）；缩放弹簧主行恒定
     * {2,25,100}、BG 行用 AMLL 专用软参数 {1,20,50}。
     */
    private fun applySpringParams() {
      val posYParams =
        SpringParams(mass = springMass, damping = springDamping, stiffness = springStiffness)
      val scaleParams = SpringParams(mass = 2f, damping = 25f, stiffness = 100f)
      val bgScaleParams = SpringParams(mass = 1f, damping = 20f, stiffness = 50f)
      for (spring in lineSprings.indices) {
        lineSprings[spring].position.updateParams(posYParams)
        lineSprings[spring].scale.updateParams(
          if (lyricLines.getOrNull(spring)?.isBG == true) bgScaleParams else scaleParams,
        )
        lineSprings[spring].bgSlide?.updateParams(posYParams)
        activeLineSpringIndices.add(spring)
      }
    }

    /**
     * 依据时间轴快照应用 AMLL 位置弹簧策略（对齐 updateSpringParams 的触发条件）
     *
     * 在时间轴跳转、间奏切换、滚动目标切换或歌曲结束状态变化时，
     * 用 [AndroidLyricTimeline.resolvePosYSpringPolicy] 的结果覆盖全部行位置弹簧
     * 与 BG 滑动弹簧的 stiffness/damping；mass 不受策略影响，保持用户配置
     *
     * @param forceSeek - 本次 sync 是否由显式跳转触发
     */
    private fun applyPosYSpringPolicyFromTimeline(forceSeek: Boolean) {
      if (lineSprings.isEmpty()) return
      val snapshot = timelineController.getSnapshot()
      val endOfSong = snapshot.isEndOfSong
      val endChanged = endOfSong != lastPolicyEndOfSong
      lastPolicyEndOfSong = endOfSong
      val timelineDiff = timelineController.diff
      if (!(
          forceSeek ||
            timelineDiff.isTimeJumped ||
            timelineDiff.isInterludeChanged ||
            timelineDiff.isScrollToChanged ||
            endChanged
        )
      ) {
        return
      }
      // 间隔取滚动目标行与前一主行的开始时间差（对齐 AMLL currentGroup/prevGroup）
      val lineIndex = mainLineIndices.getOrNull(snapshot.scrollToIndex)
      val prevIndex = mainLineIndices.getOrNull(snapshot.scrollToIndex - 1)
      val intervalMs =
        if (lineIndex != null && prevIndex != null) {
          val current = lyricLines.getOrNull(lineIndex)
          val prev = lyricLines.getOrNull(prevIndex)
          if (current != null && prev != null) current.startTime - prev.startTime else null
        } else {
          null
        }
      val policy =
        AndroidLyricTimeline.resolvePosYSpringPolicy(
          isSeeking = timelineDiff.isTimeJumped,
          isInterludeActive = snapshot.activeInterlude != null,
          intervalMs = intervalMs,
          isEndOfSong = endOfSong,
        )
      for (index in lineSprings.indices) {
        lineSprings[index].position.updateParams(policy)
        lineSprings[index].bgSlide?.updateParams(policy)
        activeLineSpringIndices.add(index)
      }
    }

    /** 是否有逐行弹簧仍在运动 */
    private fun lineSpringsActive(): Boolean = activeLineSpringIndices.isNotEmpty()

    /**
     * 是否有行的模糊仍在渐变中，需要连续帧驱动
     *
     * 暂停或静态行场景下没有其他动画源续帧，缺少该检查会导致模糊渐变冻结
     * （例如暂停时打开模糊开关，模糊永远爬不到目标值而失效）
     */
    private fun lineBlurAnimating(): Boolean {
      // 关闭模糊时残余值同样会回落到 0，期间 settled=false，由数组判定自然覆盖
      for (settled in lineBlurSettled) {
        if (!settled) return true
      }
      return false
    }

    /** 逐词浮动衰减是否仍在进行,由真实时间驱动,暂停态同样需要连续帧 */
    private fun hasActiveFloatFade(): Boolean {
      for (i in lineFloatFadeProgress.indices) {
        if (lineFloatFadeProgress[i] > 0.001f) return true
      }
      return false
    }

    private fun hasActiveFrameAnimation(
      activeState: ActiveState,
      currentTimeMs: Long,
    ): Boolean {
      if (hasActiveFloatFade()) return true
      if (!enableWordHighlight) return false
      for (index in activeState.activeLineIndices) {
        val line = lyricLines.getOrNull(index) ?: continue
        if (!isWordByWordLine(line)) continue
        // QW-4: 浮动/强调只在词效果时间窗(含强调动画余量)内需要连续帧,
        // 窗外由 nextVisualBoundaryMs 按下一词边界唤醒,避免暂停/效果结束后 60fps 空转
        if (currentTimeMs < (lineWordEffectEndCache.getOrNull(index) ?: Long.MIN_VALUE)) return true
      }
      if (!enableFloatAnimation && !enableEmphasizeEffect) return false
      for (index in activeState.bufferedLineIndices) {
        val line = lyricLines.getOrNull(index) ?: continue
        if (hasRetainedWordEffect(index, line, currentTimeMs)) return true
      }
      return false
    }

    /**
     * 设置逐行弹簧目标，对齐 AMLL calculateLayout/setTransform。
     *
     * positionSpring 驱动行内容坐标 Y，scaleSpring 驱动行缩放：
     * - 激活行 100，非激活主行 97，非激活 BG 行 75
     * - 位置级联延迟让远离激活行的行依次过渡，产生波浪效果；
     *   缩放对齐 AMLL 无级联延迟，所有行同步回弹
     */
    private fun applyLineSpringTargets(
      layouts: List<LineLayout>,
      activeState: ActiveState,
      syncImmediate: Boolean,
      noCascade: Boolean,
    ) {
      // 对齐 Web 引擎 calculateLayout/setTransform：
      // - 激活判定仅用正在演唱行集合（hot），不高亮残留与滚动中间行
      // - 缩放目标：激活行 100，非激活主行 97、非激活 BG 行 75（暂停时全 100）
      // - BG 滑动弹簧追逐激活目标（激活滑到 0，否则回到隐藏位 ±80）
      // - 级联延迟让远离激活行的行依次过渡，产生波浪效果；noCascade（seek）时全员同步
      val hotSet = activeState.activeLineIndices
      val anchorIdx = activeState.anchorIndex
      val isUserScrolling = isDragging || scrollResetNano > 0L
      var cascadeDelayMs = 0f
      var baseDelayMs = if (syncImmediate || noCascade) 0f else 50f
      // 上一行实际使用的级联延迟；置顶背景行复用其主行的延迟以同步运动
      var prevDelayMs = 0f
      for (i in layouts.indices) {
        val line = lyricLines.getOrNull(i) ?: continue
        val lineSpring = lineSprings.getOrNull(i) ?: continue
        val isActive = hotSet.contains(i)
        val targetScale = if (!isActive && playing) (if (line.isBG) 75f else 97f) else 100f
        val targetTop = layouts[i].top
        // 置顶背景行位于主行上方，但索引在主行之后；复用主行延迟，避免主行先行上移造成视觉重叠
        val lineDelay = if (line.isBG && isBgAbove(i)) prevDelayMs else cascadeDelayMs
        // 对齐 Web 引擎 group.setTransform + lyric-line.setTransform：
        // posY/bgSlide/scale 在 immediate（拖拽/视口变化/恢复）时瞬移，其余带级联延迟走弹簧
        if (syncImmediate) {
          lineSpring.position.setPosition(targetTop)
          lineSpring.bgSlide?.setPosition(bgSlideTarget(i, isActive))
          lineSpring.scale.setPosition(targetScale)
        } else {
          lineSpring.position.setTargetPosition(targetTop, lineDelay / 1000f)
          lineSpring.bgSlide?.setTargetPosition(bgSlideTarget(i, isActive), lineDelay / 1000f)
          lineSpring.scale.setTargetPosition(targetScale, lineDelay / 1000f)
        }
        activeLineSpringIndices.add(i)
        prevDelayMs = lineDelay
        // 对齐 Web 引擎：级联延迟看的是当前布局推进后的游标，而不是行 top。
        // 这样行顶部仍在屏外、但底部已进入视口时，也能及时推进后续行的级联时序。
        if (layouts[i].flowBottom >= 0f && !isUserScrolling) {
          if (!line.isBG) cascadeDelayMs += baseDelayMs
          if (i >= anchorIdx) baseDelayMs /= 1.05f
        }
      }
    }

    // 与 drawInterludeDots 内部计算保持一致：dotSize=0.6em, dotGap=0.3em, paddingX=1em
    // contentWidth + paddingX*2 = 0.6*3 + 0.3*2 + 1*2 = 4.4em
    private fun measureDotsContainerWidth(fontSize: Float): Float = fontSize * 4.4f

    // containerHeight = dotSize + paddingY*2 = 0.6 + 0.2*2 = 1.0em
    private fun measureDotsContainerHeight(fontSize: Float): Float = fontSize * 1.0f

    private fun computeFrameLayouts(
      activeState: ActiveState,
      interlude: InterludeInfo?,
    ): Pair<List<LineLayout>, Float> {
      if (viewportWidth <= 0 || viewportHeight <= 0 || lyricLines.isEmpty()) {
        return emptyList<LineLayout>() to 0f
      }
      ensureLayoutCache()
      // 对齐 Web 引擎 containerHeight：激活行居中基于全高视口，底部排除区仅用于触摸判定
      val effectiveHeight = viewportHeight
      val hotSet = activeState.activeLineIndices
      val heights = lineHeightsCache
      val targetIndex = activeState.anchorIndex.coerceIn(0, lyricLines.lastIndex)
      val dotsGap = 10f * density
      // 对齐 Web 引擎 calculateLayout：滚动偏移进入布局游标，行弹簧直接追逐含滚动的目标
      var cursor = -userScrollOffset
      for (index in 0 until targetIndex) {
        // 对齐 Web 引擎：非激活 BG 折叠不占位，与播放态无关
        if (lyricLines[index].isBG && !hotSet.contains(index)) continue
        cursor -= heights[index]
      }
      cursor += effectiveHeight * alignPosition - heights[targetIndex] * 0.5f
      // 激活主行带置顶背景行时，主行被背景行下推，整体上移以保持主行居中
      if (isBgAbove(targetIndex + 1) && hotSet.contains(targetIndex + 1)) {
        cursor -= heights[targetIndex + 1]
      }
      var dotsInserted = false
      var nextInterludeState = InterludeState()
      // 置顶背景行延后到下一轮迭代摆放，记录其槽位（对齐 Web 引擎 pendingBg）
      var pendingBgIdx = -1
      var pendingBgY = 0f
      val tops = FloatArray(lyricLines.size)
      val flowBottoms = FloatArray(lyricLines.size)

      for (index in lyricLines.indices) {
        val line = lyricLines[index]

        // 间奏圆点占位
        if (!dotsInserted && interlude != null && index == interlude.prevLineIndex + 1) {
          dotsInserted = true
          cursor += dotsGap
          val anchorFontSize = if (line.isBG) fontSizePx * 0.75f else fontSizePx
          val dotsWidth = measureDotsContainerWidth(anchorFontSize)
          val dotsHeight = measureDotsContainerHeight(anchorFontSize)
          val linePaddingX = anchorFontSize * 0.6f
          val x =
            if (interlude.nextLineIsDuet) {
              viewportWidth - dotsWidth + anchorFontSize - linePaddingX
            } else {
              linePaddingX - anchorFontSize
            }
          nextInterludeState =
            InterludeState(
              isActive = true,
              startTime = interlude.startTime,
              endTime = interlude.endTime,
              x = x,
              y = cursor,
              alignRight = interlude.nextLineIsDuet,
              anchorIndex = index,
              anchorOffset = -(dotsHeight + dotsGap),
              fontSizePx = anchorFontSize,
            )
          cursor += dotsHeight + dotsGap
        }

        // 对齐 Web 引擎：非激活 BG 折叠不占位，与播放态无关
        val collapsedBG = line.isBG && !hotSet.contains(index)
        var top = cursor
        var advance = if (collapsedBG) 0f else heights[index]
        if (index == pendingBgIdx) {
          // 置顶背景行：用主行处预留的上方槽位，自身不再推进布局
          top = pendingBgY
          advance = 0f
          pendingBgIdx = -1
        } else if (isBgAbove(index + 1)) {
          // 主行带置顶背景行：背景行在上、主行在下
          val bgIdx = index + 1
          val bgH = heights[bgIdx]
          val bgSpace = if (hotSet.contains(bgIdx)) bgH else 0f
          top = cursor + bgSpace
          pendingBgY = top - bgH
          pendingBgIdx = bgIdx
          advance = bgSpace + heights[index]
        }
        tops[index] = top
        flowBottoms[index] = cursor + advance
        cursor += advance
      }

      val layouts =
        ArrayList<LineLayout>(lyricLines.size).also { list ->
          for (index in lyricLines.indices) {
            val subLines = lineSubLinesCache.getOrNull(index)?.size ?: 0
            list +=
              LineLayout(
                top = tops[index],
                height = heights[index],
                centerY = tops[index] + heights[index] * 0.5f,
                subLines = subLines,
                flowBottom = flowBottoms[index],
              )
          }
        }

      val lastVisibleHeight =
        lyricLines.indices.lastOrNull { index -> !lyricLines[index].isBG }?.let { heights[it] }
          ?: (heights.lastOrNull() ?: fontSizePx)
      val tailPadding = max(effectiveHeight * (1f - alignPosition) - lastVisibleHeight * 0.5f, 0f)
      interludeState = nextInterludeState
      return layouts to (cursor + tailPadding)
    }

    private fun isBgAbove(index: Int): Boolean {
      // 对齐 AMLL `LyricLineGroup.addBgLine`：`isBgFirst = bgStartTime < mainStartTime`，
      // 并受 `alwaysPostpositionBackground` 一票否决。用户开启后强制背景置后，
      // 无论 BG 首词是否早于主行首词。
      lineBgAboveCache.getOrNull(index)?.let {
        return it
      }
      if (alwaysPostpositionBackground) return false
      if (index !in lyricLines.indices) return false
      val bg = lyricLines[index]
      val main = lyricLines.getOrNull(index - 1) ?: return false
      if (!bg.isBG || main.isBG) return false
      val bgStart = bg.words.firstOrNull()?.startTime ?: bg.startTime
      val mainStart = main.words.firstOrNull()?.startTime ?: main.startTime
      return bgStart < mainStart
    }

    private fun hasDuetLines(): Boolean = hasDuetLinesCached

    /**
     * 取第 index 行主文本字体，越界回退全局主字体
     * @param index - 歌词行索引
     */
    private fun lineMainTypeface(index: Int): Typeface = if (index in lineMainTypefaces.indices) lineMainTypefaces[index] else mainTypeface

    /**
     * 按行语言匹配分语种字体，对齐 Web 端 :lang(zh/ja/ko/und-Latn) 选择器
     * @param language - 行 BCP 47 语言标签
     * @returns 匹配到的字体族，未配置分语种字体时回退全局字体族
     */
    private fun resolveLineFontFamily(language: String?): String? {
      if (language.isNullOrBlank()) return fontFamily
      return when {
        language.startsWith("ja") -> fontFamilyJapanese ?: fontFamily
        language.startsWith("ko") -> fontFamilyKorean ?: fontFamily
        language.startsWith("zh") -> fontFamilyChinese ?: fontFamily
        language.startsWith("und-Latn") -> fontFamilyLatin ?: fontFamily
        else -> fontFamily
      }
    }

    private fun createTypeface(
      family: String?,
      weight: Int,
    ): Typeface {
      // 对齐 Web 引擎 font-synthesis: weight style，使用 API 28+ 细粒度字重 API
      // 旧实现只支持 BOLD/NORMAL 二值，无法呈现 100-900 的中间档（500/600/800 等）
      // Typeface.create 的 weight 硬限制为 1-1000，超过会抛 IllegalArgumentException，渲染前钳制到 1000
      val w = weight.coerceIn(100, 1000)
      val base =
        if (family.isNullOrBlank()) {
          Typeface.DEFAULT
        } else {
          Typeface.create(family, Typeface.NORMAL)
        }
      return Typeface.create(base, w, false)
    }

    private fun applyAlpha(
      color: Int,
      alphaFactor: Float,
    ): Int {
      val alpha = (Color.alpha(color) * alphaFactor).roundToInt().coerceIn(0, 255)
      return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    companion object {
      fun parseLyricLines(json: String): List<NativeLyricLine> {
        if (json.isBlank()) return emptyList()
        return try {
          val raw = JSONArray(json)
          val result = ArrayList<NativeLyricLine>(raw.length())
          for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: continue
            val wordsArray = item.optJSONArray("words") ?: JSONArray()
            val words = ArrayList<NativeLyricWord>(wordsArray.length())
            for (j in 0 until wordsArray.length()) {
              val word = wordsArray.optJSONObject(j) ?: continue
              val rubyArray = word.optJSONArray("ruby")
              val ruby =
                if (rubyArray != null) {
                  ArrayList<NativeLyricSpan>(rubyArray.length()).apply {
                    for (k in 0 until rubyArray.length()) {
                      val rubyItem = rubyArray.optJSONObject(k) ?: continue
                      add(
                        NativeLyricSpan(
                          word = rubyItem.optString("word", ""),
                          startTime = rubyItem.optLong("startTime", 0L),
                          endTime = rubyItem.optLong("endTime", 0L),
                        ),
                      )
                    }
                  }
                } else {
                  emptyList()
                }
              words +=
                NativeLyricWord(
                  word = word.optString("word", ""),
                  startTime = word.optLong("startTime", 0L),
                  endTime = word.optLong("endTime", 0L),
                  romanWord = word.optString("romanWord", ""),
                  obscene = word.optBoolean("obscene", false),
                  ruby = ruby,
                )
            }
            result +=
              NativeLyricLine(
                words = words,
                translatedLyric = item.optString("translatedLyric", ""),
                romanLyric = item.optString("romanLyric", ""),
                startTime = item.optLong("startTime", 0L),
                endTime = item.optLong("endTime", 0L),
                isBG = item.optBoolean("isBG", false),
                isDuet = item.optBoolean("isDuet", false),
              )
          }
          result
        } catch (_: Exception) {
          emptyList()
        }
      }

      fun parseColor(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return try {
          Color.parseColor(raw.trim())
        } catch (_: Exception) {
          null
        }
      }
    }
  }
