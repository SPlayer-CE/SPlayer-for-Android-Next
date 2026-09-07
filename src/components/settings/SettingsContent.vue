<script setup lang="ts">
import { settingsSchema } from "@/settings/schema";
import { useSettingsDialog } from "@/settings/useSettingsDialog";
import { useSettingsStore } from "@/stores/settings";
import { openExternal } from "@/utils/url";
import { REPO_URL, REPO_NAME, APP_VERSION, IS_APPX } from "@/utils/config";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import { filterSectionsByPlatform } from "@/settings/platformFilter";

const { initialCategory, initialHighlight, rememberCategory } = useSettingsDialog();
const { useMobileLayout } = useResponsiveLayout();

// 同步后端配置
useSettingsStore().syncSystem();
const { t } = useI18n();

const activeId = ref(initialCategory.value);
const highlightKey = ref(initialHighlight.value);
const scrollRef = ref<HTMLElement>();
const isSearchActive = ref(false);
const showMobileMenu = ref(true);

const activeCategory = computed(() => settingsSchema.find((c) => c.id === activeId.value));

/** 按平台过滤 section 与其 items（含子项），统一收敛到 platformFilter */
const visibleSections = computed(() =>
  filterSectionsByPlatform(activeCategory.value?.sections ?? []),
);

/** 计算每个 section 的全局起始索引 */
const sectionStartIndices = computed(() => {
  const indices: number[] = [];
  let idx = 0;
  for (const sec of visibleSections.value) {
    indices.push(idx);
    idx += 1 + sec.items.length;
  }
  return indices;
});

const onCategorySelect = (id: string) => {
  activeId.value = id;
  highlightKey.value = undefined;
  rememberCategory(id);
  if (useMobileLayout.value) {
    showMobileMenu.value = false;
  }
  nextTick(() => scrollRef.value?.scrollTo({ top: 0 }));
};

const onSearchSelect = (categoryId: string, itemKey: string) => {
  highlightKey.value = itemKey;
  if (activeId.value !== categoryId) {
    activeId.value = categoryId;
  }
  if (useMobileLayout.value) {
    showMobileMenu.value = false;
  }
  nextTick(() => {
    setTimeout(() => {
      const el = document.getElementById(`setting-${itemKey}`);
      el?.scrollIntoView({ block: "center", behavior: "smooth" });
      setTimeout(() => {
        highlightKey.value = undefined;
      }, 2500);
    }, 100);
  });
};

onMounted(() => {
  if (initialHighlight.value) {
    onSearchSelect(initialCategory.value, initialHighlight.value);
  }
});
</script>

<template>
  <div
    class="relative flex h-full overflow-hidden rounded-xl"
    :class="{ 'flex-col': useMobileLayout }"
  >
    <!-- 移动端遮罩层 -->
    <Transition name="fade">
      <div
        v-if="useMobileLayout && showMobileMenu"
        class="absolute inset-0 z-1 bg-black/40"
        @click="showMobileMenu = false"
      />
    </Transition>
    <!-- 左侧 -->
    <div
      v-show="!useMobileLayout || showMobileMenu"
      class="shrink-0 flex flex-col bg-surface-panel p-5"
      :class="useMobileLayout ? 'absolute left-0 top-0 bottom-0 z-2 w-[280px] shadow-lg' : 'w-70'"
    >
      <div class="mb-5 px-1">
        <h2 class="text-[22px] leading-tight font-bold mb-1">
          {{ t("settings.title") }}
        </h2>
        <p class="text-xs text-on-surface-variant/70">
          {{ t("settings.subtitle") }}
        </p>
      </div>

      <!-- 搜索 -->
      <SettingsSearch
        class="mb-4"
        @select="onSearchSelect"
        @active-change="isSearchActive = $event"
      />

      <!-- 菜单 -->
      <Transition name="fade">
        <div v-show="!isSearchActive" class="flex-1 min-h-0 overflow-y-auto -mr-5 pr-5">
          <SettingsMenu
            :categories="settingsSchema"
            :active-id="activeId"
            @select="onCategorySelect"
          />
        </div>
      </Transition>

      <!-- 底部 -->
      <div class="shrink-0 mt-auto pt-4 px-1 flex items-center gap-1">
        <SButton variant="text" size="tiny" @click="openExternal(REPO_URL)">
          <template #icon><IconLucideGithub /></template>
          {{ REPO_NAME }}
        </SButton>
        <STag size="tiny">v{{ APP_VERSION }}</STag>
        <STag v-if="IS_APPX" size="tiny">{{ t("settings.storeVersion") }}</STag>
      </div>
    </div>

    <!-- 右侧 -->
    <div
      v-show="!useMobileLayout || !showMobileMenu"
      ref="scrollRef"
      class="min-w-0 flex-1 overflow-y-auto bg-surface relative"
      :class="useMobileLayout ? 'p-4' : 'py-6 px-8'"
    >
      <div v-if="useMobileLayout" class="mb-4">
        <SButton variant="text" size="small" @click="showMobileMenu = true">
          <template #icon><IconLucideArrowLeft /></template>
          返回
        </SButton>
      </div>
      <div v-if="activeCategory" :key="activeCategory.id" class="animate-fade-in">
        <component :is="activeCategory.component" v-if="activeCategory.component" />
        <template v-else>
          <SettingsSection
            v-for="(sec, si) in visibleSections"
            :key="sec.id"
            :section="sec"
            :highlight-key="highlightKey"
            :start-index="sectionStartIndices[si] ?? 0"
          />
        </template>
      </div>
    </div>
  </div>
</template>
