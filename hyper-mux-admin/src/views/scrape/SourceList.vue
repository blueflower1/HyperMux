<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  NAlert,
  NButton,
  NIcon,
  NPopconfirm,
  NSpace,
  NSpin,
  NSwitch,
  NTag,
  useDialog,
  useMessage,
} from 'naive-ui'
import {
  AddOutline,
  CheckmarkCircleOutline,
  CloseCircleOutline,
  CloudOutline,
  LinkOutline,
  PulseOutline,
  RefreshOutline,
} from '@vicons/ionicons5'
import { sourceApi } from '@/api/source'
import { ApiError, errMsg } from '@/api/http'
import type { SourceVo } from '@/api/types'
import TimeText from '@/components/TimeText.vue'
import EmptyState from '@/components/EmptyState.vue'
import SourceForm from './SourceForm.vue'

const message = useMessage()
const dialog = useDialog()

const list = ref<SourceVo[]>([])
const loading = ref(false)

interface TestState {
  loading: boolean
  result: { ok: boolean; version: string | null; message: string } | null
}
const testStates = reactive<Record<number, TestState>>({})
function ts(id: number): TestState {
  if (!testStates[id]) testStates[id] = { loading: false, result: null }
  return testStates[id]
}

async function load() {
  loading.value = true
  try {
    const r = await sourceApi.list()
    list.value = r.list
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function doTest(s: SourceVo) {
  const st = ts(s.id)
  st.loading = true
  try {
    const r = await sourceApi.test(s.id)
    st.result = r
    if (r.ok) message.success(`「${s.name}」连接正常 v${r.version}`)
    else message.error(`「${s.name}」连接失败：${r.message}`)
  } catch (e) {
    st.result = { ok: false, version: null, message: errMsg(e) }
    message.error(errMsg(e))
  } finally {
    st.loading = false
  }
}

async function toggleStatus(s: SourceVo, on: boolean) {
  try {
    await sourceApi.update(s.id, { status: on ? 1 : 0 })
    message.success(on ? `已启用「${s.name}」` : `已停用「${s.name}」`)
    await load()
  } catch (e) {
    message.error(errMsg(e))
  }
}

function askDelete(s: SourceVo) {
  sourceApi
    .remove(s.id)
    .then(() => {
      message.success(`已删除「${s.name}」`)
      load()
    })
    .catch((e) => {
      if (e instanceof ApiError && e.code === 409) {
        dialog.warning({
          title: '无法删除',
          content: e.message || '该源已被影片引用，请改用停用。',
          positiveText: '知道了',
        })
      } else {
        message.error(errMsg(e))
      }
    })
}

const formVisible = ref(false)
const editSource = ref<SourceVo | null>(null)

function openCreate() {
  editSource.value = null
  formVisible.value = true
}
function openEdit(s: SourceVo) {
  editSource.value = s
  formVisible.value = true
}
function onDone() {
  load()
}
</script>

<template>
  <div class="page">
    <div class="hm-page-head">
      <div>
        <div class="hm-page-title">OpenList 数据源</div>
        <div class="hm-page-sub">登记 OpenList 站点；新建影片时可从这里选目录自动填充剧集，并按此源组装公网播放直链</div>
      </div>
      <NSpace>
        <NButton @click="load">
          <template #icon><RefreshOutline /></template>
          刷新
        </NButton>
        <NButton type="primary" @click="openCreate">
          <template #icon><AddOutline /></template>
          新建数据源
        </NButton>
      </NSpace>
    </div>

    <NAlert type="info" :bordered="false" class="tip" title="只做配置">
      数据源仅登记站点地址、访问凭据与默认根目录。创建影片时点「从 OpenList 导入」，选目录即可自动解析片名/年份/剧集，
      并可按片名刮削元数据；<strong>API Token 使用 AES-256-GCM 加密存储，任何接口都不会返回明文</strong>。
    </NAlert>

    <NSpin :show="loading">
      <div v-if="list.length" class="grid hm-stagger">
        <div v-for="(s, i) in list" :key="s.id" class="hm-card hm-card--lift src-card" :style="{ '--i': i } as any">
          <div class="src-head">
            <div class="hm-chip hm-chip--green src-ico"><CloudOutline /></div>
            <div class="src-name" :title="s.name">{{ s.name }}</div>
            <NSwitch :value="s.status === 1" size="small" @update:value="(v: boolean) => toggleStatus(s, v)" />
          </div>

          <div class="src-url">
            <NIcon size="14"><LinkOutline /></NIcon>
            <span class="mono" :title="s.serverUrl">{{ s.serverUrl }}</span>
          </div>

          <div class="src-meta">
            <div class="meta-row">
              <span class="k">默认根目录</span>
              <span class="v mono">{{ s.rootPath || '/' }}</span>
            </div>
            <div class="meta-row">
              <span class="k">关联影片</span>
              <span class="v">
                <NTag v-if="s.movieCount > 0" size="small" type="info" :bordered="false">{{ s.movieCount }} 部</NTag>
                <span v-else class="dim">0 部</span>
              </span>
            </div>
            <div class="meta-row">
              <span class="k">状态</span>
              <span class="v">
                <NTag size="small" :type="s.status === 1 ? 'success' : 'default'" :bordered="false">
                  {{ s.status === 1 ? '启用中' : '已停用' }}
                </NTag>
              </span>
            </div>
            <div class="meta-row">
              <span class="k">更新时间</span>
              <span class="v"><TimeText :ms="s.updateTime" /></span>
            </div>
          </div>

          <div class="src-test">
            <div
              v-if="ts(s.id).result"
              class="hm-result"
              :class="ts(s.id).result!.ok ? 'hm-result--ok' : 'hm-result--err'"
            >
              <NIcon v-if="ts(s.id).result!.ok" size="18" class="res-ico"><CheckmarkCircleOutline /></NIcon>
              <NIcon v-else size="18" class="res-ico"><CloseCircleOutline /></NIcon>
              <div>
                <template v-if="ts(s.id).result!.ok">连接正常 · 版本 {{ ts(s.id).result!.version }}</template>
                <template v-else>连接失败：{{ ts(s.id).result!.message }}</template>
              </div>
            </div>
          </div>

          <div class="src-foot">
            <NButton size="small" :loading="ts(s.id).loading" @click="doTest(s)">
              <template #icon><PulseOutline /></template>
              测试连接
            </NButton>
            <NSpace :size="4">
              <NButton size="small" quaternary @click="openEdit(s)">编辑</NButton>
              <NPopconfirm :on-positive-click="() => askDelete(s)" positive-text="删除" negative-text="取消">
                <template #trigger>
                  <NButton size="small" quaternary type="error">删除</NButton>
                </template>
                <div>
                  删除数据源「{{ s.name }}」？<span v-if="s.movieCount > 0">（其关联影片不会被删除，但将失去播放直链来源）</span>
                </div>
              </NPopconfirm>
            </NSpace>
          </div>
        </div>
      </div>
      <EmptyState v-else-if="!loading" desc="还没有数据源。在 OpenList 拿到 API Token 后，到这里登记站点">
        <template #action>
          <NButton type="primary" @click="openCreate">
            <template #icon><AddOutline /></template>
            新建第一个数据源
          </NButton>
        </template>
      </EmptyState>
    </NSpin>

    <SourceForm v-model:show="formVisible" :source="editSource" @done="onDone" />
  </div>
</template>

<style scoped>
.page {
  max-width: none; width: 100%;
}
.tip {
  margin-bottom: 18px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}
.src-card {
  display: flex;
  flex-direction: column;
  padding: 16px 18px 14px;
  gap: 12px;
}
.src-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.src-ico {
  width: 38px;
  height: 38px;
}
.src-ico svg {
  width: 20px;
  height: 20px;
}
.src-name {
  flex: 1;
  font-size: 15px;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.src-url {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 8px 10px;
  background: #f6f8fb;
  border-radius: 9px;
  color: var(--hm-ink-2);
}
.src-url .mono {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.src-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 14px;
}
.meta-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  min-width: 0;
}
.meta-row .k {
  color: var(--hm-ink-3);
  flex: none;
}
.meta-row .v {
  color: var(--hm-ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.dim {
  color: var(--hm-ink-3);
}
.src-test {
  min-height: 6px;
}
.res-ico {
  flex: none;
  margin-top: 1px;
}
.src-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 10px;
  border-top: 1px dashed var(--hm-line);
  gap: 8px;
}
</style>
