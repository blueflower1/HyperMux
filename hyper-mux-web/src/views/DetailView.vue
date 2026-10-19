<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from '@/components/Icon.vue'
import Cover from '@/components/Cover.vue'
import { getMovieDetail, getMovieSeasons } from '@/api'
import type { MovieDetail, Season, VideoItem } from '@/api/types'
import { errMsg } from '@/api/http'
import { useToastStore } from '@/stores/toast'
import { fmtSec, progressPct, remainText } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const toast = useToastStore()

const movieId = computed(() => Number(route.params.id))

const detail = ref<MovieDetail | null>(null)
const seasons = ref<Season[]>([])
const loading = ref(true)
const failed = ref(false)

const activeSeason = ref(1)
const introExpanded = ref(false)

function flatVideos(): { video: VideoItem; seasonNo: number }[] {
  return seasons.value.flatMap((s) => s.videos.map((v) => ({ video: v, seasonNo: s.seasonNo })))
}

const flat = computed(flatVideos)
const current = computed(() => {
  const d = detail.value
  if (!d?.progress) return null
  return flat.value.find((f) => f.video.id === d.progress!.videoId) || null
})

const playText = computed(() => {
  const p = detail.value?.progress
  if (p && current.value && p.finished !== 1 && p.positionMs < 60_000 && p.positionMs > 0) {
    return { main: '从头开始', sub: `第${p.seasonNo}季 第${p.episodeNo}集` }
  }
  if (p && p.finished !== 1 && p.positionMs > 0) {
    return { main: `续播 第${p.seasonNo}季 第${p.episodeNo}集`, sub: remainText(p.positionMs, p.durationMs) || '' }
  }
  if (current.value) {
    return { main: '继续观看', sub: `第${current.value.seasonNo}季 第${current.value.video.episodeNo}集` }
  }
  return { main: '开始播放', sub: '' }
})

function startPlay(videoId?: number) {
  const target =
    videoId ??
    (detail.value?.progress && detail.value.progress.finished !== 1 ? detail.value.progress.videoId : undefined) ??
    flat.value[0]?.video.id
  if (!target) {
    toast.error('暂无可播放的剧集')
    return
  }
  router.push(`/movie/${movieId.value}/play?videoId=${target}`)
}

function epState(v: VideoItem): { cls: string; pct: number } | null {
  if (!v.progress) return null
  const pct = progressPct(v.progress.positionMs, v.progress.durationMs)
  if (v.progress.finished === 1) return { cls: 'is-watched', pct: 100 }
  if (detail.value?.progress?.videoId === v.id) return { cls: 'is-playing', pct }
  return { cls: 'is-progress', pct }
}

async function load() {
  loading.value = true
  failed.value = false
  detail.value = null
  seasons.value = []
  try {
    const [d, s] = await Promise.all([getMovieDetail(movieId.value), getMovieSeasons(movieId.value)])
    detail.value = d
    seasons.value = s.seasons || []
    if (seasons.value.length) {
      const cur = d.progress
      activeSeason.value = cur ? cur.seasonNo : seasons.value[0].seasonNo
    }
  } catch (e) {
    failed.value = true
    if (e instanceof Error && e.message.includes('404')) {
      toast.error('影片不存在或已下架')
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(movieId, load)
</script>

<template>
  <div v-if="loading" class="empty" style="padding-top: 120px">
    <div class="spin"></div>
  </div>

  <div v-else-if="failed" class="empty" style="padding-top: 120px">
    <Icon name="x" :size="28" />
    <div class="empty-title">加载失败</div>
    <button class="btn" @click="load">重试</button>
  </div>

  <div v-else-if="detail">
    <!-- 头部信息 -->
    <div class="hero card">
      <div class="hero-cover">
        <Cover :src="detail.coverUrl" :title="detail.title" />
      </div>
      <div class="hero-body">
        <h1 class="hero-title">{{ detail.title }}</h1>
        <div v-if="detail.originalTitle" class="hero-origin">{{ detail.originalTitle }}</div>

        <div class="hero-meta">
          <span v-if="detail.rating != null" class="chip">
            <Icon name="star" :size="12" /> {{ Number(detail.rating).toFixed(1) }}
          </span>
          <span v-if="detail.year" class="chip">{{ detail.year }}</span>
          <span v-if="detail.area" class="chip">{{ detail.area }}</span>
          <span v-if="detail.language" class="chip">{{ detail.language }}</span>
          <span class="chip">{{ detail.episodeTotal }} 集</span>
          <span v-if="detail.category" class="chip">{{ detail.category.name }}</span>
        </div>

        <div v-if="detail.tags && detail.tags.length" class="hero-tags">
          <span v-for="t in detail.tags" :key="t.id" class="chip" style="font-size: 11.5px; height: 20px">{{ t.name }}</span>
        </div>

        <div v-if="detail.intro" class="hero-intro" :class="{ clamped: !introExpanded }">{{ detail.intro }}</div>
        <button
          v-if="detail.intro && detail.intro.length > 60"
          class="intro-toggle"
          type="button"
          @click="introExpanded = !introExpanded"
        >
          {{ introExpanded ? '收起' : '展开' }}
        </button>

        <div class="hero-actions">
          <button class="btn btn-primary" type="button" @click="startPlay()">
            <Icon name="play" :size="16" /> {{ playText.main }}
            <span v-if="playText.sub" style="font-weight: 500; opacity: 0.85">· {{ playText.sub }}</span>
          </button>
          <button
            class="btn"
            type="button"
            v-if="!detail.progress"
            @click="startPlay(flat.length ? flat[0].video.id : undefined)"
          >
            <Icon name="play" :size="14" /> 从第 1 集开始
          </button>
        </div>
      </div>
    </div>

    <!-- 选集 -->
    <div class="episodes card" v-if="seasons.length">
      <div class="ep-head">
        <h2 class="section-title" style="font-size: 15.5px">选集</h2>
      </div>

      <div v-if="seasons.length > 1" class="season-tabs" style="margin-bottom: 12px">
        <button
          v-for="s in seasons"
          :key="s.seasonNo"
          class="season-tab"
          :class="{ active: s.seasonNo === activeSeason }"
          type="button"
          @click="activeSeason = s.seasonNo"
        >
          第{{ s.seasonNo }}季
        </button>
      </div>

      <div class="ep-grid">
        <button
          v-for="v in seasons.find((s) => s.seasonNo === activeSeason)?.videos || []"
          :key="v.id"
          class="ep-btn"
          :class="epState(v)?.cls"
          type="button"
          :title="v.title || `第${v.episodeNo}集${v.duration ? ' · ' + fmtSec(v.duration) : ''}`"
          @click="startPlay(v.id)"
        >
          {{ v.title || v.episodeNo }}
          <span v-if="epState(v)" class="ep-progress"><i :style="{ width: epState(v)!.pct + '%' }"></i></span>
        </button>
      </div>
    </div>
    <div v-else class="empty">
      <Icon name="film" :size="24" />
      <div class="empty-title">暂无可播放的剧集</div>
    </div>
  </div>
</template>
