package top.imsyy.splayer_next.android.cache

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * 音频预载缓存 TTL 索引。
 *
 * <p>独立于 [CacheStorage] 的全局 LRU：仅作用于 ExoPlayer SimpleCache 中的 prefetch 字节，
 * 给每个 cacheKey 维护"最后一次被访问"的时间戳，超过 [TTL_MILLIS] 未访问则从 SimpleCache 删除。
 *
 * <p>访问语义：
 *
 * <ul>
 *   <li>prefetchUrl() 完成后调 [markAccess]：记录 t = now
 *   <li>PlaybackManager.load() 真正播放时调 [markAccess]：续期到 now（重复播放保留缓存）
 * </ul>
 *
 * <p>[sweep] 在应用启动 + 每 30 分钟跑一次，删掉过期条目并调用 SimpleCache.removeResource。
 *
 * <p>持久化：SharedPreferences 文件 audio_prefetch_index，仅存 cacheKey → lastAccessAtMs 映射。
 */
class AudioPrefetchTtlIndex private constructor(
  context: Context,
) {
  private val appContext: Context = context.applicationContext

  /** prefetch 时间戳索引：cacheKey → lastAccessAtMs。仅包含「临时预载」条目。 */
  private val prefs: SharedPreferences =
    this.appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

  /** promoted 集合：“正式缓存” cacheKey，不受 50min TTL，仅受全局 LRU 配额管。 */
  private val promotedPrefs: SharedPreferences =
    this.appContext.getSharedPreferences(PREF_PROMOTED, Context.MODE_PRIVATE)

  /** 后台线程：sweep 涉及 SP 全表读 + SimpleCache.removeResource 物理删文件，不能跑在主线程。 */
  private val sweepThread: HandlerThread =
    HandlerThread("audio-prefetch-sweep", Thread.NORM_PRIORITY - 2).apply { start() }
  private val sweepHandler: Handler = Handler(sweepThread.looper)

  private val sweepRunnable =
    object : Runnable {
      override fun run() {
        sweep()
        // 重新调度
        sweepHandler.postDelayed(this, SWEEP_INTERVAL_MILLIS)
      }
    }

  companion object {
    private const val TAG = "AudioPrefetchTtl"
    private const val PREF_NAME = "audio_prefetch_index"

    /** promoted 集合 prefs：key 为 cacheKey，value 固定 true（只看 key 是否存在）。 */
    private const val PREF_PROMOTED = "audio_cache_promoted"

    /** 50 分钟有效期；过期清除（仅适用于未 promoted 的 prefetch 字节）。 */
    const val TTL_MILLIS = 50L * 60L * 1000L

    /** 周期清理间隔：30 分钟。 */
    const val SWEEP_INTERVAL_MILLIS = 30L * 60L * 1000L

    @Volatile
    private var instance: AudioPrefetchTtlIndex? = null

    @JvmStatic
    fun getInstance(context: Context): AudioPrefetchTtlIndex =
      instance ?: synchronized(this) {
        instance ?: AudioPrefetchTtlIndex(context).also { instance = it }
      }
  }

  /**
   * 标记一次访问：cacheKey → now。<br>
   * 调用方：prefetchUrl 完成 / PlaybackManager.load 时各调一次。重复调用即续期。
   * <p>若该 key 已 promoted，markAccess 为 no-op（promoted 不受 TTL）。
   */
  fun markAccess(cacheKey: String) {
    if (cacheKey.isEmpty()) return
    if (promotedPrefs.contains(cacheKey)) return // 已正式缓存，无需记时间戳
    prefs.edit().putLong(cacheKey, System.currentTimeMillis()).apply()
  }

  /**
   * 升级为“正式缓存”：调用者判定用户真的听了这首歌（如播放 > 10s）。<br>
   * 从 prefetch 索引中移除，加入 promoted 集合；将不再被 50min TTL sweep 清。
   *
   * @return true 表示是首次 promote（调用者可据此决定是否启动预载）
   */
  fun promote(cacheKey: String): Boolean {
    if (cacheKey.isEmpty()) return false
    if (promotedPrefs.contains(cacheKey)) return false
    promotedPrefs.edit().putBoolean(cacheKey, true).apply()
    prefs.edit().remove(cacheKey).apply()
    Log.d(TAG, "promoted: $cacheKey")
    return true
  }

  /** 查询是否已 promoted。供 PlaybackManager / 设置面板统计使用。 */
  fun isPromoted(cacheKey: String): Boolean = cacheKey.isNotEmpty() && promotedPrefs.contains(cacheKey)

  /** 取消单个 key 的 promoted 标记。供 audio LRU 驱逐时同步索引使用。 */
  fun unmarkPromoted(cacheKey: String) {
    if (cacheKey.isEmpty()) return
    promotedPrefs.edit().remove(cacheKey).apply()
  }

  /** 清空所有 promoted 标记 + prefetch 时间戳；调用方：clearAll audio 缓存时同步索引。 */
  fun clearAllPromoted() {
    promotedPrefs.edit().clear().apply()
    prefs.edit().clear().apply()
  }

  /** 返回 promoted cacheKey 总数。供设置面板统计。 */
  fun countPromoted(): Int = promotedPrefs.all.size

  /** 返回 prefetch-only cacheKey 总数（未 promoted）。 */
  fun countPrefetchOnly(): Int = prefs.all.size

  /**
   * 扫描所有条目，删除：
   *
   * <ol>
   *   <li>过期（now - lastAccessAt > TTL_MILLIS）的条目，从 SimpleCache 中清除对应资源
   *   <li>SimpleCache 中已经不存在该 cacheKey 的"孤立"索引项
   * </ol>
   */
  fun sweep() {
    val now = System.currentTimeMillis()
    val expireBefore = now - TTL_MILLIS

    val all = prefs.all

    val cache = AudioCacheProvider.peekSimpleCache()
    if (cache == null) {
      Log.d(TAG, "sweep: SimpleCache not initialized, skip")
      return
    }
    val liveKeys = cache.keys.toSet()

    // 1) 扫 prefetch 索引：TTL 过期 / orphan 清除
    val editor = prefs.edit()
    var expired = 0
    var orphan = 0
    for ((cacheKey, v) in all) {
      if (v !is Long) {
        editor.remove(cacheKey)
        continue
      }
      val ts = v

      // 防御：若 cacheKey 已 promoted，不应出现在 prefetch 索引里（除非 markAccess 发生在 promote 之前且有并发）
      if (promotedPrefs.contains(cacheKey)) {
        editor.remove(cacheKey)
        continue
      }

      if (!liveKeys.contains(cacheKey)) {
        editor.remove(cacheKey)
        orphan++
        continue
      }

      if (ts < expireBefore) {
        // TOCTOU 二次校验：sweep 进入 if 后、调 removeResource 前，
        // 另一线程的 promote() 可能刚把这个 key 升为正式缓存。
        // 若已 promoted 则跳过物理删，避免误删用户刚听过 >10s 的音频字节。
        if (promotedPrefs.contains(cacheKey)) {
          editor.remove(cacheKey)
          continue
        }
        val latestTs = prefs.getLong(cacheKey, ts)
        if (latestTs >= expireBefore) {
          continue
        }
        try {
          cache.removeResource(cacheKey)
          editor.remove(cacheKey)
          expired++
        } catch (e: Throwable) {
          Log.w(TAG, "sweep: removeResource failed: $cacheKey", e)
        }
      }
    }
    editor.apply()

    // 2) 扫 promoted 集合：清除 SimpleCache 已不存在的孤立条目（被全局 LRU 配额删过）
    val promotedAll = promotedPrefs.all
    val pEditor = promotedPrefs.edit()
    var promotedOrphan = 0
    for (cacheKey in promotedAll.keys) {
      if (!liveKeys.contains(cacheKey)) {
        pEditor.remove(cacheKey)
        promotedOrphan++
      }
    }
    pEditor.apply()

    if (expired > 0 || orphan > 0 || promotedOrphan > 0) {
      Log.d(
        TAG,
        "sweep done: expired=$expired, orphan=$orphan, promotedOrphan=$promotedOrphan",
      )
    }
  }

  /** 启动周期清理（应用启动时调一次）；幂等，重复调不会重复 schedule。 */
  fun startPeriodicSweep() {
    sweepHandler.removeCallbacks(sweepRunnable)
    // 延后 5s 跑首次 sweep，避免与启动期 IO 抢资源
    sweepHandler.postDelayed(sweepRunnable, 5_000L)
  }

  /** 停止周期清理（仅测试 / 应用关闭使用）。 */
  fun stopPeriodicSweep() {
    sweepHandler.removeCallbacks(sweepRunnable)
  }
}
