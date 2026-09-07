import type { SettingCategory, SettingItem, SettingSection } from "@/types/settings-schema";
import { isAndroid } from "@/services/bridge";

/** 判断 platform 字段在当前平台是否可见；未声明视为全平台。 */
export const isPlatformVisible = (platform?: "android" | "desktop"): boolean => {
  if (!platform) return true;
  if (platform === "android") return isAndroid;
  return !isAndroid;
};

/** 按当前平台过滤 items（保留子项的递归过滤）。 */
export const filterItemsByPlatform = (items: SettingItem[]): SettingItem[] =>
  items
    .filter((it) => isPlatformVisible(it.platform))
    .map((it) =>
      it.children?.length ? { ...it, children: filterItemsByPlatform(it.children) } : it,
    );

/** 按当前平台过滤 sections（同时过滤 items）。 */
export const filterSectionsByPlatform = (sections: SettingSection[]): SettingSection[] =>
  sections
    .filter((sec) => isPlatformVisible(sec.platform))
    .map((sec) => ({ ...sec, items: filterItemsByPlatform(sec.items) }))
    .filter((sec) => sec.items.length > 0);

/** 当前平台下分类是否可见：自身 platform 通过、且至少有可见 section 或纯 component 分类。 */
export const isCategoryVisible = (cat: SettingCategory): boolean => {
  if (!isPlatformVisible(cat.platform)) return false;
  if (cat.component && !cat.sections) return true;
  return filterSectionsByPlatform(cat.sections ?? []).length > 0;
};
