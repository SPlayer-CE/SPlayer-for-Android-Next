import crypto from "node:crypto";
import vm from "node:vm";
import zlib from "node:zlib";
import ts from "typescript";
import type {
  ActionIO,
  HostApi,
  HostCallMethod,
  HostRequestOptions,
  HostRequestResult,
  PluginAction,
  PluginGrant,
  PluginRegistration,
  PluginUpdateInfo,
  RegisterArgs,
  SourceCapability,
} from "../../shared/types/plugin";
import { PluginErrorCodes } from "../../shared/defaults/plugin-api";
import { installLxShim } from "../../electron/main/plugins/lx-shim";
import { dispatchAndroidHostCall } from "./android-host-dispatch";

interface AndroidPluginLoadSpec {
  pluginId: string;
  grant: PluginGrant[];
  apiLevel: number;
  locale: string;
  appVersion: string;
  userSettings: Record<string, unknown>;
  source: string;
  scriptInfo: {
    name: string;
    description: string;
    version: string;
    author: string;
    homepage: string;
  };
}

interface AndroidPluginSandboxCallbacks {
  onReady: (sources: Record<string, SourceCapability>) => void;
  onRegistered: (registration: PluginRegistration) => void;
  onSourcesUpdate: (sources: Record<string, SourceCapability>) => void;
  onUpdateAvailable: (info: PluginUpdateInfo) => void;
  onLog: (level: "debug" | "info" | "warn" | "error", args: unknown[]) => void;
}

interface PluginRuntimeRecord {
  handlers: Map<PluginAction, (req: unknown) => Promise<unknown>>;
  playerEventHandlers: Map<string, Array<(data: unknown) => void>>;
  settingChangeHandlers: Map<string, Array<(value: unknown) => void>>;
  registeredSources: Record<string, SourceCapability>;
  userSettingsCache: Record<string, unknown>;
  timers: Set<NodeJS.Timeout>;
  immediates: Set<NodeJS.Immediate>;
  consoleGroupDepth: number;
  disposed: boolean;
}

type FetchHeadersInit =
  SandboxHeaders | Record<string, string> | Array<[string, string]> | undefined;

type FetchBodyInit = string | ArrayBuffer | Uint8Array | URLSearchParams | undefined;

interface SandboxFetchRequestInit {
  method?: string;
  headers?: FetchHeadersInit;
  body?: FetchBodyInit;
}

class SandboxHeaders {
  private readonly store = new Map<string, string>();

  constructor(init?: FetchHeadersInit) {
    if (!init) return;
    if (init instanceof SandboxHeaders) {
      init.forEach((value, key) => this.set(key, value));
      return;
    }
    if (Array.isArray(init)) {
      for (const [key, value] of init) this.append(key, value);
      return;
    }
    for (const [key, value] of Object.entries(init)) {
      if (value != null) this.set(key, String(value));
    }
  }

  append(key: string, value: string): void {
    const lower = key.toLowerCase();
    const prev = this.store.get(lower);
    this.store.set(lower, prev ? `${prev}, ${value}` : value);
  }

  delete(key: string): void {
    this.store.delete(key.toLowerCase());
  }

  get(key: string): string | null {
    return this.store.get(key.toLowerCase()) ?? null;
  }

  has(key: string): boolean {
    return this.store.has(key.toLowerCase());
  }

  set(key: string, value: string): void {
    this.store.set(key.toLowerCase(), value);
  }

  forEach(
    callback: (value: string, key: string, parent: SandboxHeaders) => void,
    thisArg?: unknown,
  ): void {
    for (const [key, value] of this.store.entries()) {
      callback.call(thisArg, value, key, this);
    }
  }

  entries(): IterableIterator<[string, string]> {
    return this.store.entries();
  }

  keys(): IterableIterator<string> {
    return this.store.keys();
  }

  values(): IterableIterator<string> {
    return this.store.values();
  }

  [Symbol.iterator](): IterableIterator<[string, string]> {
    return this.entries();
  }

  toObject(): Record<string, string> {
    return Object.fromEntries(this.store.entries());
  }
}

class SandboxRequest {
  readonly url: string;
  readonly method: string;
  readonly headers: SandboxHeaders;
  readonly body?: FetchBodyInit;

