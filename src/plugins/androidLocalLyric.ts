import { registerPlugin } from "@capacitor/core";

/** SAF 选目录结果 */
export interface AndroidLyricPickResult {
  cancelled: boolean;
  uri?: string;
  name?: string;
}

/** 歌词目录引用 */
export interface AndroidLyricDirectory {
  uri: string;
  name: string;
}

/** 歌词条目 */
export interface AndroidLyricIndexEntry {
  uri: string;
  name: string;
  lastModified: number;
  directoryUri: string;
  format: string;
  metadata: {
    title?: string;
    artist?: string;
    album?: string;
    ncmMusicId?: string;
  };
}

/** 歌词扫描统计 */
export interface AndroidLyricScanSummary {
  indexMap: Record<string, AndroidLyricIndexEntry>;
  entries: AndroidLyricIndexEntry[];
  totalFiles: number;
  matchedFiles: number;
  duplicateIds: number;
  failedFiles: number;
  failures: { uri: string; name: string; reason: string; directoryUri: string }[];
}

/** Sidecar 歌词查找结果 */
export interface AndroidSidecarLyricResult {
  content: string;
  format?: string;
}

/** 已导入字体数据（含 base64 编码，用于 FontFace 注册） */
export interface ImportedFontData {
  name: string;
  base64: string;
  format: string;
}

/** 导入字体结果 */
export interface ImportFontResult {
  success: boolean;
  fontNames?: string[];
  fontData?: ImportedFontData[];
  error?: string;
}

/** AndroidLocalLyric 插件接口（与 Java 端 AndroidLocalLyricPlugin 方法对齐） */
export interface AndroidLocalLyricPlugin {
  /** SAF 选取歌词目录（只读权限） */
  pickLyricDirectory(): Promise<AndroidLyricPickResult>;
  /** 扫描歌词目录列表，构建索引 */
  scanLyricDirectories(options: {
    directories: AndroidLyricDirectory[];
  }): Promise<AndroidLyricScanSummary>;
  /** 读取歌词文件内容 */
  readLyricFile(options: { uri: string }): Promise<{ content: string }>;
  /** 获取音频文件同目录下的同名歌词文件内容，或通过 TTML 元信息匹配 */
  findSidecarLyric(options: {
    audioPath: string;
    title?: string;
    artist?: string;
  }): Promise<AndroidSidecarLyricResult>;
  /** 列出系统字体和已导入字体名称 */
  listFonts(): Promise<{ fonts: string[] }>;
  /** SAF 选择并导入字体文件，返回字体名称和 base64 数据 */
  importFont(): Promise<ImportFontResult>;
  /** 读取已导入字体的 base64 数据（用于 FontFace 注册） */
  readImportedFonts(): Promise<{ fonts: ImportedFontData[] }>;
}

export const AndroidLocalLyric = registerPlugin<AndroidLocalLyricPlugin>("AndroidLocalLyric");
