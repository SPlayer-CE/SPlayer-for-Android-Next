<script setup lang="ts">
import localforage from "localforage";
import { toast } from "@/composables/useToast";
import { dialog } from "@/composables/useDialog";
import bridge, { isAndroid, isAndroidPreview } from "@/services/bridge";
import { usePlaylistStore } from "@/stores/playlist";
import { useSettingsStore } from "@/stores/settings";
import { APP_VERSION } from "@/utils/config";

defineOptions({ inheritAttrs: false });

const { t } = useI18n();

type ActionKey = "backup" | "restore" | "resetSettings" | "resetAll";

interface ActionRow {
  key: ActionKey;
  buttonKey: string;
  /** error 类型按钮（红色） */
  destructive?: boolean;
}

const rows: ActionRow[] = [
  { key: "backup", buttonKey: "backup.button" },
  { key: "restore", buttonKey: "restore.button" },
  { key: "resetSettings", buttonKey: "resetSettings.button" },
  { key: "resetAll", buttonKey: "resetAll.button", destructive: true },
];

const running = ref<ActionKey | null>(null);

/** 备份文件标识：恢复时用以辨识是否本应用导出的 JSON */
const BACKUP_TYPE = "splayer-settings";
/** 渲染端 settings store 持久化到 localStorage 的 key（与 pinia store id 同名） */
const SETTINGS_STORE_KEY = "settings";

interface BackupPayload {
  type: typeof BACKUP_TYPE;
  /** 导出时的软件版本号 */
  appVersion: string;
  exportedAt: number;
  /** 主进程 SystemConfig */
  main: unknown;
  /** 渲染端 settings store 持久化 state */
  renderer: { settings?: unknown };
}

/** 校验是否本应用导出的备份 */
const isBackupPayload = (data: unknown): data is BackupPayload => {
  if (!data || typeof data !== "object") return false;
  const obj = data as Record<string, unknown>;
  return obj.type === BACKUP_TYPE && "main" in obj;
};

/** 读取 localStorage 中已持久化的 settings state */
const readPersistedSettings = (): unknown => {
  const raw = localStorage.getItem(SETTINGS_STORE_KEY);
  if (raw === null) return undefined;
  try {
    return JSON.parse(raw);
  } catch {
    return undefined;
  }
};

/** 备份文件名时间戳与 PC 端保持一致 */
const buildBackupFileName = (): string => {
  const stamp = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
  return `splayer-settings-${stamp}.json`;
};

/** 安卓导出：原生经 bridge.system.saveFile 落盘到下载目录，预览用浏览器下载 */
const exportJsonOnAndroid = async (json: string): Promise<void> => {
  const fileName = buildBackupFileName();
  if (isAndroidPreview) {
    downloadJsonViaBrowser(json, fileName);
    return;
  }
  const raw = new TextEncoder().encode(json);
  const data = raw.buffer.slice(raw.byteOffset, raw.byteOffset + raw.byteLength) as ArrayBuffer;
  try {
    const result = await bridge.system.saveFile(data, fileName);
    if (result.success) return;
    throw new Error(result.error ?? "saveFile failed");
  } catch (e) {
    console.warn("[StorageManager] 安卓原生导出失败", e);
    throw e;
  }
};

