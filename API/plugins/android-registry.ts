import fs from "node:fs";
import path from "node:path";
import { EventEmitter } from "node:events";
import { writeFileSync as atomicWriteSync } from "atomically";
import type {
  PlaybackEventKind,
  PluginInfo,
  PluginManifest,
  PluginMenuItem,
  PluginSettingItem,
  PluginStatus,
  PluginUpdateInfo,
} from "../../shared/types/plugin";
import { PluginErrorCodes } from "../../shared/defaults/plugin-api";
import { getSystemConfigValue, getSystemConfigStore, setSystemConfigValue } from "../config-store";
import {
  pluginsDataDir,
  pluginsManifestFilePath,
  pluginsRootDir,
  pluginsScriptsDir,
} from "../runtime-paths";
import { loadScript } from "../../electron/main/plugins/loader";
import { pluginStorageDrop } from "./android-storage";
import { fetchScript } from "./android-net";
import { AndroidPluginSandbox } from "./android-host";

interface StoredManifest {
  version: 1;
  plugins: Record<string, PluginManifest>;
}

interface PluginRuntime {
  manifest: PluginManifest;
  enabled: boolean;
  status: PluginStatus;
  loading: boolean;
  source: string;
  sandbox: AndroidPluginSandbox | null;
  updateInfo: PluginUpdateInfo | null;
  events: PlaybackEventKind[];
  controls: boolean;
  settings: PluginSettingItem[];
  menus: PluginMenuItem[];
}

const ensureDirs = (): void => {
  for (const directory of [pluginsRootDir, pluginsScriptsDir, pluginsDataDir]) {
    if (!fs.existsSync(directory)) {
      fs.mkdirSync(directory, { recursive: true });
    }
  }
};

const PLUGIN_SCRIPT_FILE_RE = /^[a-z0-9._-]+\.js$/;

const scriptFileNameForId = (id: string): string => {
  const fileName = `${id}.js`;
  if (!PLUGIN_SCRIPT_FILE_RE.test(fileName)) {
    throw Object.assign(new Error(`invalid plugin id: ${id}`), {
      code: PluginErrorCodes.INVALID_MANIFEST,
    });
  }
  return fileName;
};

const scriptPathForFileName = (fileName: string): string => {
  if (!PLUGIN_SCRIPT_FILE_RE.test(fileName)) {
    throw Object.assign(new Error(`invalid plugin script file: ${fileName}`), {
      code: PluginErrorCodes.INVALID_MANIFEST,
    });
  }
  return path.join(pluginsScriptsDir, fileName);
};

const readStored = (): StoredManifest => {
  try {
    const raw = fs.readFileSync(pluginsManifestFilePath, "utf8");
    const parsed = JSON.parse(raw) as StoredManifest;
    if (parsed.version === 1 && parsed.plugins) return parsed;
  } catch {
    /* ignore */
  }
  return { version: 1, plugins: {} };
};

const writeStored = (data: StoredManifest): void => {
  ensureDirs();
  atomicWriteSync(pluginsManifestFilePath, JSON.stringify(data, null, 2));
};

const sanitizeSettingValue = (item: PluginSettingItem, value: unknown): unknown => {
  switch (item.type) {
    case "switch":
      return Boolean(value);
    case "number": {
      let num = Number(value);
      if (!Number.isFinite(num)) num = Number(item.default);
      if (item.min != null) num = Math.max(item.min, num);
      if (item.max != null) num = Math.min(item.max, num);
      return num;
    }
    case "select":
      return item.options?.some((option) => option.value === value) ? value : item.default;
    case "text":
    default:
      return String(value ?? "");
  }
};

class AndroidPluginRegistry extends EventEmitter {
  private readonly runtimes = new Map<string, PluginRuntime>();
  private initPromise: Promise<void> | null = null;

