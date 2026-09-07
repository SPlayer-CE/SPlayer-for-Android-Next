<script setup lang="ts">
import { useDataStore } from "@/stores/data";
import { useStatusStore } from "@/stores/status";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import { getHotSearches, type HotSearchItem } from "@/apis/search/hot";
import { getSearchSuggest, type SuggestData, type SuggestSimpleItem } from "@/apis/search/suggest";
import { songsByIds as getNeteaseSongsByIds } from "@/apis/song/netease";
import { formatCompact } from "@/utils/format";
import { navigateToAlbum, navigateToArtist, navigateToPlaylist } from "@/utils/navigate";
import { parseMusicLink, type LinkType } from "@/utils/link";
import type { TrackSource } from "@shared/types/player";
import * as player from "@/core/player";
import IconLucideMusic from "~icons/lucide/music";
import IconLucideUser from "~icons/lucide/user";
import IconLucideDisc from "~icons/lucide/disc";
import IconLucideListMusic from "~icons/lucide/list-music";
import IconLucideAudioWaveform from "~icons/lucide/audio-waveform";
import IconLucideChevronDown from "~icons/lucide/chevron-down";
import { isAndroid } from "@/services/bridge";

const { t, locale } = useI18n();
const router = useRouter();
const data = useDataStore();
const status = useStatusStore();
const { useMobileLayout, isPhonePortrait } = useResponsiveLayout();

const SEARCH_EXPANDED_WIDTH = "23rem";
const SEARCH_EXPANDED_RIGHT = "-1.5rem";
const MOBILE_SEARCH_EXPANDED_WIDTH = "calc(var(--page-zoom-100vw, 100vw) - 24px)";
const SEARCH_PANEL_COLLAPSE_MS = 250;
const SEARCH_INPUT_TRANSITION_MS = 250;

/** 展开态面板开关 */
const panelOpen = ref(false);
const panelCollapsing = ref(false);
const inputExpanded = ref(false);
const searchClosing = ref(false);
const searchQuery = ref("");
const recognitionOpen = ref(false);
let closePanelTimer: ReturnType<typeof setTimeout> | undefined;
let closeLayerTimer: ReturnType<typeof setTimeout> | undefined;

const searchLayerActive = computed(
  () => panelOpen.value || panelCollapsing.value || inputExpanded.value || searchClosing.value,
);
const searchPanelVisible = computed(() => panelOpen.value || panelCollapsing.value);
const searchBoxStyle = computed(() => ({
  width: inputExpanded.value
    ? useMobileLayout.value
      ? MOBILE_SEARCH_EXPANDED_WIDTH
      : SEARCH_EXPANDED_WIDTH
    : "100%",
  right: useMobileLayout.value ? "auto" : inputExpanded.value ? SEARCH_EXPANDED_RIGHT : "0",
  left: useMobileLayout.value ? "0" : "auto",
  height: useMobileLayout.value ? "2.75rem" : undefined,
}));
const searchPanelStyle = computed(() => ({
  width: useMobileLayout.value ? MOBILE_SEARCH_EXPANDED_WIDTH : SEARCH_EXPANDED_WIDTH,
  right: useMobileLayout.value ? "auto" : SEARCH_EXPANDED_RIGHT,
  left: useMobileLayout.value ? "0" : "auto",
  height: useMobileLayout.value ? "65vh" : undefined,
}));

const clearCloseTimers = (): void => {
  if (closePanelTimer) clearTimeout(closePanelTimer);
  if (closeLayerTimer) clearTimeout(closeLayerTimer);
  closePanelTimer = undefined;
  closeLayerTimer = undefined;
};

const openSearch = (): void => {
  clearCloseTimers();
  panelCollapsing.value = false;
  searchClosing.value = false;
  inputExpanded.value = true;
  panelOpen.value = true;
};

