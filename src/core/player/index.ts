import type { PlaybackContext, Track } from "@shared/types/player";
import type { TagEditRequest, TagWriteOutcome } from "@shared/types/tagEditor";
import type { PersonalFmOptions } from "@/types/netease";
import { handleEvent } from "./events";
import type { RepeatMode, ShuffleMode } from "@/stores/status";
import { useMediaStore } from "@/stores/media";
import { useSettingsStore } from "@/stores/settings";
import { useStatusStore } from "@/stores/status";
import { useStreamingStore } from "@/stores/streaming";
import { usePluginsStore } from "@/stores/plugins";
import { useHistoryStore } from "@/stores/history";
import { useLibraryStore } from "@/stores/library";
import * as queue from "@/stores/queue";
import * as fm from "./fm";
import * as playback from "@/services/playback";
import * as lyricLoader from "@/services/lyric/loader";
import * as coverLoader from "@/services/coverLoader";
import * as abLoop from "@/services/abLoop";
import * as cacheScheduler from "@/services/cacheScheduler";
import { resolveTrackSource, type ResolvedTrackSource } from "@/services/audioSource";
import {
  consumePreloadedTrack,
  disposeNextTrackPreload,
  installNextTrackPreloadWatchers,
  scheduleNextTrackPreload,
} from "@/services/nextTrackPreloader";
import { installPlayStats } from "./stats";
import { useFavorite } from "@/composables/useFavorite";
import { mediaSessionManager } from "./MediaSessionManager";
import { extractColorFromUrl } from "@/utils/color";
import { handleError, isSkippableError } from "@/utils/errors";
import { ErrorCode } from "@shared/types/errors";
import { shouldSkipDjTrack } from "@/utils/preset/djMode";
import { toast } from "@/composables/useToast";
import i18n from "@/i18n";
import bridge, {
  ensureNotificationPermission,
  isAndroid,
  isAndroidNative,
} from "@/services/bridge";
import { isLanSyncReceiver } from "@/composables/useLanSyncRole";
import {
  dispatchLanRemoteCommand,
  isApplyingRemoteState,
  reportHostManualAction,
  type LanRemoteAction,
} from "@/composables/lanSyncRemote";

/**
 * 局域网协同接收端遥控拦截：本机为从设备且非"应用主机状?期间时，
 * 把用户控制指令上送主机执行，本地不直接操作播放器（随后由主机广播回灌镜像）? * @returns 是否已被遥控接管（true 则调用方应直?return? */
const tryRemoteControl = (action: LanRemoteAction, value?: number): boolean => {
  if (isApplyingRemoteState()) return false;
  if (isLanSyncReceiver()) {
    return dispatchLanRemoteCommand(action, value);
  }
  reportHostManualAction(action, value);
  return false;
};

/** 加载运行时选项 */
interface LoadRuntimeOptions {
  /** 是否抑制错误提示 */
  suppressErrorToast?: boolean;
  /** 本次播放的来源上下文 */
  context?: PlaybackContext;
}

/** 单次音源兜底过程的重试状?*/
interface SourceRetryState {
  /** 是否已放弃官方在线接口，后续仅尝试插?*/
  skipOfficialOnline: boolean;
  /** 已尝试且需跳过的插?ID */
  skippedPluginIds: Set<string>;
}

/**
 * 单次解析并加载音源的结果
 * - loaded: 已拿到解析结果，并完成一?load（成功或失败都算? * - unresolved: 当前重试策略下没有可用音? * - cancelled: 加载期间被新的切?重载请求接管
 */
type LoadSourceResult =
  | { status: "loaded"; result: LoadOutcome; resolved: ResolvedTrackSource }
  | { status: "unresolved" }
  | { status: "cancelled" };

/** 引擎 load 竞?token */
let loadToken = 0;
/** loadTrack 竞?token */
let trackToken = 0;
/** 连续加载失败计数，成功时重置 */
let consecutiveFailures = 0;
/** 连续失败硬上?*/
const MAX_CONSECUTIVE_FAILURES = 5;
/** 失败后跳下一首的节流延迟（毫秒） */
const SKIP_ON_ERROR_DELAY_MS = 1000;

/**
 * 单曲级失败兜? * 达到连续失败上限 / 队列长度则交 onQueueEnded 停下
 * @param myToken - 调用方进入失败路径时?token 快照
 * @param getCurrentToken - 取该 token 的最新值，setTimeout 触发时再次比? */
const skipOnFailure = async (myToken: number, getCurrentToken: () => number): Promise<void> => {
  consecutiveFailures++;
  if (
    consecutiveFailures >= MAX_CONSECUTIVE_FAILURES ||
    consecutiveFailures >= queue.queueLength.value
  ) {
    const reachedMax = consecutiveFailures >= MAX_CONSECUTIVE_FAILURES;
    consecutiveFailures = 0;
    await onQueueEnded();
    if (reachedMax) {
      handleError(ErrorCode.MAX_CONSECUTIVE_FAILURES);
    }
    return;
  }
  setTimeout(() => {
    if (myToken === getCurrentToken()) nextTrack();
  }, SKIP_ON_ERROR_DELAY_MS);
};

/** load() 的结果；失败时附带错误码，跳曲决策交给调用方 */
export type LoadOutcome = { ok: true; track: Track | null } | { ok: false; error?: string };

/**
 * 切歌通用前置
 * @param duration 新歌时长（毫秒），未知时?0
 */
const resetForLoad = (duration: number): void => {
  const status = useStatusStore();
  status.trackLoading = true;
  status.position = 0;
  status.duration = duration;
  playback.setCurrentTime(0, { force: true });
  playback.setDuration(duration);
  playback.setPlaying(false);
  // 上一首未达到缓存触发阈值的请求丢弃
  cacheScheduler.cancel();
};

/**
 * 加载音频? * @param source - 音频文件路径或网络地址
 * @param autoPlay - 是否自动播放
 * @param meta - 渲染层下发给主进程的权威 Track（用?SMTC/托盘。? * @param options - 加载时的内部控制? */
