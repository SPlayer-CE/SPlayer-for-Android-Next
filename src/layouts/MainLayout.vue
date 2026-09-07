<script setup lang="ts">
import { useStatusStore } from "@/stores/status";
import { useMediaStore } from "@/stores/media";
import { useSettingsStore } from "@/stores/settings";
import { useOrpheusProtocol } from "@/composables/useOrpheusProtocol";
import { useExternalFileHandler } from "@/composables/useExternalFileHandler";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import { useSettingsDialog } from "@/settings/useSettingsDialog";
import MobileFloatingNav from "@/components/layout/MobileFloatingNav.vue";
import MobileQueueOverlay from "@/components/player/MobileQueueOverlay.vue";
import LanSyncReceiver from "@/components/layout/LanSyncReceiver.vue";
import LanRoleChooser from "@/components/layout/LanRoleChooser.vue";
import bridge, { isAndroidPreview } from "@/services/bridge";
import {
  getLanSyncHostIp,
  isLanRoleChosen,
  markLanRoleChosen,
  onLanSyncRoleChanged,
  setLanSyncHostIp as setLanSyncRoleHostIp,
} from "@/composables/useLanSyncRole";

const route = useRoute();
const status = useStatusStore();
const settings = useSettingsStore();

// 接入 orpheus 协议唤起与外部音频文件播放
useOrpheusProtocol();
useExternalFileHandler();

// 作为从设备加入主机：hostIp 非空时显示接收浮窗
const syncHostIp = ref<string>("");
const syncReceiverBadgeHidden = ref(false);
const setSyncHostIp = (ip: string): void => {
  setLanSyncRoleHostIp(ip);
  syncHostIp.value = getLanSyncHostIp();
  // 切换主机 / 重新加入时重新显示同步徽章；离开主机时也顺带复位隐藏态。
  syncReceiverBadgeHidden.value = false;
};
syncHostIp.value = getLanSyncHostIp();
const stopLanSyncRoleWatch = onLanSyncRoleChanged((hostIp) => {
  syncHostIp.value = hostIp;
  if (hostIp) syncReceiverBadgeHidden.value = false;
});
onUnmounted(stopLanSyncRoleWatch);

// 暴露给全局，便于设置面板触发"作为从设备加入"
declare global {
  interface Window {
    splayerJoinLanHost?: (ip: string) => void;
    splayerLeaveLanHost?: () => void;
  }
}
window.splayerJoinLanHost = (ip: string): void => setSyncHostIp(ip.trim());
window.splayerLeaveLanHost = (): void => setSyncHostIp("");
const hideLanSyncReceiverBadge = (): void => {
  syncReceiverBadgeHidden.value = true;
};

// 局域网页面流转：浏览器打开主机服务页面时，手动选择以从设备 / 主设备进入（每会话一次）
const showRoleChooser = ref(isAndroidPreview && !isLanRoleChosen());
const chooseLanRole = (role: "receiver" | "host"): void => {
  // 从设备：在当前用户手势内解锁音频元素，规避浏览器自动播放拦截
  if (role === "receiver") bridge.player.unlockAudio();
  // 从设备：同步当前主机（页面来源即主机 IP）；主设备：独立控制，不进入同步
  setSyncHostIp(role === "receiver" ? window.location.hostname : "");
  markLanRoleChosen();
  showRoleChooser.value = false;
};

/** 有歌曲信息时显示播放栏 */
const showPlayerBar = computed(() => !!useMediaStore().track);
const { isPlayerExpanded } = storeToRefs(status);
const { appearance } = settings;
const { usePcLayout, useMobileLayout, isPadLayout, isPhoneLayout } = useResponsiveLayout();
const { open: settingsOpen } = useSettingsDialog();

/** 悬浮底栏显隐：仅移动端竖屏布局、且无全屏歌曲 / 设置弹窗 / 队列浮层时显示 */
const showFloatingNav = computed(
  () =>
    useMobileLayout.value &&
    !isPlayerExpanded.value &&
    !settingsOpen.value &&
    !status.mobileQueueOpen,
);

