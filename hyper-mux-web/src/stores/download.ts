/**
 * 桌面端应用内下载（仅 Tauri 环境）：
 * - 引擎在 Rust 侧（download.rs）：直链走 aria2（断点续传/多连接/精确进度），HLS 走 mpv stream-record；
 *   完成后 XOR 混淆成 .hypermux
 * - 这里负责任务队列（并发上限）、进度事件桥接、暂停任务持久化（localStorage）与目录设置
 * UI 交互对齐 Android 端「缓存」面板：勾选网格 + 全选 + 状态徽章（✓ / %）
 */
import { ref } from 'vue'
import { getPlayUrl } from '@/api'
import type { VideoItem } from '@/api/types'
import { errMsg } from '@/api/http'
import { isDesktop } from './desktop'
import { useToastStore } from './toast'

export interface DownloadSpec {
  movieId: number
  movieTitle: string
  seasonNo: number
  episodeNo: number
  videoId: number
  fileBase: string
}

export interface DownloadRecord {
  taskId: number
  movieId: number
  movieTitle: string
  seasonNo: number
  episodeNo: number
  videoId: number
  fileBase: string
  path: string
  bytes: number
  ts: number
}

export interface LiveTask {
  id: number
  spec: DownloadSpec
  /** 0..100；-1 = 总量未知 */
  percent: number
  bytes: number
  total: number
  speed: number
  engine: string
}

export interface PausedTask {
  spec: DownloadSpec
  bytes: number
  engine: string
  ts: number
}

const MAX_CONCURRENT = 3
const LS_DIR = 'hm.dl.dir'
const LS_RECORDS = 'hm.dl.records'
const LS_PAUSED = 'hm.dl.paused'

export const downloadRecords = ref<DownloadRecord[]>(loadList(LS_RECORDS))
export const liveTasks = ref<LiveTask[]>([])
export const pausedTasks = ref<PausedTask[]>(loadList(LS_PAUSED))

const specs = new Map<number, DownloadSpec>()
const queue: DownloadSpec[] = []
let nextTaskId = 1
let eventsBound = false
let dirCache: string | null = null

