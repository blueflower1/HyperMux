import { api } from './http'
import type { MovieLinkOpResultVo, MovieLinkVo } from './types'

/**
 * 一条关联的入参。
 * 服务端成对落两条（双向对称），所以一条入参同时带着「本页显示的文案」与「对方页显示的文案」。
 * null = 不改动已有值；新条目留空则 App 端直接用对方片名。
 */
export interface MovieLinkItemBody {
  relatedMovieId: number
  label?: string | null
  reverseLabel?: string | null
  sortNo?: number | null
  relationType?: 'season' | 'spinoff' | 'custom' | string | null
}

export const movieLinkApi = {
  list: (movieId: number) => api.get<MovieLinkVo[]>(`/movies/${movieId}/links`),
  /** 添加；同一对已存在时按本次提交更新（编辑行内文案/排序也走这里） */
  add: (movieId: number, items: MovieLinkItemBody[]) =>
    api.post<MovieLinkOpResultVo>(`/movies/${movieId}/links`, { items }),
  /** 「按季号重排」：本片涉及的「同剧其他季」关联，两侧一起把 sort_no 归位成各自目标的季号 */
  resort: (movieId: number) => api.post<MovieLinkOpResultVo>(`/movies/${movieId}/links/resort`),
  remove: (movieId: number, linkId: number) =>
    api.del<MovieLinkOpResultVo>(`/movies/${movieId}/links/${linkId}`),
}