  /** 单调递增的 watch 游标，每次插件状态变更 +1 */
  private watchSeq = 0;
  /** 环形缓冲，保留最近 50 条状态事件（含 seq） */
  private readonly watchBuffer: Array<{ seq: number; info: PluginInfo }> = [];
  private static readonly WATCH_BUFFER_LIMIT = 50;
  /** 长轮询等待者列表 */
  private readonly watchWaiters: Array<{
    sinceCursor: number;
    resolve: (result: { events: PluginInfo[]; cursor: number }) => void;
    timer: ReturnType<typeof setTimeout>;
  }> = [];

  ensureInitialized(): Promise<void> {
    if (this.initPromise) return this.initPromise;
    this.initPromise = this.init();
    return this.initPromise;
  }

  listInfo(): PluginInfo[] {
    return Array.from(this.runtimes.values()).map((runtime) => this.infoOf(runtime));
  }

  getRuntime(id: string): PluginRuntime | undefined {
    return this.runtimes.get(id);
  }

  markFailed(id: string, code: string, message: string): void {
    const runtime = this.runtimes.get(id);
    if (!runtime) return;
    runtime.sandbox?.dispose();
    runtime.sandbox = null;
    this.setStatus(runtime, {
      state: "error",
      error: {
        code,
        message,
      },
    });
  }

  async install(filePath: string): Promise<PluginInfo> {
    await this.ensureInitialized();
    const raw = fs.readFileSync(filePath, "utf8");
    return this.installFromSource(raw);
  }

  async installFromSource(raw: string): Promise<PluginInfo> {
    await this.ensureInitialized();
    ensureDirs();
    const { source, manifest } = loadScript(raw, false);
    const fileName = scriptFileNameForId(manifest.id);
    fs.writeFileSync(scriptPathForFileName(fileName), source, "utf8");
    manifest.fileName = fileName;

    const stored = readStored();
    stored.plugins[manifest.id] = manifest;
    writeStored(stored);

    const enabledMap = {
      ...this.getEnabledMap(),
      [manifest.id]: true,
    };
    setSystemConfigValue("plugins.enabled", enabledMap);

    const existing = this.runtimes.get(manifest.id);
    if (existing) await this.stop(existing);

    const runtime: PluginRuntime = {
      manifest,
      enabled: true,
      status: { state: "unloaded" },
      loading: false,
      source,
      sandbox: null,
      updateInfo: null,
      events: [],
      controls: false,
      settings: [],
      menus: [],
    };
    this.runtimes.set(manifest.id, runtime);
    await this.start(runtime);
    return this.infoOf(runtime);
  }

  async installFromUrl(url: string): Promise<PluginInfo> {
    const source = await fetchScript(url);
    return this.installFromSource(source);
  }

  async uninstall(id: string): Promise<void> {
    await this.ensureInitialized();
    const runtime = this.runtimes.get(id);
    if (!runtime) return;
    await this.stop(runtime);
    this.runtimes.delete(id);

    const stored = readStored();
    delete stored.plugins[id];
    writeStored(stored);

    try {
      fs.unlinkSync(scriptPathForFileName(runtime.manifest.fileName));
    } catch {
      /* ignore */
    }
    pluginStorageDrop(id);

    const enabledMap = { ...this.getEnabledMap() };
    delete enabledMap[id];
    setSystemConfigValue("plugins.enabled", enabledMap);
  }

  async setEnabled(id: string, enabled: boolean): Promise<void> {
    await this.ensureInitialized();
    const runtime = this.runtimes.get(id);
    if (!runtime) return;
    runtime.enabled = enabled;
    setSystemConfigValue("plugins.enabled", {
      ...this.getEnabledMap(),
      [id]: enabled,
    });
    if (enabled) {
      if (runtime.status.state !== "ready") await this.start(runtime);
    } else {
      await this.stop(runtime);
      this.setStatus(runtime, { state: "disabled" });
    }
  }

