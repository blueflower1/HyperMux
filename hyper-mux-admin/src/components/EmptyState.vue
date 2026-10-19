<script setup lang="ts">
import { NEmpty } from 'naive-ui'

withDefaults(defineProps<{ desc?: string }>(), { desc: '暂无数据' })
</script>

<template>
  <div class="empty">
    <div class="halo">
      <NEmpty size="large" :description="desc" />
    </div>
    <div v-if="$slots.action" class="actions">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.empty {
  padding: 44px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  animation: hm-fade-up 0.3s cubic-bezier(0.22, 1, 0.36, 1);
}
.halo {
  padding: 26px 40px;
  border-radius: 20px;
  background:
    radial-gradient(180px 90px at 50% 0%, rgba(245, 130, 13, 0.07), transparent 70%),
    #fafbfd;
  border: 1px dashed #e2e7f0;
}
.actions {
  display: flex;
  gap: 8px;
}
</style>
