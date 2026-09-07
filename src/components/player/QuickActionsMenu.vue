<script setup lang="ts">
import { isAndroid } from "@/services/bridge";
import { useStatusStore } from "@/stores/status";
import { useSettingsStore } from "@/stores/settings";
import * as player from "@/core/player";
import IconLucideMoreHorizontal from "~icons/lucide/more-horizontal";
import IconLucideAudioWaveform from "~icons/lucide/audio-waveform";
import IconLucideHighlighter from "~icons/lucide/highlighter";
import IconLucideCloud from "~icons/lucide/cloud";
import IconLucideVolume2 from "~icons/lucide/volume-2";
import IconLucideALargeSmall from "~icons/lucide/a-large-small";
import IconLucideWaves from "~icons/lucide/waves";
import IconLucideImage from "~icons/lucide/image";
import IconLucideEyeOff from "~icons/lucide/eye-off";
import IconLucideDroplet from "~icons/lucide/droplet";
import IconLucideMinus from "~icons/lucide/minus";
import IconLucidePlus from "~icons/lucide/plus";

const props = withDefaults(
  defineProps<{
    /** 外观变体：mobile 为移动端顶部圆形按钮；control 为桌面工具栏按钮 */
    variant?: "mobile" | "control";
    /** 封面主题（仅 control 变体生效，决定按钮与面板配色） */
    cover?: boolean;
    /** 手机横屏模式：切换到横屏专属歌词字号与布局调节 */
    landscape?: boolean;
    /** 平板模式：显示平板歌词边距调节 */
    tablet?: boolean;
  }>(),
  { variant: "mobile", cover: false, landscape: false, tablet: false },
);

const emit = defineEmits<{
  (e: "update:open", value: boolean): void;
}>();

const { t } = useI18n();
const status = useStatusStore();
const settings = useSettingsStore();

const isMobile = computed(() => props.variant === "mobile");
const isLandscape = computed(() => props.landscape === true);
const isTablet = computed(() => props.tablet === true);
const lyricPaddingLabel = computed(() =>
  isLandscape.value ? t("quickToggle.landscapeLyricPadding") : t("quickToggle.lyricPadding"),
);
/**
 * 横屏模式下 trigger 位于屏幕底部最左列，`align="end"` 会让 220px 面板
 * 向左扩展并越过屏幕左边缘，同时遮挡右侧歌词。
 * 这里强制改用 `top-start`：面板从 trigger 上方左对齐展开，向右扩展 220px，
 * 远离歌词区（歌词从 38% 屏幕宽度开始）。
 */
const popoverSide = computed(() => {
  if (isLandscape.value) return "top";
  return isMobile.value ? "bottom" : "top";
});
const popoverAlign = computed(() => {
  if (isLandscape.value) return "start";
  return isMobile.value ? "start" : "end";
});
const popoverCover = computed(() => (isMobile.value ? true : props.cover));

const triggerButtonType = computed(() =>
  isMobile.value ? "cover" : props.cover ? "cover" : "default",
);
const triggerButtonSize = computed(() => 40);
const triggerIconSize = computed(() => (isMobile.value ? 22 : 22));
const triggerMutedClass = computed(() =>
  isMobile.value ? "text-cover/70" : props.cover ? "text-cover" : "text-on-surface-variant",
);

const open = ref(false);

/** 音频频谱开关 */
const spectrum = computed<boolean>({
  get: () => settings.player.enableSpectrum,
  set: (v) => {
    settings.player.enableSpectrum = v;
  },
});

/** 频谱算法方案：pc 对齐桌面端；android 保留原生 */
const spectrumAlgorithm = computed<"pc" | "android">({
  get: () => settings.player.spectrumAlgorithm,
  set: (v) => {
    settings.player.spectrumAlgorithm = v;
  },
});

/** 逐词效果开关 */
const wordHighlight = computed<boolean>({
  get: () => settings.lyric.enableWordHighlight,
  set: (v) => {
    settings.lyric.enableWordHighlight = v;
  },
});

/** 在线 TTML 歌词开关（system 字段，走 IPC 落盘） */
const onlineTTML = computed<boolean>({
  get: () => settings.system.lyric.enableOnlineTTMLLyric,
  set: (v) => {
    settings.setSystem("lyric.enableOnlineTTMLLyric", v);
  },
});

/** 音量（0-1） */
const volumePercent = computed(() => Math.round(status.volume * 100));

