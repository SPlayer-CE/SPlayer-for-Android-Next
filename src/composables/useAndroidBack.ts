import type { Ref } from "vue";
import { App as CapacitorApp } from "@capacitor/app";
import bridge, { isAndroid, isAndroidNative } from "@/services/bridge";
import { useStatusStore } from "@/stores/status";
import { useSettingsDialog } from "@/settings/useSettingsDialog";
import { dialog } from "@/composables/useDialog";
import { toast } from "@/composables/useToast";

// ─── 返回处理栈 ────────────────────────────────────────────────────────────

type BackHandler = () => boolean | void;

/** 模块级返回处理栈，栈顶优先消费 */
const backHandlerStack: BackHandler[] = [];

/** 注册 back 处理器，返回反注册函数 */
export const pushBackHandler = (handler: BackHandler): (() => void) => {
  backHandlerStack.push(handler);
  return () => {
    const idx = backHandlerStack.lastIndexOf(handler);
    if (idx !== -1) backHandlerStack.splice(idx, 1);
  };
};

/** setup 自动绑定：onMounted 推栈，onBeforeUnmount 出栈 */
export const useBackHandler = (handler: BackHandler): void => {
  if (!isAndroid) return;
  let off: (() => void) | null = null;
  onMounted(() => {
    off = pushBackHandler(handler);
  });
  onBeforeUnmount(() => {
    off?.();
    off = null;
  });
};

/**
 * v-model:show 接入返回键
 * - showRef 为 true 时自动注册 handler
 * - 返回键按下时调 onBack，若 onBack 返回 true 则消费不关
 * - 否则直接设 showRef.value = false
 */
export const useBackClosable = (
  showRef: Ref<boolean>,
  options?: { onBack?: () => boolean | void },
): void => {
  if (!isAndroid) return;
  let off: (() => void) | null = null;
  const detach = () => {
    off?.();
    off = null;
  };
  const stop = watch(
    showRef,
    (visible) => {
      if (visible) {
        if (off) return;
        off = pushBackHandler(() => {
          if (options?.onBack?.() === true) return true;
          detach();
          showRef.value = false;
          return true;
        });
      } else {
        detach();
      }
    },
    { immediate: true },
  );
  onBeforeUnmount(() => {
    stop();
    detach();
  });
};

// ─── 路由可回退判定 ────────────────────────────────────────────────────────

const ROOT_PATHS = new Set(["/", "/home"]);

const canRouterBack = (currentPath: string): boolean => {
  if (ROOT_PATHS.has(currentPath)) return false;
  const state = window.history.state as { back?: string | null } | null;
  return !!state && typeof state.back === "string" && state.back.length > 0;
};

// ─── 顶层入口 ──────────────────────────────────────────────────────────────

/**
 * Android 返回键处理
 *
 * 在 App.vue 顶层调用一次，注册 Capacitor backButton 监听。
 * 分发优先级：栈分发 → 设置弹窗 → 全屏播放器 → 外层队列 → 搜索 → 路由回退 → 退出确认
 */
export const useAndroidBack = () => {
  if (!isAndroidNative) return;

  const router = useRouter();
  const status = useStatusStore();
  const settingsDialog = useSettingsDialog();

  let exitConfirmShown = false;

  const closeWindowOrExit = async (): Promise<void> => {
    const status = await bridge.lanShare
      .getStatus()
      .catch(() => ({ enabled: false, collabEnabled: false, shareUserInfo: false }));
    if (status.enabled) {
      bridge.window.minimize();
      toast.success("局域网分享仍在后台运行");
      return;
    }
    // 调用原生 shutdownApp：停 PlaybackService + DynamicIslandService 前台服务，
    // 再 finishAndRemoveTask + System.exit，避免 Activity 关闭后音乐仍在后台播放
    bridge.window.quit();
  };

  /** 退出确认弹窗 */
  const showExitConfirm = async (): Promise<void> => {
    if (exitConfirmShown) return;
    const status = await bridge.lanShare
      .getStatus()
      .catch(() => ({ enabled: false, collabEnabled: false, shareUserInfo: false }));
    const keepLanShareAlive = status.enabled;
    exitConfirmShown = true;
    dialog
      .confirm({
        title: keepLanShareAlive ? "关闭窗口" : "退出",
        content: keepLanShareAlive
          ? "局域网分享正在运行，关闭窗口后会继续在后台运行。"
          : "确定要退出吗？",
        confirmText: keepLanShareAlive ? "关闭窗口" : "退出",
        cancelText: "取消",
        type: "warning",
      })
      .then(async (confirmed) => {
        exitConfirmShown = false;
        if (!confirmed) return;
        await closeWindowOrExit();
      });
  };

  /** 从栈顶向下分发，返回 true 表示已消费 */
  const dispatchBackStack = (): boolean => {
    for (let i = backHandlerStack.length - 1; i >= 0; i--) {
      try {
        if (backHandlerStack[i]?.()) return true;
      } catch {
        return true; // 抛错视为已处理，防卡死
      }
    }
    return false;
  };

  const handleBack = async (): Promise<void> => {
    // 0) 栈分发（所有 useBackClosable / pushBackHandler 注册的浮层）
    if (dispatchBackStack()) return;

    // 1) 关闭设置弹窗
    if (settingsDialog.open.value) {
      settingsDialog.open.value = false;
      return;
    }

    // 2) 关闭全屏播放器（先关播放队列）
    if (status.isPlayerExpanded) {
      if (status.fullQueueOpen) {
        status.fullQueueOpen = false;
        return;
      }
      status.isPlayerExpanded = false;
      return;
    }

    // 3) 关闭外层播放队列
    if (status.outerQueueOpen) {
      status.outerQueueOpen = false;
      return;
    }

    // 4) 关闭搜索
    if (status.searchOpen) {
      status.searchOpen = false;
      return;
    }

    // 5) 路由回退
    if (canRouterBack(router.currentRoute.value.path)) {
      await router.back();
      return;
    }

    // 6) 已到根，弹退出确认
    await showExitConfirm();
  };

  let listener: { remove: () => Promise<void> } | null = null;
  let cancelled = false;

  onMounted(async () => {
    listener = await CapacitorApp.addListener("backButton", () => {
      if (cancelled) return;
      void handleBack();
    });
  });

  onBeforeUnmount(() => {
    cancelled = true;
    listener?.remove();
    listener = null;
  });
};
