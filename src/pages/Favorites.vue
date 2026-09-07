<script setup lang="ts">
defineOptions({ name: "Favorites" });

import type { Album, Artist, Playlist, Track } from "@shared/types/player";
import type { CoverItem } from "@/types/artist";
import type { UserRadioFavorite, UserVideoFavorite } from "@/types/user";
import { useUserStore } from "@/stores/user";
import { useLibraryStore } from "@/stores/library";
import { albumsToCoverItems, artistsToCoverItems } from "@/utils/format/coverItem";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import { toast } from "@/composables/useToast";
import CoverList from "@/components/list/CoverList.vue";
import SongList from "@/components/list/SongList.vue";
import IconLucideDisc3 from "~icons/lucide/disc-3";
import IconLucideListMusic from "~icons/lucide/list-music";
import IconLucidePodcast from "~icons/lucide/podcast";
import IconLucideUser from "~icons/lucide/user";
import IconLucideVideo from "~icons/lucide/video";
import IconLucideMusic from "~icons/lucide/music";
import IconMaterialSymbolsFavoriteOutline from "~icons/material-symbols/favorite-outline-rounded";
import IconLucideSearch from "~icons/lucide/search";
import IconLucideChevronDown from "~icons/lucide/chevron-down";

const { useMobileLayout } = useResponsiveLayout();
const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const user = useUserStore();
const library = useLibraryStore();

/** 搜索关键词 */
const searchQuery = ref("");
const searchFocused = ref(false);

const closeSearchInput = (): void => {
  const activeElement = document.activeElement;
  if (activeElement instanceof HTMLElement) activeElement.blur();
  searchFocused.value = false;
};

type FavTab = "liked" | "playlist" | "album" | "artist" | "video" | "radio";
type PlaylistGroup = "created" | "subscribed";

const TAB_KEYS: readonly FavTab[] = ["liked", "album", "artist", "video", "playlist", "radio"];
const PLAYLIST_GROUP_KEYS: readonly PlaylistGroup[] = ["created", "subscribed"];

/** 当前 tab */
const activeTab = computed<FavTab>(() => {
  const tab = route.query.tab;
  return typeof tab === "string" && (TAB_KEYS as readonly string[]).includes(tab)
    ? (tab as FavTab)
    : "liked";
});

/** 当前歌单分组（自建为空时自动切到收藏） */
const playlistGroup = computed<PlaylistGroup>(() => {
  const group = route.query.group;
  const parsed =
    typeof group === "string" && (PLAYLIST_GROUP_KEYS as readonly string[]).includes(group)
      ? (group as PlaylistGroup)
      : "created";
  if (
    parsed === "created" &&
    createdPlaylistItems.value.length === 0 &&
    subscribedPlaylistItems.value.length > 0
  ) {
    return "subscribed";
  }
  return parsed;
});

const onTabSwitch = (key: string): void => {
  if (key === "liked") {
    router.replace({ query: { ...route.query, tab: key, group: undefined } });
  } else if (key === "playlist") {
    router.replace({ query: { ...route.query, tab: key } });
  } else {
    router.replace({ query: { ...route.query, tab: key, group: undefined } });
  }
};

const onPlaylistGroupSwitch = (key: string): void => {
  router.replace({ query: { ...route.query, tab: "playlist", group: key } });
};

const tabs = computed(() => [
  { key: "liked" satisfies FavTab, label: t("favorites.tabs.liked") },
  { key: "album" satisfies FavTab, label: t("favorites.tabs.album") },
  { key: "artist" satisfies FavTab, label: t("favorites.tabs.artist") },
  { key: "video" satisfies FavTab, label: t("favorites.tabs.video") },
  { key: "playlist" satisfies FavTab, label: t("favorites.tabs.playlist") },
  { key: "radio" satisfies FavTab, label: t("favorites.tabs.radio") },
]);

const playlistGroups = computed(() => [
  { key: "created" satisfies PlaylistGroup, label: t("favorites.playlistGroups.created") },
  { key: "subscribed" satisfies PlaylistGroup, label: t("favorites.playlistGroups.subscribed") },
]);

const formatBadgeCount = (count?: number): string | undefined => {
  if (!count || count <= 0) return undefined;
  if (count >= 100000000) return `${Math.floor(count / 10000000) / 10}亿`;
  if (count >= 10000) return `${Math.floor(count / 1000) / 10}万`;
  return String(count);
};

const playlistToItem = (playlist: Playlist): CoverItem => ({
  id: playlist.id ?? "",
  title: playlist.name,
  cover: playlist.cover,
  subtitle: playlist.owner ?? user.profile?.nickname ?? "",
  trackCount: playlist.trackCount ?? 0,
  badge: formatBadgeCount(playlist.trackCount),
});

const videoToItem = (video: UserVideoFavorite): CoverItem => ({
  id: video.id,
  title: video.name,
  cover: video.cover,
  subtitle: video.artist ?? "",
  trackCount: 0,
  aspect: "video",
  badge: formatBadgeCount(video.playCount),
});