export const load = async (
  source: string,
  autoPlay = true,
  meta?: Track,
  options: LoadRuntimeOptions = {},
): Promise<LoadOutcome> => {
  const status = useStatusStore();
  const token = ++loadToken;
  // 切歌即清?AB 循环（per-song 状态）
  abLoop.reset();
  // 清除上一?seek 残留
  seekTarget = null;
  playback.setSeeking(false);
  resetForLoad(meta?.duration ?? 0);
  // 非本地并行歌词与取色
  const isOnline = meta?.source !== "local";
  if (isOnline) {
    void lyricLoader.loadForTrack(null);
    extractColorFromUrl(meta?.cover ?? meta?.coverOriginal ?? null);
    if (meta) void coverLoader.loadCoverForTrack(meta);
  }
  // Android：在 load 前推送元数据，让通知栏首次显示即有正确歌曲信?
  if (isAndroid) void mediaSessionManager.updateMetadata();
  try {
    const result = await bridge.player.load(source, {
      autoPlay,
      meta,
      context: options.context,
    });
    // 竞态保护
    if (token !== loadToken) return { ok: false };
    if (result.success && result.data) {
      const { detail, mediaInfo } = result.data;
      consecutiveFailures = 0;
      // 首次播放时请求通知权限（Android 13+）?
      void ensureNotificationPermission();
      const media = useMediaStore();
      // 把引擎提取的 mediaInfo 与已?Track 合并；身份字段保?
      media.enrichTrack(mediaInfo, detail);
      const enriched = media.track;
      // 更新队列中的 Track 元数据
      if (enriched) {
        queue.updateQueueTracks([enriched]);
      }
      // 避免重复请求
      if (!isOnline) {
        lyricLoader.loadForTrack(detail);
        extractColorFromUrl(enriched?.cover ?? null);
        if (enriched) void coverLoader.loadCoverForTrack(enriched);
      }
      const dur = enriched?.duration ?? mediaInfo.duration;
      status.duration = dur;
      status.state = autoPlay ? "playing" : "paused";
      status.currentSource = source;
      playback.setDuration(dur);
      playback.setPlaying(autoPlay);
      // Android：推送元数据 + 队列窗口到原?MediaSession
      if (isAndroid) {
        void mediaSessionManager.updateMetadata();
        void mediaSessionManager.syncAndroidPlaybackContext();
      }
      return { ok: true, track: enriched };
    }
    status.state = "idle";
    lyricLoader.loadForTrack(null);
    if (result.error && !options.suppressErrorToast) handleError(result.error);
    return { ok: false, error: result.error };
  } catch (_error) {
    if (token !== loadToken) return { ok: false };
    status.state = "idle";
    lyricLoader.loadForTrack(null);
    const code =
      source.startsWith("http://") || source.startsWith("https://")
        ? ErrorCode.NETWORK_ERROR
        : ErrorCode.FILE_DECODE_ERROR;
    handleError(code);
    return { ok: false, error: code };
  } finally {
    if (token === loadToken) status.trackLoading = false;
  }
};

/** 创建音源重试状?*/
const createSourceRetryState = (): SourceRetryState => ({
  skipOfficialOnline: false,
  skippedPluginIds: new Set<string>(),
});

/**
 * 根据当前重试状态解析音? * @param track - 要解析的 Track
 * @param retry - 当前重试状? */
const resolveTrackSourceWithRetry = (
  track: Track,
  retry: SourceRetryState,
): Promise<ResolvedTrackSource | null> =>
  resolveTrackSource(track, {
    skipOfficialOnline: retry.skipOfficialOnline,
    skipPluginIds: [...retry.skippedPluginIds],
  });

/**
 * 记录可继续兜底的音源失败
 * @param resolved - 本次加载使用的音? * @param retry - 当前重试状? * @returns 是否还有可尝试的后续音源
 */
const markRetryableSourceFailure = (
  resolved: ResolvedTrackSource,
  retry: SourceRetryState,
): boolean => {
  if (resolved.provider === "official" && !retry.skipOfficialOnline) {
    retry.skipOfficialOnline = true;
    return true;
  }
  if (resolved.provider === "plugin" && resolved.pluginId) {
    retry.skippedPluginIds.add(resolved.pluginId);
    return true;
  }
  return false;
};

const shouldSuppressLoadError = (resolved: ResolvedTrackSource): boolean =>
  resolved.provider === "official" || resolved.provider === "plugin";

/**
 * 将解析到的实际音质回填到 Track（仅网易云），用于 UI 展示与历史记录
 * @param track - 原始 Track
 * @param resolved - 音源解析结果
 * @returns 回填音质后的 Track；无变化时原样返回
 */
const withResolvedQuality = (track: Track, resolved: ResolvedTrackSource): Track =>
  resolved.playbackQuality && track.source === "netease"
    ? { ...track, quality: resolved.playbackQuality }
    : track;

/**
 * 解析并加载 Track，遇到可兜底的音源失败时继续尝试下一个来源
 * @param track - 要加载的 Track
 * @param context - 本次播放的来源上下文
 * @param autoPlay - 是否自动播放
 * @param shouldContinue - 竞态检查，返回 false 时放弃本轮加载
 * @param retryOnAnyFailure - 是否对任意加载失败继续换源
 */
const loadTrackSourceWithFallback = async (
  track: Track,
  context: PlaybackContext | undefined,
  autoPlay: boolean,
  shouldContinue: () => boolean,
  retryOnAnyFailure = false,
  initialResolved?: ResolvedTrackSource | null,
): Promise<LoadSourceResult> => {
  const retry = createSourceRetryState();
  let firstTry = initialResolved ?? null;
  while (true) {
    const usingInitial = firstTry !== null;
    const resolved = firstTry ?? (await resolveTrackSourceWithRetry(track, retry));
    firstTry = null;
    if (!shouldContinue()) return { status: "cancelled" };
    if (!resolved) return { status: "unresolved" };
    // 实际音质与请求档位不同时先回填，保证引擎元数据与 UI 一致
    const playbackTrack = withResolvedQuality(track, resolved);
    if (playbackTrack !== track) useMediaStore().setTrack(playbackTrack);
    const result = await load(resolved.source, autoPlay, playbackTrack, {
      suppressErrorToast: usingInitial || shouldSuppressLoadError(resolved),
      context,
    });
    if (!shouldContinue()) return { status: "cancelled" };
    // 预载 URL 可能已经过期，非本地来源失败后重新解析一次最新地址
    if (
      usingInitial &&
      !result.ok &&
      resolved.provider !== "local" &&
      resolved.provider !== "cache"
    ) {
      continue;
    }
    const canRetry =
      !result.ok &&
      (retryOnAnyFailure || Boolean(result.error && isSkippableError(result.error))) &&
      markRetryableSourceFailure(resolved, retry);
    if (canRetry) continue;
    return { status: "loaded", result, resolved };
  }
};

