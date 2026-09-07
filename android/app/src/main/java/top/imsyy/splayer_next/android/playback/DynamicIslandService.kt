package top.imsyy.splayer_next.android.playback

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import java.util.ArrayList
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 灵动岛歌词悬浮窗服务
 * 药丸形（圆角 = 高度 / 2）顶部居中悬浮窗，封面占位 + 歌词逐字高亮。
 */
class DynamicIslandService : Service() {
  private var wm: WindowManager? = null
  private var view: LyricView? = null
  private var lp: WindowManager.LayoutParams? = null

  private lateinit var prefs: SharedPreferences

  // ---------- 歌词数据 ----------
  @Volatile
  private var lrcLines: List<Line> = ArrayList()

  @Volatile
  private var yrcLines: List<Line> = ArrayList()

  @Volatile
  private var songName = ""

  @Volatile
  private var artistName = ""

  // ---------- 时间插值 ----------
  @Volatile
  private var baseMs: Long = 0

  @Volatile
  private var anchorNano: Long = System.nanoTime()

  @Volatile
  private var playing = false

  // ---------- 配置 ----------
  private var colorPlayed = Color.argb(255, 23, 113, 191) // 0xFF1771BF
  private var colorUnplayed = Color.argb(128, 171, 171, 171) // 0x80ABABAB
  private var colorStroke = 0x00000000
  private var colorBg = -0x1A000000 // 0xE6000000
  private var colorBgMask = -0x80000000 // 0x80000000
  private var heightDp = 40f
  private var fontSizeSp = 24f
  private var fontWeight = 500
  private var fontFamily = ""
  private var wordMode = true
  private var autoGenWord = true
  private var bgMask = false
  private var doubleLine = false
  private var showTran = false
  private var alignPos = "center"
  private var animationEnabled = true
  private var limitBounds = false
  private var snapCentered = true
  private var alwaysShowInfo = false
  private var locked = false
  private var dragByLongPress = true
  private var posX = 0
  private var posY = 0
  private var maxWidth = 0

  // ---------- 封面 bitmap ----------
  @Volatile
  private var coverBitmap: android.graphics.Bitmap? = null

  // ---------- 行切换动画 ----------
  private var lastLineIdx = -1
  private var prevLineIdx = -1
  private var lineAnimStartNano = 0L

  // ==================== 生命周期 ====================

