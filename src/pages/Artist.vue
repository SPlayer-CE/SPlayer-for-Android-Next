<script setup lang="ts">
import type { PlaybackContext, TrackSource } from "@shared/types/player";
import type { ArtistProfile, CoverItem } from "@/types/artist";
import { useSettingsStore } from "@/stores/settings";
import { useUserStore } from "@/stores/user";
import { toast } from "@/composables/useToast";
import { loadArtist as loadArtistService } from "@/services/artistLoader";
import { fetchArtistSongs } from "@/apis/artist/netease";
import { fetchQQMusicArtistSongs } from "@/apis/artist/qqmusic";
import { navigateToAlbum } from "@/utils/navigate";
import SongList from "@/components/list/SongList.vue";
import { formatTime } from "@/utils/time";
import * as player from "@/core/player";
import artistFallback from "@/assets/images/artist.jpg";
import IconLucideDisc3 from "~icons/lucide/disc-3";
import IconLucideListMusic from "~icons/lucide/list-music";
import IconLucideHourglass from "~icons/lucide/hourglass";
import IconLucideMusic from "~icons/lucide/music";
import type { DropdownMenuItem } from "@/components/ui/SDropdownMenu.vue";
import IconLucideListChecks from "~icons/lucide/list-checks";
import IconLucideChevronDown from "~icons/lucide/chevron-down";
import IconMaterialSymbolsFavoriteRounded from "~icons/material-symbols/favorite-rounded";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import IconMaterialSymbolsFavoriteOutlineRounded from "~icons/material-symbols/favorite-outline-rounded";

const { useMobileLayout } = useResponsiveLayout();
const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const { appearance } = useSettingsStore();
const userStore = useUserStore();

const tabTransitionName = computed(() => {
  const transition = appearance.routeTransition;
  return transition === "none" ? "" : `route-${transition}`;
});

const source = route.params.source as TrackSource;
const id = route.params.id as string;

const artist = shallowRef<ArtistProfile | null>(null);
/** 正在加载 */
const loading = ref(false);
/** 错误信息 */
const error = ref("");
/** 取消当次加载 */
let loadAbort: AbortController | null = null;
/** 是否还有更多 */
const hasMoreSongs = ref(false);
const loadingMore = ref(false);

/** 在线头像未到位时，用任一曲目的封面顶替 */
const fallbackTrackCover = computed(() => artist.value?.tracks.find((t) => t.cover)?.cover);

/** 折叠状态 */
const collapsed = ref(false);

const handleListScroll = (event: Event) => {
  const scrollTop = (event.target as HTMLElement).scrollTop;
  if (!collapsed.value && scrollTop > 10) {
    collapsed.value = true;
  } else if (collapsed.value && scrollTop === 0) {
    collapsed.value = false;
  }
};

/** 加载数据 */
const loadArtist = async (): Promise<void> => {
  collapsed.value = false;
  loadAbort?.abort();
  const myAbort = new AbortController();
  loadAbort = myAbort;
  loading.value = true;
  error.value = "";
  hasMoreSongs.value = false;

  try {
    await loadArtistService(source, id, {
      fallbackName: typeof route.query.name === "string" ? route.query.name : undefined,
      signal: myAbort.signal,
      onUpdate: (next) => {
        if (myAbort.signal.aborted) return;
        artist.value = next;
        if (
          next &&
          ((source === "netease" && next.tracks.length >= 50) ||
            (source === "qqmusic" && next.tracks.length < next.trackCount))
        ) {
          hasMoreSongs.value = true;
        }
      },
    });
  } catch (err) {
    if (myAbort.signal.aborted) return;
    error.value = err instanceof Error ? err.message : String(err);
  } finally {
    if (!myAbort.signal.aborted) loading.value = false;
  }
};

/** 触底加载 */
const onReachBottom = async (): Promise<void> => {
  if (
    (source !== "netease" && source !== "qqmusic") ||
    !hasMoreSongs.value ||
    loadingMore.value ||
    !artist.value
  )
    return;
  const current = artist.value;
  loadingMore.value = true;
  try {
    const { tracks, more } =
      source === "qqmusic"
        ? await fetchQQMusicArtistSongs(decodeURIComponent(id), current.tracks.length)
        : await fetchArtistSongs(decodeURIComponent(id), current.tracks.length);
    if (loadAbort?.signal.aborted || artist.value?.id !== current.id) return;
    if (tracks.length === 0) {
      hasMoreSongs.value = false;
      return;
    }
    artist.value = {
      ...current,
      tracks: [...current.tracks, ...tracks],
      trackCount: source === "qqmusic" ? current.trackCount : current.tracks.length + tracks.length,
    };
    hasMoreSongs.value = more;
  } finally {
    loadingMore.value = false;
  }
};

