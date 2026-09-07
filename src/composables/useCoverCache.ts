import { ref, watch, onBeforeUnmount, type Ref } from "vue";
import { Capacitor } from "@capacitor/core";
import { useCacheManager, type CacheResourceType } from "@/core/resource/CacheManager";
import { isAndroid } from "@/services/bridge";
import { useSettingsStore } from "@/stores/settings";

/** 封面缓存 type；其他类型不进入此 helper。 */
export type CoverCacheType = Extract<CacheResourceType, "covers" | "list-covers">;

/** url → 仅 ASCII 的安全 key（取末段路径，附加 hash 防同名冲突）。 */
const buildKey = (url: string): string => {
  // 简单 hash：dj2b 算法，碰撞概率极低且 deterministic
  let h = 5381;
  for (let i = 0; i < url.length; i++) h = ((h << 5) + h + url.charCodeAt(i)) | 0;
  const hashHex = (h >>> 0).toString(16);
  // path tail 提供可读性（调试时方便看出是哪首歌的封面）
  let tail = "";
  try {
    const u = new URL(url);
    const seg = u.pathname.split("/").filter(Boolean).pop() || "";
    tail = seg.replace(/[^A-Za-z0-9._-]/g, "_").slice(-32);
  } catch {
    /* not a valid URL: 走 hash 兜底 */
  }
  return tail ? `${tail}_${hashHex}` : hashHex;
};

/**
 * 内存级 blob URL LRU：上限 200 张；超出时尝试弹出最旧条目并 revokeObjectURL。
 *
 * <p>引入引用计数：useCoverCache 组件挂载时 retain，卸载时 release；refCount > 0 的条目
 * **不会被 LRU 立即 revoke**，而是标记成 pending revoke，等最后一个使用者 release 时再清。
 * 这样可以解决「LRU 顶掉的 blob 仍被某个活动 <img> 引用导致破图」的问题。
 *
 * <p>refCount=0 且未被引用的条目正常按 LRU 淘汰；超限但仍被引用的条目暂留，count 释放时再清。
 */
const MEMORY_HIT_LIMIT = 200;
type CoverEntry = { blobUrl: string; refCount: number; pendingRevoke: boolean };
const memoryHit = new Map<string, CoverEntry>(); // type|url → entry，LRU（Map 保留插入顺序）
const memoryKeyFor = (type: CoverCacheType, url: string): string => `${type}|${url}`;

/**
 * 入档新条目。<br>
 * <b>关键修复 #2</b>：新条目以 refCount=1 入档（pre-retain），
 * 调用方在用完 blobUrl 后必须配对调一次 memoryHitRelease 释放。<br>
 * 旧实现 refCount=0 入档，从 resolveCachedCover 返回到 useCoverCache 的 watch 处理器
 * 调 memoryHitRetain 之间存在异步窗口（await microtask），期间若并发触发 memoryHitPut
 * 把上限顶爆，本条目（refCount=0）就是第一个被 LRU 选中 revoke 的候选，
 * 等调用方拿到 blobUrl 时已是失效 URL，浏览器渲染为破图。
 */
const memoryHitPut = (type: CoverCacheType, url: string, blobUrl: string): void => {
  const memoryKey = memoryKeyFor(type, url);
  if (memoryHit.has(memoryKey)) memoryHit.delete(memoryKey);
  memoryHit.set(memoryKey, { blobUrl, refCount: 1, pendingRevoke: false });
  // 超限淘汰：跳过仍被引用的条目（含本次新条目），给它们打上 pendingRevoke 标记延迟到 release 时清
  while (memoryHit.size > MEMORY_HIT_LIMIT) {
    let evicted = false;
    for (const [k, v] of memoryHit) {
      if (v.refCount > 0) {
        // 暂不能 revoke：等 release 收尾
        v.pendingRevoke = true;
        continue;
      }
      memoryHit.delete(k);
      URL.revokeObjectURL(v.blobUrl);
      evicted = true;
      break;
    }
    // 全部仍被引用：跳出避免死循环；超额暂存留，等 release 自然清
    if (!evicted) break;
  }
};

