package top.imsyy.splayer_next.android.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONObject
import top.imsyy.splayer_next.android.playback.PlaybackManager

/** 外部 API REST 路由，对齐桌面端 routes.ts */
object ExternalApiRouter {
  private val mainHandler = Handler(Looper.getMainLooper())

  private const val ACTION_NEXT = "top.imsyy.splayer_next.android.playback.NEXT"
  private const val ACTION_PREVIOUS = "top.imsyy.splayer_next.android.playback.PREVIOUS"

  /** 分发 REST 请求，返回 NanoHTTPD Response */
  fun handle(
    session: IHTTPSession,
    context: Context,
  ): Response {
    val uri = session.uri
    val manager = PlaybackManager.getInstance(context)

    return when (uri) {
      "/api/external/info" -> {
        val res = JSONObject()
        res.put("name", "SPlayer Next")
        try {
          val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
          res.put("version", pkgInfo.versionName ?: "unknown")
        } catch (e: Exception) {
          res.put("version", "unknown")
        }
        res.put("wsClients", ExternalApiBroadcaster.getClientCount())
        jsonResponse(Response.Status.OK, res)
      }
      "/api/external/status" -> {
        val state = manager.buildState()
        val res = JSONObject()
        res.put(
          "state",
          when {
            state.optBoolean("playing") -> "playing"
            state.optBoolean("buffering") -> "buffering"
            else -> "paused"
          },
        )
        res.put("position", state.optLong("positionMs", 0))
        res.put("duration", state.optLong("durationMs", 0))
        res.put("volume", state.optDouble("volume", 1.0))
        res.put("isFinished", false)
        jsonResponse(Response.Status.OK, res)
      }
      "/api/external/volume" -> {
        when (session.method) {
          Method.GET -> {
            val state = manager.buildState()
            val res = JSONObject()
            res.put("volume", state.optDouble("volume", 1.0))
            jsonResponse(Response.Status.OK, res)
          }
          Method.POST -> {
            val body = readBody(session)
            val volume = body.optDouble("volume", Double.NaN)
            if (volume.isNaN() || volume < 0 || volume > 1) {
              return jsonError(Response.Status.BAD_REQUEST, "volume (number, 0..1) required")
            }
            mainHandler.post { manager.setVolume(volume.toFloat()) }
            okResponse()
          }
          else -> jsonError(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
        }
      }
      "/api/external/now-playing" -> {
        val state = manager.buildState()
        val res = JSONObject()
        res.put("src", state.optString("src"))
        res.put("songId", state.optLong("songId", 0))
        res.put("paused", state.optBoolean("paused"))
        res.put("playing", state.optBoolean("playing"))
        res.put("buffering", state.optBoolean("buffering"))
        res.put("position", state.optLong("positionMs", 0))
        res.put("duration", state.optLong("durationMs", 0))
        res.put("volume", state.optDouble("volume", 1.0))
        jsonResponse(Response.Status.OK, res)
      }
      "/api/external/play" -> {
        mainHandler.post { manager.play() }
        okResponse()
      }
      "/api/external/pause" -> {
        mainHandler.post { manager.pause() }
        okResponse()
      }
      "/api/external/stop" -> {
        mainHandler.post { manager.stop() }
        okResponse()
      }
      "/api/external/seek" -> {
        val body = readBody(session)
        val positionMs = body.optLong("positionMs", -1)
        if (positionMs < 0) {
          return jsonError(Response.Status.BAD_REQUEST, "positionMs (number, >=0) required")
        }
        mainHandler.post { manager.seek(positionMs) }
        okResponse()
      }
      "/api/external/next" -> {
        mainHandler.post { manager.handleNotificationAction(ACTION_NEXT) }
        okResponse()
      }
      "/api/external/prev" -> {
        mainHandler.post { manager.handleNotificationAction(ACTION_PREVIOUS) }
        okResponse()
      }
      else -> jsonError(Response.Status.NOT_FOUND, "endpoint not found")
    }
  }

  private fun readBody(session: IHTTPSession): JSONObject {
    val map = HashMap<String, String>()
    session.parseBody(map)
    val postData = map["postData"] ?: return JSONObject()
    return try {
      JSONObject(postData)
    } catch (e: Exception) {
      JSONObject()
    }
  }

  private fun jsonResponse(
    status: Response.Status,
    obj: JSONObject,
  ): Response = NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())

  private fun jsonError(
    status: Response.Status,
    message: String,
  ): Response {
    val obj = JSONObject()
    obj.put("error", message)
    return NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())
  }

  private fun okResponse(): Response {
    val obj = JSONObject()
    obj.put("ok", true)
    return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", obj.toString())
  }
}
