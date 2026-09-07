package top.imsyy.splayer_next.android.cache

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * ExoPlayer SimpleCache 单例提供者。
 */
object AudioCacheProvider {
  private const val TAG = "AudioCacheProvider"
  private const val PREFETCH_TAG = "AudioCachePrefetch"

  /**
   * 音频文件名匹配：路径末段是 “xxxxx.{ext}” 形式时提取作为 cacheKey 主体。<br>
   * 不仅适用 NCM；任何 CDN 返回“动态路径 + 音频后缀”均可命中。使用 (?i) 允许大写后缀。
   */
  private val AUDIO_FILE_PATTERN =
    Pattern.compile("/([A-Za-z0-9_-]+\\.(?i:mp3|flac|m4a|ogg|wav|aac|opus))")

  /**
   * 低磁盘阈值：设备剩余不足 1GB 时禁止音频缓存<strong>写入</strong>（prefetch + 播放期 sink）。<br>
   * 读取不受影响：已缓存的歌仍可本地命中播放。
   */
  const val LOW_DISK_THRESHOLD_BYTES = 1024L * 1024L * 1024L

  @Volatile
  private var simpleCache: SimpleCache? = null
  private val lock = Any()

  @Volatile
  private var diagnosticListener: DiagnosticListener? = null

  fun interface DiagnosticListener {
    fun onDiagnostic(
      tag: String,
      message: String,
    )
  }

  @JvmStatic
  fun setDiagnosticListener(listener: DiagnosticListener?) {
    diagnosticListener = listener
  }

  private fun emitDiagnosticLog(
    tag: String,
    message: String,
  ) {
    Log.d(TAG, "$tag $message")
    diagnosticListener?.onDiagnostic(tag, message)
  }

  /**
   * 设备剩余空间是否低于 [LOW_DISK_THRESHOLD_BYTES]。<br>
   * StatFs 读取失败 (-1) 时返 false（保守：读不出不随意禁写）。
   */
  @JvmStatic
  fun isLowDiskSpace(appContext: Context): Boolean {
    val free = CacheStorage.getInstance(appContext).getDeviceFreeBytes()
    if (free < 0) return false
    return free < LOW_DISK_THRESHOLD_BYTES
  }

  /** 懒初始化 SimpleCache 单例（同进程多次构造同目录会抛 IllegalStateException）。 */
  @JvmStatic
  fun getOrCreate(appContext: Context): SimpleCache {
    var cache = simpleCache
    if (cache != null) return cache
    synchronized(lock) {
      cache = simpleCache
      if (cache != null) return cache!!
      val storage = CacheStorage.getInstance(appContext)
      cache =
        SimpleCache(
          storage.getAudioCacheDir(),
          NoOpCacheEvictor(),
          StandaloneDatabaseProvider(appContext),
        )
      simpleCache = cache
      return cache!!
    }
  }

  /**
   * 偷看已初始化的 SimpleCache 实例；未初始化返 null，不触发初始化。<br>
   * 供 [CacheStorage.getTypeBytes] 在 SimpleCache 已就绪时走 O(1) 读取，否则回退扫盘。
   */
  @JvmStatic
  fun peekSimpleCache(): SimpleCache? = simpleCache

  /** 释放（应用退出 / 测试场景）；正常运行不调用，单例随进程生命周期。 */
  @JvmStatic
  fun release() {
    synchronized(lock) {
      simpleCache?.release()
      simpleCache = null
    }
  }

  /**
   * 构造带缓存的 DataSource.Factory。
   */
  @JvmStatic
  fun buildCachedDataSourceFactory(appContext: Context): DataSource.Factory {
    val cache = getOrCreate(appContext)

    val httpFactory =
      DefaultHttpDataSource
        .Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)

    val httpCacheFactory = buildHttpCacheFactory(appContext, httpFactory, cache, false)
    val localFactory = DefaultDataSource.Factory(appContext)

