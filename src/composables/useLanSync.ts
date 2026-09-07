/**
 * 局域网协同同步 — 从设备接收端。
 * 连接主机 WebSocket，将主机播放状态映射到本机现有播放服务（bridge.player）。
 * 主机切歌 → 从机加载主机提供的音频流；主机 seek/pause/play → 从机 seek/pause/play。
 */
import type { Track } from "@shared/types/player";
import { EMBEDDED_API_PORT } from "@/utils/embeddedApi";
import { useStatusStore } from "@/stores/status";
import { useMediaStore } from "@/stores/media";
import { isAndroidPreview } from "@/services/bridge";
import { getCurrentTime } from "@/services/playback";
import {
  load as playerLoad,
  play as playerPlay,
  pause as playerPause,
  seek as playerSeek,
} from "@/core/player";
import {
  registerLanRemoteSink,
  withApplyingRemoteState,
  type LanRemoteAction,
} from "@/composables/lanSyncRemote";
import {
  onLanSyncQueueRevision,
  refreshLanHostQueue,
  resetLanHostQueue,
} from "@/composables/useLanHostQueue";
import {
  onLanSyncLyricRevision,
  refreshLanHostLyric,
  resetLanHostLyric,
} from "@/composables/useLanHostLyric";

interface SyncPayload {
  type: "splayer-sync";
  ts: number;
  track?: Track;
  trackId?: string;
  position?: number;
  playing?: boolean;
  duration?: number;
  lyricIndex?: number;
  audioReady?: boolean;
  audioRevision?: number;
  audioUrl?: string;
  queueRevision?: number;
  lyricRevision?: number;
  /** 恢复播放对齐标记：主机暂停→播放或 syncSeek 校准后广播，从设备无条件 seek 不走容差 */
  syncSeek?: boolean;
}

export interface LanSyncState {
  connected: boolean;
  hostIp: string;
  trackId: string | null;
  playing: boolean;
  position: number;
  duration: number;
  lastSyncTs: number;
}

const RECONNECT_DELAY_MS = 2000;
const AUTH_RETRY_DELAY_MS = 3000;
const MAX_RECONNECT_ATTEMPTS = 10;
const TIME_SYNC_INTERVAL_MS = 5000;
const SYNC_TOLERANCE_MS_PLAYING = 2000;
const SYNC_TOLERANCE_MS_PAUSED = 500;

