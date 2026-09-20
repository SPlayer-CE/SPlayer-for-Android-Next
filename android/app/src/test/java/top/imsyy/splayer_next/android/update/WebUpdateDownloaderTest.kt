package top.imsyy.splayer_next.android.update

import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebUpdateDownloaderTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  /** 本机拒绝端口：连接立即失败（Connection refused），不依赖 DNS 与外网 */
  private val unreachableBase = "http://127.0.0.1:1"

  private fun buildManifest(
    filesJson: JSONObject,
    version: String = "v1.2.1",
  ): UpdateManifest {
    val raw =
      JSONObject()
        .put("version", version)
        .put("minNativeVersion", 102)
        .put("channel", "stable")
        .put("timestamp", 123L)
        .put("files", filesJson)
        .toString()
    return UpdateManifest.parse(raw)
  }

  private fun fileEntry(
    sha256: String,
    size: Int,
  ): JSONObject =
    JSONObject()
      .put("sha256", sha256)
      .put("size", size)

  @Test
  fun sha256MatchesKnownVector() {
    val file = tempFolder.newFile("hello.txt")
    file.writeText("hello")
    // hello 的 SHA-256 已知摘要，防实现回归
    assertEquals(
      "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
      WebUpdateDownloader.sha256Of(file),
    )
  }

  @Test
  fun rejectsEmptyDiffBeforeAnyDownload() {
    val files =
      JSONObject()
        .put("index.html", fileEntry("1".repeat(64), 100))
    val manifest = buildManifest(files)
    val staging = tempFolder.newFolder("staging")

    // 本地哈希与远端完全一致 → 差量为空 → 拒绝（应由上游在进入下载前拦截）
    assertThrows(IllegalStateException::class.java) {
      WebUpdateDownloader().downloadDiffToStaging(
        manifest = manifest,
        localHashes = mapOf("index.html" to "1".repeat(64)),
        baseUrl = unreachableBase,
        stagingPublicRoot = staging,
        onProgress = { _, _, _, _ -> },
      )
    }
    assertFalse(File(staging, "index.html").exists())
  }

  @Test
  fun failedDownloadCleansStaging() {
    val files =
      JSONObject()
        .put("assets/x.js", fileEntry("2".repeat(64), 200))
    val manifest = buildManifest(files)
    val staging = WebAssetOverlay.stagingPublicDir(tempFolder.root, "v1.2.1")

    // 无本地基线 → 全量差量；连接被拒应抛 IOException 且 staging 被清理（半包必弃）
    assertThrows(IOException::class.java) {
      WebUpdateDownloader(connectTimeoutMs = 1_000, readTimeoutMs = 1_000, maxRetries = 0)
        .downloadDiffToStaging(
          manifest = manifest,
          localHashes = emptyMap(),
          baseUrl = unreachableBase,
          stagingPublicRoot = staging,
          onProgress = { _, _, _, _ -> },
        )
    }
    assertFalse(staging.exists())
  }

  @Test
  fun rejectsPathTraversalBeforeAnyDownload() {
    // 清单内相对路径带 .. 试图逃逸 staging 根：在发起网络请求前即被拒绝
    val files =
      JSONObject()
        .put("../evil.txt", fileEntry("3".repeat(64), 10))
    val manifest = buildManifest(files)
    val staging = WebAssetOverlay.stagingPublicDir(tempFolder.root, "v1.2.1")

    assertThrows(IllegalStateException::class.java) {
      WebUpdateDownloader().downloadDiffToStaging(
        manifest = manifest,
        localHashes = emptyMap(),
        baseUrl = unreachableBase,
        stagingPublicRoot = staging,
        onProgress = { _, _, _, _ -> },
      )
    }
  }
}
