import type { EpisodeEditBody } from '@/api/video'
import type { VideoVo } from '@/api/types'

/** 剧集编辑行（EpisodeTable 内部模型；key 本地唯一，id null = 新增行） */
export interface EpRow {
  key: string
  id: number | null
  seasonNo: number | null
  episodeNo: number | null
  title: string
  url: string
  duration: number | null
  /** 只读回显：缺失标记 / 大小 / 更新时间（新建行无） */
  missing: number
  size: number | null
  updateTime: number | null
}

let uid = 0

export function makeRow(p: Partial<EpRow> = {}): EpRow {
  uid += 1
  return {
    key: `r${uid}-${Date.now()}`,
    id: null,
    seasonNo: 1,
    episodeNo: 1,
    title: '',
    url: '',
    duration: null,
    missing: 0,
    size: null,
    updateTime: null,
    ...p,
  }
}

/** 服务端 VideoVo → 编辑行（直链按 urlKind 还原为单字段 url） */
export function rowFromVideo(v: VideoVo): EpRow {
  return makeRow({
    id: v.id,
    seasonNo: v.seasonNo,
    episodeNo: v.episodeNo,
    title: v.title ?? '',
    url: v.urlKind === 'REMOTE' ? v.remoteUrl ?? '' : v.openlistPath ?? '',
    duration: v.duration,
    size: v.size,
    missing: v.missing,
    updateTime: v.updateTime,
  })
}

/** 编辑行 → 全量替换请求项（url 空的行返回 null 供调用方提前拦截） */
export function rowToEditBody(r: EpRow): EpisodeEditBody | null {
  if (!r.url.trim()) return null
  const body: EpisodeEditBody = {
    seasonNo: r.seasonNo ?? 1,
    episodeNo: r.episodeNo ?? 1,
    url: r.url.trim(),
  }
  if (r.id != null) body.id = r.id
  if (r.title.trim()) body.title = r.title.trim()
  if (r.duration != null) body.duration = r.duration
  return body
}

// ==================== 文本导入本地预览（与后端 VideoService.importEpisodes 同语义） ====================

export interface ImportLine {
  /** 非空行序号（1 基） */
  line: number
  epNo: number
  url: string
  mode: 'explicit' | 'continue'
  error: string | null
  action: 'add' | 'overwrite'
}

export interface ImportPreview {
  lines: ImportLine[]
  added: number
  updated: number
  skipped: number
  /** 裸链接从第几集起接续 */
  startEp: number
}

export function previewImport(
  text: string,
  seasonNo: number,
  existing: { seasonNo: number; episodeNo: number }[],
  hasSource: boolean,
): ImportPreview {
  const byEp = new Set<number>(
    existing.filter((e) => e.seasonNo === seasonNo).map((e) => e.episodeNo),
  )
  const maxEp = existing
    .filter((e) => e.seasonNo === seasonNo)
    .reduce((m, e) => Math.max(m, e.episodeNo), 0)
  let nextEp = maxEp + 1
  const startEp = nextEp

  const lines: ImportLine[] = []
  const batch = new Set<number>()
  let added = 0
  let updated = 0
  let skipped = 0

  for (const raw of text.split(/\r\n|\r|\n/)) {
    const t = raw.trim()
    if (!t) continue

    let epNo: number | null = null
    let url = t
    let mode: ImportLine['mode'] = 'continue'
    const comma = t.indexOf(',')
    if (comma > 0) {
      const head = t.slice(0, comma).trim()
      if (/^\d+$/.test(head)) {
        epNo = parseInt(head, 10)
        url = t.slice(comma + 1).trim()
        mode = 'explicit'
      }
    }
    if (epNo == null) {
      epNo = nextEp++
    }

    const line: ImportLine = {
      line: lines.length + 1,
      epNo,
      url,
      mode,
      error: null,
      action: byEp.has(epNo) ? 'overwrite' : 'add',
    }
    lines.push(line)

    if (!url) {
      line.error = '缺少 url'
      skipped++
      continue
    }
    if (epNo < 1 || epNo > 999) {
      line.error = '集号须 1~999'
      skipped++
      continue
    }
    if (batch.has(epNo)) {
      line.error = '与前面行集号重复'
      skipped++
      continue
    }
    batch.add(epNo)
    if (!/^https?:\/\//i.test(url) && !hasSource) {
      line.error = 'OpenList 相对路径要求影片已配置源'
      skipped++
      continue
    }
    if (line.action === 'overwrite') updated++
    else added++
  }

  return { lines, added, updated, skipped, startEp }
}
