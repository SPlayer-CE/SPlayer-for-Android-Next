package top.imsyy.splayer_next.android.lyric

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.result.ActivityResult
import androidx.documentfile.provider.DocumentFile
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern
import org.json.JSONException

@CapacitorPlugin(name = "AndroidLocalLyric")
class AndroidLocalLyricPlugin : Plugin() {
  companion object {
    private val LYRIC_EXTENSIONS = arrayOf(".ttml", ".yrc", ".lrc")
    private val SIDECAR_EXTENSIONS = arrayOf(".ttml", ".yrc", ".lrc")
    private const val READ_FLAGS =
      Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    /** 歌词文件读取上限：超限按读取失败处理，避免超大/损坏文件造成内存尖峰 */
    private const val MAX_LYRIC_FILE_BYTES = 8L * 1024 * 1024

    /** 字体导入大小上限：CJK 字体可达数十 MB，超出视为异常输入 */
    private const val MAX_FONT_BYTES = 64L * 1024 * 1024

    /** 目录扫描递归深度上限：防御病态深树导致的栈溢出 */
    private const val MAX_SCAN_DEPTH = 32

    /** sidecar 元信息缓存容量（有界 LRU） */
    private const val SIDECAR_METADATA_CACHE_CAPACITY = 256
  }

  private val executor: ExecutorService = Executors.newSingleThreadExecutor()

