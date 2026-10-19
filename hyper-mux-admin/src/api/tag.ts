import { api } from './http'
import type { TagVo } from './types'

export interface TagForm {
  name: string
  type: number
  sortNo?: number | null
}

export const tagApi = {
  list: (type?: number) => api.get<{ list: TagVo[] }>('/tags', { type }),
  create: (b: TagForm) => api.post<{ id: number }>('/tags', b),
  update: (id: number, b: Partial<TagForm> & { status?: number }) =>
    api.put<{ id: number }>(`/tags/${id}`, b),
  remove: (id: number) => api.del<{ id: number; unboundMovies: number }>(`/tags/${id}`),
}
