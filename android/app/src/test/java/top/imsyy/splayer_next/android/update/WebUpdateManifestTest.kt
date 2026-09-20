package top.imsyy.splayer_next.android.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WebUpdateManifestTest {
  private fun entryJson(
    path: String,
    sha256: String,
    size: Int,
  ): JSONObject =
    JSONObject()
      .put("path", path)
      .put("sha256", sha256)
      .put("size", size)

  private fun buildManifest(
    version: String = "v1.2.1",
    minNative: Int = 102,
    files: JSONObject,
  ): String =
    JSONObject()
      .put("version", version)
      .put("minNativeVersion", minNative)
      .put("channel", "stable")
      .put("timestamp", 1789000000000L)
      .put("files", files)
      .toString()

  @Test
  fun parsesCanonicalManifestFromGenerator() {
    // 与 scripts/generate-live-update-manifest.ts 产出形态一致（files 为 path -> entry 字典）
    val files =
      JSONObject()
        .put("index.html", entryJson("index.html", "1".repeat(64), 1234))
        .put("assets/a-bc123.css", entryJson("assets/a-bc123.css", "2".repeat(64), 567))
    val raw = buildManifest(files = files)

    val manifest = UpdateManifest.parse(raw)

    assertEquals("v1.2.1", manifest.version)
    assertEquals(102, manifest.minNativeVersion)
    assertEquals("stable", manifest.channel)
    assertEquals(1789000000000L, manifest.timestamp)
    assertEquals(setOf("index.html", "assets/a-bc123.css"), manifest.files.keys)
    assertEquals("1".repeat(64), manifest.files.getValue("index.html").sha256)
    assertEquals(567L, manifest.files.getValue("assets/a-bc123.css").size)
    assertEquals(raw, manifest.rawJson)
  }

  @Test
  fun rejectsManifestWithoutVersion() {
    val raw = JSONObject().put("minNativeVersion", 102).toString()
    // version 为必填字段（org.json 对缺失必填字段抛 JSONException），由入站解析直接拦截
    try {
      UpdateManifest.parse(raw)
      org.junit.Assert.fail("expected JSONException for missing version")
    } catch (_: org.json.JSONException) {
      // 预期行为：缺失 version 直接拒绝
    }
  }

  @Test
  fun diffExcludesIdenticalAndIncludesChangedOrMissing() {
    val remoteSha = "a".repeat(64)
    // 远端按非字母序插入，验证差量保持远端清单顺序
    val remote =
      linkedMapOf(
        "assets/b.js" to UpdateFileEntry("assets/b.js", "c".repeat(64), 300),
        "index.html" to UpdateFileEntry("index.html", remoteSha, 100),
        "assets/a.css" to UpdateFileEntry("assets/a.css", "b".repeat(64), 200),
      )
    val local =
      linkedMapOf(
        // index.html 哈希一致 → 跳过；assets/a.css 哈希不同 → 更新；assets/b.js 缺失 → 更新
        "index.html" to UpdateFileEntry("index.html", remoteSha, 100),
        "assets/a.css" to UpdateFileEntry("assets/a.css", "d".repeat(64), 200),
      )

    val diff = computeDiff(remote, local)

    // 顺序与远端清单一致（b.js 在前，a.css 在后）
    assertEquals(listOf("assets/b.js", "assets/a.css"), diff.map { it.path })
  }

  @Test
  fun diffIgnoresLocalOnlyFiles() {
    val remote =
      linkedMapOf(
        "index.html" to UpdateFileEntry("index.html", "a".repeat(64), 100),
      )
    val local =
      linkedMapOf(
        // 本地有而远端已移除的文件（如旧版本残留）不进入差量
        "assets/removed.js" to UpdateFileEntry("assets/removed.js", "b".repeat(64), 50),
      )

    val diff = computeDiff(remote, local)

    assertEquals(listOf("index.html"), diff.map { it.path })
  }

  @Test
  fun diffTreatsEmptyLocalAsFullDownload() {
    val remote =
      linkedMapOf(
        "index.html" to UpdateFileEntry("index.html", "a".repeat(64), 100),
        "assets/a.css" to UpdateFileEntry("assets/a.css", "b".repeat(64), 200),
      )

    val diff = computeDiff(remote, emptyMap())

    assertEquals(setOf("index.html", "assets/a.css"), diff.map { it.path }.toSet())
    assertEquals(2, diff.size)
  }

  @Test
  fun sanitizeVersionKeepsSafeSegments() {
    assertEquals("nightly-20260920", sanitizeVersionSegment("nightly-20260920"))
    assertEquals("v1.2.1", sanitizeVersionSegment("v1.2.1"))
    // 斜杠会被替换为连字符，作为单段目录名不会再参与路径拼接（不构成穿越）
    assertEquals("a-b", sanitizeVersionSegment("a/b"))
  }

  @Test
  fun sanitizeVersionRejectsTraversalAndEmpty() {
    assertEquals("unknown", sanitizeVersionSegment(".."))
    assertEquals("unknown", sanitizeVersionSegment("."))
    assertEquals("unknown", sanitizeVersionSegment(""))
    // 非法字符整体替换为连字符，作为单段目录名不再构成路径穿越
    assertEquals("----", sanitizeVersionSegment("////"))
  }
}
