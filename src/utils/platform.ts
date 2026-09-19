/**
 * 平台检测常量（叶子模块，不依赖任何业务模块）。
 * 单独抽出以打破 bridge ↔ embeddedApi 的循环依赖：这些常量在模块加载期即被求值，
 * 若定义在 bridge 中，被循环里先求值的模块（如 embeddedApi 的 EMBEDDED_API_ORIGIN）
 * 同步读取会触发 TDZ（Cannot access before initialization），Android 生产包启动即崩。
 */
import { Capacitor } from "@capacitor/core";

export const isAndroid =
  typeof __SPLAYER_TARGET__ !== "undefined" && __SPLAYER_TARGET__ === "android";

/** 真实 Capacitor Android 容器；浏览器预览 Android UI 时为 false。 */
export const isAndroidNative = isAndroid && Capacitor.isNativePlatform();
/** 浏览器预览 Android UI（如从设备打开主机 IP 页面）；此时无法运行嵌入式服务，不能充当广播主机。 */
export const isAndroidPreview = isAndroid && !isAndroidNative;
