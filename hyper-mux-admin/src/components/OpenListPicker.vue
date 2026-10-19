<script setup lang="ts">
/**
 * OpenList 目录选择器（弹窗）：以既有会话浏览目录，选中一个绝对路径回传。
 * 数据源表单「选根目录」与新建影片「选影片目录」共用。
 */
import { computed, ref, watch } from 'vue'
import { NButton, NIcon, NModal, NSpace, NSpin, NText } from 'naive-ui'
import { ChevronForwardOutline, DocumentOutline, FolderOutline } from '@vicons/ionicons5'
import { openlistApi, type OpenlistListResult } from '@/api/source'
import { ApiError, errMsg } from '@/api/http'
import type { OpenlistEntry } from '@/api/types'

const props = withDefaults(
  defineProps<{
    show: boolean
    sessionId: string | null
    initialPath?: string
    title?: string
    confirmText?: string
    /** 是否允许选中文件（默认仅目录） */
    allowFile?: boolean
  }>(),
  { initialPath: '/', title: '选择目录', confirmText: '选择当前目录', allowFile: false },
)
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'select', path: string): void
  (e: 'session-expired'): void
}>()

const cwd = ref('/')
const entries = ref<OpenlistEntry[]>([])
const page = ref(1)
const hasMore = ref(false)
const browsing = ref(false)
const pickedFile = ref<string | null>(null)
const errText = ref('')

async function load(reset: boolean) {
  if (!props.sessionId) return
  if (reset) {
    page.value = 1
    entries.value = []
  }
  browsing.value = true
  errText.value = ''
  try {
    const r: OpenlistListResult = await openlistApi.list(props.sessionId, cwd.value, page.value)
    entries.value = reset ? r.list : [...entries.value, ...r.list]
    hasMore.value = r.hasMore
  } catch (e) {
    entries.value = []
    hasMore.value = false
    if (e instanceof ApiError && e.code === 400 && e.message.includes('会话')) {
      errText.value = e.message
      emit('session-expired')
    } else {
      // 直接在弹窗里显示原因（此前只 console.error，用户看不见）
      errText.value = errMsg(e)
    }
  } finally {
    browsing.value = false
  }
}

function joinCwd(base: string, seg: string): string {
  const b = base === '/' ? '' : base.replace(/\/+$/, '')
  const s = seg.replace(/^\/+/, '')
  return `${b}/${s}`
}

function enterDir(e: OpenlistEntry) {
  if (!e.dir) {
    if (props.allowFile) pickedFile.value = e.path
    return
  }
  const p = e.path && e.path.startsWith('/') ? e.path : joinCwd(cwd.value, e.path || e.name)
  cwd.value = p
  pickedFile.value = null
  load(true)
}

function gotoPath(p: string) {
  cwd.value = p || '/'
  pickedFile.value = null
  load(true)
}

function loadMore() {
  page.value += 1
  load(false)
}

function confirm() {
  const p = props.allowFile && pickedFile.value ? pickedFile.value : cwd.value
  emit('select', p || '/')
  emit('update:show', false)
}

const crumbs = computed(() => {
  const out: { name: string; path: string }[] = [{ name: '/', path: '/' }]
  let cur = ''
  for (const seg of cwd.value.split('/').filter(Boolean)) {
    cur += '/' + seg
    out.push({ name: seg, path: cur })
  }
  return out
})

