import { readdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const currentFile = fileURLToPath(import.meta.url);
const rootDir = path.resolve(path.dirname(currentFile), "..");
const javaRoot = path.join(rootDir, "android", "app", "src", "main", "java");

const walk = async (dir: string): Promise<string[]> => {
  const entries = await readdir(dir, { withFileTypes: true });
  const files: string[] = [];
  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await walk(fullPath)));
    } else if (entry.isFile() && entry.name.endsWith(".java")) {
      files.push(fullPath);
    }
  }
  return files;
};

const decodeGbk = (buf: Buffer): string => {
  // Use Windows-936 (GBK) decoder via Node's internal iconv for common cases.
  // Node's Buffer.toString('latin1') preserves bytes, then we can use TextDecoder if available.
  // On Node 20+, TextDecoder supports 'gbk'/'gb18030'.
  try {
    const decoder = new TextDecoder("gbk", { fatal: true });
    return decoder.decode(buf);
  } catch {
    // fallback: try utf-8
    return buf.toString("utf-8");
  }
};

const javaFiles = await walk(javaRoot);
let converted = 0;
let skipped = 0;

for (const file of javaFiles) {
  const buf = await readFile(file);
  // Check if already valid UTF-8 without replacement chars
  const utf8Str = buf.toString("utf-8");
  if (!utf8Str.includes("\uFFFD")) {
    skipped++;
    continue;
  }
  // Decode as GBK and rewrite as UTF-8
  const gbkStr = decodeGbk(buf);
  await writeFile(file, gbkStr, "utf-8");
  converted++;
  console.log("Converted:", file);
}

console.log(
  `Java encoding fix done: ${converted} converted, ${skipped} already UTF-8, total ${javaFiles.length}`,
);