loadArtist();

onBeforeUnmount(() => {
  loadAbort?.abort();
  document.removeEventListener("pointerdown", onDocumentPointerDown, true);
});

/** 总时长 */
const totalDuration = computed(() => {
  if (!artist.value) return "";
  const total = artist.value.tracks.reduce((sum, t) => sum + t.duration, 0);
  return total > 0 ? formatTime(total) : "";
});

const playbackContext = computed<PlaybackContext>(() => ({
  provider: source,
  originId: decodeURIComponent(id),
  originType: "artist",
  originName: artist.value?.name,
}));

const handlePlayAll = () => {
  if (!artist.value?.tracks.length) return;
  player.playFrom(artist.value.tracks, 0, playbackContext.value);
};

/** 收藏歌手仅支持网易云 */
const canSubscribeArtist = computed(() => artist.value?.source === "netease");

/** 当前歌手是否已收藏（依据用户收藏歌手列表） */
const isArtistSubscribed = computed(() => {
  const current = artist.value;
  if (!current || current.source !== "netease") return false;
  return userStore.artists.some((item) => String(item.id) === String(current.id));
});

/** 收藏操作进行中 */
const artistSubBusy = ref(false);

const handleToggleSubscribe = async (): Promise<void> => {
  const current = artist.value;
  if (!current || current.source !== "netease" || artistSubBusy.value) return;
  artistSubBusy.value = true;
  try {
    await userStore.toggleArtistSubscribe(current.id, !isArtistSubscribed.value);
  } catch (err) {
    toast.error(err instanceof Error && err.message ? err.message : t("liked.toast.failed"));
  } finally {
    artistSubBusy.value = false;
  }
};

const searchQuery = ref("");
const mobileSearchExpanded = ref(false);
const mobileSearchWrapperRef = ref<HTMLElement | null>(null);
const mobileSearchInputRef = ref<{ focus: () => void } | null>(null);

const mobileSearchStyle = computed(() => ({
  width: mobileSearchExpanded.value ? "calc(var(--page-zoom-100vw, 100vw) - 32px)" : "100%",
  left: "0",
  height: "2.75rem",
}));

/** 歌曲列表引用 */
const songListRef = shallowRef<InstanceType<typeof SongList> | null>(null);

/** 更多菜单 */
const moreMenuItems = computed<DropdownMenuItem[]>(() => [
  { key: "batchManage", label: t("songList.batch.manage"), icon: IconLucideListChecks },
]);

const handleMoreMenu = (key: string) => {
  if (key === "batchManage") songListRef.value?.enterBatch();
};

type ArtistTab = "songs" | "albums";

const ARTIST_TAB_KEYS: readonly ArtistTab[] = ["songs", "albums"];

/** 当前 tab */
const activeTab = computed<ArtistTab>(() => {
  const tab = route.query.tab;
  return typeof tab === "string" && (ARTIST_TAB_KEYS as readonly string[]).includes(tab)
    ? (tab as ArtistTab)
    : "songs";
});

const expandMobileSearch = (): void => {
  if (activeTab.value !== "songs") return;
  mobileSearchExpanded.value = true;
  nextTick(() => mobileSearchInputRef.value?.focus());
};

const collapseMobileSearch = (): void => {
  mobileSearchExpanded.value = false;
};

const collapseMobileSearchInput = (): void => {
  const activeElement = document.activeElement;
  if (activeElement instanceof HTMLElement) activeElement.blur();
  collapseMobileSearch();
};

const onDocumentPointerDown = (event: PointerEvent): void => {
  if (!mobileSearchExpanded.value) return;
  if (mobileSearchWrapperRef.value?.contains(event.target as Node)) return;
  collapseMobileSearch();
};

const onTabSwitch = (key: string): void => {
  router.replace({ query: { ...route.query, tab: key } });
};

watch(activeTab, (tab) => {
  if (tab === "albums") collapsed.value = true;
  if (tab !== "songs") collapseMobileSearch();
});

watch(mobileSearchExpanded, (expanded) => {
  if (expanded) {
    nextTick(() => document.addEventListener("pointerdown", onDocumentPointerDown, true));
  } else {
    document.removeEventListener("pointerdown", onDocumentPointerDown, true);
  }
});

const tabs = computed(() => {
  const items = [{ key: "songs", label: t("artist.songs") }];
  if (artist.value?.albums.length) {
    items.push({ key: "albums", label: t("artist.albums") });
  }
  return items;
});

