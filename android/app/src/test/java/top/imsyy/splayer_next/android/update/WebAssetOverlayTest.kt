package top.imsyy.splayer_next.android.update

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebAssetOverlayTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun buildManifest(version: String): UpdateManifest {
    val files =
      JSONObject()
        .put(
          "index.html",
          JSONObject()
            .put("path", "index.html")
            .put("sha256", "1".repeat(64))
            .put("size", 100),
        )
    val raw =
      JSONObject()
        .put("version", version)
        .put("minNativeVersion", 102)
        .put("channel", "stable")
        .put("timestamp", 123L)
        .put("files", files)
        .toString()
    return UpdateManifest.parse(raw)
  }

  private fun createBundle(bundleDir: File) {
    val publicDir = File(bundleDir, "public")
    assertTrue(publicDir.mkdirs())
    File(publicDir, "index.html").writeText("<html>new bundle</html>")
  }

  @Test
  fun applyStagedMovesStagingIntoActiveAndWritesBaseline() {
    val root = tempFolder.newFolder("web-updates")
    val version = "v1.2.1"
    val stagingPublic = WebAssetOverlay.stagingPublicDir(root, version)
    createBundle(stagingPublic.parentFile!!)
    val manifest = buildManifest(version)

    val activePublic = WebAssetOverlay.applyStaged(root, version, manifest)

    // staging 已消费，active 目录生效，index.html 内容可达
    assertFalse(stagingPublic.parentFile!!.exists())
    assertTrue(File(activePublic, "index.html").isFile)
    // current.txt 记录生效版本
    assertEquals(version, WebAssetOverlay.readActiveVersion(root))
    // applied-manifest.json 可被重新解析，rawJson 原样保留
    val applied = WebAssetOverlay.readAppliedManifest(root)
    assertEquals(version, applied?.version)
    assertEquals(manifest.rawJson, applied?.rawJson)
    // active 目录名即净化后的版本段
    assertEquals("v1.2.1", activePublic.parentFile?.name)
  }

  @Test
  fun applyStagedMissingStagingThrows() {
    val root = tempFolder.newFolder("web-updates")
    val manifest = buildManifest("v1.2.1")

    try {
      WebAssetOverlay.applyStaged(root, "v1.2.1", manifest)
      org.junit.Assert.fail("expected IllegalStateException for missing staging")
    } catch (_: IllegalStateException) {
      // 预期行为
    }
  }

  @Test
  fun applyStagedIsIdempotentForSameVersion() {
    val root = tempFolder.newFolder("web-updates")
    val version = "v1.2.1"
    // 先做一次成功应用
    val stagingPublic = WebAssetOverlay.stagingPublicDir(root, version)
    createBundle(stagingPublic.parentFile!!)
    WebAssetOverlay.applyStaged(root, version, buildManifest(version))
    // 在同一版本上再次下载并应用（staging 需重新准备）
    val stagingPublic2 = WebAssetOverlay.stagingPublicDir(root, version)
    createBundle(stagingPublic2.parentFile!!)

    val activePublic = WebAssetOverlay.applyStaged(root, version, buildManifest(version))

    assertTrue(File(activePublic, "index.html").isFile)
    assertEquals(version, WebAssetOverlay.readActiveVersion(root))
    // 旧 active 目录被覆盖而非残留多份
    assertEquals(1, root.listFiles()!!.count { it.isDirectory && it.name == version })
  }

  @Test
  fun cleanupOldVersionsKeepsCurrentAndRemovesOthersAndStaging() {
    val root = tempFolder.newFolder("web-updates")
    // 历史版本目录（带 public 子目录，符合 bundle 布局）
    val oldBundle = File(root, "v1.2.0").apply { File(this, "public").mkdirs() }
    val keepBundle = File(root, "v1.2.1").apply { File(this, "public").mkdirs() }
    val staging = File(root, "staging").apply { mkdirs() }
    // 无 public 子目录的非版本目录不应被误删
    val foreign = File(root, "user-data").apply { mkdirs() }
    File(root, "current.txt").writeText("v1.2.1")

    WebAssetOverlay.cleanupOldVersions(root, "v1.2.1")

    assertFalse(oldBundle.exists())
    assertFalse(staging.exists())
    assertTrue(keepBundle.exists())
    assertTrue(foreign.exists())
    assertTrue(File(root, "current.txt").isFile)
  }

  @Test
  fun readActiveVersionReturnsNullWhenAbsent() {
    val root = tempFolder.newFolder("web-updates")
    assertNull(WebAssetOverlay.readActiveVersion(root))
    assertNull(WebAssetOverlay.readAppliedManifest(root))
  }

  @Test
  fun stagingAndActiveShareSanitizedVersionSegment() {
    val root = tempFolder.newFolder("web-updates")
    val version = "../v1.2.1"
    createBundle(WebAssetOverlay.stagingPublicDir(root, version).parentFile!!)
    val active = WebAssetOverlay.applyStaged(root, version, buildManifest(version))
    // 穿越段被净化，active 落在根目录内
    assertEquals("..-v1.2.1", active.parentFile?.name)
    assertTrue(active.absolutePath.startsWith(root.absolutePath))
  }
}
