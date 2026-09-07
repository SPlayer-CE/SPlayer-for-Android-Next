import {
  argbFromHex,
  themeFromSourceColor,
  QuantizerCelebi,
  Hct,
  type Theme,
} from "@material/material-color-utilities";
import type { ThemePalette } from "@/types/theme";
import { useSettingsStore } from "@/stores/settings";
import { useThemeStore } from "@/stores/theme";
import { resolveCoverUrl } from "@/services/bridge";
import {
  COVER_SAMPLE_SIZE,
  argbPixelsToCoverHex,
  rgbaPixelsToArgbInts,
  type CoverColorWorkerRequest,
  type CoverColorWorkerResponse,
} from "./coverColor.worker";
// Vite Worker 导入
import CoverColorWorker from "./coverColor.worker?worker";

/** 默认主色 */
export const DEFAULT_PRIMARY = "#fe7971";
/** 封面取色竞态 requestId 计数器 */
let coverColorRequestId = 0;
/** 封面边缘留白 */
const COVER_EDGE_MARGIN = 3;
/** 封面最小色度 */
const MIN_COVER_CHROMA = 8;
/** 彩色区域至少覆盖该比例，避免少量点缀色覆盖大面积中性色 */
const MIN_COLORFUL_POPULATION_RATIO = 0.12;

/** ARGB 整数转 HEX 字符串 */
const argbToHex = (argb: number): string => {
  const r = (argb >> 16) & 0xff;
  const g = (argb >> 8) & 0xff;
  const b = argb & 0xff;
  return `#${r.toString(16).padStart(2, "0")}${g.toString(16).padStart(2, "0")}${b.toString(16).padStart(2, "0")}`;
};

/** 根据位置给中心主体区域更高权重，降低边框和角落装饰干扰 */
const sampleWeight = (x: number, y: number): number => {
  const center = (COVER_SAMPLE_SIZE - 1) / 2;
  const dx = (x - center) / center;
  const dy = (y - center) / center;
  const distance = Math.sqrt(dx * dx + dy * dy);
  if (distance < 0.34) return 3;
  if (distance < 0.58) return 2;
  return 1;
};

/** 从量化后的候选中选封面代表色 */
const pickRepresentativeCoverColor = (colors: Map<number, number>): number | null => {
  const entries = Array.from(colors);
  if (entries.length === 0) return null;
  const totalCount = entries.reduce((sum, [, count]) => sum + count, 0);
  const colorfulEntries = entries.filter(([argb]) => {
    const hct = Hct.fromInt(argb);
    return hct.chroma >= MIN_COVER_CHROMA && hct.tone >= 10 && hct.tone <= 94;
  });
  const colorfulCount = colorfulEntries.reduce((sum, [, count]) => sum + count, 0);
  if (colorfulCount / totalCount < MIN_COLORFUL_POPULATION_RATIO) return null;
  const maxCount = Math.max(...colorfulEntries.map(([, count]) => count));

  let best: number | null = null;
  let bestScore = 0;
  for (const [argb, count] of colorfulEntries) {
    const hct = Hct.fromInt(argb);
    const populationScore = Math.pow(count / maxCount, 0.72);
    const chromaScore = Math.min(hct.chroma / 52, 1);
    const toneScore = Math.max(0, 1 - Math.abs(hct.tone - 58) / 58);
    const score = populationScore * 0.58 + chromaScore * 0.28 + toneScore * 0.14;
    if (score > bestScore) {
      best = argb;
      bestScore = score;
    }
  }
  return best;
};

/** 把真实代表色约束到适合作为背景基色的范围 */
const toCoverBaseColor = (argb: number): string => {
  const hct = Hct.fromInt(argb);
  const tone = Math.min(72, Math.max(28, hct.tone));
  const chroma = Math.min(64, Math.max(12, hct.chroma));
  return argbToHex(Hct.from(hct.hue, chroma, tone).toInt());
};

/**
 * 把封面原始主色转成 UI 显示色
 * 提亮到 tone 88、压缩 chroma 到 14-30 区间，避免高饱和刺眼
 */
const toCoverUiColor = (hex: string): string => {
  const hct = Hct.fromInt(argbFromHex(hex));
  const tone = 88;
  const chroma = Math.min(30, Math.max(14, hct.chroma * 0.48));
  return argbToHex(Hct.from(hct.hue, chroma, tone).toInt());
};

/** 将 HEX 字符串转为 "R G B" 字符串 */
export const hexToRgb = (hex: string): string => {
  return `${parseInt(hex.slice(1, 3), 16)} ${parseInt(hex.slice(3, 5), 16)} ${parseInt(hex.slice(5, 7), 16)}`;
};