  async setSetting(id: string, key: string, value: unknown): Promise<void> {
    await this.ensureInitialized();
    const runtime = this.runtimes.get(id);
    if (!runtime) return;
    const item = runtime.settings.find((setting) => setting.key === key);
    if (!item) return;
    const sanitized = sanitizeSettingValue(item, value);
    const current = {
      ...((getSystemConfigValue(`plugins.perPlugin.${id}`) as Record<string, unknown>) ?? {}),
      [key]: sanitized,
    };
    setSystemConfigValue(`plugins.perPlugin.${id}`, current);
    runtime.sandbox?.sendSettingsUpdate({ [key]: sanitized });
    this.emit("status", this.infoOf(runtime));
  }

  async shutdown(): Promise<void> {
    await this.ensureInitialized();
    await Promise.all(Array.from(this.runtimes.values()).map((runtime) => this.stop(runtime)));
  }

  private async init(): Promise<void> {
    ensureDirs();
    const stored = readStored();
    const enabledMap = this.getEnabledMap();

    for (const [id, manifest] of Object.entries(stored.plugins)) {
      try {
        const scriptPath = scriptPathForFileName(manifest.fileName);
        const raw = fs.readFileSync(scriptPath, "utf8");
        const loaded = loadScript(raw, false, manifest.fileName);
        const runtime: PluginRuntime = {
          manifest: {
            ...manifest,
            grant: loaded.manifest.grant,
          },
          enabled: enabledMap[id] ?? true,
          status: { state: "unloaded" },
          loading: false,
          source: loaded.source,
          sandbox: null,
          updateInfo: null,
          events: [],
          controls: false,
          settings: [],
          menus: [],
        };
        this.runtimes.set(id, runtime);
      } catch (error) {
        console.warn(`[embedded-api] 读取插件 ${manifest.fileName} 失败`, error);
      }
    }

    await Promise.allSettled(
      Array.from(this.runtimes.values())
        .filter((runtime) => runtime.enabled)
        .map((runtime) => this.start(runtime)),
    );
  }

  private async start(runtime: PluginRuntime): Promise<void> {
    if (runtime.loading || runtime.sandbox?.isAlive()) return;
    if (runtime.manifest.type === "control") {
      this.setStatus(runtime, {
        state: "error",
        error: {
          code: PluginErrorCodes.UNKNOWN,
          message: "Android 第一阶段暂不支持 control 插件",
        },
      });
      return;
    }
    runtime.loading = true;
    this.setStatus(runtime, { state: "loading" });

    const sandbox = new AndroidPluginSandbox(
      {
        pluginId: runtime.manifest.id,
        grant: runtime.manifest.grant,
        apiLevel: runtime.manifest.apiLevel,
        locale: (getSystemConfigValue("system.locale") as string | undefined) ?? "zh-CN",
        appVersion: process.env["SP_APP_VERSION"] || "1.0.0",
        userSettings:
          (getSystemConfigValue(`plugins.perPlugin.${runtime.manifest.id}`) as
            Record<string, unknown> | undefined) ?? {},
        source: runtime.source,
        scriptInfo: {
          name: runtime.manifest.name,
          description: runtime.manifest.description ?? "",
          version: runtime.manifest.version,
          author: runtime.manifest.author ?? "",
          homepage: runtime.manifest.homepage ?? "",
        },
      },
      {
        onReady: (sources) => {
          this.setStatus(runtime, {
            state: "ready",
            sources,
            events: runtime.events,
            controls: runtime.controls,
            settings: runtime.settings,
            menus: [],
          });
        },
        onRegistered: ({ events, controls, settings, menus: _menus }) => {
          runtime.events = events;
          runtime.controls = controls;
          runtime.settings = settings;
          runtime.menus = [];
          if (runtime.status.state === "ready") {
            this.setStatus(runtime, {
              ...runtime.status,
              events,
              controls,
              settings,
              menus: [],
            });
          }
        },
        onSourcesUpdate: (sources) => {
          runtime.status =
            runtime.status.state === "ready"
              ? {
                  ...runtime.status,
                  sources,
                }
              : runtime.status;
          this.emit("status", this.infoOf(runtime));
        },
        onUpdateAvailable: (info) => {
          runtime.updateInfo = info;
          this.emit("status", this.infoOf(runtime));
        },
        onLog: (level, args) => {
          const logger =
            level === "error" ? console.error : level === "warn" ? console.warn : console.info;
          logger(`[plugin:${runtime.manifest.id}]`, ...args);
        },
      },
    );

    runtime.sandbox = sandbox;
    try {
      await sandbox.load();
    } catch (error) {
      runtime.sandbox = null;
      this.setStatus(runtime, {
        state: "error",
        error: {
          code: ((error as { code?: string })?.code as string) ?? PluginErrorCodes.UNKNOWN,
          message: error instanceof Error ? error.message : String(error),
        },
      });
    } finally {
      runtime.loading = false;
    }
  }

