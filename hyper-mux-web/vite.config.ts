import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 开发 :5174，/api 代理到后端 :8080。
// base './' 相对路径：构建产物可放在任意子路径（如 :8080/web/ 同源部署）直接打开。
// 预设公共服务器：HYPERMUX_PUBLIC_SERVER_URL=http://host:port npm run build（不注入 = 无预设档，语义同 Android 的 -PhypermuxPublicServerUrl）
export default defineConfig({
  base: './',
  plugins: [vue()],
  define: {
    __HYPERMUX_PUBLIC_SERVER__: JSON.stringify(process.env.HYPERMUX_PUBLIC_SERVER_URL ?? ''),
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: true, // 允许局域网（手机）访问
    port: 5174,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
})
