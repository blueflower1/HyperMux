<script setup lang="ts">
/** 桌面壳无边框窗口的自绘标题栏（仅 Tauri 环境渲染）：品牌块 + 拖拽区 + 最小化/最大化/关闭 */
import { ref } from 'vue'
import Icon from '@/components/Icon.vue'

let winP: Promise<any> | null = null
function win() {
  if (!winP) {
    winP = import('@tauri-apps/api/window').then((m) => m.getCurrentWindow())
  }
  return winP
}

const maximized = ref(false)

async function minimize() {
  ;(await win()).minimize()
}

async function toggleMax() {
  const w = await win()
  if (await w.isMaximized()) {
    await w.unmaximize()
    maximized.value = false
  } else {
    await w.maximize()
    maximized.value = true
  }
}

async function close() {
  ;(await win()).close()
}
</script>

<template>
  <div class="titlebar" data-tauri-drag-region>
    <div class="tb-brand" data-tauri-drag-region>
      <span class="tb-mark"><Icon name="play" :size="9" /></span>
      <span data-tauri-drag-region>HyperMux</span>
    </div>
    <div class="tb-actions">
      <button class="tb-btn" type="button" title="最小化" @click="minimize">
        <Icon name="minus" :size="14" />
      </button>
      <button class="tb-btn" type="button" :title="maximized ? '还原' : '最大化'" @click="toggleMax">
        <Icon name="square" :size="13" />
      </button>
      <button class="tb-btn tb-close" type="button" title="关闭" @click="close">
        <Icon name="x" :size="14" />
      </button>
    </div>
  </div>
</template>