/** 根据主色 HEX 和明暗模式生成色板 */
export const generatePalette = (hex: string, isDark: boolean, globalTint = false): ThemePalette => {
  const safeHex = typeof hex === "string" && hex.startsWith("#") ? hex : DEFAULT_PRIMARY;
  const theme: Theme = themeFromSourceColor(argbFromHex(safeHex));
  // 用 secondary palette 生成主色
  const { hue, chroma } = theme.palettes.secondary;
  const toneColor = (tone: number) => {
    const argb = Hct.from(hue, chroma, tone).toInt();
    return `${(argb >> 16) & 0xff} ${(argb >> 8) & 0xff} ${argb & 0xff}`;
  };
  // 主色
  const primary = isDark ? toneColor(90) : toneColor(10);
  const primaryColors = {
    primary,
    primaryContainer: isDark ? toneColor(30) : toneColor(90),
    onPrimary: isDark ? toneColor(10) : toneColor(100),
    onPrimaryContainer: isDark ? toneColor(90) : toneColor(10),
  };
  // 全局着色
  if (globalTint) {
    return {
      ...primaryColors,
      secondary: isDark ? toneColor(80) : toneColor(40),
      secondaryContainer: isDark ? toneColor(30) : toneColor(90),
      surface: isDark ? toneColor(20) : toneColor(94),
      surfaceAlt: isDark ? toneColor(25) : toneColor(86),
      surfacePanel: isDark ? toneColor(16) : toneColor(92),
      surfaceBright: isDark ? toneColor(40) : toneColor(95),
      onSurface: primary,
      onSurfaceVariant: isDark ? toneColor(70) : toneColor(30),
      outline: isDark ? toneColor(40) : toneColor(60),
      outlineVariant: isDark ? toneColor(25) : toneColor(80),
    };
  }
  // 非全局着色
  const base = isDark ? SOLID_PALETTE_DARK : SOLID_PALETTE_LIGHT;
  return { ...base, ...primaryColors };
};

/** 纯色色板 — 浅色（基于 Zinc 色系，带微弱冷色调） */
export const SOLID_PALETTE_LIGHT: ThemePalette = {
  primary: "24 24 27",
  primaryContainer: "228 228 231",
  onPrimary: "255 255 255",
  onPrimaryContainer: "39 39 42",
  secondary: "82 82 91",
  secondaryContainer: "244 244 245",
  surface: "246 246 246",
  surfaceAlt: "250 250 251",
  surfacePanel: "255 255 255",
  surfaceBright: "255 255 255",
  onSurface: "24 24 27",
  onSurfaceVariant: "113 113 122",
  outline: "212 212 216",
  outlineVariant: "228 228 231",
};

/** 纯色色板 — 深色 */
export const SOLID_PALETTE_DARK: ThemePalette = {
  primary: "244 244 245",
  primaryContainer: "63 63 70",
  onPrimary: "24 24 27",
  onPrimaryContainer: "212 212 216",
  secondary: "161 161 170",
  secondaryContainer: "63 63 70",
  surface: "16 16 20",
  surfaceAlt: "39 39 42",
  surfacePanel: "24 24 28",
  surfaceBright: "72 72 78",
  onSurface: "228 228 231",
  onSurfaceVariant: "161 161 170",
  outline: "82 82 91",
  outlineVariant: "46 46 51",
};

// ============================================================
// 封面取色：LRU 缓存 + 竞态保护 + Worker 异步计算
// ============================================================

/** LRU 缓存上限：重复播放（包括预取/上一首/历史回放）零计算开销 */
const COVER_COLOR_CACHE_MAX = 64;
const coverColorCache = new Map<string, string | null>();

const readCoverColorCache = (url: string): string | null | undefined => {
  const cached = coverColorCache.get(url);
  if (cached === undefined) return undefined;
  // 命中后移到末尾，维持 LRU
  coverColorCache.delete(url);
  coverColorCache.set(url, cached);
  return cached;
};

const writeCoverColorCache = (url: string, data: string | null): void => {
  if (coverColorCache.has(url)) coverColorCache.delete(url);
  coverColorCache.set(url, data);
  while (coverColorCache.size > COVER_COLOR_CACHE_MAX) {
    const oldestKey = coverColorCache.keys().next().value;
    if (oldestKey === undefined) break;
    coverColorCache.delete(oldestKey);
  }
};

/** Worker 超时时间 */
const COVER_COLOR_WORKER_TIMEOUT = 5000;

/**
 * 把主线程重调度到空闲帧执行，避免在切歌首帧阻塞 30-100ms
 */
const runWhenIdle = (cb: () => void): void => {
  const ric = (window as unknown as { requestIdleCallback?: typeof requestIdleCallback })
    .requestIdleCallback;
  if (typeof ric === "function") {
    ric(() => cb(), { timeout: 500 });
  } else {
    setTimeout(cb, 120);
  }
};

