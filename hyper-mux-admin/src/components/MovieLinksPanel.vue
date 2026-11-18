<script setup lang="ts">
/**
 * 「其他季」关联面板（影片编辑页内）。
 *
 * 关联建在影片与影片之间：库里每一季各自是一条 movie 记录（季只挂在 video.season_no 上，
 * movie 本身没有季概念），所以这里挑的是"库里已有的另一部影片"，不是给本季加字段。
 *
 * 双向对称：服务端每次写入都成对落两条、删除也成对删（MovieLinkAdminService），所以管理员只需要
 * 在一侧添加一次，对方影片的编辑页会自动出现同一条关联。反过来，只有对方那一侧落了行的半截数据
 * 也会在本侧列出来（vo.oneWay），保存一次即自动补齐成对。
 *
 * 添加支持多选：一次挑多部 → 落到下面的「待关联」暂存表逐条调文案与排序 → 一次提交全部生效。
 */
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  NButton,
  NDataTable,
  NEmpty,
  NImage,
  NInput,
  NInputNumber,
  NPopconfirm,
  NSelect,
  NSpace,
  NTag,
  NText,
  useMessage,
} from 'naive-ui'
import type { DataTableColumn, SelectOption } from 'naive-ui'
import { movieApi } from '@/api/movie'
import { movieLinkApi } from '@/api/movieLink'
import { errMsg } from '@/api/http'
import type { MovieLinkVo, MovieStatus } from '@/api/types'
import { imgUrl } from '@/utils/image'

const props = defineProps<{ movieId: number; movieTitle: string }>()

/** 服务端 MovieLinkAdminService.MAX_LINKS：前端提前拦一下，免得提交时才报错 */
const MAX_LINKS = 50

const STATUS_TEXT: Record<MovieStatus, string> = {
  PUBLISHED: '已上架',
  DRAFT: '草稿',
  OFF: '已下架',
}

/** 关系类型只影响观众端区块标题：全是 season 时显示「其他季」，否则显示「关联影片」 */
const RELATION_TEXT: Record<string, string> = {
  season: '其他季',
  spinoff: '衍生',
  custom: '自定义',
}
const RELATION_OPTIONS = [
  { label: '同剧其他季', value: 'season' },
  { label: '衍生 / 番外', value: 'spinoff' },
  { label: '自定义', value: 'custom' },
]

/** 行内编辑要能判断"这行改过没有"，所以在原始值旁边留一份快照 */
interface LinkRow extends MovieLinkVo {
  orig: { label: string | null; reverseLabel: string | null; sortNo: number; relationType: string }
}

/** 多选挑出来、还没提交的关联 */
interface PendingItem {
  targetId: number
  title: string
  meta: string
  label: string
  reverseLabel: string
  sortNo: number | null
}

const message = useMessage()
const rows = ref<LinkRow[]>([])
const loading = ref(false)
const savingRow = ref<number | null>(null)
const removingRow = ref<number | null>(null)
const resorting = ref(false)

/* ---------- 添加区（多选 + 暂存） ---------- */
const pickedIds = ref<number[]>([])
const pending = ref<PendingItem[]>([])
const relationType = ref('season')
const adding = ref(false)
const searching = ref(false)
const options = ref<SelectOption[]>([])
/** 候选影片的元信息：搜索结果是会被覆盖的，选中的那批要一直能显示片名，所以单独存一份 */
const pickedMeta = new Map<number, { title: string; year: number | null; episodeTotal: number; status: MovieStatus }>()

const overLimit = computed(() => rows.value.length + pending.value.length > MAX_LINKS)

const statusTag = (s: MovieStatus) =>
  s === 'PUBLISHED'
    ? h(NTag, { size: 'small', type: 'success', bordered: false }, { default: () => '已上架' })
    : h(
        NTag,
        { size: 'small', type: 'warning', bordered: false },
        { default: () => `${STATUS_TEXT[s] ?? s} · App 端不显示` },
      )

