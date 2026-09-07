/**
 * stream/promises 兼容层
 *
 * 与 fsCompat 同理：nodejs-mobile 旧运行时不支持 `stream/promises`
 * 子路径（Node 15+ 才有）。改用回调式 stream.pipeline + util.promisify，
 * util.promisify 自 Node 8 起可用。
 */

import { pipeline as pipelineCb } from "stream";
import { promisify } from "util";

export const pipeline = promisify(pipelineCb);
