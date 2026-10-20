// 类型与 30-api-contract §3 一一对应（TV/App 端接口，web 端复用 role=0 用户）
export interface SessionUser {
  id: number
  username: string
  nickName: string
}

export interface LoginResult {
  token: string
  expiresAt: number
  user: SessionUser
}

export interface CategoryItem {
  id: number
  name: string
  coverUrl: string | null
  movieCount: number
}

export interface LastVideoRef {
  id: number
  seasonNo: number
  episodeNo: number
}

/** 首页「继续观看」项 */
export interface ContinueItem {
  movieId: number
  title: string
  coverUrl: string | null
  categoryName: string | null
  lastVideo: LastVideoRef
  positionMs: number | null
  durationMs: number | null
}

export interface ProgressLite {
  videoId: number
  seasonNo: number
  episodeNo: number
  positionMs: number
  durationMs: number
}

export interface MovieCard {
  id: number
  title: string
  coverUrl: string | null
  rating: number | null
  year: number | null
  area: string | null
  episodeTotal: number
  tags: string[]
  progress: ProgressLite | null
  updateTime: number
}

export interface Page<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export interface TagRef {
  id: number
  name: string
  type: number
}

export interface ProgressFull {
  videoId: number
  seasonNo: number
  episodeNo: number
  positionMs: number
  durationMs: number
  finished: number
}

export interface MovieDetail {
  id: number
  title: string
  originalTitle: string | null
  year: number | null
  coverUrl: string | null
  rating: number | null
  area: string | null
  language: string | null
  intro: string | null
  tags: TagRef[]
  episodeTotal: number
  category: CategoryRef
  progress: ProgressFull | null
}

export interface CategoryRef {
  id: number
  name: string
}

export interface VideoProgress {
  positionMs: number
  durationMs: number
  finished: number
}

export interface VideoItem {
  id: number
  episodeNo: number
  title: string | null
  /** 单位秒（与断点的 ms 区分） */
  duration: number | null
  progress: VideoProgress | null
}

export interface Season {
  seasonNo: number
  videos: VideoItem[]
}

export interface SeasonResult {
  movieId: number
  seasons: Season[]
}

export interface PlayUrl {
  url: string
  headers: Record<string, string>
  expiresAt: number | null
}

export interface HistoryItem {
  videoId: number
  movieId: number
  title: string
  coverUrl: string | null
  categoryName: string | null
  seasonNo: number
  episodeNo: number
  positionMs: number
  durationMs: number
  finished: number
  updateTime: number
}

export interface ProgressReportResult {
  videoId: number
  positionMs: number
  finished: number
  updateTime: number
}

/** 扫码登录（契约同 TV 端） */
export interface QrCreateResult {
  qrId: string
  expiresIn: number
}

export interface QrStatusResult {
  state: 'PENDING' | 'SCANNED' | 'CONFIRMED' | 'EXPIRED'
  session: LoginResult | null
}
