import { registerPlugin, type PluginListenerHandle } from "@capacitor/core";
import { shallowRef } from "vue";

/** 浮层触摸排除区（屏幕物理像素），命中区内的触摸放行给 WebView */
export interface AndroidMainLyricTouchExclusionRect {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export interface AndroidMainLyricPlugin {
  show(): Promise<void>;
  hide(): Promise<void>;
  clear(): Promise<void>;
  setTouchEnabled(options: { enabled: boolean }): Promise<void>;
  setTouchExclusionRects(options: { rects: AndroidMainLyricTouchExclusionRect[] }): Promise<void>;
  setViewport(options: {
    left: number;
    top: number;
    width: number;
    height: number;
    bottomExclusionHeight?: number;
    cssWidth?: number;
  }): Promise<void>;
  setLyrics(options: { linesJson: string }): Promise<void>;
  setConfig(options: {
    fontSizePx: number;
    fontWeight: number;
    fontFamily?: string;
    fontFamilyChinese?: string;
    fontFamilyJapanese?: string;
    fontFamilyKorean?: string;
    fontFamilyLatin?: string;
    textColor?: string;
    inactiveAlpha: number;
    alignPosition: number;
    wordFadeWidth: number;
    hidePassedLines: boolean;
    enableBlur: boolean;
    enableWordHighlight: boolean;
    enableFloatAnimation: boolean;
    enableEmphasizeEffect: boolean;
    enableWordBlockSegmentation: boolean;
    showTranslation: boolean;
    showRomanization: boolean;
    springMass?: number;
    springDamping?: number;
    springStiffness?: number;
    alwaysPostpositionBackground?: boolean;
    /** 激活行 HDR 显示，需 Android 13+ 且面板支持，否则原生侧自动忽略 */
    enableHdr?: boolean;
  }): Promise<void>;
  updateProgress(options: { timeMs: number; playing: boolean }): Promise<void>;
  setTimeOffset(options: { timeOffsetMs: number }): Promise<void>;
  freeze(): Promise<void>;
  resume(): Promise<void>;
  refreshLayout(): Promise<void>;
  suppressTapSeek(): Promise<void>;
  /** 查询激活行 HDR 能力，reason 取值 supported / unsupportedApi / unsupportedDisplay / unknown */
  isHdrSupported(): Promise<{ supported: boolean; reason: string }>;
  addListener(
    eventName: "seek",
    listenerFunc: (event: { timeMs?: number }) => void,
  ): Promise<PluginListenerHandle>;
}

export const AndroidMainLyric = registerPlugin<AndroidMainLyricPlugin>("AndroidMainLyric");

/**
 * 面板 HDR 能力缓存：null 表示尚未查询。
 *
 * 设置页与播放器不在同一条渲染链路上，用模块级单例缓存避免两处各查一次；
 * 查询失败按「不支持」处理，避免开关打开后毫无反应。
 */
export const lyricHdrSupported = shallowRef<boolean | null>(null);
let hdrCapabilityPending = false;

/** 幂等查询一次原生 HDR 能力，结果写入 {@link lyricHdrSupported} */
export function loadHdrCapability(): void {
  if (lyricHdrSupported.value !== null || hdrCapabilityPending) return;
  hdrCapabilityPending = true;
  void AndroidMainLyric.isHdrSupported()
    .then((result) => {
      lyricHdrSupported.value = result.supported;
    })
    .catch(() => {
      lyricHdrSupported.value = false;
    })
    .finally(() => {
      hdrCapabilityPending = false;
    });
}
