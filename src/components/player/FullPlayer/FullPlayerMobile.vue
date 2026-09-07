<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { CSSProperties } from "vue";
import { useSwipe } from "@vueuse/core";
import type { AndroidLyricRenderMode } from "@shared/types/settings";
import { useMediaStore } from "@/stores/media";
import { useStatusStore } from "@/stores/status";
import { useSettingsStore } from "@/stores/settings";
import { useFavorite } from "@/composables/useFavorite";
import { usePlaylistPicker } from "@/composables/usePlaylistPicker";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import * as player from "@/core/player";
import { formatTime } from "@/utils/time";
import { getQualityLabel } from "@/utils/quality";
import { isAndroid, isAndroidNative, isAndroidPreview, resolveCoverUrl } from "@/services/bridge";

import PlayerCover from "./PlayerCover.vue";
import PlayerData from "./PlayerData.vue";
import AndroidMainLyricHost from "./AndroidMainLyricHost.vue";
import AMLLLyrics from "@/components/player/Lyrics/AMLLLyrics.vue";
import WavySeekBar from "@/components/ui/WavySeekBar.vue";
import QuickActionsMenu from "@/components/player/QuickActionsMenu.vue";
import OrientationOverlay from "./OrientationOverlay.vue";
import BottomSpectrum from "./BottomSpectrum.vue";
import QueuePanel from "./QueuePanel.vue";
import QualityControl from "@/components/player/QualityControl.vue";
import { usePlaybackTime } from "@/composables/usePlaybackTime";
import { useOrientationTransition } from "@/composables/useOrientationTransition";
import { getCurrentTime } from "@/services/playback";
import { queueLength } from "@/stores/queue";
import IconFavorite from "~icons/material-symbols/favorite-rounded";
import IconFavoriteOutline from "~icons/material-symbols/favorite-outline-rounded";
import IconLucideFullscreen from "~icons/lucide/fullscreen";
import IconLucideMinimize from "~icons/lucide/minimize";
import IconLucideChevronDown from "~icons/lucide/chevron-down";
import IconLucideEyeOff from "~icons/lucide/eye-off";
import IconLucideTextQuote from "~icons/lucide/text-quote";
import IconLucideUser from "~icons/lucide/user";
import IconLucideDisc3 from "~icons/lucide/disc-3";
import IconLucideListMusic from "~icons/lucide/list-music";
import IconLucideVolume1 from "~icons/lucide/volume-1";
import IconLucideVolume2 from "~icons/lucide/volume-2";
import IconLucideVolumeX from "~icons/lucide/volume-x";
import IconLucideListPlus from "~icons/lucide/list-plus";
import PlaylistPickerDialog from "@/components/modals/PlaylistPickerDialog.vue";

type MobilePageType = "info" | "lyric";

const emit = defineEmits<{
  (e: "collapse"): void;
}>();

const media = useMediaStore();
const status = useStatusStore();
const settings = useSettingsStore();
const fav = useFavorite();
const {
  open: pickerOpen,
  tracks: pickerTracks,
  mode: pickerMode,
  openPicker,
} = usePlaylistPicker();
const { isAndroidTablet, isAndroidPhone, isPortrait, isPhoneLandscape, height } =
  useResponsiveLayout();

const {
  isPlaying,
  isLoading,
  position,
  duration,
  repeatMode,
  shuffleMode,
  heartMode,
  fmMode,
  showLyric,
} = storeToRefs(status);

const mobileStart = ref<HTMLElement | null>(null);
const infoCoverRef = ref<HTMLElement | null>(null);
const infoSongDataRef = ref<HTMLElement | null>(null);
const lyricCoverRef = ref<HTMLElement | null>(null);
const lyricSongDataRef = ref<HTMLElement | null>(null);
const pageTransitionDisabled = ref(false);
const savedPageType = ref<MobilePageType>(status.mobileFullPlayerPage);
const DIRECTION_LOCK_TOLERANCE = 8;
const HERO_TRANSITION = "all 0.5s cubic-bezier(0.25, 1, 0.5, 1)";
const HERO_TRANSITION_DURATION_MS = 560;

const hasLyric = computed(() => media.parsedLyric.length > 0 || media.lyricLoading);
const pageTypes = computed<MobilePageType[]>(() => (hasLyric.value ? ["info", "lyric"] : ["info"]));
const totalPages = computed(() => pageTypes.value.length);
const pageIndex = ref(Math.max(0, pageTypes.value.indexOf(savedPageType.value)));
const isPadPortrait = computed(() => isAndroidTablet.value && isPortrait.value);
const enableWordBlockSegmentation = computed(() => isAndroidPhone.value && isPortrait.value);
/** 歌词底部排除区高度：与 .lyric-floating-controls 使用同一尺寸，避免底栏区域和歌词预留高度脱节。 */
const lyricBottomExclusionPx = computed(() => Math.max(260, Math.min(320, height.value * 0.3)));
const qualityLabel = computed(() => getQualityLabel(media.detail?.quality) || "—");
const hasTrack = computed(() => Boolean(media.track));
const volumePercent = computed(() => Math.round(status.volume * 100));
const landscapeQueueBadgeText = computed(() =>
  queueLength.value > 999 ? "999+" : String(queueLength.value),
);
const landscapeVolumePopoverOpen = ref(false);
const lastLandscapeVolume = ref(status.volume || 0.7);
/** Android 主播放器逐词实现由歌词渲染引擎统一选择。 */
const androidLyricRenderMode = computed<AndroidLyricRenderMode>(() =>
  isAndroid && settings.lyric.engine === "kotlin" ? "kotlin" : "legacy",
);
const usesNativeKotlinLyricClock = computed(
  () => isAndroidNative && androidLyricRenderMode.value === "kotlin",
);

/** 横屏切换动画协调器 */
const {
  phase: orientationPhase,
  isImmersiveLandscape,
  enter: enterImmersiveLandscape,
  exit: exitImmersiveLandscape,
  registerLandscapeCover,
} = useOrientationTransition();

/** 横屏封面元素 ref，供 Hero 揭幕测量目标位置 */
const landscapeCoverRef = ref<HTMLElement | null>(null);

/** 仅 Android 物理手机竖屏时允许主动进入沉浸式横屏 */
const canEnterImmersive = computed(() => isAndroidPhone.value && isPortrait.value);
/** 手机横屏始终提供退出入口，避免状态滞后时无法回到竖屏 */
const canExitLandscape = computed(() => isAndroidPhone.value && isPhoneLandscape.value);

/** 手机横屏手动隐藏播放页元信息（顶栏+底栏），轻触屏幕恢复 */
const uiHidden = ref(false);
const canHideUi = computed(() => isPhoneLandscape.value);
const onHideUi = () => {
  if (canHideUi.value) uiHidden.value = true;
};
const onTapRestoreUi = () => {
  uiHidden.value = false;
};

/** 进入沉浸式横屏 */
const onEnterImmersive = async () => {
  await enterImmersiveLandscape(resolveCoverUrl(media.track?.cover) || "");
};

/** 退出沉浸式横屏 */
const onExitImmersive = async () => {
  await exitImmersiveLandscape(resolveCoverUrl(media.track?.cover) || "");
};

/** 横屏顶栏收起按钮优先恢复竖屏，竖屏时才关闭播放器 */
const onTopBarCollapse = async () => {
  if (canExitLandscape.value || isImmersiveLandscape.value) {
    await onExitImmersive();
    return;
  }
  emit("collapse");
};

/** 切换歌词展示（对齐参考项目 PlayerMenu 的 TextPlay 图标） */
const toggleLyric = (): void => {
  showLyric.value = !showLyric.value;
};

const setLandscapeVolumeThrottled = useThrottleFn((volume: number): void => {
  player.setVolume(volume);
}, 80);

const onLandscapeVolumeWheel = (event: WheelEvent): void => {
  const delta = event.deltaY < 0 ? 0.05 : -0.05;
  const next = Math.max(0, Math.min(1, status.volume + delta));
  setLandscapeVolumeThrottled(next);
};

const toggleLandscapeMute = (): void => {
  if (status.volume > 0) {
    lastLandscapeVolume.value = status.volume;
    player.setVolume(0);
  } else {
    player.setVolume(lastLandscapeVolume.value || 0.7);
  }
};

const currentPageType = computed<MobilePageType>(() => pageTypes.value[pageIndex.value] ?? "info");
const lyricRendererVisible = computed(
  () => isPhoneLandscape.value || currentPageType.value === "lyric",
);
const lyricRendererInteractive = computed(
  () =>
    lyricRendererVisible.value &&
    !status.fullQueueOpen &&
    !heroTransitionActive.value &&
    !uiHidden.value,
);
const isPhonePortraitLyricOpen = computed(
  () => !isPhoneLandscape.value && currentPageType.value === "lyric",
);
const heroTransitionActive = ref(false);
const heroTransitionDirection = ref<"open" | "close">("open");
const heroTransitionAnimating = ref(false);
const heroCoverStyle = ref<CSSProperties>({});
const heroSongDataStyle = ref<CSSProperties>({});
const infoMetaVisible = ref(true);
const pendingLyricControlsReveal = ref(false);
const heroCoverLayerRef = ref<HTMLElement | null>(null);
const heroSongDataLayerRef = ref<HTMLElement | null>(null);
let heroTransitionTimer = 0;
let heroTransitionFrame = 0;

const getHeroStyle = (rect: DOMRect, transition = "none"): CSSProperties => ({
  position: "fixed",
  left: `${rect.left}px`,
  top: `${rect.top}px`,
  width: `${rect.width}px`,
  height: `${rect.height}px`,
  transition,
});

const getHeroCoverStyle = (
  rect: DOMRect,
  transition = "none",
  borderRadius = "32px",
): CSSProperties => ({
  ...getHeroStyle(rect, transition),
  borderRadius,
});

const clearHeroTransitionFrame = (): void => {
  if (!heroTransitionFrame) return;
  cancelAnimationFrame(heroTransitionFrame);
  heroTransitionFrame = 0;
};

const scheduleHeroTransitionFrame = (callback: () => void): void => {
  clearHeroTransitionFrame();
  heroTransitionFrame = requestAnimationFrame(() => {
    heroTransitionFrame = 0;
    callback();
  });
};

const getVisibleCoverRect = (containerEl: HTMLElement | null): DOMRect | undefined => {
  const coverEl = containerEl?.firstElementChild as HTMLElement | null;
  return coverEl?.getBoundingClientRect() ?? containerEl?.getBoundingClientRect();
};

const getActiveHeroRect = (
  heroEl: HTMLElement | null,
  fallbackRectResolver: () => DOMRect | undefined,
): DOMRect | undefined => {
  if (heroTransitionActive.value) {
    const activeRect = heroEl?.getBoundingClientRect();
    if (activeRect) return activeRect;
  }
  return fallbackRectResolver();
};

const finishHeroTransition = (): void => {
  window.clearTimeout(heroTransitionTimer);
  heroTransitionTimer = 0;
  clearHeroTransitionFrame();
  heroTransitionActive.value = false;
  heroTransitionAnimating.value = false;
  if (heroTransitionDirection.value === "close") {
    pendingLyricControlsReveal.value = false;
    requestAnimationFrame(() => {
      infoMetaVisible.value = true;
    });
  } else if (pendingLyricControlsReveal.value && currentPageType.value === "lyric") {
    pendingLyricControlsReveal.value = false;
    requestAnimationFrame(() => {
      showLyricControls();
    });
  }
};