  constructor(input: string | SandboxRequest, init: SandboxFetchRequestInit = {}) {
    if (input instanceof SandboxRequest) {
      this.url = input.url;
      this.method = init.method ?? input.method;
      this.headers = new SandboxHeaders(init.headers ?? input.headers);
      this.body = init.body ?? input.body;
      return;
    }
    this.url = input;
    this.method = (init.method ?? "GET").toUpperCase();
    this.headers = new SandboxHeaders(init.headers);
    this.body = init.body;
  }

  clone(): SandboxRequest {
    return new SandboxRequest(this);
  }
}

class SandboxResponse {
  readonly status: number;
  readonly ok: boolean;
  readonly redirected: boolean;
  readonly headers: SandboxHeaders;
  readonly statusText: string;
  readonly url: string;

  constructor(
    private readonly payload: Uint8Array,
    init: {
      status: number;
      headers?: Record<string, string>;
      redirected?: boolean;
      statusText?: string;
      url?: string;
    },
  ) {
    this.status = init.status;
    this.ok = this.status >= 200 && this.status < 300;
    this.redirected = Boolean(init.redirected);
    this.headers = new SandboxHeaders(init.headers);
    this.statusText = init.statusText ?? "";
    this.url = init.url ?? "";
  }

  async text(): Promise<string> {
    return Buffer.from(this.payload).toString("utf8");
  }

  async json(): Promise<unknown> {
    return JSON.parse(await this.text());
  }

  async arrayBuffer(): Promise<ArrayBuffer> {
    const view = this.payload;
    return view.buffer.slice(view.byteOffset, view.byteOffset + view.byteLength) as ArrayBuffer;
  }

  clone(): SandboxResponse {
    return new SandboxResponse(this.payload.slice(), {
      status: this.status,
      headers: this.headers.toObject(),
      redirected: this.redirected,
      statusText: this.statusText,
      url: this.url,
    });
  }
}

const sanitizeForHost = (value: unknown, depth = 0): unknown => {
  if (depth > 6) return null;
  if (value == null) return value;
  const valueType = typeof value;
  if (valueType === "string" || valueType === "number" || valueType === "boolean") {
    return value;
  }
  if (valueType === "bigint") return String(value);
  if (valueType === "function" || valueType === "symbol") return undefined;
  if (Buffer.isBuffer(value)) return new Uint8Array(value);
  if (value instanceof Uint8Array || value instanceof ArrayBuffer) return value;
  if (Array.isArray(value)) {
    return value
      .map((item) => sanitizeForHost(item, depth + 1))
      .filter((item) => item !== undefined);
  }
  if (valueType === "object") {
    const out: Record<string, unknown> = Object.create(null);
    try {
      for (const key of Object.keys(value as object)) {
        const cleaned = sanitizeForHost((value as Record<string, unknown>)[key], depth + 1);
        if (cleaned !== undefined) out[key] = cleaned;
      }
    } catch {
      return {};
    }
    return out;
  }
  return undefined;
};

const makeTimerApi = (record: PluginRuntimeRecord): Record<string, unknown> => ({
  setTimeout: (callback: (...args: unknown[]) => void, delay?: number, ...args: unknown[]) => {
    const handle = setTimeout(() => {
      record.timers.delete(handle);
      callback(...args);
    }, delay);
    record.timers.add(handle);
    return handle;
  },
  setInterval: (callback: (...args: unknown[]) => void, delay?: number, ...args: unknown[]) => {
    const handle = setInterval(callback, delay, ...args);
    record.timers.add(handle);
    return handle;
  },
  clearTimeout: (handle?: NodeJS.Timeout): void => {
    if (handle) record.timers.delete(handle);
    clearTimeout(handle);
  },
  clearInterval: (handle?: NodeJS.Timeout): void => {
    if (handle) record.timers.delete(handle);
    clearInterval(handle);
  },
  setImmediate: (callback: (...args: unknown[]) => void, ...args: unknown[]) => {
    const handle = setImmediate(() => {
      record.immediates.delete(handle);
      callback(...args);
    });
    record.immediates.add(handle);
    return handle;
  },
  clearImmediate: (handle?: NodeJS.Immediate): void => {
    if (handle) record.immediates.delete(handle);
    clearImmediate(handle);
  },
});

