import { access, cp, mkdir, readFile, realpath, rm, stat, writeFile } from "node:fs/promises";
import { builtinModules } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";
// esbuild 类型在 pnpm 严格提升下有时不可见，不影响实际构建
import { build, transform } from "esbuild";

const currentFile = fileURLToPath(import.meta.url);
const rootDir = path.resolve(path.dirname(currentFile), "..");
const outDir = path.join(rootDir, "dist", "capacitor", "nodejs-project");
const vendorOutDir = path.join(outDir, "vendor", "netease-api");
const vendorNodeModulesOutDir = path.join(outDir, "vendor", "node_modules");
const neteaseApiRoot = await realpath(
  path.join(rootDir, "node_modules", "@neteasecloudmusicapienhanced", "api"),
);
const copiedRuntimePackages = new Set<string>();
// 嵌入式运行时是 Node 12，而 jsdom 要求 Node >= 18；厂商包内唯一使用方
// register_checktoken_v2 在下面被替换为本地桩实现，因此这里也跳过复制
const excludedRuntimePackages = new Set<string>(["jsdom"]);
const builtinModuleSet = new Set(
  builtinModules.flatMap((name) => [name, name.replace(/^node:/, "")]),
);
const aliasEntries = [{ prefix: "@shared/", targetDir: path.join(rootDir, "shared") }];

const resolveAliasFilePath = async (targetPath: string): Promise<string> => {
  const candidates = [
    targetPath,
    `${targetPath}.ts`,
    `${targetPath}.tsx`,
    `${targetPath}.js`,
    `${targetPath}.mjs`,
    `${targetPath}.cjs`,
    path.join(targetPath, "index.ts"),
    path.join(targetPath, "index.tsx"),
    path.join(targetPath, "index.js"),
    path.join(targetPath, "index.mjs"),
    path.join(targetPath, "index.cjs"),
  ];

  // access() 对目录也会成功（Windows 尤其如此），必须用 stat 校验是文件，
  // 否则目录导入（如 @main/store）会把目录本体交给 esbuild 当文件读而报错
  for (const candidate of candidates) {
    try {
      if ((await stat(candidate)).isFile()) return candidate;
    } catch {
      /* try next */
    }
  }

  return targetPath;
};

const transpileJavaScriptTree = async (rootPath: string) => {
  const stack = [rootPath];

  while (stack.length > 0) {
    const currentPath = stack.pop()!;
    const entries = await import("node:fs/promises").then(({ readdir }) =>
      readdir(currentPath, { withFileTypes: true }),
    );

    for (const entry of entries) {
      const entryPath = path.join(currentPath, entry.name);
      if (entry.isDirectory()) {
        stack.push(entryPath);
        continue;
      }

      if (!entry.isFile() || !entry.name.endsWith(".js")) continue;

      const source = await readFile(entryPath, "utf8");
      try {
        const result = await transform(source, {
          loader: "js",
          format: "cjs",
          target: "es2019",
          sourcemap: false,
          minify: true,
        });
        await writeFile(entryPath, result.code, "utf8");
      } catch (error) {
        console.warn(`Skip transpiling ${entryPath}:`, error);
      }
    }
  }
};

