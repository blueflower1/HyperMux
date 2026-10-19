<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NImage, NInput, NText } from 'naive-ui'
import { imgUrl } from '@/utils/image'

const model = defineModel<string>({ default: '' })

const imgFailed = ref(false)
watch(
  () => model.value,
  () => {
    imgFailed.value = false
  },
)

const isHttp = computed(() => !model.value || /^https?:\/\//i.test(model.value))
</script>

<template>
  <div class="cover">
    <div class="form">
      <NInput
        :value="model"
        placeholder="海报地址，http(s):// 开头"
        clearable
        @update:value="(v: string) => (model = v.trim())"
      />
      <NText v-if="!isHttp" style="color: #d03050; font-size: 12px">
        封面须为 http(s):// 开头的 URL
      </NText>
    </div>
    <div class="preview">
      <NImage
        v-if="model && isHttp"
        width="140"
        height="210"
        object-fit="cover"
        class="poster"
        :src="imgUrl(model)"
        :img-props="{
          class: imgFailed ? 'img-error' : '',
          onError: () => {
            imgFailed = true
          },
        }"
      />
      <div v-else class="placeholder">无封面</div>
    </div>
  </div>
</template>

<style scoped>
.cover {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}
.form {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.preview {
  flex: none;
}
.preview :deep(.img-error) {
  outline: 2px solid #d03050;
  border-radius: 6px;
}
.poster {
  border-radius: 6px;
}
.placeholder {
  width: 140px;
  height: 210px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f5f5;
  color: #999;
  border-radius: 6px;
  font-size: 12px;
}
</style>
