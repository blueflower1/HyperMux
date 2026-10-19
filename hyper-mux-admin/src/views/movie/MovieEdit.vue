<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import {
  NAlert,
  NButton,
  NCard,
  NDescriptions,
  NDescriptionsItem,
  NFormItem,
  NInput,
  NInputNumber,
  NPopconfirm,
  NSelect,
  NSpace,
  NTag,
  NText,
  useDialog,
  useMessage,
} from 'naive-ui'
import { SearchOutline, CloudOutline } from '@vicons/ionicons5'
import { movieApi, type EpisodeBody, type MovieMainBody } from '@/api/movie'
import { openlistApi, openlistDirectUrl, sourceApi } from '@/api/source'
import { videoApi } from '@/api/video'
import { ApiError, errMsg } from '@/api/http'
import type { MetadataDetailVo, MovieImportVo, SourceVo } from '@/api/types'
import { useAppStore } from '@/stores/app'
import CoverUploader from '@/components/CoverUploader.vue'
import ImagePicker from '@/components/ImagePicker.vue'
import { imgUrl } from '@/utils/image'
import EpisodeTable from '@/components/EpisodeTable.vue'
import RatingInput from '@/components/RatingInput.vue'
import TagPicker from '@/components/TagPicker.vue'
import MetadataScrape from '@/components/MetadataScrape.vue'
import OpenListPicker from '@/components/OpenListPicker.vue'
import { makeRow, rowFromVideo, rowToEditBody, type EpRow } from '@/utils/episode'
import { isRemoteUrl } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const dialog = useDialog()
const appStore = useAppStore()

const movieId = computed(() => (route.params.id ? Number(route.params.id) : null))
const isNew = computed(() => movieId.value == null)

// ==================== 表单 ====================

const form = reactive({
  title: '',
  originalTitle: '',
  categoryId: null as number | null,
  year: null as number | null,
  rating: null as number | null,
  area: '',
  language: '',
  intro: '',
  sortNo: 0,
  coverUrl: '',
  backdropUrl: '',
  tagIds: [] as number[],
  sourceId: null as number | null,
})

const rows = ref<EpRow[]>([])
const videoLoading = ref(false)
const saving = ref(false)
const sources = ref<SourceVo[]>([])

const hasOpenlist = computed(() => rows.value.some((r) => r.url.trim() && !isRemoteUrl(r.url)))

// ==================== 从 OpenList 导入 ====================

/** 导入选中的影片目录绝对路径（仅记录来源） */
const importSourcePath = ref<string | null>(null)
const importSourceId = ref<number | null>(null)
/** OpenList 影片目录里解析出的海报（绝对直链），供挑选封面时使用 */
const importPosters = ref<string[]>([])
const pickerVisible = ref(false)
const pickerSessionId = ref<string | null>(null)
const pickerInitialPath = ref('/')
const importing = ref(false)

// ==================== 挑选封面 / 横版封面 ====================

const imagePickerVisible = ref(false)
const imagePickerKind = ref<'poster' | 'backdrop'>('poster')
const pickerCandidates = computed(() =>
  imagePickerKind.value === 'poster' ? importPosters.value : [],
)

function openImagePicker(kind: 'poster' | 'backdrop') {
  imagePickerKind.value = kind
  imagePickerVisible.value = true
}

function onImagePicked(url: string) {
  if (imagePickerKind.value === 'poster') {
    form.coverUrl = url
    message.success('已选择封面；保存后可在影片详情页点「刮削图片」固化到 OpenList 的 images/ 目录')
  } else {
    form.backdropUrl = url
    message.success('已选择横版封面；保存后可在影片详情页点「刮削图片」固化到 OpenList 的 images/ 目录')
  }
}

async function openImportPicker() {
  const sid = importSourceId.value ?? form.sourceId
  if (!sid) {
    message.warning('请先选择数据源')
    return
  }
  try {
    const r = await openlistApi.session({ sourceId: sid })
    pickerSessionId.value = r.sessionId
    const src = sources.value.find((s) => s.id === sid)
    pickerInitialPath.value = src?.rootPath || '/'
    pickerVisible.value = true
  } catch (e) {
    message.error(errMsg(e))
  }
}

