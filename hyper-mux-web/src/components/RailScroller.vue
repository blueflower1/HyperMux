<script setup lang="ts">
/**
 * 横向 rail 的滚动外壳（首页「继续观看」与分类分区共用）。
 *
 * 为什么不再用原生滚动条：`overflow-x: auto` 在 Windows(WebView2) / Chrome 上会在整行下面挂一条
 * 通栏滚动条，和卡片抢戏（真机截图里那条白条就是它），而且它只表达"能拖"、不表达"还有几张"。
 * 这里换成：隐藏原生滚动条 + 左右翻页按钮（只在真有得滚的那一侧出现）+ 两端渐隐。
 *
 * 触屏端仍然靠原生横滑：按钮在 `hover: none` 设备上直接不渲染，避免压住封面。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import Icon from './Icon.vue'

const track = ref<HTMLElement | null>(null)
const canLeft = ref(false)
const canRight = ref(false)

function measure() {
  const el = track.value
  if (!el) return
  const max = el.scrollWidth - el.clientWidth
  canLeft.value = el.scrollLeft > 4
  canRight.value = max > 4 && el.scrollLeft < max - 4
}

function page(dir: 1 | -1) {
  const el = track.value
  if (!el) return
  // 一次翻约一屏，但故意留一张卡做视觉锚：整屏翻会让用户失去「我看到哪儿了」的参照
  el.scrollBy({ left: dir * Math.max(240, el.clientWidth - 160), behavior: 'smooth' })
}

let mo: MutationObserver | undefined
let ro: ResizeObserver | undefined
let timers: number[] = []

onMounted(() => {
  const el = track.value
  if (!el) return
  el.addEventListener('scroll', measure, { passive: true })
  // 分类分区是进视口才请求的（CategoryRail），卡片比首帧晚到 → 内容变化必须重新测
  if (typeof MutationObserver !== 'undefined') {
    mo = new MutationObserver(measure)
    mo.observe(el, { childList: true, subtree: true })
  }
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(measure)
    ro.observe(el)
  }
  measure()
  timers.push(window.setTimeout(measure, 300), window.setTimeout(measure, 1200))
})

onBeforeUnmount(() => {
  track.value?.removeEventListener('scroll', measure)
  mo?.disconnect()
  ro?.disconnect()
  timers.forEach((t) => clearTimeout(t))
})
</script>

<template>
  <div class="rail-wrap">
    <div ref="track" class="rail">
      <slot />
    </div>
    <span class="rail-mask left" :class="{ on: canLeft }" />
    <span class="rail-mask right" :class="{ on: canRight }" />
    <button v-if="canLeft" class="rail-nav left" type="button" title="向左看更多" @click="page(-1)">
      <Icon name="chev-left" :size="18" />
    </button>
    <button v-if="canRight" class="rail-nav right" type="button" title="向右看更多" @click="page(1)">
      <Icon name="chev-right" :size="18" />
    </button>
  </div>
</template>
