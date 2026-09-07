/**
 * Capacitor 桥接层高频日志过滤器
 *
 * Capacitor 的 loggingBehavior:"production" 不抑制原生桥接 notifyListeners payload 输出
 * 和 addListener resolve 日志，这些日志在播放期间以 10-30Hz 刷屏，浪费资源。
 * 本模块在 main.ts 最早执行，monkey-patch console.log/console.debug 过滤掉这些噪音。
 */

/** 匹配 Capacitor 原生调用和响应日志：
 * native PluginName.methodName (#callbackId)
 * result PluginName.methodName (#callbackId)
 */
const CAPACITOR_NATIVE_RE = /^(native|result) .+\.[a-zA-Z0-9_]+ \(#\d+\)/;

/** 匹配高频 payload：包含 fftB64（可视化数据，~30Hz）或同时含 positionMs+durationMs（进度推送，~1Hz） */
const isHighFreqPayload = (arg: unknown): boolean => {
  if (typeof arg !== "object" || arg === null) return false;
  const keys = Object.keys(arg as Record<string, unknown>);
  // FFT 可视化数据（最高频）
  if (keys.includes("fftB64")) return true;
  // 播放进度 payload（positionMs + durationMs 同时出现时为 Capacitor 桥接推送）
  if (keys.includes("positionMs") && keys.includes("durationMs")) return true;
  return false;
};

/** 检查单条日志是否应被抑制 */
const shouldSuppress = (args: unknown[]): boolean => {
  if (args.length === 0) return false;

  const first = args[0];
  // Capacitor 的 native/result 调试日志
  if (typeof first === "string") {
    if (CAPACITOR_NATIVE_RE.test(first)) return true;
    // 有时 Capacitor 也会输出不带 "native " 前缀，但格式相似的日志
    if (first.includes("AndroidNativePlayback.") && first.includes("(#")) return true;
    // 有些扩展或 WebView 控制台注入的输出前缀
    if (first.startsWith("native ") || first.startsWith("result ")) {
      if (first.includes("AndroidNativePlayback") || first.includes("(#")) return true;
    }
  }

  // 检查是否包含包含高频 payload 的对象（例如 Capacitor 打印的参数体）
  for (const arg of args) {
    if (isHighFreqPayload(arg)) return true;
  }

  // 检查是否为独立的高频对象输出（部分控制台会将独立对象打成第一参数）
  if (args.length === 1 && typeof args[0] === "object" && args[0] !== null) {
    const obj = args[0] as Record<string, unknown>;
    if (obj.callbackId && obj.pluginId) return true;
    if (obj.action && typeof obj.action === "string") return true;
    if (
      "paused" in obj &&
      "ready" in obj &&
      "playing" in obj &&
      "buffering" in obj &&
      "durationMs" in obj &&
      "positionMs" in obj
    )
      return true;
    if ("fftB64" in obj) return true;
  }

  // 对于紧跟在 native/result 日志后面的 undefined
  if (args.length === 1) {
    if (args[0] === undefined) return true;
  }

  return false;
};

/** 原始 console 方法（patch 前保存） */
const rawLog = console.log;
const rawDebug = console.debug;
const rawError = console.error;

type CapacitorBridgeLogger = {
  isLoggingEnabled?: boolean;
  logToNative?: (call: unknown) => void;
  logFromNative?: (result: { success?: boolean }) => void;
  __splayerBridgeLogPatched?: boolean;
};

/** 把 Capacitor 桥接日志改成仅错误输出，避免 native/result 正常回包刷屏。 */
const patchCapacitorBridgeLogger = (): void => {
  let retryCount = 0;

  const applyPatch = () => {
    const win = window as Window & { Capacitor?: CapacitorBridgeLogger };
    const cap = win.Capacitor;
    if (!cap || typeof cap.logToNative !== "function" || typeof cap.logFromNative !== "function") {
      if (retryCount < 60) {
        retryCount += 1;
        window.setTimeout(applyPatch, 50);
      }
      return;
    }
    if (cap.__splayerBridgeLogPatched) return;

    const rawLogFromNative = cap.logFromNative.bind(cap);
    cap.logToNative = () => {};
    cap.logFromNative = (result) => {
      if (result?.success === false) {
        rawLogFromNative(result);
      }
    };
    // 保持开关开启，让失败回包仍能走 logFromNative。
    cap.isLoggingEnabled = true;
    cap.__splayerBridgeLogPatched = true;
  };

  applyPatch();
};

/** 安装过滤器：替换 console.log / console.debug / console.error */
export const installBridgeLogFilter = (): void => {
  patchCapacitorBridgeLogger();
  console.log = (...args: unknown[]) => {
    if (shouldSuppress(args)) return;
    rawLog(...args);
  };
  console.debug = (...args: unknown[]) => {
    if (shouldSuppress(args)) return;
    rawDebug(...args);
  };
  console.error = (...args: unknown[]) => {
    // 仅拦截厂商驱动/系统噪音（GPU 无效渲染 / QoS / MIUI 预渲染 / 隐藏 API / 灭屏绘制），保留其他真实的 native error
    if (args.length > 0 && typeof args[0] === "string" && args[0].includes("[native:error]")) {
      const msg = args[0] as string;
      if (
        msg.includes("GuiExtAuxCheckAuxPath") ||
        msg.includes("setTidQosLevel") ||
        msg.includes("XQos") ||
        msg.includes("/dev/xr_fbt") ||
        msg.includes("QoS device not available") ||
        msg.includes("MI-PreRender") ||
        msg.includes("perfctl") ||
        msg.includes("FPSGO") ||
        msg.includes("hiddenapi") ||
        msg.includes("SettingTrigger") ||
        msg.includes("contentcatcher") ||
        msg.includes("Interceptor") ||
        msg.includes("VRI[") ||
        msg.includes("Not drawing due to screen off")
      ) {
        return;
      }
    }
    rawError(...args);
  };
};
