<script setup lang="ts">
import { useStatusStore } from "@/stores/status";
import { useMediaStore } from "@/stores/media";
import { useSettingsStore } from "@/stores/settings";
import { usePlaybackTime } from "@/composables/usePlaybackTime";
import { getCurrentTime } from "@/services/playback";
import type { QualityLevel } from "@/utils/quality";
import { useFavorite } from "@/composables/useFavorite";
import { useDownload, buildDownloadQualityItems } from "@/composables/useDownload";
import { usePlaylistPicker } from "@/composables/usePlaylistPicker";
import { useImmersiveMode } from "@/composables/useImmersiveMode";
import { useTimeFormat } from "@/composables/useTimeFormat";
import { useProgressLyric } from "@/composables/useProgressLyric";
import Lyrics from "@/components/player/Lyrics/index.vue";
import AMLLLyrics from "@/components/player/Lyrics/AMLLLyrics.vue";
import AndroidMainLyricHost from "./AndroidMainLyricHost.vue";
import PlaylistPickerDialog from "@/components/modals/PlaylistPickerDialog.vue";
import FullPlayerMobile from "./FullPlayerMobile.vue";
import QuickActionsMenu from "@/components/player/QuickActionsMenu.vue";
import { useWindowControls } from "@/composables/useWindowControls";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import { useOrientationTransition } from "@/composables/useOrientationTransition";
import { useMobileCardTransition } from "@/composables/useDragOpenPlayer";
import { isAndroid, isAndroidNative } from "@/services/bridge";
import * as player from "@/core/player";
import { openExternal } from "@/utils/url";
import IconFavorite from "~icons/material-symbols/favorite-rounded";
import IconFavoriteOutline from "~icons/material-symbols/favorite-outline-rounded";
import IconLucideListPlus from "~icons/lucide/list-plus";
import IconLucideDownload from "~icons/lucide/download";
import IconLucideEyeOff from "~icons/lucide/eye-off";

const status = useStatusStore();
const media = useMediaStore();
const settings = useSettingsStore();
const fav = useFavorite();
const { enqueue: enqueueDownload } = useDownload();
const { t } = useI18n();
const {
  isPlaying,
  isLoading,
  position,
  duration,
  isPlayerExpanded,
  repeatMode,
  shuffleMode,
  heartMode,
  fmMode,
  showLyric,
} = storeToRefs(status);

const { timeDisplay, toggleTimeFormat } = useTimeFormat();
const { snapToNearestLyric } = useProgressLyric();

interface LyricController {
  setCurrentTime: (time: number, isSeek?: boolean) => void;
  freeze: () => void;
  resume: () => void;
  refreshLayout?: () => void;
  suppressTapSeek?: () => void;
}

/** 歌词组件引用 */
const lyricRef = ref<LyricController | null>(null);
const lyricMounted = ref(false);
const initialLyricTimeMs = ref(0);

/** 歌词容器渲染字重：Android 上 = 设置字重 × 2（上限 1000，即 CSS font-weight 有效区间上限，超出会使声明无效回落到 400），补偿 WebView 字重视觉偏细；桌面端用原值 */
const containerLyricWeight = computed(() =>
  isAndroid ? Math.min(settings.lyric.fontWeight * 2, 1000) : settings.lyric.fontWeight,
);

/** 字号/字重/字体变化后重排歌词，重新测量断行与布局 */
watch(
  () => [settings.lyric.fontSize, settings.lyric.fontWeight, settings.lyric.fontFamily],
  () => {
    nextTick(() => lyricRef.value?.refreshLayout?.());
  },
);

/** 加载中的歌曲使用队列当前项兜底，避免全屏播放器出现空白。 */
const displayTrack = computed(() => media.track ?? status.currentTrack);
const hasLyric = computed(() => media.parsedLyric.length > 0 || media.lyricLoading);
const hasTrack = computed(() => !!displayTrack.value);
const mainLyricLines = computed(() => media.parsedLyric.filter((line) => !line.isBG));
const bottomBarLyricText = computed(() => {
  if (media.lyricIndex < 0 || mainLyricLines.value.length === 0) return "";
  const currentMs = media.parsedLyric[media.lyricIndex]?.startTime ?? 0;
  const line = mainLyricLines.value.findLast((item) => item.startTime <= currentMs);
  if (!line) return "";
  const text = line.words.map((word) => word.word).join("");
  return line.translatedLyric ? `${text}（${line.translatedLyric}）` : text;
});

/** 精确播放时间（毫秒） */
const { start: startTick, stop: stopTick } = usePlaybackTime((currentMs) => {
  if (!status.trackLoading && !media.lyricLoading) {
    lyricRef.value?.setCurrentTime(currentMs + status.lyricOffsetMs, player.isSeeking());
  }
});

/** 展开?*/
const onAfterEnter = () => {
  initialLyricTimeMs.value = getCurrentTime() + status.lyricOffsetMs;
  lyricMounted.value = true;
  nextTick(() => {
    lyricRef.value?.resume();
    if (usesNativeKotlinLyricClock.value) stopTick();
    else startTick();
  });
};

/** 收起?*/
const onBeforeLeave = () => {
  lyricRef.value?.freeze();
  stopTick();
};

