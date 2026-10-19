<script setup lang="ts">
import { computed } from 'vue'
import { h } from 'vue'
import { NButton, NDataTable, NIcon, NInput, NInputNumber, NSpace, NTag } from 'naive-ui'
import type { DataTableColumn } from 'naive-ui'
import { ChevronDownOutline, ChevronUpOutline, CloseOutline } from '@vicons/ionicons5'
import CopyableText from './CopyableText.vue'
import TimeText from './TimeText.vue'
import { fmtDuration, fmtSize, isRemoteUrl } from '@/utils/format'
import { makeRow, type EpRow } from '@/utils/episode'

const props = withDefaults(
  defineProps<{ rows: EpRow[]; mode?: 'edit' | 'readonly'; single?: boolean }>(),
  { mode: 'edit', single: false },
)
const emit = defineEmits<{ 'update:rows': [rows: EpRow[]] }>()

// (S,E) 前端预校验：重复行标红（后端 409 兜底）
const dupKeys = computed(() => {
  const seen = new Map<string, number>()
  for (const r of props.rows) {
    if (r.seasonNo == null || r.episodeNo == null) continue
    const k = `${r.seasonNo}|${r.episodeNo}`
    seen.set(k, (seen.get(k) ?? 0) + 1)
  }
  return new Set([...seen.entries()].filter(([, n]) => n > 1).map(([k]) => k))
})

function isDup(r: EpRow): boolean {
  if (r.seasonNo == null || r.episodeNo == null) return false
  return dupKeys.value.has(`${r.seasonNo}|${r.episodeNo}`)
}

function patchRow(row: EpRow, patch: Partial<EpRow>) {
  emit('update:rows', props.rows.map((r) => (r.key === row.key ? { ...r, ...patch } : r)))
}

function addRow() {
  const season = props.rows.length ? (props.rows[props.rows.length - 1].seasonNo ?? 1) : 1
  const maxEp = props.rows
    .filter((r) => r.seasonNo === season)
    .reduce((m, r) => Math.max(m, r.episodeNo ?? 0), 0)
  emit('update:rows', [...props.rows, makeRow({ seasonNo: season, episodeNo: maxEp + 1 })])
}

function removeRow(idx: number) {
  const rows = props.rows.slice()
  rows.splice(idx, 1)
  emit('update:rows', rows)
}

function move(idx: number, dir: -1 | 1) {
  const j = idx + dir
  if (j < 0 || j >= props.rows.length) return
  const rows = props.rows.slice()
  ;[rows[idx], rows[j]] = [rows[j], rows[idx]]
  emit('update:rows', rows)
}

// ==================== 编辑模式列 ====================

/** 单集影片（电影）：隐藏「季/集」两列，只当「正片」处理 */
const editColumns = computed<DataTableColumn<EpRow>[]>(() =>
  props.single
    ? allEditColumns.filter((c) => 'key' in c && c.key !== 'seasonNo' && c.key !== 'episodeNo')
    : allEditColumns,
)