  // sidecar 元信息匹配缓存：目录内 TTML 的解析结果按 URI+修改时间缓存，
  // 命中时跳过解析但仍需 readText 拿匹配文件内容；所有入口均在单线程 executor 上执行，无需加锁。
  // 注意部分 SAF provider 的 lastModified 恒为 0，依赖文件原地变更的场景不会感知
  private val sidecarMetadataCache =
    object : LinkedHashMap<String, AndroidLyricMetadataParser.LyricMetadata>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AndroidLyricMetadataParser.LyricMetadata>): Boolean =
        size > SIDECAR_METADATA_CACHE_CAPACITY
    }

  override fun handleOnDestroy() {
    executor.shutdownNow()
  }

  @PluginMethod
  fun importFont(call: PluginCall) {
    if (activity == null) {
      call.reject("Activity unavailable")
      return
    }
    val intent =
      Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        putExtra(
          Intent.EXTRA_MIME_TYPES,
          arrayOf(
            "font/ttf",
            "font/otf",
            "application/x-font-ttf",
            "application/x-font-truetype",
            "application/x-font-opentype",
            "application/vnd.ms-opentype",
          ),
        )
      }
    startActivityForResult(call, intent, "onImportFontResult")
  }

  @ActivityCallback
  private fun onImportFontResult(
    call: PluginCall?,
    result: ActivityResult,
  ) {
    if (call == null) return
    val data = result.data
    if (data == null) {
      val response =
        JSObject().apply {
          put("success", false)
          put("error", "NO_FILE_SELECTED")
        }
      call.resolve(response)
      return
    }

    val uris = mutableListOf<android.net.Uri>()
    if (data.clipData != null) {
      val count = data.clipData!!.itemCount
      for (i in 0 until count) {
        uris.add(data.clipData!!.getItemAt(i).uri)
      }
    } else if (data.data != null) {
      uris.add(data.data!!)
    }

    if (uris.isEmpty()) {
      val response =
        JSObject().apply {
          put("success", false)
          put("error", "NO_FILE_SELECTED")
        }
      call.resolve(response)
      return
    }

    executor.execute {
      try {
        val externalFontDir = context.getExternalFilesDir(null)?.let { File(it, "fonts") }
        if (externalFontDir != null) {
          if (!externalFontDir.exists()) {
            externalFontDir.mkdirs()
          }
          val fontData = JSArray()
          val fontNames = JSArray()
          for (uri in uris) {
            val documentFile = DocumentFile.fromSingleUri(context, uri)
            val rawName = documentFile?.name ?: "custom_font_${System.currentTimeMillis()}.ttf"
            // 文件名仅保留最后一段并排除目录跳转，防止异常 Provider 名称写出字体目录
            val fileName =
              rawName.substringAfterLast('/').takeIf { it.isNotEmpty() && it != "." && it != ".." }
                ?: "custom_font_${System.currentTimeMillis()}.ttf"
            // 文档已失效时 openInputStream 返回 null，静默跳过会把空文件/旧内容当字体上报
            val input =
              context.contentResolver.openInputStream(uri)
                ?: throw IOException("Font stream unavailable: $fileName")
            val bytes =
              input.use { stream ->
                val buffer = ByteArray(8192)
                val output = ByteArrayOutputStream()
                while (true) {
                  val read = stream.read(buffer)
                  if (read < 0) break
                  output.write(buffer, 0, read)
                  if (output.size() > MAX_FONT_BYTES) throw IOException("Font file exceeds size limit: $fileName")
                }
                output.toByteArray()
              }
            // 校验 sfnt 魔数（TrueType/OpenType/集合/woff），非字体内容不落盘
            if (!isFontBytes(bytes)) throw IOException("Unsupported font file: $fileName")
            val fontName = fileName.substringBeforeLast('.')
            val ext = fileName.substringAfterLast('.', "ttf").lowercase()
            // 先写临时名再原子重命名，失败不残留半成品字体
            val targetFile = File(externalFontDir, fileName)
            val tmpFile = File(externalFontDir, "$fileName.tmp")
            tmpFile.writeBytes(bytes)
            if (!tmpFile.renameTo(targetFile)) {
              tmpFile.delete()
              throw IOException("Font write failed: $fileName")
            }
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val entry =
              JSObject().apply {
                put("name", fontName)
                put("base64", base64)
                put("format", ext)
              }
            fontData.put(entry)
            fontNames.put(fontName)
          }
          val response =
            JSObject().apply {
              put("success", true)
              put("fontNames", fontNames)
              put("fontData", fontData)
            }
          call.resolve(response)
        } else {
          val response =
            JSObject().apply {
              put("success", false)
              put("error", "EXTERNAL_DIR_UNAVAILABLE")
            }
          call.resolve(response)
        }
      } catch (e: Exception) {
        val response =
          JSObject().apply {
            put("success", false)
            put("error", e.message)
          }
        call.resolve(response)
      }
    }
  }

  @PluginMethod
  fun listFonts(call: PluginCall) {
    executor.execute {
      try {
        val fonts = JSArray()
        collectFontNames(File("/system/fonts/"), fonts)
        context
          .getExternalFilesDir(null)
          ?.let { File(it, "fonts") }
          ?.let { collectFontNames(it, fonts) }

        val response = JSObject()
        response.put("fonts", fonts)
        call.resolve(response)
      } catch (e: Exception) {
        call.reject("LIST_FONTS_FAILED", e)
      }
    }
  }

  private fun collectFontNames(
    dir: File,
    fonts: JSArray,
  ) {
    if (!dir.exists() || !dir.isDirectory) return
    val files = dir.listFiles() ?: return
    for (file in files) {
      if (!file.isFile) continue
      val lower = file.name.lowercase()
      if (lower.endsWith(".ttf") || lower.endsWith(".otf")) {
        fonts.put(file.name.substringBeforeLast('.'))
      }
    }
  }

  @PluginMethod
  fun readImportedFonts(call: PluginCall) {
    executor.execute {
      try {
        val fontData = JSArray()
        val externalFontDir =
          context.getExternalFilesDir(null)?.let {
            File(it, "fonts")
          }
        if (externalFontDir != null && externalFontDir.exists() && externalFontDir.isDirectory) {
          val files = externalFontDir.listFiles()
          if (files != null) {
            for (file in files) {
              if (!file.isFile) continue
              val lower = file.name.lowercase()
              if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) continue
              val fontName = file.name.substringBeforeLast('.')
              val ext = file.name.substringAfterLast('.', "ttf").lowercase()
              val base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
              val entry =
                JSObject().apply {
                  put("name", fontName)
                  put("base64", base64)
                  put("format", ext)
                }
              fontData.put(entry)
            }
          }
        }
        val response = JSObject()
        response.put("fonts", fontData)
        call.resolve(response)
      } catch (e: Exception) {
        call.reject("READ_IMPORTED_FONTS_FAILED", e)
      }
    }
  }

  @PluginMethod
  fun pickLyricDirectory(call: PluginCall) {
    if (activity == null) {
      call.reject("Activity unavailable")
      return
    }

    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    intent.addFlags(READ_FLAGS)
    startActivityForResult(call, intent, "onPickLyricDirectoryResult")
  }

  @ActivityCallback
  private fun onPickLyricDirectoryResult(
    call: PluginCall?,
    result: ActivityResult,
  ) {
    if (call == null) return

    val data = result.data
    val uri = data?.data
    if (uri == null) {
      val cancelled = JSObject()
      cancelled.put("cancelled", true)
      call.resolve(cancelled)
      return
    }

    try {
      var flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
      if (flags == 0) flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
      context.contentResolver.takePersistableUriPermission(uri, flags)
    } catch (error: SecurityException) {
      call.reject("LYRIC_DIRECTORY_PERMISSION_FAILED", error)
      return
    }

    val directory = DocumentFile.fromTreeUri(context, uri)
    val response = JSObject()
    response.put("cancelled", false)
    response.put("uri", uri.toString())
    response.put("name", safeName(directory, uri))
    call.resolve(response)
  }

  @PluginMethod
  fun scanLyricDirectories(call: PluginCall) {
    val directories = call.getArray("directories")
    if (directories == null) {
      call.reject("directories is required")
      return
    }

    executor.execute {
      val accumulator = ScanAccumulator()
      for (i in 0 until directories.length()) {
        try {
          val item = directories.getJSONObject(i)
          val uriText = item.optString("uri", "")
          if (uriText.isEmpty()) {
            accumulator.addFailure("", "", "EMPTY_DIRECTORY_URI", "")
            continue
          }

          val uri = Uri.parse(uriText)
          val directoryInfo = DirectoryInfo(uriText, item.optString("name", ""))
          val directory = DocumentFile.fromTreeUri(context, uri)
          if (directory == null || !directory.exists() || !directory.canRead()) {
            accumulator.addFailure(uriText, directoryInfo.name, "DIRECTORY_UNREADABLE", uriText)
            continue
          }

          scanDirectory(directory, directoryInfo, accumulator, 0)
        } catch (error: JSONException) {
          accumulator.addFailure("", "", "INVALID_DIRECTORY_PAYLOAD", "")
        } catch (error: SecurityException) {
          accumulator.addFailure("", "", "DIRECTORY_PERMISSION_EXPIRED", "")
        }
      }
      call.resolve(accumulator.toJSObject())
    }
  }

  @PluginMethod
  fun readLyricFile(call: PluginCall) {
    val uriText = call.getString("uri", "")
    if (uriText.isNullOrEmpty()) {
      call.reject("uri is required")
      return
    }

    executor.execute {
      try {
        val uri = Uri.parse(uriText)
        // 纵深防御：仅允许读取已持久化授权的 SAF 树内文档，防止 WebView 侧任意 URI 读取
        if (!isUriWithinPersistedPermission(uri)) {
          call.reject("LYRIC_URI_UNAUTHORIZED")
          return@execute
        }
        val response = JSObject()
        response.put("content", readText(uri))
        call.resolve(response)
      } catch (error: SecurityException) {
        call.reject("LYRIC_FILE_PERMISSION_EXPIRED", error)
      } catch (error: Exception) {
        call.reject("LYRIC_FILE_READ_FAILED", error)
      }
    }
  }

  @PluginMethod
  fun findSidecarLyric(call: PluginCall) {
    val audioPath = call.getString("audioPath", "")
    val title = call.getString("title", "") ?: ""
    if (audioPath.isNullOrEmpty()) {
      call.reject("audioPath is required")
      return
    }

    executor.execute {
      try {
        val audioUri = Uri.parse(audioPath)
        if ("content".equals(audioUri.scheme, ignoreCase = true)) {
          val contentResult = findContentSidecarLyric(audioUri, title)
          if (contentResult != null) {
            call.resolve(contentResult)
            return@execute
          }
        }

        val fileResult = findFileSidecarLyric(audioPath, title)
        if (fileResult != null) {
          call.resolve(fileResult)
          return@execute
        }

        val empty = JSObject()
        empty.put("content", "")
        call.resolve(empty)
      } catch (error: Exception) {
        val empty = JSObject()
        empty.put("content", "")
        call.resolve(empty)
      }
    }
  }

  private fun findContentSidecarLyric(
    audioUri: Uri,
    title: String,
  ): JSObject? {
    val mediaInfo = queryMediaStoreAudioInfo(audioUri)
    val baseName =
      if (mediaInfo != null && mediaInfo.displayName.isNotEmpty()) {
        stripExtension(mediaInfo.displayName)
      } else {
        getAudioBaseName(audioUri)
      }
    // baseName 会进入 documentId 拼接与 File 构造，含斜杠即有逃逸目录风险
    if (!isSafeBaseName(baseName)) return null

    val direct = findSidecarFromTree(audioUri, audioUri, baseName, title)
    if (direct != null) return direct

    try {
      for (permission in context.contentResolver.persistedUriPermissions) {
        if (!permission.isReadPermission) continue
        val treeUri = permission.uri
        var result = findSidecarFromTree(audioUri, treeUri, baseName, title)
        if (result == null && mediaInfo != null) {
          result = findSidecarFromMediaStoreInfo(treeUri, mediaInfo.relativePath, baseName, title)
        }
        if (result != null) return result
      }
    } catch (ignored: Exception) {
      return null
    }

    return null
  }

  private fun queryMediaStoreAudioInfo(audioUri: Uri): MediaStoreAudioInfo? {
    val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
    try {
      context.contentResolver.query(audioUri, projection, null, null, null).use { cursor ->
        if (cursor == null || !cursor.moveToFirst()) return null
        val displayName = getCursorString(cursor, MediaStore.MediaColumns.DISPLAY_NAME)
        val relativePath = getCursorString(cursor, MediaStore.MediaColumns.RELATIVE_PATH)
        if (displayName.isEmpty() && relativePath.isEmpty()) return null
        return MediaStoreAudioInfo(displayName, relativePath)
      }
    } catch (ignored: Exception) {
      return null
    }
  }

  private fun getCursorString(
    cursor: Cursor,
    columnName: String,
  ): String {
    val index = cursor.getColumnIndex(columnName)
    if (index < 0) return ""
    val value = cursor.getString(index)
    return value?.trim() ?: ""
  }

  private fun findSidecarFromTree(
    audioUri: Uri,
    treeUri: Uri,
    baseName: String,
    title: String,
  ): JSObject? {
    try {
      if (audioUri.authority == null || audioUri.authority != treeUri.authority) {
        return null
      }

      val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
      val audioDocumentId = DocumentsContract.getDocumentId(audioUri)
      val relativePath = getRelativeDocumentPath(treeDocumentId, audioDocumentId)
      if (relativePath.isNullOrEmpty()) return null

      val parentRelativePath = getParentRelativePath(relativePath)
      val parent = resolveTreeDocument(treeUri, parentRelativePath)
      if (parent != null && parent.isDirectory && parent.canRead()) {
        val result = findSidecarInDocumentDirectory(parent, baseName, title)
        if (result != null) return result
      }

      return findSidecarByDocumentId(treeUri, treeDocumentId, parentRelativePath, baseName)
    } catch (ignored: Exception) {
      return null
    }
  }

  private fun findSidecarFromMediaStoreInfo(
    treeUri: Uri,
    mediaRelativePath: String,
    baseName: String,
    title: String,
  ): JSObject? {
    try {
      val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
      val parentRelativePath =
        AndroidLocalLyricPathMapper.mapMediaParentToTreeRelativePath(
          treeDocumentId,
          mediaRelativePath,
        ) ?: return null

      val parent = resolveTreeDocument(treeUri, parentRelativePath)
      if (parent != null && parent.isDirectory && parent.canRead()) {
        val result = findSidecarInDocumentDirectory(parent, baseName, title)
        if (result != null) return result
      }

      return findSidecarByDocumentId(treeUri, treeDocumentId, parentRelativePath, baseName)
    } catch (ignored: Exception) {
      return null
    }
  }

  private fun findSidecarInDocumentDirectory(
    parent: DocumentFile,
    baseName: String,
    title: String,
  ): JSObject? {
    for (ext in SIDECAR_EXTENSIONS) {
      val lyricFile = findChild(parent, baseName + ext)
      if (lyricFile == null || !lyricFile.isFile || !lyricFile.canRead()) continue
      try {
        return buildSidecarResponse(readText(lyricFile.uri), ext)
      } catch (ignored: Exception) {
        // 继续尝试低优先级格式
      }
    }

    // 元信息扫描匹配
    if (title.isNotEmpty()) {
      for (child in parent.listFiles()) {
        val name = child.name ?: continue
        if (!name.endsWith(".ttml", true)) continue
        if (name.equals("$baseName.ttml", true)) continue // 已经精确匹配过
        if (!child.isFile || !child.canRead()) continue

        // 单个文件读取失败不中断其余候选
        try {
          val cacheKey = "${child.uri}@${child.lastModified()}"
          var metadata = sidecarMetadataCache[cacheKey]
          if (metadata == null) {
            val content = readText(child.uri)
            metadata = AndroidLyricMetadataParser.extractTtmlMetadata(content)
            sidecarMetadataCache[cacheKey] = metadata
            if (metadata.musicName?.equals(title, true) == true) {
              return buildSidecarResponse(content, ".ttml")
            }
          } else if (metadata.musicName?.equals(title, true) == true) {
            return buildSidecarResponse(readText(child.uri), ".ttml")
          }
        } catch (ignored: Exception) {
        }
      }
    }

    return null
  }

  private fun findSidecarByDocumentId(
    treeUri: Uri,
    treeDocumentId: String,
    parentRelativePath: String,
    baseName: String,
  ): JSObject? {
    for (ext in SIDECAR_EXTENSIONS) {
      val siblingRelativePath =
        if (parentRelativePath.isEmpty()) {
          baseName + ext
        } else {
          parentRelativePath + "/" + baseName + ext
        }
      val siblingDocumentId = buildDocumentId(treeDocumentId, siblingRelativePath)
      try {
        val siblingUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, siblingDocumentId)
        val lyricFile = DocumentFile.fromSingleUri(context, siblingUri)
        if (lyricFile == null || !lyricFile.exists() || !lyricFile.canRead()) continue
        return buildSidecarResponse(readText(siblingUri), ext)
      } catch (ignored: Exception) {
        // 继续尝试其他格式
      }
    }
    return null
  }

  private fun findFileSidecarLyric(
    audioPath: String,
    title: String,
  ): JSObject? {
    val audioFile = resolveAudioFile(audioPath) ?: return null

    val parentDir = audioFile.parentFile
    if (parentDir == null || !parentDir.exists() || !parentDir.canRead()) return null

    val baseName = stripExtension(audioFile.name)
    // baseName 会进入 File 构造，含斜杠即有逃逸目录风险
    if (!isSafeBaseName(baseName)) return null
    for (ext in SIDECAR_EXTENSIONS) {
      val lyricFile = File(parentDir, baseName + ext)
      if (!lyricFile.exists() || !lyricFile.canRead()) continue
      try {
        return buildSidecarResponse(readFileText(lyricFile), ext)
      } catch (ignored: Exception) {
        // 继续尝试低优先级格式
      }
    }

    // 元信息扫描匹配
    if (title.isNotEmpty()) {
      val files = parentDir.listFiles()
      if (files != null) {
        for (file in files) {
          val name = file.name
          if (!name.endsWith(".ttml", true)) continue
          if (name.equals("$baseName.ttml", true)) continue
          if (!file.isFile || !file.canRead()) continue

          // 单个文件读取失败不中断其余候选
          try {
            val cacheKey = "${file.absolutePath}@${file.lastModified()}"
            var metadata = sidecarMetadataCache[cacheKey]
            if (metadata == null) {
              val content = readFileText(file)
              metadata = AndroidLyricMetadataParser.extractTtmlMetadata(content)
              sidecarMetadataCache[cacheKey] = metadata
              if (metadata.musicName?.equals(title, true) == true) {
                return buildSidecarResponse(content, ".ttml")
              }
            } else if (metadata.musicName?.equals(title, true) == true) {
              return buildSidecarResponse(readFileText(file), ".ttml")
            }
          } catch (ignored: Exception) {
          }
        }
      }
    }

    return null
  }

  private fun resolveAudioFile(audioPath: String): File? {
    try {
      val uri = Uri.parse(audioPath)
      val scheme = uri.scheme
      if ("file".equals(scheme, ignoreCase = true)) {
        val path = uri.path
        return if (path.isNullOrEmpty() || path.contains("../")) null else File(path)
      }
      if (!scheme.isNullOrEmpty()) return null
    } catch (ignored: Exception) {
      // 使用原始路径兜底
    }
    // 纵深防御：拒绝包含目录跳转的路径，防止以 sidecar 名义读取任意目录
    return if (audioPath.contains("../")) null else File(audioPath)
  }

  private fun buildSidecarResponse(
    content: String,
    ext: String,
  ): JSObject {
    val response = JSObject()
    response.put("content", content)
    response.put("format", ext.substring(1))
    return response
  }

  private fun getAudioBaseName(audioUri: Uri): String {
    try {
      val audioFile = DocumentFile.fromSingleUri(context, audioUri)
      val name = audioFile?.name
      if (!name.isNullOrEmpty()) return stripExtension(name)
    } catch (ignored: Exception) {
      // 继续使用 URI 路径兜底
    }

    try {
      val documentId = DocumentsContract.getDocumentId(audioUri)
      val slashIdx = documentId.lastIndexOf('/')
      val name = if (slashIdx >= 0) documentId.substring(slashIdx + 1) else documentId
      if (name.isNotEmpty()) return stripExtension(name)
    } catch (ignored: Exception) {
      // 继续使用 URI 路径兜底
    }

    val path = audioUri.path
    if (path.isNullOrEmpty()) return ""
    val slashIdx = path.lastIndexOf('/')
    val name = if (slashIdx >= 0) path.substring(slashIdx + 1) else path
    return stripExtension(name)
  }

  private fun resolveTreeDocument(
    treeUri: Uri,
    relativePath: String,
  ): DocumentFile? {
    var current = DocumentFile.fromTreeUri(context, treeUri) ?: return null
    if (relativePath.isEmpty()) return current

    val parts = relativePath.split("/")
    for (part in parts) {
      if (part.isEmpty()) continue
      current = findChild(current, part) ?: return null
      if (!current.isDirectory) return null
    }
    return current
  }

  private fun findChild(
    parent: DocumentFile,
    name: String,
  ): DocumentFile? {
    try {
      for (child in parent.listFiles()) {
        if (name == child.name) return child
      }
    } catch (ignored: Exception) {
      return null
    }
    return null
  }

  private fun getRelativeDocumentPath(
    treeDocumentId: String,
    documentId: String,
  ): String? {
    val relative: String =
      when {
        documentId == treeDocumentId -> return ""
        documentId.startsWith("$treeDocumentId/") -> documentId.substring("$treeDocumentId/".length)
        treeDocumentId.endsWith(":") && documentId.startsWith(treeDocumentId) ->
          documentId.substring(treeDocumentId.length).removePrefix("/")
        else -> return null
      }
    // documentId 拼接 "../" 可在 provider 侧逃出授权树，视为越权
    if (relative.split('/').any { it == ".." }) return null
    return relative
  }

  private fun getParentRelativePath(relativePath: String): String {
    val slashIdx = relativePath.lastIndexOf('/')
    return if (slashIdx > 0) relativePath.substring(0, slashIdx) else ""
  }

  private fun buildDocumentId(
    treeDocumentId: String,
    relativePath: String,
  ): String {
    val cleaned = if (relativePath.startsWith("/")) relativePath.substring(1) else relativePath
    if (cleaned.isEmpty()) return treeDocumentId
    return if (treeDocumentId.endsWith(":")) treeDocumentId + cleaned else "$treeDocumentId/$cleaned"
  }

  private fun stripExtension(name: String): String {
    val dotIdx = name.lastIndexOf('.')
    return if (dotIdx > 0) name.substring(0, dotIdx) else name
  }

  private fun scanDirectory(
    directory: DocumentFile,
    directoryInfo: DirectoryInfo,
    accumulator: ScanAccumulator,
    depth: Int,
  ) {
    // 递归深度上限：病态深树的 StackOverflowError 是 Error，无法被调用点的 catch Exception 捕获
    if (depth > MAX_SCAN_DEPTH) {
      accumulator.addFailure(
        directory.uri.toString(),
        directory.name ?: "",
        "DIRECTORY_TOO_DEEP",
        directoryInfo.uri,
      )
      return
    }

    val children: Array<DocumentFile> =
      try {
        directory.listFiles()
      } catch (error: SecurityException) {
        accumulator.addFailure(
          directory.uri.toString(),
          directory.name ?: "",
          "DIRECTORY_PERMISSION_EXPIRED",
          directoryInfo.uri,
        )
        return
      }

    for (child in children) {
      if (child.isDirectory) {
        scanDirectory(child, directoryInfo, accumulator, depth + 1)
        continue
      }

      if (!child.isFile) continue
      val name = child.name
      if (name == null || !isLyricFile(name)) continue

      accumulator.totalFiles++
      val fileUri = child.uri.toString()
      val format = getFormatFromName(name)
      val lastModified = Math.max(child.lastModified(), 0L)

      try {
        var metadata = AndroidLyricMetadataParser.LyricMetadata()
        if ("ttml" == format) {
          val content = readText(child.uri)
          metadata = AndroidLyricMetadataParser.extractTtmlMetadata(content)
        } else if ("lrc" == format) {
          val content = readText(child.uri)
          metadata = AndroidLyricMetadataParser.extractLrcMetadata(content)
        }

        val entry =
          AndroidLyricIndexEntry(
            fileUri,
            name,
            lastModified,
            directoryInfo.uri,
            format,
            metadata,
          )
        accumulator.addEntry(entry)

        val ids = LinkedHashSet<String>()
        if ("ttml" == format && !metadata.ncmMusicId.isNullOrEmpty()) {
          ids.add(metadata.ncmMusicId!!)
        }
        val filenameId = extractLyricFilenameId(name)
        if (!filenameId.isNullOrEmpty()) ids.add(filenameId)

        if (ids.isNotEmpty()) {
          accumulator.matchedFiles++
          for (id in ids) {
            accumulator.putIndex(id, entry)
          }
        }
      } catch (error: SecurityException) {
        accumulator.failedFiles++
        accumulator.addFailure(fileUri, name, "FILE_PERMISSION_EXPIRED", directoryInfo.uri)
      } catch (error: Exception) {
        accumulator.failedFiles++
        accumulator.addFailure(fileUri, name, "FILE_READ_FAILED", directoryInfo.uri)
      }
    }
  }

  private fun isLyricFile(name: String): Boolean {
    val lower = name.lowercase()
    for (ext in LYRIC_EXTENSIONS) {
      if (lower.endsWith(ext)) return true
    }
    return false
  }

  private fun getFormatFromName(name: String): String {
    val lower = name.lowercase()
    if (lower.endsWith(".ttml")) return "ttml"
    if (lower.endsWith(".yrc")) return "yrc"
    if (lower.endsWith(".lrc")) return "lrc"
    return "lrc"
  }

  @Throws(IOException::class)
  private fun readText(uri: Uri): String {
    val input =
      context.contentResolver.openInputStream(uri) ?: throw IOException("Input stream unavailable")
    return input.use { stream ->
      val buffer = ByteArray(8192)
      val output = ByteArrayOutputStream()
      while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        if (output.size() > MAX_LYRIC_FILE_BYTES) throw IOException("Lyric file exceeds size limit")
      }
      output.toString(StandardCharsets.UTF_8.name())
    }
  }

  @Throws(IOException::class)
  private fun readFileText(file: File): String {
    if (file.length() > MAX_LYRIC_FILE_BYTES) throw IOException("Lyric file exceeds size limit")
    return file.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
  }

  /** 校验 URI 位于已持久化授权的 SAF 树内，防止 WebView 侧任意 URI 读取 */
  private fun isUriWithinPersistedPermission(uri: Uri): Boolean {
    if (!"content".equals(uri.scheme, ignoreCase = true)) return false
    for (permission in context.contentResolver.persistedUriPermissions) {
      if (!permission.isReadPermission) continue
      val treeUri = permission.uri
      if (treeUri.authority != uri.authority) continue
      if (treeUri == uri) return true
      try {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val documentId = DocumentsContract.getDocumentId(uri)
        if (getRelativeDocumentPath(treeDocumentId, documentId) != null) return true
      } catch (ignored: Exception) {
        // 非文档 URI 形态，继续检查下一项授权
      }
    }
    return false
  }

  /** baseName 会进入 documentId 拼接与 File 构造，禁止包含路径分隔符 */
  private fun isSafeBaseName(name: String): Boolean = name.isNotEmpty() && !name.contains('/')

  /** 校验 sfnt 容器魔数：TrueType 0x00010000、OTTO、ttcf、wOFF */
  private fun isFontBytes(bytes: ByteArray): Boolean {
    if (bytes.size < 4) return false
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    val b2 = bytes[2].toInt() and 0xFF
    val b3 = bytes[3].toInt() and 0xFF
    return (b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) ||
      (b0 == 'O'.code && b1 == 'T'.code && b2 == 'T'.code && b3 == 'O'.code) ||
      (b0 == 't'.code && b1 == 't'.code && b2 == 'c'.code && b3 == 'f'.code) ||
      (b0 == 'w'.code && b1 == 'O'.code && b2 == 'F'.code && b3 == 'F'.code)
  }

  private fun safeName(
    file: DocumentFile?,
    uri: Uri,
  ): String {
    val name = file?.name
    if (!name.isNullOrEmpty()) return name
    val lastPath = uri.lastPathSegment
    return if (lastPath.isNullOrEmpty()) uri.toString() else lastPath
  }

  private class DirectoryInfo(
    val uri: String,
    val name: String,
  )

  private class MediaStoreAudioInfo(
    val displayName: String,
    val relativePath: String,
  )

  private class AndroidLyricIndexEntry(
    val uri: String,
    val name: String,
    val lastModified: Long,
    val directoryUri: String,
    val format: String,
    val metadata: AndroidLyricMetadataParser.LyricMetadata,
  ) {
    fun toJSObject(): JSObject {
      val jsObject = JSObject()
      jsObject.put("uri", uri)
      jsObject.put("name", name)
      jsObject.put("lastModified", lastModified)
      jsObject.put("directoryUri", directoryUri)
      jsObject.put("format", format)
      jsObject.put("metadata", metadata.toJSObject())
      return jsObject
    }
  }

  private class ScanAccumulator {
    var totalFiles = 0
    var matchedFiles = 0
    var duplicateIds = 0
    var failedFiles = 0
    val indexMap = JSObject()
    val entries = JSArray()
    val failures = JSArray()

    fun addEntry(entry: AndroidLyricIndexEntry) {
      entries.put(entry.toJSObject())
    }

    fun putIndex(
      id: String,
      entry: AndroidLyricIndexEntry,
    ) {
      val existing = indexMap.optJSONObject(id)
      if (existing != null) {
        duplicateIds++
        val oldLastModified = existing.optLong("lastModified", 0L)
        val oldFormat = existing.optString("format", "lrc")
        if (!shouldReplace(oldLastModified, entry.lastModified, oldFormat, entry.format)) return
      }
      indexMap.put(id, entry.toJSObject())
    }

    fun addFailure(
      uri: String,
      name: String,
      reason: String,
      directoryUri: String,
    ) {
      val failure = JSObject()
      failure.put("uri", uri)
      failure.put("name", name)
      failure.put("reason", reason)
      failure.put("directoryUri", directoryUri)
      failures.put(failure)
    }

    fun toJSObject(): JSObject {
      val response = JSObject()
      response.put("indexMap", indexMap)
      response.put("entries", entries)
      response.put("totalFiles", totalFiles)
      response.put("matchedFiles", matchedFiles)
      response.put("duplicateIds", duplicateIds)
      response.put("failedFiles", failedFiles)
      response.put("failures", failures)
      return response
    }

    private fun shouldReplace(
      oldLastModified: Long,
      newLastModified: Long,
      oldFormat: String,
      newFormat: String,
    ): Boolean {
      val oldPriority = formatPriority(oldFormat)
      val newPriority = formatPriority(newFormat)
      if (newPriority != oldPriority) return newPriority > oldPriority
      // 同格式按时间戳比较
      if (oldLastModified > 0 && newLastModified > 0) {
        return newLastModified > oldLastModified
      }
      if (oldLastModified <= 0 && newLastModified > 0) return true
      if (oldLastModified > 0) return false
      return true
    }

    private fun formatPriority(format: String): Int {
      if ("ttml" == format) return 3
      if ("yrc" == format) return 2
      return 1
    }
  }
}

private val FILENAME_ID_PATTERN = Pattern.compile("(\\d+)")

/**
 * 从歌词文件名提取数字 ID，匹配 "123456" 或 "SongName.123456" 格式
 */
internal fun extractLyricFilenameId(fileName: String): String? {
  var baseName = fileName
  val dotIdx = baseName.lastIndexOf('.')
  if (dotIdx > 0) baseName = baseName.substring(0, dotIdx)

  val lastPart = baseName.substringAfterLast('.')
  if (FILENAME_ID_PATTERN.matcher(lastPart).matches() && lastPart.length >= 2) {
    return lastPart
  }

  if (FILENAME_ID_PATTERN.matcher(baseName).matches() && baseName.length >= 2) {
    return baseName
  }

  return null
}
