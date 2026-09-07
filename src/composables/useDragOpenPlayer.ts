import { onBeforeUnmount, ref, toValue, type MaybeRefOrGetter } from "vue";
import { useStatusStore } from "@/stores/status";
import { isAndroid } from "@/services/bridge";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";

/**
 * 移动端"上滑跟手开启"全屏播放器：1:1 复刻 SPlayer-for-Android@70a5f861
 * 的 MainPlayer.vue drag-open 手势 + FullPlayer.vue mobile-card 进出场动画。
 *
 * 两个组件协作：
 * - 底栏（MobilePlayerBar）：通过 useDragOpenPlayer().bindPointer() 绑定 Pointer 事件，
 *   检测上滑手势，实时把 FullPlayer 容器从底栏顶部缩放展开。
 * - 全屏（FullPlayer/index.vue）：onMobileEnter / onMobileLeave 走 JS 卡片动画，
 *   兼容拖拽中的内联 transform。
 *
 * 全局标志 __splayerDragOpen 用于让 enter 钩子判断是否跳过起始视口位移，
 * 直接由底栏拖拽逻辑接管 transform 写入。
 */

const DRAG_OPEN_FLAG = "__splayerDragOpen";
const OPEN_THRESHOLD = 100;
const INIT_DRAG_OPEN_MAX_RETRY = 8;
const FINISH_DRAG_OPEN_MAX_RETRY = 8;

const MOBILE_CARD_ENTER = "transform 0.36s cubic-bezier(0.22, 1, 0.36, 1)";
const MOBILE_CARD_LEAVE = "transform 0.32s cubic-bezier(0.4, 0, 1, 1)";

type DragLocked = "h" | "v" | null;

const setDragOpenFlag = (v: boolean): void => {
  (window as unknown as { [k: string]: unknown })[DRAG_OPEN_FLAG] = v;
};

const readDragOpenFlag = (): boolean =>
  !!(window as unknown as { [k: string]: unknown })[DRAG_OPEN_FLAG];

interface DragOpenRefs {
  /** 拖拽目标容器（.full-player 容器） */
  parent: HTMLElement | null;
  /** 主内容区（缩放/淡出） */
  main: HTMLElement | null;
}

const clearParentInline = (parent: HTMLElement): void => {
  parent.style.transition = "";
  parent.style.transform = "";
  parent.style.borderRadius = "";
  parent.style.transformOrigin = "";
  parent.style.willChange = "";
  parent.style.pointerEvents = "";
  parent.style.backfaceVisibility = "";
};

const clearMainInline = (main: HTMLElement): void => {
  main.style.transition = "";
  main.style.transform = "";
  main.style.opacity = "";
  main.style.willChange = "";
};

const FULL_PLAYER_SELECTOR = ".full-player";
const MAIN_SELECTORS = ["#main", "#main-app-root", "#app-root"];

/** 在 Teleport / Transition 异步挂载期间重试查找全屏播放器容器 */
const queryFullPlayer = (): HTMLElement | null =>
  document.querySelector(FULL_PLAYER_SELECTOR) as HTMLElement | null;

const queryMain = (): HTMLElement | null => {
  for (const sel of MAIN_SELECTORS) {
    const el = document.querySelector(sel) as HTMLElement | null;
    if (el) return el;
  }
  return null;
};

/**
 * 全屏播放器进出场卡片动画（移动端）。
 * 由 FullPlayer/index.vue 的 <Transition> JS 钩子调用。
 *
   * enter：从底部视口高度 + scale(0.92) 弹出，transformOrigin 50% 0；

 *       若处于拖拽开启模式则不预设位移，交给底栏拖拽逻辑接管。
 * leave：反向滑出底部 + scale(0.92)。
 *
 * 桌面端由 :css CSS 类动画驱动，钩子直接 done() 不写内联，避免破坏桌面动画。
 */
export const useMobileCardTransition = (enabled: MaybeRefOrGetter<boolean> = isAndroid) => {
  const shouldUseMobileCard = (): boolean => isAndroid && toValue(enabled);

  const onEnter = (el: Element, done: () => void): void => {
    if (!shouldUseMobileCard()) {
      done();
      return;
    }
    onMobileEnter(el, done);
  };

  const onLeave = (el: Element, done: () => void): void => {
    if (!shouldUseMobileCard()) {
      done();
      return;
    }
    onMobileLeave(el, done);
  };

  return { onEnter, onLeave };
};

