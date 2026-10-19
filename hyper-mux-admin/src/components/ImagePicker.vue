<script setup lang="ts">
/**
 * 图片挑选弹窗：为影片挑选「竖版封面」或「横版封面」。
 * 候选来源：
 *  ① 元数据（豆瓣/TMDB）：先搜索 → 点某个结果 → 加载该条目的**全部图片**
 *     （豆瓣相册的剧照/海报 + 预告片封面；TMDB 的 posters/backdrops），可多张挑选
 *  ② 该影片 OpenList 目录里的海报文件
 *  ③ 手动粘贴 URL
 * 所有远程图都经本服务 /img 代理显示（豆瓣图床直连会 403）。
 * 图片一律用原生 <img> + CSS object-fit 渲染（缩略图必须等比裁切，避免被放大裁切）。
 */
import { reactive, ref, watch } from 'vue'
import {
  NAlert,
  NButton,
  NEmpty,
  NInput,
  NInputNumber,
  NModal,
  NRadioButton,
  NRadioGroup,
  NSpace,
  NSpin,
  NTag,
  NText,
  useMessage,
} from 'naive-ui'
import { metadataApi } from '@/api/metadata'
import { settingsApi } from '@/api/settings'
import { errMsg } from '@/api/http'
import { imgUrl } from '@/utils/image'
import type { ImageCandidateVo, MetadataItemVo } from '@/api/types'

const props = withDefaults(
  defineProps<{
    show: boolean
    /** poster = 竖版封面；backdrop = 横版封面 */
    kind?: 'poster' | 'backdrop'
    keyword?: string
    year?: number | null
    /** 直接可用的候选 URL（如 OpenList 影片目录里的海报） */
    candidates?: string[]
  }>(),
  { kind: 'poster', keyword: '', year: null, candidates: () => [] },
)
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'select', url: string): void
}>()

const message = useMessage()

const provider = ref<'tmdb' | 'douban'>('douban')
const keyword = ref('')
const year = ref<number | null>(null)
const results = ref<MetadataItemVo[]>([])
const searching = ref(false)

const active = ref<MetadataItemVo | null>(null)
const posters = ref<ImageCandidateVo[]>([])
const backdrops = ref<ImageCandidateVo[]>([])
const loadingImages = ref(false)

const manualUrl = ref('')
/** 加载失败的图片 URL（显示占位，不再显示破图） */
const failed = reactive<Record<string, boolean>>({})

const kindLabel = () => (props.kind === 'backdrop' ? '横版封面' : '封面')
const primary = () => (props.kind === 'backdrop' ? backdrops.value : posters.value)
const secondary = () => (props.kind === 'backdrop' ? posters.value : [])
const selected = ref('')

async function initProvider() {
  try {
    const s = await settingsApi.get()
    provider.value = (s.metadataProvider as 'tmdb' | 'douban') || 'douban'
  } catch {
    provider.value = 'douban'
  }
}

watch(
  () => props.show,
  (v) => {
    if (!v) return
    keyword.value = props.keyword || ''
    year.value = props.year ?? null
    results.value = []
    active.value = null
    posters.value = []
    backdrops.value = []
    manualUrl.value = ''
    selected.value = ''
    Object.keys(failed).forEach((k) => delete failed[k])
    initProvider()
    if (keyword.value.trim()) doSearch()
  },
  { immediate: true },
)

async function doSearch() {
  const kw = keyword.value.trim()
  if (!kw) {
    message.warning('请先填写片名或搜索关键词')
    return
  }
  searching.value = true
  active.value = null
  posters.value = []
  backdrops.value = []
  try {
    const list = await metadataApi.search(kw, year.value, provider.value)
    // 有海报的排前面，方便直接点
    results.value = [...list].sort((a, b) => (b.posterUrl ? 1 : 0) - (a.posterUrl ? 1 : 0))
    if (!results.value.length) message.info('没搜到结果，换个关键词或切换来源试试')
    else if (results.value.length === 1) await loadImages(results.value[0])
  } catch (e) {
    results.value = []
    message.error(errMsg(e))
  } finally {
    searching.value = false
  }
}

