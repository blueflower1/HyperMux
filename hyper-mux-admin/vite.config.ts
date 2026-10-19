import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 同源部署（50-admin §8）：构建产物直接输出到 server classpath static，
// 由 spring boot 以 / 服务（生产可用 HYPERMUX_STATIC_DIRS 挂载 admin-dist 热替换）。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true },
    },
  },
  build: {
    outDir: '../server/src/main/resources/static',
    emptyOutDir: true,
  },
})
