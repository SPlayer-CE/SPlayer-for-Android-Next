<script setup lang="ts">
import type { SettingCategory } from "@/types/settings-schema";
import type { SMenuItem } from "@/components/ui/SMenu.vue";
import { isCategoryVisible } from "@/settings/platformFilter";

const props = defineProps<{
  categories: SettingCategory[];
  activeId: string;
}>();

const emit = defineEmits<{
  select: [id: string];
}>();

const { t } = useI18n();

const menuItems = computed<SMenuItem[]>(() =>
  props.categories
    .filter((cat) => isCategoryVisible(cat))
    .map((cat) => ({
      key: cat.id,
      label: t(`settings.group.${cat.id}`),
      icon: cat.icon,
      trailing: undefined,
    })),
);
</script>

<template>
  <SMenu
    :items="menuItems"
    :model-value="activeId"
    center-active-on-mount
    @select="emit('select', $event)"
  />
</template>
