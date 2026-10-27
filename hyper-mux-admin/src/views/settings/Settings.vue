<script setup lang="ts">
/**
 * 系统设置：元数据刮削来源（TMDB / 豆瓣可切换）与 TMDB API Key + 远程投屏开关与确认倒计时。
 * 新建/编辑影片的「刮削元数据」会使用这里的当前来源。
 * 投屏两项落 sys_config、覆盖 application.yml 的 hypermux.cast.* 默认值，保存后即时生效不用重启。
 */
import { onMounted, reactive, ref } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NRadioButton,
  NRadioGroup,
  NSpace,
  NSwitch,
  useMessage,
} from 'naive-ui'
import { settingsApi } from '@/api/settings'
import { errMsg } from '@/api/http'

const message = useMessage()
const loading = ref(false)
const saving = ref(false)

const form = reactive({
  metadataProvider: 'tmdb' as 'tmdb' | 'douban',
  tmdbApiKey: '',
  castEnabled: true,
  // NInputNumber 清空时会给到 null，这里允许 null 并在保存时兜回默认值
  castConfirmSeconds: 6 as number | null,
})

async function load() {
  loading.value = true
  try {
    const s = await settingsApi.get()
    form.metadataProvider = s.metadataProvider
    form.tmdbApiKey = s.tmdbApiKey
    form.castEnabled = s.castEnabled
    form.castConfirmSeconds = s.castConfirmSeconds
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function save() {
  saving.value = true
  try {
    const s = await settingsApi.update({
      metadataProvider: form.metadataProvider,
      tmdbApiKey: form.tmdbApiKey.trim(),
      castEnabled: form.castEnabled,
      castConfirmSeconds: form.castConfirmSeconds ?? 6,
    })
    form.metadataProvider = s.metadataProvider
    form.tmdbApiKey = s.tmdbApiKey
    form.castEnabled = s.castEnabled
    form.castConfirmSeconds = s.castConfirmSeconds
    message.success('已保存')
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="hm-page-head">
      <div>
        <div class="hm-page-title">系统设置</div>
        <div class="hm-page-sub">配置元数据刮削来源与远程投屏。新建 / 编辑影片时按片名刮削即取此处设置</div>
      </div>
    </div>

    <NCard :bordered="false" class="hm-card">
      <NForm label-placement="left" label-width="120">
        <NFormItem label="元数据来源">
          <NRadioGroup v-model:value="form.metadataProvider">
            <NRadioButton value="tmdb">TMDB</NRadioButton>
            <NRadioButton value="douban">豆瓣</NRadioButton>
          </NRadioGroup>
          <template #feedback>
            <span class="hint">
              TMDB 需填写 API Key（字段最全，含海报/评分/简介/地区/类型）；豆瓣免 Key，但无官方接口、可能限流或失效。
            </span>
          </template>
        </NFormItem>

        <NFormItem v-if="form.metadataProvider === 'tmdb'" label="TMDB API Key">
          <NInput
            v-model:value="form.tmdbApiKey"
            type="password"
            show-password-on="click"
            placeholder="在 themoviedb.org 申请后粘贴"
            maxlength="128"
          />
          <template #feedback>
            <span class="hint">仅保存在服务端数据库，接口不回传；可随时更换或清空。</span>
          </template>
        </NFormItem>

        <NFormItem v-else label="说明">
          <NAlert type="warning" :bordered="false" style="width: 100%">
            豆瓣刮削为尽力而为，若长时间无结果或报错，建议切换到 TMDB。
          </NAlert>
        </NFormItem>

        <NFormItem label="远程投屏">
          <NSwitch v-model:value="form.castEnabled" />
          <template #feedback>
            <span class="hint">
              本期支持手机端 → 同账号的电视端。关闭后所有端不可投屏：手机端不显示投屏入口，
              电视端轮询拿到空结果（不会报错），已确认的投屏不受影响、可自然播完。
            </span>
          </template>
        </NFormItem>

        <NFormItem label="确认倒计时">
          <NInputNumber
            v-model:value="form.castConfirmSeconds"
            :min="3"
            :max="15"
            :step="1"
            show-button-controls
          />
          <template #feedback>
            <span class="hint">
              电视收到投屏请求后弹窗默认选「是」，倒计时结束自动进入播放；取值 3~15 秒，默认 6 秒。
            </span>
          </template>
        </NFormItem>

        <NFormItem label=" ">
          <NSpace>
            <NButton type="primary" :loading="saving" @click="save">保存</NButton>
            <NButton :loading="loading" @click="load">重置</NButton>
          </NSpace>
        </NFormItem>
      </NForm>
    </NCard>
  </div>
</template>

<style scoped>
.page {
  max-width: none; width: 100%;
}
.hint {
  color: var(--hm-ink-3);
  font-size: 12px;
  line-height: 1.6;
}
</style>
