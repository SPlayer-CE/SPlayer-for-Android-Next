import type { Ref } from "vue";
import { useI18n } from "vue-i18n";
import type { Track } from "@shared/types/player";
import type { SVirtualListExposed } from "@/components/ui/SVirtualList.vue";
import { useStatusStore } from "@/stores/status";
import { useMediaStore } from "@/stores/media";
import { useThemeStore } from "@/stores/theme";
import { clearQueue, queue, queueLength } from "@/stores/queue";
import {
  isLanSyncReceiver,
  getLanSyncHostIp,
  onLanSyncRoleChanged,
} from "@/composables/useLanSyncRole";
import { hostQueue, hostQueuePlayIndex, refreshLanHostQueue } from "@/composables/useLanHostQueue";
import { dispatchLanRemoteCommand } from "@/composables/lanSyncRemote";
import * as player from "@/core/player";

export interface UseQueuePanelOptions {
  listRef: Ref<SVirtualListExposed | null>;
}

/**
 * 播放队列面板的共享逻辑（与拖排序解耦）
 * 视觉表达交给具体组件，这里只管：播放、移除、清空、定位
 * 拖排序按需在 consumer 里直接调 useDragSort，避免不需要的组件白付出 ref/closure 开销
 *
 * 从设备（LAN 协同网页端）：队列与高亮镜像主机快照；点播经 WS command 上送，
 * 移除/清空等写操作禁用（主机队列的所有权在主机）。
 */
export const useQueuePanel = (options: UseQueuePanelOptions) => {
  const { t } = useI18n();
  const statusStore = useStatusStore();
  const mediaStore = useMediaStore();

  /** 从设备模式：队列数据源切到主机镜像 */
  const lanRemoteQueue = ref(isLanSyncReceiver());
  let hostIp = getLanSyncHostIp();

  // 角色切换（LanRoleChooser 选择/退出从设备）→ 换数据源并重拉快照
  const offRoleChanged = onLanSyncRoleChanged((ip) => {
    lanRemoteQueue.value = isLanSyncReceiver();
    hostIp = ip;
    if (lanRemoteQueue.value && ip) void refreshLanHostQueue(ip);
  });
  onScopeDispose(() => offRoleChanged());

  // 打开面板即拉一次快照；WS 连接的 useLanSync 还会按 queueRevision 增量驱动
  if (lanRemoteQueue.value) void refreshLanHostQueue(hostIp);

  const effectiveQueue = computed<Track[]>(() =>
    lanRemoteQueue.value ? hostQueue.value : queue.value,
  );
  const effectiveQueueLength = computed(() => effectiveQueue.value.length);

  /** 当前在播索引：从设备取主机快照的 playIndex */
  const activePlayIndex = computed(() =>
    lanRemoteQueue.value ? hostQueuePlayIndex.value : statusStore.playIndex,
  );

  /** 拼接艺术家名称 */
  const formatArtists = (artists: Track["artists"]): string => {
    if (!artists?.length) return t("playlist.unknownArtist");
    return artists.map((ar) => ar.name).join(" / ");
  };

  /** 播放指定索引；从设备模式下经 WS 上送主机点播 */
  const playAt = async (index: number): Promise<void> => {
    if (lanRemoteQueue.value) {
      dispatchLanRemoteCommand("playAt", index);
      return;
    }
    await player.playAtIndex(index);
  };

  /** 移除单首；从设备不可改主机队列，直接忽略 */
  const removeAt = (index: number): void => {
    if (lanRemoteQueue.value) return;
    player.removeFromQueue(index);
  };

  const clearConfirmOpen = ref(false);

  /** 清空队列 + 重置播放索引；从设备不可改主机队列，直接忽略 */
  const clearAll = (): void => {
    if (lanRemoteQueue.value) {
      clearConfirmOpen.value = false;
      return;
    }
    if (queueLength.value === 0) return;
    player.stop();
    statusStore.playIndex = -1;
    clearQueue();
    mediaStore.clear();
    useThemeStore().coverColor = null;
    clearConfirmOpen.value = false;
  };

  /** 滚动到当前正在播放项 */
  const scrollToCurrent = (): void => {
    if (activePlayIndex.value >= 0) {
      options.listRef.value?.scrollToIndex(activePlayIndex.value);
    }
  };

  return {
    statusStore,
    queue: effectiveQueue,
    queueLength: effectiveQueueLength,
    activePlayIndex,
    lanRemoteQueue,
    formatArtists,
    playAt,
    removeAt,
    clearConfirmOpen,
    clearAll,
    scrollToCurrent,
  };
};
