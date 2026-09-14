package top.imsyy.splayer_next.android.lyric

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.LruCache
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 主播放器歌词的逐行模糊策略（自 MainPlayerLyricOverlayView 收拢）。
 *
 * 状态：每行模糊当前值/目标值（AMLL 档位语义）与指数逼近更新（对齐 0.4s ease 过渡）；
 * 渲染：静态行（非激活且非浮动衰减）走位图缓存，内容逐帧变化的行走 API 31+ 的 GPU 模糊层。
 *
 * 功耗优化：模糊是低通滤波，模糊图与 GPU 层统一按 [DOWNSCALE] 降采样栅格化再放大，
 * 视觉近无损而软件模糊与 GPU 模糊开销约降至 1/8；档位渐变期用「渐变起点档 + 目标档」
 * 双位图叠化替代逐档重建，一次渐变最多构建两张图。叠化近端必须是起点档模糊图而非
 * 清晰图：已模糊行随锚点推进升档时，清晰图分量会在渐变期闪入造成视觉残留。
 */
internal class LyricBlurController(
  private val density: Float,
) {
  /** 静态行的模糊渲染计划（纯决策，与绘制环境无关，供 JVM 测试） */
  internal enum class StaticDrawPlan {
    /** 当前值与目标值都逼近 0：单张清晰位图 */
    CLEAR_ONLY,

    /** 已收敛到目标档：单张目标档模糊位图 */
    SINGLE_BLUR,

    /** 渐变途中（渐强与渐清统一）：起点档与目标档两张位图按进度叠化，不逐档重建 */
    CROSSFADE,
  }

  var enableBlur = false

  // 每行模糊当前值/目标值（AMLL 档位语义，×1.5×density 换算物理像素）与收敛标记
  private var values = FloatArray(0)
  private var targets = FloatArray(0)

  // 每行渐变起点档位：目标变化瞬间的当前值，叠化近端用起点档模糊图
  private var starts = FloatArray(0)
  private var settled = BooleanArray(0)

  private var lastDeltaMs = -1f
  private var lastExpFactor = 0f

  private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

  private data class LineBitmap(
    val bitmap: Bitmap,
    val padFull: Int,
    val blurKey: Int,
    val downscaled: Boolean,
  )

  // H-3: 配额从 maxMemory/6(上限 96MB)收紧到 maxMemory/8(上限 48MB),超限行走直绘,视效不变
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

  private val blurMaskFilterCache = object : LruCache<Int, BlurMaskFilter>(64) {}

  // 直绘路径的 GPU 模糊层（API 31+）：退场期（逐词浮动衰减）的行必须逐帧直绘、无法进入位图缓存，
  // 而硬件加速 Canvas 对 drawText 的 BlurMaskFilter 不生效：这些行的模糊会整段缺失，
  // 直到衰减结束切回位图缓存时突变出现。这里把整行录进半分辨率 RenderNode，由 RenderEffect
  // 在 GPU 上模糊（对齐 Web 引擎 filter: blur 的整行语义）。
  // 低版本（API 29/30）无 RenderEffect，退场期维持原有的"无模糊"行为。
  private val blurLayerNodes = LinkedHashMap<Int, RenderNode>(4)

  // 每行 RenderNode 上已设置的模糊档位（半分辨率坐标系量化到 1px），避免渐变期逐帧重建 RenderEffect
  private val blurLayerSigmaKeys = HashMap<Int, Int>(4)

  // RenderNode 是 native 资源：只为同时处于退场直绘态的行（通常 1~2 行）保留少量实例
  private val blurLayerNodeLimit = 3

  /**
   * 更新并返回一行的模糊半径（物理像素）
   *
   * 目标档位对齐 AMLL resolveBlurLevel；渐变采用指数逼近（factor 12），
   * 对应 AMLL 中 CSS filter 的 0.4s ease 过渡
   *
   * @param index - 行索引（模糊值的存储槽位）
   * @param distanceIndex - 距离计算所用索引；BG 行传其主行索引（AMLL 模糊为组级，主行与 BG 共享档位）
   * @param lineCount - 当前歌词行数（容量不足时重置状态数组）
   * @param anchorIndex - 自动滚动对齐的焦点行索引
   * @param latestHighlightIndex - 最靠后的高亮行索引
   * @param active - 行是否为焦点行（对齐 AMLL resolveIsActive 判定）
   * @param isUserScrolling - 用户是否正在触摸滚动
   * @param inViewport - 行是否在视口内（视口外目标直接为最大档位，滚入时从模糊渐入）
   * @param viewportCssPx - 视口宽（CSS 像素，窄视口判定用）
   * @param deltaMs - 帧间隔（毫秒）
   * @returns 当前帧模糊半径（物理像素）
   */
  fun updateLine(
    index: Int,
    distanceIndex: Int,
    lineCount: Int,
    anchorIndex: Int,
    latestHighlightIndex: Int,
    active: Boolean,
    isUserScrolling: Boolean,
    inViewport: Boolean,
    viewportCssPx: Float,
    deltaMs: Float,
  ): Float {
    ensureCapacity(lineCount)
    val target =
      AndroidLyricTimeline.resolveBlurTarget(
        enableBlur = enableBlur,
        inViewport = inViewport,
        isUserScrolling = isUserScrolling,
        isFocused = active,
        index = distanceIndex,
        scrollToIndex = anchorIndex,
        latestIndex = latestHighlightIndex,
        isNarrowViewport = viewportCssPx <= 1024f,
      )
    val current = values[index]
    if (targets[index] != target) {
      // 目标档位变化瞬间把当前值记为渐变起点：叠化近端用起点档模糊图而非清晰图，
      // 否则已模糊行升档时清晰分量会在渐变期闪入（视觉残留）
      starts[index] = current
    }
    val factor =
      if (deltaMs == lastDeltaMs) {
        lastExpFactor
      } else {
        lastDeltaMs = deltaMs
        lastExpFactor = 1f - Math.exp((-12f * (deltaMs.coerceAtMost(100f) / 1000f)).toDouble()).toFloat()
        lastExpFactor
      }
    val next = if (abs(target - current) < 0.01f) target else current + (target - current) * factor
    values[index] = next
    targets[index] = target
    // 模糊是否已逼近目标：仅用于帧调度（渐变期间需要连续帧驱动），稳定后即可停帧；
    // 位图缓存不再等待该标记，渐变期由叠化承担过渡
    settled[index] = abs(target - next) < 0.3f
    return levelToPx(next)
  }

  /** 是否有行的模糊仍在渐变中，需要连续帧驱动（缺少该续帧会让暂停态的模糊渐变冻结） */
  fun isAnimating(): Boolean {
    // 关闭模糊时残余值同样会回落到 0，期间 settled=false，由数组判定自然覆盖
    for (s in settled) {
      if (!s) return true
    }
    return false
  }

  /** 视口剔除预留的模糊外扩（物理像素）；取上一帧更新值，仅作 padding 估算 */
  fun currentRadiusPx(index: Int): Float = levelToPx(values.getOrNull(index) ?: 0f)

  /** 渐变起点档位（物理像素），供测试校验目标变化时的记录行为 */
  internal fun startRadiusPx(index: Int): Float = levelToPx(starts.getOrNull(index) ?: 0f)

  /**
   * 绘制静态行（非激活且非浮动衰减）的位图缓存路径。
   *
   * 把整行栅格化到 Bitmap，亚像素的 translate/scale 由 Skia 平滑采样，避免每帧重栅格化文字
   * 产生 AA 漂移。模糊必须走位图缓存：硬件加速 Canvas 对 drawText 的 BlurMaskFilter 不生效，
   * 只有 Canvas(bitmap) 软件画布能栅格化出模糊。清晰图全分辨率保真，模糊图按 [DOWNSCALE]
   * 降采样栅格化、绘制时放大（模糊内容无高频，双线性上采样视觉近无损）。
   *
   * @param lineScale - 行缩放（对齐 Web 引擎 transform-origin，由调用方算好原点）
   * @param contentHeight - 行内容高（物理像素）
   * @param drawContent - 行内容绘制回调：参数依次为目标画布、位图内 top（含 pad）、位图坐标系 σ
   * @returns true 表示已用位图缓存绘制完成；false 表示缓存构建失败，调用方回退直绘
   */
  fun drawStaticLine(
    canvas: Canvas,
    index: Int,
    top: Float,
    lineAlpha: Float,
    lineScale: Float,
    scaleOriginX: Float,
    scaleOriginY: Float,
    contentHeight: Float,
    viewportWidth: Int,
    drawContent: (Canvas, Float, Float) -> Unit,
  ): Boolean {
    val currentPx = levelToPx(values.getOrNull(index) ?: 0f)
    val targetPx = levelToPx(targets.getOrNull(index) ?: 0f)
    when (resolveStaticDrawPlan(currentPx, targetPx, settled.getOrNull(index) ?: true)) {
      StaticDrawPlan.CLEAR_ONLY -> {
        val clear =
          getOrBuildLineBitmap(index, 0f, viewportWidth, contentHeight, drawContent)
            ?: return false
        drawCachedLine(canvas, clear, top, lineAlpha, lineScale, scaleOriginX, scaleOriginY)
      }
      StaticDrawPlan.SINGLE_BLUR -> {
        val blur =
          getOrBuildLineBitmap(index, targetPx, viewportWidth, contentHeight, drawContent)
            ?: return false
        drawCachedLine(canvas, blur, top, lineAlpha, lineScale, scaleOriginX, scaleOriginY)
      }
      StaticDrawPlan.CROSSFADE -> {
        val fromPx = levelToPx(starts.getOrNull(index) ?: 0f)
        val from =
          getOrBuildLineBitmap(index, fromPx, viewportWidth, contentHeight, drawContent)
        val to =
          getOrBuildLineBitmap(index, targetPx, viewportWidth, contentHeight, drawContent)
        if (from == null || to == null) return false
        // 构建 to 图时的 LRU 逐出会连带回收 from 图（entryRemoved 即 recycle），
        // 绘制前校验引用，避免画到已回收的 Bitmap
        if (from.bitmap.isRecycled || to.bitmap.isRecycled) return false
        // 避免量化到同档位时重复绘制相同位图导致半透明区域（如光晕/抗锯齿边缘）重叠变深
        if (from === to) {
          drawCachedLine(canvas, to, top, lineAlpha, lineScale, scaleOriginX, scaleOriginY)
          return true
        }
        val progress = crossfadeProgress(currentPx, fromPx, targetPx)
        // 底层起点档全量绘制、顶层目标档按进度盖入：两张半透明图叠画的总不透明度
        // 只有 1-p+p²（中途整体变淡），底层全量 + 顶层渐变才是线性叠化
        drawCachedLine(canvas, from, top, lineAlpha, lineScale, scaleOriginX, scaleOriginY)
        drawCachedLine(canvas, to, top, lineAlpha * progress, lineScale, scaleOriginX, scaleOriginY)
      }
    }
    return true
  }

  /**
   * 直绘路径的 GPU 模糊层（API 31+ 且硬件加速时启用）。
   *
   * 把整行录进半分辨率 RenderNode，由 RenderEffect 对整行做高斯模糊（对齐 Web 引擎
   * filter: blur 语义），层内绘制不再单独设置模糊；绘制时放大回全分辨率。
   *
   * @param drawContent - 行内容录制回调：参数依次为目标画布、行 top、内容自身模糊半径（恒 0）
   * @returns true 表示已用模糊层绘制完成；false 表示当前环境不支持，调用方回退原直绘
   */
  fun drawGpuLayer(
    canvas: Canvas,
    index: Int,
    top: Float,
    layoutHeight: Float,
    blurRadiusPx: Float,
    viewportWidth: Int,
    hardwareAccelerated: Boolean,
    drawContent: (Canvas, Float, Float) -> Unit,
  ): Boolean {
    if (blurRadiusPx <= CLEAR_THRESHOLD_PX ||
      Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
      !hardwareAccelerated
    ) {
      return false
    }
    val node = acquireBlurLayerNode(index) ?: return false
    val padFull = blurRadiusPx * 1.5f + 8f * density
    val layerTopFull = floor(top - padFull)
    val layerBottomFull = ceil(top + layoutHeight + padFull)
    if (viewportWidth <= 0 || layerBottomFull <= layerTopFull) return false
    val layerTopHalf = floor(layerTopFull * DOWNSCALE).toInt()
    val layerBottomHalf = ceil(layerBottomFull * DOWNSCALE).toInt()
    val layerRightHalf = ceil(viewportWidth * DOWNSCALE).toInt()
    if (layerBottomHalf <= layerTopHalf || layerRightHalf <= 0) return false
    node.setPosition(0, layerTopHalf, layerRightHalf, layerBottomHalf)
    // 层位置已取整，层内用 translate 保留行的浮点位置：亚像素位移不被层边界吃掉，
    // 弹簧/浮动动画期间不会出现整像素抖动
    val recording = node.beginRecording()
    recording.scale(DOWNSCALE, DOWNSCALE)
    recording.translate(0f, -layerTopFull)
    drawContent(recording, top, 0f)
    node.endRecording()
    // σ 直接取 blurRadius：Web 引擎的逐行模糊是 filter: blur(blurCurrent * 1.5px)，
    // 而 CSS blur() 的参数即高斯标准差；blurRadius = 档位 * 1.5 * density 正是同一 σ 的物理像素值。
    // RenderEffect 在半分辨率层坐标系内模糊，σ 同步减半，放大回全分辨率后观感与 Web 基准一致
    val sigma = blurRadiusPx * DOWNSCALE
    val sigmaKey = sigma.roundToInt().coerceAtLeast(1)
    if (blurLayerSigmaKeys[index] != sigmaKey) {
      node.setRenderEffect(RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.DECAL))
      blurLayerSigmaKeys[index] = sigmaKey
    }
    canvas.save()
    canvas.scale(1f / DOWNSCALE, 1f / DOWNSCALE)
    canvas.drawRenderNode(node)
    canvas.restore()
    return true
  }

  /**
   * 取内容绘制用的 BlurMaskFilter（位图栅格化时由 View 的 drawMainText/drawSubTexts 设置）。
   * 按 0.1px 量化缓存，避免逐次分配。
   *
   * 入参是全分辨率高斯 σ（与 GPU 模糊层 RenderEffect 的 sigma 同语义），原样透传：
   * minSdk 29 起的 Skia 把 BlurMaskFilter 入参直接当作 σ，且 σ 随画布 CTM 缩放
   * （computeXformedSigma → mapRadius）——半分辨率位图画布（scale 0.5）内实际模糊 σ/2，
   * 绘制放大 2 倍后等效全分辨率 σ，位图与 GPU 层两条路径强度一致。此前先乘 0.5 再
   * 反解旧版 radius 的写法会让位图路径比 GPU 层浅一档，行切入缓存时整行突然变清晰。
   */
  fun blurMaskFilter(sigmaPx: Float): BlurMaskFilter {
    val key = (sigmaPx * 10f).roundToInt().coerceAtLeast(1)
    blurMaskFilterCache.get(key)?.let {
      return it
    }
    val filter = BlurMaskFilter(key / 10f, BlurMaskFilter.Blur.NORMAL)
    blurMaskFilterCache.put(key, filter)
    return filter
  }

  /** 布局几何变化后行位图与模糊层同为失效对象（顺带释放 RenderNode 的 native 资源） */
  fun invalidateAll() {
    if (blurLayerNodes.isNotEmpty()) {
      for (node in blurLayerNodes.values) node.discardDisplayList()
      blurLayerNodes.clear()
      blurLayerSigmaKeys.clear()
    }
    lineBitmapCache.evictAll()
  }

  /** 行内容转活跃（逐帧变化）时释放其全部档位的位图缓存 */
  fun invalidateLine(index: Int) {
    for (key in lineBitmapCache.snapshot().keys) {
      if (key / CACHE_KEY_SLOT_STRIDE == index) lineBitmapCache.remove(key)
    }
  }

  /** 歌词集变化时重置模糊状态数组 */
  fun reset(lineCount: Int) {
    values = FloatArray(lineCount)
    targets = FloatArray(lineCount)
    starts = FloatArray(lineCount)
    settled = BooleanArray(lineCount) { true }
  }

  private fun ensureCapacity(lineCount: Int) {
    if (values.size != lineCount) reset(lineCount)
  }

  /**
   * 取一行指定模糊档位的位图缓存。blurRadius 量化到 2px 档而非每 1px 一档，亚档差异不触发
   * 重建；缓存键直接编码量化档位，叠化两端（起点档/目标档）各占一格，渐变更替不互相挤占。
   * 位图顶部/底部各预留 padFull 像素吸收模糊光晕，调用方需把绘制 Y 上推同等距离。
   * 模糊图按 [DOWNSCALE] 降采样栅格化：传给 drawContent 的 σ 保持全分辨率值，
   * Skia 会按位图画布的 CTM 缩放（mapRadius）把模糊折算到位图像素空间，
   * 绘制放大后视觉等效全分辨率。
   */
  private fun getOrBuildLineBitmap(
    index: Int,
    blurRadiusPx: Float,
    viewportWidth: Int,
    contentHeight: Float,
    drawContent: (Canvas, Float, Float) -> Unit,
  ): LineBitmap? {
    val blurKey = quantizeBlurKey(blurRadiusPx)
    val key = cacheKey(index, blurKey)
    lineBitmapCache.get(key)?.let {
      if (it.blurKey == blurKey) return it
    }
    if (viewportWidth <= 0 || contentHeight <= 0f) return null
    // 降采样仅用于模糊图：清晰图直接全分辨率栅格化，避免放大后文字发虚
    val downscaled = blurRadiusPx > CLEAR_THRESHOLD_PX
    val scale = if (downscaled) DOWNSCALE else 1f
    // pad 必须覆盖高斯光晕的可见范围（约 3σ），且按全分辨率计量、随位图降采样减半：
    // 取 σ+2 时半分辨率位图里只剩约 0.6σ，光晕在约 1σ 处被硬裁切，行从 GPU 模糊层
    // （pad 1.5σ+8density ≈ 3.3σ，光晕完整）切入位图缓存时光晕塌陷、文字芯显得锐利，
    // 看起来像「从模糊变成清晰」
    val padFull = (ceil(blurRadiusPx * 3f).toInt() + 2).coerceAtLeast(0)
    val bmpWidth = (viewportWidth * scale).roundToInt().coerceAtLeast(1)
    val bmpHeight = ((contentHeight + padFull * 2f) * scale).roundToInt().coerceAtLeast(1)
    val bitmapKb = ((bmpWidth.toLong() * bmpHeight.toLong() * 4L) / 1024L).coerceAtLeast(1L)
    if (bitmapKb > lineBitmapCacheMaxKb / 3L) return null
    val bitmap =
      try {
        Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
      } catch (e: OutOfMemoryError) {
        return null
      }
    val bmpCanvas = Canvas(bitmap)
    bmpCanvas.scale(scale, scale)
    // σ 保持全分辨率值传入：BlurMaskFilter 的模糊随画布 CTM 缩放折算（mapRadius），
    // 乘 scale 会让模糊被 0.5 折算两次，位图路径比 GPU 模糊层浅一档（行切入缓存时变清晰）
    drawContent(bmpCanvas, padFull.toFloat(), blurRadiusPx)
    val cache = LineBitmap(bitmap = bitmap, padFull = padFull, blurKey = blurKey, downscaled = downscaled)
    lineBitmapCache.put(key, cache)
    if (lineBitmapCache.get(key) !== cache) return null
    return cache
  }

  private fun drawCachedLine(
    canvas: Canvas,
    cache: LineBitmap,
    top: Float,
    alpha: Float,
    lineScale: Float,
    scaleOriginX: Float,
    scaleOriginY: Float,
  ) {
    canvas.save()
    if (lineScale != 1f) {
      canvas.scale(lineScale, lineScale, scaleOriginX, scaleOriginY)
    }
    bitmapPaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
    // 位图顶部留了 padFull 像素吸收模糊溢出，绘制时把 Y 上推同等距离让正文落在 top
    val anchorY = top - cache.padFull
    if (cache.downscaled) {
      canvas.scale(1f / DOWNSCALE, 1f / DOWNSCALE)
      canvas.drawBitmap(cache.bitmap, 0f, anchorY * DOWNSCALE, bitmapPaint)
    } else {
      canvas.drawBitmap(cache.bitmap, 0f, anchorY, bitmapPaint)
    }
    canvas.restore()
  }

  /**
   * 取一行专用的 RenderNode（同一帧内每行独立实例，避免多行共享时互相覆盖录制内容）。
   *
   * 超过 [blurLayerNodeLimit] 时只解除引用、不主动丢弃 DisplayList：被淘汰的实例可能刚被
   * 本帧的父显示列表引用，清空会让那一行本帧消失（native 资源交由 GC 回收）。
   */
  private fun acquireBlurLayerNode(index: Int): RenderNode? {
    blurLayerNodes.remove(index)?.let {
      blurLayerNodes[index] = it
      return it
    }
    val eldest = blurLayerNodes.entries.firstOrNull()
    if (eldest != null && blurLayerNodes.size >= blurLayerNodeLimit) {
      blurLayerNodes.remove(eldest.key)
      blurLayerSigmaKeys.remove(eldest.key)
    }
    val node =
      try {
        RenderNode("splayer-lyric-blur")
      } catch (e: OutOfMemoryError) {
        return null
      }
    blurLayerNodes[index] = node
    return node
  }

  private fun cacheKey(
    index: Int,
    blurKey: Int,
  ): Int = index * CACHE_KEY_SLOT_STRIDE + blurKey.coerceIn(0, CACHE_KEY_SLOT_STRIDE - 1)

  private fun levelToPx(level: Float): Float = level * 1.5f * density

  companion object {
    // 模糊栅格化降采样比例：模糊是低通滤波，1/4分辨率栅格化再放大视觉近无损，但像素处理量降至1/16，大幅节省功耗与GPU开销
    private const val DOWNSCALE = 0.25f

    // 模糊档位量化步长（物理像素）：亚档差异不触发位图重建
    private const val BLUR_KEY_STEP_PX = 2f

    // 0.5px 以下的模糊直接按清晰处理，避免设置无效的 maskFilter / RenderEffect
    private const val CLEAR_THRESHOLD_PX = 0.5f

    // 缓存键中每行预留的档位槽数：档位按 2px 量化，模糊上限 5 档 ×1.5×density(最大约 4)
    // ≈ 30px → 档位 key ≤ 15，64 个槽位对单行的起点/目标双图与历史档位裕量充足
    private const val CACHE_KEY_SLOT_STRIDE = 64

    /** 模糊位图缓存档位量化（物理像素 → 档位 key） */
    internal fun quantizeBlurKey(radiusPx: Float): Int = (radiusPx / BLUR_KEY_STEP_PX).roundToInt()

    /**
     * 叠化进度（目标档图的不透明度权重）：当前值在 [fromPx, toPx] 区间中的位置，钳制在 [0, 1]；
     * 起点与终点逼近时按 1 处理（两端量化同档，直接显示目标档）
     */
    internal fun crossfadeProgress(
      currentPx: Float,
      fromPx: Float,
      toPx: Float,
    ): Float =
      if (abs(toPx - fromPx) <= CLEAR_THRESHOLD_PX) {
        1f
      } else {
        ((currentPx - fromPx) / (toPx - fromPx)).coerceIn(0f, 1f)
      }

    /** 静态行模糊渲染计划决策 */
    internal fun resolveStaticDrawPlan(
      currentPx: Float,
      targetPx: Float,
      settled: Boolean,
    ): StaticDrawPlan =
      when {
        currentPx <= CLEAR_THRESHOLD_PX && targetPx <= CLEAR_THRESHOLD_PX -> StaticDrawPlan.CLEAR_ONLY
        settled -> StaticDrawPlan.SINGLE_BLUR
        else -> StaticDrawPlan.CROSSFADE
      }
  }
}
