import axios from 'axios'
import { useUserStore } from '@/stores/user'
import { apiBase } from '@/stores/server'

const TOKEN_KEY = 'hypermux.web.token'
const EXP_KEY = 'hypermux.web.tokenExp'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token: string, expiresAt?: number) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
    if (expiresAt) localStorage.setItem(EXP_KEY, String(expiresAt))
  } else {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(EXP_KEY)
  }
}

export function getExp(): number {
  const v = Number(localStorage.getItem(EXP_KEY) || 0)
  return Number.isFinite(v) ? v : 0
}

function handleUnauthorized() {
  const user = useUserStore()
  user.clear()
  if (!location.hash.startsWith('#/login')) {
    location.hash = '#/login'
  }
}

/** 业务错误：code = HTTP 状态码 = 业务码（契约 §1）。 */
export class ApiError extends Error {
  code: number
  data: any

  constructor(code: number, msg: string, data?: any) {
    super(msg)
    this.name = 'ApiError'
    this.code = code
    this.data = data
  }
}

export function errMsg(e: unknown): string {
  return e instanceof Error && e.message ? e.message : '出错了'
}

const http = axios.create({
  timeout: 15000,
})

http.interceptors.request.use((config) => {
  // 服务器档位可能在运行时切换，baseURL 每次请求动态解析
  config.baseURL = apiBase()
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

function extractError(err: any): ApiError {
  const status = err.response?.status
  const d = err.response?.data
  if (d && typeof d === 'object' && 'code' in d) {
    return new ApiError(d.code, d.msg || `请求失败(${d.code})`, d.data)
  }
  if (status) {
    return new ApiError(status, `请求失败(HTTP ${status})`)
  }
  if (err.code === 'ECONNABORTED' || /timeout/i.test(err.message || '')) {
    return new ApiError(0, '请求超时，请检查网络')
  }
  return new ApiError(0, '网络异常，请检查网络或稍后重试')
}

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return (body.data ?? null)
      if (body.code === 401) handleUnauthorized()
      throw new ApiError(body.code, body.msg || `请求失败(${body.code})`, body.data)
    }
    return body
  },
  (err) => {
    const status = err.response?.status
    if (status === 401) handleUnauthorized()
    throw extractError(err)
  },
)

/** 统一请求出口：拦截器已解包 R<T>，泛型声明返回 data。 */
export const api = {
  get: <T = any>(url: string, params?: Record<string, any>) =>
    http.get<any, T>(url, { params }),
  post: <T = any>(url: string, body?: any, timeout?: number) =>
    http.post<any, T>(url, body, { timeout }),
  put: <T = any>(url: string, body?: any) => http.put<any, T>(url, body),
  del: <T = any>(url: string) => http.delete<any, T>(url),
}