/** 路由切换动效 */
const routeTransitionName = computed(() => {
  const transition = appearance.routeTransition;
  if (transition === "none") return "";
  if (useMobileLayout.value && transition === "slide") return "route-fade";
  return `route-${transition}`;
});

/** 路由 key */
const routeKey = computed(() => {
  const hasParam = route.matched.some((m) => m.path.includes(":"));
  return hasParam ? route.path : (route.matched[1]?.path ?? route.path);
});

/** 需要受控缓存的页面组件白名单 */
const cachedViews = [
  "Home",
  "Library",
  "Liked",
  "History",
  "Download",
  "Daily",
  "Favorites",
  "Cloud",
  "LocalList",
  "Folders",
  "SearchPage",
  "Stats",
  "StreamingIndex",
];

const mainContainerRef = shallowRef<HTMLElement | null>(null);
const mainScrollMap = new Map<string, number>();

// 路由离开前记录滚动位置
watch(
  () => route.fullPath,
  (_newPath, oldPath) => {
    if (oldPath && mainContainerRef.value) {
      mainScrollMap.set(oldPath, mainContainerRef.value.scrollTop);
    }
  },
);

// 路由切换完成后恢复滚动位置
const handleAfterEnter = (): void => {
  if (!mainContainerRef.value) return;
  const saved = mainScrollMap.get(route.fullPath) ?? 0;
  mainContainerRef.value.scrollTop = saved;
};

/** 侧边栏样式 */
const sidebarClass = computed(() => {
  const classes: string[] = [];
  if (appearance.layoutMode === "floating") {
    classes.push("ml-3 mt-3 mb-3 rounded-xl border border-solid border-primary/10");
  } else {
    classes.push("border-r border-r-solid border-r-primary/10");
    if (showPlayerBar.value && appearance.layoutMode === "default") classes.push("mb-20");
  }
  return classes.join(" ");
});

const phoneNavTotal = "var(--phone-nav-total-height)";
const phonePlayerExtra =
  "calc(var(--phone-player-height)+var(--phone-player-gap)+var(--phone-content-gap))";

/** 主内容区底部 padding：为浮岛 + 底栏 + 安全区让出空间 */
const mainPaddingClass = computed(() => {
  // PC 布局沿用原 mb-20 语义（底栏 80px），改用 padding 表达
  if (!useMobileLayout.value) {
    return showPlayerBar.value && appearance.layoutMode !== "floating" ? "pb-20" : "";
  }
  // 移动端：底栏总高 + 安全区；有浮岛时再叠加浮岛空间
  if (!showPlayerBar.value) return `pb-[calc(${phoneNavTotal}+8px)]`;
  return `pb-[calc(${phoneNavTotal}+${phonePlayerExtra})]`;
});

/** 外层播放条样式 */
const playerBarWrapperClass = computed(() => {
  if (useMobileLayout.value) {
    return "fixed z-50 left-0 right-0 pointer-events-none";
  }
  const base = "fixed bottom-0 z-50 transition-[left] duration-300 pointer-events-none";
  const collapsed = appearance.sidebarCollapsed;
  switch (appearance.layoutMode) {
    case "sidebar-full":
      return `${base} ${collapsed ? "left-16" : "left-60"} right-0`;
    case "floating":
      return `${base} ${collapsed ? "left-[76px]" : "left-[252px]"} right-0 px-4 pb-6`;
    default:
      return `${base} left-0 right-0`;
  }
});

/** 移动端播放栏动态 bottom：底栏总高 + 播放栏间距 = 72 + 8 + safe-bottom = 80px + safe-bottom */
const mobilePlayerBarStyle = computed(() => {
  if (!useMobileLayout.value) return {};
  return {
    bottom: "calc(var(--phone-nav-total-height) + var(--phone-player-gap))",
    transform: "translateY(var(--android-keyboard-offset, 0px))",
  };
});

/** 内层播放条样式 */
const playerBarInnerClass = computed(() => {
  // 禁用底部播放栏交互
  const base = isPlayerExpanded.value ? "pointer-events-none" : "pointer-events-auto";
  if (useMobileLayout.value) {
    return `${base}`;
  }
  switch (appearance.layoutMode) {
    case "floating":
      return `${base} mx-auto max-w-4xl glass-panel rounded-full shadow-xl border border-solid border-primary/10`;
    default:
      return `${base} h-20 bg-surface-panel border-t border-t-solid border-t-primary/10`;
  }
});