function applyImport(r: MovieImportVo) {
  form.sourceId = r.sourceId
  if (r.title) form.title = r.title
  if (r.year) form.year = r.year
  importSourcePath.value = r.path
  importPosters.value = (r.posters ?? []).map((p) => openlistDirectUrl(r.serverUrl, r.rootPath, p))
  if (r.posters?.length) {
    form.coverUrl = openlistDirectUrl(r.serverUrl, r.rootPath, r.posters[0])
  }
  rows.value = r.episodes.map((ep) =>
    makeRow({
      seasonNo: ep.seasonNo,
      episodeNo: ep.episodeNo,
      title: ep.title ?? '',
      url: ep.relPath,
      size: ep.size,
    }),
  )
}

async function onImportPick(path: string) {
  const sid = importSourceId.value ?? form.sourceId
  if (!sid) return
  importing.value = true
  try {
    const r = await openlistApi.importFolder(sid, path)
    applyImport(r)
    const extra = r.skippedDirs > 0 ? `，${r.skippedDirs} 个子目录不可读已跳过` : ''
    message.success(`已从目录解析 ${r.episodes.length} 集${r.parsed ? '' : '（片名未含年份，请手动补充）'}${extra}`)
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    importing.value = false
  }
}

// ==================== 刮削元数据 ====================

const metaVisible = ref(false)

function openMeta() {
  if (!form.title.trim()) {
    message.warning('请先填写片名或先「从 OpenList 导入」')
    return
  }
  metaVisible.value = true
}

function onMetaApply(d: MetadataDetailVo) {
  if (d.title) form.title = d.title
  if (d.originalTitle) form.originalTitle = d.originalTitle
  if (d.year) form.year = d.year
  if (d.rating != null) form.rating = Math.round(d.rating * 10) / 10
  if (d.intro) form.intro = d.intro
  if (d.posterUrl) form.coverUrl = d.posterUrl
  if (d.backdropUrl) form.backdropUrl = d.backdropUrl
  if (d.area) form.area = d.area
  const ids = new Set(form.tagIds)
  const typeTags = appStore.tags.filter((t) => t.type === 0 && t.status === 1)
  for (const g of d.genres ?? []) {
    const hit = typeTags.find((t) => t.name === g || t.name.includes(g) || g.includes(t.name))
    if (hit) ids.add(hit.id)
  }
  if (d.area) {
    const hit = appStore.regionTags.find((t) => t.name === d.area)
    if (hit) ids.add(hit.id)
  }
  form.tagIds = [...ids]
  message.success('已回填元数据，可继续微调')
}

function snapMain(): MovieMainBody {
  return {
    title: form.title.trim(),
    originalTitle: form.originalTitle.trim() || null,
    categoryId: form.categoryId as number,
    year: form.year,
    coverUrl: form.coverUrl.trim() || null,
    backdropUrl: form.backdropUrl.trim() || null,
    rating: form.rating,
    area: form.area || null,
    language: form.language.trim() || null,
    intro: form.intro.trim() || null,
    sortNo: form.sortNo ?? 0,
    sourceId: hasOpenlist.value ? form.sourceId : null,
    sourcePath: importSourcePath.value,
    tagIds: [...form.tagIds],
  }
}

// ==================== 脏检查 ====================

const baseline = ref<{ main: string; eps: string } | null>(null)

function snapshot() {
  baseline.value = { main: JSON.stringify(snapMain()), eps: JSON.stringify(rows.value) }
}

const dirty = computed(() => {
  if (videoLoading.value || !baseline.value) return false
  return JSON.stringify(snapMain()) !== baseline.value.main || JSON.stringify(rows.value) !== baseline.value.eps
})

// ==================== 校验 ====================

const saveIssue = computed<string | null>(() => {
  if (!form.title.trim()) return '请填写片名'
  if (!form.categoryId) return '请选择影视类型'
  if (isNew.value && !form.coverUrl.trim()) return '新建影片需封面（创建即上架，封面必填）'
  if (!rows.value.length) return '请至少添加一集'
  const seen = new Set<string>()
  for (const r of rows.value) {
    if (!r.url.trim()) return '存在未填直链的剧集'
    if (r.seasonNo == null || r.episodeNo == null) return '存在未填季/集号的剧集'
    const k = `${r.seasonNo}|${r.episodeNo}`
    if (seen.has(k)) return `剧集 (S${r.seasonNo}E${r.episodeNo}) 重复`
    seen.add(k)
  }
  if (hasOpenlist.value && !form.sourceId) return '存在相对路径剧集，请选择 OpenList 源'
  return null
})

