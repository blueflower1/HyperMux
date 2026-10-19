<script setup lang="ts">
import { useRouter } from 'vue-router'
import Icon from '@/components/Icon.vue'

const router = useRouter()
</script>

<template>
  <div class="notfound">
    <h1>404</h1>
    <p>页面走丢了</p>
    <button class="btn btn-primary" @click="router.push('/')">
      <Icon name="home" :size="15" /> 回首页
    </button>
  </div>
</template>