const openLyricPage = async (): Promise<void> => {
  if (
    !hasLyric.value ||
    (heroTransitionActive.value && heroTransitionDirection.value === "open") ||
    (!heroTransitionActive.value && currentPageType.value === "lyric")
  ) {
    return;
  }
  infoMetaVisible.value = true;
  const sourceCoverRect = getActiveHeroRect(heroCoverLayerRef.value, () =>
    getVisibleCoverRect(infoCoverRef.value),
  );
  const sourceSongRect = getActiveHeroRect(heroSongDataLayerRef.value, () =>
    infoSongDataRef.value?.getBoundingClientRect(),
  );
  if (!sourceCoverRect || !sourceSongRect) {
    pageIndex.value = pageTypes.value.indexOf("lyric");
    showLyricControls();
    return;
  }
  pendingLyricControlsReveal.value = true;
  window.clearTimeout(heroTransitionTimer);
  heroTransitionDirection.value = "open";
  heroTransitionActive.value = true;
  heroTransitionAnimating.value = false;
  heroCoverStyle.value = getHeroCoverStyle(sourceCoverRect);
  heroSongDataStyle.value = getHeroStyle(sourceSongRect);
  pageIndex.value = pageTypes.value.indexOf("lyric");
  showLyricControls();
  await nextTick();
  if (!lyricCoverRef.value || !lyricSongDataRef.value) {
    finishHeroTransition();
    return;
  }
  const targetCoverRect = getVisibleCoverRect(lyricCoverRef.value);
  const targetSongRect = lyricSongDataRef.value.getBoundingClientRect();
  if (!targetCoverRect) {
    finishHeroTransition();
    return;
  }
  scheduleHeroTransitionFrame(() => {
    heroTransitionAnimating.value = true;
    heroCoverStyle.value = getHeroCoverStyle(targetCoverRect, HERO_TRANSITION, "6px");
    heroSongDataStyle.value = getHeroStyle(targetSongRect, HERO_TRANSITION);
  });
  heroTransitionTimer = window.setTimeout(finishHeroTransition, HERO_TRANSITION_DURATION_MS);
};

const closeLyricPage = async (): Promise<void> => {
  if (
    (heroTransitionActive.value && heroTransitionDirection.value === "close") ||
    (!heroTransitionActive.value && currentPageType.value !== "lyric")
  ) {
    return;
  }
  const sourceCoverRect = getActiveHeroRect(heroCoverLayerRef.value, () =>
    getVisibleCoverRect(lyricCoverRef.value),
  );
  const sourceSongRect = getActiveHeroRect(heroSongDataLayerRef.value, () =>
    lyricSongDataRef.value?.getBoundingClientRect(),
  );
  if (!sourceCoverRect || !sourceSongRect) {
    finishHeroTransition();
    pageIndex.value = pageTypes.value.indexOf("info");
    return;
  }

  window.clearTimeout(heroTransitionTimer);
  heroTransitionActive.value = true;
  heroTransitionDirection.value = "close";
  heroTransitionAnimating.value = false;
  pendingLyricControlsReveal.value = false;
  infoMetaVisible.value = false;
  heroCoverStyle.value = getHeroCoverStyle(sourceCoverRect, "none", "6px");
  heroSongDataStyle.value = getHeroStyle(sourceSongRect);
  hideLyricControls();
  pageIndex.value = pageTypes.value.indexOf("info");
  await nextTick();
  if (!infoCoverRef.value || !infoSongDataRef.value) {
    finishHeroTransition();
    return;
  }
  const targetCoverRect = getVisibleCoverRect(infoCoverRef.value);
  const targetSongRect = infoSongDataRef.value.getBoundingClientRect();
  if (!targetCoverRect) {
    finishHeroTransition();
    return;
  }

  scheduleHeroTransitionFrame(() => {
    heroTransitionAnimating.value = true;
    heroCoverStyle.value = getHeroCoverStyle(targetCoverRect, HERO_TRANSITION);
    heroSongDataStyle.value = getHeroStyle(targetSongRect, HERO_TRANSITION);
  });
  heroTransitionTimer = window.setTimeout(finishHeroTransition, HERO_TRANSITION_DURATION_MS);
};

watch(currentPageType, (value) => {
  savedPageType.value = value;
  status.mobileFullPlayerPage = value;
});

watch(pageTypes, (types) => {
  const nextIndex = types.indexOf(savedPageType.value);
  pageTransitionDisabled.value = true;
  pageIndex.value = nextIndex >= 0 ? nextIndex : 0;
  window.setTimeout(() => {
    pageTransitionDisabled.value = false;
  }, 80);
});

watch(isPhoneLandscape, (value) => {
  if (!value) status.fullQueueOpen = false;
});

watch(canHideUi, (value) => {
  if (!value) uiHidden.value = false;
});

watch(
  () => status.volume,
  (value) => {
    if (value > 0) lastLandscapeVolume.value = value;
  },
);

/** 横屏封面挂载后注册到状态机，供 Hero 揭幕测量终点位置 */
watch(landscapeCoverRef, (el) => {
  registerLandscapeCover(el);
});

const dragHandleStyle = computed(() => {
  const safeTop = "var(--mobile-safe-top, var(--safe-area-top, 0px))";
  const dragTop = `max(24px, ${safeTop})`;
  if (currentPageType.value === "lyric") {
    return {
      top: dragTop,
      left: "0",
      right: "70px",
      height: `calc(118px + ${safeTop} - ${dragTop})`,
    };
  }
  return {
    top: dragTop,
    left: "0",
    right: "0",
    height: `calc(56px + ${safeTop} + var(--page-zoom-100vh, 100vh) * 0.25 - ${dragTop})`,
  };
});

const contentTransform = computed(() => {
  const pageWidthPct = 100 / totalPages.value;
  const baseOffset = pageIndex.value * pageWidthPct;
  return `translateX(-${baseOffset}%)`;
});

const dragHandleRef = ref<HTMLElement | null>(null);
let dragValue = 0;
const CLOSE_THRESHOLD = 120;
const REVEAL_DISTANCE = 480;
const SPRING_TRANSITION =
  "transform 0.32s cubic-bezier(0.22, 1, 0.36, 1), opacity 0.32s cubic-bezier(0.22, 1, 0.36, 1)";
let parentEl: HTMLElement | null = null;
let mainEl: HTMLElement | null = null;
let dragStarted = false;
let rafId = 0;
let pendingDy = 0;

const writeStyles = (dy: number): void => {
  if (parentEl) {
    if (dy <= 0) {
      parentEl.style.transform = "";
    } else {
      const scale = Math.max(0.92, 1 - dy / 2400);
      parentEl.style.transform = `translate3d(0, ${dy}px, 0) scale(${scale})`;
    }
  }
  if (mainEl) {
    if (dy <= 0) {
      mainEl.style.opacity = "";
      mainEl.style.transform = "";
    } else {
      const progress = Math.min(dy / REVEAL_DISTANCE, 1);
      mainEl.style.opacity = String(progress);
      mainEl.style.transform = `scale(${0.9 + progress * 0.1})`;
    }
  }
};

const scheduleFlush = (dy: number): void => {
  pendingDy = dy;
  if (rafId) return;
  rafId = requestAnimationFrame(() => {
    rafId = 0;
    writeStyles(pendingDy);
  });
};

const beginDrag = (): void => {
  parentEl = (mobileStart.value?.closest(".full-player") as HTMLElement | null) ?? null;
  mainEl = document.getElementById("main") ?? document.getElementById("main-app-root") ?? null;
  if (parentEl) {
    parentEl.style.transition = "none";
    parentEl.style.willChange = "transform";
    parentEl.style.transformOrigin = "50% 0";
    parentEl.style.borderRadius = "28px";
    parentEl.style.backfaceVisibility = "hidden";
    parentEl.style.backdropFilter = "blur(48px)";
    parentEl.style.contain = "paint";
  }
  if (mainEl) {
    mainEl.style.transition = "none";
    mainEl.style.willChange = "transform, opacity";
  }
};

const clearWillChange = (): void => {
  if (parentEl) parentEl.style.willChange = "";
  if (mainEl) mainEl.style.willChange = "";
};

const resetInlineStyles = (): void => {
  if (parentEl) {
    parentEl.style.transition = "";
    parentEl.style.transform = "";
    parentEl.style.borderRadius = "";
    parentEl.style.transformOrigin = "";
    parentEl.style.willChange = "";
    parentEl.style.backfaceVisibility = "";
    parentEl.style.backdropFilter = "";
    parentEl.style.contain = "";
  }
  if (mainEl) {
    mainEl.style.transition = "";
    mainEl.style.opacity = "";
    mainEl.style.transform = "";
    mainEl.style.willChange = "";
  }
};

const finishCardDrag = (shouldClose: boolean): void => {
  if (rafId) {
    cancelAnimationFrame(rafId);
    rafId = 0;
  }

  if (shouldClose) {
    if (parentEl) {
      parentEl.style.transition = "";
    }
    if (mainEl) {
      mainEl.style.transition =
        "opacity 0.32s ease, transform 0.32s cubic-bezier(0.22, 1, 0.36, 1)";
      mainEl.style.opacity = "1";
      mainEl.style.transform = "scale(1)";
    }
    emit("collapse");
    window.setTimeout(() => {
      clearWillChange();
      resetInlineStyles();
    }, 400);
  } else {
    if (parentEl) {
      parentEl.style.transition = SPRING_TRANSITION;
      parentEl.style.borderRadius = "";
    }
    if (mainEl) mainEl.style.transition = SPRING_TRANSITION;
    writeStyles(0);
    window.setTimeout(() => {
      clearWillChange();
      resetInlineStyles();
    }, 360);
  }

  dragValue = 0;
};

let dragDirectionLock: "h" | "v" | null = null;

const { lengthX: topLengthX, lengthY: topLengthY } = useSwipe(dragHandleRef, {
  threshold: 0,
  passive: true,
  onSwipeStart: () => {
    dragDirectionLock = null;
    dragStarted = false;
  },
  onSwipe: () => {
    if (dragDirectionLock === "h") return;
    if (!dragDirectionLock) {
      const ax = Math.abs(topLengthX.value);
      const ay = Math.abs(topLengthY.value);
      if (Math.max(ax, ay) < DIRECTION_LOCK_TOLERANCE) return;
      dragDirectionLock = ay > ax ? "v" : "h";
      if (dragDirectionLock === "h") return;
    }
    if (!dragStarted) {
      beginDrag();
      dragStarted = true;
    }
    const dy = -topLengthY.value;
    dragValue = dy >= 0 ? dy : dy * 0.2;
    scheduleFlush(Math.max(dragValue, 0));
  },
  onSwipeEnd: () => {
    const wasDragging = dragStarted;
    dragDirectionLock = null;
    dragStarted = false;
    if (wasDragging) finishCardDrag(dragValue > CLOSE_THRESHOLD);
  },
});

let pointerCloseId: number | null = null;
let pointerCloseStartX = 0;
let pointerCloseStartY = 0;
let pointerCloseLocked: "h" | "v" | null = null;
let pointerCloseStarted = false;

const resetPointerCloseDrag = (): void => {
  pointerCloseId = null;
  pointerCloseLocked = null;
  pointerCloseStarted = false;
};