// ==================== 加载 ====================

async function load() {
  await appStore.ensureAll(true).catch(() => {})
  sourceApi
    .list()
    .then((r) => (sources.value = r.list))
    .catch(() => {})

  if (isNew.value) {
    rows.value = [makeRow()]
    snapshot()
    return
  }
  videoLoading.value = true
  try {
    const [d, v] = await Promise.all([
      movieApi.detail(movieId.value as number),
      videoApi.list(movieId.value as number),
    ])
    form.title = d.title
    form.originalTitle = d.originalTitle ?? ''
    form.categoryId = d.categoryId
    form.year = d.year
    form.rating = d.rating
    form.area = d.area ?? ''
    form.language = d.language ?? ''
    form.intro = d.intro ?? ''
    form.sortNo = d.sortNo ?? 0
    form.coverUrl = d.coverUrl ?? ''
    form.backdropUrl = d.backdropUrl ?? ''
    form.tagIds = (d.tags ?? []).map((t) => t.id)
    form.sourceId = d.sourceId ?? null
    status.value = (d.status as 'PUBLISHED' | 'DRAFT' | 'OFF') ?? 'DRAFT'
    rows.value = v.list.map(rowFromVideo)
    snapshot()
  } catch (e) {
    message.error(errMsg(e))
    router.replace('/movies')
  } finally {
    videoLoading.value = false
  }
}

function onBeforeUnload(e: BeforeUnloadEvent) {
  if (dirty.value) {
    e.preventDefault()
    e.returnValue = ''
  }
}

onMounted(() => {
  window.addEventListener('beforeunload', onBeforeUnload)
  load()
})
onBeforeUnmount(() => window.removeEventListener('beforeunload', onBeforeUnload))

onBeforeRouteLeave((_to, _from, next) => {
  if (!dirty.value) return next()
  dialog.warning({
    title: '有未保存的修改',
    content: '离开本页将丢失未保存的修改，确定离开吗？',
    positiveText: '离开',
    negativeText: '留下',
    onPositiveClick: () => next(),
    onNegativeClick: () => next(false),
  })
})

// ==================== 保存 ====================

function rowsToEpisodes(): EpisodeBody[] {
  return rows.value.map((r) => ({
    seasonNo: r.seasonNo ?? 1,
    episodeNo: r.episodeNo ?? 1,
    title: r.title.trim() || null,
    url: r.url.trim(),
    duration: r.duration,
  }))
}

async function save() {
  const issue = saveIssue.value
  if (issue) {
    message.warning(issue)
    return
  }
  saving.value = true
  try {
    if (isNew.value) {
      const res = await movieApi.create({ ...snapMain(), episodes: rowsToEpisodes() })
      message.success(`已创建并发布（共 ${res.episodeTotal} 集）`)
      router.replace(`/movies/${res.id}`)
      return
    }
    const main = snapMain()
    const mainChanged = baseline.value ? JSON.stringify(main) !== baseline.value.main : true
    const epChanged = baseline.value ? JSON.stringify(rows.value) !== baseline.value.eps : true
    if (mainChanged) {
      await movieApi.update(movieId.value as number, main)
    }
    if (epChanged) {
      const r = await videoApi.replace(
        movieId.value as number,
        rows.value.map(rowToEditBody).filter((b): b is NonNullable<typeof b> => b != null),
      )
      message.success(`已保存：新增 ${r.added} · 更新 ${r.updated} · 移除 ${r.removed}（可播放 ${r.total} 集）`)
    } else if (mainChanged) {
      message.success('已保存')
    } else {
      message.info('没有需要保存的变化')
    }
    // 合并成单页后不再跳转，就地重新拉取并重置基线
    await load()
  } catch (e) {
    if (e instanceof ApiError && e.code === 409) {
      dialog.error({ title: '保存失败', content: errMsg(e), positiveText: '知道了' })
    } else {
      message.error(errMsg(e))
    }
  } finally {
    saving.value = false
  }
}