const allEditColumns: DataTableColumn<EpRow>[] = [
  {
    title: '季',
    key: 'seasonNo',
    width: 84,
    render: (r) =>
      h(NInputNumber, {
        value: r.seasonNo,
        min: 1,
        max: 99,
        size: 'small',
        status: isDup(r) ? 'error' : undefined,
        'onUpdate:value': (v: number | null) => patchRow(r, { seasonNo: v }),
      }),
  },
  {
    title: '集',
    key: 'episodeNo',
    width: 92,
    render: (r) =>
      h(NInputNumber, {
        value: r.episodeNo,
        min: 1,
        max: 999,
        size: 'small',
        status: isDup(r) ? 'error' : undefined,
        'onUpdate:value': (v: number | null) => patchRow(r, { episodeNo: v }),
      }),
  },
  {
    title: '标题',
    key: 'title',
    minWidth: 130,
    render: (r) =>
      h(NInput, {
        value: r.title,
        size: 'small',
        maxlength: 191,
        placeholder: '留空显示 第N集',
        'onUpdate:value': (v: string) => patchRow(r, { title: v }),
      }),
  },
  {
    title: '直链 / OpenList 路径',
    key: 'url',
    minWidth: 300,
    render: (r) =>
      h('div', { class: 'url-cell' }, [
        h(NInput, {
          value: r.url,
          size: 'small',
          maxlength: 1024,
          placeholder: 'https://… 或 OpenList 相对路径',
          'onUpdate:value': (v: string) => patchRow(r, { url: v }),
        }),
        h(
          NTag,
          { size: 'small', bordered: false, type: isRemoteUrl(r.url) ? 'info' : 'success', class: 'url-tag' },
          { default: () => (r.url && isRemoteUrl(r.url) ? '直链' : 'OpenList') },
        ),
      ]),
  },
  {
    title: '时长(秒)',
    key: 'duration',
    width: 96,
    render: (r) =>
      h(NInputNumber, {
        value: r.duration,
        min: 0,
        size: 'small',
        'onUpdate:value': (v: number | null) => patchRow(r, { duration: v }),
      }),
  },
  {
    title: '缺失',
    key: 'missing',
    width: 68,
    render: (r) =>
      r.missing
        ? h(NTag, { size: 'small', type: 'error' }, { default: () => '缺失' })
        : h('span', { class: 'dim' }, '—'),
  },
  {
    title: '操作',
    key: 'ops',
    width: 100,
    render: (r, idx) =>
      h(NSpace, { size: 2 }, {
        default: () => [
          h(
            'span',
            {
              class: 'op',
              style: idx === 0 ? 'opacity:0.3;pointer-events:none' : '',
              title: '上移',
              onClick: () => move(idx, -1),
            },
            [h(NIcon, { component: ChevronUpOutline, size: 15 })],
          ),
          h(
            'span',
            {
              class: 'op',
              style: idx === props.rows.length - 1 ? 'opacity:0.3;pointer-events:none' : '',
              title: '下移',
              onClick: () => move(idx, 1),
            },
            [h(NIcon, { component: ChevronDownOutline, size: 15 })],
          ),
          h('span', { class: 'op op-del', title: '删除行', onClick: () => removeRow(idx) }, [
            h(NIcon, { component: CloseOutline, size: 15 }),
          ]),
        ],
      }),
  },
]

// ==================== 只读列（影片详情） ====================

const readonlyColumns: DataTableColumn<EpRow>[] = [
  {
    title: '季/集',
    key: 'se',
    width: 80,
    render: (r) => `S${r.seasonNo}E${String(r.episodeNo).padStart(2, '0')}`,
  },
  {
    title: '标题',
    key: 'title',
    minWidth: 140,
    render: (r) => r.title || `第${r.episodeNo}集`,
  },
  {
    title: '直链 / 路径',
    key: 'url',
    minWidth: 280,
    render: (r) =>
      h('div', { class: 'url-cell' }, [
        h(CopyableText, { text: r.url, max: 52 }),
        h(
          NTag,
          { size: 'small', bordered: false, type: isRemoteUrl(r.url) ? 'info' : 'success' },
          { default: () => (isRemoteUrl(r.url) ? '直链' : 'OpenList') },
        ),
      ]),
  },
  { title: '大小', key: 'size', width: 88, render: (r) => fmtSize(r.size) },
  { title: '时长', key: 'duration', width: 76, render: (r) => fmtDuration(r.duration) },
  {
    title: '状态',
    key: 'missing',
    width: 68,
    render: (r) =>
      r.missing
        ? h(NTag, { size: 'small', type: 'error' }, { default: () => '缺失' })
        : h(NTag, { size: 'small', bordered: false }, { default: () => '正常' }),
  },
  {
    title: '更新时间',
    key: 'updateTime',
    width: 110,
    render: (r) => h(TimeText, { ms: r.updateTime }),
  },
]
</script>

<template>
  <div class="ep">
    <NDataTable
      :columns="mode === 'edit' ? editColumns : readonlyColumns"
      :data="rows"
      :row-key="(r: EpRow) => r.key"
      size="medium"
      :bordered="false"
      :row-class-name="(r: EpRow) => (mode === 'edit' && isDup(r) ? 'row-dup' : '')"
    />
    <div v-if="mode === 'edit'" class="foot">
      <NButton dashed size="small" @click="addRow">＋ 添加一集</NButton>
      <span class="count">{{ single ? '正片（单文件影片）' : `共 ${rows.length} 集` }}</span>
    </div>
  </div>
</template>

<style scoped>
.foot {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-top: 10px;
}
.count {
  color: #999;
  font-size: 12px;
}
.url-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}
.url-cell :deep(.n-input) {
  flex: 1;
  min-width: 0;
}
.url-tag {
  flex: none;
}
.op {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 2px;
  border-radius: 4px;
  cursor: pointer;
  color: #666;
}
.op:hover {
  background: #f0f0f0;
}
.op-del:hover {
  color: #d03050;
}
.dim {
  color: #999;
}
:deep(.row-dup) td {
  background: #fff1f0 !important;
}
</style>
