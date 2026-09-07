import type { Track } from "@shared/types/player";
import type { ContentScope } from "@/types/collection";
import { useUserStore } from "@/stores/user";
import { usePlaylistStore } from "@/stores/playlist";
import { toast } from "@/composables/useToast";

/**
 * 添加到歌单选择器
 * 在线歌曲未登录时拦截并提示，不打开弹窗
 */
export const usePlaylistPicker = () => {
  const user = useUserStore();
  const playlistStore = usePlaylistStore();
  const { t } = useI18n();

  /** 弹窗开关 */
  const open = ref(false);
  /** 待添加曲目 */
  const tracks = shallowRef<Track[]>([]);
  /** 本地 / 在线 */
  const mode = ref<ContentScope>("local");

  /**
   * 打开添加到歌单弹窗
   * @param items - 待添加曲目，需同源（local 或 netease）
   */
  const openPicker = async (items: Track[]): Promise<void> => {
    if (items.length === 0) return;
    const scope: ContentScope = items[0].source === "netease" ? "online" : "local";
    if (scope === "online" && !user.isLoggedIn) {
      toast.warning(t("liked.toast.needLogin"));
      return;
    }
    // Android 手机竖屏下 SideBar 不挂载，load 不会被调用，这里按需补一次
    if (scope === "local" && !playlistStore.initialized) {
      await playlistStore.load();
    }
    tracks.value = items;
    mode.value = scope;
    open.value = true;
  };

  return { open, tracks, mode, openPicker };
};
