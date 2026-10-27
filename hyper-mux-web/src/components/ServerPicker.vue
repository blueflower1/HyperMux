<script setup lang="ts">
import { computed, ref } from 'vue'
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

/** 档位变化（含自定义保存）后回调：父级清会话并重启扫码引擎；pending = 选了自定义但还没保存地址 */
const props = defineProps<{ onChanged?: () => void; onPending?: () => void }>()

const draft = ref(customUrl.value)
const showCustomInput = ref(serverMode.value === 'custom' && !customUrl.value)
const inputError = ref('')

/** 档位高亮：自定义被点开（即使还没保存）也算选中，避免"点了没反应"的观感 */
const presetActive = computed(() => serverMode.value === 'preset' && !showCustomInput.value)
const customActive = computed(() => serverMode.value === 'custom' || showCustomInput.value)

function pick(mode: ServerMode) {
  inputError.value = ''
  if (mode === 'custom' && !customUrl.value) {
    // 还没保存过自定义地址：展开输入框、通知父级暂停扫码（保存成功后才恢复）
    showCustomInput.value = true
    props.onPending?.()
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
        <button v-if="PRESET_URL" type="button" :class="{ active: presetActive }" @click="pick('preset')">
          公共服务器
        </button>
        <button type="button" :class="{ active: customActive }" @click="pick('custom')">自定义</button>
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