function isDirty(r: LinkRow): boolean {
  return (
    (r.label ?? '') !== (r.orig.label ?? '') ||
    (r.reverseLabel ?? '') !== (r.orig.reverseLabel ?? '') ||
    (r.sortNo ?? 0) !== r.orig.sortNo ||
    r.relationType !== r.orig.relationType
  )
}

async function load() {
  loading.value = true
  try {
    const list = await movieLinkApi.list(props.movieId)
    rows.value = list.map((l) => ({
      ...l,
      orig: {
        label: l.label,
        reverseLabel: l.reverseLabel,
        sortNo: l.sortNo ?? 0,
        relationType: l.relationType,
      },
    }))
    markLinked()
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    loading.value = false
  }
}

/**
 * 远程搜索。
 *
 * 坑点（踩过一次）：naive-ui 的 NSelect **没有** `update:search-value` 事件，搜索处理器只有
 * `onSearch` 这个 prop（模板里写 `@search`）。绑错事件不会报错，只是静默地一次请求都不发，
 * 于是 `remote` 模式的下拉永远显示「无数据」，看着像"库里查不到这部片"。
 */
let searchTimer: number | undefined

function onSearch(q: string) {
  if (searchTimer) window.clearTimeout(searchTimer)
  // 每敲一个字就打一次接口太吵，压 260ms
  searchTimer = window.setTimeout(() => void fetchOptions(q), 260)
}

async function fetchOptions(q: string) {
  searching.value = true
  try {
    // 库内全量（含未上架）：管理员常常是"先把季关联好，再陆续上架"，只让搜已上架的会卡住他
    const r = await movieApi.page({ page: 1, size: 20, keyword: q.trim() || undefined, status: 'PUBLISHED,DRAFT,OFF' })
    const next: SelectOption[] = []
    for (const m of r.list) {
      if (m.id === props.movieId) continue
      pickedMeta.set(m.id, { title: m.title, year: m.year, episodeTotal: m.episodeTotal, status: m.status })
      next.push({
        label: `${m.title}${m.year ? ' · ' + m.year : ''} · ${m.episodeTotal} 集 · ${STATUS_TEXT[m.status]}`,
        value: m.id,
      })
    }
    options.value = next
    markLinked()
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    searching.value = false
  }
}

/** 已经关联过的候选直接禁选：省得管理员重复挑一遍、提交时才发现是"更新"而不是"新增" */
function markLinked() {
  const linked = new Set(rows.value.map((r) => r.relatedMovieId))
  for (const o of options.value) {
    const disabled = linked.has(o.value as number)
    if (o.disabled !== disabled) o.disabled = disabled
  }
}

/** NSelect 多选：勾选/取消都在这里把暂存表对齐（已有的条目保留管理员改过的文案） */
function onPick(v: number[] | null) {
  const ids = (v ?? []).filter((id) => typeof id === 'number' && id !== props.movieId)
  pickedIds.value = ids
  const kept: PendingItem[] = []
  for (const id of ids) {
    const exist = pending.value.find((p) => p.targetId === id)
    if (exist) {
      kept.push(exist)
      continue
    }
    const info = pickedMeta.get(id)
    kept.push({
      targetId: id,
      title: info?.title ?? `影片 #${id}`,
      meta: info ? `${info.year ?? '—'} · ${info.episodeTotal} 集 · ${STATUS_TEXT[info.status]}` : '',
      label: '',
      reverseLabel: '',
      sortNo: null,
    })
  }
  pending.value = kept
}

function dropPending(p: PendingItem) {
  pending.value = pending.value.filter((x) => x.targetId !== p.targetId)
  pickedIds.value = pickedIds.value.filter((x) => x !== p.targetId)
}

function clearPending() {
  pending.value = []
  pickedIds.value = []
}

async function submitPending() {
  if (!pending.value.length) return
  adding.value = true
  try {
    const r = await movieLinkApi.add(
      props.movieId,
      pending.value.map((p) => ({
        relatedMovieId: p.targetId,
        label: p.label.trim() || null,
        reverseLabel: p.reverseLabel.trim() || null,
        sortNo: p.sortNo,
        relationType: relationType.value,
      })),
    )
    const parts: string[] = []
    if (r.added) parts.push(`新增 ${r.added} 条`)
    if (r.updated) parts.push(`更新 ${r.updated} 条`)
    message.success(`${parts.join(' · ') || '已提交'}（本片与对方那一侧同时生效）· 当前 ${r.total} 条关联`)
    clearPending()
    await load()
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    adding.value = false
  }
}

