/**
 * Eruda 移动端调试面板 — 本地打包 + Android 适配
 *
 * 早期版本从 jsdelivr CDN 动态加载脚本，Android WebView 以 file:// 加载页面时，
 * https CDN 受混合内容 / CSP / 断网影响，整个面板加载失败。改为 npm 依赖动态 import，
 * 随 bundle 一起分发，断网亦可使用；桌面端从不调用 toggleEruda，eruda 会被 tree-shaking
 * 移出桌面 bundle。
 *
 * 适配要点：
 * - 挂载到独立容器 #eruda-root，避开 html[data-window-mode=full] body 的
 *   position:fixed + overflow:hidden，以及全局 * { min-height:44px } 对工具栏的干扰。
 * - 使用 #eruda scoped CSS 隔离全局样式，确保 console 暂停 / 日志类型过滤按钮可点。
 * - 注入竖屏适配 CSS：顶部工具栏横向滚动、面板高度受限、入口球避开状态栏。
 * - Network 抓包兜底：eruda 默认劫持 XHR/fetch，但本应用走嵌入式 127.0.0.1 API
 *   与 Capacitor 原生通道。bridge.ts 的 apiFetch 会把请求推给 networkLogHook，
 *   这里转交 eruda Network 面板。
 */

import type { Eruda } from "eruda";

/** 嵌入式 API 请求记录，由 bridge.apiFetch 推送 */
export interface NetworkLogEntry {
  method: string;
  url: string;
  status: number;
  durationMs: number;
  ok: boolean;
  error?: string;
}

/** bridge 注入的网络日志钩子；非 Android 或 eruda 未初始化时为 null */
type NetworkLogHook = (entry: NetworkLogEntry) => void;
let networkLogHook: NetworkLogHook | null = null;

/**
 * 供 bridge.apiFetch 调用：推送一条请求记录。
 * 未安装 hook 时为 no-op，零开销。
 */
export function reportNetworkEntry(entry: NetworkLogEntry): void {
  networkLogHook?.(entry);
}

let inited = false;
let loadingPromise: Promise<void> | null = null;
let rootEl: HTMLElement | null = null;
let consolePaused = false;
const consoleMethods = ["log", "info", "warn", "error", "debug"] as const;
type ConsoleMethod = (typeof consoleMethods)[number];
const originalConsoleMethods = new Map<ConsoleMethod, Console[ConsoleMethod]>();

/** 注入竖屏 / 安全区适配样式到 eruda 容器 */
function injectAdaptCss(): void {
  const css = `
    #eruda {
      pointer-events: none;
    }
    #eruda *,
    #eruda *::before,
    #eruda *::after {
      min-height: 0;
      user-select: text;
      -webkit-user-select: text;
    }
    #eruda .eruda-entry-btn,
    #eruda .eruda-dev-tools {
      pointer-events: auto;
      touch-action: auto;
    }
    #eruda .eruda-btn,
    #eruda .eruda-tab,
    #eruda [class*="control"],
    #eruda [class*="level"],
    #eruda [class*="icon-clear"],
    #eruda [class*="icon-filter"],
    #eruda [class*="icon-copy"],
    #eruda [data-level] {
      pointer-events: auto;
      touch-action: manipulation;
    }
    #eruda [class*="control"] {
      display: flex;
      align-items: center;
      gap: 8px;
      min-height: 42px !important;
      overflow-x: auto;
      overflow-y: hidden;
      -webkit-overflow-scrolling: touch;
    }
    #eruda [class*="control"] > span,
    #eruda .splayer-eruda-pause {
      min-width: 34px !important;
      min-height: 34px !important;
      padding: 0 6px !important;
      display: inline-flex !important;
      align-items: center;
      justify-content: center;
      flex: 0 0 auto;
      box-sizing: border-box;
      border-radius: 6px;
      pointer-events: auto;
      touch-action: manipulation;
    }
    #eruda .splayer-eruda-pause {
      color: #aeb6c2;
      font-size: 16px;
      font-weight: 700;
      line-height: 1;
    }
    #eruda .splayer-eruda-pause.is-paused {
      color: #4f8cff;
      background: rgb(79 140 255 / 0.14);
    }
    /* 顶部工具栏横向滚动，竖屏不裁切 console 暂停 / 日志类型按钮 */
    #eruda .eruda-dev-tools .eruda-toolbar,
    #eruda .eruda-dev-tools .eruda-tabbar {
      overflow-x: auto;
      overflow-y: hidden;
      white-space: nowrap;
      -webkit-overflow-scrolling: touch;
    }
    /* 面板高度受限，避免竖屏溢出可视区 */
    #eruda .eruda-dev-tools .eruda-panel {
      max-height: min(70vh, calc(100dvh - var(--safe-area-top, 0px)));
    }
    /* 底部留出手势条安全区 */
    #eruda .eruda-dev-tools {
      padding-bottom: var(--safe-area-bottom, 0px);
    }
    /* 入口球避开状态栏，便于拖动 */
    #eruda .eruda-entry-btn {
      top: calc(var(--safe-area-top, 0px) + 8px) !important;
    }
  `;
  const style = document.createElement("style");
  style.textContent = css;
  // 样式挂到 document.head，使用 #eruda 前缀限制作用域
  document.head.appendChild(style);
}

/** 首次加载并初始化 eruda */
async function ensureInited(eruda: Eruda): Promise<void> {
  const root = document.createElement("div");
  root.id = "eruda-root";
  root.style.cssText = "position:fixed;inset:0;z-index:99999;pointer-events:none;";
  document.body.appendChild(root);
  rootEl = root;

  eruda.init({
    container: root,
    tool: ["console", "elements", "network", "resources", "info"],
    useShadowDom: false,
    autoScale: true,
  });
  injectAdaptCss();
  hookNetwork(eruda);
  installConsoleControls();
  inited = true;
}

