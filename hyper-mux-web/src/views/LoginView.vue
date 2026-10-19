<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from '@/components/Icon.vue'
import { useUserStore } from '@/stores/user'
import { useThemeStore } from '@/stores/theme'
import { errMsg } from '@/api/http'

const router = useRouter()
const route = useRoute()
const user = useUserStore()
const theme = useThemeStore()

const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')

function onThemeToggle(e: MouseEvent) {
  theme.toggleAt(e.clientX, e.clientY)
}

async function onSubmit() {
  if (busy.value) return
  error.value = ''
  if (!username.value.trim()) {
    error.value = '请输入用户名'
    return
  }
  if (!password.value) {
    error.value = '请输入密码'
    return
  }
  busy.value = true
  try {
    await user.login(username.value, password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    router.replace(redirect.startsWith('/') ? redirect : '/')
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-logo"><Icon name="play" :size="22" /></div>
      <h1 class="login-title">HyperMux</h1>
      <p class="login-sub">登录后继续你的观影进度</p>

      <form class="login-form" @submit.prevent="onSubmit">
        <input
          v-model="username"
          class="input"
          type="text"
          autocomplete="username"
          placeholder="用户名"
          maxlength="32"
          :disabled="busy"
        />
        <input
          v-model="password"
          class="input"
          type="password"
          autocomplete="current-password"
          placeholder="密码"
          maxlength="20"
          :disabled="busy"
        />
        <div v-if="error" class="login-error">{{ error }}</div>
        <button class="btn btn-primary" type="submit" :disabled="busy">
          <span v-if="busy" class="spin" style="width: 16px; height: 16px"></span>
          {{ busy ? '登录中…' : '登 录' }}
        </button>
      </form>

      <div class="login-tip">账号由管理员在后台创建</div>
    </div>

    <button class="icon-btn" type="button" style="position: absolute; right: 18px; top: 18px" @click="onThemeToggle">
      <Icon :name="theme.theme === 'dark' ? 'sun' : 'moon'" />
    </button>
  </div>
</template>