/** 预览环境兜底：Blob + a.download 触发浏览器下载 */
const downloadJsonViaBrowser = (json: string, fileName: string): void => {
  const blob = new Blob([json], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
};

/** 安卓恢复：系统文件选择器读取用户挑选的备份 JSON（focus 回退兼容无 cancel 事件的 WebView） */
const pickBackupFileOnAndroid = (): Promise<File | null> => {
  return new Promise((resolve) => {
    const input = document.createElement("input");
    input.type = "file";
    input.accept = ".json,application/json";
    input.style.display = "none";
    let settled = false;
    const done = (file: File | null): void => {
      if (settled) return;
      settled = true;
      input.remove();
      resolve(file);
    };
    input.addEventListener("change", () => done(input.files?.[0] ?? null));
    input.addEventListener("cancel", () => done(null), { once: true });
    // 用户取消选择时 window focus 回来且没选文件
    window.addEventListener(
      "focus",
      () => {
        setTimeout(() => {
          if (!input.files || input.files.length === 0) done(null);
        }, 1000);
      },
      { once: true },
    );
    document.body.appendChild(input);
    input.click();
  });
};

/** 应用恢复后的备份内容并重启生效，main 配置写入失败返回 false */
const applyBackupPayload = async (payload: BackupPayload): Promise<boolean> => {
  if (isAndroid) {
    try {
      await bridge.config.replaceAll(payload.main);
    } catch (e) {
      console.warn("[StorageManager] 恢复主进程配置失败", e);
      toast.error(t("settings.restore.failed"));
      return false;
    }
  } else {
    await window.api.config.replaceAll(payload.main);
  }
  const settingsState = payload.renderer?.settings;
  if (settingsState !== undefined) {
    localStorage.setItem(SETTINGS_STORE_KEY, JSON.stringify(settingsState));
  }
  if (isAndroid) {
    await bridge.system.relaunch();
    if (isAndroidPreview) window.location.reload();
    return true;
  }
  await window.api.system.relaunch();
  return true;
};

/** 重置主进程配置，安卓走嵌入式 API，失败返回 false */
const resetMainConfig = async (): Promise<boolean> => {
  try {
    if (isAndroid) await bridge.config.reset();
    else await window.api.config.reset();
    return true;
  } catch (e) {
    console.warn("[StorageManager] 重置主进程配置失败", e);
    toast.error(t("settings.resetSettings.failed"));
    return false;
  }
};

/** 备份 */
const handleBackup = async (): Promise<void> => {
  try {
    const main = isAndroid ? await bridge.config.getAll() : await window.api.config.getAll();
    const payload: BackupPayload = {
      type: BACKUP_TYPE,
      appVersion: APP_VERSION,
      exportedAt: Date.now(),
      main,
      renderer: { settings: readPersistedSettings() },
    };
    const result = isAndroid
      ? (await exportJsonOnAndroid(JSON.stringify(payload, null, 2)), { ok: true as const })
      : await window.api.config.exportToFile(payload);
    if (!result.ok) {
      if (result.reason === "writeFailed") toast.error(t("settings.backup.failed"));
      return;
    }
  } catch (e) {
    console.warn("[StorageManager] 备份失败", e);
    toast.error(t("settings.backup.failed"));
    return;
  }
  toast.success(t("settings.backup.exported"));
};

/** 恢复 */
const handleRestore = async (): Promise<void> => {
  if (isAndroid) {
    const file = await pickBackupFileOnAndroid();
    if (!file) return;
    let parsed: unknown;
    try {
      parsed = JSON.parse(await file.text());
    } catch {
      toast.error(t("settings.restore.invalid"));
      return;
    }
    if (!isBackupPayload(parsed)) {
      toast.error(t("settings.restore.invalid"));
      return;
    }
    const confirmed = await dialog.confirm({
      title: t("settings.restore.confirmTitle"),
      content: t("settings.restore.confirmDesc"),
      type: "warning",
    });
    if (!confirmed) return;
    if (!(await applyBackupPayload(parsed))) return;
    return;
  }
  const picked = await window.api.config.importFromFile();
  if (!picked.ok) {
    if (picked.reason === "parseFailed") toast.error(t("settings.restore.invalid"));
    else if (picked.reason === "readFailed") toast.error(t("settings.restore.failed"));
    return;
  }
  if (!isBackupPayload(picked.data)) {
    toast.error(t("settings.restore.invalid"));
    return;
  }
  const confirmed = await dialog.confirm({
    title: t("settings.restore.confirmTitle"),
    content: t("settings.restore.confirmDesc"),
    type: "warning",
  });
  if (!confirmed) return;

  await applyBackupPayload(picked.data as BackupPayload);
};

/** 重置设置 */
const handleResetSettings = async (): Promise<void> => {
  const confirmed = await dialog.confirm({
    title: t("settings.resetSettings.confirmTitle"),
    content: t("settings.resetSettings.confirmDesc"),
    type: "warning",
  });
  if (!confirmed) return;
  const settingsStore = useSettingsStore();
  settingsStore.$reset();
  if (!(await resetMainConfig())) return;
  toast.success(t("settings.resetSettings.done"));
};

/** 清除全部数据 */
const handleResetAll = async (): Promise<void> => {
  const confirmed = await dialog.confirm({
    title: t("settings.resetAll.confirmTitle"),
    content: t("settings.resetAll.confirmDesc"),
    type: "error",
  });
  if (!confirmed) return;
  const stores = ["playlists", "queue", "library"];
  await Promise.all(
    stores.map((name) => localforage.createInstance({ name: "splayer", storeName: name }).clear()),
  );
  const settingsStore = useSettingsStore();
  settingsStore.$reset();
  if (!(await resetMainConfig())) return;
  const playlistStore = usePlaylistStore();
  await playlistStore.clear();
  toast.success(t("settings.resetAll.done"));
};

/** 按 key 分发并互斥执行 */
const runAction = async (key: ActionKey): Promise<void> => {
  if (running.value) return;
  running.value = key;
  try {
    if (key === "backup") await handleBackup();
    else if (key === "restore") await handleRestore();
    else if (key === "resetSettings") await handleResetSettings();
    else await handleResetAll();
  } finally {
    running.value = null;
  }
};
</script>

<template>
  <div class="flex flex-col gap-3">
    <div
      v-for="row in rows"
      :key="row.key"
      class="rounded-xl bg-surface-panel border border-solid border-outline-variant/15 px-4 py-3.5 flex items-center justify-between gap-4"
    >
      <div class="min-w-0 flex-1">
        <div class="text-base">{{ t(`settings.${row.key}.label`) }}</div>
        <div class="text-sm text-on-surface-variant/70 mt-0.5">
          {{ t(`settings.${row.key}.description`) }}
        </div>
      </div>
      <SButton
        :type="row.destructive ? 'error' : 'primary'"
        variant="secondary"
        :loading="running === row.key"
        :disabled="running !== null && running !== row.key"
        @click="runAction(row.key)"
      >
        {{ t(`settings.${row.buttonKey}`) }}
      </SButton>
    </div>
  </div>
</template>
