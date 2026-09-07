package top.imsyy.splayer_next.android.cache

import android.util.Base64
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONException
import org.json.JSONObject

/**
 * Android 端缓存 Capacitor 插件。
 *
 * <p>所有数据走 base64（JSON 不能携带二进制）。读取大文件性能损失可接受（封面通常 < 200KB，
 * 歌词 < 50KB；音频不走该通道而是 ExoPlayer SimpleCache 直读）。
 *
 * <p>JS 调用 [CacheStorage] 单例。
 */
@CapacitorPlugin(name = "AndroidCache")
class AndroidCachePlugin : Plugin() {
  companion object {
    /**
     * type 白名单：仅允许 CacheStorage 已声明的子目录名，杜绝 "../shared_prefs" 之类路径穿越。
     * 与 CacheStorage 层的第二道防线（typeDir canonical 校验）配合，双重隔离。
     */
    private val ALLOWED_TYPES: Set<String> = CacheStorage.knownTypes().toSet()

    /** 从 PluginCall 读取一个数字字段，按 Double/Long/Integer/String 顺序兜底，全部 null 时返回 null。 */
    private fun readNumberAsLong(
      call: PluginCall,
      key: String,
    ): Long? {
      val d = call.getDouble(key)
      if (d != null) return d.toLong()
      val l = call.getLong(key)
      if (l != null) return l
      val i = call.getInt(key)
      if (i != null) return i.toLong()
      val s = call.getString(key)
      if (!s.isNullOrEmpty()) {
        try {
          return s.toDouble().toLong()
        } catch (ignored: NumberFormatException) {
          // fall through
        }
      }
      return null
    }
  }

  private fun storage(): CacheStorage = CacheStorage.getInstance(context)

  /** 获取 DbCacheHelper 单例，首次调用时初始化 */
  private fun dbHelper(): DbCacheHelper = DbCacheHelper.init(context)

  /** db 缓存类别白名单 */
  private val dbCategories: Set<String> = setOf("lyric", "lyricTTML", "lyricMatch")

  /** 校验 type 在白名单内；非法直接 reject 并返 null。 */
  private fun requireValidType(
    type: String?,
    call: PluginCall,
  ): String? {
    if (type == null) {
      call.reject("type 必填")
      return null
    }
    if (!ALLOWED_TYPES.contains(type)) {
      call.reject("非法 type: $type")
      return null
    }
    return type
  }

  @PluginMethod
  fun read(call: PluginCall) {
    val type = requireValidType(call.getString("type"), call) ?: return
    val key = call.getString("key")
    if (key == null) {
      call.reject("key 必填")
      return
    }
    val data = storage().read(type, key)
    val ret = JSObject()
    if (data == null) {
      ret.put("hit", false)
    } else {
      ret.put("hit", true)
      ret.put("data", Base64.encodeToString(data, Base64.NO_WRAP))
      ret.put("size", data.size)
    }
    call.resolve(ret)
  }

