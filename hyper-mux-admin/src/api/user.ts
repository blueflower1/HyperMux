import { api } from './http'
import type { Page, UserVo } from './types'

export interface UserSaveForm {
  username: string
  password: string
  nickName?: string | null
  role?: number
}

export const userApi = {
  page: (page: number, size: number, keyword?: string, role?: number, status?: number) =>
    api.get<Page<UserVo>>('/users', { page, size, keyword, role, status }),
  create: (b: UserSaveForm) => api.post<{ id: number; username: string }>('/users', b),
  update: (id: number, b: { nickName?: string | null; role?: number; status?: number }) =>
    api.put<{ id: number }>(`/users/${id}`, b),
  resetPassword: (id: number, password?: string) =>
    api.post<{ userId: number; username: string; newPassword: string }>(
      `/users/${id}/reset-password`,
      password ? { password } : {},
    ),
  remove: (id: number) => api.del<{ id: number; deletedProgress: number }>(`/users/${id}`),
}
