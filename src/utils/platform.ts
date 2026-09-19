import { Capacitor } from "@capacitor/core";

export const isAndroid =
  typeof __SPLAYER_TARGET__ !== "undefined" && __SPLAYER_TARGET__ === "android";

/** 真实 Capacitor Android 容器；浏览器预览 Android UI 时为 false。 */
export const isAndroidNative = isAndroid && Capacitor.isNativePlatform();

/** 浏览器预览 Android UI（如从设备打开主机 IP 页面）；此时无法运行嵌入式服务，不能充当广播主机。 */
export const isAndroidPreview = isAndroid && !isAndroidNative;
