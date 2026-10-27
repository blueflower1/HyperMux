import { api } from './http'
import type {
  CategoryItem,
  CastAccepted,
  CastConfig,
  CastDevice,
  CastPollResult,
  ContinueItem,
  HistoryItem,
  LoginResult,
  MovieCard,
  MovieDetail,
  Page,
  PlayUrl,
  ProgressReportResult,
  QrCreateResult,
  QrStatusResult,
  SeasonResult,
} from './types'

/** 首页聚合（契约 3.2） */
export function getHome() {
  return api.get<{ categories: CategoryItem[]; continueWatching: ContinueItem[] }>('/home')
}

/** 分类内影片分页（契约 3.3）：keyword 片名/原名模糊 */
export function getCategoryMovies(categoryId: number, page = 1, size = 30, keyword?: string) {
  return api.get<Page<MovieCard>>(`/category/${categoryId}/movies`, { page, size, keyword: keyword || undefined })
}

/** 全局搜索（契约 3.10）：片名/原名模糊，仅已上架，分页 */
export function searchMovies(keyword: string, page = 1, size = 30) {
  return api.get<Page<MovieCard>>('/search', { keyword, page, size })
}

/** 影片详情（契约 3.4） */
export function getMovieDetail(id: number) {
  return api.get<MovieDetail>(`/movie/${id}`)
}

/** 剧集列表（契约 3.5）：仅 missing=0，按季分组 */
export function getMovieSeasons(id: number) {
  return api.get<SeasonResult>(`/movie/${id}/videos`)
}

/** 换取播放直链（契约 3.6）。OpenList 解析可能偏慢，放宽超时 */
export function getPlayUrl(videoId: number) {
  return api.post<PlayUrl>('/play-url', { videoId }, 30000)
}

/** 历史记录（契约 3.7） */
export function getHistory(limit = 50) {
  return api.get<HistoryItem[]>('/history', { limit })
}

/** 断点上报（契约 3.8）：幂等 upsert */
export function reportProgress(videoId: number, positionMs: number, durationMs?: number, finished?: number) {
  return api.put<ProgressReportResult>('/progress', { videoId, positionMs, durationMs, finished })
}

/** 标记看完 / 删除记录（契约 3.9） */
export function finishProgress(videoId: number, action: 'finish' | 'remove') {
  return api.post<{ videoId: number; action: string }>('/progress/finish', { videoId, action })
}

/** 登录（契约 3.1）：role=0 用户 */
export function login(username: string, password: string) {
  return api
    .post<LoginResult>('/auth/login', { username, password })
    .catch((e) => {
      // 登录限频 429 / 401 需要可读文案
      const msg = e instanceof Error ? e.message : '登录失败'
      throw new Error(msg)
    })
}

/** 扫码登录（契约同 TV 端）：匿名创建一次性登录码（60s） */
export function qrCreate() {
  return api.post<QrCreateResult>('/auth/qr/create')
}

/** 扫码登录：轮询状态；CONFIRMED 时 session 一次性投递 */
export function qrStatus(id: string) {
  return api.get<QrStatusResult>('/auth/qr/status', { id })
}

/* ---------- 远程投屏（契约 §3.13）：web 端 = 接收端 ---------- */

/** 设备注册（幂等 upsert）。platform = 'web'（浏览器）或 'windows'（桌面壳），由 stores/cast.ts 判定 */
export function registerDevice(deviceId: string, deviceName: string, platform: string) {
  return api.post<{ deviceId: string }>('/devices/register', {
    deviceId,
    platform,
    deviceName,
  })
}

/** 同账号设备列表（platform 可空 = 全部） */
export function getCastDevices(platform?: string) {
  return api.get<CastDevice[]>('/devices', { platform: platform || undefined })
}

/** 接收端轮询（2s）：待确认请求 + 需要停止的投屏 id；顺带即设备在线心跳 */
export function castPoll(deviceId: string) {
  return api.get<CastPollResult>('/cast/poll', { deviceId })
}

/** 确认（reason = user-yes | countdown） */
export function castAccept(id: number, reason: string) {
  return api.post<CastAccepted>('/cast/accept', { id, reason })
}

/** 拒绝（reason = user-no）。幂等。 */
export function castReject(id: number, reason = 'user-no') {
  return api.post<boolean>('/cast/reject', { id, reason })
}

/** 取消投屏（reason = sender-cancel | tv-exit | web-exit | desktop-exit）。幂等。 */
export function castCancel(id: number, reason = 'sender-cancel') {
  return api.post<boolean>('/cast/cancel', { id, reason })
}

/** 投屏配置（开关 / 确认倒计时 / 轮询节奏） */
export function getCastConfig() {
  return api.get<CastConfig>('/cast/config')
}
