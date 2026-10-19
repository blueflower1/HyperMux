<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Icon from '@/components/Icon.vue'
import Cover from '@/components/Cover.vue'
import { finishProgress, getHistory } from '@/api'
import { errMsg } from '@/api/http'
import type { HistoryItem } from '@/api/types'
import { progressPct, remainText, fmtTime } from '@/utils/format'
import { useToastStore } from '@/stores/toast'

const router = useRouter()
const toast = useToastStore()

const list = ref<HistoryItem[]>([])
const loading = ref(true)
const failed = ref(false)

async function load() {
  loading.value = true
  failed.value = false
  try {
    list.value = (await getHistory(50)) || []
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

function open(item: HistoryItem) {
  router.push(`/movie/${item.movieId}/play?videoId=${item.videoId}`)
}

async function remove(item: HistoryItem) {
  try {
    await finishProgress(item.videoId, 'remove')
    list.value = list.value.filter((x) => x.videoId !== item.videoId)
    toast.show('已删除记录')
  } catch (e) {
    toast.error(errMsg(e))
  }
}

async function markDone(item: HistoryItem) {
  try {
    await finishProgress(item.videoId, 'finish')
    item.finished = 1
    list.value = [...list.value].sort((a, b) => (b.updateTime || 0) - (a.updateTime || 0))
    toast.show('已标记看完')
  } catch (e) {
    toast.error(errMsg(e))
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <h1 class="page-title">观看历史</h1>
      <button v-if="list.length" class="btn btn-ghost" style="height: 34px; font-size: 12.5px" @click="load">
        <Icon name="refresh" :size="14" /> 刷新
      </button>
    </div>

    <div v-if="loading" class="empty" style="padding-top: 90px">
      <div class="spin"></div>
    </div>

    <div v-else-if="failed" class="empty" style="padding-top: 90px">
      <Icon name="x" :size="26" />
      <div class="empty-title">加载失败</div>
      <button class="btn" @click="load">重试</button>
    </div>

    <div v-else-if="!list.length" class="empty" style="padding-top: 90px">
      <Icon name="history" :size="26" />
      <div class="empty-title">还没有观看记录</div>
      开始看片后会出现在这里
    </div>

    <div v-else class="hist-list">
      <div v-for="item in list" :key="item.videoId" class="hist-row">
        <div class="hist-cover">
          <Cover :src="item.coverUrl" :title="item.title" />
          <div class="card-progress" v-if="progressPct(item.positionMs, item.durationMs) > 0">
            <i :style="{ width: progressPct(item.positionMs, item.durationMs) + '%' }"></i>
          </div>
        </div>
        <div class="hist-info" @click="open(item)">
          <div class="hist-title">
            {{ item.title }}
            <span style="color: var(--text-3); font-weight: 500; font-size: 12.5px">
              第{{ item.seasonNo }}季 第{{ item.episodeNo }}集
            </span>
          </div>
          <div class="hist-sub">
            <span v-if="item.categoryName">{{ item.categoryName }}</span>
            <span>{{ fmtTime(item.updateTime, true) }}</span>
            <span v-if="item.finished === 1" class="chip" style="height: 18px; font-size: 10.5px">已看完</span>
            <span v-else>{{ remainText(item.positionMs, item.durationMs) }}</span>
          </div>
          <div class="hist-progress" v-if="item.finished !== 1">
            <i :style="{ width: progressPct(item.positionMs, item.durationMs) + '%' }"></i>
          </div>
        </div>
        <div class="hist-actions">
          <button
            v-if="item.finished !== 1"
            class="icon-btn"
            type="button"
            title="继续播放"
            @click="open(item)"
          >
            <Icon name="play" :size="15" />
          </button>
          <button
            v-else
            class="icon-btn"
            type="button"
            title="标记看完"
            @click="markDone(item)"
          >
            <Icon name="refresh" :size="15" />
          </button>
          <button class="icon-btn" type="button" title="删除记录" @click="remove(item)">
            <Icon name="trash" :size="15" />
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
