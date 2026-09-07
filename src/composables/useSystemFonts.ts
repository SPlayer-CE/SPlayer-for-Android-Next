/**
 * 读取系统已安装字体，并在 Android 上通过 FontFace 注册导入的字体到 WebView
 */

/** 已导入字体数据（来自原生插件） */
interface ImportedFontData {
  name: string;
  base64: string;
  format: string;
}

/** 系统字体列表 */
const families = ref<string[]>([]);
/** 是否正在加载字体列表 */
const loading = ref(false);
/** 进行中的拉取任务 */
let pending: Promise<void> | null = null;
/** 已注册到 document.fonts 的字体名集合，避免重复注册 */
const registeredFonts = new Set<string>();

/** 将 base64 字体数据通过 FontFace 注册到 WebView */
const registerFontFace = async (data: ImportedFontData): Promise<void> => {
  if (registeredFonts.has(data.name)) return;
  const mimeType = data.format === "otf" ? "font/otf" : "font/ttf";
  const face = new FontFace(data.name, `url(data:${mimeType};base64,${data.base64})`);
  try {
    await face.load();
    document.fonts.add(face);
    registeredFonts.add(data.name);
  } catch (e) {
    console.warn(`[fonts] Failed to load font "${data.name}"`, e);
  }
};

/** 拉取系统字体并去重排序 */
const fetchFamilies = async (): Promise<string[]> => {
  const list = await window.api.system.listFonts();
  const unique = Array.from(new Set(list.filter((f) => f.trim().length > 0)));
  unique.sort((a, b) => a.localeCompare(b, "zh-CN"));
  return unique;
};

/** 加载已导入字体并注册到 WebView（仅 Android） */
const loadImportedFonts = async (): Promise<void> => {
  if (typeof window.api.system.readImportedFonts !== "function") return;
  try {
    const fonts = await window.api.system.readImportedFonts();
    await Promise.all(fonts.map(registerFontFace));
  } catch (e) {
    console.warn("[fonts] readImportedFonts failed", e);
  }
};

/** 导入字体文件 (仅 Android) */
export const importFont = async (): Promise<string[] | null> => {
  if (typeof window.api.system.importFont !== "function") {
    return null;
  }
  const result = await window.api.system.importFont();

  // 新格式：带 base64 数据，注册到 WebView
  if (result.success && result.fontData) {
    await Promise.all(result.fontData.map(registerFontFace));
    for (const font of result.fontData) {
      if (!families.value.includes(font.name)) {
        families.value.push(font.name);
      }
    }
    families.value.sort((a, b) => a.localeCompare(b, "zh-CN"));
    return result.fontData.map((f) => f.name);
  }

  // 兼容旧格式：仅返回名称
  if (result.success && result.fontNames) {
    for (const name of result.fontNames) {
      if (!families.value.includes(name)) {
        families.value.push(name);
      }
    }
    families.value.sort((a, b) => a.localeCompare(b, "zh-CN"));
    return result.fontNames;
  } else if (result.success && result.fontName) {
    if (!families.value.includes(result.fontName)) {
      families.value.push(result.fontName);
      families.value.sort((a, b) => a.localeCompare(b, "zh-CN"));
    }
    return [result.fontName];
  }
  return null;
};

/** 使用系统字体 */
export const useSystemFonts = () => {
  /** 仅首次拉取，之后复用缓存 */
  const ensureLoaded = (): Promise<void> => {
    if (families.value.length > 0) return Promise.resolve();
    if (pending) return pending;
    loading.value = true;
    pending = fetchFamilies()
      .then(async (list) => {
        families.value = list;
        // 加载已导入字体并注册到 WebView
        await loadImportedFonts();
      })
      .catch((err) => {
        console.error("[fonts] listFonts failed", err);
      })
      .finally(() => {
        loading.value = false;
        pending = null;
      });
    return pending;
  };

  return { families, loading, ensureLoaded, importFont };
};
