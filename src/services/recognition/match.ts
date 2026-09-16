/**
 * 渲染层听歌识曲匹配：把 AFP 指纹交给内嵌网易云 audio_match 接口，取前三个候选。
 * 桌面端由主进程 match.ts 承担，此处供 Android 渲染层识别管线使用（经 bridge 走内嵌 API）。
 */

import { neteaseCall } from "@/apis/netease";

/** 匹配接口返回的原始歌曲信息 */
export interface MatchedSong {
  id: number;
  name: string;
  artists: { name: string }[];
  album?: { name: string; picUrl?: string };
}

interface MatchResponse {
  code?: number;
  data?: {
    result?: { startTime?: number; song?: MatchedSong }[];
  };
}

export type MatchResult =
  { ok: true; songs: { song: MatchedSong; startTime?: number }[] } | { ok: false; code: "network" };

/**
 * 将音频指纹交给内嵌网易云接口匹配
 * @param fingerprint - AFP 生成的指纹 base64
 * @param durationSec - 音频片段时长，单位为秒
 * @returns 最多三个候选；接口异常或网络失败统一返回 network 错误
 */
export const matchAudio = async (
  fingerprint: string,
  durationSec: number,
): Promise<MatchResult> => {
  try {
    const body = await neteaseCall<MatchResponse>("audio_match", {
      audioFP: fingerprint,
      duration: durationSec,
    });
    if (body?.code !== 200) return { ok: false, code: "network" };
    const songs = (body.data?.result ?? [])
      .filter((item): item is { startTime?: number; song: MatchedSong } => !!item.song)
      .slice(0, 3)
      .map((item) => ({ song: item.song, startTime: item.startTime }));
    return { ok: true, songs };
  } catch (error) {
    console.warn("[recognition] 音频匹配请求失败", error);
    return { ok: false, code: "network" };
  }
};
