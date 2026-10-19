import { ref, type Ref } from 'vue'
import { getHome } from '@/api'
import type { CategoryItem } from '@/api/types'

/** 模块级共享：首页分区（多组件复用同一份请求） */
const categories: Ref<CategoryItem[]> = ref([])
let loading: Promise<void> | null = null

export function useCategories() {
  const ready = ref(categories.value.length > 0)

  function ensure() {
    if (!loading) {
      loading = getHome()
        .then((h) => {
          categories.value = (h.categories || []).filter((c) => c.movieCount > 0)
          ready.value = true
        })
        .catch(() => {
          loading = null // 失败允许重试
        })
    }
    return loading
  }

  return { categories, ready, ensure }
}
