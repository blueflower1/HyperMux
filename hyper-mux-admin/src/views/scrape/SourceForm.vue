<script setup lang="ts">
/**
 * OpenList 数据源表单（新建 / 编辑）。
 * 数据源仅登记：站点地址 + 凭据（API Token 或账密换取，服务端会话代持）+ 默认浏览根目录。
 * 保存前可「测试连接」验证连通性；根目录可用目录浏览器选择。
 */
import { computed, reactive, ref, watch } from 'vue'
import {
  NAlert,
  NButton,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NModal,
  NRadioButton,
  NRadioGroup,
  NSpace,
  NSwitch,
  useMessage,
} from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'
import { FolderOpenOutline } from '@vicons/ionicons5'
import { openlistApi, sourceApi, type OpenlistSessionReq } from '@/api/source'
import { errMsg } from '@/api/http'
import type { SourceVo } from '@/api/types'
import OpenListPicker from '@/components/OpenListPicker.vue'

const props = defineProps<{ show: boolean; source: SourceVo | null }>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'done'): void
}>()

const message = useMessage()
const editing = computed(() => props.source != null)

const form = reactive({
  name: '',
  serverUrl: '',
  rootPath: '/',
  mode: 'token' as 'token' | 'password',
  apiToken: '',
  username: '',
  password: '',
  otp: '',
  status: 1,
})

const formRef = ref<FormInst | null>(null)
const formRules: FormRules = {
  name: [{ required: true, message: '请输入数据源名称', trigger: ['input', 'blur'] }],
  serverUrl: [
    { required: true, message: '请输入 OpenList 地址', trigger: ['input', 'blur'] },
    { pattern: /^https?:\/\/\S+$/i, message: '须 http(s):// 开头', trigger: ['input', 'blur'] },
  ],
}

const connecting = ref(false)
const connected = ref(false)
const version = ref<string | null>(null)
const sessionId = ref<string | null>(null)

const pickerVisible = ref(false)

function reset() {
  const s = props.source
  form.name = s?.name ?? ''
  form.serverUrl = s?.serverUrl ?? ''
  form.rootPath = s?.rootPath ?? '/'
  form.mode = 'token'
  form.apiToken = ''
  form.username = ''
  form.password = ''
  form.otp = ''
  form.status = s?.status ?? 1
  connected.value = false
  version.value = null
  sessionId.value = null
}

watch(
  () => props.show,
  (v) => {
    if (v) reset()
  },
)

function normalizeUrl(): string {
  return form.serverUrl.trim().replace(/\/+$/, '')
}

async function connect(): Promise<boolean> {
  const url = normalizeUrl()
  if (!/^https?:\/\/\S+$/i.test(url)) {
    message.warning('请输入正确的 OpenList 地址（http(s):// 开头）')
    return false
  }
  let body: OpenlistSessionReq
  if (form.mode === 'password') {
    if (!form.username.trim() || !form.password) {
      message.warning('请输入账号与密码')
      return false
    }
    body = { serverUrl: url, username: form.username.trim(), password: form.password, otpCode: form.otp.trim() || undefined }
  } else if (form.apiToken.trim()) {
    body = { serverUrl: url, apiToken: form.apiToken.trim() }
  } else if (props.source && url === props.source.serverUrl) {
    body = { sourceId: props.source.id }
  } else {
    message.warning('请填写 API Token（或改用账号密码登录）')
    return false
  }
  connecting.value = true
  try {
    const r = await openlistApi.session(body)
    sessionId.value = r.sessionId
    version.value = r.version
    connected.value = true
    message.success(`连接成功${r.version ? ` · OpenList v${r.version}` : ''}`)
    return true
  } catch (e) {
    connected.value = false
    sessionId.value = null
    message.error(errMsg(e))
    return false
  } finally {
    connecting.value = false
  }
}

function onPickRoot(p: string) {
  form.rootPath = p || '/'
}

const saving = ref(false)

