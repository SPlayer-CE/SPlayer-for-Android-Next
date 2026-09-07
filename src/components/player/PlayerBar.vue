<script setup lang="ts">
import { useStatusStore } from "@/stores/status";
import { useSettingsStore } from "@/stores/settings";
import { useMediaStore } from "@/stores/media";
import { useFavorite } from "@/composables/useFavorite";
import { usePlaylistPicker } from "@/composables/usePlaylistPicker";
import { useTrackMenu } from "@/composables/useTrackMenu";
import { useDownload } from "@/composables/useDownload";
import { useProgressLyric } from "@/composables/useProgressLyric";
import * as player from "@/core/player";
import { formatTime } from "@/utils/time";
import { useResponsiveLayout } from "@/composables/useResponsiveLayout";
import MobilePlayerBar from "./MobilePlayerBar.vue";
import WavySeekBar from "@/components/ui/WavySeekBar.vue";
import IconFavorite from "~icons/material-symbols/favorite-rounded";
import IconFavoriteOutline from "~icons/material-symbols/favorite-outline-rounded";
import IconLucideMoreHorizontal from "~icons/lucide/more-horizontal";

const status = useStatusStore();
const settings = useSettingsStore();
const media = useMediaStore();
const fav = useFavorite();
const { useMobileLayout } = useResponsiveLayout();
const { position, duration, isPlaying } = storeToRefs(status);
const { formatTooltip, snapToNearestLyric } = useProgressLyric();

/** 是否是浮动模式 */
const isFloating = computed(() => settings.appearance.layoutMode === "floating");
/** 是否显示进度条提示 */
const showTooltip = computed(() => settings.player.showProgressTooltip);

const onSeekDragEnd = (value: number): void => {
  const snappedValue = snapToNearestLyric(value);
  player.seek(snappedValue);
};

/** 添加到歌单 */
const {
  open: pickerOpen,
  tracks: pickerTracks,
  mode: pickerMode,
  openPicker,
} = usePlaylistPicker();

/** 歌曲菜单 */
const { enqueue: enqueueDownload } = useDownload();
const { items: menuItems, handleSelect: onMenuSelect } = useTrackMenu(toRef(media, "track"), {
  hidePlayActions: true,
  onAddToPlaylist: (track) => openPicker([track]),
  onDownload: (track, quality) => void enqueueDownload(track, { quality }),
});
</script>

<template>
  <!-- 手机版专属浮动播放条 -->
  <template v-if="useMobileLayout">
    <MobilePlayerBar />
  </template>

  <template v-else>
    <!-- 浮动模式 -->
    <div v-if="isFloating" class="relative flex items-center px-4 gap-4 min-w-0">
      <PlayerControls compact />
      <div class="flex flex-col flex-1 min-w-0 gap-1 pt-2 pb-1">
        <div class="flex items-center gap-2 min-w-0">
          <TrackInfo compact class="flex-1">
            <template #title-trailing>
              <div class="flex items-center shrink-0">
                <SButton
                  class="-my-1"
                  type="primary"
                  variant="text"
                  circle
                  :size="24"
                  :icon-size="16"
                  @click="fav.toggle(media.track)"
                >
                  <template #icon>
                    <IconFavorite v-if="fav.isLiked(media.track)" />
                    <IconFavoriteOutline v-else />
                  </template>
                </SButton>
                <SDropdownMenu
                  v-if="media.track"
                  :items="menuItems"
                  side="top"
                  align="start"
                  @select="onMenuSelect"
                >
                  <template #trigger>
                    <SButton
                      class="-my-1"
                      type="primary"
                      variant="text"
                      circle
                      :size="24"
                      :icon-size="16"
                    >
                      <template #icon><IconLucideMoreHorizontal /></template>
                    </SButton>
                  </template>
                </SDropdownMenu>
              </div>
            </template>
          </TrackInfo>
          <span class="text-xs text-on-surface-variant/70 tabular-nums shrink-0">
            {{ formatTime(position) }} / {{ formatTime(duration) }}
          </span>
        </div>
        <WavySeekBar
          v-if="settings.appearance.wavyProgressBar"
          :model-value="position"
          :min="0"
          :max="duration"
          :step="100"
          :height="10"
          :stroke-width="3"
          :wavelength="20"
          :amplitude="3"
          :playing="isPlaying"
          active-color="rgb(var(--s-primary))"
          inactive-color="rgb(var(--s-on-surface) / 0.12)"
          thumb-color="rgb(var(--s-primary))"
          class="w-full"
          @drag-end="onSeekDragEnd"
        />
        <SSlider
          v-else
          :model-value="position"
          :min="0"
          :max="duration"
          :step="100"
          :track-height="3"
          :thumb-size="10"
          :always-show-thumb="false"
          class="w-full"
          @drag-end="onSeekDragEnd"
        />
      </div>
      <div class="shrink-0">
        <Toolbar />
      </div>
    </div>
    <!-- 默认模式 -->
    <div v-else class="relative h-full">
      <div class="absolute left-0 right-0 top-0 -translate-y-1/2 z-10">
        <WavySeekBar
          v-if="settings.appearance.wavyProgressBar"
          :model-value="position"
          :min="0"
          :max="duration"
          :step="100"
          :height="12"
          :stroke-width="3"
          :wavelength="20"
          :amplitude="3"
          :playing="isPlaying"
          active-color="rgb(var(--s-primary))"
          inactive-color="rgb(var(--s-on-surface) / 0.12)"
          thumb-color="rgb(var(--s-primary))"
          @drag-end="onSeekDragEnd"
        />
        <SSlider
          v-else
          :model-value="position"
          :min="0"
          :max="duration"
          :step="100"
          :track-height="3"
          :thumb-size="12"
          :always-show-thumb="false"
          :show-popover="showTooltip"
          @drag-end="onSeekDragEnd"
        >
          <template #popover="{ value }">{{ formatTooltip(value) }}</template>
        </SSlider>
      </div>
      <div class="grid grid-cols-[1fr_auto_1fr] items-center h-full px-3 gap-3">
        <TrackInfo>
          <template #title-trailing>
            <div class="flex items-center shrink-0">
              <SButton
                class="-my-1"
                type="primary"
                variant="text"
                circle
                :size="28"
                :icon-size="18"
                @click="fav.toggle(media.track)"
              >
                <template #icon>
                  <SIconSwap :active="fav.isLiked(media.track)">
                    <template #on><IconFavorite /></template>
                    <template #off><IconFavoriteOutline /></template>
                  </SIconSwap>
                </template>
              </SButton>
              <SDropdownMenu
                v-if="media.track"
                :items="menuItems"
                side="top"
                align="start"
                @select="onMenuSelect"
              >
                <template #trigger>
                  <SButton
                    class="-my-1"
                    type="primary"
                    variant="text"
                    circle
                    :size="28"
                    :icon-size="18"
                  >
                    <template #icon><IconLucideMoreHorizontal /></template>
                  </SButton>
                </template>
              </SDropdownMenu>
            </div>
          </template>
        </TrackInfo>
        <PlayerControls class="mx-15" />
        <div class="flex items-center justify-end gap-2 min-w-0">
          <PlayerTimeInfo />
          <Toolbar />
        </div>
      </div>
    </div>
  </template>
  <PlaylistPickerDialog v-model:open="pickerOpen" :mode="pickerMode" :tracks="pickerTracks" />
</template>
