<script setup lang="ts">
import { onBeforeUnmount, onMounted, watch } from 'vue'
import { useThemeStore } from '@/stores/theme'

const theme = useThemeStore()

// 主题色由 store 的 switchTo 立即落定，内容永不被遮挡；
// 这里只负责在点击点播一圈向外扩散的"波纹"，纯装饰、pointer-events:none。
let fx: HTMLDivElement | null = null
let doneTimer: number | undefined

const DURATION = 680

function cleanup() {
  if (doneTimer) {
    clearTimeout(doneTimer)
    doneTimer = undefined
  }
  if (fx) {
    fx.remove()
    fx = null
  }
}

/** 从 (x,y) 向外扩散一圈主题色波纹，覆盖到最远的屏幕角后淡出 */
function runReveal(from: { x: number; y: number }) {
  cleanup() // 上一次未播完：先移除再播新的
  if (window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches) return

  // 主题刚落定为新主题，读取到的即新主题的主色
  const accent =
    getComputedStyle(document.documentElement).getPropertyValue('--primary').trim() || '#5b6cff'

  const r = Math.hypot(
    Math.max(from.x, window.innerWidth - from.x),
    Math.max(from.y, window.innerHeight - from.y),
  )
  const el = document.createElement('div')
  el.setAttribute('aria-hidden', 'true')
  el.style.cssText = [
    'position:fixed',
    `left:${from.x - r}px`,
    `top:${from.y - r}px`,
    `width:${r * 2}px`,
    `height:${r * 2}px`,
    'margin:0',
    'border-radius:50%',
    'pointer-events:none',
    'z-index:2147483647',
    `border:2px solid ${accent}`,
    `box-shadow:0 0 34px 6px ${accent}55, inset 0 0 26px 2px ${accent}33`,
    `background:radial-gradient(closest-side, transparent 70%, ${accent}24)`,
    'transform:scale(0.01)',
    'will-change:transform,opacity',
    `animation:hypermux-reveal ${DURATION}ms cubic-bezier(0.22,0.61,0.36,1) forwards`,
  ].join(';')
  document.body.appendChild(el)
  fx = el
  el.addEventListener('animationend', cleanup, { once: true })
  doneTimer = window.setTimeout(() => {
    if (fx === el) cleanup()
  }, DURATION + 300)
}

watch(
  () => theme.reveal.seq,
  (seq) => {
    if (!seq) return
    const from = theme.reveal.from
    if (from) runReveal(from)
  },
)

onMounted(() => theme.applyAttr()) // 与 index.html 内联脚本对齐（幂等）
onBeforeUnmount(cleanup)
</script>

<template>
  <!-- 命令式管理波纹层，无渲染内容 -->
</template>
