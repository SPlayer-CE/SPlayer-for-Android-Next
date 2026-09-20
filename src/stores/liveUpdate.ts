import { defineStore } from "pinia";
import { ref, computed } from "vue";
import type {
  LiveUpdateManifest,
  LiveUpdateFileEntry,
  LiveUpdateProgress,
  LiveUpdateCheckResult,
} from "@shared/types/liveUpdate";
import { isAndroid, isAndroidNative } from "@/utils/platform";
import { toast } from "@/composables/useToast";
import { EMBEDDED_API_ORIGIN } from "@/utils/embeddedApi";
import {
  LIVE_UPDATE_APPLIED_MANIFEST_KEY,
  LIVE_UPDATE_PUBLIC_KEY_BASE64,
  LIVE_UPDATE_SIGNATURE_FILE,
} from "@shared/constants/liveUpdate";

/** GitHub Releases 官方分发基址 */
const DEFAULT_UPDATE_HOST = "https://api.github.com/repos/SPlayer-CE/SPlayer-for-Android-Next";

export const useLiveUpdateStore = defineStore("liveUpdate", () => {
  /** 当前状态: idle | checking | available | downloading | ready | error */
  const status = ref<"idle" | "checking" | "available" | "downloading" | "ready" | "error">("idle");
  /** 最新远端 Manifest */
  const remoteManifest = ref<LiveUpdateManifest | null>(null);
  /** 需下载的文件差量清单 */
  const pendingFiles = ref<LiveUpdateFileEntry[]>([]);
  /** 下载与校验进度 */
  const progress = ref<LiveUpdateProgress>({
    downloadedBytes: 0,
    totalBytes: 0,
    completedFiles: 0,
    totalFiles: 0,
    percent: 0,
  });
  /** 错误信息 */
  const errorMessage = ref<string>("");

  /** 是否有可用差量热更 */
  const hasUpdate = computed(() => status.value === "available" || status.value === "ready");

  /**
   * 检查热更新
   * @param manual 是否手动触发（控制 toast 提示）
   * @param channel 渠道 "stable" | "nightly"
   */
  async function checkForUpdate(
    manual = false,
    channel: "stable" | "nightly" = "nightly",
  ): Promise<LiveUpdateCheckResult> {
    if (!isAndroid) {
      return { hasUpdate: false };
    }

    status.value = "checking";
    errorMessage.value = "";

    try {
      // 1. 获取最新 Release / Pre-release 信息
      const endpoint =
        channel === "nightly"
          ? `${DEFAULT_UPDATE_HOST}/releases/tags/nightly`
          : `${DEFAULT_UPDATE_HOST}/releases/latest`;

      const res = await fetch(endpoint, {
        headers: { Accept: "application/vnd.github.v3+json" },
      });

      if (!res.ok) {
        throw new Error(`获取更新配置失败 (HTTP ${res.status})`);
      }

      const releaseData = await res.json();
      const assets: Array<{ name: string; browser_download_url: string }> =
        releaseData.assets || [];
      const manifestAsset = assets.find((a) => a.name === "manifest.json");

      if (!manifestAsset) {
        status.value = "idle";
        if (manual) toast.info("当前已是最新版本");
        return { hasUpdate: false };
      }

      // 2. 拉取远程 manifest.json（保留原文用于验签）与独立签名 manifest.sig
      const manifestRes = await fetch(manifestAsset.browser_download_url);
      if (!manifestRes.ok) throw new Error("下载更新清单失败");
      const manifestRaw = await manifestRes.text();
      const manifest: LiveUpdateManifest = JSON.parse(manifestRaw);
      remoteManifest.value = manifest;

      const sigAsset = assets.find((a) => a.name === LIVE_UPDATE_SIGNATURE_FILE);
      let signature = "";
      if (sigAsset) {
        const sigRes = await fetch(sigAsset.browser_download_url);
        if (sigRes.ok) signature = (await sigRes.text()).trim();
      }

      // 验签：公钥已配置时强制校验；未配置（M0.3 密钥体系落地前）仅告警不阻断
      if (LIVE_UPDATE_PUBLIC_KEY_BASE64 !== "") {
        if (!signature) {
          status.value = "error";
          errorMessage.value = "更新清单缺少签名，拒绝应用";
          if (manual) toast.error("更新清单缺少签名，拒绝应用");
          return { hasUpdate: false };
        }
        const verifyRes = await fetch(`${EMBEDDED_API_ORIGIN}/api/liveUpdate/verify`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ manifestJson: manifestRaw, signature }),
        });
        const verifyData = (await verifyRes.json()) as { ok?: boolean; reason?: string };
        if (!verifyRes.ok || !verifyData.ok) {
          status.value = "error";
          errorMessage.value = `更新清单验签失败 (${verifyData.reason ?? "signature-mismatch"})`;
          if (manual) toast.error("更新清单验签失败，拒绝应用");
          return { hasUpdate: false };
        }
      } else {
        console.warn("[live-update] 验签公钥未配置（M0.3 前），本次跳过验签");
      }

      // 3. 校验 minNativeVersion (如果当前原生版本太低，提示需要整包更新 APK)
      // 注意：原生 APK 版本号通过 bridge 或 Capacitor App 插件获取
      const currentNativeCode = 1; // 默认基准，由原生注入
      if (manifest.minNativeVersion > currentNativeCode) {
        status.value = "available";
        return {
          hasUpdate: true,
          manifest,
          requireFullApkUpdate: true,
        };
      }

      // 4. 计算文件差量：本地 hash 表取自“已应用 manifest”的持久化记录；
      // 首次热更（尚无已应用记录）时本地视为空，差量即全量，属正确语义
      let localHashes: Record<string, string> = {};
      const appliedRaw = localStorage.getItem(LIVE_UPDATE_APPLIED_MANIFEST_KEY);
      if (appliedRaw) {
        try {
          const applied = JSON.parse(appliedRaw) as LiveUpdateManifest;
          localHashes = Object.fromEntries(
            Object.entries(applied.files ?? {}).map(([p, f]) => [p, f.sha256]),
          );
        } catch {
          localHashes = {};
        }
      }
      const diff: LiveUpdateFileEntry[] = [];
      let totalDiffSize = 0;

      for (const [relPath, fileEntry] of Object.entries(manifest.files)) {
        // 如果本地没有该文件，或者 sha256 不一致，加入差量下载列表
        if (localHashes[relPath] !== fileEntry.sha256) {
          diff.push(fileEntry);
          totalDiffSize += fileEntry.size;
        }
      }

      pendingFiles.value = diff;
      progress.value = {
        downloadedBytes: 0,
        totalBytes: totalDiffSize,
        completedFiles: 0,
        totalFiles: diff.length,
        percent: 0,
      };

      if (diff.length > 0) {
        status.value = "available";
        if (manual) toast.info(`检测到热更新: ${manifest.version} (${diff.length} 个文件待更新)`);
        return {
          hasUpdate: true,
          manifest,
          diffFiles: diff,
          diffBytes: totalDiffSize,
        };
      } else {
        status.value = "idle";
        if (manual) toast.info("当前静态资源已是最新");
        return { hasUpdate: false };
      }
    } catch (err: unknown) {
      status.value = "error";
      const msg = err instanceof Error ? err.message : String(err);
      errorMessage.value = msg;
      if (manual) toast.error(`检查更新异常: ${msg}`);
      return { hasUpdate: false };
    }
  }

  /**
   * 应用已下载的更新（请求原生层原子切换目录并重载 WebView）
   */
  async function applyUpdateAndReload(): Promise<void> {
    if (!isAndroidNative) {
      window.location.reload();
      return;
    }

    try {
      // 记录已应用 manifest，供下次检查做差量比对（修复 localHashes 恒空导致每次全量）
      if (remoteManifest.value) {
        localStorage.setItem(
          LIVE_UPDATE_APPLIED_MANIFEST_KEY,
          JSON.stringify(remoteManifest.value),
        );
      }
      // 向原生层发送原子切换信号：例如通过 window.api 或 Capacitor 自定义插件
      // 原生把 pending 目录原子 rename 为 active 目录 ，然后执行 webView.reload()
      toast.info("正在应用更新并重载...");
      window.location.reload();
    } catch {
      toast.error("应用更新失败");
    }
  }

  return {
    status,
    remoteManifest,
    pendingFiles,
    progress,
    errorMessage,
    hasUpdate,
    checkForUpdate,
    applyUpdateAndReload,
  };
});
