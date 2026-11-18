<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ArtPlayer from 'artplayer'
import Icon from '@/components/Icon.vue'
import MovieCard from '@/components/MovieCard.vue'
import RailScroller from '@/components/RailScroller.vue'
import { finishProgress, getMovieDetail, getMovieSeasons, getPlayUrl, reportProgress } from '@/api'
import { getToken, errMsg } from '@/api/http'
import { apiBase } from '@/stores/server'
import { isDesktop } from '@/stores/desktop'
import { playLocal, recordOf } from '@/stores/download'
import { cancelActiveCast, castActive, castPending, onCastStopped } from '@/stores/cast'
import type { MovieDetail, Season, VideoItem } from '@/api/types'
import { useToastStore } from '@/stores/toast'
import { fmtSec, progressPct } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const toast = useToastStore()

/* 双击画面切全屏是 artplayer 的类级默认（Artplayer.DBCLICK_FULLSCREEN），不在构造参数里，
   播放时每次点击才读取，所以这里直接关掉：双击常常只是想看看控制条，误进全屏很难退出。
   全屏仍可由播放器右下角按钮、或浏览器/桌面壳自身操作。 */
;(ArtPlayer as unknown as { DBCLICK_FULLSCREEN: boolean }).DBCLICK_FULLSCREEN = false

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
const currentSeasonNo = computed(() => flat.value.find((f) => f.v.id === videoId.value)?.s ?? null)
const isCurrentWatched = computed(() => currentVideo.value?.progress?.finished === 1)
const watchedCount = computed(() => flat.value.filter((f) => f.v.progress?.finished === 1).length)

/* ---------- 关联影片（「其他季」，契约 §3.4 的 relatedMovies） ---------- */
// 只是浏览入口：点卡片进那一部的详情页，不参与「上一集/下一集」与自动连播（本期口径）
const related = computed(() => detail.value?.relatedMovies ?? [])
const relatedTitle = computed(() =>
  related.value.length && related.value.every((r) => r.relationType === 'season') ? '其他季' : '关联影片',
)

/* ---------- 已下载集的本地播放（桌面端） ---------- */
const localRecord = computed(() =>
  currentVideo.value ? recordOf(movieId.value, currentVideo.value.id) || null : null
)

async function playLocalNow() {
  if (!localRecord.value) return
  try {
    await playLocal(localRecord.value)
    toast.show('已在本地播放核心打开')
  } catch (e) {
    toast.error('本地播放失败：' + errMsg(e))
  }
}

/* ---------- 播放器 ---------- */
// 注意：artplayer 会直接覆写 container.innerHTML，
// 必须用独立子节点承载播放器，不能与 Vue 管理的 overlay 节点共用容器
const playerHost = ref<HTMLDivElement | null>(null)
let player: ArtPlayer | null = null
const playState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const errorText = ref('')
let resumePendingMs = 0
let resumeFromCast = false
let seekDone = false
let autoNextTimer: number | undefined
let reportTimer: number | undefined
let eventCleanups: Array<() => void> = []
let offCastStopped: (() => void) | undefined

/* ---------- 投屏（web 端 = 接收端） ---------- */

/** 手机端送来的起播位置（App.vue 接受投屏时写进 query），优先于该集的服务端断点 */
function castPosMs(): number | null {
  const q = route.query.castPos
  const raw = Array.isArray(q) ? q[0] : q
  if (raw == null || raw === '') return null
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : null
}

/** 投屏被手机端取消 / 被同账号其它设备接管：停播并回影片页 */
function onCastStoppedByPeer() {
  if (autoNextTimer) {
    clearTimeout(autoNextTimer)
    autoNextTimer = undefined
  }
  reportNow() // 先把最后位置落库再拆播放器，否则手机下次播这集会从头开始
  destroyPlayer()
  playState.value = 'idle'
  toast.show('投屏已结束：手机端已取消，或被同账号其它设备接管')
  router.push(`/movie/${movieId.value}`)
}

/* ---------- 桌面壳 mpv 解码兜底（仅 Tauri 环境） ---------- */
const isDesktopShell = isDesktop()
let lastPlayUrl = ''
let lastPlayHeaders: Record<string, string> = {}

