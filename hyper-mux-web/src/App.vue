<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import TopBar from '@/components/TopBar.vue'
import Sidebar from '@/components/Sidebar.vue'
import TabBar from '@/components/TabBar.vue'
import ToastHost from '@/components/ToastHost.vue'
import ThemeRevealFX from '@/components/ThemeRevealFX.vue'

const route = useRoute()

// 登录页与播放页不带主导航壳（播放页有沉浸式顶栏）
const shellHidden = computed(() => route.path === '/login' || route.path.includes('/play'))
</script>

<template>
  <div class="app" :class="{ 'no-shell': shellHidden }">
    <TopBar v-if="!shellHidden" />
    <div class="shell">
      <Sidebar v-if="!shellHidden" />
      <main class="content">
        <router-view />
      </main>
    </div>
    <TabBar v-if="!shellHidden" />
    <ToastHost />
    <ThemeRevealFX />
  </div>
</template>
