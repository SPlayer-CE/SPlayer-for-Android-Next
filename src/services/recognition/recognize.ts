/**
 * 渲染层听歌识曲会话核心：驱动 指纹 → 匹配 → 结果 的滑窗状态机，通过本地事件订阅广播进度。
 * 桌面端等价逻辑在主进程 session.ts；Android 无主进程，故在渲染层重建同一套编排，
 * 采集来源（麦克风 / 系统声音）由 bridge 决定后把 PCM 交给 submitRecognitionPcm。
 */

import type {
  RecognitionCandidate,
  RecognitionErrorCode,
  RecognitionEvent,
} from "@shared/types/recognition";
import { fingerprintPcm } from "./fingerprint";
import { matchAudio } from "./match";

/** 静音判定阈值（8 kHz 单声道 RMS） */
const SILENCE_RMS_THRESHOLD = 0.005;
/** 匹配窗口：3 秒窗口、1 秒步长滑动 */
const WINDOW_SAMPLES = 3 * 8000;
const STEP_SAMPLES = 1 * 8000;
/** 指纹输入的期望 RMS：低音量回采经增益补偿后接近该值 */
const TARGET_RMS = 0.1;
/** 增益上限，避免把近静音放大成噪声指纹 */
const MAX_GAIN = 50;

const listeners = new Set<(event: RecognitionEvent) => void>();
/** 会话令牌：submit / cancel 时递增，滑窗各步据此识别取消 */
let sessionToken = 0;

/**
 * 订阅识别事件
 * @param callback - 事件回调
 * @returns 取消订阅函数
 */
export const subscribeRecognition = (callback: (event: RecognitionEvent) => void): (() => void) => {
  listeners.add(callback);
  return () => {
    listeners.delete(callback);
  };
};

/**
 * 推送识别事件给全部订阅者
 * @param event - 识别事件
 */
export const emitRecognition = (event: RecognitionEvent): void => {
  for (const callback of listeners) callback(event);
};

/** 推送错误事件 */
export const emitRecognitionError = (code: RecognitionErrorCode, message: string): void => {
  emitRecognition({ phase: "error", error: { code, message } });
};

/** 取消当前识别：递增令牌，令进行中的滑窗循环在下一步早退 */
export const cancelRecognition = (): void => {
  sessionToken++;
};

/** 8 kHz 单声道样本的 RMS 音量 */
const rms = (pcm: Float32Array): number => {
  let energy = 0;
  for (let i = 0; i < pcm.length; i++) {
    energy += pcm[i] * pcm[i];
  }
  return Math.sqrt(energy / Math.max(1, pcm.length));
};

/**
 * 音量归一化：信号偏弱时放大到目标 RMS，过强时保持原样
 * @param pcm - 8 kHz 单声道样本
 * @returns 归一化后的样本
 */
const normalizeLevel = (pcm: Float32Array): Float32Array => {
  const current = rms(pcm);
  if (current <= 0) return pcm;
  const gain = Math.min(MAX_GAIN, TARGET_RMS / current);
  if (gain <= 1) return pcm;
  const out = new Float32Array(pcm.length);
  for (let i = 0; i < pcm.length; i++) {
    out[i] = pcm[i] * gain;
  }
  return out;
};

/**
 * 识别一段 8 kHz 单声道 PCM：按 3 秒窗口 / 1 秒步长滑动，顺序匹配，首个有候选的窗口即停止
 * @param pcm - 8 kHz 单声道样本
 */
export const submitRecognitionPcm = async (pcm: Float32Array): Promise<void> => {
  if (!(pcm instanceof Float32Array) || pcm.length === 0) {
    emitRecognitionError("capture-failed", "提交了无效的 PCM");
    return;
  }
  sessionToken++;
  const token = sessionToken;
  const normalized = normalizeLevel(pcm);
  if (rms(normalized) < SILENCE_RMS_THRESHOLD) {
    emitRecognitionError("silent-input", "没有采集到声音，请检查音频输出");
    return;
  }
  emitRecognition({ phase: "fingerprinting" });
  const segments: Array<{ start: number; pcm: Float32Array }> = [];
  for (let start = 0; start + WINDOW_SAMPLES <= normalized.length; start += STEP_SAMPLES) {
    segments.push({ start, pcm: normalized.subarray(start, start + WINDOW_SAMPLES) });
  }
  if (segments.length === 0) {
    segments.push({ start: 0, pcm: normalized });
  }
  let candidates: RecognitionCandidate[] = [];
  for (const segment of segments) {
    if (token !== sessionToken) return;
    // 每个窗口前让出事件循环：AFP WASM 计算密集，连续多窗长时间阻塞主线程会导致波形动画掉帧
    await new Promise((resolve) => setTimeout(resolve, 0));
    if (token !== sessionToken) return;
    const fingerprint = await fingerprintPcm(segment.pcm);
    if (token !== sessionToken) return;
    if (!fingerprint.ok) {
      emitRecognitionError(
        fingerprint.error === "afp-unavailable" ? "afp-unavailable" : "unknown",
        fingerprint.error,
      );
      return;
    }
    emitRecognition({ phase: "matching" });
    const match = await matchAudio(fingerprint.fingerprint, WINDOW_SAMPLES / 8000);
    if (token !== sessionToken) return;
    if (!match.ok) {
      emitRecognitionError("network", "音频匹配服务不可用");
      return;
    }
    if (match.songs.length === 0) continue;
    candidates = match.songs.map((item) => ({
      songId: String(item.song.id),
      title: item.song.name,
      artists: (item.song.artists ?? []).map((artist) => artist.name),
      album: item.song.album?.name,
      cover: item.song.album?.picUrl,
      startTime: (item.startTime ?? 0) + segment.start / 8000,
    }));
    break;
  }
  emitRecognition({ phase: "done", candidates });
};
