<script setup lang="ts">
// 首页分类分区：自行懒加载前 12 张（进入分区可视范围即请求）
import { onBeforeUnmount, onMounted, ref } from 'vue'
import Icon from '@/components/Icon.vue'
import MovieCard from '@/components/MovieCard.vue'
import { getCategoryMovies } from '@/api'
import type { CategoryItem, MovieCard as MovieCardT } from '@/api/types'

const props = defineProps<{ category: CategoryItem }>()

const movies = ref<MovieCardT[]>([])
const loading = ref(true)
const failed = ref(false)

let io: IntersectionObserver | null = null
const root = ref<HTMLElement | null>(null)
let loaded = false

async function load() {
  if (loaded) return
  loaded = true
  try {
    const page = await getCategoryMovies(props.category.id, 1, 12)
    movies.value = page.list
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (!root.value || typeof IntersectionObserver === 'undefined') {
    load()
    return
  }
  io = new IntersectionObserver(
    (entries) => {
      if (entries.some((e) => e.isIntersecting)) {
        load()
        io?.disconnect()
      }
    },
    { rootMargin: '400px' },
  )
  io.observe(root.value)
})

onBeforeUnmount(() => io?.disconnect())
</script>

<template>
  <section ref="root" class="section">
    <div class="section-head">
      <router-link class="section-title" :to="`/category/${category.id}`">{{ category.name }}</router-link>
      <router-link class="section-more" :to="`/category/${category.id}`">
        全部 <Icon name="chev-right" :size="14" />
      </router-link>
    </div>

    <div v-if="loading" class="rail" style="gap: 14px">
      <div v-for="n in 6" :key="n" class="skeleton-card card" />
    </div>
    <div v-else-if="movies.length" class="rail">
      <div v-for="m in movies" :key="m.id" class="rail-card">
        <MovieCard :movie="m" rail />
      </div>
    </div>
    <div v-else class="rail">
      <div class="empty-card">{{ failed ? '加载失败' : '暂无内容' }}</div>
    </div>
  </section>
</template>

<style scoped>
.skeleton-card {
  width: 124px;
  aspect-ratio: 2 / 3;
  border-radius: var(--r);
  background: linear-gradient(100deg, var(--surface-2) 40%, var(--surface) 50%, var(--surface-2) 60%);
  background-size: 200% 100%;
  animation: shimmer 1.4s infinite linear;
  flex: none;
}
@keyframes shimmer {
  to {
    background-position: -200% 0;
  }
}
.empty-card {
  padding: 30px 20px;
  border-radius: var(--r);
  border: 1px dashed var(--line-strong);
  color: var(--text-3);
  font-size: 13px;
}
</style>