/**
 * 加载指定 Track 到播放器
 * 乐观更新：立即显示歌曲信息，快速切歌时只有最后一次 load 生效
 * @param track - 要播放的 Track，为 null 时忽略
 * @param context - 本次播放的来源上下文
 */
const loadTrack = async (track: Track | null, context?: PlaybackContext): Promise<void> => {
  if (!track) return;
  // Fuck DJ Mode
  const settings = useSettingsStore();
  if (settings.preset.fuckDjMode && shouldSkipDjTrack(track)) {
    await nextTrack();
    return;
  }
  const myToken = ++trackToken;
  loadToken++;
  const status = useStatusStore();
  // 乐观更新
  const media = useMediaStore();
  media.setTrack(track);
  media.setPlaybackContext(context);
  lyricLoader.beginLoad();
  resetForLoad(track.duration ?? 0);
  // Android：播放权威在原生。推全量队列上下文后由原生解析并开播，JS 不做音源解析
  if (isAndroidNative) {
    const isOnline = track.source !== "local";
    if (isOnline) {
      void lyricLoader.loadForTrack(null);
      extractColorFromUrl(track.cover ?? track.coverOriginal ?? null);
      void coverLoader.loadCoverForTrack(track);
    }
    // 换歌后 currentSource 仍是上一首的 URL 快照，必须清空：否则 buildAndroidQueueTracks
    // 会把它当作新曲的 knownUrl，导致原生直接播放上一首（点 A 播 B）
    status.currentSource = null;
    try {
      await mediaSessionManager.syncAndroidPlaybackContext();
      if (myToken !== trackToken) return;
      await bridge.android.playIndex(status.fmMode ? 0 : status.playIndex);
    } catch (error) {
      console.warn("[player] android native load failed", error);
      if (myToken === trackToken) status.trackLoading = false;
    }
    return;
  }
  // 消费预载结果
  const preloaded = consumePreloadedTrack(track);
  try {
    await bridge.player.stop();
  } catch (error) {
    console.warn("[player] stop before load failed", error);
  }
  if (myToken !== trackToken) return;
  // 是否可跳曲
  let shouldSkip = false;
  try {
    const loaded = await loadTrackSourceWithFallback(
      track,
      context,
      true,
      () => myToken === trackToken,
      false,
      preloaded?.source,
    );
    if (loaded.status === "cancelled") return;
    if (loaded.status === "unresolved") {
      const status = useStatusStore();
      status.currentSource = null;
      status.state = "idle";
      void bridge.player.stop();
      useMediaStore().setLyric(null, null);
      shouldSkip = true;
    } else {
      const { result, resolved } = loaded;
      if (!result.ok && result.error && isSkippableError(result.error)) {
        handleError(result.error);
        shouldSkip = true;
      } else if (result.ok) {
        // 用户主动触发的成功播放记入历史；initPlayer 的恢复路径走 load() 不经此处
        void useHistoryStore().record(withResolvedQuality(track, resolved));
        if (resolved.cacheRequest) {
          cacheScheduler.schedule(track.id, resolved.cacheRequest);
        }
        scheduleNextTrackPreload();
      } else if (result.error) {
        handleError(result.error);
      }
    }
  } finally {
    if (myToken === trackToken) {
      const status = useStatusStore();
      if (shouldSkip || status.state !== "playing") {
        status.trackLoading = false;
      }
    }
  }
  if (shouldSkip) await skipOnFailure(myToken, () => trackToken);
};

/**
 * 热替换当前播放
 * 切换在线音质、或在线 URL 过期时调用：重新解析 URL 并从当前进度续播
 * @param forcePlay - 重载后强制播放
 * @returns 是否完成（成功或被更新的加载接管）；解析/加载失败返回 false，调用方据此决定是否跳曲
 */
export const reloadCurrentTrack = async (forcePlay?: boolean): Promise<boolean> => {
  const media = useMediaStore();
  const track = media.track;
  if (!track || track.source === "local") return false;
  const status = useStatusStore();
  const shouldPlay = forcePlay ?? status.isPlaying;
  const resumePosition = Math.round(playback.getCurrentTime());
  // 抢占加载令牌，与 loadTrack 互相取消
  const myToken = ++trackToken;
  loadToken++;
  // resolveTrackSource 联网解析较慢，先置加载态，让播放键立即给出反馈
  status.trackLoading = true;
  // Android：原生重载——刷新 API 上下文清空解析缓存后按索引重播并恢复进度
  if (isAndroidNative) {
    try {
      await mediaSessionManager.syncAndroidApiContext(true);
      if (myToken !== trackToken) return true;
      await bridge.android.playIndex(status.fmMode ? 0 : status.playIndex, resumePosition);
      if (!shouldPlay) await pause();
      return true;
    } catch (error) {
      console.warn("[player] android native reload failed", error);
      status.trackLoading = false;
      return false;
    }
  }
  // helper 以暂停态完成单次加载（含实际音质回填），seek 回原进度后再决定是否播放
  const loaded = await loadTrackSourceWithFallback(
    track,
    media.playbackContext,
    false,
    () => myToken === trackToken,
    true,
  );
  // 被更新的加载接管：由它负责结果，不算本次失败
  if (myToken !== trackToken) return true;
  if (loaded.status === "cancelled") return true;
  if (loaded.status === "unresolved") {
    status.trackLoading = false;
    return false;
  }
  if (!loaded.result.ok) return false;
  if (resumePosition > 0) await seek(resumePosition);
  if (shouldPlay) await play();
  if (loaded.resolved.cacheRequest) {
    cacheScheduler.schedule(track.id, loaded.resolved.cacheRequest);
  }
  return true;
};

