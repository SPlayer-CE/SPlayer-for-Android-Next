/**
 * match.ts 匹配封装单元测试（O4）：覆盖调用参数、code 非 200、候选映射与截断、
 * 无 song 项过滤、result 缺失、以及请求异常统一归为 network。neteaseCall 被 mock。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";

const neteaseCallMock = vi.fn();

vi.mock("@/apis/netease", () => ({
  neteaseCall: (...args: unknown[]) => neteaseCallMock(...args),
}));

import { matchAudio } from "./match";

/** 构造匹配接口返回的原始歌曲信息 */
const song = (id: number) => ({
  id,
  name: `T${id}`,
  artists: [{ name: `A${id}` }],
  album: { name: `AL${id}`, picUrl: `p${id}` },
});

describe("matchAudio", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("以 audio_match 与指纹/时长调用内嵌接口", async () => {
    neteaseCallMock.mockResolvedValue({ code: 200, data: { result: [] } });
    await matchAudio("FP", 3);
    expect(neteaseCallMock).toHaveBeenCalledWith("audio_match", { audioFP: "FP", duration: 3 });
  });

  it("code 非 200 归为 network", async () => {
    neteaseCallMock.mockResolvedValue({ code: 301 });
    const res = await matchAudio("FP", 3);
    expect(res).toEqual({ ok: false, code: "network" });
  });

  it("映射候选、过滤无 song 项并截断到 3 个", async () => {
    neteaseCallMock.mockResolvedValue({
      code: 200,
      data: {
        result: [
          { song: song(1), startTime: 1 },
          { startTime: 2 },
          { song: song(2), startTime: 3 },
          { song: song(3), startTime: 4 },
          { song: song(4), startTime: 5 },
        ],
      },
    });
    const res = await matchAudio("FP", 3);
    expect(res.ok).toBe(true);
    if (!res.ok) return;
    expect(res.songs).toHaveLength(3);
    expect(res.songs.map((item) => item.song.id)).toEqual([1, 2, 3]);
    expect(res.songs[0].startTime).toBe(1);
  });

  it("result 缺失返回空候选", async () => {
    neteaseCallMock.mockResolvedValue({ code: 200 });
    const res = await matchAudio("FP", 3);
    expect(res.ok).toBe(true);
    if (res.ok) expect(res.songs).toEqual([]);
  });

  it("请求抛错统一归为 network", async () => {
    neteaseCallMock.mockRejectedValue(new Error("boom"));
    const res = await matchAudio("FP", 3);
    expect(res).toEqual({ ok: false, code: "network" });
  });
});
