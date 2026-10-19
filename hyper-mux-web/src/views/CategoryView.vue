<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from '@/components/Icon.vue'
import MovieCard from '@/components/MovieCard.vue'
import { getCategoryMovies } from '@/api'
import { useCategories } from '@/composables/useCategories'
import { errMsg } from '@/api/http'
import { useToastStore } from '@/stores/toast'

const route = useRoute()
const router = useRouter()
const toast = useToastStore()
const { categories, ready, ensure } = useCategories()

const categoryId = computed(() => Number(route.params.id))
const catName = computed(() => categories.value.find((c) => c.id === categoryId.value)?.name)

const keyword = ref('')
const debouncedKw = ref('')
const list = ref<any[]>([])
const page = ref(0)
const total = ref(0)
const loading = ref(false)
const failed = ref(false)
const sentinel = ref<HTMLElement | null>(null)

let io: IntersectionObserver | null = null
let loadSeq = 0

const hasMore = computed(() => list.value.length < total.value)

async function reload() {
  const seq = ++loadSeq
  page.value = 0
  list.value = []
  total.value = 0
  loading.value = true
  failed.value = false
  try {
    const r = await getCategoryMovies(categoryId.value, 1, 30, debouncedKw.value)
    if (seq !== loadSeq) return
    list.value = r.list
    total.value = r.total
    page.value = 1
    if (hasMore.value) loadMore()
  } catch (e) {
    if (seq !== loadSeq) return
    failed.value = true
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

async function loadMore() {
  if (loading.value || !hasMore.value) return
  const seq = loadSeq
  loading.value = true
  try {
    const r = await getCategoryMovies(categoryId.value, page.value + 1, 30, debouncedKw.value)
    if (seq !== loadSeq) return
    list.value = list.value.concat(r.list)
    total.value = r.total
    page.value = r.page
    if (hasMore.value) loadMore()
  } catch (e) {
    if (seq !== loadSeq) return
    toast.error(errMsg(e))
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

function setupSentinel() {
  io?.disconnect()
  io = null
  if (!sentinel.value || typeof IntersectionObserver === 'undefined') return
  io = new IntersectionObserver(
    (entries) => {
      if (entries.some((e) => e.isIntersecting)) loadMore()
    },
    { rootMargin: '600px' },
  )
  io.observe(sentinel.value)
}

let kwTimer: any = null
watch(keyword, (v) => {
  clearTimeout(kwTimer)
  kwTimer = setTimeout(() => {
    debouncedKw.value = v.trim()
    reload()
  }, 350)
})

watch(categoryId, () => {
  reload()
  setupSentinel()
})

watch(() => list.value.length, () => setupSentinel())

onMounted(() => {
  ensure()
  reload()
})

onBeforeUnmount(() => {
  io?.disconnect()
  clearTimeout(kwTimer)
})
</script>

<template>
  <div>
    <div class="page-head">
      <h1 class="page-title">
        {{ catName || '影片列表' }}
        <span v-if="debouncedKw" class="muted">「{{ debouncedKw }}」</span>
      </h1>
      <span v-if="!loading" class="page-total">共 {{ total }} 部</span>
    </div>

    <div class="search-box">
      <Icon name="search" :size="17" />
      <input
        v-model="keyword"
        class="input"
        type="search"
        placeholder="搜索片名 / 原名"
        enterkeyhint="search"
        @keyup.esc="keyword = ''"
      />
      <button v-if="keyword" class="clear" type="button" @click="keyword = ''">
        <Icon name="x" :size="14" />
      </button>
    </div>

    <div v-if="list.length" class="grid">
      <MovieCard v-for="m in list" :key="m.id" :movie="m" />
    </div>

    <div v-if="failed && !list.length" class="empty">
      <Icon name="x" :size="26" />
      <div class="empty-title">加载失败</div>
      <button class="btn" @click="reload">重试</button>
    </div>
    <div v-else-if="!loading && !list.length" class="empty">
      <Icon name="film" :size="26" />
      <div class="empty-title">{{ debouncedKw ? '没有找到相关影片' : '这个分类下还没有内容' }}</div>
      <div v-if="debouncedKw">换个关键词试试</div>
    </div>

    <div v-if="hasMore" ref="sentinel" style="padding: 18px; text-align: center">
      <span v-if="loading" class="spin"></span>
    </div>
  </div>
</template>

<style scoped>
.page-total {
  font-size: 12.5px;
  color: var(--text-3);
}
</style>