const closeSearch = (): void => {
  if (!panelOpen.value && !panelCollapsing.value && !inputExpanded.value && !searchClosing.value) {
    return;
  }
  clearCloseTimers();
  panelOpen.value = false;
  panelCollapsing.value = true;
  searchClosing.value = true;
  closePanelTimer = setTimeout(() => {
    panelCollapsing.value = false;
    inputExpanded.value = false;
    closeLayerTimer = setTimeout(() => {
      searchClosing.value = false;
      closeLayerTimer = undefined;
    }, SEARCH_INPUT_TRANSITION_MS);
    closePanelTimer = undefined;
  }, SEARCH_PANEL_COLLAPSE_MS);
};

/** 移动端收起搜索，同时让系统输入法关闭 */
const collapseSearchInput = (): void => {
  const activeElement = document.activeElement;
  if (activeElement instanceof HTMLElement) activeElement.blur();
  closeSearch();
};

/** 快捷键打开：挂在 status store */
watch(
  () => status.searchOpen,
  (v) => {
    if (v) {
      openSearch();
      status.searchOpen = false;
    }
  },
);

/** 触发器点击 */
const onTriggerClick = (): void => {
  openSearch();
  if (isPhonePortrait.value) {
    searchInputRef.value?.focus();
  }
};

const trimmedQuery = computed(() => searchQuery.value.trim());

/** 音乐链接检测 */
const parsedLink = computed(() => parseMusicLink(trimmedQuery.value));

/** 热搜结果 */
const hotItems = ref<HotSearchItem[]>([]);

const loadHot = async (): Promise<void> => {
  try {
    hotItems.value = await getHotSearches();
  } catch {
    hotItems.value = [];
  }
};

/** 搜索建议 */
const EMPTY_SUGGEST: SuggestData = { songs: [], albums: [], artists: [], playlists: [] };
const suggest = ref<SuggestData>({ ...EMPTY_SUGGEST });

const loadSuggest = useDebounceFn(async (keyword: string) => {
  try {
    suggest.value = await getSearchSuggest(keyword);
  } catch {
    // API 不可达时静默回退
  }
}, 300);

type SuggestKind = "song" | "artist" | "album" | "playlist";

/**
 * 建议分类配置
 */
const suggestSections = computed(() => {
  const data = suggest.value;
  return [
    {
      kind: "song" as SuggestKind,
      icon: IconLucideMusic,
      label: t("search.tabs.songs"),
      items: data.songs.map<SuggestSimpleItem>((song) => ({
        id: song.id,
        name: song.name,
        subtitle: [song.artist, song.album].filter(Boolean).join(" · ") || undefined,
      })),
    },
    {
      kind: "artist" as SuggestKind,
      icon: IconLucideUser,
      label: t("search.tabs.artists"),
      items: data.artists,
    },
    {
      kind: "album" as SuggestKind,
      icon: IconLucideDisc,
      label: t("search.tabs.albums"),
      items: data.albums,
    },
    {
      kind: "playlist" as SuggestKind,
      icon: IconLucideListMusic,
      label: t("search.tabs.playlists"),
      items: data.playlists,
    },
  ].filter((sec) => sec.items.length > 0);
});

/** 跳转到搜索页 */
const submit = (raw: string): void => {
  const word = raw.trim();
  if (!word) return;
  data.addSearchHistory(word);
  router.push({ name: "search", query: { q: word } });
  closeSearch();
};

const onSubmit = (): void => submit(trimmedQuery.value);
const onPickKeyword = (keyword: string): void => submit(keyword);
const onRemoveHistory = (keyword: string): void => data.removeSearchHistory(keyword);
const onClearHistory = (): void => data.clearSearchHistory();

/**
 * 建议点击
 * @param kind - 建议类型
 * @param id - 歌曲 id
 * @param name - 名称
 */
