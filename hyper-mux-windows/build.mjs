// 桌面端打包正式入口（对齐 Android 的 -PhypermuxPublicServerUrl 风格）：
//   npm run build -- --public-server=http://host:port   → 注入公共服务器预制档
//   npm run build                                       → 不注入（登录页只有自定义档）
// 构建成功后自动把 NSIS 安装包归位 dist/hypermux-windows-<version>-setup.exe
import { spawnSync } from 'node:child_process'
import { copyFileSync, mkdirSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = dirname(fileURLToPath(import.meta.url))

// --public-server=<url> 优先；也兼容 HYPERMUX_PUBLIC_SERVER_URL 环境变量
const arg = process.argv.slice(2).find((a) => a.startsWith('--public-server='))
const publicServer = arg ? arg.slice('--public-server='.length) : process.env.HYPERMUX_PUBLIC_SERVER_URL || ''

if (publicServer) {
  process.env.HYPERMUX_PUBLIC_SERVER_URL = publicServer
  console.log(`[build] 已注入公共服务器预制档: ${publicServer}`)
} else {
  console.log('[build] 未注入公共服务器（登录页只有自定义档）')
}

const run = (cmd) => {
  const r = spawnSync(cmd, { cwd: root, stdio: 'inherit', shell: true })
  if (r.status !== 0) process.exit(r.status ?? 1)
}

run('node ensure-sidecars.mjs')

run('npx tauri build')

// 安装包归位 dist（命名与 Android dist 风格一致）
const conf = JSON.parse(readFileSync(resolve(root, 'src-tauri', 'tauri.conf.json'), 'utf8'))
const version = conf.version
const src = resolve(root, 'src-tauri', 'target', 'release', 'bundle', 'nsis', `HyperMux_${version}_x64-setup.exe`)
const out = resolve(root, 'dist', `hypermux-windows-${version}-setup.exe`)
mkdirSync(resolve(root, 'dist'), { recursive: true })
copyFileSync(src, out)
console.log(`[copy-dist] ${out}`)
