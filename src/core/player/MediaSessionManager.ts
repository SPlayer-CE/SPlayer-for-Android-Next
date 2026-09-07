import type { RepeatMode } from "@/stores/status";
import type { Track } from "@shared/types/player";
import { watch } from "vue";
import { useMediaStore } from "@/stores/media";
import { useSettingsStore } from "@/stores/settings";
import { useStatusStore } from "@/stores/status";
import { useUserStore } from "@/stores/user";
import { usePluginsStore } from "@/stores/plugins";
import { useHistoryStore } from "@/stores/history";
import * as queueStore from "@/stores/queue";
import * as lyricLoader from "@/services/lyric/loader";
import { songsByIds } from "@/apis/song/netease";
import * as fm from "./fm";
import { EMBEDDED_API_ORIGIN } from "@/utils/embeddedApi";
import bridge, { isAndroidNative } from "@/services/bridge";
import { isLanSyncReceiver } from "@/composables/useLanSyncRole";
import { isApplyingRemoteState } from "@/composables/lanSyncRemote";

/** 平台 → 插件源名（对齐 audioSource.PLATFORM_TO_PLUGIN_SOURCE） */
const PLUGIN_SOURCE_BY_PLATFORM: Record<string, string> = {
  netease: "wy",
  qqmusic: "tx",
  kugou: "kg",
};

/** 计算各插件源的可用插件 ID 候选（enabled + ready + 支持 musicUrl），推给原生解析器 */
const buildPluginSourceCandidates = (): Record<string, string[]> => {
  const out: Record<string, string[]> = {};
  const plugins = usePluginsStore().list;
  for (const pluginSource of Object.values(PLUGIN_SOURCE_BY_PLATFORM)) {
    const ids = plugins
      .filter(
        (info) =>
          info.enabled &&
          info.status.state === "ready" &&
          info.status.sources[pluginSource]?.actions.includes("musicUrl"),
      )
      .map((info) => info.manifest.id);
    if (ids.length > 0) out[pluginSource] = ids;
  }
  return out;
};

/** 队列变更推送防抖间隔：删/移/洗牌等高频编辑合并为一次全量推送 */
const QUEUE_PUSH_DEBOUNCE_MS = 400;

/** 队列变更推送防抖定时器句柄 */
let queuePushTimer: number | undefined;

/**
 * Android 原生 MediaSession 自定义动作事件
 * Java 端通过 Capacitor emitCustomAction("customAction", ...) 推送
 */
export interface AndroidNativeCustomActionEvent {
  action:
    | "next"
    | "previous"
    | "play"
    | "pause"
    | "favorite"
    | "desktopLyric"
    | "desktopLyricReady"
    | "collapse"
    | "autoNext"
    | "trackChanged";
  songId?: number;
  liked?: boolean;
  desktopLyricEnabled?: boolean;
  collapsed?: boolean;
  success?: boolean;
  message?: string;
  playListIndex?: number;
  source?: "auto" | "next" | "previous" | "index" | "fm";
  url?: string;
  title?: string;
  artist?: string;
  album?: string;
  coverUrl?: string;
  durationMs?: number;
}

/** 队列中单首曲目的原生 payload */
interface AndroidNativeQueueTrack {
  track: Track;
  source: Track["source"];
  sourceId: string;
  path?: string;
  serverId?: string;
  originalId?: string;
  liked: boolean;
  url: string | null;
  playListIndex: number;
  skipSong: boolean;
}

/** 全量队列推送 payload */
interface AndroidNativeQueueContextPayload {
  liked: boolean;
  canSkipPrevious: boolean;
  personalFmMode: boolean;
  controllerEnabled: boolean;
  desktopLyricButtonEnabled: boolean;
  desktopLyricEnabled: boolean;
  repeatMode: RepeatMode;
  tracks: AndroidNativeQueueTrack[];
  currentIndex: number;
  fmRecentSongIds?: number[];
}

/** 将 Track.id（string）解析为 number，供原生事件比对 */
const toNativeSongId = (id: string | undefined): number => {
  if (!id) return 0;
  // netease 等平台 id 为纯数字
  const num = Number(id);
  return Number.isFinite(num) && num > 0 ? Math.floor(num) : 0;
};

/**
 * 前端媒体会话管理器
 *
 * 适配当前项目架构（函数式 player API + queue store），
 * 负责：
 * - 注册原生 customAction 事件监听（耳机/通知栏/锁屏按钮）
 * - 切歌时推送前端 Track 快照到 Kotlin MediaSession 服务
 * - 推送全量播放队列到 Kotlin 端（原生队列权威，WebView 冻结时仍可自治切歌）
 * - 同步 API 上下文（基址/Cookie/音质/缓存/插件源）到 Kotlin 端
 */
class MediaSessionManager {
  private customActionUnsubscribe: (() => void) | null = null;
  private queueWatchStop: (() => void) | null = null;

