/**
 * 从 SAF content URI 或文件路径中提取人类可读的目录名。
 *
 * SAF URI 格式举例：
 * - content://com.android.externalstorage.documents/tree/primary%3AMusic → "Music"
 * - content://com.android.externalstorage.documents/tree/primary%3AMusic%2FSub → "Sub"
 * - content://com.android.externalstorage.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FMusic → "Music"
 * - content://com.android.externalstorage.documents/document/primary%3AMusic → "Music"
 *
 * 非 content:// 的普通文件路径直接取最后一段。
 *
 * @param dir - SAF URI 或文件路径
 * @returns 人类可读的目录名
 */
export const safDirName = (dir: string): string => {
  if (!dir) return dir;

  if (dir.startsWith("content://")) {
    try {
      // 取 URI 最后一段路径（encoded），再 URL 解码
      const segments = dir.split("/").filter(Boolean);
      const lastSegment = segments[segments.length - 1] || dir;
      const decoded = decodeURIComponent(lastSegment);

      // document ID 格式: "primary:Music/Sub" 或 "raw:/storage/emulated/0/Music"
      // 按 ":" 分割取末段（去掉卷标前缀），再按 "/" 取最后一段
      const afterColon = decoded.includes(":") ? decoded.split(":").pop() || decoded : decoded;
      const parts = afterColon.split("/").filter(Boolean);
      return parts[parts.length - 1] || decoded;
    } catch {
      // decodeURIComponent 失败时回退到原始路径分割
    }
  }

  const parts = dir.replace(/\\/g, "/").split("/").filter(Boolean);
  return parts[parts.length - 1] || dir;
};

/**
 * 将 SAF content URI 转换为人类可读的路径。
 *
 * SAF URI 的 document/tree 段经 URL 解码后格式为 "volume:path"，
 * 去掉卷标前缀即可得到真实路径。优先取 document 段（文件级），其次 tree 段（目录级）。
 *
 * @param uri SAF content URI 或普通文件路径
 * @returns 人类可读路径；非 content:// 直接返回原值
 */
export const safToHumanPath = (uri: string): string => {
  if (!uri || !uri.startsWith("content://")) return uri;

  try {
    const docMatch = uri.match(/\/document\/(.+)$/);
    const treeMatch = uri.match(/\/tree\/([^/]+)/);
    const encoded = docMatch?.[1] ?? treeMatch?.[1];
    if (!encoded) return uri;
    const decoded = decodeURIComponent(encoded);
    const colonIdx = decoded.indexOf(":");
    return colonIdx >= 0 ? decoded.slice(colonIdx + 1) : decoded;
  } catch {
    return uri;
  }
};
