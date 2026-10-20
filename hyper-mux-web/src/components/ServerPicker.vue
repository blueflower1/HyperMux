<script setup lang="ts">
import { ref } from 'vue'
import Icon from '@/components/Icon.vue'
import {
  PRESET_URL,
  serverMode,
  customUrl,
  setServerMode,
  saveCustomUrl,
  activeServerLabel,
  type ServerMode,
} from '@/stores/server'

/** 档位变化（含自定义保存）后回调：父级重启扫码引擎 */
const props = defineProps<{ onChanged?: () => void }>()

const draft = ref(customUrl.value)
const showCustomInput = ref(false)
const inputError = ref('')

function pick(mode: ServerMode) {
  inputError.value = ''
  if (mode === 'custom' && !customUrl.value) {
    // 还没保存过自定义地址：先展开输入框，保存成功后才切档
    showCustomInput.value = true
    return
  }
  showCustomInput.value = mode === 'custom'
  setServerMode(mode)
  props.onChanged?.()
}

function save() {
  const n = saveCustomUrl(draft.value)
  if (!n) {
    inputError.value = '地址无效，示例：http://192.168.1.10:8080'
    return
  }
  inputError.value = ''
  draft.value = n
  showCustomInput.value = true
  props.onChanged?.()
}
</script>

<template>
  <div class="server-picker">
    <div class="server-picker-head">
      <span class="server-picker-label"><Icon name="globe" :size="15" /> 服务器</span>
      <div class="seg">
        <button v-if="PRESET_URL" type="button" :class="{ active: serverMode === 'preset' }" @click="pick('preset')">
          公共服务器
        </button>
        <button type="button" :class="{ active: serverMode === 'custom' }" @click="pick('custom')">自定义</button>
      </div>
    </div>

    <div v-if="serverMode === 'custom' || showCustomInput" class="server-custom">
      <input
        v-model="draft"
        class="input"
        type="text"
        placeholder="http://host:port"
        maxlength="128"
        @keyup.enter="save"
      />
      <button class="btn btn-primary" type="button" @click="save">保存</button>
    </div>
    <div v-if="inputError" class="login-error" style="margin-top: 0">{{ inputError }}</div>

    <div class="server-current">当前使用：{{ activeServerLabel() }}</div>
  </div>
</template>