/** 收起?*/
const onAfterLeave = () => {
  lyricMounted.value = false;
};

/** Android 主播放器逐词实现模式；平?PC 布局也要参与切换，不能只在手机页生效?*/
const androidLyricRenderMode = computed(() =>
  isAndroid && settings.lyric.engine === "kotlin" ? "kotlin" : "legacy",
);
const usesNativeKotlinLyricClock = computed(
  () => isAndroidNative && androidLyricRenderMode.value === "kotlin",
);

watch(usesNativeKotlinLyricClock, (usesNativeClock) => {
  if (usesNativeClock) {
    stopTick();
  } else if (lyricMounted.value && isPlayerExpanded.value) {
    startTick();
  }
});

// 重新挂载时，刷新初始时间
watch(hasLyric, (value) => {
  if (value && lyricMounted.value) {
    initialLyricTimeMs.value = getCurrentTime() + status.lyricOffsetMs;
  }
});

// 歌词变化时先推送精确时间?
watch(
  () => media.parsedLyric,
  () => lyricRef.value?.setCurrentTime(getCurrentTime() + status.lyricOffsetMs),
);

// 切换歌词引擎时，重新计算初始并推送时间?
watch(
  () => settings.lyric.engine,
  () => {
    initialLyricTimeMs.value = getCurrentTime() + status.lyricOffsetMs;
    nextTick(() => {
      lyricRef.value?.setCurrentTime(getCurrentTime() + status.lyricOffsetMs);
      if (isPlaying.value) lyricRef.value?.resume();
    });
  },
);

const fullscreenCover = computed(() => settings.player.coverLayout === "fullscreen");
const coverWidth = computed(() => `${settings.player.coverLyricRatio * 100}%`);

const coverCentered = computed(() => {
  if (fullscreenCover.value || status.fullQueueOpen) return false;
  return !showLyric.value || (settings.player.autoCenterCover && !hasLyric.value);
});

const lyricFontSize = computed(() =>
  settings.lyric.adaptiveFontSize
    ? `calc(${settings.lyric.fontSize} / 1080 * 100vh)`
    : `${settings.lyric.fontSize}px`,
);

const handleLyricSeek = async (timeMs: number): Promise<void> => {
  await player.seek(timeMs);
  if (!isPlaying.value) await player.play();
};

const springConfig = computed(() => ({
  mass: settings.lyric.springMass,
  damping: settings.lyric.springDamping,
  stiffness: settings.lyric.springStiffness,
}));

/** 响应式布局：Android 平板与 PC 双栏，手机窄屏走竖屏布局 */
const {
  useMobileLayout: responsiveUseMobileLayout,
  isCompactMobilePlayer,
  isAndroidTablet,
} = useResponsiveLayout();

/** 横屏切换动画协调?*/
const { isImmersiveLandscape } = useOrientationTransition();

/** 主动横屏模式下强制使用手机布局，避免被 PC 布局覆盖导致无法退出?*/
const useMobileLayout = computed(
  () => responsiveUseMobileLayout.value || isImmersiveLandscape.value,
);

/** 移动端卡片进出场动画钩子（仅 compact 竖屏布局写内联） */
const { onEnter: onMobileCardEnter, onLeave: onMobileCardLeave } =
  useMobileCardTransition(isCompactMobilePlayer);

const {
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
} = useImmersiveMode(isPlayerExpanded);

const { isFullscreen, toggleFullscreen } = useWindowControls();

/** 平板模式手动隐藏播放页元信息（顶/底栏），轻触屏幕恢复 */
const manualHidden = ref(false);
const hidePlayerBars = (): void => {
  manualHidden.value = true;
};
/** 轻触恢复：同时清除手动隐藏与自动沉浸 */
const onManualTapRestore = (e: Event) => {
  manualHidden.value = false;
  onTapRestore(e);
};

const canDownload = computed(
  () =>
    !!displayTrack.value &&
    displayTrack.value.source !== "local" &&
    settings.system.download.enabled,
);

const downloadQualityItems = computed(() =>
  buildDownloadQualityItems(t("download.qualityDefault")),
);

const onDownloadSelect = (key: string): void => {
  if (!displayTrack.value) return;
  void enqueueDownload(displayTrack.value, key ? { quality: key as QualityLevel } : {});
};

const collapse = (): void => {
  status.fullQueueOpen = false;
  isPlayerExpanded.value = false;
};

/** Android 下滑手势关闭全屏播放?*/
const SWIPE_THRESHOLD = 80;
let touchStartY = 0;
let touchStartX = 0;
/** 触摸起点是否在歌词区域内（歌词有自己的滚动，不应触发下滑退出） */
let touchInLyricArea = false;

const onTouchStart = (e: TouchEvent): void => {
  if (!isAndroid || useMobileLayout.value) return;
  const touch = e.touches[0];
  touchStartY = touch.clientY;
  touchStartX = touch.clientX;
  // 歌词区域有独立的滚动机制，标记后?onTouchEnd 中跳过退出逻辑
  const target = e.target as HTMLElement;
  touchInLyricArea = !!target?.closest?.(".lp-root");
};

