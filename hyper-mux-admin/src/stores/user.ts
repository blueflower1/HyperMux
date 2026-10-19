import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import { setToken } from '@/api/http'

const TOKEN_KEY = 'hypermux.admin.token'
const USER_KEY = 'hypermux.admin.user'

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
    user: JSON.parse(localStorage.getItem(USER_KEY) || 'null') as AdminUser | null,
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
  },
  actions: {
    setSession(session: Session) {
      this.token = session.token
      this.user = {
        id: session.user.id,
        username: session.user.username,
        nickName: session.user.nickName,
        role: 1,
      }
      setToken(session.token)
      localStorage.setItem(USER_KEY, JSON.stringify(this.user))
    },
    async login(username: string, password: string) {
      const session = await authApi.login(username, password)
      this.setSession(session)
    },
    logout() {
      this.token = ''
      this.user = null
      setToken('')
      localStorage.removeItem(USER_KEY)
    },
  },
})
