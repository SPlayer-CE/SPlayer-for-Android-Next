import { registerPlugin, type PluginListenerHandle } from "@capacitor/core";

export interface LanDevice {
  ip: string;
  name?: string;
  sharedLogin: boolean;
  shareCollab: boolean;
  addedAt: number;
}

export interface AndroidLanSharePlugin {
  getStatus(): Promise<{
    enabled: boolean;
    collabEnabled: boolean;
    shareUserInfo: boolean;
    deviceCount: number;
    sharedCount: number;
    serverIp: string;
    wsToken: string;
  }>;
  setEnabled(options: { enabled: boolean }): Promise<{ ok: boolean; enabled: boolean }>;
  setCollabEnabled(options: { enabled: boolean }): Promise<{ ok: boolean; collabEnabled: boolean }>;
  setShareUserInfo(options: { enabled: boolean }): Promise<{ ok: boolean; shareUserInfo: boolean }>;
  getDevices(): Promise<{ ok: boolean; devices: LanDevice[] }>;
  addDevice(options: { ip: string; name?: string }): Promise<{ ok: boolean; devices: LanDevice[] }>;
  removeDevice(options: { ip: string }): Promise<{ ok: boolean; devices: LanDevice[] }>;
  shareLogin(options: {
    ip: string;
    shared: boolean;
  }): Promise<{ ok: boolean; device?: LanDevice }>;
  setDeviceCollab(options: {
    ip: string;
    enabled: boolean;
  }): Promise<{ ok: boolean; device: LanDevice }>;
  broadcastPlayback(state: Record<string, unknown>): Promise<{ ok: boolean }>;
  /** 推送主机播放队列快照（已序列化 JSON），供 LAN 从设备经 /api/lanShare/getQueue 拉取 */
  updateQueue(options: { json: string }): Promise<{ ok: boolean }>;
  /** 推送主机当前歌词快照（已序列化 JSON），供 LAN 从设备经 /api/lanShare/getLyric 拉取 */
  updateLyric(options: { json: string }): Promise<{ ok: boolean }>;
  getLocalIPs(): Promise<{
    ok: boolean;
    ips: Array<{ name: string; address: string; family: "IPv4" | "IPv6" }>;
    port: number;
  }>;

  addListener(
    eventName: "onCommand",
    listenerFunc: (event: {
      type?: string;
      action?: string;
      value?: number;
      deviceName?: string;
    }) => void,
  ): Promise<PluginListenerHandle>;
}

export const AndroidLanShare = registerPlugin<AndroidLanSharePlugin>("AndroidLanShare");
