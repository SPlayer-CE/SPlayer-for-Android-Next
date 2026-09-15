<script setup lang="ts">
import { fetchNeteaseDynamicCover } from "@/apis/song/netease";
import { isAndroid } from "@/services/bridge";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import { useMediaStore } from "@/stores/media";
import { useSettingsStore } from "@/stores/settings";
import { useStatusStore } from "@/stores/status";
import { useUserStore } from "@/stores/user";

const props = withDefaults(defineProps<{ fullscreen?: boolean }>(), { fullscreen: false });

/** 动态封面循环之间的停顿（毫秒），避免首尾帧硬切 */
const DYNAMIC_COVER_REPLAY_DELAY_MS = 2000;

const media = useMediaStore();
const status = useStatusStore();
const settings = useSettingsStore();
const user = useUserStore();
const { isPhoneLandscape } = useResponsiveLayout();
const { isPlaying } = storeToRefs(status);

/** 加载中的歌曲使用队列当前项作为封面兜底。 */
const displayTrack = computed(() => media.track ?? status.currentTrack);

/** 高清封面缓存 */
const hdCache = shallowRef<{ id: string; data: string } | null>(null);

const coverSrc = computed(() =>
  hdCache.value && hdCache.value.id === displayTrack.value?.id
    ? hdCache.value.data
    : displayTrack.value?.coverOriginal || displayTrack.value?.cover,
);

watchEffect(async () => {
  const id = displayTrack.value?.id;
  if (!status.isPlayerExpanded || status.trackLoading || !id) return;
  if (displayTrack.value?.source !== "local" || hdCache.value?.id === id) return;
  const r = await window.api.player.getCoverRaw();
  if (displayTrack.value?.id !== id || !r.success || !r.data) return;
  hdCache.value = { id, data: r.data };
});

// ─── 动态封面 ────────────────────────────────────────────────────────────────

/** 视频封面地址（null = 保持静态封面） */
const dynamicCoverUrl = shallowRef<string | null>(null);
/** 首帧就绪前不淡入，避免露出黑色视频框 */
const dynamicCoverLoaded = ref(false);
const videoRef = shallowRef<HTMLVideoElement | null>(null);
/** 页面可见性：切后台时暂停，回前台续播 */
const pageVisible = ref(!document.hidden);
/** 循环停顿定时器 */
let replayTimer = 0;
/** 竞态令牌：切歌或关开关后到达的响应直接丢弃 */
let coverToken = 0;

/** 封面当前是否为视觉焦点：动态封面只在此时加载（安卓竖屏仅信息页有封面，横屏常驻） */
const coverVisible = computed(() => {
  if (!status.isPlayerExpanded) return false;
  if (!isAndroid) return true;
  return isPhoneLandscape.value || status.mobileFullPlayerPage === "info";
});

/** 是否加载动态封面：非全屏封面铺底、开关开启、已登录、网易云在线曲 */
const dynamicCoverActive = computed(
  () =>
    !props.fullscreen &&
    settings.player.dynamicCover &&
    user.isLoggedIn &&
    displayTrack.value?.source === "netease" &&
    !!displayTrack.value.id &&
    coverVisible.value,
);

/** 卸载视频：暂停并清空 src，避免残留解码器与花屏残帧 */
const unloadDynamicCover = (): void => {
  window.clearTimeout(replayTimer);
  replayTimer = 0;
  const video = videoRef.value;
  if (video) {
    video.pause();
    video.removeAttribute("src");
    video.load();
  }
  dynamicCoverUrl.value = null;
  dynamicCoverLoaded.value = false;
};

/** 按页面可见性同步播放状态：隐藏时保持暂停，不做无谓解码 */
const syncDynamicCoverPlayback = (): void => {
  const video = videoRef.value;
  if (!video || !dynamicCoverUrl.value) return;
  if (pageVisible.value) void video.play().catch(() => {});
  else video.pause();
};

