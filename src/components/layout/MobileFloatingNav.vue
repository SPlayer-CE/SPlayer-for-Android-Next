<script setup lang="ts">
import { useRoute, useRouter } from "vue-router";
import IconLucideHome from "~icons/lucide/home";
import IconLucideHistory from "~icons/lucide/history";
import IconLucideStar from "~icons/lucide/star";
import IconLucideMusic from "~icons/lucide/music";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();

// —— 尺寸预设（对齐参考项目 SPlayer-for-Android）——
const ITEM_WIDTH = 90;
const BAR_HEIGHT = 64;
const THUMB_HEIGHT = 58;
const ICON_SIZE = 24;
const FONT_SIZE = "0.75rem";
const LONG_PRESS_MS = 220;
const CLICK_SUPPRESS_MS = 80;

/** 底栏 tab 定义 */
const tabs = computed(() => [
  { path: "/", icon: IconLucideHome, label: t("nav.home") },
  { path: "/history", icon: IconLucideHistory, label: t("nav.history") },
  { path: "/favorites", icon: IconLucideStar, label: t("nav.favorites") },
  { path: "/library", icon: IconLucideMusic, label: t("nav.library") },
]);

/** 当前激活 tab 序号 */
const activeIndex = computed(() => {
  const path = route.path;
  if (path.startsWith("/history")) return 1;
  if (path.startsWith("/favorites")) return 2;
  if (path.startsWith("/library")) return 3;
  return 0;
});

/** 指示块宽度 */
const thumbWidth = computed(() => ITEM_WIDTH - 4);
const sliderWidth = computed(() => ITEM_WIDTH * tabs.value.length);

/** 指示块位移（物理弹簧插值） */
const currentThumbX = ref(0);
const navbarRef = ref<HTMLElement | null>(null);
const dragActive = ref(false);
const dragIndex = ref<number | null>(null);
let animFrame = 0;
let longPressTimer = 0;
let activePointerId: number | null = null;
let pointerDownIndex: number | null = null;
let lastPointerClientX = 0;
let suppressNextClick = false;

const visualIndex = computed(() => dragIndex.value ?? activeIndex.value);

const clamp = (value: number, min: number, max: number): number =>
  Math.min(Math.max(value, min), max);

const thumbXForIndex = (index: number): number => {
  const centerOffset = (ITEM_WIDTH - thumbWidth.value) / 2;
  return index * ITEM_WIDTH + centerOffset;
};

const thumbXForClientX = (clientX: number): number => {
  const rect = navbarRef.value?.getBoundingClientRect();
  if (!rect) return thumbXForIndex(activeIndex.value);
  const centerOffset = (ITEM_WIDTH - thumbWidth.value) / 2;
  const max = (tabs.value.length - 1) * ITEM_WIDTH + centerOffset;
  return clamp(clientX - rect.left - thumbWidth.value / 2, centerOffset, max);
};

const indexForClientX = (clientX: number): number => {
  const rect = navbarRef.value?.getBoundingClientRect();
  if (!rect) return activeIndex.value;
  const x = clamp(clientX - rect.left, 0, sliderWidth.value - 1);
  return clamp(Math.floor(x / ITEM_WIDTH), 0, tabs.value.length - 1);
};

const clearLongPressTimer = (): void => {
  window.clearTimeout(longPressTimer);
  longPressTimer = 0;
};

const setPointerCaptureSafely = (target: HTMLElement, pointerId: number): void => {
  try {
    target.setPointerCapture?.(pointerId);
  } catch {
    return;
  }
};

const releasePointerCaptureSafely = (target: HTMLElement, pointerId: number): void => {
  try {
    target.releasePointerCapture?.(pointerId);
  } catch {
    return;
  }
};

function animateThumb() {
  const dest = thumbXForIndex(activeIndex.value);
  const diff = dest - currentThumbX.value;
  currentThumbX.value += diff * 0.35;
  if (Math.abs(diff) < 0.5) {
    currentThumbX.value = dest;
    animFrame = 0;
    return;
  }
  animFrame = requestAnimationFrame(animateThumb);
}