// ==================== 头部预览 / 状态操作（原「影片详情」合并进来）====================

const status = ref<'PUBLISHED' | 'DRAFT' | 'OFF'>('DRAFT')

const heroStatusText = computed(() =>
  status.value === 'PUBLISHED' ? '已上架' : status.value === 'DRAFT' ? '草稿' : '已下架',
)
const heroStatusType = computed(() =>
  status.value === 'PUBLISHED' ? 'success' : status.value === 'DRAFT' ? 'warning' : 'default',
)
const heroSub = computed(
  () =>
    [form.originalTitle, form.year, form.area, form.language].filter(Boolean).join(' · ') ||
    '原名 / 年份 / 地区 / 语言（未填）',
)
const heroCategory = computed(
  () => appStore.categories.find((c) => c.id === form.categoryId)?.name ?? '未选择',
)
const heroSourceName = computed(() =>
  form.sourceId ? (sources.value.find((s) => s.id === form.sourceId)?.name ?? `源 #${form.sourceId}`) : '手动',
)
const heroTagNames = computed(() =>
  appStore.tags.filter((t) => form.tagIds.includes(t.id)).map((t) => t.name),
)

const scrapingImages = ref(false)

/** 刮削海报 + 横图（把当前封面/横图固化到 OpenList 的 images/，换成自有直链） */
async function doScrapeImages() {
  if (isNew.value) {
    message.warning('请先保存影片，再刮削图片')
    return
  }
  scrapingImages.value = true
  try {
    const r = await movieApi.scrapeImages(movieId.value as number, true)
    const tip = r.message ? `（${r.message}）` : ''
    message.success(`刮削完成：${r.matchedTitle ?? form.title}${tip}`)
    await load()
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    scrapingImages.value = false
  }
}

async function doPublish() {
  try {
    await movieApi.publish(movieId.value as number)
    message.success('已上架')
    await load()
  } catch (e) {
    message.error(errMsg(e))
  }
}

async function doOffline() {
  try {
    await movieApi.offline(movieId.value as number)
    message.success('已下架')
    await load()
  } catch (e) {
    message.error(errMsg(e))
  }
}

function askDelete() {
  dialog.warning({
    title: '删除影片',
    content: `将删除「${form.title}」及其 ${rows.value.length} 集剧集与相关观看断点，TV 端立即不可见，不可恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await movieApi.remove(movieId.value as number)
        message.success(`已删除「${form.title}」`)
        router.replace('/movies')
      } catch (e) {
        message.error(errMsg(e))
      }
    },
  })
}

/** 放弃未保存修改（重新拉服务端数据） */
async function resetForm() {
  if (!dirty.value) {
    message.info('当前没有未保存的修改')
    return
  }
  await load()
  message.success('已还原为服务端最新数据')
}

/** 头部预览点击 → 滚动到对应表单项 */
function scrollToField(fieldId: string) {
  document.getElementById(fieldId)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

/** 单文件影片（电影）：剧集区按「正片」呈现 */
const isSingleMovie = computed(() => rows.value.length === 1)

// ==================== 下拉数据 ====================

const categoryOptions = computed(() => {
  const opts = appStore.enabledCategories.map((c) => ({ label: c.name, value: c.id }))
  if (form.categoryId != null && !opts.some((o) => o.value === form.categoryId)) {
    opts.unshift({ label: `#${form.categoryId}（已停用）`, value: form.categoryId })
  }
  return opts
})

const sourceOptions = computed(() =>
  sources.value.map((s) => ({
    label: s.status === 1 ? s.name : `${s.name}（已停用）`,
    value: s.id,
  })),
)

const regionOptions = computed(() =>
  appStore.regionTags.map((t) => ({ label: t.name, value: t.name })),
)

function goBack() {
  router.replace('/movies')
}
</script>

