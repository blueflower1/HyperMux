<script setup lang="ts">
import { NCard, NTag } from 'naive-ui'
import { RocketOutline } from '@vicons/ionicons5'

withDefaults(
  defineProps<{
    title: string
    milestone: string
    note?: string
  }>(),
  { note: '' },
)
</script>

<template>
  <NCard :title="title" :bordered="false" class="ph hm-card">
    <template #header-extra>
      <NTag type="warning" size="small">{{ milestone }} 实现</NTag>
    </template>
    <div class="inner">
      <div class="rocket">
        <RocketOutline />
      </div>
      <div class="text">
        <div class="main">页面规范已定稿（docs/plans/50-admin-detailed.md），当前为骨架占位</div>
        <div v-if="note" class="note">{{ note }}</div>
      </div>
    </div>
  </NCard>
</template>

<style scoped>
.ph {
  max-width: 1200px;
}
.inner {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 30px 10px;
  animation: hm-fade-up 0.3s cubic-bezier(0.22, 1, 0.36, 1);
}
.rocket {
  width: 64px;
  height: 64px;
  border-radius: 20px;
  background: linear-gradient(135deg, rgba(247, 163, 37, 0.16), rgba(245, 114, 13, 0.16));
  color: #ef7c0a;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
}
.rocket svg {
  width: 32px;
  height: 32px;
}
.text .main {
  font-size: 14px;
  font-weight: 600;
}
.text .note {
  margin-top: 6px;
  color: #98a1b0;
  font-size: 13px;
}
</style>
