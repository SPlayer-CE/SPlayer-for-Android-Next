/// <reference types="vite/client" />
/// <reference types="unplugin-icons/types/vue" />

declare module "*.vue" {
  import type { DefineComponent } from "vue";
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>;
  export default component;
}

declare const __APP_VERSION__: string;
declare const __APP_REPO_URL__: string;
declare const __APP_REPO_NAME__: string;
declare const __APP_AUTHOR__: string;
declare const __APP_HOMEPAGE__: string;
declare const __APP_AUTHOR_URL__: string;
declare const __COMMIT_HASH__: string;
declare const __COMMIT_DATE__: string;
declare const __SPLAYER_TARGET__: "electron" | "android";

/** nodejs-mobile-cordova 运行时桥接 */
interface NodeJsMobileChannel {
  setListener(callback: (message: string) => void): void;
  post(event: string, ...args: unknown[]): void;
  send(...args: unknown[]): void;
}

interface NodeJsMobileRuntime {
  start(
    filename: string,
    callback?: (error?: Error | null) => void,
    options?: Record<string, unknown>,
  ): void;
  startWithScript(
    script: string,
    callback?: (error?: Error | null) => void,
    options?: Record<string, unknown>,
  ): void;
  channel: NodeJsMobileChannel;
}

interface Window {
  __splashStart?: number;
  nodejs?: NodeJsMobileRuntime;
}
