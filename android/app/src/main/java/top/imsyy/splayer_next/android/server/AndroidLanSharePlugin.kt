package top.imsyy.splayer_next.android.server

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONObject

@CapacitorPlugin(name = "AndroidLanShare")
class AndroidLanSharePlugin : Plugin() {
  companion object {
    var instance: AndroidLanSharePlugin? = null
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

  fun notifyCommand(command: JSONObject) {
    val jsObj = JSObject()
    command.keys().forEach { jsObj.put(it, command.get(it)) }
    notifyListeners("onCommand", jsObj)
  }

  @PluginMethod
  fun getStatus(call: PluginCall) {
    val devices = LanShareManager.getVisibleDevices()
    val res = JSObject()
    res.put("enabled", LanShareManager.config.enabled)
    res.put("collabEnabled", LanShareManager.config.collabEnabled)
    res.put("shareUserInfo", LanShareManager.config.shareUserInfo)
    res.put("deviceCount", devices.size)
    res.put("sharedCount", devices.count { it.sharedLogin })
    res.put("serverIp", "0.0.0.0")
    res.put("wsToken", LanShareManager.config.wsToken)
    call.resolve(res)
  }

  @PluginMethod
  fun setEnabled(call: PluginCall) {
    val enabled = call.getBoolean("enabled") ?: false
    LanShareManager.config.enabled = enabled
    if (enabled) {
      // 开启共享时生成新 token，旧 token 立即失效
      LanShareManager.regenerateToken()
    } else {
      LanShareManager.config.collabEnabled = false
    }
    LanShareManager.saveConfig()
    call.resolve(JSObject().put("ok", true).put("enabled", enabled))
  }

  @PluginMethod
  fun setCollabEnabled(call: PluginCall) {
    val enabled = call.getBoolean("enabled") ?: false
    LanShareManager.config.collabEnabled = enabled
    LanShareManager.saveConfig()
    call.resolve(JSObject().put("ok", true).put("collabEnabled", enabled))
  }

  @PluginMethod
  fun setShareUserInfo(call: PluginCall) {
    val enabled = call.getBoolean("enabled") ?: false
    LanShareManager.config.shareUserInfo = enabled
    LanShareManager.saveConfig()
    call.resolve(JSObject().put("ok", true).put("shareUserInfo", enabled))
  }

  @PluginMethod
  fun getDevices(call: PluginCall) {
    val res = JSObject()
    res.put("ok", true)
    val array =
      org.json.JSONArray(
        com.google.gson
          .Gson()
          .toJson(LanShareManager.getVisibleDevices()),
      )
    res.put("devices", array)
    call.resolve(res)
  }

  @PluginMethod
  fun addDevice(call: PluginCall) {
    val ip = call.getString("ip") ?: ""
    val name = call.getString("name")
    if (ip.isNotEmpty()) {
      val existing = LanShareManager.config.devices.find { it.ip == ip }
      if (existing == null) {
        LanShareManager.config.devices.add(LanDevice(ip, name, false, false, System.currentTimeMillis()))
        LanShareManager.saveConfig()
      }
    }
    getDevices(call)
  }

  @PluginMethod
  fun removeDevice(call: PluginCall) {
    val ip = call.getString("ip") ?: ""
    LanShareManager.removeDevice(ip)
    getDevices(call)
  }

  @PluginMethod
  fun setDeviceCollab(call: PluginCall) {
    val ip = call.getString("ip") ?: ""
    val enabled = call.getBoolean("enabled") ?: false
    if (ip.isEmpty()) {
      call.reject("ip required")
      return
    }
    val device = LanShareManager.setDeviceCollab(ip, enabled)
    val dObj = JSObject()
    dObj.put("ip", device.ip)
    dObj.put("name", device.name)
    dObj.put("sharedLogin", device.sharedLogin)
    dObj.put("shareCollab", device.shareCollab)
    dObj.put("addedAt", device.addedAt)
    call.resolve(JSObject().put("ok", true).put("device", dObj))
  }

  @PluginMethod
  fun shareLogin(call: PluginCall) {
    val ip = call.getString("ip") ?: ""
    val shared = call.getBoolean("shared") ?: false
    val device = LanShareManager.config.devices.find { it.ip == ip }
    if (device != null) {
      device.sharedLogin = shared
      LanShareManager.saveConfig()
      val dObj = JSObject()
      dObj.put("ip", device.ip)
      dObj.put("name", device.name)
      dObj.put("sharedLogin", device.sharedLogin)
      call.resolve(JSObject().put("ok", true).put("device", dObj))
    } else {
      call.resolve(JSObject().put("ok", false))
    }
  }

  @PluginMethod
  fun broadcastPlayback(call: PluginCall) {
    try {
      val data = call.data
      val json = JSONObject(data.toString())
      KotlinApiServer.broadcastPlayback(json)
      call.resolve(JSObject().put("ok", true))
    } catch (e: Exception) {
      call.reject("broadcastPlayback failed")
    }
  }

  @PluginMethod
  fun updateQueue(call: PluginCall) {
    val json = call.getString("json") ?: ""
    if (json.isEmpty()) {
      call.reject("updateQueue: json required")
      return
    }
    KotlinApiServer.updateQueue(json)
    call.resolve(JSObject().put("ok", true))
  }

  @PluginMethod
  fun updateLyric(call: PluginCall) {
    val json = call.getString("json") ?: ""
    if (json.isEmpty()) {
      call.reject("updateLyric: json required")
      return
    }
    KotlinApiServer.updateLyric(json)
    call.resolve(JSObject().put("ok", true))
  }

  @PluginMethod
  fun getLocalIPs(call: PluginCall) {
    val ips = org.json.JSONArray()
    try {
      val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
      while (interfaces.hasMoreElements()) {
        val intf = interfaces.nextElement()
        val addrs = intf.inetAddresses
        while (addrs.hasMoreElements()) {
          val addr = addrs.nextElement()
          if (!addr.isLoopbackAddress) {
            val obj = JSONObject()
            obj.put("name", intf.name)
            obj.put("address", addr.hostAddress)
            if (addr is java.net.Inet4Address) {
              obj.put("family", "IPv4")
            } else if (addr is java.net.Inet6Address && !addr.hostAddress!!.startsWith("fe80")) {
              obj.put("family", "IPv6")
            }
            if (obj.has("family")) ips.put(obj)
          }
        }
      }
    } catch (e: Exception) {
    }
    val res = JSObject()
    res.put("ok", true)
    res.put("ips", ips)
    res.put("port", 13962)
    call.resolve(res)
  }
}
