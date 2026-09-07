import { ref } from "vue";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import { useOrientationLock } from "@/composables/useOrientationLock";
import bridge from "@/services/bridge";

/** 横屏切换的 7 阶段状态机 */
export type OrientationPhase =
  | "idle"
  | "enter-rising"
  | "enter-rotating"
  | "enter-revealing"
  | "exit-collapsing"
  | "exit-rotating"
  | "exit-revealing";

/** 模块级单例状态，组件 unmount 不打断进行中的动画 */
const phase = ref<OrientationPhase>("idle");
const busy = ref(false);
/** 是否处于主动横屏模式（用户点击按钮触发，区别于重力自动旋转） */
const isImmersiveLandscape = ref(false);
/** Hero 揭幕封面图源 */
const heroSrc = ref<string | undefined>(undefined);
/** Hero 揭幕封面目标位置（横屏 cover 的 boundingRect） */
const heroToRect = ref<{ top: number; left: number; width: number; height: number } | null>(null);

/** 横屏封面元素引用，由 FullPlayerMobile 注册 */
let landscapeCoverEl: HTMLElement | null = null;

const wait = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

/** 轮询等待条件满足，超时后继续 */
const waitFor = (predicate: () => boolean, timeoutMs: number): Promise<void> =>
  new Promise((resolve) => {
    const start = Date.now();
    const check = () => {
      if (predicate() || Date.now() - start >= timeoutMs) {
        resolve();
        return;
      }
      requestAnimationFrame(check);
    };
    check();
  });

const prefersReducedMotion = (): boolean =>
  typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

/**
 * 横屏切换动画协调器。
 * 模块级单例，多处调用共享同一状态。
 */
export const useOrientationTransition = () => {
  const { isPhoneLandscape, isPhonePortrait } = useResponsiveLayout();
  const { lockPortrait } = useOrientationLock();

  /** 注册横屏封面元素，供 Hero 揭幕测量目标位置 */
  const registerLandscapeCover = (el: HTMLElement | null): void => {
    landscapeCoverEl = el;
  };

  /** 进入沉浸式横屏 */
  const enter = async (coverSrc: string): Promise<void> => {
    if (busy.value || isImmersiveLandscape.value) return;
    busy.value = true;
    heroSrc.value = coverSrc;
    heroToRect.value = null;

    if (prefersReducedMotion()) {
      await bridge.android.setImmersiveLandscape(true);
      isImmersiveLandscape.value = true;
      busy.value = false;
      return;
    }

    // 1. 黑场升起
    phase.value = "enter-rising";
    await wait(280);

    // 2. 触发 native 旋转
    await bridge.android.setImmersiveLandscape(true);
    isImmersiveLandscape.value = true;
    phase.value = "enter-rotating";

    // 3. 等横屏挂载
    await waitFor(() => isPhoneLandscape.value && landscapeCoverEl !== null, 1200);
    await wait(50);

    // 测量横屏 cover 终点位置
    const rect = landscapeCoverEl?.getBoundingClientRect();
    heroToRect.value = rect
      ? { top: rect.top, left: rect.left, width: rect.width, height: rect.height }
      : null;

    // 4. Hero 揭幕 + Stagger 错开入场
    phase.value = "enter-revealing";
    await wait(500);

    // 5. cleanup
    phase.value = "idle";
    heroSrc.value = undefined;
    heroToRect.value = null;
    busy.value = false;
  };

  /** 退出沉浸式横屏 */
  const exit = async (coverSrc: string): Promise<void> => {
    if (busy.value) return;
    busy.value = true;
    heroSrc.value = coverSrc;
    heroToRect.value = null;

    if (prefersReducedMotion()) {
      isImmersiveLandscape.value = false;
      await bridge.android.setImmersiveLandscape(false);
      await lockPortrait();
      busy.value = false;
      return;
    }

    // 1. Stagger 收起 + 黑场升起
    phase.value = "exit-collapsing";
    await wait(280);

    // 2. 释放方向锁
    isImmersiveLandscape.value = false;
    await bridge.android.setImmersiveLandscape(false);
    await lockPortrait();
    phase.value = "exit-rotating";

    // 3. 等竖屏挂载
    await waitFor(() => isPhonePortrait.value, 800);

    // 4. 黑场退场
    phase.value = "exit-revealing";
    await wait(340);

    // 5. cleanup
    phase.value = "idle";
    heroSrc.value = undefined;
    heroToRect.value = null;
    busy.value = false;
  };

  return {
    phase,
    busy,
    isImmersiveLandscape,
    heroSrc,
    heroToRect,
    enter,
    exit,
    registerLandscapeCover,
  };
};