async function loadImages(item: MetadataItemVo) {
  active.value = item
  loadingImages.value = true
  posters.value = []
  backdrops.value = []
  try {
    const r = await metadataApi.images(item.providerId, item.mediaType, provider.value)
    posters.value = r.posters ?? []
    // 横图按分辨率从高到低（豆瓣预告片封面只有 720×405，剧照能到 1600×900+）
    backdrops.value = [...(r.backdrops ?? [])].sort(
      (a, b) => (b.width ?? 0) * (b.height ?? 0) - (a.width ?? 0) * (a.height ?? 0),
    )
    const n = posters.value.length + backdrops.value.length
    if (!n) message.warning(`「${item.title}」没有可用图片，换一个结果或手动填地址`)
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    loadingImages.value = false
  }
}

function pick(url: string) {
  selected.value = url
  emit('select', url)
  emit('update:show', false)
}

function pickManual() {
  const u = manualUrl.value.trim()
  if (!u || !/^https?:\/\//i.test(u)) {
    message.warning('请填写 http(s):// 开头的图片地址')
    return
  }
  pick(u)
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    :title="`选择${kindLabel()}`"
    style="width: 980px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <NSpace vertical :size="12">
      <NSpace align="center" :size="10" :wrap="false">
        <NRadioGroup v-model:value="provider" size="small" @update:value="doSearch">
          <NRadioButton value="douban">豆瓣</NRadioButton>
          <NRadioButton value="tmdb">TMDB</NRadioButton>
        </NRadioGroup>
        <NInput v-model:value="keyword" placeholder="片名" style="max-width: 220px" @keyup.enter="doSearch" />
        <NInputNumber v-model:value="year" placeholder="年份" style="width: 120px" :show-button="false" />
        <NButton type="primary" size="small" :loading="searching" @click="doSearch">搜索</NButton>
        <NText depth="3" style="font-size: 12px">① 搜索 → ② 点结果 → ③ 点图片</NText>
      </NSpace>

      <!-- 搜索结果（点一个去加载它的全部图片） -->
      <NSpin :show="searching">
        <NEmpty v-if="!results.length" size="small" description="输入片名后点「搜索」" style="padding: 16px 0" />
        <div v-else class="results">
          <div
            v-for="it in results"
            :key="it.providerId"
            class="r-chip"
            :class="{ on: active?.providerId === it.providerId }"
            @click="loadImages(it)"
          >
            <img
              v-if="it.posterUrl && !failed[it.posterUrl]"
              class="r-thumb"
              :src="imgUrl(it.posterUrl)"
              alt=""
              loading="lazy"
              @error="failed[it.posterUrl] = true"
            />
            <div v-else class="r-thumb none">无图</div>
            <div class="r-meta">
              <span class="r-title">{{ it.title }}</span>
              <NTag size="tiny" :bordered="false">{{ it.year ?? '—' }}</NTag>
            </div>
          </div>
        </div>
      </NSpin>

      <!-- 选中条目的候选图片 -->
      <template v-if="active">
        <NSpace align="center" :size="8">
          <NText strong>{{ active.title }}</NText>
          <NText depth="3" style="font-size: 12px">
            {{ kind === 'backdrop' ? '横版候选' : '竖版候选' }}：{{ primary().length }} 张
            <template v-if="secondary().length">，另有竖版 {{ secondary().length }} 张（作横图会被裁切）</template>
          </NText>
          <NButton text size="tiny" @click="loadImages(active)">刷新</NButton>
        </NSpace>

        <NSpin :show="loadingImages">
          <NEmpty
            v-if="!primary().length && !secondary().length && !loadingImages"
            size="small"
            description="该条目暂无图片"
          />
          <div class="grid" :class="{ wide: kind === 'backdrop' }">
            <div
              v-for="(c, i) in primary()"
              :key="'p' + i"
              class="cell"
              :class="{ picked: selected === c.url }"
              :title="c.url"
              @click="pick(c.url)"
            >
              <img
                v-if="!failed[c.url]"
                class="thumb"
                :src="imgUrl(c.url)"
                alt=""
                loading="lazy"
                @error="failed[c.url] = true"
              />
              <div v-else class="thumb none">加载失败</div>
              <div class="meta">
                <span class="t">{{ c.label ?? '图片' }}</span>
                <span v-if="c.width && c.height" class="dim">{{ c.width }}×{{ c.height }}</span>
              </div>
            </div>
          </div>

          <template v-if="secondary().length">
            <NText depth="3" style="font-size: 12px">竖版（选它作横图会被裁切）</NText>
            <div class="grid">
              <div
                v-for="(c, i) in secondary()"
                :key="'s' + i"
                class="cell"
                :class="{ picked: selected === c.url }"
                :title="c.url"
                @click="pick(c.url)"
              >
                <img
                  v-if="!failed[c.url]"
                  class="thumb"
                  :src="imgUrl(c.url)"
                  alt=""
                  loading="lazy"
                  @error="failed[c.url] = true"
                />
                <div v-else class="thumb none">加载失败</div>
                <div class="meta"><span class="t">{{ c.label ?? '图片' }}</span></div>
              </div>
            </div>
          </template>
        </NSpin>
      </template>

      <!-- OpenList 目录里的海报 -->
      <template v-if="candidates && candidates.length">
        <NText depth="3" style="font-size: 12px">该影片 OpenList 目录里的海报文件（{{ candidates.length }}）</NText>
        <div class="grid">
          <div
            v-for="(c, i) in candidates"
            :key="'c' + i"
            class="cell"
            :class="{ picked: selected === c }"
            :title="c"
            @click="pick(c)"
          >
            <img
              v-if="!failed[c]"
              class="thumb"
              :src="imgUrl(c)"
              alt=""
              loading="lazy"
              @error="failed[c] = true"
            />
            <div v-else class="thumb none">加载失败</div>
            <div class="meta"><span class="t">目录海报 {{ i + 1 }}</span></div>
          </div>
        </div>
      </template>

      <NSpace align="center" :size="10" :wrap="false">
        <NText depth="3" style="font-size: 12px">手动地址</NText>
        <NInput v-model:value="manualUrl" placeholder="https://... 图片地址" style="flex: 1" />
        <NButton size="small" @click="pickManual">使用</NButton>
      </NSpace>

      <NAlert type="info" :bordered="false" :show-icon="false">
        选中后只把地址填进表单；保存后到影片详情页点「刮削图片」，即可把图片下载并固化到该影片 OpenList 目录的
        <b>images/</b>（换成自有直链，不再依赖远程图床）。
      </NAlert>
    </NSpace>
  </NModal>
</template>

<style scoped>
.results {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 4px;
}
.r-chip {
  flex: none;
  width: 92px;
  cursor: pointer;
  border: 1px solid var(--hm-line, #e5e6eb);
  border-radius: 8px;
  overflow: hidden;
  background: #fafbfc;
}
.r-chip.on {
  border-color: var(--hm-accent, #2080f0);
  box-shadow: 0 0 0 2px rgba(32, 128, 240, 0.15);
}
/* 缩略图：固定尺寸 + object-fit，避免原图被放大裁切 */
.r-thumb {
  width: 92px;
  height: 122px;
  display: block;
  object-fit: cover;
  background: #f0f2f5;
}
.r-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  padding: 4px 6px;
  font-size: 12px;
}
.r-title {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-weight: 600;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(132px, 1fr));
  gap: 10px;
  max-height: 340px;
  overflow-y: auto;
  padding: 2px;
}
.grid.wide {
  grid-template-columns: repeat(auto-fill, minmax(206px, 1fr));
}
.cell {
  cursor: pointer;
  border: 1px solid var(--hm-line, #e5e6eb);
  border-radius: 8px;
  overflow: hidden;
  background: #fafbfc;
}
.cell:hover {
  border-color: var(--hm-accent, #2080f0);
}
.cell.picked {
  border-color: var(--hm-accent, #2080f0);
  box-shadow: 0 0 0 2px rgba(32, 128, 240, 0.18);
}
.thumb {
  width: 100%;
  height: 190px;
  display: block;
  object-fit: cover;
  background: #f0f2f5;
}
.grid.wide .thumb {
  height: 116px;
}
.thumb.none {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #bbb;
  font-size: 12px;
}
.meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 5px 7px;
  font-size: 11.5px;
  color: #666;
  background: #fff;
}
.meta .t {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.meta .dim {
  flex: none;
  color: #aaa;
}
</style>