const buildUtils = (): object => ({
  crypto: {
    md5: (data: string | Uint8Array) =>
      crypto
        .createHash("md5")
        .update(data as crypto.BinaryLike)
        .digest("hex"),
    sha1: (data: string | Uint8Array) =>
      crypto
        .createHash("sha1")
        .update(data as crypto.BinaryLike)
        .digest("hex"),
    sha256: (data: string | Uint8Array) =>
      crypto
        .createHash("sha256")
        .update(data as crypto.BinaryLike)
        .digest("hex"),
    hmac: (algorithm: string, key: string | Uint8Array, data: string | Uint8Array) =>
      crypto
        .createHmac(algorithm, key as crypto.BinaryLike)
        .update(data as crypto.BinaryLike)
        .digest("hex"),
    randomBytes: (size: number) => crypto.randomBytes(size),
    aesEncrypt: (
      data: string | Uint8Array,
      key: Buffer | Uint8Array,
      mode: string,
      iv?: Buffer | Uint8Array,
    ) => {
      const cipher = crypto.createCipheriv(mode, key as crypto.CipherKey, iv ?? null);
      const input = typeof data === "string" ? Buffer.from(data, "utf8") : Buffer.from(data);
      return Buffer.concat([cipher.update(input), cipher.final()]);
    },
    aesDecrypt: (
      data: Buffer | Uint8Array,
      key: Buffer | Uint8Array,
      mode: string,
      iv?: Buffer | Uint8Array,
    ) => {
      const decipher = crypto.createDecipheriv(mode, key as crypto.CipherKey, iv ?? null);
      return Buffer.concat([decipher.update(Buffer.from(data)), decipher.final()]);
    },
    rsaEncrypt: (data: Buffer | Uint8Array, publicKey: string) =>
      crypto.publicEncrypt(publicKey, Buffer.from(data)),
  },
  buffer: {
    from: (
      data: ArrayBuffer | SharedArrayBuffer | string | Uint8Array | number[],
      encoding?: BufferEncoding,
    ) =>
      typeof data === "string" ? Buffer.from(data, encoding) : Buffer.from(data as ArrayBuffer),
    bufToString: (buffer: Buffer | Uint8Array, encoding: BufferEncoding = "utf8") =>
      Buffer.from(buffer).toString(encoding),
    concat: (list: Array<Buffer | Uint8Array>) => Buffer.concat(list),
  },
  base64: {
    encode: (data: string | Uint8Array) => Buffer.from(data as Buffer).toString("base64"),
    decode: (data: string) => Buffer.from(data, "base64").toString("utf8"),
  },
  zlib: {
    inflate: (data: Buffer | Uint8Array) => zlib.inflateSync(data),
    deflate: (data: Buffer | Uint8Array) => zlib.deflateSync(data),
    gunzip: (data: Buffer | Uint8Array) => zlib.gunzipSync(data),
    gzip: (data: Buffer | Uint8Array) => zlib.gzipSync(data),
  },
});

const createSandboxConsole = (
  record: PluginRuntimeRecord,
  onLog: AndroidPluginSandboxCallbacks["onLog"],
): Record<string, (...args: unknown[]) => void> => {
  const prefix = (): string => "  ".repeat(Math.max(record.consoleGroupDepth, 0));
  const formatArgs = (args: unknown[]): unknown[] => (prefix() ? [prefix(), ...args] : args);
  return {
    log: (...args: unknown[]) => onLog("info", formatArgs(args)),
    info: (...args: unknown[]) => onLog("info", formatArgs(args)),
    debug: (...args: unknown[]) => onLog("debug", formatArgs(args)),
    warn: (...args: unknown[]) => onLog("warn", formatArgs(args)),
    error: (...args: unknown[]) => onLog("error", formatArgs(args)),
    trace: (...args: unknown[]) => onLog("debug", formatArgs(args)),
    table: (...args: unknown[]) => onLog("info", formatArgs(args)),
    group: (...args: unknown[]) => {
      if (args.length > 0) onLog("info", formatArgs(args));
      record.consoleGroupDepth += 1;
    },
    groupCollapsed: (...args: unknown[]) => {
      if (args.length > 0) onLog("info", formatArgs(args));
      record.consoleGroupDepth += 1;
    },
    groupEnd: () => {
      record.consoleGroupDepth = Math.max(0, record.consoleGroupDepth - 1);
    },
    assert: (condition: unknown, ...args: unknown[]) => {
      if (condition) return;
      onLog("error", formatArgs(args.length > 0 ? args : ["Assertion failed"]));
    },
  };
};

