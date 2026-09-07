import { registerPlugin, type PluginListenerHandle } from "@capacitor/core";

export interface AndroidMainLyricPlugin {
  show(): Promise<void>;
  hide(): Promise<void>;
  clear(): Promise<void>;
  setTouchEnabled(options: { enabled: boolean }): Promise<void>;
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
  }): Promise<void>;
  updateProgress(options: { timeMs: number; playing: boolean }): Promise<void>;
  setTimeOffset(options: { timeOffsetMs: number }): Promise<void>;
  freeze(): Promise<void>;
  resume(): Promise<void>;
  refreshLayout(): Promise<void>;
  suppressTapSeek(): Promise<void>;
  addListener(
    eventName: "seek",
    listenerFunc: (event: { timeMs?: number }) => void,
  ): Promise<PluginListenerHandle>;
}

export const AndroidMainLyric = registerPlugin<AndroidMainLyricPlugin>("AndroidMainLyric");
