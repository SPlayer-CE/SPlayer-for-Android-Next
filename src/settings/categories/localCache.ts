import type { SettingCategory } from "@/types/settings-schema";
import { useSettingsStore } from "@/stores/settings";
import FileCacheManager from "@/components/settings/custom/FileCacheManager.vue";
import DbCacheManager from "@/components/settings/custom/DbCacheManager.vue";
import IconLucideHardDrive from "~icons/lucide/hard-drive";

const localCacheCategory: SettingCategory = {
  id: "localCache",
  icon: IconLucideHardDrive,
  sections: [
    {
      id: "songCache",
      items: [
        {
          key: "enableCache",
          type: "switch",
          binding: { store: "settings", path: "system.cache.enabled" },
          defaultValue: true,
          children: [
            {
              key: "enableSongCache",
              type: "switch",
              binding: { store: "settings", path: "system.cache.songCache.enabled" },
              defaultValue: true,
              children: [
                {
                  key: "cacheStreaming",
                  type: "switch",
                  binding: { store: "settings", path: "system.cache.songCache.cacheStreaming" },
                  defaultValue: false,
                },
                {
                  key: "songCacheSizeLimit",
                  type: "number",
                  binding: { store: "settings", path: "system.cache.songCache.sizeLimitGb" },
                  min: 0,
                  max: 10,
                  step: 1,
                  unit: "G",
                  defaultValue: 10,
                },
              ],
              childrenCondition: () => useSettingsStore().system.cache?.songCache?.enabled === true,
            },
          ],
          childrenCondition: () => useSettingsStore().system.cache?.enabled === true,
        },
      ],
    },
    {
      id: "cache",
      items: [
        {
          key: "fileCacheManager",
          type: "custom",
          component: FileCacheManager,
          fullWidth: true,
          keywords: ["cacheDir.label", "fileClearAll.label"],
        },
      ],
    },
    {
      id: "database",
      items: [
        {
          key: "dbCacheManager",
          type: "custom",
          component: DbCacheManager,
          fullWidth: true,
          keywords: ["dbClearAll.label"],
        },
      ],
    },
  ],
};

export default localCacheCategory;
