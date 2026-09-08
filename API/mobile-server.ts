import {
  createReadStream,
  existsSync,
  mkdirSync,
  readFileSync,
  renameSync,
  statSync,
  unlinkSync,
  writeFileSync,
} from "fs";
import http, { IncomingMessage, ServerResponse } from "http";
import https from "https";
import { createRequire } from "module";
import path from "path";
import { gunzipSync, inflateSync } from "zlib";
import type { SystemConfig } from "../shared/types/settings";
import type { Artist, Track } from "../shared/types/player";
import type {
  DailyPlayStats,
  FavoriteEventInput,
  HourlyPlayStats,
  PlayEventInput,
  PlayStatsSummary,
  TopAlbum,
  TopArtist,
  TopTrack,
} from "../shared/types/stats";
import { decodeKrc } from "../electron/main/apis/kugou/core/krc";
import { decryptQrc } from "../electron/main/apis/qqmusic/core/qrc";
import {
  getSystemConfigStore as sharedGetSystemConfigStore,
  replaceSystemConfigStore as sharedReplaceSystemConfigStore,
  resetSystemConfigStore as sharedResetSystemConfigStore,
  writeSystemConfigStore as sharedWriteSystemConfigStore,
} from "./config-store";
import { handleAndroidPluginRoute } from "./plugins/android-routes";
import { androidPluginRegistry } from "./plugins/android-registry";

const DEFAULT_PORT = Number(process.env["SP_API_PORT"] || process.env["VITE_SERVER_PORT"] || 6688);
// Vite dev 模式下监听 0.0.0.0，局域网设备可访问；真机打包由 nodejs-mobile 自行管理
const DEFAULT_HOST = process.env["SP_API_HOST"] || "0.0.0.0";
const DEFAULT_AMLL_DB_SERVER =
  process.env["SP_AMLL_DB_SERVER"] || "https://amlldb.bikonoo.com/%p/%s.ttml";

/**
 * 校验请求自带的 AMLL DB 模板地址：仅接受 http(s) 且非私网 host，
 * 不合法时回落默认服务，防止被当作 SSRF 跳板探测内网。
 */
const resolveTtmlServerTemplate = (server: unknown): string => {
  if (typeof server !== "string" || !server) return DEFAULT_AMLL_DB_SERVER;
  try {
    const parsed = new URL(server);
    if (
      (parsed.protocol === "http:" || parsed.protocol === "https:") &&
      !isPrivateDevHost(parsed.hostname)
    ) {
      return server;
    }
  } catch {
    /* 非法 URL，回落默认 */
  }
  return DEFAULT_AMLL_DB_SERVER;
};
const ALLOWED_HEADERS =
  "Content-Type, Authorization, X-Requested-With, Accept, Origin, Range, X-SPlayer-Cookie";
const EMBEDDED_API_READY_EVENT = "embedded-api-ready";
const currentFilePath =
  typeof __filename !== "undefined"
    ? __filename
    : path.resolve(process.cwd(), "API", "mobile-server.ts");
const currentDirPath = path.dirname(currentFilePath);

type ApiFunction = (params: Record<string, unknown>) => Promise<{ body?: unknown } | unknown>;
type JsonObject = Record<string, unknown>;

type NeteaseLyricBody = {
  code?: number;
  yrc?: { lyric?: string };
  lrc?: { lyric?: string };
  ytlrc?: { lyric?: string };
  tlyric?: { lyric?: string };
  yromalrc?: { lyric?: string };
  romalrc?: { lyric?: string };
};

type NeteaseSong = {
  id?: string | number;
  name?: string;
  artists?: Array<{ name?: string }>;
  ar?: Array<{ name?: string }>;
  album?: { name?: string };
  al?: { name?: string };
  duration?: number;
  dt?: number;
};

type ProviderASong = {
  id?: string | number;
  mid?: string;
  name?: string;
  title?: string;
  artist?: string;
  singer?: Array<{ name?: string }>;
  album?: string | { name?: string; mid?: string };
  albumMid?: string;
  duration?: number;
  interval?: number;
};

type ProviderBSong = {
  id?: string | number;
  hash?: string;
  name?: string;
  songname?: string;
  filename?: string;
  artist?: string;
  singername?: string;
  album?: string;
  album_name?: string;
  duration?: number;
  interval?: number;
  hashes?: Record<string, string>;
};

type LyricCandidate<Extra> = {
  name: string;
  artist: string;
  album?: string;
  duration?: number;
  extra: Extra;
};

type LyricCandidateMatch<Extra> = {
  candidate: LyricCandidate<Extra>;
  score: number;
  nameExact: boolean;
  artistExact: boolean;
  artistContains: boolean;
  albumExact: boolean;
  durationClose: boolean;
  versionConflict: boolean;
};

type MobileLyricResult = {
  platform: string;
  format: "yrc" | "qrc" | "krc" | "lrc";
  content: string;
  translation?: string;
  translationFormat?: "lrc";
  romaji?: string;
  romajiFormat?: "yrc" | "qrc" | "krc" | "lrc";
  extra?: Record<string, unknown>;
};

const packagedNeteaseApiRoot = path.join(currentDirPath, "vendor", "netease-api");
const sourceNeteaseApiRoot = path.resolve(
  currentDirPath,
  "..",
  "node_modules",
  "@neteasecloudmusicapienhanced",
  "api",
);
const EMBEDDED_API_VENDOR_ROOT = existsSync(packagedNeteaseApiRoot)
  ? packagedNeteaseApiRoot
  : sourceNeteaseApiRoot;
const EMBEDDED_API_MAIN_ENTRY = path.join(EMBEDDED_API_VENDOR_ROOT, "main.js");
const nodeRequire = createRequire(EMBEDDED_API_MAIN_ENTRY);
const CONFIG_DIR =
  process.env["SP_CONFIG_DIR"] || path.resolve(currentDirPath, "..", "..", "splayer-data");
const STATS_PATH = path.join(CONFIG_DIR, "stats.json");
const MAX_PLAY_HISTORY = 5000;
const MAX_FAVORITE_HISTORY = 2000;
const NETEASE_API_ALIASES: Record<string, string> = {
  // Electron 侧历史模块名；增强版包内对应模块名为 playmode_intelligence_list。
  playmode_intelligence: "playmode_intelligence_list",
};

const decodeAscii = (codes: number[]) => String.fromCharCode(...codes);
const buildHttpsUrl = (hostCodes: number[], path: string) =>
  `https://${decodeAscii(hostCodes)}${path}`;
const PLATFORM_A_ID = decodeAscii([113, 113, 109, 117, 115, 105, 99]);
const PLATFORM_B_ID = decodeAscii([107, 117, 103, 111, 117]);
const PLATFORM_A_DB_PATH = decodeAscii([113, 113, 45, 108, 121, 114, 105, 99, 115]);
const PROVIDER_A_API_URL = buildHttpsUrl(
  [117, 46, 121, 46, 113, 113, 46, 99, 111, 109],
  "/cgi-bin/musicu.fcg",
);
const PROVIDER_A_SESSION_TTL_MS = 60 * 60 * 1000;
const PROVIDER_A_HEADERS: Record<string, string> = {
  "Content-Type": "application/json",
  "Accept-Encoding": "gzip",
  "User-Agent": "okhttp/3.14.9",
  Referer: buildHttpsUrl([121, 46, 113, 113, 46, 99, 111, 109], ""),
  Cookie: "tmeLoginType=-1;",
};

const PROVIDER_B_SEARCH_PRIMARY_URL = buildHttpsUrl(
  [109, 111, 98, 105, 108, 101, 99, 100, 110, 46, 107, 117, 103, 111, 117, 46, 99, 111, 109],
  "/api/v3/search/song",
);
const PROVIDER_B_SEARCH_FALLBACK_URL = buildHttpsUrl(
  [115, 111, 110, 103, 115, 101, 97, 114, 99, 104, 46, 107, 117, 103, 111, 117, 46, 99, 111, 109],
  "/song_search_v2",
);
const PROVIDER_B_LYRIC_SEARCH_URL = buildHttpsUrl(
  [108, 121, 114, 105, 99, 115, 46, 107, 117, 103, 111, 117, 46, 99, 111, 109],
  "/search",
);
const PROVIDER_B_LYRIC_DOWNLOAD_URL = buildHttpsUrl(
  [108, 121, 114, 105, 99, 115, 46, 107, 117, 103, 111, 117, 46, 99, 111, 109],
  "/download",
);
const PROVIDER_B_LYRIC_HEADERS: Record<string, string> = {
  [decodeAscii([75, 71, 45, 82, 67])]: "1",
  [decodeAscii([75, 71, 45, 84, 72, 97, 115, 104])]: "expand_search_manager.cpp:852736169:451",
  "User-Agent": "KuGou2012-9020-ExpandSearchManager",
};

let generateNeteaseApiConfig: (() => Promise<void>) | null = null;
let neteaseApiConfigPromise: Promise<void> | null = null;

const notifyEmbeddedApiReady = () => {
  try {
    // 尝试多种方式加载 cordova-bridge，避免因 process.mainModule 弃用而丢失就绪通知
    type CordovaBridge = { channel?: { send?: (payload: unknown) => void } };
    let cordova: CordovaBridge | null = null;

    // 1. 现代 Node.js 方式：使用 module.createRequire
    try {
      // module.createRequire 自 Node 12.2.0 起可用，旧版 @types/node 可能缺失类型
      const createRequireFn: typeof createRequire | undefined =
        typeof (module as { createRequire?: unknown }).createRequire === "function"
          ? (module as unknown as { createRequire: typeof createRequire }).createRequire
          : undefined;
      const modRequire = createRequireFn ? createRequireFn(currentFilePath) : null;
      if (modRequire) {
        cordova = modRequire("cordova-bridge") as CordovaBridge;
      }
    } catch {}

    // 2. nodejs-mobile 传统方式：globalThis.require 或 process.mainModule.require
    if (!cordova) {
      // process.mainModule 自 Node 14 起弃用，但 nodejs-mobile 运行时仍需要
      const mainModule = (process as { mainModule?: { require?: (id: string) => unknown } })
        .mainModule;
      const globalRequire =
        typeof globalThis.require === "function"
          ? globalThis.require
          : typeof mainModule?.require === "function"
            ? mainModule.require
            : null;
      if (globalRequire) {
        cordova = globalRequire("cordova-bridge") as CordovaBridge;
      }
    }

    // 3. 直接从全局对象获取（某些 nodejs-mobile 版本会注入）
    if (!cordova) {
      cordova = (globalThis as unknown as Record<string, unknown>)[
        "cordova-bridge"
      ] as CordovaBridge | null;
    }

    if (!cordova) {
      console.warn(
        "[embedded-api] 无法加载 cordova-bridge，就绪通知未能发出（前端将通过轮询兜底）",
      );
      return;
    }

    if (!cordova.channel?.send) {
      console.warn("[embedded-api] cordova-bridge.channel.send 不可用，就绪通知未能发出");
      return;
    }

    cordova.channel.send(EMBEDDED_API_READY_EVENT);
    console.info("[embedded-api] 就绪通知已通过 cordova-bridge 发出");
  } catch (error) {
    console.warn("[embedded-api] notifyEmbeddedApiReady 失败:", error);
  }
};

const toPathCase = (value: string) => {
  return value
    .replace(/([a-z0-9])([A-Z])/g, "$1-$2")
    .replace(/_/g, "/")
    .replace(/-/g, "/")
    .toLowerCase();
};

const isLoopbackHost = (host: string) => {
  return host === "localhost" || host === "127.0.0.1" || host === "::1";
};

const isPrivateDevHost = (host: string) => {
  if (!host) return false;
  // 去掉 IPv6 字面量方括号（URL.hostname 保留 []）
  const normalized = host.replace(/^\[|\]$/g, "").toLowerCase();
  if (isLoopbackHost(normalized) || normalized === "0.0.0.0") return true;
  if (
    normalized.startsWith("192.168.") ||
    normalized.startsWith("10.") ||
    /^172\.(1[6-9]|2\d|3[0-1])\./.test(normalized) ||
    /^169\.254\./.test(normalized)
  ) {
    return true;
  }
  // IPv6 链路本地 fe80::/10、ULA fc00::/7、未指定地址 ::
  if (normalized === "::" || /^fe[89ab]/.test(normalized) || /^f[cd]/.test(normalized)) {
    return true;
  }
  // IPv4 映射形式 ::ffff:a.b.c.d —— 还原为 IPv4 再判
  const mapped = normalized.match(/^::ffff:(\d{1,3}(?:\.\d{1,3}){3})$/);
  if (mapped) return isPrivateDevHost(mapped[1]);
  return false;
};

const isAllowedLocalOrigin = (origin: string | undefined) => {
  if (!origin) return false;
  if (origin === "capacitor://localhost") return true;
  try {
    const url = new URL(origin);
    return (url.protocol === "http:" || url.protocol === "https:") && isLoopbackHost(url.hostname);
  } catch {
    return false;
  }
};

const isAllowedOrigin = (origin: string | undefined) => {
  if (!origin) return false;
  if (origin === "capacitor://localhost") return true;
  try {
    const url = new URL(origin);
    return (
      (url.protocol === "http:" || url.protocol === "https:") && isPrivateDevHost(url.hostname)
    );
  } catch {
    return false;
  }
};

const setCorsHeaders = (request: IncomingMessage, response: ServerResponse) => {
  const origin = request.headers.origin;
  if (origin && isAllowedOrigin(origin)) {
    response.setHeader("Access-Control-Allow-Origin", origin);
    response.setHeader("Access-Control-Allow-Credentials", "true");
    response.setHeader("Vary", "Origin");
  }
  response.setHeader("Access-Control-Allow-Headers", ALLOWED_HEADERS);
  response.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
};

const getHeaderValue = (value: string | string[] | undefined) => {
  return Array.isArray(value) ? value[0] : value;
};

const getApiErrorStatus = (status?: number) => {
  return typeof status === "number" && status >= 400 && status < 600 ? status : 500;
};

const ensureNeteaseApiConfig = async () => {
  if (!neteaseApiConfigPromise) {
    generateNeteaseApiConfig ||= nodeRequire(
      path.join(EMBEDDED_API_VENDOR_ROOT, "generateConfig.js"),
    ) as () => Promise<void>;
    neteaseApiConfigPromise = Promise.resolve(generateNeteaseApiConfig()).catch(
      (error: unknown) => {
        neteaseApiConfigPromise = null;
        throw error;
      },
    );
  }

  await neteaseApiConfigPromise;
};

const createRouterMap = (neteaseApi: Record<string, unknown>) => {
  const routerMap = new Map<string, ApiFunction>();
  Object.keys(neteaseApi).forEach((key) => {
    const value = neteaseApi[key];
    if (typeof value !== "function") return;
    [key, toPathCase(key)].forEach((routePath) => {
      if (!routerMap.has(routePath)) routerMap.set(routePath, value as ApiFunction);
    });
  });
  return routerMap;
};