/**
 * 命中返 blobUrl 同时 refCount+1（所有权移交调用方）。<br>
 * 配合 memoryHitPut 的 pre-retain 语义统一：无论 HIT 还是 MISS，调用方都拿到「已 retain」的 url，
 * 用完必须配对调用 memoryHitRelease（修复 #2）。
 */
const memoryHitGet = (type: CoverCacheType, url: string): string | undefined => {
  const memoryKey = memoryKeyFor(type, url);
  const e = memoryHit.get(memoryKey);
  if (e !== undefined) {
    // LRU touch：删除后重新 set，挪到 Map 末尾
    memoryHit.delete(memoryKey);
    memoryHit.set(memoryKey, e);
    e.refCount++;
    return e.blobUrl;
  }
  return undefined;
};

/** 引用计数 -1；refCount=0 且 pendingRevoke 时立刻 revoke 并清出 Map。 */
const memoryHitRelease = (type: CoverCacheType, url: string): void => {
  const memoryKey = memoryKeyFor(type, url);
  const e = memoryHit.get(memoryKey);
  if (!e) return;
  e.refCount = Math.max(0, e.refCount - 1);
  if (e.refCount === 0 && e.pendingRevoke) {
    memoryHit.delete(memoryKey);
    URL.revokeObjectURL(e.blobUrl);
  }
};

/** type|url → 解析中的 Promise，仅活到 cm.get 完成，去重首次解析并发。type 隔离防串话。 */
const inFlight = new Map<string, Promise<string | undefined>>();
/** type|url → 后台下载 Promise，活到 fetch + cm.set 写盘完成；防止 #4 同 url 重复网络请求。 */
const downloadInFlight = new Map<string, Promise<void>>();

/**
 * 解析 url：命中本地缓存返 blob URL；未命中返 undefined（调用方应回退到原 url，
 * 同时本 helper 会在后台异步下载并写入缓存，下次进入直接命中）。
 */
const resolveCachedCover = async (
  url: string,
  type: CoverCacheType,
): Promise<string | undefined> => {
  if (!url || !url.startsWith("http")) return undefined;
  const cm = useCacheManager();
  const key = buildKey(url);
  const flightKey = `${type}|${url}`;
  // 内存级 hit 直接返（带 LRU touch + refCount++）
  const hit = memoryHitGet(type, url);
  if (hit) return hit;
  // 并发 dedup（按 type+url 隔离，避免 covers 与 list-covers 串话）。
  // 修复 #4：pending 命中时不能直接返 await 结果——首次调用方在 memoryHitPut 已拿走那 1 个引用，
  // 后续 waiter 必须各自再过一次 memoryHitGet 拿到自己的 retain，否则卸载时多次 release 会让
  // refCount 错误归零，触发 LRU pendingRevoke 把仍被使用的 blob URL revoke 掉（破图）。
  const pending = inFlight.get(flightKey);
  if (pending) {
    return pending.then((firstResult) => {
      if (firstResult && firstResult.startsWith("blob:")) {
        // 走 memoryHitGet 拿本调用方的 retain；若 entry 已被 evict 则降级返原值（调用方走原 url 兜底）
        const ownRef = memoryHitGet(type, url);
        return ownRef ?? firstResult;
      }
      return firstResult;
    });
  }

  const task = (async (): Promise<string | undefined> => {
    try {
      const r = await cm.get(type, key);
      if (r.success && r.data) {
        // Blob 构造在 TS 5.x 对 Uint8Array.buffer (ArrayBufferLike) 推断过严，断言为 ArrayBuffer 兜底
        const ab = r.data.buffer.slice(
          r.data.byteOffset,
          r.data.byteOffset + r.data.byteLength,
        ) as ArrayBuffer;
        const blob = new Blob([ab]);
        const blobUrl = URL.createObjectURL(blob);
        memoryHitPut(type, url, blobUrl);
        return blobUrl;
      }
    } catch {
      /* miss：走未命中分支 */
    }
    // 未命中：后台异步下载并写入；不阻塞返回，让调用方先用原 url 显示
    void downloadAndCache(url, key, type);
    return undefined;
  })();

  inFlight.set(flightKey, task);
  try {
    return await task;
  } finally {
    inFlight.delete(flightKey);
  }
};

