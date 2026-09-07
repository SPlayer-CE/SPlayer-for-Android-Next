/**
 * 局域网协同同步 — 主机推送端。
 * 监听播放状态变化，高频推送到已连接的从设备。
 * 推送内容：进度（~12Hz）、歌曲元信息、歌词索引、封面。
 * 歌曲切换时额外推送完整歌词数据。
 */
import { effectScope } from "vue";
import bridge, { isAndroidNative } from "@/services/bridge";
import { useStatusStore } from "@/stores/status";
import { useMediaStore } from "@/stores/media";
import { queue } from "@/stores/queue";
import { isLanSyncReceiver, onLanSyncRoleChanged } from "@/composables/useLanSyncRole";
import { EMBEDDED_API_PORT } from "@/utils/embeddedApi";
import {
  play,
  pause,
  togglePlay,
  seek,
  nextTrack,
  prevTrack,
  playAtIndex,
  setSpeed,
  setPitch,
} from "@/core/player";
import {
  registerHostActionSink,
  withApplyingRemoteState,
  type LanRemoteAction,
} from "@/composables/lanSyncRemote";

const PUSH_THROTTLE_MS = 80; // ~12Hz
const COMMAND_WS_RECONNECT_MS = 2000;

let instance: ReturnType<typeof createLanSyncHost> | null = null;

export function useLanSyncHost() {
  if (!instance) {
    const scope = effectScope(true);
    instance = scope.run(() => createLanSyncHost())!;
  }
  return instance;
}

export const initLanSyncHost = async (): Promise<void> => {
  const syncHost = useLanSyncHost();
  try {
    const status = await bridge.lanShare.getStatus();
    if (status.collabEnabled && status.enabled && !isLanSyncReceiver()) {
      syncHost.start();
    }
  } catch {}
};