const sendJson = (
  request: IncomingMessage,
  response: ServerResponse,
  statusCode: number,
  payload: unknown,
) => {
  setCorsHeaders(request, response);
  response.statusCode = statusCode;
  response.setHeader("Content-Type", "application/json; charset=utf-8");
  response.end(JSON.stringify(payload));
};

const sendText = (
  request: IncomingMessage,
  response: ServerResponse,
  statusCode: number,
  payload: string,
) => {
  setCorsHeaders(request, response);
  response.statusCode = statusCode;
  response.setHeader("Content-Type", "text/plain; charset=utf-8");
  response.end(payload);
};

const STATIC_WEB_ROOT = path.join(currentDirPath, "web");

const STATIC_MIME: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".gif": "image/gif",
  ".webp": "image/webp",
  ".ico": "image/x-icon",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".ttf": "font/ttf",
  ".txt": "text/plain; charset=utf-8",
  ".map": "application/json; charset=utf-8",
  ".wasm": "application/wasm",
};

const statSafe = (p: string) => {
  try {
    return statSync(p);
  } catch {
    return null;
  }
};

/**
 * 提供 SPA 静态资源（仅打包 APK 内置 web/ 时生效）。
 * 命中实体文件直接回传；缺扩展名的路径视为前端路由回退 index.html。
 * @returns 是否已处理该请求
 */
const serveStaticAsset = (
  request: IncomingMessage,
  response: ServerResponse,
  pathname: string,
): boolean => {
  if (!existsSync(STATIC_WEB_ROOT)) return false;

  let relPath: string;
  try {
    relPath = decodeURIComponent(pathname).replace(/^\/+/, "");
  } catch {
    return false;
  }
  // 防止路径穿越：解析后必须仍位于 web 根目录内
  const candidate = path.resolve(STATIC_WEB_ROOT, relPath || "index.html");
  if (candidate !== STATIC_WEB_ROOT && !candidate.startsWith(STATIC_WEB_ROOT + path.sep)) {
    return false;
  }

  let target = candidate;
  let stat = statSafe(target);
  if (stat?.isDirectory()) {
    target = path.join(target, "index.html");
    stat = statSafe(target);
  }
  // 未命中实体文件：带扩展名的资源缺失交回兜底；否则按前端路由回退 index.html
  if (!stat) {
    if (path.extname(candidate)) return false;
    target = path.join(STATIC_WEB_ROOT, "index.html");
    stat = statSafe(target);
    if (!stat) return false;
  }

  setCorsHeaders(request, response);
  const ext = path.extname(target).toLowerCase();
  response.setHeader("Content-Type", STATIC_MIME[ext] || "application/octet-stream");
  response.setHeader(
    "Cache-Control",
    ext === ".html" ? "no-cache" : "public, max-age=31536000, immutable",
  );
  response.statusCode = 200;
  response.setHeader("Content-Length", String(stat.size));
  if (request.method === "HEAD") {
    response.end();
    return true;
  }
  createReadStream(target).pipe(response);
  return true;
};

/** 远程取字节上限：4MB（封面与小图够用，挡掉异常大资源 OOM） */
const REMOTE_FETCH_MAX_BYTES = 4 * 1024 * 1024;
/** 最大重定向跳数 */
const REMOTE_FETCH_MAX_REDIRECTS = 5;

/**
 * 安全地从远程 URL 抓取字节，供 fetchRemoteBytes 端点复用
 * 限制：协议白名单、内网地址拦截、大小上限、跟随 3xx 跳转、超时
 */
const fetchRemoteBytesSafely = (url: string, redirectsLeft = REMOTE_FETCH_MAX_REDIRECTS) =>
  new Promise<Buffer>((resolve, reject) => {
    let parsed: URL;
    try {
      parsed = new URL(url);
    } catch {
      reject(Object.assign(new Error("BAD_URL"), { code: "BAD_URL", status: 400 }));
      return;
    }
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
      reject(Object.assign(new Error("BAD_PROTOCOL"), { code: "BAD_PROTOCOL", status: 400 }));
      return;
    }
    const host = parsed.hostname.toLowerCase();
    if (isPrivateDevHost(host)) {
      reject(Object.assign(new Error("PRIVATE_HOST"), { code: "PRIVATE_HOST", status: 400 }));
      return;
    }
    const client = (parsed.protocol === "https:" ? https : http) as typeof http;
    const req = client.get(
      url,
      { timeout: 10_000, headers: { "User-Agent": "SPlayer-Next-Android" } },
      (upstream: IncomingMessage) => {
        const status = upstream.statusCode ?? 0;
        if (status >= 300 && status < 400 && upstream.headers.location) {
          upstream.resume();
          if (redirectsLeft <= 0) {
            reject(
              Object.assign(new Error("TOO_MANY_REDIRECTS"), {
                code: "TOO_MANY_REDIRECTS",
                status: 502,
              }),
            );
            return;
          }
          const nextUrl = new URL(upstream.headers.location, url).toString();
          fetchRemoteBytesSafely(nextUrl, redirectsLeft - 1).then(resolve, reject);
          return;
        }
        if (status !== 200) {
          upstream.resume();
          reject(
            Object.assign(new Error(`UPSTREAM_${status}`), {
              code: `UPSTREAM_${status}`,
              status: 502,
            }),
          );
          return;
        }
        const declared = Number(upstream.headers["content-length"] ?? 0);
        if (declared > REMOTE_FETCH_MAX_BYTES) {
          upstream.destroy();
          reject(Object.assign(new Error("TOO_LARGE"), { code: "TOO_LARGE", status: 413 }));
          return;
        }
        const chunks: Buffer[] = [];
        let received = 0;
        upstream.on("data", (chunk: Buffer) => {
          received += chunk.length;
          if (received > REMOTE_FETCH_MAX_BYTES) {
            upstream.destroy();
            reject(Object.assign(new Error("TOO_LARGE"), { code: "TOO_LARGE", status: 413 }));
            return;
          }
          chunks.push(chunk);
        });
        upstream.on("end", () => resolve(Buffer.concat(chunks)));
        upstream.on("error", () =>
          reject(
            Object.assign(new Error("UPSTREAM_ERROR"), { code: "UPSTREAM_ERROR", status: 502 }),
          ),
        );
      },
    );
    req.on("timeout", () => {
      req.destroy();
      reject(Object.assign(new Error("TIMEOUT"), { code: "TIMEOUT", status: 504 }));
    });
    req.on("error", () =>
      reject(Object.assign(new Error("REQUEST_ERROR"), { code: "REQUEST_ERROR", status: 502 })),
    );
  });

/** 请求体上限：8MB。Node 与 WebView 同机，无上限时恶意/异常 body 可耗尽内存 */
const REQUEST_BODY_MAX_BYTES = 8 * 1024 * 1024;

const readRequestBody = async (request: IncomingMessage) => {
  const chunks: Buffer[] = [];
  let total = 0;
  for await (const chunk of request) {
    const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    total += buf.length;
    if (total > REQUEST_BODY_MAX_BYTES) {
      request.destroy();
      throw Object.assign(new Error("REQUEST_BODY_TOO_LARGE"), { status: 413 });
    }
    chunks.push(buf);
  }
  return Buffer.concat(chunks).toString("utf8");
};

const parseBody = (rawBody: string, contentType: string | undefined) => {
  if (!rawBody) return {};

  const normalizedContentType = (contentType || "").toLowerCase();

  if (normalizedContentType.includes("application/json")) {
    try {
      return JSON.parse(rawBody) as Record<string, unknown>;
    } catch {
      return {};
    }
  }

  if (normalizedContentType.includes("application/x-www-form-urlencoded")) {
    return Object.fromEntries(new URLSearchParams(rawBody).entries());
  }

  try {
    return JSON.parse(rawBody) as Record<string, unknown>;
  } catch {
    return Object.fromEntries(new URLSearchParams(rawBody).entries());
  }
};

const asJsonObject = (value: unknown): JsonObject | null => {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  return value as JsonObject;
};

const isPlayEventInput = (value: unknown): value is PlayEventInput => {
  const body = asJsonObject(value);
  if (!body) return false;
  const track = asJsonObject(body.track);
  return (
    track !== null &&
    typeof body.startedAt === "number" &&
    Number.isFinite(body.startedAt) &&
    typeof body.listenedMs === "number" &&
    Number.isFinite(body.listenedMs)
  );
};

const isFavoriteEventInput = (value: unknown): value is FavoriteEventInput => {
  const body = asJsonObject(value);
  if (!body) return false;
  const track = asJsonObject(body.track);
  return track !== null && (body.action === "add" || body.action === "remove");
};

const cloneJson = <T>(value: T): T => {
  if (value === undefined) return value;
  return JSON.parse(JSON.stringify(value)) as T;
};

const createPlainObject = (): JsonObject => Object.create(null) as JsonObject;

const isUnsafePathKey = (key: string): boolean =>
  key === "__proto__" || key === "constructor" || key === "prototype";

const getPathKeys = (keyPath: unknown): string[] | null => {
  if (typeof keyPath !== "string" || !keyPath.trim()) return null;
  const keys = keyPath.split(".").filter(Boolean);
  if (keys.length === 0 || keys.some(isUnsafePathKey)) return null;
  return keys;
};

const deepMerge = <T>(defaults: T, stored: unknown): T => {
  if (Array.isArray(defaults)) {
    return cloneJson(Array.isArray(stored) ? stored : defaults) as T;
  }
  if (typeof defaults !== "object" || defaults === null) {
    return (stored === undefined || stored === null ? defaults : stored) as T;
  }
  if (typeof stored !== "object" || stored === null || Array.isArray(stored)) {
    return cloneJson(defaults);
  }

  const result = createPlainObject();
  const defaultRecord = defaults as JsonObject;
  const storedRecord = stored as JsonObject;
  for (const key of Object.keys(defaultRecord)) {
    if (isUnsafePathKey(key)) continue;
    result[key] = deepMerge(defaultRecord[key], storedRecord[key]);
  }
  for (const key of Object.keys(storedRecord)) {
    if (isUnsafePathKey(key) || key in result) continue;
    result[key] = cloneJson(storedRecord[key]);
  }
  return result as T;
};

const getByDotPath = (obj: unknown, keyPath: string): unknown => {
  const keys = getPathKeys(keyPath);
  if (!keys) return undefined;
  let cur = obj as JsonObject | null | undefined;
  for (const key of keys) {
    if (cur == null || typeof cur !== "object") return undefined;
    cur = cur[key] as JsonObject | null | undefined;
  }
  return cur;
};

const setByDotPath = (obj: unknown, keyPath: string, value: unknown): boolean => {
  const keys = getPathKeys(keyPath);
  if (!keys) return false;
  let cur = obj as JsonObject;
  for (let i = 0; i < keys.length - 1; i++) {
    const key = keys[i];
    if (cur[key] == null || typeof cur[key] !== "object" || Array.isArray(cur[key])) {
      cur[key] = createPlainObject();
    }
    cur = cur[key] as JsonObject;
  }
  cur[keys[keys.length - 1]] = value;
  return true;
};

/**
 * 原子写：先写临时文件再 rename。Android 文件系统部分实现 rename 覆盖会失败，
 * 这里先尝试覆盖式 rename，失败则 unlink 旧文件再 rename，最差也直接覆盖写本体，
 * 杜绝"设置丢失"由写盘失败导致下次冷启动读不到。
 */
const atomicWriteJson = (filePath: string, payload: unknown): void => {
  const dir = path.dirname(filePath);
  mkdirSync(dir, { recursive: true });
  const tempPath = `${filePath}.${process.pid}.${Date.now()}.tmp`;
  const serialized = `${JSON.stringify(payload, null, 2)}\n`;
  writeFileSync(tempPath, serialized, "utf8");
  try {
    renameSync(tempPath, filePath);
  } catch (firstError) {
    try {
      if (existsSync(filePath)) unlinkSync(filePath);
      renameSync(tempPath, filePath);
    } catch {
      // 最终兜底：直接覆盖写正式文件，确保数据落盘；写完清理 temp
      try {
        writeFileSync(filePath, serialized, "utf8");
      } finally {
        try {
          if (existsSync(tempPath)) unlinkSync(tempPath);
        } catch {}
      }
      console.warn("[embedded-api] 原子写失败，已降级为覆盖写:", filePath, firstError);
    }
  }
};

const writeConfigFile = (_config: SystemConfig): void => {
  sharedWriteSystemConfigStore();
};

interface MobilePlayHistoryItem {
  track: Track;
  startedAt: number;
  listenedMs: number;
}

interface MobileFavoriteHistoryItem {
  track: Track;
  action: "add" | "remove";
  at: number;
}

interface MobileStatsStore {
  playHistory: MobilePlayHistoryItem[];
  favoriteHistory: MobileFavoriteHistoryItem[];
}

const EMPTY_STATS_SUMMARY: PlayStatsSummary = {
  todayListenedMs: 0,
  weekListenedMs: 0,
  lastWeekListenedMs: 0,
  totalListenedMs: 0,
  weekPlayCount: 0,
  totalPlayCount: 0,
  weekFavoriteAdds: 0,
  streakDays: 0,
};

const readStatsFile = (): MobileStatsStore => {
  try {
    const raw = readFileSync(STATS_PATH, "utf8");
    const parsed = JSON.parse(raw) as Partial<MobileStatsStore>;
    return {
      playHistory: Array.isArray(parsed.playHistory) ? parsed.playHistory : [],
      favoriteHistory: Array.isArray(parsed.favoriteHistory) ? parsed.favoriteHistory : [],
    };
  } catch {
    return { playHistory: [], favoriteHistory: [] };
  }
};

let statsFlushTimer: ReturnType<typeof setTimeout> | null = null;
const writeStatsFile = (stats: MobileStatsStore): void => {
  if (statsFlushTimer) clearTimeout(statsFlushTimer);
  statsFlushTimer = setTimeout(() => {
    statsFlushTimer = null;
    atomicWriteJson(STATS_PATH, stats);
  }, 5000);
};

let statsStore: MobileStatsStore | null = null;

const getStatsStore = (): MobileStatsStore => {
  if (!statsStore) statsStore = readStatsFile();
  return statsStore;
};

const dayStartMs = (now: number): number => {
  const date = new Date(now);
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
};

const weekStartMs = (now: number): number => {
  const date = new Date(now);
  const daysFromMonday = (date.getDay() + 6) % 7;
  const monday = new Date(date.getFullYear(), date.getMonth(), date.getDate() - daysFromMonday);
  return monday.getTime();
};

const dayKey = (date: Date): string => {
  const pad = (value: number): string => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
};

