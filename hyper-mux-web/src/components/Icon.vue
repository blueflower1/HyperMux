<script setup lang="ts">
// 内联 SVG 图标（feather 风格 24x24 stroke；fill 类图标单独标注）
const STROKE: Record<string, string> = {
  home: '<path d="M3 9.5 12 3l9 6.5V20a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/><path d="M9 22v-8h6v8"/>',
  history: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/>',
  user: '<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m21 21-4.5-4.5"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z"/>',
  'chev-left': '<path d="m15 18-6-6 6-6"/>',
  'chev-right': '<path d="m9 18 6-6-6-6"/>',
  'chev-down': '<path d="m6 9 6 6 6-6"/>',
  'skip-next': '<path d="M5 4l10 8-10 8V4Z"/><path d="M19 5v14"/>',
  check: '<path d="m20 6-11 11-5-5"/>',
  trash: '<path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M10 11v6M14 11v6"/>',
  logout: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="m16 17 5-5-5-5"/><path d="M21 12H9"/>',
  film: '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M7 3v18M17 3v18M3 8h4M3 16h4M17 8h4M17 16h4"/>',
  refresh: '<path d="M23 4v6h-6"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>',
  x: '<path d="M18 6 6 18M6 6l12 12"/>',
  /* fill 图标 */
  play: '<path d="M8 5v14l11-7Z" fill="currentColor" stroke="none"/>',
  pause: '<rect x="6" y="5" width="4" height="14" rx="1" fill="currentColor" stroke="none"/><rect x="14" y="5" width="4" height="14" rx="1" fill="currentColor" stroke="none"/>',
  star: '<path d="m12 2 3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01Z" fill="currentColor" stroke="none"/>',
}

const props = withDefaults(
  defineProps<{
    name: string
    size?: number | string
  }>(),
  { size: 20 },
)

// v-html 注入的是静态字符串常量表，无用户输入
const html = () => `<svg width="${props.size}" height="${props.size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${STROKE[props.name] || ''}</svg>`
</script>

<template>
  <span class="icon" v-html="html()" />
</template>
