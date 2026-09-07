import type { SettingItem, SettingSection, SettingCategory } from "@/types/settings-schema";
import { isAndroid } from "@/services/bridge";
import { useSettingsStore } from "@/stores/settings";
import { isMac } from "@/utils/config";
import IconLucideMonitor from "~icons/lucide/monitor";

/** 为 Android 上不可用的设置项添加禁用和提示 */
const androidDisabledItems = (items: SettingItem[]): SettingItem[] =>
  isAndroid
    ? items.map((item) => ({
        ...item,
        disabled: () => true,
        descriptionKey: "settings.desktopOnly",
      }))
    : items;

const desktopLyricSection: SettingSection = {
  id: "desktopLyric",
  tag: { text: "Beta" },
  platform: "desktop",
  items: androidDisabledItems([
    {
      key: "desktopLyricEnabled",
      type: "switch",
      binding: { store: "settings", path: "isDesktopLyricOpen" },
      defaultValue: false,
    },
    {
      key: "desktopLyricFontSize",
      type: "slider",
      binding: { store: "settings", path: "system.desktopLyric.fontSize" },
      renderAsNumberWhen: () => useSettingsStore().externalLyricManualInput,
      defaultValue: 24,
      min: 20,
      max: 96,
      step: 1,
      marks: { 20: "20", 58: "58", 96: "96" },
    },
    {
      key: "desktopLyricFontWeight",
      type: "slider",
      binding: { store: "settings", path: "system.desktopLyric.fontWeight" },
      renderAsNumberWhen: () => useSettingsStore().externalLyricManualInput,
      min: 100,
      max: 900,
      step: 100,
      defaultValue: 600,
      marks: { 100: "100", 400: "400", 700: "700", 900: "900" },
    },
    {
      key: "desktopLyricDoubleLine",
      type: "switch",
      binding: { store: "settings", path: "system.desktopLyric.doubleLine" },
      defaultValue: true,
    },
    {
      key: "desktopLyricAlign",
      type: "select",
      binding: { store: "settings", path: "system.desktopLyric.align" },
      options: [
        { value: "left", labelKey: "settings.desktopLyricAlign.left" },
        { value: "center", labelKey: "settings.desktopLyricAlign.center" },
        { value: "right", labelKey: "settings.desktopLyricAlign.right" },
        { value: "justify", labelKey: "settings.desktopLyricAlign.justify" },
      ],
      defaultValue: "center",
    },
    {
      key: "desktopLyricWordByWord",
      type: "switch",
      binding: { store: "settings", path: "system.desktopLyric.wordByWord" },
      defaultValue: true,
    },
    {
      key: "desktopLyricAutoGenerateWordByWord",
      type: "switch",
      binding: { store: "settings", path: "system.desktopLyric.autoGenerateWordByWord" },
      defaultValue: true,
    },
    {
      key: "desktopLyricShowTranslation",
      type: "switch",
      binding: { store: "settings", path: "system.desktopLyric.showTranslation" },
      defaultValue: true,
    },
    {
      key: "desktopLyricPlayedColor",
      type: "color",
      binding: { store: "settings", path: "system.desktopLyric.playedColor" },
      defaultValue: "#ffffff",
      showAlpha: false,
    },
    {
      key: "desktopLyricUnplayedColor",
      type: "color",
      binding: { store: "settings", path: "system.desktopLyric.unplayedColor" },
      defaultValue: "#7d7d7d",
      showAlpha: false,
    },
    {
      key: "desktopLyricStrokeColor",
      type: "color",
      binding: { store: "settings", path: "system.desktopLyric.strokeColor" },
      defaultValue: "rgba(0, 0, 0, 0.5)",
    },
    {
      key: "desktopLyricBackgroundMask",
      type: "switch",
      binding: { store: "settings", path: "system.desktopLyric.backgroundMask" },
      defaultValue: false,
      children: [
        {
          key: "desktopLyricBackgroundMaskColor",
          type: "color",
          binding: { store: "settings", path: "system.desktopLyric.backgroundMaskColor" },
          defaultValue: "rgba(0, 0, 0, 0.3)",
        },
      ],
    },
    {
      key: "desktopLyricAlwaysShowSongInfo",
      type: "switch",
      binding: { store: "settings", path: "system.desktopLyric.alwaysShowSongInfo" },
      defaultValue: false,
    },
    {
      key: "desktopLyricAnimation",
      type: "switch",
      binding: { store: "settings", path: "system.desktopLyric.animation" },
      defaultValue: true,
    },
    {
      key: "desktopLyricLimitBounds",
      type: "switch",
      binding: { store: "settings", path: "system.desktopLyric.limitBounds" },
      defaultValue: false,
    },
    {
      key: "desktopLyricAlwaysOnTop",
      type: "switch",
      binding: { store: "settings", path: "system.desktopLyric.alwaysOnTop" },
      defaultValue: true,
    },
    {
      key: "desktopLyricLocked",
      type: "switch",
      binding: { store: "settings", path: "system.desktopLyric.locked" },
      defaultValue: false,
    },
  ]),
};

