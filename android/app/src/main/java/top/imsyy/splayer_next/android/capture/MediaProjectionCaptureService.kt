package top.imsyy.splayer_next.android.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.atomic.AtomicBoolean
import top.imsyy.splayer_next.android.R

/**
 * 系统声音采集前台服务：Android 14+ 要求先以 mediaProjection 类型升级前台，再获取 MediaProjection，
 * 否则 getMediaProjection 抛 SecurityException。服务持有投影与采集，完成或取消后停止投影并自行结束，
 * 采集事件经 AudioCaptureEvents 回传插件。
 */
class MediaProjectionCaptureService : Service() {
  companion object {
    const val EXTRA_RESULT_CODE = "resultCode"
    const val EXTRA_DATA = "data"
    const val EXTRA_DURATION_MS = "durationMs"
    private const val DEFAULT_DURATION_MS = 8000
    private const val CHANNEL_ID = "splayer_capture"
    private const val NOTIFICATION_ID = 11452
  }

  private val captureManager = AudioCaptureManager()
  private var projection: MediaProjection? = null

  /** stopCapture 会从采集线程/onDestroy/投影回调多处触发，防重入确保只清理一次 */
  private val stopped = AtomicBoolean(false)

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    // 立即在主线程升级前台：延迟到 onStartCommand 在系统资源紧张时可能触发
    // Android 14+ 的 ForegroundServiceDidNotStartInTimeException（对齐 PlaybackService 做法）
    ServiceCompat.startForeground(
      this,
      NOTIFICATION_ID,
      buildNotification(),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
    )
  }

  @Suppress("DEPRECATION")
  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
    val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
    val durationMs = intent?.getIntExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS) ?: DEFAULT_DURATION_MS
    if (data == null) {
      AudioCaptureEvents.emit(CaptureEvent.Error("capture-failed", "缺少系统声音采集授权数据"))
      stopCapture()
      return START_NOT_STICKY
    }
    val manager = getSystemService(MediaProjectionManager::class.java)
    val mediaProjection = manager?.getMediaProjection(resultCode, data)
    if (mediaProjection == null) {
      AudioCaptureEvents.emit(CaptureEvent.Error("capture-failed", "获取 MediaProjection 失败"))
      stopCapture()
      return START_NOT_STICKY
    }
    projection = mediaProjection
    mediaProjection.registerCallback(
      object : MediaProjection.Callback() {
        override fun onStop() {
          stopCapture()
        }
      },
      Handler(Looper.getMainLooper()),
    )
    captureManager.startSystem(mediaProjection, durationMs) { event ->
      AudioCaptureEvents.emit(event)
      if (event is CaptureEvent.Done || event is CaptureEvent.Error) stopCapture()
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    stopCapture()
    super.onDestroy()
  }

  /**
   * 停止采集、投影与前台服务（幂等）：先 cancel 同步释放 AudioRecord，再停 MediaProjection，
   * 避免先销毁父级投影导致底层录音设备释放异常/Binder 死锁。
   */
  private fun stopCapture() {
    if (!stopped.compareAndSet(false, true)) return
    captureManager.cancel()
    projection?.let { runCatching { it.stop() } }
    projection = null
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun buildNotification(): Notification {
    ensureChannel()
    return NotificationCompat
      .Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(getString(R.string.capture_notification_title))
      .setContentText(getString(R.string.capture_notification_text))
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setOngoing(true)
      .build()
  }

  private fun ensureChannel() {
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        getString(R.string.capture_notification_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      )
    channel.description = getString(R.string.capture_notification_channel_description)
    getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
  }
}
