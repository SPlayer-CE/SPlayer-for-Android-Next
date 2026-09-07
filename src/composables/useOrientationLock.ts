import { ScreenOrientation } from "@capacitor/screen-orientation";
import { isAndroidNative } from "@/services/bridge";

/**
 * Android 物理手机方向锁封装。
 * 仅在真实 Capacitor Android 容器内生效，浏览器预览时为空操作。
 */
export const useOrientationLock = () => {
  /** 锁定竖屏（退出沉浸式横屏后调用，确保设备回到竖屏） */
  const lockPortrait = async (): Promise<void> => {
    if (!isAndroidNative) return;
    try {
      await ScreenOrientation.lock({ orientation: "portrait" });
    } catch {
      // 部分设备或系统版本不支持方向锁，忽略错误
    }
  };

  /** 释放方向锁，交还系统重力感应 */
  const unlock = async (): Promise<void> => {
    if (!isAndroidNative) return;
    try {
      await ScreenOrientation.unlock();
    } catch {
      // 忽略
    }
  };

  return { lockPortrait, unlock };
};
