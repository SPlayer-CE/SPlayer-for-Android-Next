import { registerPlugin, type PluginListenerHandle } from "@capacitor/core";

/** SAF 选目录结果 */
export interface AndroidDownloadPickResult {
  cancelled: boolean;
  uri?: string;
  name?: string;
}

/** 下载文件请求 */
export interface AndroidDownloadFileRequest {
  taskId: string;
  url: string;
  fileName: string;
  directoryUri: string;
  subPath?: string;
}

/** 下载文件结果 */
export interface AndroidDownloadResult {
  status: "success" | "skipped";
  path: string;
  fileName?: string;
}

/** 写文本文件请求 */
export interface AndroidWriteTextFileRequest {
  fileName: string;
  content: string;
  directoryUri: string;
  subPath?: string;
}

/** 写文本文件结果 */
export interface AndroidWriteFileResult {
  status: string;
  path: string;
}

/** 内嵌标签请求 */
export interface AndroidEmbedTagsRequest {
  /** 已下载音频文件的 content:// URI */
  filePath: string;
  /** 封面原图 URL */
  coverUrl?: string;
  /** 歌曲标题 */
  title?: string;
  /** 艺术家 */
  artist?: string;
  /** 专辑名 */
  album?: string;
  /** 歌词文本 */
  lyrics?: string;
  /** 是否内嵌封面 */
  embedCover: boolean;
  /** 是否内嵌元信息 */
  embedMeta: boolean;
  /** 是否内嵌歌词 */
  embedLyric: boolean;
}

/** 内嵌标签结果 */
export interface AndroidEmbedTagsResult {
  status: "success" | "skipped";
}

/** 下载目录信息 */
export interface AndroidDownloadDirectoryInfo {
  exists: boolean;
  canWrite?: boolean;
  name?: string;
}

/** 本地扫描结果 */
export interface AndroidLocalScanResult {
  songs: unknown[];
  failedDirectories?: number;
}

/** 保存二进制文件到公共下载目录的请求 */
export interface AndroidSaveFileRequest {
  /** base64 编码的文件内容 */
  data: string;
  fileName: string;
}

/** 保存二进制文件结果 */
export interface AndroidSaveFileResult {
  status: "success";
  path: string;
  /** 重名时 MediaStore 追加序号后的最终文件名 */
  fileName?: string;
}

/** 下载进度事件 */
export interface AndroidDownloadProgress {
  taskId: string;
  bytesRead: number;
  contentLength: number;
  percent: number;
}

/** AndroidDownload 插件接口（与 Java 端 AndroidDownloadPlugin 方法对齐） */
export interface AndroidDownloadPlugin {
  /** SAF 选取下载目录（读写权限） */
  pickDownloadDirectory(): Promise<AndroidDownloadPickResult>;
  /** 下载文件到指定目录 */
  downloadFile(options: AndroidDownloadFileRequest): Promise<AndroidDownloadResult>;
  /** 取消下载任务 */
  cancelDownload(options: { taskId: string }): Promise<void>;
  /** 写文本文件到指定目录 */
  writeTextFile(options: AndroidWriteTextFileRequest): Promise<AndroidWriteFileResult>;
  /** 保存二进制文件到公共下载目录（MediaStore Downloads） */
  saveFile(options: AndroidSaveFileRequest): Promise<AndroidSaveFileResult>;
  /** 将封面/元信息/歌词内嵌到已下载音频文件的标签中 */
  embedTags(options: AndroidEmbedTagsRequest): Promise<AndroidEmbedTagsResult>;
  /** 获取下载目录信息 */
  getDownloadDirectoryInfo(options: {
    directoryUri: string;
  }): Promise<AndroidDownloadDirectoryInfo>;
  /** 列出下载目录下的全部音乐文件 */
  listDownloadedSongs(options: { directoryUri: string }): Promise<AndroidLocalScanResult>;
  /** SAF 选取本地音乐目录（只读权限） */
  pickLocalMusicDirectory(): Promise<AndroidDownloadPickResult>;
  /** 扫描本地音乐目录列表 */
  scanLocalMusic(options: {
    directories: { uri: string; name: string }[];
  }): Promise<AndroidLocalScanResult>;
  /** 监听下载进度事件 */
  addListener(
    eventName: "downloadProgress",
    callback: (progress: AndroidDownloadProgress) => void,
  ): Promise<PluginListenerHandle>;
  /** 解析下载 URL（Kotlin 端自主调用嵌入式 API + netease package） */
  resolveDownloadUrl(options: {
    songId: number;
    usePlayback?: boolean;
  }): Promise<{ url: string; format?: string; size?: number } | null>;
  /** 移除全部监听器 */
  removeAllListeners(): Promise<void>;
}

export const AndroidDownload = registerPlugin<AndroidDownloadPlugin>("AndroidDownload");
