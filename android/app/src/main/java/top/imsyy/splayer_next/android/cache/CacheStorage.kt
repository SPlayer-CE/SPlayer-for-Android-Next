package top.imsyy.splayer_next.android.cache

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * 统一缓存存储：cacheDir 下分类型子目录，全局 LRU（按 mtime），严格遵从 maxBytes。
 *
 * <p>设计要点：
 * <ul>
 *   <li>落盘到 [Context.getCacheDir]，App 卸载随系统清理；ROM 「应用缓存」识别。
 *   <li>所有文件统一 `.bin` 扩展名，避开 MediaStore 扫描。
 *   <li>`.nomedia` 兜底。
 *   <li>读命中时刷新 mtime，确保「最近播放」不被误删。
 *   <li>写后概率性触发驱逐（10%），全局节流。手动调用 [enforceLimit] 立即执行。
 *   <li>maxBytes 由用户在 setting 设置严格生效；运行期不擅自下调。
 * </ul>
 */
class CacheStorage private constructor(
  context: Context,
) {
  private val appContext: Context = context.applicationContext
  private val rootDir: File = appContext.cacheDir
  private val writeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private val maxBytes = AtomicLong(DEFAULT_MAX_BYTES)
  private val fileWriteLocks = ConcurrentHashMap<String, Any>()

  /** 上次「驱逐至失败」日志时间戳，避免日志洪水。 */
  @Volatile
  private var lastEvictWarnAtMs = 0L

  /**
   * 内存索引：type → 当前总字节。读 O(1) 替代扫盘。<br>
   * 维护策略：
   * <ul>
   *   <li>构造期一次性 baseline 扫描所有 type 子目录
   *   <li>[write] 成功后增量加（若文件已存在则先减旧 size）
   *   <li>[remove] / [clear] 同步减或归零
   *   <li>[enforceLimit] 删文件时同步减
   *   <li>`audio` 类型不进此 map（由 ExoPlayer SimpleCache.getCacheSpace 直接返）
   *   <li>启动后每 30min reconcile 一次：和实际扫描值对账，漂移 > 5% 则纠正
   * </ul>
   */
  private val perTypeBytes = ConcurrentHashMap<String, AtomicLong>()

  /** 上次 reconcile 时间戳，防止短时间重复扫盘。 */
  @Volatile
  private var lastReconcileAtMs = 0L

  init {
    if (!rootDir.exists()) {
      rootDir.mkdirs()
    }
    ensureNoMediaSentinel()
    for (type in ALL_TYPES) {
      ensureTypeDir(type)
      // 非 audio 类型预创建索引项（audio 走 SimpleCache.getCacheSpace 不入索引）
      if (TYPE_AUDIO != type) {
        perTypeBytes[type] = AtomicLong(0L)
      }
    }
    // 构造期 baseline scan：后台线程跑，不阻塞首屏
    writeExecutor.submit { reconcileAllNow() }
  }

  companion object {
    private const val TAG = "CacheStorage"

    /** 默认 5GB；setting 端调用 setMaxBytes 之前的兜底值。 */
    private const val DEFAULT_MAX_BYTES = 5L * 1024 * 1024 * 1024

    /** 最低 256MB：低于此值频繁驱逐反而损伤体验。 */
    private const val MIN_MAX_BYTES = 256L * 1024 * 1024

    /** 驱逐回到 80% 水位（避免抖动反复触发）。 */
    private const val EVICT_TARGET_RATIO = 0.8

    /** 写入后随机触发驱逐检查的概率，节流 stat 调用。 */
    private const val EVICT_PROBE_PROB = 0.1

    /** 文件扩展名：所有缓存文件统一 .bin 防 MediaStore 扫描。 */
    const val CACHE_EXT = ".bin"

    /** 已知缓存类型子目录名。新增类型在此扩展。ExoPlayer 的 exo/ 子目录由音频侧管理但参与全局 LRU。 */
    const val TYPE_LYRICS = "lyrics"
    const val TYPE_COVERS = "covers"
    const val TYPE_LIST_COVERS = "list-covers"
    const val TYPE_LIST_DATA = "list-data"
    const val TYPE_AUDIO = "exo" // ExoPlayer SimpleCache 子目录

    private val ALL_TYPES =
      arrayOf(
        TYPE_LYRICS,
        TYPE_COVERS,
        TYPE_LIST_COVERS,
        TYPE_LIST_DATA,
        TYPE_AUDIO,
      )

    @Volatile
    private var instance: CacheStorage? = null

    /** reconcile 节流间隔：30 分钟。 */
    private const val RECONCILE_INTERVAL_MS = 30L * 60L * 1000L

    /** mtime 节流间隔：1 小时；read 命中时若 mtime 已 < 1h 前则不再 setLastModified。 */
    private const val MTIME_THROTTLE_MS = 60L * 60L * 1000L

    /**
     * 单次 read 返回给 JS 的上限：8MB。封面/歌词<2MB，list-data 歌单<5MB，8MB 足够且避免低端机 OOM。<br>
     * 超过该大小一定是异常（调用方错误使用 /文件写入），提前 reject。
     */
    private const val MAX_READ_BYTES = 8L * 1024 * 1024

    /**
     * 设备剩余空间不足时的自适应上限比例：缓存总占用不能超过剩余空间 × 60%。<br>
     * 例：设备剩 8GB → 有效封顶 4.8GB；设备剩 4GB → 有效封顶 2.4GB。<br>
     * 仅在该限制低于用户设定值时生效；高于用户设定仍以用户设定为准。
     */
    private const val ADAPTIVE_FREE_RATIO = 0.6

    @JvmStatic
    fun getInstance(context: Context): CacheStorage =
      instance ?: synchronized(this) {
        instance ?: CacheStorage(context).also { instance = it }
      }

    /** 计算给定 url 的缓存 key（md5 hex）。封面 / list-data 等有 url 的场景使用。 */
    @JvmStatic
    fun keyFromUrl(url: String): String =
      try {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(url.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
          val hex = Integer.toHexString(b.toInt() and 0xFF)
          if (hex.length == 1) sb.append('0')
          sb.append(hex)
        }
        sb.toString()
      } catch (e: Exception) {
        // 兜底：直接 hashCode（碰撞概率高，但不致命）
        Integer.toHexString(url.hashCode())
      }

    /** 仅供调试 / 测试：拿全部 type 列表 */
    @JvmStatic
    fun knownTypes(): Array<String> = ALL_TYPES.copyOf()

    /** type 白名单（与 plugin 层 ALLOWED_TYPES 同步），用 Set 加速校验。 */
    private val KNOWN_TYPE_SET = ALL_TYPES.toSet()

    /** 防路径穿越：去掉 .. 与 / 等。空 key 直接拒绝（sanitize 退化成 ".bin" 隐藏文件，list/sweep 反推会得到空 key，往返不对称）。 */
    private fun sanitizeKey(key: String): String {
      require(key.isNotEmpty()) { "cache key must not be empty" }
      return key
        .replace("..", "_")
        .replace('/', '_')
        .replace('\\', '_')
        .replace(':', '_')
    }

    private fun dirSize(dir: File?): Long {
      if (dir == null || !dir.isDirectory) return 0L
      var total = 0L
      val children = dir.listFiles() ?: return 0L
      for (f in children) {
        if (f.isDirectory) {
          total += dirSize(f)
        } else {
          total += f.length()
        }
      }
      return total
    }

    private fun deleteRecursive(f: File?): Boolean {
      if (f == null || !f.exists()) return true
      if (f.isDirectory) {
        val children = f.listFiles()
        if (children != null) {
          for (c in children) deleteRecursive(c)
        }
      }
      return f.delete()
    }
  }

  /** 设置缓存上限（字节），最小 256MB；低于则按 256MB 处理。 */
  fun setMaxBytes(bytes: Long) {
    val clamped = max(MIN_MAX_BYTES, bytes)
    val old = maxBytes.getAndSet(clamped)
    if (old != clamped) {
      // 上限调小时立即异步驱逐
      writeExecutor.submit { enforceLimit() }
    }
  }

  /** 返回用户设定的原始上限（设置面板展示用）；不含设备容量自适应调控。 */
  fun getMaxBytes(): Long = maxBytes.get()

  /**
   * 返回运行期生效的上限。计算逻辑：
   *
   * <ol>
   *   <li>读用户设定值 `maxBytes`（同 [getMaxBytes]）
   *   <li>读设备剩余可用空间 `free`；StatFs 失败 (-1) 时跳过自适应
   *   <li>取 `min(maxBytes, free * 0.6)`，再与 [MIN_MAX_BYTES] 取 max
   * </ol>
   *
   * <p>例：用户设 5GB、设备剩 8GB → effective = min(5GB, 4.8GB) = 4.8GB；<br>
   * 用户设 5GB、设备剩 20GB → effective = min(5GB, 12GB) = 5GB（用户设为准）。
   */
  fun getEffectiveMaxBytes(): Long {
    val userMax = maxBytes.get()
    val free = getDeviceFreeBytes()
    if (free < 0) return userMax // 读不出剩余空间，退化为用户设定
    val adaptive = (free * ADAPTIVE_FREE_RATIO).toLong()
    val effective = min(userMax, adaptive)
    return max(MIN_MAX_BYTES, effective)
  }

  /** 当前剩余可用磁盘空间（用于 UI 校验，不参与运行期 cap 计算）。 */
  fun getDeviceFreeBytes(): Long =
    try {
      val stat = StatFs(rootDir.absolutePath)
      stat.availableBytes
    } catch (e: Exception) {
      -1L
    }

  /** 获取总占用（O(1)：累加内存索引 + audio SimpleCache 占用）。 */
  fun getTotalBytes(): Long {
    var total = 0L
    for (type in ALL_TYPES) {
      total += getTypeBytes(type)
    }
    return total
  }

  /** 单类型占用 O(1)：非 audio 类型读内存索引；audio 走 SimpleCache.getCacheSpace。 */
  fun getTypeBytes(type: String): Long {
    if (TYPE_AUDIO == type) {
      return getAudioCacheSpace()
    }
    val v = perTypeBytes[type]
    return v?.get() ?: 0L
  }

  /** 全部类型分项占用 O(1)。 */
  fun getPerTypeBytes(): Map<String, Long> {
    val map = HashMap<String, Long>()
    for (type in ALL_TYPES) {
      map[type] = getTypeBytes(type)
    }
    return map
  }

  /**
   * 读 ExoPlayer SimpleCache 当前占用 —— 它内部已维护索引，O(1) 无扫盘。<br>
   * SimpleCache 尚未初始化（应用启动早期）则回退到一次性扫盘。
   */
  private fun getAudioCacheSpace(): Long {
    try {
      val cache = AudioCacheProvider.peekSimpleCache()
      if (cache != null) return cache.cacheSpace
    } catch (e: Throwable) {
      // SimpleCache 不可用时静默回退
    }
    return dirSize(typeDir(TYPE_AUDIO))
  }

  /** 读取（命中后节流刷新 mtime 让 LRU 视作最近使用）。 */
  fun read(
    type: String,
    key: String,
  ): ByteArray? {
    if (TYPE_AUDIO == type) {
      // audio 由 ExoPlayer SimpleCache 内部读取；JS 侧不应走 read('exo', …)。
      Log.w(TAG, "read('exo', $key) rejected: audio cache is managed by SimpleCache")
      return null
    }
    val f = fileFor(type, key)
    if (!f.isFile) return null
    val fileLen = f.length()
    if (fileLen > MAX_READ_BYTES) {
      Log.w(TAG, "read rejected: $type/$key size=$fileLen > $MAX_READ_BYTES")
      return null
    }
    val oldMtime = f.lastModified()
    try {
      FileInputStream(f).use { fis ->
        ByteArrayOutputStream(max(1024, min(fileLen, MAX_READ_BYTES).toInt())).use { bos ->
          val buf = ByteArray(8192)
          var n: Int
          while (fis.read(buf).also { n = it } != -1) bos.write(buf, 0, n)
          val data = bos.toByteArray()
          // mtime 节流：仅在距上次更新已超 1 小时才 setLastModified，避免高频读触发 metadata 写
          val now = System.currentTimeMillis()
          if (now - oldMtime > MTIME_THROTTLE_MS) {
            f.setLastModified(now)
          }
          return data
        }
      }
    } catch (e: IOException) {
      Log.w(TAG, "read failed: $type/$key", e)
      return null
    }
  }

  /** 写入（同步路径；调用方需自行决定是否在异步线程执行）。写到 .tmp 后 rename，保证原子性。 */
  fun write(
    type: String,
    key: String,
    data: ByteArray,
  ): Boolean {
    if (TYPE_AUDIO == type) {
      // 避免使用者在 audio/exo 目录写入普通 .bin；会被 SimpleCache 忽略并永不被 LRU 驱逐。
      Log.w(TAG, "write('exo', $key) rejected: audio cache is managed by SimpleCache")
      return false
    }
    val writeLockKey = "$type\u0000$key"
    val writeLock = fileWriteLocks.computeIfAbsent(writeLockKey) { Any() }
    return try {
      synchronized(writeLock) {
        writeLocked(type, key, data)
      }
    } finally {
      fileWriteLocks.remove(writeLockKey, writeLock)
    }
  }

  private fun writeLocked(
    type: String,
    key: String,
    data: ByteArray,
  ): Boolean {
    val f = fileFor(type, key)
    val parent = f.parentFile
    if (parent != null && !parent.exists()) {
      parent.mkdirs()
    }
    // 若文件已存在，先记录旧 size 用于索引差分
    val oldSize = if (f.isFile) f.length() else 0L
    // 原子写：写临时文件 → rename 覆盖；进程被杀 / OOM 不会产生半截断缓存。
    // tmp 名带 UUID 防并发写同一 key 时互改 .tmp。Capacitor 插件多线程调用 + 内部 writeAsync 可能同时出现。
    val tmp = File(f.parentFile, f.name + "." + UUID.randomUUID() + ".tmp")
    try {
      FileOutputStream(tmp).use { fos ->
        fos.write(data)
        fos.fd.sync()
      }
    } catch (e: IOException) {
      tmp.delete()
      val now = System.currentTimeMillis()
      if (now - lastEvictWarnAtMs > 60_000L) {
        Log.w(TAG, "write failed (磁盘不足或 IO 异常): $type/$key", e)
        lastEvictWarnAtMs = now
      }
      return false
    }
    // rename 原子覆盖；Windows 上 rename 覆盖已存在文件会失败，先 delete（Android 实际是 POSIX 语义可直接覆盖，但保险起见）。
    if (f.exists() && !f.delete()) {
      Log.w(TAG, "write rename: 预删旧文件失败 $type/$key")
    }
    if (!tmp.renameTo(f)) {
      tmp.delete()
      Log.w(TAG, "write rename failed: $type/$key")
      return false
    }
    // 内存索引增量维护：新 size - 旧 size
    addBytes(type, data.size - oldSize)
    // 节流触发驱逐：内存索引 O(1)，可以提高检查频率（仍走异步线程）
    if (Math.random() < EVICT_PROBE_PROB) {
      writeExecutor.submit { enforceLimit() }
    }
    // 周期 reconcile：30min 跑一次，纠正外部修改 / 索引漂移
    maybeReconcile()
    return true
  }

  /** 异步写入。 */
  fun writeAsync(
    type: String,
    key: String,
    data: ByteArray,
  ) {
    writeExecutor.submit { write(type, key, data) }
  }

  /** 删除单文件。 */
  fun remove(
    type: String,
    key: String,
  ): Boolean {
    if (TYPE_AUDIO == type) {
      Log.w(TAG, "remove('exo', $key) rejected: audio cache is managed by SimpleCache")
      return false
    }
    val f = fileFor(type, key)
    if (!f.isFile) return false
    val size = f.length()
    val ok = f.delete()
    if (ok) addBytes(type, -size)
    return ok
  }

  /** 列出某类型下所有缓存文件（递归）。 */
  fun list(type: String): List<CacheEntry> {
    val out = ArrayList<CacheEntry>()
    collectFiles(typeDir(type), type, out)
    return out
  }

  /**
   * 清空单类型。
   *
   * <p>`exo`（音频）走 SimpleCache.removeResource：不能直接 deleteRecursive，
   * 否则活跃 SimpleCache 实例仍报握内部 ContentIndex，后续写入或 sweep 会招致索引/磁盘不一致。
   */
  fun clear(type: String): Boolean {
    if (TYPE_AUDIO == type) {
      AudioCacheProvider.clearAll(appContext)
      return true
    }
    val dir = typeDir(type)
    val ok = deleteRecursive(dir)
    ensureTypeDir(type) // 重建空目录
    val v = perTypeBytes[type]
    v?.set(0L)
    return ok
  }

  /** 清空所有类型。 */
  fun clearAll(): Boolean {
    var allOk = true
    for (type in ALL_TYPES) {
      allOk = clear(type) && allOk
    }
    ensureNoMediaSentinel()
    // 全清后索引归零（clear 已分别 set 0，这里 paranoia 二次确认）
    for (v in perTypeBytes.values) {
      v.set(0L)
    }
    return allOk
  }

  /**
   * 全局 LRU 驱逐：扫描所有类型，按 mtime 升序删除直到回到 maxBytes * 80%。
   *
   * <p>同步执行；调用方按需放到 [writeExecutor]。
   * <p>驱逐时同步更新内存索引；audio 类型由 ExoPlayer 自管，这里不扫 exo/ 目录。
   */
  @Synchronized
  fun enforceLimit() {
    // 走有效上限：设备空间不足时会从 DEFAULT_MAX_BYTES 自适应降到 free * 60%。
    val limit = getEffectiveMaxBytes()
    // 快照 audio size：避免二轮计算 nonAudio 时与首轮 total 不一致。
    val audioSnapshot = getTypeBytes(TYPE_AUDIO)
    var nonAudioSnapshot = 0L
    for (t in ALL_TYPES) {
      if (TYPE_AUDIO == t) continue
      nonAudioSnapshot += getTypeBytes(t)
    }
    val total = audioSnapshot + nonAudioSnapshot
    if (total <= limit) return

    val target = (limit * EVICT_TARGET_RATIO).toLong()

    val all = ArrayList<CacheEntry>()
    for (type in ALL_TYPES) {
      // 这一轮仅收集非 audio：audio 不能走 file-level deleteRecursive（会破坏 SimpleCache 索引），
      // 后面如果还超配额统一交给 AudioCacheProvider.enforceLimitTo 走 SimpleCache.removeResource 路径。
      if (TYPE_AUDIO == type) continue
      collectFiles(typeDir(type), type, all)
    }
    // 按 mtime 升序：最旧的先删
    all.sortBy { it.mtime }

    var deleted = 0L
    for (e in all) {
      if (total - deleted <= target) break
      val f = File(e.absolutePath)
      val size = e.size
      if (f.delete()) {
        deleted += size
        addBytes(e.type, -size)
      }
    }
    val nonAudioAfter = nonAudioSnapshot - deleted
    // 二轮：非 audio 全删完仍超额 → 压缩 audio。计算使用快照的 audio size 避免中途变化调控偏差。
    if (nonAudioAfter + audioSnapshot > limit) {
      val audioBudget = max(0L, limit - nonAudioAfter)
      AudioCacheProvider.enforceLimitTo(appContext, audioBudget)
    }
    Log.i(
      TAG,
      "enforceLimit: 非 audio 已驱逐 $deleted 字节，总计回到 ${getTotalBytes()}/$limit",
    )
  }

  /** ExoPlayer SimpleCache 路径：cacheDir/exo/。供 PlaybackManager 构造 SimpleCache 用。 */
  fun getAudioCacheDir(): File = ensureTypeDir(TYPE_AUDIO)

  /** 缓存根目录（Context.getCacheDir()），供 UI 展示路径用。 */
  fun getRootDir(): File = rootDir

  // ==================== 内部 ====================

  /** 缓存项元数据。 */
  class CacheEntry(
    val type: String,
    val key: String,
    val size: Long,
    val mtime: Long,
    val absolutePath: String,
  )

  /**
   * 解析 type 子目录。
   *
   * <ol>
   *   <li>type 必须在白名单 [ALL_TYPES] 内
   *   <li>canonical 路径必须以 rootDir 为前缀（防 Windows/Linux 任意符号链接 / 路径穿越）
   * </ol>
   *
   * 不满足任一条件直接抛 IAE：违法调用方应当被立刻发现，不可静默回退。
   */
  private fun typeDir(type: String): File {
    require(KNOWN_TYPE_SET.contains(type)) { "非法 cache type: $type" }
    val dir = File(rootDir, type)
    try {
      val dirCanonical = dir.canonicalPath
      val rootCanonical = rootDir.canonicalPath
      require(dirCanonical == rootCanonical || dirCanonical.startsWith(rootCanonical + File.separator)) {
        "type 逃出 cache 根目录: $type"
      }
    } catch (e: IOException) {
      throw IllegalArgumentException("canonical 解析失败: $type", e)
    }
    return dir
  }

  private fun ensureTypeDir(type: String): File {
    val dir = typeDir(type)
    if (!dir.exists()) {
      dir.mkdirs()
    }
    return dir
  }

  private fun fileFor(
    type: String,
    key: String,
  ): File {
    var safeKey = sanitizeKey(key)
    if (!safeKey.endsWith(CACHE_EXT)) {
      safeKey += CACHE_EXT
    }
    return File(typeDir(type), safeKey)
  }

  private fun ensureNoMediaSentinel() {
    val flag = File(rootDir, ".nomedia")
    if (!flag.exists()) {
      try {
        flag.createNewFile()
      } catch (ignored: IOException) {
      }
    }
  }

  /** 递归收集类型目录下所有文件，相对路径作为 key（不带 .bin 扩展）。 */
  private fun collectFiles(
    dir: File?,
    type: String,
    out: MutableList<CacheEntry>,
  ) {
    if (dir == null || !dir.isDirectory) return
    val children = dir.listFiles() ?: return
    for (f in children) {
      if (f.isDirectory) {
        collectFiles(f, type, out)
      } else if (f.isFile) {
        val name = f.name
        val key =
          if (name.endsWith(CACHE_EXT)) {
            name.substring(0, name.length - CACHE_EXT.length)
          } else {
            name
          }
        out.add(CacheEntry(type, key, f.length(), f.lastModified(), f.absolutePath))
      }
    }
  }

  // ==================== 内存索引维护 ====================

  /** 给指定 type 的内存索引加减字节数；audio 不进索引。 */
  private fun addBytes(
    type: String,
    delta: Long,
  ) {
    if (TYPE_AUDIO == type || delta == 0L) return
    val v = perTypeBytes[type]
    if (v != null) {
      val newVal = v.addAndGet(delta)
      // 兜底：理论不会负，但若外部删 / 索引未跟上则纠正
      if (newVal < 0L) v.set(0L)
    }
  }

  /**
   * 节流 reconcile：仅在距上次 reconcile 已超 30min 时触发后台全扫，纠正索引漂移。<br>
   * 漂移来源：外部删文件（系统清缓存）、构造期 baseline 尚未跑完的并发写。
   */
  private fun maybeReconcile() {
    val now = System.currentTimeMillis()
    if (now - lastReconcileAtMs < RECONCILE_INTERVAL_MS) return
    lastReconcileAtMs = now
    writeExecutor.submit { reconcileAllNow() }
  }

  /** 强制 reconcile：扫所有非 audio type 目录，把内存索引校准到真实 size。 */
  private fun reconcileAllNow() {
    for (type in ALL_TYPES) {
      if (TYPE_AUDIO == type) continue
      val real = dirSize(typeDir(type))
      val v = perTypeBytes[type]
      if (v != null) {
        val old = v.getAndSet(real)
        val diff = Math.abs(real - old)
        // 漂移 > 1MB 时记日志，便于排查异常
        if (diff > 1024 * 1024) {
          Log.i(TAG, "reconcile $type: $old → $real (diff=$diff)")
        }
      }
    }
    lastReconcileAtMs = System.currentTimeMillis()
  }
}
