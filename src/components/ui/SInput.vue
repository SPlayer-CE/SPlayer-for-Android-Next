<script setup lang="ts">
export interface SInputProps {
  modelValue?: string;
  placeholder?: string;
  disabled?: boolean;
  /** 只读：保留聚焦/事件，仅禁用键入 */
  readonly?: boolean;
  clearable?: boolean;
  round?: boolean;
  type?: "text" | "password" | "email" | "number" | "url" | "search" | "tel" | "textarea";
  /** 多行行数 */
  rows?: number;
  /** 是否显示原生缩放手柄 */
  resize?: "none" | "vertical" | "horizontal" | "both";
  /** 尺寸 */
  size?: "small" | "medium" | "large";
  /** 状态色（错误等） */
  status?: "default" | "error";
  /** 更新时机：input 立即更新；blur 在失焦或回车时提交 */
  updateOn?: "input" | "blur";
}

const props = withDefaults(defineProps<SInputProps>(), {
  modelValue: "",
  placeholder: "",
  disabled: false,
  readonly: false,
  clearable: false,
  round: false,
  type: "text",
  rows: 3,
  resize: "none",
  size: "medium",
  status: "default",
  updateOn: "input",
});

const isTextarea = computed(() => props.type === "textarea");

const resizeClass = computed(() => {
  switch (props.resize) {
    case "vertical":
      return "resize-y";
    case "horizontal":
      return "resize-x";
    case "both":
      return "resize";
    default:
      return "resize-none";
  }
});

const sizeClasses = computed(() => {
  if (isTextarea.value) {
    // 多行容器不限定高度，仅设字号 + 内边距
    if (props.size === "small") return "px-2 py-1.5 text-xs";
    if (props.size === "large") return "px-4 py-2.5 text-base";
    return "px-3 py-2 text-sm";
  }
  if (props.size === "small") return "h-8 px-2 text-xs";
  if (props.size === "large") return "h-10 px-4 text-base";
  return "h-9 px-3 text-sm";
});

const emit = defineEmits<{
  "update:modelValue": [value: string];
  focus: [];
  blur: [];
}>();

const isFocused = ref(false);
const showClear = computed(() => props.clearable && props.modelValue.length > 0 && !props.disabled);
const inputRef = ref<HTMLInputElement | null>(null);
const textareaRef = ref<HTMLTextAreaElement | null>(null);

const handleInput = (value: string): void => {
  emit("update:modelValue", value);
};

const commitValue = (): void => {};

const rollbackValue = (): void => {};

const handleClear = () => {
  handleInput("");
};

const handleBlur = (): void => {
  isFocused.value = false;
  if (props.updateOn === "blur") commitValue();
  emit("blur");
};

const handleEnter = (event: KeyboardEvent): void => {
  if (props.updateOn !== "blur") return;
  commitValue();
  (event.currentTarget as HTMLInputElement).blur();
};

const handleEscape = (event: KeyboardEvent): void => {
  if (props.updateOn === "blur") rollbackValue();
  (event.currentTarget as HTMLInputElement).blur();
};

const focus = (): void => {
  if (isTextarea.value) textareaRef.value?.focus();
  else inputRef.value?.focus();
};

defineExpose({ focus });
</script>

<template>
  <div
    class="text-on-surface border border-solid transition-[border-color,box-shadow,background-color,width,right,opacity] duration-250"
    :class="[
      isTextarea ? 'relative block' : 'flex items-center gap-2',
      sizeClasses,
      round ? 'rounded-full' : 'rounded-lg',
      isFocused
        ? status === 'error'
          ? 'bg-on-surface/8 border-red-500 ring-2 ring-red-500/20'
          : 'bg-on-surface/8 border-primary ring-2 ring-primary/20'
        : status === 'error'
          ? 'bg-on-surface/3 border-red-500/60 hover:bg-on-surface/10'
          : 'bg-on-surface/3 border-on-surface/15 hover:bg-on-surface/10 hover:border-on-surface/25',
      disabled ? 'opacity-50 cursor-not-allowed' : '',
    ]"
  >
    <!-- 多行 -->
    <template v-if="isTextarea">
      <textarea
        ref="textareaRef"
        :value="modelValue"
        :placeholder="placeholder"
        :disabled="disabled"
        :readonly="readonly"
        :rows="rows"
        class="w-full block bg-transparent outline-none border-none shadow-none text-on-surface placeholder:text-on-surface-variant/40 disabled:cursor-not-allowed"
        :class="[resizeClass, readonly ? 'cursor-pointer' : '', showClear ? 'pr-5' : '']"
        @input="handleInput(($event.target as HTMLTextAreaElement).value)"
        @focus="
          isFocused = true;
          emit('focus');
        "
        @blur="handleBlur"
        @keydown.esc="handleEscape"
      />
      <!-- 清空按钮（textarea 模式右上角浮动） -->
      <Transition name="fade">
        <IconLucideX
          v-if="showClear"
          class="absolute top-2 right-2 size-3.5 text-on-surface-variant/50 cursor-pointer transition-colors duration-200 hover:text-on-surface"
          @mousedown.prevent.stop="handleClear"
        />
      </Transition>
    </template>

    <template v-else>
      <!-- 前置插槽 -->
      <slot name="prefix" />

      <input
        ref="inputRef"
        :value="modelValue"
        :type="type"
        :placeholder="placeholder"
        :disabled="disabled"
        :readonly="readonly"
        class="flex-1 min-w-0 h-full bg-transparent outline-none border-none shadow-none text-on-surface placeholder:text-on-surface-variant/40 disabled:cursor-not-allowed"
        :class="readonly ? 'cursor-pointer' : ''"
        @input="handleInput(($event.target as HTMLInputElement).value)"
        @focus="
          isFocused = true;
          emit('focus');
        "
        @blur="handleBlur"
        @keydown.enter="handleEnter"
        @keydown.esc="handleEscape"
      />

      <!-- 清空按钮：mousedown.prevent 保留输入焦点，避免触发 blur 让 focus-within 宽度回缩导致点击错位 -->
      <Transition name="fade">
        <IconLucideX
          v-if="showClear"
          class="size-3.5 text-on-surface-variant/50 shrink-0 cursor-pointer transition-colors duration-200 hover:text-on-surface"
          @mousedown.prevent.stop="handleClear"
        />
      </Transition>

      <!-- 后置插槽 -->
      <slot name="suffix" />
    </template>
  </div>
</template>
