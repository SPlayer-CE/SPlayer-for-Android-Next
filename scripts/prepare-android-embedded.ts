import { cp, mkdir, readFile, readdir, realpath, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { gunzipSync } from "node:zlib";

const currentFile = fileURLToPath(import.meta.url);
const rootDir = path.resolve(path.dirname(currentFile), "..");

const pluginRoot = await realpath(path.join(rootDir, "node_modules", "nodejs-mobile-cordova"));
const androidRoot = path.join(rootDir, "android");
const generatedPluginRoot = path.join(androidRoot, "capacitor-cordova-android-plugins");
const generatedBridgeRoot = path.join(
  generatedPluginRoot,
  "src",
  "main",
  "libs",
  "cdvnodejsmobile",
);
const pluginLibnodeRoot = path.join(pluginRoot, "libs", "android", "libnode");
const pluginAssetsRoot = path.join(pluginRoot, "install", "nodejs-mobile-cordova-assets");
const embeddedNodeProjectRoot = path.join(rootDir, "dist", "capacitor", "nodejs-project");
const appAssetsRoot = path.join(androidRoot, "app", "src", "main", "assets");
const appAssetProjectRoot = path.join(appAssetsRoot, "www", "nodejs-project");
const embeddedVendorRoot = path.join(embeddedNodeProjectRoot, "vendor");
const appAssetVendorRoot = path.join(appAssetProjectRoot, "vendor");
const embeddedVendorNodeModulesRoot = path.join(embeddedVendorRoot, "node_modules");
const appAssetVendorNodeModulesRoot = path.join(appAssetVendorRoot, "node_modules");
const appPluginAssetsRoot = path.join(appAssetsRoot, "nodejs-mobile-cordova-assets");
const generatedNodeJsFile = path.join(
  generatedPluginRoot,
  "src",
  "main",
  "java",
  "com",
  "janeasystems",
  "cdvnodejsmobile",
  "NodeJS.java",
);

const targetRoots = [
  path.join(androidRoot, "app", "libs", "cdvnodejsmobile"),
  path.join(generatedPluginRoot, "libs", "cdvnodejsmobile"),
];

const ensureNodeJsPatched = async () => {
  const original = await readFile(generatedNodeJsFile, "utf8");
  const patched = original.replace(
    "if (BuildConfig.DEBUG) {",
    "if (Log.isLoggable(LOGTAG, Log.DEBUG)) {",
  );
  if (patched !== original) {
    await writeFile(generatedNodeJsFile, patched, "utf8");
  }
};

const inflateLibnodeBinaries = async (targetRoot: string) => {
  const abiDirs = await realpath(path.join(targetRoot, "libnode", "bin")).catch(() => "");
  if (!abiDirs) return;

  for (const abi of ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]) {
    const gzPath = path.join(targetRoot, "libnode", "bin", abi, "libnode.so.gz");
    const soPath = path.join(targetRoot, "libnode", "bin", abi, "libnode.so");
    const gzBuffer = await readFile(gzPath);
    await writeFile(soPath, gunzipSync(gzBuffer));
  }
};

const syncDirectoryContents = async (sourceRoot: string, targetRoot: string) => {
  await mkdir(targetRoot, { recursive: true });
  const entries = await readdir(sourceRoot, { withFileTypes: true });

  for (const entry of entries) {
    const sourcePath = path.join(sourceRoot, entry.name);
    const targetPath = path.join(targetRoot, entry.name);
    await rm(targetPath, { recursive: true, force: true });
    await cp(sourcePath, targetPath, {
      recursive: true,
      dereference: false,
      verbatimSymlinks: true,
    });
  }
};

const syncEmbeddedNodeAssets = async () => {
  await mkdir(path.join(appAssetsRoot, "www"), { recursive: true });
  await rm(appAssetProjectRoot, { recursive: true, force: true });
  await mkdir(appAssetProjectRoot, { recursive: true });
  await cp(
    path.join(embeddedNodeProjectRoot, "main.js"),
    path.join(appAssetProjectRoot, "main.js"),
  );
  await cp(
    path.join(embeddedNodeProjectRoot, "package.json"),
    path.join(appAssetProjectRoot, "package.json"),
  );
  await rm(appAssetVendorRoot, { recursive: true, force: true });
  await syncDirectoryContents(embeddedVendorRoot, appAssetVendorRoot);
  await syncDirectoryContents(embeddedVendorNodeModulesRoot, appAssetVendorNodeModulesRoot);

  // SPA 静态资源：将 cap sync 落到 assets/public 的网页拷入 node 项目 web/，
  // 供嵌入式 server 在局域网内提供同步页面（浏览器接入即成为从设备）
  const spaSourceRoot = path.join(appAssetsRoot, "public");
  const spaTargetRoot = path.join(appAssetProjectRoot, "web");
  await rm(spaTargetRoot, { recursive: true, force: true });
  try {
    await cp(spaSourceRoot, spaTargetRoot, {
      recursive: true,
      dereference: false,
      verbatimSymlinks: true,
    });
  } catch (error) {
    console.warn(
      `[prepare-android-embedded] 未找到 SPA 资源，跳过 web/ 拷贝: ${spaSourceRoot}`,
      error,
    );
  }

  await rm(appPluginAssetsRoot, { recursive: true, force: true });
  await cp(pluginAssetsRoot, appPluginAssetsRoot, {
    recursive: true,
    dereference: false,
    verbatimSymlinks: true,
  });
};

for (const targetRoot of targetRoots) {
  await mkdir(path.dirname(targetRoot), { recursive: true });
  await rm(targetRoot, { recursive: true, force: true });
  await cp(generatedBridgeRoot, targetRoot, {
    recursive: true,
    dereference: false,
    verbatimSymlinks: true,
  });
  await rm(path.join(targetRoot, "libnode"), { recursive: true, force: true });
  await cp(pluginLibnodeRoot, path.join(targetRoot, "libnode"), {
    recursive: true,
    dereference: false,
    verbatimSymlinks: true,
  });
  await inflateLibnodeBinaries(targetRoot);
}

await ensureNodeJsPatched();
await syncEmbeddedNodeAssets();

// 清除 Gradle 的 merged assets 缓存，强制下次 assembleDebug 重新打包 assets 目录
const gradleMergedAssets = path.join(androidRoot, "app", "build", "intermediates", "merged_assets");
await rm(gradleMergedAssets, { recursive: true, force: true });

console.log("Prepared Android embedded nodejs-mobile assets.");
