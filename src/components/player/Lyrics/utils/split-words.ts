import type { LyricWord } from "@shared/types/lyrics";

const CJK_RE = /^[\p{Unified_Ideograph}\u0800-\u9FFC]+$/u;

/**
 * 判断字符串是否全部为 CJK（中日韩统一表意文字）
 */
export const isCJK = (char: string): boolean => CJK_RE.test(char);

const hasSegmenter = typeof Intl !== "undefined" && typeof Intl.Segmenter !== "undefined";

/**
 * 根据时间比例创建一个歌词原子
 * @param word - 文字内容
 * @param romanWord - 罗马音/拼音
 * @param obscene - 是否为脏话
 * @param startTime - 起始时间
 * @param endTime - 结束时间
 */
const makeAtom = (
  word: string,
  romanWord: string,
  obscene: boolean,
  startTime: number,
  endTime: number,
): LyricWord => ({ word, romanWord, startTime, endTime, obscene });

/**
 * 将歌词单词列表重新分组：CJK 字符逐字拆分，并通过 Intl.Segmenter 进行多语言分词。
 *
 * 处理流程：
 * 1. 按空格拆分每个单词，CJK 多字词再逐字拆开，按比例分配时间
 * 2. 若浏览器支持 Intl.Segmenter，将原子按词边界重新分组
 * 3. 强制合并被 Intl.Segmenter 拆开的同源 CJK 多字词，避免词典差异导致整词高光失效
 *
 * 对齐 PC 端实现：CJK 多字词（如"光芒"）拆分后会重新组合成 group，
 * 使 shouldChunkEmphasize 能对整个 group 判定，触发整体 emphasize 高光。
 * Android WebView 的 ICU 词典可能与桌面 Chromium 不同，导致"活下去"被拆成
 * 独立 chunk，使整词 emphasize 失效，因此需要强制合并同源 atom。
 *
 * @param words - 原始歌词单词数组
 * @returns 分组后的单词/单词组数组，单元素为 LyricWord，多元素组为 LyricWord[]
 */
export const chunkAndSplitLyricWords = (words: LyricWord[]): (LyricWord | LyricWord[])[] => {
  const atoms: LyricWord[] = [];
  /** 记录每个 atom 的源分组 ID（同源 CJK 多字词共享 ID，用于强制合并） */
  const atomGroupId = new WeakMap<LyricWord, number>();
  let nextGroupId = 1;

  for (const w of words) {
    const content = w.word.trim();
    const romanWord = w.romanWord ?? "";
    const obscene = w.obscene ?? false;

    // 空白或含 ruby 注音的单词直接保留
    if (content.length === 0 || (w.ruby?.length ?? 0) > 0) {
      atoms.push({ ...w });
      continue;
    }

    const parts = w.word.split(/(\s+)/).filter((p) => p.length > 0);
    const totalLen = w.word.replace(/\s/g, "").length || 1;
    const duration = w.endTime - w.startTime;
    let offset = 0;

    for (const part of parts) {
      if (!part.trim()) {
        const t = w.startTime + (offset / totalLen) * duration;
        atoms.push(makeAtom(part, "", obscene, t, t));
        continue;
      }

      if (isCJK(part) && part.length > 1 && romanWord.trim().length === 0) {
        // CJK 多字词逐字拆分，均分时间，并标记同源 groupId 以便后续强制合并
        const charDur = duration / totalLen;
        const gid = nextGroupId++;
        for (const char of part) {
          const t = w.startTime + (offset / totalLen) * duration;
          const atom = makeAtom(char, "", obscene, t, t + charDur);
          atomGroupId.set(atom, gid);
          atoms.push(atom);
          offset++;
        }
      } else {
        const t = w.startTime + (offset / totalLen) * duration;
        const partDur = (part.length / totalLen) * duration;
        atoms.push(makeAtom(part, romanWord, obscene, t, t + partDur));
        offset += part.length;
      }
    }
  }

  if (!hasSegmenter) return mergeAdjacentCJKGroups(atoms, atomGroupId);

  // 利用 Intl.Segmenter 按词边界重新分组
  const fullText = atoms.map((a) => a.word).join("");
  const segments = new Intl.Segmenter(undefined, { granularity: "word" });
  const result: (LyricWord | LyricWord[])[] = [];
  let atomIdx = 0;
  let actual = 0;
  let expected = 0;
  let group: LyricWord[] = [];

  for (const seg of segments.segment(fullText)) {
    expected += seg.segment.length;

    while (actual < expected && atomIdx < atoms.length) {
      const atom = atoms[atomIdx++];
      group.push(atom);
      actual += atom.word.length;
    }

    if (actual === expected) {
      // 将前导空白从分组中提出
      while (group.length > 1 && !group[0].word.trim()) {
        result.push(group.shift()!);
      }
      result.push(group.length === 1 ? group[0] : group);
      group = [];
    }
  }

  // 处理剩余原子
  while (atomIdx < atoms.length) {
    result.push(atoms[atomIdx++]);
  }
  if (group.length > 0) {
    result.push(group.length === 1 ? group[0] : group);
  }

  // 强制合并被 Intl.Segmenter 拆开的同源 CJK 多字词
  return mergeAdjacentCJKGroups(result, atomGroupId);
};

