<script setup lang="ts">
import { useStatusStore } from "@/stores/status";
import { useMediaStore } from "@/stores/media";
import * as player from "@/core/player";
import { useDragOpenPlayer } from "@/composables/useDragOpenPlayer";
import IconLucidePlay from "~icons/lucide/play";
import IconLucidePause from "~icons/lucide/pause";
import IconLucideSkipForward from "~icons/lucide/skip-forward";
import IconLucideListMusic from "~icons/lucide/list-music";

const status = useStatusStore();
const media = useMediaStore();
const { isPlaying, trackLoading } = storeToRefs(status);

// 上滑跟手开启全屏播放器（1:1 对齐 SPlayer-for-Android@70a5f861 drag-open 手势）
const { bindPointer, horizontalSwipeDirection } = useDragOpenPlayer();

// 横向滑动切歌：左滑下一首，右滑上一首
watch(horizontalSwipeDirection, (direction) => {
  if (direction === "left") void player.nextTrack(true);
  if (direction === "right") void player.prevTrack();
});

const guardClickAfterDrag = (e: MouseEvent): void => {
  bindPointer.onClick(e);
};

const openFullPlayer = (e: MouseEvent): void => {
  if (e.defaultPrevented) return;
  status.isPlayerExpanded = true;
};

const openFullQueue = (): void => {
  status.mobileQueueOpen = true;
};
</script>

<template>
  <div
    class="flex items-center h-16 bg-surface-panel rounded-[18px] shadow-xl border border-solid border-primary/10 px-2.5 mx-2.5 gap-3 cursor-pointer transition-transform active:scale-95 touch-none"
    @pointerdown="bindPointer.onDown"
    @pointermove="bindPointer.onMove"
    @pointerup="bindPointer.onEnd"
    @pointercancel="bindPointer.onEnd"
    @click.capture="guardClickAfterDrag"
    @click="openFullPlayer"
  >
    <!-- 封面 -->
    <div
      class="w-10 h-10 shrink-0 rounded-full overflow-hidden bg-surface-variant relative shadow-md"
    >
      <SImg v-if="media.track" :src="media.track.cover" class="w-full h-full object-cover" />
      <div v-else class="w-full h-full flex items-center justify-center bg-primary/10">
        <IconLucideMusic class="text-primary/50 text-sm" />
      </div>
    </div>

    <!-- 标题和歌手 -->
    <div class="flex-1 min-w-0 flex flex-col justify-center">
      <div class="text-[14px] font-medium text-on-surface truncate">
        {{ media.track ? media.track.title : "SPlayer" }}
      </div>
      <div class="text-[11px] text-on-surface-variant/70 truncate mt-0.5">
        {{ media.track ? media.track.artists.map((a) => a.name).join(" / ") : "聆听好音乐" }}
      </div>
    </div>

    <!-- 控制按钮 -->
    <div class="flex items-center gap-1 shrink-0">
      <SButton
        type="cover"
        variant="ghost"
        circle
        :size="36"
        :icon-size="20"
        :loading="trackLoading"
        class="text-on-surface"
        @click.stop="player.togglePlay()"
      >
        <template #icon>
          <IconLucidePause v-if="isPlaying" fill="currentColor" />
          <IconLucidePlay v-else fill="currentColor" class="ml-1" />
        </template>
      </SButton>
      <SButton
        type="cover"
        variant="ghost"
        circle
        :size="36"
        :icon-size="20"
        :disabled="!media.track"
        class="text-on-surface"
        @click.stop="player.nextTrack(true)"
      >
        <template #icon>
          <IconLucideSkipForward fill="currentColor" />
        </template>
      </SButton>
      <SButton
        type="cover"
        variant="ghost"
        circle
        :size="36"
        :icon-size="20"
        class="text-on-surface ml-1"
        @click.stop="openFullQueue"
      >
        <template #icon>
          <IconLucideListMusic />
        </template>
      </SButton>
    </div>
  </div>
</template>
