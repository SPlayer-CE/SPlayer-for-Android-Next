export const LAN_SYNC_HOST_STORAGE_KEY = "splayer/lan-sync-host";
export const LAN_SYNC_ROLE_EVENT = "splayer:lan-sync-role-changed";
/** 浏览器预览页面是否已手动选择过角色（从设备 / 主设备），避免重复弹窗与角色冲突。 */
export const LAN_ROLE_CHOSEN_STORAGE_KEY = "splayer/lan-role-chosen";

export const getLanSyncHostIp = (): string => {
  try {
    return sessionStorage.getItem(LAN_SYNC_HOST_STORAGE_KEY) ?? "";
  } catch {
    return "";
  }
};

export const isLanSyncReceiver = (): boolean => getLanSyncHostIp().trim().length > 0;

export const setLanSyncHostIp = (ip: string): void => {
  const nextIp = ip.trim();
  try {
    if (nextIp) sessionStorage.setItem(LAN_SYNC_HOST_STORAGE_KEY, nextIp);
    else sessionStorage.removeItem(LAN_SYNC_HOST_STORAGE_KEY);
  } catch {}
  window.dispatchEvent(new CustomEvent(LAN_SYNC_ROLE_EVENT, { detail: { hostIp: nextIp } }));
};

/** 是否已手动确定过本页角色（从设备或主设备） */
export const isLanRoleChosen = (): boolean => {
  try {
    return sessionStorage.getItem(LAN_ROLE_CHOSEN_STORAGE_KEY) === "1";
  } catch {
    return false;
  }
};

/** 标记本页角色已手动确定，后续不再弹出选择 */
export const markLanRoleChosen = (): void => {
  try {
    sessionStorage.setItem(LAN_ROLE_CHOSEN_STORAGE_KEY, "1");
  } catch {}
};

export const onLanSyncRoleChanged = (callback: (hostIp: string) => void): (() => void) => {
  const handleRoleEvent = (event: Event): void => {
    const detail = (event as CustomEvent<{ hostIp?: string }>).detail;
    callback(detail?.hostIp ?? getLanSyncHostIp());
  };
  const handleStorageEvent = (event: StorageEvent): void => {
    if (event.key === LAN_SYNC_HOST_STORAGE_KEY) callback(getLanSyncHostIp());
  };
  window.addEventListener(LAN_SYNC_ROLE_EVENT, handleRoleEvent);
  window.addEventListener("storage", handleStorageEvent);
  return () => {
    window.removeEventListener(LAN_SYNC_ROLE_EVENT, handleRoleEvent);
    window.removeEventListener("storage", handleStorageEvent);
  };
};