  private async stop(runtime: PluginRuntime): Promise<void> {
    runtime.loading = false;
    runtime.sandbox?.dispose();
    runtime.sandbox = null;
    this.setStatus(runtime, { state: "unloaded" });
  }

  private setStatus(runtime: PluginRuntime, status: PluginStatus): void {
    runtime.status = status;
    this.emit("status", this.infoOf(runtime));
  }

  private infoOf(runtime: PluginRuntime): PluginInfo {
    return {
      manifest: runtime.manifest,
      enabled: runtime.enabled,
      status: runtime.status,
      updateInfo: runtime.updateInfo,
      settingsValues:
        (getSystemConfigValue(`plugins.perPlugin.${runtime.manifest.id}`) as
          Record<string, unknown> | undefined) ?? {},
    };
  }

  private getEnabledMap(): Record<string, boolean> {
    const pluginsConfig = getSystemConfigStore().plugins;
    return { ...(pluginsConfig.enabled ?? {}) };
  }

  override emit(eventName: string | symbol, ...args: unknown[]): boolean {
    const result = super.emit(eventName, ...args);
    if (eventName === "status" && args[0]) {
      this.recordWatchEvent(args[0] as PluginInfo);
    }
    return result;
  }

  /**
   * 记录一次插件状态事件，递增 seq 并写入环形缓冲，随后唤醒所有长轮询等待者。
   * @param info - 本次状态变更对应的 PluginInfo 快照
   */
  private recordWatchEvent(info: PluginInfo): void {
    this.watchSeq += 1;
    this.watchBuffer.push({ seq: this.watchSeq, info });
    if (this.watchBuffer.length > AndroidPluginRegistry.WATCH_BUFFER_LIMIT) {
      this.watchBuffer.shift();
    }
    this.flushWatchWaiters();
  }

  /**
   * 唤醒所有等待中的长轮询，返回其对应游标后的增量事件。
   */
  private flushWatchWaiters(): void {
    if (this.watchWaiters.length === 0) return;
    const waiters = [...this.watchWaiters];
    this.watchWaiters.length = 0;
    for (const waiter of waiters) {
      clearTimeout(waiter.timer);
      const result = this.collectWatchEventsSince(waiter.sinceCursor);
      // reset 场景下返回 reset 标记对应的最新 cursor，前端会走全量 list
      if ("reset" in result) {
        waiter.resolve({ events: [], cursor: result.cursor });
        continue;
      }
      waiter.resolve(result);
    }
  }

  /**
   * 获取当前 watch 游标（单调递增 seq）。
   * @returns 当前最新 seq
   */
  getWatchCursor(): number {
    return this.watchSeq;
  }

  /**
   * 按游标收集增量事件，用于 watch 路由的即时返回判断。
   * @param sinceCursor - 客户端上次拿到的 cursor
   * @returns 增量事件与最新 cursor；若游标过旧/未知则返回 reset 标记
   */
  getWatchEventsSince(
    sinceCursor: number,
  ): { events: PluginInfo[]; cursor: number } | { reset: true; cursor: number } {
    return this.collectWatchEventsSince(sinceCursor);
  }