/**
 * 后台抓取并写入缓存（fire-and-forget）。同 url 期间已有下载在跑则直接复用，
 * 防止 resolveCachedCover 在 cm.get miss 后多次触发同一 url 的网络请求（#4 修复）。
 */
const downloadAndCache = (url: string, key: string, type: CoverCacheType): Promise<void> => {
  const flightKey = `${type}|${url}`;
  const existing = downloadInFlight.get(flightKey);
  if (existing) return existing;

  // AbortController 兜底：30s 还没完成则主动取消 fetch，配合 finally 清 inflight
  const ctrl = new AbortController();
  // 包成 holder：正常完成时立刻 clearTimeout，避免闭包延寿 30s（ctrl/job/flightKey 占内存）
  const timer: { id?: ReturnType<typeof setTimeout> } = {};
  const job = (async () => {
    try {
      const settingsStore = useSettingsStore();
      if (!settingsStore.system.cache.enabled) return;
      const resp = await fetch(url, { signal: ctrl.signal });
      if (!resp.ok) return;
      const buf = await resp.arrayBuffer();
      if (buf.byteLength === 0) return;
      const cm = useCacheManager();
      await cm.set(type, key, new Uint8Array(buf));
    } catch (e) {
      // 网络失败 / abort 不致命：下次访问仍可能命中或重试
      console.warn("[useCoverCache] download failed:", url, e);
    } finally {
      downloadInFlight.delete(flightKey);
      if (timer.id !== undefined) clearTimeout(timer.id);
    }
  })();

  downloadInFlight.set(flightKey, job);
  // 30s 兜底超时：abort fetch + 清 inflight；正常完成时由上面 finally clearTimeout 取消
  timer.id = setTimeout(() => {
    if (downloadInFlight.get(flightKey) === job) {
      ctrl.abort();
      downloadInFlight.delete(flightKey);
    }
  }, 30_000);
  return job;
};

/**
 * 预下载封面到本地缓存：供 SongManager.prefetchNextSong 等场景主动调用。
 *
 * <p>已在缓存命中或正在下载时直接跳过；并发安全（同 url 只发一次请求）。
 * 与 resolveCachedCover 共享 inFlight / memoryHit map，去重彻底。
 *
 * @param url 远端封面 url（http/https）
 * @param type 默认 "covers"；列表场景传 "list-covers"
 */
export const prefetchCoverToCache = async (
  url: string | undefined,
  type: CoverCacheType = "covers",
): Promise<void> => {
  if (!url || !url.startsWith("http")) return;
  if (!isAndroid) return;
  // 复用 resolveCachedCover：命中直接返，未命中触发后台下载。
  // 拿到 blob URL 后立即 release（修复 #2 pre-retain 副作用）：
  // prefetch 仅是「写入缓存」语义，本身不持有 blob URL 引用。
  const cached = await resolveCachedCover(url, type);
  if (cached && cached.startsWith("blob:")) {
    memoryHitRelease(type, url);
  }
};

/** 列表项最小形态：从 cover / coverSize 提取首选 url。 */
type CoverLike = {
  cover?: string;
  coverSize?: { s?: string; m?: string; l?: string; xl?: string };
};

/**
 * 从一条列表项按尺寸偏好提取 url。<br>
 * 关键：必须与实际 <s-image :src=...> 使用的 url 一致，否则 prefetch 写入和组件读取不在同一缓存条目上，浪费下载。
 *
 * - "s"（小图）：SongCard、SongList 行封面
 * - "m"（中图）：CoverList、ArtistList、Local/Streaming/HomeMobile 列表卡片
 */
const pickListCoverUrl = (item: CoverLike, sizePref: "s" | "m" = "m"): string | undefined => {
  if (sizePref === "s") return item?.coverSize?.s || item?.cover;
  return item?.coverSize?.m || item?.coverSize?.s || item?.cover;
};

