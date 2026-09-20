/**
 * 差异化热更新（Issue #26）共享常量。
 */

/**
 * 热更新验签公钥（Ed25519，base64 编码的 SPKI DER）。
 * M0.3 密钥体系落地前为空串：此时客户端仅告警、不强制验签；
 * 一旦配置公钥，manifest 必须携带可验证签名，否则拒绝应用。
 */
export const LIVE_UPDATE_PUBLIC_KEY_BASE64 = "";

/** 更新清单文件名（Release 附件 / 本地缓存） */
export const LIVE_UPDATE_MANIFEST_FILE = "manifest.json";
/** 清单签名文件名（与 manifest.json 分离，验签对 manifest.json 原文进行） */
export const LIVE_UPDATE_SIGNATURE_FILE = "manifest.sig";
/** 客户端持久化"已应用 manifest"的 localStorage key，供后续差量比对 */
export const LIVE_UPDATE_APPLIED_MANIFEST_KEY = "liveUpdate.appliedManifest";