const dynamicIslandSection: SettingSection = {
  id: "dynamicIsland",
  tag: { text: "Beta" },
  platform: "desktop",
  items: androidDisabledItems([
    {
      key: "dynamicIslandEnabled",
      type: "switch",
      binding: { store: "settings", path: "isDynamicIslandOpen" },
      defaultValue: false,
    },
    {
      key: "dynamicIslandHeight",
      type: "slider",
      binding: { store: "settings", path: "system.dynamicIsland.height" },
      renderAsNumberWhen: () => useSettingsStore().externalLyricManualInput,
      min: 32,
      max: 96,
      step: 1,
      defaultValue: 40,
      marks: { 32: "32", 40: "40", 64: "64", 96: "96" },
    },
    {
      key: "dynamicIslandFontWeight",
      type: "slider",
      binding: { store: "settings", path: "system.dynamicIsland.fontWeight" },
      renderAsNumberWhen: () => useSettingsStore().externalLyricManualInput,
      min: 100,
      max: 900,
      step: 100,
      defaultValue: 500,
      marks: { 100: "100", 500: "500", 900: "900" },
    },
    {
      key: "dynamicIslandWordByWord",
      type: "switch",
      binding: { store: "settings", path: "system.dynamicIsland.wordByWord" },
      defaultValue: true,
    },
    {
      key: "dynamicIslandTransition",
      type: "select",
      binding: { store: "settings", path: "system.dynamicIsland.transition" },
      options: [
        { value: "bounce", labelKey: "settings.dynamicIslandTransition.bounce" },
        { value: "smooth", labelKey: "settings.dynamicIslandTransition.smooth" },
      ],
      defaultValue: "bounce",
    },
    {
      key: "dynamicIslandDoubleLine",
      type: "switch",
      binding: { store: "settings", path: "system.dynamicIsland.doubleLine" },
      defaultValue: false,
    },
    {
      key: "dynamicIslandShowTranslation",
      type: "switch",
      binding: { store: "settings", path: "system.dynamicIsland.showTranslation" },
      defaultValue: false,
    },
    {
      key: "dynamicIslandPlayedColor",
      type: "color",
      binding: { store: "settings", path: "system.dynamicIsland.playedColor" },
      defaultValue: "rgb(23, 113, 191)",
      showAlpha: false,
    },
    {
      key: "dynamicIslandUnplayedColor",
      type: "color",
      binding: { store: "settings", path: "system.dynamicIsland.unplayedColor" },
      defaultValue: "rgba(171, 171, 171, 0.5)",
    },
    {
      key: "dynamicIslandBackgroundColor",
      type: "color",
      binding: { store: "settings", path: "system.dynamicIsland.backgroundColor" },
      defaultValue: "rgba(0, 0, 0, 1)",
    },
    {
      key: "dynamicIslandAlwaysOnTop",
      type: "switch",
      binding: { store: "settings", path: "system.dynamicIsland.alwaysOnTop" },
      defaultValue: true,
    },
    ...(isMac
      ? [
          {
            key: "dynamicIslandNotchFusion",
            type: "switch" as const,
            binding: { store: "settings" as const, path: "system.dynamicIsland.notchFusion" },
            defaultValue: false,
          },
        ]
      : []),
    {
      key: "dynamicIslandSnapCentered",
      type: "switch",
      binding: { store: "settings", path: "system.dynamicIsland.snapCentered" },
      defaultValue: true,
      disabled: () => useSettingsStore().system.dynamicIsland.notchFusion,
    },
    {
      key: "dynamicIslandNonOcclusive",
      type: "switch",
      binding: { store: "settings", path: "system.dynamicIsland.nonOcclusive" },
      defaultValue: false,
    },
  ]),
};

