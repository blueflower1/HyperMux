<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import QRCode from 'qrcode'
import Icon from '@/components/Icon.vue'
import ServerPicker from '@/components/ServerPicker.vue'
import { useUserStore } from '@/stores/user'
import { useThemeStore } from '@/stores/theme'
import { ApiError, errMsg } from '@/api/http'
import { qrCreate, qrStatus } from '@/api'

const router = useRouter()
const route = useRoute()
const user = useUserStore()
const theme = useThemeStore()

/* ---------- 账号密码登录 ---------- */
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')

function onThemeToggle(e: MouseEvent) {
  theme.toggleAt(e.clientX, e.clientY)
}

function goAfterLogin() {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  router.replace(redirect.startsWith('/') ? redirect : '/')
}

async function onSubmit() {
  if (busy.value) return
  error.value = ''
  if (!username.value.trim()) {
    error.value = '请输入用户名'
    return
  }
  if (!password.value) {
    error.value = '请输入密码'
    return
  }
  busy.value = true
  try {
    await user.login(username.value, password.value)
    goAfterLogin()
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    busy.value = false
  }
}

/* ---------- 扫码登录（与 TV 端同构：create 一次 → 2s 轮询 → 60s 换新码） ---------- */
type QrState = 'init' | 'waiting' | 'scanned' | 'confirmed' | 'unsupported' | 'error'

const qrState = ref<QrState>('init')
const qrDataUrl = ref('')
const qrError = ref('')

const QR_TTL_MS = 60_000
let qrId = ''
let qrCreatedAt = 0
let pollTimer: number | undefined
let retryTimer: number | undefined
let destroyed = false

function clearTimers() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = undefined
  }
  if (retryTimer) {
    clearTimeout(retryTimer)
    retryTimer = undefined
  }
}

async function startQr() {
  if (destroyed) return
  clearTimers()
  qrDataUrl.value = ''
  qrError.value = ''
  try {
    const r = await qrCreate()
    if (destroyed) return
    qrId = r.qrId
    qrCreatedAt = Date.now()
    // 载荷与手机端 ScanViewModel 约定一致：web 来源用 web-login
    qrDataUrl.value = await QRCode.toDataURL(`hypermux://web-login?id=${qrId}`, {
      margin: 1,
      width: 412,
    })
    if (destroyed) return
    qrState.value = 'waiting'
    pollTimer = window.setInterval(poll, 2000)
  } catch (e) {
    if (destroyed) return
    const code = e instanceof ApiError ? e.code : -1
    if (code === 404 || code === 401 || code === 405) {
      // 旧服务器没有扫码端点：账号密码登录不受影响
      qrState.value = 'unsupported'
      return
    }
    qrError.value = code === 429 ? '二维码创建过于频繁，30 秒后自动重试' : errMsg(e)
    qrState.value = 'error'
    retryTimer = window.setTimeout(startQr, 30_000)
  }
}

async function poll() {
  if (destroyed || !qrId) return
  // 服务端 60s 过期按创建时间判定：客户端到点主动换新码（勿高频 create，避免 429）
  if (Date.now() - qrCreatedAt > QR_TTL_MS) {
    startQr()
    return
  }
  try {
    const s = await qrStatus(qrId)
    if (destroyed) return
    if (s.state === 'CONFIRMED' && s.session) {
      qrState.value = 'confirmed'
      clearTimers()
      user.save(s.session.token, s.session.expiresAt, s.session.user)
      goAfterLogin()
    } else if (s.state === 'SCANNED') {
      qrState.value = 'scanned'
    } else if (s.state === 'EXPIRED') {
      startQr()
    }
    // PENDING：继续等待
  } catch (e) {
    if (destroyed) return
    const code = e instanceof ApiError ? e.code : -1
    if (code === 404 || code === 410) {
      startQr()
    } else if (code === 401 || code === 405) {
      qrState.value = 'unsupported'
      clearTimers()
    }
    // 网络抖动：本轮忽略，2s 后下一轮
  }
}

const qrStatusText = computed(() => {
  switch (qrState.value) {
    case 'init':
      return '正在生成二维码…'
    case 'waiting':
      return '用手机 App 扫码登录'
    case 'scanned':
      return '已扫码，请在手机上确认'
    case 'confirmed':
      return '已确认，正在登录…'
    case 'unsupported':
      return '服务器不支持扫码登录'
    case 'error':
      return qrError.value || '二维码获取失败，重试中…'
  }
  return ''
})

const qrPlaceholderText = computed(() => {
  switch (qrState.value) {
    case 'init':
      return '正在生成二维码…'
    case 'unsupported':
      return '服务器不支持扫码登录'
    default:
      return '获取失败，自动重试中'
  }
})

/** 档位切换：会话属于旧服务器，清掉并按新档位重开码 */
function onServerChanged() {
  user.clear()
  startQr()
}

onMounted(startQr)
onBeforeUnmount(() => {
  destroyed = true
  clearTimers()
})
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-logo"><Icon name="play" :size="22" /></div>
      <h1 class="login-title">HyperMux</h1>
      <p class="login-sub">登录后继续你的观影进度</p>

      <div class="login-panes">
        <div class="login-qr">
          <div class="qr-frame">
            <img v-if="qrDataUrl" :src="qrDataUrl" alt="登录二维码" />
            <div v-else class="qr-placeholder">
              <Icon :name="qrState === 'unsupported' ? 'x' : qrState === 'error' ? 'refresh' : 'user'" :size="26" />
              <span>{{ qrPlaceholderText }}</span>
            </div>
          </div>
          <div
            v-if="qrDataUrl || qrState === 'error'"
            class="qr-status"
            :class="{
              ok: qrState === 'scanned' || qrState === 'confirmed',
              bad: qrState === 'error',
            }"
          >
            {{ qrStatusText }}
          </div>
          <div class="qr-note">
            打开 HyperMux 手机 App →「扫一扫」扫描左侧二维码<br />
            二维码 60 秒自动刷新；手机需登录同一服务器
          </div>
        </div>

        <form class="login-form" @submit.prevent="onSubmit">
          <input
            v-model="username"
            class="input"
            type="text"
            autocomplete="username"
            placeholder="用户名"
            maxlength="32"
            :disabled="busy"
          />
          <input
            v-model="password"
            class="input"
            type="password"
            autocomplete="current-password"
            placeholder="密码"
            maxlength="20"
            :disabled="busy"
          />
          <div v-if="error" class="login-error">{{ error }}</div>
          <button class="btn btn-primary" type="submit" :disabled="busy">
            <span v-if="busy" class="spin" style="width: 16px; height: 16px"></span>
            {{ busy ? '登录中…' : '登 录' }}
          </button>
        </form>
      </div>

      <ServerPicker :on-changed="onServerChanged" />

      <div class="login-tip">账号由管理员在后台创建</div>
    </div>

    <button class="icon-btn" type="button" style="position: absolute; right: 18px; top: 18px" @click="onThemeToggle">
      <Icon :name="theme.theme === 'dark' ? 'sun' : 'moon'" />
    </button>
  </div>
</template>
