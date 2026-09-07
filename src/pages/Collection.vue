<script setup lang="ts">
import type { PlaybackContext, TrackSource } from "@shared/types/player";
import type { Collection, CollectionType } from "@/types/collection";
import type { DropdownMenuItem } from "@/components/ui/SDropdownMenu.vue";
import { loadCollection as loadCollectionService } from "@/services/collection";
import { getCollectionShareUrl } from "@/utils/format/shareUrl";
import { useCopyText } from "@/composables/useCopyText";
import { useCollectionSubscribe } from "@/composables/collection/useCollectionSubscribe";
import { usePlaylistManage } from "@/composables/collection/usePlaylistManage";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import SongList from "@/components/list/SongList.vue";
import { formatTime } from "@/utils/time";
import * as player from "@/core/player";
import IconLucidePencil from "~icons/lucide/pencil";
import IconLucideTrash2 from "~icons/lucide/trash-2";
import IconLucideListChecks from "~icons/lucide/list-checks";
import IconLucideListMusic from "~icons/lucide/list-music";
import IconLucideHourglass from "~icons/lucide/hourglass";
import IconLucideCalendar from "~icons/lucide/calendar";
import IconLucideUser from "~icons/lucide/user";
import IconLucideChevronDown from "~icons/lucide/chevron-down";
import IconMaterialSymbolsFavoriteRounded from "~icons/material-symbols/favorite-rounded";
import IconMaterialSymbolsFavoriteOutlineRounded from "~icons/material-symbols/favorite-outline-rounded";
import IconMoreHorizontal from "~icons/lucide/more-horizontal";
import IconCopy from "~icons/lucide/copy";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const { copy } = useCopyText();
const { useMobileLayout } = useResponsiveLayout();

const source = route.params.source as TrackSource;
const type = route.params.type as CollectionType;
const id = route.params.id as string;

const collection = shallowRef<Collection | null>(null);
/** 正在加载 */
const loading = ref(false);
/** 错误信息 */
const error = ref("");
/** 取消当次加载 */
let loadAbort: AbortController | null = null;

/** 折叠状态 */
const collapsed = ref(false);
/** 简介弹窗 */
const descriptionOpen = ref(false);

/** 滚动超过阈值折叠 */
const handleListScroll = (event: Event) => {
  const scrollTop = (event.target as HTMLElement).scrollTop;
  if (!collapsed.value && scrollTop > 10) {
    collapsed.value = true;
  } else if (collapsed.value && scrollTop === 0) {
    collapsed.value = false;
  }
};

/** 加载数据 */
const loadCollection = async (): Promise<void> => {
  collapsed.value = false;
  loadAbort?.abort();
  const myAbort = new AbortController();
  loadAbort = myAbort;
  loading.value = true;
  error.value = "";

  try {
    await loadCollectionService(source, type, id, {
      fallbackName: typeof route.query.name === "string" ? route.query.name : undefined,
      signal: myAbort.signal,
      onUpdate: (next) => {
        if (myAbort.signal.aborted) return;
        collection.value = next;
      },
    });
  } catch (err) {
    if (myAbort.signal.aborted) return;
    error.value = err instanceof Error ? err.message : String(err);
  } finally {
    if (!myAbort.signal.aborted) loading.value = false;
  }
};

/**
 * 乐观过滤本地 tracks
 * @param removedIds 已成功删除的曲目 id 列表
 */
const handleTracksRemoved = (removedIds: string[]): void => {
  if (!collection.value || removedIds.length === 0) return;
  const removed = new Set(removedIds);
  const tracks = collection.value.tracks.filter((track) => !removed.has(track.id));
  collection.value = {
    ...collection.value,
    tracks,
    trackCount: tracks.length,
  };
};

const typeLabel = computed(() => {
  const map: Record<CollectionType, string> = {
    album: t("collection.album"),
    playlist: t("collection.playlist"),
    radio: t("collection.radio"),
    cloud: t("cloud.title"),
  };
  return map[type] ?? "";
});

/** 合集所属范围 */
const scopeLabel = computed(() =>
  t(source === "local" ? "collection.scope.local" : "collection.scope.online"),
);

/** 总时长 */
const totalDuration = computed(() => {
  if (!collection.value) return "";
  const total = collection.value.tracks.reduce((sum, t) => sum + t.duration, 0);
  return total > 0 ? formatTime(total) : "";
});

/** 歌手文本 */
const artistText = computed(() => {
  if (!collection.value?.artists?.length) return "";
  return collection.value.artists.map((a) => a.name).join(" / ");
});

