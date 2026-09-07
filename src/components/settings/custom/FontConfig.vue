<script setup lang="ts">
import type { StyleValue } from "vue";
import { useSettingsStore } from "@/stores/settings";
import { useSystemFonts } from "@/composables/useSystemFonts";
import { toast } from "@/composables/useToast";
import { isAndroid } from "@/services/bridge";
import IconLucideRotateCcw from "~icons/lucide/rotate-ccw";
import IconLucideDownload from "~icons/lucide/download";

defineOptions({ inheritAttrs: false });

const { t } = useI18n();
const settings = useSettingsStore();
const { families: fonts, loading: loadingFonts, ensureLoaded, importFont } = useSystemFonts();

type FontDraftKey =
  | "global"
  | "lyric"
  | "lyricChinese"
  | "lyricJapanese"
  | "lyricKorean"
  | "lyricLatin"
  | "desktopLyric"
  | "dynamicIsland"
  | "taskbarLyric";
type FontGroup = "general" | "appLyric" | "externalLyric";
type FontMode = "select" | "custom";

interface FontDraft {
  global: string;
  lyric: string;
  lyricChinese: string;
  lyricJapanese: string;
  lyricKorean: string;
  lyricLatin: string;
  desktopLyric: string;
  dynamicIsland: string;
  taskbarLyric: string;
}

interface FontTarget {
  key: FontDraftKey;
  label: string;
  defaultLabel: string;
  androidUnavailable?: boolean;
}

interface FontOption {
  value: string;
  label: string;
  style?: StyleValue;
}

const open = ref(false);
const mode = ref<FontMode>("select");

const draft = reactive<FontDraft>({
  global: "",
  lyric: "",
  lyricChinese: "",
  lyricJapanese: "",
  lyricKorean: "",
  lyricLatin: "",
  desktopLyric: "",
  dynamicIsland: "",
  taskbarLyric: "",
});

/** 字段定义 */
const TARGET_DEFS: Array<{ key: FontDraftKey; group: FontGroup; androidUnavailable?: boolean }> = [
  { key: "global", group: "general" },
  { key: "lyric", group: "appLyric" },
  { key: "lyricChinese", group: "appLyric" },
  { key: "lyricJapanese", group: "appLyric" },
  { key: "lyricKorean", group: "appLyric" },
  { key: "lyricLatin", group: "appLyric" },
  { key: "desktopLyric", group: "externalLyric", androidUnavailable: true },
  { key: "dynamicIsland", group: "externalLyric", androidUnavailable: true },
  { key: "taskbarLyric", group: "externalLyric", androidUnavailable: true },
];

const GROUP_ORDER: FontGroup[] = ["general", "appLyric", "externalLyric"];

/** 分组目标 */
const groupedTargets = computed<Array<{ group: FontGroup; items: FontTarget[] }>>(() => {
  const buildTarget = (def: {
    key: FontDraftKey;
    group: FontGroup;
    androidUnavailable?: boolean;
  }): FontTarget => {
    const name = t(`settings.fontConfig.fields.${def.key}`);
    const followLyricKeys: FontDraftKey[] = [
      "lyricChinese",
      "lyricJapanese",
      "lyricKorean",
      "lyricLatin",
    ];
    return {
      key: def.key,
      label: t("settings.fontConfig.fieldLabel", { name }),
      defaultLabel:
        def.key === "lyric"
          ? t("settings.fontConfig.useGlobal")
          : followLyricKeys.includes(def.key)
            ? t("settings.fontConfig.useLyric")
            : t("settings.fontConfig.useSystem"),
      androidUnavailable: def.androidUnavailable,
    };
  };
  return GROUP_ORDER.map((group) => ({
    group,
    items: TARGET_DEFS.filter((d) => d.group === group).map((d) => buildTarget(d)),
  })).filter((g) => g.items.length > 0);
});

/** 设置方式选项 */
const modeOptions = computed<FontOption[]>(() => [
  { value: "select", label: t("settings.fontConfig.modeSelect") },
  { value: "custom", label: t("settings.fontConfig.modeCustom") },
]);

/** 系统字体下拉项 */
const fontOptions = computed<FontOption[]>(() =>
  fonts.value.map((font) => ({ value: font, label: font, style: { fontFamily: `"${font}"` } })),
);

