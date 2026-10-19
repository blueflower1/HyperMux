import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
// artplayer@5.4 的 CSS 由运行时自注入（<style id="artplayer-style">），无需额外引入
import './style.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
