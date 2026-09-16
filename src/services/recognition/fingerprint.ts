/**
 * 渲染层音频指纹：在 WebView 内懒加载网易云 AFP 指纹库（纯 WASM），
 * 从 8 kHz 单声道 PCM 生成指纹 base64。
 * 桌面端由主进程 fingerprint.worker.ts 承担，此处仅供 Android 渲染层识别管线使用。
 */

type AfpModule = { GenerateFP?: (pcm: Float32Array) => Promise<string> };

export type FingerprintResult = { ok: true; fingerprint: string } | { ok: false; error: string };

let afpPromise: Promise<AfpModule | null> | null = null;

/** 懒加载 AFP ESM 指纹库；加载失败缓存 null，避免每次识别重复尝试 */
const loadAfp = (): Promise<AfpModule | null> => {
  if (afpPromise) return afpPromise;
  afpPromise = import("@root/resources/afp/afp.mjs")
    .then((mod: AfpModule) => (typeof mod.GenerateFP === "function" ? mod : null))
    .catch(() => null);
  return afpPromise;
};

/**
 * 计算音频指纹
 * @param pcm - 8 kHz 单声道样本
 * @returns 指纹字符串；库不可用时返回 afp-unavailable
 */
export const fingerprintPcm = async (pcm: Float32Array): Promise<FingerprintResult> => {
  const mod = await loadAfp();
  if (!mod?.GenerateFP) return { ok: false, error: "afp-unavailable" };
  try {
    const fingerprint = await mod.GenerateFP(pcm);
    return { ok: true, fingerprint };
  } catch (error) {
    return { ok: false, error: error instanceof Error ? error.message : String(error) };
  }
};