const patchNeteaseVendor = async (rootPath: string) => {
  // login_qr_check.js：catch 块引用了 try 块的块级变量 result，导致 ReferenceError。
  const loginQrCheckPath = path.join(rootPath, "module", "login_qr_check.js");
  const loginQrCheckSource = await readFile(loginQrCheckPath, "utf8");
  // 用 `body: {}`（catch 块空对象）作为锚点确保只修补 catch 块的 cookie 引用。
  const loginQrCheckPatched = loginQrCheckSource.replace(
    /(body:\s*\{\},\s*cookie:\s*)result\.cookie/,
    "$1error && Array.isArray(error.cookie) ? error.cookie : []",
  );
  if (loginQrCheckPatched !== loginQrCheckSource) {
    await writeFile(loginQrCheckPath, loginQrCheckPatched, "utf8");
  }

  // playlist_tracks.js：成功路径 return { status: 200, body: { ...res } } 多包了一层，
  // 导致 callNeteaseApi 提取 body 后拿不到 code 字段，ensureOk 误判为失败。
  // 改为 return res 与其他模块一致。
  const playlistTracksPath = path.join(rootPath, "module", "playlist_tracks.js");
  const playlistTracksSource = await readFile(playlistTracksPath, "utf8");
  const playlistTracksPatched = playlistTracksSource.replace(
    /return\s*\{\s*status:\s*200,\s*body:\s*\{\s*\.\.\.res\s*,?\s*\}\s*,?\s*\}/,
    "return res",
  );
  if (playlistTracksPatched !== playlistTracksSource) {
    await writeFile(playlistTracksPath, playlistTracksPatched, "utf8");
  }

  // register_checktoken_v2.js：靠 jsdom 跑易盾 watchman 实时取反作弊 token，而 jsdom 要求
  // Node >= 18 —— 嵌入式 Node 12 上它只会抛 `performance is not defined` 返回空串。
  // 空串会被塞进 X-antiCheatToken（playlist_subscribe 强制 checkToken='v2'），
  // 因此整体替换为回退配置内置静态 token 的桩，行为对齐 4.34.3。
  await writeFile(
    path.join(rootPath, "module", "register_checktoken_v2.js"),
    `// Android 嵌入式替代实现：Node 12 跑不了 jsdom，回退配置内置的静态 token
const { APP_CONF } = require('../util/config.json')

const readToken = () => APP_CONF.checkToken || ''

module.exports = async () => {
  const token = readToken()
  return { status: 200, body: { code: 200, token, registered: !!token } }
}

module.exports.getToken = async () => readToken()
`,
    "utf8",
  );

  // generateConfig.js：register_anonimous 现在走 xeapi，要求 xeapi public key 已存在，
  // 但原顺序是先注册匿名再取 key，首次冷启动必然抛 xeapi public key is missing。
  // 把取 key 的 try 块整体前移到注册块之前。
  const generateConfigPath = path.join(rootPath, "generateConfig.js");
  const generateConfigSource = await readFile(generateConfigPath, "utf8");
  const generateConfigLines = generateConfigSource.split(/\r?\n/);
  const anonymousBlockStart = generateConfigLines.findIndex((line) =>
    line.includes("await register_anonimous()"),
  );
  const xeapiBlockStart = generateConfigLines.findIndex((line) =>
    line.includes("let currentPublicKey = {}"),
  );
  const functionEnd = generateConfigLines.indexOf("}", xeapiBlockStart);

  if (
    anonymousBlockStart > 0 &&
    xeapiBlockStart > anonymousBlockStart &&
    functionEnd > xeapiBlockStart
  ) {
    // findIndex 命中的是块内首行，`try {` 在它上面一行
    const reorderedLines = [
      ...generateConfigLines.slice(0, anonymousBlockStart - 1),
      ...generateConfigLines.slice(xeapiBlockStart - 1, functionEnd),
      ...generateConfigLines.slice(anonymousBlockStart - 1, xeapiBlockStart - 1),
      ...generateConfigLines.slice(functionEnd),
    ];
    // 前移后取公钥发生在 register_anonimous 之前，而 global.deviceId 只在 register_anonimous
    // 内部才被赋值；deviceId 为空时 key/get 会返回不含 sk 的响应，公钥文件写不进去，本会话所有
    // xeapi 接口（含 song/url/v1）都会抛 xeapi public key is missing 导致播放失败，
    // 因此取公钥前先兜底生成一个 deviceId（register_anonimous 之后会按自身逻辑覆盖）。
    const reorderedSource = reorderedLines.join("\n");
    const deviceIdImportAnchor =
      "const { cookieToJson, generateRandomChineseIP } = require('./util/index')";
    const publicKeyAnchor =
      "const publicKey = await getXeapiPublicKey(currentPublicKey, global.deviceId)";
    if (
      !reorderedSource.includes(deviceIdImportAnchor) ||
      !reorderedSource.includes(publicKeyAnchor)
    ) {
      throw new Error("generateConfig.js 结构变更，deviceId 兜底补丁无法应用");
    }
    await writeFile(
      generateConfigPath,
      reorderedSource
        .replace(
          deviceIdImportAnchor,
          "const { cookieToJson, generateRandomChineseIP, generateDeviceId } = require('./util/index')",
        )
        .replace(
          publicKeyAnchor,
          "if (!global.deviceId) global.deviceId = generateDeviceId()\n    const publicKey = await getXeapiPublicKey(currentPublicKey, global.deviceId)",
        ),
      "utf8",
    );
  }
};

const resolveDependencyPackageJsonPath = async (
  packageName: string,
  searchFromPackageJsonPath: string,
) => {
  let currentDir = path.dirname(searchFromPackageJsonPath);

  while (true) {
    const candidatePackageJsonPath = path.join(
      currentDir,
      "node_modules",
      ...packageName.split("/"),
      "package.json",
    );

    try {
      await access(candidatePackageJsonPath);
      return await realpath(candidatePackageJsonPath);
    } catch {
      const parentDir = path.dirname(currentDir);
      if (parentDir === currentDir) {
        throw new Error(
          `Unable to resolve dependency ${packageName} from ${searchFromPackageJsonPath}`,
        );
      }
      currentDir = parentDir;
    }
  }
};

