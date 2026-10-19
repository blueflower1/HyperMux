<script setup lang="ts">
/**
 * 元数据刮削弹窗：按片名搜索 TMDB / 豆瓣，选中后取详情并回填表单。
 * 当前来源默认取「系统设置」，可临时切换对比。
 */
import { ref, watch } from 'vue'
import {
  NAlert,
  NButton,
  NEmpty,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NModal,
  NRadioButton,
  NRadioGroup,
  NSpin,
  NTag,
  useMessage,
} from 'naive-ui'
import { SearchOutline } from '@vicons/ionicons5'
import { metadataApi } from '@/api/metadata'
import { imgUrl } from '@/utils/image'
import { settingsApi } from '@/api/settings'
import { errMsg } from '@/api/http'
import type { MetadataDetailVo, MetadataItemVo } from '@/api/types'

const props = defineProps<{
  show: boolean
  keyword?: string
  year?: number | null
}>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'apply', d: MetadataDetailVo): void
}>()

const message = useMessage()

const provider = ref<'tmdb' | 'douban'>('douban')
const keyword = ref('')
const year = ref<number | null>(null)
const searching = ref(false)
const results = ref<MetadataItemVo[]>([])
const picked = ref<MetadataItemVo | null>(null)
const detail = ref<MetadataDetailVo | null>(null)
const detailLoading = ref(false)
let providerInited = false

async function initProvider() {
  if (providerInited) return
  providerInited = true
  try {
    const s = await settingsApi.get()
    provider.value = s.metadataProvider
    if (s.metadataProvider === 'tmdb' && !s.tmdbApiKey) {
      message.warning('尚未配置 TMDB API Key，请到「系统设置」填写，或切到豆瓣')
    }
  } catch {
    /* 用默认 tmdb */
  }
}

watch(
  () => props.show,
  (v) => {
    if (!v) return
    keyword.value = props.keyword ?? ''
    year.value = props.year ?? null
    results.value = []
    picked.value = null
    detail.value = null
    initProvider()
    if (keyword.value.trim()) doSearch()
  },
)

async function doSearch() {
  const kw = keyword.value.trim()
  if (!kw) {
    message.warning('请输入片名关键字')
    return
  }
  searching.value = true
  picked.value = null
  detail.value = null
  try {
    results.value = await metadataApi.search(kw, year.value, provider.value)
    if (!results.value.length) message.info('没有找到匹配结果，试试减少年份或改关键字')
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    searching.value = false
  }
}

async function pick(item: MetadataItemVo) {
  picked.value = item
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await metadataApi.detail(item.providerId, item.mediaType, provider.value)
  } catch (e) {
    message.error(errMsg(e))
  } finally {
    detailLoading.value = false
  }
}

function apply() {
  if (!detail.value) return
  emit('apply', detail.value)
  emit('update:show', false)
}