const computeStreak = (descDays: string[]): number => {
  if (descDays.length === 0) return 0;
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  if (descDays[0] !== dayKey(today) && descDays[0] !== dayKey(yesterday)) return 0;
  const present = new Set(descDays);
  const cursor = new Date(today);
  if (descDays[0] !== dayKey(today)) cursor.setDate(cursor.getDate() - 1);
  let streak = 0;
  while (present.has(dayKey(cursor))) {
    streak += 1;
    cursor.setDate(cursor.getDate() - 1);
  }
  return streak;
};

const insertMobilePlayEvent = (event: PlayEventInput): void => {
  const stats = getStatsStore();
  stats.playHistory.unshift({
    track: event.track,
    startedAt: event.startedAt,
    listenedMs: event.listenedMs,
  });
  stats.playHistory = stats.playHistory.slice(0, MAX_PLAY_HISTORY);
  writeStatsFile(stats);
};

const insertMobileFavoriteEvent = (event: FavoriteEventInput): void => {
  const stats = getStatsStore();
  stats.favoriteHistory.unshift({ track: event.track, action: event.action, at: Date.now() });
  stats.favoriteHistory = stats.favoriteHistory.slice(0, MAX_FAVORITE_HISTORY);
  writeStatsFile(stats);
};

const getMobileStatsSummary = (): PlayStatsSummary => {
  try {
    const stats = getStatsStore();
    const now = Date.now();
    const dayStart = dayStartMs(now);
    const weekStart = weekStartMs(now);
    const lastWeekStart = weekStart - 7 * 24 * 60 * 60 * 1000;
    const dayRows = Array.from(
      new Set(
        stats.playHistory
          .map((item) => dayKey(new Date(item.startedAt)))
          .sort((a, b) => b.localeCompare(a)),
      ),
    );
    const listenedSince = (start: number, end = Number.POSITIVE_INFINITY): number =>
      stats.playHistory
        .filter((item) => item.startedAt >= start && item.startedAt < end)
        .reduce((total, item) => total + item.listenedMs, 0);

    return {
      todayListenedMs: listenedSince(dayStart),
      weekListenedMs: listenedSince(weekStart),
      lastWeekListenedMs: listenedSince(lastWeekStart, weekStart),
      totalListenedMs: listenedSince(0),
      weekPlayCount: stats.playHistory.filter((item) => item.startedAt >= weekStart).length,
      totalPlayCount: stats.playHistory.length,
      weekFavoriteAdds: stats.favoriteHistory.filter(
        (item) => item.action === "add" && item.at >= weekStart,
      ).length,
      streakDays: computeStreak(dayRows),
    };
  } catch {
    return EMPTY_STATS_SUMMARY;
  }
};

const getMobileTopTracks = (limit: number): TopTrack[] => {
  const trackMap = new Map<string, { track: Track; playCount: number; lastStartedAt: number }>();
  for (const item of getStatsStore().playHistory) {
    const key = `${item.track.source}:${item.track.id}`;
    const existing = trackMap.get(key);
    if (existing) {
      existing.playCount += 1;
      existing.lastStartedAt = Math.max(existing.lastStartedAt, item.startedAt);
    } else {
      trackMap.set(key, { track: item.track, playCount: 1, lastStartedAt: item.startedAt });
    }
  }
  return Array.from(trackMap.values())
    .sort((a, b) => b.playCount - a.playCount || b.lastStartedAt - a.lastStartedAt)
    .slice(0, limit)
    .map(({ track, playCount }) => ({ track, playCount }));
};

/** 取播放历史中出现过的不重复曲目（历史最新在前，保留最近一次的曲目信息） */
const getMobilePlayedTracks = (): Track[] => {
  const trackMap = new Map<string, Track>();
  for (const item of getStatsStore().playHistory) {
    const key = `${item.track.source}:${item.track.id}`;
    if (!trackMap.has(key)) trackMap.set(key, item.track);
  }
  return Array.from(trackMap.values());
};

/** 取最近 N 天（含今天）的每日播放统计，按日期升序 */
const getMobilePlayHistoryDaily = (days: number): DailyPlayStats[] => {
  const startMs = dayStartMs(Date.now()) - (days - 1) * 24 * 60 * 60 * 1000;
  const countByDay = new Map<string, number>();
  for (const item of getStatsStore().playHistory) {
    if (item.startedAt < startMs) continue;
    const key = dayKey(new Date(item.startedAt));
    countByDay.set(key, (countByDay.get(key) ?? 0) + 1);
  }
  return Array.from(countByDay.entries())
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([day, playCount]) => ({ day, playCount }));
};

/** 取本地时区各小时的累计播放统计，固定 24 项与桌面端对齐 */
const getMobilePlayHistoryHourly = (): HourlyPlayStats[] => {
  const countByHour = new Map<number, number>();
  for (const item of getStatsStore().playHistory) {
    const hour = new Date(item.startedAt).getHours();
    countByHour.set(hour, (countByHour.get(hour) ?? 0) + 1);
  }
  return Array.from({ length: 24 }, (_, hour) => ({
    hour,
    playCount: countByHour.get(hour) ?? 0,
  }));
};

/** 取最常播放的专辑，无 id 时按名称归组，代表曲目取组内最近播放的一首 */
const getMobileTopAlbums = (limit: number): TopAlbum[] => {
  const groups = new Map<string, { track: Track; playCount: number; lastStartedAt: number }>();
  for (const item of getStatsStore().playHistory) {
    const albumName = (item.track.album?.name ?? "").trim();
    if (!albumName) continue;
    const key = `${item.track.source}:${item.track.album?.id ?? albumName}`;
    const existing = groups.get(key);
    if (existing) {
      existing.playCount += 1;
      if (item.startedAt > existing.lastStartedAt) {
        existing.lastStartedAt = item.startedAt;
        existing.track = item.track;
      }
    } else {
      groups.set(key, { track: item.track, playCount: 1, lastStartedAt: item.startedAt });
    }
  }
  return Array.from(groups.values())
    .sort((a, b) => b.playCount - a.playCount || b.lastStartedAt - a.lastStartedAt)
    .slice(0, limit)
    .map(({ track, playCount }) => ({ track, playCount }));
};

/** 取最常播放的歌手，多歌手曲目各自计数，无 id 时按小写名称归组 */
const getMobileTopArtists = (limit: number): TopArtist[] => {
  const groups = new Map<
    string,
    { artist: Artist; track: Track; playCount: number; lastStartedAt: number }
  >();
  for (const item of getStatsStore().playHistory) {
    for (const artist of item.track.artists) {
      const name = artist.name.trim();
      if (!name) continue;
      const key = `${item.track.source}:${artist.id ?? name.toLowerCase()}`;
      const existing = groups.get(key);
      if (existing) {
        existing.playCount += 1;
        if (item.startedAt > existing.lastStartedAt) {
          existing.lastStartedAt = item.startedAt;
          existing.artist = artist;
          existing.track = item.track;
        }
      } else {
        groups.set(key, {
          artist,
          track: item.track,
          playCount: 1,
          lastStartedAt: item.startedAt,
        });
      }
    }
  }
  return Array.from(groups.values())
    .sort((a, b) => b.playCount - a.playCount || b.lastStartedAt - a.lastStartedAt)
    .slice(0, limit)
    .map(({ artist, track, playCount }) => ({ artist, track, playCount }));
};

const getSystemConfigStore = (): SystemConfig => {
  return sharedGetSystemConfigStore();
};

const replaceSystemConfigStore = (config: unknown): SystemConfig => {
  return sharedReplaceSystemConfigStore(config);
};

const resetSystemConfigStore = (keyPath?: unknown): boolean => {
  return sharedResetSystemConfigStore(keyPath);
};

const handleConfigRoute = (
  pathname: string,
  query: Record<string, unknown>,
  body: Record<string, unknown>,
  request: IncomingMessage,
  response: ServerResponse,
): boolean => {
  if (pathname === "/api/config/getAll") {
    sendJson(request, response, 200, getSystemConfigStore());
    return true;
  }

  if (pathname === "/api/config/get") {
    const keyPath = String(query.keyPath || body.keyPath || "");
    sendJson(
      request,
      response,
      200,
      keyPath ? (getByDotPath(getSystemConfigStore(), keyPath) ?? null) : null,
    );
    return true;
  }

  if (pathname === "/api/config/set") {
    const { keyPath, value } = body as { keyPath?: string; value?: unknown };
    if (!keyPath || !setByDotPath(getSystemConfigStore(), keyPath, value)) {
      sendJson(request, response, 400, { ok: false, error: "invalid keyPath" });
      return true;
    }
    writeConfigFile(getSystemConfigStore());
    sendJson(request, response, 200, { ok: true });
    return true;
  }

  if (pathname === "/api/config/reset") {
    const keyPath = body.keyPath ?? query.keyPath;
    if (!resetSystemConfigStore(keyPath)) {
      sendJson(request, response, 400, { ok: false, error: "invalid keyPath" });
      return true;
    }
    sendJson(request, response, 200, { ok: true });
    return true;
  }

  if (pathname === "/api/config/replaceAll") {
    replaceSystemConfigStore(
      Object.prototype.hasOwnProperty.call(body, "config") ? body.config : body,
    );
    sendJson(request, response, 200, { ok: true });
    return true;
  }

  if (pathname === "/api/config/importFromFile") {
    sendJson(request, response, 200, { ok: false, reason: "canceled" });
    return true;
  }

  if (pathname === "/api/config/exportToFile") {
    sendJson(request, response, 200, { ok: false, reason: "canceled" });
    return true;
  }

  return false;
};

const mergeCookieInput = (
  query: Record<string, unknown>,
  body: Record<string, unknown>,
  origin: string | undefined,
  cookieHeader: string | undefined,
  splayerCookieHeader: string | undefined,
) => {
  const { cookie: _queryCookie, ...safeQuery } = query;
  const { cookie: _bodyCookie, ...safeBody } = body;

  // 判断请求来源
  const isAllowed = isAllowedOrigin(origin);
  const isLanRequest =
    isAllowed &&
    origin !== "capacitor://localhost" &&
    !(origin?.includes("127.0.0.1") || origin?.includes("localhost") || origin?.includes("[::1]"));

  // 主机端请求或允许的 LAN 请求：优先用 SPlayer Cookie
  const customCookie = isAllowed ? splayerCookieHeader : undefined;
  // 共享用户信息开启时，授权 LAN 设备可使用主机登录态
  const shouldShareSession = lanShareState.enabled && lanShareState.shareUserInfo && isLanRequest;
  const cookie =
    customCookie ||
    cookieHeader ||
    (shouldShareSession || !isLanRequest ? neteaseSessionCookie : "");

  return {
    ...safeQuery,
    ...safeBody,
    ...(cookie ? { cookie } : {}),
  };
};

let routerMap: Map<string, ApiFunction> | null = null;
let neteaseSessionCookie = "";

/**
 * 登录会话独立文件，避免与 settings.json 混在一起。
 * 冷启动时从盘加载，让 server 进程能即时供给 cookie，
 * 不再依赖渲染层 setCookie 推送（消除 initPlayer / restoreQueue 早于 NavUser.fetchStatus 的 race）。
 */
const NETEASE_SESSION_PATH = path.join(CONFIG_DIR, "netease-session.json");

const loadPersistedNeteaseSession = (): void => {
  try {
    if (!existsSync(NETEASE_SESSION_PATH)) return;
    const raw = readFileSync(NETEASE_SESSION_PATH, "utf8");
    const parsed = JSON.parse(raw) as { cookie?: unknown };
    const cookie = typeof parsed?.cookie === "string" ? parsed.cookie : "";
    if (cookie.includes("MUSIC_U")) neteaseSessionCookie = cookie;
  } catch (err) {
    console.warn("[embedded-api] 读取持久化网易 cookie 失败:", err);
  }
};

const persistNeteaseSession = (cookie: string): void => {
  try {
    if (!cookie) {
      if (existsSync(NETEASE_SESSION_PATH)) unlinkSync(NETEASE_SESSION_PATH);
      return;
    }
    atomicWriteJson(NETEASE_SESSION_PATH, { cookie });
  } catch (err) {
    console.warn("[embedded-api] 持久化网易 cookie 失败:", err);
  }
};

const setNeteaseSessionCookie = (cookie: string): void => {
  if (neteaseSessionCookie === cookie) return;
  neteaseSessionCookie = cookie;
  persistNeteaseSession(cookie);
};

// 模块加载即尝试加载持久化的 cookie，确保任何 callNeteaseApi 在第一次执行前都能拿到
loadPersistedNeteaseSession();

const loadNeteaseApi = (requestPath: string): ApiFunction | null => {
  if (!routerMap) {
    routerMap = createRouterMap(nodeRequire(EMBEDDED_API_MAIN_ENTRY) as Record<string, unknown>);
  }
  const direct = routerMap.get(requestPath);
  if (direct) return direct;

  const aliasPath = NETEASE_API_ALIASES[requestPath];
  const aliased = aliasPath ? routerMap.get(aliasPath) : null;
  if (!aliased) return null;
  if (requestPath === "playmode_intelligence") {
    return (params) => aliased({ ...params, crypto: params.crypto ?? "weapi" });
  }
  return aliased;
};

const callNeteaseApi = async (
  requestPath: string,
  query: Record<string, unknown>,
  body: Record<string, unknown>,
  request: IncomingMessage,
) => {
  // ensureNeteaseApiConfig 必须在 loadNeteaseApi 之前执行：
  // request.js 在模块加载时同步读取 anonymous_token 文件，若文件不存在会直接抛异常
  await ensureNeteaseApiConfig();
  const neteaseApi = loadNeteaseApi(requestPath);
  if (!neteaseApi) {
    return { ok: false as const, error: `API not found: ${requestPath}` };
  }
  const result = await neteaseApi(
    mergeCookieInput(
      query,
      body,
      request.headers.origin,
      request.headers.cookie,
      getHeaderValue(request.headers["x-splayer-cookie"]),
    ),
  );
  let payload =
    typeof result === "object" && result && "body" in result
      ? (result as { body?: unknown }).body
      : result;
  // playlist_tracks.js 成功路径 return { status: 200, body: { ...res } } 多包了一层，
  // 导致 payload 是 { status, body: { code, ... }, cookie } 而非 { code, ... }。
  // 检测：payload 本身无 code 但有 body.code → 解包一层。
  if (
    typeof payload === "object" &&
    payload &&
    !("code" in payload) &&
    "body" in payload &&
    typeof (payload as { body?: unknown }).body === "object" &&
    (payload as { body?: unknown }).body !== null &&
    "code" in (payload as { body?: Record<string, unknown> }).body!
  ) {
    payload = (payload as { body: unknown }).body;
  }
  if (typeof payload === "object" && payload && "cookie" in payload) {
    const cookie = (payload as { cookie?: unknown }).cookie;
    if (typeof cookie === "string" && cookie.includes("MUSIC_U")) {
      setNeteaseSessionCookie(cookie);
    }
  }
  const status =
    typeof result === "object" && result && "status" in result
      ? (result as { status?: number }).status
      : 200;

  return { ok: true as const, status: status ?? 200, body: payload };
};

