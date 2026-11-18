import { ref } from 'vue'
import { apiBase } from './server'

/** Tauri 桌面壳（Windows 客户端）运行时注入 __TAURI_INTERNALS__；浏览器端为 undefined */
export function isDesktop(): boolean {
  return typeof (window as any).__TAURI_INTERNALS__ !== 'undefined'
}

/** Windows 端 versionCode 约定：major*10000 + minor*100 + patch（1.1.0 -> 10100，1.1.1 -> 10101，1.2.0 -> 10200） */
export function codeFromVersion(v: string): number {
  const m = /^(\d+)\.(\d+)\.(\d+)/.exec(v || '')
  if (!m) return 0
  return Number(m[1]) * 10000 + Number(m[2]) * 100 + Number(m[3])
}

/**
 * 检查更新时上报的 code 固定为 0 = 「把该平台最新发布直接给我」。
 * 服务端那条 versionCode 由管理端手填、口径不可靠（现网 Windows 的 1.1.0 填的是 11000，
 * 客户端按上面的约定自身是 10100，于是永远判定「有更新」→ 提示自己更新自己），
 * 所以这里不拿它当判定依据：拿到最新发布后，由客户端按版本名决定要不要提示（见 checkDesktopUpdate）。
 * 若发布记录没有版本名，才退回用 versionCode 与本机 codeFromVersion 比较。
 */
export const UPDATE_CHECK_REPORT_CODE = 0

/** 版本名按数字段比较（"1.10.0" > "1.9.0"；字符串直接比较会反过来） */
export function cmpVersion(a: string, b: string): number {
  const pa = (a || '').split('.').map((x) => parseInt(x, 10) || 0)
  const pb = (b || '').split('.').map((x) => parseInt(x, 10) || 0)
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0)
    if (d !== 0) return d > 0 ? 1 : -1
  }
  return 0
}

export interface DesktopUpdateInfo {
  versionCode: number
  versionName: string
  sha256: string
  sizeBytes: number
  releaseNotes: string
  mandatory: boolean
}

export interface DesktopUpdateState {
  checked: boolean
  currentVersion: string
  update: DesktopUpdateInfo | null
  error: string
}

export const desktopUpdate = ref<DesktopUpdateState>({
  checked: false,
  currentVersion: '',
  update: null,
  error: '',
})

/** 检查更新（契约 §3.11，匿名端点）；204 = 无更新。返回更新信息或 null。 */
export async function checkDesktopUpdate(): Promise<DesktopUpdateInfo | null> {
  if (!isDesktop()) return null
  try {
    const { getVersion } = await import('@tauri-apps/api/app')
    const v = await getVersion()
    const resp = await fetch(`${apiBase()}/update/check?platform=windows&code=${UPDATE_CHECK_REPORT_CODE}`, {
      headers: { Accept: 'application/json' },
    })
    if (resp.status === 204) {
      desktopUpdate.value = { checked: true, currentVersion: v, update: null, error: '' }
      return null
    }
    const body = await resp.json().catch(() => null)
    if (body && body.code === 0 && body.data) {
      const info = body.data as DesktopUpdateInfo
      // 「已是最新」由客户端判定：版本名不比本机更新（含同号覆盖发布）就当没有更新，
      // 避免出现「v1.1.0 · 可更新到 v1.1.0」这种提示自己更新的死循环。
      const newer = info.versionName
        ? cmpVersion(info.versionName, v) > 0
        : info.versionCode > codeFromVersion(v)
      if (!newer) {
        desktopUpdate.value = { checked: true, currentVersion: v, update: null, error: '' }
        return null
      }
      desktopUpdate.value = { checked: true, currentVersion: v, update: info, error: '' }
      return info
    }
    desktopUpdate.value = {
      checked: true,
      currentVersion: v,
      update: null,
      error: (body && body.msg) || `检查失败(${resp.status})`,
    }
  } catch {
    desktopUpdate.value = { ...desktopUpdate.value, checked: true, error: '检查更新失败：网络异常' }
  }
  return null
}

/** 下载直链（绝对 URL，交给系统浏览器下载 NSIS 安装包） */
export function desktopDownloadUrl(): string {
  const base = apiBase()
  const path = '/update/download?platform=windows'
  return base.startsWith('http') ? base + path : new URL(base + path, location.origin).href
}

/** 外部链接/下载：桌面走 opener 插件（系统默认浏览器），浏览器走 window.open */
export async function openExternal(url: string): Promise<void> {
  if (isDesktop()) {
    const { openUrl } = await import('@tauri-apps/plugin-opener')
    await openUrl(url)
  } else {
    window.open(url, '_blank')
  }
}

export function fmtBytes(n: number): string {
  if (!Number.isFinite(n) || n <= 0) return '—'
  if (n >= 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  if (n >= 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${n} B`
}