/** 歌词字号 */
const FONT_SIZE_MIN = 24;
const FONT_SIZE_MAX = 100;
const FONT_SIZE_STEP = 2;
const LANDSCAPE_FONT_SIZE_MIN = 16;
const LANDSCAPE_FONT_SIZE_MAX = 40;
const LANDSCAPE_FONT_SIZE_STEP = 1;
const LANDSCAPE_COVER_OFFSET_MIN = -80;
const LANDSCAPE_COVER_OFFSET_MAX = 80;
const LANDSCAPE_COVER_OFFSET_STEP = 2;
const LANDSCAPE_LYRIC_PADDING_MIN = -120;
const LANDSCAPE_LYRIC_PADDING_MAX = 120;
const LANDSCAPE_LYRIC_PADDING_STEP = 2;

const clampValue = (value: number, min: number, max: number): number =>
  Math.min(max, Math.max(min, value));

const lyricFontSizeLabel = computed(() =>
  isLandscape.value ? t("quickToggle.fontSizeLandscape") : t("quickToggle.fontSize"),
);
const lyricFontSizeValue = computed<number>({
  get: () => (isLandscape.value ? settings.lyric.fontSizeLandscape : settings.lyric.fontSize),
  set: (value) => {
    if (isLandscape.value) {
      settings.lyric.fontSizeLandscape = value;
    } else {
      settings.lyric.fontSize = value;
    }
  },
});
const lyricFontSizeMin = computed(() =>
  isLandscape.value ? LANDSCAPE_FONT_SIZE_MIN : FONT_SIZE_MIN,
);
const lyricFontSizeMax = computed(() =>
  isLandscape.value ? LANDSCAPE_FONT_SIZE_MAX : FONT_SIZE_MAX,
);
const lyricFontSizeStep = computed(() =>
  isLandscape.value ? LANDSCAPE_FONT_SIZE_STEP : FONT_SIZE_STEP,
);

const decFontSize = (): void => {
  lyricFontSizeValue.value = clampValue(
    lyricFontSizeValue.value - lyricFontSizeStep.value,
    lyricFontSizeMin.value,
    lyricFontSizeMax.value,
  );
};
const incFontSize = (): void => {
  lyricFontSizeValue.value = clampValue(
    lyricFontSizeValue.value + lyricFontSizeStep.value,
    lyricFontSizeMin.value,
    lyricFontSizeMax.value,
  );
};
const adjustLandscapeCoverOffset = (delta: number): void => {
  settings.lyric.landscapeCoverOffsetX = clampValue(
    settings.lyric.landscapeCoverOffsetX + delta,
    LANDSCAPE_COVER_OFFSET_MIN,
    LANDSCAPE_COVER_OFFSET_MAX,
  );
};
const adjustLandscapeLyricPadding = (delta: number): void => {
  settings.lyric.landscapeLyricPaddingX = clampValue(
    settings.lyric.landscapeLyricPaddingX + delta,
    LANDSCAPE_LYRIC_PADDING_MIN,
    LANDSCAPE_LYRIC_PADDING_MAX,
  );
};

/** 歌词渲染方式：默认 / AMLL / Kotlin（Kotlin 仅 Android） */
type LyricRenderMode = "default" | "amll" | "kotlin";

const renderMode = computed<LyricRenderMode>(() => {
  if (settings.lyric.engine === "amll") return "amll";
  if (isAndroid && settings.lyric.engine === "kotlin") return "kotlin";
  return "default";
});

const setRenderMode = (mode: LyricRenderMode): void => {
  if (mode === "amll") {
    settings.lyric.engine = "amll";
  } else if (mode === "kotlin") {
    settings.lyric.engine = "kotlin";
    settings.setSystem("androidLyric.renderMode", "kotlin");
  } else {
    settings.lyric.engine = "physics";
    settings.setSystem("androidLyric.renderMode", "legacy");
  }
};

/** 弹簧效果（仅 AMLL 引擎时启用） */
const amllSpringDisabled = computed(() => renderMode.value !== "amll");
const amllSpring = computed<boolean>({
  get: () => settings.lyric.useAMSpring,
  set: (v) => {
    settings.lyric.useAMSpring = v;
  },
});

/** AMLL 动态背景（枚举字段 playerBgType 派生） */
const amllDynamicBg = computed<boolean>({
  get: () => settings.player.playerBgType === "animation",
  set: (v) => {
    settings.player.playerBgType = v ? "animation" : "blur";
    // 打开 AMLL 动态背景时同步开启背景跳动，关闭时不联动
    if (v) {
      settings.player.playerBgBeat = true;
    }
  },
});

/** 隐藏已播放 */
const hidePassed = computed<boolean>({
  get: () => settings.lyric.hidePassedLines,
  set: (v) => {
    settings.lyric.hidePassedLines = v;
  },
});

