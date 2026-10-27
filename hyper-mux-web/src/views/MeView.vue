<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Icon from '@/components/Icon.vue'
import { useThemeStore, type Theme } from '@/stores/theme'
import { useUserStore } from '@/stores/user'
import { useToastStore } from '@/stores/toast'
import { activeServerLabel } from '@/stores/server'
import { desktopUpdate, checkDesktopUpdate, openExternal, desktopDownloadUrl, isDesktop, fmtBytes } from '@/stores/desktop'
import { castPolling, refreshConfig as refreshCastConfig, webDeviceId, webDeviceName } from '@/stores/cast'
import { getCastDevices } from '@/api'
import type { CastDevice } from '@/api/types'
import { fmtDate } from '@/utils/format'
import { getExp } from '@/api/http'

const router = useRouter()
const theme = useThemeStore()
const user = useUserStore()
const toast = useToastStore()

const initials = computed(() => {
  const n = user.user?.nickName || user.user?.username || '?'
  return n.trim().slice(0, 1)
})

const expireText = computed(() => {
  const exp = getExp()
  if (!exp) return '长期有效'
  const leftDay = Math.round((exp - Date.now()) / 86_400_000)
  return leftDay > 1 ? `登录信息剩 ${leftDay} 天过期` : fmtDate(exp)
})

function logout() {
  user.clear()
  router.push('/login')
}

/* ---------- 桌面壳自更新（仅 Windows 客户端显示） ---------- */
const desktop = isDesktop()

async function doCheckUpdate() {
  const info = await checkDesktopUpdate()
  if (info) {
    toast.show(`发现新版本 ${info.versionName}`)
  } else if (!desktopUpdate.value.error) {
    toast.show('已是最新版本')
  }
}

function doDownloadUpdate() {
  const info = desktopUpdate.value.update
  if (info) openExternal(desktopDownloadUrl())
}

/** 点中哪个主题按钮就从该按钮中心向外扩散切换 */
function setThemeAt(t: Theme, e: MouseEvent) {
  const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
  theme.switchTo(t, r.left + r.width / 2, r.top + r.height / 2)
}

/* ---------- 投屏接收（web 端 = 接收端） ---------- */
const castSelfId = webDeviceId()
const castSelfName = webDeviceName()
const castTargets = ref<CastDevice[]>([])

const castStatusText = computed(() => {
  if (!user.isLoggedIn) return '未登录 · 不接收投屏'
  if (document.visibilityState !== 'visible') return '页面在后台 · 暂停接收'
  return castPolling.value ? '监听中（每 2s 轮询）' : '正在启动…'
})

function castTargetLabel(d: CastDevice) {
  if (d.deviceName) return d.deviceName
  return d.platform === 'web' ? '网页端' : d.platform === 'windows' ? '电脑端' : d.platform === 'mobile' ? '手机端' : '电视'
}

async function refreshCast() {
  await refreshCastConfig()
  try {
    // 与手机端同一口径：可投屏目标 = 同账号的其它设备（电视 / 网页端 / 电脑端 / 另一台手机）。
    // 本机自己排除 —— 它是「本机投屏设备」那一行。
    castTargets.value = (await getCastDevices()).filter((d) => d.deviceId !== castSelfId)
  } catch {
    /* 保持上一次的列表 */
  }
}

onMounted(refreshCast)
</script>