/** 同一首歌因源失效连续重载的次数，换歌时归?*/
let sourceRecoveryCount = 0;
/** sourceRecoveryCount 对应?track id */
let sourceRecoveryTrackId: string | null = null;

/**
 * 源失效恢复。? * 重载一次后仍失败则放弃跳曲
 */
export const recoverFromSourceFailure = async (): Promise<void> => {
  const track = useMediaStore().track;
  if (!track) return;
  // 本地文件源失效（文件被删/磁盘错误）没有重载意义，直接跳曲
  if (track.source === "local") {
    await nextTrack();
    return;
  }
  if (sourceRecoveryTrackId !== track.id) {
    sourceRecoveryTrackId = track.id;
    sourceRecoveryCount = 0;
  }
  // 最多重载一?
  if (sourceRecoveryCount >= 1) {
    sourceRecoveryCount = 0;
    await nextTrack();
    return;
  }
  sourceRecoveryCount++;
  // 重载失败（重新解析的 URL 仍失?/ 加载报错，且不会再有 sourceError 兜底）→ 立即跳曲
  const ok = await reloadCurrentTrack(true);
  if (!ok) {
    sourceRecoveryCount = 0;
    await nextTrack();
  }
};

/** 恢复播放 */
export const play = async (): Promise<void> => {
  if (tryRemoteControl("play")) return;
  const status = useStatusStore();
  if (status.state === "stopped" && status.currentTrack) {
    await loadTrack(status.currentTrack, status.currentPlaybackContext);
    return;
  }
  const prev = status.state;
  status.state = "playing";
  playback.setPlaying(true);
  const result = await bridge.player.play();
  if (!result.success) {
    status.state = prev;
    playback.setPlaying(false);
    handleError(result.error ?? "UNKNOWN");
  }
};

/** 切换播放/暂停 */
export const togglePlay = (): void => {
  if (tryRemoteControl("toggle")) return;
  const status = useStatusStore();
  if (status.isPlaying) {
    pause();
  } else {
    play();
  }
};

/** 暂停播放 */
export const pause = async (): Promise<void> => {
  if (tryRemoteControl("pause")) return;
  const status = useStatusStore();
  const prev = status.state;
  status.state = "paused";
  playback.setPlaying(false);
  const result = await bridge.player.pause();
  if (!result.success) {
    status.state = prev;
    playback.setPlaying(true);
  }
};

/** 停止播放并重置进度 */
export const stop = async (): Promise<void> => {
  const status = useStatusStore();
  status.trackLoading = false;
  const result = await bridge.player.stop();
  if (result.success) {
    status.state = "stopped";
    status.position = 0;
    playback.reset();
  }
};

/**
 * seek 目标位置（毫秒），非 null 表示正在 seek? * 后端推送的 position 必须接近此值才会被接受
 */
let seekTarget: number | null = null;

/**
 * 判断后端推送的 position 是否已到?seek 目标附近
 * @param position - 后端推送的播放位置（毫秒）
 * @returns 是否已到?seek 目标
 */
export const hasReachedSeekTarget = (position: number): boolean => {
  if (seekTarget === null) return true;
  // 容差：后端推送的位置?seek 目标 ±1s 内视为已到达
  if (Math.abs(position - seekTarget) < 1000) {
    seekTarget = null;
    playback.setSeeking(false);
    return true;
  }
  return false;
};

/** 当前是否正在 seek */
export const isSeeking = (): boolean => seekTarget !== null;

/**
 * 跳转到指定播放位? * @param posMs - 目标位置（毫秒）
 */
export const seek = async (posMs: number): Promise<void> => {
  if (tryRemoteControl("seek", posMs)) return;
  const status = useStatusStore();
  // 歌曲加载?seek 无意义：引擎此刻没有?seek 的解码线程，
  // ?seekTarget 残留会让加载完成后的 position 推送被持续丢弃
  if (status.trackLoading) return;
  // 先冻结插值，再写入位?  playback.setSeeking(true);
  status.position = posMs;
  playback.setCurrentTime(posMs);

  // 设置 seek 目标，屏蔽旧 position 推?  seekTarget = posMs;

  const result = await bridge.player.seek(posMs);
  if (result.success) {
    status.position = posMs;
    playback.setCurrentTime(posMs);
  }
};

/**
 * 标记一次非渲染层发起的 seek
 * @param posMs - 目标位置（毫秒）
 */
export const markSeek = (posMs: number): void => {
  const status = useStatusStore();
  if (status.trackLoading) return;
  playback.setSeeking(true);
  status.position = posMs;
  playback.setCurrentTime(posMs);
  seekTarget = posMs;
};

/**
 * 设置音量
 * @param vol - 音量值（0.0 ~ 1.0? */
export const setVolume = async (vol: number): Promise<void> => {
  const result = await bridge.player.setVolume(vol);
  if (result.success) {
    useStatusStore().volume = vol;
  }
};

/**
 * 设置播放速度
 * @param v - 速度?.5 ~ 2.0? */
export const setSpeed = async (v: number): Promise<void> => {
  const safe = Number.isFinite(v) ? Math.max(0.5, Math.min(2.0, v)) : 1.0;
  // 接收端上送速度变更到主机，由主机统一广播给所有从设备
  if (tryRemoteControl("setSpeed", safe)) return;
  const result = await bridge.player.setSpeed(safe);
  if (result.success) {
    useStatusStore().speed = safe;
    // 同步?playback 时间源，让墙钟插值正确换算到源时?    playback.setSpeed(safe);
  }
};

/**
 * 设置音调偏移（半?-12 ~ 12? */
export const setPitch = async (n: number): Promise<void> => {
  const safe = Number.isFinite(n) ? Math.max(-12, Math.min(12, Math.round(n))) : 0;
  // 接收端上送音调变更到主机
  if (tryRemoteControl("setPitch", safe)) return;
  const result = await bridge.player.setPitch(safe);
  if (result.success) useStatusStore().pitch = safe;
};

