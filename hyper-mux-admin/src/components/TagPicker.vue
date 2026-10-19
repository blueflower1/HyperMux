<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { NSelect } from 'naive-ui'
import type { SelectOption } from 'naive-ui'
import { useAppStore } from '@/stores/app'

defineProps<{ modelValue: number[]; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: number[]] }>()

const appStore = useAppStore()

const GROUPS: { type: number; label: string }[] = [
  { type: 0, label: '类型' },
  { type: 1, label: '地区' },
  { type: 2, label: '自定义' },
]

const options = computed<SelectOption[]>(() =>
  GROUPS.map((g) => ({
    type: 'group' as const,
    label: g.label,
    key: `g${g.type}`,
    children: appStore.tags
      .filter((t) => t.type === g.type && t.status === 1)
      .map((t) => ({ label: t.name, value: t.id })),
  })),
)

onMounted(() => {
  appStore.refreshTags().catch(() => {})
})
</script>

<template>
  <NSelect
    :value="modelValue"
    :options="options"
    :disabled="disabled"
    multiple
    filterable
    clearable
    tag
    placeholder="选择标签（可多选）"
    @update:value="(v: (string | number)[]) => emit('update:modelValue', v as number[])"
  />
</template>
