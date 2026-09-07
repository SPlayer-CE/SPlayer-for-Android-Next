import { isAndroid, isAndroidPreview } from "@/services/bridge";

export type ResponsiveDeviceMode = "desktop" | "tablet" | "phone";
export type ResponsiveLayoutMode = "pc" | "mobile";
export type DeviceModeOverride = "auto" | "phone" | "pad";

/** Android 平板按 Material sw600dp 口径识别 */
export const TABLET_MIN_SHORT_EDGE = 600;
/** SSR / 非 Android 初始视口宽度回退值 */
export const DESKTOP_MIN_WIDTH = 900;
/** 视口短边占屏幕短边比例低于此值视为小窗模式（分屏 / 自由窗口 / PiP） */
export const SMALL_WINDOW_RATIO = 0.9;

/**
 * 模块级设备形态覆盖 ref，供 App.vue 从 settings store 桥接写入。
 * "auto" 跟随自动识别，"phone" 强制手机 UI，"pad" 强制平板 UI。
 */
export const deviceModeOverride = ref<DeviceModeOverride>("auto");

const readViewportSize = (): { width: number; height: number } => {
  if (typeof window === "undefined") {
    return { width: DESKTOP_MIN_WIDTH, height: TABLET_MIN_SHORT_EDGE };
  }
  if (isAndroid) {
    return {
      width: Math.round(window.innerWidth),
      height: Math.round(window.innerHeight),
    };
  }
  const viewport = window.visualViewport;
  return {
    width: Math.round(viewport?.width ?? window.innerWidth),
    height: Math.round(viewport?.height ?? window.innerHeight),
  };
};

const readScreenSize = (): { width: number; height: number } => {
  if (typeof window === "undefined") {
    return { width: DESKTOP_MIN_WIDTH, height: TABLET_MIN_SHORT_EDGE };
  }
  return {
    width: Math.round(window.screen?.width ?? window.innerWidth),
    height: Math.round(window.screen?.height ?? window.innerHeight),
  };
};

const initialViewportSize = readViewportSize();
const initialScreenSize = readScreenSize();
const viewportWidth = ref(initialViewportSize.width);
const viewportHeight = ref(initialViewportSize.height);
const screenWidth = ref(initialScreenSize.width);
const screenHeight = ref(initialScreenSize.height);
let consumers = 0;

const userAgent = typeof navigator === "undefined" ? "" : navigator.userAgent;
const uaLooksTablet =
  /pad|tablet/i.test(userAgent) || (/android/i.test(userAgent) && !/mobile/i.test(userAgent));

const updateViewportSize = (): void => {
  const viewport = readViewportSize();
  const screen = readScreenSize();
  viewportWidth.value = viewport.width;
  viewportHeight.value = viewport.height;
  screenWidth.value = screen.width;
  screenHeight.value = screen.height;
};

const addViewportListeners = (): void => {
  if (typeof window === "undefined") return;
  updateViewportSize();
  window.addEventListener("resize", updateViewportSize, { passive: true });
  window.addEventListener("orientationchange", updateViewportSize, { passive: true });
  window.visualViewport?.addEventListener("resize", updateViewportSize, { passive: true });
  window.screen.orientation?.addEventListener("change", updateViewportSize);
};

const removeViewportListeners = (): void => {
  if (typeof window === "undefined") return;
  window.removeEventListener("resize", updateViewportSize);
  window.removeEventListener("orientationchange", updateViewportSize);
  window.visualViewport?.removeEventListener("resize", updateViewportSize);
  window.screen.orientation?.removeEventListener("change", updateViewportSize);
};

/** 统一的设备 / 布局模式判断，避免在组件里散落 Android + 宽度判断 */
export const useResponsiveLayout = () => {
  const shortEdge = computed(() => Math.min(viewportWidth.value, viewportHeight.value));
  const screenShortEdge = computed(() => Math.min(screenWidth.value, screenHeight.value));
  const deviceShortEdge = computed(() => Math.max(shortEdge.value, screenShortEdge.value));
  const isLandscape = computed(() => viewportWidth.value > viewportHeight.value);
  const isPortrait = computed(() => !isLandscape.value);
  const isAndroidTablet = computed(() => {
    if (!isAndroid) return false;
    if (deviceModeOverride.value === "phone") return false;
    if (deviceModeOverride.value === "pad") return true;
    if (isAndroidPreview) return shortEdge.value >= TABLET_MIN_SHORT_EDGE;
    return uaLooksTablet || deviceShortEdge.value >= TABLET_MIN_SHORT_EDGE;
  });
  const isAndroidPhone = computed(() => isAndroid && !isAndroidTablet.value);
  const isPadLayout = computed(() => isAndroidTablet.value && isLandscape.value);
  const isPhoneLayout = computed(() => isAndroid && !isPadLayout.value);
  const isPhonePortrait = computed(() => isPhoneLayout.value && isPortrait.value);
  const isPhoneLandscape = computed(() => isPhoneLayout.value && isLandscape.value);
  const isCompactMobilePlayer = computed(() => isPhonePortrait.value);
  const usePcLayout = computed(() => !isAndroid || isPadLayout.value);
  const useMobileLayout = computed(() => isAndroid && !usePcLayout.value);
  /** 小窗模式：视口短边明显小于屏幕短边，说明处于分屏 / 自由窗口 / PiP 状态 */
  const isSmallWindow = computed(() => {
    if (!isAndroid) return false;
    const vpShort = Math.min(viewportWidth.value, viewportHeight.value);
    const scrShort = Math.min(screenWidth.value, screenHeight.value);
    return scrShort > 0 && vpShort / scrShort < SMALL_WINDOW_RATIO;
  });
  const deviceMode = computed<ResponsiveDeviceMode>(() => {
    if (!isAndroid) return "desktop";
    return isAndroidTablet.value ? "tablet" : "phone";
  });
  const layoutMode = computed<ResponsiveLayoutMode>(() => (usePcLayout.value ? "pc" : "mobile"));

  onMounted(() => {
    consumers += 1;
    if (consumers === 1) addViewportListeners();
    else updateViewportSize();
  });

  onBeforeUnmount(() => {
    consumers = Math.max(0, consumers - 1);
    if (consumers === 0) removeViewportListeners();
  });

  return {
    width: viewportWidth,
    height: viewportHeight,
    shortEdge,
    screenShortEdge,
    deviceShortEdge,
    isLandscape,
    isPortrait,
    isAndroidTablet,
    isAndroidPhone,
    isPadLayout,
    isPhoneLayout,
    isPhonePortrait,
    isPhoneLandscape,
    isCompactMobilePlayer,
    usePcLayout,
    useMobileLayout,
    isSmallWindow,
    deviceMode,
    layoutMode,
  };
};
