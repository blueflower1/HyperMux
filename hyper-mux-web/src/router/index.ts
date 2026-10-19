import { createRouter, createWebHashHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  // hash 模式：任意静态托管/子路径均可打开，无需服务端 rewrite
  history: createWebHashHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
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

router.beforeEach((to) => {
  const user = useUserStore()
  if (!user.isLoggedIn && !to.meta.public) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (user.isLoggedIn && to.path === '/login') {
    return { path: '/' }
  }
})

export default router
