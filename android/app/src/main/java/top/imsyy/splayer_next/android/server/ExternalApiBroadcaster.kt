package top.imsyy.splayer_next.android.server

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject

/** 外部 API WS 客户端注册表 + 事件广播 */
object ExternalApiBroadcaster {
  private const val TAG = "ExternalApiBroadcaster"
  private val clients = CopyOnWriteArrayList<ExternalApiWebSocket>()

  /** 高频事件名单：默认不向外部 WS 推送（避免带宽爆炸） */
  private val HIGH_FREQ_EVENTS = setOf("fftData", "position")

  fun registerClient(client: ExternalApiWebSocket) {
    clients.add(client)
    Log.i(TAG, "External API WS 客户端已连接，当前在线 ${clients.size}")
  }

  fun unregisterClient(client: ExternalApiWebSocket) {
    if (clients.remove(client)) {
      Log.i(TAG, "External API WS 客户端已断开，当前在线 ${clients.size}")
    }
  }

  fun getClientCount(): Int = clients.size

  /** 推送事件给所有 WS 客户端，高频事件默认过滤 */
  fun broadcast(
    eventType: String,
    data: JSONObject?,
  ) {
    if (clients.isEmpty()) return
    if (eventType in HIGH_FREQ_EVENTS) return
    val payload = JSONObject()
    payload.put("kind", "event")
    payload.put("type", eventType)
    if (data != null) payload.put("data", data)
    val payloadStr = payload.toString()
    for (client in clients) {
      try {
        client.send(payloadStr)
      } catch (e: Exception) {
        Log.w(TAG, "WS 推送失败，移除失效客户端", e)
        clients.remove(client)
      }
    }
  }
}
