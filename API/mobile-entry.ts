// 真机内嵌 Node 只给 Kotlin 代理层访问，继续监听 13233，避免与 13962 的代理服务撞端口
process.env["SP_API_PORT"] = process.env["SP_API_PORT"] || "13233";
// 真机仅需本机回环；Vite dev 会显式传入 0.0.0.0
process.env["SP_API_HOST"] = process.env["SP_API_HOST"] || "127.0.0.1";
process.env["SP_EMBEDDED"] = "1";

// nodejs-mobile 的 getaddrinfo 在部分 Android 设备/ROM 上无法读取系统 DNS 配置，
// 导致 ENOTFOUND 错误。用公共 DNS 绕过系统 DNS 解析，覆盖所有外部网络请求。
// dns.setServers 只影响 dns.resolve4/6；http/https 模块内部走 dns.lookup，
// 所以还需要替换 lookup 让它走 dns.resolve4 而非系统 getaddrinfo。
const dns = require("dns");
if (!process.env["SP_DNS_SERVERS"]) {
  dns.setServers(["223.5.5.5", "8.8.8.8"]);

  const originalLookup = dns.lookup;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  dns.lookup = (hostname: string, options: any, callback: any) => {
    if (typeof options === "function") {
      callback = options;
      options = {};
    } else if (typeof options === "number") {
      // dns.lookup(hostname, family, callback) 签名归一化
      options = { family: options };
    }
    // IPv6-only 或 all: true 模式回退到原始 getaddrinfo
    if (options?.family === 6 || options?.all) {
      originalLookup(hostname, options, callback);
      return;
    }
    dns.resolve4(hostname, (err, addresses) => {
      if (!err && addresses.length) {
        callback(null, addresses[0], 4);
        return;
      }
      originalLookup(hostname, options, callback);
    });
  };
}

// 未捕获异步异常的节流参数：仅在窗口内打印前 N 条，避免重复堆栈刷屏
const UNHANDLED_REJECTION_WINDOW_MS = 60_000;
const UNHANDLED_REJECTION_LOGS_PER_WINDOW = 8;
let unhandledRejectionCount = 0;
let unhandledRejectionWindowStartedAt = 0;
let fatalExitScheduled = false;

const scheduleFatalExit = (reason: string): void => {
  if (fatalExitScheduled) return;
  fatalExitScheduled = true;
  console.error(`[embedded-api] fatal ${reason}, exiting for Android restart`);
  setTimeout(() => process.exit(1), 100).unref?.();
};

// nodejs-mobile 只能启动一次：进程退出后嵌入式 API 永久不可用（必须重启 App 才能恢复）。
// 因此 uncaughtException / unhandledRejection 一律不退出进程，仅记录日志；请求级错误由各 handler 的 try/catch 兜底。
// 仅在 mobile-server 启动导入失败这种根本性错误时才退出。
process.on("uncaughtException", (error) => {
  console.error("[embedded-api] uncaughtException", error);
});

// 第三方插件在沙箱里执行，常产生无法归因的异步异常（例如引用了未注入的全局变量
// `ReferenceError: xxx is not defined`）。这类插件错误只影响该插件本身，绝不能拖垮整个 App 内嵌 API，
// 所以这里只做节流日志，方便排障，不触发 fatalExit。
process.on("unhandledRejection", (reason) => {
  const now = Date.now();
  if (now - unhandledRejectionWindowStartedAt > UNHANDLED_REJECTION_WINDOW_MS) {
    unhandledRejectionWindowStartedAt = now;
    unhandledRejectionCount = 0;
  }
  unhandledRejectionCount += 1;
  if (unhandledRejectionCount <= UNHANDLED_REJECTION_LOGS_PER_WINDOW) {
    console.error("[embedded-api] unhandledRejection", reason);
  }
});

import("./mobile-server").catch((error) => {
  console.error("[embedded-api] failed to load mobile-server:", error);
  scheduleFatalExit("mobile-server import failure");
});
