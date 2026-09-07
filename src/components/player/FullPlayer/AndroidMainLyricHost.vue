<script setup lang="ts">
import type { PluginListenerHandle } from "@capacitor/core";
import type { LyricLine } from "@shared/types/lyrics";
import type { AndroidLyricRenderMode } from "@shared/types/settings";
import type { SpringParams } from "@/components/player/Lyrics/engine/spring";
import { isAndroidNative } from "@/services/bridge";
import { AndroidMainLyric } from "@/plugins/androidMainLyric";
import Lyrics from "@/components/player/Lyrics/index.vue";
import { applyScrollPreroll } from "@/components/player/Lyrics/utils/scroll-preroll";

const props = withDefaults(
  defineProps<{
    lyricLines: LyricLine[];
    initialTime?: number;
    timeOffsetMs?: number;
    playing?: boolean;
    fontWeight?: number;
    fontFamily?: string;
    alignPosition?: number;
    wordFadeWidth?: number;
    springConfig?: Partial<SpringParams>;
    inactiveAlpha?: number;
    hidePassedLines?: boolean;
    enableBlur?: boolean;
    enableWordHighlight?: boolean;
    enableFloatAnimation?: boolean;
    enableEmphasizeEffect?: boolean;
    enableWordBlockSegmentation?: boolean;
    showTranslation?: boolean;
    showRomanization?: boolean;
    renderMode?: AndroidLyricRenderMode;
    unlockFpsLimit?: boolean;
    bottomExclusionHeightPx?: number;
    visible?: boolean;
    interactive?: boolean;
  }>(),
  {
    initialTime: 0,
    timeOffsetMs: 0,
    playing: false,
    fontWeight: 700,
    fontFamily: undefined,
    alignPosition: undefined,
    wordFadeWidth: undefined,
    springConfig: undefined,
    inactiveAlpha: undefined,
    hidePassedLines: undefined,
    enableBlur: undefined,
    enableWordHighlight: undefined,
    enableFloatAnimation: undefined,
    enableEmphasizeEffect: undefined,
    enableWordBlockSegmentation: false,
    showTranslation: true,
    showRomanization: true,
    renderMode: "legacy",
    unlockFpsLimit: undefined,
    bottomExclusionHeightPx: 0,
    visible: true,
    interactive: true,
  },
);

const emit = defineEmits<{
  (e: "seek", timeMs: number): void;
}>();

interface LyricHostController {
  setCurrentTime: (time: number) => void;
  freeze: () => void;
  resume: () => void;
  refreshLayout: () => void;
  suppressTapSeek: () => void;
}

const legacyRef = ref<LyricHostController | null>(null);
const kotlinHostRef = ref<HTMLElement | null>(null);
// 字号哨兵元素：width/height 各 1em，字号变化时其像素尺寸随之变化，
// 被 ResizeObserver 捕获后触发 syncKotlinConfig 重新读取 computed fontSize
const fontSentinelRef = ref<HTMLElement | null>(null);
const preparedLyricLines = computed(() => applyScrollPreroll(props.lyricLines ?? []));
const lyricLinesJson = computed(() => JSON.stringify(preparedLyricLines.value));
const activeRenderer = computed<"legacy" | "kotlin">(() =>
  props.renderMode === "kotlin" && isAndroidNative ? "kotlin" : "legacy",
);

/**
 * Android 渲染字重 = 设置字重 × 2（上限 1000，即 CSS font-weight 有效区间上限，超出会使声明无效回落到 400）
 * Android 端 WebView / 原生渲染对同值字重的视觉粗细明显弱于桌面端，
 * 在渲染入口统一放大，legacy 与 kotlin 两条渲染路径保持一致
 */
const scaledFontWeight = computed(() => Math.min((props.fontWeight ?? 700) * 2, 1000));