<template>
  <NSpin :show="videoLoading">
    <div class="page">
      <NButton quaternary class="back" @click="goBack">← 返回影片列表</NButton>

      <!-- 头部预览：竖版海报(2:3) + 横版封面(16:9) + 信息，图片均完整显示不裁切；点图跳到对应表单项 -->
      <section class="hero">
        <div class="hero-media">
          <figure class="shot shot-poster" title="点击跳到「封面」设置" @click="scrollToField('field-cover')">
            <div class="shot-box portrait">
              <img v-if="form.coverUrl" :src="imgUrl(form.coverUrl)" alt="竖版封面" />
              <div v-else class="shot-empty">无封面</div>
            </div>
            <figcaption>竖版封面 · 2:3</figcaption>
          </figure>
          <figure class="shot shot-wide" title="点击跳到「横版封面」设置" @click="scrollToField('field-backdrop')">
            <div class="shot-box landscape">
              <img v-if="form.backdropUrl" :src="imgUrl(form.backdropUrl)" alt="横版封面" />
              <div v-else class="shot-empty lite">无横版封面<br /><span>首页将按竖版封面展示</span></div>
            </div>
            <figcaption>横版封面 · 16:9</figcaption>
          </figure>
        </div>

        <div class="hero-info">
          <div class="hero-title-row">
            <h1 class="title">{{ form.title || '未命名影片' }}</h1>
            <NTag v-if="isNew" size="small" type="warning" :bordered="false">未保存</NTag>
            <NTag v-else size="small" :type="heroStatusType" :bordered="false">{{ heroStatusText }}</NTag>
          </div>
          <div class="sub">{{ heroSub }}</div>
          <div class="chips">
            <span v-if="form.rating != null" class="chip score">★ {{ form.rating.toFixed(1) }}</span>
            <span class="chip">类型 · {{ heroCategory }}</span>
            <span class="chip">集数 · {{ rows.length }}</span>
            <span class="chip" :class="{ warn: !form.coverUrl }">{{ form.coverUrl ? '有封面' : '缺封面' }}</span>
            <span class="chip" :class="{ warn: !form.backdropUrl }">
              {{ form.backdropUrl ? '有横版封面' : '无横版封面' }}
            </span>
            <span class="chip">来源 · {{ heroSourceName }}</span>
          </div>
          <div v-if="heroTagNames.length" class="tags">
            <NTag v-for="n in heroTagNames" :key="n" size="small" :bordered="false" type="info">{{ n }}</NTag>
          </div>
          <NSpace class="hero-actions" :size="10">
            <NButton :disabled="isNew" :loading="scrapingImages" @click="doScrapeImages">刮削图片</NButton>
            <NButton @click="openMeta">刮削元数据</NButton>
            <template v-if="!isNew">
              <NPopconfirm
                v-if="status === 'PUBLISHED'"
                positive-text="下架"
                negative-text="取消"
                @positive-click="doOffline"
              >
                <template #trigger>
                  <NButton>下架</NButton>
                </template>
                下架后 TV 端立即失效，确定下架？
              </NPopconfirm>
              <NPopconfirm v-else positive-text="上架" negative-text="取消" @positive-click="doPublish">
                <template #trigger>
                  <NButton type="success">上架</NButton>
                </template>
                上架将校验 类型/封面/可用剧集，确定上架？
              </NPopconfirm>
              <NButton type="error" secondary @click="askDelete">删除</NButton>
            </template>
          </NSpace>
        </div>
      </section>

      <!-- 从 OpenList 导入（仅新建） -->
      <NCard v-if="isNew" size="small" :bordered="false" class="hm-card import-card">
        <template #header>
          <span class="sec-title"><span class="bar"></span>从 OpenList 导入</span>
        </template>
        <div class="import-row">
          <NSelect
            v-model:value="importSourceId"
            :options="sourceOptions"
            placeholder="选择数据源"
            filterable
            style="width: 240px"
          />
          <NButton type="primary" :loading="importing" :disabled="!sources.length" @click="openImportPicker">
            <template #icon><CloudOutline /></template>
            选择目录并自动填充
          </NButton>
          <NText depth="3" style="font-size: 12px">
            进入影片目录（含其下季/清晰度子目录）后选择，将自动解析片名、年份与全部剧集
          </NText>
        </div>
        <NAlert v-if="importSourcePath" type="success" :bordered="false" class="import-done">
          已导入：<span class="mono">{{ importSourcePath }}</span>（共 {{ rows.length }} 集，相对路径已按所选源组装直链）
        </NAlert>
      </NCard>

      <NCard size="small" :bordered="false" class="hm-card" style="margin-bottom: 14px">
        <template #header>
          <span class="sec-title"><span class="bar"></span>基础信息</span>
        </template>
        <div class="grid">
          <NFormItem label="片名" required>
            <div class="title-row">
              <NInput v-model:value="form.title" placeholder="TV 端展示的片名" maxlength="191" />
              <NButton size="small" secondary @click="openMeta">
                <template #icon><SearchOutline /></template>
                刮削元数据
              </NButton>
            </div>
          </NFormItem>
          <NFormItem label="原名">
            <NInput v-model:value="form.originalTitle" placeholder="可选，如 Oppenheimer" maxlength="191" />
          </NFormItem>
          <NFormItem label="所属类型" required>
            <NSelect
              v-model:value="form.categoryId"
              :options="categoryOptions"
              placeholder="选择影视类型（电视剧 / 电影 / 综艺…）"
              filterable
            />
          </NFormItem>
          <NFormItem label="年份">
            <NInputNumber
              v-model:value="form.year"
              :min="1900"
              :max="2100"
              placeholder="如 2023"
              style="width: 120px"
            />
          </NFormItem>
          <NFormItem label="评分">
            <RatingInput v-model="form.rating" />
          </NFormItem>
          <NFormItem label="地区">
            <NSelect
              v-model:value="form.area"
              :options="regionOptions"
              placeholder="复用地区标签"
              clearable
              filterable
            />
          </NFormItem>
          <NFormItem label="语言">
            <NInput v-model:value="form.language" placeholder="如 中 / English" maxlength="32" />
          </NFormItem>
          <NFormItem label="排序">
            <NInputNumber v-model:value="form.sortNo" :min="0" style="width: 120px" />
          </NFormItem>
          <NFormItem label="标签" class="span2">
            <TagPicker v-model="form.tagIds" />
          </NFormItem>
          <NFormItem id="field-cover" :label="isNew ? '封面（必填）· 竖版 2:3' : '封面 · 竖版 2:3'" class="span2">
            <div class="img-field">
              <CoverUploader v-model="form.coverUrl" />
              <NSpace :size="8" style="margin-top: 8px">
                <NButton size="small" @click="openImagePicker('poster')">选择封面（豆瓣 / TMDB / 目录海报）</NButton>
              </NSpace>
            </div>
          </NFormItem>
          <NFormItem id="field-backdrop" label="横版封面 · 16:9（首页信息流）" class="span2">
            <div class="bd-field">
              <div class="bd-left">
                <NInput
                  v-model:value="form.backdropUrl"
                  placeholder="留空则首页按竖版封面展示"
                  clearable
                />
                <NSpace :size="8" style="margin-top: 8px">
                  <NButton size="small" @click="openImagePicker('backdrop')">选择横图</NButton>
                  <NButton size="small" quaternary :disabled="!form.coverUrl" @click="form.backdropUrl = form.coverUrl">
                    用竖版封面代替
                  </NButton>
                  <NButton v-if="form.backdropUrl" size="small" quaternary @click="form.backdropUrl = ''">清空</NButton>
                </NSpace>
              </div>
              <div class="bd-right">
                <img v-if="form.backdropUrl" :src="imgUrl(form.backdropUrl)" class="bd-img" alt="横版封面" />
                <div v-else class="bd-empty">无横图<br />（App 首页将显示竖版封面）</div>
              </div>
            </div>
          </NFormItem>
          <NFormItem label="摘要" class="span2">
            <NInput
              v-model:value="form.intro"
              type="textarea"
              :autosize="{ minRows: 3, maxRows: 8 }"
              placeholder="剧情简介，TV 端详情展示"
              maxlength="10000"
              show-count
            />
          </NFormItem>
        </div>
      </NCard>

      <NCard size="small" :bordered="false" class="hm-card">
        <template #header>
          <div class="ep-head">
            <span>
              <span class="bar"></span>{{ isSingleMovie ? '正片' : `剧集（${rows.length}）` }}
            </span>
            <NText depth="3" style="font-size: 12px">
              {{ isSingleMovie ? '单文件影片（电影）：只需一行；再加一行即变为多集剧集' : '保存 = 全量替换；(季,集) 重复行标红并禁止保存' }}
            </NText>
          </div>
        </template>
        <NSpace vertical :size="12">
          <NAlert v-if="hasOpenlist" type="info" :bordered="false">
            <template #header>部分剧集为 OpenList 相对路径</template>
            相对路径将按所选源组装公网播放直链（serverUrl + /d/ 逐段编码）。
            <NSelect
              v-model:value="form.sourceId"
              :options="sourceOptions"
              size="small"
              style="width: 260px; margin-top: 8px"
              placeholder="选择 OpenList 源（必选）"
            />
          </NAlert>
          <EpisodeTable v-model:rows="rows" mode="edit" :single="isSingleMovie" />
        </NSpace>
      </NCard>

      <!-- 关联数据（已保存的影片才展示） -->
      <section v-if="!isNew" class="card">
        <div class="card-head">
          <span class="bar"></span>
          <span class="card-title">关联数据</span>
        </div>
        <NDescriptions label-placement="left" :column="2" bordered size="small" class="meta-desc">
          <NDescriptionsItem label="数据源">{{ heroSourceName }}</NDescriptionsItem>
          <NDescriptionsItem label="影片 ID">{{ movieId }}</NDescriptionsItem>
          <NDescriptionsItem label="导入目录">{{ importSourcePath ?? '—' }}</NDescriptionsItem>
          <NDescriptionsItem label="排序">{{ form.sortNo ?? 0 }}</NDescriptionsItem>
        </NDescriptions>
      </section>

      <div class="bottom-bar">
        <NText v-if="dirty" type="warning" style="font-size: 13px">● 有未保存的修改</NText>
        <NText v-else depth="3" style="font-size: 13px">
          {{ isNew ? '填写后点「保存并发布」' : '所有修改都在本页完成' }}
        </NText>
        <NSpace>
          <NButton :disabled="!dirty" @click="resetForm">重置</NButton>
          <NButton @click="goBack">返回列表</NButton>
          <NButton type="primary" :loading="saving" @click="save">
            {{ isNew ? '保存并发布' : '保存' }}
          </NButton>
        </NSpace>
      </div>

      <MetadataScrape
        v-model:show="metaVisible"
        :keyword="form.title"
        :year="form.year"
        @apply="onMetaApply"
      />
      <ImagePicker
        v-model:show="imagePickerVisible"
        :kind="imagePickerKind"
        :keyword="form.title"
        :year="form.year"
        :candidates="pickerCandidates"
        @select="onImagePicked"
      />
      <OpenListPicker
        v-if="pickerSessionId"
        v-model:show="pickerVisible"
        :session-id="pickerSessionId"
        :initial-path="pickerInitialPath"
        title="选择影片目录"
        confirm-text="选择此目录"
        @select="onImportPick"
      />
    </div>
  </NSpin>