const startThumbAnimation = (): void => {
  if (dragActive.value) return;
  cancelAnimationFrame(animFrame);
  animFrame = requestAnimationFrame(animateThumb);
};

watch(activeIndex, startThumbAnimation, { immediate: true });

// 首次挂载直接定位，不播动画
onMounted(() => {
  cancelAnimationFrame(animFrame);
  currentThumbX.value = thumbXForIndex(activeIndex.value);
});

onUnmounted(() => {
  clearLongPressTimer();
  cancelAnimationFrame(animFrame);
});

const beginDrag = (): void => {
  if (activePointerId === null) return;
  dragActive.value = true;
  dragIndex.value = indexForClientX(lastPointerClientX);
  cancelAnimationFrame(animFrame);
  currentThumbX.value = thumbXForClientX(lastPointerClientX);
};

const updateDrag = (event: PointerEvent): void => {
  if (!dragActive.value) return;
  event.preventDefault();
  lastPointerClientX = event.clientX;
  currentThumbX.value = thumbXForClientX(event.clientX);
  dragIndex.value = indexForClientX(event.clientX);
};

const finishPointer = (event: PointerEvent, shouldSelect: boolean): void => {
  if (activePointerId !== event.pointerId) return;
  clearLongPressTimer();

  const wasDragging = dragActive.value;
  const nextIndex = wasDragging
    ? (dragIndex.value ?? activeIndex.value)
    : (pointerDownIndex ?? indexForClientX(event.clientX));
  activePointerId = null;
  pointerDownIndex = null;
  dragActive.value = false;
  dragIndex.value = null;
  releasePointerCaptureSafely(event.currentTarget as HTMLElement, event.pointerId);

  if (shouldSelect) {
    suppressNextClick = true;
    window.setTimeout(() => {
      suppressNextClick = false;
    }, CLICK_SUPPRESS_MS);
    selectTab(nextIndex);
    if (wasDragging) startThumbAnimation();
  }
};

const handlePointerDown = (event: PointerEvent): void => {
  if (event.button !== 0) return;
  activePointerId = event.pointerId;
  pointerDownIndex = indexForClientX(event.clientX);
  lastPointerClientX = event.clientX;
  setPointerCaptureSafely(event.currentTarget as HTMLElement, event.pointerId);
  clearLongPressTimer();
  longPressTimer = window.setTimeout(beginDrag, LONG_PRESS_MS);
};

const handlePointerMove = (event: PointerEvent): void => {
  if (activePointerId !== event.pointerId) return;
  lastPointerClientX = event.clientX;
  updateDrag(event);
};

const handlePointerUp = (event: PointerEvent): void => finishPointer(event, true);
const handlePointerCancel = (event: PointerEvent): void => finishPointer(event, false);

const selectTab = (index: number): void => {
  const target = tabs.value[index];
  if (target && route.path !== target.path) {
    router.push(target.path);
  }
};

/** 切换 tab */
const handleSelect = (index: number): void => {
  if (suppressNextClick) return;
  selectTab(index);
};
</script>

