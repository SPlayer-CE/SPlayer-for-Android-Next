import { registerPlugin, type PluginListenerHandle } from "@capacitor/core";
import type { IpcResponse, Track } from "@shared/types/player";
import type { AlbumSummary, ArtistSummary, ScanProgress } from "@shared/types/library";

/** 扫描目录详情（带名称，用于 UI 显示） */
export interface AndroidScanDirDetail {
  uri: string;
  name: string;
}

/** SAF 选目录结果 */
export interface AndroidPickDirResult {
  success: boolean;
  data?: string;
  error?: string;
  name?: string;
}

/** AndroidLibrary 插件接口（与 Java 端 AndroidLibraryPlugin 方法对齐） */
export interface AndroidLibraryPlugin {
  /** SAF 选取本地音乐目录，持久化 URI 权限并存入 scan_dirs 表 */
  pickMusicDirectory(): Promise<AndroidPickDirResult>;
  /** 开始扫描 */
  scan(options: { incremental: boolean }): Promise<IpcResponse>;
  /** 取消扫描 */
  cancelScan(): Promise<IpcResponse>;
  /** 获取扫描状态 */
  isScanning(): Promise<IpcResponse<boolean>>;
  /** 获取全部曲目 */
  getTracks(): Promise<IpcResponse<Track[]>>;
  /** 获取专辑聚合列表 */
  getAlbums(): Promise<IpcResponse<AlbumSummary[]>>;
  /** 获取歌手聚合列表 */
  getArtists(): Promise<IpcResponse<ArtistSummary[]>>;
  /** 按专辑名获取全部曲目 */
  getAlbumTracks(options: { albumName: string }): Promise<IpcResponse<Track[]>>;
  /** 按歌手名获取全部曲目 */
  getArtistTracks(options: { artistName: string }): Promise<IpcResponse<Track[]>>;
  /** 按 ID 批量获取曲目 */
  getTracksByIds(options: { ids: string[] }): Promise<IpcResponse<Track[]>>;
  /** 搜索曲目 */
  searchTracks(options: { query: string }): Promise<IpcResponse<Track[]>>;
  /** 获取曲目总数 */
  getTrackCount(): Promise<IpcResponse<number>>;
  /** 随机取一首曲目 */
  getRandomTrack(): Promise<IpcResponse<Track | null>>;
  /** 随机取多首曲目 */
  getRandomTracks(options: { limit: number }): Promise<IpcResponse<Track[]>>;
  /** 获取扫描目录列表（URI 字符串数组） */
  getScanDirs(): Promise<IpcResponse<string[]>>;
  /** 获取扫描目录详情（带名称） */
  getScanDirDetails(): Promise<IpcResponse<AndroidScanDirDetail[]>>;
  /** 移除扫描目录及其下曲目 */
  removeScanDir(options: { dir: string }): Promise<IpcResponse>;
  /** 删除曲目文件并从数据库移除 */
  deleteTracks(options: {
    paths: string[];
  }): Promise<IpcResponse<{ deleted: number; failed: number }>>;
  /** SAF 选取封面图片 */
  pickCoverImage(): Promise<IpcResponse<{ path: string; dataUrl: string }>>;
  /** 读取标签（支持 content:// SAF URI） */
  readTags(options: { path: string }): Promise<IpcResponse>;
  /** 写入标签（支持 content:// SAF URI） */
  writeTags(options: { edits: unknown[] }): Promise<IpcResponse>;
  /** 获取歌手头像（Android 端暂不支持） */
  fetchArtistAvatar(options: { artistName: string }): Promise<IpcResponse<string | null>>;
  /** 批量预取歌手头像（Android 端暂不支持） */
  prefetchArtistAvatars(options: {
    artistNames: string[];
  }): Promise<IpcResponse<Record<string, string>>>;
  /** 监听扫描进度事件 */
  addListener(
    eventName: "library:scanProgress",
    callback: (progress: ScanProgress) => void,
  ): Promise<PluginListenerHandle>;
}

export const AndroidLibrary = registerPlugin<AndroidLibraryPlugin>("AndroidLibrary");
