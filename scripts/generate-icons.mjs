// 生成 Android 应用图标。
// 输入：仓库根目录上一级的 SPA-CE-favicon-512×512-2.png
// 输出：android/app/src/main/res/mipmap-*/
// - ic_launcher_foreground.png  —— 自适应图标前景（75% 居中 logo，透明底）
// - ic_launcher.png              —— legacy 方形（浅色底 + 22% 圆角 + 75% logo）
// - ic_launcher_round.png        —— legacy 圆形（浅色底 + 75% logo）

import sharp from "sharp";
import { mkdir, writeFile } from "node:fs/promises";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(__dirname, "..");
const SRC = resolve(REPO_ROOT, "..", "SPA-CE-favicon-512×512-2.png");
const RES = resolve(REPO_ROOT, "android/app/src/main/res");

const LIGHT_BG = "#dcefdf";

const DENSITIES = {
  mdpi: { launcher: 48, foreground: 108 },
  hdpi: { launcher: 72, foreground: 162 },
  xhdpi: { launcher: 96, foreground: 216 },
  xxhdpi: { launcher: 144, foreground: 324 },
  xxxhdpi: { launcher: 192, foreground: 432 },
};

const LOGO_RATIO = 0.75;
const CORNER_RATIO = 0.22;

async function resizeLogo(size) {
  return sharp(SRC)
    .resize(size, size, { fit: "contain", background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .png()
    .toBuffer();
}

async function foreground(canvasSize) {
  const logoSize = Math.round(canvasSize * LOGO_RATIO);
  const logo = await resizeLogo(logoSize);
  return sharp({
    create: {
      width: canvasSize,
      height: canvasSize,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    },
  })
    .composite([{ input: logo, gravity: "center" }])
    .png()
    .toBuffer();
}

async function legacySquare(size) {
  const r = Math.round(size * CORNER_RATIO);
  const svg = Buffer.from(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}">
       <rect x="0" y="0" width="${size}" height="${size}" rx="${r}" ry="${r}" fill="${LIGHT_BG}"/>
     </svg>`,
  );
  const logoSize = Math.round(size * LOGO_RATIO);
  const logo = await resizeLogo(logoSize);
  return sharp(svg)
    .composite([{ input: logo, gravity: "center" }])
    .png()
    .toBuffer();
}

async function legacyRound(size) {
  const svg = Buffer.from(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}">
       <circle cx="${size / 2}" cy="${size / 2}" r="${size / 2}" fill="${LIGHT_BG}"/>
     </svg>`,
  );
  const logoSize = Math.round(size * LOGO_RATIO);
  const logo = await resizeLogo(logoSize);
  return sharp(svg)
    .composite([{ input: logo, gravity: "center" }])
    .png()
    .toBuffer();
}

async function main() {
  for (const [density, { launcher, foreground: fg }] of Object.entries(DENSITIES)) {
    const dir = join(RES, `mipmap-${density}`);
    await mkdir(dir, { recursive: true });

    const [fgBuf, sqBuf, rdBuf] = await Promise.all([
      foreground(fg),
      legacySquare(launcher),
      legacyRound(launcher),
    ]);

    await Promise.all([
      writeFile(join(dir, "ic_launcher_foreground.png"), fgBuf),
      writeFile(join(dir, "ic_launcher.png"), sqBuf),
      writeFile(join(dir, "ic_launcher_round.png"), rdBuf),
    ]);

    console.log(`  [${density}] foreground=${fg}px launcher=${launcher}px ✓`);
  }
  console.log("done.");
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
