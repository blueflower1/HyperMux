import { createRouter, createWebHashHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { isDesktop } from '@/stores/desktop'

// 裸地址进入（http://host:port 无 #/）：先显式补上 #/，保证默认直达落地页，
// 避免空 hash 初始解析差异与浏览器补全旧 #/login 造成的跳登录
if (!window.location.hash) {
  window.location.replace('#/')
}

const router = createRouter({
  // hash 模式：任意静态托管/子路径均可打开，无需服务端 rewrite
  history: createWebHashHistory(),
  routes: [
    {
      // 产品落地页（web 根路由）：介绍 + 各端下载 + 进入网页端
      path: '/',
      name: 'landing',
      component: () => import('@/views/LandingView.vue'),
      meta: { public: true },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/home',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
    },
    {
      path: '/category/:id(\\d+)',
      name: 'category',
      component: () => import('@/views/CategoryView.vue'),
    },
    {
      path: '/movie/:id(\\d+)',
      name: 'movie',
      component: () => import('@/views/DetailView.vue'),
    },
    {
      path: '/movie/:id(\\d+)/play',
      name: 'play',
      component: () => import('@/views/PlayView.vue'),
      props: true,
    },
    {
      path: '/history',
      name: 'history',
      component: () => import('@/views/HistoryView.vue'),
    },
    {
      path: '/me',
      name: 'me',
      component: () => import('@/views/MeView.vue'),
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
    },
  ],
  scrollBehavior() {
    return { top: 0 }
  },
})

/* 全新加载落在应用入口（补全/书签/刷新带着旧 hash）一律弹回落地页；
   仅真正的深链（影片/分类/播放）保留直达 */
const ENTRY_PATHS = new Set(['/login', '/home', '/me', '/history'])
function isDeepLink(redirect: unknown): boolean {
  if (typeof redirect !== 'string') return false
  return redirect.startsWith('/movie') || redirect.startsWith('/category') || redirect.includes('/play')
}

let firstNav = true
router.beforeEach((to) => {
  const user = useUserStore()
  // 桌面壳不展示产品落地页：根路由直达应用首页（未登录直达登录页）
  if (to.path === '/' && isDesktop()) return { path: user.isLoggedIn ? '/home' : '/login' }
  // 浏览器端首跳落在应用入口：弹回落地页（带深链 redirect 的登录页除外）
  if (firstNav && !isDesktop() && ENTRY_PATHS.has(to.path)) {
    if (!(to.path === '/login' && isDeepLink(to.query.redirect))) return { path: '/' }
  }
  if (user.isLoggedIn && to.path === '/login') return { path: '/home' }
  if (!user.isLoggedIn && !to.meta.public) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  firstNav = false
})

export default router