/** TTML / 文本资源抓取超时（ms）；与桌面端 ttml.ts 对齐 */
const FETCH_TEXT_TIMEOUT_MS = 8000;
/** 最大重定向跳数；AMLL DB CDN 常发 301/302 */
const FETCH_TEXT_MAX_REDIRECTS = 5;

/**
 * 通用文本抓取。
 * - 跟随 3xx 重定向（AMLL DB 等 CDN 强依赖）
 * - 设 8s 超时，避免请求挂住把 Promise.all 拖死导致 TTML 失踪
 * - 带 User-Agent，部分 mirror 对空 UA 直接 403
 * 返回 null 表示失败/未命中；调用方可以静默回退。
 */
const fetchText = (url: string, redirectsLeft = FETCH_TEXT_MAX_REDIRECTS) =>
  new Promise<string | null>((resolve) => {
    let parsed: URL;
    try {
      parsed = new URL(url);
    } catch {
      resolve(null);
      return;
    }
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
      resolve(null);
      return;
    }
    // 每一跳都拦截私网地址：该响应体会回传给调用方，防止经重定向打内网服务（SSRF）
    if (isPrivateDevHost(parsed.hostname)) {
      resolve(null);
      return;
    }
    const client = (parsed.protocol === "https:" ? https : http) as typeof http;
    const req = client.get(
      url,
      {
        timeout: FETCH_TEXT_TIMEOUT_MS,
        headers: { "User-Agent": "SPlayer-Next-Android" },
      },
      (response: IncomingMessage) => {
        const status = response.statusCode ?? 0;
        if (status >= 300 && status < 400 && response.headers.location) {
          response.resume();
          if (redirectsLeft <= 0) {
            resolve(null);
            return;
          }
          const nextUrl = new URL(response.headers.location, url).toString();
          fetchText(nextUrl, redirectsLeft - 1).then(resolve);
          return;
        }
        if (status !== 200) {
          response.resume();
          resolve(null);
          return;
        }
        const chunks: Buffer[] = [];
        let total = 0;
        response.on("data", (chunk: string | Buffer) => {
          const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
          total += buf.length;
          if (total > REMOTE_FETCH_MAX_BYTES) {
            req.destroy();
            chunks.length = 0;
            resolve(null);
            return;
          }
          chunks.push(buf);
        });
        response.on("end", () => {
          const text = Buffer.concat(chunks).toString("utf8");
          // 服务器偶尔 200 但 body 空，按未命中处理（与桌面端 ttml.ts 一致）
          resolve(text.trim() ? text : null);
        });
        response.on("error", () => resolve(null));
      },
    );
    req.on("timeout", () => {
      req.destroy();
      resolve(null);
    });
    req.on("error", () => resolve(null));
  });

const REQUEST_JSON_TIMEOUT_MS = 8000;
const REQUEST_JSON_MAX_BYTES = 2 * 1024 * 1024;

const decodeResponseBuffer = (buffer: Buffer, encoding?: string): Buffer => {
  const normalized = (encoding || "").toLowerCase();
  if (normalized.includes("gzip")) return gunzipSync(buffer);
  if (normalized.includes("deflate")) return inflateSync(buffer);
  return buffer;
};

const requestJson = <T>(
  url: string,
  options: {
    method?: "GET" | "POST";
    headers?: Record<string, string>;
    body?: unknown;
    redirectsLeft?: number;
  } = {},
) =>
  new Promise<T>((resolve, reject) => {
    let parsed: URL;
    try {
      parsed = new URL(url);
    } catch (error) {
      reject(error);
      return;
    }
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
      reject(new Error("unsupported protocol"));
      return;
    }

    const method = options.method ?? "GET";
    const bodyText = options.body === undefined ? undefined : JSON.stringify(options.body);
    const headers: Record<string, string> = {
      "User-Agent": "SPlayer-Next-Android",
      ...(bodyText
        ? {
            "Content-Type": "application/json; charset=utf-8",
            "Content-Length": String(Buffer.byteLength(bodyText)),
          }
        : {}),
      ...(options.headers ?? {}),
    };
    const client = (parsed.protocol === "https:" ? https : http) as typeof http;
    const req = client.request(
      parsed,
      { method, timeout: REQUEST_JSON_TIMEOUT_MS, headers },
      (response: IncomingMessage) => {
        const status = response.statusCode ?? 0;
        if (status >= 300 && status < 400 && response.headers.location) {
          response.resume();
          const redirectsLeft = options.redirectsLeft ?? FETCH_TEXT_MAX_REDIRECTS;
          if (redirectsLeft <= 0) {
            reject(new Error("too many redirects"));
            return;
          }
          const nextUrl = new URL(response.headers.location, url).toString();
          requestJson<T>(nextUrl, { ...options, redirectsLeft: redirectsLeft - 1 }).then(
            resolve,
            reject,
          );
          return;
        }
        if (status !== 200) {
          response.resume();
          reject(new Error(`HTTP ${status}`));
          return;
        }
        const chunks: Buffer[] = [];
        let received = 0;
        response.on("data", (chunk: Buffer) => {
          received += chunk.length;
          if (received > REQUEST_JSON_MAX_BYTES) {
            response.destroy();
            reject(new Error("response too large"));
            return;
          }
          chunks.push(chunk);
        });
        response.on("end", () => {
          try {
            const buffer = decodeResponseBuffer(
              Buffer.concat(chunks),
              getHeaderValue(response.headers["content-encoding"]),
            );
            resolve(JSON.parse(buffer.toString("utf8")) as T);
          } catch (error) {
            reject(error);
          }
        });
        response.on("error", reject);
      },
    );
    req.on("timeout", () => {
      req.destroy();
      reject(new Error("request timeout"));
    });
    req.on("error", reject);
    if (bodyText) req.write(bodyText);
    req.end();
  });

