import { registerPlugin } from "@capacitor/core";

/** 桌面图标色板变体名（与 Android 端 AndroidAppIconPlugin 及 manifest 中 activity-alias 一一对应） */
export type AndroidAppIconVariant = "green" | "red" | "blue" | "purple" | "orange" | "pink";

/** AndroidAppIcon 插件接口（与 Kotlin 端 AndroidAppIconPlugin 方法对齐） */
export interface AndroidAppIconPlugin {
  /** 读取当前生效的桌面图标变体名 */
  getIcon(): Promise<{ icon: AndroidAppIconVariant }>;
  /** 切换桌面图标变体（桌面图标刷新可能有延迟） */
  setIcon(options: { icon: AndroidAppIconVariant }): Promise<void>;
}

export const AndroidAppIcon = registerPlugin<AndroidAppIconPlugin>("AndroidAppIcon");
