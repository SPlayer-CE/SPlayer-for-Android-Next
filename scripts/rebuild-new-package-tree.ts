import { readdir, readFile, writeFile, mkdir, rm } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const currentFile = fileURLToPath(import.meta.url);
const rootDir = path.resolve(path.dirname(currentFile), "..");
const oldRoot = path.join(
  rootDir,
  "android",
  "app",
  "src",
  "main",
  "java",
  "top",
  "imsyy",
  "splayer",
  "android",
);
const newRoot = path.join(
  rootDir,
  "android",
  "app",
  "src",
  "main",
  "java",
  "top",
  "imsyy",
  "splayer_next",
  "android",
);

async function walk(dir: string, base: string): Promise<string[]> {
  const entries = await readdir(dir, { withFileTypes: true });
  const files: string[] = [];
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await walk(full, base)));
    } else if (entry.name.endsWith(".java")) {
      files.push(path.relative(base, full));
    }
  }
  return files;
}

// 1. Remove new package tree
await rm(newRoot, { recursive: true, force: true });

// 2. Copy from old tree, replacing package names
const files = await walk(oldRoot, oldRoot);
for (const rel of files) {
  const src = path.join(oldRoot, rel);
  const dest = path.join(newRoot, rel);
  await mkdir(path.dirname(dest), { recursive: true });

  let content = await readFile(src, "utf-8");
  // Replace package and import references
  content = content.replace(/top\.imsyy\.splayer\.android/g, "top.imsyy.splayer_next.android");
  // Also handle the package declaration line specifically to be safe
  content = content.replace(
    /package top\.imsyy\.splayer_next\.android(?:(\.[a-zA-Z0-9_]+)+)?;/g,
    (match) => match, // already correct
  );

  await writeFile(dest, content, "utf-8");
}

console.log(`Rebuilt ${files.length} files from old package tree to new package tree.`);