const normalizeText = (text: unknown): string => {
  if (typeof text !== "string") return "";
  return text.toLowerCase().replace(/[、&;，,/|()·・\s\-_'"`~!?？！.。]+/g, "");
};

const bothContains = (left: string, right: string): boolean =>
  left.length > 0 && right.length > 0 && (left.includes(right) || right.includes(left));

const durationClose = (leftMs?: number, rightMs?: number, tolMs = 5000): boolean => {
  if (!leftMs || !rightMs) return false;
  return Math.abs(leftMs - rightMs) <= tolMs;
};

const durationFar = (leftMs?: number, rightMs?: number, tolMs = 20000): boolean => {
  if (!leftMs || !rightMs) return false;
  return Math.abs(leftMs - rightMs) > tolMs;
};

const NAME_CONTAIN_MIN_RATIO = 0.34;
const DURATION_BUCKET_MS = 5000;
const LYRIC_VERSION_TOKEN_RULES: Array<{ key: string; pattern: RegExp }> = [
  { key: "live", pattern: /\blive\b|现场|演唱会/u },
  { key: "remix", pattern: /\bremix\b|混音/u },
  { key: "acoustic", pattern: /\bacoustic\b|不插电/u },
  { key: "instrumental", pattern: /\binst\b|\binstrumental\b|伴奏|纯音乐/u },
  { key: "karaoke", pattern: /\bkaraoke\b|卡拉ok|卡拉ok版/u },
  { key: "cover", pattern: /\bcover\b|翻唱/u },
  { key: "tvsize", pattern: /\btv\s*size\b|tv版|动漫版|动画版/u },
  { key: "dj", pattern: /\bdj\b/u },
  { key: "demo", pattern: /\bdemo\b/u },
];

/** KotlinApiServer 基础地址，用于访问 SQLite 持久化缓存 */
const KOTLIN_API_BASE = "http://127.0.0.1:13962";

/** db 缓存 HTTP 调用超时（ms）；KotlinApiServer 本机调用应 < 100ms，给 2s 余量 */
const KOTLIN_CACHE_TIMEOUT_MS = 2000;

/**
 * 调用 KotlinApiServer 的 /api/cache/db/* 路由访问 SQLite 持久化缓存。
 * 失败时静默返回 null，调用方回退到未命中逻辑（不影响主流程）。
 */
const callKotlinCacheDb = <T>(path: string, body?: unknown): Promise<T | null> => {
  return new Promise((resolve) => {
    const url = new URL(path, KOTLIN_API_BASE);
    const isPost = body !== undefined;
    const bodyText = isPost ? JSON.stringify(body) : undefined;
    const headers: Record<string, string> = {
      "User-Agent": "SPlayer-Next-Android",
    };
    if (bodyText) {
      headers["Content-Type"] = "application/json; charset=utf-8";
      headers["Content-Length"] = String(Buffer.byteLength(bodyText));
    }
    const req = http.request(
      url,
      { method: isPost ? "POST" : "GET", timeout: KOTLIN_CACHE_TIMEOUT_MS, headers },
      (response: IncomingMessage) => {
        const status = response.statusCode ?? 0;
        if (status !== 200) {
          response.resume();
          resolve(null);
          return;
        }
        const chunks: Buffer[] = [];
        response.on("data", (chunk: Buffer) => chunks.push(chunk));
        response.on("end", () => {
          try {
            resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")) as T);
          } catch {
            resolve(null);
          }
        });
        response.on("error", () => resolve(null));
      },
    );
    req.on("timeout", () => {
      req.destroy();
      resolve(null);
    });
    req.on("error", () => resolve(null));
    if (bodyText) req.write(bodyText);
    req.end();
  });
};

const getStringValue = (...values: unknown[]): string => {
  for (const value of values) {
    if (typeof value === "string" && value.trim()) return value;
    if (typeof value === "number" && Number.isFinite(value)) return String(value);
  }
  return "";
};

const getNumberValue = (...values: unknown[]): number | undefined => {
  for (const value of values) {
    if (typeof value === "number" && Number.isFinite(value)) return value;
    if (typeof value === "string") {
      const parsed = Number(value);
      if (Number.isFinite(parsed)) return parsed;
    }
  }
  return undefined;
};

const getTrackTitle = (track: JsonObject): string => getStringValue(track.title, track.name);

const getTrackArtist = (track: JsonObject): string => {
  const artists = track.artists;
  if (Array.isArray(artists)) {
    const first = artists[0] as JsonObject | undefined;
    const name = first && typeof first === "object" ? first.name : first;
    const value = getStringValue(name);
    if (value) return value;
  }
  return getStringValue(track.artist);
};

const getTrackAlbum = (track: JsonObject): string => {
  const album = track.album;
  if (album && typeof album === "object" && !Array.isArray(album)) {
    return getStringValue((album as JsonObject).name);
  }
  return getStringValue(album);
};

const getTrackDuration = (track: JsonObject): number | undefined =>
  getNumberValue(track.duration, track.durationMs);

const buildLyricSearchKeyword = (track: JsonObject): string => {
  const title = getTrackTitle(track).trim();
  const artist = getTrackArtist(track).trim();
  const album = getTrackAlbum(track).trim();
  const parts = [title, artist];
  if (album) {
    const normalizedAlbum = normalizeText(album);
    const duplicate = parts.some((part) => normalizeText(part) === normalizedAlbum);
    if (!duplicate) parts.push(album);
  }
  return parts.filter(Boolean).join(" ").trim();
};

const buildTrackFingerprint = (track: JsonObject): string => {
  const title = normalizeText(getTrackTitle(track));
  const artist = normalizeText(getTrackArtist(track));
  const album = normalizeText(getTrackAlbum(track));
  const duration = getTrackDuration(track);
  const bucket = duration ? Math.round(duration / DURATION_BUCKET_MS) : 0;
  return `${title}|${artist}|${album}|${bucket}`;
};

interface L1CacheEntry<T> {
  value: T;
  expireAt: number;
}

const l1Cache = new Map<string, L1CacheEntry<unknown>>();

/** L1 硬上限（条）：歌词/TTML 单条可达数百 KB，无硬上限会在长会话中持续增长 */
const L1_CACHE_MAX_ENTRIES = 150;

const getL1Cache = <T>(key: string): T | undefined => {
  const entry = l1Cache.get(key);
  if (!entry) return undefined;
  if (Date.now() > entry.expireAt) {
    l1Cache.delete(key);
    return undefined;
  }
  // 命中重插刷新 LRU 新鲜度（Map 迭代按插入序）
  l1Cache.delete(key);
  l1Cache.set(key, entry);
  return entry.value as T;
};

const setL1Cache = <T>(key: string, value: T, ttlMs = 5 * 60 * 1000): void => {
  l1Cache.delete(key);
  l1Cache.set(key, { value, expireAt: Date.now() + ttlMs });
  if (l1Cache.size <= L1_CACHE_MAX_ENTRIES) return;
  // 超限时按最旧顺序强摘（Map 迭代按插入序，命中刷新已保证新项在尾部）
  while (l1Cache.size > L1_CACHE_MAX_ENTRIES) {
    const oldest = l1Cache.keys().next().value;
    if (oldest === undefined) break;
    l1Cache.delete(oldest);
  }
};

const getMatchedLyricId = async (
  track: JsonObject,
  platform: string,
): Promise<{ platformId: string; extra?: string } | undefined> => {
  const fingerprint = buildTrackFingerprint(track);
  const cacheKey = `lyricMatch:${platform}:${fingerprint}`;
  const l1 = getL1Cache<{ platformId: string; extra?: string } | null>(cacheKey);
  if (l1 !== undefined) return l1 === null ? undefined : l1;

  const res = await callKotlinCacheDb<{
    hit: boolean;
    platformId?: string;
    extra?: string | null;
  }>(
    `/api/cache/db/lyricMatch/get?fingerprint=${encodeURIComponent(fingerprint)}&platform=${encodeURIComponent(platform)}`,
  );
  if (res?.hit && res.platformId) {
    const result = { platformId: res.platformId, extra: res.extra ?? undefined };
    setL1Cache(cacheKey, result);
    return result;
  }
  setL1Cache(cacheKey, null, 30000); // 30s negative cache
  return undefined;
};

const getCachedLyric = async (
  platform: string,
  platformId: string,
): Promise<MobileLyricResult | null> => {
  const cacheKey = `lyric:${platform}:${platformId}`;
  const l1 = getL1Cache<MobileLyricResult | null>(cacheKey);
  if (l1 !== undefined) return l1;

  const res = await callKotlinCacheDb<{ hit: boolean; data?: string }>(
    `/api/cache/db/lyric/get?platform=${encodeURIComponent(platform)}&platformId=${encodeURIComponent(platformId)}`,
  );
  if (!res?.hit || !res.data) {
    setL1Cache(cacheKey, null, 30000); // 30s negative cache
    return null;
  }
  try {
    const parsed = JSON.parse(res.data) as MobileLyricResult;
    setL1Cache(cacheKey, parsed);
    return parsed;
  } catch {
    return null;
  }
};

const setCachedLyric = async (
  platform: string,
  platformId: string,
  data: MobileLyricResult,
): Promise<void> => {
  const cacheKey = `lyric:${platform}:${platformId}`;
  setL1Cache(cacheKey, data);
  await callKotlinCacheDb("/api/cache/db/lyric/set", {
    platform,
    platformId,
    data: JSON.stringify(data),
  });
};

const setMatchedLyricId = async (
  track: JsonObject,
  platform: string,
  platformId: string,
  extra?: Record<string, unknown>,
): Promise<void> => {
  const fingerprint = buildTrackFingerprint(track);
  const cacheKey = `lyricMatch:${platform}:${fingerprint}`;
  setL1Cache(cacheKey, { platformId, extra: extra ? JSON.stringify(extra) : undefined });
  await callKotlinCacheDb("/api/cache/db/lyricMatch/set", {
    fingerprint,
    platform,
    platformId,
    extra: extra ? JSON.stringify(extra) : null,
  });
};

/**
 * 暂存本次匹配到的平台 ID，仅写内存 L1（5 分钟），不进 SQLite 持久化。
 * 模糊命中但未达持久化门槛时，同次播放的 TTML 覆盖查询仍能通过 getMatchedLyricId 拿到 ID；
 * 严格的持久化门槛保持不动，避免把不精确匹配长期写库。
 */
const stashMatchedLyricId = (
  track: JsonObject,
  platform: string,
  platformId: string,
  extra?: Record<string, unknown>,
): void => {
  const fingerprint = buildTrackFingerprint(track);
  const cacheKey = `lyricMatch:${platform}:${fingerprint}`;
  setL1Cache(cacheKey, { platformId, extra: extra ? JSON.stringify(extra) : undefined });
};

const collectLyricVersionTokens = (text: unknown): string[] => {
  if (typeof text !== "string" || !text.trim()) return [];
  const lowered = text.toLowerCase();
  return LYRIC_VERSION_TOKEN_RULES.filter((rule) => rule.pattern.test(lowered)).map(
    (rule) => rule.key,
  );
};

const analyzeLyricCandidate = <Extra>(
  candidate: LyricCandidate<Extra>,
  track: JsonObject,
): LyricCandidateMatch<Extra> | null => {
  const trackName = normalizeText(getTrackTitle(track));
  const trackArtist = normalizeText(getTrackArtist(track));
  const trackAlbum = normalizeText(getTrackAlbum(track));
  const trackDuration = getTrackDuration(track);
  const candName = normalizeText(candidate.name);
  const candArtist = normalizeText(candidate.artist);
  const candAlbum = normalizeText(candidate.album);

  const nameExact = candName.length > 0 && candName === trackName;
  if (!nameExact) {
    if (!bothContains(candName, trackName)) return null;
    const longer = Math.max(candName.length, trackName.length);
    const shorter = Math.min(candName.length, trackName.length);
    if (longer === 0 || shorter / longer < NAME_CONTAIN_MIN_RATIO) return null;
  }

  if (durationFar(candidate.duration, trackDuration)) return null;

  const artistExact = trackArtist.length > 0 && candArtist === trackArtist;
  const artistContains = !artistExact && bothContains(candArtist, trackArtist);
  if (trackArtist.length > 0 && !artistExact && !artistContains) return null;
  if (!nameExact && trackArtist.length === 0) return null;

  const trackVersionTokens = new Set([
    ...collectLyricVersionTokens(getTrackTitle(track)),
    ...collectLyricVersionTokens(getTrackAlbum(track)),
  ]);
  const candidateVersionTokens = new Set([
    ...collectLyricVersionTokens(candidate.name),
    ...collectLyricVersionTokens(candidate.album),
  ]);
  const sharedVersionToken = [...trackVersionTokens].some((token) =>
    candidateVersionTokens.has(token),
  );
  const versionConflict =
    trackVersionTokens.size > 0 && candidateVersionTokens.size > 0 && !sharedVersionToken;
  if (versionConflict) return null;

  const albumExact = trackAlbum.length > 0 && candAlbum === trackAlbum;
  const isDurationClose = durationClose(candidate.duration, trackDuration);

  let score = nameExact ? 10 : 4;
  if (artistExact) score += 6;
  else if (artistContains) score += 3;
  if (albumExact) score += 3;
  if (isDurationClose) score += 3;
  if (sharedVersionToken) score += 2;
  else if (candidateVersionTokens.size > 0 && trackVersionTokens.size === 0) score -= 3;

  return {
    candidate,
    score,
    nameExact,
    artistExact,
    artistContains,
    albumExact,
    durationClose: isDurationClose,
    versionConflict,
  };
};

const pickBestCandidate = <Extra>(
  candidates: LyricCandidate<Extra>[],
  track: JsonObject,
): LyricCandidateMatch<Extra> | null => {
  let best: LyricCandidateMatch<Extra> | null = null;

  for (const candidate of candidates) {
    const analyzed = analyzeLyricCandidate(candidate, track);
    if (!analyzed) continue;
    if (!best || analyzed.score > best.score) best = analyzed;
  }

  return best;
};

const shouldPersistLyricMatch = <Extra>(
  match: LyricCandidateMatch<Extra>,
  track: JsonObject,
): boolean => {
  if (match.versionConflict) return false;
  if (!match.nameExact) return false;
  const hasArtist = normalizeText(getTrackArtist(track)).length > 0;
  if (hasArtist) {
    if (match.artistExact) return true;
    return match.artistContains && (match.durationClose || match.albumExact);
  }
  return match.durationClose || match.albumExact;
};

const pickNeteaseMain = (
  yrc?: string,
  lrc?: string,
): { content: string; format: "yrc" | "lrc" } | undefined => {
  const yrcContent = yrc?.trim();
  if (yrcContent) return { content: yrcContent, format: "yrc" };
  const lrcContent = lrc?.trim();
  if (lrcContent) return { content: lrcContent, format: "lrc" };
  return undefined;
};

const pickNeteaseSub = (
  yPaired?: string,
  plain?: string,
): { content: string; format: "lrc" } | undefined => {
  const preferred = yPaired?.trim();
  if (preferred) return { content: preferred, format: "lrc" };
  const fallback = plain?.trim();
  if (fallback) return { content: fallback, format: "lrc" };
  return undefined;
};

const buildNeteaseLyricResult = (body: NeteaseLyricBody) => {
  const main = pickNeteaseMain(body.yrc?.lyric, body.lrc?.lyric);
  if (!main) return null;
  const trans = pickNeteaseSub(body.ytlrc?.lyric, body.tlyric?.lyric);
  const roma = pickNeteaseSub(body.yromalrc?.lyric, body.romalrc?.lyric);
  return {
    platform: "netease" as const,
    format: main.format,
    content: main.content,
    translation: trans?.content,
    translationFormat: trans?.format,
    romaji: roma?.content,
    romajiFormat: roma?.format,
  };
};

const fetchNeteaseLyricById = async (id: string | number, request: IncomingMessage) => {
  const cached = await getCachedLyric("netease", String(id));
  if (cached) return cached;
  const lyricResult = await callNeteaseApi("lyric_new", { id: String(id) }, {}, request);
  if (!lyricResult.ok || lyricResult.status !== 200) return null;
  if (!lyricResult.body || typeof lyricResult.body !== "object") return null;
  const body = lyricResult.body as NeteaseLyricBody;
  if (typeof body.code === "number" && body.code !== 200) return null;
  const result = buildNeteaseLyricResult(body);
  if (result) await setCachedLyric("netease", String(id), result);
  return result;
};

const toNeteaseCandidate = (song: NeteaseSong): LyricCandidate<{ id: string }> | null => {
  if (song.id == null) return null;
  const artists = song.artists ?? song.ar ?? [];
  return {
    name: getStringValue(song.name),
    artist: artists
      .map((artist) => getStringValue(artist.name))
      .filter(Boolean)
      .join(" / "),
    album: getStringValue(song.album?.name, song.al?.name),
    duration: getNumberValue(song.duration, song.dt),
    extra: { id: String(song.id) },
  };
};

const searchNeteaseLyricByTrack = async (track: JsonObject, request: IncomingMessage) => {
  const cachedId = await getMatchedLyricId(track, "netease");
  if (cachedId) return fetchNeteaseLyricById(cachedId.platformId, request);

  const keyword = buildLyricSearchKeyword(track);
  if (!keyword) return null;

  const searchResult = await callNeteaseApi(
    "search",
    { keywords: keyword, limit: 20, type: 1 },
    {},
    request,
  );
  if (!searchResult.ok || searchResult.status !== 200) return null;
  if (!searchResult.body || typeof searchResult.body !== "object") return null;

  const searchBody = searchResult.body as { result?: { songs?: NeteaseSong[] } };
  const songs = Array.isArray(searchBody.result?.songs) ? searchBody.result.songs : [];
  const candidates = songs
    .map(toNeteaseCandidate)
    .filter((candidate): candidate is LyricCandidate<{ id: string }> => candidate !== null);
  const best = pickBestCandidate(candidates, track);
  console.info(
    `[embedded-api] netease fuzzy "${keyword}" -> ${candidates.length} hits, best=${best?.candidate.name ?? "none"}`,
  );
  if (!best) return null;

  if (shouldPersistLyricMatch(best, track)) {
    await setMatchedLyricId(track, "netease", best.candidate.extra.id);
  } else {
    stashMatchedLyricId(track, "netease", best.candidate.extra.id);
  }
  return fetchNeteaseLyricById(best.candidate.extra.id, request);
};

const decodeVendorName = (text: string | undefined): string => {
  if (!text) return "";
  return text.replace(
    /&nbsp;|&amp;|&lt;|&gt;|&quot;|&apos;|&#039;/g,
    (entity) =>
      ({
        "&nbsp;": " ",
        "&amp;": "&",
        "&lt;": "<",
        "&gt;": ">",
        "&quot;": '"',
        "&apos;": "'",
        "&#039;": "'",
      })[entity] ?? entity,
  );
};

const formatSingerName = (singers: Array<{ name?: string }> | undefined): string =>
  singers
    ?.map((singer) => decodeVendorName(singer.name))
    .filter(Boolean)
    .join(" / ") ?? "";

interface ProviderASession {
  uid?: string;
  sid?: string;
  userip?: string;
  expireAt: number;
}

interface ProviderAResponse {
  code?: number;
  request?: { code?: number; data?: unknown };
}

let providerASession: ProviderASession = { expireAt: 0 };
let providerASessionPromise: Promise<void> | null = null;

const getProviderACommonParams = (): Record<string, string | number> => ({
  ct: 11,
  cv: "1003006",
  v: "1003006",
  os_ver: "15",
  phonetype: "24122RKC7C",
  tmeAppID: decodeAscii([113, 113, 109, 117, 115, 105, 99, 108, 105, 103, 104, 116]),
  nettype: "NETWORK_WIFI",
  udid: "0",
  OpenUDID: "0",
  QIMEI36: "0",
  uin: "0",
});

const postProviderARaw = (body: unknown): Promise<ProviderAResponse> =>
  requestJson<ProviderAResponse>(PROVIDER_A_API_URL, {
    method: "POST",
    headers: PROVIDER_A_HEADERS,
    body,
  });

const ensureProviderASession = (): Promise<void> => {
  if (providerASession.uid && providerASession.expireAt > Date.now()) return Promise.resolve();
  if (providerASessionPromise) return providerASessionPromise;

  providerASessionPromise = (async () => {
    try {
      const data = await postProviderARaw({
        comm: getProviderACommonParams(),
        request: {
          module: "music.getSession.session",
          method: "GetSession",
          param: { caller: 0, uid: "0", vkey: 0 },
        },
      });
      if (data.code === 0 && data.request?.code === 0) {
        const info =
          ((data.request.data as { session?: Partial<ProviderASession> }) ?? {}).session ?? {};
        providerASession = {
          uid: info.uid,
          sid: info.sid,
          userip: info.userip,
          expireAt: Date.now() + PROVIDER_A_SESSION_TTL_MS,
        };
      }
    } catch {
      // session 失败不阻塞后续调用，大部分接口无 session 也能回结果
    } finally {
      providerASessionPromise = null;
    }
  })();

  return providerASessionPromise;
};

const callProviderAApi = async <T>(
  moduleName: string,
  method: string,
  param: Record<string, unknown>,
): Promise<T> => {
  await ensureProviderASession();
  const comm = {
    ...getProviderACommonParams(),
    ...(providerASession.uid ? { uid: providerASession.uid } : {}),
    ...(providerASession.sid ? { sid: providerASession.sid } : {}),
    ...(providerASession.userip ? { userip: providerASession.userip } : {}),
  };
  const data = await postProviderARaw({
    comm,
    request: { module: moduleName, method, param },
  });
  const outerCode = data.code ?? 0;
  const innerCode = data.request?.code ?? 0;
  if (outerCode !== 0 || innerCode !== 0) {
    throw new Error(`provider-a api error: outer=${outerCode} inner=${innerCode}`);
  }
  return data.request?.data as T;
};

const generateProviderASearchId = (): string =>
  String(
    BigInt(Math.floor(Math.random() * 20)) * BigInt("18014398509481984") +
      BigInt(Math.floor(Math.random() * 4194304) * 4294967296) +
      BigInt(Date.now() % 86400000),
  );

const toProviderAArtist = (singers: Array<{ name?: string }> | undefined): string =>
  singers
    ?.map((singer) => getStringValue(singer.name))
    .filter(Boolean)
    .join(" / ") ?? "";

const toProviderACandidate = (
  song: ProviderASong,
): LyricCandidate<{ id: string; mid: string }> | null => {
  const id = getStringValue(song.id);
  if (!id) return null;
  const album = typeof song.album === "object" && song.album ? song.album : null;
  const duration = getNumberValue(song.duration, song.interval);
  return {
    name: getStringValue(song.name, song.title),
    artist: getStringValue(song.artist, toProviderAArtist(song.singer)),
    album: getStringValue(typeof song.album === "string" ? song.album : album?.name),
    duration: duration && duration < 1000 ? duration * 1000 : duration,
    extra: { id, mid: getStringValue(song.mid) },
  };
};

const tryDecryptProviderALyric = (hex: string | undefined): string | undefined => {
  if (!hex) return undefined;
  try {
    return decryptQrc(hex);
  } catch {
    return undefined;
  }
};

const pickProviderAFormatted = (
  qrc?: string,
  lrc?: string,
): { content: string; format: "qrc" | "lrc" } | undefined => {
  const qrcContent = qrc?.trim();
  if (qrcContent) return { content: qrcContent, format: "qrc" };
  const lrcContent = lrc?.trim();
  if (lrcContent) return { content: lrcContent, format: "lrc" };
  return undefined;
};

const fetchProviderALyricById = async (id: string, mid?: string) => {
  const cached = await getCachedLyric(PLATFORM_A_ID, id);
  if (cached) return cached;
  try {
    const b64 = (text: unknown): string =>
      Buffer.from(String(text ?? ""), "utf8").toString("base64");
    const baseParam = {
      albumName: b64(""),
      crypt: 1,
      ct: 19,
      cv: 2111,
      interval: 0,
      lrc_t: 0,
      qrc: 1,
      qrc_t: 0,
      roma: 1,
      roma_t: 0,
      singerName: b64(""),
      songID: Number(id),
      songName: b64(""),
      trans: 1,
      trans_t: 0,
      type: 0,
    };
    const resp = await callProviderAApi<{
      lyric?: string;
      qrc_t?: number;
      trans?: string;
      roma?: string;
    }>("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo", baseParam);
    const mainDecrypted = tryDecryptProviderALyric(resp.lyric);
    let qrc: string | undefined;
    let lrc: string | undefined;
    if (mainDecrypted) {
      if (resp.qrc_t === 0) lrc = mainDecrypted;
      else qrc = mainDecrypted;
    }
    if (qrc && !lrc) {
      try {
        const lrcResp = await callProviderAApi<{ lyric?: string }>(
          "music.musichallSong.PlayLyricInfo",
          "GetPlayLyricInfo",
          { ...baseParam, qrc: 0, qrc_t: 0 },
        );
        lrc = tryDecryptProviderALyric(lrcResp.lyric);
      } catch {
        // 主歌词已命中，行级 LRC 失败不影响展示
      }
    }
    const main = pickProviderAFormatted(qrc, lrc);
    if (!main) return null;
    const trans = tryDecryptProviderALyric(resp.trans)?.trim();
    const roma = tryDecryptProviderALyric(resp.roma)?.trim();
    const result: MobileLyricResult = {
      platform: PLATFORM_A_ID,
      format: main.format,
      content: main.content,
      translation: trans || undefined,
      translationFormat: trans ? ("lrc" as const) : undefined,
      romaji: roma || undefined,
      romajiFormat: roma ? main.format : undefined,
      extra: mid ? { mid } : undefined,
    };
    await setCachedLyric(PLATFORM_A_ID, id, result);
    return result;
  } catch (err) {
    console.warn(`[embedded-api] provider-a lyric fetch failed for id=${id}:`, err);
    return null;
  }
};

/**
 * provider-a 长关键词（含全角括号备注/原声带后缀）易被上游拒（inner=2001），去备注并截断后重试用
 */
const simplifyProviderAKeyword = (keyword: string): string =>
  keyword
    .replace(/（[^）]*）/g, " ")
    .replace(/\([^)]*\)/g, " ")
    .replace(/\[[^\]]*\]/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 60);

