<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  NButton,
  NCard,
  NDataTable,
  NDropdown,
  NImage,
  NInput,
  NPopconfirm,
  NSelect,
  NSpace,
  NTag,
  useDialog,
  useMessage,
} from 'naive-ui'
import type { DataTableColumn } from 'naive-ui'
import { CreateOutline, RefreshOutline, SearchOutline } from '@vicons/ionicons5'
import { movieApi } from '@/api/movie'
import { imgUrl } from '@/utils/image'
import { errMsg, ApiError } from '@/api/http'
import type { MovieListVo, MovieStatus } from '@/api/types'
import { useAppStore } from '@/stores/app'
import TimeText from '@/components/TimeText.vue'
import EmptyState from '@/components/EmptyState.vue'
import { h } from 'vue'

const router = useRouter()
const message = useMessage()
const dialog = useDialog()
const appStore = useAppStore()

const page = ref(1)
const size = ref(20)
const keyword = ref('')
const categoryId = ref<number | null>(null)
const statusTab = ref<'DEFAULT' | 'ALL' | MovieStatus>('DEFAULT')
const list = ref<MovieListVo[]>([])
const total = ref(0)
const loading = ref(false)

const statusParam = computed(() => {
  switch (statusTab.value) {
    case 'ALL':
      return 'PUBLISHED,OFF,DRAFT'
    case 'DEFAULT':
      return undefined // 服务端缺省 = PUBLISHED + OFF
    default:
      return statusTab.value
  }
})

let timer: number | undefined
function onKeyword(v: string) {
  window.clearTimeout(timer)
  timer = window.setTimeout(() => {
    page.value = 1
    load()
  }, 300)
}

onMounted(async () => {
  await appStore.refreshCategories().catch(() => {})
  load()
})
function load() {
  loading.value = true
  movieApi
    .page({
      page: page.value,
      size: size.value,
      keyword: keyword.value.trim() || undefined,
      status: statusParam.value,
      categoryId: categoryId.value ?? undefined,
    })
    .then((r) => {
      list.value = r.list
      total.value = r.total
    })
    .catch((e) => message.error(errMsg(e)))
    .finally(() => (loading.value = false))
}

function onTabChange() {
  page.value = 1
  load()
}

// ==================== 发布 / 下架 / 删除 ====================

async function doPublish(r: MovieListVo) {
  try {
    await movieApi.publish(r.id)
    message.success(`「${r.title}」已上架`)
    load()
  } catch (e) {
    // 400「发布失败：…」直接展示服务端原因
    message.error(e instanceof Error ? e.message : errMsg(e))
  }
}

async function doOffline(r: MovieListVo) {
  try {
    await movieApi.offline(r.id)
    message.success(`「${r.title}」已下架`)
    load()
  } catch (e) {
    message.error(errMsg(e))
  }
}

