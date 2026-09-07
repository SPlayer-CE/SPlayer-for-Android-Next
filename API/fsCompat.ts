/**
 * fs/promises 兼容层
 *
 * nodejs-mobile 携带的 libnode 运行时较旧（约 Node 12 级别），
 * `require("fs/promises")` 在该运行时下会抛出 MODULE_NOT_FOUND，
 * 进而导致整个嵌入式 API 服务在加载阶段崩溃（端口 1698 永不监听）。
 *
 * 这里用回调式 fs + util.promisify 复刻 songCache 实际使用到的
 * promise 化子集，保持调用处写法不变。util.promisify 自 Node 8 起可用。
 */

import {
  open as openCb,
  close as closeCb,
  read as readCb,
  mkdir as mkdirCb,
  stat as statCb,
  unlink as unlinkCb,
  rename as renameCb,
  type Stats,
} from "fs";
import { promisify } from "util";

const openAsync = promisify(openCb);
const closeAsync = promisify(closeCb);
const mkdirAsync = promisify(mkdirCb);
const statAsync = promisify(statCb);
const unlinkAsync = promisify(unlinkCb);
const renameAsync = promisify(renameCb);

export interface FileHandle {
  fd: number;
  read<TBuffer extends ArrayBufferView>(
    buffer: TBuffer,
    offset: number,
    length: number,
    position: number,
  ): Promise<{ bytesRead: number; buffer: TBuffer }>;
  close(): Promise<void>;
}

const open = async (path: string, flags: string): Promise<FileHandle> => {
  const fd = await openAsync(path, flags);
  return {
    fd,
    read(buffer, offset, length, position) {
      return new Promise((resolve, reject) => {
        // 使用类型断言绕过 Node 18+ fs.read 重载与泛型 TBuffer 的兼容性问题
        type ReadCallback = (
          err: NodeJS.ErrnoException | null,
          bytesRead: number,
          buffer: ArrayBufferView,
        ) => void;
        const callback: ReadCallback = (error, bytesRead, outputBuffer) => {
          if (error) {
            reject(error);
            return;
          }
          resolve({ bytesRead, buffer: outputBuffer as typeof buffer });
        };
        (
          readCb as unknown as (
            fd: number,
            buffer: ArrayBufferView,
            offset: number,
            length: number,
            position: number,
            callback: ReadCallback,
          ) => void
        )(fd, buffer as ArrayBufferView, offset, length, position, callback);
      });
    },
    async close() {
      await closeAsync(fd);
    },
  };
};

export default {
  open,
  mkdir: (path: string, options?: { recursive?: boolean }) => mkdirAsync(path, options as never),
  stat: (path: string) => statAsync(path) as Promise<Stats>,
  unlink: (path: string) => unlinkAsync(path),
  rename: (oldPath: string, newPath: string) => renameAsync(oldPath, newPath),
};