    return DataSource.Factory { SchemeRoutingDataSource(httpCacheFactory, localFactory) }
  }

  private fun buildHttpCacheFactory(
    appContext: Context,
    httpFactory: DataSource.Factory,
    cache: SimpleCache,
    blockOnCache: Boolean,
  ): DataSource.Factory {
    val cacheSinkFactory =
      CacheDataSink
        .Factory()
        .setCache(cache)
        .setFragmentSize(Long.MAX_VALUE)

    return DataSource.Factory {
      val cf =
        CacheDataSource
          .Factory()
          .setCache(cache)
          .setUpstreamDataSourceFactory(httpFactory)
          .setCacheKeyFactory { spec -> resolveCacheKey(spec.uri) }
          .setFlags(
            if (blockOnCache) {
              CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or CacheDataSource.FLAG_BLOCK_ON_CACHE
            } else {
              CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
            },
          )
      if (!isLowDiskSpace(appContext)) {
        cf.setCacheWriteDataSinkFactory(cacheSinkFactory)
      } else {
        Log.w(TAG, "low disk: audio cache write disabled (read-only)")
      }
      cf.createDataSource()
    }
  }

  @JvmStatic
  internal fun buildHttpCacheFactoryForPrefetch(appContext: Context): DataSource.Factory {
    val cache = getOrCreate(appContext)
    val httpFactory =
      DefaultHttpDataSource
        .Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)
    return buildHttpCacheFactory(appContext, httpFactory, cache, true)
  }

  private class SchemeRoutingDataSource(
    private val httpCacheFactory: DataSource.Factory,
    private val localFactory: DataSource.Factory,
  ) : DataSource {
    private val pendingListeners = ArrayList<TransferListener>()
    private val routeLock = Any()

    @Volatile
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
      var ds: DataSource?
      synchronized(routeLock) {
        ds = current
        if (ds == null) {
          pendingListeners.add(transferListener)
        }
      }
      ds?.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
      var previous: DataSource?
      synchronized(routeLock) {
        previous = current
        current = null
      }
      try {
        previous?.close()
      } catch (ignored: IOException) {
      }

      val scheme = dataSpec.uri.scheme
      val isHttp = "http" == scheme || "https" == scheme
      val ds = if (isHttp) httpCacheFactory.createDataSource() else localFactory.createDataSource()

      synchronized(routeLock) {
        for (listener in pendingListeners) {
          ds.addTransferListener(listener)
        }
        pendingListeners.clear()
        current = ds
      }

      val startedAt = SystemClock.elapsedRealtime()
      try {
        return ds.open(dataSpec)
      } finally {
        val costMs = SystemClock.elapsedRealtime() - startedAt
        if (isHttp && costMs > 1000L) {
          emitDiagnosticLog("DIAG-AudioOpen", "slow open ${costMs}ms key=${resolveCacheKey(dataSpec.uri)}")
        }
      }
    }

    @Throws(IOException::class)
    override fun read(
      buffer: ByteArray,
      offset: Int,
      length: Int,
    ): Int {
      val ds = current ?: return C.RESULT_END_OF_INPUT
      return ds.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = current?.uri

    @Throws(IOException::class)
    override fun close() {
      var ds: DataSource?
      synchronized(routeLock) {
        ds = current
        current = null
      }
      ds?.close()
    }
  }

  private val EPHEMERAL_QUERY_KEYS =
    setOf(
      "expire",
      "expires",
      "signature",
      "sign",
      "token",
      "auth_key",
      "_t",
      "timestamp",
      "ts",
    )

  /**
   * 解析音频缓存键。
   * 为何不能只用文件名：不同 host/路径下可能出现同名文件（如 /abc.mp3），
   * 旧实现 "aud:"+文件名会把 https://A/abc.mp3 与 https://B/abc.mp3 映射到同一键，
   * 导致跨歌串缓存；配合 FLAG_IGNORE_CACHE_ON_ERROR 会掩盖错误、误播他人缓存。
   * 新策略：AUDIO_FILE_PATTERN 命中时优先 aud:{songId}:{filename}（songId>0 时），
   * 否则 aud:{host}/{filename}；host 为空时回退到 host+path+非 EPHEMERAL query 的哈希逻辑。
   * 全部 key 统一 lowercase。
   * 迁移策略：旧 aud:xxx 自然失效、不做迁移或映射，未命中即重新拉取写入新键。
   */
  @JvmStatic
  fun resolveCacheKey(uri: Uri): String = resolveCacheKey(uri, 0L)

  @JvmStatic
  fun resolveCacheKey(
    uri: Uri,
    songId: Long,
  ): String {
    val scheme = uri.scheme
    if (scheme != null && scheme.startsWith("file")) {
      return "local:" + uri.path
    }
    val path = uri.path ?: ""
    val m = AUDIO_FILE_PATTERN.matcher(path)
    if (m.find()) {
      val filename = m.group(1)!!.lowercase(Locale.ROOT)
      // 优先用 songId 区分同名文件：不同歌曲即使同文件名也不共享缓存
      if (songId > 0) {
        return "aud:" + songId + ":" + filename
      }
      val host = uri.host?.lowercase(Locale.ROOT) ?: ""
      if (host.isNotEmpty()) {
        return "aud:" + host + "/" + filename
      }
      // host 为空时回退到下方 sb(host+path+非 ephemeral query) 逻辑，避免裸文件名碰撞
    }

    val sb = java.lang.StringBuilder()
    uri.host?.let { sb.append(it.lowercase(Locale.ROOT)) }
    sb.append(path.lowercase(Locale.ROOT))

    val qnames =
      try {
        uri.queryParameterNames
      } catch (e: UnsupportedOperationException) {
        emptySet()
      }

    if (qnames.isNotEmpty()) {
      val sortedKeys = qnames.toMutableList()
      sortedKeys.sort()
      sb.append('?')
      var first = true
      for (qk in sortedKeys) {
        if (EPHEMERAL_QUERY_KEYS.contains(qk.lowercase(Locale.ROOT))) continue
        val qv = uri.getQueryParameter(qk) ?: ""
        if (!first) sb.append('&')
        // query key/value 统一 lowercase，保证同资源不同大小写不产生多份缓存
        sb.append(qk.lowercase(Locale.ROOT)).append('=').append(qv.lowercase(Locale.ROOT))
        first = false
      }
    }
    return CacheStorage.keyFromUrl(sb.toString().lowercase(Locale.ROOT))
  }

  // ========== prefetch ==========

  const val DEFAULT_PREFETCH_BYTES = 2L * 1024L * 1024L

  @Volatile private var prefetchExecutor: ExecutorService? = null

  @Volatile private var fullDownloadExecutor: ExecutorService? = null

  @Volatile private var currentFullCancelFlag: AtomicBoolean? = null
  private val inFlight = HashSet<String>()
  private val cacheKeyWriterLocks = ConcurrentHashMap<String, Any>()

  @Volatile private var currentCancelFlag: AtomicBoolean? = null

  @Volatile private var prefetchGeneration = 0L

  private fun getExecutor(): ExecutorService =
    prefetchExecutor ?: synchronized(this) {
      prefetchExecutor ?: Executors
        .newSingleThreadExecutor { r ->
          val t = Thread(r, "audio-cache-prefetch")
          t.isDaemon = true
          t.priority = Thread.NORM_PRIORITY - 1
          t
        }.also { prefetchExecutor = it }
    }

  private fun getFullDownloadExecutor(): ExecutorService =
    fullDownloadExecutor ?: synchronized(this) {
      fullDownloadExecutor ?: Executors
        .newSingleThreadExecutor { r ->
          val t = Thread(r, "audio-cache-full-download")
          t.isDaemon = true
          t.priority = Thread.NORM_PRIORITY - 2
          t
        }.also { fullDownloadExecutor = it }
    }

  @JvmStatic
  fun prefetchUrl(
    appContext: Context,
    url: String?,
  ) {
    prefetchUrlWithLength(appContext, url, DEFAULT_PREFETCH_BYTES, true)
  }

  @JvmStatic
  fun prefetchUrlFull(
    appContext: Context,
    url: String?,
  ) {
    if (url.isNullOrEmpty()) return
    val uri = Uri.parse(url)
    val scheme = uri.scheme
    if (scheme == null || (scheme != "http" && scheme != "https")) return
    val cacheKey = resolveCacheKey(uri)
    if (AudioPrefetchTtlIndex.getInstance(appContext).isPromoted(cacheKey)) return
    prefetchUrlWithLength(appContext, url, Long.MAX_VALUE, false)
  }

  private fun prefetchUrlWithLength(
    appContext: Context,
    url: String?,
    length: Long,
    cancelPrev: Boolean,
  ) {
    if (url.isNullOrEmpty()) return
    if (isLowDiskSpace(appContext)) {
      Log.d(PREFETCH_TAG, "low disk: prefetch skipped for $url")
      return
    }
    val uri = Uri.parse(url)
    val scheme = uri.scheme
    if (scheme == null || (scheme != "http" && scheme != "https")) return

    val cacheKey = resolveCacheKey(uri)
    val inFlightKey = "$cacheKey|$length"
    synchronized(inFlight) {
      if (inFlight.contains(inFlightKey)) {
        return
      }
      inFlight.add(inFlightKey)
    }

    val cache = getOrCreate(appContext)
    val ttlIndex = AudioPrefetchTtlIndex.getInstance(appContext)
    val probeLength = if (length == Long.MAX_VALUE) DEFAULT_PREFETCH_BYTES else length
    val cachedBytes = cache.getCachedBytes(cacheKey, 0, probeLength)
    if (length != Long.MAX_VALUE && cachedBytes >= length) {
      ttlIndex.markAccess(cacheKey)
      synchronized(inFlight) {
        inFlight.remove(inFlightKey)
      }
      return
    }

    val cancelFlag = AtomicBoolean(false)
    val isFull = length == Long.MAX_VALUE
    val generation = prefetchGeneration
    if (cancelPrev) {
      currentCancelFlag?.set(true)
      currentCancelFlag = cancelFlag
    }
    if (isFull) {
      currentFullCancelFlag?.set(true)
      currentFullCancelFlag = cancelFlag
    }

    val chosen = if (isFull) getFullDownloadExecutor() else getExecutor()
    chosen.execute {
      val writerLock = cacheKeyWriterLocks.computeIfAbsent(cacheKey) { Any() }
      val lockWaitStartedAt = SystemClock.elapsedRealtime()
      try {
        synchronized(writerLock) {
          if (cancelFlag.get() || generation != prefetchGeneration) return@execute
          val lockWaitMs = SystemClock.elapsedRealtime() - lockWaitStartedAt
          if (lockWaitMs > 500L) {
            emitDiagnosticLog("DIAG-CacheWriter", "lock wait ${lockWaitMs}ms key=$cacheKey")
          }
          val factory = buildHttpCacheFactoryForPrefetch(appContext)
          val ds = factory.createDataSource()
          val specBuilder =
            DataSpec
              .Builder()
              .setUri(uri)
              .setKey(cacheKey)
              .setPosition(0)
          if (length != Long.MAX_VALUE) {
            specBuilder.setLength(length)
          }
          val spec = specBuilder.build()
          val writer =
            CacheWriter(
              ds as CacheDataSource,
              spec,
              null,
            ) { _, _, _ ->
              if (cancelFlag.get()) Thread.currentThread().interrupt()
            }
          writer.cache()

          if (length == Long.MAX_VALUE) {
            val expected = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
            val actual = cache.getCachedBytes(cacheKey, 0, Long.MAX_VALUE)
            if (expected > 0 && actual < expected) {
              Log.w(PREFETCH_TAG, "promote skipped (truncated): $cacheKey cached=$actual expected=$expected")
            } else {
              ttlIndex.promote(cacheKey)
            }
          } else {
            ttlIndex.markAccess(cacheKey)
          }
          val lengthLabel = if (length == Long.MAX_VALUE) "FULL" else "$length bytes"
          Log.d(PREFETCH_TAG, "prefetch done: $cacheKey ($lengthLabel)")
        }
      } catch (e: Throwable) {
        Log.d(PREFETCH_TAG, "prefetch aborted: $cacheKey - ${e.message}")
      } finally {
        synchronized(inFlight) {
          inFlight.remove(inFlightKey)
        }
        cacheKeyWriterLocks.remove(cacheKey, writerLock)
      }
    }
  }

  @JvmStatic
  fun cancelAllPrefetch() {
    prefetchGeneration++
    currentCancelFlag?.set(true)
    currentFullCancelFlag?.set(true)
    synchronized(inFlight) {
      inFlight.clear()
    }
  }

  @JvmStatic
  fun clearAll(appContext: Context) {
    cancelAllPrefetch()
    val cache = simpleCache
    if (cache != null) {
      val keys = HashSet(cache.keys)
      for (key in keys) {
        try {
          cache.removeResource(key)
        } catch (e: Throwable) {
          Log.w(TAG, "removeResource failed: $key", e)
        }
      }
    }
    AudioPrefetchTtlIndex.getInstance(appContext).clearAllPromoted()
  }

  @JvmStatic
  fun enforceLimitTo(
    appContext: Context,
    maxAudioBytes: Long,
  ): Long {
    val cache = simpleCache ?: return 0L
    val total = cache.cacheSpace
    if (total <= maxAudioBytes) return 0L

    val idx = AudioPrefetchTtlIndex.getInstance(appContext)
    val keys =
      try {
        HashSet(cache.keys)
      } catch (e: Throwable) {
        return 0L
      }

    class KeyMeta(
      val key: String,
      val mtime: Long,
      val bytes: Long,
      val promoted: Boolean,
    )

    val metas = ArrayList<KeyMeta>()
    for (key in keys) {
      val spans =
        try {
          cache.getCachedSpans(key)
        } catch (e: Throwable) {
          continue
        }
      if (spans.isEmpty()) continue
      var maxMtime = 0L
      var bytes = 0L
      for (s in spans) {
        if (s.file != null) maxMtime = kotlin.math.max(maxMtime, s.file!!.lastModified())
        bytes += s.length
      }
      metas.add(KeyMeta(key, maxMtime, bytes, idx.isPromoted(key)))
    }

    metas.sortWith { a, b ->
      if (a.promoted != b.promoted) return@sortWith if (a.promoted) 1 else -1
      a.mtime.compareTo(b.mtime)
    }

    var current = total
    val target = (maxAudioBytes * 0.8).toLong()
    var freed = 0L
    for (m in metas) {
      if (current <= target) break
      try {
        cache.removeResource(m.key)
        if (m.promoted) idx.unmarkPromoted(m.key)
        current -= m.bytes
        freed += m.bytes
      } catch (e: Throwable) {
        Log.w(TAG, "evict failed: ${m.key}", e)
      }
    }
    Log.i(TAG, "audio enforceLimit: 释放 $freed 字节，回到 $current/$maxAudioBytes")
    return freed
  }

  @JvmStatic
  fun getPromotedAudioFile(
    appContext: Context,
    url: String?,
  ): File? {
    if (url.isNullOrEmpty()) return null
    val uri =
      try {
        Uri.parse(url)
      } catch (e: Throwable) {
        return null
      }
    val scheme = uri.scheme
    if (scheme == null || (scheme != "http" && scheme != "https")) return null

    val cacheKey = resolveCacheKey(uri)
    if (!AudioPrefetchTtlIndex.getInstance(appContext).isPromoted(cacheKey)) return null

    val cache = getOrCreate(appContext)

    val spans =
      try {
        cache.getCachedSpans(cacheKey)
      } catch (e: Throwable) {
        return null
      }
    if (spans.isEmpty()) return null

    if (spans.size > 1) return null
    val span = spans.first()
    if (span == null || span.position != 0L || !span.isCached || span.file == null) return null
    if (!span.file!!.isFile) return null
    return span.file
  }

  @JvmStatic
  fun isPromotedAudioReady(
    appContext: Context,
    url: String?,
  ): Boolean = getPromotedAudioFile(appContext, url) != null
}
