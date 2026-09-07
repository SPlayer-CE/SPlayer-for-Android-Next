/**
 * 平台抽象层（Bridge）
 *
 * 将前端对 window.api.* 的调用在 Android 和 Electron 两个平台上分别路由到不同的后端实现。
 * - Electron：直接透传到 window.api.*
 * - Android：路由到嵌入式本地 API（fetch）或 Capacitor 原生插件
 */

import type {
  ConfigApi,
  SystemConfig,
  LocaleCode,
  ExternalApiStatus,
  McpStatus,
  McpClientConfigParams,
  McpAgentApp,
  TaskbarLyricSettings,
  DynamicIslandSettings,
} from "@shared/types/settings";
import type { AiModelState, AiModelSaveInput } from "@shared/types/ai";
import type { CloudUploadResult, PickedSong } from "@shared/types/cloudUpload";
import type { CjkTransformMode } from "@shared/types/opencc";
import { Capacitor, registerPlugin, type PluginListenerHandle } from "@capacitor/core";
import type { AndroidLibraryPlugin } from "@/plugins/androidLibrary";
import type { AndroidDownloadPlugin } from "@/plugins/androidDownload";
import type { AndroidLocalLyricPlugin } from "@/plugins/androidLocalLyric";
import type {
  AndroidCachePlugin,
  AndroidCacheType,
  AndroidDbCacheCategory,
} from "@/plugins/androidCache";
import type { AndroidAppIconPlugin, AndroidAppIconVariant } from "@/plugins/androidAppIcon";
import { AndroidSongCache } from "@/plugins/androidSongCache";
import { AndroidLanShare, type LanDevice } from "@/plugins/androidLanShare";
import type {
  PlayerApi,
  TrackSource,
  IpcResponse,
  LoadOptions,
  PlayerStatus,
  PlayerState,
  AudioDevice,
  PlayerEvent,
  LoadResult,
  Track,
  FftData,
} from "@shared/types/player";
import type { LibraryApi, ScanProgress } from "@shared/types/library";
import type {
  DownloadApi,
  DownloadRequest,
  DownloadResolution,
  DownloadResolvePayload,
  DownloadTask,
  DownloadProgress,
  EnqueueResult,
} from "@shared/types/download";
import type {
  WindowApi,
  DesktopLyricApi,
  DesktopLyricUnlockButtonBounds,
  DynamicIslandApi,
  TaskbarLyricApi,
  TaskbarLyricLayoutEvent,
} from "@shared/types/window";
import type {
  PluginsApi,
  PluginInfo,
  PluginInvokeMenuArgs,
  PluginInvokeMenuResult,
  PluginMatchCoverArgs,
  PluginMatchCoverResult,
  PluginMatchLyricArgs,
  PluginMatchLyricResult,
  MarketPlugin,
  PluginResolveUrlArgs,
  MusicUrlRes,
} from "@shared/types/plugin";
import type { ApisApi, ApiPlatform, ApiCallResponse } from "@shared/types/apis";
import type { LyricsApi, LyricMatchResponse, LyricTTMLResponse } from "@shared/types/lyrics";
import type {
  NowPlayingApi,
  NowPlayingSnapshot,
  NowPlayingPositionSync,
  NowPlayingLyricOffsetSync,
  NowPlayingUpdatePayload,
} from "@shared/types/nowPlaying";
import type {
  HotkeyApi,
  HotkeyActionId,
  HotkeyBinding,
  HotkeyConfig,
  HotkeyConflict,
} from "@shared/types/hotkey";
import type {
  StreamingApi,
  StreamingConnectResult,
  StreamingLibrarySnapshot,
  StreamingPingResult,
  StreamingSearchResult,
  StreamingServerConfig,
  StreamingServerInput,
} from "@shared/types/streaming";
import type { LastfmApi, LastfmConnectResult, LastfmStatus } from "@shared/types/lastfm";
import type {
  StatsApi,
  PlayEventInput,
  FavoriteEventInput,
  PlayStatsSummary,
  TopTrack,
  LibraryStats,
  DailyPlayStats,
  HourlyPlayStats,
  TopAlbum,
  TopArtist,
} from "@shared/types/stats";
import type { UpdateApi, UpdateEvent } from "@shared/types/update";
import type { PlaylistApi } from "@shared/types/playlist";
import type { RecognitionApi } from "@shared/types/recognition";
import type { TrackTags, TagEditRequest, TagWriteOutcome } from "@shared/types/tagEditor";
import type { Platform } from "@shared/types/platform";
import type {
  CommentsApi,
  CommentSource,
  MusicCommentQuery,
  MusicCommentResponse,
} from "@shared/types/comment";
import { defaultSystemConfig } from "@shared/defaults/settings";
import { defaultHotkeyConfig } from "@shared/defaults/hotkeys";
import { useSettingsStore } from "@/stores/settings";
import { toggleEruda, reportNetworkEntry } from "@/composables/useEruda";

// ─── 平台检测 ────────────────────────────────────────────────────────────────

export const isAndroid =
  typeof __SPLAYER_TARGET__ !== "undefined" && __SPLAYER_TARGET__ === "android";

/** 真实 Capacitor Android 容器；浏览器预览 Android UI 时为 false。 */
export const isAndroidNative = isAndroid && Capacitor.isNativePlatform();
/** 浏览器预览 Android UI（如从设备打开主机 IP 页面）；此时无法运行嵌入式服务，不能充当广播主机。 */
export const isAndroidPreview = isAndroid && !isAndroidNative;

/**
 * 将 file:// / content:// URI 转为 Capacitor WebView 可加载的 URL。
 * Android WebView 禁止直接加载 file:// 协议图片，需经 Capacitor.convertFileSrc 代理。
 * 非 Android 或已是代理 URL / http(s) / data: / blob: 则原样返回。
 * @param url - 原始封面 URL
 * @returns WebView 可加载的 URL
 */
export const resolveCoverUrl = (url: string | undefined | null): string | undefined => {
  if (!url) return undefined;
  if (isAndroid && (url.startsWith("file://") || url.startsWith("content://"))) {
    try {
      return Capacitor.convertFileSrc(url);
    } catch {
      return url;
    }
  }
  return url;
};

// ─── 嵌入式 API 端口管理 ─────────────────────────────────────────────────────

let bridgePort = 0;
let androidEmbeddedApiAvailable = false;
/**
 * 嵌入式 API 就绪 Promise。由 main.ts 调 setEmbeddedApiReadyPromise 注入。
 * apiFetch 在 isAndroid 且未 ready 时会 await 该 promise（不再立刻抛错），
 * 解决组件 onMounted 触发的 API 早于 embedded API ready 的 race。
 */
let embeddedApiReadyPromise: Promise<unknown> | null = null;

/** 由 Android 端通过 Capacitor 事件或 URL 参数传入端口号 */
export function setApiPort(port: number): void {
  bridgePort = port;
}

export function setAndroidEmbeddedApiAvailable(available: boolean): void {
  androidEmbeddedApiAvailable = available;
}

/** main.ts 启动时调用，把 waitForEmbeddedApiReady() 注入给 bridge */
export function setEmbeddedApiReadyPromise(p: Promise<unknown>): void {
  embeddedApiReadyPromise = p;
}

// ─── Capacitor 插件懒加载 ────────────────────────────────────────────────────

interface AndroidNativePlaybackPlugin {
  load: (options: {
    url: string;
    positionMs: number;
    autoPlay: boolean;
  }) => Promise<IpcResponse<LoadResult>>;
  play: () => Promise<IpcResponse>;
  /** 切下一首（原生队列权威推进） */
  next: () => Promise<IpcResponse>;
  /** 切上一首（原生队列权威推进） */
  previous: () => Promise<IpcResponse>;
  /** 播放指定索引的曲目（原生解析并开播；positionMs 为起播进度） */
  playIndex: (options: { index: number; positionMs?: number }) => Promise<IpcResponse>;
  pause: () => Promise<IpcResponse>;
  stop: () => Promise<IpcResponse>;
  seek: (options: { positionMs: number }) => Promise<IpcResponse>;
  setVolume: (options: { volume: number }) => Promise<IpcResponse>;
  setSpeed: (options: { speed: number }) => Promise<IpcResponse>;
  updateMetadata: (options: Record<string, unknown>) => Promise<void>;
  updateQueueContext: (options: Record<string, unknown>) => Promise<void>;
  updateNotificationPrefs: (options: Record<string, unknown>) => Promise<void>;
  setAllowMixWithOthers: (options: { allow: boolean }) => Promise<void>;
  setShowStatusBar: (options: { show: boolean }) => Promise<void>;
  setHideNavigationBar: (options: { hide: boolean }) => Promise<void>;
  setImmersiveLandscape: (options: { active: boolean }) => Promise<void>;
  syncApiContext: (options: Record<string, unknown>) => Promise<void>;
  getStatus: () => Promise<IpcResponse<PlayerStatus>>;
  syncRemoteState: (options: Record<string, unknown>) => Promise<void>;
  requestNotificationPermission: () => Promise<{ granted: boolean }>;
  showDynamicIsland: () => Promise<void>;
  hideDynamicIsland: () => Promise<void>;
  isDynamicIslandRunning: () => Promise<{ running: boolean }>;
  updateDynamicIslandData: (options: Record<string, unknown>) => Promise<void>;
  updateDynamicIslandProgress: (options: Record<string, unknown>) => Promise<void>;
  updateDynamicIslandSongInfo: (options: Record<string, unknown>) => Promise<void>;
  updateDynamicIslandConfig: (options: Record<string, unknown>) => Promise<void>;
  checkOverlayPermission: () => Promise<{ granted: boolean }>;
  requestOverlayPermission: () => Promise<{ granted: boolean }>;
  prefetchAudio: (options: { url: string }) => Promise<void>;
  isPromotedAudioReady: (options: { url: string }) => Promise<{ ready: boolean }>;
  setFftEnabled: (options: { enabled: boolean }) => Promise<IpcResponse>;
  setSpectrumAlgorithm: (options: { mode: string }) => Promise<IpcResponse>;
  setEqualizerEnabled: (options: { enabled: boolean }) => Promise<IpcResponse>;
  setEqualizerBands: (options: { gainsDb: number[] }) => Promise<IpcResponse>;
  setPreampGain: (options: { preampDb: number }) => Promise<IpcResponse>;
  cleanup: () => Promise<IpcResponse>;
  moveTaskToBack: () => Promise<void>;
  shutdownApp: () => Promise<void>;
  /** 监听原生播放事件（Capacitor 插件事件） */
  addListener: (
    eventName: string,
    callback: (data: unknown) => void,
  ) => Promise<{ remove: () => void }>;
}

let _playbackPlugin: AndroidNativePlaybackPlugin | null = null;

function getPlaybackPlugin(): AndroidNativePlaybackPlugin {
  if (_playbackPlugin) return _playbackPlugin;
  _playbackPlugin = registerPlugin<AndroidNativePlaybackPlugin>("AndroidNativePlayback");
  return _playbackPlugin!;
}

// ─── Android Library / Download / LocalLyric 插件懒加载 ──────────────────

let _androidLibraryPlugin: AndroidLibraryPlugin | null = null;

/** 获取 AndroidLibrary 插件（本地音乐库管理） */
function getAndroidLibrary(): AndroidLibraryPlugin {
  if (_androidLibraryPlugin) return _androidLibraryPlugin;
  _androidLibraryPlugin = registerPlugin<AndroidLibraryPlugin>("AndroidLibrary");
  return _androidLibraryPlugin!;
}

let _androidDownloadPlugin: AndroidDownloadPlugin | null = null;

/** 获取 AndroidDownload 插件（SAF 下载目录 + 文件操作） */
function getAndroidDownload(): AndroidDownloadPlugin {
  if (_androidDownloadPlugin) return _androidDownloadPlugin;
  _androidDownloadPlugin = registerPlugin<AndroidDownloadPlugin>("AndroidDownload");
  return _androidDownloadPlugin!;
}

let _androidAppIconPlugin: AndroidAppIconPlugin | null = null;

/** 获取 AndroidAppIcon 插件（桌面图标颜色变体切换） */
function getAndroidAppIcon(): AndroidAppIconPlugin {
  if (_androidAppIconPlugin) return _androidAppIconPlugin;
  _androidAppIconPlugin = registerPlugin<AndroidAppIconPlugin>("AndroidAppIcon");
  return _androidAppIconPlugin!;
}

interface AndroidClipboardPlugin {
  writeText: (options: { text: string }) => Promise<void>;
  readText: () => Promise<{ text: string }>;
}

let _androidClipboardPlugin: AndroidClipboardPlugin | null = null;

/** 获取 AndroidClipboard 插件（系统剪贴板读写） */
function getAndroidClipboard(): AndroidClipboardPlugin {
  if (_androidClipboardPlugin) return _androidClipboardPlugin;
  _androidClipboardPlugin = registerPlugin<AndroidClipboardPlugin>("AndroidClipboard");
  return _androidClipboardPlugin!;
}

let _androidLocalLyricPlugin: AndroidLocalLyricPlugin | null = null;

/** 获取 AndroidLocalLyric 插件（SAF 歌词目录 + 扫描） */
function getAndroidLocalLyric(): AndroidLocalLyricPlugin {
  if (_androidLocalLyricPlugin) return _androidLocalLyricPlugin;
  _androidLocalLyricPlugin = registerPlugin<AndroidLocalLyricPlugin>("AndroidLocalLyric");
  return _androidLocalLyricPlugin!;
}

let _androidCachePlugin: AndroidCachePlugin | null = null;

/** 获取 AndroidCache 插件（统一缓存管理） */
function getAndroidCache(): AndroidCachePlugin {
  if (_androidCachePlugin) return _androidCachePlugin;
  _androidCachePlugin = registerPlugin<AndroidCachePlugin>("AndroidCache");
  return _androidCachePlugin!;
}

/** 订阅 Android 扫描进度事件，返回同步取消订阅函数 */
function subscribeAndroidScanProgress(callback: (progress: ScanProgress) => void): () => void {
  let handle: PluginListenerHandle | null = null;
  let cancelled = false;
  void getAndroidLibrary()
    .addListener("library:scanProgress", callback)
    .then((h) => {
      if (cancelled) {
        void h.remove();
        return;
      }
      handle = h;
    });
  return () => {
    cancelled = true;
    if (handle) void handle.remove();
  };
}

// ─── Capacitor App 插件（用于 relaunch 退出） ───────────────────────────────

interface CapacitorAppPlugin {
  exit: () => void;
}

let _appPlugin: CapacitorAppPlugin | null = null;

function getAppPlugin(): CapacitorAppPlugin {
  if (_appPlugin) return _appPlugin;
  _appPlugin = registerPlugin<CapacitorAppPlugin>("App");
  return _appPlugin!;
}

// ─── Capacitor ExternalApi 插件（外部 API 服务控制） ──────────────────────

interface AndroidExternalApiPlugin {
  /** 从 Node.js 设置存储同步配置并重启外部 API 服务，返回最新状态 */
  restart: () => Promise<ExternalApiStatus>;
  /** 返回外部 API 服务运行时状态 */
  getStatus: () => Promise<ExternalApiStatus>;
}

let _externalApiPlugin: AndroidExternalApiPlugin | null = null;

function getExternalApiPlugin(): AndroidExternalApiPlugin {
  if (_externalApiPlugin) return _externalApiPlugin;
  _externalApiPlugin = registerPlugin<AndroidExternalApiPlugin>("ExternalApi");
  return _externalApiPlugin!;
}

// ─── 嵌入式 API fetch 工具 ───────────────────────────────────────────────────

/** 请求重试次数 */
const API_FETCH_RETRIES = 2;
/** 首次重试延迟（毫秒） */
const API_FETCH_RETRY_DELAY_MS = 500;
/** ready 事件丢失时的 HTTP 健康检查重试次数 */
const API_READY_HEALTH_RETRIES = 20;

const delay = (ms: number): Promise<void> =>
  new Promise((resolve) => window.setTimeout(resolve, ms));

const IDEMPOTENT_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);
const RETRYABLE_HTTP_STATUSES = new Set([502, 503, 504]);

/** /api/apis/call 的 API 调用本身是幂等的（取二维码 key、轮询状态等），500 时也应重试 */
const RETRYABLE_500_PATHS = new Set(["/api/apis/call"]);
/** /api/apis/call 重试时允许的 HTTP 状态码（含 500） */
const RETRYABLE_HTTP_STATUSES_OR_500 = new Set([500, 502, 503, 504]);

const shouldRetryApiFetch = (error: unknown, method: string, path: string): boolean => {
  if (error instanceof TypeError && error.message.includes("Failed to fetch")) return true;
  if (error instanceof Error && error.name === "AbortError") return true;
  const status =
    typeof error === "object" && error != null
      ? Number((error as { status?: unknown }).status)
      : Number.NaN;
  // 幂等方法 + 502/503/504 → 重试
  if (IDEMPOTENT_METHODS.has(method.toUpperCase()) && RETRYABLE_HTTP_STATUSES.has(status))
    return true;
  // /api/apis/call 的 500/502/503/504 通常是 Node.js Mobile 冷启动瞬态错误，重试可恢复
  if (RETRYABLE_500_PATHS.has(path) && RETRYABLE_HTTP_STATUSES_OR_500.has(status)) return true;
  return false;
};

const probeEmbeddedApi = async (): Promise<boolean> => {
  if (!bridgePort) return false;
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), 1500);
  try {
    const res = await fetch(apiUrl("/api/health"), {
      method: "GET",
      cache: "no-store",
      signal: controller.signal,
    });
    if (!res.ok) return false;
    const body = (await res.json()) as { nodeReady?: boolean };
    return body.nodeReady === true;
  } catch {
    return false;
  } finally {
    window.clearTimeout(timer);
  }
};

