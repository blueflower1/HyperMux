import puppeteer from 'puppeteer-core'
import fs from 'fs'

const CHROME = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox'] })
const page = await browser.newPage()
await page.setViewport({ width: 1600, height: 1000 })

// 复用已登录态：直接带 token 进不来（localStorage），走登录
await page.goto('http://localhost:5174/#/login', { waitUntil: 'networkidle2' })
if (await page.evaluate(() => location.hash.startsWith('#/dashboard'))) {
  // 已登录态残留于 localStorage
} else {
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

const dump = await page.evaluate(() => {
  const menu = document.querySelector('.n-menu')
  const items = [...document.querySelectorAll('.n-menu-item')]
  return {
    itemCount: items.length,
    items: items.map((el) => ({
      label: el.textContent.trim(),
      idx: el.getAttribute('aria-label') || '',
      html: el.outerHTML.slice(0, 400),
    })),
    menuTail: menu ? menu.innerHTML.slice(-800) : '(no .n-menu)',
  }
})
fs.mkdirSync('.probe-out', { recursive: true })
fs.writeFileSync('.probe-out\\menu-dump.json', JSON.stringify(dump, null, 2))
console.log('items:', dump.itemCount)
dump.items.forEach((it) => console.log('-', it.label || '(empty label)'))
await browser.close()
