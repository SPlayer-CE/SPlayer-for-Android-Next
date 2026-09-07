/**
 * 当前歌曲歌词加载服务
 */

import type { Track, TrackDetail } from "@shared/types/player";
import type { LyricData, LyricInput } from "@shared/types/lyrics";
import type { LyricFormat } from "@shared/types/lyrics";
import { isPlatform, type Platform } from "@shared/types/platform";
import { bestExternalIndex, detectFormat } from "@/utils/lyric/parse";
import { useMediaStore } from "@/stores/media";
import { useSettingsStore } from "@/stores/settings";
import { DEFAULT_LYRIC_FORMAT_ORDER, DEFAULT_LYRIC_SOURCE_ORDER } from "@/types/settings";
import { useCacheManager } from "@/core/resource/CacheManager";
import { isAndroid, isLanWebClient } from "@/services/bridge";
import {
  embeddedLyricFromDetail,
  isBetterFormat,
  isPluginLyricPreferred,
  resolveLocalRepoLyric,
  resolvePluginLyric,
  resolveStreamingByPreference,
  type LocalLyric,
  type OnlineResult,
  type ResolvedLyric,
} from "@/services/lyric/resolve";
import { consumePreloadedLyric } from "@/services/lyric/preload";

/** 竞态 token */
let currentToken = 0;

/**
 * 读取本地歌词
 * @param detail - 歌曲详细信息
 */
const readLocal = async (detail: TrackDetail): Promise<LocalLyric | null> => {
  const order = useSettingsStore().lyric.lyricFormatOrder ?? DEFAULT_LYRIC_FORMAT_ORDER;
  const idx = bestExternalIndex(detail.externalLyrics, order);
  if (idx !== -1) {
    const ext = detail.externalLyrics[idx];
    const result = await window.api.player.readLyricFile(ext.path);
    if (!result.success || result.data == null) return null;
    return { source: { source: "external", format: ext.format }, content: result.data };
  }

  if (isAndroid) {
    const settings = useSettingsStore();
    if (settings.system.localLyric?.enableSidecarMatch) {
      const track = useMediaStore().track;
      if (track && track.source === "local" && track.path) {
        const artistsStr = track.artists.map((a) => a.name).join(",");
        if (window.api.lyrics.findSidecarLyric) {
          const sidecar = await window.api.lyrics.findSidecarLyric(
            track.path,
            track.title,
            artistsStr,
          );
          if (sidecar) {
            return {
              source: { source: "external", format: sidecar.format as LyricFormat },
              content: sidecar.content,
            };
          }
        }
      }
    }
  }

  if (detail.embeddedLyric) {
    return {
      source: { source: "embedded", format: detectFormat(detail.embeddedLyric) },
      content: detail.embeddedLyric,
    };
  }
  return null;
};

/** 歌词缓存是否启用 */
const isLyricCacheEnabled = (): boolean =>
  isAndroid && useSettingsStore().system.cache?.enabled === true;

/**
 * 读取歌词缓存
 * @param key - 缓存 key
 */
const readLyricCache = async (key: string): Promise<OnlineResult | null> => {
  try {
    const result = await useCacheManager().get("lyrics", key);
    if (!result.success || !result.data) return null;
    const json = new TextDecoder().decode(result.data);
    return JSON.parse(json) as OnlineResult;
  } catch {
    return null;
  }
};

/**
 * 写入歌词缓存（fire-and-forget）
 * @param key - 缓存 key
 * @param data - 缓存内容
 */
const writeLyricCache = (key: string, data: OnlineResult): void => {
  useCacheManager()
    .set("lyrics", key, JSON.stringify(data))
    .catch(() => {});
};

/**
 * 向指定平台请求歌词
 * track.source 等于目标平台时优先走 byId，酷狗需要曲名和时长参与匹配
 */