let seekListener: PluginListenerHandle | null = null;
let resizeObserver: ResizeObserver | null = null;
let interactionObserver: MutationObserver | null = null;
let viewportRaf = 0;
let progressPushRaf = 0;
let touchStateRaf = 0;
/** 上次推送到原生的播放时间，暂停时用于判断时间是否变化 */
let lastPushedTime = -1;
let lastTouchEnabled: boolean | null = null;
// 播放器入场动画期间 getBoundingClientRect 返回过渡位置，导致视口被设为错误值。
// ResizeObserver 只监听尺寸变化不监听位置变化，无法自动修正。
// 在初始同步后延迟重新同步视口，确保动画结束后位置正确。
let postMountSyncTimers: number[] = [];
/** 弹层关闭后恢复原生歌词层的防抖计时器 */
let overlayRecoverTimer = 0;

const blockingOverlaySelector =
  '[role="dialog"]:not([data-side]), .mobile-queue-panel, .mobile-queue-sheet';

const containsBlockingOverlay = (node: Node): boolean => {
  if (!(node instanceof Element)) return false;
  return (
    node.matches(blockingOverlaySelector) || node.querySelector(blockingOverlaySelector) !== null
  );
};

const shouldHandleOverlayMutations = (mutations: MutationRecord[]): boolean =>
  mutations.some((mutation) => {
    if (mutation.type === "childList") {
      return [...mutation.addedNodes, ...mutation.removedNodes].some(containsBlockingOverlay);
    }
    if (mutation.type !== "attributes") return false;
    if (mutation.target === document.body) return true;
    return (
      mutation.target instanceof Element &&
      mutation.target.closest(blockingOverlaySelector) !== null
    );
  });

const schedulePostMountViewportSync = (): void => {
  for (const t of postMountSyncTimers) window.clearTimeout(t);
  // 420ms 覆盖 380ms 入场动画 + 一帧余量；800ms 兜底慢设备
  postMountSyncTimers = [
    window.setTimeout(() => void syncKotlinViewport(), 420),
    window.setTimeout(() => void syncKotlinViewport(), 800),
  ];
};

/**
 * 检查是否有需要隐藏原生歌词层的覆盖物：
 * - SDialog / SDrawer（设置页、添加到歌单等）：role="dialog" 但无 data-side
 * - 播放列表面板（平板内嵌 .mobile-queue-panel / 手机底部 .mobile-queue-sheet）
 * Popover（快捷开关）有 data-side 属性，不在此列——用户希望快捷开关打开时歌词仍可见
 */
const hasBlockingOverlay = (): boolean => {
  if (document.querySelector('[role="dialog"][data-state="open"]:not([data-side])')) return true;
  return document.querySelector(".mobile-queue-panel, .mobile-queue-sheet") !== null;
};

const syncKotlinViewport = async (): Promise<void> => {
  if (activeRenderer.value !== "kotlin") return;
  if (!props.visible || hasBlockingOverlay()) {
    await AndroidMainLyric.hide();
    return;
  }
  const host = kotlinHostRef.value;
  if (!host) return;

  const rect = host.getBoundingClientRect();
  const reservedHeight = Math.max(0, props.bottomExclusionHeightPx);
  const dpr = window.devicePixelRatio || 1;
  const width = Math.round(rect.width * dpr);
  const height = Math.round(rect.height * dpr);

  if (width <= 0 || height <= 0) {
    await AndroidMainLyric.hide();
    return;
  }

  await AndroidMainLyric.setViewport({
    left: Math.round(rect.left * dpr),
    top: Math.round(rect.top * dpr),
    width,
    height,
    bottomExclusionHeight: Math.round(reservedHeight * dpr),
    cssWidth: rect.width,
  });
  await AndroidMainLyric.show();
};

