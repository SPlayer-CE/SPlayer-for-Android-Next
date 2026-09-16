/**
 * Android 原生采集编排：调用 AndroidAudioCapture 插件采集系统声音 / 麦克风，
 * level 事件转发给识别核心，done 事件的 PCM 解码后交给 submitRecognitionPcm；
 * 采集期间暂停自身播放、结束恢复（对齐桌面主进程 session.ts 的 wasPlaying 逻辑）。
 */

import type { PluginListenerHandle } from "@capacitor/core";
import type { RecognitionConfig, RecognitionErrorCode } from "@shared/types/recognition";
import { AndroidAudioCapture, type AndroidCaptureEvent } from "@/plugins/androidAudioCapture";
import * as player from "@/core/player";
import { useStatusStore } from "@/stores/status";
import {
  cancelRecognition,
  emitRecognition,
  emitRecognitionError,
  submitRecognitionPcm,
} from "./recognize";

let listener: PluginListenerHandle | null = null;
let capturing = false;
/** 采集前是否正在播放，结束后恢复 */
let wasPlaying = false;

/** 原生错误码 → 共享错误码 */
const mapErrorCode = (code?: string): RecognitionErrorCode => {
  switch (code) {
    case "unsupported":
    case "no-device":
    case "permission-denied":
    case "capture-failed":
      return code;
    default:
      return "unknown";
  }
};

/** base64 的 8 kHz 单声道 Int16 小端 PCM → Float32Array */
const decodePcm = (base64: string): Float32Array => {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  const samples = new Int16Array(bytes.buffer, bytes.byteOffset, Math.floor(bytes.length / 2));
  const pcm = new Float32Array(samples.length);
  for (let i = 0; i < samples.length; i++) {
    pcm[i] = samples[i] / 32768;
  }
  return pcm;
};

/** 结束会话：停止监听并恢复播放（幂等，取消与完成的竞态下可安全重入） */
const finish = (): void => {
  capturing = false;
  const current = listener;
  listener = null;
  if (current) void current.remove();
  if (wasPlaying) {
    wasPlaying = false;
    void player.play();
  }
};

/** 处理原生采集事件 */
const handleCaptureEvent = (event: AndroidCaptureEvent): void => {
  if (event.eventType === "level") {
    emitRecognition({ phase: "capturing", level: event.level ?? 0 });
    return;
  }
  if (event.eventType === "error") {
    emitRecognitionError(mapErrorCode(event.errorCode), event.error ?? "采集失败");
    finish();
    return;
  }
  // done：取消时无 data，仅收尾
  if (!event.data) {
    finish();
    return;
  }
  const pcm = decodePcm(event.data);
  // 识别完成后（含取消早退）再收尾恢复播放，对齐桌面 finishSession 时机
  void submitRecognitionPcm(pcm).finally(() => finish());
};

/** 取消进行中的原生采集识别 */
export const cancelNativeRecognition = (): void => {
  cancelRecognition();
  if (capturing) void AndroidAudioCapture.cancelCapture();
  finish();
};

/**
 * 开始一次原生采集识别
 * @param config - 采集来源与时长
 */
export const startNativeRecognition = async (config: RecognitionConfig): Promise<void> => {
  cancelNativeRecognition();
  wasPlaying = useStatusStore().isPlaying;
  if (wasPlaying) await player.pause();
  capturing = true;
  emitRecognition({ phase: "capturing" });
  try {
    listener = await AndroidAudioCapture.addListener("captureEvent", handleCaptureEvent);
    await AndroidAudioCapture.startCapture({
      source: config.source,
      durationMs: config.durationMs,
    });
  } catch (error) {
    emitRecognitionError("capture-failed", error instanceof Error ? error.message : String(error));
    finish();
  }
};