  /** 初始化媒体会话：注册 customAction 监听 + 队列变更自动推送 + 同步 API 上下文 */
  public init(): void {
    if (!isAndroidNative) return;

    // 注册原生 customAction 事件监听
    if (this.customActionUnsubscribe) {
      this.customActionUnsubscribe();
      this.customActionUnsubscribe = null;
    }
    this.customActionUnsubscribe = bridge.android.onCustomAction((data) => {
      this.handleCustomAction(data as AndroidNativeCustomActionEvent);
    });

    // 队列内容变更（删/移/洗牌/清空等）防抖重推原生队列；索引推进由原生 trackChanged 自洽
    if (!this.queueWatchStop) {
      this.queueWatchStop = watch(
        () => queueStore.queue.value,
        () => this.scheduleQueuePush(),
      );
    }

    // 同步 API 上下文
    void this.syncAndroidApiContext();
  }

  /** 清理事件订阅 */
  public dispose(): void {
    if (this.customActionUnsubscribe) {
      this.customActionUnsubscribe();
      this.customActionUnsubscribe = null;
    }
    if (this.queueWatchStop) {
      this.queueWatchStop();
      this.queueWatchStop = null;
    }
    if (queuePushTimer !== undefined) {
      window.clearTimeout(queuePushTimer);
      queuePushTimer = undefined;
    }
  }

  /** 队列变更推送防抖句柄 */
  private scheduleQueuePush(): void {
    if (queuePushTimer !== undefined) window.clearTimeout(queuePushTimer);
    queuePushTimer = window.setTimeout(() => {
      queuePushTimer = undefined;
      void this.syncAndroidPlaybackContext();
    }, QUEUE_PUSH_DEBOUNCE_MS);
  }

  /** 处理原生 customAction 事件 */
  private handleCustomAction = async (event: AndroidNativeCustomActionEvent): Promise<void> => {
    // 延迟导入避免循环依赖
    const player = await import("@/core/player");
    switch (event.action) {
      case "next":
        void player.nextTrack();
        break;
      case "previous":
        void player.prevTrack();
        break;
      case "play":
        void player.play();
        break;
      case "pause":
        void player.pause();
        break;
      case "trackChanged":
        if (typeof event.playListIndex === "number" && event.playListIndex >= 0) {
          void this.applyNativeTrackChanged(
            event.playListIndex,
            event.liked,
            event.source,
            event.url,
          );
        } else if (useStatusStore().fmMode && typeof event.songId === "number") {
          void this.applyNativeFmTrackChanged(event);
        }
        break;
      case "favorite":
        // 收藏动作由原生端自行处理 API 调用，这里仅同步 UI 状态
        if (event.success && typeof event.songId === "number" && typeof event.liked === "boolean") {
          // 同步 likedSongIds
          const userStore = useUserStore();
          const track = useMediaStore().track;
          if (track && toNativeSongId(track.id) === event.songId) {
            // 更新本地收藏状态
            if (event.liked) {
              userStore.likedSongIds.add(track.id);
            } else {
              userStore.likedSongIds.delete(track.id);
            }
          }
        }
        break;
      case "desktopLyric":
        // 桌面歌词切换由原生端处理，前端无需额外操作
        break;
    }
  };

  /**
   * 同步 API 上下文到原生端
   * Kotlin 端带 lastKey 去重，避免切歌瞬间重复应用相同上下文
   *
   * 注意：apiBaseUrl 必须带 /api/netease 前缀，因为 Node.js 后端的网易云 API 路由是
   * /api/netease/*，Java 端的 PlaybackUrlResolver 会直接拼接 /song/url/v1 等路径。
   * songLevel 传项目内部档位，由 Kotlin 端转换为网易云 API level。
   */
  public async syncAndroidApiContext(force = false): Promise<void> {
    if (!isAndroidNative) return;

    const settings = useSettingsStore();
    const userStore = useUserStore();
    const cookie = userStore.cookie || "";
    const rawLevel = settings.player.songLevel || "hq";
    const apiBaseUrl = `${EMBEDDED_API_ORIGIN}/api/netease`;

    try {
      await bridge.android.syncApiContext({
        apiBaseUrl,
        cookie,
        songLevel: rawLevel,
        playSongDemo: settings.player.allowTrialPlay === true,
        songCacheEnabled: settings.system.cache?.songCache?.enabled === true,
        pluginSources: buildPluginSourceCandidates(),
        force,
      });
    } catch (error) {
      console.warn("[MediaSession] sync Android API context failed:", error);
    }
  }

  /** 用户登录登出 / 切换音质后调用，强制下次同步 */
  public invalidateSyncedApiContext(): void {
    void this.syncAndroidApiContext(true);
  }

