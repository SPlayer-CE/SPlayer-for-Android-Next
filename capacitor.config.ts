/// <reference types="@capacitor/status-bar" />

import type { CapacitorConfig } from "@capacitor/cli";

type AndroidCapacitorConfig = CapacitorConfig & {
  /**
   * Project-level marker for Android WebView blur requirements.
   * Capacitor does not consume this key directly. Keep AndroidManifest.xml
   * synchronized with android:hardwareAccelerated="true" after `npx cap add android`.
   */
  androidHardwareAcceleration?: boolean;
};

const config: AndroidCapacitorConfig = {
  appId: "top.imsyy.splayer_next",
  appName: "SPlayer Next",
  webDir: "dist/capacitor",
  backgroundColor: "#00000000",
  // 关闭 Capacitor 的事件冗余日志（playbackStateChanged / progressChanged 等 10Hz 推送会刷屏）
  loggingBehavior: "production",
  initialFocus: true,
  android: {
    backgroundColor: "#00000000",
    allowMixedContent: true,
    webContentsDebuggingEnabled: true,
    initialFocus: true,
  },
  plugins: {
    StatusBar: {
      overlaysWebView: true,
      style: "LIGHT",
      backgroundColor: "#00000000",
    },
    // 禁用 Capacitor 内置 safe-area CSS 变量注入：启动时 DOM 尚未就绪会报 null 错误，
    // 且 global.css 已通过 env(safe-area-inset-*) 自行处理安全区域
    SystemBars: {
      insetsHandling: "disable",
    },
  },
  androidHardwareAcceleration: true,
};

export default config;
