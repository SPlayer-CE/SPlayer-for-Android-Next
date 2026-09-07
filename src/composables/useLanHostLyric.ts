/**
 * 局域网协同 — 主机当前歌词镜像（从设备端）。
 * 快照经 /api/lanShare/getLyric 拉取；sync 载荷里 lyricRevision 前进时防抖重拉。
 * 拿到后经 media.setLyric 进入标准渲染链路（歌词窗口 / 全屏播放页零改动）。
 * 与 useLanHostQueue 同构：失败静默，等下个 revision 驱动重试。
 */
import type { LyricData, LyricInput } from "@shared/types/lyrics";
import { EMBEDDED_API_PORT } from "@/utils/embeddedApi";
import { useMediaStore } from "@/stores/media";

const REFRESH_DEBOUNCE_MS = 500;

let hostLyricRevision = -1;
let refreshTimer: ReturnType<typeof setTimeout> | null = null;
let refreshInFlight = false;

const hostBaseUrl = (hostIp: string): string =>
  `http://${hostIp || window.location.hostname || "127.0.0.1"}:${EMBEDDED_API_PORT}`;

interface HostLyricPayload {
  source?: LyricData;
  input?: LyricInput | null;
}

/** 拉取一次主机歌词快照；失败静默，等下个 revision 驱动重试 */
export const refreshLanHostLyric = async (hostIp: string): Promise<void> => {
  if (refreshInFlight) return;
  refreshInFlight = true;
  try {
    const res = await fetch(`${hostBaseUrl(hostIp)}/api/lanShare/getLyric`, { cache: "no-store" });
    if (!res.ok) return;
    const json = (await res.json()) as {
      ok?: boolean;
      revision?: number;
      data?: HostLyricPayload | null;
    };
    if (!json?.ok) return;
    const revision = typeof json.revision === "number" ? json.revision : -1;
    if (revision >= 0 && revision <= hostLyricRevision) return;
    hostLyricRevision = revision;
    const payload = json.data ?? null;
    useMediaStore().setLyric(payload?.source ?? null, payload?.input ?? null);
  } catch {
    // 静默：歌词镜像非关键路径，等下个 revision 或重连重拉
  } finally {
    refreshInFlight = false;
  }
};

/** sync 载荷回调：revision 前进才安排刷新（合并突发推送） */
export const onLanSyncLyricRevision = (revision: number, hostIp: string): void => {
  if (revision <= hostLyricRevision) return;
  if (refreshTimer) return;
  refreshTimer = setTimeout(() => {
    refreshTimer = null;
    void refreshLanHostLyric(hostIp);
  }, REFRESH_DEBOUNCE_MS);
};

/** 断开/退出从设备模式时重置版本号（回退本机歌词链路） */
export const resetLanHostLyric = (): void => {
  hostLyricRevision = -1;
  if (refreshTimer) {
    clearTimeout(refreshTimer);
    refreshTimer = null;
  }
};
