<script setup lang="ts">
/**
 * 投屏确认弹窗（计划书 80-cast-detailed.md §14，流程口径与 TV 端 CastConfirmDialog 一致）：
 *
 * - 标题「接收到来自手机端的投屏请求」，正文「是否进入播放：{片名} {集名}」+ 来自 + 续播位置
 * - **默认「是」**：倒计时归零自动接受（reason=countdown）
 * - 点「否」或点遮罩 / 关闭 / Esc = 拒绝（reason=user-no）；Enter = 是
 *
 * 倒计时按 pending.id 记忆：同一请求在 PENDING 期间会被每轮轮询重复下发，
 * 不能让弹窗每秒重建、倒计时被重置（stores/cast.ts 已按 id 去重，这里再兜一层）。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Icon from '@/components/Icon.vue'
import type { CastPending } from '@/api/types'
import { fmtMs } from '@/utils/format'

const props = defineProps<{
  pending: CastPending
  confirmSeconds: number
}>()
const emit = defineEmits<{
  (e: 'accept', reason: 'user-yes' | 'countdown'): void
  (e: 'reject'): void
}>()

function clampSecs(v: number): number {
  const n = Number.isFinite(v) ? Math.round(v) : 8
  return Math.min(15, Math.max(3, n))
}

const total = computed(() => clampSecs(props.confirmSeconds))
const left = ref(total.value)
let timer: number | undefined

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = undefined
  }
}

function startTimer() {
  stopTimer()
  timer = window.setInterval(() => {
    if (left.value <= 1) {
      stopTimer()
      // 倒计时结束 = 默认「是」
      emit('accept', 'countdown')
      return
    }
    left.value -= 1
  }, 1000)
}

watch(
  () => props.pending.id,
  () => {
    left.value = total.value
    startTimer()
  }
)

/* Enter = 「是」，Esc = 「否」；拦在捕获阶段，避免同时被播放页的快捷键吃掉 */
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    e.preventDefault()
    e.stopPropagation()
    emit('accept', 'user-yes')
  } else if (e.key === 'Escape') {
    e.preventDefault()
    e.stopPropagation()
    emit('reject')
  }
}

onMounted(() => {
  document.addEventListener('keydown', onKeydown, true)
  startTimer()
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown, true)
  stopTimer()
})
</script>

<template>
  <teleport to="body">
    <div class="dl-overlay cast-overlay" @click.self="emit('reject')">
      <div class="dl-sheet cast-card card" role="dialog" aria-modal="true">
        <div class="dl-head">
          <div class="dl-head-text">
            <div class="dl-title">
              <Icon name="tv" :size="16" /> 接收到来自手机端的投屏请求
            </div>
            <div class="dl-sub">来自：{{ pending.senderName || '手机端' }}</div>
          </div>
          <button class="icon-btn" type="button" title="拒绝（否）" @click="emit('reject')">
            <Icon name="x" :size="15" />
          </button>
        </div>

        <div class="cast-body">
          <div class="cast-cover">
            <img v-if="pending.coverUrl" :src="pending.coverUrl" alt="" />
            <Icon v-else name="film" :size="24" />
          </div>
          <div class="cast-info">
            <div class="cast-name">
              是否进入播放：{{ [pending.movieTitle, pending.episodeLabel].filter(Boolean).join(' ') || '投屏内容' }}
            </div>
            <div v-if="pending.positionMs > 0" class="cast-resume">
              从 {{ fmtMs(pending.positionMs) }} 处继续
            </div>
            <div class="cast-hint">
              {{ left }} 秒后自动播放（默认「是」）；点「否」或关闭 = 拒绝
            </div>
          </div>
        </div>

        <div class="cast-bar">
          <i :style="{ width: Math.min(100, Math.max(0, (left / total) * 100)) + '%' }" />
        </div>

        <div class="dl-foot">
          <button class="btn" type="button" @click="emit('reject')">否</button>
          <button class="btn btn-primary" type="button" @click="emit('accept', 'user-yes')">
            <Icon name="play" :size="13" /> {{ left > 0 ? `是（${left}s）` : '是' }}
          </button>
        </div>
      </div>
    </div>
  </teleport>
</template>
