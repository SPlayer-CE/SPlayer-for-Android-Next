// 过滤 Capacitor 桥接层高频日志（必须在所有其他 import 之前执行，避免 Capacitor 运行时早于 patch 输出）
import { installBridgeLogFilter } from "@/utils/bridgeLogFilter";
installBridgeLogFilter();

import "virtual:uno.css";
import "@/styles/global.css";

import piniaPersistedstate from "pinia-plugin-persistedstate";
import App from "./App.vue";
import router from "./router";
import i18n from "./i18n";

import { useThemeStore } from "./stores/theme";
import { useSettingsStore } from "./stores/settings";
import { useHotkeyStore } from "./stores/hotkey";
import { useUserStore } from "./stores/user";
import { initPlayer, playFiles, restoreLastTrack } from "./core/player";
import { handleOrpheus } from "./services/orpheus";
import { installHotkeyManager } from "./core/hotkey/manager";
import { vRipple } from "./directives/ripple";
import {
  setApiPort,
  setAndroidEmbeddedApiAvailable,
  setEmbeddedApiReadyPromise,
  isAndroid,
  isAndroidNative,
} from "./services/bridge";
import bridge from "./services/bridge";
import { EMBEDDED_API_PORT, waitForEmbeddedApiReady } from "./utils/embeddedApi";

const pinia = createPinia();
pinia.use(piniaPersistedstate);

const app = createApp(App);
app.directive("ripple", vRipple);
app.use(pinia);
app.use(router);
app.use(i18n);
const embeddedApiReady = isAndroidNative ? waitForEmbeddedApiReady() : Promise.resolve(true);
// 把 ready promise 注入 bridge：apiFetch 在未就绪时会 await 它，避免组件 onMounted 早调 API 直接抛错
if (isAndroidNative) setEmbeddedApiReadyPromise(embeddedApiReady);

if (isAndroid) {
  setApiPort(EMBEDDED_API_PORT);
  (window as unknown as Record<string, unknown>).api = bridge;
  if (!isAndroidNative) setAndroidEmbeddedApiAvailable(true);
}

// 初始化主题
useThemeStore().init();

/**
 * Android 端 cookie 注水：server 进程持久化 cookie 到磁盘（mobile-server 启动即加载），
 * 渲染层再补一次推送是为了应对持久化文件被外部清掉的边界。embeddedApiCookieReady 让 initPlayer
 * 等到推送完成（或确认无需推送）后再 restoreQueue，杜绝早于 NavUser.fetchStatus 取 VIP 流的 race。
 */
export let embeddedApiCookieReady: Promise<void> = Promise.resolve();

// Android 端设置 Node.js 嵌入式 API 端口；浏览器预览由 Vite dev 启动同端口 API。
if (isAndroid) {
  // 立刻 hydrate user store：pinia-plugin-persistedstate 在首次访问时同步从 localStorage 读取
  const user = useUserStore();
  embeddedApiCookieReady = embeddedApiReady
    .then(async (ready) => {
      if (!ready) {
        console.warn("[embedded-api] not ready, skip cookie push");
        return;
      }
      if (isAndroidNative) console.info("[embedded-api] ready");
      if (!user.cookie || !user.cookie.includes("MUSIC_U")) return;
      try {
        await bridge.apis.setCookie("netease", user.cookie);
      } catch (err) {
        console.warn("[user] 推送本地 cookie 到 server 失败", err);
      }
    })
    .catch((err) => {
      console.warn("[embedded-api] not available, some features may be limited:", err);
    });
}

// 应用级 effect scope：setup store 内的 onScopeDispose 需要活跃作用域才能注册，
// main.ts 顶层调用 store 时不属于任何组件，需显式提供作用域避免 dev 告警
effectScope(true).run(() => {
  // 同步语言设置
  watch(
    () => useSettingsStore().locale,
    (v) => {
      i18n.global.locale.value = v;
      window.api?.system.setLocale(v);
    },
    { immediate: true },
  );
});

/** splash 最短展示时长（ms） */
const SPLASH_MIN_MS = 1100;

/** splash 淡出时长（ms） */
const SPLASH_FADE_MS = 300;

/** 最短展示计时 */
const splashMinElapsed = new Promise<void>((resolve) => setTimeout(resolve, SPLASH_MIN_MS));

/** 等待首帧绘制完成 */
const nextPaintedFrame = (): Promise<void> =>
  new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));

/** 淡出并移除 splash 层 */
const removeSplash = (): void => {
  const el = document.getElementById("app-loading");
  if (!el) return;
  el.classList.add("hidden");
  setTimeout(() => el.remove(), SPLASH_FADE_MS + 50);
};

/**
 * 启动播放服务并分发冷启动任务
 */
const bootstrapPlayback = async (): Promise<void> => {
  await initPlayer();

  const pendingAudioFiles = await window.api.system.consumePendingAudioFiles();
  const pendingOrpheusUrl = await window.api.system.consumePendingProtocolUrl();

  if (pendingAudioFiles && pendingAudioFiles.length > 0) {
    await playFiles(pendingAudioFiles);
  } else if (pendingOrpheusUrl) {
    await handleOrpheus(pendingOrpheusUrl);
  } else {
    await restoreLastTrack();
  }
};

// 初始化程序
router.isReady().then(async () => {
  // 挂载应用
  app.mount("#app");
  // 淡出加载动画
  await Promise.all([splashMinElapsed, nextPaintedFrame()]);
  removeSplash();
  setTimeout(() => bootstrapPlayback().catch(console.error), SPLASH_FADE_MS);
  // 初始化快捷键
  useHotkeyStore()
    .init()
    .then(installHotkeyManager)
    .catch((err) => console.error("[hotkey] init failed", err));
});
