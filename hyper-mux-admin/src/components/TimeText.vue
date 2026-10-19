<script setup lang="ts">
import { computed } from 'vue'
import { NTooltip } from 'naive-ui'
import { fmtAbsolute, fmtRelative } from '@/utils/format'

const props = defineProps<{ ms: number | null }>()
const rel = computed(() => fmtRelative(props.ms))
const abs = computed(() => fmtAbsolute(props.ms))
</script>

<template>
  <NTooltip v-if="ms" trigger="hover">
    <template #trigger>
      <span>{{ rel }}</span>
    </template>
    {{ abs }}
  </NTooltip>
  <span v-else>—</span>
</template>
