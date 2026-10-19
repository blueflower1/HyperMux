<script setup lang="ts">
/**
 * 应用自更新卡片（契约 §3.11）：展示平台当前发布 + 上传新版本表单。
 * mobile / tv 各渲染一张，互不影响；每平台仅保留最新一条，发布即覆盖。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import {
  NButton,
  NCard,
  NDivider,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NSpace,
  NSwitch,
  NTag,
  useMessage,
} from 'naive-ui'
import { appReleaseApi } from '@/api/appRelease'
import { errMsg } from '@/api/http'
import type { AppReleaseVo } from '@/api/types'
import { fmtSize } from '@/utils/format'
import CopyableText from './CopyableText.vue'
import EmptyState from './EmptyState.vue'
import TimeText from './TimeText.vue'

const props = defineProps<{ platform: 'mobile' | 'tv'; title: string }>()
const message = useMessage()

const loading = ref(false)
const publishing = ref(false)
const cur = ref<AppReleaseVo | null>(null)

const file = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const form = reactive({
  versionCode: null as number | null,
  versionName: '',
  releaseNotes: '',
  mandatory: false,
})

const suggestedCode = computed(() => (cur.value ? cur.value.versionCode + 1 : null))

const downloadUrl = computed(
  () => `${location.origin}/api/app/update/download?platform=${props.platform}`,
)

async function load() {
  loading.value = true
  try {
    cur.value = await appReleaseApi.current(props.platform)
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    loading.value = false
  }
}
onMounted(load)

function pickFile() {
  fileInput.value?.click()
}

function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const f = input.files?.[0]
  input.value = ''
  if (!f) return
  if (!f.name.toLowerCase().endsWith('.apk')) {
    message.error('仅支持 .apk 文件')
    return
  }
  if (f.size > 300 * 1024 * 1024) {
    message.error('APK 超过 300MB 上限')
    return
  }
  file.value = f
}

async function publish() {
  if (!file.value) return
  if (!form.versionCode || form.versionCode < 1) {
    message.warning('请填写 versionCode（整数，须大于当前已发布值）')
    return
  }
  if (!form.versionName.trim()) {
    message.warning('请填写 versionName（展示用，如 0.1.8）')
    return
  }
  publishing.value = true
  try {
    const fd = new FormData()
    fd.append('file', file.value)
    fd.append('platform', props.platform)
    fd.append('versionCode', String(form.versionCode))
    fd.append('versionName', form.versionName.trim())
    fd.append('releaseNotes', form.releaseNotes.trim())
    fd.append('mandatory', form.mandatory ? 'true' : 'false')
    const vc = form.versionCode
    const vn = form.versionName.trim()
    await appReleaseApi.publish(fd)
    // 发布响应里的实体 updateTime 还未经过 DB 默认值填充，回读一次拿到服务端持久化后的行
    cur.value = await appReleaseApi.current(props.platform)
    file.value = null
    form.versionCode = null
    form.versionName = ''
    form.releaseNotes = ''
    form.mandatory = false
    message.success(`已发布 ${props.title} v${vn}（versionCode ${vc}）`)
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    publishing.value = false
  }
}

const canPublish = computed(
  () => !!file.value && !!form.versionCode && form.versionCode >= 1 && form.versionName.trim() !== '',
)
</script>

<template>
  <NCard :bordered="false" class="hm-card plat-card" :loading="loading">
    <template #header>
      <div class="card-head">
        <span class="card-title">{{ title }}</span>
        <template v-if="cur">
          <NTag type="success" size="small" round>v{{ cur.versionName }}</NTag>
          <NTag size="small" round>versionCode {{ cur.versionCode }}</NTag>
          <NTag v-if="cur.mandatory" type="error" size="small" round>强制更新</NTag>
        </template>
        <NTag v-else type="default" size="small" round>未发布</NTag>
      </div>
    </template>
    <template #header-extra>
      <NButton size="tiny" quaternary @click="load">刷新</NButton>
    </template>

    <!-- 当前发布 -->
    <div v-if="cur" class="info">
      <div class="row">
        <span class="k">包大小</span>
        <span class="v">{{ fmtSize(cur.sizeBytes) }}</span>
      </div>
      <div class="row">
        <span class="k">SHA-256</span>
        <span class="v"><CopyableText :text="cur.sha256" :max="32" /></span>
      </div>
      <div class="row">
        <span class="k">发布时间</span>
        <span class="v"><TimeText :ms="cur.updateTime" /></span>
      </div>
      <div class="row">
        <span class="k">更新说明</span>
        <span class="v notes">{{ cur.releaseNotes || '—' }}</span>
      </div>
      <div class="row">
        <span class="k">下载链接</span>
        <span class="v"><CopyableText :text="downloadUrl" :max="40" /></span>
      </div>
      <div class="tip">
        链接匿名可直接访问（浏览器打开即下载）。App 内的检查更新跟随用户配置的服务器：公开服务器查公开发布，自托管部署查自己的发布。
      </div>
    </div>
    <EmptyState v-else :desc="`${title}尚未发布`" />

    <NDivider style="margin: 18px 0 14px" />

    <!-- 发布新版本 -->
    <div class="form-title">发布新版本</div>
    <NForm label-placement="top" require-asterisk="optional">
      <NFormItem label="APK 文件">
        <NButton size="small" :disabled="publishing" @click="pickFile">
          {{ file ? `已选择：${file.name}` : '选择 APK' }}
        </NButton>
        <span v-if="file" class="file-size">{{ fmtSize(file.size) }}</span>
        <input
          ref="fileInput"
          type="file"
          accept=".apk"
          class="file-input"
          @change="onFileChange"
        />
      </NFormItem>

      <div class="code-row">
        <NFormItem label="versionCode" class="code-flex">
          <NInputNumber
            v-model:value="form.versionCode"
            :min="1"
            :step="1"
            :precision="0"
            :placeholder="suggestedCode ? `如 ${suggestedCode}` : '整数，须大于当前已发布值'"
            style="width: 100%"
          />
        </NFormItem>
        <NFormItem label="versionName" class="code-flex">
          <NInput
            v-model:value="form.versionName"
            placeholder="如 0.1.8"
            maxlength="32"
            @keyup.enter="publish"
          />
        </NFormItem>
      </div>

      <NFormItem label="发布说明">
        <NInput
          v-model:value="form.releaseNotes"
          type="textarea"
          placeholder="App 更新弹窗里展示，可留空"
          :autosize="{ minRows: 2, maxRows: 5 }"
        />
      </NFormItem>

      <NFormItem label="强制更新">
        <NSpace align="center">
          <NSwitch v-model:value="form.mandatory" />
          <span class="hint">
            开启后更新弹窗不可关闭，用户必须更新到本版本才能继续使用 App（默认关闭）。
          </span>
        </NSpace>
      </NFormItem>

      <NFormItem label=" ">
        <NSpace>
          <NButton type="primary" :loading="publishing" :disabled="!canPublish" @click="publish">
            发布
          </NButton>
          <NText v-if="suggestedCode" depth="3" class="hint">
            建议 versionCode：{{ suggestedCode }}（当前 {{ cur?.versionCode }}）
          </NText>
        </NSpace>
      </NFormItem>
    </NForm>
  </NCard>
</template>

<style scoped>
.plat-card {
  height: 100%;
}
.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.card-title {
  font-weight: 700;
}
.info {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.row {
  display: flex;
  gap: 12px;
  font-size: 13px;
  line-height: 1.5;
}
.row .k {
  width: 64px;
  flex: none;
  color: var(--hm-ink-3);
}
.row .v {
  flex: 1;
  min-width: 0;
  word-break: break-all;
}
.row .notes {
  white-space: pre-wrap;
  word-break: break-word;
}
.tip {
  margin-top: 2px;
  color: var(--hm-ink-3);
  font-size: 12px;
  line-height: 1.6;
  background: #fafbfd;
  border: 1px dashed #e2e7f0;
  border-radius: 8px;
  padding: 8px 10px;
}
.form-title {
  font-size: 13px;
  font-weight: 700;
  margin-bottom: 12px;
}
.file-size {
  margin-left: 10px;
  color: var(--hm-ink-3);
  font-size: 12px;
}
.file-input {
  display: none;
}
.code-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.code-flex {
  margin-bottom: 0;
}
.hint {
  color: var(--hm-ink-3);
  font-size: 12px;
  line-height: 1.6;
}
</style>