const ensureAndroidEmbeddedApiReady = async (): Promise<void> => {
  if (!isAndroidNative || androidEmbeddedApiAvailable) return;
  if (embeddedApiReadyPromise) {
    try {
      await embeddedApiReadyPromise;
    } catch {
      // ready 流程是 soft-fail；下面的 HTTP 健康检查决定是否继续。
    }
  }
  if (androidEmbeddedApiAvailable) return;

  for (let attempt = 0; attempt <= API_READY_HEALTH_RETRIES; attempt++) {
    if (await probeEmbeddedApi()) {
      setAndroidEmbeddedApiAvailable(true);
      return;
    }
    if (attempt < API_READY_HEALTH_RETRIES) {
      await delay(API_FETCH_RETRY_DELAY_MS * (attempt + 1));
    }
  }

  throw new Error("[bridge] embedded API is not ready");
};

async function apiFetch<T = unknown>(path: string, init?: RequestInit): Promise<T> {
  if (!bridgePort) throw new Error("[bridge] API port not set, call setApiPort() first");
  await ensureAndroidEmbeddedApiReady();
  const url = apiUrl(path);
  const method = init?.method ?? "GET";
  const startedAt = performance.now();

  for (let attempt = 0; attempt <= API_FETCH_RETRIES; attempt++) {
    try {
      const res = await fetch(url, init);
      if (!res.ok) {
        let detail = "";
        try {
          const bodyText = (await res.text()).trim();
          if (bodyText) detail = bodyText.slice(0, 300);
        } catch {
          // 忽略错误正文读取失败，保留原始状态码信息
        }
        throw Object.assign(
          new Error(
            `[bridge] API request failed: ${res.status} ${res.statusText}${detail ? ` - ${detail}` : ""}`,
          ),
          { status: res.status },
        );
      }
      reportNetworkEntry({
        method,
        url,
        status: res.status,
        durationMs: Math.round(performance.now() - startedAt),
        ok: true,
      });
      // 必须 await：否则 res.json() 的 SyntaxError 会绕过 try-catch 成为未捕获 rejection
      return await res.json();
    } catch (error) {
      if (shouldRetryApiFetch(error, method, path) && attempt < API_FETCH_RETRIES) {
        await new Promise<void>((resolve) =>
          window.setTimeout(resolve, API_FETCH_RETRY_DELAY_MS * (attempt + 1)),
        );
        continue;
      }
      reportNetworkEntry({
        method,
        url,
        status: 0,
        durationMs: Math.round(performance.now() - startedAt),
        ok: false,
        error: error instanceof Error ? error.message : String(error),
      });
      throw error;
    }
  }
  // 不会到达此处，但 TypeScript 需要返回值
  throw new Error("[bridge] API fetch exhausted retries");
}

/** Android 端直接调用 /api/netease/* REST 路由，绕过 /api/apis/call RPC 包装。
 *  参考SPlayer-for-Android实现：直接 REST 调用能确保响应中的 cookie 字段完整传递。 */
export async function neteaseRestCall<T = unknown>(
  route: string,
  params?: Record<string, unknown>,
): Promise<T> {
  if (!isAndroid) {
    // 桌面端回退到 electronApi
    const res: ApiCallResponse = await electronApi().apis.call("netease", route, params);
    if (!res.ok) throw new NeteaseRestError(route, res.error ?? "unknown error");
    return res.body as T;
  }
  // Android 端直接 GET /api/netease/<route>?<params>
  const search = params
    ? `?${new URLSearchParams(Object.entries(params).map(([k, v]) => [k, String(v)])).toString()}`
    : "";
  return apiFetch<T>(`/api/netease/${route}${search}`, { method: "GET" });
}

class NeteaseRestError extends Error {
  constructor(route: string, message: string) {
    super(`[netease-rest] ${route} failed: ${message}`);
    this.name = "NeteaseRestError";
  }
}

/**
 * 是否「LAN 网页客户端」：经主机 13962 端口访问 SPA 的非本机浏览器。
 * apis/call、setCookie 等凭据路由按设计仅本机可用（KotlinApiServer 对非本机一律 403）；
 * 此类客户端调用前直接早退，不发请求，避免无意义的 403 刷浏览器错误日志。
 */
export const isLanWebClient = (): boolean => {
  if (!isAndroid || isAndroidNative) return false;
  const hostname = window.location.hostname;
  if (hostname === "localhost" || hostname === "127.0.0.1" || hostname === "[::1]") return false;
  return window.location.port === "13962";
};

async function apiPost<T = unknown>(path: string, body: unknown): Promise<T> {
  return apiFetch<T>(path, {
    method: "POST",
    headers: { "Content-Type": "application/json; charset=utf-8" },
    body: JSON.stringify(body),
  });
}

function apiUrl(path: string): string {
  if (!bridgePort) throw new Error("[bridge] API port not set, call setApiPort() first");
  // 真机固定走 nodejs-mobile 回环地址；局域网预览则为实际 LAN IP
  const host = isAndroidNative ? "127.0.0.1" : window.location.hostname || "127.0.0.1";
  return `http://${host}:${bridgePort}${path}`;
}

// ─── noop 工具 ───────────────────────────────────────────────────────────────

const noop = (): void => {};
const noopReturn =
  <T>(val: T) =>
  (): T =>
    val;

const ok = <T>(data?: T): IpcResponse<T> => ({ success: true, data });

/** Android 端通过 input[type=file] 选择插件脚本并上传安装 */
const pickPluginFileAndroid = async (): Promise<{
  ok: boolean;
  id?: string;
  error?: string;
  cancelled?: boolean;
}> => {
  return new Promise((resolve) => {
    const input = document.createElement("input");
    input.type = "file";
    input.accept = ".js,.txt,text/javascript";
    input.style.display = "none";
    let settled = false;
    const done = (r: { ok: boolean; id?: string; error?: string; cancelled?: boolean }): void => {
      if (settled) return;
      settled = true;
      input.remove();
      resolve(r);
    };
    input.addEventListener("change", async () => {
      const file = input.files?.[0];
      if (!file) {
        done({ ok: false, cancelled: true });
        return;
      }
      try {
        const source = await file.text();
        const res = await apiPost<{ ok: boolean; id?: string; error?: string }>(
          "/api/plugins/install",
          { source },
        );
        done(res);
      } catch (err) {
        done({ ok: false, error: err instanceof Error ? err.message : String(err) });
      }
    });
    // 用户取消选择时 window focus 回来且没选文件
    window.addEventListener(
      "focus",
      () => {
        setTimeout(() => {
          if (!settled && (!input.files || input.files.length === 0)) {
            done({ ok: false, cancelled: true });
          }
        }, 1000);
      },
      { once: true },
    );
    document.body.appendChild(input);
    input.click();
  });
};

/**
 * Android 端插件状态订阅：用 HTTP 长轮询模拟桌面端 Electron IPC 的 push 语义。
 * - 桌面端由主进程 `plugin:status` 事件主动推送；Android 无 IPC 通道，旧实现用
 *   `setInterval(tick, 2000)` 全量 GET /api/plugins/list 轮询，空转多、日志刷屏。
 * - 长轮询改为服务端持有 25s，无事件才空回；前端单例循环多路复用，无订阅者时自动停止，
 *   显著降低请求量与尾部延迟。
 * - 页面隐藏（document.hidden）时暂停循环、可见时恢复，避免后台无意义长连接与耗电。
 */
type PluginWatchResponse = {
  events: PluginInfo[];
  cursor: number;
  reset?: boolean;
};

/** 单例 watch 状态：多回调共享同一长轮询循环 */
let pluginWatchCursor = 0;
let pluginWatchPrevById = new Map<string, string>();
const pluginWatchCallbacks = new Set<(info: PluginInfo) => void>();
let pluginWatchLoopActive = false;
let pluginWatchAbort: AbortController | null = null;
let pluginWatchRetryTimer: number | null = null;
let pluginWatchBackoffMs = 2000;
let pluginWatchVisibilityHandlerRegistered = false;
let pluginWatchSuspendedByHidden = false;

/** 长轮询单次请求超时：需 > 服务端 25s 持有时长，留 10s 余量 */
const PLUGIN_WATCH_TIMEOUT_MS = 35_000;
const PLUGIN_WATCH_BACKOFF_INITIAL_MS = 2000;
const PLUGIN_WATCH_BACKOFF_MAX_MS = 30_000;

/** 仅变化才回调，保持与旧轮询一致的 JSON-diff 语义 */
const emitPluginWatchDiffs = (infos: PluginInfo[]): void => {
  for (const info of infos) {
    const key = info.manifest.id;
    const serialized = JSON.stringify(info);
    const prev = pluginWatchPrevById.get(key);
    if (prev !== serialized) {
      pluginWatchPrevById.set(key, serialized);
      for (const cb of pluginWatchCallbacks) {
        try {
          cb(info);
        } catch {
          // 单个回调异常不影响其他订阅者
        }
      }
    }
  }
};

const handlePluginWatchVisibilityChange = (): void => {
  // 页面隐藏时暂停循环：打断进行中的 fetch 与退避定时器，切回后台不做无意义长连接
  if (document.hidden) {
    if (pluginWatchCallbacks.size > 0 && !pluginWatchSuspendedByHidden) {
      pluginWatchSuspendedByHidden = true;
      if (pluginWatchAbort) {
        pluginWatchAbort.abort();
        pluginWatchAbort = null;
      }
      if (pluginWatchRetryTimer) {
        clearTimeout(pluginWatchRetryTimer);
        pluginWatchRetryTimer = null;
      }
    }
    return;
  }
  // 页面可见恢复：若仍有订阅者则重启循环
  if (pluginWatchSuspendedByHidden) {
    pluginWatchSuspendedByHidden = false;
    if (pluginWatchCallbacks.size > 0 && !pluginWatchLoopActive) {
      pluginWatchLoopActive = true;
      void pluginWatchLoop().finally(() => {
        pluginWatchLoopActive = false;
      });
    }
  }
};

/** 单例长轮询循环：复用现有 apiFetch（含重试/ready 探测），仅 watch 请求放宽超时至 35s */
const pluginWatchLoop = async (): Promise<void> => {
  while (pluginWatchCallbacks.size > 0 && !pluginWatchSuspendedByHidden) {
    // 隐藏期间直接退出，待 visible 事件再重启
    if (document.hidden) {
      pluginWatchSuspendedByHidden = true;
      break;
    }
    const controller = new AbortController();
    pluginWatchAbort = controller;
    const timeoutTimer = window.setTimeout(() => controller.abort(), PLUGIN_WATCH_TIMEOUT_MS);
    try {
      // 复用 apiFetch 的重试与 ready 探测；传入 signal 以支持隐藏暂停与超时取消
      // 现有 apiFetch 本身无默认超时，watch 显式放宽到 35s（> 服务端 25s），避免被提前掐断
      const res = await apiFetch<PluginWatchResponse>(
        `/api/plugins/watch?cursor=${pluginWatchCursor}`,
        { signal: controller.signal },
      );
      window.clearTimeout(timeoutTimer);
      pluginWatchAbort = null;

      // cursor 过旧：服务端要求全量重建
      if ((res as { reset?: boolean }).reset) {
        pluginWatchCursor = typeof res.cursor === "number" ? res.cursor : pluginWatchCursor;
        try {
          const list = await apiFetch<PluginInfo[]>("/api/plugins/list");
          const nextPrev = new Map<string, string>();
          for (const info of list) {
            const serialized = JSON.stringify(info);
            const prev = pluginWatchPrevById.get(info.manifest.id);
            if (prev !== serialized) {
              nextPrev.set(info.manifest.id, serialized);
              for (const cb of pluginWatchCallbacks) {
                try {
                  cb(info);
                } catch {}
              }
            } else {
              nextPrev.set(info.manifest.id, serialized);
            }
          }
          // 已卸载的插件不再保留在 prev 中
          pluginWatchPrevById = nextPrev;
        } catch {
          // 全量刷新失败走退避重试，外层 catch 会处理
          throw new Error("watch reset list failed");
        }
        pluginWatchBackoffMs = PLUGIN_WATCH_BACKOFF_INITIAL_MS;
        continue;
      }

      if (Array.isArray(res.events) && res.events.length > 0) {
        emitPluginWatchDiffs(res.events);
      }
      if (typeof res.cursor === "number") pluginWatchCursor = res.cursor;
      pluginWatchBackoffMs = PLUGIN_WATCH_BACKOFF_INITIAL_MS;
      // 无事件时服务端已等待 25s，直接发下一轮；有事件时也立即可下一轮，保证低延迟
    } catch (error) {
      window.clearTimeout(timeoutTimer);
      pluginWatchAbort = null;
      if (pluginWatchSuspendedByHidden || document.hidden) {
        pluginWatchSuspendedByHidden = true;
        break;
      }
      // 主动取消（隐藏暂停）不计入退避
      if (
        error instanceof DOMException &&
        error.name === "AbortError" &&
        pluginWatchCallbacks.size === 0
      ) {
        break;
      }
      const delayMs = pluginWatchBackoffMs;
      pluginWatchBackoffMs = Math.min(pluginWatchBackoffMs * 2, PLUGIN_WATCH_BACKOFF_MAX_MS);
      await new Promise<void>((resolve) => {
        pluginWatchRetryTimer = window.setTimeout(resolve, delayMs);
      });
      pluginWatchRetryTimer = null;
      if (document.hidden) {
        pluginWatchSuspendedByHidden = true;
        break;
      }
      // 网络错退避后继续循环
    }
  }
};

/** 对外保持 onStatus(cb): unsubscribe 形状，多回调共享同一 watch 循环 */
const watchPluginStatusAndroid = (callback: (info: PluginInfo) => void): (() => void) => {
  pluginWatchCallbacks.add(callback);
  const isFirstSubscriber = pluginWatchCallbacks.size === 1;
  if (isFirstSubscriber) {
    if (!pluginWatchVisibilityHandlerRegistered) {
      pluginWatchVisibilityHandlerRegistered = true;
      document.addEventListener("visibilitychange", handlePluginWatchVisibilityChange);
    }
    // 首次订阅先全量 list() 一次，保持“mount 即刷新一次”语义，再进入 watch
    void (async () => {
      try {
        const list = await apiFetch<PluginInfo[]>("/api/plugins/list");
        const nextPrev = new Map<string, string>();
        for (const info of list) {
          const serialized = JSON.stringify(info);
          const prev = pluginWatchPrevById.get(info.manifest.id);
          if (prev !== serialized) {
            nextPrev.set(info.manifest.id, serialized);
            for (const cb of pluginWatchCallbacks) {
              try {
                cb(info);
              } catch {}
            }
          } else {
            nextPrev.set(info.manifest.id, serialized);
          }
        }
        pluginWatchPrevById = nextPrev;
      } catch {
        // 首刷失败忽略，由后续 watch/reset 兜底
      }
      if (document.hidden) {
        pluginWatchSuspendedByHidden = true;
        return;
      }
      if (!pluginWatchLoopActive) {
        pluginWatchLoopActive = true;
        pluginWatchSuspendedByHidden = false;
        void pluginWatchLoop().finally(() => {
          pluginWatchLoopActive = false;
        });
      }
    })();
  }
  return () => {
    pluginWatchCallbacks.delete(callback);
    if (pluginWatchCallbacks.size === 0) {
      if (pluginWatchAbort) {
        pluginWatchAbort.abort();
        pluginWatchAbort = null;
      }
      if (pluginWatchRetryTimer) {
        clearTimeout(pluginWatchRetryTimer);
        pluginWatchRetryTimer = null;
      }
      // 循环会在下次迭代因 size===0 退出；若当前正等待可见恢复则保持暂停标记
      pluginWatchLoopActive = false;
    }
  };
};

const androidLoadResultFromMeta = (meta: Track | undefined): LoadResult => ({
  detail: {
    quality: meta?.quality ?? {
      sampleRate: 0,
      channels: 0,
      bitsPerSample: 0,
      bitRate: 0,
      codec: "",
    },
    externalLyrics: [],
  },
  mediaInfo: {
    duration: meta?.duration ?? 0,
    cover: meta?.cover,
    quality: meta?.quality,
  },
});

// ─── Android Web 预览播放器 ──────────────────────────────────────────────────

const PREVIEW_PROGRESS_INTERVAL_MS = 200;

let previewAudio: HTMLAudioElement | null = null;
let previewState: PlayerState = "idle";
let previewKnownDurationMs = 0;
let previewVolume = 1;
let previewPlaybackRate = 1;
let previewProgressTimer = 0;
let previewAudioOperationId = 0;
let previewPlayPromise: Promise<void> | null = null;

const previewPlayerListeners = new Set<(event: PlayerEvent) => void>();

const getPreviewDurationMs = (): number => {
  const duration = previewAudio?.duration;
  if (typeof duration === "number" && Number.isFinite(duration) && duration > 0) {
    return Math.round(duration * 1000);
  }
  return previewKnownDurationMs;
};

const getPreviewPositionMs = (): number => {
  const currentTime = previewAudio?.currentTime ?? 0;
  return Math.round(Math.max(0, currentTime) * 1000);
};

const getPreviewStatus = (state: PlayerState = previewState): PlayerStatus => ({
  state,
  position: getPreviewPositionMs(),
  duration: getPreviewDurationMs(),
  volume: previewVolume,
  speed: previewPlaybackRate,
  isFinished: previewAudio?.ended ?? false,
});

const emitPreviewPlayerEvent = (event: PlayerEvent): void => {
  previewPlayerListeners.forEach((listener) => listener(event));
};

const emitPreviewStatus = (state: PlayerState = previewState): void => {
  emitPreviewPlayerEvent({ type: "status", data: getPreviewStatus(state) });
};

const emitPreviewPosition = (authoritative?: boolean): void => {
  emitPreviewPlayerEvent({
    type: "position",
    data: {
      position: getPreviewPositionMs(),
      duration: getPreviewDurationMs(),
      authoritative,
    },
  });
};

