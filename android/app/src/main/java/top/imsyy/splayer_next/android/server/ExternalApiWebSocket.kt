package top.imsyy.splayer_next.android.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import top.imsyy.splayer_next.android.playback.PlaybackManager

/** 外部 API WebSocket 连接，对齐桌面端 ws.ts 协议 */
class ExternalApiWebSocket(
  handshake: IHTTPSession,
  private val context: Context,
) : NanoWSD.WebSocket(handshake) {
  private val mainHandler = Handler(Looper.getMainLooper())

  private val actionNext = "top.imsyy.splayer_next.android.playback.NEXT"
  private val actionPrevious = "top.imsyy.splayer_next.android.playback.PREVIOUS"

  override fun onOpen() {
    ExternalApiBroadcaster.registerClient(this)
    val hello = JSONObject()
    hello.put("kind", "hello")
    hello.put("clients", ExternalApiBroadcaster.getClientCount())
    try {
      send(hello.toString())
    } catch (e: Exception) {
    }
  }

  override fun onClose(
    code: NanoWSD.WebSocketFrame.CloseCode,
    reason: String,
    initiatedByRemote: Boolean,
  ) {
    ExternalApiBroadcaster.unregisterClient(this)
  }

  override fun onMessage(message: NanoWSD.WebSocketFrame) {
    val payload = message.textPayload
    val msg: JSONObject
    try {
      msg = JSONObject(payload)
    } catch (e: Exception) {
      sendError("?", "invalid json")
      return
    }
    val op = msg.optString("op")
    dispatchCommand(op, msg)
  }

  override fun onPong(pong: NanoWSD.WebSocketFrame) {}

  override fun onException(exception: java.io.IOException) {
    ExternalApiBroadcaster.unregisterClient(this)
  }

  private fun dispatchCommand(
    op: String,
    msg: JSONObject,
  ) {
    val manager = PlaybackManager.getInstance(context)
    when (op) {
      "play" -> {
        mainHandler.post { manager.play() }
        sendAck(op)
      }
      "pause" -> {
        mainHandler.post { manager.pause() }
        sendAck(op)
      }
      "stop" -> {
        mainHandler.post { manager.stop() }
        sendAck(op)
      }
      "next" -> {
        mainHandler.post { manager.handleNotificationAction(actionNext) }
        sendAck(op)
      }
      "prev" -> {
        mainHandler.post { manager.handleNotificationAction(actionPrevious) }
        sendAck(op)
      }
      "seek" -> {
        val positionMs = msg.optLong("positionMs", -1)
        if (positionMs < 0) {
          sendError(op, "positionMs (number, >=0) required")
          return
        }
        mainHandler.post { manager.seek(positionMs) }
        sendAck(op)
      }
      "setVolume" -> {
        val volume = msg.optDouble("volume", Double.NaN)
        if (volume.isNaN() || volume < 0 || volume > 1) {
          sendError(op, "volume (number, 0..1) required")
          return
        }
        mainHandler.post { manager.setVolume(volume.toFloat()) }
        sendAck(op)
      }
      else -> sendError(op, "unknown op")
    }
  }

  private fun sendAck(op: String) {
    val ack = JSONObject()
    ack.put("kind", "ack")
    ack.put("op", op)
    try {
      send(ack.toString())
    } catch (e: Exception) {
    }
  }

  private fun sendError(
    op: String,
    error: String,
  ) {
    val err = JSONObject()
    err.put("kind", "error")
    err.put("op", op)
    err.put("error", error)
    try {
      send(err.toString())
    } catch (e: Exception) {
    }
  }
}
