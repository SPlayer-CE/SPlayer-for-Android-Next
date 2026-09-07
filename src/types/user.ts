/**
 * 用户登录相关类型
 */

/** 用户基础资料 */
export interface UserProfile {
  userId: number;
  nickname: string;
  avatarUrl?: string;
  backgroundUrl?: string;
  signature?: string;
  /** 0=普通，非 0=黑胶 VIP */
  vipType?: number;
  gender?: number;
  province?: number;
  city?: number;
}

/** 用户订阅计数（/user/subcount） */
export interface UserSubcount {
  /** 自建歌单数 */
  createdPlaylistCount: number;
  /** 收藏歌单数 */
  subPlaylistCount: number;
  /** 收藏歌手数 */
  artistCount: number;
  /** 收藏 MV 数 */
  mvCount: number;
  /** 收藏播客数 */
  djRadioCount: number;
}

/** 用户收藏视频 */
export interface UserVideoFavorite {
  /** 视频 ID */
  id: string;
  /** 标题 */
  name: string;
  /** 封面 */
  cover?: string;
  /** 艺人 */
  artist?: string;
  /** 播放次数 */
  playCount?: number;
  /** 时长（毫秒） */
  duration?: number;
}

/** 用户收藏播客 */
export interface UserRadioFavorite {
  /** 播客 ID */
  id: string;
  /** 标题 */
  name: string;
  /** 封面 */
  cover?: string;
  /** 主播 */
  creator?: string;
  /** 节目数 */
  programCount?: number;
  /** 订阅数 */
  subCount?: number;
}
