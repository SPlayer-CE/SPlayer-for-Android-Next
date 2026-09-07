package top.imsyy.splayer_next.android.server

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import org.json.JSONObject

/** 外部 API 配置管理单例 */
object ExternalApiManager {
  private const val TAG = "ExternalApiManager"
  private var configPath: File? = null
  private val gson: Gson = GsonBuilder().create()
  private var nodejsPort: Int = 0
  private val secureRandom = SecureRandom()

  @Volatile
  var config = ExternalApiConfig()
    private set

  /** 初始化配置目录与 Node.js 端口，加载本地缓存配置 */
  fun init(
    context: Context,
    nodePort: Int,
  ) {
    val rootDir = File(context.getExternalFilesDir(null), "splayer-data")
    if (!rootDir.exists()) rootDir.mkdirs()
    configPath = File(rootDir, "external-api.json")
    nodejsPort = nodePort
    loadConfig()
  }

  fun loadConfig() {
    try {
      val file = configPath ?: return
      if (!file.exists()) return
      val json = file.readText()
      config = gson.fromJson(json, ExternalApiConfig::class.java) ?: ExternalApiConfig()
      if (config.token.isBlank()) {
        config.token = generateToken()
        saveConfig()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to load external API config", e)
    }
  }

  fun saveConfig() {
    try {
      val file = configPath ?: return
      val tempFile = File(file.parentFile, "${file.name}.tmp")
      tempFile.writeText(gson.toJson(config))
      tempFile.renameTo(file)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to save external API config", e)
    }
  }

  /** 原子更新配置并持久化 */
  fun updateConfig(block: (ExternalApiConfig) -> Unit) {
    synchronized(this) {
      block(config)
      if (config.token.isBlank()) {
        config.token = generateToken()
      }
      saveConfig()
    }
  }

  fun ensureToken(): String {
    synchronized(this) {
      if (config.token.isBlank()) {
        config.token = generateToken()
        saveConfig()
      }
      return config.token
    }
  }

  /** 从 Node.js 设置存储重新加载配置（restart 时调用），同步到本地缓存 */
  fun reloadConfigFromNode() {
    if (nodejsPort == 0) return
    try {
      val url = URL("http://127.0.0.1:$nodejsPort/api/config/get?keyPath=externalApi")
      val connection = url.openConnection() as HttpURLConnection
      connection.requestMethod = "GET"
      connection.connectTimeout = 1500
      connection.readTimeout = 1500
      connection.useCaches = false
      val responseCode = connection.responseCode
      if (responseCode in 200..299) {
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        config =
          ExternalApiConfig(
            enabled = json.optBoolean("enabled", false),
            wsEnabled = json.optBoolean("wsEnabled", false),
            allowLan = json.optBoolean("allowLan", false),
            port = json.optInt("port", 6688),
            token = config.token.ifBlank { generateToken() },
          )
        saveConfig()
      }
      connection.disconnect()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to reload config from Node.js", e)
    }
  }

  private fun generateToken(): String {
    val bytes = ByteArray(32)
    secureRandom.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
  }
}