async function saveRow(r: LinkRow) {
  savingRow.value = r.linkId
  try {
    await movieLinkApi.add(props.movieId, [
      {
        relatedMovieId: r.relatedMovieId,
        label: r.label ?? '',
        reverseLabel: r.reverseLabel ?? '',
        sortNo: r.sortNo ?? 0,
        relationType: r.relationType,
      },
    ])
    message.success(r.oneWay ? '已补齐成对：这条关联现在两侧都有了' : '已更新这条关联（含对方那一侧）')
    await load()
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    savingRow.value = null
  }
}

async function removeLink(r: LinkRow) {
  removingRow.value = r.linkId
  try {
    await movieLinkApi.remove(props.movieId, r.linkId)
    message.success(`已解除与「${r.title}」的关联（两侧一起解除）`)
    await load()
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    removingRow.value = null
  }
}

/** 整表归位：服务端把两侧所有「同剧其他季」的 sort_no 都改成各自目标的季号 */
async function resortAll() {
  resorting.value = true
  try {
    const r = await movieLinkApi.resort(props.movieId)
    message.success(
      r.updated ? `已按季号重排 ${r.updated} 行（对方那一侧一起改）` : '所有「同剧其他季」的排序本来就等于季号，没有改动',
    )
    await load()
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    resorting.value = false
  }
}

/* ---------- 已有关联表 ---------- */
const cols = computed<DataTableColumn<LinkRow>[]>(() => [
  {
    title: '对方影片',
    key: 'title',
    minWidth: 220,
    render: (r) =>
      h('div', { class: 'target' }, [
        r.coverUrl
          ? h(NImage, { src: imgUrl(r.coverUrl), width: 34, height: 50, objectFit: 'cover', class: 'cover' })
          : h('span', { class: 'cover cover-empty' }),
        h('div', { class: 'target-text' }, [
          h('div', {}, r.title),
          h(
            NText,
            { depth: 3, style: 'font-size:12px' },
            { default: () => `${r.seasonNo ? '第 ' + r.seasonNo + ' 季 · ' : ''}${r.year ?? '—'} · ${r.episodeTotal} 集` },
          ),
          r.oneWay
            ? h(
                NTag,
                { size: 'tiny', type: 'warning', bordered: false, title: '库里只有对方那一侧落了这条关联，点「保存」即补齐成对' },
                { default: () => '仅单向记录 · 保存后自动补齐双向' },
              )
            : null,
        ]),
      ]),
  },
  { title: '状态', key: 'status', width: 150, render: (r) => statusTag(r.status as MovieStatus) },
  {
    title: '关系',
    key: 'relationType',
    width: 118,
    render: (r) =>
      h(NSelect, {
        value: r.relationType,
        options: RELATION_OPTIONS,
        size: 'small',
        'onUpdate:value': (v: string) => (r.relationType = v),
      }),
  },
  {
    title: '本页文案（给对方看）',
    key: 'label',
    minWidth: 150,
    render: (r) =>
      h(NInput, {
        value: r.label ?? '',
        size: 'small',
        maxlength: 64,
        placeholder: '留空即用片名',
        'onUpdate:value': (v: string) => (r.label = v),
      }),
  },
  {
    title: '对方页文案（给本片刻）',
    key: 'reverseLabel',
    minWidth: 150,
    render: (r) =>
      h(NInput, {
        value: r.reverseLabel ?? '',
        size: 'small',
        maxlength: 64,
        placeholder: '留空即用片名',
        'onUpdate:value': (v: string) => (r.reverseLabel = v),
      }),
  },
  {
    title: '排序',
    key: 'sortNo',
    minWidth: 148,
    render: (r) =>
      h('div', { class: 'sort-cell' }, [
        h(NInputNumber, {
          value: r.sortNo,
          size: 'small',
          style: 'width:84px',
          'onUpdate:value': (v: number | null) => (r.sortNo = v ?? 0),
        }),
        // 季号就是服务端留空时的默认值；对不上时给一个一键填对的入口（还是要点「保存」才落库）
        r.relationType === 'season' && r.seasonNo != null && r.sortNo !== r.seasonNo
          ? h(
              NButton,
              { size: 'tiny', quaternary: true, type: 'primary', onClick: () => (r.sortNo = r.seasonNo!) },
              { default: () => `季号 ${r.seasonNo}` },
            )
          : null,
      ]),
  },
  {
    title: '操作',
    key: 'ops',
    width: 148,
    render: (r) =>
      h(NSpace, { size: 6 }, {
        default: () => [
          h(
            NButton,
            {
              size: 'tiny',
              type: 'primary',
              secondary: true,
              disabled: !isDirty(r),
              loading: savingRow.value === r.linkId,
              onClick: () => saveRow(r),
            },
            { default: () => (r.oneWay ? '补齐双向' : '保存') },
          ),
          h(
            NPopconfirm,
            { onPositiveClick: () => removeLink(r) },
            {
              trigger: () =>
                h(
                  NButton,
                  { size: 'tiny', quaternary: true, type: 'error', loading: removingRow.value === r.linkId },
                  { default: () => '解除' },
                ),
              default: () => `解除与「${r.title}」的关联（对方那一侧同时解除）`,
            },
          ),
        ],
      }),
  },
])

