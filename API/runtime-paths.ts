import { existsSync } from "fs";
import path from "path";

const currentFilePath =
  typeof __filename !== "undefined"
    ? __filename
    : path.resolve(process.cwd(), "API", "runtime-paths.ts");

export const currentDirPath = path.dirname(currentFilePath);

const packagedNeteaseApiRoot = path.join(currentDirPath, "vendor", "netease-api");
const sourceNeteaseApiRoot = path.resolve(
  currentDirPath,
  "..",
  "node_modules",
  "@neteasecloudmusicapienhanced",
  "api",
);

export const embeddedApiVendorRoot = existsSync(packagedNeteaseApiRoot)
  ? packagedNeteaseApiRoot
  : sourceNeteaseApiRoot;

export const embeddedApiMainEntry = path.join(embeddedApiVendorRoot, "main.js");

export const embeddedDataDir =
  process.env["SP_CONFIG_DIR"] || path.resolve(currentDirPath, "..", "..", "splayer-data");

export const settingsFilePath = path.join(embeddedDataDir, "settings.json");
export const statsFilePath = path.join(embeddedDataDir, "stats.json");
export const pluginsRootDir = path.join(embeddedDataDir, "plugins");
export const pluginsScriptsDir = path.join(pluginsRootDir, "scripts");
export const pluginsDataDir = path.join(pluginsRootDir, "data");
export const pluginsManifestFilePath = path.join(pluginsRootDir, "manifest.json");