const searchProviderALyricByTrack = async (track: JsonObject) => {
  const cachedId = await getMatchedLyricId(track, PLATFORM_A_ID);
  if (cachedId) return fetchProviderALyricById(cachedId.platformId);

  const keyword = buildLyricSearchKeyword(track);
  if (!keyword) return null;
  // 内层搜索：同一关键词只调一次上游，主关键词与简化关键词各走一次
  const runSearch = (query: string) =>
    callProviderAApi<{ body?: { item_song?: ProviderASong[] } }>(
      "music.search.SearchCgiService",
      decodeAscii([
        68, 111, 83, 101, 97, 114, 99, 104, 70, 111, 114, 81, 81, 77, 117, 115, 105, 99, 77, 111,
        98, 105, 108, 101,
      ]),
      {
        search_id: generateProviderASearchId(),
        remoteplace: "search.android.keyboard",
        query,
        search_type: 0,
        num_per_page: 25,
        page_num: 1,
        highlight: 0,
        nqc_flag: 0,
        multi_zhida: 0,
        cat: 2,
        grp: 1,
        sin: 0,
        sem: 0,
        page_id: 1,
      },
    );
  try {
    let data: Awaited<ReturnType<typeof runSearch>>;
    try {
      data = await runSearch(keyword);
    } catch (err) {
      // 长关键词被上游拒（inner=2001）时用简化关键词重试一次，其他错误直接上抛
      const simplified = simplifyProviderAKeyword(keyword);
      if (
        !simplified ||
        simplified === keyword ||
        !(err instanceof Error) ||
        !err.message.includes("inner=2001")
      ) {
        throw err;
      }
      console.info(`[embedded-api] provider-a retry simplified "${simplified}"`);
      data = await runSearch(simplified);
    }
    const songs = Array.isArray(data.body?.item_song) ? data.body.item_song : [];
    const candidates = songs
      .map(toProviderACandidate)
      .filter(
        (candidate): candidate is LyricCandidate<{ id: string; mid: string }> => candidate !== null,
      );
    const best = pickBestCandidate(candidates, track);
    console.info(
      `[embedded-api] provider-a fuzzy "${keyword}" -> ${candidates.length} hits, best=${best?.candidate.name ?? "none"}`,
    );
    if (!best) return null;
    if (shouldPersistLyricMatch(best, track)) {
      await setMatchedLyricId(track, PLATFORM_A_ID, best.candidate.extra.id, {
        mid: best.candidate.extra.mid,
      });
    } else {
      stashMatchedLyricId(track, PLATFORM_A_ID, best.candidate.extra.id, {
        mid: best.candidate.extra.mid,
      });
    }
    return fetchProviderALyricById(best.candidate.extra.id, best.candidate.extra.mid);
  } catch (err) {
    console.warn(`[embedded-api] provider-a search failed for "${keyword}":`, err);
    return null;
  }
};

const providerBRequest = async <T>(url: string, headers?: Record<string, string>): Promise<T> => {
  const body = await requestJson<T & { error_code?: number; errcode?: number; err_code?: number }>(
    url,
    {
      headers,
    },
  );
  const code = body.error_code ?? body.errcode ?? body.err_code ?? 0;
  if (code !== 0 && code !== 200) throw new Error(`provider-b api error_code=${code}`);
  return body;
};

const toProviderBCandidate = (song: ProviderBSong): LyricCandidate<{ hash: string }> | null => {
  const hash = getStringValue(
    song.hash,
    song.hashes?.["128k"],
    song.hashes?.["320k"],
    song.hashes?.flac,
  );
  if (!hash) return null;
  const duration = getNumberValue(song.duration, song.interval);
  return {
    name: decodeVendorName(getStringValue(song.name, song.songname, song.filename)),
    artist: decodeVendorName(getStringValue(song.artist, song.singername))
      .split(/、|,|;|\//)
      .filter(Boolean)
      .join(" / "),
    album: decodeVendorName(getStringValue(song.album, song.album_name)),
    duration: duration && duration < 1000 ? duration * 1000 : duration,
    extra: { hash },
  };
};

const collectProviderBCandidates = (
  songs: ProviderBSong[] | undefined,
): LyricCandidate<{ hash: string }>[] =>
  (songs ?? [])
    .map(toProviderBCandidate)
    .filter((candidate): candidate is LyricCandidate<{ hash: string }> => candidate !== null);

const isProviderBPrimaryTlsMismatch = (error: unknown): boolean => {
  const message = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
  return (
    message.includes("ERR_TLS_CERT_ALTNAME_INVALID") ||
    (message.includes("mobilecdn.kugou.com") && message.includes("cert's altnames"))
  );
};

const searchProviderBFallbackCandidates = async (
  keyword: string,
): Promise<LyricCandidate<{ hash: string }>[]> => {
  const legacyUrl =
    `${PROVIDER_B_SEARCH_FALLBACK_URL}?keyword=${encodeURIComponent(keyword)}` +
    "&page=1&pagesize=25&userid=0&clientver=&platform=WebFilter&filter=2&iscorrection=1&privilege_filter=0&area_code=1";
  const body = await providerBRequest<{
    data?: {
      lists?: Array<{
        FileHash?: string;
        SongName?: string;
        Singers?: Array<{ name?: string }>;
        AlbumName?: string;
        Duration?: number;
      }>;
    };
  }>(legacyUrl);
  return (body.data?.lists ?? [])
    .map((song) =>
      toProviderBCandidate({
        hash: song.FileHash,
        name: song.SongName,
        artist: formatSingerName(song.Singers),
        album: song.AlbumName,
        duration: song.Duration ? song.Duration * 1000 : undefined,
      }),
    )
    .filter((candidate): candidate is LyricCandidate<{ hash: string }> => candidate !== null);
};

const searchProviderBLyricCandidates = async (
  keyword: string,
): Promise<LyricCandidate<{ hash: string }>[]> => {
  const url = `${PROVIDER_B_SEARCH_PRIMARY_URL}?keyword=${encodeURIComponent(keyword)}&page=1&pagesize=25&format=json&showtype=1`;
  try {
    const fallbackCandidates = await searchProviderBFallbackCandidates(keyword);
    if (fallbackCandidates.length > 0) return fallbackCandidates;
  } catch (error) {
    console.warn("[embedded-api] provider-b fallback search failed", error);
  }

  try {
    const body = await providerBRequest<{ data?: { info?: ProviderBSong[] } }>(url);
    const candidates = collectProviderBCandidates(body.data?.info);
    if (candidates.length > 0) return candidates;
  } catch (error) {
    if (!isProviderBPrimaryTlsMismatch(error)) {
      console.warn("[embedded-api] provider-b primary search failed", error);
    }
  }
  return [];
};

const pickProviderBFormatted = (
  krc?: string,
  lrc?: string,
): { content: string; format: "krc" | "lrc" } | undefined => {
  const krcContent = krc?.trim();
  if (krcContent) return { content: krcContent, format: "krc" };
  const lrcContent = lrc?.trim();
  if (lrcContent) return { content: lrcContent, format: "lrc" };
  return undefined;
};

const fetchProviderBLyricByHash = async (hash: string, name = "", durationMs?: number) => {
  const cached = await getCachedLyric(PLATFORM_B_ID, hash);
  if (cached) return cached;
  const seconds = durationMs ? Math.round(durationMs / 1000) : 0;
  const searchUrl =
    `${PROVIDER_B_LYRIC_SEARCH_URL}?ver=1&man=yes&client=pc&lrctxt=1` +
    `&keyword=${encodeURIComponent(name)}` +
    `&hash=${encodeURIComponent(hash)}` +
    `&timelength=${seconds}`;
  const searchResp = await providerBRequest<{
    candidates?: Array<{ id?: string; accesskey?: string; krctype?: number; contenttype?: number }>;
  }>(searchUrl, PROVIDER_B_LYRIC_HEADERS);
  const candidate = searchResp.candidates?.[0];
  if (!candidate?.id || !candidate.accesskey) return null;
  const fmt = candidate.krctype === 1 && candidate.contenttype !== 1 ? "krc" : "lrc";
  const downloadUrl =
    `${PROVIDER_B_LYRIC_DOWNLOAD_URL}?ver=1&client=pc&charset=utf8` +
    `&id=${encodeURIComponent(candidate.id)}` +
    `&accesskey=${encodeURIComponent(candidate.accesskey)}` +
    `&fmt=${fmt}`;
  const download = await providerBRequest<{ fmt?: string; content?: string }>(
    downloadUrl,
    PROVIDER_B_LYRIC_HEADERS,
  );
  if (!download.content) return null;

  let krc: string | undefined;
  let lrc: string | undefined;
  let trans: string | undefined;
  let roma: string | undefined;
  if (download.fmt === "krc") {
    const parsed = await decodeKrc(download.content);
    krc = parsed.krc;
    lrc = parsed.lrc;
    trans = parsed.trans;
    roma = parsed.roma;
  } else if (download.fmt === "lrc") {
    lrc = Buffer.from(download.content, "base64").toString("utf8");
  }

  const main = pickProviderBFormatted(krc, lrc);
  if (!main) return null;
  const result: MobileLyricResult = {
    platform: PLATFORM_B_ID,
    format: main.format,
    content: main.content,
    translation: trans?.trim() || undefined,
    translationFormat: trans?.trim() ? ("lrc" as const) : undefined,
    romaji: roma?.trim() || undefined,
    romajiFormat: roma?.trim() ? ("lrc" as const) : undefined,
  };
  await setCachedLyric(PLATFORM_B_ID, hash, result);
  return result;
};

const searchProviderBLyricByTrack = async (track: JsonObject) => {
  const cachedHash = await getMatchedLyricId(track, PLATFORM_B_ID);
  if (cachedHash)
    return fetchProviderBLyricByHash(
      cachedHash.platformId,
      getTrackTitle(track),
      getTrackDuration(track),
    );

  const keyword = buildLyricSearchKeyword(track);
  if (!keyword) return null;
  const candidates = await searchProviderBLyricCandidates(keyword);
  const best = pickBestCandidate(candidates, track);
  console.info(
    `[embedded-api] provider-b fuzzy "${keyword}" -> ${candidates.length} hits, best=${best?.candidate.name ?? "none"}`,
  );
  if (!best) return null;
  if (shouldPersistLyricMatch(best, track)) {
    await setMatchedLyricId(track, PLATFORM_B_ID, best.candidate.extra.hash);
  } else {
    stashMatchedLyricId(track, PLATFORM_B_ID, best.candidate.extra.hash);
  }
  return fetchProviderBLyricByHash(
    best.candidate.extra.hash,
    best.candidate.name,
    best.candidate.duration,
  );
};

// ── 评论数据规范化 ───────────────────────────────────────────────────────────

const toStringId = (value: unknown): string => {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "bigint") return String(value);
  return "";
};

const optionalString = (value: unknown): string | undefined => {
  if (typeof value !== "string") return undefined;
  const text = value.trim();
  return text || undefined;
};

/** 转换网易云评论项为统一格式 */
const normalizeCommentItem = (raw: JsonObject) => {
  const id = toStringId(raw.commentId ?? raw.beRepliedCommentId);
  const text = optionalString(raw.content);
  if (!id || !text) return null;

  const user = raw.user as JsonObject | undefined;
  const userId = toStringId(user?.userId);
  const userName = optionalString(user?.nickname) ?? "";
  const avatar = optionalString(user?.avatarUrl);

  const reply = (Array.isArray(raw.beReplied) ? raw.beReplied : [])
    .map((item) => normalizeCommentItem(item as JsonObject))
    .filter((item): item is NonNullable<typeof item> => item !== null);

  const item: Record<string, unknown> = { id, userName, text };
  if (userId) item.userId = userId;
  if (avatar) item.avatar = avatar;
  if (typeof raw.time === "number") item.time = raw.time;
  const ipLocation = raw.ipLocation as JsonObject | undefined;
  const location = optionalString(ipLocation?.location);
  if (location) item.location = location;
  if (typeof raw.likedCount === "number") item.likedCount = raw.likedCount;
  if (typeof raw.replyCount === "number") item.replyTotal = raw.replyCount;
  if (reply.length) item.reply = reply;
  return item;
};