/**
 * 设置"音调同步"开? * @param on - true = 变速保音调，false = 变速变? */
export const setPitchSync = async (on: boolean): Promise<void> => {
  const result = await bridge.player.setPitchSync(on);
  if (result.success) useStatusStore().pitchSync = on;
};

/** 刷新音频输出设备列表 */
export const refreshDevices = async (): Promise<void> => {
  const result = await bridge.player.getOutputDevices();
  if (result.success && result.data) useStatusStore().outputDevices = result.data;
};

/**
 * 切换音频输出设备
 * @param deviceId - 设备 ID，传 null 跟随系统默认
 */
export const switchDevice = async (deviceId: string | null): Promise<void> => {
  const settings = useSettingsStore();
  const pauseBeforeSwitch =
    settings.player.pauseOnDeviceSwitch && useStatusStore().state === "playing";
  const result = await bridge.player.setOutputDevice(deviceId, pauseBeforeSwitch);
  if (result.success) settings.player.outputDevice = deviceId;
};

/**
 * 设置队列并从指定位置开始播放
 * @param items - 歌曲列表
 * @param startIndex - 起始播放位置，默认 0
 * @param context - 队列中曲目共用的播放来源上下文
 */
export const playFrom = async (
  items: readonly Track[],
  startIndex = 0,
  context?: PlaybackContext,
): Promise<void> => {
  if (items.length === 0) return;
  // 接收端不能自主切队列：交由主机决定播放内容，否则会与主机广播产生冲突
  if (isLanSyncReceiver() && !isApplyingRemoteState()) return;
  const status = useStatusStore();
  const media = useMediaStore();
  // 退出特殊模?  status.heartMode = false;
  status.fmMode = false;
  const idx = Math.max(0, Math.min(startIndex, items.length - 1));
  const isSameTrack = media.track?.id === items[idx]?.id;
  queue.setQueue(items, context);
  status.playIndex = idx;
  if (status.shuffleMode === "on") {
    queue.shuffleQueue(status.playIndex);
    status.playIndex = 0;
  }
  if (isSameTrack) {
    if (!status.isPlaying) play();
  } else {
    await loadTrack(status.currentTrack, status.currentPlaybackContext);
  }
};

/** 标签写入后恢复当前曲：暂停态加??seek 回原进度 ?视情况恢复播?*/
const resumeAfterTagWrite = async (
  track: Track,
  resumeMs: number,
  wasPlaying: boolean,
): Promise<void> => {
  if (!track.path) return;
  const myToken = ++trackToken;
  loadToken++;
  useMediaStore().setTrack(track);
  lyricLoader.beginLoad();
  const result = await load(track.path, false, track);
  if (myToken !== trackToken || !result.ok) return;
  if (resumeMs > 0) await seek(resumeMs);
  if (wasPlaying) await play();
};

/**
 * 写入本地文件标签并同步各处缓存? * 目标包含当前播放曲时：记录进??停止释放文件句柄 ?写入 ?重载并恢复进? * @param edits - 标签编辑请求（按文件路径? * @returns 逐项写入结果；IPC 层失败时返回 null
 */
export const saveTrackTags = async (edits: TagEditRequest[]): Promise<TagWriteOutcome[] | null> => {
  if (edits.length === 0) return [];
  const media = useMediaStore();
  const status = useStatusStore();
  const current = media.track;
  const currentPath = current?.source === "local" ? current.path : undefined;
  const touchesCurrent = !!currentPath && edits.some((edit) => edit.path === currentPath);

  let resumeMs = 0;
  let wasPlaying = false;
  if (touchesCurrent) {
    resumeMs = Math.round(playback.getCurrentTime());
    wasPlaying = status.isPlaying;
    // Windows 下引擎持有文件句柄，必须先停止才能写?
    await bridge.player.stop();
  }

  const result = await window.api.library.writeTags(edits);
  if (!result.success || !result.data) {
    if (result.error) handleError(result.error);
    // 写入失败也要恢复被停掉的当前?
    if (touchesCurrent && current) await resumeAfterTagWrite(current, resumeMs, wasPlaying);
    return null;
  }

  const updated = result.data
    .filter((outcome) => outcome.success && outcome.track)
    .map((outcome) => outcome.track!);
  if (updated.length > 0) {
    useLibraryStore().applyTrackUpdates(updated);
    queue.updateQueueTracks(updated);
  }

  if (touchesCurrent && current) {
    const newTrack = updated.find((track) => track.path === currentPath) ?? current;
    await resumeAfterTagWrite(newTrack, resumeMs, wasPlaying);
  }
  return result.data;
};

/**
 * 进入心动模式：用智能推荐列表替换队列并从头播? * @param tracks - 网易云智能推荐曲? */
export const playHeartMode = async (tracks: readonly Track[]): Promise<void> => {
  if (tracks.length === 0) return;
  const status = useStatusStore();
  queue.setQueue(tracks);
  status.playIndex = 0;
  status.shuffleMode = "off";
  status.heartMode = true;
  // 心动 / FM 互斥
  status.fmMode = false;
  syncPlayMode();
  await loadTrack(status.currentTrack, status.currentPlaybackContext);
};

/** 退出心动模式，保留当前队列继续播放 */
export const exitHeartMode = (): void => {
  useStatusStore().heartMode = false;
};

/**
 * 启动私人 FM 播放
 * @param options - 可选的 FM 模式与场景选项
 * @returns 是否成功进入并开始播放
 */
export const playPersonalFm = async (options?: PersonalFmOptions): Promise<boolean> => {
  const status = useStatusStore();
  const track = await fm.start(options);
  if (!track) return false;
  status.fmMode = true;
  // 心动 / FM 互斥
  status.heartMode = false;
  await loadTrack(track);
  return true;
};

/**
 * 标记当前私人 FM 曲目为不喜欢并切到下一首
 */
