import { mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync, existsSync } from "fs";
import { defaultSystemConfig } from "../shared/defaults/settings";
import type { SystemConfig } from "../shared/types/settings";
import { settingsFilePath } from "./runtime-paths";

type JsonObject = Record<string, unknown>;

const cloneJson = <T>(value: T): T => {
  if (value === undefined) return value;
  return JSON.parse(JSON.stringify(value)) as T;
};

const createPlainObject = (): JsonObject => Object.create(null) as JsonObject;

const isUnsafePathKey = (key: string): boolean =>
  key === "__proto__" || key === "constructor" || key === "prototype";

const getPathKeys = (keyPath: unknown): string[] | null => {
  if (typeof keyPath !== "string" || !keyPath.trim()) return null;
  const keys = keyPath.split(".").filter(Boolean);
  if (keys.length === 0 || keys.some(isUnsafePathKey)) return null;
  return keys;
};

const deepMerge = <T>(defaults: T, stored: unknown): T => {
  if (Array.isArray(defaults)) {
    return cloneJson(Array.isArray(stored) ? stored : defaults) as T;
  }
  if (typeof defaults !== "object" || defaults === null) {
    return (stored === undefined || stored === null ? defaults : stored) as T;
  }
  if (typeof stored !== "object" || stored === null || Array.isArray(stored)) {
    return cloneJson(defaults);
  }

  const result = createPlainObject();
  const defaultRecord = defaults as JsonObject;
  const storedRecord = stored as JsonObject;
  for (const key of Object.keys(defaultRecord)) {
    if (isUnsafePathKey(key)) continue;
    result[key] = deepMerge(defaultRecord[key], storedRecord[key]);
  }
  for (const key of Object.keys(storedRecord)) {
    if (isUnsafePathKey(key) || key in result) continue;
    result[key] = cloneJson(storedRecord[key]);
  }
  return result as T;
};

export const getByDotPath = (obj: unknown, keyPath: string): unknown => {
  const keys = getPathKeys(keyPath);
  if (!keys) return undefined;
  let current = obj as JsonObject | null | undefined;
  for (const key of keys) {
    if (current == null || typeof current !== "object") return undefined;
    current = current[key] as JsonObject | null | undefined;
  }
  return current;
};

export const setByDotPath = (obj: unknown, keyPath: string, value: unknown): boolean => {
  const keys = getPathKeys(keyPath);
  if (!keys) return false;
  let current = obj as JsonObject;
  for (let index = 0; index < keys.length - 1; index++) {
    const key = keys[index];
    if (current[key] == null || typeof current[key] !== "object" || Array.isArray(current[key])) {
      current[key] = createPlainObject();
    }
    current = current[key] as JsonObject;
  }
  current[keys[keys.length - 1]] = value;
  return true;
};

const readConfigFile = (): JsonObject => {
  try {
    const raw = readFileSync(settingsFilePath, "utf8");
    const parsed = JSON.parse(raw) as unknown;
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? (parsed as JsonObject)
      : {};
  } catch {
    return {};
  }
};

const atomicWriteJson = (filePath: string, payload: unknown): void => {
  const tempPath = `${filePath}.${process.pid}.${Date.now()}.tmp`;
  const serialized = `${JSON.stringify(payload, null, 2)}\n`;
  mkdirSync(pathDirname(filePath), { recursive: true });
  writeFileSync(tempPath, serialized, "utf8");
  try {
    renameSync(tempPath, filePath);
  } catch (firstError) {
    try {
      if (existsSync(filePath)) unlinkSync(filePath);
      renameSync(tempPath, filePath);
    } catch {
      try {
        writeFileSync(filePath, serialized, "utf8");
      } finally {
        try {
          if (existsSync(tempPath)) unlinkSync(tempPath);
        } catch {}
      }
      console.warn("[embedded-api] 原子写失败，已降级为覆盖写:", filePath, firstError);
    }
  }
};

const pathDirname = (filePath: string): string => {
  const normalized = filePath.replace(/[\\/]+$/, "");
  const slashIndex = Math.max(normalized.lastIndexOf("/"), normalized.lastIndexOf("\\"));
  return slashIndex >= 0 ? normalized.slice(0, slashIndex) : ".";
};

let systemConfigStore: SystemConfig | null = null;

export const getSystemConfigStore = (): SystemConfig => {
  if (!systemConfigStore) {
    systemConfigStore = deepMerge(defaultSystemConfig, readConfigFile());
  }
  return systemConfigStore;
};

export const writeSystemConfigStore = (): void => {
  atomicWriteJson(settingsFilePath, getSystemConfigStore());
};

export const replaceSystemConfigStore = (config: unknown): SystemConfig => {
  systemConfigStore = deepMerge(defaultSystemConfig, config);
  writeSystemConfigStore();
  return systemConfigStore;
};

export const resetSystemConfigStore = (keyPath?: unknown): boolean => {
  const keyPathText = typeof keyPath === "string" ? keyPath : "";
  if (!keyPathText) {
    systemConfigStore = cloneJson(defaultSystemConfig);
    writeSystemConfigStore();
    return true;
  }
  const defaultValue = getByDotPath(defaultSystemConfig, keyPathText);
  if (defaultValue === undefined) return false;
  const config = getSystemConfigStore();
  if (!setByDotPath(config, keyPathText, cloneJson(defaultValue))) return false;
  writeSystemConfigStore();
  return true;
};

export const setSystemConfigValue = (keyPath: string, value: unknown): boolean => {
  const config = getSystemConfigStore();
  if (!setByDotPath(config, keyPath, value)) return false;
  writeSystemConfigStore();
  return true;
};

export const getSystemConfigValue = <T = unknown>(keyPath: string): T | undefined =>
  getByDotPath(getSystemConfigStore(), keyPath) as T | undefined;
