import axios from 'axios'

const TOKEN_KEY = 'hypermux.admin.token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token: string) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

function clearSession() {
  setToken('')
  localStorage.removeItem('hypermux.admin.user')
}

function handleUnauthorized() {
  clearSession()
  if (!location.hash.startsWith('#/login')) {
    location.hash = '#/login'
  }
}

/**
 * 业务错误：code = HTTP 状态码 = 业务码（契约 §1）。
 * data 携带服务端附带的提示数据（池删除 409 → {id, movieCount} 等）。
 */
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
  baseURL: '/api/admin',
  timeout: 15000,
})

http.interceptors.request.use((config) => {
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
  return new ApiError(0, '网络异常，请检查后端服务')
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

/** 统一请求出口：拦截器已解包 R<T>，这里泛型声明返回 data。 */
export const api = {
  get: <T = any>(url: string, params?: Record<string, any>) =>
    http.get<any, T>(url, { params }),
  post: <T = any>(url: string, body?: any) => http.post<any, T>(url, body),
  postForm: <T = any>(url: string, form: FormData, timeoutMs = 300_000) =>
    http.post<any, T>(url, form, { timeout: timeoutMs }),
  put: <T = any>(url: string, body?: any) => http.put<any, T>(url, body),
  del: <T = any>(url: string) => http.delete<any, T>(url),
}
