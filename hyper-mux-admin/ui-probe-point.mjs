import puppeteer from 'puppeteer-core'
import fs from 'fs'

const CHROME = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const OUT = '.probe-out'
fs.mkdirSync(OUT, { recursive: true })
const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox'] })
const page = await browser.newPage()
await page.setViewport({ width: 1600, height: 1000 })
await page.goto('http://localhost:5174/#/login', { waitUntil: 'networkidle2' })
const atDash = await page.evaluate(() => location.hash.startsWith('#/dashboard'))
if (!atDash) {
  await page.type('input[placeholder="请输入用户名"]', 'admin')
  await page.type('input[placeholder="请输入密码"]', 'Admin@1234')
  await page.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.replace(/\s/g, '').includes('登录'))
    btn.click()
  })
  await page.waitForFunction(() => location.hash.startsWith('#/dashboard'), { timeout: 15000 })
}
await page.evaluate(() => { location.hash = '#/app-release' })
await new Promise((r) => setTimeout(r, 1500))

// 鼠标移到截图里那个 pill 位置（约 x=128, y=567），看 hover 出来的元素链
await page.mouse.move(128, 567)
await new Promise((r) => setTimeout(r, 400))

const info = await page.evaluate(() => {
  const el = document.elementFromPoint(128, 567)
  const chain = []
  let cur = el
  while (cur && chain.length < 8) {
    const r = cur.getBoundingClientRect()
    chain.push({
      tag: cur.tagName,
      cls: (cur.className && String(cur.className).slice(0, 90)) || '',
      text: (cur.textContent || '').trim().slice(0, 40),
      rect: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
    })
    cur = cur.parentElement
  }
  return chain
})
fs.writeFileSync(OUT + '\\probe-point.json', JSON.stringify(info, null, 2))
console.log(JSON.stringify(info, null, 2))

// 裁剪侧栏截图放大
const clip = await page.evaluate(() => {
  const sider = document.querySelector('.n-layout-sider')
  const r = sider.getBoundingClientRect()
  return { x: r.x, y: r.y, width: r.width, height: r.height }
})
await page.screenshot({ path: OUT + '\\sider-clip.png', clip: { x: clip.x, y: clip.y, width: clip.width, height: clip.height, scale: 0 } })
// 用 deviceScaleFactor=2 重新截
const page2 = await browser.newPage()
await page2.setViewport({ width: 1600, height: 1000, deviceScaleFactor: 2 })
await page2.goto('http://localhost:5174/#/app-release', { waitUntil: 'networkidle2' })
// 新页面没有登录态（不同 page 共享 localStorage！同源 http 下会共享）-> 若被踢回登录就走一次
if (await page2.evaluate(() => location.hash === '#/login')) {
  await page2.type('input[placeholder="请输入用户名"]', 'admin')
  await page2.type('input[placeholder="请输入密码"]', 'Admin@1234')
  await page2.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.replace(/\s/g, '').includes('登录'))
    btn.click()
  })
  await page2.waitForFunction(() => location.hash.startsWith('#/dashboard'), { timeout: 15000 })
  await page2.evaluate(() => { location.hash = '#/app-release' })
  await new Promise((r) => setTimeout(r, 1500))
}
const clip2 = await page2.evaluate(() => {
  const sider = document.querySelector('.n-layout-sider')
  const r = sider.getBoundingClientRect()
  return { x: r.x, y: r.y, width: r.width, height: r.height }
})
await page2.screenshot({ path: OUT + '\\sider-2x.png', clip: clip2 })
await browser.close()
console.log('done')