const onTouchEnd = (e: TouchEvent): void => {
  if (!isAndroid || useMobileLayout.value) return;
  if (touchInLyricArea) return;
  const touch = e.changedTouches[0];
  const deltaY = touch.clientY - touchStartY;
  const deltaX = Math.abs(touch.clientX - touchStartX);
  // 下滑超过阈值且水平偏移较小时关?
  if (deltaY > SWIPE_THRESHOLD && deltaX < deltaY) {
    collapse();
  }
};

const onSeekDragEnd = (value: number): void => {
  player.seek(snapToNearestLyric(value));
};

/** 添加到歌?*/
const {
  open: pickerOpen,
  tracks: pickerTracks,
  mode: pickerMode,
  openPicker,
} = usePlaylistPicker();

const lyricToggleDisabled = computed(() => !hasLyric.value || fullscreenCover.value);
const lyricToggleActive = computed(
  () => showLyric.value && hasLyric.value && !status.fullQueueOpen && !fullscreenCover.value,
);
const androidLyricInteractive = computed(
  () => showLyric.value && !status.fullQueueOpen && !fullscreenCover.value,
);

/** 切换歌词展示 */
const toggleLyric = (): void => {
  if (status.fullQueueOpen) {
    status.fullQueueOpen = false;
    showLyric.value = true;
  } else {
    showLyric.value = !showLyric.value;
  }
};

const showComments = (): void => {
  if (displayTrack.value) status.showComments(displayTrack.value);
};
</script>