const stopPreviewProgressTimer = (): void => {
  window.clearInterval(previewProgressTimer);
  previewProgressTimer = 0;
};

const startPreviewProgressTimer = (): void => {
  if (previewProgressTimer) return;
  previewProgressTimer = window.setInterval(() => {
    if (previewState === "playing") emitPreviewPosition();
  }, PREVIEW_PROGRESS_INTERVAL_MS);
};

const getPreviewAudio = (): HTMLAudioElement => {
  if (previewAudio) return previewAudio;

  const audio = new Audio();
  audio.preload = "auto";
  audio.volume = previewVolume;
  audio.playbackRate = previewPlaybackRate;

  const updateDuration = (): void => {
    previewKnownDurationMs = getPreviewDurationMs();
    emitPreviewPosition(true);
  };

  audio.addEventListener("loadedmetadata", updateDuration);
  audio.addEventListener("durationchange", updateDuration);
  audio.addEventListener("playing", () => {
    previewState = "playing";
    startPreviewProgressTimer();
    emitPreviewStatus("playing");
    emitPreviewPosition(true);
  });
  audio.addEventListener("pause", () => {
    if (previewState === "stopped" || audio.ended) return;
    previewState = "paused";
    stopPreviewProgressTimer();
    emitPreviewStatus("paused");
    emitPreviewPosition(true);
  });
  audio.addEventListener("ended", () => {
    previewState = "stopped";
    stopPreviewProgressTimer();
    emitPreviewPosition(true);
    emitPreviewPlayerEvent({ type: "ended" });
  });
  audio.addEventListener("error", () => {
    previewState = "idle";
    stopPreviewProgressTimer();
    emitPreviewPlayerEvent({ type: "sourceError" });
  });

  previewAudio = audio;
  return audio;
};

/** 静音片段，用于在用户手势内激活音频元素以规避浏览器自动播放拦截 */
const SILENT_AUDIO_SRC =
  "data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=";

/**
 * 浏览器预览模式：在用户手势内播放一段静音片段以激活音频元素，
 * 使后续 programmatic play() 不被自动播放策略拦截。
 * 必须在用户手势回调内同步调用。
 */
const unlockPreviewAudio = (): void => {
  if (!isAndroidPreview) return;
  const audio = getPreviewAudio();
  const operationId = ++previewAudioOperationId;
  const prevMuted = audio.muted;
  const restore = (): void => {
    if (previewAudioOperationId !== operationId) return;
    audio.muted = prevMuted;
    audio.pause();
    audio.currentTime = 0;
    audio.removeAttribute("src");
    try {
      audio.load();
    } catch {}
  };
  try {
    audio.muted = true;
    audio.src = SILENT_AUDIO_SRC;
    audio
      .play()
      .then(() => restore())
      .catch(() => restore());
  } catch {
    restore();
  }
};

const loadPreviewAudio = async (
  source: string,
  options?: LoadOptions,
): Promise<IpcResponse<LoadResult>> => {
  try {
    const audio = getPreviewAudio();
    const autoPlay = options?.autoPlay ?? true;
    previewAudioOperationId++;
    previewKnownDurationMs = options?.meta?.duration ?? 0;
    previewState = "loading";
    stopPreviewProgressTimer();
    audio.pause();
    audio.src = source;
    audio.currentTime = 0;
    audio.volume = previewVolume;
    audio.playbackRate = previewPlaybackRate;
    audio.load();
    emitPreviewStatus("loading");

    if (autoPlay) {
      await audio.play();
    } else {
      previewState = "paused";
      emitPreviewStatus("paused");
      emitPreviewPosition(true);
    }

    return ok(androidLoadResultFromMeta(options?.meta));
  } catch (error) {
    previewState = "idle";
    stopPreviewProgressTimer();
    console.warn("[bridge:android-preview] audio load failed", error);
    return { success: false, error: "NETWORK_ERROR" };
  }
};

const playPreviewAudio = async (): Promise<IpcResponse> => {
  const audio = getPreviewAudio();
  if (!audio.src) return ok();
  if (!audio.paused && previewState === "playing") return ok();
  const playPromise = previewPlayPromise ?? audio.play();
  previewPlayPromise = playPromise;
  try {
    await playPromise;
    return ok();
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") return ok();
    console.warn("[bridge:android-preview] audio play failed", error);
    return { success: false, error: "NETWORK_ERROR" };
  } finally {
    if (previewPlayPromise === playPromise) previewPlayPromise = null;
  }
};

const pausePreviewAudio = (): IpcResponse => {
  previewAudioOperationId++;
  previewAudio?.pause();
  previewState = "paused";
  stopPreviewProgressTimer();
  emitPreviewStatus("paused");
  emitPreviewPosition(true);
  return ok();
};

const stopPreviewAudio = (): IpcResponse => {
  const audio = previewAudio;
  previewAudioOperationId++;
  previewState = "stopped";
  stopPreviewProgressTimer();
  if (audio) {
    audio.pause();
    audio.removeAttribute("src");
    audio.load();
  }
  previewKnownDurationMs = 0;
  emitPreviewStatus("stopped");
  return ok();
};

const seekPreviewAudio = (positionMs: number): IpcResponse => {
  const audio = getPreviewAudio();
  const maxPosition = getPreviewDurationMs();
  const safePosition = Math.max(
    0,
    maxPosition > 0 ? Math.min(positionMs, maxPosition) : positionMs,
  );
  audio.currentTime = safePosition / 1000;
  emitPreviewPosition(true);
  return ok();
};

const setPreviewVolume = (volume: number): IpcResponse => {
  previewVolume = Math.max(0, Math.min(1, volume));
  if (previewAudio) previewAudio.volume = previewVolume;
  return ok();
};

const setPreviewPlaybackRate = (speed: number): IpcResponse => {
  previewPlaybackRate = Math.max(0.5, Math.min(2, speed));
  if (previewAudio) previewAudio.playbackRate = previewPlaybackRate;
  return ok();
};

// ─── Android 降级警告 ────────────────────────────────────────────────────────

/** 在 Android 上调用不可用方法时输出警告 */
const androidWarned = new Set<string>();
const androidWarn = (namespace: string, method: string): void => {
  if (isAndroidPreview) return;
  const key = `${namespace}.${method}`;
  if (androidWarned.has(key)) return;
  androidWarned.add(key);
  console.debug(`[bridge:android] ${key} is not available on Android`);
};

