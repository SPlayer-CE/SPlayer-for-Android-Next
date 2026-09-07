package top.imsyy.splayer_next.android.playback

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
  override fun onCreate() {
    super.onCreate()
    PlaybackManager.getInstance(this).attachService(this)
  }

  override fun onDestroy() {
    PlaybackManager.getInstance(this).detachService(this)
    super.onDestroy()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = PlaybackManager.getInstance(this).session
}