const fetchFromPlatform = async (
  platform: Platform,
  track: Track,
): Promise<OnlineResult | null> => {
  const cacheKey = `${platform}_${track.id}.json`;
  // 缓存命中：跳过网络请求
  if (isLyricCacheEnabled()) {
    const cached = await readLyricCache(cacheKey);
    if (cached) return cached;
  }
  const mode = track.source === platform && platform !== "kugou" ? "byId" : "byQuery";
  // QM lyric 接口要数字 songID
  const lookupId = platform === "qqmusic" ? (track.extId ?? track.id) : track.id;
  const resp =
    mode === "byId"
      ? await window.api.lyrics.matchById(platform, lookupId)
      : await window.api.lyrics.matchByQuery(platform, track);
  // apiFetch 的 res.json() 类型为 any，防御性检查 null/undefined
  if (!resp || !resp.ok || !resp.data) return null;
  const data = resp.data;
  const result: OnlineResult = {
    source: { source: "online", format: data.format, platform: data.platform },
    input: {
      content: data.content,
      translation: data.translation,
      translationFormat: data.translationFormat,
      romaji: data.romaji,
      romajiFormat: data.romajiFormat,
    },
  };
  // 写入缓存（fire-and-forget）
  if (isLyricCacheEnabled()) writeLyricCache(cacheKey, result);
  return result;
};

/** 平台主格式可达列表 */
const PLATFORM_MAIN_FORMATS: Record<Platform, LyricFormat[]> = {
  netease: ["yrc", "lrc"],
  qqmusic: ["qrc", "lrc"],
  kugou: ["krc", "lrc"],
};

/**
 * 判断在指定平台是否能拿到比本地更优的主格式
 * 用于「智能选择 - 优先在线」预筛
 * @param platform - 平台
 * @param localFormat - 本地格式
 * @param formatOrder - 格式优先级
 */
const platformCanUpgrade = (
  platform: Platform,
  localFormat: LyricFormat,
  formatOrder: readonly LyricFormat[],
): boolean => {
  const localIdx = formatOrder.indexOf(localFormat);
  if (localIdx === -1) return true;
  for (const f of PLATFORM_MAIN_FORMATS[platform] ?? []) {
    const idx = formatOrder.indexOf(f);
    if (idx !== -1 && idx < localIdx) return true;
  }
  return false;
};

/**
 * 单次在线结果是否真的优于本地
 * @param result - 在线结果
 * @param localFormat - 本地格式
 */
const isOnlineResultUpgrade = (result: OnlineResult, localFormat: LyricFormat): boolean => {
  const settings = useSettingsStore();
  const formatOrder = settings.lyric.lyricFormatOrder ?? DEFAULT_LYRIC_FORMAT_ORDER;
  const localIdx = formatOrder.indexOf(localFormat);
  if (localIdx === -1) return true;
  const mainIdx = formatOrder.indexOf(result.source.format);
  return mainIdx !== -1 && mainIdx < localIdx;
};

/**
 * 提交歌词
 * @param token - 竞态 token
 * @param source - 歌词源
 * @param input - 歌词内容
 */
const commit = (token: number, source: LyricData, input: LyricInput | null): void => {
  if (token !== currentToken) return;
  useMediaStore().setLyric(source, input);
};

/** 提交本地歌词 */
const commitLocal = (token: number, local: LocalLyric): void => {
  commit(token, local.source, { content: local.content });
};

/**
 * 提交歌词并返回解析是否有效
 * @param token - 竞态 token
 * @param source - 歌词源
 * @param input - 歌词内容
 */
const commitAndHasParsed = (
  token: number,
  source: NonNullable<LyricData>,
  input: LyricInput,
): boolean => {
  commit(token, source, input);
  if (token !== currentToken) return false;
  return useMediaStore().parsedLyric.length > 0;
};

/** 提交已解析歌词候选并返回是否有效 */
const commitResolvedAndHasParsed = (token: number, resolved: ResolvedLyric): boolean =>
  commitAndHasParsed(token, resolved.source, resolved.input);

/**
 * TTML 异步升级
 * @param token - 竞态 token
 * @param track - 歌曲信息
 */
