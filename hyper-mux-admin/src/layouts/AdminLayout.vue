<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NButton,
  NDropdown,
  NIcon,
  NLayout,
  NLayoutContent,
  NLayoutHeader,
  NLayoutSider,
  NMenu,
  NText,
} from 'naive-ui'
import type { MenuOption } from 'naive-ui'
import {
  BookmarkOutline,
  CloudDownloadOutline,
  CloudOutline,
  ContractOutline,
  ExpandOutline,
  HomeOutline,
  LayersOutline,
  LibraryOutline,
  LogOutOutline,
  PeopleOutline,
  PlayOutline,
  SettingsOutline,
} from '@vicons/ionicons5'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const collapsed = ref(false)

// 深色侧栏菜单配色（通过组件主题覆写，CSS 变量会被组件内联主题覆盖，不能靠外部样式）
const menuThemeOverrides = {
  color: 'transparent',
  groupTextColor: 'rgba(255, 255, 255, 0.32)',
  itemHeight: '40px',
  borderRadius: '10px',
  itemTextColor: 'rgba(255, 255, 255, 0.66)',
  itemTextColorHover: 'rgba(255, 255, 255, 0.95)',
  itemTextColorActive: '#ffffff',
  itemTextColorActiveHover: '#ffffff',
  itemIconColor: 'rgba(255, 255, 255, 0.45)',
  itemIconColorHover: 'rgba(255, 255, 255, 0.9)',
  itemIconColorActive: '#ffb063',
  itemIconColorActiveHover: '#ffb063',
  itemColorHover: 'rgba(255, 255, 255, 0.07)',
  itemColorActive: 'rgba(245, 130, 13, 0.2)',
  itemColorActiveHover: 'rgba(245, 130, 13, 0.28)',
  // 收起态：naive 的默认值取自「浅色主题」（图标 textColor1 深灰、选中色 10% 透明度），
  // 放在深色侧栏上等于看不见，这里显式补上深色底可用的值
  itemColorActiveCollapsed: 'rgba(245, 130, 13, 0.24)',
  itemIconColorCollapsed: 'rgba(255, 255, 255, 0.5)',
  arrowColor: 'rgba(255, 255, 255, 0.4)',
  arrowColorHover: 'rgba(255, 255, 255, 0.8)',
}

function renderIcon(icon: any) {
  return () => h(NIcon, null, { default: () => h(icon) })
}

const options: MenuOption[] = [
  { label: '总览', key: '/dashboard', icon: renderIcon(HomeOutline) },
  {
    label: '影片管理',
    type: 'group',
    key: 'g-movie',
    children: [
      { label: '影视类型', key: '/categories', icon: renderIcon(LibraryOutline) },
      { label: '影片列表', key: '/movies', icon: renderIcon(LayersOutline) },
    ],
  },
  { label: '标签管理', key: '/tags', icon: renderIcon(BookmarkOutline) },
  {
    label: '资源',
    type: 'group',
    key: 'g-resource',
    children: [
      { label: 'OpenList 数据源', key: '/sources', icon: renderIcon(CloudOutline) },
      { label: '系统设置', key: '/settings', icon: renderIcon(SettingsOutline) },
    ],
  },
  { label: '用户管理', key: '/users', icon: renderIcon(PeopleOutline) },
  { label: '应用更新', key: '/app-release', icon: renderIcon(CloudDownloadOutline) },
]

function toPath(key: string) {
  router.push(key)
}

const userName = computed(() => userStore.user?.nickName || userStore.user?.username || '管理员')
const roleLabel = computed(() => (userStore.user?.role === 1 ? '管理员' : '普通用户'))
const avatarChar = computed(() => (userName.value[0] || 'H').toUpperCase())

const userMenu = computed<MenuOption[]>(() => [
  { label: `当前身份：${roleLabel.value}`, key: 'info', disabled: true },
  { type: 'divider', key: 'd' },
  {
    label: '退出登录',
    key: 'logout',
    icon: renderIcon(LogOutOutline),
  },
])

function onUserSelect(key: string) {
  if (key === 'logout') {
    userStore.logout()
    router.replace('/login')
  }
}
</script>