const resolveKotlinTouchEnabled = (): boolean => {
  if (activeRenderer.value !== "kotlin") return false;
  if (!props.visible || !props.interactive) return false;
  if (hasBlockingOverlay()) return false;
  if (document.body.hasAttribute("data-scroll-locked")) return false;
  if (document.body.style.pointerEvents === "none") return false;

  const host = kotlinHostRef.value;
  if (!host) return false;
  const interactionRoot = host.parentElement ?? host;
  const rect = host.getBoundingClientRect();
  if (rect.width <= 0 || rect.height <= 0) return false;

  const inset = Math.min(24, Math.max(8, Math.min(rect.width, rect.height) * 0.12));
  // 底栏排除区内的采样点会被 .lyric-floating-controls 的子元素遮挡，
  // 导致整个歌词层触摸被禁用；将底部采样点上移到排除区之外。
  const bottomY = Math.max(rect.top + inset, rect.bottom - props.bottomExclusionHeightPx - inset);
  const samplePoints: Array<[number, number]> = [
    [rect.left + rect.width / 2, rect.top + rect.height / 2],
    [rect.left + inset, rect.top + inset],
    [rect.right - inset, rect.top + inset],
    [rect.left + inset, bottomY],
    [rect.right - inset, bottomY],
  ];

  for (const [x, y] of samplePoints) {
    if (x < 0 || y < 0 || x > window.innerWidth || y > window.innerHeight) continue;
    const topElement = document.elementFromPoint(x, y);
    if (!topElement) continue;
    if (interactionRoot.contains(topElement)) return true;
  }

  return false;
};

const syncKotlinTouchState = async (force = false): Promise<void> => {
  if (activeRenderer.value !== "kotlin") {
    lastTouchEnabled = null;
    return;
  }
  const enabled = resolveKotlinTouchEnabled();
  if (!force && lastTouchEnabled === enabled) return;
  lastTouchEnabled = enabled;
  await AndroidMainLyric.setTouchEnabled({ enabled });
};

const scheduleViewportSync = (): void => {
  if (activeRenderer.value !== "kotlin") return;
  window.cancelAnimationFrame(viewportRaf);
  viewportRaf = window.requestAnimationFrame(() => {
    void syncKotlinViewport();
    void syncKotlinConfig();
    void syncKotlinTouchState();
  });
};

const scheduleKotlinTouchSync = (): void => {
  if (activeRenderer.value !== "kotlin") return;
  window.cancelAnimationFrame(touchStateRaf);
  touchStateRaf = window.requestAnimationFrame(() => {
    void syncKotlinTouchState();
  });
};

const syncKotlinConfig = async (): Promise<void> => {
  if (activeRenderer.value !== "kotlin") return;
  const host = kotlinHostRef.value;
  if (!host) return;

  const style = getComputedStyle(host);
  const inherited = getComputedStyle(host.parentElement ?? host);
  const dpr = window.devicePixelRatio || 1;
  const rootStyle = getComputedStyle(document.documentElement);
  const coverRgb = rootStyle.getPropertyValue("--s-cover").trim();
  const textColor = coverRgb ? `rgb(${coverRgb})` : inherited.color || style.color || "#ffffff";
  const fontSizePx = (Number.parseFloat(style.fontSize) || 34) * dpr;

  await AndroidMainLyric.setConfig({
    fontSizePx,
    fontWeight: scaledFontWeight.value,
    fontFamily: props.fontFamily,
    // 分语种歌词字体：与 Web 端 :lang(zh/ja/ko/und-Latn) 同源的 CSS 变量，继承自歌词容器
    fontFamilyChinese: style.getPropertyValue("--lyric-font-zh").trim() || undefined,
    fontFamilyJapanese: style.getPropertyValue("--lyric-font-ja").trim() || undefined,
    fontFamilyKorean: style.getPropertyValue("--lyric-font-ko").trim() || undefined,
    fontFamilyLatin: style.getPropertyValue("--lyric-font-latin").trim() || undefined,
    textColor,
    inactiveAlpha: props.inactiveAlpha ?? 0.2,
    alignPosition: props.alignPosition ?? 0.35,
    wordFadeWidth: props.wordFadeWidth ?? 0.5,
    hidePassedLines: props.hidePassedLines ?? false,
    enableBlur: props.enableBlur ?? false,
    enableWordHighlight: props.enableWordHighlight ?? true,
    enableFloatAnimation: props.enableFloatAnimation ?? false,
    enableEmphasizeEffect: props.enableEmphasizeEffect ?? false,
    enableWordBlockSegmentation: props.enableWordBlockSegmentation ?? false,
    showTranslation: props.showTranslation ?? true,
    showRomanization: props.showRomanization ?? true,
    springMass: props.springConfig?.mass,
    springDamping: props.springConfig?.damping,
    springStiffness: props.springConfig?.stiffness,
  });
};

