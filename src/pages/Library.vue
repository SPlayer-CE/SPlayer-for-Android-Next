<script setup lang="ts">
defineOptions({ name: "Library" });

import type { PlaybackContext } from "@shared/types/player";
import type { DropdownMenuItem } from "@/components/ui/SDropdownMenu.vue";
import { useLibraryStore } from "@/stores/library";
import SongList from "@/components/list/SongList.vue";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import { formatFileSize } from "@/utils/format";
import IconFolderOpen from "~icons/lucide/folder-open";
import IconRefreshCw from "~icons/lucide/refresh-cw";
import IconLucideListChecks from "~icons/lucide/list-checks";
import IconLucideChevronDown from "~icons/lucide/chevron-down";
import * as player from "@/core/player";

const { t } = useI18n();
const libraryStore = useLibraryStore();
const { useMobileLayout } = useResponsiveLayout();
const { tracks, scanDirs, scanning, scanProgress, initialized } = storeToRefs(libraryStore);

const playbackContext = computed<PlaybackContext>(() => ({
  originId: "library",
  originType: "page",
  originName: t("library.title"),
}));

/** 搜索关键词 */
const searchQuery = ref("");
const searchFocused = ref(false);

const closeSearchInput = (): void => {
  const activeElement = document.activeElement;
  if (activeElement instanceof HTMLElement) activeElement.blur();
  searchFocused.value = false;
};

/** 多选模式 */
const songListRef = shallowRef<InstanceType<typeof SongList> | null>(null);

/** 所有歌曲的总文件大小 */
const totalSize = computed(() => {
  const bytes = tracks.value.reduce((sum, track) => sum + (track.fileSize ?? 0), 0);
  return bytes > 0 ? formatFileSize(bytes) : "";
});

/** 新增目录后立即扫描 */
const handleFolderAdded = (): void => {
  libraryStore.startScan(false);
};

const handleQuickAddFolder = async (): Promise<void> => {
  const res = await libraryStore.addScanDir();
  if (res.success) libraryStore.startScan(false);
};

// 播放全部
const handlePlayAll = (): void => {
  if (tracks.value.length === 0) return;
  player.playFrom(tracks.value, 0, playbackContext.value);
};

// 扫描进度百分比
const scanPercent = computed(() => {
  if (!scanProgress.value || scanProgress.value.total === 0) return 0;
  return Math.round((scanProgress.value.scanned / scanProgress.value.total) * 100);
});

// 目录管理弹窗
const folderDialogOpen = ref(false);

const moreMenuItems = computed<DropdownMenuItem[]>(() => [
  { key: "batchManage", label: t("songList.batch.manage"), icon: IconLucideListChecks },
  { key: "folders", label: t("library.folders"), icon: IconFolderOpen, separator: true },
  {
    key: "scan",
    label: scanning.value ? t("library.scanning") : t("library.scanAll"),
    icon: IconRefreshCw,
    disabled: scanning.value || scanDirs.value.length === 0,
  },
]);

// 更多菜单
const handleMoreMenu = (key: string): void => {
  switch (key) {
    // 批量管理
    case "batchManage":
      songListRef.value?.enterBatch();
      break;
    // 目录管理
    case "folders":
      folderDialogOpen.value = true;
      break;
    // 全量扫描
    case "scan":
      libraryStore.startScan(false);
      break;
  }
};

// 进入页面时初始化
onMounted(async () => {
  libraryStore.subscribeScanProgress();
  try {
    if (!initialized.value) {
      await libraryStore.load();
    }
    // 有目录即扫描：尚无曲目时全量，已有曲目时增量
    if (scanDirs.value.length > 0) {
      libraryStore.startScan(tracks.value.length > 0);
    }
  } catch (error) {
    console.warn("[Library] init failed:", error);
  }
});

onUnmounted(() => {
  libraryStore.unsubscribeScanProgress();
});
</script>

