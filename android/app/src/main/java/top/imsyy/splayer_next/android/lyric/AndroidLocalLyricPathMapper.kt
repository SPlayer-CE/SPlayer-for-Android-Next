package top.imsyy.splayer_next.android.lyric

internal object AndroidLocalLyricPathMapper {
  fun mapMediaParentToTreeRelativePath(
    treeDocumentId: String,
    mediaRelativePath: String,
  ): String? {
    val treeRoot = normalizePath(getDocumentPath(treeDocumentId))
    val mediaParent = normalizePath(mediaRelativePath)
    if (mediaParent.isNullOrEmpty()) return null
    if (treeRoot.isNullOrEmpty()) return mediaParent
    if (mediaParent == treeRoot) return ""
    val prefix = "$treeRoot/"
    if (mediaParent.startsWith(prefix)) return mediaParent.substring(prefix.length)
    return null
  }

  private fun getDocumentPath(documentId: String): String {
    val colonIndex = documentId.indexOf(':')
    if (colonIndex < 0) return documentId
    return documentId.substring(colonIndex + 1)
  }

  private fun normalizePath(path: String?): String? {
    if (path == null) return null
    var normalized = path.trim().replace('\\', '/')
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1)
    }
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length - 1)
    }
    return normalized
  }
}
