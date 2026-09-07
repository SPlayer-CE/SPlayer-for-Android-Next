<script setup lang="ts">
import { computed } from "vue";
import { useOrientationTransition } from "@/composables/useOrientationTransition";

const { phase, heroSrc, heroToRect } = useOrientationTransition();

const visible = computed(() => phase.value !== "idle");

/** 黑场在揭幕阶段淡出 */
const backdropClass = computed(() => `phase-${phase.value}`);

/** Hero 揭幕封面的定位样式 */
const heroStyle = computed(() => {
  const rect = heroToRect.value;
  if (!rect) return {};
  return {
    top: `${rect.top}px`,
    left: `${rect.left}px`,
    width: `${rect.width}px`,
    height: `${rect.height}px`,
    borderRadius: "16px",
  };
});

/** Hero 仅在揭幕阶段渲染 */
const heroShow = computed(
  () => phase.value === "enter-revealing" && !!heroSrc.value && !!heroToRect.value,
);
</script>

<template>
  <div v-if="visible" class="orientation-overlay">
    <div class="orientation-backdrop" :class="backdropClass" />
    <Transition name="hero">
      <img
        v-if="heroShow"
        class="orientation-hero"
        :src="heroSrc"
        :style="heroStyle"
        decoding="async"
      />
    </Transition>
  </div>
</template>

<style scoped>
.orientation-overlay {
  position: fixed;
  inset: 0;
  z-index: 9000;
  pointer-events: none;
}

.orientation-backdrop {
  position: fixed;
  inset: -12vmax;
  background-color: rgba(0, 0, 0, 0.62);
  transition: opacity 320ms cubic-bezier(0.22, 1, 0.36, 1);
}

.orientation-backdrop.phase-enter-revealing,
.orientation-backdrop.phase-exit-revealing {
  opacity: 0;
}

.orientation-hero {
  position: fixed;
  z-index: 9100;
  object-fit: cover;
  box-shadow: 0 16px 32px rgba(0, 0, 0, 0.28);
}

.hero-enter-active {
  transition:
    transform 480ms cubic-bezier(0.22, 1, 0.36, 1),
    opacity 320ms cubic-bezier(0.22, 1, 0.36, 1);
}

.hero-leave-active {
  transition:
    transform 280ms cubic-bezier(0.22, 1, 0.36, 1),
    opacity 200ms cubic-bezier(0.22, 1, 0.36, 1);
}

.hero-enter-from {
  opacity: 0;
  transform: scale(0.92);
}

.hero-leave-to {
  opacity: 0;
  transform: scale(0.97);
}
</style>