export const onMobileEnter = (el: Element, done: () => void): void => {
  const parent = el as HTMLElement;
  // 拖拽开启模式：同步写好起始态再交给底栏拖拽逻辑接管，避免一帧"全屏可见"裸态
  if (readDragOpenFlag()) {
    parent.style.transformOrigin = "50% 0";
    parent.style.willChange = "transform";
    parent.style.transition = "none";
    parent.style.transform = "translate3d(0, var(--page-zoom-100vh, 100vh), 0) scale(0.92)";
    parent.style.borderRadius = "28px";
    parent.style.backfaceVisibility = "hidden";
    done();
    return;
  }
  parent.style.transformOrigin = "50% 0";
  parent.style.willChange = "transform";
  parent.style.transition = "none";
  parent.style.transform = "translate3d(0, var(--page-zoom-100vh, 100vh), 0) scale(0.92)";
  parent.style.borderRadius = "28px";
  parent.style.backfaceVisibility = "hidden";
  // 强制重排，确保起始态生效
  parent.getBoundingClientRect();
  requestAnimationFrame(() => {
    parent.style.transition = MOBILE_CARD_ENTER;
    parent.style.transform = "";
  });
  window.setTimeout(() => {
    parent.style.transition = "";
    parent.style.borderRadius = "";
    parent.style.willChange = "";
    parent.style.transformOrigin = "";
    parent.style.backfaceVisibility = "";
    done();
  }, 380);
};

export const onMobileLeave = (el: Element, done: () => void): void => {
  const parent = el as HTMLElement;
  parent.style.transformOrigin = "50% 0";
  parent.style.willChange = "transform";
  parent.style.transition = MOBILE_CARD_LEAVE;
  parent.style.borderRadius = "28px";
  parent.style.backfaceVisibility = "hidden";
  requestAnimationFrame(() => {
    parent.style.transform = "translate3d(0, var(--page-zoom-100vh, 100vh), 0) scale(0.92)";
  });
  window.setTimeout(() => {
    done();
  }, 340);
};

/**
 * 底栏拖拽开启手势：返回 Pointer 事件处理器，绑定到播放栏根元素。
 *
 * 用法：
 * const { bindPointer } = useDragOpenPlayer();
 * <div @pointerdown="bindPointer.onDown" @pointermove="bindPointer.onMove"
 *      @pointerup="bindPointer.onEnd" @pointercancel="bindPointer.onEnd">
 */