<template>
  <Teleport to="#app">
    <Transition
      :css="!isCompactMobilePlayer"
      mode="out-in"
      enter-active-class="transition-transform duration-500 ease-[cubic-bezier(0.7,0,0.3,1)]"
      leave-active-class="transition-transform duration-500 ease-[cubic-bezier(0.7,0,0.3,1)]"
      enter-from-class="translate-y-full"
      leave-to-class="translate-y-full"
      @after-enter="onAfterEnter"
      @before-leave="onBeforeLeave"
      @after-leave="onAfterLeave"
      @enter="onMobileCardEnter"
      @leave="onMobileCardLeave"
    >
      <div
        v-if="isPlayerExpanded"
        :class="[
          'full-player fixed inset-0 z-200 bg-surface overflow-hidden text-cover',
          useMobileLayout ? 'mobile-full-player' : '',
          immersive || tapRestoreShield ? 'cursor-none [&_*]:!cursor-none' : '',
        ]"
        :style="[
          {
            '--lp-color': 'rgb(var(--s-cover))',
            '--tablet-lyric-padding-x': `${settings.lyric.landscapeLyricPaddingX}px`,
          },
          useMobileLayout ? { backgroundColor: 'rgb(var(--s-surface))' } : {},
        ]"
        @mouseenter="onPlayerMouseEnter"
        @mouseleave="onPlayerMouseLeave"
        @touchstart.passive="
          onTouchStart($event);
          armIdle();
        "
        @touchend.passive="onTouchEnd"
      >
        <!-- 背景 -->
        <PlayerBackground />

        <!-- 平板触摸恢复拦截层：bars 隐藏时覆盖全屏，首次触摸恢复 UI -->
        <div
          v-if="tapRestoreEnabled && (immersive || tapRestoreShield || manualHidden)"
          class="absolute inset-0 z-[250] cursor-default"
          @pointerdown="onManualTapRestore"
        />

        <!-- 移动端全屏布局 -->
        <FullPlayerMobile v-if="useMobileLayout" @collapse="collapse" />

        <!-- 桌面端全屏布局 -->
        <template v-else>
          <!-- 全屏封面（手机竖屏不使用 PC 左侧铺底方案?-->
          <div v-if="fullscreenCover" class="absolute inset-y-0 left-0 w-[60%]">
            <PlayerCover fullscreen />
          </div>
          <!-- 底部频谱 -->
          <BottomSpectrum
            v-if="isPlayerExpanded && settings.player.enableSpectrum"
            :show="isPlaying && (immersive || manualHidden)"
          />
          <!-- 顶/底栏渐变遮罩（全屏封面模式） -->
          <div
            v-if="fullscreenCover"
            class="cover-mask-top absolute top-0 inset-x-0 h-20 z-5 pointer-events-none transition-opacity duration-400"
            :class="immersive || manualHidden ? 'opacity-0' : 'opacity-100'"
          />
          <div
            v-if="fullscreenCover"
            class="cover-mask-bottom absolute bottom-0 inset-x-0 h-48 z-5 pointer-events-none transition-opacity duration-400"
            :class="immersive || manualHidden ? 'opacity-0' : 'opacity-100'"
          />
          <!-- 顶栏 -->
          <div
            class="absolute top-0 inset-x-0 h-14 z-10 app-drag-region transition-opacity duration-400 flex items-center justify-between px-3"
            :class="immersive || manualHidden ? 'opacity-0 pointer-events-none' : 'opacity-100'"
            @mouseenter="onBarEnter"
            @mouseleave="onBarLeave"
          >
            <div class="app-no-drag flex items-center gap-2">
              <SButton
                v-if="useMobileLayout"
                type="cover"
                variant="ghost"
                circle
                :size="40"
                @click="collapse"
              >
                <template #icon><IconLucideChevronDown /></template>
              </SButton>
              <SButton
                type="cover"
                variant="ghost"
                circle
                :size="40"
                :disabled="lyricToggleDisabled"
                :class="lyricToggleActive ? 'opacity-100' : 'opacity-40'"
                @click="toggleLyric"
              >
                <template #icon><IconLucideTextQuote /></template>
              </SButton>
            </div>
            <div class="app-no-drag flex items-center gap-2">
              <template v-if="useMobileLayout">
                <SButton
                  type="cover"
                  variant="ghost"
                  circle
                  :size="40"
                  :disabled="!hasTrack"
                  @click="fav.toggle(media.track)"
                >
                  <template #icon>
                    <IconFavorite v-if="fav.isLiked(media.track)" />
                    <IconFavoriteOutline v-else />
                  </template>
                </SButton>
                <SButton
                  v-if="media.track?.source === 'local' || media.track?.source === 'netease'"
                  type="cover"
                  variant="ghost"
                  circle
                  :size="40"
                  @click="media.track && openPicker([media.track])"
                >
                  <template #icon><IconLucideListPlus /></template>
                </SButton>
              </template>
              <template v-else>
                <SButton
                  v-if="!isAndroid"
                  type="cover"
                  variant="ghost"
                  circle
                  :size="40"
                  @click="toggleFullscreen"
                >
                  <template #icon>
                    <IconLucideMinimize v-if="isFullscreen" />
                    <IconLucideMaximize v-else />
                  </template>
                </SButton>
                <WindowControls v-if="!isAndroid" cover />
              </template>
            </div>
          </div>
          <!-- 主区域?-->
          <div
            class="absolute top-14 inset-x-0"
            :class="useMobileLayout ? 'bottom-24 flex flex-col px-5 pt-2 pb-3' : 'bottom-20'"
            @mousemove="onMainMove"
          >
            <!-- 左侧 / 手机顶部封面 -->
            <div
              v-if="!fullscreenCover"
              class="transition-transform duration-600 ease-[cubic-bezier(0.4,0,0.2,1)]"
              :class="
                useMobileLayout
                  ? 'relative shrink-0 flex items-center justify-center px-6 pt-2 pb-4'
                  : 'absolute inset-y-0 left-0 flex items-center justify-center px-12'
              "
              :style="
                useMobileLayout
                  ? undefined
                  : {
                      width: coverWidth,
                      transform: coverCentered ? 'translateX(calc(50vw - 50%))' : undefined,
                    }
              "
            >
              <!-- 封面 + 歌曲信息 -->
              <div
                class="relative"
                :class="
                  useMobileLayout
                    ? 'w-[clamp(176px,58vw,300px)]'
                    : 'w-[clamp(200px,85%,50vh)] -translate-y-[11vh]'
                "
              >
                <Transition name="scale-switch" mode="out-in">
                  <div :key="displayTrack?.id">
                    <PlayerCover />
                    <!-- 歌曲信息 -->
                    <div
                      class="left-0 w-full"
                      :class="useMobileLayout ? 'relative pt-4' : 'absolute top-full pt-6'"
                    >
                      <PlayerData :align="useMobileLayout ? 'center' : 'left'" />
                    </div>
                  </div>
                </Transition>
              </div>
            </div>
            <!-- 右侧 / 手机歌词区?-->
            <div
              class="group flex flex-col transition-opacity duration-600 ease-[cubic-bezier(0.4,0,0.2,1)]"
              :class="[
                useMobileLayout
                  ? 'relative flex-1 min-h-0 px-1'
                  : isAndroidTablet
                    ? 'absolute inset-y-0 right-0'
                    : 'absolute inset-y-0 right-0 pr-20',
                !useMobileLayout &&
                  (fullscreenCover ? 'w-1/2' : isAndroidTablet ? 'w-[60%]' : 'w-[55%]'),
                !useMobileLayout && (coverCentered || status.fullQueueOpen)
                  ? 'opacity-0 pointer-events-none'
                  : 'opacity-100',
              ]"
              :style="
                !useMobileLayout && !fullscreenCover && !isAndroidTablet
                  ? { width: `calc(100% - ${coverWidth})` }
                  : undefined
              "
            >
              <!-- 全屏封面 -->
              <div
                v-if="fullscreenCover"
                class="shrink-0"
                :class="
                  useMobileLayout ? 'px-2 pt-4 pb-4 text-center' : 'pt-2 pb-6 pl-[calc(1em-0.5rem)]'
                "
                :style="{
                  fontSize: settings.lyric.adaptiveFontSize
                    ? `calc(${settings.lyric.fontSize} / 1080 * 100vmin)`
                    : `${settings.lyric.fontSize}px`,
                }"
              >
                <PlayerData :align="useMobileLayout ? 'center' : 'left'" simple />
              </div>
              <div
                class="lyric-area relative flex-1 min-h-0"
                :class="{ 'tablet-lyric-area': isAndroidTablet && !useMobileLayout }"
                :style="{
                  fontSize: settings.lyric.adaptiveFontSize
                    ? useMobileLayout
                      ? `calc(${settings.lyric.fontSize} / 430 * 100vmin)`
                      : `calc(${settings.lyric.fontSize} / 1080 * 100vmin)`
                    : `${settings.lyric.fontSize}px`,
                  fontWeight: String(containerLyricWeight),
                  fontFamily: settings.lyric.fontFamily || undefined,
                  '--lyric-font-zh': settings.lyric.fontFamilyChinese || undefined,
                  '--lyric-font-ja': settings.lyric.fontFamilyJapanese || undefined,
                  '--lyric-font-ko': settings.lyric.fontFamilyKorean || undefined,
                  '--lyric-font-latin': settings.lyric.fontFamilyLatin || undefined,
                  mixBlendMode: settings.lyric.lyricBlendMode,
                }"
              >
                <AMLLLyrics
                  v-if="lyricMounted && hasLyric && settings.lyric.engine === 'amll'"
                  ref="lyricRef"
                  :lyric-lines="media.parsedLyric"
                  :initial-time="initialLyricTimeMs"
                  :time-offset-ms="status.lyricOffsetMs"
                  :playing="isPlaying"
                  :align-position="settings.lyric.alignPosition"
                  :word-fade-width="settings.lyric.wordFadeWidth"
                  :hide-passed-lines="settings.lyric.hidePassedLines"
                  :enable-blur="settings.lyric.enableBlur"
                  :show-translation="settings.lyric.showTranslation"
                  :show-line-romanization="settings.lyric.amllShowLineRomanization"
                  :show-word-romanization="settings.lyric.amllShowWordRomanization"
                  @seek="handleLyricSeek($event)"
                >
                  <template #bottom>
                    <div v-if="media.lyricAuthors.length > 0" class="lyric-credit-line">
                      <span class="lyric-credit-prefix">{{ $t("player.lyricCredit") }}</span>
                      <template v-for="(author, idx) in media.lyricAuthors" :key="author">
                        <span v-if="idx > 0" class="mx-1">,</span>
                        <span
                          class="lp-content lyric-credit"
                          @click.stop="openExternal(`https://github.com/${author}`)"
                        >
                          {{ "@" + author }}
                        </span>
                      </template>
                    </div>
                  </template>
                </AMLLLyrics>
                <AndroidMainLyricHost
                  v-else-if="lyricMounted && hasLyric && isAndroid"
                  ref="lyricRef"
                  :lyric-lines="media.parsedLyric"
                  :initial-time="initialLyricTimeMs"
                  :playing="isPlaying"
                  :visible="!pickerOpen"
                  :font-weight="settings.lyric.fontWeight"
                  :font-family="settings.lyric.fontFamily"
                  :align-position="settings.lyric.alignPosition"
                  :word-fade-width="settings.lyric.wordFadeWidth"
                  :spring-config="springConfig"
                  :inactive-alpha="settings.lyric.inactiveAlpha"
                  :hide-passed-lines="settings.lyric.hidePassedLines"
                  :enable-blur="settings.lyric.enableBlur"
                  :enable-word-highlight="settings.lyric.enableWordHighlight"
                  :enable-float-animation="settings.lyric.enableFloatAnimation"
                  :enable-emphasize-effect="settings.lyric.enableEmphasizeEffect"
                  :enable-word-block-segmentation="false"
                  :show-translation="settings.lyric.showTranslation"
                  :show-romanization="settings.lyric.showRomanization"
                  :render-mode="androidLyricRenderMode"
                  :bottom-exclusion-height-px="0"
                  :interactive="androidLyricInteractive"
                  @seek="handleLyricSeek($event)"
                />
                <Lyrics
                  v-else-if="lyricMounted && hasLyric"
                  ref="lyricRef"
                  :lyric-lines="media.parsedLyric"
                  :initial-time="initialLyricTimeMs"
                  :playing="isPlaying"
                  :align-position="settings.lyric.alignPosition"
                  :word-fade-width="settings.lyric.wordFadeWidth"
                  :spring-config="springConfig"
                  :inactive-alpha="settings.lyric.inactiveAlpha"
                  :hide-passed-lines="settings.lyric.hidePassedLines"
                  :enable-blur="settings.lyric.enableBlur"
                  :enable-word-highlight="settings.lyric.enableWordHighlight"
                  :enable-float-animation="settings.lyric.enableFloatAnimation"
                  :enable-emphasize-effect="settings.lyric.enableEmphasizeEffect"
                  :show-translation="settings.lyric.showTranslation"
                  :show-romanization="settings.lyric.showRomanization"
                  :unlock-fps-limit="true"
                  @seek="handleLyricSeek($event)"
                >
                  <template #bottom>
                    <div v-if="media.lyricAuthors.length > 0" class="lyric-credit-line">
                      <span class="lyric-credit-prefix">{{ $t("player.lyricCredit") }}</span>
                      <template v-for="(author, idx) in media.lyricAuthors" :key="author">
                        <span v-if="idx > 0" class="mx-1">,</span>
                        <span
                          class="lp-content lyric-credit"
                          @click.stop="openExternal(`https://github.com/${author}`)"
                        >
                          {{ "@" + author }}
                        </span>
                      </template>
                    </div>
                  </template>
                </Lyrics>
                <div
                  v-else-if="lyricMounted"
                  class="w-full h-full flex items-center justify-center text-cover/30"
                >
                  暂无歌词
                </div>
              </div>
              <!-- 歌词侧边工具栏?-->
              <LyricActions v-if="!useMobileLayout" :immersive="immersive" />
            </div>
            <!-- 播放队列：仅打开时阻?touch 冒泡，避免关闭时占位区误吞父级滑动手?-->
            <div
              class="flex items-center"
              :class="[
                useMobileLayout
                  ? 'absolute inset-x-4 top-4 bottom-4 z-20'
                  : 'absolute inset-y-0 right-0 pl-4 py-6',
                !useMobileLayout && (fullscreenCover ? 'w-1/2' : 'w-[55%]'),
                status.fullQueueOpen ? '' : 'pointer-events-none',
              ]"
              :style="
                !useMobileLayout && !fullscreenCover
                  ? { width: `calc(100% - ${coverWidth})` }
                  : undefined
              "
              @touchstart.passive="status.fullQueueOpen && $event.stopPropagation()"
              @touchend.passive="status.fullQueueOpen && $event.stopPropagation()"
            >
              <Transition
                enter-active-class="transition-opacity duration-600 ease-[cubic-bezier(0.4,0,0.2,1)]"
                enter-from-class="opacity-0"
                leave-active-class="transition-opacity duration-600 ease-[cubic-bezier(0.4,0,0.2,1)]"
                leave-to-class="opacity-0"
              >
                <div v-if="status.fullQueueOpen" class="w-full h-full">
                  <QueuePanel @close="status.fullQueueOpen = false" />
                </div>
              </Transition>
            </div>
          </div>
          <!-- 底栏 -->
          <div
            v-if="useMobileLayout"
            class="absolute bottom-0 inset-x-0 z-10 transition-opacity duration-400"
            :class="[
              immersive || manualHidden ? 'opacity-0 pointer-events-none' : 'opacity-100',
              useMobileLayout
                ? 'h-24 flex flex-col justify-center gap-2 px-5 pb-[env(safe-area-inset-bottom,0px)]'
                : 'h-[calc(5rem+var(--mobile-safe-bottom))] flex items-center gap-4 px-4 pb-[var(--mobile-safe-bottom)]',
            ]"
            @mouseenter="onBarEnter"
            @mouseleave="onBarLeave"
          >
            <div
              v-if="!useMobileLayout"
              class="flex-1 min-w-0 flex items-center justify-start gap-2"
            >
              <PlayerData align="left" simple />
            </div>
            <!-- 歌词容器 -->
            <div
              class="lyric-area relative flex-1 min-h-0"
              :style="{
                fontSize: lyricFontSize,
                fontWeight: String(containerLyricWeight),
                fontFamily: settings.lyric.fontFamily || undefined,
                '--lyric-font-zh': settings.lyric.fontFamilyChinese || undefined,
                '--lyric-font-ja': settings.lyric.fontFamilyJapanese || undefined,
                '--lyric-font-ko': settings.lyric.fontFamilyKorean || undefined,
                '--lyric-font-latin': settings.lyric.fontFamilyLatin || undefined,
                mixBlendMode: settings.lyric.lyricBlendMode,
              }"
            >
              <div
                v-if="lyricMounted && hasLyric && androidLyricRenderMode === 'kotlin'"
                class="android-bottom-lyric"
              >
                {{ bottomBarLyricText }}
              </div>
              <AMLLLyrics
                v-else-if="lyricMounted && hasLyric && settings.lyric.engine === 'amll'"
                ref="lyricRef"
                :lyric-lines="media.parsedLyric"
                :initial-time="initialLyricTimeMs"
                :playing="isPlaying"
                :align-position="settings.lyric.alignPosition"
                :word-fade-width="settings.lyric.wordFadeWidth"
                :hide-passed-lines="settings.lyric.hidePassedLines"
                :enable-blur="settings.lyric.enableBlur"
                :show-translation="settings.lyric.showTranslation"
                :show-line-romanization="settings.lyric.amllShowLineRomanization"
                :show-word-romanization="settings.lyric.amllShowWordRomanization"
                @seek="handleLyricSeek"
              >
                <template #bottom>
                  <div v-if="media.lyricAuthors.length > 0" class="lyric-credit-line">
                    <span class="lyric-credit-prefix">{{ $t("player.lyricCredit") }}</span>
                    <template v-for="(author, idx) in media.lyricAuthors" :key="author">
                      <span v-if="idx > 0" class="mx-1">,</span>
                      <span
                        class="lp-content lyric-credit"
                        @click.stop="openExternal(`https://github.com/${author}`)"
                      >
                        {{ "@" + author }}
                      </span>
                    </template>
                  </div>
                </template>
              </AMLLLyrics>
              <Lyrics
                v-else-if="lyricMounted && hasLyric"
                ref="lyricRef"
                :lyric-lines="media.parsedLyric"
                :initial-time="initialLyricTimeMs"
                :playing="isPlaying"
                :align-position="settings.lyric.alignPosition"
                :word-fade-width="settings.lyric.wordFadeWidth"
                :spring-config="springConfig"
                :inactive-alpha="settings.lyric.inactiveAlpha"
                :hide-passed-lines="settings.lyric.hidePassedLines"
                :enable-blur="settings.lyric.enableBlur"
                :enable-word-highlight="settings.lyric.enableWordHighlight"
                :enable-float-animation="settings.lyric.enableFloatAnimation"
                :enable-emphasize-effect="settings.lyric.enableEmphasizeEffect"
                :show-translation="settings.lyric.showTranslation"
                :show-romanization="settings.lyric.showRomanization"
                @seek="handleLyricSeek"
              >
                <template #bottom>
                  <div v-if="media.lyricAuthors.length > 0" class="lyric-credit-line">
                    <span class="lyric-credit-prefix">{{ $t("player.lyricCredit") }}</span>
                    <template v-for="(author, idx) in media.lyricAuthors" :key="author">
                      <span v-if="idx > 0" class="mx-1">,</span>
                      <span
                        class="lp-content lyric-credit"
                        @click.stop="openExternal(`https://github.com/${author}`)"
                      >
                        {{ "@" + author }}
                      </span>
                    </template>
                  </div>
                </template>
              </Lyrics>
              <div
                v-else-if="lyricMounted"
                class="w-full h-full flex items-center justify-center text-cover/30"
              >
                暂无歌词
              </div>
            </div>
            <!-- 歌词侧边工具栏?-->
            <LyricActions :immersive="immersive" />
          </div>
          <!-- 播放队列 -->
          <div
            v-if="useMobileLayout"
            class="absolute inset-y-0 right-0 pl-4 py-6 flex items-center"
            :class="status.fullQueueOpen ? '' : 'pointer-events-none'"
            :style="{ width: fullscreenCover ? '50%' : `calc(100% - ${coverWidth})` }"
          >
            <Transition
              enter-active-class="transition-opacity duration-600 ease-[cubic-bezier(0.4,0,0.2,1)]"
              enter-from-class="opacity-0"
              leave-active-class="transition-opacity duration-600 ease-[cubic-bezier(0.4,0,0.2,1)]"
              leave-to-class="opacity-0"
            >
              <div v-if="status.fullQueueOpen" class="w-full h-full">
                <QueuePanel @close="status.fullQueueOpen = false" />
              </div>
            </Transition>
          </div>
          <!-- 底栏 -->
          <div
            class="absolute bottom-0 inset-x-0 h-20 z-10 flex items-center gap-4 px-4 transition-opacity duration-400"
            :class="immersive || manualHidden ? 'opacity-0 pointer-events-none' : 'opacity-100'"
            @mouseenter="onBarEnter"
            @mouseleave="onBarLeave"
          >
            <div class="flex-1 min-w-0 flex items-center justify-start gap-2">
              <SButton type="cover" variant="ghost" size="large" circle @click="collapse">
                <template #icon><IconLucideChevronDown /></template>
              </SButton>
              <SButton
                type="cover"
                variant="ghost"
                size="large"
                circle
                :disabled="!hasTrack"
                @click="fav.toggle(displayTrack)"
              >
                <template #icon>
                  <SIconSwap :active="fav.isLiked(displayTrack)">
                    <template #on><IconFavorite /></template>
                    <template #off><IconFavoriteOutline /></template>
                  </SIconSwap>
                </template>
              </SButton>
              <SButton
                type="cover"
                variant="ghost"
                size="large"
                circle
                :disabled="!hasTrack"
                @click="showComments"
              >
                <template #icon><IconLucideMessageCircle /></template>
              </SButton>
              <SButton
                v-if="displayTrack?.source === 'local' || displayTrack?.source === 'netease'"
                type="cover"
                variant="ghost"
                size="large"
                circle
                @click="displayTrack && openPicker([displayTrack])"
              >
                <template #icon><IconLucideListPlus /></template>
              </SButton>
              <SDropdownMenu
                v-if="canDownload"
                :items="downloadQualityItems"
                cover
                side="top"
                align="start"
                @select="onDownloadSelect"
              >
                <template #trigger>
                  <SButton type="cover" variant="ghost" size="large" circle>
                    <template #icon><IconLucideDownload /></template>
                  </SButton>
                </template>
              </SDropdownMenu>
              <QuickActionsMenu v-if="isAndroidTablet" variant="control" cover tablet />
              <SButton
                v-if="isAndroidTablet"
                type="cover"
                variant="ghost"
                size="large"
                circle
                aria-label="隐藏底栏"
                @click="hidePlayerBars"
              >
                <template #icon><IconLucideEyeOff /></template>
              </SButton>
            </div>
            <div class="shrink-0 flex flex-col items-center gap-1 w-[clamp(360px,35%,480px)]">
              <div class="flex items-center gap-3">
                <SButton
                  type="cover"
                  variant="ghost"
                  circle
                  @click="
                    fmMode
                      ? player.dislikeFmTrack()
                      : heartMode
                        ? player.exitHeartMode()
                        : player.toggleShuffleMode()
                  "
                >
                  <template #icon>
                    <IconLucideHeartOff v-if="fmMode" />
                    <IconSpHeartMode v-else-if="heartMode" />
                    <IconLucideShuffle v-else-if="shuffleMode === 'on'" />
                    <IconSpPlayOrder v-else />
                  </template>
                </SButton>
                <SButton
                  type="cover"
                  variant="ghost"
                  circle
                  :disabled="!hasTrack || fmMode"
                  @click="player.prevTrack()"
                >
                  <template #icon><IconLucideSkipBack /></template>
                </SButton>
                <SButton
                  type="cover"
                  variant="secondary"
                  size="large"
                  circle
                  :loading="isLoading"
                  :disabled="!hasTrack && !isLoading"
                  @click="player.togglePlay()"
                >
                  <template #icon>
                    <SIconSwap :active="isPlaying">
                      <template #on><IconLucidePause /></template>
                      <template #off><IconLucidePlay /></template>
                    </SIconSwap>
                  </template>
                </SButton>
                <SButton
                  type="cover"
                  variant="ghost"
                  circle
                  :disabled="!hasTrack"
                  @click="player.nextTrack(true)"
                >
                  <template #icon><IconLucideSkipForward /></template>
                </SButton>
                <SButton
                  type="cover"
                  variant="ghost"
                  circle
                  :disabled="fmMode"
                  :class="fmMode ? 'opacity-40' : 'opacity-100'"
                  @click="player.cycleRepeatMode()"
                >
                  <template #icon>
                    <IconLucideInfinity v-if="fmMode" />
                    <IconLucideRepeat1 v-else-if="repeatMode === 'one'" />
                    <IconLucideRepeat v-else />
                  </template>
                </SButton>
              </div>
              <div class="flex items-center gap-2 w-full">
                <span
                  class="text-xs text-cover/50 tabular-nums min-w-9 text-center cursor-pointer px-1.5 py-0.5 rounded-md transition-colors hover:bg-cover/10"
                  @click="toggleTimeFormat"
                >
                  {{ timeDisplay[0] }}
                </span>
                <SSlider
                  :model-value="position"
                  :min="0"
                  :max="duration"
                  :step="100"
                  :always-show-thumb="false"
                  cover
                  class="flex-1"
                  @drag-end="onSeekDragEnd"
                />
                <span
                  class="text-xs text-cover/50 tabular-nums min-w-9 text-center cursor-pointer px-1.5 py-0.5 rounded-md transition-colors hover:bg-cover/10"
                  @click="toggleTimeFormat"
                >
                  {{ timeDisplay[1] }}
                </span>
              </div>
            </div>
            <div class="flex-1 min-w-0 flex items-center justify-end">
              <Toolbar cover />
            </div>
          </div>
        </template>
      </div>
    </Transition>
    <PlaylistPickerDialog v-model:open="pickerOpen" :mode="pickerMode" :tracks="pickerTracks" />
  </Teleport>
