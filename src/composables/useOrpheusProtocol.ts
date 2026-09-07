import { handleOrpheus } from "@/services/orpheus";
import { isAndroid } from "@/services/bridge";

/**
 * 主窗口接入 orpheus 协议唤起。
 * Android 端没有自定义协议唤起通道（webDir 直加载），整个 hook 视为 no-op。
 */
export const useOrpheusProtocol = (): void => {
  if (isAndroid) return;
  let unsubscribe: (() => void) | null = null;
  onMounted(() => {
    // 监听主进程下发的协议唤起事件
    unsubscribe = window.api.system.onProtocolUrl(handleOrpheus);
  });
  onBeforeUnmount(() => unsubscribe?.());
};
