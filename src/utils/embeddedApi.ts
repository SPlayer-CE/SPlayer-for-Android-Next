import { isAndroid, isAndroidNative, setAndroidEmbeddedApiAvailable } from "@/services/bridge";

export const EMBEDDED_API_PORT = 13962;

// 真机固定走设备回环地址；浏览器预览跟随当前页面 hostname（支持局域网访问）
const getEmbeddedApiHost = (): string => {
  if (isAndroidNative) return "127.0.0.1";
  return typeof window !== "undefined" ? window.location.hostname || "127.0.0.1" : "127.0.0.1";
};

export const EMBEDDED_API_ORIGIN = `http://${getEmbeddedApiHost()}:${EMBEDDED_API_PORT}`;

const DEVICE_READY_TIMEOUT_MS = 15000;
const EMBEDDED_API_READY_EVENT = "embedded-api-ready";
// 服务就绪总预算：nodejs-mobile 首次冷启动需解压资源，真机可能较慢，给足时间避免误判失败
const EMBEDDED_API_READY_BUDGET_MS = 60000;
// 健康检查轮询间隔
const EMBEDDED_API_HEALTH_POLL_MS = 1000;

let nodeRuntimeStartPromise: Promise<void> | null = null;
let embeddedApiReadyPromise: Promise<boolean> | null = null;
let nodeRuntimeStarted = false;
let embeddedApiAvailable = false;

/** 检查嵌入式 API 是否可用（前端可在调用前判断，避免不必要的网络请求） */
export const isEmbeddedApiAvailable = (): boolean => embeddedApiAvailable;

const markEmbeddedApiAvailable = (): void => {
  embeddedApiAvailable = true;
  setAndroidEmbeddedApiAvailable(true);
};

const delay = (ms: number) => new Promise<void>((resolve) => window.setTimeout(resolve, ms));

const waitForNodeRuntimeBridge = async () => {
  if (!isAndroid) return;
  if (window.nodejs) return;

  await new Promise<void>((resolve, reject) => {
    const cleanup = () => {
      window.clearTimeout(timer);
      document.removeEventListener("deviceready", onDeviceReady);
    };

    const onDeviceReady = () => {
      if (window.nodejs) {
        cleanup();
        resolve();
      }
    };

    const timer = window.setTimeout(() => {
      cleanup();
      reject(new Error("Timed out waiting for nodejs-mobile bridge"));
    }, DEVICE_READY_TIMEOUT_MS);

    document.addEventListener("deviceready", onDeviceReady, { once: true });

    const poll = async () => {
      while (!window.nodejs) {
        await delay(100);
        if (window.nodejs) {
          cleanup();
          resolve();
          return;
        }
      }
    };

    void poll();
  });
};

let readyListenerInstalled = false;

/** 安装持久就绪事件监听：无论服务何时就绪都能幂等标记可用，晚到的事件同样生效 */
const installReadyEventListener = (): void => {
  if (readyListenerInstalled) return;
  const nodeRuntime = window.nodejs;
  if (!nodeRuntime) return;
  readyListenerInstalled = true;
  nodeRuntime.channel.setListener((message: string) => {
    if (message === EMBEDDED_API_READY_EVENT) {
      console.info("[embedded-api] 收到就绪事件");
      markEmbeddedApiAvailable();
    }
  });
};

const isEmbeddedApiHealthy = async () => {
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), 2000);

  try {
    const response = await fetch(`${EMBEDDED_API_ORIGIN}/api/health`, {
      method: "GET",
      cache: "no-store",
      signal: controller.signal,
    });
    if (!response.ok) return false;
    const body = (await response.json()) as { nodeReady?: boolean };
    return body.nodeReady === true;
  } catch {
    return false;
  } finally {
    window.clearTimeout(timer);
  }
};

export const startEmbeddedApiRuntime = async () => {
  if (!isAndroid) return;
  if (nodeRuntimeStarted) return;
  if (nodeRuntimeStartPromise) return nodeRuntimeStartPromise;

  nodeRuntimeStartPromise = (async () => {
    await waitForNodeRuntimeBridge();

    await new Promise<void>((resolve, reject) => {
      const nodeRuntime = window.nodejs;
      if (!nodeRuntime) {
        reject(new Error("nodejs-mobile runtime bridge is unavailable"));
        return;
      }

      nodeRuntime.start(
        "main.js",
        (error?: Error | null) => {
          if (error) {
            const message = String(error);
            if (message.toLowerCase().includes("already")) {
              nodeRuntimeStarted = true;
              resolve();
              return;
            }
            reject(error);
            return;
          }

          nodeRuntimeStarted = true;
          resolve();
        },
        { redirectOutputToLogcat: true },
      );
    });
  })();

  try {
    await nodeRuntimeStartPromise;
  } catch (error) {
    nodeRuntimeStartPromise = null;
    console.error("[embedded-api] startup failed:", error);
    throw error;
  }
};

export const waitForEmbeddedApiReady = async (): Promise<boolean> => {
  if (!isAndroid) return true;
  if (embeddedApiReadyPromise) return embeddedApiReadyPromise;

  embeddedApiReadyPromise = (async () => {
    await waitForNodeRuntimeBridge();
    // 持久监听就绪事件（幂等）；nodejs-mobile 引擎只能启动一次，故运行时只启动一次、失败也不重启
    installReadyEventListener();
    try {
      await startEmbeddedApiRuntime();
    } catch (error) {
      console.warn("[embedded-api] 运行时启动失败，继续等待服务监听:", error);
    }

    // 轮询健康检查直到服务真正监听；就绪事件经持久监听提前置位 embeddedApiAvailable 时立即返回
    const deadline = Date.now() + EMBEDDED_API_READY_BUDGET_MS;
    while (Date.now() < deadline) {
      if (embeddedApiAvailable) return true;
      if (await isEmbeddedApiHealthy()) {
        markEmbeddedApiAvailable();
        return true;
      }
      await delay(EMBEDDED_API_HEALTH_POLL_MS);
    }

    // 预算耗尽 — soft-fail：不阻塞应用启动。持久监听仍在，服务晚启动后会自动置位可用
    console.warn("[embedded-api] 启动超时，暂以降级模式运行（服务就绪后将自动恢复）");
    setAndroidEmbeddedApiAvailable(false);
    return false;
  })();

  // 不抛异常：即使 embedded API 不可用，也让应用正常挂载
  return embeddedApiReadyPromise;
};
