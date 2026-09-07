<script setup lang="ts">
/**
 * 局域网页面流转 — 角色选择浮层。
 * 浏览器打开主机服务页面（从设备）时弹出，由用户手动选择以"从设备"还是"主设备"进入，
 * 避免角色自动判断产生冲突。仅在浏览器预览模式下出现，且每个会话仅询问一次。
 */
import { useI18n } from "vue-i18n";
import IconLucideHeadphones from "~icons/lucide/headphones";
import IconLucideMonitor from "~icons/lucide/monitor";

const emit = defineEmits<{ (e: "choose", role: "receiver" | "host"): void }>();

const { t } = useI18n();
</script>

<template>
  <div
    class="fixed inset-0 z-[60] flex items-center justify-center bg-black/50 backdrop-blur-sm p-6"
  >
    <div
      class="w-full max-w-md rounded-2xl bg-surface-panel border border-solid border-primary/10 shadow-2xl p-6"
    >
      <div class="text-lg font-semibold text-on-surface">
        {{ t("settings.lanShare.roleChooser.title") }}
      </div>
      <div class="mt-1 text-sm text-on-surface-variant/60">
        {{ t("settings.lanShare.roleChooser.desc") }}
      </div>

      <div class="mt-5 flex flex-col gap-3">
        <button
          class="flex items-start gap-3 p-4 rounded-xl border border-solid border-primary/15 bg-surface text-left transition-colors hover:border-primary/40 hover:bg-primary/5"
          @click="emit('choose', 'receiver')"
        >
          <IconLucideHeadphones class="size-5 text-primary shrink-0 mt-0.5" />
          <div class="min-w-0">
            <div class="text-sm font-medium text-on-surface">
              {{ t("settings.lanShare.roleChooser.receiver") }}
            </div>
            <div class="text-xs text-on-surface-variant/55 mt-0.5">
              {{ t("settings.lanShare.roleChooser.receiverDesc") }}
            </div>
          </div>
        </button>

        <button
          class="flex items-start gap-3 p-4 rounded-xl border border-solid border-primary/15 bg-surface text-left transition-colors hover:border-primary/40 hover:bg-primary/5"
          @click="emit('choose', 'host')"
        >
          <IconLucideMonitor class="size-5 text-primary shrink-0 mt-0.5" />
          <div class="min-w-0">
            <div class="text-sm font-medium text-on-surface">
              {{ t("settings.lanShare.roleChooser.host") }}
            </div>
            <div class="text-xs text-on-surface-variant/55 mt-0.5">
              {{ t("settings.lanShare.roleChooser.hostDesc") }}
            </div>
          </div>
        </button>
      </div>
    </div>
  </div>
</template>