/**
 * 合并相邻的同源 CJK atom，防止 Intl.Segmenter 词典差异导致同源多字词被拆开。
 *
 * Android WebView 的 ICU 词典可能与桌面 Chromium 不同，导致"活下去"被拆成
 * 独立 chunk，使 shouldChunkEmphasize 无法对整词判定，只有 duration ≥ 1000ms
 * 的单字触发 emphasize。此函数确保同源 CJK atom 总是合并为一个 chunk。
 *
 * @param items - 已分组的 atom 列表（可能含独立 atom 或 atom 数组）
 * @param atomGroupId - atom 到源分组的映射
 * @returns 合并后的分组列表
 */
const mergeAdjacentCJKGroups = (
  items: (LyricWord | LyricWord[])[],
  atomGroupId: WeakMap<LyricWord, number>,
): (LyricWord | LyricWord[])[] => {
  const merged: (LyricWord | LyricWord[])[] = [];
  for (const item of items) {
    const atoms = Array.isArray(item) ? item : [item];
    const gid = atoms.length > 0 && atoms[0].word.trim() ? (atomGroupId.get(atoms[0]) ?? 0) : 0;
    if (gid && merged.length > 0) {
      const last = merged[merged.length - 1];
      const lastAtoms = Array.isArray(last) ? last : [last];
      const lastGid =
        lastAtoms.length > 0 && lastAtoms[lastAtoms.length - 1].word.trim()
          ? (atomGroupId.get(lastAtoms[lastAtoms.length - 1]) ?? 0)
          : 0;
      if (lastGid === gid) {
        const combined = [...lastAtoms, ...atoms];
        merged[merged.length - 1] = combined.length === 1 ? combined[0] : combined;
        continue;
      }
    }
    merged.push(item);
  }
  return merged;
};

/** 匹配字母或数字字符（Unicode 全语言支持） */
const LETTER_OR_DIGIT_RE = /[\p{L}\p{N}]/u;

/**
 * 判断两个相邻文本之间是否需要插入空格
 *
 * CJK 字符之间不需要空格，非 CJK 的字母/数字之间需要空格。
 *
 * @param prevText - 前一个文本
 * @param nextText - 后一个文本
 * @returns 是否需要空格
 */
export const needsSpaceBetween = (prevText: string, nextText: string): boolean => {
  if (!prevText || !nextText) return false;
  const lastChar = prevText[prevText.length - 1];
  const firstChar = nextText[0];
  if (isCJK(lastChar) || isCJK(firstChar)) return false;
  return LETTER_OR_DIGIT_RE.test(lastChar) && LETTER_OR_DIGIT_RE.test(firstChar);
};
