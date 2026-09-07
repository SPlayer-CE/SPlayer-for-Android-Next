/**
 * 局域网协同 — 接收端遥控解耦层。
 * core/player 经此把用户控制指令上送主机，避免直接依赖 useLanSync 造成循环引用。
 * 同时提供"正在应用主机状态"标志：镜像主机状态时置位，使 core/player 的遥控拦截放行，杜绝回环。
 */
export type LanRemoteAction =
  | "next"
  | "prev"
  | "play"
  | "pause"
  | "toggle"
  | "seek"
  | "setSpeed"
  | "setPitch"
  | "playAt"
  | "syncSeek";

type LanRemoteSink = (action: LanRemoteAction, value?: number) => void;

let sink: LanRemoteSink | null = null;
let hostActionSink: LanRemoteSink | null = null;
let applyingCount = 0;

/** 接收端注册上送命令的实现；传 null 注销 */
export const registerLanRemoteSink = (fn: LanRemoteSink | null): void => {
  sink = fn;
};

/** 主设备端注册手动操作的通知上报；传 null 注销 */
export const registerHostActionSink = (fn: LanRemoteSink | null): void => {
  hostActionSink = fn;
};

/**
 * 上送一条遥控指令到主机
 * @returns 是否已成功交给上送实现（无 sink 时为 false，调用方据此回退本地行为）
 */
export const dispatchLanRemoteCommand = (action: LanRemoteAction, value?: number): boolean => {
  if (!sink) return false;
  sink(action, value);
  return true;
};

/** 上报主设备手动触发的动作，通知到从设备 */
export const reportHostManualAction = (action: LanRemoteAction, value?: number): void => {
  if (hostActionSink) hostActionSink(action, value);
};

/** 当前是否正在应用主机推送的状态（镜像中） */
export const isApplyingRemoteState = (): boolean => applyingCount > 0;

/**
 * 在"应用主机状态"标志置位的上下文中执行 fn。
 * 支持异步函数，确保在整个异步执行期间标志位保持开启。
 */
export const withApplyingRemoteState = async <T>(fn: () => T | Promise<T>): Promise<T> => {
  applyingCount++;
  try {
    return await fn();
  } finally {
    applyingCount--;
  }
};
