package top.imsyy.splayer_next.android.server

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class LanDevice(
  val ip: String,
  val name: String?,
  var sharedLogin: Boolean,
  var shareCollab: Boolean,
  val addedAt: Long,
)

data class LanShareConfig(
  var enabled: Boolean = false,
  var collabEnabled: Boolean = false,
  var shareUserInfo: Boolean = false,
  var wsToken: String = "",
  var devices: MutableList<LanDevice> = mutableListOf(),
)

object LanShareManager {
  private const val TAG = "LanShareManager"
  private const val OBSERVED_DEVICE_TTL_MS = 15_000L
  private const val MAX_OBSERVED_DEVICES = 64
  private var configPath: File? = null
  private val gson: Gson = GsonBuilder().create()
  private val secureRandom = SecureRandom()
  private val observedDevices = ConcurrentHashMap<String, Long>()

  var config = LanShareConfig()
    private set

  fun init(context: Context) {
    val rootDir = File(context.getExternalFilesDir(null), "splayer-data")
    if (!rootDir.exists()) rootDir.mkdirs()
    configPath = File(rootDir, "lan-share.json")
    loadConfig()
    config.devices = Collections.synchronizedList(config.devices.toMutableList())
    if (config.enabled && config.wsToken.isBlank()) regenerateToken()
  }

  private fun loadConfig() {
    try {
      val file = configPath ?: return
      if (!file.exists()) return
      val json = file.readText()
      config = gson.fromJson(json, LanShareConfig::class.java) ?: LanShareConfig()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to load LAN share config", e)
    }
  }

  fun saveConfig() {
    try {
      val file = configPath ?: return
      val tempFile = File(file.parentFile, "${file.name}.tmp")
      val json = synchronized(config.devices) { gson.toJson(config) }
      tempFile.writeText(json)
      tempFile.renameTo(file)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to save LAN share config", e)
    }
  }

  fun observeDevice(ip: String) {
    if (ip.isBlank()) return
    purgeObservedDevices()
    if (!observedDevices.containsKey(ip) && observedDevices.size >= MAX_OBSERVED_DEVICES) {
      observedDevices.minByOrNull { it.value }?.key?.let(observedDevices::remove)
    }
    observedDevices[ip] = System.currentTimeMillis()
  }

  fun getVisibleDevices(): List<LanDevice> {
    purgeObservedDevices()
    val configured = synchronized(config.devices) { config.devices.map(LanDevice::copy) }
    val configuredIps = configured.mapTo(mutableSetOf()) { it.ip }
    val observed =
      observedDevices.entries
        .filter { it.key !in configuredIps }
        .map { LanDevice(it.key, null, false, false, it.value) }
    return configured + observed
  }

  fun isCollabAllowed(ip: String): Boolean =
    synchronized(config.devices) {
      config.devices.any { it.ip == ip && it.shareCollab }
    }

  fun setDeviceCollab(
    ip: String,
    enabled: Boolean,
  ): LanDevice {
    val device =
      synchronized(config.devices) {
        config.devices.find { it.ip == ip }
          ?: LanDevice(ip, null, false, enabled, System.currentTimeMillis()).also {
            config.devices.add(it)
          }
      }
    device.shareCollab = enabled
    observedDevices.remove(ip)
    saveConfig()
    return device.copy()
  }

  fun removeDevice(ip: String) {
    synchronized(config.devices) {
      config.devices.removeAll { it.ip == ip }
    }
    observedDevices.remove(ip)
    saveConfig()
  }

  private fun purgeObservedDevices() {
    val expiredBefore = System.currentTimeMillis() - OBSERVED_DEVICE_TTL_MS
    observedDevices.entries.removeIf { it.value < expiredBefore }
  }

  /** 生成 32 字节随机 token（十六进制编码），用于 WS 连接鉴权 */
  fun regenerateToken(): String {
    val bytes = ByteArray(32)
    secureRandom.nextBytes(bytes)
    val token = bytes.joinToString("") { "%02x".format(it) }
    config.wsToken = token
    saveConfig()
    return token
  }
}
