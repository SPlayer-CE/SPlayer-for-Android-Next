<script setup lang="ts">
import type { CoverItem } from "@/types/artist";
import type { ContentScope } from "@/types/collection";
import { useStatusStore } from "@/stores/status";
import { usePlaylistStore } from "@/stores/playlist";
import { useUserStore } from "@/stores/user";
import { playlistToCoverItem } from "@/utils/format/coverItem";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import CoverList from "@/components/list/CoverList.vue";
import PlaylistCreateDialog from "@/components/modals/PlaylistCreateDialog.vue";
import IconLucideListMusic from "~icons/lucide/list-music";
import IconLucidePlus from "~icons/lucide/plus";

const { t } = useI18n();
const router = useRouter();
const status = useStatusStore();
const playlistStore = usePlaylistStore();
const userStore = useUserStore();
const { useMobileLayout } = useResponsiveLayout();

const tab = computed({
  get: () => status.myPlaylistSource,
  set: (v: ContentScope) => (status.myPlaylistSource = v),
});

const tabs = computed(() => [
  { key: "local" satisfies ContentScope, label: t("collection.localPlaylist") },
  { key: "online" satisfies ContentScope, label: t("collection.onlinePlaylist") },
]);

const createDialogOpen = ref(false);
const createMode = ref<ContentScope>(tab.value);
const localLoading = ref(false);

const localItems = computed<CoverItem[]>(() =>
  playlistStore.playlists.map((playlist) => ({
    id: playlist.id,
    title: playlist.title,
    cover: playlist.cover,
    subtitle: t("common.totalSongs", { count: playlist.trackCount ?? 0 }),
    trackCount: playlist.trackCount ?? 0,
  })),
);

const onlineItems = computed<CoverItem[]>(() =>
  userStore.createdPlaylists.slice(1).map((playlist) => ({
    ...playlistToCoverItem(playlist),
    subtitle: playlist.trackCount ? t("common.totalSongs", { count: playlist.trackCount }) : "",
  })),
);

const currentItems = computed<CoverItem[]>(() =>
  tab.value === "local" ? localItems.value : onlineItems.value,
);

const canCreate = computed(() => tab.value === "local" || userStore.isLoggedIn);
const isOnlineNotLogin = computed(() => tab.value === "online" && !userStore.isLoggedIn);
const showLocalLoading = computed(() => tab.value === "local" && localLoading.value);
const emptyText = computed(() =>
  tab.value === "local" ? t("myPlaylists.emptyLocal") : t("myPlaylists.emptyOnline"),
);

const toCollectionSource = (source: ContentScope): "local" | "netease" =>
  source === "local" ? "local" : "netease";

const handleCreate = (): void => {
  if (!canCreate.value) return;
  createMode.value = tab.value;
  createDialogOpen.value = true;
};

const handleCreated = (playlistId: string): void => {
  router.push(`/collection/${toCollectionSource(createMode.value)}/playlist/${playlistId}`);
};

const handleClick = (item: CoverItem): void => {
  if (!item.id) return;
  router.push(
    `/collection/${toCollectionSource(tab.value)}/playlist/${encodeURIComponent(item.id)}`,
  );
};

onMounted(async () => {
  if (playlistStore.initialized) return;
  localLoading.value = true;
  try {
    await playlistStore.load();
  } finally {
    localLoading.value = false;
  }
});
</script>

<template>
  <div class="flex flex-col h-full">
    <!-- 顶栏 -->
    <div class="shrink-0 pb-2" :class="useMobileLayout ? 'px-4' : 'px-5'">
      <div
        class="flex items-center justify-between gap-4"
        :class="useMobileLayout ? 'mt-1 mb-3' : 'mt-2 mb-4'"
      >
        <div class="flex items-baseline gap-4 min-w-0">
          <h1
            class="font-bold text-on-surface shrink-0"
            :class="useMobileLayout ? 'text-2xl' : 'text-3xl'"
          >
            {{ t("myPlaylists.title") }}
          </h1>
          <Transition name="fade" mode="out-in">
            <span
              v-if="!isOnlineNotLogin"
              :key="tab"
              class="flex items-center gap-1.5 text-sm text-on-surface-variant/50 truncate"
            >
              <IconLucideListMusic class="size-3.5 shrink-0" />
              {{ t("common.totalPlaylists", { count: currentItems.length }) }}
            </span>
          </Transition>
        </div>
        <SButton
          type="primary"
          variant="secondary"
          round
          class="shrink-0"
          :disabled="!canCreate"
          @click="handleCreate"
        >
          <template #icon><IconLucidePlus /></template>
          {{ t("myPlaylists.create") }}
        </SButton>
      </div>
      <div :class="useMobileLayout ? 'w-full' : 'w-64'">
        <STabs v-model="tab" :tabs="tabs" type="segment" round />
      </div>
    </div>

    <!-- 内容 -->
    <Transition name="fade" mode="out-in" :duration="150">
      <div v-if="isOnlineNotLogin" key="login" class="flex-1 flex items-center justify-center">
        <div class="text-center text-on-surface-variant/50 px-8">
          <IconLucideListMusic class="size-12 mx-auto mb-3 opacity-30" />
          <div class="text-sm">{{ t("myPlaylists.needLogin") }}</div>
        </div>
      </div>
      <div
        v-else-if="showLocalLoading"
        key="loading"
        class="flex-1 flex items-center justify-center"
      >
        <div class="text-center text-on-surface-variant/60">
          <SLoading class="text-4xl text-primary/70 mb-4 mx-auto block" />
          <div class="text-sm">{{ t("common.loading") }}</div>
        </div>
      </div>
      <div v-else-if="currentItems.length > 0" :key="tab" class="flex-1 min-h-0">
        <CoverList
          :items="currentItems"
          :min-size="useMobileLayout ? 132 : 150"
          :padding-x="useMobileLayout ? 16 : 20"
          :padding-top="8"
          :padding-bottom="20"
          @click="handleClick"
        />
      </div>
      <div v-else key="empty" class="flex-1 flex items-center justify-center">
        <div class="text-center text-on-surface-variant/50 px-8">
          <IconLucideListMusic class="size-12 mx-auto mb-3 opacity-30" />
          <div class="text-sm mb-4">{{ emptyText }}</div>
          <SButton v-if="canCreate" type="primary" variant="secondary" round @click="handleCreate">
            <template #icon><IconLucidePlus /></template>
            {{ t("myPlaylists.create") }}
          </SButton>
        </div>
      </div>
    </Transition>

    <!-- 新建歌单 -->
    <PlaylistCreateDialog
      v-model:open="createDialogOpen"
      :mode="createMode"
      @created="handleCreated"
    />
  </div>
</template>
