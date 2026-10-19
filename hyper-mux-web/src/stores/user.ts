import { defineStore } from 'pinia'
import { login as apiLogin } from '@/api'
import { getToken, setToken, getExp } from '@/api/http'
import type { SessionUser } from '@/api/types'

const USER_KEY = 'hypermux.web.user'

function readUser(): SessionUser | null {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? (JSON.parse(raw) as SessionUser) : null
  } catch {
    return null
  }
}

function sessionValid(): boolean {
  const token = getToken()
  if (!token) return false
  const exp = getExp()
  // 本地预检提前 60s，服务端 401 兜底
  return exp <= 0 || Date.now() < exp - 60_000
}

export const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken(),
    expiresAt: getExp(),
    user: readUser(),
  }),
  getters: {
    isLoggedIn: (s) => !!s.token && (s.expiresAt <= 0 || Date.now() < s.expiresAt - 60_000),
  },
  actions: {
    /** token 是否在本地有效期内（启动自举用） */
    valid() {
      return sessionValid()
    },
    /** 成功登录 */
    save(token: string, expiresAt: number, user: SessionUser) {
      this.token = token
      this.expiresAt = expiresAt
      this.user = user
      setToken(token, expiresAt)
      localStorage.setItem(USER_KEY, JSON.stringify(user))
    },
    async login(username: string, password: string) {
      const r = await apiLogin(username.trim(), password)
      this.save(r.token, r.expiresAt, r.user)
    },
    clear() {
      this.token = ''
      this.expiresAt = 0
      this.user = null
      setToken('')
      localStorage.removeItem(USER_KEY)
    },
  },
})