export function useLanSync(hostIp: string) {
  const state = reactive<LanSyncState>({
    connected: false,
    hostIp,
    trackId: null,
    playing: false,
    position: 0,
    duration: 0,
    lastSyncTs: 0,
  });

  const status = useStatusStore();
  const media = useMediaStore();

  let ws: WebSocket | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let timeSyncTimer: ReturnType<typeof setInterval> | null = null;
  let destroyed = false;
  let loadingKey: string | null = null;
  let loadedKey: string | null = null;
  let failedKey: string | null = null;
  let failedKeyAt = 0;
  let loadRequestSeq = 0;
  let hostClockOffsetMs = 0;
  let wsToken = "";
  let reconnectAttempts = 0;

  const canRetryFailedKey = (key: string): boolean => {
    if (failedKey !== key) return true;
    // 主机切歌瞬间音频源可能还没就绪，短暂失败后允许自动重试，避免必须手动刷新页面。
    return Date.now() - failedKeyAt >= 1000;
  };

  const currentAudioUrl = (trackId: string, revision: number): string => {
    const host = hostIp || window.location.hostname || "127.0.0.1";
    return `http://${host}:${EMBEDDED_API_PORT}/api/lanShare/audio?trackId=${encodeURIComponent(trackId)}&v=${revision}`;
  };

  const fetchHostStatus = async (): Promise<{
    enabled: boolean;
    collabEnabled: boolean;
    wsToken?: string;
  }> => {
    const host = hostIp || window.location.hostname || "127.0.0.1";
    const response = await fetch(`http://${host}:${EMBEDDED_API_PORT}/api/lanShare/getStatus`, {
      cache: "no-store",
    });
    if (!response.ok) throw new Error(`Host status request failed: ${response.status}`);
    return response.json();
  };

  const getEstimatedHostNow = (): number => Date.now() + hostClockOffsetMs;

  /**
   * 用主设备时间基准外推"此刻应该播放到哪里"。
   * data.ts 来自主机嵌入式服务，先经 time-sync 估算主机时钟偏移，再把网络传输耗时补回去。
   */
  const projectHostPosition = (
    basePosition: number,
    hostTs: number,
    playing: boolean,
    duration?: number,
  ): number => {
    if (!playing) return basePosition;
    const elapsed = Math.max(0, getEstimatedHostNow() - hostTs);
    const projected = basePosition + elapsed;
    if (typeof duration === "number" && duration > 0) {
      return Math.min(projected, duration);
    }
    return projected;
  };

  const updateHostClockOffset = (clientTs: number, serverTs: number): void => {
    const now = Date.now();
    const roundTripMs = Math.max(0, now - clientTs);
    const estimatedOffsetMs = serverTs + roundTripMs / 2 - now;
    // 用平滑滤波避免单次网络抖动把整条时间轴拉偏。
    hostClockOffsetMs =
      hostClockOffsetMs === 0
        ? estimatedOffsetMs
        : hostClockOffsetMs * 0.8 + estimatedOffsetMs * 0.2;
  };

  const sendTimeSync = (): void => {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    try {
      ws.send(JSON.stringify({ type: "time-sync-request", clientTs: Date.now() }));
    } catch {}
  };

  const startTimeSync = (): void => {
    if (timeSyncTimer) return;
    sendTimeSync();
    timeSyncTimer = setInterval(() => sendTimeSync(), TIME_SYNC_INTERVAL_MS);
  };

  const stopTimeSync = (): void => {
    if (!timeSyncTimer) return;
    clearInterval(timeSyncTimer);
    timeSyncTimer = null;
  };

  const resolveHostTrackUrl = (
    track: Track,
    audioRevision?: number,
    audioUrl?: string,
  ): string | null => {
    if (audioUrl && /^https?:\/\//.test(audioUrl)) return audioUrl;
    if (audioUrl?.startsWith("/")) {
      const host = hostIp || window.location.hostname || "127.0.0.1";
      return `http://${host}:${EMBEDDED_API_PORT}${audioUrl}`;
    }
    return typeof audioRevision === "number" ? currentAudioUrl(track.id, audioRevision) : null;
  };

  /** 主机切歌：通过主设备提供的音频流加载，保证音质与主设备一致 */
  const loadHostTrack = async (
    track: Track,
    audioRevision?: number,
    audioUrl?: string,
  ): Promise<void> => {
    const key = `${track.id}:${audioRevision ?? 0}`;
    if (loadingKey === key || loadedKey === key || !canRetryFailedKey(key)) return;
    const requestSeq = ++loadRequestSeq;
    loadingKey = key;
    // 乐观更新本机媒体信息，让 UI 立即响应
    media.setTrack(track);
    try {
      if (destroyed) return;
      const source = resolveHostTrackUrl(track, audioRevision, audioUrl);
      if (!source) return;
      // 切歌期间旧请求可能晚于新请求返回；这里必须丢弃过期加载，避免音频回退到上一首。
      if (requestSeq !== loadRequestSeq) return;
      // 浏览器自动播放策略：先以 paused 加载，避免 NotAllowedError 导致整首加载失败；
      // 播放动作由 syncPlayState / 首次手势回调触发
      const result = await playerLoad(source, false, track);
      if (requestSeq !== loadRequestSeq) return;
      if (!result.ok) {
        failedKey = key;
        failedKeyAt = Date.now();
        console.warn("[lan-sync] 加载主机音频失败:", track.title);
        return;
      }
      state.trackId = track.id;
      loadedKey = key;
      failedKey = null;
      failedKeyAt = 0;
      if (state.position > 0) {
        withApplyingRemoteState(() => playerSeek(state.position)).catch(() => {});
      }
      // 主机正在播放 → 尝试启动；若被自动播放策略拦截，ensurePlaybackOnGesture 会在首次手势重试
      if (state.playing && status.state !== "playing") {
        withApplyingRemoteState(() => playerPlay()).catch(() => {});
        ensurePlaybackOnGesture();
      }
    } catch (err) {
      if (requestSeq !== loadRequestSeq) return;
      failedKey = key;
      failedKeyAt = Date.now();
      console.warn("[lan-sync] 加载主机歌曲失败:", err);
    } finally {
      if (loadingKey === key) loadingKey = null;
    }
  };

  /** 同步播放/暂停状态到现有播放服务 */
  const syncPlayState = (playing: boolean): void => {
    state.playing = playing;
    if (playing) {
      if (status.state !== "playing") {
        withApplyingRemoteState(() => playerPlay()).catch(() => {});
        // 浏览器可能拦截首次自动播放，注册手势回调在首次交互时重试
        ensurePlaybackOnGesture();
      }
    } else {
      if (status.state !== "paused") {
        withApplyingRemoteState(() => playerPause()).catch(() => {});
      }
    }
  };

  /**
   * 浏览器自动播放策略兜底：当主机正在播放但本机 play() 被拦截时，
   * 在首次用户手势（pointerdown / keydown）内重试 play()。
   * 手势回调内的 play() 不会被拦截，且后续 programmatic play() 也随之放行。
   */
  const gestureHandlers: Array<() => void> = [];
  const ensurePlaybackOnGesture = (): void => {
    if (!isAndroidPreview || destroyed) return;
    if (gestureHandlers.length > 0) return; // 已注册则等待
    const tryStart = (): void => {
      if (destroyed) return;
      if (status.state === "playing") {
        // 已成功播放，注销监听
        for (const off of gestureHandlers.splice(0)) off();
        return;
      }
      if (state.playing) {
        withApplyingRemoteState(() => playerPlay()).catch(() => {});
      }
    };
    window.addEventListener("pointerdown", tryStart, { passive: true });
    window.addEventListener("keydown", tryStart, { passive: true });
    gestureHandlers.push(() => {
      window.removeEventListener("pointerdown", tryStart);
      window.removeEventListener("keydown", tryStart);
    });
  };

  /** 同步进度（偏差超过阈值才 seek，避免抖动） */
  const syncPosition = (hostMs: number): void => {
    state.position = hostMs;
    const diff = Math.abs(status.position - hostMs);
    const tolerance = state.playing ? SYNC_TOLERANCE_MS_PLAYING : SYNC_TOLERANCE_MS_PAUSED;

    // 如果偏差非常小（可能是正常的网络微小波动或推算误差），不进行强制同步，防止画面抽搐
    if (diff > tolerance) {
      withApplyingRemoteState(() => playerSeek(hostMs)).catch(() => {});
    }
  };

  const handleSync = async (data: SyncPayload): Promise<void> => {
    state.lastSyncTs = data.ts || Date.now();
    if (data.duration !== undefined) state.duration = data.duration;
    if (data.playing !== undefined) state.playing = data.playing;
    // 主机队列快照版本前进 → 防抖重拉 getQueue（队列面板数据源）
    // 主机歌词版本前进 → 防抖重拉 getLyric（从设备歌词使用主机来源）
    if (typeof data.lyricRevision === "number") onLanSyncLyricRevision(data.lyricRevision, hostIp);
    if (typeof data.queueRevision === "number") onLanSyncQueueRevision(data.queueRevision, hostIp);

    // 切歌：track 字段出现、id 变化、且未在加载中 → 加载
    // 注意：await 等待加载完成后再同步进度/状态，避免在音轨未就绪时 seek 被丢弃
    if (data.track) {
      if (data.audioReady === false) return;
      await loadHostTrack(data.track, data.audioRevision, data.audioUrl);
      const expectedKey = `${data.track.id}:${data.audioRevision ?? 0}`;
      if (loadedKey !== expectedKey) return;
    }

    // 进度与播放状态必须每包都处理
    if (data.position !== undefined) {
      const projectedPosition =
        typeof data.ts === "number"
          ? projectHostPosition(
              data.position,
              data.ts,
              data.playing ?? state.playing,
              data.duration ?? state.duration,
            )
          : data.position;
      if (data.syncSeek === true) {
        // 恢复播放对齐：以操作方精确时间轴无条件 seek（跳过容差），消除多设备出声回音
        state.position = projectedPosition;
        withApplyingRemoteState(() => playerSeek(projectedPosition)).catch(() => {});
      } else {
        // 为了防止网络抖动导致的短时间频繁回调引起的UI跳跃，我们可以在这里做一层平滑
        syncPosition(projectedPosition);
      }
    }
    if (data.playing !== undefined) syncPlayState(data.playing);
  };

  /** 上送遥控指令到主机（连接就绪才发送，断线时静默丢弃，等主机广播保证最终一致） */
  const sendCommand = (action: LanRemoteAction, value?: number): void => {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    try {
      ws.send(JSON.stringify({ type: "command", action, value, ts: Date.now() }));
      // 恢复播放时附带本机精确位置：主机 seek 对齐后广播 syncSeek，全员校准消除回音
      if (action === "play" || (action === "toggle" && !state.playing)) {
        ws.send(
          JSON.stringify({
            type: "command",
            action: "syncSeek",
            value: Math.round(getCurrentTime()),
            ts: Date.now(),
          }),
        );
      }
    } catch {}
  };

  const connect = async (): Promise<void> => {
    if (destroyed || ws) return;
    const host = hostIp || window.location.hostname || "127.0.0.1";
    try {
      const hostStatus = await fetchHostStatus();
      if (destroyed || ws) return;
      wsToken = hostStatus.wsToken || "";
      if (!hostStatus.enabled || !hostStatus.collabEnabled || !wsToken) {
        scheduleReconnect(false, AUTH_RETRY_DELAY_MS);
        return;
      }
    } catch {
      scheduleReconnect();
      return;
    }
    const tokenParam = wsToken ? `?token=${encodeURIComponent(wsToken)}` : "";
    const wsUrl = `ws://${host}:${EMBEDDED_API_PORT}/api/lanShare/ws${tokenParam}`;
    try {
      ws = new WebSocket(wsUrl);
    } catch {
      scheduleReconnect();
      return;
    }
    ws.onopen = () => {
      state.connected = true;
      reconnectAttempts = 0; // 连接成功后重置重连计数
      console.info("[lan-sync] 已连接到主机", wsUrl);
      startTimeSync();
      // 歌词同样立即拉一次快照，保证首次连接即有歌词显示
      void refreshLanHostLyric(hostIp);
      // 立即拉一次队列快照，不等下个 revision（重连场景尤其需要）
      void refreshLanHostQueue(hostIp);
    };
    ws.onmessage = (event) => {
      try {
        const d = JSON.parse(event.data as string) as SyncPayload & {
          action?: LanRemoteAction;
          deviceName?: string;
          clientTs?: number;
          serverTs?: number;
        };
        if (d.type === "splayer-sync") {
          void handleSync(d);
        } else if (
          d.type === "time-sync-response" &&
          typeof d.clientTs === "number" &&
          typeof d.serverTs === "number"
        ) {
          updateHostClockOffset(d.clientTs, d.serverTs);
        } else if (d.type === "action-notify" && d.action && d.deviceName) {
          showActionNotify(d.action, d.deviceName);
        }
      } catch {}
    };
    ws.onclose = () => {
      // 避免断开时将状态清空导致闪屏
      // 仅仅更新连接状态标志位，后续等待重新连接成功再拉取全量同步
      state.connected = false;
      stopTimeSync();
      ws = null;
      if (!destroyed) scheduleReconnect();
    };
    ws.onerror = () => ws?.close();
  };

  const showActionNotify = (action: LanRemoteAction, deviceName: string) => {
    const actionTextMap: Record<LanRemoteAction, string> = {
      play: "播放",
      pause: "暂停",
      toggle: "切换播放状态",
      next: "切换下一首",
      prev: "切换上一首",
      seek: "调整进度",
      setSpeed: "调整播放速度",
      setPitch: "调整音调",
      playAt: "点播歌曲",
      syncSeek: "对齐时间轴",
    };
    const actionText = actionTextMap[action] || action;
    import("@/composables/useToast").then(({ toast }) => {
      toast.info(`${deviceName} 已${actionText}`);
    });
  };

  const scheduleReconnect = (countAttempt = true, delayMs = RECONNECT_DELAY_MS): void => {
    if (destroyed) return;
    // 超过最大重连次数后停止，避免主机永久不可达时无限重连耗电
    if (countAttempt && reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      console.warn("[lan-sync] 达到最大重连次数，停止重连");
      return;
    }
    if (countAttempt) reconnectAttempts++;

    // 如果已经有一个在排队重连的定时器，先清除掉，防止重入或者堆积重连请求
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }

    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      void connect();
    }, delayMs);
  };

  const disconnect = (): void => {
    destroyed = true;
    resetLanHostLyric();
    registerLanRemoteSink(null);
    resetLanHostQueue();
    for (const off of gestureHandlers.splice(0)) off();
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    stopTimeSync();
    if (ws) {
      ws.close();
      ws = null;
    }
    state.connected = false;
  };

  onMounted(async () => {
    // 本机用户的播放控制经 core/player 拦截后上送主机
    registerLanRemoteSink((action, value) => sendCommand(action, value));
    await connect();
  });
  onUnmounted(() => disconnect());

  return { state, disconnect };
}
