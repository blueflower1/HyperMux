<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NInputNumber, NRate } from 'naive-ui'

const model = defineModel<number | null>({ default: null })

const stars = computed(() => (model.value == null ? 0 : model.value))

function setStars(v: number | null) {
  model.value = v == null || v === 0 ? null : Math.round(v * 10) / 10
}
</script>

<template>
  <div class="rating">
    <NRate :value="stars" :allow-half="true" @update:value="setStars" />
    <NInputNumber
      :value="model"
      size="small"
      :min="0"
      :max="10"
      :step="0.1"
      :precision="1"
      placeholder="不评分"
      style="width: 92px"
      @update:value="(v) => (model = v)"
    />
    <NButton v-if="model != null" size="small" quaternary @click="model = null">清除</NButton>
  </div>
</template>

<style scoped>
.rating {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
