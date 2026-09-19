/**
 * Android 差异化热更新 Manifest 与签名结构定义
 * 遵循 Issue #26 提案：差量传输 + Ed25519 签名防御 + Origin 保持
 */

export interface LiveUpdateFileEntry {
  /** 相对于 assets/public/ 的相对路径，如 "assets/index-DRcTdJM8.css" */
  path: string;
  /** 文件 sha256 哈希 (十六进制 64 字符) */
  sha256: string;
  /** 文件字节大小 */
  size: number;
}

export interface LiveUpdateManifest {
  /** 目标版本号或 tag，如 "nightly-20260920" 或 "v1.2.1" */
  version: string;
  /** 最低所需原生 APK 版本号 (versionCode 约束)，低于此值的 APK 必须走整包更新，防止 ABI/Plugin 不匹配 */
  minNativeVersion: number;
  /** 分发渠道: "stable" | "nightly" */
  channel: "stable" | "nightly";
  /** 打包时间戳 (毫秒) */
  timestamp: number;
  /** 发布更新说明 */
  releaseNotes?: string;
  /** 所有静态资源清单哈希表 (以 path 为索引) */
  files: Record<string, LiveUpdateFileEntry>;
  /** 整体清单签名 (可选，如果嵌入在 manifest 内或单独存储为 manifest.sig) */
  signature?: string;
}

export interface LiveUpdateProgress {
  /** 已下载字节数 */
  downloadedBytes: number;
  /** 需要下载的总字节数 */
  totalBytes: number;
  /** 当前已完成的文件数 */
  completedFiles: number;
  /** 总共需要更新的文件数 */
  totalFiles: number;
  /** 进度百分比 0-100 */
  percent: number;
}

export interface LiveUpdateCheckResult {
  hasUpdate: boolean;
  manifest?: LiveUpdateManifest;
  /** 差异文件列表（本地缺失或 hash 不一致的文件） */
  diffFiles?: LiveUpdateFileEntry[];
  /** 需下载的总差量大小 */
  diffBytes?: number;
  /** 是否因为低于 minNativeVersion 而必须走全量 APK 更新 */
  requireFullApkUpdate?: boolean;
}
