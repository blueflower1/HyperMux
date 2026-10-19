<script setup lang="ts">
// 影片封面卡（横向 rail 与网格复用）
import { computed } from 'vue'
import Cover from '@/components/Cover.vue'
import Icon from '@/components/Icon.vue'
import type { MovieCard } from '@/api/types'
import { progressPct } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    movie: MovieCard
    /** rail 模式：固定宽横向滚动 */
    rail?: boolean
  }>(),
  { rail: false },
)

const target = computed(() => {
  const p = props.movie.progress
  const name = p ? `第${p.seasonNo}季 第${p.episodeNo}集` : ''
  return { to: `/movie/${props.movie.id}`, name }
})

const ratingText = computed(() =>
  props.movie.rating != null ? Number(props.movie.rating).toFixed(1) : '',
)

const pct = computed(() =>
  props.movie.progress
    ? progressPct(props.movie.progress.positionMs, props.movie.progress.durationMs)
    : 0,
)

const progress = computed(() => props.movie.progress)
</script>

<template>
  <router-link class="movie-card" :class="{ 'is-rail': rail }" :to="target.to">
    <div class="movie-cover">
      <Cover :src="movie.coverUrl" :title="movie.title" />
      <span v-if="ratingText" class="badge-rating">
        <Icon name="star" :size="11" /> {{ ratingText }}
      </span>
      <span v-if="progress" class="badge-ep">第{{ progress.episodeNo }}集</span>
      <div v-if="pct > 0" class="card-progress">
        <i :style="{ width: pct + '%' }"></i>
      </div>
    </div>
    <div class="movie-meta">
      <div class="movie-title">{{ movie.title }}</div>
      <div class="movie-sub">
        <template v-if="movie.year">{{ movie.year }} </template>
        <template v-if="movie.area">· {{ movie.area }}</template>
        <template v-if="movie.episodeTotal"> · {{ movie.episodeTotal }}集</template>
      </div>
      <div v-if="movie.tags && movie.tags.length" class="movie-tags">
        <span v-for="t in movie.tags.slice(0, 2)" :key="t" class="tag-chip">{{ t }}</span>
      </div>
    </div>
  </router-link>
</template>