const albumItems = computed<CoverItem[]>(() => {
  if (!artist.value?.albums.length) return [];
  return artist.value.albums.map((item) => ({
    ...item,
    subtitle: t("common.totalSongs", { count: item.trackCount }),
  }));
});
</script>

<template>
  <div class="flex flex-col h-full">
    <!-- 头部信息 -->
    <div v-if="artist" class="shrink-0" :class="useMobileLayout ? 'px-4 pb-1' : 'pb-2 px-5'">
      <div
        class="flex mt-2 transition-[gap,margin] duration-300"
        :class="[
          collapsed
            ? useMobileLayout
              ? 'gap-2 mb-2'
              : 'gap-3 mb-3'
            : useMobileLayout
              ? 'gap-2 mb-2'
              : 'gap-5 mb-4',
        ]"
      >
        <!-- 头像 -->
        <SImg
          :src="artist.avatar ?? fallbackTrackCover"
          :fallback="artistFallback"
          :alt="artist.name"
          cache-type="list-covers"
          class="shrink-0 rounded-full transition-[width,height] duration-300"
          :class="[
            collapsed
              ? useMobileLayout
                ? 'size-14'
                : 'size-20'
              : useMobileLayout
                ? 'size-20'
                : 'size-40',
          ]"
        />
        <!-- 信息 -->
        <div class="flex-1 flex flex-col min-w-0 py-1">
          <div
            class="flex flex-col transition-[gap] duration-300"
            :class="collapsed ? 'gap-0.5' : useMobileLayout ? 'gap-0.5' : 'gap-2'"
          >
            <h1
              class="font-bold text-on-surface truncate lh-normal transition-[font-size,line-height] duration-300"
              :class="[
                useMobileLayout && !collapsed ? 'text-2xl' : 'text-3xl',
                collapsed ? (useMobileLayout ? '!text-lg' : '!text-xl') : '',
              ]"
            >
              {{ artist.name }}
            </h1>
            <div
              class="grid transition-[grid-template-rows,opacity] duration-300"
              :class="collapsed ? 'grid-rows-[0fr] opacity-0' : 'grid-rows-[1fr] opacity-100'"
            >
              <div
                class="overflow-hidden flex items-center leading-none text-on-surface-variant/50"
                :class="[useMobileLayout ? 'flex-wrap gap-x-2 text-xs' : 'gap-3 text-sm']"
              >
                <span class="flex items-center gap-1">
                  <IconLucideListMusic class="shrink-0" />
                  {{ t("common.totalSongs", { count: artist.trackCount }) }}
                </span>
                <span v-if="artist.albumCount" class="flex items-center gap-1">
                  <IconLucideDisc3 class="shrink-0" />
                  {{ t("common.totalAlbums", { count: artist.albumCount }) }}
                </span>
                <span v-if="totalDuration" class="flex items-center gap-1">
                  <IconLucideHourglass class="shrink-0" />
                  {{ t("collection.totalDuration", { time: totalDuration }) }}
                </span>
              </div>
            </div>
          </div>
          <!-- 操作栏 -->
          <div v-if="!useMobileLayout" class="mt-auto flex items-center justify-between gap-4">
            <div class="flex items-center gap-1.5">
              <SButton
                type="primary"
                variant="secondary"
                round
                :disabled="artist.tracks.length === 0 || activeTab !== 'songs'"
                @click="handlePlayAll"
              >
                <template #icon>
                  <IconLucidePlay />
                </template>
                {{ t("common.playAll") }}
              </SButton>
              <SButton
                v-if="canSubscribeArtist"
                variant="secondary"
                round
                :disabled="artistSubBusy"
                @click="handleToggleSubscribe"
              >
                <template #icon>
                  <IconMaterialSymbolsFavoriteRounded v-if="isArtistSubscribed" />
                  <IconMaterialSymbolsFavoriteOutlineRounded v-else />
                </template>
                {{ t(isArtistSubscribed ? "collection.unsubscribe" : "collection.subscribe") }}
              </SButton>
              <SDropdownMenu
                :items="moreMenuItems"
                :disabled="activeTab !== 'songs'"
                align="start"
                @select="handleMoreMenu"
              >
                <template #trigger>
                  <SButton variant="secondary" circle :disabled="activeTab !== 'songs'">
                    <template #icon>
                      <IconLucideEllipsis />
                    </template>
                  </SButton>
                </template>
              </SDropdownMenu>
            </div>
            <div class="relative h-9 shrink-0" :class="useMobileLayout ? 'w-full' : 'w-40'">
              <SInput
                v-model="searchQuery"
                :placeholder="t('common.search')"
                :disabled="activeTab !== 'songs'"
                clearable
                round
                class="absolute right-0 top-0 w-40 focus-within:w-56 focus-within:z-10 focus-within:backdrop-blur-lg focus-within:bg-surface/80 focus-within:shadow-lg"
                data-search-input
              >
                <template #prefix>
                  <IconLucideSearch class="size-4 text-on-surface-variant/40 shrink-0" />
                </template>
              </SInput>
            </div>
          </div>
        </div>
      </div>
      <div
        v-if="useMobileLayout"
        class="grid grid-cols-[2.75rem_minmax(0,1fr)_4.75rem_4.75rem] gap-2 transition-[margin] duration-300"
        :class="collapsed ? 'mt-2 mb-2' : 'mt-3 mb-3'"
      >
        <div
          ref="mobileSearchWrapperRef"
          class="relative h-11 min-w-0 z-10"
          @click="expandMobileSearch"
        >
          <SInput
            ref="mobileSearchInputRef"
            v-model="searchQuery"
            :placeholder="mobileSearchExpanded ? t('common.search') : ''"
            clearable
            round
            class="artist-mobile-search absolute left-0 top-0 overflow-hidden transition-[border-color,box-shadow,background-color,width,right,opacity] duration-250"
            :class="
              mobileSearchExpanded
                ? 'z-50 backdrop-blur-lg bg-surface/80 shadow-lg ring-2 ring-primary/20'
                : 'artist-mobile-search--collapsed'
            "
            :disabled="activeTab !== 'songs'"
            :style="mobileSearchStyle"
            @keydown.escape="collapseMobileSearch"
          >
            <template #prefix>
              <IconLucideSearch class="size-4 shrink-0" />
            </template>
            <template v-if="mobileSearchExpanded" #suffix>
              <button
                type="button"
                class="artist-mobile-search__collapse"
                :aria-label="t('common.close')"
                @pointerdown.stop
                @click.stop="collapseMobileSearchInput"
              >
                <IconLucideChevronDown class="size-4.5" />
              </button>
            </template>
          </SInput>
        </div>
        <button
          type="button"
          class="artist-mobile-action artist-mobile-action--primary"
          :disabled="artist.tracks.length === 0 || activeTab !== 'songs'"
          @click="handlePlayAll"
        >
          <IconLucidePlay class="size-4 shrink-0" />
          <span class="artist-mobile-action__label">{{ t("common.playAll") }}</span>
        </button>
        <button
          v-if="canSubscribeArtist"
          type="button"
          class="artist-mobile-action artist-mobile-action--compact"
          :disabled="artistSubBusy"
          @click="handleToggleSubscribe"
        >
          <IconMaterialSymbolsFavoriteRounded v-if="isArtistSubscribed" class="size-4 shrink-0" />
          <IconMaterialSymbolsFavoriteOutlineRounded v-else class="size-4 shrink-0" />
          <span class="artist-mobile-action__label">
            {{ t(isArtistSubscribed ? "collection.unsubscribe" : "collection.subscribe") }}
          </span>
        </button>
        <div v-else />
        <SDropdownMenu
          :items="moreMenuItems"
          :disabled="activeTab !== 'songs'"
          align="end"
          @select="handleMoreMenu"
        >
          <template #trigger>
            <button
              type="button"
              class="artist-mobile-action artist-mobile-action--compact w-full"
              :disabled="activeTab !== 'songs'"
            >
              <IconLucideEllipsis class="size-4 shrink-0" />
              <span class="artist-mobile-action__label">{{ t("common.more") }}</span>
            </button>
          </template>
        </SDropdownMenu>
      </div>
      <!-- Tab 切换 -->
      <STabs
        :model-value="activeTab"
        :tabs="tabs"
        :type="useMobileLayout ? 'segment' : 'bar'"
        :size="useMobileLayout ? undefined : 'large'"
        :round="useMobileLayout"
        @update:model-value="onTabSwitch"
      />
    </div>
    <Transition name="fade" mode="out-in" :duration="150">
      <div
        v-if="artist && artist.tracks.length > 0"
        :key="artist.id"
        class="flex-1 min-h-0 flex flex-col"
      >
        <Transition :name="tabTransitionName" mode="out-in">
          <!-- 歌曲列表 -->
          <div v-if="activeTab === 'songs'" key="songs" class="flex-1 min-h-0">
            <SongList
              ref="songListRef"
              :items="artist.tracks"
              :search-query="searchQuery"
              :source="source"
              :playback-context="playbackContext"
              :show-size="source === 'local'"
              :has-more="hasMoreSongs"
              :loading-more="loadingMore"
              enable-sort
              @scroll="handleListScroll"
              @change="loadArtist"
              @reach-bottom="onReachBottom"
            />
          </div>
          <!-- 专辑网格 -->
          <div v-else-if="activeTab === 'albums'" key="albums" class="flex-1 min-h-0">
            <CoverList
              :items="albumItems"
              :padding-x="20"
              :padding-bottom="24"
              @click="(item) => navigateToAlbum(item.title, { source, albumId: item.id })"
            />
          </div>
        </Transition>
      </div>
      <!-- 加载中 -->
      <div v-else-if="loading" key="loading" class="flex-1 flex items-center justify-center">
        <div class="text-center text-on-surface-variant/60">
          <SLoading class="text-4xl text-primary/70 mb-4 mx-auto block" />
          <div class="text-sm">{{ t("common.loading") }}</div>
        </div>
      </div>
      <!-- 错误态 -->
      <div v-else-if="error" key="error" class="flex-1 flex items-center justify-center px-6">
        <div class="text-center text-red-500/85">
          <IconLucideTriangleAlert class="size-14 mx-auto mb-4 opacity-50" />
          <div class="text-sm font-medium mb-1">{{ t("search.errorTitle") }}</div>
          <div class="text-xs opacity-80 break-all max-w-xs mb-4">{{ error }}</div>
          <SButton type="primary" variant="secondary" @click="loadArtist">
            <template #icon><IconLucideRefreshCw /></template>
            {{ t("common.retry") }}
          </SButton>
        </div>
      </div>
      <!-- 空状态 -->
      <div v-else-if="artist" key="empty" class="flex-1 flex items-center justify-center">
        <div class="text-center text-on-surface-variant/50">
          <IconLucideMusic class="size-12 mx-auto mb-3 opacity-30" />
          <div class="text-sm">{{ t("collection.empty") }}</div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.artist-mobile-action {
  height: 2.75rem;
  min-width: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.375rem;
  padding: 0 0.625rem;
  border: 0;
  border-radius: 9999px;
  background: rgb(var(--s-on-surface) / 0.1);
  color: rgb(var(--s-on-surface));
  appearance: none;
  cursor: pointer;
  transition:
    background-color 200ms cubic-bezier(0.4, 0, 0.2, 1),
    color 200ms cubic-bezier(0.4, 0, 0.2, 1),
    opacity 200ms cubic-bezier(0.4, 0, 0.2, 1),
    transform 200ms cubic-bezier(0.4, 0, 0.2, 1);
}

