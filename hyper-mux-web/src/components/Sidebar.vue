<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import Icon from '@/components/Icon.vue'
import { useCategories } from '@/composables/useCategories'

const route = useRoute()
const { categories, ensure } = useCategories()

onMounted(() => ensure())

const activeCategory = computed(() => (route.name === 'category' ? route.params.id : null))
</script>

<template>
  <aside class="sidebar">
    <nav class="side-nav">
      <router-link class="side-item" to="/" :class="{ active: route.name === 'home' }">
        <Icon name="home" :size="18" /> 首页
      </router-link>
      <router-link class="side-item" to="/history" :class="{ active: route.name === 'history' }">
        <Icon name="history" :size="18" /> 观看历史
      </router-link>
    </nav>

    <div class="side-divider"></div>
    <div class="side-title">影视频类</div>
    <nav class="side-nav cats">
      <router-link
        v-for="c in categories"
        :key="c.id"
        class="side-item"
        :to="`/category/${c.id}`"
        :class="{ active: String(activeCategory) === String(c.id) }"
      >
        <Icon name="film" :size="18" />
        <span class="side-label">{{ c.name }}</span>
        <span class="side-count">{{ c.movieCount }}</span>
      </router-link>
      <div v-if="categories.length === 0" class="side-empty">暂无内容</div>
    </nav>
  </aside>
</template>
