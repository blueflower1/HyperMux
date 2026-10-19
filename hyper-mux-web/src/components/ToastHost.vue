<script setup lang="ts">
import { useToastStore } from '@/stores/toast'
import Icon from '@/components/Icon.vue'

const toast = useToastStore()
</script>

<template>
  <div class="toast-host" aria-live="polite">
    <transition-group name="toast">
      <div v-for="t in toast.items" :key="t.id" class="toast-item" :class="t.type">
        <Icon :name="t.type === 'error' ? 'x' : 'check'" :size="16" />
        <span>{{ t.msg }}</span>
      </div>
    </transition-group>
  </div>
</template>
