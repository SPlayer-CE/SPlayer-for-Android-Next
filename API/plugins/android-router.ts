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
  // 并行竞速：所有可用候选同时发起，取最快返回的有效 URL。
  // 串行逐个等待时最坏 N×20s，是切歌黑窗（音频停在上一首）的主要来源之一。
  const attempts: Array<Promise<MusicUrlRes | null>> = [];
  for (const id of order) {
    if (!id || tried.has(id)) continue;
    tried.add(id);
    const runtime = androidPluginRegistry.getRuntime(id);
    if (!runtime || !runtime.enabled) continue;
    if (runtime.status.state !== "ready" || !runtime.sandbox?.isAlive()) continue;
    const sourceCapability = runtime.status.sources[params.source];
    if (!sourceCapability?.actions.includes("musicUrl")) continue;
    attempts.push(
      withTimeout(
        runtime.sandbox.call("musicUrl", params) as Promise<MusicUrlRes>,
        ACTION_TIMEOUTS.musicUrl,
        `plugin ${id} request timeout`,
        () => {
          androidPluginRegistry.markFailed(id, PluginErrorCodes.REQUEST_TIMEOUT, "request timeout");
        },
      )
        .then((result) => (typeof result?.url === "string" && result.url.trim() ? result : null))
        .catch((error) => {
          console.warn(`[embedded-api] plugin resolveUrl failed: ${id}`, error);
          return null;
        }),
    );
  }
  if (attempts.length === 0) return { url: "" };

  return await new Promise<MusicUrlRes>((resolve) => {
    let remaining = attempts.length;
    for (const attempt of attempts) {
      // attempt 已自带 catch，只会以 null 兜底 resolve，不会 reject
      attempt.then((result) => {
        if (result) resolve(result);
        else if (--remaining === 0) resolve({ url: "" });
      });
    }
  });
};