const toHostRequestBody = (body: FetchBodyInit): string | ArrayBuffer | Uint8Array | undefined => {
  if (body == null) return undefined;
  if (typeof body === "string") return body;
  if (body instanceof URLSearchParams) return body.toString();
  if (body instanceof Uint8Array || body instanceof ArrayBuffer) return body;
  return String(body);
};

const transpilePluginSourceForLegacyVm = (source: string, pluginId: string): string => {
  const result = ts.transpileModule(source, {
    compilerOptions: {
      allowJs: true,
      target: ts.ScriptTarget.ES2019,
      module: ts.ModuleKind.None,
      importHelpers: false,
      downlevelIteration: false,
      removeComments: false,
      newLine: ts.NewLineKind.LineFeed,
    },
    fileName: `plugin-${pluginId}.js`,
    reportDiagnostics: false,
  });
  return result.outputText || source;
};

type SandboxAggregateError = new (
  errors: unknown[],
  message?: string,
) => Error & {
  errors: unknown[];
};

/**
 * 补齐 nodejs-mobile（Node 12.19）缺失的 Promise.any / AggregateError
 * LX 脚本常用 Promise.any 做多源回退；语法降级无法覆盖运行时 API，需在此补齐
 * @returns 注入沙箱的 Promise 与 AggregateError 构造器
 */
const ensureModernPromiseApi = (): {
  Promise: PromiseConstructor;
  AggregateError: SandboxAggregateError;
} => {
  const g = globalThis as unknown as {
    Promise: PromiseConstructor & {
      any?: <T>(values: Iterable<T | PromiseLike<T>>) => Promise<T>;
    };
    AggregateError?: SandboxAggregateError;
  };

  if (typeof g.Promise.any !== "function") {
    class AggregateErrorPolyfill extends Error {
      errors: unknown[];

      constructor(errors: unknown[], message = "All promises were rejected") {
        super(message);
        this.name = "AggregateError";
        this.errors = errors;
      }
    }

    const AggregateErrorCtor =
      typeof g.AggregateError === "function" ? g.AggregateError : AggregateErrorPolyfill;
    g.AggregateError = AggregateErrorCtor;

    g.Promise.any = <T>(values: Iterable<T | PromiseLike<T>>): Promise<T> =>
      new Promise<T>((resolve, reject) => {
        const items = Array.from(values);
        if (items.length === 0) {
          reject(new AggregateErrorCtor([], "All promises were rejected"));
          return;
        }
        const errors = new Array<unknown>(items.length);
        let remaining = items.length;
        let settled = false;
        items.forEach((item, index) => {
          Promise.resolve(item).then(
            (value) => {
              if (settled) return;
              settled = true;
              resolve(value);
            },
            (reason) => {
              if (settled) return;
              errors[index] = reason;
              remaining -= 1;
              if (remaining === 0) {
                settled = true;
                reject(new AggregateErrorCtor(errors, "All promises were rejected"));
              }
            },
          );
        });
      });
  }

  return { Promise: g.Promise, AggregateError: g.AggregateError as SandboxAggregateError };
};

const disposeRecord = (record: PluginRuntimeRecord): void => {
  if (record.disposed) return;
  record.disposed = true;
  for (const handle of record.timers) clearTimeout(handle);
  record.timers.clear();
  for (const handle of record.immediates) clearImmediate(handle);
  record.immediates.clear();
  record.handlers.clear();
  record.playerEventHandlers.clear();
  record.settingChangeHandlers.clear();
};

export class AndroidPluginSandbox {
  private readonly record: PluginRuntimeRecord;

  constructor(
    private readonly spec: AndroidPluginLoadSpec,
    private readonly callbacks: AndroidPluginSandboxCallbacks,
  ) {
    this.record = {
      handlers: new Map(),
      playerEventHandlers: new Map(),
      settingChangeHandlers: new Map(),
      registeredSources: {},
      userSettingsCache: { ...(spec.userSettings ?? {}) },
      timers: new Set(),
      immediates: new Set(),
      consoleGroupDepth: 0,
      disposed: false,
    };
  }