watch(
  () => [dynamicCoverActive.value, displayTrack.value?.id] as const,
  async ([active, id]) => {
    const token = ++coverToken;
    // 关闭开关或切歌都先卸载旧视频，避免上一首的画面残留
    unloadDynamicCover();
    if (!active || !id) return;
    const url = await fetchNeteaseDynamicCover(id);
    if (token !== coverToken) return;
    if (!url) {
      unloadDynamicCover();
      return;
    }
    dynamicCoverUrl.value = url;
  },
  { immediate: true },
);

/** 首帧就绪：淡入并在页面可见时开播 */
const onDynamicCoverLoaded = (): void => {
  dynamicCoverLoaded.value = true;
  syncDynamicCoverPlayback();
};

/** 播完停顿后再循环，避免短片段首尾硬切 */
const onDynamicCoverEnded = (): void => {
  dynamicCoverLoaded.value = false;
  window.clearTimeout(replayTimer);
  replayTimer = window.setTimeout(() => {
    replayTimer = 0;
    dynamicCoverLoaded.value = true;
    syncDynamicCoverPlayback();
  }, DYNAMIC_COVER_REPLAY_DELAY_MS);
};

/** 加载/解码失败兜底：清 src 卸载视频，回落静态封面，避免原生播放按钮占位 */
const onDynamicCoverError = (): void => {
  unloadDynamicCover();
};

const handleVisibilityChange = (): void => {
  pageVisible.value = !document.hidden;
};

watch(pageVisible, () => syncDynamicCoverPlayback());

onMounted(() => {
  document.addEventListener("visibilitychange", handleVisibilityChange);
});

onBeforeUnmount(() => {
  document.removeEventListener("visibilitychange", handleVisibilityChange);
  unloadDynamicCover();
});
</script>

<template>
  <div
    :class="
      fullscreen
        ? 'player-cover-fullscreen relative w-full h-full aspect-auto rounded-none bg-transparent overflow-hidden shrink-0'
        : [
            'relative w-full aspect-square rounded-[32px] overflow-hidden shrink-0',
            'shadow-[0_0_20px_10px_rgba(0,0,0,0.1)]',
            'transition-transform duration-500 ease-[cubic-bezier(0.34,1.56,0.64,1)]',
            isPlaying ? 'scale-100' : 'scale-90',
          ]
    "
  >
    <SImg :src="coverSrc" class="size-full" />
    <!-- 动态封面：首帧就绪后淡入覆盖静态封面 -->
    <video
      v-if="dynamicCoverUrl"
      ref="videoRef"
      :src="dynamicCoverUrl"
      class="dynamic-cover"
      :class="dynamicCoverLoaded ? 'opacity-100' : 'opacity-0'"
      muted
      autoplay
      playsinline
      preload="auto"
      disablepictureinpicture
      @loadeddata="onDynamicCoverLoaded"
      @ended="onDynamicCoverEnded"
      @error="onDynamicCoverError"
      @stalled="onDynamicCoverError"
    />
  </div>
</template>

<style scoped>
/** 动态封面：铺满封面容器，首帧就绪后由外层 opacity 类淡入 */
.dynamic-cover {
  position: absolute;
  inset: 0;
  z-index: 1;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: opacity 0.8s ease-in-out;
  backface-visibility: hidden;
  transform: translateZ(0);
}

.player-cover-fullscreen {
  mask-image: linear-gradient(
    to right,
    rgba(0, 0, 0, 1) 0%,
    rgba(0, 0, 0, 0.98) 10%,
    rgba(0, 0, 0, 0.92) 22%,
    rgba(0, 0, 0, 0.82) 32%,
    rgba(0, 0, 0, 0.68) 42%,
    rgba(0, 0, 0, 0.52) 52%,
    rgba(0, 0, 0, 0.36) 62%,
    rgba(0, 0, 0, 0.22) 72%,
    rgba(0, 0, 0, 0.1) 82%,
    rgba(0, 0, 0, 0.03) 92%,
    rgba(0, 0, 0, 0) 100%
  );
}
</style>