function createLanSyncHost() {
  const status = useStatusStore();
  const media = useMediaStore();
  const { isPlaying, position, duration, currentSource } = storeToRefs(status);

  let lastPushTs = 0;
  let enabled = false;
  let starting = false;
  let lastTrackId: string | null = null;

  const doPush = (forceFull = false, syncSeek = false): void => {
    if (!enabled || isLanSyncReceiver()) return;
    const now = Date.now();
    if (!forceFull && now - lastPushTs < PUSH_THROTTLE_MS) return;
    lastPushTs = now;

    const track = media.track;
    const trackChanged = track?.id !== lastTrackId;
    if (trackChanged) lastTrackId = track?.id ?? null;

    const payload: Record<string, unknown> = {
      position: position.value,
      playing: isPlaying.value,
      duration: duration.value,
      lyricIndex: media.lyricIndex,
      ts: now,
    };

    // 恢复播放对齐标记：从设备见到后无条件 seek 到本包位置，不走容差
    if (syncSeek) payload.syncSeek = true;

    // 下发完整 Track，让从设备也能按主设备链路重新解析音频。
    if (track) {
      payload.track = track;
      if (currentSource.value) payload.audioSource = currentSource.value;
      // 歌词数据较大，仅在切歌或强制推送时下发
      if ((trackChanged || forceFull) && media.parsedLyric.length > 0) {
        payload.lyrics = media.parsedLyric.map((line) => ({
          startTime: line.startTime,
          endTime: line.endTime,
          words:
            line.words?.map((w) => ({
              word: w.word,
              startTime: w.startTime,
              endTime: w.endTime,
            })) || [],
          translatedLyric: line.translatedLyric,
          isBG: line.isBG,
        }));
      }
    }

    // 优先经 WS 推送（持久连接、低延迟）；WS 未就绪时回退 HTTP POST
    if (cmdWs && cmdWs.readyState === WebSocket.OPEN) {
      try {
        cmdWs.send(JSON.stringify({ type: "sync", ...payload }));
        return;
      } catch {}
    }
    bridge.lanShare.broadcastPlayback(payload).catch(() => {});
  };

  /** 构建播放队列快照（仅裁剪显示/点播必需字段，远端封面 URL 直出，不经主机代理） */
  const buildQueueJson = (): string => {
    const tracks = queue.value.map((t) => ({
      id: t.id,
      source: t.source,
      title: t.title,
      artists: t.artists,
      album: t.album,
      duration: t.duration,
      cover: t.cover,
    }));
    return JSON.stringify({ playIndex: status.playIndex, tracks });
  };

  let lastQueuePushJson = "";
  /** 推送队列快照到 Kotlin 缓存（内容去重；Kotlin 侧每次接收自增 queueRevision） */
  const pushQueue = (): void => {
    // 快照属 LAN 共享语义，不依赖协同开关：推送给 Kotlin 缓存即可，getQueue 路由按共享开关鉴权
    if (!isAndroidNative || isLanSyncReceiver()) return;
    const json = buildQueueJson();
    if (json === lastQueuePushJson) return;
    lastQueuePushJson = json;
    bridge.lanShare.updateQueue(json).catch(() => {});
  };

  let lastLyricPushJson: string | null = null;
  /** 推送当前歌词快照到 Kotlin 缓存（引用变化即推；Kotlin 侧每次接收自增 lyricRevision） */
  const pushLyric = (): void => {
    // 歌词快照属 LAN 共享语义：推送给 Kotlin 缓存即可，getLyric 路由按共享开关鉴权
    if (!isAndroidNative || isLanSyncReceiver()) return;
    const json = JSON.stringify({ source: media.activeLyric, input: media.lyricContent });
    if (json === lastLyricPushJson) return;
    lastLyricPushJson = json;
    bridge.lanShare.updateLyric(json).catch(() => {});
  };

  // 监听播放状态变化
  watch([isPlaying, position, duration], () => doPush(), { immediate: false });

  // 暂停→恢复播放（本地或遥控）→ 广播对齐标记，从设备强制 seek 消除多设备回音
  watch(
    () => status.state,
    (now, prev) => {
      if (prev === "paused" && now === "playing") doPush(true, true);
    },
  );

  // 队列增删/重排/在播索引变化 → 刷新快照
  watch([queue, () => status.playIndex], () => pushQueue(), { immediate: false });

  // 歌词加载/切换 → 刷新快照（beginLoad 置空与 setLyric 提交各推一次，从设备先清后显）
  watch(
    () => media.activeLyric,
    () => pushLyric(),
  );

  // 监听歌词索引变化
  watch(
    () => media.lyricIndex,
    () => doPush(),
  );

  // 监听歌曲切换 → 推送完整数据，并在 1.5 秒后主动同步一次精准时间
  let forceSyncTimer: ReturnType<typeof setTimeout> | null = null;
  watch(
    () => media.track?.id,
    (newId) => {
      if (!enabled || !newId) return;
      doPush(true);

      if (forceSyncTimer) clearTimeout(forceSyncTimer);
      forceSyncTimer = setTimeout(() => {
        if (enabled) doPush(true);
      }, 1500);
    },
  );

  // RAF 高频循环
  let rafId = 0;
  const rafLoop = (): void => {
    if (!enabled) return;
    doPush();
    rafId = requestAnimationFrame(rafLoop);
  };

  // ── 命令接收 WS：从设备遥控指令经 server 转发到本机（role=host），在主机队列上执行 ──
  let cmdWs: WebSocket | null = null;
  let cmdReconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let wsToken = "";

  const commandWsUrl = (): string => {
    const host = isAndroidNative ? "127.0.0.1" : window.location.hostname || "127.0.0.1";
    const tokenParam = wsToken ? `&token=${encodeURIComponent(wsToken)}` : "";
    return `ws://${host}:${EMBEDDED_API_PORT}/api/lanShare/ws?role=host${tokenParam}`;
  };

  const execCommand = (action: LanRemoteAction, value?: number): void => {
    // 包装为正在应用远程状态，避免再次触发本地的主设备操作通知
    withApplyingRemoteState(() => {
      switch (action) {
        case "next":
          nextTrack(true);
          break;
        case "prev":
          prevTrack();
          break;
        case "play":
          play();
          break;
        case "pause":
          pause();
          break;
        case "toggle":
          togglePlay();
          break;
        case "seek":
          if (typeof value === "number") seek(value);
          break;
        case "setSpeed":
          if (typeof value === "number") setSpeed(value);
          break;
        case "setPitch":
          if (typeof value === "number") setPitch(value);
          break;
        case "playAt":
          if (typeof value === "number") playAtIndex(value);
          break;
        case "syncSeek":
          // 操作方恢复播放后上报精确位置：主机 seek 对齐，并广播对齐标记让全员校准
          if (typeof value === "number") {
            seek(value);
            doPush(true, true);
          }
          break;
      }
    });
  };

  const scheduleCommandReconnect = (): void => {
    if (!enabled || cmdReconnectTimer) return;
    cmdReconnectTimer = setTimeout(() => {
      cmdReconnectTimer = null;
      connectCommandWs();
    }, COMMAND_WS_RECONNECT_MS);
  };

  const connectCommandWs = (): void => {
    if (!enabled || cmdWs) return;
    try {
      cmdWs = new WebSocket(commandWsUrl());
    } catch {
      scheduleCommandReconnect();
      return;
    }
    cmdWs.onopen = () => {
      // WS 就绪即强制推送一次完整状态，确保从设备第一时间收到当前曲目
      doPush(true);
    };
    cmdWs.onmessage = (event) => {
      try {
        const d = JSON.parse(event.data as string) as {
          type?: string;
          action?: LanRemoteAction;
          value?: number;
          deviceName?: string;
        };
        if (d.type === "command" && d.action) {
          execCommand(d.action, d.value);
          // 当收到从设备发来的控制指令时，主设备也需要弹出提示
          if (d.deviceName) showActionNotify(d.action, d.deviceName);
        } else if (d.type === "action-notify" && d.action && d.deviceName) {
          showActionNotify(d.action, d.deviceName);
        }
      } catch {}
    };
    cmdWs.onclose = () => {
      cmdWs = null;
      if (enabled) scheduleCommandReconnect();
    };
    cmdWs.onerror = () => cmdWs?.close();
  };

  const showActionNotify = (action: LanRemoteAction, deviceName: string): void => {
    // syncSeek 为内部对齐动作，不打扰用户
    if (action === "syncSeek") return;
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

  const sendHostManualAction = (action: LanRemoteAction, value?: number) => {
    if (!cmdWs || cmdWs.readyState !== WebSocket.OPEN) return;
    try {
      cmdWs.send(JSON.stringify({ type: "action-notify", action, value, ts: Date.now() }));
    } catch {}
  };

  const disconnectCommandWs = (): void => {
    if (cmdReconnectTimer) {
      clearTimeout(cmdReconnectTimer);
      cmdReconnectTimer = null;
    }
    if (cmdWs) {
      cmdWs.close();
      cmdWs = null;
    }
  };

  const start = async (): Promise<void> => {
    // 仅真实原生主机可广播；浏览器预览（从设备打开主机页面）无嵌入式服务，广播会回灌主机造成冲突
    if (!isAndroidNative) {
      stop();
      return;
    }
    if (isLanSyncReceiver()) {
      stop();
      return;
    }
    if (enabled || starting) return;
    starting = true;
    // 获取 WS 鉴权 token，本机连接虽免 token 但携带以保持链路一致
    try {
      const status = await bridge.lanShare.getStatus();
      wsToken = status.wsToken || "";
    } catch {
      wsToken = "";
    }
    starting = false;
    enabled = true;
    lastTrackId = null; // 强制首次推送完整数据
    pushQueue();
    pushLyric();
    rafId = requestAnimationFrame(rafLoop);
    connectCommandWs();
    registerHostActionSink(sendHostManualAction);
  };

  const stop = (): void => {
    enabled = false;
    registerHostActionSink(null);
    if (rafId) {
      cancelAnimationFrame(rafId);
      rafId = 0;
    }
    if (forceSyncTimer) {
      clearTimeout(forceSyncTimer);
      forceSyncTimer = null;
    }
    disconnectCommandWs();
  };

  onLanSyncRoleChanged(async (hostIp) => {
    if (hostIp) {
      // 切换为从设备：停止主机广播
      stop();
    } else {
      // 切换回主设备：若协同已开启则自动恢复广播
      try {
        const status = await bridge.lanShare.getStatus();
        if (status.collabEnabled && status.enabled) start();
      } catch {}
    }
  });

  return { start, stop };
}