<template>
  <div style="max-width: 560px">
    <div class="me-card card">
      <div class="avatar me-avatar">{{ initials }}</div>
      <div style="min-width: 0">
        <div class="me-name">{{ user.user?.nickName || user.user?.username }}</div>
        <div class="me-sub">@{{ user.user?.username }} · 普通用户</div>
      </div>
    </div>

    <div class="me-section card">
      <div class="me-row">
        <span class="me-row-label"><Icon name="moon" :size="17" /> 主题</span>
        <div class="seg">
          <button type="button" :class="{ active: theme.theme === 'light' }" @click="setThemeAt('light', $event)">
            <Icon name="sun" :size="14" /> 浅色
          </button>
          <button type="button" :class="{ active: theme.theme === 'dark' }" @click="setThemeAt('dark', $event)">
            <Icon name="moon" :size="14" /> 深色
          </button>
        </div>
      </div>
      <div class="me-row" style="cursor: default">
        <span class="me-row-label"><Icon name="history" :size="17" /> 登录信息</span>
        <span class="me-row-value">{{ expireText }}</span>
      </div>
    </div>

    <div class="me-section card" style="padding: 6px">
      <div class="me-row" style="cursor: default">
        <span class="me-row-label"><Icon name="globe" :size="17" /> 服务器</span>
        <span class="me-row-value">{{ activeServerLabel() }}</span>
      </div>
      <div class="server-hint" style="padding: 0 12px 8px">切换服务器需先退出登录，在登录页操作</div>
    </div>

    <!-- 桌面壳自更新（仅 Windows 客户端） -->
    <div v-if="desktop" class="me-section card" style="padding: 6px">
      <div class="me-row" style="cursor: default">
        <span class="me-row-label"><Icon name="refresh" :size="17" /> 软件更新</span>
        <span class="me-row-value">
          v{{ desktopUpdate.currentVersion || '—' }}
          <template v-if="desktopUpdate.update"> · 可更新到 v{{ desktopUpdate.update.versionName }}</template>
          <template v-else-if="desktopUpdate.checked"> · 已是最新</template>
        </span>
      </div>
      <div v-if="desktopUpdate.update?.releaseNotes" class="server-hint" style="padding: 0 12px 6px">
        {{ desktopUpdate.update.releaseNotes }}
      </div>
      <div v-if="desktopUpdate.error" class="server-hint" style="padding: 0 12px 6px; color: var(--danger)">
        {{ desktopUpdate.error }}
      </div>
      <div style="display: flex; gap: 8px; padding: 4px 12px 10px">
        <button class="btn" style="height: 34px; font-size: 13px" type="button" @click="doCheckUpdate">检查更新</button>
        <button
          v-if="desktopUpdate.update"
          class="btn btn-primary"
          style="height: 34px; font-size: 13px"
          type="button"
          @click="doDownloadUpdate"
        >
          下载 v{{ desktopUpdate.update.versionName }}（{{ fmtBytes(desktopUpdate.update.sizeBytes) }}）
        </button>
      </div>
    </div>

    <!-- 投屏接收（web 端 = 接收端，计划书 80-cast-detailed.md §14） -->
    <div class="me-section card" style="padding: 6px">
      <div class="me-row" style="cursor: default">
        <span class="me-row-label"><Icon name="tv" :size="17" /> 本机投屏设备</span>
        <span class="me-row-value">{{ castSelfName }}</span>
      </div>
      <div class="me-row" style="cursor: default">
        <span class="me-row-label"><Icon name="copy" :size="17" /> 设备号</span>
        <span class="me-row-value">…{{ castSelfId.slice(-8) }}</span>
      </div>
      <div class="me-row" style="cursor: default">
        <span class="me-row-label"><Icon name="refresh" :size="17" /> 接收状态</span>
        <span class="me-row-value" :class="{ 'cast-live': castPolling && user.isLoggedIn }">{{ castStatusText }}</span>
      </div>
      <div v-if="castTargets.length" class="cast-targets">
        <div v-for="d in castTargets" :key="d.deviceId" class="cast-target">
          <span class="cast-dot" :class="{ on: d.online }" />
          <span class="cast-target-name">{{ castTargetLabel(d) }}</span>
          <span class="cast-target-state">
            {{ d.online ? '在线' : '离线' }}<template v-if="d.casting"> · 正在被投屏</template>
          </span>
        </div>
      </div>
      <div class="server-hint" style="padding: 0 12px 6px">
        接收状态就是这台浏览器在服务器上的在线心跳：手机端只允许投给「在线」的目标端，页面切到后台即停止接收。
      </div>
      <div style="display: flex; gap: 8px; padding: 4px 12px 10px">
        <button class="btn" style="height: 34px; font-size: 13px" type="button" @click="refreshCast">
          <Icon name="refresh" :size="14" /> 刷新设备列表
        </button>
      </div>
    </div>

    <button class="me-logout" type="button" @click="logout">
      <Icon name="logout" :size="16" /> 退出登录
    </button>
  </div>
</template>
