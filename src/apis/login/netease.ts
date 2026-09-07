/**
 * 登录相关
 */

import type { UserProfile } from "@/types/user";
import { netease as neteaseApi } from "@/apis/netease";
import type { QrLoginAdapter, QrLoginState } from "./platform";
import { isAndroid, neteaseRestCall } from "@/services/bridge";

interface LoginStatusBody {
  code?: number | string;
  data?: {
    profile?: Partial<UserProfile> & { userId?: number };
    account?: { id?: number };
  };
  profile?: Partial<UserProfile> & { userId?: number };
  account?: { id?: number };
}

/** login_qr_key 响应体 */
interface QrKeyBody {
  data?: { unikey?: string };
  code?: number;
}

/** login_qr_check 响应体 */
interface QrCheckBody {
  code?: number;
  cookie?: string;
  nickname?: string;
  avatarUrl?: string;
}

/**
 * 生成扫码登录二维码 key
 * Android 端直接走 /api/netease/login_qr_key REST 路由，绕过 /api/apis/call RPC 包装，
 * 确保 response body 中的 cookie 字段完整传递（参考 SPlayer-for-Android 实现）
 * @returns 二维码 key
 */
export const qrKey = async (): Promise<string> => {
  const body = isAndroid
    ? await neteaseRestCall<QrKeyBody>("login_qr_key", { timestamp: Date.now() })
    : await neteaseApi.login_qr_key<QrKeyBody>({ timestamp: Date.now() });
  const unikey = body?.data?.unikey;
  if (!unikey) throw new Error("qr key missing");
  return unikey;
};

export type QrStatusCode = 800 | 801 | 802 | 803;

export interface QrCheckResult {
  code: QrStatusCode;
  cookie?: string;
  nickname?: string;
  avatarUrl?: string;
}

/**
 * 轮询扫码状态
 * - 800 已过期 / 801 待扫码 / 802 待确认 / 803 已确认（含 cookie）
 * Android 端直接走 /api/netease/login_qr_check REST 路由，确保 cookie 完整返回
 * @param key 二维码 key
 * @returns 扫码状态和结果
 */
export const qrCheck = async (key: string): Promise<QrCheckResult> => {
  const body = isAndroid
    ? await neteaseRestCall<QrCheckBody>("login_qr_check", { key, timestamp: Date.now() })
    : await neteaseApi.login_qr_check<QrCheckBody>({ key, timestamp: Date.now() });
  const code = (body?.code ?? 801) as QrStatusCode;
  return {
    code,
    cookie: body?.cookie,
    nickname: body?.nickname,
    avatarUrl: body?.avatarUrl,
  };
};

/**
 * 二维码内容
 * @param key 二维码 key
 * @returns 二维码内容
 */
export const qrContent = (key: string): string => `https://music.163.com/login?codekey=${key}`;

export const neteaseQrLoginAdapter: QrLoginAdapter = {
  create: async () => {
    const key = await qrKey();
    return { key, content: qrContent(key) };
  },
  check: async (key) => {
    const result = await qrCheck(key);
    const state: QrLoginState =
      result.code === 800
        ? "expired"
        : result.code === 802
          ? "scanned"
          : result.code === 803
            ? "success"
            : "waiting";
    return { state, nickname: result.nickname, avatarUrl: result.avatarUrl };
  },
};

/** captcha/sent 响应体 */
interface CaptchaSentBody {
  code?: number;
  data?: boolean;
}

/** login/cellphone 响应体 */
interface LoginCellphoneBody {
  code?: number;
  cookie?: string;
  profile?: { nickname?: string; avatarUrl?: string; userId?: number };
}

/** countries/code/list 国家列表条目 */
export interface CountryEntry {
  zh?: string;
  en?: string;
  code?: string;
}
export interface CountryGroup {
  label: string;
  countryList: CountryEntry[];
}

/** 发送短信验证码
 * @param phone 手机号（不含区号）
 * @param ctcode 国家代码，默认 86
 */