  async load(): Promise<Record<string, SourceCapability>> {
    const splayer = this.buildSplayer();
    (splayer as unknown as Record<string, unknown>)["utils"] = buildUtils();
    const fetch = async (
      input: string | SandboxRequest,
      init?: SandboxFetchRequestInit,
    ): Promise<SandboxResponse> => {
      const request = new SandboxRequest(input, init);
      const response = (await this.hostCall("request", [
        request.url,
        {
          method: request.method as HostRequestOptions["method"],
          headers: request.headers.toObject(),
          body: toHostRequestBody(request.body),
          responseType: "arraybuffer",
        },
      ])) as HostRequestResult;
      const payload =
        response.body instanceof Uint8Array
          ? response.body
          : typeof response.body === "string"
            ? new Uint8Array(Buffer.from(response.body, "utf8"))
            : new Uint8Array();
      return new SandboxResponse(payload, {
        status: response.status,
        headers: response.headers,
        redirected: response.redirected,
        statusText: response.statusText,
        url: response.url,
      });
    };

    const { Promise: sandboxPromise, AggregateError } = ensureModernPromiseApi();

    const sandboxGlobal: Record<string, unknown> = {
      splayer,
      Buffer,
      ...makeTimerApi(this.record),
      queueMicrotask,
      Promise: sandboxPromise,
      AggregateError,
      URL,
      URLSearchParams,
      TextEncoder,
      TextDecoder,
      navigator: {
        userAgent: "SPlayer-Next-Android",
        platform: "Android",
      },
      Headers: SandboxHeaders,
      Request: SandboxRequest,
      Response: SandboxResponse,
      fetch,
      btoa: (value: string): string => Buffer.from(value, "binary").toString("base64"),
      atob: (value: string): string => Buffer.from(value, "base64").toString("binary"),
      console: createSandboxConsole(this.record, this.callbacks.onLog),
    };

    installLxShim(
      sandboxGlobal,
      splayer,
      this.record.handlers,
      (sources) => {
        this.record.registeredSources = { ...this.record.registeredSources, ...sources };
        this.callbacks.onSourcesUpdate(this.record.registeredSources);
      },
      (info) => {
        this.callbacks.onUpdateAvailable(info);
      },
      {
        name: this.spec.scriptInfo.name,
        description: this.spec.scriptInfo.description,
        version: this.spec.scriptInfo.version,
        author: this.spec.scriptInfo.author,
        homepage: this.spec.scriptInfo.homepage,
        rawScript: this.spec.source,
      },
    );

    const existingWindow = sandboxGlobal.window;
    sandboxGlobal.window = sandboxGlobal;
    if (existingWindow && typeof existingWindow === "object") {
      for (const [key, value] of Object.entries(existingWindow)) {
        sandboxGlobal[key] = value;
      }
    }

    sandboxGlobal.globalThis = sandboxGlobal;
    sandboxGlobal.self = sandboxGlobal;
    const context = vm.createContext(sandboxGlobal, {
      name: `plugin:${this.spec.pluginId}`,
      codeGeneration: { strings: true, wasm: false },
    });

    try {
      const compiledSource = transpilePluginSourceForLegacyVm(this.spec.source, this.spec.pluginId);
      const script = new vm.Script(compiledSource, {
        filename: `plugin-${this.spec.pluginId}.js`,
      });
      script.runInContext(context, { timeout: 5_000, breakOnSigint: false });
    } catch (error) {
      disposeRecord(this.record);
      throw Object.assign(
        new Error(
          error instanceof Error ? `${error.message}\n${error.stack ?? ""}` : String(error),
        ),
        { code: PluginErrorCodes.SCRIPT_ERROR },
      );
    }

    await Promise.resolve();
    if (this.record.disposed) {
      throw Object.assign(new Error("plugin disposed"), { code: PluginErrorCodes.NOT_READY });
    }
    this.callbacks.onReady(this.record.registeredSources);
    return this.record.registeredSources;
  }

  async call(action: PluginAction, params: unknown): Promise<unknown> {
    if (this.record.disposed) {
      throw Object.assign(new Error("plugin is not ready"), { code: PluginErrorCodes.NOT_READY });
    }
    const handler = this.record.handlers.get(action);
    if (!handler) {
      throw Object.assign(new Error(`action "${action}" not registered`), {
        code: PluginErrorCodes.ACTION_UNSUPPORTED,
      });
    }
    try {
      const result = await handler(params);
      return sanitizeForHost(result);
    } catch (error) {
      throw Object.assign(new Error(error instanceof Error ? error.message : String(error)), {
        code: ((error as { code?: string })?.code as string) ?? PluginErrorCodes.HANDLER_ERROR,
      });
    }
  }

