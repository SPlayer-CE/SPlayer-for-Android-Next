/**
 * 差异化热更新 Manifest 生成与 Ed25519 签名脚本
 *
 * 用法:
 *   tsx scripts/generate-live-update-manifest.ts <dist_dir> <output_dir> [--version <ver>] [--min-native <code\>] [--channel <stable|nightly>] [--private-key <pem_or_env>]
 *
 * 行为:
 * 1. 遍历 dist/ 静态构建产物，为每一个文件计算 sha256 和 size；
 * 2. 组装符合规范的 LiveUpdateManifest；
 * 3. 若提供 Ed25519 私钥，则对 manifest 规范化 JSON 内容签名，生成 manifest.sig 与 manifest.json；
 * 4. 输出打包目录便于 CI 归档上传到 GitHub Release / CDN。
 */

import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import type { LiveUpdateFileEntry, LiveUpdateManifest } from "../shared/types/liveUpdate";

// 规范命令行入参解析
const args = process.argv.slice(2);
const distDir = path.resolve(args[0] || "dist/capacitor");
const outputDir = path.resolve(args[1] || "dist-live-update");

function getArgValue(name: string, fallback = ""): string {
  const idx = args.indexOf(name);
  if (idx !== -1 && idx + 1 < args.length) {
    return args[idx + 1];
  }
  return fallback;
}

const version = getArgValue("--version") || process.env.GITHUB_REF_NAME || "1.0.0-nightly";
const minNativeStr = getArgValue("--min-native") || process.env.MIN_NATIVE_VERSION || "1";
const minNativeVersion = parseInt(minNativeStr, 10) || 1;
const channel = (getArgValue("--channel") ||
  (version.includes("nightly") ? "nightly" : "stable")) as "stable" | "nightly";
const privateKeyInput = getArgValue("--private-key") || process.env.LIVE_UPDATE_PRIVATE_KEY || "";

function computeSha256(filePath: string): string {
  const content = fs.readFileSync(filePath);
  return crypto.createHash("sha256").update(content).digest("hex");
}

function walkDir(
  currentDir: string,
  baseDir: string,
  entries: Record<string, LiveUpdateFileEntry>,
): void {
  const list = fs.readdirSync(currentDir);
  for (const item of list) {
    const fullPath = path.join(currentDir, item);
    const stat = fs.statSync(fullPath);
    if (stat.isDirectory()) {
      walkDir(fullPath, baseDir, entries);
    } else if (stat.isFile()) {
      const relPath = path.relative(baseDir, fullPath).replace(/\\/g, "/");
      // 忽略不需要的系统隐藏文件
      if (relPath.startsWith(".") || relPath.includes("/.")) continue;
      entries[relPath] = {
        path: relPath,
        sha256: computeSha256(fullPath),
        size: stat.size,
      };
    }
  }
}

async function main() {
  if (!fs.existsSync(distDir)) {
    console.error(`[live-update] dist directory not found: ${distDir}`);
    process.exit(1);
  }

  if (!fs.existsSync(outputDir)) {
    fs.mkdirSync(outputDir, { recursive: true });
  }

  console.log(`[live-update] Scanning static assets in: ${distDir}`);
  const files: Record<string, LiveUpdateFileEntry> = {};
  walkDir(distDir, distDir, files);

  const fileCount = Object.keys(files).length;
  console.log(`[live-update] Indexed ${fileCount} files.`);

  const manifest: LiveUpdateManifest = {
    version,
    minNativeVersion,
    channel,
    timestamp: Date.now(),
    files,
  };

  // 生成规范化的待签名字串 (key 排序序列化以确保签名确定性)
  // 对 manifest 进行 key 排序序列化以确保签名确定性
  const sortedKeys = Object.keys(manifest).sort();
  const sortedManifest: Record<string, unknown> = {};
  for (const k of sortedKeys) {
    sortedManifest[k] = (manifest as Record<string, unknown>)[k];
  }
  const canonicalJson = JSON.stringify(sortedManifest, null, 2);
  const manifestPath = path.join(outputDir, "manifest.json");
  fs.writeFileSync(manifestPath, canonicalJson, "utf-8");
  console.log(`[live-update] Wrote manifest to: ${manifestPath}`);

  // 如果提供了 Ed25519 私钥，则进行签名
  if (privateKeyInput) {
    try {
      let privateKeyPem = privateKeyInput;
      // 如果传入的是 base64 字符串或者文件路径
      if (fs.existsSync(privateKeyInput)) {
        privateKeyPem = fs.readFileSync(privateKeyInput, "utf-8");
      } else if (
        !privateKeyInput.includes("BEGIN PRIVATE KEY") &&
        !privateKeyInput.includes("BEGIN ED25519 PRIVATE KEY")
      ) {
        try {
          const decoded = Buffer.from(privateKeyInput, "base64").toString("utf-8");
          if (decoded.includes("PRIVATE KEY")) privateKeyPem = decoded;
        } catch {}
      }

      const signer = crypto.createSign(undefined);
      signer.update(canonicalJson);
      const signature = signer.sign(privateKeyPem, "base64");

      const sigPath = path.join(outputDir, "manifest.sig");
      fs.writeFileSync(sigPath, signature, "utf-8");
      console.log(
        `[live-update] Manifest signed successfully with Ed25519. Signature saved to: ${sigPath}`,
      );

      // 同时也可将 signature 嵌入更新版 manifest 中
      manifest.signature = signature;
      fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2), "utf-8");
    } catch (signErr) {
      console.warn(`[live-update] Warning: Failed to sign manifest with provided key:`, signErr);
    }
  } else {
    console.log(`[live-update] No private key provided. Manifest written without signature.`);
  }

  // 生成发布打包 zip (包含全部静态文件与 manifest)
  console.log(`[live-update] Manifest generation finished.`);
}

main().catch((err) => {
  console.error("[live-update] Error generating live update manifest:", err);
  process.exit(1);
});
