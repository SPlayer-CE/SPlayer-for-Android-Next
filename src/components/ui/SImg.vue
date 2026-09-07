<script setup lang="ts">
import defaultFallback from "@/assets/images/song.jpg";
import { useCoverCache, type CoverCacheType } from "@/composables/useCoverCache";
import { normalizeNeteaseMediaUrl } from "@/utils/format/netease";

export interface SImgProps {
  /** 图片地址 */
  src?: string;
  /** 占位图地址 */
  fallback?: string;
  /** alt 文字 */
  alt?: string;
  /** Android 本地缓存分类 */
  cacheType?: CoverCacheType;
}

const props = withDefaults(defineProps<SImgProps>(), {
  fallback: defaultFallback,
  alt: "",
  cacheType: "covers",
});

const emit = defineEmits<{
  load: [el: HTMLImageElement];
}>();

const isLoaded = ref(false);

const normalizedSrc = computed(() => {
  const src = normalizeNeteaseMediaUrl(props.src) ?? props.src;
  if (!src) return src;
  if (location.protocol !== "https:") return src;
  if (!src.startsWith("http://")) return src;
  return `https://${src.slice("http://".length)}`;
});

/** Android 封面缓存：命中本地返 blob URL，未命中返原 URL 并后台下载 */
const cachedSrc = useCoverCache(normalizedSrc, props.cacheType);

const onLoad = (e: Event) => {
  const target = e.target as HTMLImageElement;
  target.style.opacity = "1";
  isLoaded.value = true;
  emit("load", target);
};

watch(
  () => props.src,
  () => {
    isLoaded.value = false;
  },
);
</script>

<template>
  <div class="relative isolate overflow-hidden">
    <!-- 占位图：封面加载完后淡出移除 -->
    <Transition name="fade">
      <img
        v-if="!isLoaded"
        :src="fallback"
        :alt="alt"
        class="absolute w-full h-full object-cover z-0"
      />
    </Transition>
    <!-- 封面：Transition 控制外层 div 的交叉淡入淡出，img 的 opacity 由 @load 单独控制 -->
    <Transition
      enter-active-class="transition-opacity duration-200"
      leave-active-class="transition-opacity duration-200"
      enter-from-class="opacity-0"
      leave-to-class="opacity-0"
    >
      <div v-if="cachedSrc" :key="cachedSrc" class="absolute inset-0 z-1">
        <img
          :src="cachedSrc"
          :alt="alt"
          class="w-full h-full object-cover opacity-0 transition-opacity duration-200"
          decoding="async"
          loading="lazy"
          @load="onLoad"
          @error="isLoaded = false"
        />
      </div>
    </Transition>
  </div>
</template>
