/**
 * 接收侧（网页端 + Windows 桌面壳共用这一份）：计划书 80-cast-detailed.md §9.5 / §9.6，
 * 语义与 TV 端 CastPollClient 一致。
 *
 * 选型：短间隔轮询（默认 2s），不引入 SSE/WebSocket —— 与 TV 端同构，也复用同一套服务端端点。
 * 轮询本身就是「设备在线」心跳：服务端 DevicePresenceService 据此把这台设备标成在线，
 * 手机端才允许投过来；停止轮询 30s 后自动离线。
 *
 * 生命周期：只在「已登录 + 页面在前台」时轮询。后台标签页空转会把这台设备一直标成在线，
 * 手机就能投到一个没人看的窗口上（与 TV 端「退到后台立刻停」同一口径）。
 *
 * 浏览器 = platform `web`；Tauri 桌面壳 = platform `windows`（见 castPlatform / TARGET_EXIT_REASON）。
 */
import { ref } from 'vue'
import { castAccept, castCancel, castPoll, castReject, getCastConfig, registerDevice } from '@/api'
import { ApiError, getExp, getToken } from '@/api/http'
import { isDesktop } from './desktop'
import type { CastAccepted, CastPending } from '@/api/types'

const DEVICE_ID_KEY = 'hypermux.web.deviceId'
const DEVICE_NAME_KEY = 'hypermux.web.deviceName'

/** 服务端 user_device.platform 白名单之一：浏览器 = web，Tauri 桌面壳 = windows */
export function castPlatform(): 'web' | 'windows' {
  return isDesktop() ? 'windows' : 'web'
}

/** 接收端退出播放页的收尾原因（服务端只认 tv-exit / web-exit / desktop-exit，其余一律按 sender-cancel 记） */
export const TARGET_EXIT_REASON: string = isDesktop() ? 'desktop-exit' : 'web-exit'

const DEFAULT_POLL_MS = 2_000
const MIN_POLL_MS = 1_000
const MAX_POLL_MS = 5_000
const MAX_BACKOFF_MS = 15_000
const MAX_BACKOFF_429_MS = 10_000
/** 本地先裁一刀（服务端 sanitizeName 截断 64），避免弹窗与手机列表里名字过长 */
const MAX_NAME = 32

/** 已接受、正在本机播放的投屏（播放页据此挂徽标、退出时 cancel） */
export interface CastActive {
  id: number
  from: string
  movieId: number
  videoId: number
}

/** 管理后台开关（cast/config） */
export const castEnabled = ref(true)
/** 轮询是否在跑（「我的」页据此显示投屏接收状态） */
export const castPolling = ref(false)
/** 待确认的投屏请求（非空即弹确认窗） */
export const castPending = ref<CastPending | null>(null)
export const castConfirmSeconds = ref(8)
export const castActive = ref<CastActive | null>(null)

/** 浏览器级设备号：localStorage 持久化（清站点数据 = 新设备，与 App 卸载重装同语义） */
export function webDeviceId(): string {
  let v = (localStorage.getItem(DEVICE_ID_KEY) || '').trim()
  if (!v) {
    v = newDeviceId()
    localStorage.setItem(DEVICE_ID_KEY, v)
  }
  return v
}

/**
 * crypto.randomUUID 只在安全上下文存在（http://192.168.x.x 这类局域网地址没有），
 * 所以留一段 v4 兜底：设备号只要「唯一 + 稳定」，不需要密码学强度。
 */