export const dislikeFmTrack = async (): Promise<void> => {
  if (!useStatusStore().fmMode) return;
  const playedSec = Math.max(0, Math.round(playback.getCurrentTime() / 1000));
  // Android：trash 由 JS 提交（含播放秒数反馈），队列推进交给原生权威
  if (isAndroidNative) {
    void fm.trashCurrent(playedSec);
    await nextTrack();
    return;
  }
  const next = await fm.dislikeCurrent(playedSec);
  if (next) await loadTrack(next);
};

/**
 * 播放下一首
 * @param manual - 用户手动点下一首
 */
export const nextTrack = async (manual = false): Promise<void> => {
  // 接收端：手动切歌上送主机；自动续播（ended）抑制——由主机续播并广播，避免双跳
  if (isLanSyncReceiver() && !isApplyingRemoteState()) {
    if (manual) dispatchLanRemoteCommand("next");
    return;
  }
  const status = useStatusStore();
  // Android：原生队列权威推进（FM 续池 / wrap / 解析 / 失败跳曲全在原生完成）
  if (isAndroidNative) {
    if (!status.fmMode && queue.queueLength.value > 0) {
      // 乐观推进索引，原生 trackChanged 为权威随后校正
      status.playIndex = status.playIndex >= queue.queueLength.value - 1 ? 0 : status.playIndex + 1;
    }
    await bridge.android.next();
    return;
  }
  // 私人 FM
  if (status.fmMode) {
    const next = await fm.next();
    if (next) await loadTrack(next);
    return;
  }
  if (queue.queueLength.value === 0) return;
  // 到末尾了
  if (status.playIndex >= queue.queueLength.value - 1) {
    if (status.shuffleMode === "on" && queue.queueLength.value > 1) {
      // 重新洗牌产生新顺序，当前歌在 index 0，从 1 开始避免重复
      queue.shuffleQueue(status.playIndex);
      status.playIndex = 1;
    } else {
      status.playIndex = 0;
    }
  } else {
    status.playIndex++;
  }
  await loadTrack(status.currentTrack, status.currentPlaybackContext);
};

/**
 * 跳到队列指定位置并播? * 同一首则不重新加载，仅在暂停时恢复播? * @param index - 队列位置
 */
export const playAtIndex = async (index: number): Promise<void> => {
  // 接收端不能自主选歌：队列索引由主机广播决定
  if (isLanSyncReceiver() && !isApplyingRemoteState()) return;
  const status = useStatusStore();
  if (index < 0 || index >= queue.queueLength.value) return;
  if (index === status.playIndex) {
    if (!status.isPlaying && useMediaStore().track) play();
    return;
  }
  // 退?FM
  status.fmMode = false;
  status.playIndex = index;
  await loadTrack(status.currentTrack, status.currentPlaybackContext);
};

/** 播放上一首，首位时回绕到末尾 */
export const prevTrack = async (): Promise<void> => {
  if (tryRemoteControl("prev")) return;
  const status = useStatusStore();
  // Android：原生队列权威推进（FM 下原生忽略 previous，对齐既有行为）
  if (isAndroidNative) {
    if (!status.fmMode && queue.queueLength.value > 0) {
      status.playIndex = status.playIndex > 0 ? status.playIndex - 1 : queue.queueLength.value - 1;
    }
    await bridge.android.previous();
    return;
  }
  if (status.fmMode) return;
  if (queue.queueLength.value === 0) return;
  status.playIndex = status.playIndex > 0 ? status.playIndex - 1 : queue.queueLength.value - 1;
  await loadTrack(status.currentTrack, status.currentPlaybackContext);
};

/** 队列播放结束，通知主进程停止并更新状态 */
const onQueueEnded = async (): Promise<void> => {
  const status = useStatusStore();
  status.trackLoading = false;
  playback.setPlaying(false);
  playback.reset();
  // 通知主进程停止音频引擎
  await bridge.player.stop();
  status.state = "stopped";
  status.position = status.duration;
};

/** 同步播放模式到主进程 */
const syncPlayMode = (): void => {
  const status = useStatusStore();
  bridge.player.syncPlayMode(status.repeatMode, status.shuffleMode);
};

/**
 * 设置循环模式
 * @param mode - list（列表循环）、one（单曲循环）
 */
export const setRepeatMode = (mode: RepeatMode): void => {
  const status = useStatusStore();
  if (status.repeatMode === mode) return;
  status.repeatMode = mode;
  syncPlayMode();
  toast.info(i18n.global.t(`player.repeatMode.${mode}`), { icon: false });
};

/** 循环切换循环模式：list → one → list */
export const cycleRepeatMode = (): void => {
  const status = useStatusStore();
  const cycle: RepeatMode[] = ["list", "one"];
  const nextIndex = (cycle.indexOf(status.repeatMode) + 1) % cycle.length;
  setRepeatMode(cycle[nextIndex]);
};

/** 切换随机模式 */
export const toggleShuffleMode = (): void => {
  const status = useStatusStore();
  setShuffleMode(status.shuffleMode === "on" ? "off" : "on");
};

/**
 * 设置随机模式，开启时洗牌队列，关闭时恢复原始顺序
 * @param mode - off（顺序）、on（随机）
 */
export const setShuffleMode = (mode: ShuffleMode): void => {
  const status = useStatusStore();
  // 心动模式下忽?
  if (status.heartMode) return;
  if (status.shuffleMode === mode) return;
  status.shuffleMode = mode;
  if (mode === "on") {
    // 洗牌，当前歌置顶
    queue.shuffleQueue(status.playIndex);
    status.playIndex = 0;
  } else {
    // 恢复原始顺序，定位到当前歌在原始队列中的位置
    const track = status.currentTrack;
    if (track) {
      status.playIndex = queue.unshuffleQueue(track.id);
    } else {
      queue.unshuffleQueue("");
    }
  }
  syncPlayMode();
  toast.info(i18n.global.t(`player.shuffleMode.${mode}`), { icon: false });
};

/**
 * 从队列移除指定位置的歌曲，自动调?playIndex
 * @param index - 要移除的队列位置
 */
