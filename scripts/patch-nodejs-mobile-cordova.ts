import { readFile, realpath, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const currentFile = fileURLToPath(import.meta.url);
const rootDir = path.resolve(path.dirname(currentFile), "..");

const pluginLinkPath = path.join(rootDir, "node_modules", "nodejs-mobile-cordova");

let pluginRoot: string;
try {
  pluginRoot = await realpath(pluginLinkPath);
} catch {
  console.log("nodejs-mobile-cordova is not installed. Skipping patch.");
  process.exit(0);
}
const gradleFile = path.join(pluginRoot, "src", "android", "build.gradle");
const nodeJsFile = path.join(
  pluginRoot,
  "src",
  "android",
  "java",
  "com",
  "janeasystems",
  "cdvnodejsmobile",
  "NodeJS.java",
);
const original = await readFile(gradleFile, "utf8");

const projectBlock =
  "String projectWWW; // www assets folder from the Application project.\n" +
  '    if ( file("${project.projectDir}/src/main/assets/www/").exists() ) {\n' +
  "        // www folder for cordova-android >= 7\n" +
  '        projectWWW = "${project.projectDir}/src/main/assets/www";\n' +
  '    } else if (file("${project.projectDir}/assets/www/").exists()) {\n' +
  "        // www folder for cordova-android < 7\n" +
  '        projectWWW = "${project.projectDir}/assets/www";\n' +
  "    } else {\n" +
  "        throw new GradleException('nodejs-mobile-cordova couldn\\'t find the www folder in the Android project.');\n" +
  "    }";

const replacementBlock =
  "String projectWWW; // www assets folder from the Application project.\n" +
  '    if ( file("${rootProject.projectDir}/app/src/main/assets/www/").exists() ) {\n' +
  "        // www folder (contains nodejs-project for Capacitor Android)\n" +
  '        projectWWW = "${rootProject.projectDir}/app/src/main/assets/www";\n' +
  '    } else if ( file("${rootProject.projectDir}/app/src/main/assets/public/").exists() ) {\n' +
  "        // public folder for Capacitor Android\n" +
  '        projectWWW = "${rootProject.projectDir}/app/src/main/assets/public";\n' +
  '    } else if ( file("${rootProject.projectDir}/app/assets/www/").exists() ) {\n' +
  "        // www folder for cordova-android < 7\n" +
  '        projectWWW = "${rootProject.projectDir}/app/assets/www";\n' +
  '    } else if ( file("${project.projectDir}/src/main/assets/www/").exists() ) {\n' +
  "        // www folder when the plugin is evaluated in the app module\n" +
  '        projectWWW = "${project.projectDir}/src/main/assets/www";\n' +
  '    } else if ( file("${project.projectDir}/src/main/assets/public/").exists() ) {\n' +
  "        // public folder for Capacitor Android when the plugin is evaluated in the app module\n" +
  '        projectWWW = "${project.projectDir}/src/main/assets/public";\n' +
  '    } else if (file("${project.projectDir}/assets/www/").exists()) {\n' +
  "        // www folder for cordova-android < 7\n" +
  '        projectWWW = "${project.projectDir}/assets/www";\n' +
  "    } else {\n" +
  "        throw new GradleException('nodejs-mobile-cordova couldn\\'t find the www folder in the Android project.');\n" +
  "    }";

if (original.includes("contains nodejs-project for Capacitor Android")) {
  // 已经应用了最新 patch，无需重复
  console.log("nodejs-mobile-cordova build.gradle already patched with latest version.");
} else if (original.includes("public folder for Capacitor Android")) {
  // 已有旧版 patch（public 优先），需要替换为 www 优先版本
  const oldPatchBlock =
    "String projectWWW; // www assets folder from the Application project.\n" +
    '    if ( file("${rootProject.projectDir}/app/src/main/assets/public/").exists() ) {\n' +
    "        // public folder for Capacitor Android\n" +
    '        projectWWW = "${rootProject.projectDir}/app/src/main/assets/public";\n' +
    '    } else if ( file("${rootProject.projectDir}/app/src/main/assets/www/").exists() ) {\n' +
    "        // www folder for cordova-android >= 7\n" +
    '        projectWWW = "${rootProject.projectDir}/app/src/main/assets/www";\n' +
    '    } else if ( file("${rootProject.projectDir}/app/assets/www/").exists() ) {\n' +
    "        // www folder for cordova-android < 7\n" +
    '        projectWWW = "${rootProject.projectDir}/app/assets/www";\n' +
    '    } else if ( file("${project.projectDir}/src/main/assets/public/").exists() ) {\n' +
    "        // public folder for Capacitor Android when the plugin is evaluated in the app module\n" +
    '        projectWWW = "${project.projectDir}/src/main/assets/public";\n' +
    '    } else if ( file("${project.projectDir}/src/main/assets/www/").exists() ) {\n' +
    "        // www folder for cordova-android >= 7\n" +
    '        projectWWW = "${project.projectDir}/src/main/assets/www";\n' +
    '    } else if (file("${project.projectDir}/assets/www/").exists()) {\n' +
    "        // www folder for cordova-android < 7\n" +
    '        projectWWW = "${project.projectDir}/assets/www";\n' +
    "    } else {\n" +
    "        throw new GradleException('nodejs-mobile-cordova couldn\\'t find the www folder in the Android project.');\n" +
    "    }";
  const patched = original.replace(oldPatchBlock, replacementBlock);
  await writeFile(gradleFile, patched, "utf8");
} else if (original.includes("String projectWWW;")) {
  // 原始未 patch 的文件
  const patched = original.replace(projectBlock, replacementBlock);
  await writeFile(gradleFile, patched, "utf8");
}

const originalNodeJs = await readFile(nodeJsFile, "utf8");
const patchedNodeJs = originalNodeJs.replace(
  "if (BuildConfig.DEBUG) {",
  "if (Log.isLoggable(LOGTAG, Log.DEBUG)) {",
);
if (patchedNodeJs !== originalNodeJs) {
  await writeFile(nodeJsFile, patchedNodeJs, "utf8");
}

const nativeLibFile = path.join(pluginRoot, "src", "android", "jni", "native-lib.cpp");
const originalNativeLib = await readFile(nativeLibFile, "utf8");

// redirect() 函数体内部块均有缩进、结束的 "}" 顶格，惰性匹配到函数末尾即可，
// 同时覆盖未打补丁的上游原始版本与旧的行缓冲版本（无 pending 上限）
const redirectFnPattern = /void redirect\(int pipe, int log_level\) \{[\s\S]*?\n\}/;

const newRedirectBlock =
  "void redirect(int pipe, int log_level) {\n" +
  "  ssize_t redirect_size;\n" +
  "  char buf[2048];\n" +
  "  char* pending = NULL;\n" +
  "  size_t pending_len = 0;\n" +
  // 无换行的超长输出（崩溃 dump、超大单行）按 64KB 截断冲刷，native 层缓冲有界防 OOM
  "  const size_t max_pending = 64 * 1024;\n" +
  "\n" +
  "  while ((redirect_size = read(pipe, buf, sizeof buf)) > 0) {\n" +
  "    char* start = buf;\n" +
  "    char* end = buf + redirect_size;\n" +
  "    while (start < end) {\n" +
  "      char* nl = (char*)memchr(start, '\\n', (size_t)(end - start));\n" +
  "      size_t seg_len = (nl == NULL) ? (size_t)(end - start) : (size_t)(nl - start);\n" +
  "      size_t take = seg_len;\n" +
  "      if (pending_len + take > max_pending) {\n" +
  "        take = (max_pending > pending_len) ? (max_pending - pending_len) : 0;\n" +
  "      }\n" +
  "      if (take > 0) {\n" +
  "        char* grown = (char*)realloc(pending, pending_len + take + 1);\n" +
  "        if (grown == NULL) {\n" +
  "          free(pending);\n" +
  "          pending = NULL;\n" +
  "          pending_len = 0;\n" +
  "        } else {\n" +
  "          pending = grown;\n" +
  "          memcpy(pending + pending_len, start, take);\n" +
  "          pending_len += take;\n" +
  "          pending[pending_len] = 0;\n" +
  "        }\n" +
  "      }\n" +
  "      start += seg_len;\n" +
  "      if (nl != NULL) {\n" +
  "        if (pending != NULL && pending_len > 0) {\n" +
  "          __android_log_write(log_level, ADBTAG, pending);\n" +
  "        }\n" +
  "        free(pending);\n" +
  "        pending = NULL;\n" +
  "        pending_len = 0;\n" +
  "        start++;\n" +
  "      }\n" +
  "    }\n" +
  "  }\n" +
  "  if (pending != NULL && pending_len > 0) {\n" +
  "    __android_log_write(log_level, ADBTAG, pending);\n" +
  "  }\n" +
  "  free(pending);\n" +
  "}";

if (originalNativeLib.includes("max_pending")) {
  console.log("nodejs-mobile-cordova native-lib.cpp already line-buffered (bounded).");
} else if (redirectFnPattern.test(originalNativeLib)) {
  let patchedNativeLib = originalNativeLib.replace(redirectFnPattern, newRedirectBlock);
  if (!patchedNativeLib.includes("#include <string.h>")) {
    patchedNativeLib = patchedNativeLib.replace(
      "#include <string>\n",
      "#include <string>\n#include <string.h>\n",
    );
  }
  if (!patchedNativeLib.includes("#include <string.h>")) {
    console.warn(
      "nodejs-mobile-cordova native-lib.cpp has no <string> include anchor; <string.h> not injected, memchr/memcpy may fail to compile.",
    );
  }
  await writeFile(nativeLibFile, patchedNativeLib, "utf8");
} else {
  console.warn(
    "nodejs-mobile-cordova native-lib.cpp redirect() block not found; line-buffer patch skipped.",
  );
}

console.log(`Patched nodejs-mobile-cordova for Capacitor compatibility: ${pluginRoot}`);