/**
 * 从 ImageData 通过 Worker 异步提取主色 HEX
 * Worker 失败时回退到主线程同步计算
 */
const extractColorByWorker = (imageData: ImageData): Promise<string | null> => {
  return new Promise((resolve) => {
    let worker: Worker;
    try {
      worker = new CoverColorWorker();
    } catch {
      // Worker 创建失败，回退主线程
      resolve(extractColorFromImageDataSync(imageData));
      return;
    }

    const cleanupWorker = (): void => {
      window.clearTimeout(timer);
      worker.onmessage = null;
      worker.onerror = null;
      worker.terminate();
    };
    const timer = window.setTimeout(() => {
      cleanupWorker();
      resolve(null);
    }, COVER_COLOR_WORKER_TIMEOUT);

    worker.onmessage = (ev: MessageEvent<CoverColorWorkerResponse>) => {
      cleanupWorker();
      resolve(ev.data.data);
    };
    worker.onerror = () => {
      cleanupWorker();
      // 回退主线程
      resolve(extractColorFromImageDataSync(imageData));
    };

    const buffer = imageData.data.slice().buffer as ArrayBuffer;
    const request: CoverColorWorkerRequest = {
      id: 0,
      buffer,
      width: imageData.width,
      height: imageData.height,
    };
    worker.postMessage(request, [buffer]);
  });
};

/**
 * 主线程同步提取主色（Worker 回退路径）
 */
const extractColorFromImageDataSync = (imageData: ImageData): string | null => {
  const pixels = rgbaPixelsToArgbInts(imageData.data);
  return argbPixelsToCoverHex(pixels);
};

/** 应用取色结果到 store */
const applyCoverColor = (hex: string | null): void => {
  useThemeStore().coverColor = hex;
};

/**
 * 从图片 URL 提取主色并应用（不依赖 DOM 渲染）
 * 适用于启动时组件还未挂载的场景
 * http(s) URL 走主进程取字节构造 blob URL，避免跨域 canvas tainted
 * @param url 封面图片 URL，传 null 清除颜色
 */
export const extractColorFromUrl = (url: string | null): void => {
  const themeStore = useThemeStore();
  if (!url || !useSettingsStore().player.followCoverColor) {
    themeStore.coverColor = null;
    return;
  }
  const requestId = ++coverColorRequestId;

  // 缓存命中：立即应用，跳过图片加载与 quantize 重算
  const cached = readCoverColorCache(url);
  if (cached !== undefined) {
    runWhenIdle(() => {
      if (requestId === coverColorRequestId) applyCoverColor(cached);
    });
    return;
  }

  // http(s) URL 走主进程代理取字节，避免跨域 canvas tainted
  if (/^https?:\/\//i.test(url)) {
    void loadColorFromRemote(url, requestId);
    return;
  }

  // 本地 URL（cache://、blob:、data: 等）直接加载
  // Android 的 file:// / content:// 需先经 Capacitor 代理转换
  const img = new Image();
  img.crossOrigin = "anonymous";
  img.onload = () => {
    runWhenIdle(async () => {
      if (requestId !== coverColorRequestId) return;
      const hex = await extractColorFromImageElement(img);
      writeCoverColorCache(url, hex);
      if (requestId === coverColorRequestId) applyCoverColor(hex);
    });
  };
  img.onerror = () => {
    if (requestId === coverColorRequestId) applyCoverColor(null);
  };
  img.src = resolveCoverUrl(url) ?? url;
};

/** 仅当请求未被新切歌覆盖时写入 store */
const applyIfFresh = (requestId: number, hex: string | null): void => {
  if (requestId === coverColorRequestId) applyCoverColor(hex);
};

/** 跨域封面：主进程拉字节 → blob URL → 同源 canvas 取色 */
const loadColorFromRemote = async (url: string, requestId: number): Promise<void> => {
  try {
    const result = await window.api.system.fetchRemoteBytes(url);
    if (requestId !== coverColorRequestId) return;
    if (!result.success || !result.data) {
      applyIfFresh(requestId, null);
      return;
    }
    const blob = new Blob([new Uint8Array(result.data)]);
    const blobUrl = URL.createObjectURL(blob);
    const img = new Image();
    img.onload = () => {
      runWhenIdle(async () => {
        if (requestId !== coverColorRequestId) {
          URL.revokeObjectURL(blobUrl);
          return;
        }
        const hex = await extractColorFromImageElement(img);
        URL.revokeObjectURL(blobUrl);
        writeCoverColorCache(url, hex);
        applyIfFresh(requestId, hex);
      });
    };
    img.onerror = () => {
      URL.revokeObjectURL(blobUrl);
      applyIfFresh(requestId, null);
    };
    img.src = blobUrl;
  } catch {
    applyIfFresh(requestId, null);
  }
};

