import type { SettingCategory } from "@/types/settings-schema";
import LanSharePanel from "@/components/settings/custom/LanSharePanel.vue";
import IconLucideWifi from "~icons/lucide/wifi";

const lanShareCategory: SettingCategory = {
  id: "lanShare",
  icon: IconLucideWifi,
  platform: "android",
  sections: [
    {
      id: "lanShare",
      items: [
        {
          key: "lanSharePanel",
          type: "custom",
          component: LanSharePanel,
          fullWidth: true,
        },
      ],
    },
  ],
};

export default lanShareCategory;
