import type { Album, Artist, AudioQuality, Playlist, Track } from "@shared/types/player";
import type { UserSubcount, UserVideoFavorite, UserRadioFavorite } from "@/types/user";
import type { NeteaseSong } from "@/types/netease";

interface NeteaseError {
  code?: number;
  message?: string;
  msg?: string;
}

/**
 * 校验网易云接口响应
 * code !== 200 时抛 Error，message 取自 body.message / body.msg
 */
export const ensureOk = <T>(body: T): T => {
  const meta = body as NeteaseError | null | undefined;
  if (!meta || meta.code !== 200) {
    throw new Error(meta?.message ?? meta?.msg ?? "");
  }
  return body;
};

/**
 * Android 端默认禁用明文 HTTP；服务商返回的资源链接偶尔仍是 http，需要就地升到 https。
 * @param url - 原始资源 URL
 * @returns 可直接用于前端/原生消费的安全 URL
 */
export const normalizeNeteaseMediaUrl = (url: string | undefined): string | undefined => {
  if (!url) return undefined;
  try {
    const parsed = new URL(url);
    if (parsed.protocol === "http:" && parsed.hostname.endsWith(".music.126.net")) {
      parsed.protocol = "https:";
      return parsed.toString();
    }
  } catch {
    return url;
  }
  return url;
};

/**
 * 归一化 Track 里的服务商资源地址。
 * 队列/历史等持久化数据可能残留旧的 http 链接，这里统一在恢复时修正。
 * @param track - 原始 Track
 * @returns 归一化后的 Track
 */
export const normalizeNeteaseTrackMediaUrls = (track: Track): Track => {
  if (track.source !== "netease") return track;
  const cover = normalizeNeteaseMediaUrl(track.cover) ?? track.cover;
  const coverOriginal = normalizeNeteaseMediaUrl(track.coverOriginal) ?? track.coverOriginal;
  const albumCover = track.album
    ? (normalizeNeteaseMediaUrl(track.album.cover) ?? track.album.cover)
    : undefined;
  const changed =
    cover !== track.cover ||
    coverOriginal !== track.coverOriginal ||
    albumCover !== track.album?.cover;
  if (!changed) return track;
  return {
    ...track,
    cover,
    coverOriginal,
    album: track.album ? { ...track.album, cover: albumCover } : undefined,
  };
};

/**
 * 给封面 URL 拼尺寸
 * @param url - 封面原始 URL
 * @param size - 期望像素边长，默认 300
 */
export const withPicSize = (url: string | undefined, size = 300): string | undefined => {
  const normalized = normalizeNeteaseMediaUrl(url);
  if (!normalized) return undefined;
  if (normalized.includes("?param=")) return normalized;
  return `${normalized}?param=${size}y${size}`;
};

/**
 * 根据 song 对象选择最佳音质
 * @param song - 原始 song 对象
 * @returns 最佳音质
 */
const pickQuality = (song: NeteaseSong): AudioQuality | undefined => {
  if (song.hr) {
    return {
      codec: "flac",
      sampleRate: Math.max(song.hr.sr ?? 0, 96000),
      bitsPerSample: 24,
      bitRate: song.hr.br,
      channels: 2,
    };
  }
  if (song.sq) {
    return {
      codec: "flac",
      sampleRate: song.sq.sr,
      bitsPerSample: 16,
      bitRate: song.sq.br,
      channels: 2,
    };
  }
  const mp3 = song.h ?? song.m ?? song.l;
  if (mp3) {
    return {
      codec: "mp3",
      sampleRate: mp3.sr,
      bitsPerSample: 16,
      bitRate: mp3.br,
      channels: 2,
    };
  }
  return undefined;
};

/**
 *  song → 应用层 Track
 * @param song - 原始 song 对象
 */
export const songToTrack = (song: NeteaseSong): Track => {
  // 兼容旧字段
  const album = song.al ?? song.album;
  const ar = song.ar ?? song.artists ?? [];
  const aliasList = song.alia ?? song.alias;
  const cover = withPicSize(album?.picUrl);
  const coverOriginal = withPicSize(album?.picUrl, 1024);
  const comment = aliasList?.find((s) => s?.trim()) ?? undefined;
  return {
    id: String(song.id),
    source: "netease",
    title: song.name,
    comment,
    artists: ar.map((artist) => ({ id: String(artist.id), name: artist.name })),
    album: album ? { id: String(album.id), name: album.name, cover } : undefined,
    duration: song.dt ?? song.duration ?? 0,
    cover,
    coverOriginal,
    quality: pickQuality(song),
    fee: song.fee,
    cloud: song.pc != null ? true : undefined,
  };
};

/**
 *  songs 列表 → Track 列表，空/缺省安全
 * @param songs - 接口返回的 songs 数组
 */
export const songsToTracks = (songs: NeteaseSong[] | undefined | null): Track[] =>
  songs?.map(songToTrack) ?? [];

/**
 * 歌单条目 → 应用层 Playlist
 * 适用 `/user/playlist` 与 `/playlist/detail` 的 playlist 字段
 */
export const toPlaylist = (raw: any): Playlist => ({
  id: String(raw.id),
  name: raw.name,
  cover: withPicSize(raw.coverImgUrl),
  description: raw.description,
  trackCount: raw.trackCount,
  owner: raw.creator?.nickname,
});

/** 收藏专辑（/album/sublist 元素）→ 应用层 Album */
export const toAlbum = (raw: any): Album => ({
  id: String(raw.id),
  name: raw.name,
  cover: withPicSize(raw.picUrl),
  artist: raw.artists?.map((a: { name: string }) => a.name).join(" / ") ?? raw.artist?.name,
  trackCount: raw.size,
  year: raw.publishTime ? new Date(raw.publishTime).getFullYear() : undefined,
});

/** 收藏歌手（/artist/sublist 元素）→ 应用层 Artist */
export const toArtist = (raw: any): Artist => ({
  id: String(raw.id),
  name: raw.name,
  avatar: withPicSize(raw.img1v1Url ?? raw.picUrl),
  albumCount: raw.albumSize,
});

/** 收藏 MV（/mv/sublist 元素）→ 轻量展示模型 */
export const toUserMvFavorite = (raw: any): UserVideoFavorite => ({
  id: String(raw.id ?? raw.vid),
  name: raw.name ?? raw.title ?? "",
  cover: withPicSize(raw.cover ?? raw.coverUrl ?? raw.imgurl),
  artist:
    raw.artistName ??
    raw.artist?.name ??
    raw.artists?.map((artist: { name: string }) => artist.name).join(" / "),
  playCount: raw.playCount,
  duration: raw.duration ?? raw.playTime,
});

/** 收藏播客（/dj/sublist 元素）→ 轻量展示模型 */
export const toUserDjFavorite = (raw: any): UserRadioFavorite => ({
  id: String(raw.id),
  name: raw.name ?? raw.title ?? "",
  cover: withPicSize(raw.picUrl ?? raw.coverUrl ?? raw.cover),
  creator: raw.dj?.nickname ?? raw.creator?.nickname ?? raw.creator?.name,
  programCount: raw.programCount,
  subCount: raw.subCount,
});

/** 订阅计数（/user/subcount）→ 应用层 UserSubcount */
export const toSubcount = (raw: any): UserSubcount => ({
  createdPlaylistCount: raw.createdPlaylistCount ?? 0,
  subPlaylistCount: raw.subPlaylistCount ?? 0,
  artistCount: raw.artistCount ?? 0,
  mvCount: raw.mvCount ?? 0,
  djRadioCount: raw.djRadioCount ?? 0,
});
