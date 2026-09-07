<script setup lang="ts">
import { isAndroid } from "@/services/bridge";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import { useSettingsDialog } from "@/settings/useSettingsDialog";
import { useWindowControls } from "@/composables/useWindowControls";
import { useThemeStore } from "@/stores/theme";
import { useUpdateStore } from "@/stores/update";
import type { DropdownMenuItem } from "@/components/ui/SDropdownMenu.vue";
import IconSun from "~icons/lucide/sun";
import IconMoon from "~icons/lucide/moon";
import IconMonitor from "~icons/lucide/monitor";
import IconRefreshCw from "~icons/lucide/refresh-cw";
import IconTerminal from "~icons/lucide/terminal";
import IconSettings from "~icons/lucide/settings";
import IconScaling from "~icons/lucide/scaling";
import IconWifi from "~icons/lucide/wifi";
import LanSharePanel from "@/components/settings/custom/LanSharePanel.vue";

const router = useRouter();
const { t } = useI18n();
const { show: showSettings } = useSettingsDialog();
const theme = useThemeStore();
const update = useUpdateStore();
const { isBorderless } = useWindowControls();
const { useMobileLayout } = useResponsiveLayout();

/** 界面缩放弹窗开关 */
const uiZoomOpen = ref(false);

/** 局域网页面流转弹窗开关 */
const lanShareOpen = ref(false);

const themeIcon = computed(() => {
  if (theme.mode === "light") return IconMoon;
  if (theme.mode === "dark") return IconMonitor;
  return IconSun;
});

const themeLabel = computed(() => {
  if (theme.mode === "light") return t("settings.themeMode.dark");
  if (theme.mode === "dark") return t("settings.themeMode.system");
  return t("settings.themeMode.light");
});

const menuItems = computed<DropdownMenuItem[]>(() => [
  {
    key: "theme",
    label: themeLabel.value,
    icon: themeIcon.value,
    disabled: theme.appearanceStyle === "image",
  },
  { key: "uiZoom", label: t("uiZoom.title"), icon: IconScaling },
  { key: "lanShare", label: t("settings.lanShare.menuLabel"), icon: IconWifi },
  { key: "reload", label: t("nav.reload"), icon: IconRefreshCw, separator: true },
  { key: "devtools", label: t("nav.devtools"), icon: IconTerminal },
  { key: "settings", label: t("nav.globalSettings"), icon: IconSettings },
]);

const onMenuSelect = (key: string): void => {
  if (key === "theme") theme.cycleMode();
  else if (key === "reload") location.reload();
  else if (key === "devtools") window.api.system.toggleDevTools();
  else if (key === "uiZoom") uiZoomOpen.value = true;
  else if (key === "lanShare") lanShareOpen.value = true;
  else if (key === "settings") showSettings();
};
</script>

<template>
  <div class="flex items-center justify-between flex-1 h-full min-w-0 app-drag-region relative">
    <!-- 左侧 -->
    <div class="flex items-center gap-2 sm:gap-3 min-w-0 shrink-0">
      <SButton
        v-if="!useMobileLayout"
        class="app-no-drag shrink-0"
        variant="tertiary"
        circle
        :size="40"
        :icon-size="20"
        @click="router.back()"
      >
        <template #icon><IconLucideChevronLeft /></template>
      </SButton>
      <SButton
        v-if="!useMobileLayout"
        class="app-no-drag shrink-0"
        variant="tertiary"
        circle
        :size="40"
        :icon-size="20"
        @click="router.forward()"
      >
        <template #icon><IconLucideChevronRight /></template>
      </SButton>
      <NavSearch />
      <template v-if="useMobileLayout">
        <SButton
          class="app-no-drag"
          variant="tertiary"
          circle
          :size="44"
          :icon-size="20"
          @click="router.back()"
        >
          <template #icon><IconLucideChevronLeft /></template>
        </SButton>
        <SButton
          class="app-no-drag"
          variant="tertiary"
          circle
          :size="44"
          :icon-size="20"
          @click="router.forward()"
        >
          <template #icon><IconLucideChevronRight /></template>
        </SButton>
      </template>
      <SButton
        v-if="update.hasUpdate"
        class="app-no-drag shrink-0"
        variant="tertiary"
        circle
        :size="40"
        :icon-size="20"
        :title="t('update.dialogTitle')"
        @click="update.openDialog()"
      >
        <template #icon><IconLucideCircleArrowUp /></template>
      </SButton>
    </div>
    <!-- 中间 -->
    <div class="flex-1 h-full min-w-4" />
    <!-- 右侧 -->
    <div class="flex items-center gap-2 sm:gap-3 shrink-0">
      <NavUser />
      <SDropdownMenu :items="menuItems" @select="onMenuSelect">
        <template #trigger>
          <SButton class="app-no-drag shrink-0" variant="tertiary" circle :size="40">
            <template #icon><IconLucideSettings /></template>
          </SButton>
        </template>
      </SDropdownMenu>
      <template v-if="!isAndroid && isBorderless">
        <SDivider vertical />
        <WindowControls />
      </template>
    </div>
    <UiZoomDialog v-model:open="uiZoomOpen" />
    <!-- 局域网页面流转弹窗 -->
    <SDialog v-model:open="lanShareOpen" :title="t('settings.lanShare.menuLabel')" width="480px">
      <LanSharePanel />
    </SDialog>
  </div>
</template>