const handleNeteaseRoute = async (
  pathname: string,
  query: Record<string, unknown>,
  body: Record<string, unknown>,
  request: IncomingMessage,
  response: ServerResponse,
) => {
  if (pathname === "/api/netease") {
    sendJson(request, response, 200, {
      name: "@neteasecloudmusicapienhanced/api",
      description: "NeteaseCloudMusicApi Enhanced",
      url: "https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced",
    });
    return;
  }

  if (pathname === "/api/netease/lyric/ttml") {
    const id = String(query.id || "");
    if (!id) {
      sendJson(request, response, 400, { error: "id is required" });
      return;
    }

    try {
      const text = await fetchText(
        DEFAULT_AMLL_DB_SERVER.replace("%p", "ncm-lyrics").replace("%s", encodeURIComponent(id)),
      );
      if (text === null) {
        sendJson(request, response, 200, null);
        return;
      }
      sendText(request, response, 200, text);
    } catch (error) {
      console.error("[embedded-api] Fetch TTML lyric failed", error);
      sendJson(request, response, 200, null);
    }
    return;
  }

  const requestPath = pathname.replace(/^\/api\/netease\//, "");
  // ensureNeteaseApiConfig 必须在 loadNeteaseApi 之前执行：
  // request.js 在模块加载时同步读取 anonymous_token 文件，若文件不存在会直接抛异常
  await ensureNeteaseApiConfig();
  if (!loadNeteaseApi(requestPath)) {
    sendJson(request, response, 404, { error: "API not found" });
    return;
  }

  try {
    const result = await callNeteaseApi(requestPath, query, body, request);
    sendJson(request, response, 200, result.ok ? result.body : { error: result.error });
  } catch (error: unknown) {
    console.error("[embedded-api] Netease API request failed", requestPath, error);

    if (typeof error === "object" && error) {
      const apiError = error as { status?: number; body?: unknown; message?: string };
      sendJson(
        request,
        response,
        getApiErrorStatus(apiError.status),
        apiError.body || { error: apiError.message || "Internal Server Error" },
      );
      return;
    }

    sendJson(request, response, 500, { error: String(error) });
  }
};

// ─── 局域网分享模式 ─────────────────────────────────────────────────────────

const LAN_SHARE_PATH = path.join(CONFIG_DIR, "lan-share.json");

interface LanDevice {
  ip: string;
  name?: string;
  sharedLogin: boolean;
  /** 是否对该设备开启协同广播（决策由主设备控制） */
  shareCollab: boolean;
  addedAt: number;
}

interface LanShareConfig {
  enabled: boolean;
  collabEnabled: boolean;
  shareUserInfo: boolean;
  devices: LanDevice[];
}

let lanShareState: LanShareConfig = {
  enabled: false,
  collabEnabled: false,
  shareUserInfo: false,
  devices: [],
};

const loadLanShareConfig = (): void => {
  try {
    if (!existsSync(LAN_SHARE_PATH)) return;
    const raw = readFileSync(LAN_SHARE_PATH, "utf8");
    const parsed = JSON.parse(raw) as Partial<LanShareConfig>;
    lanShareState = {
      enabled: parsed.enabled === true,
      collabEnabled: parsed.collabEnabled === true,
      shareUserInfo: parsed.shareUserInfo === true,
      devices: Array.isArray(parsed.devices)
        ? parsed.devices.map((d) => ({
            ip: String(d.ip ?? ""),
            name: d.name,
            sharedLogin: d.sharedLogin === true,
            // 兼容旧配置：缺失时回退到 sharedLogin 值
            shareCollab:
              d.shareCollab === undefined ? d.sharedLogin === true : d.shareCollab === true,
            addedAt: Number(d.addedAt ?? Date.now()),
          }))
        : [],
    };
  } catch (err) {
    console.warn("[embedded-api] 读取 LAN 分享配置失败:", err);
  }
};

// 冷启动加载
loadLanShareConfig();

/** 提取请求来源 IP（IPv6-mapped → IPv4） */
const extractClientIp = (req: IncomingMessage): string => {
  const raw = req.socket.remoteAddress || "";
  return raw.replace(/^::ffff:/, "");
};

/** LAN 访问控制：非 localhost 请求仅在 lanShareState.enabled 时放行（防御层） */
const isLanAccessAllowed = (req: IncomingMessage): boolean => {
  const clientIp = extractClientIp(req);
  if (clientIp === "127.0.0.1" || clientIp === "::1" || clientIp === "localhost") return true;
  return lanShareState.enabled;
};

/** 处理 LAN 分享 API 路由 */
const handleLanShareRoute = async (): Promise<boolean> => {
  // LanShare has been migrated to Kotlin ApiServer.
  return false;
};

export const startEmbeddedApiServer = async () => {
  const server = http.createServer(async (request, response) => {
    setCorsHeaders(request, response);

    // ── LAN 访问控制：非 localhost 请求仅在开启局域网分享时放行 ──
    if (!isLanAccessAllowed(request)) {
      sendJson(request, response, 403, {
        error: "LAN access disabled. Enable LAN sharing in settings.",
      });
      return;
    }

    if (!request.url) {
      sendJson(request, response, 400, { error: "Missing request URL" });
      return;
    }

    if (request.method === "OPTIONS") {
      response.statusCode = 204;
      response.end();
      return;
    }

    try {
      const url = new URL(request.url, `http://127.0.0.1:${DEFAULT_PORT}`);
      const pathname = url.pathname.replace(/\/+$/, "") || "/";
      const isDebugLogEnabled =
        process.env.SP_DEBUG_LOG === "true" || process.env.NODE_ENV === "development";
      if (isDebugLogEnabled) {
        console.log(`[embedded-api] ${request.method} ${pathname}`);
      }
      const query = Object.fromEntries(url.searchParams.entries());
      const rawBody = request.method === "POST" ? await readRequestBody(request) : "";
      const body = parseBody(rawBody, request.headers["content-type"]);

      // ── 静态资源：非 /api 路径交给 SPA 静态服务（打包 APK 内置 web/，浏览器接入即同步端） ──
      if ((request.method === "GET" || request.method === "HEAD") && !pathname.startsWith("/api")) {
        if (serveStaticAsset(request, response, pathname)) return;
      }

      // ── LAN 分享路由 ──
      if (pathname.startsWith("/api/lanShare/")) {
        if (await handleLanShareRoute()) return;
        sendJson(request, response, 404, { error: "LAN share endpoint not found" });
        return;
      }

      if (pathname === "/api") {
        sendJson(request, response, 200, {
          name: "SPlayer API",
          description: "Embedded local API service for SPlayer Android",
          list: [
            {
              name: "NeteaseCloudMusicApi",
              url: "/api/netease",
            },
          ],
        });
        return;
      }

      if (pathname === "/api/netease" || pathname.startsWith("/api/netease/")) {
        await handleNeteaseRoute(pathname, query, body, request, response);
        return;
      }

      if (pathname === "/api/apis/clearSession") {
        if (!isAllowedLocalOrigin(getHeaderValue(request.headers.origin))) {
          sendJson(request, response, 403, { ok: false, error: "forbidden" });
          return;
        }
        setNeteaseSessionCookie("");
        sendJson(request, response, 200, { ok: true });
        return;
      }

      if (pathname === "/api/apis/setCookie") {
        if (!isAllowedLocalOrigin(getHeaderValue(request.headers.origin))) {
          sendJson(request, response, 403, { ok: false, error: "forbidden" });
          return;
        }
        const { platform, cookie } = body as { platform?: string; cookie?: string };
        if (platform !== "netease") {
          sendJson(request, response, 200, { ok: false, error: "unsupported platform" });
          return;
        }
        if (!cookie?.includes("MUSIC_U")) {
          sendJson(request, response, 200, { ok: false, error: "missing MUSIC_U" });
          return;
        }
        setNeteaseSessionCookie(cookie);
        sendJson(request, response, 200, { ok: true });
        return;
      }

      if (pathname === "/api/apis/openLoginWeb") {
        if (!isAllowedLocalOrigin(getHeaderValue(request.headers.origin))) {
          sendJson(request, response, 403, { ok: false, error: "forbidden" });
          return;
        }
        sendJson(request, response, 200, { ok: false, error: "unsupported on Android" });
        return;
      }

      // ── /api/apis/call → 转发到 Netease API ──────────────────────────────────
      if (pathname === "/api/apis/call") {
        if (!isAllowedLocalOrigin(getHeaderValue(request.headers.origin))) {
          sendJson(request, response, 403, { ok: false, error: "forbidden" });
          return;
        }
        const { platform, name, params } = body as {
          platform?: string;
          name?: string;
          params?: Record<string, unknown>;
        };
        if (platform === "netease" && name) {
          try {
            const result = await callNeteaseApi(name, params ?? {}, {}, request);
            sendJson(request, response, 200, result);
          } catch (error: unknown) {
            console.error("[embedded-api] API call failed", platform, name, error);
            sendJson(request, response, 200, {
              ok: false,
              error: error instanceof Error ? error.message : String(error),
            });
          }
          return;
        }
        sendJson(request, response, 200, { ok: false, error: "unsupported platform or API name" });
        return;
      }

      // ── 已知 API 路由返回默认值，避免 404 ────────────────────────────────────

      // config
      if (pathname.startsWith("/api/config/")) {
        if (!handleConfigRoute(pathname, query, body, request, response)) {
          sendJson(request, response, 200, { ok: true });
        }
        return;
      }

      // streaming
      if (pathname === "/api/streaming/loadServers") {
        sendJson(request, response, 200, []);
        return;
      }
      if (pathname.startsWith("/api/streaming/")) {
        sendJson(request, response, 200, { ok: true });
        return;
      }

      // library
      if (pathname === "/api/library/getTracks") {
        sendJson(request, response, 200, { success: true, data: [] });
        return;
      }
      if (pathname === "/api/library/getAlbums" || pathname === "/api/library/getArtists") {
        sendJson(request, response, 200, { success: true, data: [] });
        return;
      }
      if (
        pathname === "/api/library/getAlbumTracks" ||
        pathname.startsWith("/api/library/getAlbumTracks?")
      ) {
        sendJson(request, response, 200, { success: true, data: [] });
        return;
      }
      if (
        pathname === "/api/library/getArtistTracks" ||
        pathname.startsWith("/api/library/getArtistTracks?")
      ) {
        sendJson(request, response, 200, { success: true, data: [] });
        return;
      }
      if (
        pathname === "/api/library/searchTracks" ||
        pathname.startsWith("/api/library/searchTracks?")
      ) {
        sendJson(request, response, 200, { success: true, data: [] });
        return;
      }
      if (pathname === "/api/library/getTrackCount") {
        sendJson(request, response, 200, { success: true, data: 0 });
        return;
      }
      if (pathname === "/api/library/getRandomTrack") {
        sendJson(request, response, 200, { success: true, data: null });
        return;
      }
      if (
        pathname === "/api/library/getRandomTracks" ||
        pathname.startsWith("/api/library/getRandomTracks?")
      ) {
        sendJson(request, response, 200, { success: true, data: [] });
        return;
      }
      if (pathname === "/api/library/isScanning") {
        sendJson(request, response, 200, { success: true, data: false });
        return;
      }
      if (pathname === "/api/library/getScanDirs") {
        sendJson(request, response, 200, { success: true, data: [] });
        return;
      }
      if (
        pathname === "/api/library/fetchArtistAvatar" ||
        pathname.startsWith("/api/library/fetchArtistAvatar?")
      ) {
        sendJson(request, response, 200, { success: true, data: null });
        return;
      }
      if (pathname.startsWith("/api/library/")) {
        sendJson(request, response, 200, { success: true });
        return;
      }

      // stats
      if (pathname === "/api/stats/recordPlay") {
        if (!isPlayEventInput(body)) {
          sendJson(request, response, 400, { error: "INVALID_PLAY_EVENT" });
          return;
        }
        insertMobilePlayEvent(body);
        sendJson(request, response, 200, { ok: true });
        return;
      }
      if (pathname === "/api/stats/recordFavorite") {
        if (!isFavoriteEventInput(body)) {
          sendJson(request, response, 400, { error: "INVALID_FAVORITE_EVENT" });
          return;
        }
        insertMobileFavoriteEvent(body);
        sendJson(request, response, 200, { ok: true });
        return;
      }
      if (pathname === "/api/stats/getSummary" || pathname === "/api/stats/getStatsSummary") {
        sendJson(request, response, 200, getMobileStatsSummary());
        return;
      }
      if (
        pathname === "/api/stats/getTopTracks" ||
        pathname.startsWith("/api/stats/getTopTracks?")
      ) {
        const limit = Math.max(1, Math.min(Number(query.limit) || 6, 50));
        sendJson(request, response, 200, getMobileTopTracks(limit));
        return;
      }
      if (pathname === "/api/stats/getPlayedTracks") {
        sendJson(request, response, 200, getMobilePlayedTracks());
        return;
      }
      if (
        pathname === "/api/stats/getPlayHistoryDaily" ||
        pathname.startsWith("/api/stats/getPlayHistoryDaily?")
      ) {
        const days = Math.max(1, Number(query.days) || 90);
        sendJson(request, response, 200, getMobilePlayHistoryDaily(days));
        return;
      }
      if (pathname === "/api/stats/getPlayHistoryHourly") {
        sendJson(request, response, 200, getMobilePlayHistoryHourly());
        return;
      }
      if (
        pathname === "/api/stats/getTopAlbums" ||
        pathname.startsWith("/api/stats/getTopAlbums?")
      ) {
        const limit = Math.max(1, Math.min(Number(query.limit) || 6, 50));
        sendJson(request, response, 200, getMobileTopAlbums(limit));
        return;
      }
      if (
        pathname === "/api/stats/getTopArtists" ||
        pathname.startsWith("/api/stats/getTopArtists?")
      ) {
        const limit = Math.max(1, Math.min(Number(query.limit) || 6, 50));
        sendJson(request, response, 200, getMobileTopArtists(limit));
        return;
      }
      if (pathname.startsWith("/api/stats/")) {
        sendJson(request, response, 200, { ok: true });
        return;
      }

      // plugins - watch 长轮询（新增，不影响既有插件链路）
      if (pathname === "/api/plugins/watch") {
        /**
         * 硬约束：Kotlin 代理 nodeReadTimeoutMs=30000，server 端等待必须 <30s。
         * 此处取 25s，超时返回空轮 {events:[], cursor:N}，前端收到后立即发起下一轮。
         */
        await androidPluginRegistry.ensureInitialized();
        const rawCursor = (query.cursor ?? (body as Record<string, unknown>).cursor) as unknown;
        let sinceCursor = 0;
        if (rawCursor !== undefined && rawCursor !== null && String(rawCursor).trim() !== "") {
          const parsed = Number(rawCursor);
          sinceCursor = Number.isFinite(parsed) ? Math.floor(parsed) : 0;
          if (sinceCursor < 0) sinceCursor = 0;
        }
        const immediate = androidPluginRegistry.getWatchEventsSince(sinceCursor);
        if ("reset" in immediate) {
          sendJson(request, response, 200, { reset: true, cursor: immediate.cursor });
          return;
        }
        if (immediate.events.length > 0) {
          sendJson(request, response, 200, { events: immediate.events, cursor: immediate.cursor });
          return;
        }
        // 无增量则进入长轮询等待，25s 超时后空轮返回（必须 ≤25s，避免 Kotlin 30s 掐连接）
        let aborted = false;
        const onClose = (): void => {
          aborted = true;
        };
        request.on("close", onClose);
        try {
          const waited = await androidPluginRegistry.waitForChange(sinceCursor, 25000);
          if (aborted || response.writableEnded || response.destroyed) return;
          // waitForChange 已对过期 cursor 做钳制；此处再做一次 reset 校验，确保契约严格
          const rechecked = androidPluginRegistry.getWatchEventsSince(sinceCursor);
          if ("reset" in rechecked) {
            sendJson(request, response, 200, { reset: true, cursor: rechecked.cursor });
            return;
          }
          // waited 已是增量或空轮，直接返回；保持 events/cursor 字段契约
          // 超时空轮时 waited.events=[] 且 waited.cursor===sinceCursor
          // 有增量时 waited.cursor===最新 seq
          sendJson(request, response, 200, {
            events: waited.events,
            cursor: waited.cursor,
          });
        } finally {
          request.off("close", onClose);
        }
        return;
      }

      // plugins
      if (pathname.startsWith("/api/plugins/")) {
        const pluginRouteResult = await handleAndroidPluginRoute(pathname, body);
        if (pluginRouteResult) {
          sendJson(request, response, pluginRouteResult.statusCode, pluginRouteResult.body);
          return;
        }
      }

      // apis (non-call → already handled above)
      if (pathname.startsWith("/api/apis/")) {
        sendJson(request, response, 200, { ok: true });
        return;
      }

      // lyrics
      if (pathname.startsWith("/api/lyrics/")) {
        if (pathname === "/api/lyrics/matchById" || pathname === "/api/lyrics/matchByQuery") {
          try {
            const { platform, id, track } = body as {
              platform?: string;
              id?: string;
              track?: Record<string, unknown>;
            };
            if (platform === "netease") {
              if (pathname === "/api/lyrics/matchById" && id) {
                const lyric = await fetchNeteaseLyricById(id, request);
                sendJson(request, response, 200, { ok: true, data: lyric });
                return;
              } else if (pathname === "/api/lyrics/matchByQuery" && track) {
                const lyric = await searchNeteaseLyricByTrack(track, request);
                sendJson(request, response, 200, { ok: true, data: lyric });
                return;
              }
            }
            if (platform === PLATFORM_A_ID) {
              if (pathname === "/api/lyrics/matchById" && id) {
                const lyric = await fetchProviderALyricById(id);
                sendJson(request, response, 200, { ok: true, data: lyric });
                return;
              } else if (pathname === "/api/lyrics/matchByQuery" && track) {
                const lyric = await searchProviderALyricByTrack(track);
                sendJson(request, response, 200, { ok: true, data: lyric });
                return;
              }
            }
            if (platform === PLATFORM_B_ID) {
              if (pathname === "/api/lyrics/matchById" && id) {
                const lyric = await fetchProviderBLyricByHash(id);
                sendJson(request, response, 200, { ok: true, data: lyric });
                return;
              } else if (pathname === "/api/lyrics/matchByQuery" && track) {
                const lyric = await searchProviderBLyricByTrack(track);
                sendJson(request, response, 200, { ok: true, data: lyric });
                return;
              }
            }
          } catch (error: unknown) {
            console.error("[embedded-api] lyrics match failed", error);
          }
        }

        // ── TTML 升级 ──
        if (pathname === "/api/lyrics/fetchTTMLOverlay") {
          try {
            const { track, platform, server } = body as {
              track?: Record<string, unknown>;
              platform?: string;
              server?: string;
            };
            if (!track || !platform) {
              sendJson(request, response, 200, { ok: false, error: "missing track or platform" });
              return;
            }
            // AMLL DB 仅 netease 与 provider-a 有索引
            const platformPath =
              platform === "netease"
                ? "ncm-lyrics"
                : platform === PLATFORM_A_ID
                  ? PLATFORM_A_DB_PATH
                  : null;
            if (!platformPath) {
              sendJson(request, response, 200, {
                ok: false,
                error: "platform not supported by AMLL DB",
              });
              return;
            }
            const ids: string[] = [];
            const pushId = (value: unknown) => {
              const id = getStringValue(value);
              if (id && !ids.includes(id)) ids.push(id);
            };
            const matchedId = await getMatchedLyricId(track, platform);
            // provider-a 的 mid 放前面，兼容早期 AMLL DB 文件命名
            if (platform === PLATFORM_A_ID && matchedId?.extra) {
              try {
                const extra = JSON.parse(matchedId.extra) as { mid?: string };
                pushId(extra.mid);
              } catch {
                /* extra 不是合法 JSON，忽略 */
              }
            }
            if (track.source === platform) {
              pushId(track.id);
              pushId(track.extId);
            }
            pushId(matchedId?.platformId);
            if (ids.length === 0) {
              sendJson(request, response, 200, { ok: false, error: "missing track id" });
              return;
            }
            const urlTemplate = resolveTtmlServerTemplate(server);
            for (const id of ids) {
              const cacheKey = `ttml:${platform}:${id}`;
              const l1 = getL1Cache<{ status: "hit" | "negative" | "miss"; content?: string }>(
                cacheKey,
              );
              if (l1 !== undefined) {
                if (l1.status === "hit" && l1.content) {
                  sendJson(request, response, 200, { ok: true, data: l1.content });
                  return;
                }
                if (l1.status === "negative") continue;
              }

              // 先查 SQLite 持久化缓存（含 72h 负缓存 TTL，对齐桌面端 ttml.ts）
              const ttmlCacheRes = await callKotlinCacheDb<{
                status: "hit" | "negative" | "miss";
                content?: string;
              }>(
                `/api/cache/db/ttml/get?platform=${encodeURIComponent(platform)}&id=${encodeURIComponent(id)}`,
              );

              if (ttmlCacheRes) {
                setL1Cache(cacheKey, ttmlCacheRes);
              }

              if (ttmlCacheRes?.status === "hit" && ttmlCacheRes.content) {
                sendJson(request, response, 200, { ok: true, data: ttmlCacheRes.content });
                return;
              }
              if (ttmlCacheRes?.status === "negative") {
                // 负缓存：72h 内已确认 AMLL DB 没有此 id，跳过网络请求
                continue;
              }
              // 与桌面端 ttml.ts 对齐：对 id 做 URL 编码，避免 provider-a mid 含特殊字符时拼接失败
              const url = urlTemplate
                .replace("%p", platformPath)
                .replace("%s", encodeURIComponent(id));
              const text = await fetchText(url);
              if (text !== null) {
                // 正缓存：写入 TTML 文本
                setL1Cache(cacheKey, { status: "hit", content: text });
                await callKotlinCacheDb("/api/cache/db/ttml/set", {
                  platform,
                  id,
                  content: text,
                });
                sendJson(request, response, 200, { ok: true, data: text });
                return;
              }
              // 负缓存：标记此 id 在 AMLL DB 不存在，72h 内不再请求
              setL1Cache(cacheKey, { status: "negative", content: undefined });
              await callKotlinCacheDb("/api/cache/db/ttml/set", {
                platform,
                id,
                content: null,
              });
            }
            sendJson(request, response, 200, { ok: false, error: "TTML not found" });
            return;
          } catch (error: unknown) {
            console.error("[embedded-api] fetchTTMLOverlay failed", error);
            sendJson(request, response, 200, { ok: false, error: "TTML fetch error" });
            return;
          }
        }

        // matchLocalTTML 在 Android 上暂不支持（无文件系统访问）
        if (pathname === "/api/lyrics/matchLocalTTML") {
          sendJson(request, response, 200, { ok: false, error: "unsupported on Android" });
          return;
        }

        // 其他歌词接口（pickLyricRepoDir 等）
        sendJson(request, response, 200, { ok: false, data: null });
        return;
      }

      // cache block deleted

      // ── 评论 ──────────────────────────────────────────────────────────────
      if (pathname === "/api/comments/sources") {
        sendJson(request, response, 200, [
          { id: "builtin:netease", name: "NCM", kind: "builtin", platform: "netease" },
        ]);
        return;
      }

      if (pathname === "/api/comments/get") {
        try {
          const { sourceId, track, type, page, limit } = body as {
            sourceId?: string;
            track?: JsonObject;
            type?: string;
            page?: number;
            limit?: number;
          };

          if (sourceId !== "builtin:netease" || !track) {
            sendJson(request, response, 200, {
              ok: false,
              error: "unsupported source or missing track",
            });
            return;
          }

          const pageNo = Math.max(1, Math.floor(Number(page) || 1));
          const pageLimit = Math.min(50, Math.max(1, Math.floor(Number(limit) || 20)));
          const commentType = type === "hot" ? "hot" : "new";

          let neteaseId: string | null = null;
          const trackSource = getStringValue(track.source);
          const trackId = getStringValue(track.id);
          if (trackSource === "netease" && trackId) {
            neteaseId = trackId;
          } else {
            const keyword = buildLyricSearchKeyword(track);
            if (keyword) {
              const searchResult = await callNeteaseApi(
                "search",
                { keywords: keyword, limit: 20, type: 1 },
                {},
                request,
              );
              if (searchResult.ok && searchResult.body && typeof searchResult.body === "object") {
                const searchBody = searchResult.body as { result?: { songs?: NeteaseSong[] } };
                const songs = Array.isArray(searchBody.result?.songs)
                  ? searchBody.result.songs
                  : [];
                const candidates = songs
                  .map(toNeteaseCandidate)
                  .filter((c): c is LyricCandidate<{ id: string }> => c !== null);
                const best = pickBestCandidate(candidates, track);
                if (best) neteaseId = best.candidate.extra.id;
              }
            }
          }

          if (!neteaseId) {
            sendJson(request, response, 200, {
              ok: true,
              data: { list: [], total: 0, page: pageNo, limit: pageLimit },
            });
            return;
          }

          // comment_hot 端点已被网易下线（固定返回 code 400），
          // 精选评论统一走 comment_music，从响应内 hotComments 字段取数
          const result = await callNeteaseApi(
            "comment_music",
            { id: neteaseId, type: "R_SO_4_", limit: pageLimit, offset: (pageNo - 1) * pageLimit },
            {},
            request,
          );

          if (!result.ok || result.status !== 200) {
            sendJson(request, response, 200, {
              ok: false,
              error: `netease comment API failed: ${result.error ?? "unknown"}`,
            });
            return;
          }

          const commentBody = (result.body ?? {}) as Record<string, unknown>;
          const dataObj = commentBody.data as JsonObject | undefined;
          const rawHot = commentBody.hotComments;
          const rawNew = commentBody.comments;
          const rawDataComments = dataObj?.comments;
          const rawList: unknown[] =
            commentType === "hot"
              ? Array.isArray(rawHot)
                ? rawHot
                : []
              : Array.isArray(rawNew)
                ? rawNew
                : Array.isArray(rawDataComments)
                  ? rawDataComments
                  : [];

          const list = rawList
            .map((raw) => normalizeCommentItem(raw as JsonObject))
            .filter((item): item is NonNullable<typeof item> => item !== null);

          // 热门评论不随 offset 分页，total 取列表长度，避免出现可翻页的假象
          const total =
            commentType === "hot"
              ? list.length
              : typeof commentBody.total === "number"
                ? commentBody.total
                : typeof dataObj?.totalCount === "number"
                  ? (dataObj.totalCount as number)
                  : list.length;

          sendJson(request, response, 200, {
            ok: true,
            data: { list, total, page: pageNo, limit: pageLimit },
          });
        } catch (error) {
          console.error("[embedded-api] comments/get failed:", error);
          sendJson(request, response, 200, {
            ok: false,
            error: error instanceof Error ? error.message : String(error),
          });
        }
        return;
      }

      // lastfm
      if (pathname === "/api/lastfm/getStatus") {
        sendJson(request, response, 200, { connected: false });
        return;
      }
      if (pathname.startsWith("/api/lastfm/")) {
        sendJson(request, response, 200, { ok: true });
        return;
      }

      // system (fetchRemoteBytes / saveFile)
      if (pathname === "/api/system/fetchRemoteBytes") {
        const targetUrl = query["url"];
        if (!targetUrl) {
          sendJson(request, response, 400, { success: false, error: "MISSING_URL" });
          return;
        }
        fetchRemoteBytesSafely(targetUrl)
          .then((buf) => {
            sendJson(request, response, 200, {
              success: true,
              data: buf.toString("base64"),
              encoding: "base64",
            });
          })
          .catch((err: Error & { code?: string; status?: number }) => {
            sendJson(request, response, err.status || 502, {
              success: false,
              error: err.code || "FETCH_ERROR",
            });
          });
        return;
      }
      if (pathname.startsWith("/api/system/")) {
        sendJson(request, response, 200, { success: true, data: null });
        return;
      }

      // player (readLyricFile)
      if (pathname.startsWith("/api/player/")) {
        sendJson(request, response, 200, { success: true, data: "" });
        return;
      }

      // theme
      if (pathname.startsWith("/api/theme/")) {
        sendJson(request, response, 200, { ok: true });
        return;
      }

      // update
      if (pathname === "/api/update/check") {
        sendJson(request, response, 200, null);
        return;
      }
      if (pathname.startsWith("/api/update/")) {
        sendJson(request, response, 200, { ok: true });
        return;
      }

      // 兜底：未知 API 返回空对象
      console.warn(`[embedded-api] unhandled API route: ${request.method} ${pathname}`);
      sendJson(request, response, 200, {});
    } catch (err) {
      const status = (err as { status?: number }).status === 413 ? 413 : 500;
      console.error(`[embedded-api] request handler error for ${request.url}:`, err);
      if (!response.headersSent) {
        sendJson(request, response, status, {
          error: status === 413 ? "Payload Too Large" : "Internal Server Error",
        });
      } else {
        response.end();
      }
    }
  });

  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(DEFAULT_PORT, DEFAULT_HOST, () => {
      server.removeListener("error", reject);
      resolve();
    });
  });
  // 慢连接防护：headers 上限 30s；socket 空闲上限需大于 /api/plugins/watch 的 25s 长轮询
  server.headersTimeout = 30_000;
  server.timeout = 120_000;

  console.log(`[embedded-api] listening on http://${DEFAULT_HOST}:${DEFAULT_PORT}/api`);
  notifyEmbeddedApiReady();

  return server;
};

void startEmbeddedApiServer().catch((error) => {
  console.error("[embedded-api] startEmbeddedApiServer failed", error);
});
