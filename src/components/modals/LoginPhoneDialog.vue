<script setup lang="ts">
import { toast } from "@/composables/useToast";
import { useUserStore } from "@/stores/user";
import {
  sendCaptcha,
  verifyCaptcha,
  loginCellphone,
  fetchCountryList,
  type CountryGroup,
} from "@/apis/login/netease";

const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{
  "update:open": [value: boolean];
  /** 登录成功 */
  success: [];
}>();

const { t } = useI18n();
const user = useUserStore();

const phone = ref("");
const captcha = ref("");
const ctcode = ref(86);
const loading = ref(false);
const sendingCaptcha = ref(false);

/** 倒计时秒数 */
const countdown = ref(0);
/** 发送按钮文字 */
const sendBtnText = computed(() =>
  countdown.value > 0 ? `${countdown.value}s` : t("login.phoneSendCaptcha"),
);
const sendBtnDisabled = computed(
  () => countdown.value > 0 || sendingCaptcha.value || phone.value.trim().length === 0,
);

/** 国家区号选项 */
interface CountryOption {
  label: string;
  value: number;
}
const countryOptions = ref<CountryOption[]>([]);

const loadCountryList = async (): Promise<void> => {
  const cacheKey = "splayer:countryList";
  try {
    const cached = localStorage.getItem(cacheKey);
    if (cached) {
      const groups = JSON.parse(cached) as CountryGroup[];
      countryOptions.value = transformCountryGroups(groups);
      return;
    }
  } catch {
    // 缓存解析失败，继续走网络获取
  }
  try {
    const groups = await fetchCountryList();
    if (groups.length > 0) {
      localStorage.setItem(cacheKey, JSON.stringify(groups));
    }
    countryOptions.value = transformCountryGroups(groups);
  } catch {
    countryOptions.value = [{ label: `中国 (+86)`, value: 86 }];
  }
};

const transformCountryGroups = (groups: CountryGroup[]): CountryOption[] => {
  const options: CountryOption[] = [];
  for (const group of groups) {
    for (const c of group.countryList) {
      const code = Number(c.code);
      if (!Number.isNaN(code)) {
        options.push({ label: `${c.zh || c.en || ""} (+${c.code})`, value: code });
      }
    }
  }
  // 默认 +86 排在第一位
  options.sort((a, b) => (a.value === 86 ? -1 : b.value === 86 ? 1 : a.value - b.value));
  return options;
};

const { pause: pauseCountdown, resume: resumeCountdown } = useIntervalFn(
  () => {
    countdown.value--;
    if (countdown.value <= 0) pauseCountdown();
  },
  1000,
  { immediate: false },
);

/** 发送验证码 */
const onSendCaptcha = async (): Promise<void> => {
  const p = phone.value.trim();
  if (!p) {
    toast.error(t("login.phoneEmpty"));
    return;
  }
  sendingCaptcha.value = true;
  try {
    const ok = await sendCaptcha(p, ctcode.value);
    if (ok) {
      toast.success(t("login.phoneCaptchaSent"));
      countdown.value = 60;
      resumeCountdown();
    } else {
      toast.error(t("login.phoneCaptchaSendFailed"));
    }
  } catch (err) {
    console.warn("[login] send captcha failed:", err);
    toast.error(t("login.phoneCaptchaSendFailed"));
  } finally {
    sendingCaptcha.value = false;
  }
};

/** 提交登录 */
const submit = async (): Promise<void> => {
  const p = phone.value.trim();
  const c = captcha.value.trim();
  if (!p || !c) {
    toast.error(t("login.phoneEmptyFields"));
    return;
  }
  loading.value = true;
  try {
    // 先验证码校验
    const verified = await verifyCaptcha(p, c, ctcode.value);
    if (!verified) {
      toast.error(t("login.phoneCaptchaInvalid"));
      return;
    }
    // 手机号 + 验证码登录
    const result = await loginCellphone(p, c, ctcode.value);
    if (result.code !== 200 || !result.cookie?.includes("MUSIC_U")) {
      toast.error(t("login.failed"));
      return;
    }
    await user.setCookie(result.cookie);
    const ok = await user.fetchStatus();
    if (!ok) {
      toast.error(t("login.failed"));
      return;
    }
    toast.success(t("login.success"));
    emit("success");
    emit("update:open", false);
  } catch (err) {
    console.warn("[login] phone login failed:", err);
    toast.error(t("login.failed"));
  } finally {
    loading.value = false;
  }
};

watch(
  () => props.open,
  (open) => {
    if (open) {
      if (countryOptions.value.length === 0) void loadCountryList();
    } else {
      phone.value = "";
      captcha.value = "";
      ctcode.value = 86;
      loading.value = false;
      sendingCaptcha.value = false;
      countdown.value = 0;
      pauseCountdown();
    }
  },
);

const onOpenUpdate = (value: boolean): void => emit("update:open", value);
</script>

<template>
  <SDialog :open="open" :title="t('login.phoneLogin')" width="420px" @update:open="onOpenUpdate">
    <div class="flex flex-col gap-4 py-1">
      <!-- 国家区号 + 手机号 -->
      <div class="flex gap-2">
        <SSelect
          v-model="ctcode"
          :options="countryOptions"
          :placeholder="t('login.phoneCountry')"
          class="w-36 shrink-0"
        />
        <SInput
          v-model="phone"
          type="tel"
          :placeholder="t('login.phonePlaceholder')"
          :disabled="loading"
          clearable
          class="flex-1"
        >
          <template #prefix>
            <IconLucideSmartphone class="size-4 text-on-surface-variant" />
          </template>
        </SInput>
      </div>
      <!-- 验证码 + 发送按钮 -->
      <div class="flex gap-2">
        <SInput
          v-model="captcha"
          type="text"
          :placeholder="t('login.phoneCaptchaPlaceholder')"
          :disabled="loading"
          clearable
          class="flex-1"
        >
          <template #prefix>
            <IconLucideShieldCheck class="size-4 text-on-surface-variant" />
          </template>
        </SInput>
        <SButton
          variant="outline"
          :disabled="sendBtnDisabled"
          :loading="sendingCaptcha"
          class="shrink-0"
          @click="onSendCaptcha"
        >
          {{ sendBtnText }}
        </SButton>
      </div>
    </div>
    <template #footer="{ close }">
      <SButton variant="tertiary" :disabled="loading" @click="close">
        {{ t("common.cancel") }}
      </SButton>
      <SButton type="primary" :loading="loading" @click="submit">
        {{ t("login.phoneLoginBtn") }}
      </SButton>
    </template>
  </SDialog>
</template>