/** 歌词模糊 */
const lyricBlur = computed<boolean>({
  get: () => settings.lyric.enableBlur,
  set: (v) => {
    settings.lyric.enableBlur = v;
  },
});

/** Android 返回键关闭面板 */
const onPopoverOpenChange = (val: boolean): void => {
  open.value = val;
  emit("update:open", val);
};

/** 音量滑块变化 */
const onVolumeChange = (val: number): void => {
  player.setVolume(val);
};
</script>

<template>
  <SPopover
    v-model:open="open"
    :side="popoverSide"
    :align="popoverAlign"
    :side-offset="8"
    trigger="click"
    :cover="popoverCover"
    content-class="!p-0 w-[236px] quick-actions-popover"
    @update:open="onPopoverOpenChange"
  >
    <template #trigger>
      <SButton
        :type="triggerButtonType"
        variant="ghost"
        circle
        :size="triggerButtonSize"
        :class="triggerMutedClass"
        :aria-label="t('quickToggle.trigger')"
      >
        <template #icon><IconLucideMoreHorizontal :size="triggerIconSize" /></template>
      </SButton>
    </template>

    <div class="qa-panel" :class="popoverCover ? 'qa-panel--cover' : 'qa-panel--plain'">
      <!-- 快捷开关组 -->
      <div class="qa-group">
        <div class="qa-group-title">{{ t("quickToggle.group.quick") }}</div>

        <div class="qa-row qa-row--column qa-row--collapsible">
          <div class="qa-row-head">
            <div class="qa-row-label">
              <IconLucideAudioWaveform :size="18" class="qa-row-icon" />
              <span>{{ t("quickToggle.spectrum") }}</span>
            </div>
            <SSwitch v-model="spectrum" class="qa-switch" :round="false" />
          </div>
          <div v-if="spectrum" class="qa-sub-options">
            <SButton
              :type="popoverCover ? 'cover' : 'default'"
              :variant="spectrumAlgorithm === 'pc' ? 'tertiary' : 'ghost'"
              size="small"
              round
              class="qa-sub-btn"
              @click="spectrumAlgorithm = 'pc'"
            >
              {{ t("quickToggle.spectrumPc") }}
            </SButton>
            <SButton
              :type="popoverCover ? 'cover' : 'default'"
              :variant="spectrumAlgorithm === 'android' ? 'tertiary' : 'ghost'"
              size="small"
              round
              class="qa-sub-btn"
              @click="spectrumAlgorithm = 'android'"
            >
              {{ t("quickToggle.spectrumAndroid") }}
            </SButton>
          </div>
        </div>

        <div class="qa-row">
          <div class="qa-row-label">
            <IconLucideHighlighter :size="18" class="qa-row-icon" />
            <span>{{ t("quickToggle.wordHighlight") }}</span>
          </div>
          <SSwitch v-model="wordHighlight" class="qa-switch" :round="false" />
        </div>

        <div class="qa-row">
          <div class="qa-row-label">
            <IconLucideCloud :size="18" class="qa-row-icon" />
            <span>{{ t("quickToggle.onlineTTML") }}</span>
          </div>
          <SSwitch v-model="onlineTTML" class="qa-switch" :round="false" />
        </div>

        <!-- 音量 -->
        <div class="qa-row qa-row--column">
          <div class="qa-row-head">
            <div class="qa-row-label">
              <IconLucideVolume2 :size="18" class="qa-row-icon" />
              <span>{{ t("quickToggle.volume") }}</span>
            </div>
            <span class="qa-row-value">{{ volumePercent }}%</span>
          </div>
          <SSlider
            :model-value="status.volume"
            :min="0"
            :max="1"
            :step="0.01"
            :thumb-size="14"
            :track-height="4"
            :cover="popoverCover"
            @change="onVolumeChange"
          />
        </div>

        <!-- 歌词字号 -->
        <div class="qa-row qa-row--column">
          <div class="qa-row-head">
            <div class="qa-row-label">
              <IconLucideALargeSmall :size="18" class="qa-row-icon" />
              <span>{{ lyricFontSizeLabel }}</span>
            </div>
            <div class="qa-stepper">
              <SButton
                :type="popoverCover ? 'cover' : 'default'"
                variant="ghost"
                circle
                size="small"
                :disabled="lyricFontSizeValue <= lyricFontSizeMin"
                @click="decFontSize"
              >
                <template #icon><IconLucideMinus :size="14" /></template>
              </SButton>
              <span class="qa-stepper-value">{{ lyricFontSizeValue }}</span>
              <SButton
                :type="popoverCover ? 'cover' : 'default'"
                variant="ghost"
                circle
                size="small"
                :disabled="lyricFontSizeValue >= lyricFontSizeMax"
                @click="incFontSize"
              >
                <template #icon><IconLucidePlus :size="14" /></template>
              </SButton>
            </div>
          </div>
        </div>

        <div v-if="isLandscape" class="qa-row qa-row--column">
          <div class="qa-row-head">
            <div class="qa-row-label">
              <IconLucideImage :size="18" class="qa-row-icon" />
              <span>{{ t("quickToggle.landscapeCoverOffset") }}</span>
            </div>
            <div class="qa-stepper">
              <SButton
                :type="popoverCover ? 'cover' : 'default'"
                variant="ghost"
                circle
                size="small"
                :disabled="settings.lyric.landscapeCoverOffsetX <= LANDSCAPE_COVER_OFFSET_MIN"
                @click="adjustLandscapeCoverOffset(-LANDSCAPE_COVER_OFFSET_STEP)"
              >
                <template #icon><IconLucideMinus :size="14" /></template>
              </SButton>
              <span class="qa-stepper-value">{{ settings.lyric.landscapeCoverOffsetX }}</span>
              <SButton
                :type="popoverCover ? 'cover' : 'default'"
                variant="ghost"
                circle
                size="small"
                :disabled="settings.lyric.landscapeCoverOffsetX >= LANDSCAPE_COVER_OFFSET_MAX"
                @click="adjustLandscapeCoverOffset(LANDSCAPE_COVER_OFFSET_STEP)"
              >
                <template #icon><IconLucidePlus :size="14" /></template>
              </SButton>
            </div>
          </div>
        </div>

        <div v-if="isLandscape || isTablet" class="qa-row qa-row--column">
          <div class="qa-row-head">
            <div class="qa-row-label">
              <IconLucideALargeSmall :size="18" class="qa-row-icon" />
              <span>{{ lyricPaddingLabel }}</span>
            </div>
            <div class="qa-stepper">
              <SButton
                :type="popoverCover ? 'cover' : 'default'"
                variant="ghost"
                circle
                size="small"
                :disabled="settings.lyric.landscapeLyricPaddingX <= LANDSCAPE_LYRIC_PADDING_MIN"
                @click="adjustLandscapeLyricPadding(-LANDSCAPE_LYRIC_PADDING_STEP)"
              >
                <template #icon><IconLucideMinus :size="14" /></template>
              </SButton>
              <span class="qa-stepper-value">{{ settings.lyric.landscapeLyricPaddingX }}</span>
              <SButton
                :type="popoverCover ? 'cover' : 'default'"
                variant="ghost"
                circle
                size="small"
                :disabled="settings.lyric.landscapeLyricPaddingX >= LANDSCAPE_LYRIC_PADDING_MAX"
                @click="adjustLandscapeLyricPadding(LANDSCAPE_LYRIC_PADDING_STEP)"
              >
                <template #icon><IconLucidePlus :size="14" /></template>
              </SButton>
            </div>
          </div>
        </div>
      </div>

      <div class="qa-divider" />

      <!-- AMLL 效果组 -->
      <div class="qa-group">
        <div class="qa-group-title">{{ t("quickToggle.group.amll") }}</div>

        <div class="qa-row qa-row--column">
          <div class="qa-row-head qa-row-head--stack">
            <div class="qa-segmented qa-segmented--full">
              <SButton
                :type="popoverCover ? 'cover' : 'default'"
                :variant="renderMode === 'default' ? 'tertiary' : 'ghost'"
                size="small"
                round
                @click="setRenderMode('default')"
              >
                {{ t("quickToggle.renderModeDefault") }}
              </SButton>
              <SButton
                :type="popoverCover ? 'cover' : 'default'"
                :variant="renderMode === 'amll' ? 'tertiary' : 'ghost'"
                size="small"
                round
                @click="setRenderMode('amll')"
              >
                {{ t("quickToggle.renderModeAmll") }}
              </SButton>
              <SButton
                v-if="isAndroid"
                :type="popoverCover ? 'cover' : 'default'"
                :variant="renderMode === 'kotlin' ? 'tertiary' : 'ghost'"
                size="small"
                round
                @click="setRenderMode('kotlin')"
              >
                {{ t("quickToggle.renderModeKotlin") }}
              </SButton>
            </div>
          </div>
          <div v-if="renderMode === 'amll'" class="qa-hint">
            {{ t("quickToggle.amllEngineWarning") }}
          </div>
        </div>

        <div class="qa-row">
          <div class="qa-row-label">
            <IconLucideWaves :size="18" class="qa-row-icon" />
            <span>{{ t("quickToggle.amllSpring") }}</span>
          </div>
          <SSwitch
            v-model="amllSpring"
            class="qa-switch"
            :round="false"
            :disabled="amllSpringDisabled"
          />
        </div>

        <div class="qa-row">
          <div class="qa-row-label">
            <IconLucideImage :size="18" class="qa-row-icon" />
            <span>{{ t("quickToggle.amllDynamicBg") }}</span>
          </div>
          <SSwitch v-model="amllDynamicBg" class="qa-switch" :round="false" />
        </div>

        <div class="qa-row">
          <div class="qa-row-label">
            <IconLucideEyeOff :size="18" class="qa-row-icon" />
            <span>{{ t("quickToggle.hidePassed") }}</span>
          </div>
          <SSwitch v-model="hidePassed" class="qa-switch" :round="false" />
        </div>

        <div class="qa-row">
          <div class="qa-row-label">
            <IconLucideDroplet :size="18" class="qa-row-icon" />
            <span>{{ t("quickToggle.lyricBlur") }}</span>
          </div>
          <SSwitch v-model="lyricBlur" class="qa-switch" :round="false" />
        </div>
      </div>
    </div>
  </SPopover>