export const removeFromQueue = async (index: number): Promise<void> => {
  const status = useStatusStore();
  if (index < 0 || index >= queue.queueLength.value) return;
  const isCurrentPlaying = index === status.playIndex;
  queue.removeFromQueue(index);
  if (index < status.playIndex) {
    // 移除的在当前歌之前，索引前移
    status.playIndex--;
  } else if (isCurrentPlaying) {
    // 移除的就是当前歌
    if (queue.queueLength.value === 0) {
      status.playIndex = -1;
      await onQueueEnded();
      return;
    }
    // 索引越界则回到首?
    if (status.playIndex >= queue.queueLength.value) status.playIndex = 0;
    await loadTrack(status.currentTrack, status.currentPlaybackContext);
  }
};

/**
 * 文件删除后同步队列：移除被删曲目，当前播放曲被删则切下一首（队列空则停止? * @param ids - 被删除的 Track id 列表
 */
export const purgeDeletedTracks = async (ids: readonly string[]): Promise<void> => {
  const currentId = useMediaStore().track?.id;
  // 先移除非当前曲：若先删当前曲，自动切到的下一首可能也在删除列表里，造成连环重载
  for (const id of ids) {
    if (id === currentId) continue;
    const index = queue.findTrackIndex(id);
    if (index !== -1) await removeFromQueue(index);
  }
  if (currentId && ids.includes(currentId)) {
    const index = queue.findTrackIndex(currentId);
    if (index !== -1) await removeFromQueue(index);
  }
};

/**
 * 插入歌曲到队列指定位置，自动调整 playIndex
 * 队列中已有同 ID 歌曲时移动到目标位置
 * @param item - 要插入的歌曲
 * @param afterIndex - 插入到此索引之后，默认为当前播放位置之后
 * @param context - 播放来源上下文
 * @returns 歌曲在队列中的实际索引
 */
export const insertToQueue = (
  item: Track,
  afterIndex?: number,
  context?: PlaybackContext,
): number => {
  const status = useStatusStore();
  const len = queue.queue.value.length;
  const raw = afterIndex ?? status.playIndex + 1;
  const existingIdx = queue.findTrackIndex(item.id);
  if (existingIdx !== -1) {
    queue.updateQueueItem(existingIdx, item, context);
    // 移动：目标需 clamp 到 length-1
    const safeAt = Math.max(0, Math.min(raw, len - 1));
    if (existingIdx === safeAt) return existingIdx;
    moveInQueue(existingIdx, safeAt);
    // Android 原生队列自治切歌，队列顺序变了必须重推队列
    void mediaSessionManager.syncAndroidPlaybackContext();
    return safeAt;
  }
  // 插入：可以追加到末尾，clamp ?length
  const safeAt = Math.max(0, Math.min(raw, len));
  queue.insertToQueue(item, safeAt, context);
  if (safeAt <= status.playIndex) status.playIndex++;
  // Android 原生队列自治切歌，“下一首播放”必须立即同步，否则续播仍取旧队列
  void mediaSessionManager.syncAndroidPlaybackContext();
  return safeAt;
};

/**
 * 批量插入曲目，一次性切片落盘，避免逐首插入的卡顿
 * 跳过队列中已存在的（含当前播放曲目）与传入列表内部的重复
 * @param items - 要插入的曲目
 * @param position - 插入到当前曲目之后或队列末尾
 * @param context - 播放来源上下文
 * @returns 实际插入的数量
 */
export const insertManyToQueue = (
  items: readonly Track[],
  position: "next" | "end" = "next",
  context?: PlaybackContext,
): number => {
  if (items.length === 0) return 0;
  const status = useStatusStore();
  const seen = new Set(queue.queue.value.map((track) => track.id));
  const fresh: Track[] = [];
  for (const item of items) {
    if (seen.has(item.id)) continue;
    seen.add(item.id);
    fresh.push(item);
  }
  if (fresh.length === 0) return 0;
  const insertAt = position === "end" ? queue.queue.value.length : status.playIndex + 1;
  queue.insertManyToQueue(fresh, insertAt, context);
  // Android 原生队列自治切歌，批量插入（含"下一首播放"）后必须重推队列
  void mediaSessionManager.syncAndroidPlaybackContext();
  return fresh.length;
};

/**
 * 插入歌曲到当前位置之后并立即播放
 * 如果是当前正在播放的歌曲则继续播放，不重新加载
 */
export const playNow = async (item: Track, context?: PlaybackContext): Promise<void> => {
  // 接收端不能自主插歌播放：由主机决定播放内容
  if (isLanSyncReceiver() && !isApplyingRemoteState()) return;
  const status = useStatusStore();
  const media = useMediaStore();
  // 同一首歌且已成功加载
  if (media.track?.id === item.id && status.currentSource) {
    if (!status.isPlaying) play();
    return;
  }
  // 退?FM
  status.fmMode = false;
  status.playIndex = insertToQueue(item, undefined, context);
  await loadTrack(item, context);
};

/**
 * 将本地音频文件路径转为轻量 Track 对象
 * @param filePath - 本地音频绝对路径
 */
export const createLocalTrack = (filePath: string): Track => {
  const fileName = filePath.split(/[/\\]/).pop() || filePath;
  const title = fileName.replace(/\.[^/.]+$/, "");
  return {
    id: `local:${filePath}`,
    title,
    artists: [],
    source: "local",
    path: filePath,
    duration: 0,
  };
};

/**
 * 直接播放一个本地音频文件
 * @param filePath - 本地音频绝对路径
 */
export const playFile = async (filePath: string): Promise<void> => {
  const item = createLocalTrack(filePath);
  await playNow(item, {
    originId: "local-file",
    originType: "track",
    originName: "本地文件",
  });
};

/**
 * 批量播放多个本地音频文件
 * @param filePaths - 本地音频绝对路径列表
 */
export const playFiles = async (filePaths: string[]): Promise<void> => {
  if (filePaths.length === 0) return;
  const tracks = filePaths.map(createLocalTrack);
  await playFrom(tracks, 0, {
    originId: "local-files",
    originType: "track",
    originName: "本地文件",
  });
};