/** 销毁 eruda，避免隐藏后的遮罩继续拦截页面触摸滚动 */
function destroyEruda(eruda: Eruda): void {
  restoreConsoleControls();
  eruda.destroy();
  rootEl?.remove();
  rootEl = null;
  networkLogHook = null;
  inited = false;
}

/** 切换 Eruda 面板（首次调用初始化，再次调用彻底退出） */
export const toggleEruda = async (): Promise<void> => {
  if (!inited) {
    if (loadingPromise) return;
    loadingPromise = (async () => {
      try {
        const { default: eruda } = await import("eruda");
        await ensureInited(eruda);
        eruda.show();
      } catch (e) {
        console.warn("[Eruda]", e instanceof Error ? e.message : "加载失败");
      } finally {
        loadingPromise = null;
      }
    })();
    return;
  }
  const { default: eruda } = await import("eruda");
  destroyEruda(eruda);
};

/** 为 Console 增加移动端可点击的暂停按钮，并修复 span 控件触摸命中 */
function installConsoleControls(): void {
  consolePaused = false;
  consoleMethods.forEach((method) => {
    originalConsoleMethods.set(method, console[method]);
    console[method] = ((...args: unknown[]) => {
      if (consolePaused) return;
      originalConsoleMethods.get(method)?.(...(args as never[]));
    }) as Console[typeof method];
  });

  window.setTimeout(() => {
    const controls = Array.from(
      document.querySelectorAll<HTMLElement>("#eruda [class*='control']"),
    );
    const control = controls.find((el) => el.querySelector("[data-level='all']"));
    if (!control || control.querySelector(".splayer-eruda-pause")) return;

    const pause = document.createElement("button");
    pause.type = "button";
    pause.className = "splayer-eruda-pause";
    pause.textContent = "Ⅱ";
    pause.setAttribute("aria-label", "暂停日志");
    pause.addEventListener("click", (event) => {
      event.stopPropagation();
      consolePaused = !consolePaused;
      pause.textContent = consolePaused ? "▶" : "Ⅱ";
      pause.classList.toggle("is-paused", consolePaused);
      pause.setAttribute("aria-label", consolePaused ? "恢复日志" : "暂停日志");
    });
    pause.addEventListener("touchend", (event) => {
      event.preventDefault();
      event.stopPropagation();
      pause.click();
    });
    control.insertBefore(pause, control.firstChild);

    control.addEventListener(
      "touchend",
      (event) => {
        const target = event.target as HTMLElement | null;
        const clickable = target?.closest<HTMLElement>(
          "[data-level], [class*='icon-clear'], [class*='icon-filter'], [class*='icon-copy']",
        );
        if (!clickable) return;
        event.preventDefault();
        event.stopPropagation();
        clickable.click();
      },
      { capture: true },
    );
  }, 300);
}

/** 恢复被暂停按钮包装过的 console 方法 */
function restoreConsoleControls(): void {
  originalConsoleMethods.forEach((fn, method) => {
    console[method] = fn;
  });
  originalConsoleMethods.clear();
  consolePaused = false;
}

/** eruda Network 内部请求结构 */
interface ErudaNetworkRequest {
  name: string;
  url: string;
  status: number | string;
  type: string;
  subType: string;
  size: number;
  data: string;
  method: string;
  startTime: number;
  time: number;
  displayTime: string;
  resTxt: string;
  done: boolean;
  reqHeaders: Record<string, string>;
  resHeaders: Record<string, string>;
  hasErr?: boolean;
  render: () => void;
}

interface ErudaNetworkGridNode {
  data: Record<string, unknown>;
  render: () => void;
  container?: HTMLElement;
}

interface ErudaNetworkInternal {
  _requests: Record<string, ErudaNetworkRequest>;
  _requestDataGrid?: {
    append: (
      data: Record<string, unknown>,
      options?: Record<string, unknown>,
    ) => ErudaNetworkGridNode;
  };
  _isRecording?: boolean;
}

/** 把 bridge 推送的请求记录转交 eruda Network 面板 */
function hookNetwork(eruda: Eruda): void {
  const network = eruda.get("network") as unknown as ErudaNetworkInternal | undefined;
  if (!network?._requestDataGrid) {
    // Network 插件不可用时降级到 console
    networkLogHook = (e) => {
      console.log(
        `[net] ${e.method} ${e.url} → ${e.status} (${e.durationMs}ms)${e.error ? " " + e.error : ""}`,
      );
    };
    return;
  }
  networkLogHook = (e) => {
    const requestId = `splayer-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const name = new URL(e.url).pathname.split("/").filter(Boolean).pop() ?? e.url;
    let row: ErudaNetworkGridNode | null = null;
    const request: ErudaNetworkRequest = {
      name,
      url: e.url,
      status: e.status || "failed",
      type: "fetch",
      subType: "json",
      size: 0,
      data: "",
      method: e.method,
      startTime: Date.now() - e.durationMs,
      time: e.durationMs,
      displayTime: `${e.durationMs}ms`,
      resTxt: e.error ?? "",
      done: true,
      reqHeaders: {},
      resHeaders: {},
      hasErr: !e.ok,
      render: () => {
        const data = {
          name: request.name,
          method: request.method,
          status: request.status,
          type: request.subType,
          size: request.size,
          time: request.displayTime,
        };
        if (row) {
          row.data = data;
          row.render();
        } else {
          row = network._requestDataGrid?.append(data, { selectable: true }) ?? null;
          row?.container?.setAttribute("data-id", requestId);
        }
      },
    };
    request.render();
    network._requests[requestId] = request;
  };
}
