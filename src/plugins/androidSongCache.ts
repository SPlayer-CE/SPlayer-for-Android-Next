import { registerPlugin } from "@capacitor/core";

export interface AndroidSongCachePlugin {
  lookup(options: { cacheKey: string }): Promise<{ path: string | null }>;
  fetch(options: { cacheKey: string; streamUrl: string }): Promise<{ path: string | null }>;
  cancel(options: { cacheKey: string }): Promise<void>;
}

export const AndroidSongCache = registerPlugin<AndroidSongCachePlugin>("AndroidSongCache");
