import { registerPlugin, type PluginListenerHandle } from "@capacitor/core";

/** 采集来源（与 Kotlin CaptureSource 对齐） */
export type AndroidCaptureSource = "system" | "microphone";

/** 采集事件（镜像桌面 audio-capture 的 JsCaptureEvent / Kotlin CaptureEvent） */
export interface AndroidCaptureEvent {
  eventType: "level" | "done" | "error";
  /** level 事件的 RMS 音量（0-1） */
  level?: number;
  /** done 事件的 8 kHz 单声道 Int16 小端 PCM（base64）；取消时缺省 */
  data?: string;
  /** error 事件的结构化错误码 */
  errorCode?: string;
  /** error 事件的可读信息 */
  error?: string;
}

/** AndroidAudioCapture 插件接口（与 Kotlin 端 AndroidAudioCapturePlugin 方法对齐） */
export interface AndroidAudioCapturePlugin {
  /** 开始一次采集；进度与结果经 captureEvent 事件回传 */
  startCapture(options: { source: AndroidCaptureSource; durationMs: number }): Promise<void>;
  /** 取消进行中的采集 */
  cancelCapture(): Promise<void>;
  /** 监听采集事件 */
  addListener(
    eventName: "captureEvent",
    callback: (event: AndroidCaptureEvent) => void,
  ): Promise<PluginListenerHandle>;
  /** 移除全部监听器 */
  removeAllListeners(): Promise<void>;
}

export const AndroidAudioCapture = registerPlugin<AndroidAudioCapturePlugin>("AndroidAudioCapture");
