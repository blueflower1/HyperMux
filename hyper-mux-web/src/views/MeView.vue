<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import Icon from '@/components/Icon.vue'
import { useThemeStore, type Theme } from '@/stores/theme'
import { useUserStore } from '@/stores/user'
import { fmtDate } from '@/utils/format'
import { getExp } from '@/api/http'

const router = useRouter()
const theme = useThemeStore()
const user = useUserStore()

const initials = computed(() => {
  const n = user.user?.nickName || user.user?.username || '?'
  return n.trim().slice(0, 1)
})

const expireText = computed(() => {
  const exp = getExp()
  if (!exp) return '长期有效'
  const leftDay = Math.round((exp - Date.now()) / 86_400_000)
  return leftDay > 1 ? `登录信息剩 ${leftDay} 天过期` : fmtDate(exp)
})

function logout() {
  user.clear()
  router.push('/login')
}

/** 点中哪个主题按钮就从该按钮中心向外扩散切换 */
function setThemeAt(t: Theme, e: MouseEvent) {
  const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
  theme.switchTo(t, r.left + r.width / 2, r.top + r.height / 2)
}
</script>

<template>
  <div style="max-width: 560px">
    <div class="me-card card">
      <div class="avatar me-avatar">{{ initials }}</div>
      <div style="min-width: 0">
        <div class="me-name">{{ user.user?.nickName || user.user?.username }}</div>
        <div class="me-sub">@{{ user.user?.username }} · 普通用户</div>
      </div>
    </div>

    <div class="me-section card">
      <div class="me-row">
        <span class="me-row-label"><Icon name="moon" :size="17" /> 主题</span>
        <div class="seg">
          <button type="button" :class="{ active: theme.theme === 'light' }" @click="setThemeAt('light', $event)">
            <Icon name="sun" :size="14" /> 浅色
          </button>
          <button type="button" :class="{ active: theme.theme === 'dark' }" @click="setThemeAt('dark', $event)">
            <Icon name="moon" :size="14" /> 深色
          </button>
        </div>
      </div>
      <div class="me-row" style="cursor: default">
        <span class="me-row-label"><Icon name="history" :size="17" /> 登录信息</span>
        <span class="me-row-value">{{ expireText }}</span>
      </div>
    </div>

    <button class="me-logout" type="button" @click="logout">
      <Icon name="logout" :size="16" /> 退出登录
    </button>

    <div class="me-footer">HyperMux Web · 与 TV 端 / 后台共用同一套内容</div>
  </div>
</template>
