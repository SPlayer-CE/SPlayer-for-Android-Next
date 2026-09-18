import { inflateRawSync, inflateSync, unzipSync } from "zlib";
import { qrcDecrypt } from "./tripledes";

/**
 * QRC 解密密钥 - 24 字节
 * 来源: LDDC 项目
 */
const QRC_KEY = new Uint8Array(Buffer.from("!@#)(*$%123ZXC!@!@#)(NHL", "utf8"));

/**
 * 解密 QRC 歌词（云端版本）
 * 使用 LDDC 的 Triple DES 实现 + Zlib 解压
 *
 * @param encryptedQrc - 十六进制编码的加密歌词字符串
 * @returns 解密后的歌词文本
 */
export const decryptQrc = (encryptedQrc: string): string => {
  if (!encryptedQrc || encryptedQrc.trim() === "") {
    throw new Error("没有可解密的数据");
  }

  // Hex 转 Uint8Array
  const encryptedBuffer = Buffer.from(encryptedQrc, "hex");
  const encryptedData = new Uint8Array(encryptedBuffer);

  // Triple DES 解密
  const decrypted = qrcDecrypt(encryptedData, QRC_KEY);
  const decryptedBuffer = Buffer.from(decrypted);

  // Zlib 解压：依次尝试 inflate / raw inflate / gzip
  try {
    return inflateSync(decryptedBuffer).toString("utf8");
  } catch {
    // 尝试下一种
  }
  try {
    return inflateRawSync(decryptedBuffer).toString("utf8");
  } catch {
    // 尝试下一种
  }
  try {
    return unzipSync(decryptedBuffer).toString("utf8");
  } catch {
    // 尝试下一种
  }

  // 也可能本身就不是压缩数据
  const str = decryptedBuffer.toString("utf8");
  if (str.includes("[") || str.includes("<")) return str;

  throw new Error("无法解压数据");
};
