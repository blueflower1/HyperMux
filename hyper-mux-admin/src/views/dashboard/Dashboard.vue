<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NGrid, NGi, NIcon, NNumberAnimation, NSpace, useMessage } from 'naive-ui'
import { CloudOutline, CreateOutline, FilmOutline, LibraryOutline, PeopleOutline } from '@vicons/ionicons5'
import { dashboardApi } from '@/api/dashboard'
import { errMsg } from '@/api/http'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import type { DashboardVo } from '@/api/types'

const router = useRouter()
const message = useMessage()
const appStore = useAppStore()
const userStore = useUserStore()

const dash = ref<DashboardVo | null>(null)

async function load() {
  try {
    dash.value = await dashboardApi.get()
  } catch (e) {
    message.error(errMsg(e))
  }
  appStore.ensureAll().catch(() => {})
}
onMounted(load)

const quick = [
  { label: '新建影片', desc: '从 OpenList 选目录或手动登记', to: '/movies/new', icon: CreateOutline, cls: 'hm-chip--orange' },
  { label: '管理影视类型', desc: 'TV 端首页的类型分区', to: '/categories', icon: LibraryOutline, cls: 'hm-chip--blue' },
  { label: 'OpenList 数据源', desc: '连接你的网盘播放站', to: '/sources', icon: CloudOutline, cls: 'hm-chip--green' },
]
</script>

<template>
  <div class="page">
    <div class="hm-page-head">
      <div>
        <div class="hm-page-title">总览</div>
        <div class="hm-page-sub">
          欢迎回来，{{ userStore.user?.nickName || userStore.user?.username || '管理员' }}，这里是你的影院运营台
        </div>
      </div>
      <NSpace>
        <NButton @click="() => router.push('/sources')">
          <template #icon><CloudOutline /></template>
          数据源
        </NButton>
        <NButton type="primary" @click="() => router.push('/movies/new')">
          <template #icon><CreateOutline /></template>
          新建影片
        </NButton>
      </NSpace>
    </div>

    <NGrid :x-gap="16" :y-gap="16" cols="1 s:2 m:4" responsive="screen" class="hm-stagger">
      <NGi>
        <div class="hm-card hm-card--lift stat">
          <div class="hm-chip hm-chip--orange stat-ico"><FilmOutline /></div>
          <div class="stat-body">
            <NNumberAnimation class="stat-value" :from="0" :to="dash?.publishedMovies ?? 0" :duration="800" />
            <div class="stat-label">上架影片</div>
          </div>
          <div class="stat-foot">TV 端立即可见</div>
        </div>
      </NGi>
      <NGi>
        <div class="hm-card hm-card--lift stat">
          <div class="hm-chip hm-chip--blue stat-ico"><LibraryOutline /></div>
          <div class="stat-body">
            <NNumberAnimation class="stat-value" :from="0" :to="dash?.categories ?? 0" :duration="800" />
            <div class="stat-label">影视类型</div>
          </div>
          <div class="stat-foot">TV 端首页分区</div>
        </div>
      </NGi>
      <NGi>
        <div class="hm-card hm-card--lift stat">
          <div class="hm-chip hm-chip--green stat-ico"><PeopleOutline /></div>
          <div class="stat-body">
            <NNumberAnimation class="stat-value" :from="0" :to="dash?.users ?? 0" :duration="800" />
            <div class="stat-label">平台账号</div>
          </div>
          <div class="stat-foot">可登录账号</div>
        </div>
      </NGi>
      <NGi>
        <div class="hm-card hm-card--lift stat">
          <div class="hm-chip hm-chip--purple stat-ico"><CloudOutline /></div>
          <div class="stat-body">
            <NNumberAnimation class="stat-value" :from="0" :to="dash?.sources ?? 0" :duration="800" />
            <div class="stat-label">OpenList 数据源</div>
          </div>
          <div class="stat-foot">提供视频直链</div>
        </div>
      </NGi>
    </NGrid>

    <NGrid :x-gap="16" :y-gap="16" cols="1 s:3" responsive="screen" class="hm-stagger" style="margin-top: 16px">
      <NGi v-for="q in quick" :key="q.to">
        <div class="hm-card hm-card--lift quick" @click="router.push(q.to)">
          <div class="hm-chip" :class="q.cls"><NIcon size="20"><component :is="q.icon" /></NIcon></div>
          <div class="quick-text">
            <div class="quick-title">{{ q.label }}</div>
            <div class="quick-desc">{{ q.desc }}</div>
          </div>
          <div class="quick-arrow">→</div>
        </div>
      </NGi>
    </NGrid>
  </div>
</template>

<style scoped>
.page {
  max-width: none; width: 100%;
}

/* 统计卡 */
.stat {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px 18px 16px;
}
.stat-ico {
  width: 44px;
  height: 44px;
}
.stat-ico svg {
  width: 22px;
  height: 22px;
}
.stat-body {
  flex: 1;
}
.stat-value {
  font-size: 30px;
  font-weight: 800;
  line-height: 1.1;
}
.stat-label {
  color: var(--hm-ink-2);
  font-size: 13px;
  margin-top: 2px;
}
.stat-foot {
  font-size: 12px;
  color: var(--hm-ink-3);
  border-top: 1px dashed var(--hm-line);
  padding-top: 10px;
  display: flex;
  align-items: center;
}

/* 快捷入口 */
.quick {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
  cursor: pointer;
}
.quick .hm-chip {
  width: 42px;
  height: 42px;
}
.quick-text {
  flex: 1;
  min-width: 0;
}
.quick-title {
  font-weight: 600;
  font-size: 14px;
}
.quick-desc {
  font-size: 12px;
  color: var(--hm-ink-3);
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.quick-arrow {
  color: #c3ccda;
  font-size: 18px;
  transition: transform 0.16s ease, color 0.16s ease;
}
.quick:hover .quick-arrow {
  transform: translateX(4px);
  color: var(--hm-primary);
}
</style>
