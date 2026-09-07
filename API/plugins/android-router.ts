import type { MusicUrlReq, MusicUrlRes, PluginResolveUrlArgs } from "../../shared/types/plugin";
import { ACTION_TIMEOUTS, PluginErrorCodes } from "../../shared/defaults/plugin-api";
import { getSystemConfigValue } from "../config-store";
import { androidPluginRegistry } from "./android-registry";

const withTimeout = <T>(
  promise: Promise<T>,
  timeoutMs: number,
  message: string,
  onTimeout: () => void,
): Promise<T> =>
  new Promise<T>((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      settled = true;
      onTimeout();
      reject(Object.assign(new Error(message), { code: PluginErrorCodes.REQUEST_TIMEOUT }));
    }, timeoutMs);
    promise.then(
      (value) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve(value);
      },
      (error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        reject(error);
      },
    );
  });

export const resolveUrl = async (args: PluginResolveUrlArgs): Promise<MusicUrlRes> => {
  await androidPluginRegistry.ensureInitialized();
  const params: MusicUrlReq = {
    source: args.source ?? "",
    quality: args.quality ?? "hq",
    musicInfo: args.musicInfo,
  };
  const tried = new Set<string>();
  const order = [
    args.pluginId,
    ...((getSystemConfigValue("plugins.priority.musicUrl") as string[] | undefined) ?? []),
    ...androidPluginRegistry.listInfo().map((info) => info.manifest.id),
  ];
  let failureCount = 0;

  for (const id of order) {
    if (!id || tried.has(id)) continue;
    tried.add(id);
    const runtime = androidPluginRegistry.getRuntime(id);
    if (!runtime || !runtime.enabled) continue;
    if (runtime.status.state !== "ready" || !runtime.sandbox?.isAlive()) continue;
    const sourceCapability = runtime.status.sources[params.source];
    if (!sourceCapability?.actions.includes("musicUrl")) continue;
    try {
      const result = await withTimeout(
        runtime.sandbox.call("musicUrl", params) as Promise<MusicUrlRes>,
        ACTION_TIMEOUTS.musicUrl,
        `plugin ${id} request timeout`,
        () => {
          androidPluginRegistry.markFailed(id, PluginErrorCodes.REQUEST_TIMEOUT, "request timeout");
        },
      );
      if (typeof result?.url === "string" && result.url.trim()) return result;
      console.info(`[embedded-api] plugin resolveUrl returned empty url: ${id}`);
    } catch (error) {
      failureCount += 1;
      console.warn(`[embedded-api] plugin resolveUrl failed: ${id}`, error);
    }
  }
  if (failureCount > 0) {
    console.info(
      `[embedded-api] plugin resolveUrl exhausted all candidates without valid url: ${params.source}`,
    );
  }
  return { url: "" };
};
