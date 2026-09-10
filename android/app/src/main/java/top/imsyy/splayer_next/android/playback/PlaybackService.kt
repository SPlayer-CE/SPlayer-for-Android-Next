package top.imsyy.splayer_next.android.playback

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
  override fun onCreate() {
    super.onCreate()
    // FGS 启动时限兜底：必须在主线程立即升级前台。异步 attach 的播放线程可能正被引擎
    // 重初始化占用（ExoPlayer/PreloadManager），延迟 startForeground 会触发
    // ForegroundServiceDidNotStartInTimeException；占位通知随后由 updateNotification 替换
    startForeground(PlaybackConstants.NOTIFICATION_ID, PlaybackManager.buildStartupNotification(this))
    PlaybackManager.getInstance(this).attachService(this)
  }

  override fun onDestroy() {
    PlaybackManager.getInstance(this).detachService(this)
    super.onDestroy()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = PlaybackManager.getInstance(this).session
}