async function playWithMpv() {
  if (!lastPlayUrl) return
  try {
    const { Command } = await import('@tauri-apps/plugin-shell')
    const args = [lastPlayUrl, '--force-window', '--autofit=70%', '--title=HyperMux 本地解码']
    for (const [k, v] of Object.entries(lastPlayHeaders || {})) {
      args.push(`--http-header-fields=${k}: ${v}`)
    }
    await Command.sidecar('binaries/mpv', args).execute()
    toast.show('已用本地解码核心打开')
  } catch (e) {
    toast.error('本地解码核心启动失败：' + errMsg(e))
  }
}

/** 播放页存续期间开启 Rust 侧关闭双击确认（对齐 TV 端 BACK 防误触） */
async function setCloseGuard(on: boolean) {
  if (!isDesktopShell) return
  try {
    const { invoke } = await import('@tauri-apps/api/core')
    await invoke('set_close_guard', { enabled: on })
  } catch {
    /* 非桌面或命令不可用：忽略 */
  }
}

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
  if (castPending.value) return // 投屏确认弹窗在屏幕上：快捷键让给它（CastConfirmDialog 捕获 Enter/Esc）
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

  // 起播位置：投屏送来的位置优先于该集的服务端断点（与 TV 端 castResumeMs 同语义）
  const castPos = castPosMs()
  const prog = video.progress
  resumeFromCast = castPos != null
  resumePendingMs = castPos ?? (prog && prog.finished !== 1 && prog.positionMs > 30_000 ? prog.positionMs : 0)
  if (castPos != null) {
    // 用完即从 query 抹掉：否则切下一集时它会被当成"下一集的投屏位置"、从上一集的秒数起播
    const q = { ...route.query }
    delete q.castPos
    void router.replace({ query: q })
  }

  let url = ''
  lastPlayUrl = ''
  lastPlayHeaders = {}
  try {
    const pu = await getPlayUrl(video.id)
    url = pu.url
    lastPlayUrl = url
    lastPlayHeaders = pu.headers || {}
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
      if (resumePendingMs) toast.show(resumeFromCast ? '已从手机端的位置继续播放' : '已定位到上次播放位置')
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
  // 投屏的这一集自然播完：主动收尾。否则服务端要等 accepted-ttl 才 CLOSED，手机端一直挂着「已投送至」
  if (castActive.value) cancelActiveCast()
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

/** 播放页手动结束投屏：手机端那边的「已投送至」卡立刻收掉，本机继续正常播放 */
function endCast() {
  if (!castActive.value) return
  cancelActiveCast()
  toast.show('已结束投屏，本机继续播放')
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
  // 切到另一部影片：停播并重新选集。
  // 只有「离开的是投屏那一集」才收尾 —— 接受投屏后导航进来时 castActive 指向的就是新影片，不能把自己取消掉
  const a = castActive.value
  if (a && a.movieId !== _id) cancelActiveCast()
  destroyPlayer()
  playState.value = 'idle'
  errorText.value = ''
  await loadMeta()
  // URL 已带新影片的 videoId 时直接起播：watch(videoId) 那一跳发生在元数据回来之前（currentVideo 还是旧影片的），
  // 它拿到 null 就什么都不做，靠 pickDefault 又不会触发 —— 必须在这里补一次
  const v = currentVideo.value
  if (v) play(v)
  else pickDefault()
})

watch(videoId, (id) => {
  if (id && currentVideo.value) play(currentVideo.value)
})