const navigateToResource = async (
  kind: SuggestKind | LinkType,
  id: string,
  source: TrackSource,
  name?: string,
): Promise<void> => {
  if (trimmedQuery.value) data.addSearchHistory(trimmedQuery.value);
  closeSearch();
  switch (kind) {
    case "song":
      try {
        const [track] = await getNeteaseSongsByIds([Number(id)]);
        if (track) await player.playNow(track);
      } catch (err) {
        console.warn("[NavSearch] play song failed:", err);
      }
      break;
    case "artist":
      navigateToArtist(name, { source, artistId: id });
      break;
    case "album":
      navigateToAlbum(name, { source, albumId: id });
      break;
    case "playlist":
      navigateToPlaylist(id, { source, name });
      break;
  }
};

const onPickSuggest = (kind: SuggestKind, id: number, name: string): void => {
  navigateToResource(kind, String(id), "netease", name);
};

/** 音乐链接点击 */
const onPickLink = (): void => {
  const link = parsedLink.value;
  if (!link) return;
  navigateToResource(link.type, link.id, link.source);
};

const bodyRef = ref<HTMLElement | null>(null);
const bodyHeight = ref<number | null>(null);

useResizeObserver(bodyRef, (entries) => {
  if (bodyHeight.value === null) return;
  const next = entries[0]?.borderBoxSize?.[0]?.blockSize;
  if (next != null) bodyHeight.value = next;
});

const activeIndex = ref(-1);

/** 键盘上下导航 */
const keyboardItems = computed(() => {
  const items: Array<{ id: string; action: () => void }> = [];
  if (trimmedQuery.value) {
    if (parsedLink.value) {
      items.push({ id: "parsed-link", action: onPickLink });
    }
    items.push({ id: "quick", action: onSubmit });
    for (const sec of suggestSections.value) {
      for (const entry of sec.items) {
        items.push({
          id: `${sec.kind}-${entry.id}`,
          action: () => onPickSuggest(sec.kind, entry.id, entry.name),
        });
      }
    }
  } else {
    for (const word of data.searchHistory) {
      items.push({ id: `history-${word}`, action: () => onPickKeyword(word) });
    }
    hotItems.value
      .slice(0, 20)
      .forEach((item, idx) =>
        items.push({ id: `hot-${item.keyword}-${idx}`, action: () => onPickKeyword(item.keyword) }),
      );
  }
  return items;
});

watch(keyboardItems, () => {
  activeIndex.value = -1;
});

const scrollToActive = async () => {
  await nextTick();
  const id = keyboardItems.value[activeIndex.value]?.id;
  if (!id || !bodyRef.value) return;
  const safeId = id.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
  const el = bodyRef.value.querySelector(`[data-search-id="${safeId}"]`) as HTMLElement;
  if (el) {
    el.scrollIntoView({ block: "nearest", behavior: "smooth" });
  }
};

const onKeydown = (e: KeyboardEvent) => {
  if (e.key === "ArrowDown") {
    e.preventDefault();
    if (keyboardItems.value.length === 0) return;
    activeIndex.value = (activeIndex.value + 1) % keyboardItems.value.length;
    scrollToActive();
  } else if (e.key === "ArrowUp") {
    e.preventDefault();
    if (keyboardItems.value.length === 0) return;
    activeIndex.value =
      (activeIndex.value - 1 + keyboardItems.value.length) % keyboardItems.value.length;
    scrollToActive();
  } else if (e.key === "Enter") {
    if (e.isComposing) return;
    e.preventDefault();
    if (activeIndex.value >= 0 && activeIndex.value < keyboardItems.value.length) {
      keyboardItems.value[activeIndex.value].action();
    } else {
      onSubmit();
    }
  }
};

const wrapperRef = ref<HTMLElement | null>(null);
const searchInputRef = ref<{ focus: () => void } | null>(null);

/** 点击外部关闭 */
const onDocumentClick = (e: MouseEvent): void => {
  if (!panelOpen.value) return;
  if (wrapperRef.value && !wrapperRef.value.contains(e.target as Node)) {
    closeSearch();
  }
};

