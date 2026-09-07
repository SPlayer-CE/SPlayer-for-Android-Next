import { execSync } from "child_process";

/** 获取当前 git 提交 */
export const getGitCommit = (): string => {
  try {
    return execSync("git rev-parse HEAD").toString().trim().slice(0, 7) || "unknown";
  } catch {
    return "unknown";
  }
};

/** 获取当前 git 提交日期 */
export const getGitDate = (): string => {
  try {
    return execSync("git log -1 --format=%cI").toString().trim() || "unknown";
  } catch {
    return "unknown";
  }
};
