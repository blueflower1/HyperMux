<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Icon from '@/components/Icon.vue'
import Cover from '@/components/Cover.vue'
import MovieCard from '@/components/MovieCard.vue'
import CategoryRail from '@/components/CategoryRail.vue'
import { getHome, searchMovies } from '@/api'
import { errMsg } from '@/api/http'
import { useCategories } from '@/composables/useCategories'
import { useToastStore } from '@/stores/toast'
import type { ContinueItem, MovieCard as MovieCardT } from '@/api/types'
import { progressPct, remainText } from '@/utils/format'

const { categories, ready, ensure } = useCategories()
const toast = useToastStore()

const continueList = ref<ContinueItem[]>([])
const failed = ref(false)

// ---------- 全局搜索 ----------
const searchKw = ref('')
const debouncedKw = ref('')
const results = ref<MovieCardT[]>([])
const total = ref(0)
const page = ref(0)
const searchLoading = ref(false)
const searchFailed = ref(false)
const sentinel = ref<HTMLElement | null>(null)

let io: IntersectionObserver | null = null
let seq = 0
let kwTimer: number | undefined

const searching = computed(() => debouncedKw.value.length > 0)
const hasMore = computed(() => results.value.length < total.value)

function flushSearch() {
  clearTimeout(kwTimer)
  const t = searchKw.value.trim()
  if (t !== debouncedKw.value) {
    debouncedKw.value = t
    if (t) reloadSearch()
  }
}

async function reloadSearch() {
  const s = ++seq
  page.value = 0
  results.value = []
  total.value = 0
  searchLoading.value = true
  searchFailed.value = false
  try {
    const r = await searchMovies(debouncedKw.value, 1, 30)
    if (s !== seq) return
    results.value = r.list
    total.value = r.total
    page.value = 1
    if (hasMore.value) loadMoreSearch()
  } catch {
    if (s !== seq) return
    searchFailed.value = true
  } finally {
    if (s === seq) searchLoading.value = false
  }
}

async function loadMoreSearch() {
  if (searchLoading.value || !hasMore.value) return
  const s = seq
  searchLoading.value = true
  try {
    const r = await searchMovies(debouncedKw.value, page.value + 1, 30)
    if (s !== seq) return
    results.value = results.value.concat(r.list)
    total.value = r.total
    page.value = r.page
    if (hasMore.value) loadMoreSearch()
  } catch (e) {
    toast.error(errMsg(e))
  } finally {
    if (s === seq) searchLoading.value = false
  }
}

function setupSentinel() {
  io?.disconnect()
  io = null
  if (!sentinel.value || typeof IntersectionObserver === 'undefined') return
  io = new IntersectionObserver(
    (entries) => {
      if (entries.some((e) => e.isIntersecting)) loadMoreSearch()
    },
    { rootMargin: '600px' },
  )
  io.observe(sentinel.value)
}

watch(searchKw, (v) => {
  clearTimeout(kwTimer)
  kwTimer = window.setTimeout(() => {
    const t = v.trim()
    if (t !== debouncedKw.value) {
      debouncedKw.value = t
      if (t) reloadSearch()
    }
  }, 300)
})

watch(() => results.value.length, () => setupSentinel())

// ---------- 首页 ----------
const catPick = computed(() => categories.value.filter((c) => c.movieCount > 0))

onMounted(async () => {
  ensure()
  try {
    const h = await getHome()
    continueList.value = h.continueWatching || []
  } catch {
    failed.value = true
  }
})

onBeforeUnmount(() => {
  io?.disconnect()
  clearTimeout(kwTimer)
})
</script>