const onClosePointerDown = (event: PointerEvent): void => {
  event.preventDefault();
  event.stopPropagation();
  (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
  pointerCloseId = event.pointerId;
  pointerCloseStartX = event.clientX;
  pointerCloseStartY = event.clientY;
  pointerCloseLocked = null;
  pointerCloseStarted = false;
  dragValue = 0;
};

const onClosePointerMove = (event: PointerEvent): void => {
  if (pointerCloseId !== event.pointerId) return;
  event.stopPropagation();
  const dx = event.clientX - pointerCloseStartX;
  const dy = event.clientY - pointerCloseStartY;
  if (!pointerCloseLocked) {
    if (Math.max(Math.abs(dx), Math.abs(dy)) < DIRECTION_LOCK_TOLERANCE) return;
    pointerCloseLocked = Math.abs(dy) > Math.abs(dx) ? "v" : "h";
    if (pointerCloseLocked === "v") {
      (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
    }
  }
  if (pointerCloseLocked !== "v") return;
  event.preventDefault();
  if (!pointerCloseStarted) {
    beginDrag();
    pointerCloseStarted = true;
  }
  dragValue = dy >= 0 ? dy : dy * 0.2;
  scheduleFlush(Math.max(dragValue, 0));
};

const onClosePointerEnd = (event: PointerEvent): void => {
  if (pointerCloseId !== event.pointerId) return;
  event.preventDefault();
  event.stopPropagation();
  (event.currentTarget as HTMLElement).releasePointerCapture?.(event.pointerId);
  const shouldClose = pointerCloseStarted && dragValue > CLOSE_THRESHOLD;
  const shouldRestore = pointerCloseStarted;
  resetPointerCloseDrag();
  if (shouldRestore) finishCardDrag(shouldClose);
};

const onClosePointerCancel = (event: PointerEvent): void => {
  if (pointerCloseId !== event.pointerId) return;
  event.stopPropagation();
  (event.currentTarget as HTMLElement).releasePointerCapture?.(event.pointerId);
  const shouldRestore = pointerCloseStarted;
  resetPointerCloseDrag();
  if (shouldRestore) finishCardDrag(false);
};

const onSeekDragEnd = (value: number) => {
  player.seek(value);
};

const lyricControlsVisible = ref(false);
const lyricControlsWakeBlocking = ref(false);
const quickActionsOpen = ref(false);
let lyricControlsTimer = 0;
let lyricControlsWakeBlockTimer = 0;
let ignoreControlsClickUntil = 0;
let lyricPointerStart: { x: number; y: number; time: number; controlsTap: boolean } | null = null;
let lyricWakeShieldPointerStart: { id: number; x: number; y: number; time: number } | null = null;
const LYRIC_CONTROLS_AUTO_HIDE_MS = 3000;
const LYRIC_CONTROLS_TAP_THRESHOLD = 10;
const LYRIC_CONTROLS_WAKE_ZONE_MIN = 112;
const LYRIC_CONTROLS_WAKE_ZONE_MAX = 152;
const LYRIC_CONTROLS_WAKE_ZONE_RATIO = 0.16;
/** 歌词页 Hero 打开阶段沿用信息页底栏壳层，避免先卸载再挂载导致底栏闪一下。 */
const keepSharedControlsVisibleDuringLyricOpen = computed(
  () =>
    pendingLyricControlsReveal.value ||
    (heroTransitionActive.value && heroTransitionDirection.value === "open"),
);
const sharedControlsVisible = computed(
  () =>
    !isPhoneLandscape.value &&
    (currentPageType.value === "info" ||
      lyricControlsVisible.value ||
      quickActionsOpen.value ||
      keepSharedControlsVisibleDuringLyricOpen.value),
);
const lyricControlsWakeShieldVisible = computed(
  () =>
    !isPhoneLandscape.value &&
    currentPageType.value === "lyric" &&
    !sharedControlsVisible.value &&
    !status.fullQueueOpen &&
    !heroTransitionActive.value,
);

const clearLyricControlsTimer = (): void => {
  if (!lyricControlsTimer) return;
  window.clearTimeout(lyricControlsTimer);
  lyricControlsTimer = 0;
};

const hideLyricControls = (): void => {
  clearLyricControlsTimer();
  lyricControlsVisible.value = false;
};

const armLyricControlsAutoHide = (): void => {
  if (quickActionsOpen.value) return;
  clearLyricControlsTimer();
  lyricControlsTimer = window.setTimeout(hideLyricControls, LYRIC_CONTROLS_AUTO_HIDE_MS);
};

const showLyricControls = (): void => {
  lyricControlsVisible.value = true;
  ignoreControlsClickUntil = Date.now() + 400;
  armLyricControlsAutoHide();
};

const onControlsClickCapture = (event: MouseEvent): void => {
  const target = event.target as HTMLElement | null;
  if (target?.closest(".lyric-page-toggle")) return;
  if (Date.now() < ignoreControlsClickUntil) {
    event.stopPropagation();
    event.preventDefault();
  }
};

const getLyricControlsWakeZoneHeight = (): number => {
  const baseHeight = Math.min(
    LYRIC_CONTROLS_WAKE_ZONE_MAX,
    Math.max(LYRIC_CONTROLS_WAKE_ZONE_MIN, window.innerHeight * LYRIC_CONTROLS_WAKE_ZONE_RATIO),
  );
  const safeBottom = getComputedStyle(document.documentElement)
    .getPropertyValue("--android-fullscreen-safe-bottom")
    .trim();
  return baseHeight + (Number.parseFloat(safeBottom) || 0);
};

const isLyricControlsWakeZone = (event: PointerEvent): boolean => {
  const lyricBody = event.currentTarget as HTMLElement | null;
  const bottom = lyricBody?.getBoundingClientRect().bottom ?? window.innerHeight;
  return event.clientY >= bottom - getLyricControlsWakeZoneHeight();
};

const onLyricPointerDown = (event: PointerEvent): void => {
  const controlsTap = isLyricControlsWakeZone(event);
  lyricPointerStart = {
    x: event.clientX,
    y: event.clientY,
    time: event.timeStamp,
    controlsTap,
  };
  // Kotlin 渲染器的原生排除区已阻止唤醒区 tap 的 seek，
  // 此处再 suppressNextTapSeek 会误伤下一次合法歌词点击
  if (controlsTap && androidLyricRenderMode.value !== "kotlin") lyricRef.value?.suppressTapSeek();
};

const onLyricPointerUp = (event: PointerEvent): void => {
  if (!lyricPointerStart) return;
  const start = lyricPointerStart;
  lyricPointerStart = null;
  const dx = event.clientX - start.x;
  const dy = event.clientY - start.y;
  if (Math.hypot(dx, dy) > LYRIC_CONTROLS_TAP_THRESHOLD) return;
  if (event.timeStamp - start.time > 320) return;
  if (!start.controlsTap) return;
  event.stopPropagation();
  event.preventDefault();
  if (lyricControlsVisible.value) {
    hideLyricControls();
    return;
  }
  // 唤起底栏的这次触摸只负责显示，避免刚出现的控件被同一次手势误触导致闪隐。
  armLyricControlsWakeBlock();
  showLyricControls();
};

const onLyricPointerCancel = (): void => {
  lyricPointerStart = null;
};

const onLyricWakeShieldPointerDown = (event: PointerEvent): void => {
  event.stopPropagation();
  event.preventDefault();
  // Kotlin 渲染器的原生排除区已阻止唤醒区 tap 的 seek，
  // 此处再 suppressNextTapSeek 会误伤下一次合法歌词点击
  if (androidLyricRenderMode.value !== "kotlin") lyricRef.value?.suppressTapSeek();
  lyricWakeShieldPointerStart = {
    id: event.pointerId,
    x: event.clientX,
    y: event.clientY,
    time: event.timeStamp,
  };
  (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
};

const onLyricWakeShieldPointerUp = (event: PointerEvent): void => {
  if (lyricWakeShieldPointerStart?.id !== event.pointerId) return;
  event.stopPropagation();
  event.preventDefault();
  (event.currentTarget as HTMLElement).releasePointerCapture?.(event.pointerId);
  const start = lyricWakeShieldPointerStart;
  lyricWakeShieldPointerStart = null;
  const dx = event.clientX - start.x;
  const dy = event.clientY - start.y;
  if (Math.hypot(dx, dy) > LYRIC_CONTROLS_TAP_THRESHOLD) return;
  if (event.timeStamp - start.time > 320) return;
  armLyricControlsWakeBlock();
  showLyricControls();
};

const onLyricWakeShieldPointerCancel = (event: PointerEvent): void => {
  if (lyricWakeShieldPointerStart?.id !== event.pointerId) return;
  event.stopPropagation();
  (event.currentTarget as HTMLElement).releasePointerCapture?.(event.pointerId);
  lyricWakeShieldPointerStart = null;
};

const armLyricControlsWakeBlock = (): void => {
  lyricControlsWakeBlocking.value = true;
  window.clearTimeout(lyricControlsWakeBlockTimer);
  lyricControlsWakeBlockTimer = window.setTimeout(releaseLyricControlsWakeBlock, 0);
};

const releaseLyricControlsWakeBlock = (): void => {
  window.clearTimeout(lyricControlsWakeBlockTimer);
  lyricControlsWakeBlockTimer = 0;
  lyricControlsWakeBlocking.value = false;
};

const onLyricControlsInteract = (): void => {
  armLyricControlsAutoHide();
};

const onQuickActionsOpenChange = (value: boolean): void => {
  quickActionsOpen.value = value;
  if (value) {
    lyricControlsVisible.value = true;
    clearLyricControlsTimer();
    return;
  }
  if (currentPageType.value === "lyric" && lyricControlsVisible.value) {
    armLyricControlsAutoHide();
  }
};

interface MobileLyricHostController {
  setCurrentTime: (time: number) => void;
  freeze: () => void;
  resume: () => void;
  refreshLayout: () => void;
  suppressTapSeek: () => void;
}

const lyricRef = ref<MobileLyricHostController | null>(null);
const lyricMounted = ref(false);
const initialLyricTimeMs = ref(0);
let lyricViewportRefreshTimer = 0;
let previewLyricAnchorMs = 0;
let previewLyricAnchorAt = performance.now();

const lyricClockPlaying = computed(() => {
  if (!isAndroidPreview) return isPlaying.value;
  if (status.state === "paused" || status.state === "stopped") return false;
  if (status.state === "playing") return true;
  return isPhoneLandscape.value && hasLyric.value && hasTrack.value && !status.trackLoading;
});

const syncPreviewLyricClock = (positionMs = status.position): void => {
  if (!isAndroidPreview) return;
  previewLyricAnchorMs = Math.max(0, positionMs);
  previewLyricAnchorAt = performance.now();
};

const getLyricClockTime = (fallbackMs = getCurrentTime()): number => {
  if (!isAndroidPreview) return fallbackMs;
  const elapsedMs = lyricClockPlaying.value
    ? (performance.now() - previewLyricAnchorAt) * status.speed
    : 0;
  const durationMs = status.duration > 0 ? status.duration : Number.POSITIVE_INFINITY;
  return Math.min(previewLyricAnchorMs + elapsedMs, durationMs);
};

const { start: startTick, stop: stopTick } = usePlaybackTime((currentMs) => {
  if (!status.trackLoading && !media.lyricLoading) {
    const lyricTimeMs = getLyricClockTime(currentMs);
    lyricRef.value?.setCurrentTime(lyricTimeMs + status.lyricOffsetMs);
  }
});

const activateLyricRenderer = (): void => {
  syncPreviewLyricClock();
  initialLyricTimeMs.value = getLyricClockTime() + status.lyricOffsetMs;
  if (!lyricMounted.value) lyricMounted.value = true;
  window.clearTimeout(lyricViewportRefreshTimer);
  nextTick(() => {
    lyricRef.value?.resume();
    // 避免在 Hero 转场动画开始的同一帧进行同步重排，防止丢帧
    if (!heroTransitionActive.value) {
      lyricRef.value?.refreshLayout();
    }
    lyricViewportRefreshTimer = window.setTimeout(
      () => {
        lyricRef.value?.refreshLayout();
      },
      heroTransitionActive.value ? 600 : 340,
    );
    if (usesNativeKotlinLyricClock.value) stopTick();
    else startTick();
  });
};

watch(pageIndex, (idx) => {
  if (pageTypes.value[idx] !== "lyric") {
    hideLyricControls();
    window.clearTimeout(lyricViewportRefreshTimer);
    if (!isPhoneLandscape.value) {
      lyricRef.value?.freeze();
      stopTick();
    }
  }
  if (pageTypes.value[idx] === "lyric") {
    activateLyricRenderer();
  }
});

// 横屏歌词始终可见，进入横屏时自动挂载歌词并启动时钟
watch(isPhoneLandscape, (landscape) => {
  if (landscape && hasLyric.value) {
    activateLyricRenderer();
  } else if (!landscape && currentPageType.value !== "lyric") {
    lyricRef.value?.freeze();
    stopTick();
  }
});

watch(hasLyric, (value) => {
  if (value && isAndroidPreview && isPhoneLandscape.value) {
    activateLyricRenderer();
    return;
  }
  if (value && lyricMounted.value) {
    initialLyricTimeMs.value = getLyricClockTime() + status.lyricOffsetMs;
  }
});

watch(
  () => media.parsedLyric,
  () => {
    lyricRef.value?.setCurrentTime(getLyricClockTime() + status.lyricOffsetMs);
  },
);

watch(
  () => [status.position, status.state, status.speed] as const,
  ([positionMs]) => {
    syncPreviewLyricClock(positionMs);
  },
);

watch(usesNativeKotlinLyricClock, (usesNativeClock) => {
  if (usesNativeClock) {
    stopTick();
  } else if (lyricMounted.value && (isPhoneLandscape.value || currentPageType.value === "lyric")) {
    startTick();
  }
});

onMounted(() => {
  syncPreviewLyricClock();
  if (isAndroidPreview && isPhoneLandscape.value && hasLyric.value) {
    activateLyricRenderer();
    return;
  }
  if (pageIndex.value === 1) {
    initialLyricTimeMs.value = getLyricClockTime() + status.lyricOffsetMs;
    lyricMounted.value = true;
    nextTick(() => {
      lyricRef.value?.resume();
      if (usesNativeKotlinLyricClock.value) stopTick();
      else startTick();
    });
  }
});

onBeforeUnmount(() => {
  if (rafId) cancelAnimationFrame(rafId);
  window.clearTimeout(heroTransitionTimer);
  clearHeroTransitionFrame();
  window.clearTimeout(lyricViewportRefreshTimer);
  releaseLyricControlsWakeBlock();
  clearLyricControlsTimer();
  resetPointerCloseDrag();
  resetInlineStyles();
  lyricRef.value?.freeze();
  stopTick();
});

const springConfig = computed(() => ({
  mass: settings.lyric.springMass,
  damping: settings.lyric.springDamping,
  stiffness: settings.lyric.springStiffness,
}));

const lyricPageToggleAction = computed<"open" | "close">(() => {
  if (heroTransitionActive.value) {
    return heroTransitionDirection.value === "open" ? "close" : "open";
  }
  return currentPageType.value === "lyric" ? "close" : "open";
});

/** Android 上歌词渲染字重 = 设置字重 × 2（上限 1000，即 CSS font-weight 有效区间上限，超出会使声明无效回落到 400），补偿 WebView 字重视觉偏细 */
const lyricRenderWeight = computed(() => Math.min(settings.lyric.fontWeight * 2, 1000));

watch(
  () => [
    settings.lyric.fontSize,
    settings.lyric.fontSizeLandscape,
    settings.lyric.fontWeight,
    settings.lyric.fontFamily,
  ],
  () => {
    nextTick(() => lyricRef.value?.refreshLayout());
  },
);
</script>

<template>
  <div
    ref="mobileStart"
    class="full-player-mobile"
    :class="{ 'pad-portrait': isPadPortrait, 'phone-landscape': isPhoneLandscape }"
    :style="{
      '--page-count': totalPages,
      '--landscape-cover-offset-x': `${settings.lyric.landscapeCoverOffsetX}px`,
      '--landscape-lyric-padding-x': `${settings.lyric.landscapeLyricPaddingX}px`,
    }"
    :data-orientation-phase="orientationPhase"
    :data-hero-close="heroTransitionActive && heroTransitionDirection === 'close'"
  >
    <div
      class="mobile-top-bar"
      :class="{ 'is-ui-hidden': uiHidden, 'is-lyric-open': isPhonePortraitLyricOpen }"
    >
      <div class="top-bar-left">
        <!-- 纯净歌词切换（对齐参考项目 PlayerMenu 的 TextPlay 图标） -->
        <div v-if="hasLyric" class="menu-icon" :class="{ open: !showLyric }" @click="toggleLyric">
          <IconLucideTextQuote :size="22" />
        </div>
      </div>
      <div class="top-bar-right">
        <div
          v-if="canEnterImmersive || canExitLandscape || isImmersiveLandscape"
          class="menu-icon"
          @click="canExitLandscape || isImmersiveLandscape ? onExitImmersive() : onEnterImmersive()"
        >
          <IconLucideMinimize v-if="canExitLandscape || isImmersiveLandscape" :size="22" />
          <IconLucideFullscreen v-else :size="22" />
        </div>
        <div class="menu-icon top-bar-collapse-button" @click="onTopBarCollapse">
          <IconLucideChevronDown :size="22" />
        </div>
      </div>
    </div>

    <!-- 手机横屏手动隐藏底栏后的轻触恢复拦截层 -->
    <div v-if="canHideUi && uiHidden" class="ui-restore-shield" @pointerdown="onTapRestoreUi" />

    <OrientationOverlay />

    <div
      ref="dragHandleRef"
      class="drag-close-handle"
      :style="dragHandleStyle"
      aria-hidden="true"
      @pointerdown="onClosePointerDown"
      @pointermove="onClosePointerMove"
      @pointerup="onClosePointerEnd"
      @pointercancel="onClosePointerCancel"
    />

    <BottomSpectrum
      v-if="isPhoneLandscape && settings.player.enableSpectrum"
      :show="isPlaying"
      :height="72"
    />

    <!-- 手机横屏：左右分栏布局 -->
    <div v-if="isPhoneLandscape" class="landscape-layout">
      <!-- 左：封面 + 歌曲信息 -->
      <div class="landscape-left">
        <div ref="landscapeCoverRef" class="landscape-cover" data-stagger="cover">
          <PlayerCover />
        </div>
        <div class="landscape-info" data-stagger="title">
          <div class="landscape-name text-hidden">{{ media.track?.title }}</div>
          <div class="landscape-ar-line text-hidden">
            <IconLucideUser :size="14" />
            <span class="ar text-hidden">
              {{ media.track?.artists.map((a) => a.name).join(" / ") }}
            </span>
          </div>
          <div v-if="media.track?.album?.name" class="landscape-album-line text-hidden">
            <IconLucideDisc3 :size="14" />
            <span class="album text-hidden">{{ media.track.album.name }}</span>
          </div>
        </div>
      </div>

      <!-- 右：歌词 -->
      <div class="landscape-right" :class="{ 'is-queue-open': status.fullQueueOpen }">
        <div
          class="lyric-body landscape-lyric-body"
          data-stagger="lyric"
          :style="{
            fontSize: `${settings.lyric.fontSizeLandscape}px`,
            fontWeight: String(lyricRenderWeight),
            fontFamily: settings.lyric.fontFamily || undefined,
          }"
        >
          <AMLLLyrics
            v-if="lyricMounted && hasLyric && settings.lyric.engine === 'amll'"
            ref="lyricRef"
            :lyric-lines="media.parsedLyric"
            :initial-time="initialLyricTimeMs"
            :time-offset-ms="status.lyricOffsetMs"
            :playing="lyricClockPlaying"
            :align-position="settings.lyric.alignPosition"
            :word-fade-width="settings.lyric.wordFadeWidth"
            :hide-passed-lines="settings.lyric.hidePassedLines"
            :enable-blur="settings.lyric.enableBlur"
            :show-translation="settings.lyric.showTranslation"
            :show-line-romanization="settings.lyric.amllShowLineRomanization"
            :show-word-romanization="settings.lyric.amllShowWordRomanization"
            @seek="player.seek($event)"
          />
          <AndroidMainLyricHost
            v-else-if="lyricMounted && hasLyric"
            ref="lyricRef"
            :lyric-lines="media.parsedLyric"
            :initial-time="initialLyricTimeMs"
            :playing="lyricClockPlaying"
            :font-weight="settings.lyric.fontWeight"
            :font-family="settings.lyric.fontFamily"
            :align-position="settings.lyric.alignPosition"
            :word-fade-width="settings.lyric.wordFadeWidth"
            :spring-config="springConfig"
            :inactive-alpha="settings.lyric.inactiveAlpha"
            :hide-passed-lines="settings.lyric.hidePassedLines"
            :enable-blur="settings.lyric.enableBlur"
            :enable-word-highlight="settings.lyric.enableWordHighlight"
            :enable-float-animation="settings.lyric.enableFloatAnimation"
            :enable-emphasize-effect="settings.lyric.enableEmphasizeEffect"
            :show-translation="settings.lyric.showTranslation"
            :show-romanization="settings.lyric.showRomanization"
            :unlock-fps-limit="settings.system.androidLyric.unlockFpsLimit"
            :render-mode="androidLyricRenderMode"
            :bottom-exclusion-height-px="0"
            :visible="lyricRendererVisible && !pickerOpen"
            :interactive="lyricRendererInteractive"
            @seek="player.seek($event)"
          />
          <div
            v-else-if="lyricMounted && !hasLyric"
            class="w-full h-full flex items-center justify-center text-cover/30"
          >
            暂无歌词
          </div>
        </div>
        <Transition name="landscape-queue">
          <div
            v-if="status.fullQueueOpen"
            class="landscape-queue-panel"
            data-no-page-swipe
            @pointerdown.stop
            @pointermove.stop
            @pointerup.stop
            @touchstart.stop.passive
            @touchmove.stop.passive
            @touchend.stop.passive
          >
            <QueuePanel @close="status.fullQueueOpen = false" />
          </div>
        </Transition>
      </div>
    </div>

    <!-- 横屏底栏：进度条 + 控制按钮（对齐参考项目 PlayerControl 三列 grid） -->
    <div
      v-if="isPhoneLandscape"
      class="landscape-controls"
      :class="uiHidden ? 'is-ui-hidden' : ''"
      data-no-page-swipe
      data-stagger="control"
    >
      <div class="landscape-control-left">
        <!-- 喜欢歌曲 -->
        <div
          class="menu-icon"
          :class="{ disabled: !hasTrack }"
          @click="hasTrack && fav.toggle(media.track)"
        >
          <IconFavorite v-if="fav.isLiked(media.track)" :size="22" />
          <IconFavoriteOutline v-else :size="22" />
        </div>
        <!-- 快捷操作菜单（频谱/AMLL/动态封面/逐词等） -->
        <QuickActionsMenu variant="control" cover landscape />
        <!-- 隐藏界面 -->
        <div v-if="canHideUi" class="menu-icon" aria-label="隐藏界面" @click="onHideUi">
          <IconLucideEyeOff :size="22" />
        </div>
      </div>
      <div class="landscape-control-center">
        <div class="landscape-buttons">
          <div
            class="btn-icon"
            @click="
              fmMode
                ? player.dislikeFmTrack()
                : heartMode
                  ? player.exitHeartMode()
                  : player.toggleShuffleMode()
            "
          >
            <IconLucideHeartOff v-if="fmMode" :size="20" />
            <IconSpHeartMode v-else-if="heartMode" :size="20" />
            <IconLucideShuffle v-else-if="shuffleMode === 'on'" :size="20" />
            <IconSpPlayOrder v-else :size="20" />
          </div>
          <div
            class="btn-icon"
            :class="{ disabled: !media.track || fmMode }"
            @click="player.prevTrack()"
          >
            <IconLucideSkipBack :size="26" />
          </div>
          <div
            class="btn-icon play-pause"
            :class="{ disabled: !media.track && !isLoading, loading: isLoading }"
            @click="player.togglePlay()"
          >
            <IconLucidePause v-if="isPlaying" :size="28" />
            <IconLucidePlay v-else :size="28" />
          </div>
          <div class="btn-icon" :class="{ disabled: !media.track }" @click="player.nextTrack(true)">
            <IconLucideSkipForward :size="26" />
          </div>
          <div class="btn-icon" :class="{ disabled: fmMode }" @click="player.cycleRepeatMode()">
            <IconLucideInfinity v-if="fmMode" :size="20" />
            <IconLucideRepeat1 v-else-if="repeatMode === 'one'" :size="20" />
            <IconLucideRepeat v-else :size="20" />
          </div>
        </div>
        <div class="landscape-progress">
          <span class="landscape-time-text">{{ formatTime(position) }}</span>
          <WavySeekBar
            v-if="settings.appearance.wavyProgressBar"
            :model-value="position"
            :min="0"
            :max="duration"
            :step="100"
            :playing="lyricClockPlaying"
            :height="8"
            :stroke-width="3"
            :wavelength="24"
            :amplitude="3.2"
            :wave-speed="28"
            active-color="rgb(var(--s-cover))"
            inactive-color="rgb(var(--s-cover) / 0.25)"
            thumb-color="rgb(var(--s-cover))"
            class="flex-1"
            @drag-end="onSeekDragEnd"
          />
          <SSlider
            v-else
            :model-value="position"
            :min="0"
            :max="duration"
            :step="100"
            :track-height="3"
            :thumb-size="10"
            cover
            class="flex-1"
            @drag-end="onSeekDragEnd"
          />
          <span class="landscape-time-text">
            -{{ formatTime(Math.max(duration - position, 0)) }}
          </span>
        </div>
      </div>
      <div class="landscape-control-right">
        <QualityControl :cover="true" />
        <SPopover
          v-model:open="landscapeVolumePopoverOpen"
          trigger="click"
          side="top"
          align="center"
          :side-offset="10"
          cover
          content-class="!p-0"
        >
          <template #trigger>
            <div class="menu-icon" aria-label="音量" @wheel.prevent="onLandscapeVolumeWheel">
              <IconLucideVolumeX v-if="volumePercent === 0" :size="22" />
              <IconLucideVolume1 v-else-if="volumePercent < 50" :size="22" />
              <IconLucideVolume2 v-else :size="22" />
            </div>
          </template>
          <div class="landscape-volume-panel" @wheel.prevent="onLandscapeVolumeWheel">
            <div class="landscape-volume-slider">
              <SSlider
                :model-value="status.volume"
                :min="0"
                :max="1"
                :step="0.01"
                :thumb-size="15"
                :track-height="5"
                cover
                vertical
                @change="player.setVolume($event)"
              />
            </div>
            <button class="landscape-volume-value" type="button" @click="toggleLandscapeMute">
              {{ volumePercent }}%
            </button>
          </div>
        </SPopover>
        <div
          v-if="!fmMode"
          class="menu-icon landscape-queue-trigger"
          :class="{ open: status.fullQueueOpen }"
          aria-label="播放列表"
          @click="status.fullQueueOpen = !status.fullQueueOpen"
        >
          <IconLucideListMusic :size="22" />
          <span v-if="queueLength > 0" class="landscape-queue-badge">
            {{ landscapeQueueBadgeText }}
          </span>
        </div>
      </div>
    </div>

    <!-- 手机竖屏：滑动双页布局 -->
    <div
      v-if="!isPhoneLandscape"
      class="mobile-pages"
      :class="{
        'transition-none': pageTransitionDisabled,
        'lyric-sheet-open': isPhonePortraitLyricOpen,
        'lyric-hero-active': heroTransitionActive,
        'lyric-hero-open': heroTransitionActive && heroTransitionDirection === 'open',
        'lyric-hero-close': heroTransitionActive && heroTransitionDirection === 'close',
      }"
      :style="{ width: `${totalPages * 100}%`, transform: contentTransform }"
    >
      <div class="mobile-page info-page" :style="{ width: `${100 / totalPages}%` }">
        <div class="cover-region">
          <div ref="infoCoverRef" class="cover-shell">
            <PlayerCover />
          </div>
        </div>

        <div class="info-controls">
          <div ref="infoSongDataRef" class="song-data" :class="{ 'meta-hidden': !infoMetaVisible }">
            <PlayerData align="center" />
          </div>
        </div>
      </div>

      <div
        v-if="hasLyric"
        class="mobile-page lyric-page"
        :style="{ width: `${100 / totalPages}%` }"
      >
        <div class="lyric-header">
          <div class="lyric-track-info">
            <div ref="lyricCoverRef" class="lyric-cover">
              <SImg :src="media.track?.cover" class="size-full object-cover" decoding="async" />
            </div>
            <div class="lyric-title-block min-w-0 flex-1 flex flex-col items-start">
              <div ref="lyricSongDataRef" class="max-w-full flex flex-col items-start">
                <div class="truncate text-lg font-bold text-cover mb-0.5 max-w-full">
                  {{ media.track?.title }}
                </div>
                <div class="truncate text-[13px] text-cover/60 max-w-full">
                  {{ media.track?.artists.map((a) => a.name).join(" / ") }}
                </div>
              </div>
            </div>
          </div>
          <SButton
            type="cover"
            variant="ghost"
            circle
            :size="48"
            class="shrink-0 ml-3"
            @click="fav.toggle(media.track)"
          >
            <template #icon>
              <IconFavorite v-if="fav.isLiked(media.track)" class="size-7" />
              <IconFavoriteOutline v-else class="size-7" />
            </template>
          </SButton>
        </div>

        <div
          class="lyric-body"
          :style="{
            /* 手机端使用 vmin 缩放（/ 430 * 100vmin），
               保证竖屏/横屏物理大小一致，防止横屏压缩成一条线；
               不加 clamp 上限——与桌面端行为一致。 */
            fontSize: settings.lyric.adaptiveFontSize
              ? `calc(${settings.lyric.fontSize} / 430 * 100vmin)`
              : `${settings.lyric.fontSize}px`,
            fontWeight: String(lyricRenderWeight),
            fontFamily: settings.lyric.fontFamily || undefined,
          }"
          @pointerdown="onLyricPointerDown"
          @pointerup="onLyricPointerUp"
          @pointercancel="onLyricPointerCancel"
        >
          <AMLLLyrics
            v-if="lyricMounted && hasLyric && settings.lyric.engine === 'amll'"
            ref="lyricRef"
            :lyric-lines="media.parsedLyric"
            :initial-time="initialLyricTimeMs"
            :playing="lyricClockPlaying"
            :align-position="settings.lyric.alignPosition"
            :word-fade-width="settings.lyric.wordFadeWidth"
            :hide-passed-lines="settings.lyric.hidePassedLines"
            :enable-blur="settings.lyric.enableBlur"
            :show-translation="settings.lyric.showTranslation"
            :show-line-romanization="settings.lyric.amllShowLineRomanization"
            :show-word-romanization="settings.lyric.amllShowWordRomanization"
            @seek="player.seek($event)"
          />
          <AndroidMainLyricHost
            v-else-if="lyricMounted && hasLyric"
            ref="lyricRef"
            :lyric-lines="media.parsedLyric"
            :initial-time="initialLyricTimeMs"
            :time-offset-ms="status.lyricOffsetMs"
            :playing="isPlaying"
            :font-weight="settings.lyric.fontWeight"
            :font-family="settings.lyric.fontFamily"
            :align-position="settings.lyric.alignPosition"
            :word-fade-width="settings.lyric.wordFadeWidth"
            :spring-config="springConfig"
            :inactive-alpha="settings.lyric.inactiveAlpha"
            :hide-passed-lines="settings.lyric.hidePassedLines"
            :enable-blur="settings.lyric.enableBlur"
            :enable-word-highlight="settings.lyric.enableWordHighlight"
            :enable-float-animation="settings.lyric.enableFloatAnimation"
            :enable-emphasize-effect="settings.lyric.enableEmphasizeEffect"
            :enable-word-block-segmentation="enableWordBlockSegmentation"
            :show-translation="settings.lyric.showTranslation"
            :show-romanization="settings.lyric.showRomanization"
            :unlock-fps-limit="settings.system.androidLyric.unlockFpsLimit"
            :render-mode="androidLyricRenderMode"
            :bottom-exclusion-height-px="lyricBottomExclusionPx"
            :visible="lyricRendererVisible && !pickerOpen"
            :interactive="lyricRendererInteractive"
            :kotlin-align-offset="0.1"
            @seek="player.seek($event)"
          />
        </div>
      </div>

      <div v-if="heroTransitionActive" class="lyric-hero-layer" aria-hidden="true">
        <div
          ref="heroCoverLayerRef"
          class="lyric-hero-cover"
          :class="{ 'is-closing': heroTransitionDirection === 'close' }"
          :style="heroCoverStyle"
        >
          <!-- Hero 转场只需要和当前界面视觉一致，使用缩略图避免切页瞬间额外解码高清封面。 -->
          <SImg :src="media.track?.cover" class="size-full object-cover" decoding="async" />
        </div>
        <div
          ref="heroSongDataLayerRef"
          class="lyric-hero-song-data"
          :class="[
            heroTransitionDirection === 'close' && heroTransitionAnimating
              ? 'state-info'
              : 'state-lyric',
          ]"
          :style="heroSongDataStyle"
        >
          <div class="lyric-hero-title truncate">{{ media.track?.title }}</div>
          <div class="lyric-hero-artist truncate">
            {{ media.track?.artists.map((a) => a.name).join(" / ") }}
          </div>
        </div>
      </div>
    </div>

    <div
      v-if="lyricControlsWakeShieldVisible"
      class="lyric-controls-wake-shield"
      aria-hidden="true"
      @pointerdown="onLyricWakeShieldPointerDown"
      @pointerup="onLyricWakeShieldPointerUp"
      @pointercancel="onLyricWakeShieldPointerCancel"
      @click.stop.prevent
    />

    <Transition name="lyric-controls">
      <div
        v-if="sharedControlsVisible"
        class="lyric-floating-controls shared-player-controls"
        :class="{
          'is-ui-hidden': uiHidden,
          'is-wake-blocking': currentPageType === 'lyric' && lyricControlsWakeBlocking,
          'is-transition-locking':
            currentPageType === 'lyric' && keepSharedControlsVisibleDuringLyricOpen,
        }"
        data-no-page-swipe
        @pointerdown.stop="currentPageType === 'lyric' && onLyricControlsInteract()"
        @pointermove.stop
        @pointerup.stop
        @pointercancel.stop
        @touchstart.stop.passive
        @touchmove.stop.passive
        @touchend.stop.passive
        @touchcancel.stop.passive
        @mousedown.stop
        @mouseup.stop
        @click.capture="currentPageType === 'lyric' && onControlsClickCapture($event)"
        @click.stop="currentPageType === 'lyric' && onLyricControlsInteract()"
      >
        <div class="lyric-floating-actions">
          <SButton
            type="cover"
            variant="ghost"
            circle
            :size="40"
            :disabled="!hasTrack"
            @click.stop="hasTrack && fav.toggle(media.track)"
          >
            <template #icon>
              <IconFavorite v-if="fav.isLiked(media.track)" :size="22" />
              <IconFavoriteOutline v-else :size="22" />
            </template>
          </SButton>
          <SButton
            v-if="media.track?.source === 'local' || media.track?.source === 'netease'"
            type="cover"
            variant="ghost"
            circle
            :size="40"
            @click.stop="media.track && openPicker([media.track])"
          >
            <template #icon><IconLucideListPlus :size="22" /></template>
          </SButton>
          <QuickActionsMenu
            variant="mobile"
            :tablet="isAndroidTablet"
            @update:open="onQuickActionsOpenChange"
          />
        </div>

        <div class="lyric-floating-progress">
          <WavySeekBar
            v-if="settings.appearance.wavyProgressBar"
            :model-value="position"
            :min="0"
            :max="duration"
            :step="100"
            :playing="isPlaying"
            :height="14"
            :stroke-width="5"
            :wavelength="28"
            :amplitude="4.5"
            :wave-speed="28"
            active-color="rgb(var(--s-cover))"
            inactive-color="rgb(var(--s-cover) / 0.24)"
            thumb-color="rgb(var(--s-cover))"
            class="w-full"
            @drag-start="currentPageType === 'lyric' && onLyricControlsInteract()"
            @change="currentPageType === 'lyric' && onLyricControlsInteract()"
            @drag-end="onSeekDragEnd"
          />
          <SSlider
            v-else
            :model-value="position"
            :min="0"
            :max="duration"
            :step="100"
            :track-height="4"
            :thumb-size="14"
            cover
            class="w-full"
            @drag-start="currentPageType === 'lyric' && onLyricControlsInteract()"
            @change="currentPageType === 'lyric' && onLyricControlsInteract()"
            @drag-end="onSeekDragEnd"
          />
          <div class="lyric-floating-time-row">
            <span>{{ formatTime(position) }}</span>
            <span class="lyric-floating-quality">{{ qualityLabel }}</span>
            <span>-{{ formatTime(Math.max(duration - position, 0)) }}</span>
          </div>
        </div>

        <div class="lyric-floating-buttons">
          <SButton
            type="cover"
            variant="ghost"
            circle
            :size="56"
            :disabled="!media.track || fmMode"
            @click.stop="player.prevTrack()"
          >
            <template #icon><IconLucideSkipBack :size="32" /></template>
          </SButton>

          <SButton
            type="cover"
            variant="ghost"
            circle
            :size="70"
            :loading="isLoading"
            :disabled="!media.track && !isLoading"
            @click.stop="player.togglePlay()"
          >
            <template #icon>
              <IconLucidePause v-if="isPlaying" :size="42" />
              <IconLucidePlay v-else :size="42" />
            </template>
          </SButton>

          <SButton
            type="cover"
            variant="ghost"
            circle
            :size="56"
            :disabled="!media.track"
            @click.stop="player.nextTrack(true)"
          >
            <template #icon><IconLucideSkipForward :size="32" /></template>
          </SButton>
        </div>

        <button
          v-if="hasLyric"
          class="lyric-entry-bottom lyric-floating-back lyric-page-toggle"
          type="button"
          :aria-label="lyricPageToggleAction === 'close' ? '返回封面页' : '打开歌词页'"
          @pointerdown.stop
          @click.stop="lyricPageToggleAction === 'close' ? closeLyricPage() : openLyricPage()"
        >
          <IconLucideListMusic v-if="lyricPageToggleAction === 'close'" :size="18" />
          <IconLucideTextQuote v-else :size="18" />
          <span>{{ lyricPageToggleAction === "close" ? "封面" : "歌词" }}</span>
        </button>

        <button
          v-if="!fmMode"
          class="mobile-queue-trigger"
          :class="{ open: status.fullQueueOpen }"
          type="button"
          aria-label="播放列表"
          @click.stop="status.fullQueueOpen = !status.fullQueueOpen"
        >
          <IconLucideListMusic :size="20" />
          <span v-if="queueLength > 0" class="landscape-queue-badge">
            {{ landscapeQueueBadgeText }}
          </span>
        </button>
      </div>
    </Transition>

    <Transition name="landscape-queue">
      <div
        v-if="!isPhoneLandscape && status.fullQueueOpen"
        class="mobile-queue-panel"
        data-no-page-swipe
        @pointerdown.stop
        @pointermove.stop
        @pointerup.stop
        @pointercancel.stop
        @touchstart.stop.passive
        @touchmove.stop.passive
        @touchend.stop.passive
        @touchcancel.stop.passive
        @mousedown.stop
        @mouseup.stop
        @click.stop
      >
        <QueuePanel compact @close="status.fullQueueOpen = false" />
      </div>
    </Transition>
  </div>
  <PlaylistPickerDialog v-model:open="pickerOpen" :mode="pickerMode" :tracks="pickerTracks" />