/* ---------- 待关联暂存表 ---------- */
const pendingCols = computed<DataTableColumn<PendingItem>[]>(() => [
  {
    title: '待关联影片',
    key: 'title',
    minWidth: 210,
    render: (p) =>
      h('div', { class: 'target-text' }, [
        h('div', {}, p.title),
        h(NText, { depth: 3, style: 'font-size:12px' }, { default: () => p.meta }),
      ]),
  },
  {
    title: '本页文案（给对方看）',
    key: 'label',
    minWidth: 150,
    render: (p) =>
      h(NInput, {
        value: p.label,
        size: 'small',
        maxlength: 64,
        placeholder: '如「第二季」，可留空',
        'onUpdate:value': (v: string) => (p.label = v),
      }),
  },
  {
    title: '对方页文案（给本片刻）',
    key: 'reverseLabel',
    minWidth: 150,
    render: (p) =>
      h(NInput, {
        value: p.reverseLabel,
        size: 'small',
        maxlength: 64,
        placeholder: '如「第一季」，可留空',
        'onUpdate:value': (v: string) => (p.reverseLabel = v),
      }),
  },
  {
    title: '排序',
    key: 'sortNo',
    minWidth: 132,
    render: (p) =>
      h(NInputNumber, {
        value: p.sortNo,
        size: 'small',
        placeholder: '自动＝对方季号',
        style: 'width:110px',
        'onUpdate:value': (v: number | null) => (p.sortNo = v),
      }),
  },
  {
    title: '操作',
    key: 'ops',
    width: 74,
    render: (p) =>
      h(
        NButton,
        { size: 'tiny', quaternary: true, type: 'error', onClick: () => dropPending(p) },
        { default: () => '移除' },
      ),
  },
])

onMounted(() => {
  void load()
  // 先兜一批候选：remote 模式下 options 是空的，下拉一打开就是「无数据」，很容易被当成"库里没有"
  void fetchOptions('')
})

onBeforeUnmount(() => {
  if (searchTimer) window.clearTimeout(searchTimer)
})
</script>