/** Android 端曲库统计概览：本地曲库 ∪ 播放历史（在线曲目也计入，嵌入式 API 无曲库统计路由） */
const androidLibraryStats = async (): Promise<LibraryStats> => {
  const [res, playedRes] = await Promise.all([
    getAndroidLibrary().getTracks(),
    apiFetch<Track[]>("/api/stats/getPlayedTracks").catch(() => []),
  ]);
  const localTracks = res.success && res.data ? res.data : [];
  const seen = new Set<string>();
  const tracks = [...localTracks, ...playedRes].filter((track) => {
    const key = `${track.source}:${track.id}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  const albums = new Set<string>();
  const artists = new Set<string>();
  const codecCounts = new Map<string, number>();
  let totalDurationMs = 0;
  let totalFileSize = 0;
  for (const track of tracks) {
    const albumName = track.album?.name?.trim();
    if (albumName) albums.add(albumName);
    for (const artist of track.artists) {
      const name = artist.name.trim();
      if (name) artists.add(name);
    }
    totalDurationMs += track.duration;
    totalFileSize += track.fileSize ?? 0;
    // 扫描端暂不提取 codec，本地文件用扩展名兜底
    const codec = track.quality?.codec?.trim() || track.path?.split(".").pop()?.toLowerCase() || "";
    codecCounts.set(codec, (codecCounts.get(codec) ?? 0) + 1);
  }
  return {
    trackCount: tracks.length,
    albumCount: albums.size,
    artistCount: artists.size,
    totalDurationMs,
    totalFileSize,
    codecs: Array.from(codecCounts.entries())
      .map(([codec, count]) => ({ codec, count }))
      .sort((a, b) => b.count - a.count || a.codec.localeCompare(b.codec)),
  };
};

const unsupportedPluginMethod = <T>(method: string, result: T): Promise<T> => {
  androidWarn("plugins", method);
  return Promise.resolve(result);
};

/** Android 分支的更新事件订阅者（check 时合成 notAvailable 用） */
let androidUpdateListener: ((event: UpdateEvent) => void) | null = null;

const unsupportedStreamingMethod = (method: string): Promise<never> => {
  androidWarn("streaming", method);
  return Promise.reject(new Error("STREAMING_NOT_SUPPORTED_ON_ANDROID"));
};

// ─── Android 权限引导 ────────────────────────────────────────────────────────

/** 通知权限是否已在本会话中请求过 */
let notificationPermissionRequested = false;

/** 请求通知权限（Android 13+），仅在首次播放时调用 */
export const ensureNotificationPermission = async (): Promise<void> => {
  if (!isAndroidNative || notificationPermissionRequested) return;
  notificationPermissionRequested = true;
  try {
    const plugin = getPlaybackPlugin();
    const result = await plugin.requestNotificationPermission();
    if (!result.granted) {
      console.warn("[bridge:android] notification permission not granted");
    }
  } catch (e) {
    console.warn("[bridge:android] requestNotificationPermission failed", e);
  }
};

/** 悬浮窗权限引导：检查 → 弹窗说明 → 打开设置 → 重试 */
const requestOverlayWithGuidance = async (): Promise<boolean> => {
  const plugin = getPlaybackPlugin();
  // 1. 检查权限
  const check = await plugin.checkOverlayPermission();
  if (check.granted) return true;

  // 2. 弹出说明对话框
  const { dialog } = await import("@/composables/useDialog");
  const confirmed = await dialog.confirm({
    title: "悬浮歌词权限",
    description:
      "需要在系统设置中授予「显示在其他应用上层」权限，才能显示悬浮歌词窗口。点击确定前往系统设置开启权限。",
    confirmText: "前往设置",
    cancelText: "取消",
    type: "info",
  });
  if (!confirmed) return false;

  // 3. 打开系统设置页面
  await plugin.requestOverlayPermission();

  // 4. 等待用户返回后再次检查
  const recheck = await plugin.checkOverlayPermission();
  return recheck.granted;
};

/** 注册灵动岛可见性变化监听（Android 灵动岛悬浮窗服务） */
const registerDynamicIslandVisibilityListener = (
  callback: (open: boolean) => void,
): (() => void) => {
  if (!isAndroidNative) return noop;
  const plugin = getPlaybackPlugin();
  let listener: { remove: () => void } | null = null;
  void plugin
    .addListener("dynamicIslandVisibilityChange", (data: unknown) => {
      const payload = data as { open?: boolean };
      callback(!!payload.open);
    })
    .then((handle) => {
      listener = handle;
    });
  return () => {
    listener?.remove();
  };
};

/**
 * 将前端 system.dynamicIsland 配置推送给原生灵动岛悬浮窗服务
 * 新服务 applyConfig 直接接受前端字段名，无需字段映射
 */
export const pushDynamicIslandConfig = (config: DynamicIslandSettings): void => {
  if (!isAndroidNative) return;
  getPlaybackPlugin()
    .updateDynamicIslandConfig({ config })
    .catch((err) => {
      console.error("[bridge] pushDynamicIslandConfig failed", err);
    });
};

/**
 * 推送歌词数据到原生灵动岛
 * @param lrcJson - 行级歌词 JSON 字符串（LyricLine[]），无逐字数据时填此字段
 * @param yrcJson - 逐字歌词 JSON 字符串（LyricLine[]），有逐字数据时填此字段
 */
export const pushDynamicIslandData = (lrcJson: string, yrcJson: string): void => {
  if (!isAndroidNative) return;
  getPlaybackPlugin()
    .updateDynamicIslandData({ lrcData: lrcJson, yrcData: yrcJson })
    .catch((err) => {
      console.error("[bridge] pushDynamicIslandData failed", err);
    });
};

/**
 * 推送播放进度到原生灵动岛
 * @param timeMs - 当前播放位置（毫秒）
 * @param playing - 是否正在播放
 */
export const pushDynamicIslandProgress = (timeMs: number, playing: boolean): void => {
  if (!isAndroidNative) return;
  getPlaybackPlugin()
    .updateDynamicIslandProgress({ timeMs, playing })
    .catch((err) => {
      console.error("[bridge] pushDynamicIslandProgress failed", err);
    });
};

/**
 * 推送歌曲信息到原生灵动岛
 * @param name - 歌曲名
 * @param artist - 艺术家名（已拼接）
 */
export const pushDynamicIslandSongInfo = (name: string, artist: string): void => {
  if (!isAndroidNative) return;
  getPlaybackPlugin()
    .updateDynamicIslandSongInfo({ name, artist })
    .catch((err) => {
      console.error("[bridge] pushDynamicIslandSongInfo failed", err);
    });
};

// ─── Electron 透传 ───────────────────────────────────────────────────────────

function electronApi(): Window["api"] {
  return window.api;
}

// ─── Android 下载管理器 ────────────────────────────────────────────────────────

class AndroidDownloadManager {
  private tasks: DownloadTask[] = [];
  private requests = new Map<string, DownloadRequest>();
  private stateListeners = new Set<(task: DownloadTask) => void>();
  private progressListeners = new Set<(data: DownloadProgress) => void>();
  private concurrency = 2;
  private activeCount = 0;
  private pluginListenerAdded = false;
  private urlResolveCache = new Map<string, { url: string; format?: string; size?: number }>();
  private pendingUrlResolves = new Map<
    string,
    Promise<{ url: string; format?: string; size?: number } | null>
  >();

  private setupPluginListener() {
    if (this.pluginListenerAdded) return;
    this.pluginListenerAdded = true;

    // 不 await addListener，防止阻塞队列，让它在后台异步注册
    getAndroidDownload()
      .addListener("downloadProgress", (progress) => {
        this.progressListeners.forEach((listener) =>
          listener({
            taskId: progress.taskId,
            received: progress.bytesRead,
            total: progress.contentLength,
          }),
        );
      })
      .catch((e) => {
        console.warn("[AndroidDownloadManager] Failed to add progress listener", e);
        // 如果注册失败，重置状态以便下次可以重试
        this.pluginListenerAdded = false;
      });
  }

  private updateTask(taskId: string, update: Partial<DownloadTask>) {
    const task = this.tasks.find((t) => t.taskId === taskId);
    if (!task) return;
    Object.assign(task, update);
    this.stateListeners.forEach((listener) => listener({ ...task }));
  }

  private sanitizeFilename(name: string): string {
    return name.replace(/[\\/:*?"<>|]/g, "_");
  }

  /**
   * 解析下载 URL，对齐 PC 端 [resolveNeteaseDownloadUrl] 流程
   * - 去重：同一歌曲同一音质档位复用 Promise
   * - usePlayback=false：先调下载接口，失败后回落播放接口
   * - usePlayback=true：仅用播放接口（模拟播放下载）
   */
  private async resolveDownloadUrl(
    songId: string | number,
    qualityLevel: string,
    usePlayback?: boolean,
  ): Promise<{ url: string; format?: string; size?: number } | null> {
    if (!isAndroidNative) return null;
    const id = typeof songId === "number" ? songId : Number(songId);
    if (!id || id <= 0) return null;
    const cacheKey = `${id}:${qualityLevel}:${usePlayback ? "pb" : "dl"}`;
    const cached = this.urlResolveCache.get(cacheKey);
    if (cached) return cached;
    const existing = this.pendingUrlResolves.get(cacheKey);
    if (existing) return existing;
    const promise = (async () => {
      try {
        const settings = useSettingsStore();
        const settingsUsePlayback =
          (settings.system.download as { usePlayback?: boolean }).usePlayback ?? false;
        const finalUsePlayback = usePlayback ?? settingsUsePlayback;
        const result = await getAndroidDownload().resolveDownloadUrl({
          songId: id,
          usePlayback: finalUsePlayback,
        });
        if (!result || !result.url) {
          console.warn("[AndroidDownloadManager] resolveDownloadUrl failed songId=", id);
          return null;
        }
        const resolved: { url: string; format?: string; size?: number } = {
          url: result.url,
          format: result.format,
          size: result.size,
        };
        this.urlResolveCache.set(cacheKey, resolved);
        return resolved;
      } catch (error) {
        console.warn("[AndroidDownloadManager] resolveDownloadUrl error songId=", id, error);
        return null;
      } finally {
        this.pendingUrlResolves.delete(cacheKey);
      }
    })();
    this.pendingUrlResolves.set(cacheKey, promise);
    return promise;
  }

  private async processQueue() {
    if (this.activeCount >= this.concurrency) return;

    const nextTask = this.tasks.find((t) => t.status === "queued");
    if (!nextTask) return;

    const req = this.requests.get(nextTask.taskId);
    if (!req) {
      this.updateTask(nextTask.taskId, { status: "failed", errorCode: "NO_REQUEST" });
      return;
    }

    this.activeCount++;
    this.updateTask(nextTask.taskId, { status: "downloading", errorCode: undefined });

    try {
      const dir = await apiFetch<string | null>("/api/config/get?keyPath=download.dir").catch(
        () => null,
      );
      if (!dir) throw new Error("DOWNLOAD_DIR_NOT_SET");

      let downloadUrl = req.url;
      let format = req.declaredFormat;
      let size = req.declaredSize;

      // 如果请求未预解析 URL（或者来源为 netease 且未标记已解析），使用 Kotlin 端解析
      if (!downloadUrl && req.track.source === "netease" && isAndroidNative) {
        console.log(
          "[AndroidDownloadManager] Resolving download URL for track",
          req.track.id,
          "quality",
          nextTask.qualityLevel,
        );
        const resolved = await this.resolveDownloadUrl(req.track.id, nextTask.qualityLevel);
        if (resolved) {
          downloadUrl = resolved.url;
          format = resolved.format || format;
          size = resolved.size || size;
          req.url = downloadUrl;
          req.declaredFormat = format;
          req.declaredSize = size;
          this.requests.set(req.taskId, req);
        } else {
          console.warn(
            "[AndroidDownloadManager] Failed to resolve download URL for track",
            req.track.id,
          );
          throw new Error("URL_RESOLVE_FAILED");
        }
      }

      if (!downloadUrl) {
        console.warn("[AndroidDownloadManager] No URL available for track", req.track.id);
        throw new Error("NO_URL_AVAILABLE");
      }

      const track = nextTask.track;
      const artistNames = track.artists.map((a) => a.name).join(",");
      const baseName = `${artistNames} - ${track.title}`;
      const safeBaseName = this.sanitizeFilename(baseName);

      const extMatch = downloadUrl.match(/\.([a-zA-Z0-9]+)(?:[?#]|$)/);
      const ext = extMatch ? extMatch[1] : format || "mp3";
      const fileName = `${safeBaseName}.${ext}`;

      const audioResult = await getAndroidDownload().downloadFile({
        taskId: nextTask.taskId,
        url: downloadUrl,
        fileName,
        directoryUri: dir,
      });

      // 内嵌标签（封面/元信息/歌词），失败仅告警不影响音频文件
      let tagWarning = false;
      const tagOpts = req.tagOptions;
      if (tagOpts && (tagOpts.embedCover || tagOpts.embedMeta || tagOpts.embedLyric)) {
        try {
          await getAndroidDownload().embedTags({
            filePath: audioResult.path,
            coverUrl: req.coverUrl,
            title: track.title,
            artist: artistNames,
            album: track.album?.name,
            lyrics: req.lyricText,
            embedCover: tagOpts.embedCover,
            embedMeta: tagOpts.embedMeta,
            embedLyric: tagOpts.embedLyric,
          });
        } catch (e) {
          console.warn("[AndroidDownloadManager] embedTags failed", e);
          tagWarning = true;
        }
      }

      if (req.lyricText) {
        await getAndroidDownload()
          .writeTextFile({
            fileName: `${safeBaseName}.lrc`,
            content: req.lyricText,
            directoryUri: dir,
          })
          .catch((e) => console.warn("Failed to write lyric file", e));
      }

      if (req.ttmlText) {
        await getAndroidDownload()
          .writeTextFile({
            fileName: `${safeBaseName}.ttml`,
            content: req.ttmlText,
            directoryUri: dir,
          })
          .catch((e) => console.warn("Failed to write ttml file", e));
      }

      this.updateTask(nextTask.taskId, {
        status: "done",
        filePath: audioResult.path,
        received: req.declaredSize || 0,
        total: req.declaredSize || 0,
        tagWarning: tagWarning || undefined,
        finishedAt: Date.now(),
      });
    } catch (error: any) {
      this.updateTask(nextTask.taskId, {
        status: "failed",
        errorCode: error.message || String(error),
        finishedAt: Date.now(),
      });
    } finally {
      this.activeCount--;
      this.processQueue();
    }
  }

  public async start(req: DownloadRequest): Promise<EnqueueResult> {
    this.setupPluginListener();
    const existing = this.tasks.find((t) => t.taskId === req.taskId);
    if (existing) return { ok: false, reason: "queued" };

    this.requests.set(req.taskId, req);

    const task: DownloadTask = {
      taskId: req.taskId,
      status: "queued",
      track: req.track,
      qualityLevel: req.qualityLevel,
      received: 0,
      total: req.declaredSize || 0,
      createdAt: Date.now(),
    };

    this.tasks.push(task);
    this.stateListeners.forEach((listener) => listener({ ...task }));

    this.processQueue();
    return { ok: true };
  }

  public async cancel(taskId: string): Promise<void> {
    const task = this.tasks.find((t) => t.taskId === taskId);
    if (!task) return;

    const oldStatus = task.status;
    if (oldStatus === "queued" || oldStatus === "downloading") {
      this.updateTask(taskId, { status: "canceled", errorCode: "CANCELLED" });

      if (oldStatus === "downloading") {
        // 如果正在下载，原生层会抛错并在 processQueue 的 catch 块里结束任务
        // catch 块里有 this.activeCount-- 和 this.processQueue()，所以这里不能再调，避免双减
        await getAndroidDownload()
          .cancelDownload({ taskId })
          .catch(() => {});
      } else {
        // 排队的任务还没进原生层，直接在这里释放并发槽并处理队列
        this.processQueue();
      }
    }
  }

  public async retry(req: DownloadRequest): Promise<EnqueueResult> {
    const task = this.tasks.find((t) => t.taskId === req.taskId);
    if (!task) return this.start(req);

    this.requests.set(req.taskId, req);

    if (task.status === "failed" || task.status === "canceled" || task.status === "interrupted") {
      this.updateTask(req.taskId, {
        status: "queued",
        errorCode: undefined,
        received: 0,
      });
      this.processQueue();
      return { ok: true };
    }
    return { ok: false, reason: "queued" };
  }

  public async remove(taskId: string): Promise<void> {
    this.tasks = this.tasks.filter((t) => t.taskId !== taskId);
    this.requests.delete(taskId);
  }

  public async clearFinished(): Promise<void> {
    const finishedIds = this.tasks
      .filter((t) => t.status === "done" || t.status === "failed" || t.status === "canceled")
      .map((t) => t.taskId);

    this.tasks = this.tasks.filter((t) => t.status === "queued" || t.status === "downloading");
    finishedIds.forEach((id) => this.requests.delete(id));
  }

  public async list(): Promise<DownloadTask[]> {
    return this.tasks;
  }

  public onProgress(callback: (data: DownloadProgress) => void): () => void {
    this.progressListeners.add(callback);
    return () => this.progressListeners.delete(callback);
  }

  public onState(callback: (task: DownloadTask) => void): () => void {
    this.stateListeners.add(callback);
    return () => this.stateListeners.delete(callback);
  }
}

const androidDownloadManager = new AndroidDownloadManager();

/**
 * ArrayBuffer 转 base64，供 Capacitor 插件桥传二进制
 * 分块拼接，避免 String.fromCharCode 的参数长度上限
 * @param buffer - 二进制数据
 * @returns base64 字符串
 */
const bytesToBase64 = (buffer: ArrayBuffer): string => {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(binary);
};

/** Android 原生剪贴板插件调用超时（毫秒） */
const CLIPBOARD_CALL_TIMEOUT_MS = 10_000;

/** Android 原生保存文件插件调用超时（毫秒），本地写入足够宽裕 */
const SAVE_FILE_CALL_TIMEOUT_MS = 30_000;

/**
 * Android 插件调用超时兜底：Capacitor 桥在插件方法抛异常或跨桥大载荷失败时
 * 不会 reject Promise，前端 await 将永久挂起；超时转为 reject 保证有明确结果
 * @param promise - 原生插件调用 Promise
 * @param ms - 超时毫秒数
 * @param label - 用于超时错误信息的调用名
 * @returns 带超时保护的同值 Promise
 */
const withBridgeTimeout = <T>(promise: Promise<T>, ms: number, label: string): Promise<T> =>
  new Promise<T>((resolve, reject) => {
    const timer = window.setTimeout(
      () => reject(new Error(`[bridge] native call ${label} timed out`)),
      ms,
    );
    promise.then(
      (value) => {
        window.clearTimeout(timer);
        resolve(value);
      },
      (error) => {
        window.clearTimeout(timer);
        reject(error);
      },
    );
  });

// ─── Bridge 实现 ─────────────────────────────────────────────────────────────

const bridge = {
  // ── config ──────────────────────────────────────────────────────────────────
  config: {
    get: (keyPath: string): Promise<unknown> =>
      isAndroidPreview
        ? Promise.resolve(undefined)
        : isAndroid
          ? apiFetch(`/api/config/get?keyPath=${encodeURIComponent(keyPath)}`)
          : electronApi().config.get(keyPath),
    set: (keyPath: string, value: unknown): Promise<void> =>
      isAndroidPreview
        ? Promise.resolve()
        : isAndroid
          ? apiPost("/api/config/set", { keyPath, value })
          : electronApi().config.set(keyPath, value),
    getAll: (): Promise<SystemConfig> =>
      isAndroidPreview
        ? Promise.resolve(structuredClone(defaultSystemConfig))
        : isAndroid
          ? apiFetch<SystemConfig>("/api/config/getAll")
          : electronApi().config.getAll(),
    reset: (): Promise<void> =>
      isAndroidPreview
        ? Promise.resolve()
        : isAndroid
          ? apiPost("/api/config/reset", {})
          : electronApi().config.reset(),
    replaceAll: (config: unknown): Promise<void> =>
      isAndroid
        ? apiPost("/api/config/replaceAll", { config })
        : electronApi().config.replaceAll(config),
    exportToFile: (
      payload: unknown,
    ): Promise<{ ok: boolean; reason?: "canceled" | "writeFailed" }> =>
      isAndroid
        ? apiPost("/api/config/exportToFile", payload)
        : electronApi().config.exportToFile(payload),
    importFromFile: (): Promise<
      { ok: true; data: unknown } | { ok: false; reason: "canceled" | "readFailed" | "parseFailed" }
    > =>
      isAndroid
        ? apiFetch("/api/config/importFromFile", { method: "POST" })
        : electronApi().config.importFromFile(),
  } satisfies ConfigApi,

  // ── player ──────────────────────────────────────────────────────────────────
  player: {
    load: async (source: string, options?: LoadOptions): Promise<IpcResponse<LoadResult>> => {
      if (isAndroidPreview) return loadPreviewAudio(source, options);
      if (!isAndroid) return electronApi().player.load(source, options);
      const result = await getPlaybackPlugin().load({
        url: source,
        positionMs: 0,
        autoPlay: options?.autoPlay ?? true,
      });
      if (result && typeof result === "object" && "success" in result) {
        return result as IpcResponse<LoadResult>;
      }
      return { success: true, data: androidLoadResultFromMeta(options?.meta) };
    },
    play: (): Promise<IpcResponse> =>
      isAndroidPreview
        ? playPreviewAudio()
        : isAndroid
          ? getPlaybackPlugin()
              .play()
              .then((res) =>
                res && typeof res === "object" && "success" in res ? res : { success: true },
              )
          : electronApi().player.play(),
    pause: (): Promise<IpcResponse> =>
      isAndroidPreview
        ? Promise.resolve(pausePreviewAudio())
        : isAndroid
          ? getPlaybackPlugin()
              .pause()
              .then((res) =>
                res && typeof res === "object" && "success" in res ? res : { success: true },
              )
          : electronApi().player.pause(),
    /** 浏览器预览模式：在用户手势内激活音频元素以规避自动播放拦截；其他平台为 no-op */
    unlockAudio: (): Promise<void> => {
      if (isAndroidPreview) unlockPreviewAudio();
      return Promise.resolve();
    },
    stop: (): Promise<IpcResponse> =>
      isAndroidPreview
        ? Promise.resolve(stopPreviewAudio())
        : isAndroid
          ? getPlaybackPlugin()
              .stop()
              .then((res) =>
                res && typeof res === "object" && "success" in res ? res : { success: true },
              )
          : electronApi().player.stop(),
    seek: (positionMs: number): Promise<IpcResponse> =>
      isAndroidPreview
        ? Promise.resolve(seekPreviewAudio(positionMs))
        : isAndroid
          ? getPlaybackPlugin()
              .seek({ positionMs })
              .then((res) =>
                res && typeof res === "object" && "success" in res ? res : { success: true },
              )
          : electronApi().player.seek(positionMs),
    setVolume: (volume: number): Promise<IpcResponse> =>
      isAndroidPreview
        ? Promise.resolve(setPreviewVolume(volume))
        : isAndroid
          ? getPlaybackPlugin()
              .setVolume({ volume })
              .then((res) =>
                res && typeof res === "object" && "success" in res ? res : { success: true },
              )
          : electronApi().player.setVolume(volume),
    getVolume: (): Promise<IpcResponse<number>> =>
      isAndroidPreview
        ? Promise.resolve(ok(previewVolume))
        : isAndroid
          ? getPlaybackPlugin()
              .getStatus()
              .then((s) => ({
                success: true,
                data: (s as unknown as { volume?: number }).volume ?? 1,
              }))
          : electronApi().player.getVolume(),
    setFadeDuration: (ms: number): Promise<IpcResponse> =>
      isAndroid
        ? (androidWarn("player", "setFadeDuration"), Promise.resolve({ success: true }))
        : electronApi().player.setFadeDuration(ms),
    getFadeDuration: (): Promise<IpcResponse<number>> =>
      isAndroid
        ? (androidWarn("player", "getFadeDuration"), Promise.resolve({ success: true, data: 0 }))
        : electronApi().player.getFadeDuration(),
    getStatus: (): Promise<IpcResponse<PlayerStatus>> =>
      isAndroidPreview
        ? Promise.resolve(ok(getPreviewStatus()))
        : isAndroid
          ? getPlaybackPlugin()
              .getStatus()
              .then((res) => {
                if (res && typeof res === "object" && "success" in res)
                  return res as IpcResponse<PlayerStatus>;
                return { success: true, data: res as unknown as PlayerStatus };
              })
          : electronApi().player.getStatus(),
    getFftData: (): Promise<IpcResponse<FftData>> =>
      isAndroid
        ? (androidWarn("player", "getFftData"),
          Promise.resolve({ success: true, data: { ldata: [], rdata: [] } }))
        : electronApi().player.getFftData(),
    setFftEnabled: (enabled: boolean): Promise<IpcResponse> =>
      isAndroidPreview
        ? Promise.resolve(ok())
        : isAndroid
          ? getPlaybackPlugin()
              .setFftEnabled({ enabled })
              .then((res) =>
                res && typeof res === "object" && "success" in res ? res : { success: true },
              )
          : electronApi().player.setFftEnabled(enabled),
    setSpectrumAlgorithm: (mode: "pc" | "android"): Promise<IpcResponse> =>
      isAndroidNative
        ? getPlaybackPlugin()
            .setSpectrumAlgorithm({ mode })
            .then((res) =>
              res && typeof res === "object" && "success" in res ? res : { success: true },
            )
        : isAndroid
          ? Promise.resolve(ok())
          : Promise.resolve(ok()),
    setNormalizationEnabled: (enabled: boolean): Promise<IpcResponse> =>
      isAndroid
        ? (androidWarn("player", "setNormalizationEnabled"), Promise.resolve({ success: true }))
        : electronApi().player.setNormalizationEnabled(enabled),
    setEqualizerEnabled: (enabled: boolean): Promise<IpcResponse> =>
      isAndroidNative
        ? getPlaybackPlugin()
            .setEqualizerEnabled({ enabled })
            .then((res) =>
              res && typeof res === "object" && "success" in res ? res : { success: true },
            )
        : isAndroid
          ? Promise.resolve(ok())
          : electronApi().player.setEqualizerEnabled(enabled),
    setEqualizerBands: (gainsDb: number[]): Promise<IpcResponse> =>
      isAndroidNative
        ? getPlaybackPlugin()
            .setEqualizerBands({ gainsDb })
            .then((res) =>
              res && typeof res === "object" && "success" in res ? res : { success: true },
            )
        : isAndroid
          ? Promise.resolve(ok())
          : electronApi().player.setEqualizerBands(gainsDb),
    setPreampGain: (preampDb: number): Promise<IpcResponse> =>
      isAndroidNative
        ? getPlaybackPlugin()
            .setPreampGain({ preampDb })
            .then((res) =>
              res && typeof res === "object" && "success" in res ? res : { success: true },
            )
        : isAndroid
          ? Promise.resolve(ok())
          : electronApi().player.setPreampGain(preampDb),
    setSpeed: (speed: number): Promise<IpcResponse> =>
      isAndroidPreview
        ? Promise.resolve(setPreviewPlaybackRate(speed))
        : isAndroid
          ? getPlaybackPlugin()
              .setSpeed({ speed })
              .then((res) =>
                res && typeof res === "object" && "success" in res ? res : { success: true },
              )
          : electronApi().player.setSpeed(speed),
    setPitch: (semitones: number): Promise<IpcResponse> =>
      isAndroid
        ? (androidWarn("player", "setPitch"), Promise.resolve({ success: true }))
        : electronApi().player.setPitch(semitones),
    setPitchSync: (sync: boolean): Promise<IpcResponse> =>
      isAndroid
        ? (androidWarn("player", "setPitchSync"), Promise.resolve({ success: true }))
        : electronApi().player.setPitchSync(sync),
    reinit: (): Promise<IpcResponse> =>
      isAndroid
        ? (androidWarn("player", "reinit"), Promise.resolve({ success: true }))
        : electronApi().player.reinit(),
    getOutputDevices: (): Promise<IpcResponse<AudioDevice[]>> =>
      isAndroid
        ? (androidWarn("player", "getOutputDevices"), Promise.resolve({ success: true, data: [] }))
        : electronApi().player.getOutputDevices(),
    getDefaultDeviceName: (): Promise<IpcResponse<string | null>> =>
      isAndroid
        ? (androidWarn("player", "getDefaultDeviceName"),
          Promise.resolve({ success: true, data: null }))
        : electronApi().player.getDefaultDeviceName(),
    setOutputDevice: (deviceId: string | null, pauseBeforeSwitch = false): Promise<IpcResponse> =>
      isAndroid
        ? (androidWarn("player", "setOutputDevice"), Promise.resolve({ success: true }))
        : electronApi().player.setOutputDevice(deviceId, pauseBeforeSwitch),
    setPauseOnDeviceSwitch: (enabled: boolean): Promise<IpcResponse> =>
      isAndroid
        ? (androidWarn("player", "setPauseOnDeviceSwitch"), Promise.resolve({ success: true }))
        : electronApi().player.setPauseOnDeviceSwitch(enabled),
    getSelectedDeviceName: (): Promise<IpcResponse<string | null>> =>
      isAndroid
        ? (androidWarn("player", "getSelectedDeviceName"),
          Promise.resolve({ success: true, data: null }))
        : electronApi().player.getSelectedDeviceName(),
    getCoverRaw: (): Promise<IpcResponse<string | null>> =>
      isAndroid
        ? (androidWarn("player", "getCoverRaw"), Promise.resolve({ success: true, data: null }))
        : electronApi().player.getCoverRaw(),
    readLyricFile: (filePath: string): Promise<IpcResponse<string>> =>
      isAndroidPreview
        ? Promise.resolve(ok(""))
        : isAndroid
          ? apiFetch<string>(
              `/api/player/readLyricFile?filePath=${encodeURIComponent(filePath)}`,
            ).then((data) => ({ success: true, data }))
          : electronApi().player.readLyricFile(filePath),
    syncPlayMode: (repeatMode: string, shuffleMode: string): void =>
      isAndroid
        ? (androidWarn("player", "syncPlayMode"), noop())
        : electronApi().player.syncPlayMode(repeatMode, shuffleMode),
    syncLikeState: (liked: boolean): void =>
      isAndroid
        ? (androidWarn("player", "syncLikeState"), noop())
        : electronApi().player.syncLikeState(liked),
    dispatch: (type: string): void =>
      isAndroid ? (androidWarn("player", "dispatch"), noop()) : electronApi().player.dispatch(type),
    onEvent: (callback: (event: PlayerEvent) => void): (() => void) => {
      if (isAndroidPreview) {
        previewPlayerListeners.add(callback);
        return () => previewPlayerListeners.delete(callback);
      }
      if (!isAndroid) return electronApi().player.onEvent(callback);
      const plugin = getPlaybackPlugin();
      const listeners: Array<Promise<{ remove: () => void }>> = [];

      const onPlaybackStateChanged = (data: unknown) => {
        const state = data as Record<string, unknown>;
        const playing = Boolean(state.playing);
        const paused = Boolean(state.paused);
        const buffering = Boolean(state.buffering);
        const ready = Boolean(state.ready);
        let playerState: "playing" | "paused" | "loading" | "idle" = "idle";
        if (buffering) playerState = "loading";
        else if (playing) playerState = "playing";
        else if (paused && ready) playerState = "paused";
        else if (paused) playerState = "idle";
        callback({
          type: "status",
          data: {
            state: playerState,
            position: Number(state.positionMs ?? 0),
            duration: Number(state.durationMs ?? 0),
            volume: Number(state.volume ?? 1),
            speed: Number(state.playbackRate ?? 1),
            isFinished: Boolean(state.completed),
          },
        });
      };

      const onProgressChanged = (data: unknown) => {
        const payload = data as Record<string, unknown>;
        callback({
          type: "position",
          data: {
            position: Number(payload.positionMs ?? 0),
            duration: Number(payload.durationMs ?? 0),
            authoritative: Boolean(payload.authoritative) || undefined,
          },
        });
      };

      const onEnded = (_data: unknown) => {
        console.info("[bridge:android] ended");
        callback({ type: "ended" });
      };

      const onError = (data: unknown) => {
        const payload = data as Record<string, unknown>;
        callback({ type: "error", error: String(payload.message ?? "native error") });
      };

      const onVisualizerData = (data: unknown) => {
        const payload = data as Record<string, unknown>;
        const fftB64 = payload.fftB64 as string;
        if (!fftB64) return;
        try {
          const raw = atob(fftB64);
          const arr = new Array(raw.length);
          for (let i = 0; i < raw.length; i++) {
            arr[i] = raw.charCodeAt(raw.length - 1 - i) / 255;
          }
          // Android 原生已对 lowFreq 做平方扩展 + EMA 平滑，直接消费比前端自算更接近 PC 冲击感
          const lowFreq = payload.lowFreq;
          callback({
            type: "fftData",
            data: arr,
            lowFreq: typeof lowFreq === "number" ? lowFreq : undefined,
          });
        } catch {}
      };

      // 立刻注册并确认 listener 已被附加
      void (async () => {
        try {
          const p1 = await plugin.addListener("playbackStateChanged", onPlaybackStateChanged);
          const p2 = await plugin.addListener("progressChanged", onProgressChanged);
          const p3 = await plugin.addListener("ended", onEnded);
          const p4 = await plugin.addListener("error", onError);
          const p5 = await plugin.addListener("visualizerData", onVisualizerData);
          listeners.push(
            Promise.resolve(p1),
            Promise.resolve(p2),
            Promise.resolve(p3),
            Promise.resolve(p4),
            Promise.resolve(p5),
          );
          console.info("[bridge:android] player listeners registered");
        } catch (error) {
          console.error("[bridge:android] failed to register player listeners", error);
        }
      })();

      return () => {
        listeners.forEach(async (promise) => {
          try {
            const listener = await promise;
            listener.remove();
          } catch {
            // ignore
          }
        });
      };
    },
  } satisfies PlayerApi,

  // ── android ─────────────────────────────────────────────────────────────────
  android: {
    /** 设置是否显示系统状态栏 */
    setShowStatusBar: (show: boolean): Promise<void> =>
      isAndroidNative ? getPlaybackPlugin().setShowStatusBar({ show }) : Promise.resolve(),
    /** 设置是否隐藏底部导航栏 */
    setHideNavigationBar: (hide: boolean): Promise<void> =>
      isAndroidNative ? getPlaybackPlugin().setHideNavigationBar({ hide }) : Promise.resolve(),
    /** 横屏沉浸式：同时隐藏状态栏与全面屏导航手势条 */
    setImmersiveLandscape: (active: boolean): Promise<void> =>
      isAndroidNative ? getPlaybackPlugin().setImmersiveLandscape({ active }) : Promise.resolve(),
    /** 推送元数据到原生 MediaSession */
    updateMetadata: (payload: Record<string, unknown>): Promise<void> =>
      isAndroidNative ? getPlaybackPlugin().updateMetadata(payload) : Promise.resolve(),
    /** 推送全量播放队列上下文到原生端（原生队列权威） */
    updateQueueContext: (payload: Record<string, unknown>): Promise<void> =>
      isAndroidNative ? getPlaybackPlugin().updateQueueContext(payload) : Promise.resolve(),
    /** 更新通知栏按钮偏好 */
    updateNotificationPrefs: (payload: Record<string, unknown>): Promise<void> =>
      isAndroidNative ? getPlaybackPlugin().updateNotificationPrefs(payload) : Promise.resolve(),
    /** 设置是否允许与其他应用混播 */
    setAllowMixWithOthers: (allow: boolean): Promise<void> =>
      isAndroidNative ? getPlaybackPlugin().setAllowMixWithOthers({ allow }) : Promise.resolve(),
    /** 同步 API 上下文（基址/Cookie/音质/缓存/插件源）到原生端 */
    syncApiContext: (payload: Record<string, unknown>): Promise<void> =>
      isAndroidNative ? getPlaybackPlugin().syncApiContext(payload) : Promise.resolve(),
    /** 原生队列权威切下一首 */
    next: (): Promise<IpcResponse> =>
      isAndroidNative
        ? getPlaybackPlugin().next()
        : Promise.resolve({ success: true } as IpcResponse),
    /** 原生队列权威切上一首 */
    previous: (): Promise<IpcResponse> =>
      isAndroidNative
        ? getPlaybackPlugin().previous()
        : Promise.resolve({ success: true } as IpcResponse),
    /** 原生播放指定索引曲目（原生解析并开播；positionMs 为起播进度） */
    playIndex: (index: number, positionMs = 0): Promise<IpcResponse> =>
      isAndroidNative
        ? getPlaybackPlugin().playIndex({ index, positionMs })
        : Promise.resolve({ success: true } as IpcResponse),
    /** JS 驱动播放时推送状态到原生通知栏 */
    syncRemoteState: (payload: Record<string, unknown>): Promise<void> =>
      isAndroidNative ? getPlaybackPlugin().syncRemoteState(payload) : Promise.resolve(),
    /** 注册原生 customAction 事件监听（媒体按钮 / 通知栏按钮 / Java 自治切歌） */
    onCustomAction: (callback: (data: unknown) => void): (() => void) => {
      if (!isAndroidNative) return noop;
      const plugin = getPlaybackPlugin();
      let listener: { remove: () => void } | null = null;
      void plugin
        .addListener("customAction", (data: unknown) => {
          callback(data);
        })
        .then((handle) => {
          listener = handle;
        });
      return () => {
        listener?.remove();
      };
    },
    /** 读取当前桌面图标颜色变体，非原生环境返回 null */
    getAppIcon: (): Promise<AndroidAppIconVariant | null> =>
      isAndroidNative
        ? getAndroidAppIcon()
            .getIcon()
            .then((r) => r.icon)
        : Promise.resolve(null),
    /** 切换桌面图标颜色变体 */
    setAppIcon: (icon: AndroidAppIconVariant): Promise<void> =>
      isAndroidNative ? getAndroidAppIcon().setIcon({ icon }) : Promise.resolve(),
  },

  // ── system ──────────────────────────────────────────────────────────────────
  system: {
    toggleDevTools: () => (isAndroid ? toggleEruda() : electronApi().system.toggleDevTools()),
    showInExplorer: (_filePath: string) =>
      isAndroid
        ? (androidWarn("system", "showInExplorer"), Promise.resolve())
        : electronApi().system.showInExplorer(_filePath),
    setLocale: (locale: string): void =>
      isAndroid
        ? (androidWarn("system", "setLocale"), noop())
        : electronApi().system.setLocale(locale as LocaleCode),
    focusMainWindow: () =>
      isAndroid
        ? (androidWarn("system", "focusMainWindow"), Promise.resolve())
        : electronApi().system.focusMainWindow(),
    openSettings: (category?: string, highlight?: string) =>
      isAndroid
        ? (androidWarn("system", "openSettings"), Promise.resolve())
        : electronApi().system.openSettings(category, highlight),
    onOpenSettings: (callback: (payload: { category?: string; highlight?: string }) => void) =>
      isAndroid
        ? (androidWarn("system", "onOpenSettings"), noopReturn(noop)())
        : electronApi().system.onOpenSettings(callback),
    listFonts: () =>
      isAndroid
        ? (async () => {
            if (isAndroidNative) {
              try {
                const result = await getAndroidLocalLyric().listFonts();
                if (result && result.fonts) {
                  return result.fonts;
                }
              } catch (e) {
                console.warn("[bridge:android] listFonts failed", e);
              }
            }
            return [];
          })()
        : electronApi().system.listFonts(),
    importFont: () =>
      isAndroid
        ? (async () => {
            if (isAndroidNative) {
              try {
                return await getAndroidLocalLyric().importFont();
              } catch (e) {
                console.warn("[bridge:android] importFont failed", e);
                return { success: false, error: String(e) };
              }
            }
            return { success: false, error: "NOT_SUPPORTED" };
          })()
        : electronApi().system.importFont(),
    readImportedFonts: () =>
      isAndroid
        ? (async () => {
            if (isAndroidNative) {
              try {
                const result = await getAndroidLocalLyric().readImportedFonts();
                return result.fonts ?? [];
              } catch (e) {
                console.warn("[bridge:android] readImportedFonts failed", e);
                return [];
              }
            }
            return [];
          })()
        : Promise.resolve([]),
    fetchRemoteBytes: (url: string) =>
      isAndroidPreview
        ? Promise.resolve({
            success: false,
            error: "PREVIEW_UNAVAILABLE",
          } as IpcResponse<ArrayBuffer | null>)
        : isAndroid
          ? apiFetch<{ success: boolean; data: string | null; encoding?: string } | null>(
              `/api/system/fetchRemoteBytes?url=${encodeURIComponent(url)}`,
            )
              .then((resp) => {
                if (!resp || !resp.success || !resp.data) {
                  return { success: false, error: "NO_DATA" } as IpcResponse<ArrayBuffer | null>;
                }
                // 服务端 base64 编码返回，解码为 ArrayBuffer
                const binary = atob(resp.data);
                const bytes = new Uint8Array(binary.length);
                for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
                return { success: true, data: bytes.buffer } as IpcResponse<ArrayBuffer | null>;
              })
              .catch(
                () =>
                  ({ success: false, error: "NETWORK_ERROR" }) as IpcResponse<ArrayBuffer | null>,
              )
          : electronApi().system.fetchRemoteBytes(url),
    saveFile: (data: ArrayBuffer, defaultName: string) =>
      isAndroidPreview
        ? Promise.resolve({ success: false, error: "PREVIEW_UNAVAILABLE" })
        : isAndroidNative
          ? withBridgeTimeout(
              getAndroidDownload().saveFile({ data: bytesToBase64(data), fileName: defaultName }),
              SAVE_FILE_CALL_TIMEOUT_MS,
              "saveFile",
            )
              .then((res) => ({ success: true as const, path: res.path }))
              .catch((error) => ({ success: false as const, error: String(error) }))
          : electronApi().system.saveFile(data, defaultName),
    writeClipboardText: (text: string): Promise<void> =>
      isAndroidNative
        ? withBridgeTimeout(
            getAndroidClipboard().writeText({ text }),
            CLIPBOARD_CALL_TIMEOUT_MS,
            "writeClipboardText",
          )
        : navigator.clipboard.writeText(text),
    readClipboardText: (): Promise<string> =>
      isAndroidNative
        ? withBridgeTimeout(
            getAndroidClipboard().readText(),
            CLIPBOARD_CALL_TIMEOUT_MS,
            "readClipboardText",
          ).then((res) => res.text)
        : navigator.clipboard.readText(),
    relaunch: () =>
      isAndroidNative
        ? (getAppPlugin().exit(), Promise.resolve())
        : isAndroid
          ? Promise.resolve()
          : electronApi().system.relaunch(),
    // Android 无桌面式文件关联与协议唤起，冷启动分发时恒为空
    consumePendingAudioFiles: (): Promise<string[]> =>
      isAndroid ? Promise.resolve([]) : electronApi().system.consumePendingAudioFiles(),
    consumePendingProtocolUrl: (): Promise<string | null> =>
      isAndroid ? Promise.resolve(null) : electronApi().system.consumePendingProtocolUrl(),
    // Android 无主进程文件打开事件（外部文件由 SAF / 分享入口进入）
    onOpenFiles: (callback: (files: string[]) => void): (() => void) => {
      if (isAndroid) return noop;
      return electronApi().system.onOpenFiles(callback);
    },
    // Android WebView 拖拽的 File 拿不到本地绝对路径
    getPathForFile: (file: File): string =>
      isAndroid ? "" : electronApi().system.getPathForFile(file),
    // 关于页展示字段：Android 无安装器类型，平台按内核语义归为 linux
    installType: isAndroid ? "portable" : electronApi().system.installType,
    platform: isAndroid ? "linux" : electronApi().system.platform,
    osInfo: isAndroid
      ? { type: "Linux", arch: "arm64", release: "Android" }
      : electronApi().system.osInfo,
    openLogsDir: (): Promise<string> =>
      isAndroid
        ? (androidWarn("system", "openLogsDir"), Promise.resolve(""))
        : electronApi().system.openLogsDir(),
    testNetworkProxy: (): Promise<boolean> =>
      isAndroid
        ? (androidWarn("system", "testNetworkProxy"), Promise.resolve(false))
        : electronApi().system.testNetworkProxy(),
    // Android 无桌面协议唤起（orpheus:// 由系统层另外处理）
    onProtocolUrl: (callback: (url: string) => void): (() => void) => {
      if (isAndroid) return noop;
      return electronApi().system.onProtocolUrl(callback);
    },
  },

  // ── mcp ─────────────────────────────────────────────────────────────────────
  // MCP 配置注入是桌面-only 能力；Android 返回未监听态让设置页安全渲染
  mcp: {
    restart: (): Promise<McpStatus> =>
      isAndroid
        ? (androidWarn("mcp", "restart"),
          Promise.resolve({ listening: false, port: null, error: null }))
        : electronApi().mcp.restart(),
    getStatus: (): Promise<McpStatus> =>
      isAndroid
        ? Promise.resolve({ listening: false, port: null, error: null })
        : electronApi().mcp.getStatus(),
    getClientConfigParams: (): Promise<McpClientConfigParams> =>
      isAndroid
        ? (androidWarn("mcp", "getClientConfigParams"), Promise.resolve({ port: 0, accessKey: "" }))
        : electronApi().mcp.getClientConfigParams(),
    detectAgents: (): Promise<McpAgentApp[]> =>
      isAndroid
        ? (androidWarn("mcp", "detectAgents"), Promise.resolve([]))
        : electronApi().mcp.detectAgents(),
    injectAgentConfig: (_agentId: string, _params: McpClientConfigParams): Promise<boolean> =>
      isAndroid
        ? (androidWarn("mcp", "injectAgentConfig"), Promise.resolve(false))
        : electronApi().mcp.injectAgentConfig(_agentId, _params),
    onStatus: (callback: (status: McpStatus) => void): (() => void) => {
      if (isAndroid) return noop;
      return electronApi().mcp.onStatus(callback);
    },
  },

  // ── aiModel ─────────────────────────────────────────────────────────────────
  aiModel: {
    list: (): Promise<AiModelState> =>
      isAndroid
        ? (androidWarn("aiModel", "list"), Promise.resolve({ models: [], activeModelId: null }))
        : electronApi().aiModel.list(),
    save: (input: AiModelSaveInput): Promise<AiModelState> =>
      isAndroid
        ? (androidWarn("aiModel", "save"), Promise.resolve({ models: [], activeModelId: null }))
        : electronApi().aiModel.save(input),
    remove: (id: string): Promise<AiModelState> =>
      isAndroid
        ? (androidWarn("aiModel", "remove"), Promise.resolve({ models: [], activeModelId: null }))
        : electronApi().aiModel.remove(id),
    setActive: (id: string | null): Promise<AiModelState> =>
      isAndroid
        ? (androidWarn("aiModel", "setActive"),
          Promise.resolve({ models: [], activeModelId: null }))
        : electronApi().aiModel.setActive(id),
  },

  // ── cloud ───────────────────────────────────────────────────────────────────
  // 云盘上传依赖桌面主进程文件流；Android 网页端暂不可用
  cloud: {
    pickSongs: (): Promise<PickedSong[]> =>
      isAndroid
        ? (androidWarn("cloud", "pickSongs"), Promise.resolve([]))
        : electronApi().cloud.pickSongs(),
    uploadSong: (path: string, uploadId: string): Promise<CloudUploadResult> =>
      isAndroid
        ? (androidWarn("cloud", "uploadSong"), Promise.resolve({ success: false, instant: false }))
        : electronApi().cloud.uploadSong(path, uploadId),
    onUploadProgress: (callback: (progress: unknown) => void): (() => void) => {
      if (isAndroid) return noop;
      return electronApi().cloud.onUploadProgress(callback);
    },
  },

  // ── opencc ──────────────────────────────────────────────────────────────────
  // Android 无 opencc 原生依赖，恒等返回原文本
  opencc: {
    convert: (text: string, config: CjkTransformMode): Promise<string> =>
      isAndroid ? Promise.resolve(text) : electronApi().opencc.convert(text, config),
    convertBatch: (texts: string[], config: CjkTransformMode): Promise<string[]> =>
      isAndroid ? Promise.resolve(texts) : electronApi().opencc.convertBatch(texts, config),
  },

  // ── library ─────────────────────────────────────────────────────────────────
  library: {
    scan: (incremental?: boolean) =>
      isAndroidPreview
        ? Promise.resolve(ok())
        : isAndroid
          ? getAndroidLibrary().scan({ incremental: incremental ?? true })
          : electronApi().library.scan(incremental),
    cancelScan: () =>
      isAndroidPreview
        ? Promise.resolve(ok())
        : isAndroid
          ? getAndroidLibrary().cancelScan()
          : electronApi().library.cancelScan(),
    getTracks: () =>
      isAndroidPreview
        ? Promise.resolve(ok([]))
        : isAndroid
          ? getAndroidLibrary().getTracks()
          : electronApi().library.getTracks(),
    getAlbums: () =>
      isAndroidPreview
        ? Promise.resolve(ok([]))
        : isAndroid
          ? getAndroidLibrary().getAlbums()
          : electronApi().library.getAlbums(),
    getArtists: () =>
      isAndroidPreview
        ? Promise.resolve(ok([]))
        : isAndroid
          ? getAndroidLibrary().getArtists()
          : electronApi().library.getArtists(),
    getAlbumTracks: (albumName: string) =>
      isAndroidPreview
        ? Promise.resolve(ok([]))
        : isAndroid
          ? getAndroidLibrary().getAlbumTracks({ albumName })
          : electronApi().library.getAlbumTracks(albumName),
    getArtistTracks: (artistName: string) =>
      isAndroidPreview
        ? Promise.resolve(ok([]))
        : isAndroid
          ? getAndroidLibrary().getArtistTracks({ artistName })
          : electronApi().library.getArtistTracks(artistName),
    getTracksByIds: (ids: string[]) =>
      isAndroidPreview
        ? Promise.resolve(ok([]))
        : isAndroid
          ? getAndroidLibrary().getTracksByIds({ ids })
          : electronApi().library.getTracksByIds(ids),
    searchTracks: (query: string) =>
      isAndroidPreview
        ? Promise.resolve(ok([]))
        : isAndroid
          ? getAndroidLibrary().searchTracks({ query })
          : electronApi().library.searchTracks(query),
    getTrackCount: () =>
      isAndroidPreview
        ? Promise.resolve(ok(0))
        : isAndroid
          ? getAndroidLibrary().getTrackCount()
          : electronApi().library.getTrackCount(),
    getRandomTrack: () =>
      isAndroidPreview
        ? Promise.resolve(ok(null))
        : isAndroid
          ? getAndroidLibrary().getRandomTrack()
          : electronApi().library.getRandomTrack(),
    getRandomTracks: (limit: number) =>
      isAndroidPreview
        ? Promise.resolve(ok([]))
        : isAndroid
          ? getAndroidLibrary().getRandomTracks({ limit })
          : electronApi().library.getRandomTracks(limit),
    isScanning: () =>
      isAndroidPreview
        ? Promise.resolve(ok(false))
        : isAndroid
          ? getAndroidLibrary().isScanning()
          : electronApi().library.isScanning(),
    addScanDir: () =>
      isAndroidPreview
        ? Promise.resolve({ success: false, error: "PREVIEW_UNAVAILABLE" })
        : isAndroid
          ? getAndroidLibrary().pickMusicDirectory()
          : electronApi().library.addScanDir(),
    removeScanDir: (dir: string) =>
      isAndroid
        ? getAndroidLibrary().removeScanDir({ dir })
        : electronApi().library.removeScanDir(dir),
    getScanDirs: () =>
      isAndroidPreview
        ? Promise.resolve(ok([]))
        : isAndroid
          ? getAndroidLibrary().getScanDirs()
          : electronApi().library.getScanDirs(),
    deleteTracks: (paths: string[]) =>
      isAndroid
        ? getAndroidLibrary().deleteTracks({ paths })
        : electronApi().library.deleteTracks(paths),
    readTags: (path: string) =>
      isAndroid
        ? getAndroidLibrary()
            .readTags({ path })
            .then((res) => res as IpcResponse<TrackTags>)
        : electronApi().library.readTags(path),
    writeTags: (edits: TagEditRequest[]) =>
      isAndroid
        ? getAndroidLibrary()
            .writeTags({ edits })
            .then((res) => res as IpcResponse<TagWriteOutcome[]>)
        : electronApi().library.writeTags(edits),
    pickCoverImage: () =>
      isAndroid ? getAndroidLibrary().pickCoverImage() : electronApi().library.pickCoverImage(),
    fetchArtistAvatar: (artistName: string) =>
      isAndroidPreview
        ? Promise.resolve(ok(null))
        : isAndroid
          ? getAndroidLibrary().fetchArtistAvatar({ artistName })
          : electronApi().library.fetchArtistAvatar(artistName),
    prefetchArtistAvatars: (artistNames: string[]) =>
      isAndroidPreview
        ? Promise.resolve(ok({}))
        : isAndroid
          ? getAndroidLibrary().prefetchArtistAvatars({ artistNames })
          : electronApi().library.prefetchArtistAvatars(artistNames),
    onScanProgress: (callback: (progress: ScanProgress) => void) =>
      isAndroidPreview
        ? noopReturn(noop)()
        : isAndroid
          ? subscribeAndroidScanProgress(callback)
          : electronApi().library.onScanProgress(callback),
  } satisfies LibraryApi,

  // ── window ──────────────────────────────────────────────────────────────────
  window: {
    toggleDesktopLyric: () =>
      isAndroid
        ? (async () => {
            const granted = await requestOverlayWithGuidance();
            if (!granted) return false;
            const plugin = getPlaybackPlugin();
            await plugin.showDynamicIsland();
            return true;
          })()
        : electronApi().window.toggleDesktopLyric(),
    closeDesktopLyric: () =>
      isAndroid
        ? (async () => {
            androidWarn("window", "closeDesktopLyric");
            await getPlaybackPlugin().hideDynamicIsland();
          })()
        : electronApi().window.closeDesktopLyric(),
    isDesktopLyricOpen: () =>
      isAndroid ? Promise.resolve(false) : electronApi().window.isDesktopLyricOpen(),
    onDesktopLyricVisibilityChange: (callback: (open: boolean) => void) =>
      isAndroid
        ? noopReturn(noop)()
        : electronApi().window.onDesktopLyricVisibilityChange(callback),
    toggleDynamicIsland: () =>
      isAndroid
        ? (async () => {
            const plugin = getPlaybackPlugin();
            const { running } = await plugin.isDynamicIslandRunning();
            if (running) {
              await plugin.hideDynamicIsland();
              return false;
            }
            const granted = await requestOverlayWithGuidance();
            if (!granted) return false;
            await plugin.showDynamicIsland();
            return true;
          })()
        : electronApi().window.toggleDynamicIsland(),
    closeDynamicIsland: () =>
      isAndroid
        ? (async () => {
            await getPlaybackPlugin().hideDynamicIsland();
          })()
        : electronApi().window.closeDynamicIsland(),
    isDynamicIslandOpen: () =>
      isAndroid
        ? getPlaybackPlugin()
            .isDynamicIslandRunning()
            .then((r) => r.running)
        : electronApi().window.isDynamicIslandOpen(),
    onDynamicIslandVisibilityChange: (callback: (open: boolean) => void) =>
      isAndroid
        ? registerDynamicIslandVisibilityListener(callback)
        : electronApi().window.onDynamicIslandVisibilityChange(callback),
    toggleTaskbarLyric: () =>
      isAndroid
        ? (androidWarn("window", "toggleTaskbarLyric"), Promise.resolve(false))
        : electronApi().window.toggleTaskbarLyric(),
    closeTaskbarLyric: () =>
      isAndroid
        ? (androidWarn("window", "closeTaskbarLyric"), Promise.resolve())
        : electronApi().window.closeTaskbarLyric(),
    isTaskbarLyricOpen: () =>
      isAndroid ? Promise.resolve(false) : electronApi().window.isTaskbarLyricOpen(),
    onTaskbarLyricVisibilityChange: (callback: (open: boolean) => void) =>
      isAndroid
        ? noopReturn(noop)()
        : electronApi().window.onTaskbarLyricVisibilityChange(callback),
    minimize: (): void =>
      isAndroidNative
        ? void getPlaybackPlugin()
            .moveTaskToBack()
            .catch((error) => {
              console.warn("[android] moveTaskToBack failed", error);
            })
        : isAndroid
          ? (androidWarn("window", "minimize"), noop())
          : electronApi().window.minimize(),
    toggleMaximize: (): void =>
      isAndroid
        ? (androidWarn("window", "toggleMaximize"), noop())
        : electronApi().window.toggleMaximize(),
    isMaximized: () => (isAndroid ? Promise.resolve(false) : electronApi().window.isMaximized()),
    onMaximizeChange: (callback: (maximized: boolean) => void) =>
      isAndroid ? noopReturn(noop)() : electronApi().window.onMaximizeChange(callback),
    toggleFullscreen: (): void =>
      isAndroid
        ? (androidWarn("window", "toggleFullscreen"), noop())
        : electronApi().window.toggleFullscreen(),
    isFullscreen: () => (isAndroid ? Promise.resolve(false) : electronApi().window.isFullscreen()),
    onFullscreenChange: (callback: (fullscreen: boolean) => void) =>
      isAndroid ? noopReturn(noop)() : electronApi().window.onFullscreenChange(callback),
    hide: (): void =>
      isAndroid ? (androidWarn("window", "hide"), noop()) : electronApi().window.hide(),
    quit: (): void =>
      isAndroidNative
        ? void getPlaybackPlugin()
            .shutdownApp()
            .catch((error) => {
              console.warn("[android] shutdownApp failed", error);
            })
        : isAndroid
          ? (androidWarn("window", "quit"), noop())
          : electronApi().window.quit(),
  } satisfies WindowApi,

  // ── desktopLyric ────────────────────────────────────────────────────────────
  desktopLyric: {
    onConfigChange: (
      callback: (config: import("@shared/types/settings").DesktopLyricSettings) => void,
    ) =>
      isAndroid
        ? (androidWarn("desktopLyric", "onConfigChange"), noopReturn(noop)())
        : electronApi().desktopLyric.onConfigChange(callback),
    setHeight: (_height: number) =>
      isAndroid
        ? (androidWarn("desktopLyric", "setHeight"), Promise.resolve())
        : electronApi().desktopLyric.setHeight(_height),
    setUnlockButtonBounds: (_bounds: DesktopLyricUnlockButtonBounds): void =>
      isAndroid
        ? (androidWarn("desktopLyric", "setUnlockButtonBounds"), noop())
        : electronApi().desktopLyric.setUnlockButtonBounds(_bounds),
    move: (_x: number, _y: number): void =>
      isAndroid
        ? (androidWarn("desktopLyric", "move"), noop())
        : electronApi().desktopLyric.move(_x, _y),
    saveState: (): void =>
      isAndroid
        ? (androidWarn("desktopLyric", "saveState"), noop())
        : electronApi().desktopLyric.saveState(),
    onCursorInside: (callback: (inside: boolean) => void) =>
      isAndroid
        ? (androidWarn("desktopLyric", "onCursorInside"), noopReturn(noop)())
        : electronApi().desktopLyric.onCursorInside(callback),
  } satisfies DesktopLyricApi,

  // ── dynamicIsland ───────────────────────────────────────────────────────────
  dynamicIsland: {
    onConfigChange: (
      callback: (config: import("@shared/types/settings").DynamicIslandSettings) => void,
    ) =>
      isAndroid
        ? (androidWarn("dynamicIsland", "onConfigChange"), noopReturn(noop)())
        : electronApi().dynamicIsland.onConfigChange(callback),
    move: (_x: number, _y: number): void =>
      isAndroid
        ? (androidWarn("dynamicIsland", "move"), noop())
        : electronApi().dynamicIsland.move(_x, _y),
    saveState: (): void =>
      isAndroid
        ? (androidWarn("dynamicIsland", "saveState"), noop())
        : electronApi().dynamicIsland.saveState(),
    resize: (_width: number): void =>
      isAndroid
        ? (androidWarn("dynamicIsland", "resize"), noop())
        : electronApi().dynamicIsland.resize(_width),
    setShape: (_width: number | null): void =>
      isAndroid
        ? (androidWarn("dynamicIsland", "setShape"), noop())
        : electronApi().dynamicIsland.setShape(_width),
    setHeight: (_height: number): void =>
      isAndroid
        ? (androidWarn("dynamicIsland", "setHeight"), noop())
        : electronApi().dynamicIsland.setHeight(_height),
    getMode: () =>
      isAndroid
        ? Promise.resolve<"snapped" | "floating">("snapped")
        : electronApi().dynamicIsland.getMode(),
    onModeChange: (callback: (mode: "snapped" | "floating") => void) =>
      isAndroid
        ? (androidWarn("dynamicIsland", "onModeChange"), noopReturn(noop)())
        : electronApi().dynamicIsland.onModeChange(callback),
    onCursorInside: (callback: (inside: boolean) => void) =>
      isAndroid
        ? (androidWarn("dynamicIsland", "onCursorInside"), noopReturn(noop)())
        : electronApi().dynamicIsland.onCursorInside(callback),
  } satisfies DynamicIslandApi,

  // ── taskbarLyric ────────────────────────────────────────────────────────────
  taskbarLyric: {
    onLayout: (callback: (data: TaskbarLyricLayoutEvent) => void) =>
      isAndroid
        ? (androidWarn("taskbarLyric", "onLayout"), noopReturn(noop)())
        : electronApi().taskbarLyric.onLayout(callback),
    onConfigChange: (callback: (config: TaskbarLyricSettings) => void) =>
      isAndroid
        ? (androidWarn("taskbarLyric", "onConfigChange"), noopReturn(noop)())
        : electronApi().taskbarLyric.onConfigChange(callback),
    setContentWidth: (_width: number): void =>
      isAndroid
        ? (androidWarn("taskbarLyric", "setContentWidth"), noop())
        : electronApi().taskbarLyric.setContentWidth(_width),
  } satisfies TaskbarLyricApi,

  // ── plugins ─────────────────────────────────────────────────────────────────
  plugins: {
    list: () =>
      isAndroid ? apiFetch<PluginInfo[]>("/api/plugins/list") : electronApi().plugins.list(),
    install: (filePath: string) =>
      isAndroid
        ? apiPost<{ ok: boolean; id?: string; error?: string }>("/api/plugins/install", {
            filePath,
          })
        : electronApi().plugins.install(filePath),
    pickAndInstall: () =>
      isAndroid ? pickPluginFileAndroid() : electronApi().plugins.pickAndInstall(),
    installFromUrl: (url: string) =>
      isAndroid
        ? apiPost<{ ok: boolean; id?: string; error?: string }>("/api/plugins/installFromUrl", {
            url,
          })
        : electronApi().plugins.installFromUrl(url),
    uninstall: (id: string) =>
      isAndroid
        ? apiPost<{ ok: boolean; error?: string }>("/api/plugins/uninstall", { id })
        : electronApi().plugins.uninstall(id),
    setEnabled: (id: string, enabled: boolean) =>
      isAndroid
        ? apiPost<void>("/api/plugins/setEnabled", { id, enabled })
        : electronApi().plugins.setEnabled(id, enabled),
    setSetting: (id: string, key: string, value: unknown) =>
      isAndroid
        ? apiPost<void>("/api/plugins/setSetting", { id, key, value })
        : electronApi().plugins.setSetting(id, key, value),
    checkUpdate: (id: string) =>
      isAndroid
        ? unsupportedPluginMethod("checkUpdate", { ok: false, hasUpdate: false })
        : electronApi().plugins.checkUpdate(id),
    applyUpdate: (id: string) =>
      isAndroid
        ? unsupportedPluginMethod("applyUpdate", {
            ok: false,
            error: "PLUGIN_UPDATE_NOT_SUPPORTED_ON_ANDROID",
          })
        : electronApi().plugins.applyUpdate(id),
    resolveUrl: (args: PluginResolveUrlArgs) =>
      isAndroid
        ? apiPost<MusicUrlRes>("/api/plugins/resolveUrl", args)
        : electronApi().plugins.resolveUrl(args),
    invokeMenu: (args: PluginInvokeMenuArgs) =>
      isAndroid
        ? unsupportedPluginMethod<PluginInvokeMenuResult>("invokeMenu", {
            ok: false,
            error: "PLUGIN_MENU_NOT_SUPPORTED_ON_ANDROID",
          })
        : electronApi().plugins.invokeMenu(args),
    matchLyric: (args: PluginMatchLyricArgs) =>
      isAndroid
        ? unsupportedPluginMethod<PluginMatchLyricResult>("matchLyric", {
            ok: false,
            error: "PLUGIN_LYRIC_MATCH_NOT_SUPPORTED_ON_ANDROID",
          })
        : electronApi().plugins.matchLyric(args),
    matchCover: (args: PluginMatchCoverArgs) =>
      isAndroid
        ? unsupportedPluginMethod<PluginMatchCoverResult>("matchCover", {
            ok: false,
            error: "PLUGIN_COVER_MATCH_NOT_SUPPORTED_ON_ANDROID",
          })
        : electronApi().plugins.matchCover(args),
    market: () =>
      isAndroid
        ? apiFetch<{ ok: boolean; plugins: MarketPlugin[]; error?: string }>("/api/plugins/market")
        : electronApi().plugins.market(),
    onStatus: (callback: (info: PluginInfo) => void) =>
      isAndroid ? watchPluginStatusAndroid(callback) : electronApi().plugins.onStatus(callback),
  } satisfies PluginsApi,

  // ── apis ────────────────────────────────────────────────────────────────────
  apis: {
    call: (platform: string, name: string, params?: Record<string, unknown>) =>
      isLanWebClient()
        ? Promise.resolve<ApiCallResponse>({ ok: false, error: "UNAVAILABLE_ON_LAN_CLIENT" })
        : isAndroid
          ? apiPost<ApiCallResponse>("/api/apis/call", {
              platform,
              name,
              params: params ?? {},
            })
          : electronApi().apis.call(platform as ApiPlatform, name, params),
    clearSession: (platform: string) =>
      isLanWebClient()
        ? Promise.resolve()
        : isAndroid
          ? apiPost<void>("/api/apis/clearSession", { platform })
          : electronApi().apis.clearSession(platform as ApiPlatform),
    openLoginWeb: (platform: string) =>
      isLanWebClient()
        ? Promise.resolve<{ ok: true } | { ok: false; error: string }>({
            ok: false,
            error: "UNAVAILABLE_ON_LAN_CLIENT",
          })
        : isAndroid
          ? apiPost<{ ok: true } | { ok: false; error: string }>("/api/apis/openLoginWeb", {
              platform,
            })
          : electronApi().apis.openLoginWeb(platform as ApiPlatform),
    setCookie: (platform: string, cookie: string) =>
      isLanWebClient()
        ? Promise.resolve<{ ok: true } | { ok: false; error: string }>({
            ok: false,
            error: "UNAVAILABLE_ON_LAN_CLIENT",
          })
        : isAndroid
          ? apiPost<{ ok: true } | { ok: false; error: string }>("/api/apis/setCookie", {
              platform,
              cookie,
            })
          : electronApi().apis.setCookie(platform as ApiPlatform, cookie),
  } satisfies ApisApi,

  // ── lyrics ──────────────────────────────────────────────────────────────────
  lyrics: {
    matchById: (platform: string, id: string) =>
      isAndroid
        ? apiPost<LyricMatchResponse>("/api/lyrics/matchById", {
            platform,
            id,
          })
        : electronApi().lyrics.matchById(platform as Platform, id),
    matchByQuery: (platform: string, track: unknown) =>
      isAndroid
        ? apiPost<LyricMatchResponse>("/api/lyrics/matchByQuery", {
            platform,
            track,
          })
        : electronApi().lyrics.matchByQuery(platform as Platform, track as Track),
    fetchTTMLOverlay: (track: unknown, platform: string) =>
      isAndroid
        ? apiPost<LyricTTMLResponse>("/api/lyrics/fetchTTMLOverlay", {
            track,
            platform,
            server: useSettingsStore().system.lyric.amllDbServer,
          })
        : electronApi().lyrics.fetchTTMLOverlay(track as Track, platform as "netease" | "qqmusic"),
    matchLocalTTML: (track: unknown) =>
      isAndroid
        ? Promise.resolve({ ok: false, error: "unsupported on Android" } as LyricTTMLResponse)
        : electronApi().lyrics.matchLocalTTML(track as Track),
    pickLyricRepoDir: () =>
      isAndroid
        ? getAndroidLocalLyric()
            .pickLyricDirectory()
            .then((r) => (r.cancelled || !r.uri ? null : r.uri))
        : electronApi().lyrics.pickLyricRepoDir(),
    findSidecarLyric: (audioPath: string, title?: string, artist?: string) =>
      isAndroid
        ? getAndroidLocalLyric()
            .findSidecarLyric({ audioPath, title, artist })
            .then((res) =>
              res.content ? { content: res.content as string, format: res.format as string } : null,
            )
        : Promise.resolve(null),
  } satisfies LyricsApi,

  // ── comments ────────────────────────────────────────────────────────────────
  comments: {
    sources: () =>
      isAndroid
        ? apiFetch<CommentSource[]>("/api/comments/sources")
        : electronApi().comments.sources(),
    get: (args: MusicCommentQuery): Promise<MusicCommentResponse> =>
      isAndroid
        ? apiPost<MusicCommentResponse>("/api/comments/get", args)
        : electronApi().comments.get(args),
  } satisfies CommentsApi,

  // ── nowPlaying ──────────────────────────────────────────────────────────────
  nowPlaying: {
    update: (payload: NowPlayingUpdatePayload): void => {
      if (isAndroid) {
        // Android：元数据由 MediaSessionManager.updateMetadata() 专路推送，
        // nowPlaying.update 的 payload 结构（{track, lyric, source}）与
        // Capacitor updateMetadata 期望的扁平字段不匹配，
        // 直接调用会把正确元数据覆盖为空值导致通知栏空白
      } else {
        electronApi().nowPlaying.update(payload);
      }
    },
    requestSnapshot: () =>
      isAndroidPreview
        ? Promise.resolve({
            track: null,
            lyric: [],
            source: null,
            position: 0,
            playing: false,
            speed: 1,
            lyricOffsetMs: 0,
            sendTimestamp: Date.now(),
          } as unknown as NowPlayingSnapshot)
        : isAndroid
          ? getPlaybackPlugin()
              .getStatus()
              .then((s) => {
                // Capacitor 插件直接返回 buildState() 的 JSObject，无 data 包装
                const state = s as unknown as Record<string, unknown>;
                return {
                  track: null,
                  lyric: [],
                  source: null,
                  position: Number(state.positionMs ?? 0),
                  playing: Boolean(state.playing),
                  speed: Number(state.playbackRate ?? 1),
                  lyricOffsetMs: 0,
                  sendTimestamp: Date.now(),
                } as unknown as NowPlayingSnapshot;
              })
          : electronApi().nowPlaying.requestSnapshot(),
    setLyricOffset: (trackId: string, offsetMs: number): void =>
      isAndroid
        ? (androidWarn("nowPlaying", "setLyricOffset"), noop())
        : electronApi().nowPlaying.setLyricOffset(trackId, offsetMs),
    onTrackChange: (callback: (data: { track: Track | null }) => void) =>
      isAndroid
        ? (androidWarn("nowPlaying", "onTrackChange"), noopReturn(noop)())
        : electronApi().nowPlaying.onTrackChange(callback),
    onLyricChange: (callback: (snapshot: NowPlayingSnapshot) => void) =>
      isAndroid
        ? (androidWarn("nowPlaying", "onLyricChange"), noopReturn(noop)())
        : electronApi().nowPlaying.onLyricChange(callback),
    onPositionSync: (callback: (data: NowPlayingPositionSync) => void) =>
      isAndroid
        ? (androidWarn("nowPlaying", "onPositionSync"), noopReturn(noop)())
        : electronApi().nowPlaying.onPositionSync(callback),
    onLyricOffsetChange: (callback: (data: NowPlayingLyricOffsetSync) => void) =>
      isAndroid
        ? (androidWarn("nowPlaying", "onLyricOffsetChange"), noopReturn(noop)())
        : electronApi().nowPlaying.onLyricOffsetChange(callback),
  } satisfies NowPlayingApi,

  // ── theme ───────────────────────────────────────────────────────────────────
  theme: {
    pickBackgroundImage: () =>
      isAndroid
        ? (androidWarn("theme", "pickBackgroundImage"), Promise.resolve(null))
        : electronApi().theme.pickBackgroundImage(),
    clearBackgroundImages: () =>
      isAndroid
        ? apiPost<void>("/api/theme/clearBackgroundImages", {})
        : electronApi().theme.clearBackgroundImages(),
  },

  // ── cache ───────────────────────────────────────────────────────────────────
  cache: {
    getStats: () =>
      isAndroid
        ? (async () => {
            // 路由到 Java 端 AndroidCachePlugin.getStats，转换为 UI 期望的格式
            const stats = await getAndroidCache().getStats();
            const dir = stats.cacheDir || "";
            // UI 类别 id ↔ Java 端 perType key
            const entries: { id: string; type: AndroidCacheType }[] = [
              { id: "covers", type: "covers" },
              { id: "songs", type: "exo" },
              { id: "lyrics", type: "lyrics" },
              { id: "list-covers", type: "list-covers" },
              { id: "list-data", type: "list-data" },
            ];
            const fileStats = entries.map((e) => ({
              id: e.id,
              kind: "file" as const,
              path: `${dir}/${e.type}`,
              size: stats.perType[e.type] || 0,
            }));
            // db 类别缓存（lyric_cache / lyric_ttml_cache / lyric_match_cache）
            const dbStats = stats.dbStats;
            const dbEntries = [
              {
                id: "lyric",
                kind: "db" as const,
                path: "lyric_cache",
                size: dbStats?.lyric ?? 0,
              },
              {
                id: "lyricTTML",
                kind: "db" as const,
                path: "lyric_ttml_cache",
                size: dbStats?.lyricTTML ?? 0,
              },
              {
                id: "lyricMatch",
                kind: "db" as const,
                path: "lyric_match_cache",
                size: dbStats?.lyricMatch ?? 0,
              },
            ];
            return [...fileStats, ...dbEntries];
          })()
        : electronApi().cache.getStats(),
    clear: (id: string) =>
      isAndroid
        ? (async () => {
            // db 类别走 clearDbCache
            const dbCategoryMap: Record<string, AndroidDbCacheCategory> = {
              lyric: "lyric",
              lyricTTML: "lyricTTML",
              lyricMatch: "lyricMatch",
            };
            const dbCategory = dbCategoryMap[id];
            if (dbCategory) {
              await getAndroidCache().clearDbCache({ category: dbCategory });
              return;
            }
            // file 类别走 clear
            const typeMap: Record<string, AndroidCacheType> = {
              covers: "covers",
              songs: "exo",
              lyrics: "lyrics",
              "list-covers": "list-covers",
              "list-data": "list-data",
            };
            const type = typeMap[id];
            if (!type) {
              console.warn(`[bridge] cache.clear: unknown id "${id}"，已忽略`);
              return;
            }
            await getAndroidCache().clear({ type });
          })()
        : electronApi().cache.clear(id),
    clearAllByKind: (kind: "file" | "db") =>
      isAndroid
        ? (async () => {
            if (kind === "file") {
              await getAndroidCache().clearAll();
            } else {
              await getAndroidCache().clearAllDbCache();
            }
          })()
        : electronApi().cache.clearAllByKind(kind),
    getDir: () =>
      isAndroid
        ? getAndroidCache()
            .getDir()
            .then((res) => res.dir)
        : electronApi().cache.getDir(),
    pickDir: () =>
      isAndroid
        ? (androidWarn("cache", "pickDir"),
          Promise.resolve({ ok: false as const, dir: "", reason: "canceled" as const }))
        : electronApi().cache.pickDir(),
    resetDir: () =>
      isAndroid
        ? // Android 端缓存目录由系统管理且不可改，"重置目录" 无意义；直接回当前路径，不动数据
          getAndroidCache()
            .getDir()
            .then((res) => res.dir)
        : electronApi().cache.resetDir(),
    song: {
      lookup: (cacheKey: string) =>
        isAndroid
          ? AndroidSongCache.lookup({ cacheKey })
              .then((res) => res.path)
              .catch(() => null)
          : electronApi().cache.song.lookup(cacheKey),
      fetch: (cacheKey: string, source: TrackSource, streamUrl: string) =>
        isAndroid
          ? AndroidSongCache.fetch({ cacheKey, streamUrl }).then((res) => res.path)
          : electronApi().cache.song.fetch(cacheKey, source, streamUrl),
      cancel: (cacheKey: string) =>
        isAndroid
          ? AndroidSongCache.cancel({ cacheKey })
          : electronApi().cache.song.cancel(cacheKey),
    },
  },

  // ── stats ───────────────────────────────────────────────────────────────────
  stats: {
    recordPlay: (event: PlayEventInput): void => {
      if (isAndroid) {
        void apiPost<void>("/api/stats/recordPlay", event).catch(() => {});
      } else {
        electronApi().stats.recordPlay(event);
      }
    },
    recordFavorite: (event: FavoriteEventInput): void => {
      if (isAndroid) {
        void apiPost<void>("/api/stats/recordFavorite", event).catch(() => {});
      } else {
        electronApi().stats.recordFavorite(event);
      }
    },
    getStatsSummary: () =>
      isAndroid
        ? apiFetch<PlayStatsSummary>("/api/stats/getStatsSummary")
        : electronApi().stats.getStatsSummary(),
    getTopTracks: (limit: number) =>
      isAndroid
        ? apiFetch<TopTrack[]>(`/api/stats/getTopTracks?limit=${limit}`)
        : electronApi().stats.getTopTracks(limit),
    getLibraryStats: () =>
      isAndroidPreview
        ? // 预览无曲库插件，返回空概览避免形状不匹配
          Promise.resolve({
            trackCount: 0,
            albumCount: 0,
            artistCount: 0,
            totalDurationMs: 0,
            totalFileSize: 0,
            codecs: [],
          } satisfies LibraryStats)
        : isAndroid
          ? androidLibraryStats()
          : electronApi().stats.getLibraryStats(),
    getPlayHistoryDaily: (days: number) =>
      isAndroid
        ? apiFetch<DailyPlayStats[]>(`/api/stats/getPlayHistoryDaily?days=${days}`)
        : electronApi().stats.getPlayHistoryDaily(days),
    getPlayHistoryHourly: () =>
      isAndroid
        ? apiFetch<HourlyPlayStats[]>("/api/stats/getPlayHistoryHourly")
        : electronApi().stats.getPlayHistoryHourly(),
    getTopAlbums: (limit: number) =>
      isAndroid
        ? apiFetch<TopAlbum[]>(`/api/stats/getTopAlbums?limit=${limit}`)
        : electronApi().stats.getTopAlbums(limit),
    getTopArtists: (limit: number) =>
      isAndroid
        ? apiFetch<TopArtist[]>(`/api/stats/getTopArtists?limit=${limit}`)
        : electronApi().stats.getTopArtists(limit),
  } satisfies StatsApi,

  // ── hotkey ──────────────────────────────────────────────────────────────────
  hotkey: {
    getAll: () =>
      isAndroid
        ? apiFetch<HotkeyConfig>("/api/config/get?keyPath=hotkeys")
        : electronApi().hotkey.getAll(),
    set: (id: HotkeyActionId, binding: HotkeyBinding) =>
      isAndroid
        ? (async () => {
            const cfg = await apiFetch<HotkeyConfig>("/api/config/get?keyPath=hotkeys");
            cfg.bindings[id] = binding;
            await apiPost("/api/config/set", { keyPath: "hotkeys", value: cfg });
            return cfg;
          })()
        : electronApi().hotkey.set(id, binding),
    reset: (id?: HotkeyActionId) =>
      isAndroid
        ? (async () => {
            if (id) {
              const cfg = await apiFetch<HotkeyConfig>("/api/config/get?keyPath=hotkeys");
              cfg.bindings[id] = { ...defaultHotkeyConfig.bindings[id] };
              await apiPost("/api/config/set", { keyPath: "hotkeys", value: cfg });
              return cfg;
            }
            await apiPost("/api/config/set", { keyPath: "hotkeys", value: defaultHotkeyConfig });
            return structuredClone(defaultHotkeyConfig);
          })()
        : electronApi().hotkey.reset(id),
    setGlobalEnabled: (enabled: boolean) =>
      isAndroid
        ? (async () => {
            const cfg = await apiFetch<HotkeyConfig>("/api/config/get?keyPath=hotkeys");
            cfg.globalEnabled = enabled;
            await apiPost("/api/config/set", { keyPath: "hotkeys", value: cfg });
            return cfg;
          })()
        : electronApi().hotkey.setGlobalEnabled(enabled),
    probe: (accelerator: string) =>
      isAndroid ? Promise.resolve(true) : electronApi().hotkey.probe(accelerator),
    getConflicts: () => (isAndroid ? Promise.resolve([]) : electronApi().hotkey.getConflicts()),
    onTrigger: (callback: (id: HotkeyActionId) => void) =>
      isAndroid ? noopReturn(noop)() : electronApi().hotkey.onTrigger(callback),
    onConflicts: (callback: (conflicts: HotkeyConflict[]) => void) =>
      isAndroid ? noopReturn(noop)() : electronApi().hotkey.onConflicts(callback),
  } satisfies HotkeyApi,

  // ── streaming ───────────────────────────────────────────────────────────────
  streaming: {
    // Android 内嵌服务无流媒体服务器管理，读操作返回空结构、写操作拒绝，避免冗余请求与服务端结构不匹配问题
    loadServers: () =>
      isAndroid
        ? Promise.resolve({ servers: [], activeServerId: null } as {
            servers: StreamingServerConfig[];
            activeServerId: string | null;
          })
        : electronApi().streaming.loadServers(),
    addServer: (input: StreamingServerInput) =>
      isAndroid
        ? unsupportedStreamingMethod("addServer")
        : electronApi().streaming.addServer(input),
    updateServer: (serverId: string, input: StreamingServerInput) =>
      isAndroid
        ? unsupportedStreamingMethod("updateServer")
        : electronApi().streaming.updateServer(serverId, input),
    removeServer: (serverId: string) =>
      isAndroid
        ? (androidWarn("streaming", "removeServer"), Promise.resolve())
        : electronApi().streaming.removeServer(serverId),
    setActiveServer: (serverId: string | null) =>
      isAndroid
        ? (androidWarn("streaming", "setActiveServer"), Promise.resolve())
        : electronApi().streaming.setActiveServer(serverId),
    testConnection: (input: StreamingServerInput, serverId?: string) =>
      isAndroid
        ? (androidWarn("streaming", "testConnection"),
          Promise.resolve({
            ok: false,
            error: "STREAMING_NOT_SUPPORTED_ON_ANDROID",
            code: "unknown",
          } satisfies StreamingPingResult))
        : electronApi().streaming.testConnection(input, serverId),
    connect: (serverId: string) =>
      isAndroid
        ? (androidWarn("streaming", "connect"),
          Promise.resolve({
            ok: false,
            error: "STREAMING_NOT_SUPPORTED_ON_ANDROID",
            code: "unknown",
          } satisfies StreamingConnectResult))
        : electronApi().streaming.connect(serverId),
    disconnect: (serverId: string) =>
      isAndroid
        ? (androidWarn("streaming", "disconnect"), Promise.resolve())
        : electronApi().streaming.disconnect(serverId),
    getSnapshot: (serverId: string) =>
      isAndroid
        ? (androidWarn("streaming", "getSnapshot"),
          Promise.resolve({
            songs: [],
            albums: [],
            artists: [],
            playlists: [],
          } satisfies StreamingLibrarySnapshot))
        : electronApi().streaming.getSnapshot(serverId),
    sync: (serverId: string, force?: boolean) =>
      isAndroid
        ? (androidWarn("streaming", "sync"), Promise.resolve(false))
        : electronApi().streaming.sync(serverId, force),
    onLibraryUpdated: (callback: (serverId: string) => void) =>
      isAndroid
        ? (androidWarn("streaming", "onLibraryUpdated"), noopReturn(noop)())
        : electronApi().streaming.onLibraryUpdated(callback),
    search: (serverId: string, query: string) =>
      isAndroid
        ? (androidWarn("streaming", "search"),
          Promise.resolve({
            songs: [],
            albums: [],
            artists: [],
          } satisfies StreamingSearchResult))
        : electronApi().streaming.search(serverId, query),
    getAlbumSongs: (serverId: string, albumId: string) =>
      isAndroid
        ? (androidWarn("streaming", "getAlbumSongs"), Promise.resolve([]))
        : electronApi().streaming.getAlbumSongs(serverId, albumId),
    getPlaylistSongs: (serverId: string, playlistId: string) =>
      isAndroid
        ? (androidWarn("streaming", "getPlaylistSongs"), Promise.resolve([]))
        : electronApi().streaming.getPlaylistSongs(serverId, playlistId),
    getArtistAlbums: (serverId: string, artistId: string) =>
      isAndroid
        ? (androidWarn("streaming", "getArtistAlbums"), Promise.resolve([]))
        : electronApi().streaming.getArtistAlbums(serverId, artistId),
    getArtistSongs: (serverId: string, artistId: string) =>
      isAndroid
        ? (androidWarn("streaming", "getArtistSongs"), Promise.resolve([]))
        : electronApi().streaming.getArtistSongs(serverId, artistId),
    getStreamUrl: (serverId: string, trackId: string, playSessionId?: string) =>
      isAndroid
        ? unsupportedStreamingMethod("getStreamUrl")
        : electronApi().streaming.getStreamUrl(serverId, trackId, playSessionId),
    getLyrics: (serverId: string, trackId: string, hint?: { artist?: string; title?: string }) =>
      isAndroid
        ? (androidWarn("streaming", "getLyrics"), Promise.resolve(null))
        : electronApi().streaming.getLyrics(serverId, trackId, hint),
  } satisfies StreamingApi,

  // ── lastfm ──────────────────────────────────────────────────────────────────
  lastfm: {
    connect: () =>
      isAndroid
        ? apiPost<LastfmConnectResult>("/api/lastfm/connect", {})
        : electronApi().lastfm.connect(),
    cancelConnect: () =>
      isAndroid
        ? apiPost<void>("/api/lastfm/cancelConnect", {})
        : electronApi().lastfm.cancelConnect(),
    disconnect: () =>
      isAndroid ? apiPost<void>("/api/lastfm/disconnect", {}) : electronApi().lastfm.disconnect(),
    getStatus: () =>
      isAndroid
        ? apiFetch<LastfmStatus>("/api/lastfm/getStatus")
        : electronApi().lastfm.getStatus(),
    love: (artist: string, track: string, loved: boolean) =>
      isAndroid
        ? apiPost<void>("/api/lastfm/love", { artist, track, loved })
        : electronApi().lastfm.love(artist, track, loved),
  } satisfies LastfmApi,

  // ── externalApi ─────────────────────────────────────────────────────────────
  externalApi: {
    restart: () =>
      isAndroidNative
        ? getExternalApiPlugin().restart()
        : isAndroid
          ? Promise.resolve({
              listening: false,
              allowLan: false,
              host: null,
              port: null,
              error: null,
            } satisfies ExternalApiStatus)
          : electronApi().externalApi.restart(),
    getStatus: () =>
      isAndroidNative
        ? getExternalApiPlugin().getStatus()
        : isAndroid
          ? Promise.resolve({
              listening: false,
              allowLan: false,
              host: null,
              port: null,
              error: null,
            } satisfies ExternalApiStatus)
          : electronApi().externalApi.getStatus(),
    // Android 原生层无状态推送事件，设置页以 getStatus 轮询为准
    onStatus: (callback: (status: ExternalApiStatus) => void): (() => void) => {
      if (isAndroid) return noop;
      return electronApi().externalApi.onStatus(callback);
    },
  },

  // ── download ────────────────────────────────────────────────────────────────
  // Android 端目录管理走 SAF（AndroidDownload 插件）+ config API 持久化 URI；
  // 下载任务管理（start/cancel/retry/remove/clearFinished/list）暂未实现，使用 stub 优雅降级
  download: {
    start: (_req: DownloadRequest) =>
      isAndroid ? androidDownloadManager.start(_req) : electronApi().download.start(_req),
    cancel: (taskId: string) =>
      isAndroid ? androidDownloadManager.cancel(taskId) : electronApi().download.cancel(taskId),
    retry: (req: DownloadRequest) =>
      isAndroid ? androidDownloadManager.retry(req) : electronApi().download.retry(req),
    // Android 下载由原生 SAF 通道逐首处理，顺序入队避免并发争抢目录句柄
    startMany: async (reqs: DownloadRequest[]): Promise<EnqueueResult[]> => {
      if (!isAndroid) return electronApi().download.startMany(reqs);
      const results: EnqueueResult[] = [];
      for (const req of reqs) {
        results.push(await androidDownloadManager.start(req));
      }
      return results;
    },
    // Android 音源解析在原生/嵌入式 API 内完成，渲染层不参与解析回调
    submitResolution: (taskId: string, res: DownloadResolution): Promise<void> =>
      isAndroid ? Promise.resolve() : electronApi().download.submitResolution(taskId, res),
    failResolution: (taskId: string): Promise<void> =>
      isAndroid ? Promise.resolve() : electronApi().download.failResolution(taskId),
    remove: (taskId: string) =>
      isAndroid ? androidDownloadManager.remove(taskId) : electronApi().download.remove(taskId),
    clearFinished: () =>
      isAndroid ? androidDownloadManager.clearFinished() : electronApi().download.clearFinished(),
    list: () => (isAndroid ? androidDownloadManager.list() : electronApi().download.list()),
    pickDir: () =>
      isAndroid
        ? (async () => {
            const result = await getAndroidDownload().pickDownloadDirectory();
            if (result.cancelled || !result.uri) {
              const current = await apiFetch<string | null>(
                "/api/config/get?keyPath=download.dir",
              ).catch(() => null);
              return { ok: false as const, dir: current ?? "", reason: "canceled" as const };
            }
            await apiPost("/api/config/set", { keyPath: "download.dir", value: result.uri });
            return { ok: true as const, dir: result.uri };
          })()
        : electronApi().download.pickDir(),
    getDir: () =>
      isAndroid
        ? apiFetch<string | null>("/api/config/get?keyPath=download.dir").then((dir) =>
            typeof dir === "string" ? dir : "",
          )
        : electronApi().download.getDir(),
    resetDir: () =>
      isAndroid
        ? apiPost("/api/config/set", { keyPath: "download.dir", value: null }).then(() => "")
        : electronApi().download.resetDir(),
    onProgress: (callback: (data: DownloadProgress) => void) =>
      isAndroid
        ? androidDownloadManager.onProgress(callback)
        : electronApi().download.onProgress(callback),
    onState: (callback: (task: DownloadTask) => void) =>
      isAndroid
        ? androidDownloadManager.onState(callback)
        : electronApi().download.onState(callback),
    onResolve: (callback: (payload: DownloadResolvePayload) => void): (() => void) => {
      if (isAndroid) return noop;
      return electronApi().download.onResolve(callback);
    },
  } satisfies DownloadApi,

  // ── update ──────────────────────────────────────────────────────────────────
  update: {
    check: (manual: boolean) => {
      if (!isAndroid) return electronApi().update.check(manual);
      // Android 无应用内更新通道：合成 notAvailable 事件，避免 UI 永久停留在 checking
      androidUpdateListener?.({ type: "notAvailable", manual });
      return Promise.resolve();
    },
    download: () =>
      isAndroid
        ? (androidWarn("update", "download"), Promise.resolve())
        : electronApi().update.download(),
    install: () =>
      isAndroid
        ? (androidWarn("update", "install"), Promise.resolve())
        : electronApi().update.install(),
    openDownloadPage: () =>
      isAndroid
        ? (window.open("https://github.com/imsyy/SPlayer/releases", "_blank"), Promise.resolve())
        : electronApi().update.openDownloadPage(),
    onEvent: (callback: (event: UpdateEvent) => void) => {
      if (!isAndroid) return electronApi().update.onEvent(callback);
      androidUpdateListener = callback;
      return () => {
        if (androidUpdateListener === callback) androidUpdateListener = null;
      };
    },
  } satisfies UpdateApi,

  // ── playlist（本地歌单，Android 无 SQLite 歌单后端，降级为空实现）──────────
  playlist: {
    list: () => Promise.resolve([]),
    get: () => Promise.resolve(null),
    create: (input) => {
      androidWarn("playlist", "create");
      const now = Date.now();
      return Promise.resolve({
        id: `local-android-${now}`,
        type: input.type,
        title: input.title,
        description: input.description,
        trackCount: 0,
        createTime: now,
        updateTime: now,
      });
    },
    update: (_id, _input) => (androidWarn("playlist", "update"), Promise.resolve(null)),
    remove: (_id) => (androidWarn("playlist", "remove"), Promise.resolve()),
    addTracks: (_id, _trackIds) => (androidWarn("playlist", "addTracks"), Promise.resolve(0)),
    removeTracks: (_id, _trackIds) => (androidWarn("playlist", "removeTracks"), Promise.resolve(0)),
    importLegacy: (_records) => (androidWarn("playlist", "importLegacy"), Promise.resolve()),
    clear: () => (androidWarn("playlist", "clear"), Promise.resolve()),
  } satisfies PlaylistApi,

  // ── recognition（听歌识曲，Android 无桌面音频采集能力，整体降级不支持）─────
  recognition: {
    isSupported: () => Promise.resolve(false),
    start: (_config) => (androidWarn("recognition", "start"), Promise.resolve(null)),
    cancel: () => (androidWarn("recognition", "cancel"), Promise.resolve(null)),
    submitPcm: (_pcm) => (androidWarn("recognition", "submitPcm"), Promise.resolve(null)),
    onEvent: (_callback) => noopReturn(noop)(),
  } satisfies RecognitionApi,

  // ── lanShare（局域网分享模式）──────────────────────────────────────────────
  lanShare: {
    getStatus: () =>
      isAndroidNative
        ? AndroidLanShare.getStatus()
        : isAndroid
          ? apiFetch<{
              enabled: boolean;
              collabEnabled: boolean;
              shareUserInfo: boolean;
              deviceCount: number;
              sharedCount: number;
              serverIp: string;
              wsToken?: string;
              collabAuthorized?: boolean;
            }>("/api/lanShare/getStatus")
          : Promise.resolve({
              enabled: false,
              collabEnabled: false,
              shareUserInfo: false,
              deviceCount: 0,
              sharedCount: 0,
              serverIp: "127.0.0.1",
              wsToken: "",
            }),
    setEnabled: (enabled: boolean) =>
      isAndroidNative
        ? AndroidLanShare.setEnabled({ enabled })
        : isAndroid
          ? apiPost<{ ok: true; enabled: boolean }>("/api/lanShare/setEnabled", { enabled })
          : Promise.resolve({ ok: true as const, enabled }),
    setCollabEnabled: (enabled: boolean) =>
      isAndroidNative
        ? AndroidLanShare.setCollabEnabled({ enabled })
        : isAndroid
          ? apiPost<{ ok: true; collabEnabled: boolean }>("/api/lanShare/setCollabEnabled", {
              enabled,
            })
          : Promise.resolve({ ok: true as const, collabEnabled: enabled }),
    setShareUserInfo: (enabled: boolean) =>
      isAndroidNative
        ? AndroidLanShare.setShareUserInfo({ enabled })
        : isAndroid
          ? apiPost<{ ok: true; shareUserInfo: boolean }>("/api/lanShare/setShareUserInfo", {
              enabled,
            })
          : Promise.resolve({ ok: true as const, shareUserInfo: enabled }),
    getDevices: () =>
      isAndroidNative
        ? (AndroidLanShare.getDevices() as unknown as Promise<{
            ok: true;
            devices: LanDevice[];
          }>)
        : isAndroid
          ? apiFetch<{
              ok: true;
              devices: LanDevice[];
            }>("/api/lanShare/getDevices")
          : Promise.resolve({ ok: true as const, devices: [] }),
    addDevice: (ip: string, name?: string) =>
      isAndroidNative
        ? (AndroidLanShare.addDevice({ ip, name }) as unknown as Promise<{
            ok: true;
            devices: LanDevice[];
          }>)
        : isAndroid
          ? apiPost<{
              ok: true;
              devices: LanDevice[];
            }>("/api/lanShare/addDevice", { ip, name })
          : Promise.resolve({ ok: true as const, devices: [] }),
    removeDevice: (ip: string) =>
      isAndroidNative
        ? (AndroidLanShare.removeDevice({ ip }) as unknown as Promise<{
            ok: true;
            devices: LanDevice[];
          }>)
        : isAndroid
          ? apiPost<{
              ok: true;
              devices: LanDevice[];
            }>("/api/lanShare/removeDevice", { ip })
          : Promise.resolve({ ok: true as const, devices: [] }),
    shareLogin: (ip: string, shared: boolean) =>
      isAndroidNative
        ? (AndroidLanShare.shareLogin({ ip, shared }) as unknown as Promise<{
            ok: true;
            device: LanDevice;
          }>)
        : isAndroid
          ? apiPost<{ ok: true; device: LanDevice }>("/api/lanShare/shareLogin", { ip, shared })
          : Promise.resolve({
              ok: true as const,
              device: {
                ip,
                name: "",
                sharedLogin: shared,
                shareCollab: false,
                addedAt: Date.now(),
              },
            }),
    setDeviceCollab: (ip: string, enabled: boolean) =>
      isAndroidNative
        ? (AndroidLanShare.setDeviceCollab({ ip, enabled }) as unknown as Promise<{
            ok: true;
            device: LanDevice;
          }>)
        : isAndroid
          ? apiPost<{ ok: true; device: LanDevice }>("/api/lanShare/setDeviceCollab", {
              ip,
              enabled,
            })
          : Promise.resolve({
              ok: true as const,
              device: { ip, sharedLogin: false, shareCollab: enabled, addedAt: Date.now() },
            }),
    broadcastPlayback: (state: Record<string, unknown>) =>
      isAndroidNative
        ? (AndroidLanShare.broadcastPlayback(state) as unknown as Promise<{ ok: true }>)
        : isAndroid
          ? apiPost<{ ok: true }>("/api/lanShare/broadcastPlayback", state).catch(() => ({
              ok: true as const,
            }))
          : Promise.resolve({ ok: true as const }),
    // 队列快照仅主机（Android 本机）可推送；其它平台 no-op
    updateQueue: (json: string) =>
      isAndroidNative
        ? AndroidLanShare.updateQueue({ json })
        : Promise.resolve({ ok: true as const }),
    // 当前歌词快照仅主机（Android 本机）可推送；其它平台 no-op
    updateLyric: (json: string) =>
      isAndroidNative
        ? AndroidLanShare.updateLyric({ json })
        : Promise.resolve({ ok: true as const }),
    resolveTrack: (track: Track, audioRevision?: number) =>
      isAndroid
        ? apiPost<{ ok: true; streamUrl: string }>("/api/lanShare/resolveTrack", {
            track,
            audioRevision,
          }).catch(() => ({ ok: false as const, streamUrl: "" }))
        : Promise.resolve({ ok: false as const, streamUrl: "" }),
    getLocalIPs: () =>
      isAndroidNative
        ? (AndroidLanShare.getLocalIPs() as unknown as Promise<{
            ok: true;
            ips: Array<{ name: string; address: string; family: "IPv4" | "IPv6" }>;
            port: number;
          }>)
        : isAndroid
          ? apiFetch<{
              ok: true;
              ips: Array<{ name: string; address: string; family: "IPv4" | "IPv6" }>;
              port: number;
            }>("/api/lanShare/getLocalIPs")
          : Promise.resolve({ ok: true as const, ips: [], port: 18098 }),
  },
};

export default bridge;
