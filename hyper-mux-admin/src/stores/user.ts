import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import { setToken } from '@/api/http'

const TOKEN_KEY = 'hypermux.admin.token'
const USER_KEY = 'hypermux.admin.user'
const EXPIRES_KEY = 'hypermux.admin.expiresAt'

export interface Session {
  token: string
  expiresAt: number
  user: { id: number; username: string; nickName: string | null }
}

export interface AdminUser {
  id: number
  username: string
  nickName: string | null
  role: number
}

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    expiresAt: Number(localStorage.getItem(EXPIRES_KEY) || 0),
    user: JSON.parse(localStorage.getItem(USER_KEY) || 'null') as AdminUser | null,
  }),
  getters: {
    // 过期时间已知且已过 → 视为未登录（服务端 401 之外的本地兜底）
    isLoggedIn: (s) => !!s.token && (s.expiresAt <= 0 || s.expiresAt > Date.now()),
  },
  actions: {
    setSession(session: Session) {
      this.token = session.token
      this.expiresAt = session.expiresAt || 0
      this.user = {
        id: session.user.id,
        username: session.user.username,
        nickName: session.user.nickName,
        role: 1,
      }
      setToken(session.token)
      if (this.expiresAt) localStorage.setItem(EXPIRES_KEY, String(this.expiresAt))
      else localStorage.removeItem(EXPIRES_KEY)
      localStorage.setItem(USER_KEY, JSON.stringify(this.user))
    },
    async login(username: string, password: string) {
      const session = await authApi.login(username, password)
      this.setSession(session)
    },
    logout() {
      this.token = ''
      this.expiresAt = 0
      this.user = null
      setToken('')
      localStorage.removeItem(USER_KEY)
      localStorage.removeItem(EXPIRES_KEY)
    },
  },
})
