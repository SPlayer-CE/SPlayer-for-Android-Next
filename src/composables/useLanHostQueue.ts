/**
 * 局域网协同 — 主机播放队列镜像（从设备端）。
 * 快照经 /api/lanShare/getQueue 拉取；sync 载荷里 queueRevision 前进时防抖重拉。
 * QueuePanel/QueuePopover 经 useQueuePanel 的数据源分支消费，本模块不耦合 WS 细节。
 */
import type { Track } from "@shared/types/player";
import { EMBEDDED_API_PORT } from "@/utils/embeddedApi";

/** 主机入队快照里的曲目字段（与主机 buildQueueJson 一致） */
interface LanQueueTrackPayload {
  id?: string | number;
  source?: Track["source"];
  title?: string;
  artists?: Track["artists"];
  album?: Track["album"];
  duration?: number;
  cover?: string;
}

export const hostQueue = shallowRef<Track[]>([]);
export const hostQueuePlayIndex = ref(-1);

const REFRESH_DEBOUNCE_MS = 500;

let hostQueueRevision = -1;
let refreshTimer: ReturnType<typeof setTimeout> | null = null;
let refreshInFlight = false;

const hostBaseUrl = (hostIp: string): string =>
  `http://${hostIp || window.location.hostname || "127.0.0.1"}:${EMBEDDED_API_PORT}`;

/** 拉取一次主机队列快照；失败静默，等下个 revision 驱动重试 */
export const refreshLanHostQueue = async (hostIp: string): Promise<void> => {
  if (refreshInFlight) return;
  refreshInFlight = true;
  try {
    const res = await fetch(`${hostBaseUrl(hostIp)}/api/lanShare/getQueue`, { cache: "no-store" });
    if (!res.ok) return;
    const json = (await res.json()) as {
      ok?: boolean;
      revision?: number;
      data?: { playIndex?: number; tracks?: LanQueueTrackPayload[] };
    };
    if (!json?.ok || !json.data) return;
    const revision = typeof json.revision === "number" ? json.revision : -1;
    if (revision >= 0 && revision <= hostQueueRevision) return;
    hostQueueRevision = revision;
    const tracks = Array.isArray(json.data.tracks) ? json.data.tracks : [];
    hostQueue.value = tracks.map((t) => ({
      id: String(t.id ?? ""),
      source: t.source ?? "netease",
      title: t.title ?? "",
      artists: Array.isArray(t.artists) ? t.artists : [],
      album: t.album,
      duration: typeof t.duration === "number" ? t.duration : 0,
      cover: t.cover,
    }));
    hostQueuePlayIndex.value = typeof json.data.playIndex === "number" ? json.data.playIndex : -1;
  } catch {
    // 静默：队列镜像非关键路径，等下个 revision 或下次打开面板重拉
  } finally {
    refreshInFlight = false;
  }
};

/** sync 载荷回调：revision 前进才安排刷新（合并突发推送） */
export const onLanSyncQueueRevision = (revision: number, hostIp: string): void => {
  if (revision <= hostQueueRevision) return;
  if (refreshTimer) return;
  refreshTimer = setTimeout(() => {
    refreshTimer = null;
    void refreshLanHostQueue(hostIp);
  }, REFRESH_DEBOUNCE_MS);
};

/** 断开/退出从设备模式时清空镜像 */
export const resetLanHostQueue = (): void => {
  hostQueue.value = [];
  hostQueuePlayIndex.value = -1;
  hostQueueRevision = -1;
  if (refreshTimer) {
    clearTimeout(refreshTimer);
    refreshTimer = null;
  }
};