const syncKotlinLyrics = async (): Promise<void> => {
  if (activeRenderer.value !== "kotlin") return;
  await AndroidMainLyric.setLyrics({ linesJson: lyricLinesJson.value });
};

const syncKotlinTimeOffset = async (): Promise<void> => {
  if (activeRenderer.value !== "kotlin") return;
  await AndroidMainLyric.setTimeOffset({ timeOffsetMs: Math.round(props.timeOffsetMs ?? 0) });
};

const pushKotlinProgress = (time: number, force = false): void => {
  if (activeRenderer.value !== "kotlin") return;
  if (!props.visible) return;
  if (!force) {
    if (props.playing) return;
    // 暂停时时间不变，仅在时间确实变化时推送
    if (Math.abs(time - lastPushedTime) < 50) return;
  }
  lastPushedTime = time;
  window.cancelAnimationFrame(progressPushRaf);
  progressPushRaf = window.requestAnimationFrame(() => {
    void AndroidMainLyric.updateProgress({
      timeMs: Math.round(time),
      playing: !!props.playing,
    });
  });
};

const teardownKotlinRenderer = async (): Promise<void> => {
  window.cancelAnimationFrame(viewportRaf);
  window.cancelAnimationFrame(progressPushRaf);
  window.cancelAnimationFrame(touchStateRaf);
  lastTouchEnabled = null;
  // 非 native 环境（LAN 网页客户端/预览）无原生歌词层，跳过 clear 避免插件 web 端未实现报错
  if (!isAndroidNative) return;
  await AndroidMainLyric.clear();
};

const setCurrentTime = (time: number): void => {
  if (activeRenderer.value === "kotlin") {
    pushKotlinProgress(time);
    return;
  }
  legacyRef.value?.setCurrentTime(time);
};

const freeze = (): void => {
  if (activeRenderer.value === "kotlin") {
    void AndroidMainLyric.freeze();
    return;
  }
  legacyRef.value?.freeze();
};

const resume = (): void => {
  if (activeRenderer.value === "kotlin") {
    if (!props.visible) return;
    void AndroidMainLyric.resume();
    pushKotlinProgress(props.initialTime ?? 0, true);
    return;
  }
  legacyRef.value?.resume();
};

const refreshLayout = (): void => {
  if (activeRenderer.value === "kotlin") {
    scheduleViewportSync();
    void AndroidMainLyric.refreshLayout();
    return;
  }
  legacyRef.value?.refreshLayout();
};

const suppressTapSeek = (): void => {
  if (activeRenderer.value === "kotlin") {
    void AndroidMainLyric.suppressTapSeek();
    return;
  }
  legacyRef.value?.suppressTapSeek();
};

defineExpose({ setCurrentTime, freeze, resume, refreshLayout, suppressTapSeek });

watch(
  activeRenderer,
  async (mode) => {
    if (mode === "kotlin") {
      await nextTick();
      if (kotlinHostRef.value) resizeObserver?.observe(kotlinHostRef.value);
      if (fontSentinelRef.value) resizeObserver?.observe(fontSentinelRef.value);
      await syncKotlinLyrics();
      await syncKotlinConfig();
      await syncKotlinTimeOffset();
      await syncKotlinViewport();
      await syncKotlinTouchState(true);
      pushKotlinProgress(props.initialTime ?? 0, true);
      schedulePostMountViewportSync();
      return;
    }
    if (kotlinHostRef.value) resizeObserver?.unobserve(kotlinHostRef.value);
    if (fontSentinelRef.value) resizeObserver?.unobserve(fontSentinelRef.value);
    await teardownKotlinRenderer();
  },
  { immediate: true },
);

