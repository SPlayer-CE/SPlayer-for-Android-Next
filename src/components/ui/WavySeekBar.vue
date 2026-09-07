<script setup lang="ts">
/**
 * WavySeekBar — 类 Android 13 原生媒体播放器的波浪进度条
 *
 * 已播放区域：平滑正弦波浪线（播放时持续流动）
 * 未播放区域：普通直线
 * 中间：圆形 thumb（hover / 拖拽时可见）
 *
 * 核心算法参考：
 * - AOSP SquigglyProgress.kt（phaseOffset 流动动画）
 * - saket/squiggly-slider（segmentsPerWavelength + clipRect 思路）
 * - mahozad/wavy-slider（逐点 sin 生成 + 动画曲线）
 */

export interface WavySeekBarProps {
  /** 当前值 */
  modelValue?: number;
  /** 最小值 */
  min?: number;
  /** 最大值 */
  max?: number;
  /** 步进值 */
  step?: number;
  /** 命中区域高度（px） */
  height?: number;
  /** 轨道粗细（px） */
  strokeWidth?: number;
  /** 波长（px） */
  wavelength?: number;
  /** 波浪振幅（px） */
  amplitude?: number;
  /** 已播放轨道颜色（默认用 currentColor） */
  activeColor?: string;
  /** 未播放轨道颜色 */
  inactiveColor?: string;
  /** thumb 颜色 */
  thumbColor?: string;
  /** 波浪流动速度（像素/秒） */
  waveSpeed?: number;
  /** 是否正在播放（控制波浪流动） */
  playing?: boolean;
  /** 是否禁用 */
  disabled?: boolean;
  /** 是否始终显示 thumb */
  alwaysShowThumb?: boolean;
}

const props = withDefaults(defineProps<WavySeekBarProps>(), {
  modelValue: 0,
  min: 0,
  max: 100,
  step: 1,
  height: 14,
  strokeWidth: 4,
  wavelength: 24,
  amplitude: 4,
  activeColor: "",
  inactiveColor: "",
  thumbColor: "",
  waveSpeed: 30,
  playing: false,
  disabled: false,
  alwaysShowThumb: true,
});

const emit = defineEmits<{
  "update:modelValue": [value: number];
  change: [value: number];
  dragStart: [value: number];
  dragEnd: [value: number];
}>();

const SEGS_PER_WAVELENGTH = 10;
const clipId = `wavy-clip-${Math.random().toString(36).slice(2, 8)}`;

const trackRef = ref<HTMLElement>();
const isDragging = ref(false);
const isHovering = ref(false);
const phase = ref(0);
const currentAmplitude = ref(props.playing ? props.amplitude : 0);
const hoverTimeout = ref(0);
const trackWidth = ref(1);

let animFrameId = 0;
let lastFrameTime = 0;
let ampAnimId = 0;
let rewindAnimId = 0;
let resizeObserver: ResizeObserver | null = null;

/** 回退动画时长（ms） */
const REWIND_DURATION = 600;

/** 当前显示值（拖拽中用临时值） */
const dragValue = ref(props.modelValue);

watch(
  () => props.modelValue,
  (v) => {
    if (isDragging.value) return;
    /** 回退动画进行中，跳过中间的 position 推送 */
    if (rewindAnimId) return;

    const range = props.max - props.min || 1;
    const drop = dragValue.value - v;
    /** 值大幅下降且起点 > 5% → 视为切歌，播放回退动画 */
    if (drop > range * 0.05 && dragValue.value > range * 0.05) {
      animateRewind(dragValue.value, v);
    } else {
      dragValue.value = v;
    }
  },
);

const ratio = computed(() => {
  const range = props.max - props.min;
  if (range <= 0) return 0;
  return Math.max(0, Math.min(1, (dragValue.value - props.min) / range));
});

/** thumb 在 SVG 像素坐标系中的 x */
const thumbX = computed(() => ratio.value * trackWidth.value);

/** viewBox 使用实际显示高度，避免 SVG 被纵向压缩 */
const vbH = computed(() => props.height);
/** 波浪基线 y（viewBox 垂直中心） */
const trackY = computed(() => vbH.value / 2);

const activeColor = computed(() => props.activeColor || "currentColor");
const inactiveColor = computed(() => props.inactiveColor || "rgb(var(--s-on-surface) / 0.2)");
const thumbColor = computed(() => props.thumbColor || "currentColor");

const thumbVisible = computed(() => props.alwaysShowThumb || isDragging.value || isHovering.value);

const rootStyle = computed(() => ({
  "--wavy-seek-bar-height": `${props.height}px`,
}));

const clampValue = (value: number): number => Math.max(props.min, Math.min(props.max, value));

const snapValue = (value: number): number => {
  const step = Math.max(1, props.step);
  return clampValue(Math.round(value / step) * step);
};

