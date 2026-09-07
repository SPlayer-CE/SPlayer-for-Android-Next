<script setup lang="ts">
import { toast } from "@/composables/useToast";
import { useLanSyncHost } from "@/composables/useLanSyncHost";
import {
  getLanSyncHostIp,
  isLanSyncReceiver,
  onLanSyncRoleChanged,
  setLanSyncHostIp,
} from "@/composables/useLanSyncRole";
import bridge, { isAndroidPreview } from "@/services/bridge";
import IconLucideWifi from "~icons/lucide/wifi";
import IconLucideWifiOff from "~icons/lucide/wifi-off";
import IconLucidePlus from "~icons/lucide/plus";
import IconLucideTrash2 from "~icons/lucide/trash-2";
import IconLucideMonitor from "~icons/lucide/monitor";
import IconLucideBroadcast from "~icons/lucide/radio";
import IconLucideCopy from "~icons/lucide/copy";
import IconLucideGlobe from "~icons/lucide/globe";
import IconLucideUser from "~icons/lucide/user";
import IconLucideHeadphones from "~icons/lucide/headphones";
import IconLucideLogIn from "~icons/lucide/log-in";
import IconLucideLogOut from "~icons/lucide/log-out";

defineOptions({ inheritAttrs: false });

const { t } = useI18n();

const joinedHostIp = ref<string>("");
const joinHostInput = ref("");

const loadJoinedHost = (): void => {
  joinedHostIp.value = getLanSyncHostIp();
};
loadJoinedHost();
const stopLanSyncRoleWatch = onLanSyncRoleChanged((hostIp) => {
  joinedHostIp.value = hostIp;
  if (hostIp) syncHost.stop();
});
onUnmounted(stopLanSyncRoleWatch);

const joinHost = (): void => {
  const ip = joinHostInput.value.trim();
  if (!ip || !/^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(ip)) {
    toast.error(t("settings.lanShare.toast.invalidIp"));
    return;
  }
  setLanSyncHostIp(ip);
  syncHost.stop();
  joinHostInput.value = "";
  toast.success(t("settings.lanShare.toast.joined"));
};

const leaveHost = (): void => {
  setLanSyncHostIp("");
  toast.success(t("settings.lanShare.toast.left"));
};

interface LanDevice {
  ip: string;
  name?: string;
  sharedLogin: boolean;
  shareCollab: boolean;
  addedAt: number;
}

const loading = ref(false);
const enabled = ref(false);
const collabEnabled = ref(false);
const shareUserInfo = ref(false);
const devices = ref<LanDevice[]>([]);
const localIPs = ref<Array<{ name: string; address: string; family: "IPv4" | "IPv6" }>>([]);
const apiPort = ref(18098);
const newDeviceIp = ref("");
const newDeviceName = ref("");
const showAddDialog = ref(false);

const syncHost = useLanSyncHost();

const refresh = async (): Promise<void> => {
  if (isAndroidPreview) return;
  try {
    const [status, devs, ips] = await Promise.all([
      bridge.lanShare.getStatus(),
      bridge.lanShare.getDevices(),
      bridge.lanShare.getLocalIPs(),
    ]);
    enabled.value = status.enabled;
    collabEnabled.value = status.collabEnabled;
    shareUserInfo.value = status.shareUserInfo;
    devices.value = devs.devices;
    localIPs.value = ips.ips;
    apiPort.value = ips.port ?? 18098;
    if (collabEnabled.value && enabled.value && !isLanSyncReceiver()) syncHost.start();
  } catch {}
};

let refreshTimer: ReturnType<typeof setInterval> | null = null;
onMounted(() => {
  if (isAndroidPreview) return;
  void refresh();
  refreshTimer = setInterval(() => void refresh(), 3000);
});
onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer);
});

const toggleEnabled = async (): Promise<void> => {
  loading.value = true;
  try {
    const result = await bridge.lanShare.setEnabled(!enabled.value);
    enabled.value = result.enabled;
    if (!enabled.value) {
      collabEnabled.value = false;
      syncHost.stop();
    }
    toast.success(
      enabled.value ? t("settings.lanShare.toast.enabled") : t("settings.lanShare.toast.disabled"),
    );
  } finally {
    loading.value = false;
  }
};

const toggleCollab = async (): Promise<void> => {
  if (!collabEnabled.value && isLanSyncReceiver()) {
    syncHost.stop();
    toast.warning(t("settings.lanShare.toast.receiverCannotBroadcast"));
    return;
  }
  const result = await bridge.lanShare.setCollabEnabled(!collabEnabled.value);
  collabEnabled.value = result.collabEnabled;
  if (collabEnabled.value && !isLanSyncReceiver()) syncHost.start();
  else syncHost.stop();
  toast.success(
    collabEnabled.value
      ? t("settings.lanShare.toast.collabEnabled")
      : t("settings.lanShare.toast.collabDisabled"),
  );
};

