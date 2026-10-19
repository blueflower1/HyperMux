import { api } from './http'
import type {
  CategoryItem,
  ContinueItem,
  HistoryItem,
  LoginResult,
  MovieCard,
  MovieDetail,
  Page,
  PlayUrl,
  ProgressReportResult,
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