</template>

<style scoped>
.lyric-area {
  filter: drop-shadow(0px 4px 6px rgba(0, 0, 0, 0.2));
  -webkit-mask: linear-gradient(
    180deg,
    hsla(0, 0%, 100%, 0) 0,
    hsla(0, 0%, 100%, 0.6) 5%,
    #fff 10%,
    #fff 75%,
    hsla(0, 0%, 100%, 0.6) 85%,
    hsla(0, 0%, 100%, 0)
  );
  mask: linear-gradient(
    180deg,
    hsla(0, 0%, 100%, 0) 0,
    hsla(0, 0%, 100%, 0.6) 5%,
    #fff 10%,
    #fff 75%,
    hsla(0, 0%, 100%, 0.6) 85%,
    hsla(0, 0%, 100%, 0)
  );
}

.tablet-lyric-area {
  padding-inline: max(0px, var(--tablet-lyric-padding-x, 0px));
  margin-inline: min(0px, var(--tablet-lyric-padding-x, 0px));
}

.android-bottom-lyric {
  display: flex;
  align-items: center;
  width: 100%;
  height: 100%;
  overflow: hidden;
  color: rgb(var(--s-cover) / 0.75);
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 顶部/底部遮罩：多段非线?alpha，避免暗色渐变出色阶 */
.cover-mask-top {
  background-image: linear-gradient(
    to bottom,
    rgba(0, 0, 0, 0.5) 0%,
    rgba(0, 0, 0, 0.44) 12%,
    rgba(0, 0, 0, 0.36) 25%,
    rgba(0, 0, 0, 0.27) 40%,
    rgba(0, 0, 0, 0.18) 55%,
    rgba(0, 0, 0, 0.1) 70%,
    rgba(0, 0, 0, 0.04) 85%,
    rgba(0, 0, 0, 0) 100%
  );
}

.cover-mask-bottom {
  background-image: linear-gradient(
    to top,
    rgba(0, 0, 0, 0.5) 0%,
    rgba(0, 0, 0, 0.44) 12%,
    rgba(0, 0, 0, 0.36) 25%,
    rgba(0, 0, 0, 0.27) 40%,
    rgba(0, 0, 0, 0.18) 55%,
    rgba(0, 0, 0, 0.1) 70%,
    rgba(0, 0, 0, 0.04) 85%,
    rgba(0, 0, 0, 0) 100%
  );
}

.lyric-credit-line {
  font-size: max(0.5em, 10px);
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-start;
  text-align: left;
  width: 100%;
}

.lyric-credit {
  margin-left: 0.5em;
}
</style>
