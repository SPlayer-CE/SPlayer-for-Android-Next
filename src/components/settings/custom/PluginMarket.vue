<script setup lang="ts">
import type { MarketPlugin } from "@shared/types/plugin";
import { usePluginsStore } from "@/stores/plugins";
import { toast } from "@/composables/useToast";

const { t } = useI18n();
const pluginsStore = usePluginsStore();
const { marketPlugins, list } = storeToRefs(pluginsStore);

const loading = ref(false);
const errored = ref(false);
const installingId = ref<string | null>(null);

/** 逐段数字比较 remote 是否比 local 新 */
const isNewer = (remote: string, local: string): boolean => {
  const remoteParts = remote.split(".").map((part) => Number(part) || 0);
  const localParts = local.split(".").map((part) => Number(part) || 0);
  for (let i = 0; i < Math.max(remoteParts.length, localParts.length); i++) {
    const a = remoteParts[i] ?? 0;
    const b = localParts[i] ?? 0;
    if (a !== b) return a > b;
  }
  return false;
};

/** 各市场插件按钮状态：未装 install / 有新版 update / 已最新 installed（一次算好，模板里 O(1) 查） */
const cardStates = computed(() => {
  const installed = new Map(list.value.map((info) => [info.manifest.id, info.manifest.version]));
  const states = new Map<string, "install" | "update" | "installed">();
  for (const plugin of marketPlugins.value) {
    const local = installed.get(plugin.id);
    states.set(
      plugin.id,
      local === undefined ? "install" : isNewer(plugin.version, local) ? "update" : "installed",
    );
  }
  return states;
});

/** 拉取市场列表（finally 保底落 loading：Android 端 bridge 抛错时也不许无限转圈） */
const refresh = async (force = false): Promise<void> => {
  loading.value = true;
  errored.value = false;
  try {
    const res = await pluginsStore.fetchMarket(force);
    errored.value = !res.ok;
  } catch {
    // Android 端 embedded API 未就绪时 fetchMarket 直接抛错，转错误态给重试按钮
    errored.value = true;
  } finally {
    loading.value = false;
  }
};

onMounted(() => void refresh());
defineExpose({ refresh });

/** 从市场安装 / 更新 */
const handleInstall = async (plugin: MarketPlugin): Promise<void> => {
  installingId.value = plugin.id;
  try {
    const res = await pluginsStore.installFromMarket(plugin);
    if (res.ok) toast.success(t("settings.plugins.importSuccess"));
    else toast.error(res.error ?? t("settings.plugins.importFailed"));
  } finally {
    installingId.value = null;
  }
};
</script>

<template>
  <div class="flex flex-col gap-3">
    <!-- 加载 -->
    <div
      v-if="loading && marketPlugins.length === 0"
      class="flex flex-col items-center gap-3 py-12"
    >
      <SLoading class="text-4xl text-primary/70" />
      <span class="text-sm text-on-surface-variant">{{ t("common.loading") }}</span>
    </div>
    <!-- 加载失败 -->
    <div
      v-else-if="errored && marketPlugins.length === 0"
      class="flex flex-col items-center gap-3 py-12"
    >
      <span class="text-sm text-on-surface-variant">{{ t("settings.plugins.marketError") }}</span>
      <SButton variant="secondary" size="small" @click="refresh(true)">
        {{ t("common.refreshCache") }}
      </SButton>
    </div>
    <!-- 空 -->
    <div v-else-if="marketPlugins.length === 0" class="flex flex-col items-center gap-3 py-12">
      <IconLucidePuzzle class="size-8 text-on-surface-variant/40" />
      <span class="text-sm text-on-surface-variant">{{ t("settings.plugins.marketEmpty") }}</span>
    </div>
    <!-- 列表 -->
    <div v-else class="grid gap-2.5 [grid-template-columns:repeat(auto-fill,minmax(220px,1fr))]">
      <PluginCardBase
        v-for="plugin in marketPlugins"
        :key="plugin.id"
        :name="plugin.name"
        :description="plugin.description"
        :author="plugin.author"
      >
        <template #title-end>
          <STag size="small" round type="default" variant="soft">v{{ plugin.version }}</STag>
        </template>
        <template #actions>
          <SButton
            v-if="cardStates.get(plugin.id) === 'installed'"
            variant="secondary"
            size="small"
            type="success"
            block
            disabled
          >
            <template #icon><IconLucideCircleCheck class="size-4" /></template>
            {{ t("settings.plugins.installed") }}
          </SButton>
          <SButton
            v-else
            variant="secondary"
            size="small"
            :type="cardStates.get(plugin.id) === 'update' ? 'warning' : 'primary'"
            block
            :loading="installingId === plugin.id"
            @click="handleInstall(plugin)"
          >
            <template #icon>
              <IconLucideArrowUpCircle
                v-if="cardStates.get(plugin.id) === 'update'"
                class="size-4"
              />
              <IconLucideDownload v-else class="size-4" />
            </template>
            {{
              cardStates.get(plugin.id) === "update"
                ? t("settings.plugins.update")
                : t("settings.plugins.install")
            }}
          </SButton>
        </template>
      </PluginCardBase>
    </div>
  </div>
</template>