</template>

<style scoped>
.full-player-mobile {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  touch-action: pan-y;
}

.mobile-top-bar {
  position: absolute;
  inset: 0 0 auto;
  height: calc(48px + var(--mobile-safe-top, var(--safe-area-top, 0px)));
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--mobile-safe-top, var(--safe-area-top, 0px)) 16px 0;
  z-index: 30;
  pointer-events: none;
}

.mobile-top-bar .s-button {
  pointer-events: auto;
}

.mobile-top-bar .top-bar-left,
.mobile-top-bar .top-bar-right > .menu-icon {
  transition:
    opacity 0.3s ease,
    background-color 0.3s,
    transform 0.3s;
}

.mobile-top-bar.is-lyric-open .top-bar-left,
.mobile-top-bar.is-lyric-open .top-bar-right > .menu-icon:not(.top-bar-collapse-button) {
  opacity: 0;
  pointer-events: none;
}

.mobile-top-bar-placeholder {
  width: 40px;
  height: 40px;
}

/* 手动隐藏播放页元信息：顶栏、横屏底栏、平板浮动控制区淡出并放弃事件 */
.mobile-top-bar.is-ui-hidden,
.landscape-controls.is-ui-hidden,
.lyric-floating-controls.is-ui-hidden,
.lyric-floating-controls.is-ui-hidden > * {
  opacity: 0;
  pointer-events: none;
}

