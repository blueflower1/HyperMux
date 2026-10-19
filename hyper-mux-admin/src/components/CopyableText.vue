<script setup lang="ts">
import { computed } from 'vue'
import { NIcon, NTooltip, useMessage } from 'naive-ui'
import { CopyOutline } from '@vicons/ionicons5'
import { copyText, truncate } from '@/utils/format'

const props = withDefaults(defineProps<{ text: string | null; max?: number }>(), { max: 42 })
const message = useMessage()
const shown = computed(() => truncate(props.text ?? '', props.max))

async function doCopy() {
  if (!props.text) return
  const ok = await copyText(props.text)
  if (ok) message.success('已复制')
}
</script>

<template>
  <NTooltip v-if="text" trigger="hover">
    <template #trigger>
      <span class="copyable" @click="doCopy">
        <span class="txt">{{ shown }}</span>
        <NIcon :component="CopyOutline" size="13" class="icon" />
      </span>
    </template>
    {{ text }}
  </NTooltip>
  <span v-else class="none">—</span>
</template>

<style scoped>
.copyable {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  cursor: pointer;
  color: #999;
}
.txt {
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 13px;
}
.icon {
  flex: none;
  color: #bbb;
}
.none {
  color: #999;
}
</style>
