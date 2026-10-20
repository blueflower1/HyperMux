<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ArtPlayer from 'artplayer'
import Icon from '@/components/Icon.vue'
import { finishProgress, getMovieDetail, getMovieSeasons, getPlayUrl, reportProgress } from '@/api'
import { getToken, errMsg } from '@/api/http'
import { apiBase } from '@/stores/server'
import type { MovieDetail, Season, VideoItem } from '@/api/types'
import { useToastStore } from '@/stores/toast'
import { fmtSec, progressPct } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const toast = useToastStore()

const movieId = computed(() => Number(route.params.id))
const videoId = computed(() => {
  const q = route.query.videoId
  const n = Number(Array.isArray(q) ? q[0] : q)
  return Number.isFinite(n) && n > 0 ? n : null
})

const detail = ref<MovieDetail | null>(null)
const seasons = ref<Season[]>([])
const metaLoading = ref(true)

const flat = computed(() => seasons.value.flatMap((s) => s.videos.map((v) => ({ v, s: s.seasonNo }))))
const currentVideo = computed(() => flat.value.find((f) => f.v.id === videoId.value)?.v || null)

/* ---------- 播放器 ---------- */
// 注意：artplayer 会直接覆写 container.innerHTML，
// 必须用独立子节点承载播放器，不能与 Vue 管理的 overlay 节点共用容器
const playerHost = ref<HTMLDivElement | null>(null)
let player: ArtPlayer | null = null
const playState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const errorText = ref('')
let resumePendingMs = 0
let seekDone = false
let autoNextTimer: number | undefined
let reportTimer: number | undefined
let eventCleanups: Array<() => void> = []

// 按住快放（移动端）：单指按住 260ms → 2 倍速，松开还原
const holdFast = ref(false)
let holdTimer: number | undefined
let holding = false
let rateBefore = 1
let activePointers = 0
let downX = 0
let downY = 0
let suppressClickUntil = 0

/** 当前媒体元素（artplayer 5.x 公开访问器：player.video） */
function vid(): HTMLVideoElement | null {
  const p = player
  return p && !p.isDestroy && p.video ? p.video : null
}

function reportNow(finished?: number) {
  const v = vid()
  const id = videoId.value
  if (!v || !id || !Number.isFinite(v.duration)) return
  const pos = Math.floor(v.currentTime * 1000)
  const dur = v.duration > 0 ? Math.floor(v.duration * 1000) : undefined
  if (!finished && pos < 3000) return
  reportProgress(id, pos, dur, finished).catch(() => {})
}

