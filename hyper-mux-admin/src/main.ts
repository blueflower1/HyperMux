import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useUserStore } from './stores/user'
import { UNAUTHORIZED_EVENT } from './api/http'
import './style.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)

// 401（token 无效 / 过期）→ 统一踢回登录页并带 expired 标记，无需手动退出
window.addEventListener(UNAUTHORIZED_EVENT, () => {
  useUserStore().logout()
  const cur = router.currentRoute.value
  if (cur.path !== '/login') {
    const query: Record<string, string> = { expired: '1' }
    if (cur.fullPath && cur.fullPath !== '/') query.redirect = cur.fullPath
    router.replace({ path: '/login', query })
  }
})

app.mount('#app')
