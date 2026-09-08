package top.imsyy.splayer_next.android.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.getcapacitor.JSObject
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import org.json.JSONObject
import top.imsyy.splayer_next.android.MainActivity
import top.imsyy.splayer_next.android.R
import top.imsyy.splayer_next.android.cache.AudioCacheProvider
import top.imsyy.splayer_next.android.cache.AudioPrefetchTtlIndex
import top.imsyy.splayer_next.android.cache.SongCacheFetcher
import top.imsyy.splayer_next.android.server.ExternalApiBroadcaster

@UnstableApi
class PlaybackManager private constructor(
  context: Context,
) {
  private val appContext: Context = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private val artworkExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private val resolveTokenCounter = AtomicLong()
  private val artworkTokenCounter = AtomicLong()

  private var player: ExoPlayer? = null
  private var sessionPlayer: Player? = null
  var session: MediaSession? = null
    private set

  @Volatile
  private var service: PlaybackService? = null

  @Volatile
  private var serviceStarted = false
  private var plugin: AndroidNativePlaybackPlugin? = null

  /**
   * WebView 是否前台可见（由插件 handleOnPause/handleOnResume 上报）。
   * 高频事件（FFT/progress）对隐藏窗口静默，恢复后下一帧自愈，无需重同步。
   */
  @Volatile
  private var webViewVisible = true
  private var coverBitmap: Bitmap? = null
  private var coverArtworkBytes: ByteArray? = null
  private var notificationIconTypeface: Typeface? = null

  @Volatile
  private var currentSource = ""

  @Volatile
  private var apiBaseUrl = ""

  @Volatile
  private var cookie = ""

  @Volatile
  private var songLevel = "exhigh"

  @Volatile
  private var disableAiAudio = false

  @Volatile
  private var playSongDemo = false

  @Volatile
  private var pendingPromotionRunnable: Runnable? = null
  private val urlResolver = PlaybackUrlResolver(appContext)
  private val songCacheExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private val fmFetcher = FmFetcher()
  private var currentMetadata = TrackMetadata()

  private var nativeRecoverySongId = 0L
  private var nativeRecoveryAttempts = 0

  /**
   * URL 过期（403/401/410）自愈重试状态。
   * - retryGeneration 防并发重试打架：每次发起重试递增，后台回调比对 generation 丢弃过期任务
   * - 按 track 重置：songId 变化时自动重置 urlExpireRetried 标记，同一 track 仅重试 Exactly 一次
   */
  private val urlExpireRetryGeneration = AtomicLong()
  private var urlExpireRetriedSongId: Long = 0L
  private var urlExpireRetried: Boolean = false
  private val playbackQueue = PlaybackQueue()

  private var controllerEnabled = true
  private var desktopLyricButtonEnabled = false
  private var desktopLyricEnabled = false
  private var allowMixWithOthers = true
  private var pauseOnDeviceSwitch = false
  private var audioBecomingNoisyReceiver: BroadcastReceiver? = null
  private var canSkipPrevious = true
  private var personalFmMode = false
  private var liked = false
  private var collapsed = false
  private var favoriteRequestInFlight = false

  /** FM 已播 songId 去重集合（容量有限），播种自 JS 上下文，自播追加。 */
  private val fmPlayedSongIds = LinkedHashSet<Long>()

  /** FM 续池护栏：每次成功播曲只允许触发一次续池，防止解析全失败的无限续池循环。 */
  private var fmRefillGuard = false

  /** 延迟歌曲缓存下载的调度句柄，切歌时取消。 */
  private var pendingSongCacheRunnable: Runnable? = null

  @Volatile
  private var songCacheEnabled = false

  private var pendingSeekPositionMs = C.TIME_UNSET
  private var pendingSeekDeadlineMs = 0L
  private var lastKnownPositionMs = 0L
  private var durationCalibratedForSource = ""

  private var dynamicIslandService: DynamicIslandService? = null
  private var bufferedLrcJson: String? = null
  private var bufferedYrcJson: String? = null
  private var bufferedLyricConfig: org.json.JSONObject? = null
  private var bufferedSongName: String? = null
  private var bufferedArtist: String? = null
  private var bufferedCoverBitmap: Bitmap? = null
  private var bufferedTimeMs: Long = 0L
  private var bufferedPlaying: Boolean = false
  private var mainLyricClockListener: (() -> Unit)? = null

  private var remoteMode = false
  private var remoteIsPlaying = false
  private var remotePositionMs = 0L
  private var remoteDurationMs = 0L
  private var remoteAnchorNano = System.nanoTime()
  private var remoteWakeLock: PowerManager.WakeLock? = null

  private val fftAudioProcessor = FftAudioProcessor()
  private val eqAudioProcessor = EqualizerAudioProcessor()
  private var visualizerRequested = false

  private val nextSessionCommand = SessionCommand(PlaybackConstants.ACTION_NEXT, Bundle.EMPTY)
  private val previousSessionCommand = SessionCommand(PlaybackConstants.ACTION_PREVIOUS, Bundle.EMPTY)
  private val favoriteSessionCommand = SessionCommand(PlaybackConstants.ACTION_FAVORITE, Bundle.EMPTY)
  private val desktopLyricSessionCommand = SessionCommand(PlaybackConstants.ACTION_DESKTOP_LYRIC, Bundle.EMPTY)

  private val contentMimeCache: MutableMap<String, String> =
    Collections.synchronizedMap(
      object : LinkedHashMap<String, String>(CONTENT_MIME_CACHE_MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > CONTENT_MIME_CACHE_MAX_SIZE
      },
    )

  /**
   * Android 端禁明文；服务商封面链接偶尔仍是 http，这里统一升到 https。
   */
  private fun normalizeMediaUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    return try {
      val parsed = Uri.parse(url)
      val host = parsed.host ?: return url
      if (parsed.scheme.equals("http", ignoreCase = true) && host.endsWith(".music.126.net")) {
        parsed
          .buildUpon()
          .scheme("https")
          .build()
          .toString()
      } else {
        url
      }
    } catch (_: Exception) {
      url
    }
  }

  private val mediaSessionCallback =
    object : MediaSession.Callback {
      override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
      ): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult
          .AcceptedResultBuilder(session)
          .setAvailableSessionCommands(buildAvailableSessionCommands())
          .setAvailablePlayerCommands(buildAvailablePlayerCommands())
          .setCustomLayout(buildCustomLayout())
          .setMediaButtonPreferences(buildMediaButtonPreferences())
          .setSessionActivity(buildContentIntent())
          .build()

      @Deprecated("兼容 Media3 现有回调接口")
      @Suppress("DEPRECATION")
      override fun onPlayerCommandRequest(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        playerCommand: Int,
      ): Int =
        when (playerCommand) {
          Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
            handleSessionAction(PlaybackConstants.ACTION_NEXT)
            SessionResult.RESULT_INFO_SKIPPED
          }
          Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
            handleSessionAction(PlaybackConstants.ACTION_PREVIOUS)
            SessionResult.RESULT_INFO_SKIPPED
          }
          else -> super.onPlayerCommandRequest(session, controller, playerCommand)
        }

      override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
      ): ListenableFuture<SessionResult> = Futures.immediateFuture(handleCustomCommand(customCommand))

      override fun onMediaButtonEvent(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        intent: Intent,
      ): Boolean {
        val keyEvent = extractKeyEvent(intent)
        if (keyEvent == null || keyEvent.action != KeyEvent.ACTION_DOWN) {
          return false
        }

        return when (keyEvent.keyCode) {
          KeyEvent.KEYCODE_MEDIA_NEXT -> handleSessionAction(PlaybackConstants.ACTION_NEXT)
          KeyEvent.KEYCODE_MEDIA_PREVIOUS -> handleSessionAction(PlaybackConstants.ACTION_PREVIOUS)
          KeyEvent.KEYCODE_MEDIA_PLAY -> {
            play()
            true
          }
          KeyEvent.KEYCODE_MEDIA_PAUSE -> {
            pause()
            true
          }
          KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
            handleNotificationAction(PlaybackConstants.ACTION_TOGGLE_PLAYBACK)
            true
          }
          else -> false
        }
      }
    }

  private val progressRunnable =
    object : Runnable {
      override fun run() {
        val p = player
        if (p != null && p.isPlaying) {
          emitProgressChanged()
        }
        if (p != null && p.currentMediaItem != null) {
          mainHandler.postDelayed(this, 250L)
        }
      }
    }

  init {
    AudioCacheProvider.setDiagnosticListener { tag, message ->
      mainHandler.post { emitDiagnosticLog(tag, message) }
    }
  }

  companion object {
    private const val TAG = "PlaybackManager"
    private const val NOTIFICATION_ICON_FONT_ASSET = "iconfont_notification.ttf"
    private const val ICON_GLYPH_LYRIC = "\uE600"
    private const val ICON_GLYPH_FAVORITE_FILLED = "\uE601"
    private const val ICON_GLYPH_FAVORITE_OUTLINE = "\uE60A"
    private const val ICON_GLYPH_PREVIOUS = "\uE63C"
    private const val ICON_GLYPH_PLAY = "\uE63D"
    private const val ICON_GLYPH_NEXT = "\uE63E"
    private const val ICON_GLYPH_PAUSE = "\uE65F"
    private const val FAVORITE_REQUEST_MAX_ATTEMPTS = 3
    private const val FAVORITE_REQUEST_RETRY_DELAY_MS = 350L
    private const val SEEK_STATE_GRACE_MS = 4000L
    private const val CONTENT_MIME_CACHE_MAX_SIZE = 1024
    private const val PROMOTE_AFTER_MS = 10_000L
    private const val NATIVE_ERROR_RECOVERY_MAX_ATTEMPTS = 2

    /** 播放该时长后才调度歌曲缓存下载，跳歌频繁场景避免浪费带宽。 */
    private const val SONG_CACHE_DOWNLOAD_DELAY_MS = 30_000L

    /** FM 已播去重集合容量。 */
    private const val FM_PLAYED_MAX_SIZE = 16

    @Volatile
    private var instance: PlaybackManager? = null

    @JvmStatic
    fun getInstance(context: Context): PlaybackManager =
      instance ?: synchronized(this) {
        instance ?: PlaybackManager(context).also { instance = it }
      }
  }

  @Synchronized
  fun attachService(playbackService: PlaybackService) {
    service = playbackService
    serviceStarted = true
    ensureInitialized()
    updateNotification()
  }

  @Synchronized
  fun detachService(playbackService: PlaybackService) {
    if (service === playbackService) {
      service = null
      serviceStarted = false
    }
  }

  @Synchronized
  fun attachPlugin(playbackPlugin: AndroidNativePlaybackPlugin) {
    plugin = playbackPlugin
    emitPlaybackState(true)
  }

  @Synchronized
  fun detachPlugin(playbackPlugin: AndroidNativePlaybackPlugin) {
    if (plugin === playbackPlugin) {
      plugin = null
      visualizerRequested = false
      fftAudioProcessor.setListener(null)
    }
  }

  /** 插件生命周期上报 WebView 前后台；隐藏期间高频事件静默，避免唤醒冻结中的 WebView 渲染。 */
  fun setWebViewVisible(visible: Boolean) {
    webViewVisible = visible
  }

  @Synchronized
  fun load(
    url: String?,
    positionMs: Long,
    autoPlay: Boolean,
  ): JSObject {
    Log.d(TAG, "load: url=$url positionMs=$positionMs autoPlay=$autoPlay")
    ensureInitialized()
    ensureServiceRunning()

    remoteMode = false
    currentSource = url ?: ""
    currentMetadata.url = currentSource

    cancelPromotion()

    if (currentSource.isNotEmpty()) {
      try {
        val parsed = Uri.parse(currentSource)
        val scheme = parsed.scheme
        if (scheme == "http" || scheme == "https") {
          val cacheKey = AudioCacheProvider.resolveCacheKey(parsed, currentMetadata.songId)
          val sourceSnapshot = currentSource
          val ttlIndex = AudioPrefetchTtlIndex.getInstance(appContext)
          ttlIndex.markAccess(cacheKey)
          if (!ttlIndex.isPromoted(cacheKey)) {
            schedulePromotion(cacheKey, sourceSnapshot)
          }
        }
      } catch (ignored: Throwable) {
      }
    }

    clearPendingSeek()
    lastKnownPositionMs = 0L
    resolveTokenCounter.incrementAndGet()
    // 切歌重置过期重试标记，避免旧 generation 误伤新 track；按 track 的 Exactly 一次语义由 tryHandleUrlExpireRetry 内 songId 比对保证
    urlExpireRetryGeneration.incrementAndGet()
    urlExpireRetried = false
    urlExpireRetriedSongId = 0L
    cancelSongCacheDownload()

    val p = player ?: return buildState()
    p.setMediaItem(buildMediaItem(currentSource))
    p.prepare()
    durationCalibratedForSource = ""
    if (positionMs > 0) {
      p.seekTo(positionMs)
      beginPendingSeek(positionMs)
    }
    p.playWhenReady = autoPlay
    updateNotification()
    emitPlaybackState(true)
    return buildState()
  }

  @Synchronized
  fun play(): JSObject {
    ensureInitialized()
    ensureServiceRunning()
    player?.play()
    updateNotification()
    emitPlaybackState(true)
    return buildState()
  }

  @Synchronized
  fun pause(): JSObject {
    ensureInitialized()
    player?.pause()
    updateNotification()
    emitPlaybackState(true)
    return buildState()
  }

  @Synchronized
  fun stop(): JSObject {
    ensureInitialized()
    player?.pause()
    player?.seekTo(0L)
    clearPendingSeek()
    lastKnownPositionMs = 0L
    stopProgressUpdates()
    emitPlaybackState(true)
    return buildState()
  }

  private fun schedulePromotion(
    cacheKey: String,
    sourceSnapshot: String,
  ) {
    cancelPromotion()
    val urlSnapshot = sourceSnapshot
    val holder = arrayOfNulls<Runnable>(1)
    val r =
      Runnable {
        val curSource = currentSource
        if (urlSnapshot != curSource) {
          if (pendingPromotionRunnable === holder[0]) pendingPromotionRunnable = null
          return@Runnable
        }
        val p = player
        if (p != null && !p.isPlaying && !p.playWhenReady) {
          if (pendingPromotionRunnable === holder[0]) pendingPromotionRunnable = null
          return@Runnable
        }
        if (pendingPromotionRunnable === holder[0]) pendingPromotionRunnable = null
        AudioCacheProvider.prefetchUrlFull(appContext, urlSnapshot)
        Log.d(TAG, "promotion scheduled (download starts): $cacheKey")
      }
    holder[0] = r
    pendingPromotionRunnable = r
    mainHandler.postDelayed(r, PROMOTE_AFTER_MS)
  }

  private fun cancelPromotion() {
    val r = pendingPromotionRunnable
    if (r != null) {
      mainHandler.removeCallbacks(r)
      pendingPromotionRunnable = null
    }
  }

  @Synchronized
  fun shutdownAll() {
    try {
      cleanup()
    } catch (e: Exception) {
      Log.w(TAG, "shutdownAll cleanup failed", e)
    }
    unregisterAudioBecomingNoisyReceiver()
    try {
      val island = Intent(appContext, DynamicIslandService::class.java)
      appContext.stopService(island)
      dynamicIslandService = null
    } catch (e: Exception) {
      Log.w(TAG, "shutdownAll stop dynamic island failed", e)
    }
    try {
      val playback = Intent(appContext, PlaybackService::class.java)
      appContext.stopService(playback)
      serviceStarted = false
    } catch (e: Exception) {
      Log.w(TAG, "shutdownAll stop playback service failed", e)
    }
  }

  @Synchronized
  fun cleanup(): JSObject {
    ensureInitialized()
    val p = player
    if (p != null) {
      p.pause()
      p.seekTo(0L)
      p.stop()
      p.clearMediaItems()
    }
    currentSource = ""
    cancelPromotion()
    clearPendingSeek()
    lastKnownPositionMs = 0L
    playbackQueue.replace(null, -1, PlaybackQueue.RepeatMode.OFF, false)
    durationCalibratedForSource = ""
    resolveTokenCounter.incrementAndGet()
    urlExpireRetryGeneration.incrementAndGet()
    urlExpireRetried = false
    urlExpireRetriedSongId = 0L
    cancelSongCacheDownload()
    stopProgressUpdates()
    clearNotification()
    emitPlaybackState(true)
    return buildState()
  }

  @Synchronized
  fun seek(positionMs: Long): JSObject {
    ensureInitialized()
    val safePositionMs = max(0L, positionMs)
    beginPendingSeek(safePositionMs)
    remoteMode = false

    val p = player
    if (p != null) {
      if (p.currentMediaItem == null || currentSource.isEmpty() || p.playbackState == Player.STATE_IDLE) {
        val wasPlaying = p.playWhenReady
        p.setMediaItem(buildMediaItem(currentSource), safePositionMs)
        p.prepare()
        p.playWhenReady = wasPlaying
      } else {
        p.seekTo(safePositionMs)
      }
    }

    updateNotification()
    emitPlaybackState(true)
    return buildState()
  }

  @Synchronized
  fun setVolume(volume: Float) {
    ensureInitialized()
    player?.volume = volume.coerceIn(0f, 1f)
    emitPlaybackState(false)
    updateNotification()
  }

  @Synchronized
  fun setRate(rate: Float) {
    ensureInitialized()
    player?.setPlaybackSpeed(rate.coerceIn(0.25f, 3f))
    emitPlaybackState(false)
    updateNotification()
  }

  @Synchronized
  fun updateMetadata(metadata: TrackMetadata?) {
    currentMetadata = metadata ?: TrackMetadata()
    loadCoverBitmapAsync(currentMetadata.coverUrl)
    refreshCurrentMediaItemMetadata()
    updateMediaSessionButtons()
    updateNotification()
    emitPlaybackState(true)
  }

  @Synchronized
  fun updateQueueContext(
    likedState: Boolean,
    canSkipPreviousState: Boolean,
    personalFmModeState: Boolean,
    controllerEnabledState: Boolean,
    desktopLyricButtonEnabledState: Boolean,
    desktopLyricEnabledState: Boolean,
    queueTracks: List<PlaybackQueue.Track>?,
    queueCurrentIndex: Int,
    repeatMode: String,
    fmRecentSongIds: List<Long>,
  ) {
    liked = likedState
    currentMetadata.liked = likedState
    canSkipPrevious = canSkipPreviousState
    personalFmMode = personalFmModeState
    controllerEnabled = controllerEnabledState
    desktopLyricButtonEnabled = desktopLyricButtonEnabledState
    desktopLyricEnabled = desktopLyricEnabledState

    playbackQueue.replace(
      queueTracks,
      queueCurrentIndex,
      PlaybackQueue.RepeatMode.fromString(repeatMode),
      personalFmModeState,
    )

    seedFmPlayed(fmRecentSongIds)
    prefetchUpcomingUrls()
    updateMediaSessionButtons()
    updateNotification()
    emitPlaybackState(false)
  }

  @Synchronized
  fun updateNotificationPrefs(
    controllerEnabledState: Boolean,
    desktopLyricButtonEnabledState: Boolean,
  ) {
    controllerEnabled = controllerEnabledState
    desktopLyricButtonEnabled = desktopLyricButtonEnabledState
    updateMediaSessionButtons()
    updateNotification()
  }

  @Synchronized
  fun setAllowMixWithOthers(allow: Boolean) {
    allowMixWithOthers = allow
    player?.setAudioAttributes(
      AudioAttributes
        .Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build(),
      !allowMixWithOthers,
    )
  }

  @Synchronized
  fun setPauseOnDeviceSwitch(enabled: Boolean) {
    pauseOnDeviceSwitch = enabled
  }

  @Synchronized
  fun syncApiContext(
    baseUrl: String?,
    cookieValue: String?,
    level: String?,
    rawLevel: String?,
    disableAiAudioState: Boolean,
    playSongDemoState: Boolean,
    songCacheEnabledState: Boolean,
    pluginSourcesState: Map<String, List<String>>,
  ) {
    apiBaseUrl = baseUrl?.trim() ?: ""
    cookie = cookieValue?.trim() ?: ""
    disableAiAudio = disableAiAudioState
    playSongDemo = playSongDemoState
    songCacheEnabled = songCacheEnabledState
    if (!level.isNullOrEmpty()) {
      songLevel = level
    }
    urlResolver.updateContext(
      apiBaseUrl,
      cookie,
      songLevel,
      rawLevel,
      disableAiAudio,
      playSongDemo,
      songCacheEnabledState,
      pluginSourcesState,
    )
    prefetchUpcomingUrls()
  }

  @Synchronized
  fun syncRemoteState(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
  ) {
    ensureInitialized()
    ensureServiceRunning()
    remoteMode = true
    remoteIsPlaying = playing
    remotePositionMs = max(0L, positionMs)
    remoteDurationMs = max(0L, durationMs)
    remoteAnchorNano = System.nanoTime()
    mainLyricClockListener?.invoke()
    updateRemoteWakeLock()
    updateNotification()
  }

  private fun isEffectivelyPlaying(): Boolean {
    if (remoteMode) return remoteIsPlaying
    return player?.isPlaying == true
  }

  private fun isEffectivelyBuffering(): Boolean {
    if (remoteMode) return false
    return player?.playbackState == Player.STATE_BUFFERING
  }

  private fun updateRemoteWakeLock() {
    if (remoteMode && remoteIsPlaying) {
      if (remoteWakeLock == null) {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        remoteWakeLock =
          pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SPlayer::RemoteAudio").apply {
            setReferenceCounted(false)
          }
      }
      remoteWakeLock?.let {
        if (!it.isHeld) {
          it.acquire(4 * 60 * 60 * 1000L)
        }
      }
    } else {
      remoteWakeLock?.let {
        if (it.isHeld) it.release()
      }
    }
  }

  @Synchronized
  fun buildState(): JSObject {
    ensureInitialized()
    val state = JSObject()
    val p = player
    state.put("src", currentSource)
    state.put("songId", currentMetadata.songId)
    state.put("paused", p == null || !p.playWhenReady || !p.isPlaying)
    state.put("ready", p != null && p.playbackState == Player.STATE_READY)
    state.put("playing", p?.isPlaying == true)
    state.put("buffering", p != null && p.playbackState == Player.STATE_BUFFERING)
    state.put("durationMs", getDurationMs())
    state.put("positionMs", getPositionMs())
    state.put("volume", p?.volume ?: 1f)
    state.put("playbackRate", p?.playbackParameters?.speed ?: 1f)
    state.put("errorCode", 0)
    return state
  }

  @Synchronized
  fun getLyricPositionMs(): Long {
    if (!remoteMode) return getPositionMs()
    val elapsedMs =
      if (remoteIsPlaying) {
        (System.nanoTime() - remoteAnchorNano) / 1_000_000L
      } else {
        0L
      }
    val positionMs = max(0L, remotePositionMs + elapsedMs)
    return if (remoteDurationMs > 0L) positionMs.coerceAtMost(remoteDurationMs) else positionMs
  }

  @Synchronized
  fun getLyricPlaybackRate(): Float {
    if (remoteMode) return 1f
    return player?.playbackParameters?.speed ?: 1f
  }

  @Synchronized
  fun setMainLyricClockListener(listener: (() -> Unit)?) {
    mainLyricClockListener = listener
  }

  @Synchronized
  fun handleNotificationAction(action: String?) {
    if (action == null) return
    Log.d(
      TAG,
      "notification action=$action fm=$personalFmMode queueSize=${playbackQueue.size()}",
    )

    when (action) {
      PlaybackConstants.ACTION_TOGGLE_PLAYBACK -> {
        if (remoteMode) {
          emitCustomAction(if (remoteIsPlaying) "pause" else "play", null, null, null, null, true, null)
        } else {
          val p = player ?: return
          if (p.isPlaying) pause() else play()
        }
      }
      PlaybackConstants.ACTION_NEXT -> {
        val next = playbackQueue.advanceRaw(false)
        if (next != null) {
          resolveAndPlayAsync(next, "next", true, 5)
          return
        }
        if (personalFmMode) {
          requestFmRefill("next")
          return
        }
        Log.w(TAG, "advance returned null: queue empty or all skipped")
      }
      PlaybackConstants.ACTION_PREVIOUS -> {
        if (personalFmMode) {
          // FM 无上一首语义，与 JS prevTrack 的 FM no-op 行为对齐
          Log.d(TAG, "previous ignored in personal fm mode")
          return
        }
        val prev = playbackQueue.backRaw()
        if (prev != null) {
          resolveAndPlayAsync(prev, "previous", false, 3)
        } else {
          Log.w(TAG, "back returned null: queue empty or all skipped")
        }
      }
      PlaybackConstants.ACTION_FAVORITE -> toggleFavoriteAsync()
      PlaybackConstants.ACTION_DESKTOP_LYRIC -> {
        desktopLyricEnabled = !desktopLyricEnabled
        if (desktopLyricEnabled) showDynamicIsland() else hideDynamicIsland()
        updateMediaSessionButtons()
        updateNotification()
        emitCustomAction("dynamicIsland", null, null, desktopLyricEnabled, null, true, null)
      }
      PlaybackConstants.ACTION_COLLAPSE -> {
        collapsed = !collapsed
        updateNotification()
        emitCustomAction("collapse", null, null, null, collapsed, true, null)
      }
    }
  }

  private fun handleSessionAction(action: String?): Boolean {
    if (action == null) return false
    return when (action) {
      PlaybackConstants.ACTION_NEXT, PlaybackConstants.ACTION_PREVIOUS -> {
        handleNotificationAction(action)
        true
      }
      PlaybackConstants.ACTION_FAVORITE -> {
        if (!currentMetadata.canLike) return false
        handleNotificationAction(action)
        true
      }
      PlaybackConstants.ACTION_DESKTOP_LYRIC -> {
        if (!desktopLyricButtonEnabled) return false
        handleNotificationAction(action)
        true
      }
      else -> false
    }
  }

  private fun handleCustomCommand(customCommand: SessionCommand?): SessionResult {
    if (customCommand?.customAction == null) {
      return SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
    }
    return if (handleSessionAction(customCommand.customAction)) {
      SessionResult(SessionResult.RESULT_SUCCESS)
    } else {
      SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
    }
  }

  @Synchronized
  private fun ensureInitialized() {
    if (player != null && session != null) return

    createNotificationChannel()

    val renderersFactory =
      object : DefaultRenderersFactory(appContext) {
        override fun buildAudioSink(
          context: Context,
          enableFloatOutput: Boolean,
          enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink =
          DefaultAudioSink
            .Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(fftAudioProcessor, eqAudioProcessor))
            .build()
      }

    val cachedFactory = AudioCacheProvider.buildCachedDataSourceFactory(appContext)
    val mediaSourceFactory =
      DefaultMediaSourceFactory(
        cachedFactory,
        DefaultExtractorsFactory()
          .setConstantBitrateSeekingEnabled(true)
          .setConstantBitrateSeekingAlwaysEnabled(true),
      )

    val newPlayer =
      ExoPlayer
        .Builder(appContext, renderersFactory)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()

    newPlayer.setAudioAttributes(
      AudioAttributes
        .Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build(),
      !allowMixWithOthers,
    )
    newPlayer.setHandleAudioBecomingNoisy(false)
    newPlayer.setWakeMode(C.WAKE_MODE_NETWORK)

    newPlayer.addListener(
      object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
          if (playbackState == Player.STATE_ENDED) {
            stopProgressUpdates()
            // 注意：handleAutoAdvanceOnEnded 如果返回了 true，意味着 Java 层已经发起了自动下一曲的解析或切换
            if (!handleAutoAdvanceOnEnded()) {
              emitEnded()
            }
          } else if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
            startProgressUpdates()
            if (playbackState == Player.STATE_READY) calibrateDurationFromPlayer()
          }
          updateNotification()
          emitPlaybackState(true)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
          if (isPlaying) startProgressUpdates()
          updateNotification()
          emitPlaybackState(true)
        }

        override fun onPositionDiscontinuity(
          oldPosition: Player.PositionInfo,
          newPosition: Player.PositionInfo,
          reason: Int,
        ) {
          if (reason == Player.DISCONTINUITY_REASON_SEEK ||
            reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION ||
            reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
          ) {
            emitProgressChanged(max(0L, newPosition.positionMs), true, true)
            updateNotification()
            emitPlaybackState(true)
          }
        }

        override fun onPlayerError(error: PlaybackException) {
          if (tryHandleUrlExpireRetry(error)) return
          if (recoverCurrentTrackAfterError(error)) return
          emitError(error.errorCode, error.message)
          updateNotification()
        }
      },
    )

    player = newPlayer

    sessionPlayer =
      object : ForwardingPlayer(newPlayer) {
        override fun getAvailableCommands(): Player.Commands =
          Player.Commands
            .Builder()
            .addAll(super.getAvailableCommands())
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

        override fun seekToPrevious() {
          handleSessionAction(PlaybackConstants.ACTION_PREVIOUS)
        }

        override fun seekToPreviousMediaItem() {
          handleSessionAction(PlaybackConstants.ACTION_PREVIOUS)
        }

        override fun seekToNext() {
          handleSessionAction(PlaybackConstants.ACTION_NEXT)
        }

        override fun seekToNextMediaItem() {
          handleSessionAction(PlaybackConstants.ACTION_NEXT)
        }
      }

    session =
      MediaSession
        .Builder(appContext, sessionPlayer!!)
        .setSessionActivity(buildContentIntent())
        .setCallback(mediaSessionCallback)
        .setCustomLayout(buildCustomLayout())
        .setMediaButtonPreferences(buildMediaButtonPreferences())
        .setPeriodicPositionUpdateEnabled(true)
        .build()

    updateMediaSessionButtons()
    coverBitmap = BitmapFactory.decodeResource(appContext.resources, R.mipmap.ic_launcher)
    coverArtworkBytes = encodeArtworkBytes(coverBitmap)
    registerAudioBecomingNoisyReceiver()
  }

  /**
   * 注册音频变嘈杂（耳机/蓝牙断连）广播接收器，仅在 pauseOnDeviceSwitch 开启时暂停播放
   */
  private fun registerAudioBecomingNoisyReceiver() {
    if (audioBecomingNoisyReceiver != null) return
    val receiver =
      object : BroadcastReceiver() {
        override fun onReceive(
          context: Context?,
          intent: Intent?,
        ) {
          if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
          if (!pauseOnDeviceSwitch) return
          mainHandler.post { pause() }
        }
      }
    val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    } else {
      appContext.registerReceiver(receiver, filter)
    }
    audioBecomingNoisyReceiver = receiver
  }

  /**
   * 注销音频变嘈杂广播接收器
   */
  private fun unregisterAudioBecomingNoisyReceiver() {
    val receiver = audioBecomingNoisyReceiver ?: return
    try {
      appContext.unregisterReceiver(receiver)
    } catch (e: Exception) {
      Log.w(TAG, "unregisterAudioBecomingNoisyReceiver failed", e)
    }
    audioBecomingNoisyReceiver = null
  }

  private fun calibrateDurationFromPlayer() {
    val p = player ?: return
    if (currentSource.isEmpty()) return
    val realDurationMs = p.duration
    if (realDurationMs == C.TIME_UNSET || realDurationMs <= 0L) return

    val stabilityThresholdMs = 1000L
    if (currentSource == durationCalibratedForSource &&
      Math.abs(realDurationMs - currentMetadata.durationMs) <= stabilityThresholdMs
    ) {
      return
    }
    durationCalibratedForSource = currentSource
    if (Math.abs(realDurationMs - currentMetadata.durationMs) <= 500L) {
      return
    }
    currentMetadata.durationMs = realDurationMs
    refreshCurrentMediaItemMetadata()
  }

  private fun encodeArtworkBytes(bitmap: Bitmap?): ByteArray? {
    if (bitmap == null) return null
    try {
      val baos = ByteArrayOutputStream()
      if (bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)) {
        return baos.toByteArray()
      }
    } catch (e: Exception) {
      Log.w(TAG, "encodeArtworkBytes failed", e)
    }
    return null
  }

  private fun ensureServiceRunning() {
    if (serviceStarted && service != null) return
    val intent = Intent(appContext, PlaybackService::class.java)
    ContextCompat.startForegroundService(appContext, intent)
  }

  private fun buildMediaItem(url: String?): MediaItem {
    val builder = MediaItem.Builder()
    if (!url.isNullOrEmpty()) {
      val uri = Uri.parse(url)
      builder.setUri(uri)
      if ("content" == uri.scheme) {
        resolveContentMimeType(uri)?.let { builder.setMimeType(it) }
      }
    }
    builder.setMediaMetadata(buildMediaMetadata())
    return builder.build()
  }

  private fun resolveContentMimeType(uri: Uri): String? {
    val key = uri.toString()
    val cached = contentMimeCache[key]
    if (cached != null) {
      return if (cached.isEmpty()) null else cached
    }
    return try {
      val resolver = appContext.contentResolver
      val mime = resolver.getType(uri)
      contentMimeCache[key] = mime ?: ""
      mime
    } catch (error: Exception) {
      Log.w(TAG, "resolveContentMimeType failed", error)
      contentMimeCache[key] = ""
      null
    }
  }

  private fun buildMediaMetadata(): MediaMetadata {
    val builder = MediaMetadata.Builder()
    if (currentMetadata.title.isNotEmpty()) builder.setTitle(currentMetadata.title)
    if (currentMetadata.artist.isNotEmpty()) builder.setArtist(currentMetadata.artist)
    if (currentMetadata.album.isNotEmpty()) builder.setAlbumTitle(currentMetadata.album)

    var resolvedDurationMs = currentMetadata.durationMs
    player?.let {
      val realDurationMs = it.duration
      if (realDurationMs != C.TIME_UNSET && realDurationMs > 0L) {
        resolvedDurationMs = realDurationMs
      }
    }
    if (resolvedDurationMs > 0L) builder.setDurationMs(resolvedDurationMs)

    val artworkUrl = normalizeMediaUrl(currentMetadata.coverUrl)
    if (!artworkUrl.isNullOrEmpty() && !artworkUrl.startsWith("blob:")) {
      try {
        builder.setArtworkUri(Uri.parse(artworkUrl))
      } catch (ignored: Exception) {
      }
    }
    coverArtworkBytes?.let {
      builder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
    }
    return builder.build()
  }

  private fun refreshCurrentMediaItemMetadata() {
    val p = player ?: return
    val current = p.currentMediaItem ?: return
    try {
      val updated = current.buildUpon().setMediaMetadata(buildMediaMetadata()).build()
      val index = p.currentMediaItemIndex
      if (index in 0 until p.mediaItemCount) {
        p.replaceMediaItem(index, updated)
      }
    } catch (e: Exception) {
      Log.w(TAG, "refreshCurrentMediaItemMetadata failed", e)
    }
  }

  private fun handleAutoAdvanceOnEnded(): Boolean {
    val next = playbackQueue.advanceRaw(true)
    if (next != null) {
      resolveAndPlayAsync(next, "auto", true, 5)
      return true
    }
    if (personalFmMode) {
      requestFmRefill("auto")
      return true
    }
    return false
  }

  /**
   * 解析并播放下一首；解析失败时按方向继续跳曲，重试耗尽后停止播放并上报错误
   * （切歌权威全原生，不再委托 JS）。
   */
  private fun resolveAndPlayAsync(
    next: PlaybackQueue.Track?,
    source: String,
    forward: Boolean,
    remainAttempts: Int,
  ) {
    if (next == null) {
      if (forward && personalFmMode) {
        requestFmRefill(source)
      } else {
        Log.w(TAG, "resolveAndPlayAsync terminal: no playable track, source=$source")
      }
      return
    }
    if (next.playable()) {
      playFromQueue(next, source)
      prefetchUpcomingUrls()
      return
    }
    if (remainAttempts <= 0) {
      stopAndEmitResolveFailure()
      return
    }

    val target = next
    val myToken = resolveTokenCounter.incrementAndGet()
    urlResolver.submitResolve(target) { url ->
      mainHandler.post {
        if (myToken != resolveTokenCounter.get()) return@post
        if (url != null) {
          target.url = url
          playbackQueue.updateTrackUrl(target, url)
          playFromQueue(target, source)
          prefetchUpcomingUrls()
        } else if (forward) {
          Log.w(TAG, "resolveAndPlayAsync failed, skip forward")
          val again = playbackQueue.advanceRaw(false)
          resolveAndPlayAsync(again, source, true, remainAttempts - 1)
        } else {
          Log.w(TAG, "resolveAndPlayAsync failed, skip backward")
          val again = playbackQueue.backRaw()
          resolveAndPlayAsync(again, source, false, remainAttempts - 1)
        }
      }
    }
  }

  /** 解析重试耗尽的终局：暂停播放并经 error 事件上抛，JS 仅做 UI 提示。 */
  private fun stopAndEmitResolveFailure() {
    Log.w(TAG, "resolve exhausted, stop playback")
    try {
      player?.pause()
    } catch (ignored: Exception) {
    }
    emitError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, "resolve failed after retries")
    updateNotification()
  }

  private fun prefetchUpcomingUrls() {
    val resolvedUrls = playbackQueue.peekUpcomingResolvedUrls(2)
    for (url in resolvedUrls) {
      AudioCacheProvider.prefetchUrl(appContext, url)
    }

    val upcoming = playbackQueue.peekUpcomingUnresolved(3)
    for (t in upcoming) {
      urlResolver.prefetchAsync(t, playbackQueue) {
        val resolvedUrl = playbackQueue.findUrlForTrack(t)
        if (!resolvedUrl.isNullOrEmpty()) {
          AudioCacheProvider.prefetchUrl(appContext, resolvedUrl)
        }
      }
    }
  }

  private fun playFromQueue(
    track: PlaybackQueue.Track?,
    source: String,
  ) {
    if (track == null || !track.playable()) return
    resolveTokenCounter.incrementAndGet()
    fmRefillGuard = false
    nativeRecoverySongId = 0L
    nativeRecoveryAttempts = 0
    // 切歌重置过期重试状态，下一首可重新获得 Exactly 一次机会；同时递增 generation 废弃旧重试回调
    urlExpireRetryGeneration.incrementAndGet()
    urlExpireRetried = false
    urlExpireRetriedSongId = 0L

    val metadata = trackToMetadata(track)
    startTrackFromState(track.url, metadata, track.liked, true)

    if (personalFmMode && track.songId > 0) {
      markFmPlayed(track.songId)
    }
    scheduleSongCacheDownload(track)

    plugin?.let {
      val payload = JSObject()
      payload.put("action", "trackChanged")
      payload.put("success", true)
      payload.put("songId", track.songId)
      payload.put("playListIndex", track.playListIndex)
      payload.put("source", source)
      payload.put("liked", track.liked)
      payload.put("url", track.url)
      payload.put("title", track.title)
      payload.put("artist", track.artist)
      payload.put("album", track.album)
      payload.put("coverUrl", track.coverUrl)
      payload.put("durationMs", track.durationMs)
      it.emitEvent("customAction", payload, true)
    }
  }

  /**
   * 播放指定索引的曲目（JS 点击任意歌曲入口），原生完成解析与开播。
   *
   * @param index 队列索引
   * @param positionMs 起播进度（恢复播放/热重载场景），0 表示从头播
   */
  @Synchronized
  fun playIndexAt(
    index: Int,
    positionMs: Long,
  ) {
    ensureInitialized()
    ensureServiceRunning()
    Log.d(TAG, "playIndexAt index=$index positionMs=$positionMs fm=$personalFmMode")
    val track = playbackQueue.trackAt(index)
    if (track == null) {
      // 越界必须上抛 error：插件调用仍会正常 resolve，JS 只能靠该事件复位 trackLoading
      Log.w(TAG, "playIndexAt out of bounds: index=$index size=${playbackQueue.size()}")
      emitError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, "playIndex out of bounds: $index")
      return
    }
    // 锚定游标到点击曲目：否则解析失败跳曲、后续 next/prev、预取 upcoming 都仍以旧曲目为基准
    if (!personalFmMode) playbackQueue.setCurrentIndex(index)
    if (track.playable()) {
      playFromQueue(track, "index")
      restoreSeekAfterStart(positionMs)
      prefetchUpcomingUrls()
      return
    }
    val myToken = resolveTokenCounter.incrementAndGet()
    urlResolver.submitResolve(track) { url ->
      mainHandler.post {
        if (myToken != resolveTokenCounter.get()) return@post
        if (url == null) {
          // 点击曲目解析失败：跳下一首（复用既有跳曲链路，重试 4 次）
          val again = playbackQueue.advanceRaw(false)
          resolveAndPlayAsync(again, "index", true, 4)
          return@post
        }
        track.url = url
        playbackQueue.updateTrackUrl(track, url)
        playFromQueue(track, "index")
        restoreSeekAfterStart(positionMs)
        prefetchUpcomingUrls()
      }
    }
  }

  /** 开播后恢复进度（ExoPlayer 会把 seek 排队到 prepare 之后生效）。 */
  private fun restoreSeekAfterStart(positionMs: Long) {
    if (positionMs <= 0) return
    player?.seekTo(positionMs)
    beginPendingSeek(positionMs)
  }

  /**
   * FM 续池：拉取一批个人 FM 曲目追加进队列后继续推进；带护栏防止
   * 解析全失败时的无限续池循环。排除集在主线程快照，拉取在后台线程执行。
   */
  private fun requestFmRefill(source: String) {
    if (fmRefillGuard) {
      Log.w(TAG, "fm refill guarded, give up")
      stopAndEmitResolveFailure()
      return
    }
    fmRefillGuard = true
    Log.d(TAG, "fm refill start, source=$source")
    val exclude = playbackQueue.songIdSnapshot() + fmPlayedSongIds
    val baseUrlSnapshot = apiBaseUrl
    val cookieSnapshot = cookie
    networkExecutor.execute {
      val fetched = fmFetcher.fetch(baseUrlSnapshot, cookieSnapshot, exclude)
      mainHandler.post {
        if (fetched.isEmpty()) {
          Log.w(TAG, "fm refill empty or failed")
          stopAndEmitResolveFailure()
          return@post
        }
        playbackQueue.append(fetched)
        val next = playbackQueue.advanceRaw(false)
        resolveAndPlayAsync(next, source, true, 5)
      }
    }
  }

  /** FM 已播集合播种（JS 上下文推送），容量有限。 */
  private fun seedFmPlayed(ids: List<Long>) {
    if (ids.isEmpty()) return
    for (id in ids) {
      if (fmPlayedSongIds.size >= FM_PLAYED_MAX_SIZE && id !in fmPlayedSongIds) {
        val oldest = fmPlayedSongIds.iterator().next()
        fmPlayedSongIds.remove(oldest)
      }
      fmPlayedSongIds.add(id)
    }
  }

  private fun markFmPlayed(songId: Long) {
    if (fmPlayedSongIds.size >= FM_PLAYED_MAX_SIZE && songId !in fmPlayedSongIds) {
      val oldest = fmPlayedSongIds.iterator().next()
      fmPlayedSongIds.remove(oldest)
    }
    fmPlayedSongIds.add(songId)
  }

  /**
   * 播放成功后延迟调度歌曲缓存下载（缓存开启 + 在线 URL 时），
   * 切歌时由 cancelSongCacheDownload 取消上一个。
   */
  private fun scheduleSongCacheDownload(track: PlaybackQueue.Track) {
    cancelSongCacheDownload()
    val url = track.url ?: return
    if (!songCacheEnabled || track.songId <= 0) return
    if (!url.startsWith("http")) return
    val cacheKey = urlResolver.songCacheKeyFor(track) ?: return
    val songId = track.songId
    val r =
      Runnable {
        Log.d(TAG, "song cache download start songId=$songId")
        songCacheExecutor.execute {
          SongCacheFetcher.download(appContext, cacheKey, url)
        }
      }
    pendingSongCacheRunnable = r
    mainHandler.postDelayed(r, SONG_CACHE_DOWNLOAD_DELAY_MS)
  }

  private fun cancelSongCacheDownload() {
    val r = pendingSongCacheRunnable
    if (r != null) {
      mainHandler.removeCallbacks(r)
      pendingSongCacheRunnable = null
    }
  }

  /**
   * 循环解包 PlaybackException cause 链，查找 HttpDataSource.InvalidResponseCodeException。
   * 仅 403/401/410 视为 URL 过期（签名失效），需换新鲜 URL 重试；其余 4xx/5xx 不按过期处理。
   * 不做字符串匹配，避免误判；必须通过 responseCode 精确判断。
   */
  private fun extractUrlExpireResponseCode(error: PlaybackException): Int? {
    var cause: Throwable? = error
    while (cause != null) {
      if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
        val code = cause.responseCode
        if (code == 403 || code == 401 || code == 410) return code
        return null
      }
      // 兼容部分厂商/Exo 版本将 InvalidResponseCodeException 包在 PlaybackException/ExoPlaybackException 内
      cause = cause.cause
    }
    return null
  }

  /**
   * 从 URL 提取 host 用于 diagnosticLog，避免泄露签名 query。
   */
  private fun sanitizeHostForLog(url: String): String =
    try {
      Uri.parse(url).host ?: "unknown"
    } catch (_: Exception) {
      "unknown"
    }

  /**
   * URL 过期自愈：当 cause 链含 InvalidResponseCodeException 且 code 为 403/401/410 时，
   * 用 songId 经 urlResolver 取新鲜 URL 重试 Exactly 一次。
   * - 必须换新鲜 URL，禁止复用 currentSource
   * - 单次重试标记按 track 重置（songId 变化自动重置）
   * - 引入 retryGeneration 防并发重试打架
   * - 后台线程 resolve，禁止主线程同步阻塞
   * - 重试时打 diagnosticLog（含 songId+host，不含签名 query）；重试仍失败才上抛 UI
   */
  @Synchronized
  private fun tryHandleUrlExpireRetry(error: PlaybackException): Boolean {
    val expireCode = extractUrlExpireResponseCode(error) ?: return false
    val songId = currentMetadata.songId
    if (songId <= 0) return false
    if (currentSource.isEmpty()) return false
    // 按 track 重置 Exactly 一次语义
    if (urlExpireRetriedSongId != songId) {
      urlExpireRetriedSongId = songId
      urlExpireRetried = false
    }
    if (urlExpireRetried) {
      Log.d(TAG, "url expire retry already done for songId=$songId, skip")
      return false
    }
    urlExpireRetried = true
    val generation = urlExpireRetryGeneration.incrementAndGet()
    val expiredHost = sanitizeHostForLog(currentSource)
    val tokenSnapshot = resolveTokenCounter.get()
    val positionMs = max(0L, getPositionMs())
    val metadataSnapshot = currentMetadata.copy()
    val likedSnapshot = liked
    // 重试时打 diagnosticLog（含 songId+host，不含签名 query）
    emitDiagnosticLog("DIAG-UrlExpireRetry", "songId=$songId host=$expiredHost code=$expireCode gen=$generation retry=1")
    Log.w(TAG, "url expired $expireCode songId=$songId host=$expiredHost gen=$generation, fetch fresh url")
    // 清旧缓存，避免命中过期 URL
    urlResolver.clear(songId)
    networkExecutor.execute {
      // 禁止主线程同步阻塞；走后台执行器取新鲜 URL
      var freshUrl: String? = null
      try {
        freshUrl = urlResolver.resolveDownloadUrlSync(songId, false)?.url
      } catch (_: Exception) {
      }
      if (freshUrl.isNullOrEmpty()) {
        try {
          freshUrl = urlResolver.resolveSync(songId)
        } catch (_: Exception) {
        }
      }
      val resolvedFreshUrl = freshUrl
      mainHandler.post {
        if (generation != urlExpireRetryGeneration.get()) {
          Log.d(TAG, "url expire retry stale gen=$generation current=${urlExpireRetryGeneration.get()} songId=$songId")
          return@post
        }
        if (tokenSnapshot != resolveTokenCounter.get() || currentMetadata.songId != songId) {
          Log.d(TAG, "url expire retry stale track gen=$generation songId=$songId")
          return@post
        }
        if (resolvedFreshUrl.isNullOrEmpty()) {
          Log.w(TAG, "url expire retry no fresh url songId=$songId gen=$generation")
          emitError(error.errorCode, error.message ?: "url expired $expireCode, no fresh url")
          updateNotification()
          return@post
        }
        // 必须换新鲜 URL，禁止直接复用 currentSource
        if (resolvedFreshUrl == currentSource) {
          Log.w(TAG, "url expire retry fresh url same as expired, still try gen=$generation")
        }
        val freshHost = sanitizeHostForLog(resolvedFreshUrl)
        emitDiagnosticLog("DIAG-UrlExpireRetry", "songId=$songId freshHost=$freshHost gen=$generation apply")
        Log.i(TAG, "url expire retry apply fresh host=$freshHost songId=$songId gen=$generation")
        currentSource = resolvedFreshUrl
        currentMetadata = metadataSnapshot.copy().apply { url = resolvedFreshUrl }
        playbackQueue.updateTrackUrl(songId, resolvedFreshUrl)
        liked = likedSnapshot
        clearPendingSeek()
        durationCalibratedForSource = ""
        try {
          player?.setMediaItem(buildMediaItem(resolvedFreshUrl))
          player?.prepare()
          if (positionMs > 0) {
            player?.seekTo(positionMs)
            beginPendingSeek(positionMs)
          }
          player?.play()
        } catch (e: Exception) {
          Log.w(TAG, "url expire retry setMediaItem failed gen=$generation", e)
          emitError(error.errorCode, e.message)
          updateNotification()
          return@post
        }
        updateNotification()
        emitPlaybackState(true)
        emitProgressChanged()
        nativeRecoverySongId = 0L
        nativeRecoveryAttempts = 0
        prefetchUpcomingUrls()
      }
    }
    return true
  }

  private fun recoverCurrentTrackAfterError(error: PlaybackException): Boolean {
    val songId = currentMetadata.songId
    if (songId <= 0 || !currentMetadata.canLike) return false
    if (currentSource.isEmpty()) return false
    if (!isRecoverablePlaybackError(error)) return false
    if (nativeRecoverySongId != songId) {
      nativeRecoverySongId = songId
      nativeRecoveryAttempts = 0
    }
    if (nativeRecoveryAttempts >= NATIVE_ERROR_RECOVERY_MAX_ATTEMPTS) return false
    nativeRecoveryAttempts++

    val positionMs = max(0L, getPositionMs())
    val metadataSnapshot = currentMetadata.copy()
    val likedSnapshot = liked
    Log.w(TAG, "native recover playback error code=${error.errorCode} songId=$songId")

    val myToken = resolveTokenCounter.incrementAndGet()
    urlResolver.clear(songId)
    urlResolver.submitResolve(songId) { url ->
      mainHandler.post {
        if (player == null || myToken != resolveTokenCounter.get()) return@post
        if (url.isNullOrEmpty()) {
          emitError(error.errorCode, error.message)
          updateNotification()
          return@post
        }
        metadataSnapshot.url = url
        currentSource = url
        currentMetadata = metadataSnapshot.copy()
        playbackQueue.updateTrackUrl(songId, url)
        liked = likedSnapshot
        clearPendingSeek()
        durationCalibratedForSource = ""
        player?.setMediaItem(buildMediaItem(url))
        player?.prepare()
        if (positionMs > 0) {
          player?.seekTo(positionMs)
          beginPendingSeek(positionMs)
        }
        player?.play()
        updateNotification()
        emitPlaybackState(true)
        emitProgressChanged()
        nativeRecoverySongId = 0L
        nativeRecoveryAttempts = 0
        prefetchUpcomingUrls()
      }
    }
    return true
  }

  private fun isRecoverablePlaybackError(error: PlaybackException): Boolean {
    val code = error.errorCode
    val isDnsError = error.cause is java.net.UnknownHostException
    return isDnsError ||
      code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
      code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
      code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
      code == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
      code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
      code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
      code == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
      code == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ||
      code == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
  }

  private fun trackToMetadata(track: PlaybackQueue.Track): TrackMetadata {
    val m = TrackMetadata()
    m.songId = track.songId
    m.durationMs = track.durationMs
    m.canLike = track.canLike
    m.liked = track.liked
    m.title = track.title
    m.artist = track.artist
    m.album = track.album
    m.coverUrl = track.coverUrl
    m.url = track.url ?: ""
    return m
  }

  private fun emitDiagnosticLog(
    tag: String,
    message: String,
  ) {
    plugin?.let {
      val payload = JSObject()
      payload.put("tag", tag)
      payload.put("message", message)
      it.emitEvent("diagnosticLog", payload, false)
    }
  }

  private fun startTrackFromState(
    source: String?,
    metadata: TrackMetadata?,
    likedState: Boolean,
    emitProgressImmediately: Boolean,
  ) {
    if (player == null) return
    currentSource = source ?: ""
    currentMetadata = metadata?.copy() ?: TrackMetadata()
    liked = likedState
    clearPendingSeek()
    lastKnownPositionMs = 0L
    durationCalibratedForSource = ""

    player?.setMediaItem(buildMediaItem(currentSource))
    player?.prepare()
    player?.seekTo(0L)
    player?.play()
    loadCoverBitmapAsync(currentMetadata.coverUrl)
    updateMediaSessionButtons()
    updateNotification()
    emitPlaybackState(true)
    if (emitProgressImmediately) {
      emitProgressChanged()
    }
  }

  private fun buildAvailableSessionCommands(): SessionCommands {
    val builder = SessionCommands.Builder().add(nextSessionCommand).add(previousSessionCommand)
    if (currentMetadata.canLike) builder.add(favoriteSessionCommand)
    if (desktopLyricButtonEnabled) builder.add(desktopLyricSessionCommand)
    return builder.build()
  }

  private fun buildAvailablePlayerCommands(): Player.Commands {
    val builder = Player.Commands.Builder().addAll(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
    builder.add(Player.COMMAND_SEEK_TO_NEXT)
    builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)
    builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
    return builder.build()
  }

  private fun buildCustomLayout(): List<CommandButton> = buildMediaButtonPreferences()

  private fun buildMediaButtonPreferences(): List<CommandButton> {
    val buttons = ArrayList<CommandButton>()
    if (!controllerEnabled) return buttons

    if (currentMetadata.canLike) {
      buttons.add(
        CommandButton
          .Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
          .setSessionCommand(favoriteSessionCommand)
          .setDisplayName(appContext.getString(R.string.playback_notification_favorite))
          .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
          .build(),
      )
    }

    buttons.add(
      CommandButton
        .Builder(CommandButton.ICON_PREVIOUS)
        .setSessionCommand(previousSessionCommand)
        .setDisplayName(appContext.getString(R.string.playback_notification_previous))
        .setSlots(CommandButton.SLOT_BACK)
        .build(),
    )

    buttons.add(
      CommandButton
        .Builder(CommandButton.ICON_NEXT)
        .setSessionCommand(nextSessionCommand)
        .setDisplayName(appContext.getString(R.string.playback_notification_next))
        .setSlots(CommandButton.SLOT_FORWARD)
        .build(),
    )

    if (desktopLyricButtonEnabled) {
      buttons.add(
        CommandButton
          .Builder(CommandButton.ICON_SUBTITLES)
          .setSessionCommand(desktopLyricSessionCommand)
          .setDisplayName(appContext.getString(R.string.playback_notification_desktop_lyric))
          .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
          .build(),
      )
    }

    return buttons
  }

  private fun updateMediaSessionButtons() {
    val currentSession = session ?: return
    val customLayout = buildCustomLayout()
    val mediaButtonPreferences = buildMediaButtonPreferences()
    val sessionCommands = buildAvailableSessionCommands()

    currentSession.setCustomLayout(customLayout)
    currentSession.setMediaButtonPreferences(mediaButtonPreferences)
    for (controller in currentSession.connectedControllers) {
      currentSession.setAvailableCommands(controller, sessionCommands, buildAvailablePlayerCommands())
    }
  }

  private fun updateNotification() {
    if (service == null || player == null) return

    if (!remoteMode && player?.currentMediaItem == null && currentSource.isEmpty()) {
      clearNotification()
      return
    }
    if (remoteMode && currentMetadata.title.isEmpty() && currentSource.isEmpty()) {
      clearNotification()
      return
    }

    val notification = buildNotification()
    val p = player
    val userPaused = !remoteMode && p != null && p.playbackState == Player.STATE_READY && !p.playWhenReady
    val remotePaused = remoteMode && !remoteIsPlaying
    val shouldStayForeground = !userPaused && !remotePaused
    try {
      if (shouldStayForeground) {
        service?.startForeground(PlaybackConstants.NOTIFICATION_ID, notification)
      } else {
        service?.let { ServiceCompat.stopForeground(it, ServiceCompat.STOP_FOREGROUND_DETACH) }
        if (appContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
          android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
          NotificationManagerCompat.from(appContext).notify(PlaybackConstants.NOTIFICATION_ID, notification)
        }
      }
    } catch (error: SecurityException) {
      Log.w(TAG, "Failed to show playback notification", error)
    } catch (error: IllegalStateException) {
      if (!isForegroundServiceStartNotAllowed(error)) throw error
      Log.w(TAG, "Failed to enter foreground service from background", error)
      if (appContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
      ) {
        NotificationManagerCompat.from(appContext).notify(PlaybackConstants.NOTIFICATION_ID, notification)
      }
    }
  }

  private fun isForegroundServiceStartNotAllowed(error: IllegalStateException): Boolean {
    val className = error.javaClass.name
    val message = error.message
    return className.contains("ForegroundServiceStartNotAllowedException") ||
      (message != null && message.contains("ForegroundService"))
  }

  private fun buildNotification(): Notification {
    val builder =
      NotificationCompat
        .Builder(appContext, PlaybackConstants.CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentIntent(buildContentIntent())
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(isEffectivelyPlaying() || isEffectivelyBuffering())
        .setContentTitle(safeText(currentMetadata.title, appContext.getString(R.string.app_name)))
        .setContentText(safeText(currentMetadata.artist, ""))
        .setLargeIcon(coverBitmap)

    if (!controllerEnabled) return builder.build()

    var actionCount = 0

    if (currentMetadata.canLike) {
      builder.addAction(
        buildNotificationAction(
          if (liked) ICON_GLYPH_FAVORITE_FILLED else ICON_GLYPH_FAVORITE_OUTLINE,
          if (liked) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off,
          appContext.getString(R.string.playback_notification_favorite),
          PlaybackConstants.ACTION_FAVORITE,
        ),
      )
      actionCount++
    }

    val previousActionIndex = actionCount
    builder.addAction(
      buildNotificationAction(
        ICON_GLYPH_PREVIOUS,
        android.R.drawable.ic_media_previous,
        appContext.getString(R.string.playback_notification_previous),
        PlaybackConstants.ACTION_PREVIOUS,
      ),
    )
    actionCount++

    val playPauseActionIndex = actionCount
    val effectivelyPlaying = isEffectivelyPlaying()
    builder.addAction(
      buildNotificationAction(
        if (effectivelyPlaying) ICON_GLYPH_PAUSE else ICON_GLYPH_PLAY,
        if (effectivelyPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        appContext.getString(R.string.playback_notification_play_pause),
        PlaybackConstants.ACTION_TOGGLE_PLAYBACK,
      ),
    )
    actionCount++

    val nextActionIndex = actionCount
    builder.addAction(
      buildNotificationAction(
        ICON_GLYPH_NEXT,
        android.R.drawable.ic_media_next,
        appContext.getString(R.string.playback_notification_next),
        PlaybackConstants.ACTION_NEXT,
      ),
    )

    if (desktopLyricButtonEnabled) {
      builder.addAction(
        buildNotificationAction(
          ICON_GLYPH_LYRIC,
          if (desktopLyricEnabled) android.R.drawable.presence_audio_online else android.R.drawable.presence_audio_busy,
          appContext.getString(R.string.playback_notification_desktop_lyric),
          PlaybackConstants.ACTION_DESKTOP_LYRIC,
        ),
      )
    }

    val compactActionIndices = intArrayOf(previousActionIndex, playPauseActionIndex, nextActionIndex)

    if (session != null) {
      builder.setStyle(
        androidx.media3.session.MediaStyleNotificationHelper
          .MediaStyle(session!!)
          .setShowActionsInCompactView(*compactActionIndices),
      )
    } else {
      builder.setStyle(
        MediaNotificationCompat
          .MediaStyle()
          .setShowActionsInCompactView(*compactActionIndices),
      )
    }

    return builder.build()
  }

  private fun buildNotificationAction(
    glyph: String?,
    fallbackIconResId: Int,
    title: CharSequence,
    action: String,
  ): NotificationCompat.Action {
    val pendingIntent = buildActionPendingIntent(action)
    val iconBitmap = glyph?.let { renderNotificationGlyph(it) }
    if (iconBitmap != null) {
      return NotificationCompat.Action.Builder(IconCompat.createWithBitmap(iconBitmap), title, pendingIntent).build()
    }
    return NotificationCompat.Action.Builder(fallbackIconResId, title, pendingIntent).build()
  }

  private fun renderNotificationGlyph(glyph: String): Bitmap? {
    val typeface = getNotificationIconTypeface() ?: return null
    val density = appContext.resources.displayMetrics.density
    val bitmapSize = max(48, Math.round(24f * density))
    val textSize = bitmapSize * 0.78f

    val bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    paint.color = Color.WHITE
    paint.typeface = typeface
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = textSize

    val fontMetrics = paint.fontMetrics
    val baseline = (bitmapSize - fontMetrics.ascent - fontMetrics.descent) / 2f
    canvas.drawText(glyph, bitmapSize / 2f, baseline, paint)
    return bitmap
  }

  @Synchronized
  private fun getNotificationIconTypeface(): Typeface? {
    if (notificationIconTypeface != null) return notificationIconTypeface
    return try {
      notificationIconTypeface = Typeface.createFromAsset(appContext.assets, NOTIFICATION_ICON_FONT_ASSET)
      notificationIconTypeface
    } catch (error: Exception) {
      Log.w(TAG, "Failed to load notification icon font", error)
      null
    }
  }

  private fun buildContentIntent(): PendingIntent {
    val intent = Intent(appContext, MainActivity::class.java)
    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(
      appContext,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
    )
  }

  private fun buildActionPendingIntent(action: String): PendingIntent {
    val intent = Intent(appContext, PlaybackActionReceiver::class.java)
    intent.action = action
    return PendingIntent.getBroadcast(
      appContext,
      action.hashCode(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
    )
  }

  private fun immutableFlag(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

  private fun extractKeyEvent(intent: Intent?): KeyEvent? {
    if (intent == null) return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      return intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    }
    @Suppress("DEPRECATION")
    return intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
  }

  @Synchronized
  private fun clearNotification() {
    stopProgressUpdates()
    try {
      service?.let { ServiceCompat.stopForeground(it, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    } catch (error: Exception) {
      Log.w(TAG, "Failed to stop foreground playback service", error)
    }
    NotificationManagerCompat.from(appContext).cancel(PlaybackConstants.NOTIFICATION_ID)
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel =
      NotificationChannel(
        PlaybackConstants.CHANNEL_ID,
        appContext.getString(R.string.playback_notification_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      )
    channel.description = appContext.getString(R.string.playback_notification_channel_description)
    val notificationManager = ContextCompat.getSystemService(appContext, NotificationManager::class.java)
    notificationManager?.createNotificationChannel(channel)
  }

  private fun startProgressUpdates() {
    mainHandler.removeCallbacks(progressRunnable)
    mainHandler.post(progressRunnable)
  }

  private fun stopProgressUpdates() {
    mainHandler.removeCallbacks(progressRunnable)
  }

  private fun emitPlaybackState(retain: Boolean) {
    val state = buildState()
    mainLyricClockListener?.invoke()
    pushDynamicIslandProgress(getLyricPositionMs(), isEffectivelyPlaying())
    plugin?.emitEvent("playbackStateChanged", state, retain)
    // 外部 API WS 广播：stateChanged 不在高频过滤名单内，会推送给所有 WS 客户端
    ExternalApiBroadcaster.broadcast("stateChanged", state)
  }

  private fun emitProgressChanged() {
    emitProgressChanged(getPositionMs(), true, false)
  }

  private fun emitProgressChanged(
    positionMs: Long,
    acknowledgePendingSeek: Boolean,
    authoritative: Boolean,
  ) {
    val safePositionMs = max(0L, positionMs)
    if (acknowledgePendingSeek) {
      rememberReportedPosition(safePositionMs)
    } else {
      lastKnownPositionMs = safePositionMs
    }
    if (authoritative) mainLyricClockListener?.invoke()
    pushDynamicIslandProgress(safePositionMs, isEffectivelyPlaying())
    // 隐藏窗口不推高频进度（250ms 周期对齐桌面 broadcast 第三参语义）；记账与灵动岛通道不受影响
    if (webViewVisible) {
      plugin?.let {
        val payload = JSObject()
        payload.put("durationMs", getDurationMs())
        payload.put("positionMs", safePositionMs)
        if (authoritative) payload.put("authoritative", true)
        it.emitEvent("progressChanged", payload, true)
      }
    }
  }

  private fun emitEnded() {
    plugin?.let {
      val payload = JSObject()
      payload.put("durationMs", getDurationMs())
      it.emitEvent("ended", payload, true)
    }
    // 外部 API WS 广播：ended 事件无附加数据
    ExternalApiBroadcaster.broadcast("ended", null)
  }

  private fun emitError(
    errorCode: Int,
    message: String?,
  ) {
    plugin?.let {
      val payload = JSObject()
      payload.put("errorCode", errorCode)
      if (message != null) payload.put("message", message)
      it.emitEvent("error", payload, true)
    }
  }

  private fun emitCustomAction(
    action: String,
    songId: Long?,
    likedState: Boolean?,
    desktopLyricEnabledState: Boolean?,
    collapsedState: Boolean?,
    success: Boolean,
    message: String?,
  ) {
    plugin?.let {
      val payload = JSObject()
      payload.put("action", action)
      payload.put("success", success)
      if (songId != null) payload.put("songId", songId)
      if (likedState != null) payload.put("liked", likedState)
      if (desktopLyricEnabledState != null) payload.put("desktopLyricEnabled", desktopLyricEnabledState)
      if (collapsedState != null) payload.put("collapsed", collapsedState)
      if (message != null) payload.put("message", message)
      it.emitEvent("customAction", payload, true)
    }
  }

  // ========== 频谱可视化 ==========

  @Synchronized
  fun enableVisualizer(enable: Boolean): Boolean {
    visualizerRequested = enable
    updateFftListenerAttachment()
    return true
  }

  /**
   * 切换 FFT 算法方案。主线程调用，音频线程下次 analyze 生效。
   * @param mode "pc" 对齐桌面端；"android" 保留原生方案
   */
  fun setSpectrumAlgorithm(mode: String): Boolean {
    fftAudioProcessor.setAlgorithm(mode)
    return true
  }

  /** 启用/禁用均衡器。主线程调用，音频线程下次 queueInput 生效。 */
  fun setEqualizerEnabled(enabled: Boolean) {
    eqAudioProcessor.setEnabled(enabled)
  }

  /** 更新 10 频段增益（dB）。主线程调用，原子替换系数快照。 */
  fun setEqualizerBands(gainsDb: FloatArray) {
    eqAudioProcessor.setBands(gainsDb)
  }

  /** 更新前级增益（dB）。主线程调用，原子替换系数快照。 */
  fun setEqualizerPreamp(preampDb: Float) {
    eqAudioProcessor.setPreamp(preampDb)
  }

  private fun updateFftListenerAttachment() {
    if (visualizerRequested) {
      fftAudioProcessor.setListener(this::onFftData)
    } else {
      fftAudioProcessor.setListener(null)
    }
  }

  private fun onFftData(
    fftBins: IntArray,
    lowFreq: Float,
  ) {
    if (!visualizerRequested) return
    // 音频线程约 10ms 一帧；隐藏窗口连 evaluateJavascript 都不能发，
    // 否则会持续唤醒 WebView 渲染导致 buffer 积压（BLASTBufferQueue 刷屏错误）与耗电
    if (!webViewVisible) return
    val len = fftBins.size
    synchronized(visualizerSnapshotLock) {
      var buf = visualizerByteBuf
      if (buf == null || buf.size != len) {
        buf = ByteArray(len)
        visualizerByteBuf = buf
      }
      for (i in 0 until len) {
        buf[i] = fftBins[i].toByte()
      }
      pendingLowFreq = lowFreq
      hasPendingVisualizerData = true
    }
    if (visualizerEmitScheduled.compareAndSet(false, true)) {
      mainHandler.post(visualizerEmitTask)
    }
  }

  private val visualizerSnapshotLock = java.lang.Object()
  private var visualizerByteBuf: ByteArray? = null
  private var pendingLowFreq = 0f
  private var hasPendingVisualizerData = false
  private val visualizerEmitScheduled = AtomicBoolean(false)

  private val visualizerEmitTask =
    Runnable {
      visualizerEmitScheduled.set(false)
      val currentPlugin = plugin ?: return@Runnable

      val snapshot: ByteArray
      val lowFreq: Float
      synchronized(visualizerSnapshotLock) {
        if (!hasPendingVisualizerData || visualizerByteBuf == null) return@Runnable
        snapshot = visualizerByteBuf!!.clone()
        lowFreq = pendingLowFreq
        hasPendingVisualizerData = false
      }

      val b64 = Base64.encodeToString(snapshot, Base64.NO_WRAP)
      val payload = JSObject()
      payload.put("fftB64", b64)
      payload.put("lowFreq", lowFreq.toDouble())
      currentPlugin.emitEvent("visualizerData", payload, false)
    }

  private fun loadCoverBitmapAsync(coverUrl: String?) {
    val artworkToken = artworkTokenCounter.incrementAndGet()
    val normalizedCoverUrl = normalizeMediaUrl(coverUrl)
    if (normalizedCoverUrl.isNullOrEmpty() || normalizedCoverUrl.startsWith("blob:")) {
      coverBitmap = BitmapFactory.decodeResource(appContext.resources, R.mipmap.ic_launcher)
      coverArtworkBytes = encodeArtworkBytes(coverBitmap)
      bufferedCoverBitmap = coverBitmap
      dynamicIslandService?.pushCover(coverBitmap)
      updateNotification()
      return
    }

    artworkExecutor.execute {
      var bitmap: Bitmap? = null
      var inputStream: InputStream? = null
      var connection: HttpURLConnection? = null

      try {
        if (normalizedCoverUrl.startsWith("data:")) {
          val commaIdx = normalizedCoverUrl.indexOf(',')
          val base64MarkerIdx = normalizedCoverUrl.indexOf(";base64")
          if (commaIdx > 0 &&
            base64MarkerIdx > 0 &&
            base64MarkerIdx < commaIdx &&
            normalizedCoverUrl.startsWith("data:image/")
          ) {
            val base64Data = normalizedCoverUrl.substring(commaIdx + 1)
            try {
              val decoded = Base64.decode(base64Data, Base64.DEFAULT)
              bitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
            } catch (ignored: IllegalArgumentException) {
            }
          }
        } else if (normalizedCoverUrl.startsWith("http://") || normalizedCoverUrl.startsWith("https://")) {
          connection = URL(normalizedCoverUrl).openConnection() as HttpURLConnection
          connection.connectTimeout = 8000
          connection.readTimeout = 8000
          connection.doInput = true
          connection.connect()
          inputStream = connection.inputStream
          bitmap = BitmapFactory.decodeStream(inputStream)
        } else if (normalizedCoverUrl.startsWith("content://")) {
          val resolver = appContext.contentResolver
          inputStream = resolver.openInputStream(Uri.parse(normalizedCoverUrl))
          if (inputStream != null) {
            bitmap = BitmapFactory.decodeStream(inputStream)
          }
        } else if (normalizedCoverUrl.startsWith("file://")) {
          bitmap = BitmapFactory.decodeFile(Uri.parse(normalizedCoverUrl).path)
        }
      } catch (error: Exception) {
        Log.w(TAG, "Failed to load cover art", error)
      } finally {
        try {
          inputStream?.close()
        } catch (ignored: Exception) {
        }
        connection?.disconnect()
      }

      val resolvedBitmap = bitmap ?: BitmapFactory.decodeResource(appContext.resources, R.mipmap.ic_launcher)
      val encodedBytes = encodeArtworkBytes(resolvedBitmap)

      mainHandler.post {
        if (artworkToken != artworkTokenCounter.get()) {
          emitDiagnosticLog("DIAG-Artwork", "stale cover ignored")
          return@post
        }
        coverBitmap = resolvedBitmap
        coverArtworkBytes = encodedBytes
        bufferedCoverBitmap = resolvedBitmap
        dynamicIslandService?.pushCover(resolvedBitmap)
        refreshCurrentMediaItemMetadata()
        updateNotification()
      }
    }
  }

  private fun toggleFavoriteAsync() {
    if (!currentMetadata.canLike || currentMetadata.songId <= 0) {
      emitCustomAction("favorite", currentMetadata.songId, liked, null, null, false, "favorite_unavailable")
      return
    }

    if (apiBaseUrl.isEmpty() || cookie.isEmpty()) {
      emitCustomAction("favorite", currentMetadata.songId, liked, null, null, false, "login_required")
      return
    }

    val targetLike = !liked
    val songId = currentMetadata.songId
    synchronized(this) {
      if (favoriteRequestInFlight) {
        emitCustomAction("favorite", songId, liked, null, null, false, "favorite_busy")
        return
      }
      favoriteRequestInFlight = true
    }

    networkExecutor.execute {
      val requestResult = performFavoriteRequest(songId, targetLike)
      mainHandler.post {
        favoriteRequestInFlight = false
        if (requestResult.success) {
          liked = targetLike
          currentMetadata.liked = targetLike
          updateMediaSessionButtons()
          updateNotification()
        }
        emitCustomAction(
          "favorite",
          songId,
          if (requestResult.success) targetLike else liked,
          null,
          null,
          requestResult.success,
          requestResult.message,
        )
      }
    }
  }

  private fun performFavoriteRequest(
    songId: Long,
    targetLike: Boolean,
  ): FavoriteRequestResult {
    val likeEndpoint = if (apiBaseUrl.endsWith("/like")) apiBaseUrl else "$apiBaseUrl/like"

    for (attempt in 1..FAVORITE_REQUEST_MAX_ATTEMPTS) {
      var connection: HttpURLConnection? = null
      try {
        // cookie 只走请求头：query 再带一份会让请求行+Cookie 头越过 nanohttpd 8192 上限，代理层直接 500
        val separator = if (likeEndpoint.contains("?")) "&" else "?"
        val urlString =
          "$likeEndpoint${separator}id=$songId&like=$targetLike" +
            "&timestamp=${System.currentTimeMillis()}"

        connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cookie", cookie)
        connection.connect()

        val httpCode = connection.responseCode
        val inputStream = if (httpCode >= 400) connection.errorStream else connection.inputStream
        val response = readStream(inputStream)
        val businessCode = parseBusinessCode(response)

        if (httpCode == 200 && businessCode == 200) {
          return FavoriteRequestResult.success()
        }

        if (businessCode == 301 || businessCode == 401 || response.contains("需要登录")) {
          return FavoriteRequestResult.failure("login_required")
        }

        if (attempt >= FAVORITE_REQUEST_MAX_ATTEMPTS || !shouldRetryFavoriteRequest(httpCode, businessCode)) {
          Log.w(TAG, "Favorite request failed, httpCode=$httpCode, businessCode=$businessCode, response=$response")
          return FavoriteRequestResult.failure("favorite_failed")
        }
      } catch (error: Exception) {
        Log.w(TAG, "Failed to toggle song favorite, attempt=$attempt", error)
        if (attempt >= FAVORITE_REQUEST_MAX_ATTEMPTS) {
          return FavoriteRequestResult.failure("favorite_failed")
        }
      } finally {
        connection?.disconnect()
      }

      try {
        Thread.sleep(FAVORITE_REQUEST_RETRY_DELAY_MS)
      } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        return FavoriteRequestResult.failure("favorite_failed")
      }
    }

    return FavoriteRequestResult.failure("favorite_failed")
  }

  private fun parseBusinessCode(response: String?): Int {
    if (response.isNullOrEmpty()) return -1
    return try {
      JSONObject(response).optInt("code", -1)
    } catch (error: Exception) {
      Log.w(TAG, "Failed to parse favorite response", error)
      -1
    }
  }

  private fun shouldRetryFavoriteRequest(
    httpCode: Int,
    businessCode: Int,
  ): Boolean {
    if (httpCode >= 500) return true
    return httpCode == 0 || httpCode == 408 || httpCode == 429 || businessCode == -1
  }

  private fun readStream(inputStream: InputStream?): String {
    if (inputStream == null) return ""
    val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
    val builder = StringBuilder()
    var line: String?
    while (reader.readLine().also { line = it } != null) {
      builder.append(line)
    }
    reader.close()
    return builder.toString()
  }

  private fun getDurationMs(): Long {
    val p = player ?: return currentMetadata.durationMs
    val duration = p.duration
    if (duration > 0) return duration
    return max(0L, currentMetadata.durationMs)
  }

  private fun getPositionMs(): Long {
    val p = player ?: return max(0L, lastKnownPositionMs)

    val playerPositionMs = max(0L, p.currentPosition)
    if (pendingSeekPositionMs != C.TIME_UNSET) {
      val now = System.currentTimeMillis()
      if (now > pendingSeekDeadlineMs) {
        clearPendingSeek()
      } else if (playerPositionMs + 250L < pendingSeekPositionMs) {
        return max(0L, lastKnownPositionMs)
      }
    }

    lastKnownPositionMs = playerPositionMs
    return playerPositionMs
  }

  private fun beginPendingSeek(positionMs: Long) {
    pendingSeekPositionMs = max(0L, positionMs)
    pendingSeekDeadlineMs = System.currentTimeMillis() + SEEK_STATE_GRACE_MS
    lastKnownPositionMs = pendingSeekPositionMs
  }

  private fun clearPendingSeek() {
    pendingSeekPositionMs = C.TIME_UNSET
    pendingSeekDeadlineMs = 0L
  }

  private fun rememberReportedPosition(positionMs: Long) {
    val safePositionMs = max(0L, positionMs)
    if (pendingSeekPositionMs != C.TIME_UNSET) {
      val now = System.currentTimeMillis()
      if (now > pendingSeekDeadlineMs) {
        clearPendingSeek()
      } else if (safePositionMs + 250L < pendingSeekPositionMs) {
        return
      }
    }
    lastKnownPositionMs = safePositionMs
  }

  private fun safeText(
    value: String?,
    fallback: String,
  ): String = if (value.isNullOrBlank()) fallback else value

  @Synchronized
  fun showDynamicIsland() {
    val intent = Intent(appContext, DynamicIslandService::class.java)
    try {
      appContext.startService(intent)
    } catch (error: Exception) {
      // 应用退后台时 startService 受后台启动限制抛异常，回滚开关，
      // 由调用方随后的通知与事件同步按关闭状态收敛
      desktopLyricEnabled = false
    }
  }

  @Synchronized
  fun hideDynamicIsland() {
    dynamicIslandService = null
    bufferedLrcJson = null
    bufferedYrcJson = null
    val intent = Intent(appContext, DynamicIslandService::class.java)
    appContext.stopService(intent)
  }

  @Synchronized
  fun attachDynamicIslandService(service: DynamicIslandService) {
    dynamicIslandService = service
    bufferedLyricConfig?.let { service.applyConfig(it) }
    if (bufferedLrcJson != null || bufferedYrcJson != null) {
      service.pushLyrics(bufferedLrcJson, bufferedYrcJson)
    }
    if (bufferedSongName != null) {
      service.pushSongInfo(bufferedSongName, bufferedArtist)
    }
    service.pushCover(bufferedCoverBitmap)
    service.pushProgress(bufferedTimeMs, bufferedPlaying)
  }

  @Synchronized
  fun detachDynamicIslandService(service: DynamicIslandService) {
    if (dynamicIslandService === service) dynamicIslandService = null
  }

  @Synchronized
  fun updateDynamicIslandData(
    lrcJson: String?,
    yrcJson: String?,
  ) {
    bufferedLrcJson = lrcJson
    bufferedYrcJson = yrcJson
    dynamicIslandService?.pushLyrics(lrcJson, yrcJson)
  }

  @Synchronized
  fun updateDynamicIslandProgress(
    timeMs: Long,
    playing: Boolean,
  ) {
    pushDynamicIslandProgress(timeMs, playing)
  }

  private fun pushDynamicIslandProgress(
    timeMs: Long,
    playing: Boolean,
  ) {
    bufferedTimeMs = timeMs
    bufferedPlaying = playing
    dynamicIslandService?.pushProgress(timeMs, playing)
  }

  @Synchronized
  fun updateDynamicIslandSongInfo(
    name: String?,
    artist: String?,
  ) {
    bufferedSongName = name
    bufferedArtist = artist
    dynamicIslandService?.pushSongInfo(name, artist)
  }

  @Synchronized
  fun updateDynamicIslandConfig(config: JSONObject?) {
    bufferedLyricConfig = config
    dynamicIslandService?.applyConfig(config)
  }

  @Synchronized
  fun isDynamicIslandRunning(): Boolean = dynamicIslandService != null

  fun emitDynamicIslandClosed() {
    desktopLyricEnabled = false
    updateMediaSessionButtons()
    updateNotification()
    emitCustomAction("dynamicIsland", null, null, false, null, true, null)
  }

  /**
   * 解析下载 URL，对齐 PC 端 [resolveNeteaseDownloadUrl] 流程
   * @param songId 网易云 songId
   * @param usePlayback true 时跳过下载接口、直接用播放接口（模拟播放下载）
   * @return 下载 URL 结果（含格式/体积）；无可用源返 null
   */
  fun resolveDownloadUrl(
    songId: Long,
    usePlayback: Boolean,
  ): DownloadUrlResult? = urlResolver.resolveDownloadUrlSync(songId, usePlayback)

  class TrackMetadata {
    var songId: Long = 0
    var durationMs: Long = 0
    var canLike: Boolean = false
    var liked: Boolean = false
    var title: String = ""
    var artist: String = ""
    var album: String = ""
    var coverUrl: String = ""
    var url: String = ""

    fun copy(): TrackMetadata {
      val copy = TrackMetadata()
      copy.songId = songId
      copy.durationMs = durationMs
      copy.canLike = canLike
      copy.liked = liked
      copy.title = title
      copy.artist = artist
      copy.album = album
      copy.coverUrl = coverUrl
      copy.url = url
      return copy
    }
  }

  private class FavoriteRequestResult private constructor(
    val success: Boolean,
    val message: String?,
  ) {
    companion object {
      fun success(): FavoriteRequestResult = FavoriteRequestResult(true, null)

      fun failure(message: String): FavoriteRequestResult = FavoriteRequestResult(false, message)
    }
  }
}
