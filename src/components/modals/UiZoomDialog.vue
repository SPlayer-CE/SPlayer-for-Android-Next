<script setup lang="ts">
import { isAndroid } from "@/services/bridge";
import { useSettingsStore } from "@/stores/settings";
import { dialog } from "@/composables/useDialog";
import type { DeviceModeOverride } from "@/composables/useResponsiveLayout";

const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{ "update:open": [value: boolean] }>();

const { t } = useI18n();
const settingsStore = useSettingsStore();

/** 当前缩放百分比 */
const percent = ref(100);

/** Electron 端写入主进程配置 */
const applyElectron = (value: number): void => {
  percent.value = value;
  window.api.config.set("system.uiZoom", value).catch(() => {});
};

/** Android 端写入 settings store（usePageZoom composable 自动应用） */
const applyAndroid = (value: number): void => {
  percent.value = value;
  settingsStore.appearance.pageZoom = value;
};

/** 写入并应用缩放 */
const apply = (value: number): void => {
  if (isAndroid) applyAndroid(value);
  else applyElectron(value);
};

/** 恢复默认 100% */
const reset = (): void => apply(100);

/** 设备形态覆盖选项 */
const deviceModeOptions = computed<{ value: DeviceModeOverride; label: string }[]>(() => [
  { value: "auto", label: t("uiZoom.deviceModeAuto") },
  { value: "phone", label: t("uiZoom.deviceModePhone") },
  { value: "pad", label: t("uiZoom.deviceModePad") },
]);

/** 切换设备形态：恢复 auto 直接生效，强制模式需确认 */
const handleDeviceModeChange = (mode: DeviceModeOverride): void => {
  if (mode === settingsStore.appearance.androidDeviceModeOverride) return;
  if (mode === "auto") {
    settingsStore.appearance.androidDeviceModeOverride = mode;
    return;
  }
  dialog
    .confirm({
      title: t("uiZoom.deviceModeConfirmTitle"),
      content: t("uiZoom.deviceModeConfirmContent"),
      confirmText: t("uiZoom.deviceModeConfirm"),
      cancelText: t("common.cancel"),
      type: "warning",
    })
    .then((confirmed) => {
      if (confirmed) {
        settingsStore.appearance.androidDeviceModeOverride = mode;
      }
    });
};

// 打开时读取当前缩放值
watch(
  () => props.open,
  async (isOpen) => {
    if (!isOpen) return;
    if (isAndroid) {
      percent.value = settingsStore.appearance.pageZoom ?? 100;
    } else {
      const saved = await window.api.config.get("system.uiZoom");
      percent.value = typeof saved === "number" ? saved : 100;
    }
  },
);
</script>

<template>
  <SDialog
    :open="open"
    :title="t('uiZoom.title')"
    width="360px"
    @update:open="emit('update:open', $event)"
  >
    <div class="flex flex-col items-center gap-5 py-2">
      <span class="text-sm text-on-surface-variant">{{ t("uiZoom.range") }}</span>
      <SNumberInput
        :model-value="percent"
        :min="50"
        :max="200"
        :step="5"
        unit="%"
        size="large"
        class="w-50"
        @update:model-value="apply"
      />
    </div>
    <!-- Android 设备形态覆盖 -->
    <div v-if="isAndroid" class="flex flex-col gap-2 pt-4 border-t border-outline-variant">
      <div class="flex items-center justify-between">
        <span class="text-sm font-medium">{{ t("uiZoom.deviceMode") }}</span>
        <span class="text-xs text-on-surface-variant">{{ t("uiZoom.deviceModeHint") }}</span>
      </div>
      <div class="flex gap-2">
        <SButton
          v-for="opt in deviceModeOptions"
          :key="opt.value"
          :variant="
            settingsStore.appearance.androidDeviceModeOverride === opt.value
              ? 'filled'
              : 'secondary'
          "
          size="small"
          @click="handleDeviceModeChange(opt.value)"
        >
          {{ opt.label }}
        </SButton>
      </div>
    </div>
    <template #footer>
      <SButton variant="secondary" @click="reset">{{ t("uiZoom.reset") }}</SButton>
    </template>
  </SDialog>
</template>