watch(
  () => props.visible,
  async (visible) => {
    if (activeRenderer.value !== "kotlin") return;
    if (!visible) {
      await AndroidMainLyric.setTouchEnabled({ enabled: false });
      lastTouchEnabled = false;
      await AndroidMainLyric.hide();
      await AndroidMainLyric.freeze();
      return;
    }
    await AndroidMainLyric.resume();
    await nextTick();
    await syncKotlinConfig();
    await syncKotlinTimeOffset();
    await syncKotlinViewport();
    await syncKotlinTouchState(true);
    pushKotlinProgress(props.initialTime ?? 0, true);
    schedulePostMountViewportSync();
  },
  { flush: "post" },
);

watch(
  () => props.interactive,
  (interactive) => {
    if (!interactive) {
      // 立即禁用触摸，不等 RAF——弹层 Transition 期间 MutationObserver 会反复
      // 取消并重新调度 RAF，导致 setTouchEnabled(false) 延迟多帧才到达原生层，
      // 这段时间原生覆盖层仍在消费本该传递给弹层的触摸事件。
      window.cancelAnimationFrame(touchStateRaf);
      if (lastTouchEnabled !== false) {
        lastTouchEnabled = false;
        void AndroidMainLyric.setTouchEnabled({ enabled: false });
      }
      return;
    }
    scheduleKotlinTouchSync();
  },
  { flush: "post" },
);

watch(
  lyricLinesJson,
  async () => {
    await syncKotlinLyrics();
    // 切歌后重新同步视口+触摸状态，确保竖屏 .lyric-page 的 pointer-events
    // 过渡不会让原生层残留旧的触摸禁用状态
    scheduleViewportSync();
  },
  { flush: "post" },
);

watch(
  () => [
    props.fontWeight,
    props.fontFamily,
    props.alignPosition,
    props.wordFadeWidth,
    props.inactiveAlpha,
    props.hidePassedLines,
    props.enableBlur,
    props.enableWordHighlight,
    props.enableFloatAnimation,
    props.enableEmphasizeEffect,
    props.enableWordBlockSegmentation,
    props.showTranslation,
    props.showRomanization,
    props.bottomExclusionHeightPx,
    props.springConfig?.mass,
    props.springConfig?.damping,
    props.springConfig?.stiffness,
  ],
  () => {
    void syncKotlinConfig();
    scheduleViewportSync();
  },
  { flush: "post" },
);

watch(
  () => props.timeOffsetMs,
  () => {
    void syncKotlinTimeOffset();
  },
);

watch(
  () => props.playing,
  () => {
    pushKotlinProgress(props.initialTime ?? 0, true);
  },
);

watch(
  () => props.initialTime,
  (time) => {
    if (!props.playing) pushKotlinProgress(time ?? 0, true);
  },
);

