<script setup lang="ts">
export interface SSwitchProps {
  modelValue?: boolean;
  disabled?: boolean;
  /** 是否为圆形（默认圆形，false 为圆角矩形） */
  round?: boolean;
}

withDefaults(defineProps<SSwitchProps>(), {
  modelValue: false,
  disabled: false,
  round: true,
});

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
}>();
</script>

<template>
  <div class="inline-flex min-h-11 min-w-11 items-center justify-center">
    <SwitchRoot
      :model-value="modelValue"
      :disabled="disabled"
      class="group relative inline-flex !h-6 !min-h-6 !w-11 !min-w-11 shrink-0 items-center border-none p-0 cursor-pointer outline-none transition-[background-color] duration-300 disabled:opacity-40 disabled:cursor-not-allowed"
      :class="[
        modelValue
          ? 'bg-primary hover:bg-primary/90'
          : 'bg-outline-variant hover:bg-outline-variant/80',
        round ? 'rounded-full' : 'rounded-lg',
      ]"
      @update:model-value="emit('update:modelValue', $event)"
    >
      <SwitchThumb
        class="block h-5 w-5 bg-white shadow-sm pointer-events-none transition-[transform,width] duration-300 ease-[cubic-bezier(0.4,0,0.2,1)] group-active:w-6"
        :class="[
          modelValue ? 'translate-x-5 group-active:translate-x-4' : 'translate-x-0.5',
          round ? 'rounded-full' : 'rounded-md',
        ]"
      />
    </SwitchRoot>
  </div>
</template>
