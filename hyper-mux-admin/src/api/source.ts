import { api } from './http'
import type { MovieImportVo, OpenlistEntry, SourceVo } from './types'

export interface SourceSaveForm {
  name: string
  serverUrl: string
  /** 与 sessionId 二选一（账密登录走 sessionId，token 由服务端会话代持） */
  apiToken?: string
  sessionId?: string
  rootPath?: string | null
}

export interface SourceUpdateForm extends Partial<SourceSaveForm> {
  /** 带值 = 更换 token；不带 = 保留 */
  apiToken?: string
  status?: number
}

// ==================== 建源/浏览：OpenList 会话与目录列表 ====================

export interface OpenlistSessionReq {
  serverUrl?: string
  /** 粘贴 Token 方式 */
  apiToken?: string
  /** 账密登录方式 */
  username?: string
  password?: string
  otpCode?: string
  /** 已存源：沿用其 Token（服务端解密，明文永不出参） */
  sourceId?: number
}

export interface OpenlistSessionInfo {
  sessionId: string
  version: string | null
}

export interface OpenlistListResult {
  list: OpenlistEntry[]
  total: number
  page: number
  hasMore: boolean
}

export const openlistApi = {
  /** 建连接会话（探测通过才返回；失败 400 透传原因） */
  session: (b: OpenlistSessionReq) => api.post<OpenlistSessionInfo>('/openlist/session', b),
  /** 以会话列目录（单页；page 从 1 起） */
  list: (sessionId: string, path: string, page: number) =>
    api.post<OpenlistListResult>(`/openlist/session/${sessionId}/list`, { path, page }),
  /** 扫描指定数据源的某目录并解析（新建影片导入用） */
  importFolder: (sourceId: number, path: string) =>
    api.post<MovieImportVo>('/openlist/import', { sourceId, path }),
}

export const sourceApi = {
  list: () => api.get<{ list: SourceVo[] }>('/sources'),
  create: (b: SourceSaveForm) => api.post<{ id: number }>('/sources', b),
  update: (id: number, b: SourceUpdateForm) => api.put<{ id: number }>(`/sources/${id}`, b),
  remove: (id: number) => api.del<{ id: number }>(`/sources/${id}`),
  test: (id: number) => api.post<{ ok: boolean; version: string | null; message: string }>(
    `/sources/${id}/test`,
  ),
}

/** 拼 OpenList 直读 URL：{serverUrl}/d/<rootPath 相对路径逐段编码>（与后端 encodeSegments 同口径） */
export function openlistDirectUrl(serverUrl: string, rootPath: string, relPath: string): string {
  const root = (rootPath || '/').replace(/\/+$/, '')
  const abs = `${root === '' ? '/' : root}/${relPath}`.replace(/^\/+/, '/')
  const segs = abs.split('/').filter(Boolean).map((s) => encodeURIComponent(s))
  return `${serverUrl.replace(/\/+$/, '')}/d/${segs.join('/')}`
}
