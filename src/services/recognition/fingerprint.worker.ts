/**
 * 听歌识曲指纹 Worker：在独立线程加载网易云 AFP 指纹库（纯 WASM）并计算指纹，
 * 避免滑窗连续多次 GenerateFP 阻塞 WebView 主线程导致波形动画掉帧。
 * 对齐 src/utils/coverColor.worker.ts 的 self.onmessage/postMessage 模式。
 */
import { GenerateFP } from "@root/resources/afp/afp.mjs";

/** Worker 请求消息 */
export interface FingerprintWorkerRequest {
  id: number;
  /** 8 kHz 单声道样本 */
  pcm: Float32Array;
}

/** Worker 响应消息 */
export interface FingerprintWorkerResponse {
  id: number;
  ok: boolean;
  fingerprint?: string;
  error?: string;
}

self.onmessage = async (ev: MessageEvent<FingerprintWorkerRequest>) => {
  const { id, pcm } = ev.data;
  try {
    const fingerprint = await GenerateFP(pcm);
    const resp: FingerprintWorkerResponse = { id, ok: true, fingerprint };
    self.postMessage(resp);
  } catch (error) {
    const resp: FingerprintWorkerResponse = {
      id,
      ok: false,
      error: error instanceof Error ? error.message : String(error),
    };
    self.postMessage(resp);
  }
};