/**
 * 批量后台 prefetch 列表中前 N 张封面，进入详情页 / 首页加载完成后调用。
 *
 * <p>fire-and-forget；并发由 useCoverCache 内的 inFlight 去重，不会重复请求。
 * 仅 Android 走本地缓存路径；其他平台 noop。
 *
 * @param items     列表项（含 cover / coverSize）
 * @param type      "list-covers"（默认） / "covers"
 * @param limit     预热的前 N 条；默认 20
 * @param sizePref  尺寸偏好：与 <s-image> 实际请求的尺寸保持一致才能命中
 */
export const prefetchListCovers = (
  items: readonly CoverLike[] | undefined,
  type: CoverCacheType = "list-covers",
  limit = 20,
  sizePref: "s" | "m" = "m",
): void => {
  if (!items || items.length === 0) return;
  if (!isAndroid) return;
  const max = Math.min(limit, items.length);
  for (let i = 0; i < max; i++) {
    const url = pickListCoverUrl(items[i], sizePref);
    if (url) void prefetchCoverToCache(url, type);
  }
};

/**
 * 把远端封面 url 映射为「优先本地、回退远端」的反应式 src。
 *
 * - 仅 Android 启用；其他平台直接透传原 url（项目仅安卓运行，但保留兜底）
 * - 卸载时释放 blob URL，避免内存泄漏
 *
 * @param srcRef 原始 url ref（来自 props.src 等）
 * @param type 默认 "covers"；列表场景传 "list-covers"
 * @returns 处理后的 src ref
 */
export const useCoverCache = (
  srcRef: Ref<string | undefined>,
  type: CoverCacheType = "covers",
): Ref<string | undefined> => {
  const resolved = ref<string | undefined>(srcRef.value);
  /** 当前组件 retain 的源 url 列表；释放时与缓存分类组合成内存 key。 */
  const retainedUrls: string[] = [];

  watch(
    srcRef,
    async (url, prevUrl) => {
      // 切换 src：先 release 旧 url 的引用计数
      if (prevUrl && retainedUrls.includes(prevUrl)) {
        memoryHitRelease(type, prevUrl);
        const idx = retainedUrls.indexOf(prevUrl);
        if (idx >= 0) retainedUrls.splice(idx, 1);
      }
      if (!url) {
        resolved.value = undefined;
        return;
      }
      // Capacitor WebView 禁止 <img src="file://">，先做代理转换
      if (isAndroid && (url.startsWith("file://") || url.startsWith("content://"))) {
        try {
          resolved.value = Capacitor.convertFileSrc(url);
          return;
        } catch {
          resolved.value = url;
          return;
        }
      }
      // 已是代理 URL 的直接透传，不需要再走缓存 IPC
      if (url.includes("_capacitor_file_")) {
        resolved.value = url;
        return;
      }
      // 非 http(s) 直接透传：data URI / blob URL / 其他 scheme
      if (!url.startsWith("http")) {
        resolved.value = url;
        return;
      }
      if (!isAndroid) {
        resolved.value = url;
        return;
      }
      // 列表图优先走 WebView 图片缓存，避免滚动时批量读盘和 base64 解码
      if (type === "list-covers") {
        resolved.value = url;
        return;
      }
      // 先用原 url 显示，避免等待 IPC（命中时立即升级）
      resolved.value = url;
      const cached = await resolveCachedCover(url, type);
      if (cached && srcRef.value === url) {
        resolved.value = cached;
        if (cached.startsWith("blob:")) {
          // resolveCachedCover 已 pre-retain（修复 #2），这里只需记录 url 等卸载时 release
          retainedUrls.push(url);
        } else {
          // 极少见：返回非 blob URL（透传场景），不持有引用
        }
      } else if (cached && cached.startsWith("blob:")) {
        // src 已切换：本次拿到的 blob 没用上，立即释放所有权避免泄漏
        memoryHitRelease(type, url);
      }
    },
    { immediate: true },
  );

  onBeforeUnmount(() => {
    // 释放所有 retain 的 blob URL；refCount 归零且 LRU 已超额则真正 revoke。
    for (const u of retainedUrls) memoryHitRelease(type, u);
    retainedUrls.length = 0;
  });

  return resolved;
};
