import { isAndroid } from "@/services/bridge";
import { useSettingsStore } from "@/stores/settings";

/**
 * 虚拟 binding：path 不对应任何持久化 store 字段
 * 而是映射到瞬时状态 / 一次性 action（如窗口开关、命令触发）
 * 写入通常只调 IPC / action，真实状态由广播回流驱动 UI
 */
export interface VirtualBinding {
  get: () => unknown;
  set: (value: unknown) => void;
}

/** 桌面歌词虚拟绑定 */
const desktopLyricBinding: VirtualBinding = {
  get: () => useSettingsStore().isDesktopLyricOpen,
  set: (v) => {
    const store = useSettingsStore();
    if (v === store.isDesktopLyricOpen) return;
    window.api.window.toggleDesktopLyric().catch(() => {});
  },
};

/** 灵动岛虚拟绑定 */
const dynamicIslandBinding: VirtualBinding = {
  get: () => useSettingsStore().isDynamicIslandOpen,
  set: (v) => {
    const store = useSettingsStore();
    if (v === store.isDynamicIslandOpen) return;
    window.api.window.toggleDynamicIsland().catch(() => {});
  },
};

/** 任务栏歌词虚拟绑定 */
const taskbarLyricBinding: VirtualBinding = {
  get: () => useSettingsStore().isTaskbarLyricOpen,
  set: (v) => {
    const store = useSettingsStore();
    if (v === store.isTaskbarLyricOpen) return;
    window.api.window.toggleTaskbarLyric().catch(() => {});
  },
};

/** 虚拟绑定 */
export const virtualBindings: Record<string, VirtualBinding> = isAndroid
  ? {
      /** 灵动岛歌词（Android 上通过悬浮窗服务实现） */
      isDynamicIslandOpen: dynamicIslandBinding,
    }
  : {
      /** 桌面歌词窗口 */
      isDesktopLyricOpen: desktopLyricBinding,
      /** 灵动岛窗口 */
      isDynamicIslandOpen: dynamicIslandBinding,
      /** 任务栏歌词窗口 */
      isTaskbarLyricOpen: taskbarLyricBinding,
    };
