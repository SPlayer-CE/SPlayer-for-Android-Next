import { computed, onBeforeUnmount, onMounted, watch } from "vue";
import { Capacitor } from "@capacitor/core";
import { StatusBar, Style } from "@capacitor/status-bar";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import { useSettingsStore } from "@/stores/settings";
import bridge, { isAndroid, isAndroidNative } from "@/services/bridge";

/**
 * Android 沉浸式模式：
 * - WebView 延伸到状态栏下方
 * - 启动后隐藏状态栏（除非用户选择显示）
 * - 隐藏底部导航栏（平板横竖屏 / 手机竖屏）
 *
 * 底部导航栏隐藏由原生 MainActivity.applyImmersiveMode() 通过
 * WindowInsetsControllerCompat.hide(navigationBars) 实现，
 * 前端通过 bridge.android.setHideNavigationBar 写入 SharedPreferences 驱动。
 */
export const useImmersive = () => {
  let reapplyTimer: number | undefined;
  const settings = useSettingsStore();
  const { isAndroidTablet, isAndroidPhone } = useResponsiveLayout();

  // 平板与手机横竖屏均隐藏底部导航栏，实现全场景沉浸
  const shouldHideNavigationBar = computed(() => {
    if (!isAndroid) return false;
    if (!settings.androidHidePortraitNavBar) return false;
    // 平板：横竖屏都隐藏
    if (isAndroidTablet.value) return true;
    // 手机：横竖屏都隐藏
    if (isAndroidPhone.value) return true;
    return false;
  });

  const applyImmersive = async () => {
    if (Capacitor.getPlatform() !== "android") return;

    document.documentElement.classList.add("android-capacitor");

    try {
      await StatusBar.setStyle({ style: Style.Light });
      await StatusBar.setOverlaysWebView({ overlay: true });
      // 状态栏显示由用户设置控制
      if (settings.androidShowStatusBar) {
        await StatusBar.show();
      } else {
        await StatusBar.hide();
      }
    } catch (error) {
      console.warn("[useImmersive] 进入沉浸式模式失败", error);
    }
  };

  const scheduleImmersive = (delay = 180) => {
    if (Capacitor.getPlatform() !== "android") return;

    window.clearTimeout(reapplyTimer);
    reapplyTimer = window.setTimeout(() => {
      void applyImmersive();
    }, delay);
  };

  const wantsStatusBar = () => settings.androidShowStatusBar;

  const handleFocus = () => {
    if (wantsStatusBar()) return;
    scheduleImmersive(120);
  };
  const handleResize = () => {
    if (wantsStatusBar()) return;
    scheduleImmersive(220);
  };
  const handleOrientationChange = () => {
    if (wantsStatusBar()) {
      void StatusBar.show().catch(() => {});
      return;
    }
    scheduleImmersive(260);
  };
  const handleVisibilityChange = () => {
    if (document.visibilityState === "visible") {
      if (wantsStatusBar()) {
        void StatusBar.show().catch(() => {});
        return;
      }
      scheduleImmersive();
    }
  };

  let initialized = false;

  /** 首次挂载时同步一次原生 SharedPreferences，避免 watch immediate 在 setup 阶段触发 Capacitor 插件调用 */
  const syncNativePrefs = () => {
    if (initialized || !isAndroidNative) return;
    initialized = true;
    void bridge.android.setHideNavigationBar(shouldHideNavigationBar.value);
    void bridge.android.setShowStatusBar(settings.androidShowStatusBar);
    if (settings.androidShowStatusBar) {
      void StatusBar.show().catch(() => {});
    } else {
      void StatusBar.hide().catch(() => {});
    }
  };

  onMounted(() => {
    if (!isAndroidNative) return;
    syncNativePrefs();
    void applyImmersive();
    window.addEventListener("focus", handleFocus);
    window.addEventListener("resize", handleResize);
    window.addEventListener("orientationchange", handleOrientationChange);
    document.addEventListener("visibilitychange", handleVisibilityChange);
  });

  onBeforeUnmount(() => {
    window.clearTimeout(reapplyTimer);
    window.removeEventListener("focus", handleFocus);
    window.removeEventListener("resize", handleResize);
    window.removeEventListener("orientationchange", handleOrientationChange);
    document.removeEventListener("visibilitychange", handleVisibilityChange);
  });

  // 监听 shouldHideNavigationBar 变化，写入原生 SharedPreferences
  // 跳过 immediate：首次同步在 onMounted 的 syncNativePrefs 中完成，避免 setup 阶段触发 Capacitor 调用
  watch(shouldHideNavigationBar, (hide) => {
    if (!isAndroidNative || !initialized) return;
    void bridge.android.setHideNavigationBar(hide);
  });

  // 监听状态栏显示偏好变化
  watch(
    () => settings.androidShowStatusBar,
    (show) => {
      if (!isAndroidNative || !initialized) return;
      void bridge.android.setShowStatusBar(show);
      if (show) {
        void StatusBar.show().catch(() => {});
      } else {
        void StatusBar.hide().catch(() => {});
      }
    },
  );

  return {
    applyImmersive,
    shouldHideNavigationBar,
  };
};
