<script setup lang="ts">
/**
 * 局域网协同 — 从设备同步状态徽章。
 * 实际播放/歌词/进度全部由本机现有播放服务呈现（PlayerBar/FullPlayer）。
 * 此徽章显示：连接状态、主机 IP、断开按钮；浏览器自动播放被拦截时提示点击开始。
 */
import { computed } from "vue";
import { useLanSync } from "@/composables/useLanSync";
import { useStatusStore } from "@/stores/status";
import IconLucideRadio from "~icons/lucide/radio";
import IconLucideX from "~icons/lucide/x";
import IconLucideLoader from "~icons/lucide/loader-2";
import IconLucidePlayCircle from "~icons/lucide/play-circle";

const props = defineProps<{ hostIp: string }>();
const emit = defineEmits<{ (e: "close"): void }>();

const { state } = useLanSync(props.hostIp);
const status = useStatusStore();

// 主机在播放但本机被浏览器自动播放策略拦截 → 需要用户点击启动
const needsGesture = computed(() => state.connected && state.playing && status.state !== "playing");

const statusText = computed(() => {
  if (!state.connected) return "连接中…";
  if (needsGesture.value) return "点击开始播放";
  return "已连接主机";
});
</script>

<template>
  <button
    class="lan-sync-badge fixed z-50 left-1/2 -translate-x-1/2 top-[calc(env(safe-area-inset-top,0px)+8px)] flex items-center gap-1.5 px-3 py-1.5 rounded-full border border-solid border-primary/20 bg-surface-panel/95 backdrop-blur-md shadow-lg pointer-events-auto transition-colors hover:bg-surface-panel"
    :class="needsGesture ? 'animate-pulse border-primary/50' : ''"
    :title="needsGesture ? '浏览器拦截了自动播放，点击此处开始' : `同步自主机 ${hostIp}`"
  >
    <IconLucideLoader v-if="!state.connected" class="size-3.5 text-primary animate-spin shrink-0" />
    <IconLucidePlayCircle v-else-if="needsGesture" class="size-3.5 text-primary shrink-0" />
    <IconLucideRadio v-else class="size-3.5 text-primary animate-pulse shrink-0" />
    <span class="text-xs font-medium text-on-surface">{{ statusText }}</span>
    <span class="text-[10px] text-on-surface-variant/50 font-mono max-w-[80px] truncate">
      {{ hostIp }}
    </span>
    <span
      class="ml-1 flex items-center justify-center size-4 rounded-full text-on-surface-variant/50 hover:text-error hover:bg-error/10 transition-colors"
      @click.stop="emit('close')"
    >
      <IconLucideX class="size-3" />
    </span>
  </button>
</template>
