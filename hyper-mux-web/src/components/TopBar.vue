<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Icon from '@/components/Icon.vue'
import { useThemeStore } from '@/stores/theme'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const theme = useThemeStore()
const user = useUserStore()

const userMenuOpen = ref(false)
const menuEl = ref<HTMLElement | null>(null)

const initials = computed(() => {
  const n = user.user?.nickName || user.user?.username || '?'
  return n.trim().slice(0, 1)
})

function onLogo() {
  router.push('/')
}

function onThemeToggle(e: MouseEvent) {
  // 从点击点（或触控点）起做圆形扩散切换
  theme.toggleAt(e.clientX, e.clientY)
}

function onLogout() {
  userMenuOpen.value = false
  user.clear()
  router.push('/login')
}

function onDocClick(e: MouseEvent) {
  if (menuEl.value && !menuEl.value.contains(e.target as Node)) {
    userMenuOpen.value = false
  }
}

onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))
</script>

<template>
  <header class="topbar">
    <div class="topbar-inner">
      <button class="logo" type="button" @click="onLogo">
        <span class="logo-mark"><Icon name="play" :size="14" /></span>
        HyperMux<span class="logo-sub">影视</span>
      </button>

      <div class="topbar-right">
        <button
          class="icon-btn"
          type="button"
          :title="theme.theme === 'dark' ? '切换到浅色' : '切换到深色'"
          @click="onThemeToggle"
        >
          <Icon :name="theme.theme === 'dark' ? 'sun' : 'moon'" />
        </button>

        <div class="user-menu" ref="menuEl">
          <button class="user-chip" type="button" @click="userMenuOpen = !userMenuOpen">
            <span class="avatar">{{ initials }}</span>
            <span class="user-name">{{ user.user?.nickName || user.user?.username }}</span>
            <Icon name="chev-down" :size="14" :class="{ 'is-open': userMenuOpen }" />
          </button>
          <transition name="fade">
            <div v-if="userMenuOpen" class="user-dropdown">
              <div class="dropdown-head">
                <div class="dropdown-avatar">{{ initials }}</div>
                <div class="dropdown-id">
                  <div class="dropdown-name">{{ user.user?.nickName || user.user?.username }}</div>
                  <div class="dropdown-sub">@{{ user.user?.username }}</div>
                </div>
              </div>
              <button class="dropdown-item" type="button" @click="() => (userMenuOpen = false, router.push('/me'))">
                <Icon name="user" :size="16" /> 我的
              </button>
              <button class="dropdown-item danger" type="button" @click="onLogout">
                <Icon name="logout" :size="16" /> 退出登录
              </button>
            </div>
          </transition>
        </div>
      </div>
    </div>
  </header>
</template>
