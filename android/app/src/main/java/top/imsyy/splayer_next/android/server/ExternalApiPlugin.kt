package top.imsyy.splayer_next.android.server

import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.net.Inet4Address
import java.net.NetworkInterface
import org.json.JSONObject

/** 外部 API 服务 Capacitor 插件，提供重启与状态查询 */
@CapacitorPlugin(name = "ExternalApi")
class ExternalApiPlugin : Plugin() {
  companion object {
    private const val TAG = "ExternalApiPlugin"

    /** 外部 API 复用 KotlinApiServer 端口 */
    private const val KOTLIN_API_SERVER_PORT = 13962
    var instance: ExternalApiPlugin? = null
  }

  override fun load() {
    super.load()
    instance = this
  }

  override fun handleOnDestroy() {
    if (instance == this) {
      instance = null
    }
    super.handleOnDestroy()
  }

  /** 重启外部 API 服务：从 Node.js 设置存储同步配置并返回最新状态 */
  @PluginMethod
  fun restart(call: PluginCall) {
    Thread {
      try {
        ExternalApiManager.reloadConfigFromNode()
        call.resolve(buildStatus())
      } catch (e: Exception) {
        Log.e(TAG, "restart failed", e)
        call.reject("restart failed", e)
      }
    }.start()
  }

  /** 返回外部 API 服务运行时状态 */
  @PluginMethod
  fun getStatus(call: PluginCall) {
    call.resolve(buildStatus())
  }

  private fun buildStatus(): JSObject {
    val config = ExternalApiManager.config
    val token = ExternalApiManager.ensureToken()
    val res = JSObject()
    res.put("listening", config.enabled)
    res.put("allowLan", config.allowLan)
    res.put("token", token)
    if (config.enabled) {
      res.put("host", if (config.allowLan) (getLanAddress() ?: "0.0.0.0") else "127.0.0.1")
      res.put("port", KOTLIN_API_SERVER_PORT)
    } else {
      res.put("host", JSONObject.NULL)
      res.put("port", JSONObject.NULL)
    }
    res.put("error", JSONObject.NULL)
    return res
  }

  /** 取局域网 IPv4 地址，优先 192.168 网段，其次 10. 网段 */
  private fun getLanAddress(): String? {
    val candidates = mutableListOf<String>()
    try {
      val interfaces = NetworkInterface.getNetworkInterfaces()
      while (interfaces.hasMoreElements()) {
        val intf = interfaces.nextElement()
        val addrs = intf.inetAddresses
        while (addrs.hasMoreElements()) {
          val addr = addrs.nextElement()
          if (!addr.isLoopbackAddress && addr is Inet4Address) {
            candidates.add(addr.hostAddress ?: "")
          }
        }
      }
    } catch (e: Exception) {
    }
    return candidates.firstOrNull { it.startsWith("192.168.") }
      ?: candidates.firstOrNull { it.startsWith("10.") }
      ?: candidates.firstOrNull()
  }
}