const handleAddDevice = async (): Promise<void> => {
  const ip = newDeviceIp.value.trim();
  if (!ip || !/^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(ip)) {
    toast.error(t("settings.lanShare.toast.invalidIp"));
    return;
  }
  const result = await bridge.lanShare.addDevice(ip, newDeviceName.value.trim() || undefined);
  devices.value = result.devices;
  newDeviceIp.value = "";
  newDeviceName.value = "";
  showAddDialog.value = false;
  toast.success(t("settings.lanShare.toast.deviceAdded"));
};

const handleRemoveDevice = async (ip: string): Promise<void> => {
  const result = await bridge.lanShare.removeDevice(ip);
  devices.value = result.devices;
};

const toggleDeviceLogin = async (device: LanDevice): Promise<void> => {
  const result = await bridge.lanShare.shareLogin(device.ip, !device.sharedLogin);
  const idx = devices.value.findIndex((d) => d.ip === device.ip);
  if (idx >= 0) devices.value[idx] = { ...devices.value[idx], ...result.device };
};

const toggleDeviceCollab = async (device: LanDevice): Promise<void> => {
  const result = await bridge.lanShare.setDeviceCollab(device.ip, !device.shareCollab);
  const idx = devices.value.findIndex((item) => item.ip === device.ip);
  if (idx >= 0) devices.value[idx] = { ...devices.value[idx], ...result.device };
};

const fillIp = (address: string): void => {
  newDeviceIp.value = address;
};

const toggleShareUserInfo = async (): Promise<void> => {
  const result = await bridge.lanShare.setShareUserInfo(!shareUserInfo.value);
  shareUserInfo.value = result.shareUserInfo;
  toast.success(
    shareUserInfo.value
      ? t("settings.lanShare.toast.shareEnabled")
      : t("settings.lanShare.toast.shareDisabled"),
  );
};

/** 构造完整 HTTP 链接 */
const buildUrl = (address: string, family: "IPv4" | "IPv6"): string => {
  const host = family === "IPv6" ? `[${address}]` : address;
  return `http://${host}:${apiPort.value}`;
};

/** 复制链接到剪贴板，提示安全警告 */
const copyUrl = async (address: string, family: "IPv4" | "IPv6"): Promise<void> => {
  const url = buildUrl(address, family);
  try {
    await window.api.system.writeClipboardText(url);
    toast.success(url);
    toast.warning(t("settings.lanShare.toast.securityWarning"));
  } catch {
    toast.error(t("common.copyFailed", "复制失败"));
  }
};
</script>

