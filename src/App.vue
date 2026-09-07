<script setup lang="ts">
import { isAndroid } from "@/services/bridge";
import { useAndroidBack } from "@/composables/useAndroidBack";
import { useImmersive } from "@/composables/useImmersive";
import { useResponsiveLayout, deviceModeOverride } from "@/composables/useResponsiveLayout";
import { useSettingsStore } from "@/stores/settings";
import { initLanSyncHost } from "@/composables/useLanSyncHost";
import { usePageZoom } from "@/composables/usePageZoom";

const { deviceMode, layoutMode, isSmallWindow } = useResponsiveLayout();
const settingsStore = useSettingsStore();

// Android CSS 页面缩放
usePageZoom();

// 设备形态覆盖：从 settings store 同步到 useResponsiveLayout 模块级 ref
watch(
  () => settingsStore.appearance.androidDeviceModeOverride,
  (mode) => {
    deviceModeOverride.value = mode ?? "auto";
  },
  { immediate: true },
);

// 启动局域网分享主机服务（如果已开启）
initLanSyncHost();

watchEffect(() => {
  const v = settingsStore.appearance.fontFamily;
  const root = document.documentElement.style;
  if (v) root.setProperty("--user-font", `${v}, var(--app-font)`);
  else root.removeProperty("--user-font");
});

watchEffect(() => {
  const root = document.documentElement.dataset;
  root.device = deviceMode.value;
  root.layout = layoutMode.value;
});

// Android 平台标记（用于 CSS 条件化）
if (isAndroid) {
  document.documentElement.dataset.platform = "android";
  // 初始窗口模式标记，避免 watchEffect 首次执行前的样式闪烁
  document.documentElement.dataset.windowMode = isSmallWindow.value ? "small" : "full";
}

/** Android 窗口模式标记：小窗 / 全屏，用于切换 CSS 容器策略 */
watchEffect(() => {
  if (!isAndroid) return;
  document.documentElement.dataset.windowMode = isSmallWindow.value ? "small" : "full";
});

/**
 * Android 全屏模式键盘防偏移：
 * adjustNothing 在部分 ROM / WebView 版本下仍会让页面被输入法顶起，
 * 这里通过监听 visualViewport 变化强制回零，并让底部悬浮层抵消 IME 对 fixed bottom 的顶起。
 */
if (isAndroid) {
  let stableLayoutHeight = window.innerHeight;
  const updateAndroidKeyboardViewport = () => {
    const viewport = window.visualViewport;
    const activeElement = document.activeElement;
    const inputFocused =
      activeElement instanceof HTMLInputElement || activeElement instanceof HTMLTextAreaElement;
    const visualKeyboardOffset = viewport
      ? Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop)
      : 0;
    if (!inputFocused && visualKeyboardOffset <= 0) {
      stableLayoutHeight = window.innerHeight;
    }
    const keyboardOffset = Math.max(visualKeyboardOffset, stableLayoutHeight - window.innerHeight);
    const root = document.documentElement.style;
    root.setProperty("--android-keyboard-offset", `${keyboardOffset}px`);
    root.setProperty("--android-layout-viewport-height", `${stableLayoutHeight}px`);
  };
  updateAndroidKeyboardViewport();
  const resetScroll = () => {
    updateAndroidKeyboardViewport();
    if (isSmallWindow.value) return;
    window.scrollTo(0, 0);
    document.documentElement.scrollTop = 0;
    document.body.scrollTop = 0;
  };
  window.visualViewport?.addEventListener("resize", resetScroll, { passive: true });
  window.visualViewport?.addEventListener("scroll", resetScroll, { passive: true });
  window.addEventListener("resize", resetScroll, { passive: true });
}

// Android 返回键处理
useAndroidBack();

// Android 沉浸式模式：隐藏状态栏与底部导航栏
useImmersive();
</script>

<template>
  <AppBackground />
  <RouterView />
</template>
