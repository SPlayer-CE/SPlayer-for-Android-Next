import fs from "node:fs";
import path from "node:path";
import { writeFileSync as atomicWriteSync } from "atomically";
import { pluginsDataDir } from "../runtime-paths";

const MAX_STORAGE_CACHE_ENTRIES = 32;
const caches = new Map<string, Record<string, unknown>>();

const setCache = (pluginId: string, data: Record<string, unknown>): void => {
  caches.delete(pluginId);
  caches.set(pluginId, data);
  while (caches.size > MAX_STORAGE_CACHE_ENTRIES) {
    const oldest = caches.keys().next().value;
    if (!oldest) break;
    caches.delete(oldest);
  }
};

const ensureDir = (): void => {
  if (!fs.existsSync(pluginsDataDir)) {
    fs.mkdirSync(pluginsDataDir, { recursive: true });
  }
};

const fileOf = (pluginId: string): string => path.join(pluginsDataDir, `${pluginId}.json`);

const load = (pluginId: string): Record<string, unknown> => {
  const cached = caches.get(pluginId);
  if (cached) {
    setCache(pluginId, cached);
    return cached;
  }
  try {
    const raw = fs.readFileSync(fileOf(pluginId), "utf-8");
    const data = JSON.parse(raw) as Record<string, unknown>;
    setCache(pluginId, data);
    return data;
  } catch {
    const empty: Record<string, unknown> = {};
    setCache(pluginId, empty);
    return empty;
  }
};

const flush = (pluginId: string, data: Record<string, unknown>): void => {
  ensureDir();
  atomicWriteSync(fileOf(pluginId), JSON.stringify(data, null, 2));
  setCache(pluginId, data);
};

export const pluginStorageGet = (pluginId: string, key: string): unknown =>
  load(pluginId)[key] ?? null;

export const pluginStorageSet = (pluginId: string, key: string, value: unknown): void => {
  flush(pluginId, { ...load(pluginId), [key]: value });
};

export const pluginStorageRemove = (pluginId: string, key: string): void => {
  const current = load(pluginId);
  if (!(key in current)) return;
  const next = { ...current };
  delete next[key];
  flush(pluginId, next);
};

export const pluginStorageKeys = (pluginId: string): string[] => Object.keys(load(pluginId));

export const pluginStorageDrop = (pluginId: string): void => {
  caches.delete(pluginId);
  try {
    fs.unlinkSync(fileOf(pluginId));
  } catch {
    /* ignore */
  }
};