const radioToItem = (radio: UserRadioFavorite): CoverItem => ({
  id: radio.id,
  title: radio.name,
  cover: radio.cover,
  subtitle: radio.creator ?? "",
  trackCount: radio.programCount ?? 0,
  badge: formatBadgeCount(radio.programCount ?? radio.subCount),
});

const createdPlaylistItems = computed<CoverItem[]>(() =>
  user.createdPlaylists.slice(1).map(playlistToItem),
);

const subscribedPlaylistItems = computed<CoverItem[]>(() =>
  user.subscribedPlaylists.map(playlistToItem),
);

const playlistItems = computed<CoverItem[]>(() =>
  playlistGroup.value === "created" ? createdPlaylistItems.value : subscribedPlaylistItems.value,
);

const albumItems = computed<CoverItem[]>(() => albumsToCoverItems(user.albums as Album[]));

const artistItems = computed<CoverItem[]>(() => artistsToCoverItems(user.artists as Artist[]));

const videoItems = computed<CoverItem[]>(() => user.mvs.map(videoToItem));

const radioItems = computed<CoverItem[]>(() => user.djs.map(radioToItem));

/** liked tab: 在线优先，未登录回退本地 */
const likedTracks = computed<Track[]>(() => {
  if (user.isLoggedIn && user.likedPlaylistTracks.length > 0) return user.likedPlaylistTracks;
  const byId = new Map<string, Track>(library.tracks.map((track) => [track.id, track]));
  const list: Track[] = [];
  for (const id of library.likedOrderedIds) {
    const track = byId.get(id);
    if (track) list.push(track);
  }
  return list;
});

const likedTrackCount = computed(() => likedTracks.value.length);

const currentItems = computed<CoverItem[]>(() => {
  switch (activeTab.value) {
    case "album":
      return albumItems.value;
    case "artist":
      return artistItems.value;
    case "video":
      return videoItems.value;
    case "radio":
      return radioItems.value;
    case "playlist":
    default:
      return playlistItems.value;
  }
});

const countMeta = computed(() => {
  switch (activeTab.value) {
    case "liked":
      return {
        icon: IconLucideMusic,
        text: t("common.totalSongs", { count: likedTrackCount.value }),
      };
    case "album":
      return {
        icon: IconLucideDisc3,
        text: t("common.totalAlbums", { count: albumItems.value.length }),
      };
    case "artist":
      return {
        icon: IconLucideUser,
        text: t("common.totalArtists", { count: artistItems.value.length }),
      };
    case "video":
      return {
        icon: IconLucideVideo,
        text: t("favorites.totalVideos", { count: videoItems.value.length }),
      };
    case "radio":
      return {
        icon: IconLucidePodcast,
        text: t("favorites.totalRadios", { count: radioItems.value.length }),
      };
    case "playlist":
    default:
      return {
        icon: IconLucideListMusic,
        text: t("common.totalPlaylists", { count: playlistItems.value.length }),
      };
  }
});

const listType = computed(() => (activeTab.value === "artist" ? "artist" : "default"));
const listMinSize = computed(() => {
  if (activeTab.value === "artist") return useMobileLayout.value ? 112 : 120;
  if (activeTab.value === "video") return useMobileLayout.value ? 150 : 220;
  return useMobileLayout.value ? 132 : 140;
});

const emptyText = computed(() => {
  if (activeTab.value === "liked")
    return user.isLoggedIn ? t("liked.empty.online") : t("liked.needLogin");
  if (activeTab.value === "video") return t("favorites.emptyVideo");
  if (activeTab.value === "radio") return t("favorites.emptyRadio");
  return t("favorites.empty");
});

const handleClick = (item: CoverItem): void => {
  switch (activeTab.value) {
    case "artist":
      router.push(`/artist/netease/${encodeURIComponent(item.id)}`);
      break;
    case "album":
    case "playlist":
      router.push(`/collection/netease/${activeTab.value}/${encodeURIComponent(item.id)}`);
      break;
    case "video":
      toast.info(t("favorites.unsupportedVideo"));
      break;
    case "radio":
      toast.info(t("favorites.unsupportedRadio"));
      break;
  }
};

// 直进 /favorites?tab=liked 时本地库可能未初始化，需手动触发；同时拉取在线喜欢歌单
onMounted(() => {
  if (!library.initialized)
    library.load().catch((err) => console.warn("[Favorites] library load failed:", err));
  if (user.isLoggedIn) {
    user
      .ensureLikedPlaylist()
      .catch((err) => console.warn("[Favorites] ensureLikedPlaylist failed:", err));
    if (user.playlists.length === 0)
      user
        .loadContent(user.profile!.userId)
        .catch((err) => console.warn("[Favorites] loadContent failed:", err));
  }
});
</script>