  sendSettingsUpdate(settings: Record<string, unknown>): void {
    for (const [key, value] of Object.entries(settings)) {
      this.record.userSettingsCache[key] = value;
      const handlers = this.record.settingChangeHandlers.get(key) ?? [];
      for (const handler of handlers) {
        try {
          handler(value);
        } catch {
          /* ignore */
        }
      }
    }
  }

  dispose(): void {
    disposeRecord(this.record);
  }

  isAlive(): boolean {
    return !this.record.disposed;
  }

  private buildSplayer(): HostApi {
    return {
      pluginId: this.spec.pluginId,
      apiLevel: this.spec.apiLevel,
      locale: this.spec.locale,
      appVersion: this.spec.appVersion,
      request: (url: string, options?: HostRequestOptions): Promise<HostRequestResult> =>
        this.hostCall("request", [url, options ?? {}]) as Promise<HostRequestResult>,
      register: (args: RegisterArgs) => {
        if (args.sources) {
          this.record.registeredSources = {
            ...this.record.registeredSources,
            ...args.sources,
          };
          this.callbacks.onSourcesUpdate(this.record.registeredSources);
        }
        if (args.events || args.controls !== undefined || args.settings || args.menus) {
          if (Array.isArray(args.settings)) {
            for (const item of args.settings) {
              if (!(item.key in this.record.userSettingsCache)) {
                this.record.userSettingsCache[item.key] = item.default;
              }
            }
          }
          this.callbacks.onRegistered({
            events: Array.isArray(args.events) ? args.events : [],
            controls: Boolean(args.controls),
            settings: Array.isArray(args.settings) ? args.settings : [],
            menus: Array.isArray(args.menus) ? args.menus : [],
          });
        }
      },
      on: <A extends PluginAction>(
        action: A,
        handler: (req: ActionIO[A]["req"]) => Promise<ActionIO[A]["res"]>,
      ) => {
        this.record.handlers.set(action, handler as (req: unknown) => Promise<unknown>);
      },
      log: {
        debug: (...args: unknown[]) => this.callbacks.onLog("debug", args),
        info: (...args: unknown[]) => this.callbacks.onLog("info", args),
        warn: (...args: unknown[]) => this.callbacks.onLog("warn", args),
        error: (...args: unknown[]) => this.callbacks.onLog("error", args),
      },
      storage: {
        get: <T = unknown>(key: string): Promise<T | null> =>
          this.hostCall("storage.get", [key]) as Promise<T | null>,
        set: (key: string, value: unknown) =>
          this.hostCall("storage.set", [key, value]).then(() => {}),
        remove: (key: string) => this.hostCall("storage.remove", [key]).then(() => {}),
        keys: () => this.hostCall("storage.keys", []) as Promise<string[]>,
      },
      getSetting: <T = unknown>(key: string): T | undefined =>
        this.record.userSettingsCache[key] as T | undefined,
      player: {
        on: (kind, handler) => {
          const list = this.record.playerEventHandlers.get(kind) ?? [];
          list.push(handler as (data: unknown) => void);
          this.record.playerEventHandlers.set(kind, list);
        },
        play: () => void this.hostCall("player.play", []).catch(() => {}),
        pause: () => void this.hostCall("player.pause", []).catch(() => {}),
        next: () => void this.hostCall("player.next", []).catch(() => {}),
        prev: () => void this.hostCall("player.prev", []).catch(() => {}),
        seek: (positionMs: number) =>
          void this.hostCall("player.seek", [positionMs]).catch(() => {}),
        setVolume: (volume: number) =>
          void this.hostCall("player.setVolume", [volume]).catch(() => {}),
        getPosition: () => this.hostCall("player.getPosition", []) as Promise<number>,
      },
      onSettingChange: (key: string, handler: (value: unknown) => void) => {
        const list = this.record.settingChangeHandlers.get(key) ?? [];
        list.push(handler);
        this.record.settingChangeHandlers.set(key, list);
      },
    };
  }

  private hostCall(method: HostCallMethod, args: unknown[]): Promise<unknown> {
    return dispatchAndroidHostCall(this.spec.pluginId, this.spec.grant, method, args);
  }
}
