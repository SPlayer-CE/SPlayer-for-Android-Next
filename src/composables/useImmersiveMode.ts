import type { Ref } from "vue";
import { useSettingsStore } from "@/stores/settings";
import { useStatusStore } from "@/stores/status";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import { isAndroid } from "@/services/bridge";

/** 沉浸模式闲置时间（ms） */
const IMMERSIVE_IDLE_MS = 3000;

/**
 * 全屏播放器沉浸模式
 * 闲置指定时间后自动隐藏顶栏/底栏，鼠标/触摸移动时恢复
 *
 * Android 适配：
 * - 平板设备自动开启沉浸模式（无需依赖 autoImmersive 设置）
 * - 触摸恢复拦截层：bars 隐藏后首次触摸恢复 UI，shield 短暂保留以吞掉后续合成 click
 * - 音量弹层打开时退出沉浸模式，关闭后重新计时
 *
 * @param isPlayerExpanded - 播放器是否展开
 */
export const useImmersiveMode = (isPlayerExpanded: Ref<boolean>) => {
  const settings = useSettingsStore();
  const status = useStatusStore();
  const { isAndroidTablet, useMobileLayout } = useResponsiveLayout();

  const immersive = ref(false);
  const barHovered = ref(false);
  let idleTimer: ReturnType<typeof setTimeout> | undefined;

  /** 沉浸模式是否启用（平板始终开启，桌面端依赖设置项） */
  const immersiveEnabled = computed(
    () => (settings.player.autoImmersive || isAndroidTablet.value) && isPlayerExpanded.value,
  );

  const armIdle = (): void => {
    clearTimeout(idleTimer);
    immersive.value = false;
    if (!immersiveEnabled.value) return;
    idleTimer = setTimeout(() => {
      if (!barHovered.value && !tapRestoreShield.value && !status.volumePopoverOpen)
        immersive.value = true;
    }, IMMERSIVE_IDLE_MS);
  };

  const onPlayerMouseEnter = (): void => armIdle();

  const onPlayerMouseLeave = (): void => {
    clearTimeout(idleTimer);
    if (immersiveEnabled.value) immersive.value = true;
  };

  const onMainMove = (): void => {
    if (!barHovered.value) armIdle();
  };

  const onBarEnter = (): void => {
    barHovered.value = true;
    clearTimeout(idleTimer);
    immersive.value = false;
  };

  const onBarLeave = (): void => {
    barHovered.value = false;
    armIdle();
  };

  watch(immersiveEnabled, (on) => {
    if (!on) {
      clearTimeout(idleTimer);
      immersive.value = false;
      barHovered.value = false;
    }
  });

  /** 音量弹层打开时退出沉浸模式，关闭后重新计时 */
  watch(
    () => status.volumePopoverOpen,
    (open) => {
      if (open) {
        clearTimeout(idleTimer);
        immersive.value = false;
      } else {
        armIdle();
      }
    },
  );

  /** 触摸恢复拦截层：bars 隐藏后首次触摸恢复 UI，shield 短暂保留以吞掉后续合成 click */
  const tapRestoreEnabled = computed(
    () => isAndroidTablet.value || useMobileLayout.value || isAndroid,
  );
  const tapRestoreShield = ref(false);
  let tapRestoreShieldTimer: ReturnType<typeof setTimeout> | undefined;

  const clearTapRestoreShieldTimer = (): void => {
    if (tapRestoreShieldTimer !== undefined) {
      clearTimeout(tapRestoreShieldTimer);
      tapRestoreShieldTimer = undefined;
    }
  };

  const releaseTapRestoreShield = (): void => {
    clearTapRestoreShieldTimer();
    tapRestoreShield.value = false;
  };

  const holdTapRestoreShield = (duration = 420): void => {
    clearTapRestoreShieldTimer();
    tapRestoreShield.value = true;
    tapRestoreShieldTimer = setTimeout(() => {
      tapRestoreShield.value = false;
      tapRestoreShieldTimer = undefined;
    }, duration);
  };

  const onTapRestore = (e: Event): void => {
    e.stopPropagation();
    if (e.cancelable) e.preventDefault();
    holdTapRestoreShield();
    immersive.value = false;
    armIdle();
  };

  watch(immersive, (hidden) => {
    if (!hidden) releaseTapRestoreShield();
  });

  onBeforeUnmount(() => {
    clearTimeout(idleTimer);
    clearTapRestoreShieldTimer();
  });

  return {
    immersive,
    armIdle,
    onPlayerMouseEnter,
    onPlayerMouseLeave,
    onMainMove,
    onBarEnter,
    onBarLeave,
    tapRestoreEnabled,
    tapRestoreShield,
    onTapRestore,
  };
};