<template>
  <NLayout has-sider class="shell" style="height: 100vh">
    <NLayoutSider
      bordered
      class="sider"
      :collapsed="collapsed"
      :width="240"
      :collapsed-width="72"
      :show-trigger="false"
      collapse-mode="width"
      :native-scrollbar="false"
      @update:collapsed="(v: boolean) => (collapsed = v)"
    >
      <div class="brand">
        <div class="brand-mark">
          <PlayOutline />
        </div>
        <transition name="hm-fade">
          <div v-if="!collapsed" class="brand-text">
            <div class="brand-name">HyperMux</div>
            <div class="brand-sub">运营控制台</div>
          </div>
        </transition>
      </div>
      <NMenu
        class="menu"
        :theme-overrides="menuThemeOverrides"
        :value="route.path"
        :collapsed="collapsed"
        :collapsed-width="72"
        :collapsed-icon-size="20"
        :options="options"
        @update:value="toPath"
      />
      <div class="sider-foot">
        <button class="collapse-btn" title="收起 / 展开" @click="collapsed = !collapsed">
          <ExpandOutline v-if="collapsed" />
          <ContractOutline v-else />
        </button>
      </div>
    </NLayoutSider>

    <NLayout class="main">
      <NLayoutHeader class="header" :segmented="false">
        <div class="header-title">
          <span class="title">{{ route.meta.title }}</span>
          <span class="crumb">/ {{ route.path.replace(/^\//, '') }}</span>
        </div>
        <NDropdown :options="userMenu" @select="onUserSelect" trigger="click" placement="bottom-end">
          <div class="user-chip">
            <div class="avatar">{{ avatarChar }}</div>
            <div class="user-meta">
              <div class="user-name">{{ userName }}</div>
              <div class="user-role">{{ roleLabel }}</div>
            </div>
          </div>
        </NDropdown>
      </NLayoutHeader>

      <NLayoutContent
        class="content"
        :native-scrollbar="false"
        content-style="min-height: 100%; padding: 20px 24px 32px;"
      >
        <router-view v-slot="{ Component, route: r }">
          <!-- 不用 <transition mode="out-in">：与懒加载路由组件存在状态机竞态，
               偶发渲染成空注释节点导致切页白屏（刷新才恢复）。
               改为挂载时直接播放入场动画，效果等价且无竞态。 -->
          <component :is="Component" :key="r.path" class="hm-page-enter" />
        </router-view>
      </NLayoutContent>
    </NLayout>
  </NLayout>
</template>

<style scoped>
.shell {
  background: transparent;
}

/* ---------- 侧栏 ---------- */
.sider {
  background: #161c2c !important;
}
.sider :deep(.n-layout-sider__content) {
  background: transparent;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 16px 14px;
  min-height: 64px;
  overflow: hidden;
}
/* 收起态只有 36px 的 logo，靠 justify-content 精确居中（padding 16px 会差 2px） */
.sider.n-layout-sider--collapsed .brand {
  padding-left: 0;
  padding-right: 0;
  justify-content: center;
}
.sider:not(.n-layout-sider--collapsed) .brand {
  padding: 18px 20px 14px;
}
.brand-mark {
  width: 36px;
  height: 36px;
  border-radius: 11px;
  background: linear-gradient(135deg, #f7a325, #f5720d);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex: none;
  box-shadow: 0 4px 14px -4px rgba(245, 114, 13, 0.7);
}
.brand-mark svg {
  width: 18px;
  height: 18px;
  margin-left: 2px;
}
.brand-text {
  white-space: nowrap;
}
.brand-name {
  font-size: 16px;
  font-weight: 800;
  color: #fff;
  letter-spacing: 0.04em;
  line-height: 1.2;
}
.brand-sub {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.4);
  margin-top: 1px;
}

/* 菜单（深色，配色由 :theme-overrides 注入） */
.menu {
  padding: 4px 10px 10px;
}
.menu :deep(.n-menu-item) {
  border-radius: 10px;
  margin-bottom: 2px;
}
.menu :deep(.n-menu-item--selected) {
  font-weight: 600;
  background: linear-gradient(90deg, rgba(245, 130, 13, 0.3), rgba(245, 130, 13, 0.14)) !important;
  box-shadow: inset 0 0 0 1px rgba(245, 160, 60, 0.25);
}
.menu :deep(.n-menu-item-content) {
  transition: transform 0.14s ease;
}
.menu :deep(.n-menu-item:hover) .n-menu-item-content {
  transform: translateX(2px);
}

/* ---------- 菜单收起态修正 ---------- */
/* naive 收起态按「菜单容器 = 侧栏全宽」来居中图标（padding-left = (72 - 20) / 2 = 26px），
   而 .menu 的左右 10px 内边距会把图标整体右移 10px、并把内容区压到 52px；
   收起态去掉左右内边距，让 naive 的居中算式成立 */
.sider :deep(.n-menu--collapsed) {
  padding-left: 0;
  padding-right: 0;
}
/* 收起态的高亮块改成居中的 40x40 圆角方块
   （默认 left/right 8px 会把内容区压到 52px 后仍按 8px 计算，两侧留白不对称） */
.sider :deep(.n-menu--collapsed .n-menu-item-content::before) {
  left: 16px;
  right: 16px;
}
/* 分组标题在收起态没有图标，只剩「竖排文字」并压住下面的图标：
   隐藏文字，换成一条居中短分隔线维持分组节奏 */
.sider :deep(.n-menu--collapsed .n-menu-item-group-title) {
  padding-left: 0 !important;
  justify-content: center;
  font-size: 0;
}
.sider :deep(.n-menu--collapsed .n-menu-item-group-title)::after {
  content: '';
  width: 24px;
  height: 1px;
  border-radius: 1px;
  background: rgba(255, 255, 255, 0.14);
}

.sider-foot {
  padding: 10px 14px 14px;
  display: flex;
  justify-content: center;
}
.collapse-btn {
  width: 100%;
  max-width: 300px;
  border: none;
  background: rgba(255, 255, 255, 0.06);
  color: rgba(255, 255, 255, 0.55);
  border-radius: 9px;
  padding: 7px 0;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.16s ease, color 0.16s ease, transform 0.12s ease;
}
.collapse-btn:hover {
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
}
.collapse-btn:active {
  transform: scale(0.97);
}
.collapse-btn svg {
  width: 16px;
  height: 16px;
}

/* ---------- 主区 ---------- */
.main {
  background:
    radial-gradient(900px 300px at 85% -80px, rgba(245, 130, 13, 0.05), transparent 60%),
    #f3f5f9;
}
.header {
  height: 60px;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid var(--hm-line);
}
.header-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}
.header-title .title {
  font-size: 16px;
  font-weight: 700;
}
.header-title .crumb {
  color: var(--hm-ink-3);
  font-size: 12px;
  font-family: Consolas, Menlo, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 10px 5px 6px;
  border-radius: 99px;
  border: 1px solid var(--hm-line);
  background: #fff;
  cursor: pointer;
  transition: box-shadow 0.16s ease, transform 0.12s ease, border-color 0.16s ease;
}
.user-chip:hover {
  box-shadow: 0 4px 14px -6px rgba(23, 32, 51, 0.25);
  border-color: #d9e0ea;
  transform: translateY(-1px);
}
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, #2b3a5c, #161c2c);
  color: #ffb063;
  font-weight: 700;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
}
.user-meta {
  line-height: 1.25;
  white-space: nowrap;
}
.user-name {
  font-size: 13px;
  font-weight: 600;
}
.user-role {
  font-size: 11px;
  color: var(--hm-ink-3);
}

.content :deep(.n-layout-content) {
  background: transparent;
}
/* naive 的滚动容器默认白底，会在内容区底部和页面灰底之间露出一条接缝线；
   统一透明，让整块内容走 .main 的灰底，卡片用白底区分 */
.content,
.content :deep(.n-layout-content),
.content :deep(.n-layout-scroll-container) {
  background: transparent !important;
  border: none !important;
}
</style>
