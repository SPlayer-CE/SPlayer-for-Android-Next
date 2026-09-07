<script setup lang="ts">
import { isAndroid } from "@/services/bridge";

export interface SCardProps {
  /** 卡片标题 */
  title?: string;
  /** 背景变体 */
  variant?: "default" | "primary" | "settings";
  /** 是否显示边框 */
  bordered?: boolean;
  /** 内边距尺寸 */
  size?: "small" | "medium" | "large";
  /** 圆角尺寸 */
  radius?: "md" | "lg" | "xl";
  /** 鼠标悬浮高亮 */
  hoverable?: boolean;
  /** 是否选中态 */
  selected?: boolean;
  /** 无内边距 */
  flush?: boolean;
}

const props = withDefaults(defineProps<SCardProps>(), {
  variant: "default",
  bordered: true,
  size: "medium",
  radius: "lg",
  selected: false,
});

/** 变体到背景 / 边框样式的映射 */
const variantClass: Record<NonNullable<SCardProps["variant"]>, { bg: string; border: string }> = {
  default: { bg: "bg-surface-panel", border: "border-primary/20" },
  primary: { bg: "bg-primary/5", border: "border-primary/25" },
  settings: { bg: "bg-surface-panel dark:bg-on-surface/5", border: "border-primary/20" },
};

const sizePadding: Record<NonNullable<SCardProps["size"]>, string> = {
  small: "px-3 py-2",
  medium: "px-4 py-3",
  large: "px-5 py-4",
};

const radiusClass: Record<NonNullable<SCardProps["radius"]>, string> = {
  md: "rounded-md",
  lg: "rounded-lg",
  xl: "rounded-xl",
};

const slots = useSlots();
const structured = computed(() => !!slots.header || !!slots["header-extra"] || !!slots.footer);

const cardClass = computed(() => {
  const classes = [
    variantClass[props.variant].bg,
    radiusClass[props.radius],
    props.bordered && [
      "border border-solid",
      props.selected ? "border-primary" : variantClass[props.variant].border,
    ],
    !structured.value && !props.title && !props.flush && sizePadding[props.size],
  ];
  if (props.hoverable) {
    classes.push(
      isAndroid ? "cursor-pointer active:bg-on-surface/6" : "cursor-pointer hover:shadow-md",
    );
  }
  return classes;
});
</script>

<template>
  <div
    class="transition-[background-color,border-color,box-shadow] duration-200"
    :class="cardClass"
  >
    <!-- 结构化：头部 -->
    <template v-if="structured || props.title">
      <div
        class="flex items-center justify-between gap-2"
        :class="[sizePadding[props.size], $slots.default && 'pb-2']"
      >
        <div class="min-w-0 flex-1 text-base font-medium">
          <slot name="header">{{ props.title }}</slot>
        </div>
        <div v-if="$slots['header-extra']" class="shrink-0">
          <slot name="header-extra" />
        </div>
      </div>
      <!-- 主体 -->
      <div
        v-if="$slots.default"
        :class="[sizePadding[props.size], 'pt-0', $slots.footer && 'pb-2']"
      >
        <slot />
      </div>
      <!-- 底部 -->
      <div v-if="$slots.footer" :class="[sizePadding[props.size], 'pt-0']">
        <slot name="footer" />
      </div>
    </template>

    <!-- 简单模式 -->
    <slot v-else />
  </div>
</template>
