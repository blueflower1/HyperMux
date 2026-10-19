import { createRouter, createWebHashHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/Login.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', component: () => import('@/views/dashboard/Dashboard.vue'), meta: { title: '总览' } },
      { path: 'categories', name: 'categories', component: () => import('@/views/movie/CategoryList.vue'), meta: { title: '影视类型' } },
      { path: 'movies', name: 'movies', component: () => import('@/views/movie/MovieList.vue'), meta: { title: '影片列表' } },
      { path: 'movies/new', name: 'movie-new', component: () => import('@/views/movie/MovieEdit.vue'), meta: { title: '新建影片' } },
      // 详情与编辑已合并为「影片工作台」：看、改、剧集、发布在同一页（保留旧地址兼容）
      { path: 'movies/:id', name: 'movie-detail', component: () => import('@/views/movie/MovieEdit.vue'), meta: { title: '影片详情' } },
      { path: 'movies/:id/edit', name: 'movie-edit', component: () => import('@/views/movie/MovieEdit.vue'), meta: { title: '影片详情' } },
      { path: 'tags', name: 'tags', component: () => import('@/views/tag/TagList.vue'), meta: { title: '标签管理' } },
      { path: 'sources', name: 'sources', component: () => import('@/views/scrape/SourceList.vue'), meta: { title: 'OpenList 数据源' } },
      { path: 'settings', name: 'settings', component: () => import('@/views/settings/Settings.vue'), meta: { title: '系统设置' } },
      { path: 'users', name: 'users', component: () => import('@/views/user/UserList.vue'), meta: { title: '用户管理' } },
      { path: 'app-release', name: 'app-release', component: () => import('@/views/apprelease/AppRelease.vue'), meta: { title: '应用更新' } },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFound.vue'),
    meta: { public: true, title: '404' },
  },
]

const router = createRouter({ history: createWebHashHistory(), routes })

router.beforeEach((to) => {
  const store = useUserStore()
  document.title = to.meta.title ? `${to.meta.title} · HyperMux` : 'HyperMux'

  if (!to.meta.public && !store.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && store.isLoggedIn) {
    return { path: '/dashboard' }
  }
  return true
})

// 懒加载 chunk 拉取失败兜底：发布切换 jar / 旧标签页引用旧哈希文件时，
// 整页刷新拿最新构建。10s 冷却防刷新死循环；部署修复后冷却期一过自动恢复。
router.onError((err) => {
  const msg = String((err && err.message) || err)
  if (
    /Failed to fetch dynamically imported module|Importing a module script failed/i.test(msg) ||
    /Loading chunk [\s\S]*failed/i.test(msg)
  ) {
    try {
      const last = Number(sessionStorage.getItem('hm.chunk-reload-at') || 0)
      const now = Date.now()
      if (now - last > 10000) {
        sessionStorage.setItem('hm.chunk-reload-at', String(now))
        window.location.reload()
      }
    } catch {
      /* sessionStorage 不可用时忽略 */
    }
  }
})

export default router