/**
 * 从图片 URL 提取主色（用于背景图取色，不起 Worker，无缓存）
 * @returns 主色 HEX 或 null（图片加载失败 / 单调 / 低色度时）
 */
export const extractColorFromImageUrl = (url: string): Promise<string | null> => {
  return new Promise((resolve) => {
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => resolve(extractColorFromImageElementSync(img));
    img.onerror = () => resolve(null);
    img.src = url;
  });
};

/**
 * 从 HTMLImageElement 提取主色 HEX（Worker 异步版）
 * 采样到 COVER_SAMPLE_SIZE×COVER_SAMPLE_SIZE，经 Worker 量化评判
 * @returns 主色 HEX 或 null（单调/低色度时）
 */
const extractColorFromImageElement = async (img: HTMLImageElement): Promise<string | null> => {
  const canvas = document.createElement("canvas");
  canvas.width = COVER_SAMPLE_SIZE;
  canvas.height = COVER_SAMPLE_SIZE;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  if (!ctx) return null;
  ctx.drawImage(
    img,
    0,
    0,
    img.naturalWidth,
    img.naturalHeight,
    0,
    0,
    COVER_SAMPLE_SIZE,
    COVER_SAMPLE_SIZE,
  );
  // 跨域时 CORS 头会污染 canvas，getImageData 抛 SecurityError → 静默放弃提色
  let imageData: ImageData;
  try {
    imageData = ctx.getImageData(0, 0, COVER_SAMPLE_SIZE, COVER_SAMPLE_SIZE);
  } catch {
    canvas.width = 0;
    canvas.height = 0;
    return null;
  }
  // 释放 canvas GPU 资源
  canvas.width = 0;
  canvas.height = 0;
  return extractColorByWorker(imageData);
};

/**
 * 从 HTMLImageElement 同步提取主色 HEX（无 Worker，用于 extractColorFromImageUrl）
 * 沿用上游主线程代表色算法（中心加权 + 彩色占比过滤），避免背景图取色引入 Worker 复杂度
 * @returns 主色 HEX 或 null（单调/低色度时）
 */
const extractColorFromImageElementSync = (img: HTMLImageElement): string | null => {
  const canvas = document.createElement("canvas");
  canvas.width = COVER_SAMPLE_SIZE;
  canvas.height = COVER_SAMPLE_SIZE;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  if (!ctx) return null;
  ctx.drawImage(
    img,
    0,
    0,
    img.naturalWidth,
    img.naturalHeight,
    0,
    0,
    COVER_SAMPLE_SIZE,
    COVER_SAMPLE_SIZE,
  );
  let data: Uint8ClampedArray;
  try {
    data = ctx.getImageData(0, 0, COVER_SAMPLE_SIZE, COVER_SAMPLE_SIZE).data;
  } catch {
    canvas.width = 0;
    canvas.height = 0;
    return null;
  }
  // RGBA → ARGB int
  const pixels: number[] = [];
  for (let y = COVER_EDGE_MARGIN; y < COVER_SAMPLE_SIZE - COVER_EDGE_MARGIN; y++) {
    for (let x = COVER_EDGE_MARGIN; x < COVER_SAMPLE_SIZE - COVER_EDGE_MARGIN; x++) {
      const i = (y * COVER_SAMPLE_SIZE + x) * 4;
      if (data[i + 3] < 16) continue;
      const argb = ((data[i + 3] << 24) | (data[i] << 16) | (data[i + 1] << 8) | data[i + 2]) >>> 0;
      const weight = sampleWeight(x, y);
      for (let j = 0; j < weight; j++) pixels.push(argb);
    }
  }
  if (pixels.length === 0) return null;
  const quantized = QuantizerCelebi.quantize(pixels, 128);
  const picked = pickRepresentativeCoverColor(quantized);
  if (!picked) return null;
  // 释放 canvas GPU 资源
  canvas.width = 0;
  canvas.height = 0;
  return toCoverBaseColor(picked);
};

/**
 * 将色板和封面主色写入 CSS 自定义属性，并切换明暗 class
 */
export const applyThemeToDOM = (
  palette: ThemePalette,
  coverColorHex: string | null,
  isDark: boolean,
): void => {
  const root = document.documentElement;
  for (const [key, value] of Object.entries(palette)) {
    const cssVar = `--s-${key.replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`)}`;
    root.style.setProperty(cssVar, value);
  }
  root.style.setProperty(
    "--s-cover",
    coverColorHex ? hexToRgb(toCoverUiColor(coverColorHex)) : "239 239 239",
  );
  root.style.setProperty("--s-cover-base", coverColorHex ? hexToRgb(coverColorHex) : "20 20 28");
  root.classList.toggle("dark", isDark);
};
