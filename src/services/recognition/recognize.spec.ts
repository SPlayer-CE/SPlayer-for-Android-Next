/**
 * recognize.ts 滑窗编排单元测试（O4 测试补齐）：覆盖事件订阅、无效/静音输入、
 * 滑窗分段与首命中即停、指纹/匹配失败分支、以及取消令牌的中途早退。
 * 指纹与匹配均 mock，避免依赖 WASM 与网络。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { RecognitionEvent } from "@shared/types/recognition";

const fingerprintPcmMock = vi.fn();
const matchAudioMock = vi.fn();

vi.mock("./fingerprint", () => ({
  fingerprintPcm: (...args: unknown[]) => fingerprintPcmMock(...args),
}));
vi.mock("./match", () => ({
  matchAudio: (...args: unknown[]) => matchAudioMock(...args),
}));

import { cancelRecognition, submitRecognitionPcm, subscribeRecognition } from "./recognize";

/** 生成 8 kHz 单声道正弦样本，amplitude 控制音量 */
const makePcm = (seconds: number, amplitude = 0.2): Float32Array => {
  const total = seconds * 8000;
  const pcm = new Float32Array(total);
  for (let i = 0; i < total; i++) {
    pcm[i] = amplitude * Math.sin((2 * Math.PI * 440 * i) / 8000);
  }
  return pcm;
};

/** 构造一个匹配候选（matchAudio 的 songs 元素） */
const makeSong = (id: number) => ({
  song: {
    id,
    name: `T${id}`,
    artists: [{ name: `A${id}` }],
    album: { name: `AL${id}`, picUrl: `p${id}` },
  },
  startTime: 2,
});

const doneEvents = (events: RecognitionEvent[]) => events.filter((e) => e.phase === "done");
const errorEvents = (events: RecognitionEvent[]) => events.filter((e) => e.phase === "error");

describe("recognize 滑窗编排", () => {
  let events: RecognitionEvent[];
  let unsubscribe: () => void;

  beforeEach(() => {
    vi.clearAllMocks();
    events = [];
    unsubscribe = subscribeRecognition((event) => events.push(event));
    fingerprintPcmMock.mockResolvedValue({ ok: true, fingerprint: "fp" });
    matchAudioMock.mockResolvedValue({ ok: true, songs: [] });
  });

  afterEach(() => {
    unsubscribe();
  });

  it("空 PCM 直接报 capture-failed 且不进入指纹", async () => {
    await submitRecognitionPcm(new Float32Array(0));
    expect(errorEvents(events).map((e) => e.error?.code)).toContain("capture-failed");
    expect(fingerprintPcmMock).not.toHaveBeenCalled();
  });

  it("全静音输入报 silent-input 且不进入指纹", async () => {
    await submitRecognitionPcm(new Float32Array(8000));
    expect(errorEvents(events).map((e) => e.error?.code)).toContain("silent-input");
    expect(fingerprintPcmMock).not.toHaveBeenCalled();
  });

  it("短于一个窗口时至少产生一个分段并正常 done", async () => {
    await submitRecognitionPcm(makePcm(1));
    expect(fingerprintPcmMock).toHaveBeenCalledTimes(1);
    expect(doneEvents(events)).toHaveLength(1);
  });

  it("5 秒输入按 3s 窗口 / 1s 步长切 3 段，首命中即停", async () => {
    matchAudioMock
      .mockResolvedValueOnce({ ok: true, songs: [] })
      .mockResolvedValueOnce({ ok: true, songs: [] })
      .mockResolvedValueOnce({ ok: true, songs: [makeSong(7)] });
    await submitRecognitionPcm(makePcm(5));
    expect(fingerprintPcmMock).toHaveBeenCalledTimes(3);
    const done = doneEvents(events);
    expect(done).toHaveLength(1);
    const candidates = done[0].candidates ?? [];
    expect(candidates).toHaveLength(1);
    expect(candidates[0].title).toBe("T7");
    // 第三段起点 16000 样本 = 2s，叠加匹配返回的 startTime 2 → 4
    expect(candidates[0].startTime).toBe(4);
  });

  it("指纹不可用报 afp-unavailable 且不再匹配", async () => {
    fingerprintPcmMock.mockResolvedValue({ ok: false, error: "afp-unavailable" });
    await submitRecognitionPcm(makePcm(3));
    expect(errorEvents(events).map((e) => e.error?.code)).toContain("afp-unavailable");
    expect(matchAudioMock).not.toHaveBeenCalled();
  });

  it("匹配服务不可用报 network", async () => {
    matchAudioMock.mockResolvedValue({ ok: false });
    await submitRecognitionPcm(makePcm(3));
    expect(errorEvents(events).map((e) => e.error?.code)).toContain("network");
  });

  it("指纹期间取消则中途早退，不派发 done", async () => {
    fingerprintPcmMock.mockImplementation(async () => {
      cancelRecognition();
      return { ok: true, fingerprint: "fp" };
    });
    await submitRecognitionPcm(makePcm(3));
    expect(doneEvents(events)).toHaveLength(0);
    expect(events.some((e) => e.phase === "matching")).toBe(false);
  });
});
