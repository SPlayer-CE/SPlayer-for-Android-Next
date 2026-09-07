import { toast } from "@/composables/useToast";

/**
 * 复制文本到剪贴板，自动 toast 反馈
 */
export const useCopyText = () => {
  const { t } = useI18n();

  /**
   * 复制文本
   * @param text - 要复制的内容
   */
  const copy = async (text: string | null | undefined): Promise<void> => {
    if (!text) {
      toast.error(t("common.copyFailed"));
      return;
    }
    try {
      // Android WebView 的 navigator.clipboard 写入常被权限门控拒绝，统一走原生/桌面剪贴板通道
      await window.api.system.writeClipboardText(text);
      toast.success(t("common.copied"));
    } catch {
      toast.error(t("common.copyFailed"));
    }
  };

  return { copy };
};
