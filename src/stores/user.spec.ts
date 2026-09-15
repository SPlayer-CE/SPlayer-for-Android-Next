import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Track } from "@shared/types/player";

const storage = vi.hoisted(() => ({
  getItem: vi.fn(),
  setItem: vi.fn(() => Promise.resolve()),
}));

vi.mock("localforage", () => ({
  default: {
    createInstance: () => storage,
  },
}));

vi.mock("@/apis/user/netease", () => ({
  fetchLikelist: vi.fn(),
  fetchSubcount: vi.fn(),
  fetchUserAlbums: vi.fn(),
  fetchUserArtists: vi.fn(),
  fetchUserDjs: vi.fn(),
  fetchUserLevel: vi.fn(),
  fetchUserMvs: vi.fn(),
  fetchUserPlaylists: vi.fn(),
  toggleLikeSong: vi.fn(() => Promise.resolve()),
}));

let fetchPlaylistHandler: ((id: string, opts: any) => Promise<any>) | null = null;
vi.mock("@/apis/playlist/netease", () => ({
  fetchPlaylist: vi.fn((id, opts) => {
    if (fetchPlaylistHandler) return fetchPlaylistHandler(id, opts);
    return Promise.resolve();
  }),
  createPlaylist: vi.fn(),
  deletePlaylist: vi.fn(),
  updatePlaylistName: vi.fn(),
  updatePlaylistDesc: vi.fn(),
  addToPlaylist: vi.fn(),
  removeFromPlaylist: vi.fn(),
  subscribePlaylist: vi.fn(),
}));

vi.mock("@/apis/login/netease", () => ({
  fetchLoginStatus: vi.fn(),
  refreshLogin: vi.fn(),
  logoutNetease: vi.fn(),
}));

vi.mock("@/apis/song/netease", () => ({
  songsByIds: vi.fn(() => Promise.resolve([])),
}));

vi.mock("@/utils/embeddedApi", () => ({
  waitForEmbeddedCookieReady: vi.fn(() => Promise.resolve()),
}));

import { useUserStore } from "./user";

const createTrack = (id: string, title = id): Track => ({
  id,
  title,
  artists: [],
  source: "netease",
  duration: 180000,
});

