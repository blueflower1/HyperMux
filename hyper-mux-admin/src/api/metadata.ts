import { api } from './http'
import type { MetadataDetailVo, MetadataImagesVo, MetadataItemVo } from './types'

export const metadataApi = {
  /** 按片名搜索（provider 缺省用「系统设置」当前来源） */
  search: (keyword: string, year?: number | null, provider?: string) =>
    api.get<MetadataItemVo[]>('/metadata/search', {
      keyword,
      year: year ?? undefined,
      provider: provider || undefined,
    }),
  /** 取详情用于一键填充表单 */
  detail: (providerId: string, mediaType?: string, provider?: string) =>
    api.get<MetadataDetailVo>('/metadata/detail', {
      providerId,
      mediaType: mediaType || undefined,
      provider: provider || undefined,
    }),
  /** 取该条目的全部候选图片（竖版海报 / 横版剧照），供「选择封面」挑选 */
  images: (providerId: string, mediaType?: string, provider?: string) =>
    api.get<MetadataImagesVo>('/metadata/images', {
      providerId,
      mediaType: mediaType || undefined,
      provider: provider || undefined,
    }),
}