const tryTTMLOverlay = async (token: number, track: Track): Promise<void> => {
  const settings = useSettingsStore();
  // 用户未开启在线 TTML，或 self 模式不跨平台，直接跳过
  if (!settings.system.lyric.enableOnlineTTMLLyric) return;
  if (settings.lyric.lyricSourcePreference === "self") return;
  const formatOrder = settings.lyric.lyricFormatOrder ?? DEFAULT_LYRIC_FORMAT_ORDER;
  if (formatOrder.indexOf("ttml") === -1) return;
  const order = settings.lyric.lyricSourceOrder ?? DEFAULT_LYRIC_SOURCE_ORDER;
  const candidates = order.filter(
    (p): p is "netease" | "qqmusic" => p === "netease" || p === "qqmusic",
  );
  if (candidates.length === 0) return;
  const cacheEnabled = isLyricCacheEnabled();
  const cacheManager = useCacheManager();
  // 先尝试缓存命中
  if (cacheEnabled) {
    for (const platform of candidates) {
      const ttmlKey = `${platform}_${track.id}.ttml.json`;
      try {
        const cached = await cacheManager.get("lyrics", ttmlKey);
        if (cached.success && cached.data) {
          const content = new TextDecoder().decode(cached.data);
          if (content) {
            commit(token, { source: "online", format: "ttml", platform }, { content });
            return;
          }
        }
      } catch {
        /* 缓存读取失败，继续走网络 */
      }
    }
  }
  // 决定请求顺序：优先尝试 track 所在平台
  const primaryPlatform =
    track.source === "netease" || track.source === "qqmusic" ? track.source : candidates[0];
  const sortedCandidates = [
    primaryPlatform,
    ...candidates.filter((p) => p !== primaryPlatform),
  ] as ("netease" | "qqmusic")[];

  for (const platform of sortedCandidates) {
    const resp = await window.api.lyrics.fetchTTMLOverlay(track, platform);
    if (token !== currentToken) return;
    if (resp && resp.ok && resp.data) {
      // 写入 TTML 缓存（fire-and-forget）
      if (cacheEnabled) {
        const ttmlKey = `${platform}_${track.id}.ttml.json`;
        cacheManager.set("lyrics", ttmlKey, resp.data).catch(() => {});
      }
      commit(token, { source: "online", format: "ttml", platform }, { content: resp.data });
      return;
    }
  }
};

/**
 * 获取在线歌词
 * - self：本地歌曲不走第三方；在线歌曲查自家平台
 * - auto + 已有本地：默认不走；smartPreferOnline 开启时按格式优先级筛选可升级平台
 * - auto + 无本地：默认首个命中即返回；smartPreferOnline 开启时按 lyricFormatOrder 跨平台取格式最优
 * - 指定平台：查该平台
 * @param token - 竞态 token
 * @param track - 歌曲信息
 * @param hasLocal - 是否有本地歌词
 * @param localFormat - 本地歌词格式
 * @returns 在线歌词结果
 */
const tryOnlineByPreference = async (
  token: number,
  track: Track,
  hasLocal: boolean,
  localFormat: LyricFormat | null,
): Promise<OnlineResult | null> => {
  const settings = useSettingsStore();
  const preference = settings.lyric.lyricSourcePreference;
  if (preference === "self") {
    // 在线歌曲
    if (isPlatform(track.source)) {
      return fetchFromPlatform(track.source, track);
    }
    return null;
  }
  if (preference === "auto") {
    const order = settings.lyric.lyricSourceOrder ?? DEFAULT_LYRIC_SOURCE_ORDER;
    const formatOrder = settings.lyric.lyricFormatOrder ?? DEFAULT_LYRIC_FORMAT_ORDER;
    let candidates: Platform[] = [...order];
    if (hasLocal) {
      if (!settings.lyric.smartPreferOnline || !localFormat) return null;
      candidates = order.filter((p) => platformCanUpgrade(p, localFormat, formatOrder));
      if (candidates.length === 0) return null;
    }
    // smart：并行拉所有候选，谁先回有内容就先 commit，后到的 rank 更高才替换
    if (settings.lyric.smartPreferOnline) {
      let best: OnlineResult | null = null;
      const localIdx = hasLocal && localFormat ? formatOrder.indexOf(localFormat) : -1;
      let bestRank = localIdx === -1 ? Infinity : localIdx;
      await Promise.all(
        candidates.map(async (platform) => {
          const result = await fetchFromPlatform(platform, track);
          if (token !== currentToken || !result) return;
          const idx = formatOrder.indexOf(result.source.format);
          const rank = idx === -1 ? Infinity : idx;
          if (rank < bestRank) {
            best = result;
            bestRank = rank;
            commit(token, result.source, result.input);
          }
        }),
      );
      if (token !== currentToken) return null;
      return best;
    }
    // 其它：按音源顺序首个有效即返回
    for (const platform of candidates) {
      const result = await fetchFromPlatform(platform, track);
      if (token !== currentToken) return null;
      if (!result) continue;
      if (hasLocal && localFormat && !isOnlineResultUpgrade(result, localFormat)) continue;
      return result;
    }
    return null;
  }
  return fetchFromPlatform(preference, track);
};

/**
 * 提交在线歌词；解析后为空时优先回退本地，本地也无再按需 TTML 升级
 */
