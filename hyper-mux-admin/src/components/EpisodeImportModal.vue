<script setup lang="ts">
import { computed, h, ref, watch } from 'vue'
import {
  NButton,
  NDataTable,
  NInput,
  NModal,
  NSelect,
  NSpace,
  NText,
  useMessage,
} from 'naive-ui'
import type { DataTableColumn } from 'naive-ui'
import { videoApi, type ImportError } from '@/api/video'
import { errMsg } from '@/api/http'
import { previewImport, type ImportLine, type ImportPreview } from '@/utils/episode'
import { truncate } from '@/utils/format'

const show = defineModel<boolean>('show', { required: true })

const props = defineProps<{
  movieId: number
  seasons: number[]
  /** 当前全部 (季,集)，用于计算“接到第 N 集起”与覆盖判定 */
  episodes: { seasonNo: number; episodeNo: number }[]
  /** 影片是否已配置 sourceId（相对路径放行前提） */
  hasSource: boolean
}>()
const emit = defineEmits<{ success: [] }>()

const msg = useMessage()

const season = ref(1)
const text = ref('')
const preview = ref<ImportPreview | null>(null)
const importing = ref(false)

// 行级错误明细弹窗
const resultVisible = ref(false)
const resultStat = ref('')
const resultErrors = ref<ImportError[]>([])

watch(show, (v) => {
  if (v) {
    text.value = ''
    preview.value = null
    resultErrors.value = []
    season.value = props.seasons.includes(1) ? 1 : (props.seasons[0] ?? 1)
  }
})

function runPreview() {
  preview.value = previewImport(text.value, season.value, props.episodes, props.hasSource)
}

async function confirmImport() {
  if (!text.value.trim()) return
  importing.value = true
  try {
    const r = await videoApi.importText(props.movieId, season.value, text.value)
    if (r.errors.length) {
      resultStat.value = `新增 ${r.added} · 覆盖 ${r.updated} · 跳过 ${r.errors.length} 行`
      resultErrors.value = r.errors
      resultVisible.value = true
    } else {
      msg.success(`导入完成：新增 ${r.added} · 覆盖 ${r.updated}`)
      show.value = false
    }
    emit('success')
  } catch (e) {
    msg.error(errMsg(e))
  } finally {
    importing.value = false
  }
}

const seasonOptions = computed(() =>
  (props.seasons.length ? props.seasons : [1]).map((s) => ({ label: `第 ${s} 季`, value: s })),
)

function resultLabel(l: ImportLine) {
  if (l.error) return h(NText, { type: 'error' }, { default: () => l.error })
  return l.action === 'overwrite'
    ? h(NText, { type: 'warning' }, { default: () => '覆盖已有' })
    : h(NText, { type: 'success' }, { default: () => '新增' })
}

const previewCols: DataTableColumn<ImportLine>[] = [
  { title: '行', key: 'line', width: 48, render: (l) => l.line },
  { title: '集号', key: 'epNo', width: 60, render: (l) => l.epNo },
  { title: '方式', key: 'mode', width: 64, render: (l) => (l.mode === 'explicit' ? '指定' : '接续') },
  { title: 'url', key: 'url', minWidth: 220, render: (l) => truncate(l.url, 40) },
  { title: '结果', key: 'result', minWidth: 150, render: resultLabel },
]

const errCols: DataTableColumn<ImportError>[] = [
  { title: '行号', key: 'line', width: 70 },
  { title: '原因', key: 'msg', render: (e) => h(NText, { type: 'error' }, { default: () => e.msg }) },
]
</script>

<template>
  <NModal v-model:show="show" preset="card" title="批量导入剧集" style="width: 760px">
    <NSpace vertical :size="12">
      <div class="row">
        <span class="label">导入到季</span>
        <NSelect v-model:value="season" :options="seasonOptions" size="small" style="width: 130px" />
        <NText v-if="preview" depth="3" class="hint">
          裸链接从第 {{ preview.startEp }} 集起接续
        </NText>
      </div>

      <NInput
        v-model:value="text"
        type="textarea"
        :autosize="{ minRows: 8, maxRows: 16 }"
        placeholder="每行一条：https://…  或  41,https://…"
        class="mono"
      />

      <div v-if="!preview">
        <NInput
          readonly
          type="textarea"
          class="fmt mono"
          :autosize="{ minRows: 3, maxRows: 6 }"
          value="https://cdn.example.com/movie/01.mkv     ← 裸链接：接续该季最大集号 +1
41,https://cdn.example.com/movie/41.mkv     ← 指定集号；该季已存在的集 → 覆盖
42,https://cdn.example.com/movie/42.mkv"
        />
      </div>

      <div v-if="preview" class="row">
        <span class="stat">
          将
          <NText type="success">新增 {{ preview.added }} 集</NText>·
          <NText type="warning">覆盖 {{ preview.updated }} 集</NText>·
          <NText :type="preview.skipped ? 'error' : undefined">跳过 {{ preview.skipped }} 行</NText>
        </span>
        <NButton size="small" @click="runPreview">重新预览</NButton>
      </div>
      <NDataTable
        v-if="preview && preview.lines.length"
        :columns="previewCols"
        :data="preview.lines.slice(0, 8)"
        size="small"
        :bordered="false"
        :max-height="220"
        :row-key="(l: ImportLine) => l.line"
      />
      <NText v-if="preview && preview.lines.length > 8" depth="3" style="font-size: 12px">
        … 仅显示前 8 行，共 {{ preview.lines.length }} 行
      </NText>
    </NSpace>

    <template #footer>
      <NSpace justify="end">
        <NButton @click="show = false">取消</NButton>
        <NButton type="primary" :loading="importing" :disabled="!text.trim()" @click="confirmImport">
          确认导入
        </NButton>
      </NSpace>
    </template>
  </NModal>

  <!-- 行级错误明细 -->
  <NModal v-model:show="resultVisible" preset="card" title="导入结果" style="width: 480px">
    <NSpace vertical :size="10">
      <NText>{{ resultStat }}</NText>
      <NDataTable
        v-if="resultErrors.length"
        :columns="errCols"
        :data="resultErrors"
        size="small"
        :bordered="false"
        :max-height="260"
        :row-key="(e: ImportError) => e.line"
      />
    </NSpace>
    <template #footer>
      <NButton type="primary" @click="resultVisible = false">知道了</NButton>
    </template>
  </NModal>
</template>

<style scoped>
.row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.label {
  color: #666;
  font-size: 13px;
}
.hint {
  font-size: 12px;
}
.stat {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}
.mono {
  font-family: Consolas, Menlo, monospace;
}
</style>
