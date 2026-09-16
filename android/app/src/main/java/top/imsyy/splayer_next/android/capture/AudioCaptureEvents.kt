package top.imsyy.splayer_next.android.capture

/**
 * 采集事件总线：系统声音采集运行于前台服务、麦克风采集运行于插件，二者共用此总线
 * 把 CaptureEvent 送到插件注册的 JS 监听器。同一时刻仅一次识别会话，@Volatile 监听器足够。
 */
object AudioCaptureEvents {
  @Volatile
  private var listener: ((CaptureEvent) -> Unit)? = null

  /** 设置事件监听器（插件 load 时注册、destroy 时置空） */
  fun setListener(callback: ((CaptureEvent) -> Unit)?) {
    listener = callback
  }

  /** 推送采集事件到当前监听器 */
  fun emit(event: CaptureEvent) {
    listener?.invoke(event)
  }
}
