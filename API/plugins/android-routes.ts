import fs from "node:fs";
import type { MarketPlugin, PluginQuality, PluginResolveUrlArgs } from "../../shared/types/plugin";
import { PLUGIN_REGISTRY_URL } from "../../shared/defaults/plugin-api";
import { androidPluginRegistry } from "./android-registry";
import { fetchScript } from "./android-net";
import { resolveUrl } from "./android-router";

type JsonObject = Record<string, unknown>;

export interface AndroidPluginRouteResult {
  statusCode: number;
  body: unknown;
}

const getString = (value: unknown): string => (typeof value === "string" ? value.trim() : "");

const isPluginQuality = (value: unknown): value is PluginQuality =>
  value === "lq" || value === "sq" || value === "hq" || value === "lossless" || value === "hi-res";

const toResolveArgs = (body: JsonObject): PluginResolveUrlArgs | null => {
  const pluginId = getString(body.pluginId);
  const source = getString(body.source);
  const musicInfo =
    body.musicInfo && typeof body.musicInfo === "object" && !Array.isArray(body.musicInfo)
      ? (body.musicInfo as Record<string, unknown>)
      : null;
  if (!pluginId || !source || !musicInfo) return null;
  const songmid = getString(musicInfo.songmid);
  if (!songmid) return null;
  return {
    pluginId,
    source,
    quality: isPluginQuality(body.quality) ? body.quality : undefined,
    musicInfo: {
      ...musicInfo,
      songmid,
    },
  };
};

export const handleAndroidPluginRoute = async (
  pathname: string,
  body: JsonObject,
): Promise<AndroidPluginRouteResult | null> => {
  if (!pathname.startsWith("/api/plugins/")) return null;
  await androidPluginRegistry.ensureInitialized();

  // /api/plugins/watch 长轮询由 mobile-server.ts 主分发器直接处理，不经过本路由

  if (pathname === "/api/plugins/list") {
    return {
      statusCode: 200,
      body: androidPluginRegistry.listInfo(),
    };
  }

  if (pathname === "/api/plugins/install") {
    const source = getString(body.source);
    const filePath = getString(body.filePath);
    if (!source && !filePath) {
      return { statusCode: 400, body: { ok: false, error: "missing source or filePath" } };
    }
    try {
      const info = source
        ? await androidPluginRegistry.installFromSource(source)
        : await androidPluginRegistry.install(filePath);
      return { statusCode: 200, body: { ok: true, id: info.manifest.id } };
    } catch (error) {
      return {
        statusCode: 500,
        body: { ok: false, error: error instanceof Error ? error.message : String(error) },
      };
    }
  }

  if (pathname === "/api/plugins/installFromUrl") {
    const url = getString(body.url);
    if (!url) return { statusCode: 400, body: { ok: false, error: "missing url" } };
    try {
      const info = await androidPluginRegistry.installFromUrl(url);
      return { statusCode: 200, body: { ok: true, id: info.manifest.id } };
    } catch (error) {
      return {
        statusCode: 500,
        body: { ok: false, error: error instanceof Error ? error.message : String(error) },
      };
    }
  }

  if (pathname === "/api/plugins/uninstall") {
    const id = getString(body.id);
    if (!id) return { statusCode: 400, body: { ok: false, error: "missing id" } };
    try {
      await androidPluginRegistry.uninstall(id);
      return { statusCode: 200, body: { ok: true } };
    } catch (error) {
      return {
        statusCode: 500,
        body: { ok: false, error: error instanceof Error ? error.message : String(error) },
      };
    }
  }

  if (pathname === "/api/plugins/setEnabled") {
    const id = getString(body.id);
    const enabled = typeof body.enabled === "boolean" ? body.enabled : null;
    if (!id || enabled === null) {
      return { statusCode: 400, body: { ok: false, error: "missing id or enabled" } };
    }
    await androidPluginRegistry.setEnabled(id, enabled);
    return { statusCode: 200, body: { ok: true } };
  }

  if (pathname === "/api/plugins/setSetting") {
    const id = getString(body.id);
    const key = getString(body.key);
    if (!id || !key) return { statusCode: 400, body: { ok: false, error: "missing id or key" } };
    await androidPluginRegistry.setSetting(id, key, body.value);
    return { statusCode: 200, body: { ok: true } };
  }

  if (pathname === "/api/plugins/resolveUrl") {
    const args = toResolveArgs(body);
    if (!args) return { statusCode: 400, body: { ok: false, error: "invalid resolve args" } };
    const result = await resolveUrl(args);
    return { statusCode: 200, body: result };
  }

  if (pathname === "/api/plugins/market") {
    try {
      const raw = await fetchScript(PLUGIN_REGISTRY_URL);
      const data = JSON.parse(raw) as { plugins?: MarketPlugin[] };
      const plugins = Array.isArray(data.plugins)
        ? data.plugins.filter((item) => item?.id && item?.updateUrl)
        : [];
      return { statusCode: 200, body: { ok: true, plugins } };
    } catch (error) {
      return {
        statusCode: 502,
        body: {
          ok: false,
          plugins: [],
          error: error instanceof Error ? error.message : String(error),
        },
      };
    }
  }

  if (
    pathname === "/api/plugins/checkUpdate" ||
    pathname === "/api/plugins/applyUpdate" ||
    pathname === "/api/plugins/invokeMenu" ||
    pathname === "/api/plugins/matchLyric" ||
    pathname === "/api/plugins/matchCover"
  ) {
    return {
      statusCode: 501,
      body: { ok: false, error: "ANDROID_PLUGIN_PHASE1_ONLY" },
    };
  }

  if (pathname === "/api/plugins/installFromFile") {
    const filePath = getString(body.filePath);
    if (!filePath || !fs.existsSync(filePath)) {
      return { statusCode: 400, body: { ok: false, error: "file not found" } };
    }
    try {
      const info = await androidPluginRegistry.install(filePath);
      return { statusCode: 200, body: { ok: true, id: info.manifest.id } };
    } catch (error) {
      return {
        statusCode: 500,
        body: { ok: false, error: error instanceof Error ? error.message : String(error) },
      };
    }
  }

  return { statusCode: 404, body: { ok: false, error: "UNSUPPORTED" } };
};