<template>
  <div>
    <!-- 全局搜索 -->
    <div class="search-box">
      <Icon name="search" :size="17" />
      <input
        v-model="searchKw"
        class="input"
        type="search"
        placeholder="搜索片名 / 原名"
        enterkeyhint="search"
        @keyup.esc="searchKw = ''"
        @keyup.enter="flushSearch"
      />
      <button v-if="searchKw" class="clear" type="button" @click="searchKw = ''">
        <Icon name="x" :size="14" />
      </button>
      <button class="search-btn" type="button" @click="flushSearch">搜索</button>
    </div>

    <!-- 移动端分类入口（桌面端在侧栏） -->
    <div v-if="catPick.length" class="cat-chips">
      <router-link
        v-for="c in catPick"
        :key="`chip-${c.id}`"
        class="cat-chip"
        :to="`/category/${c.id}`"
      >
        {{ c.name }}<i>{{ c.movieCount }}</i>
      </router-link>
    </div>

    <!-- 搜索模式：只留结果区 -->
    <template v-if="searching">
      <div class="page-head">
        <h1 class="page-title">
          搜索结果 <span class="muted">「{{ debouncedKw }}」</span>
        </h1>
        <span v-if="!searchLoading && total" class="page-total">共 {{ total }} 部</span>
      </div>

      <div v-if="results.length" class="grid">
        <MovieCard v-for="m in results" :key="m.id" :movie="m" />
      </div>

      <div v-if="searchFailed && !results.length" class="empty">
        <Icon name="x" :size="26" />
        <div class="empty-title">搜索失败</div>
        <button class="btn" @click="reloadSearch">重试</button>
      </div>
      <div v-else-if="!searchLoading && !results.length" class="empty">
        <Icon name="film" :size="26" />
        <div class="empty-title">没有找到相关影片</div>
        换个关键词试试
      </div>

      <div v-if="hasMore" ref="sentinel" style="padding: 18px; text-align: center">
        <span v-if="searchLoading" class="spin"></span>
      </div>
    </template>

    <!-- 常规首页 -->
    <template v-else>
      <!-- 继续观看 -->
      <section v-if="continueList.length" class="section">
        <div class="section-head">
          <h2 class="section-title">继续观看</h2>
        </div>
        <div class="rail">
          <router-link
            v-for="c in continueList"
            :key="c.movieId"
            class="continue-card card"
            :to="`/movie/${c.movieId}`"
          >
            <div class="continue-cover">
              <Cover :src="c.coverUrl" :title="c.title" />
              <div class="continue-play"><Icon name="play" :size="18" /></div>
            </div>
            <div class="continue-meta">
              <div class="movie-title">{{ c.title }}</div>
              <div class="continue-sub">
                <template v-if="c.lastVideo">第{{ c.lastVideo.seasonNo }}季 {{ c.lastVideo.episodeNo }}集 · </template>
                {{ remainText(c.positionMs, c.durationMs) || '继续播放' }}
              </div>
              <div class="hist-progress">
                <i :style="{ width: progressPct(c.positionMs, c.durationMs) + '%' }"></i>
              </div>
            </div>
          </router-link>
        </div>
      </section>

      <template v-if="categories.length">
        <CategoryRail v-for="c in catPick" :key="c.id" :category="c" />
        <div v-if="catPick.length === 0" class="empty">
          <Icon name="film" :size="26" />
          <div class="empty-title">还没有内容</div>
          等待管理员上架影片后刷新
        </div>
      </template>

      <div v-else-if="!ready && !failed" class="empty">
        <div class="spin"></div>
        加载中…
      </div>
      <div v-else-if="failed" class="empty">
        <Icon name="x" :size="26" />
        <div class="empty-title">加载失败</div>
        请检查网络后刷新页面
      </div>
    </template>
  </div>
</template>

<style scoped>
/* 首页搜索框：嵌入「搜索」按钮（分类页同款 .search-box 不受影响） */
.search-box .input {
  padding-right: 100px;
}
.search-btn {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  height: 30px;
  padding: 0 15px;
  border: none;
  border-radius: var(--r-pill);
  background: var(--primary);
  color: var(--on-primary);
  font-size: 13px;
  font-weight: 600;
  line-height: 1;
  cursor: pointer;
}
.search-btn:hover {
  background: var(--primary-hover);
}
.search-box .clear {
  right: 74px;
}
.page-total {
  font-size: 12.5px;
  color: var(--text-3);
}
.continue-card {
  width: 320px;
  max-width: 100%;
  display: flex;
  gap: 12px;
  padding: 10px;
  flex: 0 0 auto;
  scroll-snap-align: start;
}
.continue-cover {
  position: relative;
  width: 84px;
  flex: none;
  aspect-ratio: 2 / 3;
  border-radius: var(--r-sm);
  overflow: hidden;
}
.continue-play {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(10, 12, 18, 0.35);
  color: #fff;
  opacity: 0;
  transition: opacity 0.15s ease;
}
.continue-card:hover .continue-play {
  opacity: 1;
}
.continue-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 5px;
}
.continue-sub {
  font-size: 12.5px;
  color: var(--text-3);
}
</style>