  /** 切歌时推送元数据到原生 MediaSession */
  public async updateMetadata(): Promise<void> {
    if (!isAndroidNative) return;

    const media = useMediaStore();
    const track = media.track;
    if (!track) return;

    try {
      await this.syncAndroidApiContext();

      const userStore = useUserStore();
      const isLiked = userStore.likedSongIds.has(track.id);

      await bridge.android.updateMetadata({
        track,
        liked: isLiked,
      });
    } catch (error) {
      console.warn("[MediaSession] update metadata failed:", error);
    }
  }

  /**
   * 推送全量播放队列上下文到原生端（原生队列权威）
   * WebView 冻结时原生凭全量队列自治完成 ENDED/NEXT/PREVIOUS/FM 续池
   */
  public async syncAndroidPlaybackContext(): Promise<void> {
    if (!isAndroidNative) return;

    const status = useStatusStore();
    const settings = useSettingsStore();
    const userStore = useUserStore();
    const media = useMediaStore();

    const track = media.track;
    if (!track) return;

    const queueResult = this.buildAndroidQueueTracks();
    if (!queueResult) return;

    try {
      await this.syncAndroidApiContext();
      const isLiked = userStore.likedSongIds.has(track.id);
      const payload: AndroidNativeQueueContextPayload = {
        liked: isLiked,
        canSkipPrevious: !status.fmMode,
        personalFmMode: status.fmMode,
        controllerEnabled: settings.androidMediaControllerEnabled,
        desktopLyricButtonEnabled: settings.androidMediaControllerDesktopLyricEnabled,
        desktopLyricEnabled: false,
        ...queueResult,
      };
      await Promise.all([
        bridge.android.updateQueueContext(payload as unknown as Record<string, unknown>),
        bridge.android.updateNotificationPrefs({
          controllerEnabled: settings.androidMediaControllerEnabled,
          desktopLyricButtonEnabled: settings.androidMediaControllerDesktopLyricEnabled,
        }),
        bridge.android.setAllowMixWithOthers(settings.androidAllowMixWithOthers),
      ]);
    } catch (error) {
      console.warn("[MediaSession] sync playback context failed:", error);
    }
  }

  /** 构建全量队列 payload（FM 模式推 FM 池，当前曲恒在队头） */
  private buildAndroidQueueTracks(): {
    tracks: AndroidNativeQueueTrack[];
    currentIndex: number;
    repeatMode: RepeatMode;
    fmRecentSongIds?: number[];
  } | null {
    const status = useStatusStore();
    const userStore = useUserStore();
    const playIndex = status.playIndex;
    const currentSource = status.currentSource;
    const fmMode = status.fmMode;
    const list = fmMode ? fm.snapshot() : queueStore.queue.value;

    const empty = {
      tracks: [] as AndroidNativeQueueTrack[],
      currentIndex: -1,
      repeatMode: status.repeatMode,
    };

    // FM 模式当前曲恒为池头（索引 0），playIndex 是普通队列的陈旧值，两个越界判断都必须跳过
    if (list.length === 0 || (!fmMode && (playIndex < 0 || playIndex >= list.length))) {
      return empty;
    }

    const tracks: AndroidNativeQueueTrack[] = [];
    // FM 模式当前曲即池头（索引恒 0）；普通队列用 playIndex
    let currentIndex = -1;

    for (let i = 0; i < list.length; i++) {
      const t = list[i];
      if (!t) continue;
      const isCurrent = fmMode ? i === 0 : i === playIndex;
      const isLiked = userStore.likedSongIds.has(t.id);
      const knownUrl =
        isCurrent && currentSource ? currentSource : t.source === "local" ? t.path : null;

      tracks.push({
        track: t,
        source: t.source,
        sourceId: t.id,
        path: t.path,
        serverId: t.serverId,
        originalId: t.originalId,
        liked: isLiked,
        url: knownUrl ?? null,
        // FM 曲目不映射队列索引（trackChanged 以 playListIndex<0 + fmMode 识别 FM 分支）
        playListIndex: fmMode ? -1 : i,
        skipSong: false,
      });

      if (isCurrent) {
        currentIndex = tracks.length - 1;
      }
    }

    const result: {
      tracks: AndroidNativeQueueTrack[];
      currentIndex: number;
      repeatMode: RepeatMode;
      fmRecentSongIds?: number[];
    } = {
      tracks,
      currentIndex,
      repeatMode: status.repeatMode,
    };
    if (fmMode) {
      result.fmRecentSongIds = fm
        .recentPlayedIds()
        .map(toNativeSongId)
        .filter((id) => id > 0);
    }
    return result;
  }

