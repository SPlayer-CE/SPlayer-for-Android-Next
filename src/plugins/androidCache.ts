import { registerPlugin } from "@capacitor/core";

/** 缓存类型与 Java 端 CacheStorage.TYPE_* 保持一致 */
export type AndroidCacheType = "lyrics" | "covers" | "list-covers" | "list-data" | "exo";

export interface AndroidCacheReadResult {
  hit: boolean;
  /** base64 编码的二进制数据；hit=false 时不存在 */
  data?: string;
  size?: number;
}

export interface AndroidCacheWriteResult {
  success: boolean;
  message?: string;
}

export interface AndroidCacheListEntry {
  key: string;
  size: number;
  /** 毫秒时间戳 */
  mtime: number;
}

export interface AndroidCacheStats {
  totalBytes: number;
  /** 设备剩余可用字节（StatFs.getAvailableBytes()）；-1 表示读取失败 */
  deviceFreeBytes: number;
  /** 用户设定的原始上限（不含设备容量自适应） */
  maxBytes: number;
  /**
   * 运行期有效上限：设备空间不足时会自动降到 `deviceFreeBytes * 0.6`，
   * 高于用户设定时仍以用户设定为准。enforceLimit 走的就是此值。
   */
  effectiveMaxBytes: number;
  /** 缓存根目录绝对路径，供 UI 展示 */
  cacheDir: string;
  /** 各类型分项占用 */
  perType: Record<AndroidCacheType, number>;
  /** db 类别缓存占用（lyric_cache / lyric_ttml_cache / lyric_match_cache） */
  dbStats?: {
    lyric: number;
    lyricTTML: number;
    lyricMatch: number;
  };
}

/** db 缓存类别 id */
export type AndroidDbCacheCategory = "lyric" | "lyricTTML" | "lyricMatch";

export interface AndroidCachePlugin {
  read(options: { type: AndroidCacheType; key: string }): Promise<AndroidCacheReadResult>;
  /** data 必须是 base64 编码的字符串 */
  write(options: {
    type: AndroidCacheType;
    key: string;
    data: string;
  }): Promise<AndroidCacheWriteResult>;
  remove(options: { type: AndroidCacheType; key: string }): Promise<{ success: boolean }>;
  list(options: { type: AndroidCacheType }): Promise<{ entries: AndroidCacheListEntry[] }>;
  clear(options: { type: AndroidCacheType }): Promise<{ success: boolean }>;
  clearAll(): Promise<{ success: boolean }>;
  /** 清空指定 db 类别缓存（lyric / lyricTTML / lyricMatch） */
  clearDbCache(options: { category: AndroidDbCacheCategory }): Promise<{ success: boolean }>;
  /** 一键清空全部 db 类别缓存 */
  clearAllDbCache(): Promise<{ success: boolean }>;
  getStats(): Promise<AndroidCacheStats>;
  /** 返回缓存根目录绝对路径 */
  getDir(): Promise<{ dir: string }>;
  setMaxBytes(options: {
    maxBytes: number;
  }): Promise<{ success: boolean; appliedMaxBytes: number }>;
  enforceLimit(): Promise<{ success: boolean; totalBytes: number }>;
}

export const AndroidCache = registerPlugin<AndroidCachePlugin>("AndroidCache");
