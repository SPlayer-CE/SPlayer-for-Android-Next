<script setup lang="ts">
import { useStatusStore } from "@/stores/status";
import { useBackClosable } from "@/composables/useAndroidBack";
import QueuePopover from "@/components/list/QueuePopover.vue";

const status = useStatusStore();
const { mobileQueueOpen } = storeToRefs(status);

/** Android 返回键关闭队列浮层 */
useBackClosable(mobileQueueOpen);

const close = (): void => {
  status.mobileQueueOpen = false;
};

const CLOSE_THRESHOLD = 96;
const DRAG_TOLERANCE = 8;
const sheetRef = ref<HTMLElement | null>(null);
const dragY = ref(0);
const dragging = ref(false);
let pointerId: number | null = null;
let startX = 0;
let startY = 0;
let locked: "h" | "v" | null = null;

const sheetStyle = computed(() => ({
  transform: `translateY(calc(var(--android-keyboard-offset, 0px) + ${dragY.value}px))`,
  transition: dragging.value ? "none" : undefined,
}));

const resetDrag = (): void => {
  pointerId = null;
  locked = null;
  dragging.value = false;
  dragY.value = 0;
};

const onPointerDown = (event: PointerEvent): void => {
  if (event.pointerType === "mouse" && event.button !== 0) return;
  const target = event.target as HTMLElement | null;
  if (!target?.closest("[data-mobile-queue-drag]") && !target?.closest(".mobile-queue-grip")) {
    return;
  }
  pointerId = event.pointerId;
  startX = event.clientX;
  startY = event.clientY;
  locked = null;
  dragY.value = 0;
};

const onPointerMove = (event: PointerEvent): void => {
  if (pointerId !== event.pointerId) return;
  const dx = event.clientX - startX;
  const dy = event.clientY - startY;
  if (!locked) {
    if (Math.max(Math.abs(dx), Math.abs(dy)) < DRAG_TOLERANCE) return;
    locked = Math.abs(dy) > Math.abs(dx) ? "v" : "h";
    if (locked === "v") sheetRef.value?.setPointerCapture?.(event.pointerId);
  }
  if (locked !== "v") return;
  event.preventDefault();
  dragging.value = true;
  dragY.value = Math.max(0, dy);
};

const onPointerEnd = (event: PointerEvent): void => {
  if (pointerId !== event.pointerId) return;
  sheetRef.value?.releasePointerCapture?.(event.pointerId);
  const shouldClose = dragging.value && dragY.value > CLOSE_THRESHOLD;
  resetDrag();
  if (shouldClose) close();
};
</script>

<template>
  <Teleport to="body">
    <!-- 遮罩：淡入淡出 -->
    <Transition
      enter-active-class="transition-opacity duration-280 ease-out"
      enter-from-class="opacity-0"
      leave-active-class="transition-opacity duration-220 ease-in"
      leave-to-class="opacity-0"
    >
      <div v-if="mobileQueueOpen" class="mobile-queue-mask" @click="close" />
    </Transition>

    <!-- 列表 sheet：从底部滑入 -->
    <Transition
      enter-active-class="transition-transform duration-350 ease-[cubic-bezier(0.22,1,0.36,1)]"
      enter-from-class="translate-y-full"
      leave-active-class="transition-transform duration-280 ease-[cubic-bezier(0.4,0,1,1)]"
      leave-to-class="translate-y-full"
    >
      <div
        v-if="mobileQueueOpen"
        ref="sheetRef"
        class="mobile-queue-sheet"
        :style="sheetStyle"
        @pointerdown.stop="onPointerDown"
        @pointermove.stop="onPointerMove"
        @pointerup.stop="onPointerEnd"
        @pointercancel.stop="onPointerEnd"
        @touchstart.stop.passive
        @touchend.stop.passive
        @touchcancel.stop.passive
        @click.stop
      >
        <div data-mobile-queue-drag class="mobile-queue-drag-zone">
          <div class="mobile-queue-grip" />
        </div>
        <QueuePopover :drag-sort="false" @close="close" />
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.mobile-queue-mask {
  position: fixed;
  inset: 0;
  z-index: 250;
  background: rgb(0 0 0 / 0.55);
  backdrop-filter: blur(2px);
  -webkit-backdrop-filter: blur(2px);
}

.mobile-queue-sheet {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 251;
  display: flex;
  flex-direction: column;
  width: min(100%, 480px);
  height: min(78vh, 640px);
  max-height: calc(100vh - 16px);
  margin: 0 auto;
  padding-bottom: var(--mobile-safe-bottom, var(--android-fullscreen-safe-bottom, 0px));
  background: rgb(var(--s-surface));
  border-radius: 20px 20px 0 0;
  box-shadow: 0 -8px 32px rgb(0 0 0 / 0.2);
  overflow: hidden;
  touch-action: pan-y;
  transform: translateY(var(--android-keyboard-offset, 0px));
}

.mobile-queue-sheet :deep(.overflow-y-auto) {
  overscroll-behavior: contain;
  touch-action: pan-y;
  -webkit-overflow-scrolling: touch;
}

.mobile-queue-drag-zone {
  flex-shrink: 0;
  padding: 8px 0 2px;
  cursor: grab;
  touch-action: none;
}

.mobile-queue-grip {
  flex-shrink: 0;
  width: 36px;
  height: 4px;
  margin: 0 auto;
  border-radius: 9999px;
  background: rgb(var(--s-on-surface) / 0.2);
}
</style>