<template>
  <div class="flex flex-col h-full">
    <!-- 顶栏 -->
    <div class="shrink-0 pb-2" :class="useMobileLayout ? 'px-4' : 'px-5'">
      <div
        class="flex items-center justify-between mb-4"
        :class="useMobileLayout ? 'mt-1' : 'mt-2'"
      >
        <div class="flex items-baseline gap-4">
          <h1
            class="font-bold text-on-surface text-balance"
            :class="useMobileLayout ? 'text-2xl' : 'text-3xl'"
          >
            {{ t("library.title") }}
          </h1>
          <!-- 统计或进度 -->
          <Transition name="fade" mode="out-in">
            <div
              v-if="scanning && scanProgress"
              key="progress"
              class="flex items-center gap-2 text-sm text-on-surface-variant/50"
            >
              <SLoading class="size-3.5 text-primary shrink-0" />
              <span class="tabular-nums">
                {{
                  t("library.scanProgress", {
                    scanned: scanProgress.scanned,
                    total: scanProgress.total,
                  })
                }}
              </span>
              <span class="text-on-surface-variant/40">{{ scanPercent }}%</span>
            </div>
            <div
              v-else-if="tracks.length > 0"
              key="stats"
              class="flex items-center gap-3 text-sm text-on-surface-variant/50"
            >
              <span class="flex items-center gap-1">
                <IconLucideMusic class="size-3.5" />
                {{ t("common.totalSongs", { count: tracks.length }) }}
              </span>
              <span v-if="totalSize" class="flex items-center gap-1">
                <IconLucideHardDrive class="size-3.5" />
                {{ totalSize }}
              </span>
            </div>
          </Transition>
        </div>
      </div>
      <div class="relative flex items-center justify-between gap-4">
        <div class="flex items-center gap-3">
          <SButton
            type="primary"
            variant="secondary"
            round
            :disabled="tracks.length === 0"
            @click="handlePlayAll"
          >
            <template #icon>
              <IconLucidePlay />
            </template>
            {{ t("common.playAll") }}
          </SButton>
          <SButton
            variant="secondary"
            circle
            :disabled="scanning || scanDirs.length === 0"
            @click="libraryStore.startScan(true)"
          >
            <template #icon>
              <IconLucideRefreshCw :class="{ 'animate-spin': scanning }" />
            </template>
          </SButton>
          <SDropdownMenu :items="moreMenuItems" align="start" @select="handleMoreMenu">
            <template #trigger>
              <SButton variant="secondary" circle>
                <template #icon>
                  <IconLucideEllipsis />
                </template>
              </SButton>
            </template>
          </SDropdownMenu>
        </div>
        <div class="relative h-9 w-40 shrink-0">
          <SInput
            v-model="searchQuery"
            :placeholder="t('common.search')"
            clearable
            round
            class="absolute right-0 top-0 w-40 focus-within:w-56 focus-within:z-10 focus-within:backdrop-blur-lg focus-within:bg-surface/80 focus-within:shadow-lg"
            data-search-input
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
    </div>
    <!-- 曲目列表 -->
    <div v-if="tracks.length > 0" class="flex-1 min-h-0">
      <SongList
        ref="songListRef"
        :items="tracks"
        :search-query="searchQuery"
        :playback-context="playbackContext"
        enable-sort
        show-size
      />
    </div>
    <!-- 空状态：无目录或无歌曲 -->
    <div v-else class="flex-1 flex items-center justify-center">
      <div class="text-center text-on-surface-variant/50">
        <IconLucideMusic class="size-12 mx-auto mb-3 opacity-30" />
        <div class="text-sm mb-1">{{ t("library.empty") }}</div>
        <div class="text-xs mb-4 opacity-70">{{ t("library.emptyHint") }}</div>
        <SButton type="primary" variant="secondary" @click="handleQuickAddFolder">
          <template #icon><IconLucideFolderPlus /></template>
          {{ t("library.addFolder") }}
        </SButton>
      </div>
    </div>
    <!-- 文件夹管理 -->
    <SDialog
      v-model:open="folderDialogOpen"
      :title="t('library.folders')"
      :description="t('library.foldersDescription')"
      width="480px"
    >
      <FolderManager @added="handleFolderAdded" />
    </SDialog>
  </div>
</template>
