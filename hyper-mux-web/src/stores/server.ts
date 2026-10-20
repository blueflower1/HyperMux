import { ref } from 'vue'

/**
 * 服务器档位（语义对齐 Android 端）：
 * - preset ：预设公共服务器（构建时 HYPERMUX_PUBLIC_SERVER_URL 注入），有注入即为默认档
 * - custom ：用户自填服务器地址（localStorage 持久化），无预设时默认档
 * 未填自定义地址前隐式走同源 /api/app（dev 代理或 :8080/web/ 同源部署兜底），不作为可见档位。
 */
export type ServerMode = 'preset' | 'custom'

const MODE_KEY = 'hypermux.web.serverMode'
const CUSTOM_KEY = 'hypermux.web.serverCustom'

/** 构建注入的预设公共服务器（编译期常量；未注入为空串），去掉末尾斜杠 */
export const PRESET_URL: string = (
  typeof __HYPERMUX_PUBLIC_SERVER__ === 'string' ? __HYPERMUX_PUBLIC_SERVER__ : ''
)
  .trim()
  .replace(/\/+$/, '')

/** 校验并归一化用户输入的服务器地址：允许省略协议（默认 http），去末尾斜杠；非法返回 null */
export function normalizeServerUrl(input: string): string | null {
  let s = (input || '').trim()
  if (!s) return null
  if (!/^https?:\/\//i.test(s)) s = 'http://' + s
  let u: URL
  try {
    u = new URL(s)
  } catch {
    return null
  }
  if (!/^https?:$/.test(u.protocol) || !u.hostname) return null
  if (u.search || u.hash) return null
  const path = u.pathname.replace(/\/+$/, '')
  // 防止把 API 全路径当服务器地址粘进来
  if (path === '/api' || path.endsWith('/api/app')) return null
  return u.origin + path
}

function readMode(): ServerMode {
  const m = localStorage.getItem(MODE_KEY)
  if (m === 'custom' && localStorage.getItem(CUSTOM_KEY)) return 'custom'
  if (m === 'preset' && PRESET_URL) return 'preset'
  // 默认：有构建注入的预设档 → 公共服务器；否则自定义
  return PRESET_URL ? 'preset' : 'custom'
}

export const serverMode = ref<ServerMode>(readMode())
export const customUrl = ref<string>(localStorage.getItem(CUSTOM_KEY) || '')

export function setServerMode(mode: ServerMode) {
  if (mode === 'preset' && !PRESET_URL) return
  serverMode.value = mode
  localStorage.setItem(MODE_KEY, mode)
}

/** 保存自定义服务器；返回归一化结果，非法输入返回 null */
export function saveCustomUrl(input: string): string | null {
  const n = normalizeServerUrl(input)
  if (!n) return null
  customUrl.value = n
  localStorage.setItem(CUSTOM_KEY, n)
  serverMode.value = 'custom'
  localStorage.setItem(MODE_KEY, 'custom')
  return n
}

export function clearCustomUrl() {
  customUrl.value = ''
  localStorage.removeItem(CUSTOM_KEY)
  if (serverMode.value === 'custom' && PRESET_URL) setServerMode('preset')
}

/** API 基址（含 /api/app 前缀）：http 拦截器与裸 fetch 的唯一出口 */
export function apiBase(): string {
  if (serverMode.value === 'preset' && PRESET_URL) return PRESET_URL + '/api/app'
  if (serverMode.value === 'custom' && customUrl.value) return customUrl.value + '/api/app'
  return '/api/app'
}

/** UI 展示名：预设公共服务器只显示标签、不暴露地址；自定义档显示用户自填地址 */
export function activeServerLabel(): string {
  if (serverMode.value === 'preset' && PRESET_URL) return '公共服务器'
  if (serverMode.value === 'custom' && customUrl.value) return customUrl.value
  return location.origin
}