/** 字符串 → 数组 */
const parseFontChain = (value: string): string[] => {
  if (!value) return [];
  return value
    .split(",")
    .map((f) => f.trim().replace(/^["']|["']$/g, ""))
    .filter(Boolean);
};

/** 数组 → 字符串 */
const stringifyFontChain = (chain: string[]): string => {
  return chain.map((f) => (/[\s,]/.test(f) ? `"${f}"` : f)).join(", ");
};

/** 同步字体配置 */
const syncDraft = (): void => {
  draft.global = settings.appearance.fontFamily;
  draft.lyric = settings.lyric.fontFamily;
  draft.lyricChinese = settings.lyric.fontFamilyChinese;
  draft.lyricJapanese = settings.lyric.fontFamilyJapanese;
  draft.lyricKorean = settings.lyric.fontFamilyKorean;
  draft.lyricLatin = settings.lyric.fontFamilyLatin;
  draft.desktopLyric = settings.system.desktopLyric.fontFamily;
  draft.dynamicIsland = settings.system.dynamicIsland.fontFamily;
  draft.taskbarLyric = settings.system.taskbarLyric.fontFamily;
};

/** 打开字体配置 */
watch(open, (value) => {
  if (!value) return;
  syncDraft();
  ensureLoaded();
});

/** 选择字体 */
const handleChainChange = (
  key: FontDraftKey,
  value: string | number | (string | number)[],
): void => {
  const chain = Array.isArray(value) ? value : [value];
  draft[key] = stringifyFontChain(chain.map(String));
};

/** 手动输入字体 */
const handleManualInput = (key: FontDraftKey, value: string): void => {
  draft[key] = value.trim();
};

/** 重置字段 */
const handleResetField = (key: FontDraftKey): void => {
  draft[key] = "";
};

/** 更新设置方式 */
const updateMode = (value: string | number | boolean): void => {
  mode.value = value === "custom" ? "custom" : "select";
};

/** 保存字体配置 */
const handleSave = async (): Promise<void> => {
  settings.appearance.fontFamily = draft.global;
  settings.lyric.fontFamily = draft.lyric;
  settings.lyric.fontFamilyChinese = draft.lyricChinese;
  settings.lyric.fontFamilyJapanese = draft.lyricJapanese;
  settings.lyric.fontFamilyKorean = draft.lyricKorean;
  settings.lyric.fontFamilyLatin = draft.lyricLatin;
  await Promise.all([
    settings.setSystem("desktopLyric.fontFamily", draft.desktopLyric),
    settings.setSystem("dynamicIsland.fontFamily", draft.dynamicIsland),
    settings.setSystem("taskbarLyric.fontFamily", draft.taskbarLyric),
  ]);
  open.value = false;
};

const handleImportFont = async () => {
  const fontNames = await importFont();
  if (fontNames && fontNames.length > 0) {
    toast.success(t("settings.fontConfig.importSuccess", { name: fontNames.join(", ") }));
    // 导入后切换到选择模式，让用户在下拉列表中看到已导入的字体
    mode.value = "select";
  } else {
    toast.warning(t("settings.fontConfig.importFailed"));
  }
};
</script>

<template>
  <SButton type="primary" variant="secondary" size="small" @click="open = true">
    {{ t("common.configure") }}
  </SButton>
  <SDialog
    v-model:open="open"
    :title="t('settings.fontConfig.title')"
    :description="t('settings.fontConfig.description')"
    width="620px"
  >
    <div class="flex flex-col gap-3">
      <!-- 设置方式 -->
      <SCard variant="settings" class="flex items-center gap-3">
        <div class="min-w-0 flex-1">
          <div class="text-base">{{ t("settings.fontConfig.modeLabel") }}</div>
          <div class="text-sm text-on-surface-variant/70 mt-0.5">
            {{ t("settings.fontConfig.modeHint") }}
          </div>
        </div>
        <div class="shrink-0 flex gap-2">
          <SButton v-if="isAndroid" variant="secondary" class="shrink-0" @click="handleImportFont">
            <template #icon><IconLucideDownload /></template>
            {{ t("settings.fontConfig.importFont") }}
          </SButton>
          <div class="w-32 sm:w-40">
            <SSelect :model-value="mode" :options="modeOptions" @update:model-value="updateMode" />
          </div>
        </div>
      </SCard>

      <!-- 各分组 -->
      <div v-for="g in groupedTargets" :key="g.group" class="flex flex-col gap-2.5">
        <h4 class="flex items-center gap-2 text-base text-on-surface px-1">
          <span class="w-1 h-4 rounded-full bg-primary" />
          {{ t(`settings.fontConfig.groups.${g.group}`) }}
        </h4>
        <SCard
          v-for="target in g.items"
          :key="target.key"
          variant="settings"
          class="flex flex-col gap-2.5"
          :class="isAndroid && target.androidUnavailable ? 'opacity-50' : ''"
        >
          <div class="flex items-center gap-3">
            <div class="min-w-0 flex-1 text-base">
              {{ target.label }}
              <span
                v-if="isAndroid && target.androidUnavailable"
                class="text-xs text-on-surface-variant/50 ml-1"
              >
                {{ t("settings.desktopOnly") }}
              </span>
            </div>
            <SButton
              variant="ghost"
              circle
              size="small"
              :disabled="!draft[target.key] || (isAndroid && !!target.androidUnavailable)"
              :title="t('settings.fontConfig.resetRow')"
              @click="handleResetField(target.key)"
            >
              <template #icon><IconLucideRotateCcw /></template>
            </SButton>
          </div>
          <SCombobox
            v-if="mode === 'select'"
            class="w-full"
            :model-value="parseFontChain(draft[target.key])"
            :options="fontOptions"
            :disabled="loadingFonts || (isAndroid && !!target.androidUnavailable)"
            :placeholder="loadingFonts ? t('settings.fontConfig.loading') : target.defaultLabel"
            multiple
            clearable
            virtual
            fallback-option
            @update:model-value="handleChainChange(target.key, $event)"
          />
          <SInput
            v-else
            class="w-full"
            :model-value="draft[target.key]"
            :placeholder="t('settings.fontConfig.placeholder')"
            :disabled="isAndroid && !!target.androidUnavailable"
            clearable
            @update:model-value="handleManualInput(target.key, $event)"
          />
        </SCard>
      </div>
    </div>

    <template #footer="{ close }">
      <SButton variant="secondary" @click="close">{{ t("common.cancel") }}</SButton>
      <SButton type="primary" @click="handleSave">{{ t("common.save") }}</SButton>
    </template>
  </SDialog>
</template>