describe("user store - 红心列表缓存与防死锁", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    storage.getItem.mockReset();
    storage.setItem.mockClear();
    fetchPlaylistHandler = null;
  });

  it("水合本地缓存成功恢复红心曲目、歌单和 ID 列表", async () => {
    const cachedTracks: Track[] = [createTrack("101", "Song 1"), createTrack("102", "Song 2")];
    storage.getItem.mockImplementation((key: string) => {
      if (key === "liked-playlist") {
        return Promise.resolve({
          playlistId: "pl-1",
          userId: 12345,
          tracks: cachedTracks,
          cachedAt: Date.now(),
        });
      }
      if (key === "liked-song-ids") {
        return Promise.resolve({
          userId: 12345,
          ids: ["101", "102"],
          cachedAt: Date.now(),
        });
      }
      if (key === "playlists") {
        return Promise.resolve({
          userId: 12345,
          playlists: [{ id: "pl-1", name: "我喜欢的音乐", trackCount: 2 }],
          cachedAt: Date.now(),
        });
      }
      return Promise.resolve(null);
    });

    const store = useUserStore();
    store.profile = { userId: 12345, nickname: "Tester" } as any;

    await store.ensureLikedPlaylist();

    expect(store.likedPlaylistTracks).toEqual(cachedTracks);
    expect(store.likedSongIds.has("101")).toBe(true);
    expect(store.likedSongIds.has("102")).toBe(true);
    expect(store.likedPlaylistId).toBe("pl-1");
  });

  it("toggleLike 传入 track 时乐观更新 likedPlaylistTracks 并持久化缓存", async () => {
    const store = useUserStore();
    store.profile = { userId: 12345, nickname: "Tester" } as any;
    store.playlists = [{ id: "pl-1", name: "我喜欢的音乐", trackCount: 0 }] as any;

    const track = createTrack("201", "New Favorite");
    const ok = await store.toggleLike("201", track);

    expect(ok).toBe(true);
    expect(store.likedSongIds.has("201")).toBe(true);
    expect(store.likedPlaylistTracks).toContainEqual(track);
    expect(storage.setItem).toHaveBeenCalledWith(
      "liked-playlist",
      expect.objectContaining({
        playlistId: "pl-1",
        userId: 12345,
        tracks: expect.arrayContaining([expect.objectContaining({ id: "201" })]),
      }),
    );

    // 取消收藏
    const ok2 = await store.toggleLike("201", track);
    expect(ok2).toBe(true);
    expect(store.likedSongIds.has("201")).toBe(false);
    expect(store.likedPlaylistTracks.some((t) => t.id === "201")).toBe(false);
  });

  it("并发覆盖：旧请求被 abort 时 loading 由最新请求收尾复位，且增量持久化批次", async () => {
    const tick = (): Promise<void> => new Promise((resolve) => window.setTimeout(() => resolve(), 0));
    const secondGate: { release?: () => void } = {};
    let calls = 0;

    fetchPlaylistHandler = (_id, opts) => {
      calls += 1;
      if (calls === 1) {
        // 第一批到达后长期挂起，直到被后续请求 abort
        opts.onBatch([createTrack("301", "Batch 1")]);
        return new Promise((_resolve, reject) => {
          opts.signal.addEventListener("abort", () => {
            reject(new DOMException("Aborted", "AbortError"));
          });
        });
      }
      opts.onBatch([createTrack("401", "Batch 2")]);
      return new Promise<void>((resolve) => {
        secondGate.release = resolve;
      });
    };

    const store = useUserStore();
    store.profile = { userId: 12345, nickname: "Tester" } as any;
    store.playlists = [{ id: "pl-1", name: "我喜欢的音乐", trackCount: 1 }] as any;

    // 第一次加载：请求挂起，loading 置位，首批已增量落盘
    await store.ensureLikedPlaylist();
    await tick();
    expect(store.likedPlaylistTracks.map((track) => track.id)).toEqual(["301"]);
    expect(store.likedPlaylistLoading).toBe(true);
    expect(storage.setItem).toHaveBeenCalledWith("liked-playlist", expect.anything());

    // 第二次加载覆盖并 abort 旧请求：旧请求结束不得提前把 loading 置回，须由最新请求收尾
    await store.ensureLikedPlaylist(true);
    await tick();
    expect(calls).toBe(2);
    expect(store.likedPlaylistLoading).toBe(true);

    secondGate.release?.();
    await tick();

    expect(store.likedPlaylistTracks.map((track) => track.id)).toEqual(["401"]);
    expect(store.likedPlaylistLoading).toBe(false);
  });

  it("离线冷启动：playlists 尚未恢复时，likedPlaylistId 可通过 liked-playlist 缓存回退并正常就绪", async () => {
    storage.getItem.mockImplementation((key: string) => {
      if (key === "liked-playlist") {
        return Promise.resolve({
          playlistId: "offline-pl-999",
          userId: 12345,
          tracks: [createTrack("999", "Offline Song")],
          cachedAt: Date.now(),
        });
      }
      return Promise.resolve(null);
    });

    // 模拟离线网络错误
    fetchPlaylistHandler = () => Promise.reject(new Error("Network offline"));

    const store = useUserStore();
    store.profile = { userId: 12345, nickname: "Tester" } as any;

    expect(store.likedPlaylistId).toBeNull();
    await store.ensureLikedPlaylist();

    // 缓存加载后，likedPlaylistId 自动指向 offline-pl-999
    expect(store.likedPlaylistTracks).toHaveLength(1);
    expect(store.likedPlaylistTracks[0].id).toBe("999");
    expect(store.likedPlaylistId).toBe("offline-pl-999");
    expect(store.likedPlaylistLoading).toBe(false);
  });
});
