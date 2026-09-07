import type { Collection } from "@/types/collection";
import { useCacheManager } from "@/core/resource/CacheManager";

/**
 * 列表类型
 *
 * - playlist / album / radio：网易云资源详情
 * - streaming-playlist / streaming-album：流媒体歌单/专辑，id 为字符串
 * - home-rec：首页推荐区聚合数据（4 条 API 合一），id 固定 0
 */
export type ListType =
  "playlist" | "album" | "radio" | "streaming-playlist" | "streaming-album" | "home-rec";

/**
 * 列表缓存数据结构
 */
export interface ListCacheData {
  /** 缓存版本号 */
  version: number;
  /** 缓存时间戳 */
  timestamp: number;
  /** 缓存是否完整 */
  complete: boolean;
  /** 列表类型 */
  type: ListType;
  /** 列表 ID（数字 或 字符串，后者供 streaming 场景） */
  id: number | string;
  /** 合集数据（元信息 + 曲目列表） */
  collection: Collection;
}

/** 缓存版本号 */
const CACHE_VERSION = 2; // 因缓存逻辑变更提升版本

/**
 * 列表数据缓存组合式函数
 * 提供列表缓存的读写功能
 */
export const useListDataCache = () => {
  const cacheManager = useCacheManager();

  /**
   * 生成缓存 key
   * @param type 列表类型
   * @param id 列表 ID
   */
  const getCacheKey = (type: ListType, id: number | string): string => {
    return `${type}-${id}.json`;
  };

  /**
   * 保存缓存
   * @param type 列表类型
   * @param id 列表 ID
   * @param collection 合集数据
   * @param complete 缓存是否完整（默认 true）
   */
  const saveCache = async (
    type: ListType,
    id: number | string,
    collection: Collection,
    complete: boolean = true,
  ): Promise<void> => {
    const cacheData: ListCacheData = {
      version: CACHE_VERSION,
      timestamp: Date.now(),
      complete,
      type,
      id,
      collection,
    };

    const key = getCacheKey(type, id);
    const jsonStr = JSON.stringify(cacheData);

    try {
      await cacheManager.set("list-data", key, jsonStr);
      console.log(`✅ List cache saved: ${key}`);
    } catch (error) {
      console.error(`❌ Failed to save list cache: ${key}`, error);
    }
  };

  /**
   * 加载缓存
   * @param type 列表类型
   * @param id 列表 ID
   * @returns 缓存数据，如果不存在或已过期则返回 null
   */
  const loadCache = async (type: ListType, id: number | string): Promise<ListCacheData | null> => {
    const key = getCacheKey(type, id);

    try {
      const result = await cacheManager.get("list-data", key);
      if (!result.success || !result.data) {
        return null;
      }

      // 将 Uint8Array 转换为字符串
      const jsonStr = new TextDecoder().decode(result.data);
      const cacheData: ListCacheData = JSON.parse(jsonStr);

      // 检查版本
      if (cacheData.version !== CACHE_VERSION) {
        console.log(`⚠️ Cache version mismatch: ${key}, removing old cache`);
        await removeCache(type, id);
        return null;
      }

      if (typeof cacheData.complete !== "boolean") {
        cacheData.complete = true;
      }

      console.log(`✅ List cache loaded: ${key}`);
      return cacheData;
    } catch (error) {
      console.error(`❌ Failed to load list cache: ${key}`, error);
      return null;
    }
  };

  /**
   * 检查缓存是否需要更新
   * 通过比较 trackCount 来判断
   * @param cached 缓存数据
   * @param latest 新获取的合集数据
   * @returns 是否需要更新
   */
  const checkNeedsUpdate = (cached: ListCacheData, latest: Collection): boolean => {
    if (cached.collection.trackCount !== undefined && latest.trackCount !== undefined) {
      return cached.collection.trackCount !== latest.trackCount;
    }
    // fallback：比较曲目数量
    return (
      cached.collection.tracks.length !== (latest.trackCount ?? cached.collection.tracks.length)
    );
  };

  /**
   * 删除缓存
   * @param type 列表类型
   * @param id 列表 ID
   */
  const removeCache = async (type: ListType, id: number | string): Promise<void> => {
    const key = getCacheKey(type, id);

    try {
      await cacheManager.remove("list-data", key);
      console.log(`🗑️ List cache removed: ${key}`);
    } catch (error) {
      console.error(`❌ Failed to remove list cache: ${key}`, error);
    }
  };

  /**
   * 清除所有列表缓存
   */
  const clearAllCache = async (): Promise<void> => {
    try {
      await cacheManager.clear("list-data");
      console.log(`🗑️ All list cache cleared`);
    } catch (error) {
      console.error(`❌ Failed to clear list cache`, error);
    }
  };

  return {
    getCacheKey,
    saveCache,
    loadCache,
    checkNeedsUpdate,
    removeCache,
    clearAllCache,
  };
};
