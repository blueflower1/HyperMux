import { defineStore } from 'pinia'
import { categoryApi } from '@/api/category'
import { tagApi } from '@/api/tag'
import type { CategoryVo, TagVo } from '@/api/types'

/**
 * 全局下拉数据缓存（50-admin §3）：影视类型列表 + 全量标签。
 * 表单页打开时 ensureAll()；任何 CRUD 后 refreshXxx(true) 强制刷新。
 */
export const useAppStore = defineStore('app', {
  state: () => ({
    categories: [] as CategoryVo[],
    tags: [] as TagVo[],
    categoriesLoaded: false,
    tagsLoaded: false,
  }),
  getters: {
    enabledCategories: (s) => s.categories.filter((c) => c.status === 1),
    regionTags: (s) => s.tags.filter((t) => t.type === 1 && t.status === 1),
    categoryName: (s) => (id: number) => s.categories.find((c) => c.id === id)?.name ?? `#${id}`,
  },
  actions: {
    async refreshCategories(force = false) {
      if (this.categoriesLoaded && !force) return
      const p = await categoryApi.page(1, 200)
      this.categories = p.list
      this.categoriesLoaded = true
    },
    async refreshTags(force = false) {
      if (this.tagsLoaded && !force) return
      const t = await tagApi.list()
      this.tags = t.list
      this.tagsLoaded = true
    },
    ensureAll(force = false) {
      return Promise.all([this.refreshCategories(force), this.refreshTags(force)])
    },
  },
})