<template>
  <div class="flex flex-col h-full">
    <!-- 顶栏 -->
    <div class="shrink-0 pb-2" :class="useMobileLayout ? 'px-4' : 'px-5'">
      <div
        class="flex items-center gap-4 min-w-0"
        :class="useMobileLayout ? 'mt-1 mb-3' : 'mt-2 mb-4'"
      >
        <div class="flex items-baseline gap-4 min-w-0">
          <h1
            class="font-bold text-on-surface shrink-0 text-balance"
            :class="useMobileLayout ? 'text-2xl' : 'text-3xl'"
          >
            {{ t("favorites.title") }}
          </h1>
          <Transition name="fade" mode="out-in">
            <span
              v-if="activeTab === 'liked' || user.isLoggedIn"
              :key="`${activeTab}-${playlistGroup}`"
              class="flex items-center gap-1.5 text-sm text-on-surface-variant/50 truncate"
            >
              <component :is="countMeta.icon" class="size-3.5 shrink-0" />
              {{ countMeta.text }}
            </span>
          </Transition>
        </div>
        <div class="ml-auto relative h-9 w-40 shrink-0">
          <SInput
            v-model="searchQuery"
            :placeholder="t('common.search')"
            clearable
            round
            class="absolute right-0 top-0 w-40 focus-within:w-56 focus-within:z-10 focus-within:backdrop-blur-lg focus-within:bg-surface/80 focus-within:shadow-lg"
            @focus="searchFocused = true"
            @blur="searchFocused = false"
          >
            <template #prefix>
              <IconLucideSearch class="size-4 text-on-surface-variant/40 shrink-0" />
            </template>
            <template v-if="searchFocused" #suffix>
              <button
                type="button"
                class="size-7 shrink-0 inline-flex items-center justify-center rounded-full border-none bg-transparent appearance-none cursor-pointer text-on-surface-variant/70 transition-[color,background-color] duration-200 hover:bg-on-surface/10 hover:text-on-surface active:bg-on-surface/16"
                :aria-label="t('common.close')"
                @pointerdown.prevent.stop
                @click.stop="closeSearchInput"
              >
                <IconLucideChevronDown class="size-4.5" />
              </button>
            </template>
          </SInput>
        </div>
      </div>
      <div
        :class="[
          useMobileLayout
            ? 'w-full min-w-0 overflow-x-auto [&::-webkit-scrollbar]:hidden -mx-4 px-4'
            : 'w-[30rem]',
        ]"
      >
        <STabs
          :model-value="activeTab"
          :tabs="tabs"
          :type="useMobileLayout ? 'bar' : 'segment'"
          round
          @update:model-value="onTabSwitch"
        />
      </div>
      <Transition name="fade" mode="out-in">
        <div v-if="activeTab === 'playlist' && user.isLoggedIn" class="mt-3 w-fit max-w-full">
          <STabs
            :model-value="playlistGroup"
            :tabs="playlistGroups"
            type="segment"
            size="small"
            round
            @update:model-value="onPlaylistGroupSwitch"
          />
        </div>
      </Transition>
    </div>
    <!-- 未登录（liked tab 有本地数据回退，但提示登录可看在线） -->
    <div
      v-if="activeTab !== 'liked' && !user.isLoggedIn"
      class="flex-1 flex items-center justify-center"
    >
      <div class="text-center text-on-surface-variant/60">
        <IconMaterialSymbolsFavoriteOutline class="size-12 mx-auto mb-3 opacity-30" />
        <div class="text-sm">{{ t("favorites.notLogin") }}</div>
      </div>
    </div>
    <!-- 内容 -->
    <Transition
      v-if="activeTab === 'liked' || user.isLoggedIn"
      name="fade"
      mode="out-in"
      :duration="150"
    >
      <!-- liked tab: 歌曲列表 -->
      <div v-if="activeTab === 'liked'" key="liked" class="flex-1 min-h-0">
        <div v-if="likedTrackCount > 0" class="flex-1 min-h-0 h-full">
          <SongList
            :items="likedTracks"
            :show-size="false"
            :source="user.isLoggedIn ? 'netease' : 'local'"
            :search-query="searchQuery"
            enable-sort
          />
        </div>
        <div v-else class="flex-1 flex items-center justify-center h-full">
          <div class="text-center text-on-surface-variant/50">
            <IconMaterialSymbolsFavoriteOutline class="size-12 mx-auto mb-3 opacity-30" />
            <div class="text-sm">{{ emptyText }}</div>
          </div>
        </div>
      </div>
      <!-- cover 类 tab -->
      <div
        v-else-if="currentItems.length > 0"
        :key="`${activeTab}-${playlistGroup}`"
        class="flex-1 min-h-0"
      >
        <CoverList
          :items="currentItems"
          :type="listType"
          :virtual="activeTab !== 'video'"
          :min-size="listMinSize"
          :gap="useMobileLayout ? 14 : 20"
          :padding-x="useMobileLayout ? 16 : 20"
          :padding-top="8"
          :padding-bottom="20"
          @click="handleClick"
        />
      </div>
      <div v-else key="empty" class="flex-1 flex items-center justify-center">
        <div class="text-center text-on-surface-variant/50">
          <IconMaterialSymbolsFavoriteOutline class="size-12 mx-auto mb-3 opacity-30" />
          <div class="text-sm">{{ emptyText }}</div>
        </div>
      </div>
    </Transition>
  </div>
</template>
