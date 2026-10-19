<script setup lang="ts">
import { useRouter } from 'vue-router'
import { NButton, NEmpty } from 'naive-ui'

const router = useRouter()
</script>

<template>
  <div class="nf">
    <NEmpty size="huge" description="页面不存在（404）">
      <template #extra>
        <NButton type="primary" size="small" @click="router.replace('/dashboard')">
          回总览
        </NButton>
      </template>
    </NEmpty>
  </div>
</template>

<style scoped>
.nf {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
}
</style>
