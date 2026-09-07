<script setup lang="ts">
import type { SettingItem } from "@/types/settings-schema";
import { useSettingModel } from "@/settings/useSettingModel";
import { dialog } from "@/composables/useDialog";

const props = defineProps<{
  item: SettingItem;
  highlighted?: boolean;
}>();

const { t } = useI18n();

const model = props.item.binding ? useSettingModel(props.item.binding) : ref<any>();

/** 应用变更 */
const applyChange = async (next: unknown): Promise<void> => {
  const cfg = props.item.confirm;
  if (cfg && (!cfg.when || cfg.when(next))) {
    const confirmed = await dialog.confirm({
      title: cfg.titleKey ? t(cfg.titleKey) : undefined,
      content: t(cfg.contentKey),
      type: cfg.type ?? "warning",
      confirmText: cfg.confirmTextKey ? t(cfg.confirmTextKey) : undefined,
      cancelText: cfg.cancelTextKey ? t(cfg.cancelTextKey) : undefined,
    });
    if (!confirmed) return;
  }
  model.value = next;
  await props.item.action?.(next);
};

const selectOptions = computed(() =>
  (props.item.options ?? []).map((o) => ({
    value: o.value,
    label: o.label ?? (o.labelKey ? t(o.labelKey) : String(o.value)),
  })),
);

const isChildrenActive = computed(() => {
  if (props.item.childrenCondition) return props.item.childrenCondition();
  return model.value === true;
});

const isDisabled = computed(() => props.item.disabled?.() ?? false);
const isVisible = computed(() => props.item.visible?.() ?? true);
const resolvedType = computed(() =>
  props.item.type === "slider" && props.item.renderAsNumberWhen?.() ? "number" : props.item.type,
);

const descriptionText = computed(() =>
  t(props.item.descriptionKey ?? `settings.${props.item.key}.description`),
);
</script>

<template>
  <div v-if="isVisible" :id="`setting-${item.key}`">
    <component
      :is="item.component"
      v-if="item.type === 'custom' && item.fullWidth && item.component"
      v-bind="item.componentProps"
      class="transition-all duration-300"
      :class="highlighted ? 'animate-highlight-pulse' : ''"
    />
    <div
      v-else
      class="settings-item-card flex items-center justify-between gap-4 rounded-xl bg-surface-panel border border-solid border-outline-variant/15 px-4 py-3.5 transition-all duration-300"
      :class="highlighted ? 'animate-highlight-pulse' : ''"
    >
      <div class="settings-item-label min-w-0 flex-1">
        <div class="flex items-center gap-2 text-[15px] sm:text-base font-medium">
          <span>{{ t(`settings.${item.key}.label`) }}</span>
          <STag v-if="item.tag" :type="item.tag.type ?? 'primary'">
            {{ item.tag.text }}
          </STag>
        </div>
        <div
          v-if="!item.hideDescription"
          class="text-xs sm:text-sm text-on-surface-variant/70 mt-0.5 leading-relaxed"
        >
          {{ descriptionText }}
        </div>
      </div>

      <div class="settings-item-control shrink-0 flex justify-end w-50">
        <SSwitch
          v-if="resolvedType === 'switch'"
          :model-value="model"
          :disabled="isDisabled"
          @update:model-value="applyChange($event)"
        />
        <SSelect
          v-else-if="resolvedType === 'select'"
          :model-value="model"
          :options="selectOptions"
          :disabled="isDisabled"
          @update:model-value="applyChange($event)"
        />
        <SSlider
          v-else-if="resolvedType === 'slider'"
          :model-value="model"
          :min="item.min ?? 0"
          :max="item.max ?? 100"
          :step="item.step ?? 1"
          :marks="item.marks"
          :disabled="isDisabled"
          class="w-full"
          :thumb-size="14"
          :track-height="4"
          always-show-thumb
          show-popover
          @change="applyChange($event)"
        >
          <template #popover="{ value }">{{ value }}</template>
        </SSlider>
        <SColor
          v-else-if="resolvedType === 'color'"
          :model-value="model"
          :disabled="isDisabled"
          :show-alpha="item.showAlpha"
          :format="item.colorFormat"
          @update:model-value="applyChange($event)"
        />
        <SButton
          v-else-if="resolvedType === 'button'"
          type="primary"
          variant="secondary"
          size="small"
          @click="item.action?.()"
        >
          {{ t(`settings.${item.key}.label`) }}
        </SButton>
        <SNumberInput
          v-else-if="resolvedType === 'number'"
          v-model="model"
          :min="item.min"
          :max="item.max"
          :step="item.step"
          :unit="item.unit"
          :placeholder="item.placeholderKey ? t(item.placeholderKey) : ''"
          :disabled="isDisabled"
          update-on="blur"
          class="w-full"
          @update:model-value="applyChange($event)"
        />
        <SInput
          v-else-if="item.type === 'text'"
          :model-value="model"
          :placeholder="item.placeholderKey ? t(item.placeholderKey) : ''"
          :disabled="isDisabled"
          update-on="blur"
          clearable
          class="w-full"
          @update:model-value="applyChange($event)"
        />
        <component
          :is="item.component"
          v-else-if="resolvedType === 'custom' && item.component"
          v-bind="item.componentProps"
          :model-value="model"
          @update:model-value="model = $event"
        />
      </div>
    </div>
    <div
      v-if="item.children?.length && (!item.hideChildren || isChildrenActive)"
      class="mt-2.5 flex flex-col gap-2.5 transition-opacity duration-200"
      :class="isChildrenActive ? '' : 'opacity-50 pointer-events-none'"
    >
      <SettingsItem v-for="child in item.children" :key="child.key" :item="child" />
    </div>
  </div>
</template>

<style scoped>
/* 使用容器查询：手机横屏下外部歌词页的标签较长、控件也更宽，640px 内统一上下堆叠更稳妥 */
.settings-item-card {
  container-type: inline-size;
}

@container (max-width: 640px) {
  .settings-item-card {
    flex-direction: column;
    align-items: stretch;
    gap: 0.625rem;
  }

  /* 窄容器（竖屏移动端）下两列各占整行，杜绝 Tailwind 工具类被 scope hash 隔离导致的覆盖失败。
     `flex: 1 1 100%` 在 column flex 上下文中等价于 cross-axis 撑满；同时清掉 .w-50 的固定 200px 与
     .flex-1 在 column 下隐含的 min-content 收缩，避免出现"主/题/模/式"竖排塌缩。 */
  .settings-item-card > .settings-item-label,
  .settings-item-card > .settings-item-control {
    flex: 1 1 100%;
    width: 100%;
    max-width: 100%;
    min-width: 0;
  }
}
</style>
