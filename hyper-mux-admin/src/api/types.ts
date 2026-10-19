// 后台接口共享响应形状（事实源：docs/plans/30-api-contract.md v1.1）

export interface Page<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export interface TagRef {
  id: number
  name: string
}

export interface CategoryVo {
  id: number
  name: string
  coverUrl: string | null
  description: string | null
  sortNo: number | null
  status: number
  movieCount: number
  createTime: number
  updateTime: number
}

export type MovieStatus = 'PUBLISHED' | 'DRAFT' | 'OFF'

export interface MovieListVo {
  id: number
  title: string
  originalTitle: string | null
  categoryId: number
  categoryName: string | null
  year: number | null
  coverUrl: string | null
  rating: number | null
  area: string | null
  language: string | null
  intro: string | null
  episodeTotal: number
  missingCount: number
  tags: TagRef[]
  status: MovieStatus
  sourceType: string | null
  sourceId: number | null
  sourceName: string | null
  sourcePath: string | null
  sortNo: number | null
  createTime: number
  updateTime: number
}

export interface MovieDetailVo {
  id: number
  title: string
  originalTitle: string | null
  categoryId: number | null
  year: number | null
  coverUrl: string | null
  backdropUrl: string | null
  rating: number | null
  area: string | null
  language: string | null
  intro: string | null
  sortNo: number | null
  sourceId: number | null
  status: MovieStatus
  tags: TagRef[]
}

export type UrlKind = 'REMOTE' | 'OPENLIST'

export interface VideoVo {
  id: number
  seasonNo: number
  episodeNo: number
  title: string | null
  urlKind: UrlKind
  openlistPath: string | null
  remoteUrl: string | null
  size: number | null
  duration: number | null
  sourceFile: string | null
  fingerprint: string | null
  missing: number
  updateTime: number
}

export interface VideoListData {
  movieId: number
  list: VideoVo[]
  missingCount: number
}

export interface TagVo {
  id: number
  name: string
  type: number
  sortNo: number
  status: number
  movieCount: number
}

export interface UserVo {
  id: number
  username: string
  nickName: string | null
  role: number
  status: number
  lastLoginTime: number | null
  createTime: number
}

export interface DashboardVo {
  categories: number
  publishedMovies: number
  draftMovies: number
  users: number
  sources: number
}

export interface SourceVo {
  id: number
  name: string
  serverUrl: string
  /** 默认浏览根目录（前导 /、无尾 /） */
  rootPath: string
  status: number
  movieCount: number
  createTime: number
  updateTime: number
}

/** OpenList 目录条目（浏览 / 导入共用） */
export interface OpenlistEntry {
  name: string
  path: string
  size: number
  dir: boolean
  modifiedMs: number
}

/** 目录导入解析结果（新建影片「从 OpenList 导入」自动填充） */
export interface ImportedEpisodeVo {
  seasonNo: number
  episodeNo: number
  title: string | null
  relPath: string
  fileName: string
  size: number
}

export interface MovieImportVo {
  sourceId: number
  serverUrl: string
  rootPath: string
  /** 选中的影片目录绝对路径 */
  path: string
  /** 相对源根目录（无前导 /） */
  relPath: string
  title: string
  year: number | null
  parsed: boolean
  episodes: ImportedEpisodeVo[]
  posters: string[]
  dirCount: number
  skippedDirs: number
}

/** 元数据搜索结果 */
export interface MetadataItemVo {
  providerId: string
  mediaType: string
  title: string
  originalTitle: string | null
  year: number | null
  posterUrl: string | null
}

/** 元数据详情（一键填充表单） */
export interface MetadataDetailVo {
  providerId: string
  mediaType: string
  title: string
  originalTitle: string | null
  year: number | null
  rating: number | null
  intro: string | null
  posterUrl: string | null
  backdropUrl: string | null
  area: string | null
  genres: string[]
}

export interface SettingsVo {
  metadataProvider: 'tmdb' | 'douban'
  tmdbApiKey: string
}

/** 元数据候选图片（挑封面用） */
export interface ImageCandidateVo {
  url: string
  label: string | null
  width: number | null
  height: number | null
}

export interface MetadataImagesVo {
  posters: ImageCandidateVo[]
  backdrops: ImageCandidateVo[]
}

/** 横图刮削 + 图片写入 OpenList 影片目录的返回（阶段 3） */
export interface ScrapeImagesResultVo {  id: number
  title: string
  provider: string
  matchedTitle: string | null
  matchedYear: number | null
  coverUrl: string | null
  backdropUrl: string | null
  uploaded: string[]
  message: string | null
}

// ---- 应用自更新（契约 §3.11）----

/** 平台当前发布（无发布时 data 为 null；每平台仅最新一条） */
export interface AppReleaseVo {
  platform: 'mobile' | 'tv'
  versionCode: number
  versionName: string
  sha256: string
  sizeBytes: number
  releaseNotes: string
  mandatory: boolean
  updateTime: number
}