const copyRuntimePackage = async (packageName: string, searchFromPackageJsonPath: string) => {
  if (builtinModuleSet.has(packageName)) return;
  if (excludedRuntimePackages.has(packageName)) return;
  if (copiedRuntimePackages.has(packageName)) return;

  const packageJsonPath = await resolveDependencyPackageJsonPath(
    packageName,
    searchFromPackageJsonPath,
  );
  const packageRoot = path.dirname(packageJsonPath);
  const packageTargetRoot = path.join(vendorNodeModulesOutDir, ...packageName.split("/"));
  const packageJson = JSON.parse(await readFile(packageJsonPath, "utf8")) as {
    dependencies?: Record<string, string>;
    optionalDependencies?: Record<string, string>;
  };

  copiedRuntimePackages.add(packageName);

  await mkdir(path.dirname(packageTargetRoot), { recursive: true });
  await rm(packageTargetRoot, { recursive: true, force: true });
  await cp(packageRoot, packageTargetRoot, { recursive: true });
  await rm(path.join(packageTargetRoot, "node_modules"), { recursive: true, force: true });
  await transpileJavaScriptTree(packageTargetRoot);

  const runtimeDeps = new Set([
    ...Object.keys(packageJson.dependencies ?? {}),
    ...Object.keys(packageJson.optionalDependencies ?? {}),
  ]);

  for (const dependencyName of runtimeDeps) {
    await copyRuntimePackage(dependencyName, packageJsonPath);
  }
};

await rm(outDir, { recursive: true, force: true });
await mkdir(outDir, { recursive: true });
await mkdir(path.dirname(vendorOutDir), { recursive: true });

await build({
  entryPoints: [path.join(rootDir, "API", "mobile-entry.ts")],
  outfile: path.join(outDir, "main.js"),
  bundle: true,
  format: "cjs",
  platform: "node",
  target: ["es2019"],
  sourcemap: false,
  minify: true,
  plugins: [
    {
      name: "resolve-ts-path-aliases",
      setup(buildContext) {
        for (const alias of aliasEntries) {
          const filter = new RegExp(`^${alias.prefix.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}`);
          buildContext.onResolve({ filter }, async (args) => ({
            path: await resolveAliasFilePath(
              path.join(alias.targetDir, args.path.slice(alias.prefix.length)),
            ),
          }));
        }
      },
    },
    {
      name: "strip-node-prefix",
      setup(buildContext) {
        buildContext.onResolve({ filter: /^node:/ }, (args) => ({
          path: args.path.slice(5),
          external: true,
        }));
      },
    },
  ],
  banner: {
    js: "process.chdir(__dirname);",
  },
});

const mainBundlePath = path.join(outDir, "main.js");
const mainBundleSource = await readFile(mainBundlePath, "utf8");
const normalizedBundleSource = mainBundleSource
  .replace(/require\(("|')node:/g, "require($1")
  .replace(/__require\(("|')node:/g, "__require($1");

if (normalizedBundleSource !== mainBundleSource) {
  await writeFile(mainBundlePath, normalizedBundleSource, "utf8");
}

await rm(vendorOutDir, { recursive: true, force: true });
await cp(neteaseApiRoot, vendorOutDir, { recursive: true });
await rm(path.join(vendorOutDir, "node_modules"), { recursive: true, force: true });
await patchNeteaseVendor(vendorOutDir);
await transpileJavaScriptTree(vendorOutDir);
await rm(vendorNodeModulesOutDir, { recursive: true, force: true });
await mkdir(vendorNodeModulesOutDir, { recursive: true });

const neteaseApiPackageJsonPath = path.join(neteaseApiRoot, "package.json");
const neteaseApiPackageJson = JSON.parse(await readFile(neteaseApiPackageJsonPath, "utf8")) as {
  dependencies?: Record<string, string>;
  optionalDependencies?: Record<string, string>;
};
const neteaseApiRuntimeDeps = new Set([
  ...Object.keys(neteaseApiPackageJson.dependencies ?? {}),
  ...Object.keys(neteaseApiPackageJson.optionalDependencies ?? {}),
]);

for (const dependencyName of neteaseApiRuntimeDeps) {
  await copyRuntimePackage(dependencyName, neteaseApiPackageJsonPath);
}

await writeFile(
  path.join(outDir, "package.json"),
  JSON.stringify(
    {
      name: "splayer-embedded-api",
      private: true,
      main: "main.js",
    },
    null,
    2,
  ) + "\n",
  "utf8",
);
