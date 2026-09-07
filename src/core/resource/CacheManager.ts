import { AndroidCache, type AndroidCacheType } from "@/plugins/androidCache";

/**
 * 缓存资源类型（仅 Android）
 * - lyrics: 歌词缓存
 * - list-data: 列表数据缓存（歌单 / 专辑 / 电台 / 推荐 等 metadata JSON）
 * - covers: 歌曲封面（缓存原始字节，规避 MediaStore 扫描）
 * - list-covers: 列表封面
 * - music: 音频缓存（业务侧通常无需直接读写；ExoPlayer SimpleCache 自治；保留方便清理 / 统计）
 */
export type CacheResourceType = "lyrics" | "list-data" | "covers" | "list-covers" | "music";

export type CacheListItem = {
  /** 缓存 key */
  key: string;
  /** 字节大小 */
  size: number;
  /** 最后修改毫秒时间戳 */
  mtime: number;
};

export type CacheResult<T = any> = {
  success: boolean;
  data?: T;
  message?: string;
};

type CacheWriteData = Uint8Array | ArrayBuffer | string;

const mapType = (type: CacheResourceType): AndroidCacheType => {
  if (type === "music") return "exo";
  return type;
};

const toBase64 = (data: CacheWriteData): string => {
  let bytes: Uint8Array;
  if (typeof data === "string") bytes = new TextEncoder().encode(data);
  else if (data instanceof ArrayBuffer) bytes = new Uint8Array(data);
  else bytes = data;
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode.apply(null, Array.from(bytes.subarray(i, i + chunk)));
  }
  return btoa(binary);
};

const fromBase64 = (b64: string): Uint8Array => {
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
};

/**
 * Android 端缓存管理器（项目仅安卓运行）。
 *
 * <p>所有读写走 AndroidCache Capacitor 插件 → CacheStorage / SimpleCache。
 * 业务侧统一调用 useCacheManager()，无需关心平台差异。
 */
class CacheManager {
  /** 始终可用：进程在 Capacitor Android 上运行；保留接口避免业务侧大改。 */
  isAvailable(): boolean {
    return true;
  }

  async list(type: CacheResourceType): Promise<CacheResult<CacheListItem[]>> {
    try {
      const r = await AndroidCache.list({ type: mapType(type) });
      return { success: true, data: r.entries };
    } catch (e: any) {
      return { success: false, message: String(e?.message ?? e) };
    }
  }

  async get(type: CacheResourceType, key: string): Promise<CacheResult<Uint8Array>> {
    try {
      const r = await AndroidCache.read({ type: mapType(type), key });
      if (!r.hit || !r.data) return { success: false, message: "miss" };
      return { success: true, data: fromBase64(r.data) };
    } catch (e: any) {
      return { success: false, message: String(e?.message ?? e) };
    }
  }

  async set(
    type: CacheResourceType,
    key: string,
    data: CacheWriteData,
  ): Promise<CacheResult<null>> {
    try {
      const r = await AndroidCache.write({ type: mapType(type), key, data: toBase64(data) });
      return r.success ? { success: true, data: null } : { success: false, message: r.message };
    } catch (e: any) {
      return { success: false, message: String(e?.message ?? e) };
    }
  }

  async remove(type: CacheResourceType, key: string): Promise<CacheResult<null>> {
    const r = await AndroidCache.remove({ type: mapType(type), key });
    return { success: r.success, data: null };
  }

  async clear(type: CacheResourceType): Promise<CacheResult<null>> {
    const r = await AndroidCache.clear({ type: mapType(type) });
    return { success: r.success, data: null };
  }

  async clearAll(): Promise<CacheResult<null>> {
    const r = await AndroidCache.clearAll();
    return { success: r.success, data: null };
  }

  async getSize(): Promise<CacheResult<number>> {
    try {
      const stats = await AndroidCache.getStats();
      return { success: true, data: stats.totalBytes };
    } catch (e: any) {
      return { success: false, message: String(e?.message ?? e) };
    }
  }

  async getStats(): Promise<
    CacheResult<{
      totalBytes: number;
      deviceFreeBytes: number;
      maxBytes: number;
      /** 设备空间不足时的运行期生效上限（min(maxBytes, deviceFreeBytes * 0.6)） */
      effectiveMaxBytes: number;
      perType: Partial<Record<CacheResourceType, number>>;
    }>
  > {
    try {
      const stats = await AndroidCache.getStats();
      return {
        success: true,
        data: {
          totalBytes: stats.totalBytes,
          deviceFreeBytes: stats.deviceFreeBytes,
          maxBytes: stats.maxBytes,
          // 老版本 native 端无此字段时 fallback 到 maxBytes（向前兼容）
          effectiveMaxBytes: stats.effectiveMaxBytes ?? stats.maxBytes,
          // exo 子目录映射回业务类型 music
          perType: {
            lyrics: stats.perType.lyrics,
            covers: stats.perType.covers,
            "list-covers": stats.perType["list-covers"],
            "list-data": stats.perType["list-data"],
            music: stats.perType.exo,
          },
        },
      };
    } catch (e: any) {
      return { success: false, message: String(e?.message ?? e) };
    }
  }

  async setMaxBytes(maxBytes: number): Promise<CacheResult<{ appliedMaxBytes: number }>> {
    try {
      const r = await AndroidCache.setMaxBytes({ maxBytes });
      return { success: r.success, data: { appliedMaxBytes: r.appliedMaxBytes } };
    } catch (e: any) {
      return { success: false, message: String(e?.message ?? e) };
    }
  }

  async enforceLimit(): Promise<CacheResult<{ totalBytes: number }>> {
    try {
      const r = await AndroidCache.enforceLimit();
      return { success: r.success, data: { totalBytes: r.totalBytes } };
    } catch (e: any) {
      return { success: false, message: String(e?.message ?? e) };
    }
  }
}

let cacheManager: CacheManager | null = null;

/** 获取全局单例的缓存管理器。 */
export const useCacheManager = (): CacheManager => {
  if (!cacheManager) cacheManager = new CacheManager();
  return cacheManager;
};
