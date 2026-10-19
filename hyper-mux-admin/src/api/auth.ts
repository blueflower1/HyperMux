import { api } from './http'
import type { Session } from '@/stores/user'

export const authApi = {
  login: (username: string, password: string) => api.post<Session>('/auth/login', { username, password }),
  me: () => api.get<{ id: number; username: string; nickName: string | null; role: number; lastLoginTime: number | null }>('/auth/me'),
}