  /**
   * Java 端 emit trackChanged 时调用：同步 statusStore.playIndex
   * 覆盖 ENDED / 用户 NEXT / 用户 PREVIOUS 三种来源
   *
   * <p>注意：Java 自治切歌不走前端 loadTrack，因此需在此处显式重置歌词状态并重新加载，
   * 否则 UI 会残留上一首歌词（对齐 PC 端 index.ts loadTrack 的 beginLoad + loadForTrack 流程）。
   * <p>原生索引已自洽，此处不再重推队列上下文（全量推送代价高且无必要）。
   */
  public async applyNativeTrackChanged(
    playListIndex: number,
    liked?: boolean,
    _source?: "auto" | "next" | "previous" | "index" | "fm",
    sourceUrl?: string,
  ): Promise<void> {
    if (!isAndroidNative) return;
    // 接收端禁止原生自治切歌：从设备只跟随主机广播的曲目，否则会与主机产生双跳
    if (isLanSyncReceiver() && !isApplyingRemoteState()) return;

    const status = useStatusStore();
    const media = useMediaStore();
    const list = queueStore.queue.value;

    if (playListIndex < 0 || playListIndex >= list.length) {
      console.warn("[MediaSession] applyNativeTrackChanged: playListIndex 越界", { playListIndex });
      return;
    }

    const nextTrack = list[playListIndex];
    if (!nextTrack) return;

    // 同步 playIndex
    status.playIndex = playListIndex;
    if (sourceUrl) status.currentSource = sourceUrl;
    status.trackLoading = false;

    // 同步收藏状态
    if (typeof liked === "boolean") {
      const userStore = useUserStore();
      if (liked) {
        userStore.likedSongIds.add(nextTrack.id);
      } else {
        userStore.likedSongIds.delete(nextTrack.id);
      }
    }

    // 更新 UI：设置 track 并刷新元数据
    media.setTrack(nextTrack);
    // Android 分支不经 loadTrack 的历史记录点，这里补记播放历史（对齐桌面行为）
    void useHistoryStore().record(nextTrack);
    // 切歌时重置歌词状态并重新加载，避免残留上一首歌词
    lyricLoader.beginLoad();
    void lyricLoader.loadForTrack(null);
    await this.updateMetadata();
  }

  /**
   * Java 端 FM 切歌的 trackChanged 同步：对齐 JS FM 池到原生已播曲目，
   * 池中不含该曲（原生后台自拉批次）时按 songId 拉详情兜底。
   */
  public async applyNativeFmTrackChanged(event: AndroidNativeCustomActionEvent): Promise<void> {
    if (!isAndroidNative) return;
    if (isLanSyncReceiver() && !isApplyingRemoteState()) return;

    const status = useStatusStore();
    const media = useMediaStore();

    const aligned = await fm.skipTo(event.songId!).catch(() => null);
    let track = aligned ?? null;
    if (!track || track.id !== String(event.songId)) {
      try {
        const [detail] = await songsByIds([event.songId!]);
        track = detail ?? null;
      } catch (error) {
        console.warn("[MediaSession] fetch fm track detail failed:", error);
      }
    }

    if (event.url) status.currentSource = event.url;
    status.trackLoading = false;

    if (!track) {
      // 详情兜底也失败：用事件内嵌的最小元数据保证 UI 不空白
      if (event.title) {
        const fallback: Track = {
          id: String(event.songId),
          source: "netease",
          title: event.title,
          artists: (event.artist ?? "").split("/").map((name) => ({ id: name, name })),
          album: event.album ? { id: "", name: event.album, cover: event.coverUrl } : undefined,
          cover: event.coverUrl,
          duration: event.durationMs ?? 0,
        };
        media.setTrack(fallback);
        void useHistoryStore().record(fallback);
        await this.updateMetadata();
      }
      return;
    }

    media.setTrack(track);
    // Android 分支不经 loadTrack 的历史记录点，这里补记播放历史（对齐桌面行为）
    void useHistoryStore().record(track);
    lyricLoader.beginLoad();
    void lyricLoader.loadForTrack(null);
    await this.updateMetadata();
  }

  /** 推送播放进度到原生端（JS 驱动播放时） */
  public updateState(_duration: number, _position: number): void {
    if (!isAndroidNative) return;
    // 原生 ExoPlayer 会自动更新 MediaSession 位置，无需通过 syncRemoteState 覆盖
    // 当前项目始终使用原生引擎，这里 no-op
  }

  /** 推送播放/暂停状态（原生 ExoPlayer 自动同步，no-op） */
  public updatePlaybackStatus(_isPlaying: boolean): void {
    // 原生 ExoPlayer 会自动同步 MediaSession 播放状态，无需手动推送
  }

  /** 推送播放速率 */
  public updatePlaybackRate(_rate: number): void {
    // 原生 ExoPlayer 会自动同步，无需手动推送
  }

  /** 推送音量 */
  public updateVolume(_volume: number): void {
    // 原生 ExoPlayer 会自动同步，无需手动推送
  }
}

export const mediaSessionManager = new MediaSessionManager();
