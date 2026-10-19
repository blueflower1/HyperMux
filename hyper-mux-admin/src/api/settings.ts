import { api } from './http'
import type { SettingsVo } from './types'

export const settingsApi = {
  get: () => api.get<SettingsVo>('/settings'),
  update: (b: Partial<SettingsVo>) => api.put<SettingsVo>('/settings', b),
}