  private collectWatchEventsSince(
    sinceCursor: number,
  ): { events: PluginInfo[]; cursor: number } | { reset: true; cursor: number } {
    // 非数字或负数按 0 处理的上游已保证，这里做二次防御
    if (!Number.isFinite(sinceCursor) || sinceCursor < 0) sinceCursor = 0;
    // 未知游标（客户端超前）需要重置
    if (sinceCursor > this.watchSeq) {
      return { reset: true, cursor: this.watchSeq };
    }
    if (this.watchSeq === 0) {
      return { events: [], cursor: 0 };
    }
    // 空缓冲但 seq 已推进（理论上仅 seq>0 且缓冲被清空时）→ 视为过期
    if (this.watchBuffer.length === 0) {
      return sinceCursor === this.watchSeq
        ? { events: [], cursor: this.watchSeq }
        : { reset: true, cursor: this.watchSeq };
    }
    const oldestSeq = this.watchBuffer[0].seq;
    // 游标早于缓冲最早记录 → 已过期，无法补增量
    if (sinceCursor < oldestSeq - 1) {
      return { reset: true, cursor: this.watchSeq };
    }
    // 特殊：sinceCursor 恰好等于 oldestSeq-1 时仍可通过缓冲返回完整增量
    // 其余情况过滤 seq > sinceCursor 的事件
    const events = this.watchBuffer
      .filter((entry) => entry.seq > sinceCursor)
      .map((entry) => entry.info);
    return { events, cursor: this.watchSeq };
  }

  /**
   * 长轮询等待下一次插件状态变更。
   * 硬约束：超时必须 ≤25000ms（Kotlin 代理读超时 30000ms，超时会直接掐连接）。
   * @param sinceCursor - 客户端当前 cursor
   * @param timeoutMs - 等待超时，默认 25000ms，必须 ≤25000
   * @returns 增量事件与最新 cursor；超时返回空数组且 cursor 保持原值
   */
  waitForChange(
    sinceCursor: number,
    timeoutMs = 25000,
  ): Promise<{ events: PluginInfo[]; cursor: number }> {
    // 钳制超时，防止误传 >25000 触发 Kotlin 30s 掐连接
    const cappedTimeout = Math.min(Math.max(0, timeoutMs), 25000);
    if (!Number.isFinite(sinceCursor) || sinceCursor < 0) sinceCursor = 0;
    else sinceCursor = Math.floor(sinceCursor);

    const immediate = this.collectWatchEventsSince(sinceCursor);
    if ("reset" in immediate) {
      // 调用方应按 reset 处理走全量 list；此处直接返回空增量+最新 cursor 避免挂起
      return Promise.resolve({ events: [], cursor: immediate.cursor });
    }
    if (immediate.events.length > 0) {
      return Promise.resolve(immediate);
    }

    return new Promise<{ events: PluginInfo[]; cursor: number }>((resolve) => {
      const timer = setTimeout(() => {
        // 超时即空轮：返回空数组且 cursor 保持原值，前端将发起下一轮 watch
        // 硬约束：25s 超时 < Kotlin 30s 代理读超时，避免连接被服务端掐断
        const idx = this.watchWaiters.findIndex((w) => w.resolve === resolve);
        if (idx !== -1) this.watchWaiters.splice(idx, 1);
        resolve({ events: [], cursor: sinceCursor });
      }, cappedTimeout);
      // 防止 timer 阻止进程退出
      const maybeUnrefTimer = timer as NodeJS.Timeout;
      if (typeof maybeUnrefTimer.unref === "function") {
        maybeUnrefTimer.unref();
      }
      this.watchWaiters.push({ sinceCursor, resolve, timer });
    });
  }
}

export const androidPluginRegistry = new AndroidPluginRegistry();