function fmtSize(n: number): string {
  if (!n) return ''
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let v = n
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${i === 0 ? v : v >= 10 ? Math.round(v) : v.toFixed(1)} ${units[i]}`
}

watch(
  () => props.show,
  (v) => {
    if (!v) return
    cwd.value = props.initialPath || '/'
    pickedFile.value = null
    load(true)
  },
  // immediate：组件可能以 show=true 直接挂载（首次点「选择目录并自动填充」时
  // pickerSessionId 与 show 同一次渲染生效），此时 watch 不会触发 → 目录空白。
  { immediate: true },
)
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    :title="title"
    style="width: 720px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="crumbs">
      <template v-for="(c, i) in crumbs" :key="c.path">
        <span v-if="i > 0" class="sep">/</span>
        <a class="crumb" :class="{ cur: i === crumbs.length - 1 }" @click="gotoPath(c.path)">{{ c.name }}</a>
      </template>
    </div>

    <div class="list">
      <NSpin :show="browsing">
        <div v-if="errText" class="dir-err">
          {{ errText }}
          <NButton text type="primary" size="tiny" class="retry" @click="load(true)">重试</NButton>
        </div>
        <div v-else-if="!entries.length && !browsing" class="empty-dir">（空目录）</div>
        <div
          v-for="e in entries"
          :key="e.path"
          class="row"
          :class="{ picked: allowFile && !e.dir && pickedFile === e.path }"
        >
          <NIcon size="16" class="row-ico">
            <FolderOutline v-if="e.dir" />
            <DocumentOutline v-else />
          </NIcon>
          <span class="nm" :class="{ clickable: e.dir || allowFile }" :title="e.path" @click="enterDir(e)">
            {{ e.name }}
          </span>
          <NIcon v-if="e.dir" size="13" class="go"><ChevronForwardOutline /></NIcon>
          <span class="sz">{{ e.dir ? '' : fmtSize(e.size) }}</span>
        </div>
        <NButton v-if="hasMore" block quaternary size="small" class="more" @click="loadMore">加载更多…</NButton>
      </NSpin>
    </div>

    <NText depth="3" class="cur">{{ allowFile && pickedFile ? `已选文件：${pickedFile}` : `当前目录：${cwd}` }}</NText>

    <template #footer>
      <NSpace justify="end">
        <NButton @click="emit('update:show', false)">取消</NButton>
        <NButton type="primary" @click="confirm">{{ confirmText }}</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped>
.crumbs {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px;
  padding: 7px 10px;
  background: #f6f8fb;
  border-radius: 8px;
  font-size: 13px;
  margin-bottom: 10px;
}
.crumb {
  cursor: pointer;
  color: var(--hm-ink-2);
  padding: 1px 3px;
  border-radius: 4px;
}
.crumb:hover {
  background: #e8edf5;
}
.crumb.cur {
  color: var(--hm-ink);
  font-weight: 600;
  cursor: default;
}
.sep {
  color: var(--hm-ink-3);
}
.list {
  min-height: 300px;
  max-height: 380px;
  overflow: auto;
  border: 1px solid var(--hm-line, #e5e6eb);
  border-radius: 9px;
  padding: 6px 8px;
}
.empty-dir {
  color: var(--hm-ink-3);
  text-align: center;
  padding: 40px 0;
  font-size: 13px;
}
.dir-err {
  color: #d03050;
  background: #fff1f2;
  border: 1px solid #ffd6da;
  border-radius: 8px;
  padding: 10px 12px;
  margin: 8px;
  font-size: 13px;
  word-break: break-all;
}
.row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 7px;
  font-size: 13.5px;
}
.row:hover {
  background: #f6f8fb;
}
.row.picked {
  background: var(--hm-primary-soft, #eef4ff);
}
.row-ico {
  color: var(--hm-ink-3);
  flex: none;
}
.nm {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.nm.clickable {
  cursor: pointer;
}
.nm.clickable:hover {
  color: var(--hm-accent, #2080f0);
}
.go {
  color: var(--hm-ink-3);
  flex: none;
}
.sz {
  color: var(--hm-ink-3);
  font-size: 12px;
  flex: none;
  min-width: 62px;
  text-align: right;
}
.more {
  margin-top: 4px;
}
.retry {
  margin-left: 8px;
}
.cur {
  display: block;
  margin-top: 10px;
  font-size: 12.5px;
  word-break: break-all;
}
</style>
