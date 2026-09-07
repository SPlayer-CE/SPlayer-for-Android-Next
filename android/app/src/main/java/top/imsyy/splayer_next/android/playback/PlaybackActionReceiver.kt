package top.imsyy.splayer_next.android.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PlaybackActionReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent?,
  ) {
    if (intent?.action == null) {
      return
    }
    PlaybackManager.getInstance(context).handleNotificationAction(intent.action!!)
  }
}