<template>
  <div class="lan-share-panel">
    <!-- 主开关 -->
    <div v-if="!isAndroidPreview" class="lan-share-row">
      <div
        class="lan-share-icon"
        :class="enabled ? 'bg-primary/12 text-primary' : 'bg-on-surface/6 text-on-surface-variant'"
      >
        <Component :is="enabled ? IconLucideWifi : IconLucideWifiOff" class="size-5" />
      </div>
      <div class="lan-share-label">
        <div class="text-sm font-medium text-on-surface">
          {{ t("settings.lanShare.mainToggle") }}
        </div>
        <div class="text-xs text-on-surface-variant/60">
          {{ t("settings.lanShare.mainToggleDesc") }}
        </div>
      </div>
      <SSwitch :model-value="enabled" :disabled="loading" @update:model-value="toggleEnabled" />
    </div>

    <SDivider v-if="!isAndroidPreview" />

    <div
      :class="{ 'opacity-50 pointer-events-none': !isAndroidPreview && !enabled }"
      class="transition-opacity duration-300 flex flex-col gap-3.5"
    >
      <!-- 服务地址 -->
      <div v-if="!isAndroidPreview" class="lan-share-section">
        <div class="section-title">{{ t("settings.lanShare.serverInfo") }}</div>
        <div v-if="localIPs.length > 0" class="flex flex-col gap-1">
          <div v-for="item in localIPs" :key="item.address" class="url-row">
            <div class="url-info">
              <Component
                :is="item.family === 'IPv6' ? IconLucideGlobe : IconLucideMonitor"
                class="size-3.5 shrink-0"
                :class="item.family === 'IPv6' ? 'text-primary/60' : 'text-on-surface-variant/40'"
              />
              <span class="text-xs text-on-surface-variant/50 shrink-0">{{ item.name }}</span>
              <span
                class="text-xs px-1 py-0.5 rounded bg-on-surface/5 text-on-surface-variant/70 shrink-0"
              >
                {{ item.family }}
              </span>
            </div>
            <div class="url-link" @click="copyUrl(item.address, item.family)">
              <span class="url-text">{{ buildUrl(item.address, item.family) }}</span>
              <IconLucideCopy class="size-3 text-on-surface-variant/40 shrink-0" />
            </div>
          </div>
        </div>
        <div v-else class="text-xs text-on-surface-variant/40">
          {{ t("settings.lanShare.noNetwork") }}
        </div>
      </div>

      <SDivider v-if="!isAndroidPreview" />

      <!-- 设备列表 -->
      <div v-if="!isAndroidPreview" class="lan-share-section">
        <div class="flex items-center justify-between mb-1">
          <div class="section-title">
            {{ t("settings.lanShare.deviceList") }}
            <span class="opacity-50">({{ devices.length }})</span>
          </div>
          <SButton variant="ghost" size="tiny" circle @click="showAddDialog = !showAddDialog">
            <template #icon><IconLucidePlus class="size-3.5" /></template>
          </SButton>
        </div>

        <Transition
          enter-active-class="transition-all duration-200 ease-out"
          leave-active-class="transition-all duration-150 ease-in"
          enter-from-class="opacity-0 -translate-y-1"
          leave-to-class="opacity-0 -translate-y-1"
        >
          <div v-if="showAddDialog" class="add-device-form">
            <div class="flex gap-2">
              <SInput
                v-model="newDeviceIp"
                :placeholder="t('settings.lanShare.ipPlaceholder')"
                size="small"
                class="flex-1"
              />
              <SInput
                v-model="newDeviceName"
                :placeholder="t('settings.lanShare.namePlaceholder')"
                size="small"
                class="flex-1"
              />
            </div>
            <div v-if="localIPs.length > 0" class="flex flex-wrap gap-1 items-center">
              <span class="text-xs text-on-surface-variant/40">
                {{ t("settings.lanShare.quickFill") }}
              </span>
              <button
                v-for="item in localIPs"
                :key="item.address"
                class="ip-chip"
                @click="fillIp(item.address)"
              >
                {{ item.address }}
              </button>
            </div>
            <div class="flex justify-end gap-2">
              <SButton variant="ghost" size="tiny" @click="showAddDialog = false">
                {{ t("common.cancel") }}
              </SButton>
              <SButton type="primary" size="tiny" @click="handleAddDevice">
                {{ t("common.confirm") }}
              </SButton>
            </div>
          </div>
        </Transition>

        <div
          v-if="devices.length === 0"
          class="text-xs text-on-surface-variant/35 py-3 text-center"
        >
          {{ t("settings.lanShare.noDevices") }}
        </div>
        <div v-for="device in devices" :key="device.ip" class="lan-share-device">
          <div class="flex-1 min-w-0">
            <div class="text-sm text-on-surface truncate">{{ device.name || device.ip }}</div>
            <div class="text-xs text-on-surface-variant/40 font-mono">{{ device.ip }}</div>
          </div>
          <div class="flex flex-col items-end gap-1.5 shrink-0">
            <div class="flex items-center gap-2">
              <span class="text-xs text-on-surface-variant/50">
                {{ t("settings.lanShare.shareCollab") }}
              </span>
              <SSwitch
                :model-value="device.shareCollab"
                size="small"
                @update:model-value="toggleDeviceCollab(device)"
              />
            </div>
            <div class="flex items-center gap-2">
              <span class="text-xs text-on-surface-variant/50">
                {{ t("settings.lanShare.shareLogin") }}
              </span>
              <SSwitch
                :model-value="device.sharedLogin"
                size="small"
                @update:model-value="toggleDeviceLogin(device)"
              />
            </div>
          </div>
          <div class="shrink-0">
            <SButton
              variant="ghost"
              size="tiny"
              circle
              class="text-error/50 hover:text-error"
              @click="handleRemoveDevice(device.ip)"
            >
              <template #icon><IconLucideTrash2 class="size-3" /></template>
            </SButton>
          </div>
        </div>
      </div>

      <SDivider v-if="!isAndroidPreview" />

      <!-- 共享用户信息 -->
      <div v-if="!isAndroidPreview" class="lan-share-row">
        <div
          class="lan-share-icon"
          :class="
            shareUserInfo ? 'bg-primary/12 text-primary' : 'bg-on-surface/6 text-on-surface-variant'
          "
        >
          <IconLucideUser class="size-5" />
        </div>
        <div class="lan-share-label">
          <div class="text-sm font-medium text-on-surface">
            {{ t("settings.lanShare.shareUserInfo") }}
          </div>
          <div class="text-xs text-on-surface-variant/60">
            {{ t("settings.lanShare.shareUserInfoDesc") }}
          </div>
        </div>
        <SSwitch :model-value="shareUserInfo" @update:model-value="toggleShareUserInfo" />
      </div>

      <SDivider v-if="!isAndroidPreview" />

      <!-- 协同广播 -->
      <div v-if="!isAndroidPreview" class="lan-share-row">
        <div
          class="lan-share-icon"
          :class="
            collabEnabled ? 'bg-primary/12 text-primary' : 'bg-on-surface/6 text-on-surface-variant'
          "
        >
          <IconLucideBroadcast class="size-5" />
        </div>
        <div class="lan-share-label">
          <div class="text-sm font-medium text-on-surface">
            {{ t("settings.lanShare.collabToggle") }}
          </div>
          <div class="text-xs text-on-surface-variant/60">
            {{ t("settings.lanShare.collabToggleDesc") }}
          </div>
        </div>
        <SSwitch
          :model-value="collabEnabled"
          :disabled="!!joinedHostIp"
          @update:model-value="toggleCollab"
        />
      </div>

      <SDivider v-if="!isAndroidPreview" />

      <!-- 作为从设备加入主机 -->
      <div class="lan-share-section">
        <div class="flex items-center gap-2 mb-1.5">
          <IconLucideHeadphones class="size-4 text-primary/70" />
          <div class="section-title m-0">{{ t("settings.lanShare.joinTitle") }}</div>
        </div>
        <div class="text-xs text-on-surface-variant/60 mb-2">
          {{ t("settings.lanShare.joinDesc") }}
        </div>

        <!-- 已加入状态 -->
        <div v-if="joinedHostIp" class="lan-share-device joined-host">
          <div class="flex-1 min-w-0">
            <div class="text-sm text-on-surface truncate">{{ t("settings.lanShare.joined") }}</div>
            <div class="text-xs text-on-surface-variant/40 font-mono">{{ joinedHostIp }}</div>
          </div>
          <SButton
            variant="ghost"
            size="tiny"
            class="text-error/70 hover:text-error"
            @click="leaveHost"
          >
            <template #icon><IconLucideLogOut class="size-3.5" /></template>
            {{ t("settings.lanShare.leaveBtn") }}
          </SButton>
        </div>

        <!-- 输入主机 IP -->
        <div v-else class="join-host-form">
          <SInput
            v-model="joinHostInput"
            :placeholder="t('settings.lanShare.hostIpPlaceholder')"
            size="small"
            class="flex-1"
            @keydown.enter="joinHost"
          />
          <SButton type="primary" size="small" @click="joinHost">
            <template #icon><IconLucideLogIn class="size-3.5" /></template>
            {{ t("settings.lanShare.joinBtn") }}
          </SButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.lan-share-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.lan-share-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.lan-share-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.lan-share-label {
  flex: 1;
  min-width: 0;
}
.lan-share-section {
  padding: 0 2px;
}
.section-title {
  font-size: 0.75rem;
  font-weight: 500;
  color: rgb(var(--s-on-surface-variant) / 0.7);
  margin-bottom: 6px;
}
.add-device-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  border-radius: 8px;
  background: rgba(var(--s-on-surface), 0.03);
  margin-bottom: 8px;
}
.ip-chip {
  font-size: 0.75rem;
  padding: 2px 6px;
  border-radius: 4px;
  background: rgba(var(--s-on-surface), 0.05);
  color: rgb(var(--s-on-surface-variant));
  cursor: pointer;
  border: none;
  transition: background-color 0.15s;
}
.ip-chip:hover {
  background: rgba(var(--s-on-surface), 0.1);
}
.lan-share-device {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  transition: background-color 0.15s;
}
.lan-share-device:hover {
  background: rgba(var(--s-on-surface), 0.03);
}
.joined-host {
  background: rgba(var(--s-primary), 0.06);
  border: 1px solid rgba(var(--s-primary), 0.15);
}
.join-host-form {
  display: flex;
  gap: 8px;
  align-items: center;
}
.url-row {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.url-info {
  display: flex;
  align-items: center;
  gap: 6px;
}
.url-link {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 6px;
  transition: background-color 0.15s;
  font-family: ui-monospace, monospace;
}
.url-link:hover {
  background: rgba(var(--s-on-surface), 0.05);
}
.url-text {
  font-size: 0.75rem;
  color: rgb(var(--s-primary));
  font-weight: 500;
  word-break: break-all;
}
</style>
