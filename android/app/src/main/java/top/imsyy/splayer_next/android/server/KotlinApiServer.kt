package top.imsyy.splayer_next.android.server

import android.content.Context
import android.util.Base64
import android.util.Log
import com.getcapacitor.Plugin
import com.getcapacitor.annotation.CapacitorPlugin
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoWSD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Filter
import java.util.logging.Logger
import org.json.JSONObject

@CapacitorPlugin(name = "ApiServer")
class ApiServerPlugin : Plugin() {
  private var server: KotlinApiServer? = null
  private val tag = "ApiServerPlugin"

  override fun load() {
    Log.i(tag, "Starting Kotlin API Server on port 13962")
    try {
      suppressNanoHttpdBrokenPipeLog()
      LanShareManager.init(context)
      ExternalApiManager.init(context, 13233)
      server = KotlinApiServer(context, 13962, 13233)
      server?.start(10000, false)
      Log.i(tag, "Kotlin API Server started successfully")
    } catch (e: Exception) {
      Log.e(tag, "Failed to start Kotlin API Server", e)
    }
  }

  /**
   * 丢弃 NanoHTTPD 对"响应写一半客户端断开"的 SEVERE 日志：
   * 流式响应被 ExoPlayer seek/切歌或 fetch 取消掐断时 Broken pipe 属正常取消行为，静音以免 logcat 噪音
   */
  private fun suppressNanoHttpdBrokenPipeLog() {
    Logger.getLogger("fi.iki.elonen.NanoHTTPD").filter =
      Filter { record -> record.message?.contains("Could not send response to the client") != true }
  }

  override fun handleOnDestroy() {
    Log.i(tag, "Stopping Kotlin API Server")
    server?.shutdown()
    server?.stop()
    super.handleOnDestroy()
  }
}

