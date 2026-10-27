<script setup lang="ts">
/** 桌面端右上角下载面板：进行中（百分比/速度/ETA + 暂停）+ 已暂停（继续/删除，断点续传）+ 完成记录（播放/删除）+ 目录管理 */
import { onMounted, ref } from 'vue'
import Icon from '@/components/Icon.vue'
import { useToastStore } from '@/stores/toast'
import { errMsg } from '@/api/http'
import { fmtBytes } from '@/stores/desktop'
import {
  downloadRecords,
  liveTasks,
  pausedTasks,
  downloadDir,
  setDownloadDir,
  pauseTask,
  cancelTask,
  resumePaused,
  removePaused,
  removeDownload,
  playLocal,
  type DownloadRecord,
  type LiveTask,
  type PausedTask,
} from '@/stores/download'

const emit = defineEmits<{ (e: 'close'): void }>()
const toast = useToastStore()
const dlDir = ref('')

onMounted(async () => {
  try {
    dlDir.value = await downloadDir()
  } catch {
    /* ignore */
  }
})

function fmtSpeed(n: number): string {
  if (n <= 0) return '—'
  if (n >= 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB/s'
  if (n >= 1024) return (n / 1024).toFixed(0) + ' KB/s'
  return n + ' B/s'
}

function fmtEta(t: LiveTask): string {
  if (t.total <= 0 || t.speed <= 0) return ''
  const sec = Math.max(0, Math.round((t.total - t.bytes) / t.speed))
  if (sec < 60) return `${sec}s`
  if (sec < 3600) return `${Math.round(sec / 60)} 分钟`
  return `${Math.floor(sec / 3600)} 小时 ${Math.round((sec % 3600) / 60)} 分`
}

function liveMeta(t: LiveTask): string {
  const parts: string[] = []
  if (t.percent >= 0) parts.push(Math.round(t.percent) + '%')
  parts.push(fmtBytes(t.bytes))
  parts.push(fmtSpeed(t.speed))
  const eta = fmtEta(t)
  if (eta) parts.push('剩 ' + eta)
  return parts.join(' · ')
}

async function chooseDir() {
  try {
    const { open } = await import('@tauri-apps/plugin-dialog')
    const picked = await open({ directory: true, title: '选择下载目录', defaultPath: dlDir.value || undefined })
    if (typeof picked === 'string' && picked) {
      setDownloadDir(picked)
      dlDir.value = picked
      toast.show('下载目录已更新')
    }
  } catch (e) {
    toast.error('目录选择失败：' + errMsg(e))
  }
}

async function openDir() {
  try {
    const { openPath } = await import('@tauri-apps/plugin-opener')
    await openPath(dlDir.value)
  } catch {
    toast.error('打开目录失败')
  }
}

async function doPlay(r: DownloadRecord) {
  try {
    await playLocal(r)
  } catch (e) {
    toast.error('本地播放失败：' + errMsg(e))
  }
}

async function doRemove(r: DownloadRecord) {
  try {
    await removeDownload(r)
  } catch (e) {
    toast.error('删除失败：' + errMsg(e))
  }
}

async function doResume(p: PausedTask) {
  resumePaused(p)
}

async function doRemovePaused(p: PausedTask) {
  await removePaused(p)
}
</script>

<template>
  <div class="dl-panel card" @click.stop>
    <div class="dl-panel-head">
      <span class="dl-panel-title">下载</span>
      <span v-if="liveTasks.length" class="dl-panel-count">{{ liveTasks.length }} 个进行中</span>
      <button class="dl-panel-close" type="button" title="关闭" @click="emit('close')">
        <Icon name="x" :size="14" />
      </button>
    </div>

    <div v-if="!liveTasks.length && !pausedTasks.length && !downloadRecords.length" class="dl-panel-empty">
      暂无下载。在影片页点「缓存」即可下载到本地离线观看
    </div>

    <div v-for="t in liveTasks" :key="'live-' + t.id" class="dl-item">
      <div class="dl-item-main">
        <span class="dl-item-name">{{ t.spec.fileBase }}</span>
        <span class="dl-item-meta">{{ liveMeta(t) }}</span>
        <button v-if="t.engine !== 'mpv'" class="dl-item-act" type="button" title="暂停（可续传）" @click="pauseTask(t.id)">暂停</button>
        <button v-else class="dl-item-act" type="button" @click="cancelTask(t.id)">取消</button>
      </div>
      <div class="dl-bar">
        <i :class="{ indeterminate: t.percent < 0 }" :style="t.percent >= 0 ? { width: t.percent + '%' } : {}"></i>
      </div>
    </div>

    <div v-for="p in pausedTasks" :key="'paused-' + p.spec.movieId + '-' + p.spec.videoId" class="dl-item is-paused">
      <div class="dl-item-main">
        <span class="dl-item-name">{{ p.spec.fileBase }}</span>
        <span class="dl-item-meta">已暂停 · {{ fmtBytes(p.bytes) }}</span>
        <button v-if="p.engine !== 'mpv'" class="dl-item-act" type="button" title="从断点继续" @click="doResume(p)">继续</button>
        <button class="dl-item-act danger" type="button" @click="doRemovePaused(p)">删除</button>
      </div>
    </div>

    <div v-for="r in downloadRecords" :key="r.taskId" class="dl-item">
      <div class="dl-item-main">
        <span class="dl-item-name" :title="r.path">{{ r.fileBase }}</span>
        <span class="dl-item-meta">{{ fmtBytes(r.bytes) }}</span>
        <button class="dl-item-act" type="button" title="本地播放" @click="doPlay(r)">
          <Icon name="play" :size="11" /> 播放
        </button>
        <button class="dl-item-act danger" type="button" @click="doRemove(r)">删除</button>
      </div>
    </div>

    <div class="dl-panel-foot">
      <span class="dl-panel-dir" :title="dlDir">{{ dlDir || '…' }}</span>
      <button class="dl-item-act" type="button" @click="chooseDir">更改</button>
      <button class="dl-item-act" type="button" @click="openDir">打开</button>
    </div>
  </div>
</template>
