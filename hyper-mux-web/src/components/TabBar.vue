<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import Icon from '@/components/Icon.vue'

const route = useRoute()
const router = useRouter()

const tabs = [
  { to: '/', name: 'home', icon: 'home', label: '首页' },
  { to: '/history', name: 'history', icon: 'history', label: '历史' },
  { to: '/me', name: 'me', icon: 'user', label: '我的' },
]
</script>

<template>
  <nav class="tabbar">
    <router-link
      v-for="t in tabs"
      :key="t.name"
      class="tab-item"
      :to="t.to"
      :class="{ active: route.name === t.name }"
    >
      <span class="tab-ic"><Icon :name="t.icon" :size="22" /></span>
      <span class="tab-label">{{ t.label }}</span>
    </router-link>
  </nav>
</template>