function onImgError(e: Event) {
  ;(e.target as HTMLImageElement).style.display = 'none'
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    title="刮削元数据（按片名）"
    style="width: 780px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="bar">
      <NRadioGroup v-model:value="provider" size="small" @update:value="results = []; detail = null; picked = null">
        <NRadioButton value="tmdb">TMDB</NRadioButton>
        <NRadioButton value="douban">豆瓣</NRadioButton>
      </NRadioGroup>
      <NInput
        v-model:value="keyword"
        placeholder="片名 / 原名"
        style="width: 220px"
        @keyup.enter="doSearch"
      />
      <NInputNumber v-model:value="year" :min="1900" :max="2100" placeholder="年份" style="width: 110px" />
      <NButton type="primary" :loading="searching" @click="doSearch">
        <template #icon><SearchOutline /></template>
        搜索
      </NButton>
    </div>

    <div class="body">
      <div class="results">
        <NSpin :show="searching">
          <div v-if="!results.length && !searching" class="placeholder">
            <NEmpty description="输入片名后搜索" size="small" />
          </div>
          <div
            v-for="r in results"
            :key="`${r.mediaType}-${r.providerId}`"
            class="hit"
            :class="{ active: picked && picked.providerId === r.providerId && picked.mediaType === r.mediaType }"
            @click="pick(r)"
          >
            <div class="hit-poster">
              <img v-if="r.posterUrl" :src="imgUrl(r.posterUrl)" alt="" loading="lazy" @error="onImgError" />
              <div v-else class="no-poster">无图</div>
            </div>
            <div class="hit-info">
              <div class="hit-title">{{ r.title }}</div>
              <div class="hit-sub">
                <span v-if="r.year">{{ r.year }}</span>
                <NTag size="tiny" :bordered="false">{{ r.mediaType === 'tv' ? '剧集' : '电影' }}</NTag>
              </div>
              <div v-if="r.originalTitle" class="hit-orig">{{ r.originalTitle }}</div>
            </div>
          </div>
        </NSpin>
      </div>

      <div class="detail">
        <NSpin :show="detailLoading">
          <div v-if="detail" class="detail-inner">
            <div class="detail-head">
              <img v-if="detail.posterUrl" :src="imgUrl(detail.posterUrl)" class="detail-poster" alt="" @error="onImgError" />
              <div class="detail-meta">
                <div class="d-title">{{ detail.title }}</div>
                <div v-if="detail.originalTitle" class="d-orig">{{ detail.originalTitle }}</div>
                <div class="d-tags">
                  <NTag v-if="detail.year" size="small" :bordered="false">{{ detail.year }}</NTag>
                  <NTag v-if="detail.rating != null" size="small" type="info" :bordered="false">
                    {{ detail.rating.toFixed(1) }} 分
                  </NTag>
                  <NTag v-if="detail.area" size="small" :bordered="false">{{ detail.area }}</NTag>
                  <NTag v-for="g in detail.genres" :key="g" size="small" :bordered="false">{{ g }}</NTag>
                </div>
              </div>
            </div>
            <p v-if="detail.intro" class="d-intro">{{ detail.intro }}</p>
            <p v-else class="d-intro dim">（无简介）</p>
          </div>
          <div v-else-if="!detailLoading" class="placeholder detail-empty">
            <NAlert v-if="!picked" type="info" :bordered="false">
              左侧搜索后点选结果，这里预览将要回填的字段。
            </NAlert>
            <NEmpty v-else description="未取到详情" size="small" />
          </div>
        </NSpin>
      </div>
    </div>

    <template #footer>
      <div class="footer">
        <span class="dim">将回填：片名 / 原名 / 年份 / 评分 / 简介 / 封面 / 地区 / 标签（匹配平台的类型·地区标签）</span>
        <NButton type="primary" :disabled="!detail" @click="apply">应用到表单</NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.body {
  display: grid;
  grid-template-columns: 300px 1fr;
  gap: 14px;
  min-height: 380px;
}
.results {
  max-height: 440px;
  overflow: auto;
  border: 1px solid var(--hm-line, #e5e6eb);
  border-radius: 9px;
  padding: 6px;
}
.hit {
  display: flex;
  gap: 10px;
  padding: 7px 8px;
  border-radius: 8px;
  cursor: pointer;
}
.hit:hover {
  background: #f6f8fb;
}
.hit.active {
  background: var(--hm-primary-soft, #eef4ff);
  box-shadow: inset 0 0 0 1px var(--hm-primary-line, #cfe0ff);
}
.hit-poster {
  flex: none;
  width: 46px;
  height: 66px;
  border-radius: 6px;
  overflow: hidden;
  background: #eef1f6;
}
.hit-poster img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.no-poster {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--hm-ink-3);
  font-size: 11px;
}
.hit-info {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.hit-title {
  font-size: 13.5px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hit-sub {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--hm-ink-2);
}
.hit-orig {
  font-size: 11.5px;
  color: var(--hm-ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.detail {
  border: 1px solid var(--hm-line, #e5e6eb);
  border-radius: 9px;
  padding: 12px;
  overflow: auto;
  max-height: 440px;
}
.detail-head {
  display: flex;
  gap: 14px;
}
.detail-poster {
  flex: none;
  width: 100px;
  height: 144px;
  object-fit: cover;
  border-radius: 8px;
  background: #eef1f6;
}
.d-title {
  font-size: 17px;
  font-weight: 700;
}
.d-orig {
  color: var(--hm-ink-3);
  font-size: 12.5px;
  margin-top: 2px;
}
.d-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 8px;
}
.d-intro {
  margin: 12px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--hm-ink-2);
  white-space: pre-wrap;
}
.dim {
  color: var(--hm-ink-3);
}
.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 220px;
}
.footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}
.footer .dim {
  font-size: 12px;
}
</style>
