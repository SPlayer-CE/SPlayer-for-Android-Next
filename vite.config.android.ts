import { dirname, resolve } from "path";
import { spawn } from "child_process";
import { fileURLToPath } from "url";
import { defineConfig } from "vite";
import { getGitCommit, getGitDate } from "./scripts/git-info";
import UnoCSS from "unocss/vite";
import vue from "@vitejs/plugin-vue";
import AutoImport from "unplugin-auto-import/vite";
import Icons from "unplugin-icons/vite";
import IconsResolver from "unplugin-icons/resolver";
import { FileSystemIconLoader } from "unplugin-icons/loaders";
import RekaResolver from "reka-ui/resolver";
import Components from "unplugin-vue-components/vite";
import pkg from "./package.json" with { type: "json" };

const rootDir = dirname(fileURLToPath(import.meta.url));
const embeddedApiProcessKey = "__SPLAYER_EMBEDDED_API_DEV_PROCESS__";

const getPnpmExecCommand = () => {
  const npmExecPath = process.env["npm_execpath"];
  if (npmExecPath) {
    return {
      command: process.execPath,
      args: [npmExecPath, "exec", "tsx", "--tsconfig", "tsconfig.node.json", "API/mobile-entry.ts"],
    };
  }

  if (process.platform === "win32") {
    return {
      command: "cmd.exe",
      args: [
        "/d",
        "/s",
        "/c",
        "pnpm",
        "exec",
        "tsx",
        "--tsconfig",
        "tsconfig.node.json",
        "API/mobile-entry.ts",
      ],
    };
  }

  return {
    command: "pnpm",
    args: ["exec", "tsx", "--tsconfig", "tsconfig.node.json", "API/mobile-entry.ts"],
  };
};

function embeddedApiDevServer() {
  return {
    name: "embedded-api-dev-server",
    apply: "serve" as const,
    configureServer(server: import("vite").ViteDevServer) {
      if (process.env["SPLAYER_SKIP_EMBEDDED_API_DEV"] === "true") return;

      const globalState = globalThis as typeof globalThis & {
        [embeddedApiProcessKey]?: ReturnType<typeof spawn>;
      };
      if (globalState[embeddedApiProcessKey]) return;

      const { command, args } = getPnpmExecCommand();
      const apiProcess = spawn(command, args, {
        cwd: rootDir,
        env: {
          ...process.env,
          SP_API_PORT: process.env["SP_API_PORT"] || "13962",
          SP_API_HOST: process.env["SP_API_HOST"] || "0.0.0.0",
        },
        stdio: ["ignore", "pipe", "pipe"],
      });
      globalState[embeddedApiProcessKey] = apiProcess;

      apiProcess.stdout.on("data", (chunk) => {
        const output = chunk.toString().trimEnd();
        if (output) console.info(`[embedded-api] ${output}`);
      });
      apiProcess.stderr.on("data", (chunk) => {
        const output = chunk.toString().trimEnd();
        if (output) console.warn(`[embedded-api] ${output}`);
      });
      apiProcess.on("exit", () => {
        if (globalState[embeddedApiProcessKey] === apiProcess) {
          delete globalState[embeddedApiProcessKey];
        }
      });

      const cleanup = () => {
        if (!apiProcess.killed) apiProcess.kill();
      };
      server.httpServer?.once("close", cleanup);
    },
  };
}

/**
 * Android / Capacitor 构建配置。
 *
 * 设计目标：
 * - 沿用桌面端同一套 Vue + UnoCSS + Reka UI + unplugin-icons 插件链，避免 UI 风格分叉。
 * - 输出目录与 Capacitor `webDir`（capacitor.config.ts 中的 `dist/capacitor`）保持一致。
 * - `base: "./"` 适配 Capacitor `file://` 加载，不依赖绝对路径。
 * - 桌面端 multi-page（desktop-lyric / dynamic-island / taskbar-lyric）入口不打包，Android 仅需单页入口。
 * - 定义常量与桌面端对齐（`__APP_VERSION__` / `__APP_REPO_URL__` 等），方便 Web 端按平台共用逻辑。
 */
export default defineConfig({
  base: "./",
  publicDir: resolve(rootDir, "public"),
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
    __APP_REPO_URL__: JSON.stringify(pkg.repository.url),
    __APP_REPO_NAME__: JSON.stringify(pkg.productName),
    __APP_AUTHOR__: JSON.stringify(pkg.author.name),
    __APP_HOMEPAGE__: JSON.stringify(pkg.homepage),
    __APP_AUTHOR_URL__: JSON.stringify(pkg.author.url),
    __COMMIT_HASH__: JSON.stringify(getGitCommit()),
    __COMMIT_DATE__: JSON.stringify(getGitDate()),
    __SPLAYER_TARGET__: JSON.stringify("android"),
  },
  resolve: {
    alias: {
      "@": resolve(rootDir, "src"),
      "@shared": resolve(rootDir, "shared"),
      "@root": rootDir,
    },
  },
  plugins: [
    embeddedApiDevServer(),
    {
      name: "inject-cordova-bootstrap",
      apply: "build",
      transformIndexHtml(html) {
        if (html.includes("cordova.js")) return html;
        return {
          html,
          tags: [
            {
              tag: "script",
              children:
                "if (window.Capacitor?.getPlatform) { document.write('<script src=\"./cordova.js\"><\\/script>'); }",
              injectTo: "head-prepend",
            },
          ],
        };
      },
    },
    vue(),
    UnoCSS(),
    AutoImport({
      imports: ["vue", "pinia", "vue-router", "@vueuse/core", "vue-i18n"],
      eslintrc: {
        enabled: true,
        filepath: "./auto-eslint.mjs",
      },
    }),
    Icons({
      compiler: "vue3",
      scale: 1,
      customCollections: {
        sp: FileSystemIconLoader("./src/assets/icons"),
      },
    }),
    Components({
      dirs: ["src/components"],
      resolvers: [RekaResolver(), IconsResolver({ prefix: "icon", customCollections: ["sp"] })],
    }),
  ],
  build: {
    outDir: "dist/capacitor",
    emptyOutDir: true,
    sourcemap: false,
    rollupOptions: {
      input: {
        index: resolve(rootDir, "index.html"),
      },
    },
  },
});