<template>
  <Teleport to="body">
    <nav
      class="mobile-floating-nav"
      :style="{ paddingBottom: 'max(var(--safe-area-bottom), 4px)' }"
    >
      <div
        ref="navbarRef"
        class="lg-navbar"
        :class="dragActive ? 'lg-navbar--dragging' : ''"
        :style="{
          width: `${sliderWidth}px`,
          height: `${BAR_HEIGHT}px`,
          borderRadius: `${BAR_HEIGHT / 2}px`,
        }"
        @pointerdown="handlePointerDown"
        @pointermove="handlePointerMove"
        @pointerup="handlePointerUp"
        @pointercancel="handlePointerCancel"
        @lostpointercapture="handlePointerCancel"
        @contextmenu.prevent
      >
        <!-- 背景层 -->
        <div
          class="lg-navbar__bg"
          :class="'lg-navbar__bg--glass'"
          :style="{ borderRadius: `${BAR_HEIGHT / 2}px` }"
        />
        <!-- 指示块 -->
        <div
          class="lg-navbar__thumb"
          :style="{
            width: `${thumbWidth}px`,
            height: `${THUMB_HEIGHT}px`,
            borderRadius: `${THUMB_HEIGHT / 2}px`,
            transform: `translateX(${currentThumbX}px) translateY(-50%)`,
            top: `${BAR_HEIGHT / 2}px`,
          }"
        >
          <div
            class="lg-navbar__thumb-inner"
            :class="'lg-navbar__thumb-inner--glass'"
            :style="{ borderRadius: `${THUMB_HEIGHT / 2}px` }"
          />
        </div>
        <!-- 导航项 -->
        <div class="lg-navbar__items">
          <button
            v-for="(tab, index) in tabs"
            :key="tab.path"
            class="lg-navbar__item"
            :style="{ width: `${ITEM_WIDTH}px` }"
            @click="handleSelect(index)"
          >
            <component
              :is="tab.icon"
              :size="ICON_SIZE"
              class="lg-navbar__icon"
              :class="index === visualIndex ? 'lg-navbar__icon--active' : ''"
            />
            <span
              class="lg-navbar__label"
              :style="{
                fontSize: FONT_SIZE,
                fontWeight: index === visualIndex ? 600 : 400,
              }"
              :class="index === visualIndex ? 'lg-navbar__label--active' : ''"
            >
              {{ tab.label }}
            </span>
          </button>
        </div>
      </div>
    </nav>
  </Teleport>
</template>

<style scoped>
.mobile-floating-nav {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 50;
  display: flex;
  justify-content: center;
  pointer-events: none;
  transform: translateY(var(--android-keyboard-offset, 0px));
}

.lg-navbar {
  position: relative;
  pointer-events: auto;
  user-select: none;
  touch-action: none;
  margin-bottom: 8px;
}

.lg-navbar--dragging {
  cursor: grabbing;
}

/* 背景 */
.lg-navbar__bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.lg-navbar__bg--glass {
  background-color: rgb(var(--s-surface-panel) / 0.6);
  backdrop-filter: blur(24px) saturate(1.5);
  -webkit-backdrop-filter: blur(24px) saturate(1.5);
  border: 1px solid rgb(var(--s-on-surface) / 0.06);
}

.lg-navbar__bg--solid {
  background-color: rgb(var(--s-surface-panel));
  border: 1px solid rgb(var(--s-on-surface) / 0.08);
  box-shadow: 0 2px 12px rgb(var(--s-on-surface) / 0.06);
}

/* 指示块 */
.lg-navbar__thumb {
  position: absolute;
  left: 0;
  z-index: 1;
  pointer-events: none;
  transition: none;
}

.lg-navbar__thumb-inner {
  width: 100%;
  height: 100%;
  box-shadow:
    inset 0 1px 1px rgba(255, 255, 255, 0.2),
    inset 0 -1px 2px rgba(0, 0, 0, 0.06);
}

.lg-navbar__thumb-inner--glass {
  background-color: rgb(var(--s-surface) / 0.8);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border: 1px solid rgb(var(--s-primary) / 0.15);
}

.lg-navbar__thumb-inner--solid {
  background-color: rgb(var(--s-primary) / 0.12);
  border: 1px solid rgb(var(--s-primary) / 0.18);
}

/* 导航项 */
.lg-navbar__items {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  z-index: 2;
  pointer-events: none;
}

.lg-navbar__item {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 2px;
  cursor: pointer;
  background: none;
  border: none;
  padding: 0;
  pointer-events: auto;
  -webkit-tap-highlight-color: transparent;
}

.lg-navbar__icon {
  transition:
    color 0.15s ease,
    opacity 0.15s ease;
  color: rgb(var(--s-on-surface-variant));
  opacity: 0.7;
}

.lg-navbar__icon--active {
  color: rgb(var(--s-primary));
  opacity: 1;
}

.lg-navbar__label {
  display: block;
  line-height: 1;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color 0.15s ease;
  color: rgb(var(--s-on-surface-variant));
  opacity: 0.7;
}

.lg-navbar__label--active {
  color: rgb(var(--s-primary));
  opacity: 1;
}
</style>