/* 隐藏 UI 后的全屏轻触恢复拦截层 */
.ui-restore-shield {
  position: absolute;
  inset: 0;
  z-index: 250;
  cursor: default;
  pointer-events: auto;
  touch-action: manipulation;
}

.drag-close-handle {
  position: absolute;
  z-index: 20;
  touch-action: none;
}

.mobile-pages {
  position: relative;
  z-index: 1;
  display: flex;
  height: 100%;
  transition: transform 0.5s cubic-bezier(0.25, 1, 0.5, 1);
}

.mobile-pages.lyric-sheet-open {
  transform: translateX(0) !important;
}

.mobile-page {
  position: relative;
  height: 100%;
  min-width: 0;
  flex-shrink: 0;
  pointer-events: auto;
}

.info-page {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 0 20px calc(24px + var(--mobile-safe-bottom, var(--android-fullscreen-safe-bottom, 0px)));
  transition:
    transform 0.5s cubic-bezier(0.25, 1, 0.5, 1),
    opacity 0.32s ease;
  will-change: transform, opacity;
}

.lyric-sheet-open .info-page {
  transform: translate3d(0, -16px, 0);
  opacity: 0;
  pointer-events: none;
}

/* hero 转场期间 info-page 保持原位不淡出，底栏持续可见被歌词页底栏直接覆盖 */
.lyric-hero-active .info-page {
  transform: none !important;
  opacity: 1 !important;
  pointer-events: auto !important;
}

