<script setup lang="ts">
/** 桌面壳无边框窗口的自绘标题栏（仅 Tauri 环境渲染）：品牌块 + 退出登录（二次确认）+ 拖拽区 + 最小化/最大化/关闭 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from '@/components/Icon.vue'
import { useUserStore } from '@/stores/user'

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

/* ---------- 退出登录：必须二次确认（误点就丢登录态，代价不对称） ---------- */
const route = useRoute()
const router = useRouter()
const user = useUserStore()
const confirmLogout = ref(false)
const popWrap = ref<HTMLElement | null>(null)

// 登录页 / 未登录时不挂退出入口
const showLogout = computed(() => user.isLoggedIn && route.path !== '/login')

function closePop() {
  confirmLogout.value = false
}

function doLogout() {
  closePop()
  user.clear()
  router.push('/login')
}

function onDocMousedown(e: MouseEvent) {
  if (!confirmLogout.value) return
  if (popWrap.value && !popWrap.value.contains(e.target as Node)) closePop()
}

function onKeydown(e: KeyboardEvent) {
  if (confirmLogout.value && e.key === 'Escape') {
    e.preventDefault()
    e.stopPropagation()
    closePop()
  }
}

onMounted(() => {
  document.addEventListener('mousedown', onDocMousedown, true)
  document.addEventListener('keydown', onKeydown, true)
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocMousedown, true)
  document.removeEventListener('keydown', onKeydown, true)
})
</script>

<template>
  <div class="titlebar" data-tauri-drag-region>
    <div class="tb-brand" data-tauri-drag-region>
      <span class="tb-mark"><Icon name="play" :size="9" /></span>
      <span data-tauri-drag-region>HyperMux</span>
      <div v-if="showLogout" ref="popWrap" class="tb-logout-wrap">
        <button
          class="tb-logout"
          type="button"
          :title="confirmLogout ? '取消' : '退出登录'"
          @click="confirmLogout = !confirmLogout"
        >
          <Icon name="logout" :size="13" /> 退出登录
        </button>
        <div v-if="confirmLogout" class="confirm-pop card" role="dialog" aria-modal="true">
          <div class="confirm-pop-title">退出登录？</div>
          <div class="confirm-pop-text">退出后需要重新登录（扫码或账号密码）才能继续观看，缓存与观看历史不会丢失。</div>
          <div class="confirm-pop-actions">
            <button class="btn" type="button" @click="closePop">取消</button>
            <button class="confirm-danger" type="button" @click="doLogout">
              <Icon name="logout" :size="13" /> 确认退出
            </button>
          </div>
        </div>
      </div>
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