watch(isPlayerExpanded, (expanded) => {
  if (expanded) return;
  nextTick(() => {
    const root = document.getElementById("main");
    if (!root) return;
    root.style.transition = "";
    root.style.transform = "";
    root.style.opacity = "";
    root.style.willChange = "";
  });
});
</script>

<template>
  <!-- 主界面 -->
  <div
    id="main"
    :style="{ height: 'var(--page-zoom-100dvh, 100dvh)' }"
    class="flex bg-app text-on-surface transition-[transform,opacity] duration-300 ease-[cubic-bezier(0.4,0,0.2,1)] origin-center overflow-hidden"
    :class="[
      isPadLayout ? 'pad-layout' : '',
      isPhoneLayout ? 'phone-layout' : '',
      showPlayerBar ? 'show-player' : '',
      isPlayerExpanded ? 'show-full-player opacity-0 scale-90' : '',
    ]"
  >
    <!-- 侧边栏（PC / Android 平板显示） -->
    <aside
      v-if="usePcLayout"
      class="shrink-0 bg-surface-panel overflow-y-auto z-10 transition-[width,margin] duration-300"
      :class="[appearance.sidebarCollapsed ? 'w-16' : 'w-60', sidebarClass]"
    >
      <SideBar />
    </aside>

    <!-- 右侧主区域 -->
    <div class="flex-1 flex flex-col min-w-0">
      <!-- 顶部导航 -->
      <header class="min-h-16 shrink-0 flex items-center px-3 pt-[env(safe-area-inset-top,0px)]">
        <NavHeader />
      </header>

      <!-- 主内容区（padding-bottom 为底栏/浮岛/安全区让出空间） -->
      <main
        ref="mainContainerRef"
        class="flex-1 overflow-y-auto overflow-x-hidden"
        :class="mainPaddingClass"
      >
        <RouterView v-slot="{ Component }">
          <Transition :name="routeTransitionName" mode="out-in" @after-enter="handleAfterEnter">
            <KeepAlive :max="10" :include="cachedViews">
              <component :is="Component" :key="routeKey" />
            </KeepAlive>
          </Transition>
        </RouterView>
      </main>
    </div>
  </div>

  <!-- 底部播放栏 -->
  <Transition
    enter-active-class="transition-transform duration-300 ease-out"
    leave-active-class="transition-transform duration-300 ease-in"
    enter-from-class="translate-y-full"
    leave-to-class="translate-y-full"
  >
    <div v-if="showPlayerBar" :class="playerBarWrapperClass" :style="mobilePlayerBarStyle">
      <footer :class="playerBarInnerClass">
        <PlayerBar />
      </footer>
    </div>
  </Transition>

  <!-- Vue 悬浮底栏（替代原生液态玻璃底栏） -->
  <MobileFloatingNav v-if="showFloatingNav" />

  <!-- 局域网页面流转角色选择：浏览器预览模式首次打开时弹出 -->
  <LanRoleChooser v-if="showRoleChooser" @choose="chooseLanRole" />

  <!-- 从设备协同接收浮窗：仅作为从设备时显示 -->
  <LanSyncReceiver
    v-if="syncHostIp"
    v-show="!syncReceiverBadgeHidden"
    :host-ip="syncHostIp"
    @close="hideLanSyncReceiverBadge"
  />

  <!-- Toast -->
  <SToast :max="1" />
  <!-- 性能监视器 -->
  <SPerformanceMonitor v-if="appearance.showPerformanceMonitor" />
  <!-- Dialog -->
  <SDialogProvider />
  <!-- 全屏播放器 -->
  <FullPlayer />
  <!-- 移动端独立播放队列浮层 -->
  <MobileQueueOverlay v-if="useMobileLayout" />
  <!-- 全局设置 -->
  <SettingsDialog />
  <!-- 更新弹窗 -->
  <UpdateDialog />
  <!-- 评论弹窗 -->
  <MusicCommentsDialog />
</template>

<style scoped></style>