/**
 * Android 灵动岛歌词 section
 * 由原生 DynamicIslandService 实现药丸形悬浮窗，配置项绑定到 system.dynamicIsland.* 路径
 * 由原生层读取并应用，不依赖 Electron 窗口
 */
const androidDynamicIslandSection: SettingSection = {
  id: "dynamicIsland",
  tag: { text: "Beta" },
  platform: "android",
  items: [
    {
      key: "dynamicIslandEnabled",
      type: "switch",
      binding: { store: "settings", path: "isDynamicIslandOpen" },
      defaultValue: false,
    },
    {
      key: "dynamicIslandHeight",
      type: "slider",
      binding: { store: "settings", path: "system.dynamicIsland.height" },
      renderAsNumberWhen: () => useSettingsStore().externalLyricManualInput,
      min: 32,
      max: 96,
      step: 1,
      defaultValue: 40,
      marks: { 32: "32", 40: "40", 64: "64", 96: "96" },
    },
    {
      key: "dynamicIslandFontSize",
      type: "slider",
      binding: { store: "settings", path: "system.dynamicIsland.fontSize" },
      renderAsNumberWhen: () => useSettingsStore().externalLyricManualInput,
      defaultValue: 24,
      min: 20,
      max: 96,
      step: 1,
      marks: { 20: "20", 58: "58", 96: "96" },
    },
    {
      key: "dynamicIslandFontWeight",
      type: "slider",
      binding: { store: "settings", path: "system.dynamicIsland.fontWeight" },
      renderAsNumberWhen: () => useSettingsStore().externalLyricManualInput,
      min: 100,
      max: 900,
      step: 100,
      defaultValue: 500,
      marks: { 100: "100", 500: "500", 900: "900" },
    },
    {
      key: "dynamicIslandWordByWord",
      type: "switch",
      binding: { store: "settings", path: "system.dynamicIsland.wordByWord" },
      defaultValue: true,
    },
    {
      key: "dynamicIslandAutoGenerateWordByWord",
      type: "switch",
      binding: { store: "settings", path: "system.dynamicIsland.autoGenerateWordByWord" },
      defaultValue: true,
    },
    {
      key: "dynamicIslandDoubleLine",
      type: "switch",
      binding: { store: "settings", path: "system.dynamicIsland.doubleLine" },
      defaultValue: false,
    },
    {
      key: "dynamicIslandShowTranslation",
      type: "switch",
      binding: { store: "settings", path: "system.dynamicIsland.showTranslation" },
      defaultValue: false,
    },
    {
      key: "dynamicIslandPlayedColor",
      type: "color",
      binding: { store: "settings", path: "system.dynamicIsland.playedColor" },
      defaultValue: "rgb(23, 113, 191)",
      showAlpha: false,
    },
    {
      key: "dynamicIslandUnplayedColor",
      type: "color",
      binding: { store: "settings", path: "system.dynamicIsland.unplayedColor" },
      defaultValue: "rgba(171, 171, 171, 0.5)",
    },
    {
      key: "dynamicIslandStrokeColor",
      type: "color",
      binding: { store: "settings", path: "system.dynamicIsland.strokeColor" },
      defaultValue: "rgba(0, 0, 0, 0.5)",
    },
    {
      key: "dynamicIslandBackgroundMask",
      type: "switch",
      binding: { store: "settings", path: "system.dynamicIsland.backgroundMask" },
      defaultValue: false,
      children: [
        {
          key: "dynamicIslandBackgroundMaskColor",
          type: "color",
          binding: { store: "settings", path: "system.dynamicIsland.backgroundMaskColor" },
          defaultValue: "rgba(0, 0, 0, 0.3)",
        },
      ],
    },
    {
      key: "dynamicIslandBackgroundColor",
      type: "color",
      binding: { store: "settings", path: "system.dynamicIsland.backgroundColor" },
      defaultValue: "rgba(0, 0, 0, 1)",
    },
    {
      key: "dynamicIslandAlign",
      type: "select",
      binding: { store: "settings", path: "system.dynamicIsland.align" },
      options: [
        { value: "left", labelKey: "settings.dynamicIslandAlign.left" },
        { value: "center", labelKey: "settings.dynamicIslandAlign.center" },
        { value: "right", labelKey: "settings.dynamicIslandAlign.right" },
        { value: "justify", labelKey: "settings.dynamicIslandAlign.justify" },
      ],
      defaultValue: "center",
    },
    {
      key: "dynamicIslandAnimation",
      type: "switch",
      binding: { store: "settings", path: "system.dynamicIsland.animation" },
      defaultValue: true,
    },
    {
      key: "dynamicIslandAlwaysShowSongInfo",
      type: "switch",
      binding: { store: "settings", path: "system.dynamicIsland.alwaysShowSongInfo" },
      defaultValue: false,
    },
    {
      key: "dynamicIslandAlwaysOnTop",
      type: "switch",
      binding: { store: "settings", path: "system.dynamicIsland.alwaysOnTop" },
      defaultValue: true,
    },
    {
      key: "dynamicIslandLocked",
      type: "switch",
      binding: { store: "settings", path: "system.dynamicIsland.locked" },
      defaultValue: false,
    },
    {
      key: "dynamicIslandDragByLongPress",
      type: "switch",
      binding: { store: "settings", path: "system.dynamicIsland.dragByLongPress" },
      defaultValue: true,
    },
    {
      key: "dynamicIslandLimitBounds",
      type: "switch",
      binding: { store: "settings", path: "system.dynamicIsland.limitBounds" },
      defaultValue: false,
    },
    {
      key: "dynamicIslandPosX",
      type: "slider",
      binding: { store: "settings", path: "system.dynamicIsland.posX" },
      renderAsNumberWhen: () => useSettingsStore().externalLyricManualInput,
      min: 0,
      max: 4000,
      step: 10,
      defaultValue: 0,
      marks: { 0: "0", 2000: "2000", 4000: "4000" },
    },
    {
      key: "dynamicIslandPosY",
      type: "slider",
      binding: { store: "settings", path: "system.dynamicIsland.posY" },
      renderAsNumberWhen: () => useSettingsStore().externalLyricManualInput,
      min: -200,
      max: 4000,
      step: 10,
      defaultValue: 0,
      marks: { "-200": "-200", 0: "0", 2000: "2000", 4000: "4000" },
    },
    {
      key: "dynamicIslandMaxWidth",
      type: "slider",
      binding: { store: "settings", path: "system.dynamicIsland.maxWidth" },
      renderAsNumberWhen: () => useSettingsStore().externalLyricManualInput,
      min: 0,
      max: 2000,
      step: 10,
      defaultValue: 0,
      marks: { 0: "0", 1000: "1000", 2000: "2000" },
    },
  ],
};

