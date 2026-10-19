<script setup lang="ts">
// 封面：加载失败/缺失时显示首字占位（渐变底）
import { computed, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    src?: string | null
    title?: string | null
    alt?: string | null
  }>(),
  { src: null, title: '', alt: null },
)

const failed = ref(false)

const placeholder = computed(() => {
  const t = (props.title || '').trim()
  return t ? t.slice(0, 1) : '影'
})
</script>

<template>
  <div class="cover">
    <img
      v-if="src && !failed"
      class="cover-img"
      :src="src"
      :alt="alt || title || ''"
      loading="lazy"
      referrerpolicy="no-referrer"
      @error="failed = true"
    />
    <div v-else class="cover-fallback">
      <span>{{ placeholder }}</span>
    </div>
    <slot />
  </div>
</template>
