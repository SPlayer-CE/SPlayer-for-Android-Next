import { isCJK } from "../utils/split-words";

interface BreakUnit {
  width: number;
  text: string;
  isSpace: boolean;
}

const OVERFLOW_PENALTY_MULTIPLIER = 1000;
const CJK_BREAK_PENALTY_RATIO = 0.15;
const NORMAL_BREAK_PENALTY_RATIO = 0.5;
const SPACE_BREAK_REWARD_RATIO = 0.4;
const PUNCTUATION_BREAK_REWARD_RATIO = 0.6;
const PUNCTUATION_RE = /[,.;:!?，。；：！？、）】》」』'")[\]}>~…]$/;
const BALANCED_BREAK_CLASS = "lp-balanced-break";
const BALANCED_CLASS = "lp-balanced";

const hasSegmenter = typeof Intl !== "undefined" && typeof Intl.Segmenter !== "undefined";
const wordSegmenter = hasSegmenter ? new Intl.Segmenter(undefined, { granularity: "word" }) : null;

const balancedWidthCache = new WeakMap<HTMLElement, number>();

let sharedCanvasContext: CanvasRenderingContext2D | null = null;

const getMeasurementContext = (): CanvasRenderingContext2D | null => {
  if (!sharedCanvasContext) {
    sharedCanvasContext = document.createElement("canvas").getContext("2d");
  }
  return sharedCanvasContext;
};

const clampPositive = (value: number): number => Math.max(0, value);

/**
 * 为歌词主行应用更均衡的视觉换行
 * @param mainElements - 每行主歌词元素
 */
export const balanceLyricLineBreaks = (mainElements: HTMLDivElement[]) => {
  for (const mainElement of mainElements) {
    balanceLyricLineBreak(mainElement);
  }
};

/**
 * 清理平衡换行状态
 * @param mainElements - 每行主歌词元素
 */
export const resetLyricLineBreaks = (mainElements: HTMLDivElement[]) => {
  for (const mainElement of mainElements) {
    removeBalancedBreaks(mainElement);
    restoreStaticText(mainElement);
    mainElement.classList.remove(BALANCED_CLASS);
    delete mainElement.dataset.balancedBreaks;
    balancedWidthCache.delete(mainElement);
  }
};

function balanceLyricLineBreak(mainElement: HTMLDivElement) {
  const containerWidth = getAvailableWidth(mainElement);
  if (containerWidth <= 0) return;

  // 缓存：宽度未变且已有换行符则跳过
  const existingBrs = mainElement.querySelectorAll(`br.${BALANCED_BREAK_CLASS}`);
  const lastWidth = balancedWidthCache.get(mainElement) ?? -1;
  if (containerWidth === lastWidth && existingBrs.length > 0) return;

  restoreStaticText(mainElement);
  removeBalancedBreaks(mainElement);

  const hasElementChild = Array.from(mainElement.childNodes).some(
    (node) => node.nodeType === Node.ELEMENT_NODE,
  );

  // 测量优化：临时设置 white-space: nowrap 和移除父级 transform
  const prevWhiteSpace = mainElement.style.whiteSpace;
  mainElement.style.whiteSpace = "nowrap";

  const parentElement = mainElement.parentElement;
  let prevTransform = "";
  let transformChanged = false;
  if (parentElement) {
    prevTransform = parentElement.style.transform;
    if (prevTransform && prevTransform !== "none") {
      parentElement.style.transform = "none";
      transformChanged = true;
    }
  }

  try {
    let units: BreakUnit[];
    let fullText: string;
    let needsCalibration: boolean;
    let nodeMap: ChildNode[] | null = null;

    if (hasElementChild) {
      // 动态歌词：使用 Range/getBoundingClientRect 测量
      const result = collectDynamicUnits(mainElement);
      units = result.units;
      fullText = result.fullText;
      nodeMap = result.nodeMap;
      needsCalibration = false;
    } else {
      // 非动态歌词：使用 Canvas + wordSegmenter 测量
      const result = collectStaticUnits(mainElement);
      units = result.units;
      fullText = result.fullText;
      needsCalibration = true;
    }

    if (units.length <= 1) {
      balancedWidthCache.set(mainElement, containerWidth);
      return;
    }

    // 校准：非动态歌词用 Range 测量实际渲染宽度，按比例校准 Canvas 测量值
    if (needsCalibration) {
      const range = document.createRange();
      range.selectNodeContents(mainElement);
      const visualWidth = range.getBoundingClientRect().width;
      const layoutWidth = units.reduce((sum, u) => sum + u.width, 0);
      if (layoutWidth > 0 && visualWidth > 0) {
        const scale = visualWidth / layoutWidth;
        for (const unit of units) {
          unit.width *= scale;
        }
      }
    }

    const totalWidth = units.reduce((sum, u) => sum + u.width, 0);
    if (totalWidth <= containerWidth) {
      balancedWidthCache.set(mainElement, containerWidth);
      return;
    }

    const breaks = calcBalancedBreaks(units, containerWidth, fullText);
    if (breaks.length === 0) {
      balancedWidthCache.set(mainElement, containerWidth);
      return;
    }

    mainElement.classList.add(BALANCED_CLASS);
    mainElement.dataset.balancedBreaks = breaks.join(",");

    if (nodeMap) {
      // 动态歌词：在现有节点前插入 <br>
      for (let i = breaks.length - 1; i >= 0; i--) {
        const br = document.createElement("br");
        br.className = BALANCED_BREAK_CLASS;
        mainElement.insertBefore(br, nodeMap[breaks[i]]);
      }
    } else {
      // 非动态歌词：重建 DOM
      mainElement.textContent = "";
      const breakSet = new Set(breaks);
      const fragment = document.createDocumentFragment();
      for (let i = 0; i < units.length; i++) {
        if (breakSet.has(i)) {
          const br = document.createElement("br");
          br.className = BALANCED_BREAK_CLASS;
          fragment.appendChild(br);
        }
        fragment.appendChild(document.createTextNode(units[i].text));
      }
      mainElement.appendChild(fragment);
    }

    balancedWidthCache.set(mainElement, containerWidth);
  } finally {
    mainElement.style.whiteSpace = prevWhiteSpace;
    if (transformChanged && parentElement) {
      parentElement.style.transform = prevTransform;
    }
  }
}

function restoreStaticText(mainElement: HTMLDivElement) {
  const originalText = mainElement.dataset.balancedStaticText;
  if (originalText == null) return;
  mainElement.textContent = originalText;
}

function removeBalancedBreaks(mainElement: HTMLDivElement) {
  for (const br of Array.from(mainElement.querySelectorAll(`br.${BALANCED_BREAK_CLASS}`))) {
    br.remove();
  }
}

function getAvailableWidth(mainElement: HTMLDivElement): number {
  const lineElement = mainElement.closest<HTMLElement>(".lp-line");
  const target = lineElement ?? mainElement;
  const style = getComputedStyle(target);
  const paddingLeft = Number.parseFloat(style.paddingLeft) || 0;
  const paddingRight = Number.parseFloat(style.paddingRight) || 0;
  const width =
    target.clientWidth || mainElement.parentElement?.clientWidth || mainElement.clientWidth;
  return Math.max(0, width - paddingLeft - paddingRight);
}

interface DynamicResult {
  units: BreakUnit[];
  fullText: string;
  nodeMap: ChildNode[];
}

function collectDynamicUnits(mainElement: HTMLDivElement): DynamicResult {
  const units: BreakUnit[] = [];
  const nodeMap: ChildNode[] = [];
  const range = document.createRange();

  for (const node of Array.from(mainElement.childNodes)) {
    if (node.nodeType === Node.TEXT_NODE) {
      const text = node.textContent ?? "";
      if (text.length === 0) continue;
      range.selectNodeContents(node);
      units.push({
        width: range.getBoundingClientRect().width,
        text,
        isSpace: text.trim().length === 0,
      });
      nodeMap.push(node);
    } else if (node.nodeType === Node.ELEMENT_NODE) {
      const el = node as HTMLElement;
      const rect = el.getBoundingClientRect();
      const elStyle = getComputedStyle(el);
      const marginLeft = Number.parseFloat(elStyle.marginLeft) || 0;
      const marginRight = Number.parseFloat(elStyle.marginRight) || 0;
      units.push({
        width: clampPositive(rect.width + marginLeft + marginRight),
        text: el.textContent ?? "",
        isSpace: false,
      });
      nodeMap.push(node);
    }
  }
  return { units, fullText: units.map((u) => u.text).join(""), nodeMap };
}

interface StaticResult {
  units: BreakUnit[];
  fullText: string;
}

function collectStaticUnits(mainElement: HTMLDivElement): StaticResult {
  if (mainElement.dataset.balancedStaticText == null) {
    mainElement.dataset.balancedStaticText = mainElement.textContent ?? "";
  }
  const fullText = mainElement.dataset.balancedStaticText;
  mainElement.textContent = fullText;

  const ctx = getMeasurementContext();
  if (!ctx || !wordSegmenter) {
    const chars = Array.from(fullText);
    return {
      units: chars.map((char) => ({
        width: char.length * 16,
        text: char,
        isSpace: char.trim().length === 0,
      })),
      fullText,
    };
  }

  const style = getComputedStyle(mainElement);
  ctx.font = `${style.fontWeight} ${style.fontSize} ${style.fontFamily}`;

  if ("letterSpacing" in ctx) {
    ctx.letterSpacing = style.letterSpacing !== "normal" ? style.letterSpacing : "0px";
  }
  if ("wordSpacing" in ctx) {
    ctx.wordSpacing = style.wordSpacing !== "normal" ? style.wordSpacing : "0px";
  }

  const units: BreakUnit[] = [];
  for (const { segment } of wordSegmenter.segment(fullText)) {
    units.push({
      width: ctx.measureText(segment).width,
      text: segment,
      isSpace: segment.trim().length === 0,
    });
  }
  return { units, fullText };
}

function calcBalancedBreaks(
  units: BreakUnit[],
  containerWidth: number,
  fullText: string,
): number[] {
  const count = units.length;
  if (count === 0 || containerWidth <= 0) return [];

  const cjkBoundaries = getCjkBoundaries(fullText);
  const charOffsets = new Int32Array(count + 1);
  const prefixWidth = new Float64Array(count + 1);
  for (let i = 0; i < count; i++) {
    charOffsets[i + 1] = charOffsets[i] + units[i].text.length;
    prefixWidth[i + 1] = prefixWidth[i] + units[i].width;
  }
  if (prefixWidth[count] <= containerWidth) return [];

  const dp = new Float64Array(count + 1).fill(Number.POSITIVE_INFINITY);
  const nextBreak = new Int32Array(count + 1).fill(-1);
  dp[count] = 0;
  const cjkPenalty = (containerWidth * CJK_BREAK_PENALTY_RATIO) ** 2;
  const normalPenalty = (containerWidth * NORMAL_BREAK_PENALTY_RATIO) ** 2;
  const spaceReward = (containerWidth * SPACE_BREAK_REWARD_RATIO) ** 2;
  const punctuationReward = (containerWidth * PUNCTUATION_BREAK_REWARD_RATIO) ** 2;

  for (let i = count - 1; i >= 0; i--) {
    for (let j = i + 1; j <= count; j++) {
      const width = prefixWidth[j] - prefixWidth[i];
      let lineCost = 0;
      if (width > containerWidth) {
        if (j === i + 1) lineCost = (width - containerWidth) ** 2 * OVERFLOW_PENALTY_MULTIPLIER;
        else break;
      } else {
        lineCost = (containerWidth - width) ** 2;
      }

      const totalCost =
        lineCost +
        getBreakCost(j, count, units, charOffsets, cjkBoundaries, {
          cjkPenalty,
          normalPenalty,
          spaceReward,
          punctuationReward,
        }) +
        dp[j];
      if (totalCost < dp[i]) {
        dp[i] = totalCost;
        nextBreak[i] = j;
      }
    }
  }

  const breaks: number[] = [];
  let current = 0;
  while (current < count) {
    current = nextBreak[current];
    if (current <= 0) break;
    if (current < count) breaks.push(current);
  }
  return breaks;
}

function getCjkBoundaries(fullText: string): Set<number> {
  const boundaries = new Set<number>();
  if (!wordSegmenter) return boundaries;
  let offset = 0;
  for (const segment of wordSegmenter.segment(fullText)) {
    if (
      offset > 0 &&
      segment.isWordLike &&
      Array.from(segment.segment).some((char) => isCJK(char))
    ) {
      boundaries.add(offset);
    }
    offset += segment.segment.length;
  }
  return boundaries;
}

function getBreakCost(
  breakIndex: number,
  count: number,
  units: BreakUnit[],
  charOffsets: Int32Array,
  cjkBoundaries: Set<number>,
  costs: {
    cjkPenalty: number;
    normalPenalty: number;
    spaceReward: number;
    punctuationReward: number;
  },
): number {
  if (breakIndex >= count) return 0;
  const previousUnit = units[breakIndex - 1];
  if (PUNCTUATION_RE.test(previousUnit.text)) return -costs.punctuationReward;
  if (previousUnit.isSpace) return -costs.spaceReward;
  if (cjkBoundaries.has(charOffsets[breakIndex])) return costs.cjkPenalty;
  return costs.normalPenalty;
}
