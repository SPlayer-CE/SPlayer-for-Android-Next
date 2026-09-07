import type { CoverItem } from "@/types/artist";
import { useUserStore } from "@/stores/user";
import { useSettingsStore } from "@/stores/settings";
import { useCacheManager } from "@/core/resource/CacheManager";
import { isAndroid } from "@/services/bridge";
import { prefetchListCovers } from "@/composables/useCoverCache";
import {
  fetchRecommendPlaylists,
  fetchRadarPlaylists,
  fetchArtists,
  fetchNewAlbums,
} from "@/apis/recommend/netease";

/** 首页推荐内容缓存有效期 */
const CACHE_TTL = 30 * 60 * 1000;

/** 首页推荐内容缓存 */
interface DiscoverCache {
  at: number;
  loggedIn: boolean;
  recommend: CoverItem[];
  radar: CoverItem[];
  artists: CoverItem[];
  albums: CoverItem[];
}

/** 模块级缓存，跨页面 / 重新挂载复用 */
let cache: DiscoverCache | null = null;

/** 包裹拉取：失败静默回退空数组，单区块失败不影响整体 */
const safe = (_label: string, task: Promise<CoverItem[]>): Promise<CoverItem[]> =>
  task.catch(() => []);

/**
 * 首页推荐内容
 *
 * 聚合「推荐歌单 / 雷达 / 歌手 / 新碟」四个区块，统一拉取与缓存
 * 命中缓存（30 分钟内、登录态一致）直接复用，避免重新挂载首页时重复请求
 */
export const useHomeDiscover = () => {
  const { t } = useI18n();
  const user = useUserStore();

  /** 推荐歌单 / 专属歌单 */
  const recommendPlaylists = shallowRef<CoverItem[]>([]);
  /** 雷达歌单 */
  const radarPlaylists = shallowRef<CoverItem[]>([]);
  /** 歌手推荐 */
  const artists = shallowRef<CoverItem[]>([]);
  /** 新碟上架 */
  const newAlbums = shallowRef<CoverItem[]>([]);

  /** 推荐歌单标题 */
  const recommendTitle = computed(() =>
    user.isLoggedIn ? t("home.recommend.title") : t("home.recommend.titleGuest"),
  );
  /** 推荐歌单副标题 */
  const recommendSubtitle = computed(() =>
    user.isLoggedIn ? t("home.recommend.subtitle") : t("home.recommend.subtitleGuest"),
  );

  /** 用缓存填充各区块 */
  const apply = (data: DiscoverCache): void => {
    recommendPlaylists.value = data.recommend;
    radarPlaylists.value = data.radar;
    artists.value = data.artists;
    newAlbums.value = data.albums;
  };

  /** 拉取首页推荐内容 */
  const load = async (): Promise<void> => {
    const loggedIn = user.isLoggedIn;
    const cacheKey = `home-rec-${loggedIn ? (user.profile?.userId ?? "user") : "guest"}.json`;
    if (cache && cache.loggedIn === loggedIn && Date.now() - cache.at < CACHE_TTL) {
      apply(cache);
      return;
    }
    const cacheEnabled = isAndroid && useSettingsStore().system.cache?.enabled === true;
    let diskCache: DiscoverCache | null = null;
    if (cacheEnabled) {
      try {
        const result = await useCacheManager().get("list-data", cacheKey);
        if (result.success && result.data) {
          const data = JSON.parse(new TextDecoder().decode(result.data)) as DiscoverCache;
          if (
            Array.isArray(data.recommend) &&
            Array.isArray(data.radar) &&
            Array.isArray(data.artists) &&
            Array.isArray(data.albums)
          ) {
            diskCache = data;
          }
        }
      } catch {
        diskCache = null;
      }
      if (diskCache && diskCache.loggedIn === loggedIn) {
        cache = diskCache;
        apply(diskCache);
        prefetchListCovers(diskCache.recommend, "list-covers", 20, "m");
        prefetchListCovers(diskCache.radar, "list-covers", 20, "m");
        prefetchListCovers(diskCache.artists, "list-covers", 20, "m");
        prefetchListCovers(diskCache.albums, "list-covers", 20, "m");
        if (Date.now() - diskCache.at < CACHE_TTL) return;
      }
    }
    const [recommend, radar, artistList, albums] = await Promise.all([
      safe("recommend playlists", fetchRecommendPlaylists(loggedIn)),
      loggedIn ? safe("radar playlists", fetchRadarPlaylists()) : Promise.resolve<CoverItem[]>([]),
      safe("artists", fetchArtists()),
      safe("new albums", fetchNewAlbums()),
    ]);
    const next = { at: Date.now(), loggedIn, recommend, radar, artists: artistList, albums };
    if (
      diskCache &&
      next.recommend.length + next.radar.length + next.artists.length + next.albums.length === 0
    ) {
      return;
    }
    cache = next;
    apply(cache);
    if (cacheEnabled) {
      useCacheManager()
        .set("list-data", cacheKey, JSON.stringify(cache))
        .catch(() => {});
      prefetchListCovers(cache.recommend, "list-covers", 20, "m");
      prefetchListCovers(cache.radar, "list-covers", 20, "m");
      prefetchListCovers(cache.artists, "list-covers", 20, "m");
      prefetchListCovers(cache.albums, "list-covers", 20, "m");
    }
  };

  // 登录态变化
  watch(
    () => user.isLoggedIn,
    () => {
      void load();
    },
  );

  return {
    recommendPlaylists,
    recommendTitle,
    recommendSubtitle,
    radarPlaylists,
    artists,
    newAlbums,
    load,
  };
};