</template>

<style scoped>
.page {
  max-width: none; width: 100%;
  padding-bottom: 84px;
}
.back {
  margin-bottom: 10px;
}

/* ==================== 头部预览 ==================== */
.hero {
  display: flex;
  gap: 22px;
  align-items: flex-start;
  background:
    radial-gradient(700px 240px at 6% -45%, rgba(245, 130, 13, 0.08), transparent 62%),
    #fff;
  border: 1px solid var(--hm-line, #eef0f4);
  border-radius: 16px;
  padding: 18px 20px;
  margin-bottom: 16px;
  box-shadow: 0 12px 32px -28px rgba(23, 32, 51, 0.4);
}
.hero-media {
  flex: none;
  display: flex;
  gap: 14px;
  align-items: flex-start;
}
.shot {
  margin: 0;
  cursor: pointer;
}
.shot-box {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid var(--hm-line, #e8ecf2);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
/* 竖版：2:3 框 + contain → 任何比例都完整可见，绝不截断 */
.shot-box.portrait {
  width: 156px;
  height: 234px;
  background: #f4f6f9;
}
/* 横版：严格 16:9 框 + contain */
.shot-box.landscape {
  width: 312px;
  height: 176px;
  background: #14161c;
}
.shot-box img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
}
.shot:hover .shot-box {
  border-color: var(--hm-accent, #2080f0);
  box-shadow: 0 0 0 3px rgba(32, 128, 240, 0.12);
}
.shot-empty {
  color: #98a1b0;
  font-size: 12.5px;
  text-align: center;
  line-height: 1.8;
}
.shot-empty.lite {
  color: #6f7a8b;
}
.shot-empty.lite span {
  color: #8b95a5;
  font-size: 11.5px;
}
.shot figcaption {
  margin-top: 7px;
  text-align: center;
  color: #8b95a5;
  font-size: 12px;
}

.hero-info {
  flex: 1;
  min-width: 0;
}
.hero-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.hero .title {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  line-height: 1.25;
  letter-spacing: 0.2px;
}
.hero .sub {
  margin-top: 6px;
  color: #7a8494;
  font-size: 13px;
}
.hero .chips {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.hero .chip {
  display: inline-flex;
  align-items: center;
  height: 27px;
  padding: 0 11px;
  border-radius: 14px;
  background: #f5f7fa;
  color: #4a5568;
  font-size: 12.5px;
  white-space: nowrap;
}
.hero .chip.score {
  background: linear-gradient(120deg, #fff5e6, #ffeccc);
  color: #b96b00;
  font-weight: 600;
}
.hero .chip.warn {
  background: #fff1f0;
  color: #cf1322;
}
.hero .tags {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.hero-actions {
  margin-top: 16px;
}

/* ==================== 卡片（关联数据等） ==================== */
.card {
  background: #fff;
  border: 1px solid var(--hm-line, #eef0f4);
  border-radius: 16px;
  margin-bottom: 16px;
  overflow: hidden;
  box-shadow: 0 12px 32px -30px rgba(23, 32, 51, 0.35);
}
.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--hm-line, #f0f2f5);
  background: linear-gradient(180deg, #fcfdff, #fff);
}
.card-head .bar {
  width: 4px;
  height: 16px;
  border-radius: 2px;
  background: linear-gradient(180deg, #f5820d, #ffb648);
  flex: none;
}
.card-title {
  font-size: 15px;
  font-weight: 700;
}
.meta-desc {
  padding: 14px 18px 18px;
}
.meta-desc :deep(.n-descriptions-table-content) {
  color: #303743;
}

/* ==================== 表单里的封面区 ==================== */
.img-field {
  width: 100%;
}
.bd-field {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  width: 100%;
}
.bd-left {
  flex: 1;
  min-width: 0;
}
.bd-right {
  flex: none;
  width: 288px;
}
/* 16:9 框 + contain：完整显示，不裁切 */
.bd-img {
  width: 288px;
  height: 162px;
  object-fit: contain;
  border-radius: 10px;
  border: 1px solid var(--hm-line, #e5e6eb);
  display: block;
  background: #14161c;
}
.bd-empty {
  width: 288px;
  height: 162px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  background: #f5f6f8;
  color: #98a1b0;
  border: 1px dashed #d9dee6;
  border-radius: 10px;
  font-size: 12px;
  line-height: 1.8;
}

/* ==================== 全局细节 ==================== */
.sec-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
}
.sec-title .bar,
.ep-head .bar {
  width: 4px;
  height: 16px;
  border-radius: 99px;
  background: linear-gradient(180deg, #f5820d, #ffb648);
  display: inline-block;
}
/* 让 naive-ui 卡片与自定义卡片视觉一致 */
:deep(.hm-card) {
  border-radius: 16px !important;
  border: 1px solid var(--hm-line, #eef0f4) !important;
  box-shadow: 0 12px 32px -30px rgba(23, 32, 51, 0.35);
  margin-bottom: 16px;
}
:deep(.hm-card .n-card-header) {
  padding: 14px 18px 2px;
}
:deep(.hm-card .n-card__content) {
  padding: 12px 18px 18px;
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 26px;
}
.span2 {
  grid-column: span 2;
}
.ep-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.bottom-bar {
  position: sticky;
  bottom: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: rgba(255, 255, 255, 0.94);
  backdrop-filter: blur(10px);
  border: 1px solid var(--hm-line, #e8ecf2);
  border-radius: 16px;
  box-shadow: 0 14px 34px -16px rgba(23, 32, 51, 0.28);
  padding: 12px 18px;
  margin-top: 18px;
  z-index: 10;
}
.import-card {
  margin-bottom: 16px;
}
.import-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.import-done {
  margin-top: 12px;
}
.title-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.title-row :deep(.n-input) {
  flex: 1;
  min-width: 0;
}
.mono {
  font-family: ui-monospace, Consolas, Menlo, monospace;
}
</style>
