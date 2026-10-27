<script setup lang="ts">
/**
 * 桌面端「缓存剧集」面板（交互对齐 Android 缓存面板）：
 * 勾选网格 + 季切换 + 全选切换 + 状态徽章（✓ 已下载 / % 下载中），确认后入队。
 * 入口在影片页播放按钮旁，覆盖全部季。
 */
import { computed, ref } from 'vue'
import Icon from '@/components/Icon.vue'
import type { Season, VideoItem } from '@/api/types'
import { enqueueDownloads, episodeSpec, episodeState } from '@/stores/download'

const props = defineProps<{
  movieId: number
  movieTitle: string
  seasons: Season[]
  initialSeasonNo: number
}>()
const emit = defineEmits<{ (e: 'close'): void }>()

const curNo = ref(props.initialSeasonNo)
const curSeason = computed(
  () => props.seasons.find((s) => s.seasonNo === curNo.value) || props.seasons[0]
)

function stateOf(v: VideoItem) {
  return episodeState(props.movieId, v.id)
}

// 可勾选 = 未下载且未在下载中（跨季）
const selectableIds = computed(
  () =>
    new Set(
      props.seasons
        .flatMap((s) => s.videos)
        .filter((v) => stateOf(v).state === 'none')
        .map((v) => v.id)
    )
)

// 打开即全选所有可下载集（Android 面板同款默认）
const selected = ref<Set<number>>(new Set(selectableIds.value))

const selectedCount = computed(() => selected.value.size)
const allSelected = computed(
  () => selectableIds.value.size > 0 && [...selectableIds.value].every((id) => selected.value.has(id))
)

function toggle(v: VideoItem) {
  if (!selectableIds.value.has(v.id)) return
  const next = new Set(selected.value)
  if (next.has(v.id)) next.delete(v.id)
  else next.add(v.id)
  selected.value = next
}

function toggleAll() {
  selected.value = allSelected.value ? new Set() : new Set(selectableIds.value)
}

function confirm() {
  const specs: { movieId: number; movieTitle: string; seasonNo: number; episodeNo: number; videoId: number; fileBase: string }[] = []
  for (const s of props.seasons) {
    for (const v of s.videos) {
      if (selected.value.has(v.id) && stateOf(v).state === 'none') {
        specs.push(episodeSpec(props.movieId, props.movieTitle, s.seasonNo, v))
      }
    }
  }
  if (specs.length) enqueueDownloads(specs)
  emit('close')
}
</script>

<template>
  <teleport to="body">
    <div class="dl-overlay" @click.self="emit('close')">
      <div class="dl-sheet card">
        <div class="dl-head">
          <div class="dl-head-text">
            <div class="dl-title">缓存剧集</div>
            <div class="dl-sub">{{ movieTitle }}</div>
          </div>
          <button class="icon-btn" type="button" title="关闭" @click="emit('close')">
            <Icon name="x" :size="15" />
          </button>
        </div>

        <div v-if="seasons.length > 1" class="season-tabs">
          <button
            v-for="s in seasons"
            :key="s.seasonNo"
            class="season-tab"
            :class="{ active: s.seasonNo === curSeason.seasonNo }"
            type="button"
            @click="curNo = s.seasonNo"
          >
            第{{ s.seasonNo }}季
          </button>
        </div>

        <div class="dl-toolbar">
          <span class="dl-count">已选 {{ selectedCount }} 集</span>
          <button
            class="dl-toggle-all"
            type="button"
            :class="{ disabled: selectableIds.size === 0 }"
            :disabled="selectableIds.size === 0"
            @click="toggleAll"
          >
            {{ allSelected ? '取消全选' : '全选' }}
          </button>
        </div>

        <div class="dl-grid">
          <button
            v-for="v in curSeason.videos"
            :key="v.id"
            class="dl-cell"
            :class="{
              'is-selected': selected.has(v.id),
              'is-done': stateOf(v).state === 'downloaded',
              'is-busy': stateOf(v).state === 'downloading',
            }"
            type="button"
            :disabled="stateOf(v).state !== 'none'"
            @click="toggle(v)"
          >
            <span class="dl-mark">
              <Icon v-if="stateOf(v).state === 'downloaded' || selected.has(v.id)" name="check" :size="13" />
            </span>
            <span class="dl-ep">{{ v.title || `第${v.episodeNo}集` }}</span>
            <span v-if="stateOf(v).state === 'downloading'" class="dl-pct">
              {{ stateOf(v).percent >= 0 ? Math.round(stateOf(v).percent) + '%' : '…' }}
            </span>
          </button>
        </div>

        <div class="dl-foot">
          <button class="btn" type="button" @click="emit('close')">取消</button>
          <button class="btn btn-primary" type="button" :disabled="selectedCount === 0" @click="confirm">
            <Icon name="download" :size="14" /> 开始缓存{{ selectedCount ? ` ${selectedCount} 集` : '' }}
          </button>
        </div>
      </div>
    </div>
  </teleport>
</template>
