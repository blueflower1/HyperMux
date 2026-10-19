import { api } from './http'
import type { AppReleaseVo } from './types'

/** 应用自更新（契约 §3.11）：服务器自管 APK，每平台仅保留最新一条。 */
export const appReleaseApi = {
  /** 平台当前发布（无发布时返回 null） */
  current: (platform: string) => api.get<AppReleaseVo | null>('/app-release', { platform }),
  /** 发布新版本（multipart：file + platform + versionCode + versionName + releaseNotes + mandatory） */
  publish: (form: FormData) => api.postForm<AppReleaseVo>('/app-release', form),
}
