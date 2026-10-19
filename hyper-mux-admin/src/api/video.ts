import { api } from './http'
import type { VideoListData } from './types'
import type { EpisodeBody } from './movie'

export interface EpisodeEditBody extends EpisodeBody {
  id?: number
}

export interface ImportError {
  line: number
  msg: string
}

export const videoApi = {
  list: (movieId: number) => api.get<VideoListData>(`/movies/${movieId}/videos`),
  replace: (movieId: number, episodes: EpisodeEditBody[]) =>
    api.put<{ total: number; added: number; updated: number; removed: number }>(
      `/movies/${movieId}/videos`,
      { episodes },
    ),
  importText: (movieId: number, seasonNo: number, text: string) =>
    api.post<{ added: number; updated: number; errors: ImportError[] }>(
      `/movies/${movieId}/videos/import`,
      { seasonNo, text },
    ),
}