.info-controls {
  position: absolute;
  right: 20px;
  bottom: calc(
    clamp(204px, 23vh, 248px) +
      var(--mobile-safe-bottom, var(--android-fullscreen-safe-bottom, 0px))
  );
  left: 20px;
  z-index: 10;
  display: flex;
  flex-direction: column;
  pointer-events: auto;
}

.cover-region {
  display: flex;
  width: 100%;
  min-height: clamp(220px, calc(var(--page-zoom-100vh, 100vh) * 0.42), 360px);
  margin-top: calc(52px + var(--mobile-safe-top, var(--safe-area-top, 0px)));
  margin-bottom: 18px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
}

.cover-shell {
  position: relative;
  width: clamp(240px, 72vw, 380px);
}

.lyric-sheet-open .cover-shell {
  opacity: 0;
}

.lyric-hero-active .cover-shell {
  opacity: 0;
}

/* 关闭末段让真实封面延迟淡入，和 Hero 封面交接，掩盖最后一帧的补沉感。 */
.lyric-hero-active.lyric-hero-close .cover-shell {
  opacity: 1;
  transition: opacity 0.12s linear 0.38s;
}

.song-data {
  position: relative;
  z-index: 10;
  width: 100%;
  margin-bottom: 24px;
  pointer-events: auto;
}

.song-data :deep(> div > :not(:first-child)) {
  transition:
    opacity 0.22s ease,
    transform 0.28s cubic-bezier(0.22, 1, 0.36, 1);
}

.song-data.meta-hidden :deep(> div > :not(:first-child)) {
  opacity: 0;
  transform: translate3d(0, -4px, 0);
}

.lyric-sheet-open .song-data {
  opacity: 0;
}

.lyric-hero-active .song-data {
  opacity: 0;
}

.info-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  width: 100%;
  margin-bottom: 12px;
  pointer-events: auto;
}

.progress-region {
  position: relative;
  z-index: 10;
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 8px;
  padding: 0 16px;
  margin-bottom: 24px;
  pointer-events: auto;
}

.controls-region {
  position: relative;
  z-index: 10;
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  padding: 0 8px;
  pointer-events: auto;
}

.lyric-page {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  width: calc(100% / var(--page-count)) !important;
  padding: calc(44px + var(--mobile-safe-top, var(--safe-area-top, 0px))) 20px
    calc(24px + var(--mobile-safe-bottom, var(--android-fullscreen-safe-bottom, 0px)));
  overflow: hidden;
  z-index: 10;
  background:
    radial-gradient(circle at 50% 0%, rgb(var(--s-cover) / 0.18), transparent 42%),
    linear-gradient(
      180deg,
      rgb(var(--s-surface) / 0.28) 0%,
      rgb(var(--s-surface) / 0.08) 56%,
      rgb(var(--s-surface) / 0.18) 100%
    );
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.42s cubic-bezier(0.22, 1, 0.36, 1);
  will-change: opacity;
  contain: paint;
}

.lyric-sheet-open .lyric-page {
  opacity: 1;
  pointer-events: auto;
}

.lyric-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 74px;
  margin-bottom: 20px;
  opacity: 0;
  transition: opacity 0.34s ease 120ms;
}

.lyric-sheet-open .lyric-header {
  opacity: 1;
}

.lyric-track-info {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: center;
  gap: 16px;
}

.lyric-cover {
  width: 50px;
  height: 50px;
  flex-shrink: 0;
  overflow: hidden;
  border-radius: 6px;
  box-shadow: 0 8px 24px rgb(0 0 0 / 0.18);
}

.lyric-sheet-open .lyric-cover,
.lyric-sheet-open .lyric-title-block {
  opacity: 1;
}

.lyric-hero-active .lyric-cover,
.lyric-hero-active .lyric-title-block {
  opacity: 0;
}

.lyric-hero-layer {
  position: fixed;
  inset: 0;
  z-index: 120;
  pointer-events: none;
}

.lyric-hero-cover {
  overflow: hidden;
  border-radius: 32px;
  box-shadow: 0 0 20px 10px rgba(0, 0, 0, 0.1);
  will-change: left, top, width, height, transform, border-radius;
  transform: translateZ(0);
}

.lyric-hero-cover.is-closing {
  animation: hero-cover-close-fade 0.12s linear 0.38s forwards;
}

@keyframes hero-cover-close-fade {
  from {
    opacity: 1;
  }

  to {
    opacity: 0;
  }
}

.lyric-hero-song-data {
  display: flex;
  flex-direction: column;
  justify-content: center;
  color: rgb(var(--s-cover));
  overflow: hidden;
  will-change: left, top, width, height, transform, font-size;
  transform: translateZ(0);
}

.lyric-hero-title,
.lyric-hero-artist {
  transition:
    font-size 0.56s cubic-bezier(0.25, 1, 0.5, 1),
    line-height 0.56s cubic-bezier(0.25, 1, 0.5, 1),
    margin-top 0.56s cubic-bezier(0.25, 1, 0.5, 1);
  will-change: font-size;
}

.lyric-hero-title {
  font-weight: 700;
}

.lyric-hero-artist {
  color: rgb(var(--s-cover) / 0.6);
}

.lyric-hero-song-data.state-info .lyric-hero-title {
  font-size: 24px;
  line-height: 1.2;
}

.lyric-hero-song-data.state-info {
  align-items: center;
  text-align: center;
}

.lyric-hero-song-data.state-info .lyric-hero-artist {
  margin-top: 6px;
  font-size: 14px;
}

.lyric-hero-song-data.state-lyric {
  align-items: flex-start;
  text-align: left;
}

.lyric-hero-song-data.state-lyric .lyric-hero-title {
  font-size: 18px;
  line-height: 1.25;
}

.lyric-hero-song-data.state-lyric .lyric-hero-artist {
  margin-top: 4px;
  font-size: 13px;
}

.lyric-body {
  position: relative;
  min-height: 0;
  flex: 1;
  margin-inline: 8px;
  --lp-padding-x: 0.34em;
  --lp-line-padding: 0.4em 0.34em;
  /* Android WebView 对中文字体默认不做 weight 合成（只挑最接近的可用 weight），
     导致 700 / 900 在很多设备上视觉无差异，平板 700 看着粗、手机 700 看着不够粗。
     显式启用 weight & style 合成，让中间档（500/600/800）真正生效。 */
  font-synthesis: weight style;
  -webkit-font-synthesis: weight style;
  opacity: 0;
  transform: none;
  transition: opacity 0.28s ease 120ms;
  will-change: opacity;
  mask-image: linear-gradient(
    180deg,
    transparent 0%,
    rgb(0 0 0) 10%,
    rgb(0 0 0) 90%,
    transparent 100%
  );
}

.lyric-sheet-open .lyric-body {
  transform: none;
  opacity: 1;
}

.lyric-floating-controls {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 18;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 16px;
  height: calc(
    clamp(260px, 30vh, 320px) +
      var(--mobile-safe-bottom, var(--android-fullscreen-safe-bottom, 0px))
  );
  padding: 64px 36px
    calc(20px + var(--mobile-safe-bottom, var(--android-fullscreen-safe-bottom, 0px)));
  justify-content: flex-end;
  color: rgb(var(--s-cover));
  pointer-events: none;
  touch-action: manipulation;
  isolation: isolate;
}

.lyric-floating-controls::before,
.lyric-floating-controls::after {
  position: absolute;
  z-index: 0;
  content: "";
  pointer-events: none;
}

.lyric-floating-controls::before {
  inset: 0;
  background: linear-gradient(180deg, transparent 0%, rgb(var(--s-cover) / 0.06) 100%);
  opacity: 0.45;
}

.lyric-floating-controls::after {
  right: 0;
  bottom: 0;
  left: 0;
  height: 112px;
  background: none;
  opacity: 0;
}

.lyric-floating-controls > * {
  position: relative;
  z-index: 1;
  opacity: 1;
  filter: none;
  pointer-events: auto;
}

.lyric-controls-wake-shield {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 17;
  height: calc(
    clamp(112px, 16vh, 152px) +
      var(--mobile-safe-bottom, var(--android-fullscreen-safe-bottom, 0px))
  );
  pointer-events: auto;
  touch-action: manipulation;
}

.lyric-floating-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  width: 100%;
  pointer-events: none;
}

.lyric-floating-actions > * {
  pointer-events: auto;
}

