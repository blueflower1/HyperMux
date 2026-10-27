// 插件（sidecar 可执行文件）补齐脚本。
//
// 为什么要脚本化：`mpv` / `aria2c` 这两个 sidecar 有 120MB，**不入库**（见 .gitignore 的
// `src-tauri/sidecars/`）。Tauri 的 `externalBin` 在构建期就要看到带 target triple 的文件，
// 否则 `tauri build` 直接失败 —— 所以拉完源码第一次构建前必须先补齐。打包产物不受影响：
// 构建期存在 → NSIS 安装包里照带。
//
// 用法：
//   node ensure-sidecars.mjs          缺失才下载（已就位则跳过）
//   node ensure-sidecars.mjs --force  强制重下
//   npm run build / npm run dev       会自动先跑本脚本
import { createWriteStream, mkdirSync, renameSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { Readable } from 'node:stream'

const root = dirname(fileURLToPath(import.meta.url))
const sidecarDir = resolve(root, 'src-tauri', 'sidecars')

// Tauri 要求文件名带 target triple（externalBin 写 `sidecars/mpv`，构建期找 `mpv-<triple>.exe`）
const TRIPLE = 'x86_64-pc-windows-msvc'

// 源：公共 OpenList 直链（无需 token）
const PLUGINS = [
  { name: 'aria2c', url: 'https://olist.hypermacro.cn/d/123pan/public/software/aria2c.exe' },
  { name: 'mpv', url: 'https://olist.hypermacro.cn/d/123pan/public/software/mpv.exe' }
]

// 下错成 HTML/JSON 错误页时用来兜底判定：Windows PE 文件以 `MZ` 开头
const MIN_SIZE_BYTES = 1024 * 1024
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) HyperMux-Setup/1.0'

const force = process.argv.includes('--force')

function existingSize(path) {
  try {
    return statSync(path).size
  } catch {
    return -1
  }
}

function fmtMB(bytes) {
  return (bytes / 1024 / 1024).toFixed(1)
}

function bar(ratio, width = 24) {
  const filled = Math.max(0, Math.min(width, Math.round(ratio * width)))
  return '█'.repeat(filled) + '░'.repeat(width - filled)
}

/** 单个插件：下载 → 校验 → 落盘（支持 Range 续传，115MB 断在半路很常见） */
async function ensurePlugin(plugin) {
  const target = join(sidecarDir, `${plugin.name}-${TRIPLE}.exe`)
  const part = `${target}.part`
  const have = existingSize(target)

  if (!force && have >= MIN_SIZE_BYTES) {
    console.log(`[sidecar] ${plugin.name} 已就位 (${fmtMB(have)} MB)，跳过`)
    return target
  }

  mkdirSync(sidecarDir, { recursive: true })

  // 已有半截 .part 就先问服务器能不能续
  let offset = existingSize(part)
  if (offset < 0) offset = 0
  const headers = { 'User-Agent': UA }
  if (offset > 0) headers['Range'] = `bytes=${offset}-`

  console.log(`[sidecar] 正在下载插件 ${plugin.name} …`)
  console.log(`          ${plugin.url}`)

  let res
  try {
    res = await fetch(plugin.url, { headers, redirect: 'follow' })
  } catch (e) {
    throw new Error(`${plugin.name} 请求失败：${e.message}（可稍后重试，或手动把 exe 放到 src-tauri/sidecars/）`)
  }
  if (!res.ok && res.status !== 206) {
    throw new Error(`${plugin.name} 下载失败：HTTP ${res.status} ${res.statusText}`)
  }

  const resumed = res.status === 206 && offset > 0
  if (!resumed) offset = 0 // 服务器不支持 Range：整份重来

  // content-length 是"本次要传多少"，不是文件总大小（续传时要加上已下载的 offset）
  const chunkLen = Number(res.headers.get('content-length') || 0)
  const total = res.status === 206 ? offset + chunkLen : chunkLen

  const out = createWriteStream(part, { flags: resumed ? 'a' : 'w' })
  const stream = Readable.fromWeb(res.body)
  let got = offset
  let lastDraw = 0
  const startedAt = Date.now()

  for await (const chunk of stream) {
    got += chunk.length
    out.write(chunk)
    const now = Date.now()
    if (now - lastDraw > 200) {
      lastDraw = now
      const speed = (got - offset) / ((now - startedAt) / 1000) / 1024 / 1024
      const ratio = total > 0 ? got / total : 0
      const line =
        `          ${plugin.name} ${bar(ratio)} ${(ratio * 100).toFixed(1)}%  ` +
        `${fmtMB(got)}/${total > 0 ? fmtMB(total) : '?'} MB  ${speed.toFixed(1)} MB/s`
      process.stdout.write('\r' + line.padEnd(96))
    }
  }
  await new Promise((ok, no) => out.end((e) => (e ? no(e) : ok())))
  process.stdout.write('\r' + ' '.repeat(96) + '\r')

  // 校验：别把 OpenList 的错误页存成 "exe"
  const size = existingSize(part)
  if (size < MIN_SIZE_BYTES) {
    throw new Error(
      `${plugin.name} 下载结果只有 ${size} 字节，不像可执行文件；源返回的可能是错误页，请稍后重试或换网络`
    )
  }
  const head = Buffer.alloc(2)
  {
    const { openSync, readSync, closeSync } = await import('node:fs')
    const fd = openSync(part, 'r')
    try {
      readSync(fd, head, 0, 2)
    } finally {
      closeSync(fd)
    }
  }
  if (head[0] !== 0x4d || head[1] !== 0x5a) {
    throw new Error(`${plugin.name} 下载结果不是 Windows 可执行文件（缺 MZ 头），已丢弃`)
  }

  renameSync(part, target)
  console.log(`[sidecar] ${plugin.name} 完成 (${fmtMB(size)} MB) → ${target}`)
  return target
}

async function main() {
  for (const plugin of PLUGINS) {
    await ensurePlugin(plugin)
  }
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main().catch((e) => {
    console.error(`\n[sidecar] ${e.message}`)
    process.exit(1)
  })
}

export { ensurePlugin, PLUGINS, sidecarDir, TRIPLE }