</template>

<style scoped>
.qa-panel {
  width: 236px;
  padding: 4px 0;
  display: flex;
  flex-direction: column;
  gap: 0;
  font-size: 13px;
  line-height: 1.4;
  max-height: min(calc(var(--page-zoom-100vh, 100vh) * 0.48), 360px);
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: none; /* Firefox */
  -ms-overflow-style: none; /* IE/Edge */
}

.qa-panel::-webkit-scrollbar {
  display: none; /* Chrome/Safari/Opera */
}

.qa-panel::-webkit-scrollbar-thumb {
  border-radius: 2px;
  background-color: rgb(128 128 128 / 0.3);
}

.qa-panel::-webkit-scrollbar-track {
  background-color: transparent;
}

.qa-group {
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0;
}

.qa-group-title {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  opacity: 0.5;
  padding: 4px 8px 2px;
}

.qa-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  height: 34px;
  padding: 0 8px;
  border-radius: 6px;
  transition: background-color 0.15s;
}

.qa-row > .qa-switch {
  flex-shrink: 0;
  margin-right: -4px;
  transform: scale(0.82);
  transform-origin: right center;
}

.qa-row--column {
  flex-direction: column;
  align-items: stretch;
  gap: 4px;
  height: auto;
  padding: 6px 8px 8px;
}