  override fun onCreate() {
    super.onCreate()
    prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
    restoreConfigFromPrefs()
    wm = getSystemService(WINDOW_SERVICE) as WindowManager
  }

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    if (intent == null) {
      // 进程被杀后的粘性重启：WebView 与数据源均已消失，直接停止避免空壳悬浮窗
      stopSelf()
      return START_NOT_STICKY
    }
    // 视图构建与数据挂载延迟到携带真实意图的启动，粘性重启路径不会创建窗口
    if (view == null) {
      buildView()
      PlaybackManager.getInstance(this).attachDynamicIslandService(this)
    }
    return START_STICKY
  }

  override fun onDestroy() {
    PlaybackManager.getInstance(this).detachDynamicIslandService(this)
    view?.let {
      try {
        wm?.removeView(it)
      } catch (ignored: Exception) {
      }
      view = null
    }
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  /** 从 SharedPreferences 恢复配置 */
  private fun restoreConfigFromPrefs() {
    colorPlayed = prefs.getInt("colorPlayed", colorPlayed)
    colorUnplayed = prefs.getInt("colorUnplayed", colorUnplayed)
    colorStroke = prefs.getInt("colorStroke", colorStroke)
    colorBg = prefs.getInt("colorBg", colorBg)
    colorBgMask = prefs.getInt("colorBgMask", colorBgMask)
    heightDp = prefs.getFloat("heightDp", heightDp)
    fontSizeSp = prefs.getFloat("fontSizeSp", fontSizeSp)
    fontWeight = prefs.getInt("fontWeight", fontWeight)
    fontFamily = prefs.getString("fontFamily", fontFamily) ?: fontFamily
    wordMode = prefs.getBoolean("wordMode", wordMode)
    doubleLine = prefs.getBoolean("doubleLine", doubleLine)
    showTran = prefs.getBoolean("showTran", showTran)
    alignPos = prefs.getString("alignPos", alignPos) ?: alignPos
    animationEnabled = prefs.getBoolean("animation", animationEnabled)
    bgMask = prefs.getBoolean("bgMask", bgMask)
    limitBounds = prefs.getBoolean("limitBounds", limitBounds)
    snapCentered = prefs.getBoolean("snapCentered", snapCentered)
    alwaysShowInfo = prefs.getBoolean("alwaysShowInfo", alwaysShowInfo)
    locked = prefs.getBoolean("locked", locked)
    dragByLongPress = prefs.getBoolean("dragByLongPress", dragByLongPress)
    posX = prefs.getInt("posX", posX)
    posY = prefs.getInt("posY", posY)
    maxWidth = prefs.getInt("maxWidth", maxWidth)
  }

  /** 持久化配置到 SharedPreferences */
  private fun persistConfigToPrefs() {
    prefs
      .edit()
      .putInt("colorPlayed", colorPlayed)
      .putInt("colorUnplayed", colorUnplayed)
      .putInt("colorStroke", colorStroke)
      .putInt("colorBg", colorBg)
      .putInt("colorBgMask", colorBgMask)
      .putFloat("heightDp", heightDp)
      .putFloat("fontSizeSp", fontSizeSp)
      .putInt("fontWeight", fontWeight)
      .putString("fontFamily", fontFamily)
      .putBoolean("wordMode", wordMode)
      .putBoolean("doubleLine", doubleLine)
      .putBoolean("showTran", showTran)
      .putString("alignPos", alignPos)
      .putBoolean("animation", animationEnabled)
      .putBoolean("bgMask", bgMask)
      .putBoolean("limitBounds", limitBounds)
      .putBoolean("snapCentered", snapCentered)
      .putBoolean("alwaysShowInfo", alwaysShowInfo)
      .putBoolean("locked", locked)
      .putBoolean("dragByLongPress", dragByLongPress)
      .putInt("posX", posX)
      .putInt("posY", posY)
      .putInt("maxWidth", maxWidth)
      .apply()
  }

  // ==================== View 构建 ====================

  @SuppressLint("RtlHardcoded")
  private fun buildView() {
    view = LyricView(this)
    val dm = resources.displayMetrics
    val w = Math.min((dm.widthPixels * 0.7f).toInt(), (620 * dm.density).toInt())
    val h = (56 * dm.density).toInt()
    val x = if (posX > 0) posX - w / 2 else (dm.widthPixels - w) / 2
    val y = posY

    val overlayType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
      }

    lp =
      WindowManager
        .LayoutParams(
          w,
          h,
          overlayType,
          windowFlags(),
          PixelFormat.TRANSLUCENT,
        ).apply {
          gravity = Gravity.TOP or Gravity.LEFT
          this.x = x
          this.y = y
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
          }
        }
    try {
      wm?.addView(view, lp)
    } catch (e: Exception) {
      Log.e(TAG, "addView", e)
    }
  }

  private fun windowFlags(): Int {
    var flags =
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    if (locked) {
      flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }
    return flags
  }

  private fun refreshWindowFlags() {
    val p = lp ?: return
    val nextFlags = windowFlags()
    if (p.flags == nextFlags) return
    p.flags = nextFlags
    try {
      wm?.updateViewLayout(view, p)
    } catch (ignored: Exception) {
    }
  }

  private fun updateWindowPosition(
    windowX: Int,
    windowY: Int,
    windowWidth: Int,
  ) {
    posX = windowX + windowWidth / 2
    posY = windowY
  }

  private fun persistWindowPosition(
    windowX: Int,
    windowY: Int,
    windowWidth: Int,
  ) {
    updateWindowPosition(windowX, windowY, windowWidth)
    persistConfigToPrefs()
  }

  // ==================== 对外 API ====================

  fun pushLyrics(
    lrcJson: String?,
    yrcJson: String?,
  ) {
    lrcLines = parseLines(lrcJson)
    yrcLines = parseLines(yrcJson)
    view?.markContentChanged()
    postRedraw()
  }

  fun pushProgress(
    ms: Long,
    isPlaying: Boolean,
  ) {
    baseMs = ms
    anchorNano = System.nanoTime()
    playing = isPlaying
    postRedraw()
  }

  fun pushSongInfo(
    name: String?,
    artist: String?,
  ) {
    songName = name ?: ""
    artistName = artist ?: ""
    view?.markContentChanged()
    postRedraw()
  }

  /**
   * 推送当前歌曲封面 bitmap，null 表示清除（恢复占位图标）。
   * @param bmp - 封面位图
   */
  fun pushCover(bmp: android.graphics.Bitmap?) {
    coverBitmap = bmp
    postRedraw()
  }

  /**
   * 应用来自 JS 端的灵动岛配置。所有字段均可选，缺失则保持现值。
   */
  fun applyConfig(config: JSONObject?) {
    if (config == null) return

    parseColor(config.opt("playedColor"))?.let { colorPlayed = it }
    parseColor(config.opt("unplayedColor"))?.let { colorUnplayed = it }
    parseColor(config.opt("strokeColor"))?.let { colorStroke = it }
    parseColor(config.opt("backgroundColor"))?.let { colorBg = it }
    parseColor(config.opt("backgroundMaskColor"))?.let { colorBgMask = it }

    if (config.has("height")) {
      val h = config.optDouble("height", heightDp.toDouble())
      if (!h.isNaN() && h > 0) heightDp = h.toFloat().coerceIn(32f, 96f)
    }
    if (config.has("fontSize")) {
      val f = config.optDouble("fontSize", fontSizeSp.toDouble())
      if (!f.isNaN() && f > 0) fontSizeSp = f.toFloat()
    }
    if (config.has("fontWeight")) {
      val w = config.optInt("fontWeight", fontWeight)
      if (w in 100..900) fontWeight = w
    }
    if (config.has("fontFamily")) {
      fontFamily = config.optString("fontFamily", fontFamily)
    }
    if (config.has("wordByWord")) wordMode = config.optBoolean("wordByWord", wordMode)
    if (config.has("autoGenerateWordByWord")) autoGenWord = config.optBoolean("autoGenerateWordByWord", autoGenWord)
    if (config.has("backgroundMask")) bgMask = config.optBoolean("backgroundMask", bgMask)
    if (config.has("doubleLine")) doubleLine = config.optBoolean("doubleLine", doubleLine)
    if (config.has("showTranslation")) showTran = config.optBoolean("showTranslation", showTran)
    if (config.has("align")) {
      val a = config.optString("align", alignPos)
      if (a.isNotEmpty()) alignPos = a
    }
    if (config.has("animation")) animationEnabled = config.optBoolean("animation", animationEnabled)
    if (config.has("limitBounds")) limitBounds = config.optBoolean("limitBounds", limitBounds)
    if (config.has("snapCentered")) snapCentered = config.optBoolean("snapCentered", snapCentered)
    if (config.has("alwaysShowSongInfo")) alwaysShowInfo = config.optBoolean("alwaysShowSongInfo", alwaysShowInfo)
    if (config.has("locked")) locked = config.optBoolean("locked", locked)
    if (config.has("dragByLongPress")) dragByLongPress = config.optBoolean("dragByLongPress", dragByLongPress)
    if (config.has("posX")) posX = config.optInt("posX", posX).coerceAtLeast(0)
    if (config.has("posY")) posY = config.optInt("posY", posY).coerceAtLeast(-200)
    if (config.has("maxWidth")) maxWidth = config.optInt("maxWidth", maxWidth).coerceAtLeast(0)

    persistConfigToPrefs()
    refreshWindowFlags()
    view?.markContentChanged()
    postRedraw()
  }

  private fun postRedraw() {
    view?.postInvalidateOnAnimation()
  }

  // ==================== 时间计算 ====================

  private fun seekMs(): Long {
    if (!playing) return baseMs + 300
    val elapsed = (System.nanoTime() - anchorNano) / 1_000_000L
    return baseMs + elapsed + 300
  }

  private fun activeLines(): List<Line> {
    // 仅按数据丰富度选源：有逐字数据用 yrcLines，否则用 lrcLines。
    // wordMode 只在 paintMainLyricLine 中控制渲染样式（逐字高亮 vs 整行），
    // 不能作为数据源开关，否则关闭逐字时 lrcLines 为空会导致歌词整体消失。
    return if (yrcLines.isNotEmpty()) yrcLines else lrcLines
  }

  private fun findIndex(
    ly: List<Line>,
    ms: Long,
  ): Int {
    var r = -1
    for (i in ly.indices) {
      if (ms >= ly[i].start) r = i else break
    }
    return r
  }

  // ==================== 自定义 View ====================

  @SuppressLint("ViewConstructor")
  private inner class LyricView(
    c: Context,
  ) : View(c) {
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val touchSlop = ViewConfiguration.get(c).scaledTouchSlop.toFloat()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0
    private var dragging = false
    private var longPressReady = false
    private var longPressCanceled = false
    private var windowLayoutDirty = true
    private var lastTargetWidth = 0
    private var marqueeActiveThisFrame = false
    private var wordAnimationActiveThisFrame = false
    private var cachedTypefaceFamily = ""
    private var cachedTypefaceWeight = -1
    private var cachedTypeface: Typeface = Typeface.DEFAULT

    // 字级渐变着色器缓存:几何宽度恒为密度 d,仅在颜色(含透明度)变化时重建,
    // 每帧通过 localMatrix 平移到播放头位置,避免逐帧分配 Shader
    private var wordGradientCp = 0
    private var wordGradientCu = 0
    private var wordGradientWidth = -1f
    private var wordGradient: LinearGradient? = null
    private val wordGradientMatrix = Matrix()
    private var measuredMainText = ""
    private var measuredSubText = ""
    private var measuredFontPx = -1f
    private var measuredTypeface: Typeface? = null
    private var measuredLyricWidth = 0f
    private val pillRect = RectF()
    private val maskRect = RectF()
    private val coverRect = RectF()
    private val coverDstRect = RectF()
    private val coverSrcRect = Rect()
    private val coverClipPath = Path()
    private val noteHeadRect = RectF()
    private var coverGeometryRowH = -1f
    private var coverSize = 0f
    private var coverAreaLeft = 0f
    private var coverAreaTop = 0f
    private var coverRadius = 0f
    private val longPressRunnable =
      Runnable {
        if (!locked) {
          longPressReady = true
        }
      }

    // 动画状态：shrink 阶段画上一行，expand 阶段画当前行
    private var currentDrawCurrent = true
    private var currentClipScale = 1f
    private var currentAlphaScale = 1f

    @Suppress("ReturnCount")
    override fun onTouchEvent(event: MotionEvent): Boolean {
      if (locked) return false
      val p = lp ?: return false
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          downRawX = event.rawX
          downRawY = event.rawY
          downWindowX = p.x
          downWindowY = p.y
          dragging = false
          longPressReady = !dragByLongPress
          longPressCanceled = false
          removeCallbacks(longPressRunnable)
          if (dragByLongPress) {
            postDelayed(longPressRunnable, longPressTimeout)
          }
          parent?.requestDisallowInterceptTouchEvent(true)
          return true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = event.rawX - downRawX
          val dy = event.rawY - downRawY
          if (!dragging) {
            val movedEnough = dx * dx + dy * dy >= touchSlop * touchSlop
            if (dragByLongPress && !longPressReady && movedEnough) {
              longPressCanceled = true
              removeCallbacks(longPressRunnable)
              return true
            }
            if (longPressReady && movedEnough) {
              dragging = true
              removeCallbacks(longPressRunnable)
            }
          }
          if (!dragging) return true

          p.x = downWindowX + dx.toInt()
          p.y = downWindowY + dy.toInt()
          if (limitBounds) {
            val dm = resources.displayMetrics
            p.x = p.x.coerceIn(0, Math.max(0, dm.widthPixels - p.width))
            p.y = p.y.coerceIn(0, Math.max(0, dm.heightPixels - p.height))
          }
          try {
            wm?.updateViewLayout(this, p)
            updateWindowPosition(p.x, p.y, p.width)
          } catch (ignored: Exception) {
          }
          return true
        }
        MotionEvent.ACTION_UP -> {
          removeCallbacks(longPressRunnable)
          parent?.requestDisallowInterceptTouchEvent(false)
          val movedEnough = abs(event.rawX - downRawX) >= touchSlop || abs(event.rawY - downRawY) >= touchSlop
          if (dragging) {
            persistWindowPosition(p.x, p.y, p.width)
          }
          if (!dragging && !longPressCanceled && !movedEnough) {
            performClick()
          }
          dragging = false
          longPressReady = false
          longPressCanceled = false
          return true
        }
        MotionEvent.ACTION_CANCEL -> {
          removeCallbacks(longPressRunnable)
          parent?.requestDisallowInterceptTouchEvent(false)
          dragging = false
          longPressReady = false
          longPressCanceled = false
          return true
        }
      }
      return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
      super.onDetachedFromWindow()
      // 手势进行中服务销毁时移除挂起的长按回调，避免延迟 Runnable 持有已分离的 View
      removeCallbacks(longPressRunnable)
    }

    /** 获取词级渐变着色器,颜色与宽度未变化时复用缓存实例 */
    private fun obtainWordGradient(
      cp: Int,
      cu: Int,
      d: Float,
    ): LinearGradient {
      val cached = wordGradient
      if (cached != null && wordGradientCp == cp && wordGradientCu == cu && wordGradientWidth == d) return cached
      val created = LinearGradient(0f, 0f, d, 0f, cp, cu, Shader.TileMode.CLAMP)
      wordGradient = created
      wordGradientCp = cp
      wordGradientCu = cu
      wordGradientWidth = d
      return created
    }

    fun markContentChanged() {
      windowLayoutDirty = true
      measuredFontPx = -1f
    }

    /**
     * 按需调整悬浮窗尺寸并返回本帧绘制宽度。
     * 行切换动画期间窗口一次性放大到动画峰值尺寸（收/展两相行宽取大），绘制宽度仍随
     * clipScale 逐帧动画，窗口宽于绘制宽度的部分由 onDraw 的 dx 居中补偿。若让
     * updateViewLayout 跟随动画逐帧调整，半透明悬浮窗每帧重建 surface buffer，
     * 在 MTK 机型上会触发 GPUAUX "Null anb" 逐帧刷屏并掉帧。动画结束后由
     * windowLayoutDirty 收敛到最终尺寸。
     * @param animating - 是否处于行切换动画期间
     * @returns 本帧绘制宽度
     */
    private fun updateWindowLayout(animating: Boolean): Int {
      val p = lp ?: return lastTargetWidth
      val dm = resources.displayMetrics
      val drawW = targetWidthPx()
      val mainH = mainRowHeightPx()
      val windowW: Int
      val windowH: Int
      if (animating) {
        windowW = Math.max(Math.max(drawW, targetWidthPx(1f)), lastTargetWidth)
        val subH =
          if (hasSubLineFor(lastLineIdx) || hasSubLineFor(prevLineIdx)) mainH * 0.39f else 0f
        windowH = Math.max((mainH + subH).toInt(), (32f * dm.density).toInt())
      } else {
        windowW = drawW
        windowH = targetHeightPx()
      }
      lastTargetWidth = windowW
      windowLayoutDirty = false
      if (p.width == windowW && p.height == windowH) return drawW

      p.width = windowW
      p.height = windowH
      p.x = if (posX > 0) posX - windowW / 2 else (dm.widthPixels - windowW) / 2
      p.y = posY
      if (limitBounds) {
        p.x = p.x.coerceIn(0, Math.max(0, dm.widthPixels - p.width))
        p.y = p.y.coerceIn(0, Math.max(0, dm.heightPixels - p.height))
      }
      try {
        wm?.updateViewLayout(view, p)
      } catch (ignored: Exception) {
      }
      return drawW
    }

    private fun targetHeightPx(): Int {
      val d = resources.displayMetrics.density
      val mainH = mainRowHeightPx()
      val subH = if (hasSubLine()) mainH * 0.39f else 0f
      return Math.max((mainH + subH).toInt(), (32f * d).toInt())
    }

    private fun mainRowHeightPx(): Float = heightDp * resources.displayMetrics.density

    private fun targetWidthPx(clipScale: Float = currentClipScale): Int {
      val dm = resources.displayMetrics
      val d = dm.density
      val ly = activeLines()
      val idx = if (currentDrawCurrent) lastLineIdx else prevLineIdx
      val mainText: String
      val subText: String
      if (idx in ly.indices) {
        mainText = lineText(ly[idx])
        subText = if (showTran) ly[idx].tran else ""
      } else {
        mainText = if (songName.isNotEmpty()) songName else artistName
        subText = if (songName.isNotEmpty() && artistName.isNotEmpty()) artistName else ""
      }
      val rowH = mainRowHeightPx()
      val fontPx = rowH * 0.5f
      val lyricW = measureCurrentTextWidth(mainText, subText, fontPx, textTypeface())
      val padding = rowH * 0.4f
      val cover = rowH * 0.65f
      val gap = rowH * 0.25f
      val overshoot = lyricW * 0.15f

      val effectiveScale = clipScale.coerceIn(0f, 1.15f)
      val scaledLyricW = lyricW * effectiveScale
      val scaledOvershoot = overshoot * effectiveScale

      val minW = 112f * d
      val defaultMaxW = Math.min(dm.widthPixels * 0.7f, 620f * d)
      val maxW = if (maxWidth > minW) Math.min(maxWidth.toFloat(), defaultMaxW) else defaultMaxW
      return (padding * 2 + cover + gap + scaledLyricW + scaledOvershoot).coerceIn(minW, maxW).toInt()
    }

    private fun hasSubLine(): Boolean = hasSubLineFor(if (currentDrawCurrent) lastLineIdx else prevLineIdx)

    private fun hasSubLineFor(idx: Int): Boolean {
      if (!showTran) return false
      val ly = activeLines()
      if (idx < 0 || idx >= ly.size) return false
      return ly[idx].tran.isNotEmpty()
    }

    private fun measureCurrentTextWidth(
      mainText: String,
      subText: String,
      fontPx: Float,
      typeface: Typeface,
    ): Float {
      if (
        measuredMainText == mainText &&
        measuredSubText == subText &&
        measuredFontPx == fontPx &&
        measuredTypeface === typeface
      ) {
        return measuredLyricWidth
      }
      tp.typeface = typeface
      tp.textSize = fontPx
      val mainWidth = if (mainText.isEmpty()) 0f else tp.measureText(mainText)
      tp.textSize = fontPx * 0.65f
      val subWidth = if (subText.isEmpty()) 0f else tp.measureText(subText)
      measuredMainText = mainText
      measuredSubText = subText
      measuredFontPx = fontPx
      measuredTypeface = typeface
      measuredLyricWidth = Math.max(mainWidth, subWidth)
      return measuredLyricWidth
    }

    private fun textTypeface(): Typeface {
      if (cachedTypefaceFamily == fontFamily && cachedTypefaceWeight == fontWeight) {
        return cachedTypeface
      }
      val style = if (fontWeight >= 600) Typeface.BOLD else Typeface.NORMAL
      cachedTypeface =
        if (fontFamily.isNotEmpty()) {
          Typeface.create(fontFamily, style)
        } else {
          Typeface.create(Typeface.DEFAULT, style)
        }
      cachedTypefaceFamily = fontFamily
      cachedTypefaceWeight = fontWeight
      return cachedTypeface
    }

    override fun onDraw(c: Canvas) {
      updateAnimationState()
      val animating =
        animationEnabled &&
          lineAnimStartNano > 0 &&
          (System.nanoTime() - lineAnimStartNano) < LINE_ANIM_DURATION_NANO
      val renderW =
        if (windowLayoutDirty || animating || lastTargetWidth <= 0) {
          updateWindowLayout(animating)
        } else {
          lastTargetWidth
        }
      val w = width
      val h = height
      val d = resources.displayMetrics.density
      marqueeActiveThisFrame = false
      wordAnimationActiveThisFrame = false

      // 用目标宽度作为绘制基准（跟随 clipScale 动画），窗口宽于绘制宽度时整体平移居中补偿
      // （动画期间窗口尺寸固定，见 updateWindowLayout），让 pill 背景、封面、歌词
      // 在所有位置模式下都视觉对称收缩/展开。
      val drawW = renderW
      val dx = Math.max(0f, (w - renderW) / 2f)

      val saved = c.save()
      if (dx > 0f) c.translate(dx, 0f)
      paintPillBackground(c, drawW, h, d)
      paintLyrics(c, drawW, h, d)
      c.restoreToCount(saved)

      if (animating || (playing && (marqueeActiveThisFrame || wordAnimationActiveThisFrame))) {
        postInvalidateOnAnimation()
      } else if (playing) {
        nextVisualBoundaryMs()?.let { nextTimeMs ->
          postInvalidateDelayed((nextTimeMs - seekMs()).coerceAtLeast(1L))
        }
      }
    }

    private fun updateAnimationState() {
      val ly = activeLines()
      val sk = seekMs()
      val idx = if (ly.isEmpty()) -1 else findIndex(ly, sk)

      // 检测行切换，触发动画
      if (idx != lastLineIdx) {
        windowLayoutDirty = true
        if (lastLineIdx >= 0 && animationEnabled && ly.isNotEmpty() && idx >= 0) {
          prevLineIdx = lastLineIdx
          lineAnimStartNano = System.nanoTime()
        } else {
          prevLineIdx = -1
          lineAnimStartNano = 0L
        }
        lastLineIdx = idx
      }

      var animDt = LINE_ANIM_DURATION_NANO
      if (animationEnabled && lineAnimStartNano > 0) {
        animDt = System.nanoTime() - lineAnimStartNano
        if (animDt >= LINE_ANIM_DURATION_NANO) {
          lineAnimStartNano = 0L
          prevLineIdx = -1
          windowLayoutDirty = true
        }
      }

      val animating = animationEnabled && lineAnimStartNano > 0
      val shrinkPhase = animating && animDt < LINE_SHRINK_NANO && prevLineIdx >= 0 && prevLineIdx < ly.size

      if (shrinkPhase) {
        val t = (animDt / LINE_SHRINK_NANO.toFloat()).coerceIn(0f, 1f)
        currentDrawCurrent = false
        currentClipScale = 1f - t
        currentAlphaScale = 1f - t
      } else if (animating) {
        val t = ((animDt - LINE_SHRINK_NANO) / LINE_EXPAND_NANO.toFloat()).coerceIn(0f, 1f)
        currentDrawCurrent = true
        currentClipScale = overshootEase(t).coerceAtMost(1f)
        currentAlphaScale = t
      } else {
        currentDrawCurrent = true
        currentClipScale = 1f
        currentAlphaScale = 1f
      }
    }

    // ---------- 药丸背景 ----------

    private fun paintPillBackground(
      c: Canvas,
      w: Int,
      h: Int,
      d: Float,
    ) {
      val r = h / 2f
      pillRect.set(0f, 0f, w.toFloat(), h.toFloat())

      if (colorBg ushr 24 != 0) {
        bp.color = colorBg
        c.drawRoundRect(pillRect, r, r, bp)
      }

      if (colorStroke ushr 24 != 0) {
        sp.color = colorStroke
        sp.style = Paint.Style.STROKE
        sp.strokeWidth = 1 * d
        c.drawRoundRect(pillRect, r, r, sp)
        sp.style = Paint.Style.FILL
      }
    }

    private fun ensureCoverGeometry(rowH: Float) {
      if (coverGeometryRowH == rowH) return
      coverGeometryRowH = rowH
      coverSize = rowH * 0.65f
      coverAreaLeft = rowH * 0.4f
      coverAreaTop = (rowH - coverSize) / 2f
      coverRadius = Math.max(coverSize * 0.35f, 3f * resources.displayMetrics.density)
      coverRect.set(
        coverAreaLeft,
        coverAreaTop,
        coverAreaLeft + coverSize,
        coverAreaTop + coverSize,
      )
      coverClipPath.reset()
      coverClipPath.addRoundRect(coverRect, coverRadius, coverRadius, Path.Direction.CW)
    }

    /**
     * 绘制封面：在左侧固定方形区域内居中显示。
     * 方形大小 = rowH * 0.65，左侧内边距 = rowH * 0.4，
     * 因此方形在主行高度内垂直居中。
     */
    private fun paintCover(
      c: Canvas,
      rowH: Float,
    ) {
      ensureCoverGeometry(rowH)
      val sz = coverSize
      val areaLeft = coverAreaLeft
      val areaTop = coverAreaTop
      val minD = 3f * resources.displayMetrics.density
      val radius = coverRadius

      val bmp = coverBitmap
      if (alwaysShowInfo && bmp != null && !bmp.isRecycled) {
        c.save()
        c.clipPath(coverClipPath)
        // 保持比例缩放并裁剪为 cover，bitmap 中心与方形中心重合
        val scale = Math.max(sz / bmp.width, sz / bmp.height)
        val drawW = bmp.width * scale
        val drawH = bmp.height * scale
        val cx = areaLeft + sz / 2f
        val cy = areaTop + sz / 2f
        coverDstRect.set(
          cx - drawW / 2f,
          cy - drawH / 2f,
          cx + drawW / 2f,
          cy + drawH / 2f,
        )
        coverSrcRect.set(0, 0, bmp.width, bmp.height)
        c.drawBitmap(bmp, coverSrcRect, coverDstRect, ip)
        c.restore()
        return
      }

      // 占位背景
      ip.color = 0x33FFFFFF
      ip.style = Paint.Style.FILL
      c.drawRoundRect(coverRect, radius, radius, ip)

      // 音符图标，基于方形中心定位
      ip.color = -0x33000001
      val cx = areaLeft + sz / 2f
      val cy = areaTop + sz / 2f
      val headR = sz * 0.16f
      val headCx = cx - sz * 0.10f
      val headCy = cy + sz * 0.18f
      noteHeadRect.set(headCx - headR, headCy - headR, headCx + headR, headCy + headR)
      c.drawOval(noteHeadRect, ip)

      ip.style = Paint.Style.STROKE
      ip.strokeWidth = Math.max(sz * 0.055f, minD * 0.5f)
      ip.strokeCap = Paint.Cap.ROUND
      val stemX = headCx + headR * 0.8f
      c.drawLine(stemX, headCy, stemX, cy - sz * 0.20f, ip)
      ip.style = Paint.Style.FILL
    }

    // ---------- 歌词绘制 ----------

    private fun paintLyrics(
      c: Canvas,
      w: Int,
      h: Int,
      d: Float,
    ) {
      val rowH = mainRowHeightPx()
      paintCover(c, rowH)

      val coverSz = rowH * 0.65f
      val coverGap = rowH * 0.25f
      val padding = rowH * 0.4f
      val textLeft = padding + coverSz + coverGap
      // w 已是 onDraw 计算好的目标渲染宽度（居中模式下补偿了 updateViewLayout 异步滞后）
      val textAreaW = Math.max(0f, w - padding - textLeft)

      if (bgMask && colorBgMask ushr 24 != 0) {
        val maskLeft = textLeft - 4 * d
        val maskRight = w - padding + 4 * d
        val maskTop = h * 0.12f
        val maskBot = h * 0.88f
        maskRect.set(maskLeft, maskTop, maskRight, maskBot)
        bp.color = colorBgMask
        val maskR = (maskBot - maskTop) / 2f
        c.drawRoundRect(maskRect, maskR, maskR, bp)
      }

      val tsz = rowH * 0.5f
      tp.textSize = tsz
      tp.typeface = textTypeface()
      tp.shader = null

      val drawCurrent = currentDrawCurrent
      val clipScale = currentClipScale
      val alphaScale = currentAlphaScale

      val clipW = textAreaW * clipScale.coerceIn(0f, 1f)
      val clipLeft = textLeft + (textAreaW - clipW) / 2f
      val savedC = c.save()
      c.clipRect(clipLeft, 0f, clipLeft + clipW, h.toFloat())

      val ly = activeLines()
      val idx = if (drawCurrent) lastLineIdx else prevLineIdx

      if (idx !in ly.indices) {
        tp.shader = null
        val alpha = (0xBB * alphaScale).toInt().coerceIn(0, 255)
        tp.color = (alpha shl 24) or 0x00FFFFFF
        tp.textSize = tsz
        val mainText = if (songName.isNotEmpty()) songName else artistName
        val subText = if (songName.isNotEmpty() && artistName.isNotEmpty()) artistName else ""
        val twoLine = subText.isNotEmpty()
        val mainY = if (twoLine) h * 0.36f else h * 0.5f
        drawScrollingText(c, mainText, mainY, tp, textLeft, textAreaW, tsz, alignPos)
        if (twoLine) {
          tp.shader = null
          tp.color = (alpha shl 24) or (colorUnplayed and 0x00FFFFFF)
          val subSize = tsz * 0.65f
          tp.textSize = subSize
          drawScrollingText(c, subText, h * 0.74f, tp, textLeft, textAreaW, subSize, alignPos)
        }
      } else {
        val line = ly[idx]
        val subText = if (showTran) line.tran else ""
        val twoLine = subText.isNotEmpty()
        val mainY = if (twoLine) h * 0.36f else h * 0.5f
        val alpha = (255 * alphaScale).toInt()

        if (drawCurrent) {
          paintMainLyricLine(c, line, seekMs(), mainY, tsz, textLeft, textAreaW, d, alpha)
        } else {
          tp.shader = null
          tp.color = (alpha.coerceIn(0, 255) shl 24) or (colorPlayed and 0x00FFFFFF)
          tp.textSize = tsz
          drawScrollingText(c, lineText(line), mainY, tp, textLeft, textAreaW, tsz, alignPos)
        }
        if (twoLine) {
          tp.shader = null
          val subAlpha = (255 * alphaScale).toInt()
          tp.color = (subAlpha.coerceIn(0, 255) shl 24) or (colorUnplayed and 0x00FFFFFF)
          val subSize = tsz * 0.65f
          tp.textSize = subSize
          drawScrollingText(c, subText, h * 0.74f, tp, textLeft, textAreaW, subSize, alignPos)
        }
      }
      c.restoreToCount(savedC)
    }

    private fun paintMainLyricLine(
      c: Canvas,
      line: Line,
      sk: Long,
      mainY: Float,
      tsz: Float,
      textLeft: Float,
      textMaxW: Float,
      d: Float,
      alpha: Int,
    ) {
      if (wordMode && yrcLines.isNotEmpty() && line.words.size > 1) {
        paintWordLyric(c, line, sk, mainY, tsz, textLeft, textMaxW, d, alpha)
      } else {
        tp.shader = null
        tp.color = (alpha.coerceIn(0, 255) shl 24) or (colorPlayed and 0x00FFFFFF)
        tp.textSize = tsz
        drawScrollingText(c, lineText(line), mainY, tp, textLeft, textMaxW, tsz, alignPos)
      }
    }

    private fun overshootEase(t: Float): Float {
      val c1 = 1.70158f
      val c3 = c1 + 1f
      val p = t - 1f
      return 1f + c3 * p * p * p + c1 * p * p
    }

    /** 绘制文字并在宽度不足时按比例缩小，避免截断为省略号 */
    private fun drawFittedText(
      c: Canvas,
      text: String?,
      cy: Float,
      p: Paint,
      areaLeft: Float,
      areaWidth: Float,
      baseSize: Float,
      align: String,
    ) {
      if (text.isNullOrEmpty() || areaWidth <= 0) return
      p.shader = null
      p.textSize = baseSize
      var tw = p.measureText(text)
      if (tw > areaWidth) {
        // 对齐 PC 受限宽度策略：先轻微缩放，仍不足时保持裁剪由窗口遮挡处理
        val scale = Math.max(0.78f, areaWidth / tw)
        p.textSize = baseSize * scale
        tw = p.measureText(text)
      }
      val fm = p.fontMetrics
      val y = cy - (fm.ascent + fm.descent) / 2f
      val x =
        when (align) {
          "left" -> areaLeft
          "right" -> Math.max(areaLeft, areaLeft + areaWidth - tw)
          "center", "justify" -> Math.max(areaLeft, areaLeft + (areaWidth - tw) / 2f)
          else -> Math.max(areaLeft, areaLeft + (areaWidth - tw) / 2f)
        }
      c.drawText(text, x, y, p)
    }

    /**
     * 绘制文字，当文本宽度超过区域宽度时在固定区域内水平滚动（跑马灯）。
     * 仅在 maxWidth > 0 时启用滚动，否则回退到 drawFittedText 缩放适配。
     * 滚动节奏：左停顿 800ms → 向右滚动 → 右停顿 800ms → 向左滚回 → 循环。
     */
    private fun drawScrollingText(
      c: Canvas,
      text: String?,
      cy: Float,
      p: Paint,
      areaLeft: Float,
      areaWidth: Float,
      baseSize: Float,
      align: String,
    ) {
      if (text.isNullOrEmpty() || areaWidth <= 0) return
      // maxWidth=0 未设定上限，回退到缩放适配
      if (maxWidth <= 0) {
        drawFittedText(c, text, cy, p, areaLeft, areaWidth, baseSize, align)
        return
      }
      p.shader = null
      p.textSize = baseSize
      val tw = p.measureText(text)
      // 文本不超出区域，正常绘制
      if (tw <= areaWidth) {
        drawFittedText(c, text, cy, p, areaLeft, areaWidth, baseSize, align)
        return
      }
      marqueeActiveThisFrame = true
      // 文本超出区域，水平滚动
      val fm = p.fontMetrics
      val y = cy - (fm.ascent + fm.descent) / 2f
      val scrollRange = tw - areaWidth
      val speed = 40f // px per second
      val scrollMs = (scrollRange / speed * 1000f).toLong().coerceAtLeast(1L)
      val pauseMs = 800L
      val cycleMs = pauseMs + scrollMs + pauseMs + scrollMs
      val t = (System.nanoTime() / 1_000_000L % cycleMs).coerceIn(0L, cycleMs - 1)
      val offset =
        when {
          t < pauseMs -> 0f
          t < pauseMs + scrollMs -> (t - pauseMs).toFloat() / scrollMs * scrollRange
          t < pauseMs * 2 + scrollMs -> scrollRange
          else -> scrollRange - (t - pauseMs * 2 - scrollMs).toFloat() / scrollMs * scrollRange
        }
      c.save()
      c.clipRect(areaLeft, 0f, areaLeft + areaWidth, c.height.toFloat())
      p.textAlign = Paint.Align.LEFT
      c.drawText(text, areaLeft - offset, y, p)
      c.restore()
    }

    /**
     * 逐字歌词绘制：文本不超宽时按 alignPos 定位；超宽时跟随播放头单向滚动，
     * 让当前演唱的词始终保持在可视区域内（参考 PC 渐变扫描的视觉效果）。
     * 滚动偏移 = clamp(播放头x - 视口宽*0.4, 0, 总宽-视口宽)，
     * 使当前词位于视口左侧 40% 处，右侧留出未唱词的预览空间。
     */
    private fun paintWordLyric(
      c: Canvas,
      line: Line,
      sk: Long,
      cy: Float,
      tsz: Float,
      areaLeft: Float,
      areaWidth: Float,
      d: Float,
      alpha: Int,
    ) {
      tp.textSize = tsz
      val typeface = textTypeface()
      tp.typeface = typeface
      tp.shader = null
      ensureWordMetrics(line, tsz, typeface)
      val n = line.words.size
      val ww = line.wordWidths
      val wordX = line.wordPositions
      val total = line.totalWordWidth

      val fm = tp.fontMetrics
      val bl = cy - (fm.ascent + fm.descent) / 2f
      val a = alpha.coerceIn(0, 255)

      // 计算播放头 x（相对文本起点）：已播完词推到末尾，当前词按进度内插
      var playheadX = 0f
      for (i in 0 until n) {
        val prog = wordProg(line.words[i], sk)
        if (prog >= 1f) {
          playheadX = wordX[i] + ww[i]
        } else {
          if (prog > 0f) wordAnimationActiveThisFrame = true
          playheadX = wordX[i] + ww[i] * prog.coerceAtLeast(0f)
          break
        }
      }

      // 文本不超宽：按 align 居中/对齐，不滚动
      // 文本超宽：跟随播放头滚动，当前词保持在视口左侧 40% 处
      val needScroll = total > areaWidth && areaWidth > 0f
      val scrollOffset =
        if (needScroll) {
          (playheadX - areaWidth * 0.4f).coerceIn(0f, (total - areaWidth).coerceAtLeast(0f))
        } else {
          0f
        }

      val originX =
        if (needScroll) {
          areaLeft - scrollOffset
        } else {
          when (alignPos) {
            "left" -> areaLeft
            "right" -> Math.max(areaLeft, areaLeft + areaWidth - total)
            "center", "justify" -> Math.max(areaLeft, areaLeft + (areaWidth - total) / 2f)
            else -> Math.max(areaLeft, areaLeft + (areaWidth - total) / 2f)
          }
        }

      val viewLeft = areaLeft
      val viewRight = areaLeft + areaWidth

      for (i in 0 until n) {
        val x = originX + wordX[i]
        val wordRight = x + ww[i]
        // 跳过完全在视口外的词，减少绘制开销
        if (wordRight < viewLeft || x > viewRight) continue

        val word = line.words[i]
        val prog = wordProg(word, sk)
        tp.shader = null
        if (prog <= 0f) {
          tp.color = (a shl 24) or (colorUnplayed and 0x00FFFFFF)
        } else if (prog >= 1f) {
          tp.color = (a shl 24) or (colorPlayed and 0x00FFFFFF)
        } else {
          val sx = x + ww[i] * prog
          val cp = (a shl 24) or (colorPlayed and 0x00FFFFFF)
          val cu = (a shl 24) or (colorUnplayed and 0x00FFFFFF)
          val gradient = obtainWordGradient(cp, cu, d)
          wordGradientMatrix.setTranslate(sx - d * 0.5f, 0f)
          gradient.setLocalMatrix(wordGradientMatrix)
          tp.shader = gradient
          tp.color = Color.WHITE
        }
        c.drawText(word.text, x, bl, tp)
      }
      tp.shader = null
    }

    private fun ensureWordMetrics(
      line: Line,
      textSize: Float,
      typeface: Typeface,
    ) {
      if (
        line.wordMetricsTextSize == textSize &&
        line.wordMetricsTypeface === typeface &&
        line.wordWidths.size == line.words.size
      ) {
        return
      }
      val count = line.words.size
      line.wordWidths = FloatArray(count)
      line.wordPositions = FloatArray(count)
      var totalWidth = 0f
      tp.textSize = textSize
      tp.typeface = typeface
      for (index in 0 until count) {
        line.wordWidths[index] = tp.measureText(line.words[index].text)
        line.wordPositions[index] = totalWidth
        totalWidth += line.wordWidths[index]
      }
      line.totalWordWidth = totalWidth
      line.wordMetricsTextSize = textSize
      line.wordMetricsTypeface = typeface
    }

    private fun wordProg(
      w: Word,
      sk: Long,
    ): Float {
      if (sk >= w.end) return 1f
      if (sk <= w.start) return 0f
      return (sk - w.start).toFloat() / Math.max(w.end - w.start, 1L).toFloat()
    }

    private fun nextVisualBoundaryMs(): Long? {
      val lines = activeLines()
      if (lines.isEmpty()) return null
      val currentTimeMs = seekMs()
      val currentIndex = findIndex(lines, currentTimeMs)
      var nextTimeMs =
        lines.getOrNull(currentIndex + 1)?.start
          ?: lines.firstOrNull()?.start?.takeIf { currentIndex < 0 }
      if (wordMode && yrcLines.isNotEmpty()) {
        val line = lines.getOrNull(currentIndex)
        if (line != null) {
          for (word in line.words) {
            if (word.start > currentTimeMs && (nextTimeMs == null || word.start < nextTimeMs)) {
              nextTimeMs = word.start
            }
          }
        }
      }
      return nextTimeMs
    }
  }

  companion object {
    private const val TAG = "DynamicIsland"
    private const val PREFS = "dynamic_island_prefs"
    private const val LINE_SHRINK_NANO = 250L * 1_000_000L
    private const val LINE_EXPAND_NANO = 500L * 1_000_000L
    private const val LINE_ANIM_DURATION_NANO = LINE_SHRINK_NANO + LINE_EXPAND_NANO

    // ==================== 解析 ====================

    private fun parseLines(json: String?): List<Line> {
      val r = ArrayList<Line>()
      if (json.isNullOrEmpty()) return r
      try {
        val a = JSONArray(json)
        for (i in 0 until a.length()) {
          val o = a.getJSONObject(i)
          val l = Line()
          l.start = o.optLong("startTime", 0)
          l.end = o.optLong("endTime", 0)
          l.tran = o.optString("translatedLyric", "")
          val wa = o.optJSONArray("words")
          val text = StringBuilder()
          if (wa != null) {
            for (j in 0 until wa.length()) {
              val wo = wa.getJSONObject(j)
              val wordText = wo.optString("word", "")
              l.words.add(Word(wordText, wo.optLong("startTime", 0), wo.optLong("endTime", 0)))
              text.append(wordText)
            }
          }
          l.text = text.toString()
          r.add(l)
        }
      } catch (e: Exception) {
        Log.w(TAG, "parse", e)
      }
      return r
    }

    /**
     * 解析颜色字符串：
     *  - #RRGGBB / #AARRGGBB / #RGB
     *  - rgb(r,g,b) / rgba(r,g,b,a)
     */
    private fun parseColor(raw: Any?): Int? {
      if (raw !is String) return null
      val v = raw.trim()
      if (v.isEmpty()) return null
      try {
        if (v.startsWith("#")) {
          return Color.parseColor(v)
        }
        val lower = v.lowercase()
        if (lower.startsWith("rgba(") || lower.startsWith("rgb(")) {
          val lp = v.indexOf('(')
          val rp = v.indexOf(')')
          if (lp < 0 || rp < 0) return null
          val inner = v.substring(lp + 1, rp)
          val parts = inner.split(",")
          if (parts.size < 3) return null
          val r = clamp255(Math.round(parts[0].trim().toDouble()).toInt())
          val g = clamp255(Math.round(parts[1].trim().toDouble()).toInt())
          val b = clamp255(Math.round(parts[2].trim().toDouble()).toInt())
          var a = 255
          if (parts.size >= 4) {
            val af = parts[3].trim().toDouble()
            a = if (af <= 1.0) Math.round(af * 255).toInt() else clamp255(Math.round(af).toInt())
          }
          return Color.argb(a, r, g, b)
        }
      } catch (ignored: Exception) {
      }
      return null
    }

    private fun clamp255(v: Int): Int = Math.max(0, Math.min(255, v))

    // ==================== 数据结构 ====================

    fun lineText(l: Line?): String = l?.text.orEmpty()
  }

  class Line {
    var start: Long = 0
    var end: Long = 0
    var tran: String = ""
    var words: MutableList<Word> = ArrayList()
    var text: String = ""
    var wordWidths = FloatArray(0)
    var wordPositions = FloatArray(0)
    var totalWordWidth = 0f
    var wordMetricsTextSize = -1f
    var wordMetricsTypeface: Typeface? = null
  }

  class Word(
    val text: String,
    val start: Long,
    val end: Long,
  )
}