/** 歌手或创建者 */
const creatorText = computed(() => {
  return artistText.value || collection.value?.creator || "";
});

/** 更新时间文本 */
const updateTimeText = computed(() => {
  if (!collection.value?.updateTime) return "";
  return new Date(collection.value.updateTime).toLocaleDateString();
});

const playbackContext = computed<PlaybackContext | undefined>(() => {
  const current = collection.value;
  if (!current || current.type === "cloud") return undefined;
  return {
    provider: current.source,
    originId: current.id,
    originType: current.type,
    originName: current.title,
  };
});

const handlePlayAll = () => {
  if (!collection.value?.tracks.length) return;
  player.playFrom(collection.value.tracks, 0, playbackContext.value);
};

const searchQuery = ref("");
const mobileSearchExpanded = ref(false);
const mobileSearchWrapperRef = ref<HTMLElement | null>(null);
const mobileSearchInputRef = ref<{ focus: () => void } | null>(null);

const mobileSearchStyle = computed(() => ({
  width: mobileSearchExpanded.value ? "calc(var(--page-zoom-100vw, 100vw) - 40px)" : "100%",
  left: "0",
  height: "2.75rem",
}));

const expandMobileSearch = (): void => {
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

/** 移动端搜索展开后，点击外部收起 */
const onDocumentPointerDown = (event: PointerEvent): void => {
  if (!mobileSearchExpanded.value) return;
  if (mobileSearchWrapperRef.value?.contains(event.target as Node)) return;
  collapseMobileSearch();
};

/** 歌曲列表引用 */
const songListRef = shallowRef<InstanceType<typeof SongList> | null>(null);

/** 收藏 / 取消收藏 */
const subscribe = useCollectionSubscribe(collection);

/** 歌单管理：编辑 + 删除 */
const manage = usePlaylistManage(collection, {
  onEdited: () => loadCollection(),
  onDeleted: () => {
    if (window.history.length > 1) router.back();
    else router.replace("/");
  },
});

/** 更多菜单 */
const editLabel = computed(() => t("collection.edit", { type: typeLabel.value }));

const moreMenuItems = computed<DropdownMenuItem[]>(() => {
  const isOnline = source !== "local" && source !== "streaming";
  const isLocal = source === "local";
  const list: DropdownMenuItem[] = [
    { key: "batchManage", label: t("songList.batch.manage"), icon: IconLucideListChecks },
    { key: "edit", label: editLabel.value, icon: IconLucidePencil, show: manage.canManage.value },
    {
      key: "delete",
      label: t("collection.delete", { type: typeLabel.value }),
      icon: IconLucideTrash2,
      separator: true,
      show: manage.canManage.value,
    },
    {
      key: "more",
      label: t("collection.context.more"),
      icon: markRaw(IconMoreHorizontal),
      children: [
        {
          key: "copyTitle",
          label: t(`collection.context.${type}.copyTitle`),
          icon: markRaw(IconCopy),
        },
        {
          key: "copyId",
          label: t(`collection.context.${type}.copyId`),
          icon: markRaw(IconCopy),
          show: !isLocal,
        },
        {
          key: "copyUrl",
          label: t(`collection.context.${type}.copyUrl`),
          icon: markRaw(IconCopy),
          show: isOnline && type !== "cloud",
        },
      ],
    },
  ];
  return list;
});

const handleMoreMenu = (key: string) => {
  switch (key) {
    case "batchManage":
      songListRef.value?.enterBatch();
      break;
    case "edit":
      manage.openEdit();
      break;
    case "delete":
      manage.openDelete();
      break;
    case "copyTitle":
      copy(collection.value?.title);
      break;
    case "copyId":
      copy(collection.value?.id);
      break;
    case "copyUrl":
      copy(getCollectionShareUrl(collection.value));
      break;
  }
};

watch(mobileSearchExpanded, (expanded) => {
  if (expanded) {
    nextTick(() => document.addEventListener("pointerdown", onDocumentPointerDown, true));
  } else {
    document.removeEventListener("pointerdown", onDocumentPointerDown, true);
  }
});

onMounted(() => {
  loadCollection();
});

onBeforeUnmount(() => {
  loadAbort?.abort();
  document.removeEventListener("pointerdown", onDocumentPointerDown, true);
});
</script>

<template>
  <div class="flex flex-col h-full">
    <!-- 头部信息 -->
    <div v-if="collection" class="shrink-0 px-5 pb-2">
      <div
        class="flex mt-2 transition-[gap,margin] duration-300"
        :class="[
          collapsed ? 'gap-3' : useMobileLayout ? 'gap-3' : 'gap-5',
          useMobileLayout ? 'items-start' : '',
        ]"
      >
        <!-- 封面 -->
        <SImg
          :src="collection.cover"
          :alt="collection.title"
          cache-type="list-covers"
          class="rounded-xl shrink-0 transition-[width,height] duration-300"
          :class="[
            collapsed
              ? useMobileLayout
                ? 'size-14'
                : 'size-20'
              : useMobileLayout
                ? 'size-24'
                : 'size-40',
          ]"
        />
        <!-- 信息 -->
        <div class="flex-1 flex flex-col min-w-0" :class="useMobileLayout ? 'py-0.5' : ''">
          <div
            class="flex flex-col transition-[gap] duration-300"
            :class="collapsed ? 'gap-0.5' : useMobileLayout ? 'gap-1' : 'gap-2'"
          >
            <div class="flex min-w-0 items-center gap-3">
              <h1
                class="min-w-0 flex-1 font-bold text-on-surface lh-normal transition-[font-size,line-height] duration-300"
                :class="[
                  collapsed
                    ? useMobileLayout
                      ? 'text-base truncate'
                      : 'text-xl truncate'
                    : useMobileLayout
                      ? 'text-xl line-clamp-2'
                      : 'text-3xl truncate',
                ]"
              >
                {{ collection.title }}
              </h1>
              <div
                v-if="!useMobileLayout"
                class="flex shrink-0 items-center gap-1 text-primary"
                :aria-label="`${scopeLabel} · ${typeLabel}`"
              >
                <STooltip :content="scopeLabel">
                  <span class="inline-flex size-6 cursor-default items-center justify-center">
                    <IconLucideHardDrive v-if="source === 'local'" class="size-4" />
                    <IconLucideGlobe2 v-else class="size-4" />
                  </span>
                </STooltip>
                <SDivider vertical />
                <STooltip :content="typeLabel">
                  <span
                    class="inline-flex size-6 cursor-default items-center justify-center text-primary/65"
                  >
                    <IconLucideDisc3 v-if="type === 'album'" class="size-4" />
                    <IconLucideListMusic v-else-if="type === 'playlist'" class="size-4" />
                    <IconLucideRadio v-else-if="type === 'radio'" class="size-4" />
                    <IconLucideCloud v-else class="size-4" />
                  </span>
                </STooltip>
              </div>
            </div>
            <div
              class="grid transition-[grid-template-rows,opacity] duration-300"
              :class="collapsed ? 'grid-rows-[0fr] opacity-0' : 'grid-rows-[1fr] opacity-100'"
            >
              <div class="overflow-hidden flex flex-col gap-2">
                <!-- 简介 -->
                <SButton
                  v-if="collection.description"
                  variant="text"
                  size="auto"
                  block
                  static
                  class="group max-w-full overflow-hidden text-left text-sm"
                  :aria-label="t('collection.viewIntroduction')"
                  @click="descriptionOpen = true"
                >
                  <span
                    class="min-w-0 text-on-surface-variant/70 transition-colors duration-200 group-hover:text-on-surface-variant group-focus-visible:text-on-surface-variant"
                    :class="
                      useMobileLayout ? 'line-clamp-3 whitespace-pre-line leading-5' : 'truncate'
                    "
                  >
                    {{ collection.description }}
                  </span>
                </SButton>
                <p v-else class="text-sm text-on-surface-variant/70 truncate">
                  {{ t("collection.noDescription") }}
                </p>
                <div
                  class="flex items-center gap-3 text-sm leading-none text-on-surface-variant/50"
                  :class="useMobileLayout ? 'flex-wrap gap-x-2 gap-y-1 text-xs' : ''"
                >
                  <span v-if="creatorText" class="flex items-center gap-1 min-w-0">
                    <IconLucideUser class="shrink-0" />
                    <span class="truncate">{{ creatorText }}</span>
                  </span>
                  <span class="flex items-center gap-1 shrink-0">
                    <IconLucideListMusic class="shrink-0" />
                    {{ t("common.totalSongs", { count: collection.tracks.length }) }}
                  </span>
                  <span v-if="totalDuration" class="flex items-center gap-1 shrink-0">
                    <IconLucideHourglass class="shrink-0" />
                    {{ t("collection.totalDuration", { time: totalDuration }) }}
                  </span>
                  <span v-if="updateTimeText" class="flex items-center gap-1 shrink-0">
                    <IconLucideCalendar class="shrink-0" />
                    {{ updateTimeText }}
                  </span>
                </div>
              </div>
            </div>
          </div>
          <!-- 桌面操作栏 -->
          <div v-if="!useMobileLayout" class="mt-auto flex items-center justify-between gap-3">
            <div class="flex items-center gap-3">
              <SButton
                type="primary"
                variant="secondary"
                round
                :disabled="collection.tracks.length === 0"
                @click="handlePlayAll"
              >
                <template #icon>
                  <IconLucidePlay />
                </template>
                {{ t("common.playAll") }}
              </SButton>
              <SButton
                v-if="subscribe.available.value"
                variant="secondary"
                round
                :disabled="subscribe.busy.value"
                @click="subscribe.toggle"
              >
                <template #icon>
                  <IconMaterialSymbolsFavoriteRounded v-if="subscribe.isSubscribed.value" />
                  <IconMaterialSymbolsFavoriteOutlineRounded v-else />
                </template>
                {{
                  t(
                    subscribe.isSubscribed.value
                      ? "collection.unsubscribe"
                      : "collection.subscribe",
                  )
                }}
              </SButton>
              <SDropdownMenu
                v-if="moreMenuItems.length > 0"
                :items="moreMenuItems"
                align="start"
                @select="handleMoreMenu"
              >
                <template #trigger>
                  <SButton variant="secondary" circle :size="useMobileLayout ? 32 : undefined">
                    <template #icon>
                      <IconLucideEllipsis />
                    </template>
                  </SButton>
                </template>
              </SDropdownMenu>
            </div>
            <div class="relative h-9 shrink-0 w-40">
              <SInput
                v-model="searchQuery"
                :placeholder="t('common.search')"
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
        :class="collapsed ? 'mt-2' : 'mt-4'"
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
            class="collection-mobile-search absolute left-0 top-0 overflow-hidden transition-[border-color,box-shadow,background-color,width,right,opacity] duration-250"
            :class="
              mobileSearchExpanded
                ? 'z-50 backdrop-blur-lg bg-surface/80 shadow-lg ring-2 ring-primary/20'
                : 'collection-mobile-search--collapsed'
            "
            :style="mobileSearchStyle"
            @keydown.escape="collapseMobileSearch"
          >
            <template #prefix>
              <IconLucideSearch class="size-4 shrink-0" />
            </template>
            <template v-if="mobileSearchExpanded" #suffix>
              <button
                type="button"
                class="collection-mobile-search__collapse"
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
          class="collection-mobile-action collection-mobile-action--primary"
          :disabled="collection.tracks.length === 0"
          @click="handlePlayAll"
        >
          <IconLucidePlay class="size-4 shrink-0" />
          <span class="collection-mobile-action__label">{{ t("common.playAll") }}</span>
        </button>
        <button
          v-if="subscribe.available.value"
          type="button"
          class="collection-mobile-action collection-mobile-action--compact"
          :disabled="subscribe.busy.value"
          @click="subscribe.toggle"
        >
          <IconMaterialSymbolsFavoriteRounded
            v-if="subscribe.isSubscribed.value"
            class="size-4 shrink-0"
          />
          <IconMaterialSymbolsFavoriteOutlineRounded v-else class="size-4 shrink-0" />
          <span class="collection-mobile-action__label">
            {{
              t(subscribe.isSubscribed.value ? "collection.unsubscribe" : "collection.subscribe")
            }}
          </span>
        </button>
        <div v-else />
        <SDropdownMenu
          v-if="moreMenuItems.length > 0"
          :items="moreMenuItems"
          align="end"
          @select="handleMoreMenu"
        >
          <template #trigger>
            <button
              type="button"
              class="collection-mobile-action collection-mobile-action--compact w-full"
            >
              <IconLucideEllipsis class="size-4 shrink-0" />
              <span class="collection-mobile-action__label">{{ t("common.more") }}</span>
            </button>
          </template>
        </SDropdownMenu>
        <div v-else />
      </div>
    </div>
    <Transition name="fade" mode="out-in" :duration="150">
      <div
        v-if="collection && collection.tracks.length > 0"
        :key="collection.id"
        class="flex-1 min-h-0"
      >
        <SongList
          ref="songListRef"
          :items="collection.tracks"
          :search-query="searchQuery"
          :show-album="type !== 'album'"
          :show-size="source === 'local'"
          :source="source"
          :collection-type="type"
          :collection-id="id"
          :playback-context="playbackContext"
          :can-remove="manage.canManage.value"
          enable-sort
          @scroll="handleListScroll"
          @change="handleTracksRemoved"
        />
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
          <SButton type="primary" variant="secondary" @click="loadCollection">
            <template #icon><IconLucideRefreshCw /></template>
            {{ t("common.retry") }}
          </SButton>
        </div>
      </div>
      <!-- 空状态 -->
      <div v-else-if="collection" key="empty" class="flex-1 flex items-center justify-center">
        <div class="text-center text-on-surface-variant/50">
          <IconLucideMusic class="size-12 mx-auto mb-3 opacity-30" />
          <div class="text-sm">{{ t("collection.empty") }}</div>
        </div>
      </div>
    </Transition>
    <!-- 简介全文 -->
    <SDialog
      v-model:open="descriptionOpen"
      :title="t('collection.introduction', { type: typeLabel })"
      width="min(520px, calc(100vw - 40px))"
    >
      <p class="whitespace-pre-wrap break-words leading-6 text-on-surface-variant">
        {{ collection?.description }}
      </p>
    </SDialog>
    <!-- 编辑弹窗 -->
    <SDialog v-model:open="manage.editOpen.value" :title="editLabel" width="400px">
      <div class="flex flex-col gap-4">
        <SFormItem :label="t('collection.name', { type: typeLabel })">
          <SInput v-model="manage.editTitle.value" :disabled="manage.submitting.value" />
        </SFormItem>
        <SFormItem :label="t('collection.description', { type: typeLabel })">
          <SInput v-model="manage.editDescription.value" :disabled="manage.submitting.value" />
        </SFormItem>
      </div>
      <template #footer="{ close }">
        <SButton variant="secondary" :disabled="manage.submitting.value" @click="close">
          {{ t("common.cancel") }}
        </SButton>
        <SButton
          type="primary"
          :disabled="!manage.editTitle.value.trim()"
          :loading="manage.submitting.value"
          @click="manage.saveEdit"
        >
          {{ t("common.confirm") }}
        </SButton>
      </template>
    </SDialog>
    <!-- 删除确认 -->
    <SDialog
      v-model:open="manage.deleteOpen.value"
      :title="t('collection.delete', { type: typeLabel })"
    >
      <p class="text-sm text-on-surface-variant">
        {{ t("collection.deleteConfirm", { type: typeLabel, title: collection?.title ?? "" }) }}
      </p>
      <template #footer="{ close }">
        <SButton variant="secondary" :disabled="manage.deleting.value" @click="close">
          {{ t("common.cancel") }}
        </SButton>
        <SButton type="error" :loading="manage.deleting.value" @click="manage.confirmDelete">
          {{ t("common.confirm") }}
        </SButton>
      </template>
    </SDialog>
  </div>