class KotlinApiServer(
  private val context: Context,
  port: Int,
  private val nodejsPort: Int,
) : NanoWSD(port) {
  private val tag = "KotlinApiServer"
  private val nodeConnectTimeoutMs = 1500
  private val nodeReadTimeoutMs = 30000
  private val nodeSlowReadTimeoutMs = 60000
  private val wsHeartbeatIntervalMs = 30000L
  private val heartbeatExecutor =
    java.util.concurrent.Executors
      .newSingleThreadScheduledExecutor()

  private var heartbeatFuture: java.util.concurrent.ScheduledFuture<*>? = null

  private fun startHeartbeat() {
    if (heartbeatFuture != null && !heartbeatFuture!!.isCancelled) return
    heartbeatFuture =
      heartbeatExecutor.scheduleAtFixedRate({
        for (client in wsClients) {
          try {
            client.ping(arrayOf(0x68, 0x62).map { it.toByte() }.toByteArray())
          } catch (e: Exception) {
            try {
              client.close(NanoWSD.WebSocketFrame.CloseCode.GoingAway, "heartbeat failed", false)
            } catch (_: Exception) {
            }
          }
        }
      }, wsHeartbeatIntervalMs, wsHeartbeatIntervalMs, java.util.concurrent.TimeUnit.MILLISECONDS)
  }

  private fun stopHeartbeat() {
    heartbeatFuture?.cancel(false)
    heartbeatFuture = null
  }

  init {
    // 定时心跳：ping 所有 WS 客户端，按需启动
  }

  fun shutdown() {
    heartbeatExecutor.shutdownNow()
  }

  companion object {
    val wsClients = CopyOnWriteArrayList<SyncWebSocket>()

    @Volatile var lastSyncPayload: String? = null

    @Volatile var currentLanAudioSource: String? = null

    @Volatile var currentLanAudioTrackId: String? = null

    @Volatile var currentLanAudioRevision: Int = 0

    // 允许的遥控指令白名单，防止从设备发送任意 action 执行非预期操作
    val allowedActions = setOf("next", "prev", "play", "pause", "toggle", "seek", "setSpeed", "setPitch", "playAt", "syncSeek")

    private fun isReceiverReachableAudioUrl(source: String): Boolean {
      val uri =
        try {
          URI(source)
        } catch (_: Exception) {
          return false
        }
      if (uri.scheme != "http" && uri.scheme != "https") return false
      val host = uri.host?.lowercase() ?: return false
      return host != "127.0.0.1" &&
        host != "localhost" &&
        host != "0.0.0.0" &&
        host != "::1"
    }

    /** 接收主机 JS 推送的播放队列快照（已序列化 JSON，Kotlin 不解析，透传给 getQueue 路由） */
    @Volatile var queuePayloadJson: String? = null

    /** 队列版本号：每次 JS 推送自增，经 sync 载荷下发，提示从设备重拉 */
    @Volatile var queueRevision: Int = 0

    /** 更新主机播放队列快照 */
    fun updateQueue(json: String) {
      queuePayloadJson = json
      queueRevision++
    }

    /** 接收主机 JS 推送的当前歌词快照（含 LyricData 元数据与原始内容，Kotlin 不解析，透传给 getLyric 路由） */
    @Volatile var lyricPayloadJson: String? = null

    /** 歌词版本号：每次 JS 推送自增，经 sync 载荷下发，提示从设备重拉 */
    @Volatile var lyricRevision: Int = 0

    /** 更新主机当前歌词快照 */
    fun updateLyric(json: String) {
      lyricPayloadJson = json
      lyricRevision++
    }

    fun broadcastPlayback(payload: JSONObject) {
      if (!LanShareManager.config.collabEnabled || !LanShareManager.config.enabled) return
      // 更新音频源缓存，移除 audioSource 防止主机本地路径泄露，注入 audioRevision 供从设备判断变更
      val trackId = payload.optJSONObject("track")?.optString("id", "") ?: ""
      val audioSource = payload.optString("audioSource", "")
      if (audioSource.isNotEmpty()) {
        val sourceChanged =
          currentLanAudioSource != audioSource ||
            (trackId.isNotEmpty() && currentLanAudioTrackId != trackId)
        currentLanAudioSource = audioSource
        if (trackId.isNotEmpty()) currentLanAudioTrackId = trackId
        if (sourceChanged) currentLanAudioRevision++
      }
      val audioReady =
        trackId.isNotEmpty() &&
          currentLanAudioTrackId == trackId &&
          !currentLanAudioSource.isNullOrEmpty()
      val directAudioUrl =
        currentLanAudioSource?.takeIf {
          audioReady && isReceiverReachableAudioUrl(it)
        }
      lastSyncPayload =
        JSONObject()
          .apply {
            payload.keys().forEach {
              // 排除 audioSource 防止主机本地路径泄露；排除 type 避免覆盖下方统一类型
              if (it != "audioSource" && it != "type") put(it, payload.get(it))
            }
            put("type", "splayer-sync")
            put("ts", System.currentTimeMillis())
            put("audioRevision", currentLanAudioRevision)
            put("queueRevision", queueRevision)
            put("lyricRevision", lyricRevision)
            put("audioReady", audioReady)
            if (directAudioUrl != null) put("audioUrl", directAudioUrl)
          }.toString()

      for (client in wsClients) {
        if (client.role != "host") {
          try {
            client.send(lastSyncPayload)
          } catch (e: Exception) {
          }
        }
      }
    }
  }

  private fun addCorsHeaders(
    response: Response,
    session: IHTTPSession,
  ): Response {
    val origin = session.headers["origin"]
    val allowedOrigin =
      when {
        origin != null && isAllowedLocalOrigin(origin) -> origin
        origin != null && LanShareManager.config.enabled && isAllowedLanOrigin(origin) -> origin
        else -> "http://127.0.0.1:13962"
      }
    response.addHeader("Access-Control-Allow-Origin", allowedOrigin)
    response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
    response.addHeader(
      "Access-Control-Allow-Headers",
      "Content-Type, Authorization, X-Requested-With, Origin, Accept, X-SPlayer-Cookie, X-SPlayer-Token, Range",
    )
    response.addHeader("Access-Control-Allow-Credentials", "true")
    response.addHeader("Access-Control-Max-Age", "86400")
    response.addHeader("Vary", "Origin")
    return response
  }

  private fun isAllowedLocalOrigin(origin: String): Boolean =
    origin == "https://localhost" ||
      origin == "http://localhost" ||
      origin == "capacitor://localhost" ||
      origin == "http://127.0.0.1:13962"

  private fun isPrivateHost(host: String): Boolean {
    val normalized = host.lowercase()
    return normalized == "localhost" ||
      normalized == "127.0.0.1" ||
      normalized == "::1" ||
      normalized.startsWith("192.168.") ||
      normalized.startsWith("10.") ||
      Regex("^172\\.(1[6-9]|2\\d|3[0-1])\\.").containsMatchIn(normalized)
  }

  private fun isAllowedLanOrigin(origin: String): Boolean {
    if (isAllowedLocalOrigin(origin)) return true
    return try {
      val parsed = URI(origin)
      val scheme = parsed.scheme?.lowercase()
      val host = parsed.host?.lowercase()
      (scheme == "http" || scheme == "https") && host != null && isPrivateHost(host)
    } catch (e: Exception) {
      false
    }
  }

  private fun isSensitiveNodeRoute(uri: String): Boolean =
    uri == "/api/apis/call" ||
      uri == "/api/apis/setCookie" ||
      uri == "/api/apis/clearSession" ||
      uri == "/api/apis/openLoginWeb"

  private fun extractExternalApiToken(session: IHTTPSession): String {
    val headerToken = session.headers["x-splayer-token"]?.trim().orEmpty()
    if (headerToken.isNotEmpty()) return headerToken
    return session.parameters["token"]
      ?.firstOrNull()
      ?.trim()
      .orEmpty()
  }

  private fun escapeJson(value: String): String =
    value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")

  private fun jsonResponse(
    status: Response.Status,
    error: String,
    message: String,
  ): Response =
    newFixedLengthResponse(
      status,
      "application/json; charset=utf-8",
      "{\"error\":\"${escapeJson(error)}\",\"message\":\"${escapeJson(message)}\"}",
    )

  private fun jsonResponseObj(
    status: Response.Status,
    obj: JSONObject,
  ): Response =
    newFixedLengthResponse(
      status,
      "application/json; charset=utf-8",
      obj.toString(),
    )

  @Volatile var nodeReadyLatched = false

  private fun isNodeReady(): Boolean {
    if (nodeReadyLatched) return true
    return try {
      val url = URL("http://127.0.0.1:$nodejsPort/api/config/getAll")
      val connection = url.openConnection() as HttpURLConnection
      connection.requestMethod = "GET"
      connection.connectTimeout = nodeConnectTimeoutMs
      connection.readTimeout = 1500
      connection.useCaches = false
      val ready = connection.responseCode in 200..299
      connection.disconnect()
      if (ready) nodeReadyLatched = true
      ready
    } catch (e: Exception) {
      false
    }
  }

  @Suppress("ReturnCount")
  override fun serveHttp(session: IHTTPSession): Response {
    val uri = session.uri
    // Log.d(tag, "Request: ${session.method} $uri")

    try {
      // LAN 共享关闭时，非本机请求一律拒绝（含静态资源与 /api/health），最小化 13962 暴露面
      // /api/external/* 由 ExternalApiRouter 内部按 enabled/allowLan/token 单独鉴权，不在此拦截
      val isLocal = isLocalRequest(session)
      val lanEnabled = LanShareManager.config.enabled
      if (!isLocal && !lanEnabled && !uri.startsWith("/api/external/")) {
        consumeRequestBody(session)
        return addCorsHeaders(
          jsonResponse(Response.Status.FORBIDDEN, "FORBIDDEN", "LAN access disabled"),
          session,
        )
      }

      if (session.method == Method.OPTIONS) {
        val response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
        return addCorsHeaders(response, session)
      }

      if (uri == "/api/health") {
        val res = JSONObject()
        res.put("ok", true)
        res.put("nodeReady", isNodeReady())
        return addCorsHeaders(jsonResponseObj(Response.Status.OK, res), session)
      }

      if (uri.startsWith("/api/lanShare/")) {
        return addCorsHeaders(handleLanShare(session), session)
      }

      // db 缓存路由：供 Node.js mobile-server 通过 HTTP 访问 SQLite 持久化缓存
      if (uri.startsWith("/api/cache/db/")) {
        if (!isLocal) {
          return addCorsHeaders(
            jsonResponse(Response.Status.FORBIDDEN, "FORBIDDEN", "cache db requires local access"),
            session,
          )
        }
        return addCorsHeaders(handleCacheDb(session), session)
      }

      // 外部 API 路由：按 enabled 与 allowLan 鉴权后交给 ExternalApiRouter 处理
      if (uri.startsWith("/api/external/")) {
        val externalConfig = ExternalApiManager.config
        if (!externalConfig.enabled) {
          return addCorsHeaders(
            jsonResponse(Response.Status.FORBIDDEN, "FORBIDDEN", "External API disabled"),
            session,
          )
        }
        if (!isLocal && !externalConfig.allowLan) {
          return addCorsHeaders(
            jsonResponse(Response.Status.FORBIDDEN, "FORBIDDEN", "External API LAN access disabled"),
            session,
          )
        }
        if (!isLocal) {
          val token = extractExternalApiToken(session)
          if (externalConfig.token.isBlank() || token != externalConfig.token) {
            return addCorsHeaders(
              jsonResponse(Response.Status.UNAUTHORIZED, "UNAUTHORIZED", "Invalid external API token"),
              session,
            )
          }
        }
        return addCorsHeaders(ExternalApiRouter.handle(session, context), session)
      }

      // fetchRemoteBytes 直接在 Kotlin 层处理，使用 Android 原生 HTTP 客户端（含系统 CA 证书），
      // 避免代理到 Node.js Mobile 时因 TLS/SSL 问题返回 500
      if (uri == "/api/system/fetchRemoteBytes") {
        if (!isLocal) {
          return addCorsHeaders(
            jsonResponse(Response.Status.FORBIDDEN, "FORBIDDEN", "fetchRemoteBytes requires local access"),
            session,
          )
        }
        return addCorsHeaders(handleFetchRemoteBytes(session), session)
      }

      if (uri.startsWith("/api/")) {
        if (!isLocal && isSensitiveNodeRoute(uri)) {
          consumeRequestBody(session)
          return addCorsHeaders(
            jsonResponse(Response.Status.FORBIDDEN, "FORBIDDEN", "Sensitive API requires local access"),
            session,
          )
        }
        return addCorsHeaders(proxyToNodeJs(session), session)
      }

      return addCorsHeaders(serveStaticFile(uri), session)
    } catch (e: Exception) {
      Log.e(tag, "Error handling request", e)
      val response =
        if (uri.startsWith("/api/")) {
          jsonResponse(
            Response.Status.INTERNAL_ERROR,
            "EMBEDDED_API_PROXY_INTERNAL_ERROR",
            e.message ?: "Internal proxy error",
          )
        } else {
          newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR,
            MIME_PLAINTEXT,
            "Internal Error: ${e.message}",
          )
        }
      return addCorsHeaders(response, session)
    }
  }

  private fun isLocalRequest(session: IHTTPSession): Boolean {
    val ip = getClientIp(session)
    return ip == "127.0.0.1" || ip == "0:0:0:0:0:0:0:1" || ip == "::1"
  }

  private fun getClientIp(session: IHTTPSession): String = (session.remoteIpAddress ?: "").removePrefix("::ffff:")

  private fun readBody(session: IHTTPSession): JSONObject =
    try {
      JSONObject(String(readRawRequestBody(session), StandardCharsets.UTF_8))
    } catch (e: Exception) {
      JSONObject()
    }

  /**
   * 按原始字节读取请求体。
   * 不能走 parseBody 的字符串解码：NanoHTTPD 对未声明 charset 的 Content-Type 默认按
   * US-ASCII 解码（ContentType.getEncoding 兜底），中文等非 ASCII 内容会被不可逆替换，
   * 必须在字节层面读取后由调用方按 UTF-8 还原。
   */
  private fun readRawRequestBody(session: IHTTPSession): ByteArray {
    val contentLength = session.headers["content-length"]?.trim()?.toIntOrNull() ?: 0
    if (contentLength <= 0) return ByteArray(0)
    val bytes = ByteArray(contentLength)
    var read = 0
    while (read < contentLength) {
      val count = session.inputStream.read(bytes, read, contentLength - read)
      if (count < 0) break
      read += count
    }
    return if (read == contentLength) bytes else bytes.copyOf(read)
  }

  private fun consumeRequestBody(session: IHTTPSession) {
    if (session.method != Method.POST && session.method != Method.PUT && session.method != Method.PATCH) return
    try {
      session.parseBody(HashMap())
    } catch (_: Exception) {
    }
  }

  @Suppress("ReturnCount")
  private fun handleLanShare(session: IHTTPSession): Response {
    val uri = session.uri

    // LAN access control
    val remoteIp = getClientIp(session)
    val isLocal = isLocalRequest(session)
    if (!isLocal && !LanShareManager.config.enabled) {
      return jsonResponse(Response.Status.FORBIDDEN, "FORBIDDEN", "LAN access disabled")
    }
    val remoteRoutes =
      setOf(
        "/api/lanShare/getStatus",
        "/api/lanShare/audio",
        "/api/lanShare/resolveTrack",
        "/api/lanShare/getLyric",
        "/api/lanShare/getQueue",
      )
    if (!isLocal && uri !in remoteRoutes) {
      consumeRequestBody(session)
      return jsonResponse(Response.Status.FORBIDDEN, "FORBIDDEN", "LAN management requires local access")
    }

    when (uri) {
      "/api/lanShare/getStatus" -> {
        if (!isLocal) LanShareManager.observeDevice(remoteIp)
        val devices = LanShareManager.getVisibleDevices()
        val res = JSONObject()
        res.put("enabled", LanShareManager.config.enabled)
        res.put("collabEnabled", LanShareManager.config.collabEnabled)
        res.put("shareUserInfo", LanShareManager.config.shareUserInfo)
        res.put("deviceCount", devices.size)
        res.put("sharedCount", devices.count { it.sharedLogin })
        res.put("serverIp", "0.0.0.0")
        val collabAuthorized = isLocal || LanShareManager.isCollabAllowed(remoteIp)
        res.put("collabAuthorized", collabAuthorized)
        if (isLocal || (LanShareManager.config.collabEnabled && collabAuthorized)) {
          res.put("wsToken", LanShareManager.config.wsToken)
        }
        return jsonResponseObj(Response.Status.OK, res)
      }
      "/api/lanShare/getQueue" -> {
        // 先读 revision 再读 payload：最差情况是「新 payload + 旧 revision」，
        // 从设备跳过本次（revision 不变），下次 sync 前进后重拉拿到相同数据，不会卡在旧快照
        val revision = queueRevision
        val payloadJson = queuePayloadJson
        return if (payloadJson == null) {
          newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", "{\"ok\":false,\"reason\":\"queue not ready\"}")
        } else {
          newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            "{\"ok\":true,\"revision\":$revision,\"data\":$payloadJson}",
          )
        }
      }
      "/api/lanShare/getLyric" -> {
        val revision = lyricRevision
        val payloadJson = lyricPayloadJson
        return if (payloadJson == null) {
          newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", "{\"ok\":false,\"reason\":\"lyric not ready\"}")
        } else {
          newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            "{\"ok\":true,\"revision\":$revision,\"data\":$payloadJson}",
          )
        }
      }
      "/api/lanShare/getDevices" -> {
        val res = JSONObject()
        res.put("ok", true)
        res.put(
          "devices",
          org.json.JSONArray(
            com.google.gson
              .Gson()
              .toJson(LanShareManager.getVisibleDevices()),
          ),
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json", res.toString())
      }
      "/api/lanShare/setDeviceCollab" -> {
        if (session.method != Method.POST) {
          return jsonResponse(Response.Status.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "POST required")
        }
        val body = readBody(session)
        val ip = body.optString("ip")
        if (ip.isEmpty()) {
          return jsonResponse(Response.Status.BAD_REQUEST, "BAD_REQUEST", "ip required")
        }
        val device = LanShareManager.setDeviceCollab(ip, body.optBoolean("enabled", false))
        return jsonResponseObj(
          Response.Status.OK,
          JSONObject().put("ok", true).put(
            "device",
            JSONObject(
              com.google.gson
                .Gson()
                .toJson(device),
            ),
          ),
        )
      }
      "/api/lanShare/getLocalIPs" -> {
        val ips = org.json.JSONArray()
        try {
          val interfaces = NetworkInterface.getNetworkInterfaces()
          while (interfaces.hasMoreElements()) {
            val intf = interfaces.nextElement()
            val addrs = intf.inetAddresses
            while (addrs.hasMoreElements()) {
              val addr = addrs.nextElement()
              if (!addr.isLoopbackAddress) {
                val obj = JSONObject()
                obj.put("name", intf.name)
                obj.put("address", addr.hostAddress)
                if (addr is Inet4Address) {
                  obj.put("family", "IPv4")
                } else if (addr is Inet6Address && !addr.hostAddress!!.startsWith("fe80")) {
                  obj.put("family", "IPv6")
                }
                if (obj.has("family")) ips.put(obj)
              }
            }
          }
        } catch (e: Exception) {
        }
        val res = JSONObject()
        res.put("ok", true)
        res.put("ips", ips)
        res.put("port", 13962)
        return jsonResponseObj(Response.Status.OK, res)
      }
      "/api/lanShare/audio" -> {
        val trackId = session.parameters["trackId"]?.firstOrNull() ?: ""
        if (trackId.isEmpty()) return jsonResponse(Response.Status.BAD_REQUEST, "BAD_REQUEST", "trackId required")
        if (!LanShareManager.config.enabled || !LanShareManager.config.collabEnabled) {
          return jsonResponse(Response.Status.FORBIDDEN, "FORBIDDEN", "LAN collaboration disabled")
        }
        if (currentLanAudioTrackId != trackId || currentLanAudioSource == null) {
          return jsonResponse(Response.Status.NOT_FOUND, "NOT_FOUND", "audio source not ready")
        }
        val source = currentLanAudioSource!!
        if (source.startsWith("http://") || source.startsWith("https://")) {
          if (isReceiverReachableAudioUrl(source)) {
            val res = newFixedLengthResponse(Response.Status.REDIRECT, MIME_PLAINTEXT, "")
            res.addHeader("Location", source)
            return res
          }
          return proxyLanAudioSource(session, source)
        } else {
          val file = if (source.startsWith("file://")) File(URL(source).toURI()) else File(source)
          if (!file.exists()) return jsonResponse(Response.Status.NOT_FOUND, "NOT_FOUND", "audio not found")
          // 根据扩展名推断 MIME，避免所有文件都返回 audio/mpeg 导致播放器解码异常
          val mime =
            when (file.extension.lowercase()) {
              "flac" -> "audio/flac"
              "wav" -> "audio/wav"
              "m4a", "mp4" -> "audio/mp4"
              "aac" -> "audio/aac"
              "ogg" -> "audio/ogg"
              "opus" -> "audio/opus"
              else -> "audio/mpeg"
            }
          // 支持 Range 请求，允许播放器拖动进度时按需读取
          val rangeHeader = session.headers["range"]
          val fileSize = file.length()
          val (start, end) =
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
              val range = rangeHeader.removePrefix("bytes=").split("-")
              val s = range.getOrNull(0)?.toLongOrNull() ?: 0
              val e = range.getOrNull(1)?.toLongOrNull() ?: (fileSize - 1)
              s to minOf(e, fileSize - 1)
            } else {
              0L to (fileSize - 1)
            }
          val contentLength = end - start + 1
          return try {
            val fis = FileInputStream(file)
            fis.channel.position(start)
            val response =
              newFixedLengthResponse(
                if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
                mime,
                fis,
                contentLength,
              )
            if (rangeHeader != null) {
              response.addHeader("Content-Range", "bytes $start-$end/$fileSize")
              response.addHeader("Accept-Ranges", "bytes")
            }
            response
          } catch (e: Exception) {
            jsonResponse(Response.Status.INTERNAL_ERROR, "IO_ERROR", e.message ?: "")
          }
        }
      }
      "/api/lanShare/resolveTrack" -> {
        val body = readBody(session)
        val track = body.optJSONObject("track")
        val audioRevision = body.optInt("audioRevision", 0)
        if (track == null) return jsonResponse(Response.Status.BAD_REQUEST, "BAD_REQUEST", "track required")
        val trackId = track.optString("id")
        val res = JSONObject()
        // 返回相对音频流地址，由前端拼接主机 IP 后访问
        if (trackId.isNotEmpty() && currentLanAudioSource != null && currentLanAudioTrackId == trackId) {
          res.put("ok", true)
          res.put("streamUrl", "/api/lanShare/audio?trackId=$trackId&v=$audioRevision")
        } else {
          res.put("ok", false)
          res.put("streamUrl", "")
        }
        return jsonResponseObj(Response.Status.OK, res)
      }
    }
    return jsonResponse(Response.Status.NOT_FOUND, "NOT_FOUND", "endpoint not found")
  }

  private fun proxyLanAudioSource(
    session: IHTTPSession,
    source: String,
  ): Response =
    try {
      val connection = URL(source).openConnection() as HttpURLConnection
      connection.requestMethod = "GET"
      connection.connectTimeout = nodeConnectTimeoutMs
      connection.readTimeout = nodeSlowReadTimeoutMs
      connection.instanceFollowRedirects = true
      connection.useCaches = false
      session.headers["range"]?.let { connection.setRequestProperty("Range", it) }
      connection.setRequestProperty(
        "User-Agent",
        session.headers["user-agent"] ?: "SPlayer-Next-LAN",
      )

      val responseCode = connection.responseCode
      val inputStream =
        if (responseCode >= 400) {
          connection.errorStream ?: connection.inputStream
        } else {
          connection.inputStream
        }
      val response =
        newChunkedResponse(
          Response.Status.lookup(responseCode) ?: Response.Status.INTERNAL_ERROR,
          connection.contentType ?: "audio/mpeg",
          inputStream,
        )
      for ((key, values) in connection.headerFields) {
        if (key == null || values.isEmpty()) continue
        if (key.equals("Content-Length", ignoreCase = true) ||
          key.equals("Transfer-Encoding", ignoreCase = true) ||
          key.equals("Connection", ignoreCase = true) ||
          key.startsWith("Access-Control-", ignoreCase = true)
        ) {
          continue
        }
        response.addHeader(key, values[0])
      }
      response
    } catch (e: Exception) {
      Log.w(tag, "LAN audio proxy failed: ${e.message}")
      jsonResponse(
        Response.Status.SERVICE_UNAVAILABLE,
        "LAN_AUDIO_PROXY_ERROR",
        e.message ?: "LAN audio proxy failed",
      )
    }

  /**
   * 处理 /api/cache/db 下各路由：供 Node.js mobile-server 通过 HTTP 访问 SQLite 持久化缓存。
   *
   * <p>路由列表：
   * <ul>
   *   <li>GET  /api/cache/db/lyricMatch/get?fingerprint=&platform= → 获取匹配记录
   *   <li>POST /api/cache/db/lyricMatch/set {fingerprint, platform, platformId, extra} → 写入匹配记录
   *   <li>GET  /api/cache/db/ttml/get?platform=&id= → 获取 TTML 缓存
   *   <li>POST /api/cache/db/ttml/set {platform, id, content} → 写入 TTML 缓存
   *   <li>GET  /api/cache/db/lyric/get?platform=&platformId= → 获取歌词缓存
   *   <li>POST /api/cache/db/lyric/set {platform, platformId, data} → 写入歌词缓存
   *   <li>POST /api/cache/db/cleanExpired → 清理过期 TTML 负缓存与 match 缓存
   * </ul>
   */
  @Suppress("ReturnCount")
  private fun handleCacheDb(session: IHTTPSession): Response {
    val uri = session.uri
    val db =
      top.imsyy.splayer_next.android.cache.DbCacheHelper
        .init(context)

    try {
      when (uri) {
        "/api/cache/db/lyricMatch/get" -> {
          if (session.method != Method.GET) {
            return jsonResponse(Response.Status.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "GET required")
          }
          val fingerprint = session.parameters["fingerprint"]?.firstOrNull()
          val platform = session.parameters["platform"]?.firstOrNull()
          if (fingerprint.isNullOrEmpty() || platform.isNullOrEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, "BAD_REQUEST", "fingerprint and platform required")
          }
          val record = db.getLyricMatchCache(fingerprint, platform)
          val res = JSONObject()
          if (record == null) {
            res.put("hit", false)
          } else {
            res.put("hit", true)
            res.put("platformId", record.platformId)
            res.put("extra", record.extra ?: JSONObject.NULL)
          }
          return jsonResponseObj(Response.Status.OK, res)
        }

        "/api/cache/db/lyricMatch/set" -> {
          if (session.method != Method.POST) {
            return jsonResponse(Response.Status.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "POST required")
          }
          val body = readBody(session)
          val fingerprint = body.optString("fingerprint")
          val platform = body.optString("platform")
          val platformId = body.optString("platformId")
          if (fingerprint.isEmpty() || platform.isEmpty() || platformId.isEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, "BAD_REQUEST", "fingerprint, platform, platformId required")
          }
          val extra = if (body.has("extra") && !body.isNull("extra")) body.get("extra").toString() else null
          db.setLyricMatchCache(fingerprint, platform, platformId, extra)
          val res = JSONObject()
          res.put("ok", true)
          return jsonResponseObj(Response.Status.OK, res)
        }

        "/api/cache/db/ttml/get" -> {
          if (session.method != Method.GET) {
            return jsonResponse(Response.Status.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "GET required")
          }
          val platform = session.parameters["platform"]?.firstOrNull()
          val id = session.parameters["id"]?.firstOrNull()
          if (platform.isNullOrEmpty() || id.isNullOrEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, "BAD_REQUEST", "platform and id required")
          }
          val result = db.getLyricTtmlCache(platform, id)
          val res = JSONObject()
          // result: "miss" / null（负缓存）/ 实际内容（正缓存）
          when (result) {
            "miss" -> {
              res.put("status", "miss")
            }
            null -> {
              res.put("status", "negative")
            }
            else -> {
              res.put("status", "hit")
              res.put("content", result)
            }
          }
          return jsonResponseObj(Response.Status.OK, res)
        }

        "/api/cache/db/ttml/set" -> {
          if (session.method != Method.POST) {
            return jsonResponse(Response.Status.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "POST required")
          }
          val body = readBody(session)
          val platform = body.optString("platform")
          val id = body.optString("id")
          if (platform.isEmpty() || id.isEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, "BAD_REQUEST", "platform and id required")
          }
          val content = if (body.has("content") && !body.isNull("content")) body.getString("content") else null
          db.setLyricTtmlCache(platform, id, content)
          val res = JSONObject()
          res.put("ok", true)
          return jsonResponseObj(Response.Status.OK, res)
        }

        "/api/cache/db/lyric/get" -> {
          if (session.method != Method.GET) {
            return jsonResponse(Response.Status.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "GET required")
          }
          val platform = session.parameters["platform"]?.firstOrNull()
          val platformId = session.parameters["platformId"]?.firstOrNull()
          if (platform.isNullOrEmpty() || platformId.isNullOrEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, "BAD_REQUEST", "platform and platformId required")
          }
          val data = db.getLyricCache(platform, platformId)
          val res = JSONObject()
          if (data == null) {
            res.put("hit", false)
          } else {
            res.put("hit", true)
            res.put("data", data)
          }
          return jsonResponseObj(Response.Status.OK, res)
        }

        "/api/cache/db/lyric/set" -> {
          if (session.method != Method.POST) {
            return jsonResponse(Response.Status.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "POST required")
          }
          val body = readBody(session)
          val platform = body.optString("platform")
          val platformId = body.optString("platformId")
          val data = body.optString("data")
          if (platform.isEmpty() || platformId.isEmpty() || data.isEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, "BAD_REQUEST", "platform, platformId, data required")
          }
          db.setLyricCache(platform, platformId, data)
          val res = JSONObject()
          res.put("ok", true)
          return jsonResponseObj(Response.Status.OK, res)
        }

        "/api/cache/db/cleanExpired" -> {
          if (session.method != Method.POST) {
            return jsonResponse(Response.Status.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "POST required")
          }
          val ttmlCleaned = db.cleanExpiredLyricTtmlCache()
          val matchCleaned = db.cleanExpiredLyricMatchCache()
          val res = JSONObject()
          res.put("ok", true)
          res.put("ttmlCleaned", ttmlCleaned)
          res.put("matchCleaned", matchCleaned)
          return jsonResponseObj(Response.Status.OK, res)
        }
      }
      return jsonResponse(Response.Status.NOT_FOUND, "NOT_FOUND", "cache db endpoint not found")
    } catch (e: Exception) {
      Log.e(tag, "handleCacheDb error: $uri", e)
      return jsonResponse(Response.Status.INTERNAL_ERROR, "CACHE_DB_ERROR", e.message ?: "cache db error")
    }
  }

  override fun openWebSocket(handshake: IHTTPSession): WebSocket {
    val path = handshake.uri
    // 外部 API WS：按 enabled/wsEnabled/allowLan 鉴权，独立于 LAN 共享 WS
    if (path == "/ws/external") {
      val externalConfig = ExternalApiManager.config
      if (!externalConfig.enabled || !externalConfig.wsEnabled) {
        throw NanoHTTPD.ResponseException(Response.Status.FORBIDDEN, "External API WS disabled")
      }
      val isLocal = isLocalRequest(handshake)
      if (!isLocal && !externalConfig.allowLan) {
        throw NanoHTTPD.ResponseException(Response.Status.FORBIDDEN, "External API LAN access disabled")
      }
      if (!isLocal) {
        val token = extractExternalApiToken(handshake)
        if (externalConfig.token.isBlank() || token != externalConfig.token) {
          throw NanoHTTPD.ResponseException(Response.Status.UNAUTHORIZED, "Invalid external API token")
        }
      }
      return ExternalApiWebSocket(handshake, context)
    }
    // WS 鉴权：本机连接免 token；非本机必须携带匹配的 token，防止未授权设备接入
    val isLocal = isLocalRequest(handshake)
    val remoteIp = getClientIp(handshake)
    if (!isLocal) {
      if (!LanShareManager.config.enabled || !LanShareManager.config.collabEnabled) {
        throw NanoHTTPD.ResponseException(Response.Status.FORBIDDEN, "LAN collaboration disabled")
      }
      if (handshake.parameters["role"]?.firstOrNull() == "host") {
        throw NanoHTTPD.ResponseException(Response.Status.FORBIDDEN, "Remote host role is not allowed")
      }
      if (!LanShareManager.isCollabAllowed(remoteIp)) {
        throw NanoHTTPD.ResponseException(Response.Status.FORBIDDEN, "Device collaboration is not authorized")
      }
      val token = handshake.parameters["token"]?.firstOrNull() ?: ""
      val expected = LanShareManager.config.wsToken
      if (expected.isEmpty() || token != expected) {
        throw NanoHTTPD.ResponseException(Response.Status.UNAUTHORIZED, "Invalid WS token")
      }
    }
    return SyncWebSocket(handshake)
  }

  inner class SyncWebSocket(
    handshake: IHTTPSession,
  ) : WebSocket(handshake) {
    val role: String = handshake.parameters["role"]?.firstOrNull() ?: "receiver"
    val ip: String = getClientIp(handshake).ifEmpty { "unknown" }

    override fun onOpen() {
      wsClients.add(this)
      this@KotlinApiServer.startHeartbeat()
      if (role != "host" && lastSyncPayload != null) {
        try {
          send(lastSyncPayload)
        } catch (e: Exception) {
        }
      }
    }

    override fun onClose(
      code: NanoWSD.WebSocketFrame.CloseCode,
      reason: String,
      initiatedByRemote: Boolean,
    ) {
      wsClients.remove(this)
      if (wsClients.isEmpty()) {
        this@KotlinApiServer.stopHeartbeat()
      }
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
      if (!LanShareManager.config.enabled || !LanShareManager.config.collabEnabled) return
      val payload = message.textPayload
      try {
        val json = JSONObject(payload)
        val type = json.optString("type")
        when (type) {
          "time-sync-request" -> {
            val res = JSONObject()
            res.put("type", "time-sync-response")
            res.put("clientTs", json.optLong("clientTs", System.currentTimeMillis()))
            res.put("serverTs", System.currentTimeMillis())
            send(res.toString())
          }
          // 主机推送同步状态：更新缓存并转发到所有从设备
          "sync" -> {
            if (role == "host") {
              broadcastPlayback(json)
            }
          }
          // 主机推送动作通知（如主机手动切歌）：转发到所有从设备
          "action-notify" -> {
            if (role == "host") {
              for (c in wsClients) {
                if (c.role == "receiver") {
                  try {
                    c.send(payload)
                  } catch (e: Exception) {
                  }
                }
              }
            }
          }
          // 从设备上送遥控指令：转发到主机 WS 客户端执行，同时通知其他从设备
          "command" -> {
            if (role == "receiver") {
              val action = json.optString("action")
              // 白名单校验：拒绝非预期 action，防止从设备触发未授权操作
              if (action.isNotEmpty() && action in allowedActions) {
                val notify = JSONObject()
                notify.put("type", "command")
                notify.put("action", action)
                notify.put("deviceName", ip)
                if (json.has("value")) notify.put("value", json.get("value"))

                // 转发到主机 WS 客户端（role=host），由主机端 execCommand 执行
                for (c in wsClients) {
                  if (c.role == "host") {
                    try {
                      c.send(notify.toString())
                    } catch (e: Exception) {
                    }
                  }
                }
                // 触发原生事件，供 Android 端 UI 反馈
                AndroidLanSharePlugin.instance?.notifyCommand(notify)

                // 通知其他从设备展示动作提示
                val actNotify = JSONObject()
                actNotify.put("type", "action-notify")
                actNotify.put("action", action)
                actNotify.put("deviceName", ip)
                actNotify.put("ts", System.currentTimeMillis())
                for (c in wsClients) {
                  if (c.role == "receiver" && c != this) {
                    try {
                      c.send(actNotify.toString())
                    } catch (e: Exception) {
                    }
                  }
                }
              }
            }
          }
        }
      } catch (e: Exception) {
      }
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame) {}

    override fun onException(exception: java.io.IOException) {}
  }

  /** 远程抓取大小上限 10 MB */
  private val remoteFetchMaxBytes = 10L * 1024 * 1024

  /**
   * 直接在 Kotlin 层抓取远程 URL 字节，使用 Android 原生 HttpURLConnection（含系统 CA 证书）。
   * 避免代理到 Node.js Mobile 时因 TLS/SSL 证书问题返回 500。
   */
  private fun handleFetchRemoteBytes(session: IHTTPSession): Response {
    val targetUrl = session.parameters["url"]?.firstOrNull()
    if (targetUrl.isNullOrBlank()) {
      return jsonResponseObj(
        Response.Status.BAD_REQUEST,
        JSONObject().put("success", false).put("error", "MISSING_URL"),
      )
    }

    val parsed =
      try {
        URI(targetUrl)
      } catch (e: Exception) {
        return jsonResponseObj(
          Response.Status.BAD_REQUEST,
          JSONObject().put("success", false).put("error", "BAD_URL"),
        )
      }

    val scheme = parsed.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
      return jsonResponseObj(
        Response.Status.BAD_REQUEST,
        JSONObject().put("success", false).put("error", "BAD_PROTOCOL"),
      )
    }

    val host = parsed.host?.lowercase() ?: ""
    if (isPrivateHost(host)) {
      return jsonResponseObj(
        Response.Status.BAD_REQUEST,
        JSONObject().put("success", false).put("error", "PRIVATE_HOST"),
      )
    }

    return try {
      val url = URL(targetUrl)
      val connection = url.openConnection() as HttpURLConnection
      connection.requestMethod = "GET"
      connection.connectTimeout = 10_000
      connection.readTimeout = 15_000
      connection.instanceFollowRedirects = true
      connection.setRequestProperty("User-Agent", "SPlayer-Next-Android")

      val responseCode = connection.responseCode
      if (responseCode != 200) {
        connection.errorStream?.close()
        return jsonResponseObj(
          Response.Status.OK,
          JSONObject().put("success", false).put("error", "UPSTREAM_$responseCode"),
        )
      }

      val declaredLength = connection.contentLengthLong
      if (declaredLength > remoteFetchMaxBytes) {
        connection.inputStream.close()
        return jsonResponseObj(
          Response.Status.OK,
          JSONObject().put("success", false).put("error", "TOO_LARGE"),
        )
      }

      val inputStream = connection.inputStream
      val outputStream = java.io.ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      var totalRead = 0
      while (true) {
        val read = inputStream.read(buffer)
        if (read == -1) break
        totalRead += read
        if (totalRead > remoteFetchMaxBytes) {
          inputStream.close()
          return jsonResponseObj(
            Response.Status.OK,
            JSONObject().put("success", false).put("error", "TOO_LARGE"),
          )
        }
        outputStream.write(buffer, 0, read)
      }
      inputStream.close()

      val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
      jsonResponseObj(
        Response.Status.OK,
        JSONObject()
          .put("success", true)
          .put("data", base64)
          .put("encoding", "base64"),
      )
    } catch (e: Exception) {
      Log.w(tag, "fetchRemoteBytes failed: ${e.message}")
      jsonResponseObj(
        Response.Status.OK,
        JSONObject().put("success", false).put("error", "FETCH_ERROR"),
      )
    }
  }

  private fun proxyToNodeJs(session: IHTTPSession): Response {
    val urlString =
      "http://127.0.0.1:$nodejsPort${session.uri}${if (session.queryParameterString != null) "?" + session.queryParameterString else ""}"

    try {
      val url = URL(urlString)
      val connection = url.openConnection() as HttpURLConnection
      connection.requestMethod = session.method.name
      connection.connectTimeout = nodeConnectTimeoutMs
      connection.readTimeout = getNodeReadTimeout(session.uri)
      connection.instanceFollowRedirects = false
      connection.useCaches = false

      for ((key, value) in session.headers) {
        // 跳过 hop-by-hop 头与 content-length：
        // content-length 由 setFixedLengthStreamingMode 设置，转发原始值会冲突导致 IllegalStateException
        if (key.equals("host", ignoreCase = true)) continue
        if (key.equals("content-length", ignoreCase = true)) continue
        if (key.equals("transfer-encoding", ignoreCase = true)) continue
        if (key.equals("connection", ignoreCase = true)) continue
        connection.setRequestProperty(key, value)
      }

      if (session.method == Method.POST || session.method == Method.PUT) {
        connection.doOutput = true
        copyRequestBody(session, connection)
      }

      val responseCode = connection.responseCode
      val inputStream: InputStream =
        if (responseCode >= 400) {
          connection.errorStream ?: connection.inputStream
        } else {
          connection.inputStream
        }

      val mimeType = connection.contentType ?: MIME_PLAINTEXT
      val response =
        newChunkedResponse(
          Response.Status.lookup(responseCode) ?: Response.Status.INTERNAL_ERROR,
          mimeType,
          inputStream,
        )

      for ((key, values) in connection.headerFields) {
        if (key != null && values.isNotEmpty()) {
          // Content-Length 与 newChunkedResponse 的 Transfer-Encoding: chunked 互斥，
          // 透传会让响应同时携带两者，严格 HTTP 客户端判协议非法直接拒收
          if (key.startsWith("Access-Control-", ignoreCase = true) ||
            key.equals("Transfer-Encoding", ignoreCase = true) ||
            key.equals("Content-Length", ignoreCase = true)
          ) {
            continue
          }
          response.addHeader(key, values[0])
        }
      }

      return response
    } catch (e: ConnectException) {
      return jsonResponse(
        Response.Status.SERVICE_UNAVAILABLE,
        "EMBEDDED_API_UNAVAILABLE",
        "Node.js embedded API is not ready",
      )
    } catch (e: java.io.IOException) {
      // Node.js 进程崩溃 / 连接中断 / 读取超时等 IO 层错误，返回 503 让前端重试
      return jsonResponse(
        Response.Status.SERVICE_UNAVAILABLE,
        "EMBEDDED_API_IO_ERROR",
        e.message ?: "Node.js embedded API connection failed",
      )
    } catch (e: Exception) {
      return jsonResponse(
        Response.Status.INTERNAL_ERROR,
        "EMBEDDED_API_PROXY_ERROR",
        e.message ?: "Proxy to Node.js failed",
      )
    }
  }

  private fun getNodeReadTimeout(uri: String): Int =
    when {
      uri == "/api/apis/call" -> nodeSlowReadTimeoutMs
      uri == "/api/lyrics/fetchTTMLOverlay" -> nodeSlowReadTimeoutMs
      uri == "/api/system/fetchRemoteBytes" -> nodeSlowReadTimeoutMs
      else -> nodeReadTimeoutMs
    }

  private fun copyRequestBody(
    session: IHTTPSession,
    connection: HttpURLConnection,
  ) {
    val bytes = readRawRequestBody(session)
    connection.setFixedLengthStreamingMode(bytes.size)
    connection.outputStream.use { output ->
      output.write(bytes)
    }
  }

  private fun serveStaticFile(uri: String): Response {
    var path = uri.removePrefix("/")
    if (path.isEmpty() || path.endsWith("/")) {
      path += "index.html"
    }

    val assetPath = "public/$path"

    return try {
      val inputStream = context.assets.open(assetPath)
      val mimeType = getMimeType(path)
      newChunkedResponse(Response.Status.OK, mimeType, inputStream)
    } catch (e: Exception) {
      try {
        val indexStream = context.assets.open("public/index.html")
        newChunkedResponse(Response.Status.OK, "text/html", indexStream)
      } catch (ex: Exception) {
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
      }
    }
  }

  private fun getMimeType(path: String): String =
    when {
      path.endsWith(".html") -> "text/html"
      path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript"
      path.endsWith(".css") -> "text/css"
      path.endsWith(".json") -> "application/json"
      path.endsWith(".png") -> "image/png"
      path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
      path.endsWith(".svg") -> "image/svg+xml"
      path.endsWith(".woff") -> "font/woff"
      path.endsWith(".woff2") -> "font/woff2"
      else -> MIME_PLAINTEXT
    }
}
