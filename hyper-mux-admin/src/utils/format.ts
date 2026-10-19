/** 全站格式化/小工具（50-admin §3：时间用 number(ms)，此处统一渲染）。 */

const p2 = (n: number) => String(n).padStart(2, '0')

/** 绝对时间 yyyy-MM-dd HH:mm */
export function fmtAbsolute(ms: number | null | undefined): string {
  if (!ms) return '—'
  const d = new Date(ms)
  return `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())} ${p2(d.getHours())}:${p2(d.getMinutes())}`
}

/** 相对时间：<1min 刚刚 / <1h N分钟前 / <1d N小时前 / <7d N天前 / 否则日期 */
export function fmtRelative(ms: number | null | undefined): string {
  if (!ms) return '—'
  const diff = Date.now() - ms
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  if (diff < 7 * 86_400_000) return `${Math.floor(diff / 86_400_000)} 天前`
  return fmtAbsolute(ms).slice(0, 10)
}

/** 秒 → mm:ss 或 h:mm:ss */
export function fmtDuration(sec: number | null | undefined): string {
  if (sec == null) return '—'
  const s = Math.max(0, Math.round(sec))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const ss = s % 60
  return h > 0 ? `${h}:${p2(m)}:${p2(ss)}` : `${m}:${p2(ss)}`
}

/** 字节 → B/KB/MB/GB */
export function fmtSize(bytes: number | null | undefined): string {
  if (bytes == null) return '—'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let v = bytes / 1024
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(1)} ${units[i]}`
}

export function truncate(s: string | null | undefined, max: number): string {
  if (!s) return ''
  return s.length > max ? s.slice(0, max - 1) + '…' : s
}

/** http(s) 开头 = 远程直链；否则 = OpenList 相对路径 */
export function isRemoteUrl(url: string | null | undefined): boolean {
  return !!url && /^https?:\/\//i.test(url.trim())
}

export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    try {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      const ok = document.execCommand('copy')
      document.body.removeChild(ta)
      return ok
    } catch {
      return false
    }
  }
}
