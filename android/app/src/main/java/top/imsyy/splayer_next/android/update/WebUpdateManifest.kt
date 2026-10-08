package top.imsyy.splayer_next.android.update

import org.json.JSONObject

/** 版本目录名段中不被允许的字符（白名单 [A-Za-z0-9._-] 之外） */
private val REGEX_UNSAFE_VERSION_CHARS = Regex("[^A-Za-z0-9._-]")

/**
 * 热更文件条目：与前端 shared/types/liveUpdate.ts 的 LiveUpdateFileEntry 对齐。
 */
data class UpdateFileEntry(
  val path: String,
  val sha256: String,
  val size: Long,
)

/**
 * 解析后的热更清单：与前端 shared/types/liveUpdate.ts 的 LiveUpdateManifest 对齐。
 */
data class UpdateManifest(
  val version: String,
  val minNativeVersion: Int,
  val channel: String,
  val timestamp: Long,
  val files: Map<String, UpdateFileEntry>,
  /** manifest.json 原文（保留用于 applied-manifest 持久化与后续验签复用） */
  val rawJson: String? = null,
) {
  companion object {
    /**
     * 从 manifest.json 文本解析清单。
     * files 字段为 "path -> {path, sha256, size}" 字典（scripts/generate-live-update-manifest.ts 产物）。
     *
     * @param rawJson manifest.json 原文
     * @return 解析后的清单
     */
    fun parse(rawJson: String): UpdateManifest {
      val root = JSONObject(rawJson)
      val filesJson = root.optJSONObject("files") ?: JSONObject()
      val files = LinkedHashMap<String, UpdateFileEntry>()
      val keys = filesJson.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        val entry = filesJson.getJSONObject(key)
        files[key] =
          UpdateFileEntry(
            path = entry.optString("path", key),
            sha256 = entry.getString("sha256"),
            size = entry.optLong("size", 0L),
          )
      }
      return UpdateManifest(
        version = root.getString("version"),
        minNativeVersion = root.optInt("minNativeVersion", 0),
        channel = root.optString("channel", "stable"),
        timestamp = root.optLong("timestamp", 0L),
        files = files,
        rawJson = rawJson,
      )
    }
  }
}

/**
 * 差量计算：返回远端存在而本地缺失或哈希不一致的条目（保持远端清单顺序）。
 * 本地为空表时视为首次热更，差量即全量（正确语义）。
 *
 * @param remote 远端清单哈希表（path -> entry）
 * @param local 本地已应用哈希表（path -> entry）
 * @return 需要下载的差量条目
 */
fun computeDiff(
  remote: Map<String, UpdateFileEntry>,
  local: Map<String, UpdateFileEntry>,
): List<UpdateFileEntry> {
  val diff = ArrayList<UpdateFileEntry>(remote.size)
  for ((path, entry) in remote) {
    // 本地缺失或哈希不一致才进入差量；哈希比对不区分大小写（hex 一致）
    if (local[path]?.sha256 != entry.sha256) {
      diff.add(entry)
    }
  }
  return diff
}

/**
 * 将版本号净化为安全的目录名段：仅保留 [A-Za-z0-9._-]，其余替换为 '-'，
 * 并拒绝 "." / ".." 与空串（防 manifest.version 注入路径分隔或上级目录）。
 *
 * @param version manifest 中的原始版本号
 * @return 可安全用于目录名的片段
 */
fun sanitizeVersionSegment(version: String): String {
  val sanitized = version.replace(REGEX_UNSAFE_VERSION_CHARS, "-")
  return if (sanitized.isEmpty() || sanitized == "." || sanitized == "..") {
    "unknown"
  } else {
    sanitized
  }
}