onMounted(async () => {
  if (isAndroidNative) {
    seekListener = await AndroidMainLyric.addListener("seek", (event) => {
      if (activeRenderer.value !== "kotlin") return;
      const timeMs = Number(event.timeMs ?? 0);
      if (Number.isFinite(timeMs) && timeMs >= 0) emit("seek", timeMs);
    });
  }

  resizeObserver = new ResizeObserver(() => {
    scheduleViewportSync();
  });
  if (kotlinHostRef.value) resizeObserver.observe(kotlinHostRef.value);
  if (fontSentinelRef.value) resizeObserver.observe(fontSentinelRef.value);
  if (document.body) {
    // 原生歌词层挂在 Activity 顶层，高于整个 WebView。
    // Dialog/Drawer 打开时需要隐藏原生歌词层（视觉穿透 + 触摸穿透）；
    // Popover（快捷开关）不隐藏——用户希望快捷开关打开时歌词仍可见。
    interactionObserver = new MutationObserver((mutations) => {
      if (!shouldHandleOverlayMutations(mutations)) return;
      if (hasBlockingOverlay()) {
        // 立即禁用触摸并隐藏原生歌词层，不等 RAF——
        // Transition 动画期间 MutationObserver 反复触发会延迟 RAF 回调。
        window.cancelAnimationFrame(touchStateRaf);
        window.cancelAnimationFrame(viewportRaf);
        window.clearTimeout(overlayRecoverTimer);
        if (lastTouchEnabled !== false) {
          lastTouchEnabled = false;
          void AndroidMainLyric.setTouchEnabled({ enabled: false });
        }
        void AndroidMainLyric.hide();
        return;
      }
      // 弹层关闭后恢复显示——用 setTimeout 防抖而非 RAF。
      // RAF 在 Transition 期间会被反复取消，导致 show() 延迟多帧甚至永不执行。
      window.clearTimeout(overlayRecoverTimer);
      overlayRecoverTimer = window.setTimeout(() => {
        overlayRecoverTimer = 0;
        if (hasBlockingOverlay()) return;
        void syncKotlinViewport();
        void syncKotlinTouchState(true);
        pushKotlinProgress(props.initialTime ?? 0, true);
      }, 120);
    });
    interactionObserver.observe(document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: [
        "class",
        "style",
        "data-state",
        "open",
        "data-scroll-locked",
        "data-aria-hidden",
      ],
    });
  }
  window.addEventListener("resize", scheduleViewportSync, { passive: true });
  window.addEventListener("scroll", scheduleKotlinTouchSync, { passive: true, capture: true });
});

onBeforeUnmount(async () => {
  for (const t of postMountSyncTimers) window.clearTimeout(t);
  postMountSyncTimers = [];
  window.clearTimeout(overlayRecoverTimer);
  overlayRecoverTimer = 0;
  resizeObserver?.disconnect();
  resizeObserver = null;
  interactionObserver?.disconnect();
  interactionObserver = null;
  seekListener?.remove();
  seekListener = null;
  window.removeEventListener("resize", scheduleViewportSync);
  window.removeEventListener("scroll", scheduleKotlinTouchSync, true);
  await teardownKotlinRenderer();
});
</script>

<template>
  <Lyrics
    v-if="activeRenderer === 'legacy'"
    ref="legacyRef"
    :lyric-lines="props.lyricLines"
    :initial-time="props.initialTime"
    :playing="props.playing"
    :font-weight="scaledFontWeight"
    :font-family="props.fontFamily"
    :align-position="props.alignPosition"
    :word-fade-width="props.wordFadeWidth"
    :spring-config="props.springConfig"
    :inactive-alpha="props.inactiveAlpha"
    :hide-passed-lines="props.hidePassedLines"
    :enable-blur="props.enableBlur"
    :enable-word-highlight="props.enableWordHighlight"
    :enable-float-animation="props.enableFloatAnimation"
    :enable-emphasize-effect="props.enableEmphasizeEffect"
    :enable-word-block-segmentation="props.enableWordBlockSegmentation"
    :show-translation="props.showTranslation"
    :show-romanization="props.showRomanization"
    :unlock-fps-limit="props.unlockFpsLimit"
    @seek="emit('seek', $event)"
  />
  <div v-else ref="kotlinHostRef" class="android-main-lyric-host">
    <div ref="fontSentinelRef" class="font-size-sentinel" />
  </div>
</template>

<style scoped>
.android-main-lyric-host {
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.font-size-sentinel {
  position: absolute;
  width: 1em;
  height: 1em;
  visibility: hidden;
  pointer-events: none;
}
</style>
