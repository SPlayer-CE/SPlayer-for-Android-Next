/**
 * 渲染层音频指纹：在常驻 Web Worker 中运行网易云 AFP 指纹库（纯 WASM），
 * 避免滑窗连续多次 GenerateFP 阻塞 WebView 主线程导致波形动画掉帧。
 * Worker 惰性创建、跨识别复用以省去重复加载 WASM；创建或运行失败时本次识别返回
 * afp-unavailable，并丢弃实例以便下次识别重建、从瞬时故障自愈。
 * 桌面端由主进程 fingerprint.worker.ts 承担，此处仅供 Android 渲染层识别管线使用。
 */

import FingerprintWorker from "./fingerprint.worker?worker";
import type { FingerprintWorkerRequest, FingerprintWorkerResponse } from "./fingerprint.worker";

export type FingerprintResult = { ok: true; fingerprint: string } | { ok: false; error: string };

/** 常驻指纹 Worker（惰性创建，跨识别复用） */
let worker: Worker | null = null;
let nextId = 1;
const pending = new Map<number, (result: FingerprintResult) => void>();

/** 惰性创建 Worker 并接线消息回调；创建失败返回 null */
const getWorker = (): Worker | null => {
  if (worker) return worker;
  try {
    worker = new FingerprintWorker();
  } catch {
    return null;
  }
  worker.onmessage = (ev: MessageEvent<FingerprintWorkerResponse>) => {
    const { id, ok, fingerprint, error } = ev.data;
    const resolve = pending.get(id);
    if (!resolve) return;
    pending.delete(id);
    resolve(
      ok ? { ok: true, fingerprint: fingerprint ?? "" } : { ok: false, error: error ?? "unknown" },
    );
  };
  worker.onerror = () => {
    // Worker 异常：失败所有挂起请求并丢弃实例，下次识别重建以从瞬时故障自愈
    for (const resolve of pending.values()) resolve({ ok: false, error: "afp-unavailable" });
    pending.clear();
    worker?.terminate();
    worker = null;
  };
  return worker;
};

/**
 * 计算音频指纹（在 Worker 线程运行 AFP WASM）
 * @param pcm - 8 kHz 单声道样本
 * @returns 指纹字符串；Worker 或库不可用时返回 afp-unavailable
 */
export const fingerprintPcm = (pcm: Float32Array): Promise<FingerprintResult> => {
  const current = getWorker();
  if (!current) return Promise.resolve({ ok: false, error: "afp-unavailable" });
  return new Promise((resolve) => {
    const id = nextId++;
    pending.set(id, resolve);
    // 不使用 transfer：pcm 是滑窗对归一化缓冲的 subarray 视图，转移会使共享缓冲失效
    const request: FingerprintWorkerRequest = { id, pcm };
    current.postMessage(request);
  });
};