function newDeviceId(): string {
  const c = typeof crypto !== 'undefined' ? crypto : undefined
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  const bytes = new Uint8Array(16)
  if (c && typeof c.getRandomValues === 'function') c.getRandomValues(bytes)
  else for (let i = 0; i < 16; i++) bytes[i] = Math.floor(Math.random() * 256)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** 设备名：默认「网页端 · <主机名>」/「电脑端 · <计算机名>」，手机端投屏列表直接渲染它 */
export function webDeviceName(): string {
  const custom = (localStorage.getItem(DEVICE_NAME_KEY) || '').trim()
  return custom || defaultDeviceName()
}

export function defaultDeviceName(): string {
  if (isDesktop()) return desktopLabel ? `电脑端 · ${desktopLabel}` : '电脑端'
  return `网页端 · ${location.hostname || '本机'}`
}

/** 浏览器侧没有"计算机名"可读，桌面壳问 Rust 拿（COMPUTERNAME）；拿不到就只留「电脑端」 */
let desktopLabel = ''

async function loadDesktopLabel() {
  if (!isDesktop() || desktopLabel) return
  try {
    const { invoke } = await import('@tauri-apps/api/core')
    desktopLabel = String((await invoke('device_label')) || '').trim().slice(0, 32)
  } catch {
    desktopLabel = ''
  }
}

export function setWebDeviceName(name: string) {
  const v = name.trim().slice(0, MAX_NAME)
  if (!v) localStorage.removeItem(DEVICE_NAME_KEY)
  else localStorage.setItem(DEVICE_NAME_KEY, v)
}

/* ==================== 轮询 ==================== */

let timer: number | undefined
let running = false
let visibilityHooked = false
let pollBaseMs = DEFAULT_POLL_MS
let backoffMs = DEFAULT_POLL_MS

/** 会话是否可用（与 http 拦截器同一口径：本地提前 60s 判过期） */
function hasSession(): boolean {
  const token = getToken()
  if (!token) return false
  const exp = getExp()
  return exp <= 0 || Date.now() < exp - 60_000
}

/** 挂一次前台/后台闸门；登录态变化由调用方 syncCastReceiver 驱动 */
export function initCastReceiver() {
  if (visibilityHooked) return
  visibilityHooked = true
  document.addEventListener('visibilitychange', () => syncCastReceiver())
}

export function syncCastReceiver() {
  if (hasSession() && document.visibilityState === 'visible') startCastReceiver()
  else stopCastReceiver()
}

export function startCastReceiver() {
  if (running) return
  running = true
  backoffMs = pollBaseMs
  void boot()
}

export function stopCastReceiver() {
  running = false
  if (timer) {
    clearTimeout(timer)
    timer = undefined
  }
  castPolling.value = false
  // 退到后台 / 登出：本地弹窗直接收掉（服务端 15s 后自己把 PENDING 判过期，手机端得到「未响应」）
  castPending.value = null
}

async function boot() {
  await loadDesktopLabel() // 先把计算机名拿到，避免注册出去的名字是「电脑端」再改一次
  await registerSelf()
  await refreshConfig()
  schedule(0)
}

function schedule(delayMs: number) {
  if (!running) return
  if (timer) clearTimeout(timer)
  timer = window.setTimeout(() => void tick(), delayMs)
}

async function tick() {
  if (!running) return
  try {
    const r = await castPoll(webDeviceId())
    if (!running) return
    backoffMs = pollBaseMs
    castPolling.value = true
    castConfirmSeconds.value = clampConfirm(r.confirmSeconds)
    // 同一请求在 PENDING 期间会被每轮重复下发：只在 id 变化时更新，否则确认弹窗每秒被重建、倒计时归零
    if ((r.pending?.id ?? 0) !== (castPending.value?.id ?? 0)) castPending.value = r.pending || null
    if (r.cancelledIds && r.cancelledIds.length) handleCancelled(r.cancelledIds)
  } catch (e) {
    const code = e instanceof ApiError ? e.code : 0
    if (code === 401) {
      // 会话失效已由 http 拦截器清掉登录态：停轮询，等登录态 watcher 重新拉起
      stopCastReceiver()
      return
    }
    if (code === 404) {
      // 设备未注册（换服务器 / 清站点数据后常见）：注册一次，下一轮正常
      await registerSelf()
      backoffMs = pollBaseMs
    } else if (code === 429) {
      backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_429_MS)
    } else {
      backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS)
    }
  }
  schedule(backoffMs)
}

/** 幂等 upsert 本机设备（投屏接收的入口，失败不打扰：下一轮 404 会再触发） */
export async function registerSelf() {
  if (!hasSession()) return
  try {
    await registerDevice(webDeviceId(), webDeviceName(), castPlatform())
  } catch {
    /* 未登录 / 离线 / 服务端不可用：保持沉默 */
  }
}

/** 拉投屏配置（开关 / 确认秒数 / 轮询节奏）；失败保持上一次的值，不打扰用户 */
export async function refreshConfig() {
  try {
    const c = await getCastConfig()
    castEnabled.value = c.enabled
    castConfirmSeconds.value = clampConfirm(c.confirmSeconds)
    if (c.pollIntervalMs >= MIN_POLL_MS) {
      pollBaseMs = Math.min(c.pollIntervalMs, MAX_POLL_MS)
      backoffMs = pollBaseMs
    }
  } catch {
    /* 保持上一次的值 */
  }
}

function clampConfirm(v: number): number {
  const n = Number.isFinite(v) ? Math.round(v) : 8
  return Math.min(15, Math.max(3, n))
}

/* ==================== 确认 / 拒绝 / 取消 ==================== */

/**
 * 点「是」或倒计时归零。成功返回起播所需信息（调用方据此导航到播放页）；
 * 失败（请求已被其它端接管 / 已过期）抛给调用方提示。
 */
export async function acceptCast(reason: 'user-yes' | 'countdown'): Promise<CastAccepted> {
  const p = castPending.value
  if (!p) throw new Error('该投屏请求已结束')
  castPending.value = null
  const r = await castAccept(p.id, reason)
  castActive.value = {
    id: r.id,
    from: p.senderName || '手机端',
    movieId: r.movieId,
    videoId: r.videoId,
  }
  return r
}

/** 点「否」或点遮罩关闭（服务端幂等） */
export function rejectCast(reason = 'user-no') {
  const p = castPending.value
  if (!p) return
  castPending.value = null
  castReject(p.id, reason).catch(() => {})
}

/** 播放页退出 / 本集播完：通知服务端结束投屏（幂等），并清本地标记 */
export function cancelActiveCast(reason = TARGET_EXIT_REASON) {
  const a = castActive.value
  if (!a) return
  castActive.value = null
  castCancel(a.id, reason).catch(() => {})
}

/* ==================== 「已被取消 / 被接管」事件 ==================== */

type StopHandler = (id: number) => void
const stopHandlers = new Set<StopHandler>()

/**
 * 投屏被手机端取消、或被同账号其它设备接管 → 播放页注册监听以停播并提示。
 *
 * 用回调而不是全局 ref：一次性事件留在 ref 里会把用户下次好好看着的片子踢出去（TV 端同一教训）。
 */
export function onCastStopped(fn: StopHandler): () => void {
  stopHandlers.add(fn)
  return () => {
    stopHandlers.delete(fn)
  }
}

function handleCancelled(ids: number[]) {
  if (castPending.value && ids.includes(castPending.value.id)) castPending.value = null
  const a = castActive.value
  if (a && ids.includes(a.id)) {
    castActive.value = null
    for (const fn of [...stopHandlers]) fn(a.id)
  }
}
