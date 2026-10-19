import { api } from './http'
import type { CategoryVo, Page } from './types'

export interface CategoryForm {
  name: string
  coverUrl?: string | null
  description?: string | null
  sortNo?: number | null
}

export interface CategoryUpdateForm extends Partial<CategoryForm> {
  status?: number
}

export const categoryApi = {
  page: (page: number, size: number, keyword?: string) =>
    api.get<Page<CategoryVo>>('/categories', { page, size, keyword }),
  create: (b: CategoryForm) => api.post<{ id: number }>('/categories', b),
  update: (id: number, b: CategoryUpdateForm) => api.put<{ id: number }>(`/categories/${id}`, b),
  remove: (id: number) => api.del<null>(`/categories/${id}`),
}