onMounted(async () => {
  setCloseGuard(true)
  // 投屏被手机端取消 / 被同账号其它设备接管 → 停播并回影片页
  offCastStopped = onCastStopped(onCastStoppedByPeer)
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
  setCloseGuard(false)
  offCastStopped?.()
  offCastStopped = undefined
  // 离开播放页 = 投屏结束（与 TV 端 BACK 退出投屏播放页同一口径），手机端 ≤5s 内看到状态清掉
  cancelActiveCast()
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
      <button class="play-back" type="button" title="返回上一页" @click="router.back()">
        <Icon name="chev-left" :size="17" /> 返回
      </button>
      <div class="play-title">
        <div class="play-name">{{ detail?.title || '加载中…' }}</div>
        <div v-if="currentVideo" class="play-meta">
          <span v-if="currentSeasonNo">第{{ currentSeasonNo }} 季</span>
          <span>第{{ currentVideo.episodeNo }} 集</span>
          <span v-if="currentVideo.duration">{{ fmtSec(currentVideo.duration) }}</span>
          <span v-if="isCurrentWatched" class="meta-done">已看完</span>
        </div>
      </div>
      <div v-if="castActive" class="cast-badge" :title="`来自「${castActive.from}」的投屏`">
        <Icon name="tv" :size="14" /> 投屏播放中
      </div>
      <button
        v-if="isDesktopShell && localRecord"
        class="btn btn-sm"
        type="button"
        title="该集已下载，用本地播放核心打开"
        @click="playLocalNow"
      >
        <Icon name="play" :size="12" /> 本地播放
      </button>
      <button class="play-detail" type="button" title="回到影片详情" @click="router.push(`/movie/${movieId}`)">
        <Icon name="film" :size="15" /> 影片详情
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
        <div style="display: flex; gap: 10px; flex-wrap: wrap; justify-content: center">
          <button v-if="currentVideo" class="btn" style="border-color: rgba(255,255,255,.25); color: #dfe3ee" @click="onRetry">
            重试
          </button>
          <button
            v-if="isDesktopShell && lastPlayUrl"
            class="btn btn-primary"
            @click="playWithMpv"
          >
            用本地解码核心播放
          </button>
          <button
            v-if="isDesktopShell && localRecord"
            class="btn"
            style="border-color: rgba(255,255,255,.25); color: #dfe3ee"
            @click="playLocalNow"
          >
            播放已下载版本
          </button>
        </div>
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
      <button
        v-if="currentVideo"
        class="btn"
        :class="{ 'is-done': isCurrentWatched }"
        :disabled="isCurrentWatched"
        type="button"
        @click="markFinished"
      >
        <Icon name="check" :size="15" /> {{ isCurrentWatched ? '已看完' : '标记看完' }}
      </button>
      <button v-if="castActive" class="btn cast-end" type="button" @click="endCast">
        <Icon name="x" :size="14" /> 结束投屏
      </button>
      <div class="play-tips">
        <span v-if="prevNext.next" class="play-autonext">本集播完自动接下一集</span>
        <span v-if="isDesktopShell" class="play-keys">
          <kbd>空格</kbd>播放 / 暂停<kbd>←</kbd><kbd>→</kbd>快退 / 快进 10s<kbd>↑</kbd><kbd>↓</kbd>音量
        </span>
      </div>
    </div>

    <div v-if="seasons.length" class="play-eps">
      <h3 class="ep-title">
        剧集选集
        <span class="ep-total">共 {{ flat.length }} 集 · 已看 {{ watchedCount }} 集</span>
      </h3>
      <div v-for="s in seasons" :key="s.seasonNo" class="ep-season">
        <div v-if="seasons.length > 1" class="ep-season-head">
          <span class="ep-season-name">第{{ s.seasonNo }} 季</span>
          <span class="ep-season-count">{{ s.videos.length }} 集</span>
        </div>
        <div class="ep-grid">
          <button
            v-for="v in s.videos"
            :key="v.id"
            class="ep-btn"
            :class="epState(v)"
            type="button"
            :title="`第${s.seasonNo}季 第${v.episodeNo}集${v.duration ? ' · ' + fmtSec(v.duration) : ''}`"
            @click="selectVideo(v.id)"
          >
            {{ v.title || `第 ${v.episodeNo} 集` }}
            <span
              v-if="v.progress && v.progress.finished !== 1"
              class="ep-progress"
            ><i
              :style="{ width: progressPct(v.progress.positionMs, v.progress.durationMs) + '%' }"
            /></span>
            <span v-if="v.progress && v.progress.finished === 1" class="ep-seen"><Icon name="check" :size="12" /></span>
          </button>
        </div>
      </div>
    </div>

    <!-- 其他季 / 关联影片（后台手动建立）：点卡片进那一部自己的详情页，当前影片照常停在这里 -->
    <div v-if="related.length" class="play-related">
      <h3 class="ep-title">
        {{ relatedTitle }}
        <span class="ep-total">共 {{ related.length }} 部</span>
      </h3>
      <RailScroller>
        <div v-for="r in related" :key="r.movie.id" class="rail-card">
          <MovieCard :movie="r.movie" rail />
        </div>
      </RailScroller>
    </div>
  </div>
</template>
