/**
 * 歌曲动态封面
 *
 * params:
 * - id 歌曲 id
 *
 * 仅部分歌曲返回 videoPlayUrl，未登录或无动态封面时 data 为空对象。
 */

import { createOption } from "../core/option";
import type { NeteaseModule } from "../core/types";

const song_dynamic_cover: NeteaseModule = (query, request) => {
  const data = {
    songId: query.id,
  };
  return request("/api/songplay/dynamic-cover", data, createOption(query, "eapi"));
};

export default song_dynamic_cover;
