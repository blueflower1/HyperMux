/** 毫秒 → "1:23:45" / "12:34" */
export function fmtMs(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return '--:--'
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${m}:${ss}`
}

/** 秒 → "1:23:45" / "12:34"（剧集时长 duration 单位秒） */
export function fmtSec(sec: number | null | undefined): string {
  if (sec == null || !Number.isFinite(sec) || sec <= 0) return ''
  return fmtMs(sec * 1000)
}

/** 进度百分比 0~100（无总长时兜底 0） */
export function progressPct(positionMs: number | null | undefined, durationMs: number | null | undefined): number {
  if (!positionMs || !durationMs || durationMs <= 0) return 0
  return Math.min(100, Math.round((positionMs / durationMs) * 100))
}

/** 剩余时长文案：约 xx 分钟 */
export function remainText(positionMs: number | null | undefined, durationMs: number | null | undefined): string {
  if (!durationMs || durationMs <= 0 || positionMs == null) return ''
  const remainMin = Math.max(0, Math.round(((durationMs - positionMs) / 60000) * 10) / 10)
  if (remainMin < 1) return '快看完了'
  return `剩 ${remainMin} 分钟`
}

/** 毫秒时间戳 → "10-11" / "10-11 14:30" */
export function fmtTime(ms: number | null | undefined, withTime = false): string {
  if (!ms) return ''
  const d = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  const base = `${p(d.getMonth() + 1)}-${p(d.getDate())}`
  return withTime ? `${base} ${p(d.getHours())}:${p(d.getMinutes())}` : base
}

/** 简单本地化日期（登录时间等） */
export function fmtDate(ms: number | null | undefined): string {
  if (!ms) return ''
  const d = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

export function clampText(s: string | null | undefined, n: number): string {
  const v = s || ''
  return v.length > n ? v.slice(0, n) + '…' : v
}