const buildSmoothPath = (points: Array<{ x: number; y: number }>): string => {
  if (points.length === 0) return "";
  if (points.length === 1) return `M${points[0].x},${points[0].y}`;

  const d = [`M${points[0].x},${points[0].y}`];
  for (let i = 0; i < points.length - 1; i++) {
    const p0 = points[Math.max(0, i - 1)];
    const p1 = points[i];
    const p2 = points[i + 1];
    const p3 = points[Math.min(points.length - 1, i + 2)];
    const cp1x = p1.x + (p2.x - p0.x) / 6;
    const cp1y = p1.y + (p2.y - p0.y) / 6;
    const cp2x = p2.x - (p3.x - p1.x) / 6;
    const cp2y = p2.y - (p3.y - p1.y) / 6;
    d.push(`C${cp1x},${cp1y} ${cp2x},${cp2y} ${p2.x},${p2.y}`);
  }
  return d.join("");
};

const wavePath = computed(() => {
  const { wavelength } = props;
  const amp = currentAmplitude.value;
  const ph = phase.value;
  const ty = trackY.value;
  const startX = 0;
  const endX = trackWidth.value;
  if (endX <= startX) return "";

  const segW = wavelength / SEGS_PER_WAVELENGTH;
  const count = Math.ceil((endX - startX) / segW) + 1;
  const twoPi = 2 * Math.PI;
  const points: Array<{ x: number; y: number }> = [];

  for (let i = 0; i <= count; i++) {
    const x = Math.min(startX + i * segW, endX);
    const frac = (x - startX) / wavelength;
    const y = ty + Math.sin(frac * twoPi + ph) * amp;
    points.push({ x, y });
  }
  return buildSmoothPath(points);
});

const inactivePath = computed(() => {
  const x1 = thumbX.value;
  const x2 = trackWidth.value;
  if (x2 <= x1) return "";
  return `M${x1},${trackY.value}L${x2},${trackY.value}`;
});

/** 同步外部 max 变化时重启动画 */
watch(
  () => props.max,
  () => {
    if (props.playing && !animFrameId) startPhaseAnimation();
  },
);

/** 根据指针位置算出对应的值 */
const calcValue = (e: PointerEvent): number => {
  const rect = trackRef.value?.getBoundingClientRect();
  if (!rect) return 0;
  const ratio = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
  return snapValue(props.min + ratio * (props.max - props.min));
};

const emitValue = (v: number): void => {
  emit("update:modelValue", v);
  emit("change", v);
};

/** 缓动函数（ease-out cubic） */
const easeOutCubic = (t: number): number => 1 - (1 - t) ** 3;

/** 动画过渡到目标振幅 */
const animateAmplitudeTo = (target: number, duration = 260): void => {
  if (ampAnimId) {
    cancelAnimationFrame(ampAnimId);
    ampAnimId = 0;
  }
  const start = currentAmplitude.value;
  const delta = target - start;
  if (Math.abs(delta) < 0.01) {
    currentAmplitude.value = target;
    return;
  }
  const t0 = performance.now();
  const tick = (): void => {
    const elapsed = performance.now() - t0;
    const p = Math.min(1, elapsed / duration);
    currentAmplitude.value = start + delta * easeOutCubic(p);
    if (p < 1) ampAnimId = requestAnimationFrame(tick);
    else ampAnimId = 0;
  };
  ampAnimId = requestAnimationFrame(tick);
};

/** 回退倒流：进度从 from 平滑动画到 to，同时振幅短暂抬升后回落 */
const animateRewind = (from: number, to: number): void => {
  if (rewindAnimId) {
    cancelAnimationFrame(rewindAnimId);
    rewindAnimId = 0;
  }
  const delta = from - to;
  if (Math.abs(delta) < 1) {
    dragValue.value = to;
    return;
  }
  /** 回退期间保持一定振幅，随后回落 */
  const peakAmp = Math.min(props.amplitude * 1.6, props.amplitude + 2);
  if (props.playing) animateAmplitudeTo(peakAmp, 120);

  const t0 = performance.now();
  const tick = (): void => {
    const elapsed = performance.now() - t0;
    const p = Math.min(1, elapsed / REWIND_DURATION);
    dragValue.value = from - delta * easeOutCubic(p);
    if (p < 1) {
      rewindAnimId = requestAnimationFrame(tick);
    } else {
      rewindAnimId = 0;
      dragValue.value = to;
      if (props.playing) animateAmplitudeTo(props.amplitude, 300);
    }
  };
  rewindAnimId = requestAnimationFrame(tick);
};

/** 波浪相位流动动画 */
const startPhaseAnimation = (): void => {
  if (animFrameId) return;
  lastFrameTime = performance.now();
  const tick = (now: number): void => {
    const dt = (now - lastFrameTime) / 1000;
    lastFrameTime = now;
    phase.value += ((2 * Math.PI) / props.wavelength) * props.waveSpeed * dt;
    animFrameId = requestAnimationFrame(tick);
  };
  animFrameId = requestAnimationFrame(tick);
};

const stopPhaseAnimation = (): void => {
  if (!animFrameId) return;
  cancelAnimationFrame(animFrameId);
  animFrameId = 0;
};

