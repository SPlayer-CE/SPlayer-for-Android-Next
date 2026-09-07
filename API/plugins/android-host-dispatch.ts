import type { HostCallMethod, PluginGrant } from "../../shared/types/plugin";
import { PluginErrorCodes } from "../../shared/defaults/plugin-api";
import { hostRequest } from "./android-net";
import {
  pluginStorageGet,
  pluginStorageKeys,
  pluginStorageRemove,
  pluginStorageSet,
} from "./android-storage";

export const dispatchAndroidHostCall = async (
  pluginId: string,
  grant: PluginGrant[],
  method: HostCallMethod,
  args: unknown[],
): Promise<unknown> => {
  if (method === "request" && !grant.includes("network")) {
    throw Object.assign(new Error(`plugin "${pluginId}" lacks "network" grant`), {
      code: PluginErrorCodes.PERMISSION_DENIED,
    });
  }
  if (method.startsWith("player.")) {
    if (!grant.includes("control")) {
      throw Object.assign(new Error(`plugin "${pluginId}" lacks "control" grant`), {
        code: PluginErrorCodes.PERMISSION_DENIED,
      });
    }
    throw Object.assign(new Error("Android 第一阶段暂不支持 control 类 player.* 宿主调用"), {
      code: PluginErrorCodes.UNKNOWN,
    });
  }

  switch (method) {
    case "request":
      return hostRequest(String(args[0] ?? ""), (args[1] ?? {}) as Record<string, unknown>);
    case "storage.get":
      return pluginStorageGet(pluginId, String(args[0] ?? ""));
    case "storage.set":
      pluginStorageSet(pluginId, String(args[0] ?? ""), args[1]);
      return undefined;
    case "storage.remove":
      pluginStorageRemove(pluginId, String(args[0] ?? ""));
      return undefined;
    case "storage.keys":
      return pluginStorageKeys(pluginId);
    default:
      throw Object.assign(new Error(`unknown host method: ${method}`), {
        code: PluginErrorCodes.UNKNOWN,
      });
  }
};