  @PluginMethod
  fun write(call: PluginCall) {
    val type = requireValidType(call.getString("type"), call) ?: return
    val key = call.getString("key")
    val dataB64 = call.getString("data")
    if (key == null || dataB64 == null) {
      call.reject("key / data 必填")
      return
    }
    val bytes: ByteArray
    try {
      bytes = Base64.decode(dataB64, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
      call.reject("data 不是合法 base64")
      return
    }
    val ok = storage().write(type, key, bytes)
    val ret = JSObject()
    ret.put("success", ok)
    if (!ok) ret.put("message", "写入失败（磁盘不足或 IO 异常）")
    call.resolve(ret)
  }

  @PluginMethod
  fun remove(call: PluginCall) {
    val type = requireValidType(call.getString("type"), call) ?: return
    val key = call.getString("key")
    if (key == null) {
      call.reject("key 必填")
      return
    }
    val ok = storage().remove(type, key)
    val ret = JSObject()
    ret.put("success", ok)
    call.resolve(ret)
  }

  @PluginMethod
  fun list(call: PluginCall) {
    val type = requireValidType(call.getString("type"), call) ?: return
    val entries = storage().list(type)
    val arr = JSArray()
    for (e in entries) {
      val obj = JSObject()
      obj.put("key", e.key)
      obj.put("size", e.size)
      obj.put("mtime", e.mtime)
      arr.put(obj)
    }
    val ret = JSObject()
    ret.put("entries", arr)
    call.resolve(ret)
  }

  @PluginMethod
  fun clear(call: PluginCall) {
    val type = requireValidType(call.getString("type"), call) ?: return
    val ok = storage().clear(type)
    val ret = JSObject()
    ret.put("success", ok)
    call.resolve(ret)
  }

  @PluginMethod
  fun clearAll(call: PluginCall) {
    val ok = storage().clearAll()
    val ret = JSObject()
    ret.put("success", ok)
    call.resolve(ret)
  }

  /**
   * 清空指定 db 类别缓存（lyric / lyricTTML / lyricMatch）。
   * @param category - db 类别 id
   */
  @PluginMethod
  fun clearDbCache(call: PluginCall) {
    val category = call.getString("category")
    if (category == null || !dbCategories.contains(category)) {
      call.reject("非法 db category: $category")
      return
    }
    val db = dbHelper()
    when (category) {
      "lyric" -> db.clearLyricCache()
      "lyricTTML" -> db.clearLyricTtmlCache()
      "lyricMatch" -> db.clearLyricMatchCache()
    }
    val ret = JSObject()
    ret.put("success", true)
    call.resolve(ret)
  }

  /** 一键清空全部 db 类别缓存（lyric + lyricTTML + lyricMatch）。 */
  @PluginMethod
  fun clearAllDbCache(call: PluginCall) {
    val db = dbHelper()
    db.clearLyricCache()
    db.clearLyricTtmlCache()
    db.clearLyricMatchCache()
    val ret = JSObject()
    ret.put("success", true)
    call.resolve(ret)
  }

  /** 返回 {totalBytes, perType: {lyrics:..., covers:...}, deviceFreeBytes, maxBytes, dbStats: {...}}。 */
  @PluginMethod
  fun getStats(call: PluginCall) {
    val cs = storage()
    val ret = JSObject()
    ret.put("totalBytes", cs.getTotalBytes())
    ret.put("deviceFreeBytes", cs.getDeviceFreeBytes())
    ret.put("maxBytes", cs.getMaxBytes())
    // 设备空间不足时的有效上限（剩余空间 × 60% 与用户设定取 min）；UI 用于展示「实际生效配额」。
    ret.put("effectiveMaxBytes", cs.getEffectiveMaxBytes())
    // 缓存根目录绝对路径，供 UI 展示
    ret.put("cacheDir", cs.getRootDir().absolutePath)

    val perType = JSObject()
    val map = cs.getPerTypeBytes()
    for ((k, v) in map) {
      perType.put(k, v)
    }
    ret.put("perType", perType)

    // db 类别缓存占用（lyric_cache / lyric_ttml_cache / lyric_match_cache）
    val db = dbHelper()
    val dbStats = JSObject()
    dbStats.put("lyric", db.getLyricCacheSize())
    dbStats.put("lyricTTML", db.getLyricTtmlCacheSize())
    dbStats.put("lyricMatch", db.getLyricMatchCacheSize())
    ret.put("dbStats", dbStats)
    call.resolve(ret)
  }

  /** 返回缓存根目录绝对路径，供 UI 展示。 */
  @PluginMethod
  fun getDir(call: PluginCall) {
    val ret = JSObject()
    ret.put("dir", storage().getRootDir().absolutePath)
    call.resolve(ret)
  }

  /**
   * 设置最大缓存上限（字节）。最小 256MB，setting 端调用。
   *
   * <p>JS Number 大于 Integer.MAX_VALUE（2^31-1≈2.15GB 对应字节数 2147483647）时，Capacitor 把它
   * 反序列化为 Long，{@code getDouble} 仅接受 Double/Float/Integer 三种类型，遇到 Long 直接返回 null
   * （历史上线后 sizeLimitGb 默认 10GB → 10*2^30=10737418240 > MAX_INT 触发 "maxBytes 必填"）。
   * 这里逐层兜底：getDouble → getLong → getInt → getString，覆盖所有数值表示。
   */
  @PluginMethod
  fun setMaxBytes(call: PluginCall) {
    val bytes = readNumberAsLong(call, "maxBytes")
    if (bytes == null) {
      call.reject("maxBytes 必填")
      return
    }
    storage().setMaxBytes(bytes)
    val ret = JSObject()
    ret.put("success", true)
    ret.put("appliedMaxBytes", storage().getMaxBytes())
    call.resolve(ret)
  }

  /** 主动触发一次全局 LRU 驱逐（设置面板「立即整理」按钮可用）。 */
  @PluginMethod
  fun enforceLimit(call: PluginCall) {
    storage().enforceLimit()
    val ret = JSObject()
    ret.put("success", true)
    ret.put("totalBytes", storage().getTotalBytes())
    call.resolve(ret)
  }

  /** 提供 JSON 序列化辅助（IDE 已 import 但部分调用未用，避免 unused warn）。 */
  @Suppress("unused")
  @Throws(JSONException::class)
  private fun toJsObject(json: JSONObject): JSObject {
    val obj = JSObject()
    val it = json.keys()
    while (it.hasNext()) {
      val k = it.next()
      obj.put(k, json.get(k))
    }
    return obj
  }
}