.qa-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.qa-row-head--stack {
  align-items: stretch;
  flex-direction: column;
  gap: 6px;
}

.qa-row-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  min-width: 0;
}

.qa-row-label span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  flex-shrink: 1;
}

.qa-row-icon {
  opacity: 0.7;
  flex-shrink: 0;
}

.qa-row-value {
  font-size: 11px;
  opacity: 0.6;
  font-variant-numeric: tabular-nums;
  min-width: 32px;
  text-align: right;
}

.qa-stepper {
  display: flex;
  align-items: center;
  gap: 6px;
}

.qa-stepper-value {
  min-width: 28px;
  text-align: center;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.qa-segmented {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  align-self: flex-end;
}

.qa-segmented--full {
  align-self: stretch;
  display: flex;
  justify-content: stretch;
}

.qa-segmented--full > :deep(*) {
  flex: 1 1 0;
  min-width: 0;
}

.qa-sub-options {
  display: flex;
  gap: 4px;
  padding: 0 8px 2px 32px;
}

.qa-sub-btn {
  flex: 1 1 0;
  min-width: 0;
}

.qa-hint {
  font-size: 11px;
  line-height: 1.5;
  opacity: 0.55;
  padding: 0 8px 4px 32px;
}

.qa-divider {
  height: 1px;
  margin: 4px 0;
  opacity: 0.12;
  background: currentColor;
}

/* 封面主题配色 */
.qa-panel--cover {
  color: rgb(255 255 255 / 0.9);
}

.qa-panel--cover .qa-row:hover {
  background: rgb(255 255 255 / 0.06);
}

.qa-panel--cover .qa-group-title {
  color: rgb(255 255 255 / 0.5);
}

/* 普通主题配色 */
.qa-panel--plain {
  color: var(--s-on-surface, currentColor);
}

.qa-panel--plain .qa-row:hover {
  background: var(--s-surface-variant / 0.3, rgb(0 0 0 / 0.04));
}

.qa-panel--plain .qa-group-title {
  color: var(--s-on-surface-variant, currentColor);
}

/* 安卓触摸设备移除 hover 背景变化 */
:global(html.is-android) .qa-row:hover {
  background: transparent;
}
</style>
