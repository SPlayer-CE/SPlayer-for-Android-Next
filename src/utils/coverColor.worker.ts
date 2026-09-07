/**
 * 封面取色 Worker
 * 将 QuantizerCelebi 量化 + Score 评分等重计算移出主线程，避免切歌首帧阻塞 30-100ms
 * 同时导出算法函数，供主线程回退使用
 */
import {
  themeFromSourceColor,
  QuantizerCelebi,
  Hct,
  Score,
  type Theme,
} from "@material/material-color-utilities";

/** 取色采样尺寸 */
export const COVER_SAMPLE_SIZE = 50;

/** Worker 请求消息 */
export interface CoverColorWorkerRequest {
  id: number;
  buffer: ArrayBuffer;
  width: number;
  height: number;
}

/** Worker 响应消息 */
export interface CoverColorWorkerResponse {
  id: number;
  data: string | null;
  error?: string;
}

/** ARGB 整数转 HEX 字符串 */
const argbToHex = (argb: number): string => {
  const r = (argb >> 16) & 0xff;
  const g = (argb >> 8) & 0xff;
  const b = argb & 0xff;
  return `#${r.toString(16).padStart(2, "0")}${g.toString(16).padStart(2, "0")}${b.toString(16).padStart(2, "0")}`;
};

/** RGBA 像素数组转 ARGB 整数数组 */
export const rgbaPixelsToArgbInts = (data: Uint8ClampedArray): number[] => {
  const len = data.length / 4;
  const out = new Array<number>(len);
  for (let i = 0, p = 0; i < len; i++, p += 4) {
    out[i] = ((data[p + 3] << 24) | (data[p] << 16) | (data[p + 1] << 8) | data[p + 2]) >>> 0;
  }
  return out;
};

/**
 * 从 ARGB 像素数组提取主色 HEX
 * 单调/低彩度封面返回 null
 */
export const argbPixelsToCoverHex = (pixels: number[]): string | null => {
  const quantized = QuantizerCelebi.quantize(pixels, 128);
  const sorted = Array.from(quantized).sort((a, b) => b[1] - a[1]);
  // 单调检测：前 5 色 RGB 分量差值均 < 8 → 灰度图
  const top5 = sorted
    .slice(0, 5)
    .map(([argb]) => [(argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff]);
  if (top5.every((c) => Math.max(...c) - Math.min(...c) < 8)) return null;
  // Score 评分取最佳色
  const ranked = Score.score(new Map(sorted.slice(0, 50)));
  // 彩度检测：scored 色的 chroma 过低说明实际无有效彩色
  const scoredHct = Hct.fromInt(ranked[0]);
  if (scoredHct.chroma < 6) return null;
  // 经 Material 主题提取 secondary 色相后提亮至 tone 90
  const materialTheme: Theme = themeFromSourceColor(ranked[0]);
  const { hue, chroma } = materialTheme.palettes.secondary;
  return argbToHex(Hct.from(hue, chroma, 90).toInt());
};

self.onmessage = (ev: MessageEvent<CoverColorWorkerRequest>) => {
  const { id, buffer, width, height } = ev.data;
  try {
    const expected = width * height * 4;
    if (!buffer || buffer.byteLength !== expected) {
      const resp: CoverColorWorkerResponse = {
        id,
        data: null,
        error: `Invalid buffer size ${buffer?.byteLength ?? 0}, expected ${expected}`,
      };
      self.postMessage(resp);
      return;
    }
    const rgba = new Uint8ClampedArray(buffer);
    const pixels = rgbaPixelsToArgbInts(rgba);
    const data = argbPixelsToCoverHex(pixels);
    const resp: CoverColorWorkerResponse = { id, data };
    self.postMessage(resp);
  } catch (err) {
    const resp: CoverColorWorkerResponse = {
      id,
      data: null,
      error: err instanceof Error ? err.message : String(err),
    };
    self.postMessage(resp);
  }
};