</template>

<style scoped>
.collection-mobile-action {
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

.collection-mobile-action:not(:disabled):active {
  transform: scale(0.96);
  background: rgb(var(--s-on-surface) / 0.16);
}

.collection-mobile-action:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.collection-mobile-action--primary {
  background: rgb(var(--s-primary) / 0.16);
  color: rgb(var(--s-primary));
}

.collection-mobile-action--primary:not(:disabled):active {
  background: rgb(var(--s-primary) / 0.24);
}

.collection-mobile-action--compact {
  gap: 0.25rem;
  padding-left: 0.5rem;
  padding-right: 0.5rem;
}

.collection-mobile-action__label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.75rem;
  line-height: 1;
  font-weight: 600;
}

.collection-mobile-search {
  cursor: pointer;
  transition:
    border-color 250ms cubic-bezier(0.4, 0, 0.2, 1),
    box-shadow 250ms cubic-bezier(0.4, 0, 0.2, 1),
    background-color 250ms cubic-bezier(0.4, 0, 0.2, 1),
    width 250ms cubic-bezier(0.4, 0, 0.2, 1),
    right 250ms cubic-bezier(0.4, 0, 0.2, 1),
    opacity 250ms cubic-bezier(0.4, 0, 0.2, 1);
}

.collection-mobile-search--collapsed {
  justify-content: center;
  gap: 0;
  padding-left: 0;
  padding-right: 0;
  border-color: transparent;
  background: rgb(var(--s-on-surface) / 0.1);
  color: rgb(var(--s-on-surface));
}

.collection-mobile-search--collapsed :deep(input) {
  flex: 0 0 0;
  width: 0;
  opacity: 0;
}

.collection-mobile-search__collapse {
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

.collection-mobile-search__collapse:active {
  background: rgb(var(--s-on-surface) / 0.16);
  color: rgb(var(--s-on-surface));
}
</style>