.artist-mobile-action:not(:disabled):active {
  transform: scale(0.96);
  background: rgb(var(--s-on-surface) / 0.16);
}

.artist-mobile-action:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.artist-mobile-action--primary {
  background: rgb(var(--s-primary) / 0.16);
  color: rgb(var(--s-primary));
}

.artist-mobile-action--primary:not(:disabled):active {
  background: rgb(var(--s-primary) / 0.24);
}

.artist-mobile-action--compact {
  gap: 0.25rem;
  padding-left: 0.5rem;
  padding-right: 0.5rem;
}

.artist-mobile-action__label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.75rem;
  line-height: 1;
  font-weight: 600;
}

.artist-mobile-search {
  cursor: pointer;
  transition:
    border-color 250ms cubic-bezier(0.4, 0, 0.2, 1),
    box-shadow 250ms cubic-bezier(0.4, 0, 0.2, 1),
    background-color 250ms cubic-bezier(0.4, 0, 0.2, 1),
    width 250ms cubic-bezier(0.4, 0, 0.2, 1),
    right 250ms cubic-bezier(0.4, 0, 0.2, 1),
    opacity 250ms cubic-bezier(0.4, 0, 0.2, 1);
}

.artist-mobile-search--collapsed {
  justify-content: center;
  gap: 0;
  padding-left: 0;
  padding-right: 0;
  border-color: transparent;
  background: rgb(var(--s-on-surface) / 0.1);
  color: rgb(var(--s-on-surface));
}

.artist-mobile-search--collapsed :deep(input) {
  flex: 0 0 0;
  width: 0;
  opacity: 0;
}

.artist-mobile-search__collapse {
  width: 1.75rem;
  height: 1.75rem;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 9999px;
  background: transparent;
  color: rgb(var(--s-on-surface-variant) / 0.7);
  appearance: none;
  cursor: pointer;
  transition:
    background-color 200ms cubic-bezier(0.4, 0, 0.2, 1),
    color 200ms cubic-bezier(0.4, 0, 0.2, 1);
}

.artist-mobile-search__collapse:active {
  background: rgb(var(--s-on-surface) / 0.16);
  color: rgb(var(--s-on-surface));
}
</style>