export const useDragOpenPlayer = () => {
  const status = useStatusStore();
  // 参考项目 MainPlayer.onPointerMove 以 isPhone 作为激活门禁（含手机与平板竖屏），
  // 与底栏仅在手机布局渲染的语义等价，但显式门禁更稳妥
  const { isPhoneLayout } = useResponsiveLayout();

  let dragOpenActive = false;
  let dragOpenLocked: DragLocked = null;
  const refs: DragOpenRefs = { parent: null, main: null };
  let dragOpenRaf = 0;
  let dragOpenPending = 0;
  let dragStartX = 0;
  let dragStartY = 0;
  /** 底栏顶部到视口顶部的距离，卡片起始位移 */
  let dragStartTop = 0;
  let dragLastDy = 0;
  /** 卡片展开到全屏所需的最小位移 */
  let dragOpenTravel = 0;
  let dragOpenResetTimer = 0;
  let dragOpenCloseTimer = 0;
  let dragOpenSession = 0;
  let pointerId = -1;
  let pointerActiveTarget: HTMLElement | null = null;
  let horizontalSettled = false;
  let horizontalDirection: "left" | "right" | null = null;
  let initDragOpenRetry = 0;
  let finishDragOpenRetry = 0;
  /** 关闭分支的最终落实回调 */
  let pendingCloseFinalize: ((resetImmediately?: boolean) => void) | null = null;
  /** 组件挂载标志 */
  let mounted = true;
  /** 横向滑动方向，左滑下一首，右滑上一首 */
  const horizontalSwipeDirection = ref<"left" | "right" | null>(null);
  let suppressNextClick = false;
  let suppressClickTimer = 0;

  const markSuppressClick = (): void => {
    suppressNextClick = true;
    if (suppressClickTimer) window.clearTimeout(suppressClickTimer);
    suppressClickTimer = window.setTimeout(() => {
      suppressClickTimer = 0;
      suppressNextClick = false;
    }, 400);
  };

  const consumeClickSuppression = (): boolean => {
    if (!suppressNextClick) return false;
    suppressNextClick = false;
    if (suppressClickTimer) {
      window.clearTimeout(suppressClickTimer);
      suppressClickTimer = 0;
    }
    return true;
  };

  const cancelDragOpenTimers = (flushClose = false): void => {
    if (dragOpenResetTimer) {
      window.clearTimeout(dragOpenResetTimer);
      dragOpenResetTimer = 0;
    }
    if (dragOpenCloseTimer) {
      window.clearTimeout(dragOpenCloseTimer);
      dragOpenCloseTimer = 0;
      if (flushClose && pendingCloseFinalize) {
        const finalize = pendingCloseFinalize;
        pendingCloseFinalize = null;
        finalize(true);
        return;
      }
    }
    if (flushClose) pendingCloseFinalize = null;
  };

  const resetDragOpenMotion = (): void => {
    if (dragOpenRaf) {
      cancelAnimationFrame(dragOpenRaf);
      dragOpenRaf = 0;
    }
    if (refs.parent) {
      clearParentInline(refs.parent);
    }
    if (refs.main) {
      clearMainInline(refs.main);
    }
    refs.parent = null;
    refs.main = null;
    dragOpenPending = 0;
  };

  const writeDragOpen = (dy: number): void => {
    const progress = Math.max(0, Math.min(1, dy / dragOpenTravel));
    const translate = (1 - progress) * dragStartTop;
    const scale = 0.92 + 0.08 * progress;
    if (refs.parent) {
      refs.parent.style.transform = `translate3d(0, ${translate}px, 0) scale(${scale})`;
    }
    if (refs.main) {
      refs.main.style.opacity = String(1 - progress);
      refs.main.style.transform = `scale(${1 - 0.1 * progress})`;
    }
  };

  const scheduleDragOpenFlush = (dy: number): void => {
    dragOpenPending = dy;
    if (dragOpenRaf) return;
    dragOpenRaf = requestAnimationFrame(() => {
      dragOpenRaf = 0;
      writeDragOpen(dragOpenPending);
    });
  };

  const applyDragOpenInline = (parent: HTMLElement, main: HTMLElement | null): void => {
    refs.parent = parent;
    parent.style.transformOrigin = "50% 0";
    parent.style.willChange = "transform";
    parent.style.transition = "none";
    // 起始放置在底栏顶部位置，让卡片从控制条向上展开
    parent.style.transform = `translate3d(0, ${dragStartTop}px, 0) scale(0.92)`;
    parent.style.borderRadius = "28px";
    parent.style.backfaceVisibility = "hidden";
    // 关键：让全屏播放器在拖拽期间不拦截触摸，事件继续命中底栏
    parent.style.pointerEvents = "none";
    if (main) {
      refs.main = main;
      main.style.transition = "none";
      main.style.willChange = "transform, opacity";
      main.style.opacity = "1";
      main.style.transform = "scale(1)";
    }
  };

  const initDragOpen = (): void => {
    // 取消上一轮手势遗留的清理 timer，避免清掉本次 inline 样式
    cancelDragOpenTimers();
    const session = ++dragOpenSession;
    initDragOpenRetry = 0;

    const tryAttach = (): void => {
      if (!dragOpenActive || session !== dragOpenSession) return;
      const parent = queryFullPlayer();
      const main = queryMain();
      if (parent) {
        applyDragOpenInline(parent, main);
        // 命中后立刻把当前 dy 写入，避免 0 dy 一帧裸态
        writeDragOpen(Math.max(dragLastDy, 0));
        return;
      }
      if (initDragOpenRetry++ < INIT_DRAG_OPEN_MAX_RETRY) {
        requestAnimationFrame(tryAttach);
        return;
      }
      // 超过重试上限，撤销开启意图，防止用户被卡在不可见的全屏播放器
      console.warn("[useDragOpenPlayer] 拖拽开启时未能找到全屏容器，回退关闭");
      dragOpenActive = false;
      dragOpenLocked = null;
      setDragOpenFlag(false);
      status.isPlayerExpanded = false;
      resetDragOpenMotion();
    };
    tryAttach();
  };

  const resetDragOpen = (): void => {
    if (dragOpenRaf) {
      cancelAnimationFrame(dragOpenRaf);
      dragOpenRaf = 0;
    }
    if (refs.parent) clearParentInline(refs.parent);
    if (refs.main) clearMainInline(refs.main);
    refs.parent = null;
    refs.main = null;
    dragOpenActive = false;
    dragOpenLocked = null;
    finishDragOpenRetry = 0;
    setDragOpenFlag(false);
  };

  const finishDragOpen = (dy: number): void => {
    if (!mounted) return;
    const shouldOpen = dy > OPEN_THRESHOLD;
    // 终止还在排队的 initDragOpen 重试
    initDragOpenRetry = INIT_DRAG_OPEN_MAX_RETRY + 1;
    if (dragOpenRaf) {
      cancelAnimationFrame(dragOpenRaf);
      dragOpenRaf = 0;
    }
    // 兜底：挂载阶段重试未捕获到容器，先等挂载完成再结算
    if (!refs.parent) {
      const parent = queryFullPlayer();
      if (parent) {
        refs.parent = parent;
      } else if (finishDragOpenRetry++ < FINISH_DRAG_OPEN_MAX_RETRY) {
        requestAnimationFrame(() => finishDragOpen(dy));
        return;
      } else {
        status.isPlayerExpanded = false;
        resetDragOpen();
        return;
      }
      if (!refs.main) {
        refs.main = queryMain();
      }
    }
    finishDragOpenRetry = 0;
    if (shouldOpen) {
      if (refs.parent) {
        refs.parent.style.transition = "transform 0.28s cubic-bezier(0.22, 1, 0.36, 1)";
        refs.parent.style.transform = "";
        // 立即恢复全屏播放器的指针事件，避免开启动画期间触摸穿透
        refs.parent.style.pointerEvents = "";
      }
      if (refs.main) {
        refs.main.style.transition =
          "opacity 0.28s ease, transform 0.28s cubic-bezier(0.22, 1, 0.36, 1)";
        refs.main.style.opacity = "";
        refs.main.style.transform = "";
      }
      dragOpenResetTimer = window.setTimeout(() => {
        dragOpenResetTimer = 0;
        resetDragOpen();
      }, 320);
    } else {
      if (refs.parent) {
        refs.parent.style.transition = "transform 0.24s cubic-bezier(0.4, 0, 1, 1)";
        refs.parent.style.transform = `translate3d(0, ${dragStartTop}px, 0) scale(0.92)`;
      }
      if (refs.main) {
        refs.main.style.transition =
          "opacity 0.24s ease, transform 0.24s cubic-bezier(0.22, 1, 0.36, 1)";
        refs.main.style.opacity = "1";
        refs.main.style.transform = "scale(1)";
      }
      // 注册关闭最终落实回调，便于在新手势打断时同步执行
      pendingCloseFinalize = (resetImmediately = false): void => {
        status.isPlayerExpanded = false;
        if (resetImmediately) {
          resetDragOpen();
          return;
        }
        dragOpenResetTimer = window.setTimeout(() => {
          dragOpenResetTimer = 0;
          resetDragOpen();
        }, 360);
      };
      dragOpenCloseTimer = window.setTimeout(() => {
        dragOpenCloseTimer = 0;
        const finalize = pendingCloseFinalize;
        pendingCloseFinalize = null;
        if (finalize) finalize();
      }, 240);
    }
  };

  const onDown = (e: PointerEvent): void => {
    if (e.pointerType === "mouse" && e.button !== 0) return;
    // 新手势开始前取消上一轮异步清理；若挂起的关闭动作正在等待，立即落实
    cancelDragOpenTimers(true);
    // 兜底恢复：若全屏播放器标记为打开但容器残留内联 transform / pointer-events:none，
    // 说明上一次拖拽流程留下了脏状态，强制清掉内联让全屏播放器恢复正常可交互
    if (status.isPlayerExpanded && !dragOpenActive) {
      const stalled = queryFullPlayer();
      if (stalled && (stalled.style.transform || stalled.style.pointerEvents === "none")) {
        clearParentInline(stalled);
        const mainEl = queryMain();
        if (mainEl) clearMainInline(mainEl);
        refs.parent = null;
        refs.main = null;
        setDragOpenFlag(false);
      }
    }
    pointerId = e.pointerId;
    dragStartX = e.clientX;
    dragStartY = e.clientY;
    dragLastDy = 0;
    dragOpenActive = false;
    dragOpenLocked = null;
    horizontalSettled = false;
    horizontalDirection = null;
    horizontalSwipeDirection.value = null;
    // 仅记录目标，不立即捕获指针，避免影响子元素普通点击
    pointerActiveTarget = e.currentTarget as HTMLElement;
  };

  const onMove = (e: PointerEvent): void => {
    if (e.pointerId !== pointerId) return;
    const dx = e.clientX - dragStartX;
    const dy = dragStartY - e.clientY; // 上为正
    dragLastDy = dy;
    // 对齐参考：非手机布局（含平板横屏 PC 布局）不参与拖拽开启；已展开且非本手势激活则放行
    if (!isPhoneLayout.value || (status.isPlayerExpanded && !dragOpenActive)) return;
    const ax = Math.abs(dx);
    const ay = Math.abs(dy);
    if (!dragOpenLocked) {
      if (Math.max(ax, ay) < 8) return;
      if (ay > ax) {
        // 向上拖拽才进入开启流程
        if (dy <= 0) {
          dragOpenLocked = "h";
          return;
        }
        dragOpenLocked = "v";
        // 锁定方向后再捕获指针，确保全屏覆盖后事件仍流向底栏
        pointerActiveTarget?.setPointerCapture?.(e.pointerId);
        // 缓存底栏顶部坐标，作为卡片起始位移
        const rect = pointerActiveTarget?.getBoundingClientRect();
        dragStartTop = rect ? rect.top : window.innerHeight - 80;
        dragOpenTravel = Math.max(window.innerHeight * 0.55, 360);
        // 预提升 #main 合成层：在 isPlayerExpanded 触发 Vue 挂载之前将主内容区提升到独立合成层，
        // 避免首帧 transform/opacity 变化迫使整棵子树重绘；
        // 同时禁用 CSS transition，防止 isPlayerExpanded 类变更触发 1 帧 CSS 过渡后被 initDragOpen 内联覆盖
        const mainEl = queryMain();
        if (mainEl) {
          mainEl.style.willChange = "transform, opacity";
          mainEl.style.transition = "none";
          refs.main = mainEl;
        }
        setDragOpenFlag(true);
        dragOpenActive = true;
        markSuppressClick();
        status.isPlayerExpanded = true;
        // 等到下一帧再 initDragOpen，给 <Transition mode="out-in"> 留出挂载时机
        requestAnimationFrame(() => {
          if (!dragOpenActive) return;
          initDragOpen();
        });
        return;
      }
      dragOpenLocked = "h";
      horizontalDirection = dx > 0 ? "right" : "left";
      return;
    }
    if (dragOpenLocked === "h") {
      horizontalDirection = dx > 0 ? "right" : "left";
      horizontalSettled = ax > 50;
      return;
    }
    if (dragOpenActive) scheduleDragOpenFlush(Math.max(dy, 0));
  };

  const onEnd = (e: PointerEvent): void => {
    if (e.pointerId !== pointerId) return;
    pointerId = -1;
    if (pointerActiveTarget) {
      pointerActiveTarget.releasePointerCapture?.(e.pointerId);
      pointerActiveTarget = null;
    }
    if (dragOpenActive) {
      markSuppressClick();
      finishDragOpen(Math.max(dragLastDy, 0));
      return;
    }
    // 横向滑动切换歌曲
    if (dragOpenLocked === "h" && horizontalSettled && horizontalDirection) {
      markSuppressClick();
      horizontalSwipeDirection.value = horizontalDirection;
      requestAnimationFrame(() => {
        horizontalSwipeDirection.value = null;
      });
    }
  };

  const onClick = (e: MouseEvent): void => {
    if (!consumeClickSuppression()) return;
    e.preventDefault();
    e.stopPropagation();
  };

  onBeforeUnmount(() => {
    mounted = false;
    cancelDragOpenTimers(true);
    if (suppressClickTimer) {
      window.clearTimeout(suppressClickTimer);
      suppressClickTimer = 0;
    }
    if (dragOpenRaf) {
      cancelAnimationFrame(dragOpenRaf);
      dragOpenRaf = 0;
    }
    initDragOpenRetry = INIT_DRAG_OPEN_MAX_RETRY + 1;
    resetDragOpen();
  });

  return {
    bindPointer: { onDown, onMove, onEnd, onClick },
    /** 横向滑动方向（左滑下一首，右滑上一首） */
    horizontalSwipeDirection,
    /** 上滑手势激活中（用于在拖拽时禁用底栏的普通点击动画） */
    isActive: () => dragOpenActive,
  };
};