.lyric-floating-controls.is-wake-blocking > * {
  pointer-events: none;
}

.lyric-floating-controls.is-transition-locking > * {
  pointer-events: none;
}

.lyric-floating-controls.is-transition-locking .lyric-page-toggle {
  pointer-events: auto;
}

.lyric-controls-enter-active {
  transition: opacity 430ms cubic-bezier(0.16, 1, 0.3, 1);
}

.lyric-controls-leave-active {
  transition: opacity 280ms cubic-bezier(0.4, 0, 1, 1);
}

.lyric-controls-enter-active::before,
.lyric-controls-enter-active::after {
  transition: opacity 520ms cubic-bezier(0.16, 1, 0.3, 1);
}

.lyric-controls-leave-active::before,
.lyric-controls-leave-active::after {
  transition: opacity 320ms cubic-bezier(0.4, 0, 1, 1);
}

.lyric-controls-enter-active .lyric-floating-progress {
  transition:
    opacity 320ms cubic-bezier(0.16, 1, 0.3, 1) 60ms,
    transform 320ms cubic-bezier(0.16, 1, 0.3, 1) 60ms;
}

.lyric-controls-enter-active .lyric-floating-buttons .s-button {
  transition:
    opacity 380ms cubic-bezier(0.16, 1, 0.3, 1),
    transform 380ms cubic-bezier(0.16, 1, 0.3, 1);
}

.lyric-controls-enter-active .lyric-floating-buttons .s-button:nth-child(1) {
  transition-delay: 120ms;
}

.lyric-controls-enter-active .lyric-floating-buttons .s-button:nth-child(2) {
  transition-delay: 180ms;
}

.lyric-controls-enter-active .lyric-floating-buttons .s-button:nth-child(3) {
  transition-delay: 240ms;
}

.lyric-controls-leave-active .lyric-floating-progress {
  transition:
    opacity 180ms cubic-bezier(0.4, 0, 1, 1),
    transform 180ms cubic-bezier(0.4, 0, 1, 1);
}

.lyric-controls-leave-active .lyric-floating-buttons .s-button {
  transition:
    opacity 160ms cubic-bezier(0.4, 0, 1, 1),
    transform 160ms cubic-bezier(0.4, 0, 1, 1);
}

.lyric-controls-enter-active,
.lyric-controls-leave-active,
.lyric-controls-enter-active::before,
.lyric-controls-leave-active::before,
.lyric-controls-enter-active::after,
.lyric-controls-leave-active::after,
.lyric-controls-enter-active .lyric-floating-progress,
.lyric-controls-leave-active .lyric-floating-progress,
.lyric-controls-enter-active .lyric-floating-buttons .s-button,
.lyric-controls-leave-active .lyric-floating-buttons .s-button {
  will-change: opacity, transform;
}

.lyric-controls-enter-from,
.lyric-controls-leave-to {
  opacity: 0;
}

.lyric-controls-enter-from::before,
.lyric-controls-leave-to::before,
.lyric-controls-enter-from::after,
.lyric-controls-leave-to::after {
  opacity: 0;
}

.lyric-controls-enter-from .lyric-floating-progress,
.lyric-controls-leave-to .lyric-floating-progress {
  opacity: 0;
  transform: translate3d(0, 10px, 0);
}

.lyric-controls-enter-from .lyric-floating-buttons .s-button,
.lyric-controls-leave-to .lyric-floating-buttons .s-button {
  opacity: 0;
  transform: translate3d(0, 14px, 0) scale(0.9);
}

/* 退出歌词页时如果底栏从隐藏变为显示，禁用位移滑入动画，仅保留透明度淡入，消除突兀的“顿挫感” */
.full-player-mobile[data-hero-close="true"] .lyric-controls-enter-active,
.full-player-mobile[data-hero-close="true"] .lyric-controls-enter-active::before,
.full-player-mobile[data-hero-close="true"] .lyric-controls-enter-active::after,
.full-player-mobile[data-hero-close="true"] .lyric-controls-enter-active .lyric-floating-progress,
.full-player-mobile[data-hero-close="true"]
  .lyric-controls-enter-active
  .lyric-floating-buttons
  .s-button {
  transition: opacity 320ms ease !important;
  transform: none !important;
}

.full-player-mobile[data-hero-close="true"] .lyric-controls-enter-from .lyric-floating-progress,
.full-player-mobile[data-hero-close="true"]
  .lyric-controls-enter-from
  .lyric-floating-buttons
  .s-button {
  transform: none !important;
}

.lyric-floating-progress {
  display: flex;
  flex-direction: column;
  gap: 8px;
  pointer-events: auto;
  touch-action: pan-x;
}

