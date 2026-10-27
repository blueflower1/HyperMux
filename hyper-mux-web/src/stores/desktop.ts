import { ref } from 'vue'
import { apiBase } from './server'

/** Tauri 桌面壳（Windows 客户端）运行时注入 __TAURI_INTERNALS__；浏览器端为 undefined */
export function isDesktop(): boolean {
  return typeof (window as any).__TAURI_INTERNALS__ !== 'undefined'
}

/** Windows 端 versionCode 约定：major*10000 + minor*100 + patch（0.1.0 -> 100，发布时管理端按同规则填写） */
export function codeFromVersion(v: string): number {
  const m = /^(\d+)\.(\d+)\.(\d+)/.exec(v || '')
  if (!m) return 0
  return Number(m[1]) * 10000 + Number(m[2]) * 100 + Number(m[3])
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
    const resp = await fetch(`${apiBase()}/update/check?platform=windows&code=${codeFromVersion(v)}`, {
      headers: { Accept: 'application/json' },
    })
    if (resp.status === 204) {
      desktopUpdate.value = { checked: true, currentVersion: v, update: null, error: '' }
      return null
    }
    const body = await resp.json().catch(() => null)
    if (body && body.code === 0 && body.data) {
      desktopUpdate.value = { checked: true, currentVersion: v, update: body.data, error: '' }
      return body.data as DesktopUpdateInfo
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