function askDelete(r: MovieListVo) {
  dialog.warning({
    title: '删除影片',
    content: `将删除「${r.title}」及其 ${r.episodeTotal} 集剧集与相关观看断点，TV 端立即不可见，不可恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await movieApi.remove(r.id)
        message.success(`已删除「${r.title}」`)
        load()
      } catch (e) {
        message.error(errMsg(e))
      }
    },
  })
}

// ==================== 列 ====================

const STATUS_TAGS: Record<MovieStatus, { type: 'success' | 'warning' | 'default'; label: string }> = {
  PUBLISHED: { type: 'success', label: '上架' },
  OFF: { type: 'default', label: '下架' },
  DRAFT: { type: 'warning', label: '草稿' },
}

function statusTag(r: MovieListVo) {
  const s = STATUS_TAGS[r.status] ?? { type: 'default' as const, label: r.status }
  return h(NTag, { size: 'small', type: s.type }, { default: () => s.label })
}

function tagsCell(r: MovieListVo) {
  if (!r.tags.length) return h('span', { class: 'dim' }, '—')
  const shown = r.tags.slice(0, 3)
  const rest = r.tags.length - shown.length
  return h(NSpace, { size: 4 }, {
    default: () => [
      ...shown.map((t) => h(NTag, { size: 'small', bordered: false }, { default: () => t.name })),
      rest > 0 ? h('span', { class: 'dim small' }, `+${rest}`) : null,
    ],
  })
}

async function onMenuKey(key: string, r: MovieListVo) {
  if (key === 'edit') router.push(`/movies/${r.id}/edit`)
  else if (key === 'delete') askDelete(r)
}

const columns: DataTableColumn<MovieListVo>[] = [
  {
    title: '封面',
    key: 'coverUrl',
    width: 66,
    render: (r) =>
      r.coverUrl
        ? h(NImage, { src: imgUrl(r.coverUrl), width: 44, height: 66, objectFit: 'cover', class: 'cover' })
        : h('div', { class: 'no-cover' }, [h(CreateOutline)]),
  },
  {
    title: '片名',
    key: 'title',
    minWidth: 180,
    render: (r) =>
      h('div', { class: 'title-cell' }, [
        h('div', { class: 'title' }, [statusTag(r), ` ${r.title}`]),
        h('div', { class: 'sub' }, [
          r.originalTitle || '',
          r.year ? ` · ${r.year}` : '',
          r.rating != null ? ` · ${r.rating.toFixed(1)}分` : '',
        ]),
      ]),
  },
  { title: '类型', key: 'categoryName', width: 110, render: (r) => r.categoryName || '—' },
  {
    title: '集数',
    key: 'episodeTotal',
    width: 90,
    render: (r) =>
      h(NSpace, { size: 4 }, {
        default: () => [
          h('span', null, String(r.episodeTotal)),
          r.missingCount > 0
            ? h(NTag, { size: 'small', type: 'error' }, { default: () => `缺${r.missingCount}` })
            : null,
        ],
      }),
  },
  {
    title: '来源',
    key: 'source',
    width: 110,
    render: (r) => (r.sourceType === 'MANUAL' || !r.sourceName ? '手动' : r.sourceName),
  },
  { title: '标签', key: 'tags', minWidth: 140, render: tagsCell },
  {
    title: '更新时间',
    key: 'updateTime',
    width: 110,
    render: (r) => h(TimeText, { ms: r.updateTime }),
  },
  {
    title: '操作',
    key: 'ops',
    width: 150,
    fixed: 'right',
    render: (r) =>
      h(NSpace, { size: 4, onClick: (e: Event) => e.stopPropagation() }, {
        default: () => {
          const cells: any[] = []
          if (r.status === 'PUBLISHED') {
            cells.push(
              h(NPopconfirm, {
                onPositiveClick: () => doOffline(r),
                positiveText: '下架',
                negativeText: '取消',
              }, {
                trigger: () => h(NButton, { size: 'small', quaternary: true }, { default: () => '下架' }),
                default: () => `下架「${r.title}」？TV 端立即可见失效。`,
              }),
            )
          } else if (r.status === 'DRAFT' || r.status === 'OFF') {
            cells.push(
              h(NPopconfirm, {
                onPositiveClick: () => doPublish(r),
                positiveText: '上架',
                negativeText: '取消',
              }, {
                trigger: () => h(NButton, { size: 'small', quaternary: true, type: 'success' }, { default: () => '上架' }),
                default: () => `上架「${r.title}」？（校验池/封面/剧集）`,
              }),
            )
          }
          cells.push(
            h(NDropdown, {
              options: [
                { label: '编辑', key: 'edit' },
                { label: '删除', key: 'delete', type: 'error' },
              ],
              onSelect: (key: string) => onMenuKey(key, r),
            }, {
              default: () => h(NButton, { size: 'small', quaternary: true }, { default: () => '⋯' }),
            }),
          )
          return cells
        },
      }),
  },
]

function onRowClick(r: MovieListVo) {
  router.push(`/movies/${r.id}`)
}
</script>

<template>
  <div class="page">
    <div class="hm-page-head">
      <div>
        <div class="hm-page-title">影片列表</div>
        <div class="hm-page-sub">
          管理影片与剧集：改信息、调剧集、刮削封面/横图；点片名进入「影片工作台」编辑，发布后 TV 端立即可见
        </div>
      </div>
    </div>
    <NCard :bordered="false">
    <template #header>
      <div class="card-head">
        <div class="hm-seg">
          <button
            v-for="t in (['DEFAULT', 'PUBLISHED', 'OFF', 'DRAFT', 'ALL'] as const)"
            :key="t"
            :class="{ active: statusTab === t }"
            @click="statusTab = t; onTabChange()"
          >
            {{
              { DEFAULT: '非草稿', PUBLISHED: '已上架', OFF: '已下架', DRAFT: '草稿', ALL: '全部' }[t]
            }}
          </button>
        </div>
        <NSpace>
          <NSelect
            v-model:value="categoryId"
            :options="appStore.categories.map((c) => ({ label: c.name, value: c.id }))"
            placeholder="全部类型"
            clearable
            size="small"
            style="width: 160px"
            @update:value="() => { page = 1; load() }"
          />
          <NInput
            v-model:value="keyword"
            placeholder="搜索片名 / 原名"
            clearable
            style="width: 200px"
            @update:value="(v: string) => onKeyword(v)"
          >
            <template #prefix><SearchOutline /></template>
          </NInput>
          <NButton @click="load">
            <template #icon><RefreshOutline /></template>
          </NButton>
          <NButton type="primary" @click="router.push('/movies/new')">
            <template #icon><CreateOutline /></template>
            新建影片
          </NButton>
        </NSpace>
      </div>
    </template>

    <NDataTable
      v-if="total > 0"
      :columns="columns"
      :data="list"
      :loading="loading"
      :row-key="(r: MovieListVo) => r.id"
      :bordered="false"
      size="medium"
      class="row-click"
      @row-click="onRowClick"
      :pagination="
        {
          page: page,
          pageSize: size,
          itemCount: total,
          showSizePicker: true,
          pageSizes: [20, 50, 100],
          onUpdatePage: (p: number) => (page = p),
          onUpdatePageSize: (s: number) => {
            size = s
            page = 1
          },
        } as any
      "
    />
    <EmptyState v-else-if="!loading" desc="暂无影片">
      <template #action>
        <NButton type="primary" @click="router.push('/movies/new')">新建第一部影片</NButton>
      </template>
    </EmptyState>
    </NCard>
  </div>
</template>

<style scoped>
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 16px;
  flex-wrap: wrap;
}
.cover {
  border-radius: 8px;
  cursor: pointer;
}
.no-cover {
  width: 44px;
  height: 66px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #f6f8fb, #edf0f6);
  color: #b6bfce;
  border-radius: 8px;
  font-size: 20px;
}
.title-cell {
  line-height: 1.4;
}
.title {
  font-weight: 600;
}
.title :deep(.n-tag) {
  margin-right: 6px;
  vertical-align: 1px;
}
.sub {
  color: #999;
  font-size: 12px;
}
.dim {
  color: #999;
}
.small {
  font-size: 12px;
}
:deep(.row-click .n-data-table-tr) {
  cursor: pointer;
}
</style>