.lyric-floating-time-row {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 12px;
  color: rgb(var(--s-cover) / 0.58);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.lyric-floating-time-row > span:last-child {
  text-align: right;
}

.lyric-floating-quality {
  display: inline-flex;
  min-width: 72px;
  align-items: center;
  justify-content: center;
  color: rgb(var(--s-cover) / 0.78);
  font-weight: 600;
  letter-spacing: 0.01em;
}

.lyric-floating-buttons {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: clamp(30px, 12vw, 56px);
  pointer-events: auto;
  touch-action: manipulation;
}

.lyric-floating-back {
  align-self: center;
  margin-top: -4px;
  flex-shrink: 0;
  min-width: 86px;
}

.mobile-queue-trigger {
  position: absolute;
  right: 36px;
  bottom: calc(16px + var(--mobile-safe-bottom, var(--android-fullscreen-safe-bottom, 0px)));
  z-index: 2;
  width: 42px;
  height: 42px;
  border: 1px solid rgb(var(--s-cover) / 0.18);
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: rgb(var(--s-cover));
  background: rgb(var(--s-cover) / 0.08);
  backdrop-filter: blur(18px);
  opacity: 0.88;
  transition:
    opacity 180ms ease,
    background-color 180ms ease,
    border-color 180ms ease;
}

.mobile-queue-trigger.open,
.mobile-queue-trigger:active {
  border-color: rgb(var(--s-cover) / 0.34);
  background: rgb(var(--s-cover) / 0.16);
  opacity: 1;
}

.mobile-queue-panel {
  position: absolute;
  inset: calc(56px + var(--mobile-safe-top, var(--safe-area-top, 0px))) 28px
    calc(104px + var(--mobile-safe-bottom, var(--android-fullscreen-safe-bottom, 0px)));
  z-index: 32;
  overflow: hidden;
  color: rgb(var(--s-cover));
  pointer-events: auto;
  border: 1px solid rgb(var(--s-cover) / 0.1);
  border-radius: 30px;
  background:
    linear-gradient(180deg, rgb(var(--s-cover) / 0.08), transparent 34%),
    rgb(var(--s-surface) / 0.48);
  box-shadow:
    0 22px 60px rgb(0 0 0 / 0.22),
    inset 0 1px 0 rgb(var(--s-cover) / 0.08);
  backdrop-filter: blur(30px) saturate(1.25);
}

.mobile-queue-panel :deep(.queue-panel) {
  padding: 12px 6px 8px;
}

.mobile-queue-panel :deep(.queue-panel-header) {
  align-items: center;
  gap: 10px;
  padding: 0 6px 10px 10px;
}

.mobile-queue-panel :deep(.queue-panel-heading) {
  font-size: 22px;
  line-height: 1.05;
  margin: 0;
}

.mobile-queue-panel :deep(.queue-panel-actions) {
  gap: 8px;
}

.mobile-queue-panel :deep(.queue-panel-header .s-button) {
  width: 38px;
  height: 38px;
}

.mobile-queue-panel :deep(.sv-virtual-list) {
  margin-inline: 0;
  padding-inline: 6px;
}

.mobile-queue-panel :deep(.queue-panel-item-shell) {
  padding: 4px 6px;
}

.mobile-queue-panel :deep(.s-button) {
  z-index: 8;
  pointer-events: auto;
}

.lyric-floating-buttons .s-button {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  background-color: transparent;
  box-shadow: none;
  transform: scale(1);
}

.lyric-floating-buttons .s-button > * {
  position: relative;
  z-index: 1;
}

.lyric-floating-buttons .s-button::before {
  position: absolute;
  inset: 0;
  z-index: 0;
  content: "";
  border-radius: inherit;
  background:
    radial-gradient(circle at 50% 35%, rgb(var(--s-cover) / 0.16), transparent 68%),
    rgb(var(--s-cover) / 0.08);
  box-shadow:
    0 10px 26px rgb(0 0 0 / 0.14),
    inset 0 1px 0 rgb(var(--s-cover) / 0.12);
  opacity: 0;
  transition:
    opacity 200ms ease,
    background-color 200ms ease;
}

.lyric-floating-buttons .s-button:nth-child(2) {
  background-color: transparent;
}

.lyric-floating-buttons .s-button:nth-child(2)::before {
  background:
    radial-gradient(circle at 50% 35%, rgb(var(--s-cover) / 0.22), transparent 70%),
    rgb(var(--s-cover) / 0.12);
  box-shadow:
    0 12px 34px rgb(0 0 0 / 0.18),
    0 0 28px rgb(var(--s-cover) / 0.12),
    inset 0 1px 0 rgb(var(--s-cover) / 0.16);
}

.lyric-floating-buttons .s-button:active::before {
  opacity: 1;
  background:
    radial-gradient(circle at 50% 35%, rgb(var(--s-cover) / 0.24), transparent 68%),
    rgb(var(--s-cover) / 0.16);
}

.lyric-entry-wrap {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 15;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  height: calc(
    clamp(200px, 22vh, 230px) +
      var(--mobile-safe-bottom, var(--android-fullscreen-safe-bottom, 0px))
  );
  padding-bottom: calc(
    24px + var(--mobile-safe-bottom, var(--android-fullscreen-safe-bottom, 0px))
  );
  pointer-events: auto;
  touch-action: manipulation;
}

.lyric-entry-bottom {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 34px;
  padding: 0 14px;
  border: 1px solid rgb(var(--s-cover) / 0.18);
  border-radius: 999px;
  background: rgb(var(--s-cover) / 0.08);
  color: rgb(var(--s-cover) / 0.72);
  font-size: 13px;
  font-weight: 600;
  line-height: 1;
  transition:
    transform 180ms ease,
    color 180ms ease,
    background-color 180ms ease;
  touch-action: manipulation;
}

.lyric-entry-bottom:active {
  transform: scale(0.94);
  background: rgb(var(--s-cover) / 0.14);
  color: rgb(var(--s-cover) / 0.9);
}

.pad-portrait .cover-region {
  margin-top: calc(72px + var(--mobile-safe-top, var(--safe-area-top, 0px)));
  margin-bottom: 24px;
  min-height: clamp(300px, calc(var(--page-zoom-100vh, 100vh) * 0.44), 520px);
}

.pad-portrait .cover-shell {
  width: clamp(320px, 72vw, 520px);
}

.pad-portrait .info-page {
  padding-right: clamp(32px, 6vw, 56px);
  padding-left: clamp(32px, 6vw, 56px);
  padding-bottom: calc(
    32px + var(--mobile-safe-bottom, var(--android-fullscreen-safe-bottom, 0px))
  );
}

.pad-portrait .lyric-page {
  padding-top: calc(72px + var(--mobile-safe-top, var(--safe-area-top, 0px)));
  padding-bottom: calc(
    32px + var(--mobile-safe-bottom, var(--android-fullscreen-safe-bottom, 0px))
  );
}

.pad-portrait .lyric-body {
  margin-inline: var(--landscape-lyric-padding-x, 0px);
}

.pad-portrait .mobile-top-bar {
  height: calc(72px + var(--mobile-safe-top, var(--safe-area-top, 0px)));
  padding: var(--mobile-safe-top, var(--safe-area-top, 0px)) 32px 0;
}

/* ── 手机横屏：左右分栏布局 ── */
/* 顶栏对齐参考项目 PlayerMenu landscape：min-height 56px + padding 0 12px */
.phone-landscape .mobile-top-bar {
  height: calc(56px + var(--mobile-safe-top, var(--safe-area-top, 0px)));
  padding: var(--mobile-safe-top, var(--safe-area-top, 0px)) 20px 0;
  justify-content: space-between;
}

/* 内容区不画背景，由父级 PlayerBackground 提供（对齐参考项目 FullPlayerMobileLandscape 透明） */
.phone-landscape {
  color: rgb(var(--s-cover));
}

.landscape-layout {
  position: absolute;
  z-index: 1;
  top: calc(56px + var(--mobile-safe-top, var(--safe-area-top, 0px)));
  left: 0;
  right: 0;
  display: flex;
  flex-direction: row;
  width: auto;
  height: calc(
    100% - 56px - var(--mobile-safe-top, var(--safe-area-top, 0px)) - 45px -
      var(--mobile-safe-bottom, var(--android-fullscreen-safe-bottom, 0px))
  );
  align-items: center;
}

.landscape-left {
  width: 38%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 8px 16px 6px;
  gap: 12px;
  transform: translateX(var(--landscape-cover-offset-x, 52px));
}

.landscape-cover {
  width: 70%;
  max-width: 220px;
  aspect-ratio: 1 / 1;
  height: auto;
  flex-shrink: 0;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 12px 28px rgba(0, 0, 0, 0.28);
  background-color: rgba(255, 255, 255, 0.06);
}

/* 重置 PlayerCover 内部圆角与阴影，由外层 .landscape-cover 统一提供 */
.landscape-cover :deep(> div) {
  border-radius: 0 !important;
  box-shadow: none !important;
}

.landscape-info {
  width: 100%;
  max-width: 260px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
}

.landscape-name {
  font-size: 18px;
  font-weight: 600;
  line-height: 1.25;
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.landscape-ar-line,
.landscape-album-line {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  opacity: 0.75;
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.landscape-ar-line .ar,
.landscape-album-line .album {
  overflow: hidden;
  text-overflow: ellipsis;
}

.landscape-album-line {
  font-size: 13px;
  opacity: 0.6;
}

.landscape-right {
  position: relative;
  flex: 1;
  height: 100%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  overflow: hidden;
  mix-blend-mode: var(--lyric-blend-mode, normal);
}

.landscape-right.is-queue-open .landscape-lyric-body {
  opacity: 0;
  pointer-events: none;
}

/* 正值收紧歌词内容，负值通过外边距向两侧扩展。 */
.landscape-right :deep(.am-lyric),
.landscape-right :deep(.amll-lyric-player),
.landscape-right :deep(.default-lyric),
.landscape-right :deep(.lyric-scroll-container) {
  box-sizing: border-box !important;
  padding-left: max(0px, var(--landscape-lyric-padding-x, 0px)) !important;
  padding-right: max(0px, var(--landscape-lyric-padding-x, 0px)) !important;
  margin-left: min(0px, var(--landscape-lyric-padding-x, 0px)) !important;
  margin-right: min(0px, var(--landscape-lyric-padding-x, 0px)) !important;
}

/* 横屏歌词顶部占位压缩为 80px，避免首行被顶栏遮挡 */
.landscape-right :deep(.lyric-scroll-container) .placeholder:first-child {
  height: 80px !important;
}

/* 横屏隐藏歌词浮动菜单 */
.landscape-right :deep(.lyric-menu) {
  display: none !important;
}

.landscape-lyric-body {
  position: relative;
  min-height: 0;
  flex: 1;
  opacity: 1;
  transition: opacity 240ms ease;
  --lp-padding-x: 0.42em;
  --lp-line-padding: 0.45em 0.42em;
  font-synthesis: weight style;
  -webkit-font-synthesis: weight style;
  mask-image: linear-gradient(
    180deg,
    transparent 0%,
    rgb(0 0 0) 10%,
    rgb(0 0 0) 90%,
    transparent 100%
  );
}

/* 横屏底栏：对齐参考项目 PlayerControl landscape 三列 grid */
.landscape-controls {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: calc(45px + var(--mobile-safe-bottom, var(--android-fullscreen-safe-bottom, 0px)));
  padding: 0 30px var(--mobile-safe-bottom, var(--android-fullscreen-safe-bottom, 0px));
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  align-items: center;
  gap: 0;
  z-index: 10;
}

.landscape-control-left,
.landscape-control-right {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 100%;
  padding-top: 6px;
  min-width: 0;
}

.landscape-control-left {
  justify-content: flex-start;
}

.landscape-control-right {
  justify-content: flex-end;
}

.landscape-control-right .menu-icon.open {
  background-color: rgb(var(--s-cover) / 0.16);
  opacity: 1;
}

.landscape-control-center {
  max-height: 45px;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0;
}

.landscape-progress {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  max-width: 480px;
  height: 18px;
  font-size: 12px;
}

.landscape-time-text {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  opacity: 0.78;
  white-space: nowrap;
}

.landscape-buttons {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0;
  height: 38px;
}

/* menu-icon：40x40 方形圆角，对齐参考项目 PlayerMenu/PlayerControl 的 .menu-icon */
.menu-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 8px;
  transition:
    opacity 0.3s,
    background-color 0.3s,
    transform 0.3s;
  cursor: pointer;
  color: rgb(var(--s-cover));
  pointer-events: auto;
}

.menu-icon:hover {
  transform: scale(1.05);
  background-color: rgb(var(--s-cover) / 0.14);
}

.menu-icon:active {
  transform: scale(1);
}

.menu-icon.disabled {
  opacity: 0.4;
  pointer-events: none;
}

/* 顶栏左侧 menu-icon 默认半透明，open 状态全亮（对齐参考项目 PlayerMenu .left .menu-icon） */
.top-bar-left .menu-icon {
  opacity: 0.6;
}

.top-bar-left .menu-icon.open {
  opacity: 1;
}

.top-bar-left .menu-icon:hover {
  opacity: 1;
}

/* btn-icon：38x38 圆形，对齐参考项目 PlayerControl .btn-icon */
.btn-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  margin: 0 4px;
  transition:
    background-color 0.3s,
    transform 0.3s;
  cursor: pointer;
  color: rgb(var(--s-cover));
}

.btn-icon:hover {
  transform: scale(1.1);
  background-color: rgb(var(--s-cover) / 0.14);
}

.btn-icon:active {
  transform: scale(1);
}

.btn-icon.disabled {
  opacity: 0.4;
  pointer-events: none;
}

/* play-pause：44x44 圆形 + cover 色背景，对齐参考项目 n-button.play-pause */
.btn-icon.play-pause {
  width: 44px;
  height: 44px;
  margin: 0 12px;
  background-color: rgb(var(--s-cover) / 0.14);
}

.btn-icon.play-pause:hover {
  transform: scale(1.1);
  background-color: rgb(var(--s-cover) / 0.2);
}

.btn-icon.play-pause.loading {
  opacity: 0.6;
  pointer-events: none;
}

.landscape-quality-pill {
  min-width: 42px;
  height: 28px;
  padding: 0 10px;
  border: 1px solid rgb(var(--s-cover) / 0.36);
  border-radius: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  color: rgb(var(--s-cover));
  background: rgb(var(--s-cover) / 0.08);
}

.landscape-volume-panel {
  width: 48px;
  padding: 12px 10px 10px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: rgb(var(--s-cover));
}

.landscape-volume-slider {
  height: 120px;
}

.landscape-volume-value {
  border: 0;
  background: transparent;
  color: currentColor;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  opacity: 0.78;
  cursor: pointer;
}

.landscape-queue-trigger {
  position: relative;
}

.landscape-queue-badge {
  position: absolute;
  top: 2px;
  right: -8px;
  min-width: 24px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  color: rgb(var(--s-cover));
  background: rgb(var(--s-cover) / 0.2);
  backdrop-filter: blur(18px);
}

.landscape-queue-panel {
  position: absolute;
  inset: 0 max(20px, var(--landscape-lyric-padding-x, 0px));
  z-index: 4;
  overflow: hidden;
  color: rgb(var(--s-cover));
  pointer-events: auto;
}

/*
 * QueuePanel 内的 SButton 在 .landscape-right 这个 mix-blend-mode stacking context 内，
 * 理论上 z-4 已经足够在 .landscape-lyric-body 之上，但 Android WebView 上
 * 仍偶发收到 transparent 误判导致不响应 click。强制提升到 20 并明确 pointer-events。
 */
.landscape-queue-panel :deep(.s-button) {
  position: relative;
  z-index: 20;
  pointer-events: auto;
}

.landscape-queue-enter-active,
.landscape-queue-leave-active {
  transition:
    opacity 240ms ease,
    transform 240ms cubic-bezier(0.22, 1, 0.36, 1);
}

.landscape-queue-enter-from,
.landscape-queue-leave-to {
  opacity: 0;
  transform: translateX(18px) scale(0.98);
}

/* 顶栏左/右容器：恢复 pointer-events，避免被父级 pointer-events none 截断 */
.top-bar-left {
  display: flex;
  align-items: center;
  gap: 8px;
  pointer-events: auto;
}

.top-bar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  pointer-events: auto;
}

/* 横屏底栏左列：让 QuickActionsMenu 的 trigger 对齐 menu-icon 40x40 方形圆角 */
.landscape-control-left :deep(.s-button) {
  width: 40px !important;
  height: 40px !important;
  border-radius: 8px !important;
}

/* QualityControl 沿用 .landscape-quality-pill 的视觉尺寸：28px 高度、圆角 9px */
.landscape-control-right :deep(.cursor-pointer) {
  min-width: 42px;
  height: 28px;
  padding: 0 10px;
  border-radius: 9px;
  font-size: 13px;
  font-weight: 600;
}

/* ── 横屏切换 Stagger 错开动画 ── */
@keyframes stagger-reveal-cover {
  from {
    opacity: 0;
    transform: scale(0.96);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}

@keyframes stagger-reveal-title {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes stagger-reveal-lyric {
  from {
    opacity: 0;
    transform: translateX(20px);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
}

@keyframes stagger-reveal-control {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes stagger-leave-cover {
  from {
    opacity: 1;
    transform: scale(1);
  }
  to {
    opacity: 0;
    transform: scale(0.96);
  }
}

@keyframes stagger-leave-title {
  from {
    opacity: 1;
    transform: translateY(0);
  }
  to {
    opacity: 0;
    transform: translateY(-8px);
  }
}

@keyframes stagger-leave-lyric {
  from {
    opacity: 1;
    transform: translateX(0);
  }
  to {
    opacity: 0;
    transform: translateX(-20px);
  }
}

@keyframes stagger-leave-control {
  from {
    opacity: 1;
    transform: translateY(0);
  }
  to {
    opacity: 0;
    transform: translateY(12px);
  }
}

/* 入场揭幕：cover 即刻，title 延迟 80ms，lyric 延迟 120ms，control 延迟 160ms */
[data-orientation-phase="enter-revealing"] [data-stagger="cover"] {
  animation: stagger-reveal-cover 200ms cubic-bezier(0.22, 1, 0.36, 1) 0ms forwards;
}
[data-orientation-phase="enter-revealing"] [data-stagger="title"] {
  animation: stagger-reveal-title 220ms cubic-bezier(0.22, 1, 0.36, 1) 80ms forwards;
}
[data-orientation-phase="enter-revealing"] [data-stagger="lyric"] {
  animation: stagger-reveal-lyric 260ms cubic-bezier(0.22, 1, 0.36, 1) 120ms forwards;
}
[data-orientation-phase="enter-revealing"] [data-stagger="control"] {
  animation: stagger-reveal-control 220ms cubic-bezier(0.22, 1, 0.36, 1) 160ms forwards;
}

/* 出场收起：cover 即刻，title 延迟 20ms，lyric 延迟 40ms，control 延迟 60ms */
[data-orientation-phase="exit-collapsing"] [data-stagger="cover"] {
  animation: stagger-leave-cover 180ms cubic-bezier(0.22, 1, 0.36, 1) 0ms forwards;
}
[data-orientation-phase="exit-collapsing"] [data-stagger="title"] {
  animation: stagger-leave-title 180ms cubic-bezier(0.22, 1, 0.36, 1) 20ms forwards;
}
[data-orientation-phase="exit-collapsing"] [data-stagger="lyric"] {
  animation: stagger-leave-lyric 200ms cubic-bezier(0.22, 1, 0.36, 1) 40ms forwards;
}
[data-orientation-phase="exit-collapsing"] [data-stagger="control"] {
  animation: stagger-leave-control 180ms cubic-bezier(0.22, 1, 0.36, 1) 60ms forwards;
}

@media (prefers-reduced-motion: reduce) {
  [data-orientation-phase] [data-stagger] {
    animation: none !important;
  }
}
</style>
