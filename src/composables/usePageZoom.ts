import { isAndroid } from "@/services/bridge";
import { useSettingsStore } from "@/stores/settings";

/**
 * Android CSS 页面缩放：通过 transform: scale() 缩放 #app，
 * 同步更新 --page-zoom-* 系列 CSS 变量供子组件使用。
 * Electron 端走 webContents.setZoomFactor，不走此 composable。
 */
export const usePageZoom = (): void => {
  if (!isAndroid) return;

  const store = useSettingsStore();

  const apply = (percent: number): void => {
    const ratio = Math.max(0.5, Math.min(2, percent / 100));
    const app = document.getElementById("app");
    if (!app) return;

    if (ratio === 1) {
      app.style.position = "";
      app.style.inset = "";
      app.style.transform = "";
      app.style.width = "";
      app.style.height = "";
      app.style.transformOrigin = "";
    } else {
      app.style.position = "fixed";
      app.style.inset = "0";
      app.style.transformOrigin = "0 0";
      app.style.transform = `scale(${ratio})`;
      app.style.width = `${100 / ratio}vw`;
      app.style.height = `${100 / ratio}vh`;
    }

    // 补偿后的视口变量，供子组件引用
    const root = document.documentElement.style;
    root.setProperty("--page-zoom-ratio", String(ratio));
    root.setProperty("--page-zoom-100vw", `${100 / ratio}vw`);
    root.setProperty("--page-zoom-100vh", `${100 / ratio}vh`);
    root.setProperty("--page-zoom-100dvh", `calc(var(--page-zoom-viewport-height) / ${ratio})`);

    // 触发 resize 让依赖视口的组件刷新
    requestAnimationFrame(() => {
      window.dispatchEvent(new Event("resize"));
    });
  };

  watch(
    () => store.appearance.pageZoom,
    (val) => apply(val),
    { immediate: true },
  );
};