const applyOnline = async (
  token: number,
  track: Track,
  online: OnlineResult,
  fallbackLocal: LocalLyric | null,
): Promise<void> => {
  const media = useMediaStore();
  const current = media.activeLyric;
  const alreadyCommitted =
    current?.source === "online" &&
    current.platform === online.source.platform &&
    current.format === online.source.format;
  if (!alreadyCommitted) {
    if (!commitAndHasParsed(token, online.source, online.input) && fallbackLocal) {
      commitLocal(token, fallbackLocal);
      return;
    }
    if (token !== currentToken) return;
  } else if (media.parsedLyric.length === 0 && fallbackLocal) {
    commitLocal(token, fallbackLocal);
    return;
  }
  await tryTTMLOverlay(token, track);
};

/**
 * 本地 TTML 歌词库匹配：命中即以最高优先级提交，调用方据此跳过在线请求
 * @param token - 竞态 token
 * @param track - 歌曲信息
 * @returns 是否命中
 */
const tryLocalRepo = async (token: number, track: Track): Promise<boolean> => {
  const resolved = await resolveLocalRepoLyric(track);
  if (token !== currentToken) return false;
  if (resolved && commitResolvedAndHasParsed(token, resolved)) {
    return true;
  }
  return false;
};

/**
 * 插件兜底匹配歌词：内置平台都没歌词时，向声明 musicLyric 的插件源逐个兜底
 * @param token - 竞态 token
 * @param track - 歌曲信息
 * @returns 是否已提交有效歌词
 */
const tryPluginFallback = async (token: number, track: Track): Promise<boolean> => {
  // 插件优选时不处理
  if (isPluginLyricPreferred()) return false;
  const resolved = await resolvePluginLyric(track);
  if (token !== currentToken) return false;
  return resolved ? commitResolvedAndHasParsed(token, resolved) : false;
};

/**
 * 插件优先加载
 * 插件请求与正常流程并发发出，正常流程先展示，插件返回更优格式时替换
 * @param token - 竞态 token
 * @param track - 歌曲信息
 * @param run - 正常加载流程
 */
const withPluginPrefer = async (
  token: number,
  track: Track,
  run: () => Promise<void>,
): Promise<void> => {
  if (!isPluginLyricPreferred()) {
    await run();
    return;
  }
  const pluginTask = resolvePluginLyric(track);
  await run();
  const plugin = await pluginTask;
  if (!plugin || token !== currentToken) return;
  const currentFormat = useMediaStore().activeLyric?.format ?? null;
  if (isBetterFormat(plugin.source.format, currentFormat)) {
    commitResolvedAndHasParsed(token, plugin);
  }
};

/**
 * 流媒体歌词加载：按来源偏好解析，失败后使用插件和内嵌歌词兜底
 * @param token - 竞态 token
 * @param track - 歌曲信息
 * @param detail - 歌曲详细信息
 */
const loadStreamingLyric = (
  token: number,
  track: Track,
  detail: TrackDetail | null,
): Promise<void> =>
  withPluginPrefer(token, track, async () => {
    const resolved = await resolveStreamingByPreference(track, () => token === currentToken);
    if (token !== currentToken) return;
    const embeddedFallback = embeddedLyricFromDetail(detail);
    if (resolved && commitResolvedAndHasParsed(token, resolved)) return;
    if (token !== currentToken) return;
    if (await tryPluginFallback(token, track)) return;
    if (embeddedFallback) {
      commit(token, embeddedFallback.source, { content: embeddedFallback.content });
    } else {
      commit(token, null, null);
    }
  });

/**
 * 在线平台歌曲歌词加载
 * @param token - 竞态 token
 * @param track - 歌曲信息
 */
const loadPlatformLyric = (token: number, track: Track): Promise<void> =>
  withPluginPrefer(token, track, async () => {
    const online = await tryOnlineByPreference(token, track, false, null);
    if (token !== currentToken) return;
    if (online) await applyOnline(token, track, online, null);
    else if (!(await tryPluginFallback(token, track))) commit(token, null, null);
  });

/** 开启新一轮加载周?*/
export const beginLoad = (): number => {
  currentToken++;
  // LAN 从设备：歌词由主机经 getLyric 推送，跳过本机重置，避免切歌瞬间歌词闪空
  if (!isLanWebClient()) {
    useMediaStore().resetLyricState();
  }
  return currentToken;
};

