/**
 * 私人 FM
 */

import type { Track } from "@shared/types/player";
import type { PersonalFmOptions } from "@/types/netease";
import { fetchPersonalFm, submitFmTrash } from "@/apis/recommend/netease";
import { useStatusStore } from "@/stores/status";

/** 剩余曲目不足此数时后台续推，FM 单次返回约 3 首 */
const FM_PREFETCH_AHEAD = 1;

/** 已播记录容量：播种原生去重集合用 */
const FM_RECENT_MAX = 8;

let pool: Track[] = [];
/** 续推在途 promise，并发复用 */
let fetchingPromise: Promise<void> | null = null;
/** 已播 songId 环形记录（最新在尾部），供原生续池播种去重 */
let recentIds: string[] = [];

/** 当前 FM 曲目；池空返回 null */
export const current = (): Track | null => pool[0] ?? null;

/** 池是否非空 */
export const hasTracks = (): boolean => pool.length > 0;

/** 池快照（当前曲在队头），推原生 FM 队列用 */
export const snapshot = (): Track[] => pool.slice();

/** 已播 songId 记录（最新在尾部），含当前曲 */
export const recentPlayedIds = (): string[] => recentIds.slice();

const markPlayed = (track: Track): void => {
  recentIds = recentIds.filter((id) => id !== track.id);
  recentIds.push(track.id);
  if (recentIds.length > FM_RECENT_MAX) {
    recentIds = recentIds.slice(recentIds.length - FM_RECENT_MAX);
  }
};

/** 拉一批新曲目追加到池末 */
const fetchMore = (): Promise<void> => {
  if (fetchingPromise) return fetchingPromise;
  fetchingPromise = (async () => {
    try {
      const more = await fetchPersonalFm(useStatusStore().fmOptions);
      const seen = new Set(pool.map((track) => track.id));
      const fresh = more.filter((track) => !seen.has(track.id));
      if (fresh.length > 0) pool = [...pool, ...fresh];
    } catch (error) {
      console.error("[fm] 拉取失败:", error);
    } finally {
      fetchingPromise = null;
    }
  })();
  return fetchingPromise;
};

/** 剩余曲目临近阈值时后台续推 */
const maybeFetch = (): void => {
  if (pool.length <= FM_PREFETCH_AHEAD) void fetchMore();
};

/** 弹出队头并保证剩余至少一首，无可用曲目返回 null */
const advance = async (): Promise<Track | null> => {
  if (pool[0]) markPlayed(pool[0]);
  pool = pool.slice(1);
  maybeFetch();
  if (pool.length === 0) {
    await fetchMore();
    if (pool.length === 0) return null;
  }
  return current();
};

/**
 * 启动私人 FM 播放
 * @param options - 可选的 FM 模式与场景选项
 * @returns 首曲 Track 实例，无可用曲目时返回 null
 */
export const start = async (options?: PersonalFmOptions): Promise<Track | null> => {
  const status = useStatusStore();
  const currentOptions = status.fmOptions ?? { mode: "DEFAULT" };
  if (options) {
    const isDifferentMode =
      options.mode !== currentOptions.mode || options.submode !== currentOptions.submode;
    if (isDifferentMode) {
      status.fmOptions = { ...options };
      pool = [];
    }
  }
  if (pool.length === 0) await fetchMore();
  if (pool[0]) markPlayed(pool[0]);
  return current();
};

/**
 * 推进到下一首
 * @returns 下一首 Track 实例，池空时返回 null
 */
export const next = (): Promise<Track | null> => advance();

/**
 * 仅提交减少推荐（trash），不推进池。
 *
 * Android 队列推进由原生权威完成，trash 仍由 JS 提交以携带播放秒数反馈。
 * @param playedSec - 当前曲目已播放秒数，作为算法反馈
 */
export const trashCurrent = async (playedSec?: number): Promise<void> => {
  const track = current();
  if (!track) return;
  // API 失败不阻塞推进
  void submitFmTrash(track.id, playedSec).catch((error) =>
    console.error("[fm] 减少推荐失败:", error),
  );
};

/**
 * 减少推荐
 * @param playedSec - 当前曲目已播放秒数，作为算法反馈
 */
export const dislikeCurrent = async (playedSec?: number): Promise<Track | null> => {
  await trashCurrent(playedSec);
  return advance();
};

/**
 * 池对齐到指定曲目（原生 trackChanged 回同步用）：丢弃 songId 及其之前的曲目；
 * 池中不含该曲则清池重拉。
 * @returns 对齐后的当前曲目；无可用曲目返回 null
 */
export const skipTo = async (songId: string | number): Promise<Track | null> => {
  const targetId = String(songId);
  const idx = pool.findIndex((track) => track.id === targetId);
  if (idx >= 0) {
    for (let i = 0; i <= idx; i++) markPlayed(pool[i]);
    pool = pool.slice(idx);
  } else {
    // 原生后台自拉批次 JS 池没有：清池重拉一批新的推荐
    pool = [];
    await fetchMore();
    const fresh = pool.find((track) => track.id === targetId);
    if (fresh) {
      const freshIdx = pool.indexOf(fresh);
      for (let i = 0; i <= freshIdx; i++) markPlayed(pool[i]);
      pool = pool.slice(freshIdx);
    }
  }
  return current();
};