/** 页面卸载/切后台时的兜底上报（keepalive，尽力而为） */
function reportKeepalive() {
  const v = vid()
  const id = videoId.value
  if (!v || !id || !Number.isFinite(v.duration) || v.duration <= 0 || v.currentTime * 1000 < 3000) return
  try {
    fetch(apiBase() + '/progress', {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${getToken()}`,
      },
      body: JSON.stringify({
        videoId: id,
        positionMs: Math.floor(v.currentTime * 1000),
        durationMs: Math.floor(v.duration * 1000),
      }),
      keepalive: true,
    }).catch(() => {})
  } catch {
    /* ignore */
  }
}

function destroyPlayer() {
  if (autoNextTimer) {
    clearTimeout(autoNextTimer)
    autoNextTimer = undefined
  }
  for (const fn of eventCleanups) {
    try {
      fn()
    } catch {
      /* ignore */
    }
  }
  eventCleanups = []
  const p = player
  player = null
  if (p) {
    try {
      p.destroy()
    } catch {
      /* ignore */
    }
  }
}

function startPlayback() {
  if (!player || player.isDestroy) return
  player.play().catch(() => {
    // 移动端拦截自动播放时保持暂停，用户点画面即可继续
  })
}

/* ---------- 键盘快捷键（页面级，不依赖播放器焦点） ---------- */
function onKeydown(e: KeyboardEvent) {
  const t = e.target as HTMLElement | null
  if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return
  if (e.ctrlKey || e.metaKey || e.altKey) return
  const p = player
  if (!p || p.isDestroy) return
  switch (e.code) {
    case 'Space':
      if (e.repeat) return
      e.preventDefault()
      p.toggle() // 空格：暂停 / 播放
      break
    case 'ArrowLeft':
      if (e.repeat) return
      e.preventDefault()
      p.backward = 10 // ← 快退 10s
      break
    case 'ArrowRight':
      if (e.repeat) return
      e.preventDefault()
      p.forward = 10 // → 快进 10s
      break
    case 'ArrowUp':
      e.preventDefault()
      p.volume = Math.min(1, p.volume + 0.05)
      break
    case 'ArrowDown':
      e.preventDefault()
      p.volume = Math.max(0, p.volume - 0.05)
      break
  }
}

/* ---------- 按住 2x 快放（仅触屏） ---------- */
function clearHoldTimer() {
  if (holdTimer) {
    clearTimeout(holdTimer)
    holdTimer = undefined
  }
}

function enterHold() {
  const p = player
  if (!p || p.isDestroy) return
  holding = true
  holdFast.value = true
  rateBefore = p.playbackRate
  p.playbackRate = 2
}

function exitHold(activated: boolean) {
  clearHoldTimer()
  if (activated) {
    const p = player
    if (p && !p.isDestroy) p.playbackRate = rateBefore
    // 抑制这次“抬手”在播放器上合成的 click（否则会被当成点击暂停/切控制条）
    suppressClickUntil = Date.now() + 500
  }
  holding = false
  holdFast.value = false
}

function onPointerDown(e: PointerEvent) {
  if (e.pointerType !== 'touch') return
  activePointers++
  if (activePointers > 1 || holding) {
    exitHold(holding)
    return
  }
  downX = e.clientX
  downY = e.clientY
  clearHoldTimer()
  holdTimer = window.setTimeout(() => enterHold(), 260)
}

function onPointerMove(e: PointerEvent) {
  if (e.pointerType !== 'touch' || !holdTimer) return
  // 移动超过阈值说明用户在拖拽（artplayer 手势），退出按住态
  if (Math.hypot(e.clientX - downX, e.clientY - downY) > 14) exitHold(false)
}

function onPointerUp(e: PointerEvent) {
  if (e.pointerType !== 'touch') return
  activePointers = Math.max(0, activePointers - 1)
  if (activePointers > 0) return
  exitHold(holding)
}

function onClickCapture(e: MouseEvent) {
  if (Date.now() < suppressClickUntil) {
    e.stopPropagation()
    e.preventDefault()
  }
}

/** 绑定到播放器宿主上的交互监听（键盘 + 按住快放） */
function attachPlayerUx(host: HTMLElement) {
  host.addEventListener('pointerdown', onPointerDown)
  host.addEventListener('pointermove', onPointerMove)
  host.addEventListener('pointerup', onPointerUp)
  host.addEventListener('pointercancel', onPointerUp)
  host.addEventListener('click', onClickCapture, true)
}

function detachPlayerUx(host: HTMLElement) {
  host.removeEventListener('pointerdown', onPointerDown)
  host.removeEventListener('pointermove', onPointerMove)
  host.removeEventListener('pointerup', onPointerUp)
  host.removeEventListener('pointercancel', onPointerUp)
  host.removeEventListener('click', onClickCapture, true)
}

async function play(video: VideoItem) {
  destroyPlayer()
  playState.value = 'loading'
  errorText.value = ''
  seekDone = false

  // 该集服务端断点
  const prog = video.progress
  resumePendingMs = prog && prog.finished !== 1 && prog.positionMs > 30_000 ? prog.positionMs : 0

  let url = ''
  try {
    const pu = await getPlayUrl(video.id)
    url = pu.url
  } catch (e) {
    playState.value = 'error'
    errorText.value = errMsg(e)
    return
  }
  // 请求期间组件可能已卸载/切集
  if (videoId.value !== video.id) return
  const container = playerHost.value
  if (!container) return

  try {
    player = new ArtPlayer({
      container,
      url,
      poster: detail.value?.coverUrl || '',
      volume: 0.9,
      autoplay: false, // 手动控制起播，先做断点 seek
      playbackRate: true,
      pip: true,
      autoOrientation: true,
      setting: true,
      flip: false,
      fullscreen: true, // 原生全屏按钮
      fullscreenWeb: true, // 网页内全屏按钮
      // artplayer 5.x 默认 playsInline: true，兼容 iOS 横竖屏内嵌
      autoPlayback: false, // 断点续播由后端 progress 实现，不用 artplayer 本地版
      hotkey: false, // 键盘由 PlayView 自己实现（不依赖玩家焦点）
      moreVideoAttr: { preload: 'auto' },
    })
  } catch (e) {
    player = null
    playState.value = 'error'
    errorText.value = '播放器初始化失败：' + errMsg(e)
    return
  }

  const p = player

  function handleMetadata() {
    if (seekDone) return
    seekDone = true
    if (playState.value === 'loading') playState.value = 'ready'
    if (!resumePendingMs) {
      startPlayback()
      return
    }
    // seek 到断点；seek 完成后再起播
    let seekTimeout: number | undefined
    const onSeeked = () => {
      if (seekTimeout) clearTimeout(seekTimeout)
      p.off('video:seeked', onSeeked)
      if (resumePendingMs) toast.show('已定位到上次播放位置')
      startPlayback()
    }
    p.on('video:seeked', onSeeked)
    seekTimeout = window.setTimeout(() => {
      // 大章节 seek 可能较慢，超时兜底直接起播
      p.off('video:seeked', onSeeked)
      startPlayback()
    }, 15_000)
    try {
      p.currentTime = resumePendingMs / 1000
    } catch {
      if (seekTimeout) clearTimeout(seekTimeout)
      p.off('video:seeked', onSeeked)
      startPlayback()
    }
  }

  p.on('video:loadedmetadata', handleMetadata)
  p.on('video:play', () => {
    if (playState.value === 'loading') playState.value = 'ready'
  })
  p.on('video:pause', () => reportNow())
  p.on('video:ended', onEnded)
  p.on('video:error', () => {
    playState.value = 'error'
    errorText.value =
      '视频加载失败：可能是浏览器不支持该编码格式（如部分手机/系统不支持 4K AV1），或网络不稳定。换个剧集或设备试试。'
  })

  // 切后台/离开页面立即上报
  const onVis = () => {
    if (document.visibilityState === 'hidden') reportKeepalive()
  }
  document.addEventListener('visibilitychange', onVis)
  const onUnload = () => reportKeepalive()
  window.addEventListener('pagehide', onUnload)
  eventCleanups.push(() => {
    document.removeEventListener('visibilitychange', onVis)
    window.removeEventListener('pagehide', onUnload)
  })
}

function onEnded() {
  const id = videoId.value
  reportNow(1)
  if (id) finishProgress(id, 'finish').catch(() => {})
  const idx = flat.value.findIndex((f) => f.v.id === id)
  const next = idx >= 0 ? flat.value[idx + 1] : null
  toast.show(next ? '本集已看完，即将播放下一集' : '播放结束')
  if (next) {
    autoNextTimer = window.setTimeout(() => selectVideo(next.v.id), 2_500)
  }
}

function selectVideo(id: number) {
  if (id === videoId.value) return
  router.replace({ query: { ...route.query, videoId: String(id) } })
}

function goPrev() {
  const v = prevNext.value.prev
  if (v) selectVideo(v.id)
}
function goNext() {
  const v = prevNext.value.next
  if (v) selectVideo(v.id)
}

const prevNext = computed(() => {
  const idx = flat.value.findIndex((f) => f.v.id === videoId.value)
  return {
    prev: idx > 0 ? flat.value[idx - 1].v : null,
    next: idx >= 0 && idx < flat.value.length - 1 ? flat.value[idx + 1].v : null,
  }
})

async function markFinished() {
  const id = videoId.value
  if (!id) return
  try {
    reportNow(1)
    await finishProgress(id, 'finish')
    toast.show('已标记看完')
    // 刷新当前集的进度态
    const v = currentVideo.value
    if (v?.progress) v.progress.finished = 1
  } catch (e) {
    toast.error(errMsg(e))
  }
}

function onRetry() {
  const v = currentVideo.value
  if (v) play(v)
}

function epState(v: VideoItem): string {
  if (v.id === videoId.value) return 'is-playing'
  if (!v.progress) return ''
  if (v.progress.finished === 1) return 'is-watched'
  return 'is-progress'
}

/* ---------- 元数据 ---------- */
async function loadMeta() {
  metaLoading.value = true
  try {
    const [d, s] = await Promise.all([getMovieDetail(movieId.value), getMovieSeasons(movieId.value)])
    detail.value = d
    seasons.value = s.seasons || []
  } catch (e) {
    errorText.value = errMsg(e)
    playState.value = 'error'
  } finally {
    metaLoading.value = false
  }
}

/** 未指定集时的默认选择：服务端断点集 → 第 1 集；仅改 query，起播由 watch 负责 */
function pickDefault() {
  const prog = detail.value?.progress
  const target =
    prog && prog.finished !== 1
      ? prog.videoId
      : flat.value.length
        ? flat.value[0].v.id
        : null
  if (target) {
    router.replace({ query: { ...route.query, videoId: String(target) } })
  } else if (!metaLoading.value) {
    playState.value = 'error'
    errorText.value = '暂无可播放的剧集'
  }
}

watch(movieId, async (_id, old) => {
  if (old == null) return // 首次加载由 onMounted 处理
  // 切到另一部影片：停播并重新选集
  destroyPlayer()
  playState.value = 'idle'
  errorText.value = ''
  await loadMeta()
  if (!currentVideo.value) pickDefault()
})

watch(videoId, (id) => {
  if (id && currentVideo.value) play(currentVideo.value)
})

onMounted(async () => {
  reportTimer = window.setInterval(() => {
    // 播放中每 10s 上报一次
    const v = vid()
    const id = videoId.value
    if (!v || !id || v.paused || v.ended) return
    reportNow()
  }, 10_000)

  document.addEventListener('keydown', onKeydown)
  const host = playerHost.value
  if (host) attachPlayerUx(host)

  await loadMeta()

  const v0 = currentVideo.value
  if (v0) {
    // URL 自带 videoId：直接起播（videoId 不再变化，watch 不会重复触发）
    play(v0)
    return
  }
  pickDefault()
})

onBeforeUnmount(() => {
  if (reportTimer) {
    clearInterval(reportTimer)
    reportTimer = undefined
  }
  document.removeEventListener('keydown', onKeydown)
  const host = playerHost.value
  if (host) detachPlayerUx(host)
  clearHoldTimer()
  holding = false
  holdFast.value = false
  reportKeepalive()
  destroyPlayer()
})
</script>

<template>
  <div class="play-page">
    <div class="play-topbar">
      <button class="icon-btn" type="button" title="返回" @click="router.back()">
        <Icon name="chev-left" :size="20" />
      </button>
      <div class="play-title">
        <template v-if="detail">{{ detail.title }}<span v-if="currentVideo" class="ep-tag">第{{ currentVideo.episodeNo }} 集</span></template>
        <template v-else>加载中…</template>
      </div>
      <button
        class="icon-btn"
        type="button"
        title="回到影片页"
        @click="router.push(`/movie/${movieId}`)"
      >
        <Icon name="chev-down" :size="18" />
      </button>
    </div>

    <div class="player-box">
      <div ref="playerHost" class="player-host"></div>
      <div v-if="playState === 'loading'" class="player-state">
        <div class="spin" style="border-top-color: #37d67a"></div>
        <span>正在获取播放地址…</span>
      </div>
      <div v-else-if="playState === 'error'" class="player-state">
        <div class="err-ic"><Icon name="x" :size="22" /></div>
        <div style="max-width: 420px">{{ errorText || '播放失败' }}</div>
        <button v-if="currentVideo" class="btn" style="border-color: rgba(255,255,255,.25); color: #dfe3ee" @click="onRetry">
          重试
        </button>
      </div>
      <div v-if="holdFast" class="hold-badge">2x 快放中</div>
    </div>

    <div v-if="playState === 'ready' || playState === 'loading'" class="play-actions">
      <button class="btn" :disabled="!prevNext.prev" @click="goPrev">
        <Icon name="skip-next" :size="14" style="transform: rotate(180deg)" /> 上一集
      </button>
      <button class="btn" :disabled="!prevNext.next" @click="goNext">
        下一集 <Icon name="skip-next" :size="14" />
      </button>
      <button v-if="currentVideo" class="btn" @click="markFinished">
        <Icon name="check" :size="15" /> 标记看完
      </button>
    </div>

    <div v-if="seasons.length" class="play-eps">
      <h3 class="ep-title">全部剧集（{{ flat.length }}）</h3>
      <div class="ep-grid">
        <button
          v-for="f in flat"
          :key="f.v.id"
          class="ep-btn"
          :class="epState(f.v)"
          type="button"
          :title="`第${f.s}季 第${f.v.episodeNo}集${f.v.duration ? ' · ' + fmtSec(f.v.duration) : ''}`"
          @click="selectVideo(f.v.id)"
        >
          {{ f.v.title || `第 ${f.v.episodeNo} 集` }}
          <span
            v-if="f.v.progress && f.v.progress.finished !== 1"
            class="ep-progress"
          ><i
            :style="{ width: progressPct(f.v.progress.positionMs, f.v.progress.durationMs) + '%' }"
          /></span>
        </button>
      </div>
    </div>
  </div>
</template>