const scheduleHoverHide = (): void => {
  clearTimeout(hoverTimeout.value);
  hoverTimeout.value = window.setTimeout(() => {
    if (!isDragging.value) isHovering.value = false;
  }, 1500);
};

onMounted(() => {
  const updateWidth = (): void => {
    const el = trackRef.value;
    if (!el) return;
    const w = el.offsetWidth || el.getBoundingClientRect().width || 1;
    trackWidth.value = Math.max(1, Math.round(w * 100) / 100);
  };

  updateWidth();
  resizeObserver = new ResizeObserver(updateWidth);
  if (trackRef.value) resizeObserver.observe(trackRef.value);

  // 首帧布局可能未稳定，下一帧再刷新一次
  requestAnimationFrame(updateWidth);

  if (props.playing) {
    startPhaseAnimation();
  }
});

onBeforeUnmount(() => {
  stopPhaseAnimation();
  resizeObserver?.disconnect();
  if (ampAnimId) cancelAnimationFrame(ampAnimId);
  if (rewindAnimId) cancelAnimationFrame(rewindAnimId);
  clearTimeout(hoverTimeout.value);
});

watch(
  () => props.playing,
  (p) => {
    if (p) {
      startPhaseAnimation();
      if (!isDragging.value) animateAmplitudeTo(props.amplitude, 400);
    } else {
      stopPhaseAnimation();
      if (!isDragging.value) animateAmplitudeTo(0, 400);
    }
  },
);

watch(
  () => props.amplitude,
  (a) => {
    if (ampAnimId) {
      cancelAnimationFrame(ampAnimId);
      ampAnimId = 0;
    }
    if (props.playing && !isDragging.value) animateAmplitudeTo(a, 200);
  },
);

const onPointerDown = (e: PointerEvent): void => {
  if (props.disabled) return;
  e.preventDefault();
  e.stopPropagation();
  (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  isDragging.value = true;
  animateAmplitudeTo(0, 150);
  const v = calcValue(e);
  dragValue.value = v;
  emitValue(v);
  emit("dragStart", v);
};

const onPointerMove = (e: PointerEvent): void => {
  if (!isDragging.value) return;
  e.preventDefault();
  e.stopPropagation();
  const v = calcValue(e);
  dragValue.value = v;
  emitValue(v);
};

const onPointerUp = (e: PointerEvent): void => {
  if (!isDragging.value) return;
  e.preventDefault();
  e.stopPropagation();
  (e.currentTarget as HTMLElement).releasePointerCapture?.(e.pointerId);
  const v = calcValue(e);
  isDragging.value = false;
  dragValue.value = v;
  emitValue(v);
  emit("dragEnd", v);
  if (props.playing) animateAmplitudeTo(props.amplitude, 260);
  scheduleHoverHide();
};

const onPointerEnter = (): void => {
  clearTimeout(hoverTimeout.value);
  isHovering.value = true;
};

const onPointerLeave = (): void => {
  if (!isDragging.value) scheduleHoverHide();
};
</script>

<template>
  <div
    ref="trackRef"
    class="wavy-seek-bar"
    :class="{ 'wavy-seek-bar--disabled': disabled }"
    :style="rootStyle"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerUp"
    @pointerenter="onPointerEnter"
    @pointerleave="onPointerLeave"
  >
    <svg :viewBox="`0 0 ${trackWidth} ${vbH}`" class="wavy-seek-bar__svg">
      <defs>
        <clipPath :id="clipId">
          <rect :x="0" :y="0" :width="thumbX" :height="vbH" />
        </clipPath>
      </defs>

      <!-- 未播放轨道（直线） -->
      <path
        v-if="inactivePath"
        :d="inactivePath"
        fill="none"
        :stroke="inactiveColor"
        :stroke-width="strokeWidth"
        stroke-linecap="round"
      />

      <!-- 已播放轨道（波浪，用 clipPath 裁切到 thumb 位置） -->
      <g :clip-path="`url(#${clipId})`">
        <path
          v-if="wavePath"
          :d="wavePath"
          fill="none"
          :stroke="activeColor"
          :stroke-width="strokeWidth"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </g>

      <!-- Thumb -->
      <circle
        v-if="thumbVisible"
        :cx="thumbX"
        :cy="trackY"
        :r="strokeWidth + 2"
        :fill="thumbColor"
        class="wavy-seek-bar__thumb"
      />
    </svg>
  </div>
</template>

<style scoped>
.wavy-seek-bar {
  position: relative;
  display: flex;
  align-items: center;
  width: 100%;
  height: var(--wavy-seek-bar-height);
  cursor: pointer;
  touch-action: none;
  user-select: none;
  -webkit-user-select: none;
  -webkit-tap-highlight-color: transparent;
}

.wavy-seek-bar--disabled {
  pointer-events: none;
  opacity: 0.4;
}

.wavy-seek-bar__svg {
  display: block;
  width: 100%;
  height: 100%;
  overflow: visible;
}

.wavy-seek-bar__thumb {
  transition: opacity 180ms ease;
}
</style>