export const sendCaptcha = async (phone: string, ctcode = 86): Promise<boolean> => {
  const body = isAndroid
    ? await neteaseRestCall<CaptchaSentBody>("captcha_sent", {
        phone,
        ctcode,
        timestamp: Date.now(),
      })
    : await neteaseApi.captcha_sent<CaptchaSentBody>({ phone, ctcode, timestamp: Date.now() });
  return body?.code === 200;
};

/** 验证短信验证码
 * @param phone 手机号
 * @param captcha 验证码
 * @param ctcode 国家代码
 */
export const verifyCaptcha = async (
  phone: string,
  captcha: string,
  ctcode = 86,
): Promise<boolean> => {
  const body = isAndroid
    ? await neteaseRestCall<{ code?: number }>("captcha_verify", {
        phone,
        captcha,
        ctcode,
        timestamp: Date.now(),
      })
    : await neteaseApi.captcha_verify<{ code?: number }>({
        phone,
        captcha,
        ctcode,
        timestamp: Date.now(),
      });
  return body?.code === 200;
};

/** 手机号 + 验证码登录
 * @param phone 手机号
 * @param captcha 验证码
 * @param ctcode 国家代码
 * @returns 登录结果，code 200 时 cookie 包含 MUSIC_U
 */
export const loginCellphone = async (
  phone: string,
  captcha: string,
  ctcode = 86,
): Promise<{ code: number; cookie?: string }> => {
  const body = isAndroid
    ? await neteaseRestCall<LoginCellphoneBody>("login_cellphone", {
        phone,
        captcha,
        ctcode,
        timestamp: Date.now(),
      })
    : await neteaseApi.login_cellphone<LoginCellphoneBody>({
        phone,
        captcha,
        ctcode,
        timestamp: Date.now(),
      });
  return { code: body?.code ?? 0, cookie: body?.cookie };
};

/** 获取国家区号列表（缓存到 localStorage） */
export const fetchCountryList = async (): Promise<CountryGroup[]> => {
  const body = isAndroid
    ? await neteaseRestCall<{ data?: CountryGroup[] }>("countries_code_list")
    : await neteaseApi.countries_code_list<{ data?: CountryGroup[] }>();
  return body?.data ?? [];
};

/**
 * 校验 cookie 并取当前用户 profile
 * @returns 已登录返回 profile；未登录或 cookie 失效返回 null
 */
export const fetchLoginStatus = async (): Promise<UserProfile | null> => {
  const body = await neteaseApi.login_status<LoginStatusBody>({ timestamp: Date.now() });
  if (body?.code !== undefined && Number(body.code) !== 200) return null;
  const account = body?.data?.account ?? body?.account;
  const profile = body?.data?.profile ?? body?.profile;

  // 游客/匿名账号（anonimous 为 true、或无有效 profile/nickname）判定为未登录
  if (
    !account ||
    (account as { anonimous?: boolean }).anonimous ||
    !profile ||
    !profile.userId ||
    !profile.nickname
  ) {
    return null;
  }

  return {
    userId: profile.userId,
    nickname: profile.nickname,
    avatarUrl: profile.avatarUrl,
    backgroundUrl: profile.backgroundUrl,
    signature: profile.signature,
    vipType: profile.vipType,
    gender: profile.gender,
    province: profile.province,
    city: profile.city,
  };
};

/**
 * 续期登录 cookie
 * set-cookie 由主进程 SESSION_MUTATING 自动写回 SQLite
 * @returns 服务端是否实际下发了新的登录 cookie
 */
export const refreshLogin = async (): Promise<boolean> => {
  const body = await neteaseApi.login_refresh<{ cookie?: unknown }>({ timestamp: Date.now() });
  return typeof body?.cookie === "string" && /(?:^|;)\s*MUSIC_U=/.test(body.cookie);
};

/** 服务端登出（仅打断 server session，不清本地 cookie） */
export const logoutNetease = async (): Promise<void> => {
  await neteaseApi.logout();
};