/**
 * 移动队列中的歌曲位置，自动调整 playIndex
 * @param fromIndex - 原位置
 * @param toIndex - 目标位置
 */
export const moveInQueue = (fromIndex: number, toIndex: number): void => {
  const status = useStatusStore();
  queue.moveInQueue(fromIndex, toIndex);
  // 根据移动方向调整 playIndex
  if (status.playIndex === fromIndex) {
    status.playIndex = toIndex;
  } else if (fromIndex < status.playIndex && toIndex >= status.playIndex) {
    status.playIndex--;
  } else if (fromIndex > status.playIndex && toIndex <= status.playIndex) {
    status.playIndex++;
  }
};

let unsubscribe: (() => void) | null = null;
let initialized = false;

/** 初始化播放器 */
export const initPlayer = async (): Promise<void> => {
  if (initialized) return;
  initialized = true;
  console.log("[player] init");
  // 先从主进程同步后端配置，确保 system 设置可用
  const settings = useSettingsStore();
  await settings.syncSystem();
  // 流媒体。?store 必须在恢复队列前就绪，否则队列里?streaming track 拿不?cfg
  await useStreamingStore().init();
  // 插件 store 同理：在线歌曲 URL 兜底走插件，列表必须在 loadTrack 前就绪
  void usePluginsStore().load();
  await queue.restoreQueue();
  const status = useStatusStore();
  // 兼容移除“不循环”前持久化的旧状态
  if ((status.repeatMode as string) === "off") status.repeatMode = "list";
  // 恢复上次的音量和播放模式到主进程
  await bridge.player.setVolume(status.volume);
  syncPlayMode();
  // 应用渐入渐出配置
  const { fadeEnabled, fadeDuration, loudnessNormalization, equalizer } = settings.system.player;
  await bridge.player.setFadeDuration(fadeEnabled ? fadeDuration : 0);
  // 应用音量均衡配置
  await bridge.player.setNormalizationEnabled(loudnessNormalization ?? false);
  // 应用均衡器配置
  if (equalizer) {
    await bridge.player.setEqualizerBands([...equalizer.bands]);
    await bridge.player.setPreampGain(equalizer.preamp);
    await bridge.player.setEqualizerEnabled(equalizer.enabled);
  }
  // 刷新设备列表并恢复上次选择的输出设备
  await refreshDevices();
  await bridge.player.setPauseOnDeviceSwitch(settings.player.pauseOnDeviceSwitch);
  if (settings.player.outputDevice) {
    // 1.0.0 及更早版本存的是显示名，就地换成稳定 ID；设备当前不在线时保留原值等下次启动
    const legacy = useStatusStore().outputDevices.find(
      (device) => device.name === settings.player.outputDevice,
    );
    if (legacy) settings.player.outputDevice = legacy.id;
    await bridge.player.setOutputDevice(settings.player.outputDevice);
  }
  // 先订阅事件，确保 load 触发播放后 position 事件能被接收
  if (unsubscribe) unsubscribe();
  unsubscribe = bridge.player.onEvent(handleEvent);
  // 安装播放统计累加器
  installPlayStats();
  // 订阅主进程下发的歌词偏移变化
  const media = useMediaStore();
  // 当前歌曲喜欢状态变化时同步到托盘菜?
  const fav = useFavorite();
  watch(
    () => fav.isLiked(media.track),
    (liked) => window.api.player.syncLikeState(liked),
    { immediate: true },
  );
  window.api.nowPlaying.onLyricOffsetChange(({ offsetMs }) => {
    status.lyricOffsetMs = offsetMs;
    media.updateLyricIndex(playback.getCurrentTime() + offsetMs);
  });
  // 获取歌曲偏移
  try {
    const snap = await window.api.nowPlaying.requestSnapshot();
    status.lyricOffsetMs = snap.lyricOffsetMs;
  } catch (error) {
    console.error("[player] requestSnapshot failed", error);
  }
  // Android：初始化 MediaSession（customAction 监听 + 同步 API 上下文）
  if (isAndroid) {
    mediaSessionManager.init();
  }
  // 下一首预载的监听器（Android 由原生 prefetchUpcomingUrls 接管，跳过 JS 预载）
  if (!isAndroidNative) {
    installNextTrackPreloadWatchers();
    scheduleNextTrackPreload();
  }
};

/** 恢复上次播放状态 */
export const restoreLastTrack = async (): Promise<void> => {
  const status = useStatusStore();
  const settings = useSettingsStore();
  const media = useMediaStore();
  const lastTrack = status.currentTrack;
  if (!lastTrack) {
    status.state = "idle";
    return;
  }
  const lastPosition = status.position;
  media.setTrack(lastTrack);
  media.setPlaybackContext(status.currentPlaybackContext);
  lyricLoader.beginLoad();
  // Android：推全量队列上下文恢复；autoPlay 时由原生从恢复索引开播（原生解析）。
  // 进度恢复与桌面一致受 rememberLastTrack 门控
  if (isAndroidNative) {
    await mediaSessionManager.syncAndroidPlaybackContext();
    if (settings.system.player.autoPlay) {
      const resumePosition = settings.system.player.rememberLastTrack ? lastPosition : 0;
      await bridge.android.playIndex(status.fmMode ? 0 : status.playIndex, resumePosition);
    } else {
      status.state = "idle";
    }
    return;
  }
  const loaded = await loadTrackSourceWithFallback(
    lastTrack,
    status.currentPlaybackContext,
    settings.system.player.autoPlay,
    () => true,
  );
  if (loaded.status === "loaded" && loaded.result.ok) {
    if (settings.system.player.rememberLastTrack && lastPosition > 0) {
      await seek(lastPosition);
    }
    if (loaded.resolved.cacheRequest) {
      cacheScheduler.schedule(lastTrack.id, loaded.resolved.cacheRequest);
    }
  } else {
    status.state = "idle";
  }
};

/** 清理事件订阅 */
export const disposePlayer = (): void => {
  disposeNextTrackPreload();
  if (unsubscribe) {
    unsubscribe();
    unsubscribe = null;
  }
  // 清理 MediaSession 事件订阅
  mediaSessionManager.dispose();
};
