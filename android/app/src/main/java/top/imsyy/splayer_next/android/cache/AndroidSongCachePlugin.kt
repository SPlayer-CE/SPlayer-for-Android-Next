package top.imsyy.splayer_next.android.cache

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject

@CapacitorPlugin(name = "AndroidSongCache")
class AndroidSongCachePlugin : Plugin() {
  companion object {
    private const val MAX_CONCURRENT = 2
  }

  private val executor: ExecutorService =
    ThreadPoolExecutor(
      MAX_CONCURRENT,
      MAX_CONCURRENT,
      0L,
      TimeUnit.MILLISECONDS,
      LinkedBlockingQueue(),
    )

  private fun getCacheDir(): File = SongCacheFetcher.cacheDir(context)

  private fun cleanupOrphans() {
    val dir = getCacheDir()
    val files = dir.listFiles() ?: return
    for (f in files) {
      if (f.name.endsWith(".part")) {
        f.delete()
      }
    }
  }

  override fun load() {
    super.load()
    executor.submit {
      cleanupOrphans()
    }
  }

  @PluginMethod
  fun lookup(call: PluginCall) {
    val cacheKey = call.getString("cacheKey")
    if (cacheKey.isNullOrEmpty()) {
      call.resolve(JSObject().put("path", null))
      return
    }

    val path = SongCacheFetcher.lookup(context, cacheKey)
    if (path != null) {
      call.resolve(JSObject().put("path", path))
    } else {
      call.resolve(JSObject().put("path", null))
    }
  }

  @PluginMethod
  fun fetch(call: PluginCall) {
    val cacheKey = call.getString("cacheKey")
    val streamUrl = call.getString("streamUrl")

    if (cacheKey.isNullOrEmpty() || streamUrl.isNullOrEmpty()) {
      call.resolve(JSObject().put("path", null))
      return
    }

    executor.submit {
      val file = SongCacheFetcher.download(context, cacheKey, streamUrl)
      resolveFetch(call, file?.absolutePath)
    }
  }

  private fun resolveFetch(
    call: PluginCall,
    path: String?,
  ) {
    val ret = JSObject()
    if (path != null) {
      ret.put("path", path)
    } else {
      ret.put("path", JSONObject.NULL)
    }
    call.resolve(ret)
  }

  @PluginMethod
  fun cancel(call: PluginCall) {
    val cacheKey = call.getString("cacheKey")
    if (!cacheKey.isNullOrEmpty()) {
      SongCacheFetcher.cancel(cacheKey)
    }
    call.resolve()
  }
}