async function save() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  const url = normalizeUrl()
  // 凭据校验：新建必须有 token/session；编辑可留空沿用
  const hasToken = form.mode === 'token' && !!form.apiToken.trim()
  if (!editing.value && !sessionId.value && !hasToken) {
    message.warning('请先「测试连接」或填写 API Token')
    return
  }
  if (editing.value && url !== props.source!.serverUrl && !sessionId.value && !hasToken) {
    message.warning('地址已更改，请重新连接或填写新的 API Token')
    return
  }
  saving.value = true
  try {
    const body = {
      name: form.name.trim(),
      serverUrl: url,
      rootPath: form.rootPath || '/',
      ...(sessionId.value ? { sessionId: sessionId.value } : {}),
      ...(hasToken ? { apiToken: form.apiToken.trim() } : {}),
    }
    if (editing.value) {
      await sourceApi.update(props.source!.id, { ...body, status: form.status })
      message.success('已保存')
    } else {
      await sourceApi.create(body)
      message.success('已创建数据源')
    }
    emit('update:show', false)
    emit('done')
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    :title="editing ? `编辑数据源「${source!.name}」` : '新建 OpenList 数据源'"
    style="width: 660px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <NAlert type="info" :bordered="false" class="tip">
      OpenList 仅用于登记站点并提供视频直链。创建影片时可从该源直接选目录自动填充剧集。
    </NAlert>

    <NForm ref="formRef" :model="form" :rules="formRules" label-placement="left" label-width="96">
      <NFormItem label="数据源名称" path="name" required>
        <NInput v-model:value="form.name" placeholder="如：主站 · 电影库" maxlength="64" />
      </NFormItem>
      <NFormItem label="OpenList 地址" path="serverUrl" required>
        <NInput v-model:value="form.serverUrl" placeholder="https://your-openlist.example.com" @input="connected = false" />
        <template #feedback>
          <span class="hint">结尾的 / 不需要写；播放直链按 地址/d/路径 组装</span>
        </template>
      </NFormItem>

      <NFormItem label="登录方式">
        <NRadioGroup v-model:value="form.mode" @update:value="connected = false">
          <NRadioButton value="token">API Token</NRadioButton>
          <NRadioButton value="password">账号密码</NRadioButton>
        </NRadioGroup>
      </NFormItem>
      <NFormItem v-if="form.mode === 'token'" label="API Token">
        <input
          v-model="form.apiToken"
          type="password"
          class="pwd-input"
          autocomplete="off"
          placeholder="OpenList 个人访问令牌"
          @input="connected = false"
        />
        <template v-if="editing" #feedback>
          <span class="hint">留空 = 沿用已存 Token；填写 = 更换</span>
        </template>
      </NFormItem>
      <template v-else>
        <NFormItem label="账号">
          <NInput v-model:value="form.username" placeholder="OpenList 账号" />
        </NFormItem>
        <NFormItem label="密码">
          <input v-model="form.password" type="password" class="pwd-input" autocomplete="off" placeholder="OpenList 密码" />
        </NFormItem>
        <NFormItem label="验证码">
          <NInput v-model:value="form.otp" placeholder="未开启验证码 / 2FA 可留空" />
        </NFormItem>
      </template>

      <NFormItem label="默认根目录">
        <NInput v-model:value="form.rootPath" placeholder="/" style="flex: 1">
          <template #suffix>
            <NButton text :disabled="!connected" @click="pickerVisible = true">
              <template #icon><NIcon><FolderOpenOutline /></NIcon></template>
              浏览
            </NButton>
          </template>
        </NInput>
        <template #feedback>
          <span class="hint">新建影片选目录时的起点；可留默认 /</span>
        </template>
      </NFormItem>

      <NFormItem v-if="editing" label="启用">
        <NSwitch :value="form.status === 1" @update:value="(v: boolean) => (form.status = v ? 1 : 0)" />
        <span class="hint switch-hint">停用后其下影片将无法组装播放直链</span>
      </NFormItem>
    </NForm>

    <NAlert v-if="connected" type="success" :bordered="false" class="ok">
      连接成功{{ version ? ` · OpenList v${version}` : '' }}；Token 由服务端加密保存，任何接口都不回传明文
    </NAlert>

    <template #footer>
      <NSpace justify="space-between" align="center">
        <NButton :loading="connecting" @click="connect">测试连接</NButton>
        <NSpace>
          <NButton @click="emit('update:show', false)">取消</NButton>
          <NButton type="primary" :loading="saving" @click="save">{{ editing ? '保存' : '创建' }}</NButton>
        </NSpace>
      </NSpace>
    </template>

    <OpenListPicker
      v-if="sessionId"
      v-model:show="pickerVisible"
      :session-id="sessionId"
      :initial-path="form.rootPath || '/'"
      title="选择默认根目录"
      confirm-text="选为根目录"
      @select="onPickRoot"
      @session-expired="connected = false"
    />
  </NModal>
</template>

<style scoped>
.tip {
  margin-bottom: 14px;
}
.pwd-input {
  width: 100%;
  height: 34px;
  padding: 0 12px;
  border: 1px solid #e0e0e6;
  border-radius: 3px;
  font-size: 14px;
  outline: none;
}
.pwd-input:focus {
  border-color: var(--hm-accent, #2080f0);
}
.hint {
  color: var(--hm-ink-3);
  font-size: 12px;
}
.switch-hint {
  margin-left: 10px;
}
.ok {
  margin-top: 4px;
}
</style>
