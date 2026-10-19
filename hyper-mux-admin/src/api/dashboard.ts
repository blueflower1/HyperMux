import { api } from './http'
import type { DashboardVo } from './types'

export const dashboardApi = {
  get: () => api.get<DashboardVo>('/dashboard'),
}