watch(trimmedQuery, (kw) => {
  suggest.value = { ...EMPTY_SUGGEST };
  if (kw) loadSuggest(kw);
});

watch(panelOpen, (open) => {
  if (open) {
    searchQuery.value = "";
    loadHot();
    nextTick(() => document.addEventListener("pointerdown", onDocumentClick, true));
  } else {
    // 关闭复位：下次开弹仍是 auto 起手
    bodyHeight.value = null;
    activeIndex.value = -1;
    document.removeEventListener("pointerdown", onDocumentClick, true);
  }
});

onMounted(() => {
  loadHot();
});

onUnmounted(() => {
  clearCloseTimers();
  document.removeEventListener("pointerdown", onDocumentClick, true);
});
</script>

<template>
  <!-- 顶栏展开态搜索框 + 嵌入式结果面板 -->
  <div
    ref="wrapperRef"
    class="app-no-drag relative shrink-0"
    :class="searchLayerActive ? 'z-[999]' : ''"
  >
    <!-- 触发器 / 展开态输入框 -->
    <div
      class="relative cursor-pointer"
      :class="useMobileLayout ? 'h-11 w-11' : 'h-10 w-60'"
      @click="onTriggerClick"
    >
      <SInput
        ref="searchInputRef"
        v-model="searchQuery"
        :placeholder="t('nav.searchPlaceholder')"
        clearable
        round
        class="absolute top-0 overflow-hidden"
        :class="
          searchLayerActive
            ? 'z-50 backdrop-blur-lg bg-surface/80 shadow-lg ring-2 ring-primary/20'
            : ''
        "
        :style="searchBoxStyle"
        @keydown="onKeydown"
        @keydown.enter="onSubmit"
        @keydown.escape="closeSearch"
      >
        <template #prefix>
          <IconLucideSearch class="size-4 text-on-surface-variant/50 shrink-0" />
        </template>
        <template v-if="useMobileLayout && inputExpanded" #suffix>
          <button
            type="button"
            class="size-7 shrink-0 inline-flex items-center justify-center rounded-full border-none bg-transparent appearance-none cursor-pointer text-on-surface-variant/70 transition-[color,background-color] duration-200 hover:bg-on-surface/10 hover:text-on-surface active:bg-on-surface/16"
            :aria-label="t('common.close')"
            @pointerdown.stop
            @click.stop="collapseSearchInput"
          >
            <IconLucideChevronDown class="size-4.5" />
          </button>
        </template>
      </SInput>
    </div>
    <!-- 结果面板（从搜索框底部向下延伸） -->
    <Transition name="search-panel">
      <div
        v-if="searchPanelVisible"
        class="nav-search-panel absolute top-full mt-2 overflow-y-auto rounded-xl bg-surface-alt border border-solid border-outline-variant/30 shadow-xl z-50 flex flex-col gap-4 px-5 py-4"
        :class="panelCollapsing ? 'nav-search-panel--closing pointer-events-none' : ''"
        :style="searchPanelStyle"
      >
        <template v-if="trimmedQuery">
          <!-- 快捷跳转 -->
          <div class="flex flex-col gap-1.5">
            <div class="px-2 flex items-center gap-1.5 text-sm font-medium text-primary">
              <IconLucideZap class="size-4" />
              <span>{{ t("nav.searchSection.quick") }}</span>
            </div>
            <div
              class="min-w-0 flex items-center gap-2.5 px-2 py-1.5 rounded-lg cursor-pointer hover:bg-on-surface/5 transition-colors duration-200"
              @click="onSubmit"
            >
              <span class="flex-1 truncate text-sm text-on-surface">
                {{ t("nav.searchGoto", { keyword: trimmedQuery }) }}
              </span>
              <IconLucideArrowRight class="size-4 shrink-0 text-on-surface-variant" />
            </div>
          </div>
          <div v-if="suggest.songs.length > 0" class="flex flex-col gap-1.5">
            <div class="px-2 flex items-center gap-1.5 text-sm font-medium text-primary">
              <IconLucideMusic class="size-4" />
              <span>{{ t("search.tabs.songs") }}</span>
            </div>
            <div
              v-for="song in suggest.songs"
              :key="song.id"
              class="min-w-0 flex items-center gap-2.5 px-2 py-1.5 rounded-lg cursor-pointer hover:bg-on-surface/5 transition-colors duration-200"
              @click="onPickSuggest('song', song.id, song.name)"
            >
              <div class="flex-1 min-w-0 flex flex-col leading-tight">
                <span class="truncate text-sm text-on-surface">{{ song.name }}</span>
                <span v-if="song.artist" class="truncate text-xs text-on-surface-variant">
                  {{ song.artist }}
                  <template v-if="song.album">· {{ song.album }}</template>
                </span>
              </div>
            </div>
          </div>
          <div v-if="suggest.artists.length > 0" class="flex flex-col gap-1.5">
            <div class="px-2 flex items-center gap-1.5 text-sm font-medium text-primary">
              <IconLucideUser class="size-4" />
              <span>{{ t("search.tabs.artists") }}</span>
            </div>
            <div
              v-for="artist in suggest.artists"
              :key="artist.id"
              class="min-w-0 flex items-center gap-2.5 px-2 py-1.5 rounded-lg cursor-pointer hover:bg-on-surface/5 transition-colors duration-200"
              @click="onPickSuggest('artist', artist.id, artist.name)"
            >
              <span class="flex-1 truncate text-sm text-on-surface">{{ artist.name }}</span>
            </div>
          </div>
          <div v-if="suggest.albums.length > 0" class="flex flex-col gap-1.5">
            <div class="px-2 flex items-center gap-1.5 text-sm font-medium text-primary">
              <IconLucideDisc class="size-4" />
              <span>{{ t("search.tabs.albums") }}</span>
            </div>
            <div
              v-for="album in suggest.albums"
              :key="album.id"
              class="min-w-0 flex items-center gap-2.5 px-2 py-1.5 rounded-lg cursor-pointer hover:bg-on-surface/5 transition-colors duration-200"
              @click="onPickSuggest('album', album.id, album.name)"
            >
              <div class="flex-1 min-w-0 flex flex-col leading-tight">
                <span class="truncate text-sm text-on-surface">{{ album.name }}</span>
                <span v-if="album.subtitle" class="truncate text-xs text-on-surface-variant">
                  {{ album.subtitle }}
                </span>
              </div>
            </div>
          </div>
          <div v-if="suggest.playlists.length > 0" class="flex flex-col gap-1.5">
            <div class="px-2 flex items-center gap-1.5 text-sm font-medium text-primary">
              <IconLucideListMusic class="size-4" />
              <span>{{ t("search.tabs.playlists") }}</span>
            </div>
            <div
              v-for="playlist in suggest.playlists"
              :key="playlist.id"
              class="min-w-0 flex items-center gap-2.5 px-2 py-1.5 rounded-lg cursor-pointer hover:bg-on-surface/5 transition-colors duration-200"
              @click="onPickSuggest('playlist', playlist.id, playlist.name)"
            >
              <span class="flex-1 truncate text-sm text-on-surface">{{ playlist.name }}</span>
            </div>
          </div>
        </template>
        <template v-else>
          <!-- 历史 -->
          <div v-if="data.searchHistory.length > 0" class="flex flex-col gap-2">
            <div class="px-2 flex items-center justify-between">
              <div class="flex items-center gap-1.5 text-sm font-medium text-primary">
                <IconLucideHistory class="size-4" />
                <span>{{ t("nav.searchSection.history") }}</span>
              </div>
              <SButton variant="ghost" size="tiny" circle @click="onClearHistory">
                <template #icon><IconLucideTrash2 /></template>
              </SButton>
            </div>
            <div class="flex flex-wrap gap-1.5">
              <STag
                v-for="word in data.searchHistory"
                :key="word"
                type="default"
                round
                closable
                class="max-w-50 cursor-pointer hover:bg-on-surface/20 transition-colors duration-200"
                @click="onPickKeyword(word)"
                @close="onRemoveHistory(word)"
              >
                <span class="truncate">{{ word }}</span>
              </STag>
            </div>
          </div>
          <!-- 空内容提示 -->
          <div
            v-if="data.searchHistory.length === 0 && hotItems.length === 0"
            class="py-10 flex flex-col items-center justify-center gap-2 text-on-surface-variant/40"
          >
            <IconLucideSearch class="size-8" />
            <span class="text-xs">{{ t("nav.searchEmpty") }}</span>
          </div>
          <!-- 热搜 -->
          <div v-if="hotItems.length > 0" class="flex flex-col gap-2">
            <div class="px-2 flex items-center gap-1.5 text-sm font-medium text-primary">
              <IconLucideFlame class="size-4" />
              <span>{{ t("nav.searchSection.hot") }}</span>
            </div>
            <div
              class="grid gap-x-2 gap-y-0.5"
              :class="useMobileLayout ? 'grid-cols-1' : 'grid-cols-2'"
            >
              <div
                v-for="(item, idx) in hotItems.slice(0, 20)"
                :key="`${item.keyword}-${idx}`"
                class="min-h-11 min-w-0 flex items-center gap-2.5 px-2 py-1.5 rounded-lg cursor-pointer hover:bg-on-surface/5 transition-colors duration-200"
                @click="onPickKeyword(item.keyword)"
              >
                <span
                  class="shrink-0 w-5 text-center text-sm font-semibold tabular-nums text-on-surface-variant/50"
                >
                  {{ idx + 1 }}
                </span>
                <div class="flex-1 min-w-0 flex flex-col leading-tight">
                  <span class="truncate text-sm text-on-surface">{{ item.keyword }}</span>
                  <span v-if="item.content" class="truncate text-xs text-on-surface-variant">
                    {{ item.content }}
                  </span>
                </div>
                <span
                  v-if="item.score"
                  class="shrink-0 text-xs tabular-nums text-on-surface-variant/50"
                >
                  {{ formatCompact(item.score, locale) }}
                </span>
              </div>
            </div>
          </div>
        </template>
      </div>
    </Transition>
  </div>
  <!-- 听歌识曲（依赖桌面端 audio-capture，Android 端隐藏） -->
  <SButton
    v-if="!isAndroid"
    class="app-no-drag shrink-0"
    variant="tertiary"
    circle
    :size="40"
    :icon-size="20"
    @click="recognitionOpen = true"
  >
    <template #icon><IconLucideAudioWaveform /></template>
  </SButton>
  <RecognitionDialog v-model:open="recognitionOpen" />
</template>

<style scoped>
/* 结果面板：从搜索框底部向下延伸 */
.nav-search-panel {
  max-height: 65vh;
  opacity: 1;
  transform: translateY(0);
  transition:
    max-height 250ms cubic-bezier(0.4, 0, 0.2, 1),
    padding-top 250ms cubic-bezier(0.4, 0, 0.2, 1),
    padding-bottom 250ms cubic-bezier(0.4, 0, 0.2, 1),
    opacity 250ms cubic-bezier(0.4, 0, 0.2, 1),
    transform 250ms cubic-bezier(0.4, 0, 0.2, 1);
}

.nav-search-panel--closing {
  max-height: 0;
  opacity: 0;
  transform: translateY(-10px);
  padding-top: 0;
  padding-bottom: 0;
  border-color: transparent;
}

.search-panel-enter-active {
  animation: search-panel-in 280ms cubic-bezier(0.4, 0, 0.2, 1) 250ms both;
}

@keyframes search-panel-in {
  from {
    opacity: 0;
    transform: translateY(-8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