<template>
  <div class="link-panel">
    <NText depth="3" class="hint">
      给「{{ props.movieTitle }}」配置关联：搜索后<b>可以一次勾选多部</b>，在下面的「待关联」里逐条调文案与排序，
      然后一次提交。关联是<b>双向</b>的 —— 在这里加一条，对方影片的编辑页会自动出现同一条关联；反过来，
      只有对方那一侧落了行的记录也会在这里列出并标成「仅单向记录」，点「补齐双向」即修正。
      只有「已上架且有剧集」的关联会在 web / Windows 端显示（可见性铁律）；TV 与手机端本期不显示这个区块。
    </NText>

    <div class="add-row">
      <NSelect
        :value="pickedIds"
        :options="options"
        :loading="searching"
        multiple
        filterable
        remote
        clearable
        size="small"
        max-tag-count="responsive"
        placeholder="搜索影视库里的另一部影片，可多选（只打字片名即可，如「金装律师」）"
        style="min-width: 320px; flex: 1"
        @search="onSearch"
        @update:value="onPick"
      />
      <NSelect
        v-model:value="relationType"
        :options="RELATION_OPTIONS"
        size="small"
        style="width: 148px"
        placeholder="关系"
      />
      <NButton
        type="primary"
        size="small"
        :disabled="!pending.length || overLimit"
        :loading="adding"
        @click="submitPending"
      >
        添加 {{ pending.length ? `${pending.length} 条关联` : '关联' }}
      </NButton>
      <NButton v-if="pending.length" size="small" quaternary @click="clearPending">清空待关联</NButton>
    </div>

    <div v-if="pending.length" class="pending-block">
      <NText class="pending-title">待关联 {{ pending.length }} 部（提交后两侧同时生效）</NText>
      <NDataTable
        :columns="pendingCols"
        :data="pending"
        size="small"
        :bordered="false"
        :row-key="(p: PendingItem) => p.targetId"
        class="link-table"
      />
      <NText v-if="overLimit" depth="3" class="hint">
        本片关联上限 {{ MAX_LINKS }} 条：当前已有 {{ rows.length }} 条，待关联 {{ pending.length }} 条，先移除一些再提交。
      </NText>
    </div>

    <div class="table-head">
      <NText depth="3" style="font-size: 12.5px">
        当前关联 {{ rows.length }} 条 · 排序留空即按对方季号（第一季→1、第九季→9）
      </NText>
      <NPopconfirm @positive-click="resortAll">
        <template #trigger>
          <NButton size="tiny" type="primary" secondary :loading="resorting" :disabled="!rows.length">
            按季号重排全部
          </NButton>
        </template>
        把所有「同剧其他季」关联的排序改成对方影片自己的季号，对方那一侧同时改；手动定过的数字会被覆盖，
        「衍生 / 自定义」的行不动。
      </NPopconfirm>
    </div>

    <NDataTable
      :columns="cols"
      :data="rows"
      size="small"
      :bordered="false"
      :loading="loading"
      :row-key="(r: LinkRow) => r.linkId"
      class="link-table"
    />
    <NEmpty v-if="!loading && !rows.length" description="还没有关联的其他季" size="small" class="empty" />

    <NText depth="3" class="hint">
      文案两栏可留空：观众端的卡片直接用那一季的片名，不再额外印「第 X 季」字样（留着的文案是给以后 TV /
      手机端用的备用名）。区块标题取「关系」：这一片的关联全是「同剧其他季」时显示「其他季」，否则显示「关联影片」。
      排序留空 = 按对方那一部的季号落（没有剧集的影片才退回「接在当前最大值后面 +10」），整表一次性归位点上面「按季号重排全部」。
    </NText>
  </div>
</template>

<style scoped>
.link-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.hint {
  font-size: 12.5px;
  line-height: 1.6;
}
.add-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.pending-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 10px 10px;
  border: 1px dashed rgba(24, 158, 91, 0.45);
  border-radius: 8px;
}
.pending-title {
  font-size: 12.5px;
  font-weight: 700;
}
.link-table :deep(.target) {
  display: flex;
  align-items: center;
  gap: 10px;
}
.link-table :deep(.cover) {
  border-radius: 4px;
  overflow: hidden;
}
.link-table :deep(.cover-empty) {
  display: inline-block;
  width: 34px;
  height: 50px;
  background: rgba(128, 128, 128, 0.16);
}
.link-table :deep(.target-text) {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.link-table :deep(.sort-cell) {
  display: flex;
  align-items: center;
  gap: 6px;
}
.table-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}
.empty {
  padding: 6px 0 2px;
}
</style>
