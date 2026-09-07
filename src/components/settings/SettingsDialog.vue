<script setup lang="ts">
import { useSettingsDialog } from "@/settings/useSettingsDialog";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import bridge from "@/services/bridge";
import { isAndroid } from "@/services/bridge";

const dialog = useSettingsDialog();
const { open } = dialog;
const { useMobileLayout } = useResponsiveLayout();

const horizontalSafeArea = "var(--safe-area-left) - var(--safe-area-right)";
const verticalSafeArea = "var(--safe-area-top) - var(--safe-area-bottom)";
const dialogWidth = computed(() =>
  useMobileLayout.value
    ? `calc(100vw - ${horizontalSafeArea} - 32px)`
    : `min(1024px, calc(100vw - ${horizontalSafeArea} - 40px))`,
);
const dialogHeight = computed(() =>
  useMobileLayout.value
    ? `calc(100dvh - ${verticalSafeArea} - 32px)`
    : `min(820px, calc(100dvh - ${verticalSafeArea} - 80px))`,
);

const unsubscribe = bridge.system.onOpenSettings(({ category, highlight }) => {
  dialog.show(category, highlight);
});

onBeforeUnmount(() => unsubscribe());
</script>

<template>
  <SDialog
    v-model:open="open"
    :width="dialogWidth"
    :max-width="`calc(100vw - ${horizontalSafeArea} - 32px)`"
    :height="dialogHeight"
    content-style="overflow: hidden"
    :prevent-open-auto-focus="isAndroid"
    destroy-on-close
  >
    <SettingsContent class="h-full" />
  </SDialog>
</template>