/**
 * 为当?track 加载歌词
 *
 * 1. ?track：commit null 收尾
 * 2. 在线歌曲? *    - 默认顺序下，track.platform 与候选平台一致时?matchById
 *    - 不一致则?matchByQuery
 * 3. 本地歌曲：本地有先立?commit 显示；再按偏好查在线，命中热替换
 * 4. 本地 + 在线都无：commit null 收尾 loading
 *
 * @param detail - 歌曲详细信息
 */
export const loadForTrack = async (detail: TrackDetail | null): Promise<void> => {
  const token = beginLoad();
  // LAN 从设备：歌词由主机推送，本机不解析（在线/本地歌词链路在 LAN 客户端均不可用）
  if (isLanWebClient()) return;
  try {
    const media = useMediaStore();
    const track = media.track;
    // ?track
    if (!track) {
      commit(token, null, null);
      return;
    }
    const preloaded = await consumePreloadedLyric(track);
    if (token !== currentToken) return;
    if (preloaded.hit) {
      if (commitResolvedAndHasParsed(token, preloaded.lyric)) return;
    }
    // 本地 TTML 歌词库最高优先
    if (await tryLocalRepo(token, track)) return;
    if (token !== currentToken) return;
    // 在线歌曲（任一在线平台时?
    if (isPlatform(track.source)) {
      await loadPlatformLyric(token, track);
      return;
    }
    // 流媒体服务器
    if (track.source === "streaming") {
      await loadStreamingLyric(token, track, detail);
      return;
    }
    // 本地文件
    const local = detail ? await readLocal(detail) : null;
    if (token !== currentToken) return;
    // 本地立即显示
    if (local) commitLocal(token, local);
    // 本地文件存在但解析后为空?
    const hasUsableLocal = !!local && media.parsedLyric.length > 0;
    const localFormat = local?.source.format ?? null;

    await withPluginPrefer(token, track, async () => {
      // 按偏好获取歌词
      const online = await tryOnlineByPreference(token, track, hasUsableLocal, localFormat);
      if (token !== currentToken) return;
      // id 回查本地 TTML 库
      if (online && (await tryLocalRepo(token, track))) return;
      if (online) {
        await applyOnline(token, track, online, local);
      } else if (!hasUsableLocal && !(await tryPluginFallback(token, track))) {
        commit(token, null, null);
      }
    });
  } catch (err) {
    console.error("[lyricLoader] loadForTrack failed:", err);
    commit(token, null, null);
  }
};

/** 偏好变化时的刷新 */
const refreshPreference = async (): Promise<void> => {
  currentToken++;
  const token = currentToken;
  const media = useMediaStore();
  const track = media.track;
  if (!track) return;
  // 本地 TTML 歌词库最高优?
  if (await tryLocalRepo(token, track)) return;
  if (token !== currentToken) return;
  if (track.source === "streaming") {
    await loadStreamingLyric(token, track, media.detail);
    return;
  }
  // 在线歌曲（任一在线平台时?
  if (isPlatform(track.source)) {
    await loadPlatformLyric(token, track);
    return;
  }
  // 本地歌曲
  const detail = media.detail;
  const local = detail ? await readLocal(detail) : null;
  if (token !== currentToken) return;
  const localFormat = local?.source.format ?? null;
  const showingOnline = media.activeLyric?.source === "online";

  await withPluginPrefer(token, track, async () => {
    // 按偏好获取歌词
    const online = await tryOnlineByPreference(token, track, !!local, localFormat);
    if (token !== currentToken) return;
    if (online) {
      await applyOnline(token, track, online, local);
      return;
    }
    // 目标是本地
    if (!showingOnline) return;
    if (local) commitLocal(token, local);
    else commit(token, null, null);
  });
};

/** 监听歌词偏好变化 */
export const watchLyricPreference = (): void => {
  const settings = useSettingsStore();
  watch(
    () => [
      settings.lyric.lyricSourcePreference,
      settings.lyric.lyricSourceOrder.join("|"),
      settings.lyric.lyricFormatOrder.join("|"),
      settings.lyric.smartPreferOnline,
      settings.lyric.preferPluginLyric,
      settings.lyric.detectBackgroundLyrics,
      settings.system.lyric.enableOnlineTTMLLyric,
      settings.system.localLyric.enableSidecarMatch,
      settings.system.localLyric.enableLocalTTMLOverride,
      settings.system.localLyric.repoDir,
    ],
    () => {
      refreshPreference();
    },
  );
};