/** Win 平台限定 */
const taskbarLyricSection: SettingSection = {
  id: "taskbarLyric",
  tag: { text: "Beta" },
  platform: "desktop",
  items: [
    {
      key: "taskbarLyricEnabled",
      type: "switch",
      binding: { store: "settings", path: "isTaskbarLyricOpen" },
      defaultValue: false,
    },
    {
      key: "taskbarLyricPosition",
      type: "select",
      binding: { store: "settings", path: "system.taskbarLyric.position" },
      options: [
        { value: "auto", labelKey: "settings.taskbarLyricPosition.auto" },
        { value: "left", labelKey: "settings.taskbarLyricPosition.left" },
        { value: "right", labelKey: "settings.taskbarLyricPosition.right" },
      ],
      defaultValue: "auto",
    },
    {
      key: "taskbarLyricAutoMaxWidth",
      type: "switch",
      binding: { store: "settings", path: "system.taskbarLyric.autoMaxWidth" },
      defaultValue: true,
      childrenCondition: () => true,
      children: [
        {
          key: "taskbarLyricAutoAdjustOccupiedSpace",
          type: "switch",
          binding: { store: "settings", path: "system.taskbarLyric.autoAdjustOccupiedSpace" },
          defaultValue: false,
          visible: () => useSettingsStore().system.taskbarLyric.autoMaxWidth === true,
          tag: { text: "Beta" },
        },
        {
          key: "taskbarLyricMaxWidth",
          type: "slider",
          binding: { store: "settings", path: "system.taskbarLyric.maxWidth" },
          visible: () => useSettingsStore().system.taskbarLyric.autoMaxWidth === false,
          min: 200,
          max: 800,
          step: 20,
          defaultValue: 400,
          marks: { 200: "200", 400: "400", 800: "800" },
        },
      ],
    },
    {
      key: "taskbarLyricLeftMargin",
      type: "number",
      binding: { store: "settings", path: "system.taskbarLyric.leftMargin" },
      min: 0,
      max: 500,
      defaultValue: 0,
    },
    {
      key: "taskbarLyricRightMargin",
      type: "number",
      binding: { store: "settings", path: "system.taskbarLyric.rightMargin" },
      min: 0,
      max: 500,
      defaultValue: 0,
    },
    {
      key: "taskbarLyricColorMode",
      type: "select",
      binding: { store: "settings", path: "system.taskbarLyric.colorMode" },
      options: [
        { value: "taskbar", labelKey: "settings.taskbarLyricColorMode.taskbar" },
        { value: "taskbarInverse", labelKey: "settings.taskbarLyricColorMode.taskbarInverse" },
        { value: "light", labelKey: "settings.taskbarLyricColorMode.light" },
        { value: "dark", labelKey: "settings.taskbarLyricColorMode.dark" },
      ],
      defaultValue: "taskbar",
    },
    {
      key: "taskbarLyricShowBackground",
      type: "switch",
      binding: { store: "settings", path: "system.taskbarLyric.showBackground" },
      defaultValue: false,
    },
    {
      key: "taskbarLyricFontSize",
      type: "slider",
      binding: { store: "settings", path: "system.taskbarLyric.fontSize" },
      renderAsNumberWhen: () => useSettingsStore().externalLyricManualInput,
      min: 12,
      max: 20,
      step: 1,
      defaultValue: 14,
      marks: { 12: "12", 14: "14", 17: "17", 20: "20" },
    },
    {
      key: "taskbarLyricFontWeight",
      type: "slider",
      binding: { store: "settings", path: "system.taskbarLyric.fontWeight" },
      min: 100,
      max: 900,
      step: 100,
      defaultValue: 400,
      marks: { 100: "100", 400: "400", 700: "700", 900: "900" },
    },
    {
      key: "taskbarLyricShowCover",
      type: "switch",
      binding: { store: "settings", path: "system.taskbarLyric.showCover" },
      defaultValue: true,
    },
    {
      key: "taskbarLyricWordByWord",
      type: "switch",
      binding: { store: "settings", path: "system.taskbarLyric.wordByWord" },
      defaultValue: true,
    },
    {
      key: "taskbarLyricDoubleLine",
      type: "switch",
      binding: { store: "settings", path: "system.taskbarLyric.doubleLine" },
      defaultValue: true,
    },
    {
      key: "taskbarLyricShowTranslation",
      type: "switch",
      binding: { store: "settings", path: "system.taskbarLyric.showTranslation" },
      defaultValue: true,
    },
  ],
};

const inputModeSection: SettingSection = {
  id: "externalLyricInputMode",
  items: [
    {
      key: "externalLyricManualInput",
      type: "switch",
      binding: { store: "settings", path: "externalLyricManualInput" },
      defaultValue: false,
    },
  ],
};

const externalLyricCategory: SettingCategory = {
  id: "externalLyric",
  icon: IconLucideMonitor,
  sections: isAndroid
    ? [inputModeSection, androidDynamicIslandSection]
    : [
        inputModeSection,
        desktopLyricSection,
        dynamicIslandSection,
        // taskbarLyric 仅 Windows 可用
        ...(navigator.platform.startsWith("Win") ? [taskbarLyricSection] : []),
      ],
};

export default externalLyricCategory;
