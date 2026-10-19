import { api } from './http'
import type { MovieDetailVo, MovieListVo, MovieStatus, Page, ScrapeImagesResultVo } from './types'

export interface MovieListQuery {
  page: number
  size: number
  keyword?: string
  /** 单个值（PUBLISHED/OFF/DRAFT）或逗号分隔多值（如 "PUBLISHED,OFF"） */
  status?: string
  categoryId?: number
  sortBy?: 'updateTime' | 'sortNo'
}

export interface EpisodeBody {
  seasonNo: number
  episodeNo: number
  title?: string | null
  url: string
  duration?: number | null
}

export interface MovieMainBody {
  title: string
  originalTitle?: string | null
  categoryId: number
  year?: number | null
  coverUrl?: string | null
  backdropUrl?: string | null
  rating?: number | null
  area?: string | null
  language?: string | null
  intro?: string | null
  sortNo?: number
  sourceId?: number | null
  /** 从 OpenList 导入时选中的影片目录绝对路径（仅记录来源） */
  sourcePath?: string | null
  tagIds?: number[]
}

export const movieApi = {
  page: (q: MovieListQuery) => api.get<Page<MovieListVo>>('/movies', q),
  detail: (id: number) => api.get<MovieDetailVo>(`/movies/${id}`),
  create: (b: MovieMainBody & { episodes: EpisodeBody[] }) =>
    api.post<{ id: number; status: MovieStatus; episodeTotal: number }>('/movies', b),
  update: (id: number, b: Partial<MovieMainBody>) => api.put<{ id: number }>(`/movies/${id}`, b),
  publish: (id: number) => api.post<{ id: number; status: MovieStatus }>(`/movies/${id}/publish`),
  /** 刮削海报 + 横图 → 上传到 OpenList 影片目录 images/ → 回写自有服务直链 */
  scrapeImages: (id: number, overwrite = false) =>
    api.post<ScrapeImagesResultVo>(`/movies/${id}/scrape-images?overwrite=${overwrite}`),
  offline: (id: number) => api.post<{ id: number; status: MovieStatus }>(`/movies/${id}/offline`),
  remove: (id: number) =>
    api.del<{ id: number; deletedEpisodes: number; deletedProgress: number }>(`/movies/${id}`),
}