function loadList<T>(key: string): T[] {
  try {
    const raw = localStorage.getItem(key)
    const arr = raw ? JSON.parse(raw) : []
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

function saveList(key: string, arr: unknown[]) {
  localStorage.setItem(key, JSON.stringify(arr))
}

function saveRecords() {
  saveList(LS_RECORDS, downloadRecords.value)
}
function savePaused() {
  saveList(LS_PAUSED, pausedTasks.value)
}

async function invoke<T = unknown>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  const { invoke: tInvoke } = await import('@tauri-apps/api/core')
  return tInvoke<T>(cmd, args)
}

/* ---------- 目录 ---------- */

export async function downloadDir(): Promise<string> {
  if (dirCache) return dirCache
  const saved = localStorage.getItem(LS_DIR)
  if (saved) {
    dirCache = saved
    return saved
  }
  const def = await invoke<string>('get_default_download_dir')
  dirCache = def
  return def
}

export function setDownloadDir(dir: string) {
  dirCache = dir
  localStorage.setItem(LS_DIR, dir)
}

/* ---------- 初始化（App 挂载时桌面分支调用） ---------- */

export async function initDesktopDownloads() {
  if (!isDesktop()) return
  await bindEvents()
  // mpv 残留 .part.mkv 清理（aria2 的 .part 保留给续传）+ 完成记录文件存在性校验
  try {
    await invoke('cleanup_parts', { dir: await downloadDir() })
  } catch {
    /* ignore */
  }
  try {
    const paths = downloadRecords.value.map((r) => r.path)
    if (paths.length) {
      const existing = await invoke<string[]>('check_paths', { paths })
      const keep = new Set(existing)
      downloadRecords.value = downloadRecords.value.filter((r) => keep.has(r.path))
      saveRecords()
    }
  } catch {
    /* ignore */
  }
}

interface ProgressPayload {
  id: number
  percent: number
  bytes: number
  total: number
  speed: number
  engine: string
}

async function bindEvents() {
  if (eventsBound) return
  eventsBound = true
  const { getCurrentWindow } = await import('@tauri-apps/api/window')
  const w = await getCurrentWindow()
  const toast = useToastStore()

  await w.listen<ProgressPayload>('hypermux://dl-progress', ({ payload }) => {
    const t = liveTasks.value.find((x) => x.id === payload.id)
    if (t) {
      t.percent = payload.percent
      t.bytes = payload.bytes
      t.total = payload.total
      t.speed = payload.speed
      t.engine = payload.engine
    }
  })

  await w.listen<{ id: number; path: string; bytes: number }>('hypermux://dl-done', ({ payload }) => {
    const spec = specs.get(payload.id)
    specs.delete(payload.id)
    liveTasks.value = liveTasks.value.filter((x) => x.id !== payload.id)
    if (spec) {
      // 完成即清掉同集的暂停条目
      pausedTasks.value = pausedTasks.value.filter((p) => p.spec.movieId === spec.movieId && p.spec.videoId === spec.videoId)
      savePaused()
      downloadRecords.value.unshift({
        taskId: payload.id,
        ...spec,
        path: payload.path,
        bytes: payload.bytes,
        ts: Date.now(),
      })
      saveRecords()
      toast.show(`下载完成：${spec.fileBase}`)
    }
    pumpQueue()
  })

  await w.listen<{ id: number; msg: string }>('hypermux://dl-error', ({ payload }) => {
    const t = liveTasks.value.find((x) => x.id === payload.id)
    specs.delete(payload.id)
    liveTasks.value = liveTasks.value.filter((x) => x.id !== payload.id)
    if (t) {
      // 失败保留 .part：转入暂停区，可"继续"续传重试
      if (t.engine !== 'mpv') {
        pausedTasks.value.unshift({ spec: t.spec, bytes: t.bytes, engine: t.engine, ts: Date.now() })
        savePaused()
      }
      toast.error(`下载失败：${t.spec.fileBase}（${payload.msg}）`)
    }
    pumpQueue()
  })

  await w.listen<{ id: number; bytes: number }>('hypermux://dl-paused', ({ payload }) => {
    const t = liveTasks.value.find((x) => x.id === payload.id)
    specs.delete(payload.id)
    liveTasks.value = liveTasks.value.filter((x) => x.id !== payload.id)
    if (t) {
      // 暂停已乐观入列：这里只校准最终落盘字节
      const existing = pausedTasks.value.find((p) => p.spec.movieId === t.spec.movieId && p.spec.videoId === t.spec.videoId)
      if (existing) {
        existing.bytes = payload.bytes
      } else {
        pausedTasks.value.unshift({ spec: t.spec, bytes: payload.bytes, engine: t.engine, ts: Date.now() })
      }
      savePaused()
    }
    pumpQueue()
  })

  await w.listen<{ id: number }>('hypermux://dl-cancelled', ({ payload }) => {
    specs.delete(payload.id)
    liveTasks.value = liveTasks.value.filter((x) => x.id !== payload.id)
    pumpQueue()
  })
}

/* ---------- 任务发起 ---------- */

function fileBaseOf(spec: Omit<DownloadSpec, 'fileBase'>): string {
  return `${spec.movieTitle} S${String(spec.seasonNo).padStart(2, '0')}E${String(spec.episodeNo).padStart(2, '0')}`
}

/** 入队下载（自动按并发上限排队） */
export function enqueueDownloads(specsIn: DownloadSpec[]) {
  for (const s of specsIn) {
    if (episodeState(s.movieId, s.videoId).state !== 'none') continue // 已下载 / 下载中跳过
    if (liveTasks.value.length + queue.length < MAX_CONCURRENT) {
      void startTask(s)
    } else {
      queue.push(s)
    }
  }
}

function pumpQueue() {
  while (queue.length && liveTasks.value.length < MAX_CONCURRENT) {
    void startTask(queue.shift()!)
  }
}

async function startTask(spec: DownloadSpec, fromPaused = false) {
  const id = nextTaskId++
  specs.set(id, spec)
  liveTasks.value.push({ id, spec, percent: -1, bytes: 0, total: 0, speed: 0, engine: '' })
  try {
    const play = await getPlayUrl(spec.videoId)
    await invoke('start_download', {
      taskId: id,
      url: play.url,
      headers: play.headers || {},
      dir: await downloadDir(),
      fileBase: spec.fileBase,
    })
  } catch (e) {
    liveTasks.value = liveTasks.value.filter((x) => x.id !== id)
    specs.delete(id)
    // 续传任务启动失败：退回暂停区保留"继续"入口，不让断点凭空消失
    if (fromPaused && !pausedTasks.value.some((p) => p.spec.movieId === spec.movieId && p.spec.videoId === spec.videoId)) {
      pausedTasks.value.unshift({ spec, bytes: 0, engine: 'aria2', ts: Date.now() })
      savePaused()
    }
    toastError(e)
    pumpQueue()
  }
}

function toastError(e: unknown) {
  try {
    useToastStore().error('下载启动失败：' + errMsg(e))
  } catch {
    /* ignore */
  }
}

/** 暂停（aria2 保留进度可续传；mpv 引擎等价于停止）：乐观移入暂停区，消除等待引擎停进程的视觉卡顿 */
export function pauseTask(id: number) {
  const t = liveTasks.value.find((x) => x.id === id)
  if (t) {
    liveTasks.value = liveTasks.value.filter((x) => x.id !== id)
    if (!pausedTasks.value.some((p) => p.spec.movieId === t.spec.movieId && p.spec.videoId === t.spec.videoId)) {
      pausedTasks.value.unshift({ spec: t.spec, bytes: t.bytes, engine: t.engine, ts: Date.now() })
      savePaused()
    }
    pumpQueue()
  }
  void invoke('pause_download', { taskId: id }).catch(() => {
    /* 任务可能已自然结束，忽略 */
  })
}

/** 彻底取消（删除任务与 .part 残留） */
export function cancelTask(id: number) {
  void invoke('cancel_download', { taskId: id }).catch(() => {
    liveTasks.value = liveTasks.value.filter((x) => x.id !== id)
  })
}

/** 继续已暂停任务（aria2 从断点续传） */
export function resumePaused(entry: PausedTask) {
  if (liveTasks.value.some((t) => t.spec.movieId === entry.spec.movieId && t.spec.videoId === entry.spec.videoId)) return
  pausedTasks.value = pausedTasks.value.filter((p) => !(p.spec.movieId === entry.spec.movieId && p.spec.videoId === entry.spec.videoId))
  savePaused()
  void startTask(entry.spec, true)
}

/** 删除已暂停任务（连同 .part / .aria2） */
export async function removePaused(entry: PausedTask) {
  try {
    await invoke('delete_part', { dir: await downloadDir(), fileBase: entry.spec.fileBase })
  } catch {
    /* ignore */
  }
  pausedTasks.value = pausedTasks.value.filter((p) => !(p.spec.movieId === entry.spec.movieId && p.spec.videoId === entry.spec.videoId))
  savePaused()
}

/* ---------- 状态查询（DetailView 徽章 / 面板勾选） ---------- */

export type EpisodeDlState = 'downloaded' | 'downloading' | 'none'

export function episodeState(movieId: number, videoId: number): {
  state: EpisodeDlState
  percent: number
} {
  if (downloadRecords.value.some((r) => r.movieId === movieId && r.videoId === videoId)) {
    return { state: 'downloaded', percent: 100 }
  }
  const live = liveTasks.value.find((t) => t.spec.movieId === movieId && t.spec.videoId === videoId)
  if (live) return { state: 'downloading', percent: live.percent }
  return { state: 'none', percent: -1 }
}

export function recordOf(movieId: number, videoId: number): DownloadRecord | undefined {
  return downloadRecords.value.find((r) => r.movieId === movieId && r.videoId === videoId)
}

/** 单集 spec 构造（缓存面板勾选确认时用） */
export function episodeSpec(movieId: number, movieTitle: string, seasonNo: number, v: VideoItem): DownloadSpec {
  const base = { movieId, movieTitle, seasonNo, episodeNo: v.episodeNo, videoId: v.id }
  return { ...base, fileBase: fileBaseOf(base) }
}

/* ---------- 记录操作 ---------- */

export async function removeDownload(record: DownloadRecord) {
  await invoke('delete_download', { path: record.path })
  downloadRecords.value = downloadRecords.value.filter((r) => r.taskId !== record.taskId)
  saveRecords()
}

export async function playLocal(record: DownloadRecord) {
  await invoke('play_local', { path: record.path })
}
