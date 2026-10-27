<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TopBar from '@/components/TopBar.vue'
import Sidebar from '@/components/Sidebar.vue'
import TabBar from '@/components/TabBar.vue'
import ToastHost from '@/components/ToastHost.vue'
import ThemeRevealFX from '@/components/ThemeRevealFX.vue'
import TitleBar from '@/components/TitleBar.vue'
import CastConfirmDialog from '@/components/CastConfirmDialog.vue'
import { errMsg } from '@/api/http'
import { isDesktop, checkDesktopUpdate } from '@/stores/desktop'
import { initDesktopDownloads } from '@/stores/download'
import {
  acceptCast,
  castConfirmSeconds,
  castPending,
  initCastReceiver,
  rejectCast,
  syncCastReceiver,
} from '@/stores/cast'
import { useToastStore } from '@/stores/toast'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const toast = useToastStore()
const user = useUserStore()

// 登录页、播放页与产品落地页不带主导航壳（播放页有沉浸式顶栏；落地页面向普通用户，独立成页）
// route.name 为 undefined = 初始路由尚未解析完成：此时也不挂壳，避免 Sidebar 提前发 API 触发 401 劫持跳转
const shellHidden = computed(
  () => route.name === undefined || route.name === 'landing' || route.path === '/login' || route.path.includes('/play'),
)

const desktopShell = isDesktop()

/* ---------- 投屏接收（网页端 / Windows 桌面壳 = 接收端，计划书 80-cast-detailed.md §9.5 / §9.6）---------- */
// 确认弹窗挂在壳层：投屏可能落在任何页面（首页/影片页/播放页），与 TV 端挂窗口层同一思路。
// 轮询闸门 = 已登录 + 页面在前台：后台标签页空转会把这台设备一直标成在线，
// 手机就能投到一个没人看的窗口上。
initCastReceiver()
watch(() => user.isLoggedIn, () => syncCastReceiver(), { immediate: true })

async function onCastAccept(reason: 'user-yes' | 'countdown') {
  try {
    const r = await acceptCast(reason)
    // castPos：让播放页用手机端送来的位置覆盖服务端断点（与 TV 端 castResumeMs 同语义）
    router.push({
      path: `/movie/${r.movieId}/play`,
      query: { videoId: String(r.videoId), castPos: String(r.positionMs) },
    })
  } catch (e) {
    toast.error(errMsg(e))
  }
}

function onCastReject() {
  rejectCast()
}

onMounted(async () => {
  if (!desktopShell) return
  initDesktopDownloads().catch((e) => console.error('initDesktopDownloads failed:', e))
  // 播放页关闭双击确认：Rust 侧拦截第一次关闭后广播提示
  import('@tauri-apps/api/window')
    .then((m) => m.getCurrentWindow())
    .then((w) => w.listen('hypermux://close-hint', () => toast.show('播放中，再按一次关闭以退出')))
    .catch(() => {})
  const info = await checkDesktopUpdate()
  if (info) {
    toast.show(`发现新版本 ${info.versionName}，可在「我的」页下载`)
  }
})
</script>

<template>
  <div class="app" :class="{ 'no-shell': shellHidden, 'login-route': route.path === '/login', desktop: desktopShell }">
    <TitleBar v-if="desktopShell" />
    <TopBar v-if="!shellHidden" />
    <div class="shell">
      <Sidebar v-if="!shellHidden" />
      <main class="content">
        <router-view />
      </main>
    </div>
    <TabBar v-if="!shellHidden" />
    <ToastHost />
    <ThemeRevealFX />
    <CastConfirmDialog
      v-if="castPending"
      :pending="castPending"
      :confirm-seconds="castConfirmSeconds"
      @accept="onCastAccept"
      @reject="onCastReject"
    />
  </div>
</template>
