import type { SettingCategory } from "@/types/settings-schema";
import { useSettingsStore } from "@/stores/settings";
import DeviceSelector from "@/components/settings/custom/DeviceSelector.vue";
import IconLucidePlay from "~icons/lucide/play";

/** 当前是否为流体背景 */
const isAnimationBg = () => useSettingsStore().player.playerBgType === "animation";

const playerCategory: SettingCategory = {
  id: "player",
  icon: IconLucidePlay,
  sections: [
    {
      id: "playControl",
      items: [
        {
          key: "autoPlay",
          type: "switch",
          binding: { store: "settings", path: "system.player.autoPlay" },
          defaultValue: true,
        },
        {
          key: "rememberLastTrack",
          type: "switch",
          binding: { store: "settings", path: "system.player.rememberLastTrack" },
          defaultValue: true,
        },
        {
          key: "searchPlayBehavior",
          type: "select",
          binding: { store: "settings", path: "player.searchPlayBehavior" },
          options: [
            { value: "current", labelKey: "settings.searchPlayBehavior.current" },
            { value: "all", labelKey: "settings.searchPlayBehavior.all" },
          ],
          defaultValue: "current",
        },
        {
          key: "fadeEnabled",
          type: "switch",
          platform: "desktop",
          binding: { store: "settings", path: "system.player.fadeEnabled" },
          defaultValue: true,
          children: [
            {
              key: "fadeDuration",
              type: "slider",
              binding: { store: "settings", path: "system.player.fadeDuration" },
              min: 100,
              max: 600,
              step: 100,
              defaultValue: 200,
              marks: { 100: "100", 200: "200", 600: "600" },
            },
          ],
        },
        {
          key: "loudnessNormalization",
          type: "switch",
          binding: { store: "settings", path: "system.player.loudnessNormalization" },
          defaultValue: false,
          tag: { text: "Beta" },
        },
      ],
    },
    {
      id: "audioSource",
      items: [
        {
          key: "pauseOnDeviceSwitch",
          type: "switch",
          binding: { store: "settings", path: "player.pauseOnDeviceSwitch" },
          defaultValue: false,
        },
        {
          key: "songLevel",
          type: "select",
          binding: { store: "settings", path: "player.songLevel" },
          options: [
            { value: "lq", labelKey: "settings.songLevel.lq" },
            { value: "sq", labelKey: "settings.songLevel.sq" },
            { value: "hq", labelKey: "settings.songLevel.hq" },
            { value: "lossless", labelKey: "settings.songLevel.lossless" },
            { value: "hi-res", labelKey: "settings.songLevel.hi-res" },
          ],
          defaultValue: "hq",
        },
        {
          key: "allowTrialPlay",
          type: "switch",
          binding: { store: "settings", path: "player.allowTrialPlay" },
          defaultValue: false,
        },
        {
          key: "preloadNextTrack",
          type: "switch",
          binding: { store: "settings", path: "player.preloadNextTrack" },
          defaultValue: false,
        },
      ],
    },
    {
      id: "scrobble",
      tag: { text: "Beta" },
      items: [
        {
          key: "neteaseScrobbleEnabled",
          type: "switch",
          binding: { store: "settings", path: "system.system.neteaseScrobbleEnabled" },
          defaultValue: false,
          visible: () => false,
          children: [
            {
              key: "neteaseScrobbleMode",
              type: "select",
              binding: { store: "settings", path: "system.system.neteaseScrobbleMode" },
              options: [
                { value: "legacy", labelKey: "settings.neteaseScrobbleMode.legacy" },
                { value: "ncbl", labelKey: "settings.neteaseScrobbleMode.ncbl" },
              ],
              defaultValue: "ncbl",
            },
          ],
        },
      ],
    },
    {
      id: "playback",
      items: [
        {
          key: "playerBgType",
          type: "select",
          binding: { store: "settings", path: "player.playerBgType" },
          options: [
            { value: "blur", labelKey: "settings.playerBgType.blur" },
            { value: "solid", labelKey: "settings.playerBgType.solid" },
            { value: "animation", labelKey: "settings.playerBgType.animation" },
          ],
          defaultValue: "blur",
          confirm: {
            when: (next) => next === "animation",
            titleKey: "settings.confirm.highResourceTitle",
            contentKey: "settings.confirm.highResourceContent",
            type: "warning",
          },
          childrenCondition: isAnimationBg,
          hideChildren: true,
          children: [
            {
              key: "playerBgFlowSpeed",
              type: "slider",
              binding: { store: "settings", path: "player.playerBgFlowSpeed" },
              min: 0.1,
              max: 10,
              step: 0.1,
              defaultValue: 1,
              marks: { 0.1: "0.1", 1: "1", 10: "10" },
            },
            {
              key: "playerBgRenderScale",
              type: "slider",
              binding: { store: "settings", path: "player.playerBgRenderScale" },
              min: 0.5,
              max: 2,
              step: 0.1,
              defaultValue: 0.5,
              marks: { 0.5: "0.5", 1: "1", 2: "2" },
            },
            {
              key: "playerBgFps",
              type: "slider",
              binding: { store: "settings", path: "player.playerBgFps" },
              min: 24,
              max: 120,
              step: 2,
              defaultValue: 45,
              marks: { 24: "24", 45: "45", 120: "120" },
            },
            {
              key: "playerBgFreezeOnPause",
              type: "switch",
              binding: { store: "settings", path: "player.playerBgFreezeOnPause" },
              defaultValue: true,
            },
            {
              key: "playerBgBeat",
              type: "switch",
              binding: { store: "settings", path: "player.playerBgBeat" },
              defaultValue: true,
            },
          ],
        },
        {
          key: "coverLayout",
          type: "select",
          binding: { store: "settings", path: "player.coverLayout" },
          options: [
            { value: "default", labelKey: "settings.coverLayout.default" },
            { value: "fullscreen", labelKey: "settings.coverLayout.fullscreen" },
          ],
          defaultValue: "default",
        },
        {
          key: "autoCenterCover",
          type: "switch",
          binding: { store: "settings", path: "player.autoCenterCover" },
          defaultValue: true,
        },
        {
          key: "showPlaybackSource",
          type: "switch",
          binding: { store: "settings", path: "player.showPlaybackSource" },
          defaultValue: false,
        },
        {
          key: "followCoverColor",
          type: "switch",
          binding: { store: "settings", path: "player.followCoverColor" },
          defaultValue: true,
        },
        {
          key: "timeFormat",
          type: "select",
          binding: { store: "settings", path: "player.timeFormat" },
          options: [
            { value: "current-total", labelKey: "settings.timeFormat.currentTotal" },
            { value: "remaining-total", labelKey: "settings.timeFormat.remainingTotal" },
            { value: "current-remaining", labelKey: "settings.timeFormat.currentRemaining" },
          ],
          defaultValue: "current-total",
          descriptionKey: "settings.timeFormat.description",
        },
        {
          key: "autoImmersive",
          type: "switch",
          binding: { store: "settings", path: "player.autoImmersive" },
          defaultValue: false,
        },
        {
          key: "showProgressTooltip",
          type: "switch",
          binding: { store: "settings", path: "player.showProgressTooltip" },
          defaultValue: true,
          children: [
            {
              key: "showProgressLyric",
              type: "switch",
              binding: { store: "settings", path: "player.showProgressLyric" },
              defaultValue: false,
            },
          ],
        },
        {
          key: "snapToLyric",
          type: "switch",
          binding: { store: "settings", path: "player.snapToLyric" },
          defaultValue: false,
        },
      ],
    },
    {
      id: "musicSpectrum",
      tag: { text: "Beta" },
      items: [
        {
          key: "enableSpectrum",
          type: "switch",
          binding: { store: "settings", path: "player.enableSpectrum" },
          defaultValue: false,
          children: [
            {
              key: "spectrumAlgorithm",
              type: "select",
              platform: "android",
              binding: { store: "settings", path: "player.spectrumAlgorithm" },
              defaultValue: "pc",
              options: [
                { label: "PC 对齐", value: "pc" },
                { label: "Android 原生", value: "android" },
              ],
            },
            {
              key: "spectrumBarWidth",
              type: "slider",
              binding: { store: "settings", path: "player.spectrumBarWidth" },
              min: 1,
              max: 12,
              step: 1,
              defaultValue: 4,
              marks: { 1: "1", 4: "4", 8: "8", 12: "12" },
            },
            {
              key: "reverseSpectrum",
              type: "switch",
              binding: { store: "settings", path: "player.reverseSpectrum" },
              defaultValue: false,
            },
          ],
        },
      ],
    },

    {
      id: "device",
      platform: "desktop",
      items: [
        {
          key: "outputDevice",
          type: "custom",
          component: DeviceSelector,
        },
        {
          key: "pauseOnDeviceSwitch",
          type: "switch",
          binding: { store: "settings", path: "player.pauseOnDeviceSwitch" },
          defaultValue: false,
          action: (enabled) =>
            window.api.player.setPauseOnDeviceSwitch(Boolean(enabled)).then(() => {}),
        },
      ],
    },
    {
      id: "androidSystem",
      platform: "android",
      items: [
        {
          key: "androidShowStatusBar",
          type: "switch",
          binding: { store: "settings", path: "androidShowStatusBar" },
          defaultValue: false,
        },
        {
          key: "androidHidePortraitNavBar",
          type: "switch",
          binding: { store: "settings", path: "androidHidePortraitNavBar" },
          defaultValue: true,
        },
        {
          key: "androidMediaControllerEnabled",
          type: "switch",
          binding: { store: "settings", path: "androidMediaControllerEnabled" },
          defaultValue: true,
        },
        {
          key: "androidAllowMixWithOthers",
          type: "switch",
          binding: { store: "settings", path: "androidAllowMixWithOthers" },
          defaultValue: false,
        },
      ],
    },
  ],
};

export default playerCategory;
