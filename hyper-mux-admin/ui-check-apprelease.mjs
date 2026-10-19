import puppeteer from 'puppeteer-core'
import fs from 'fs'

const CHROME = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const APK = process.env.TEMP + '\\hypermux-test-0.1.8.apk'
const OUT = '.probe-out'
fs.mkdirSync(OUT, { recursive: true })

const log = (...a) => console.log(...a)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
  defaultViewport: { width: 1600, height: 1000 },
})
const page = await browser.newPage()
page.on('console', (m) => {
  if (m.type() === 'error') log('PAGE ERROR:', m.text())
})
page.on('pageerror', (e) => log('PAGE EXCEPTION:', e.message))

async function shot(name) {
  await page.screenshot({ path: `${OUT}\\${name}.png` })
  log('  shot ->', name)
}

// ---- login ----
log('1) login page')
await page.goto('http://localhost:5174/#/login', { waitUntil: 'networkidle2', timeout: 30000 })
await page.type('input[placeholder="请输入用户名"]', 'admin')
await page.type('input[placeholder="请输入密码"]', 'Admin@1234')
await page.evaluate(() => {
  const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.replace(/\s/g, '').includes('登录'))
  btn.click()
})
await page.waitForFunction(() => location.hash.startsWith('#/dashboard'), { timeout: 15000 })
log('  logged in, at', await page.evaluate(() => location.hash))

// ---- navigate to app-release ----
await page.evaluate(() => { location.hash = '#/app-release' })
await sleep(2500)
const t1 = await page.evaluate(() => document.body.innerText)
log('2) page loaded. 应用更新:', t1.includes('应用更新'), '| mobile 0.1.7-test:', t1.includes('0.1.7-test'), '| tv 未发布:', t1.includes('TV（预留，复用同一套接口）尚未发布'))
await shot('ui-1-initial')

const cards = await page.evaluateHandle(() =>
  [...document.querySelectorAll('input[type=file]')].map((i) => i.closest('.plat-card')),
)
const fileInputs = await page.$$('input[type=file]')
log('  cards:', (await cards.jsonValue()).length, 'file inputs:', fileInputs.length)

// ---- TV card publish (code=1, 0.0.1-ui-test; never blocks a real release) ----
log('3) TV card: pick APK + publish code=1')
await fileInputs[1].uploadFile(APK)
await sleep(500)
const tvCard = await page.evaluateHandle((els) => els[1], cards)
const tvCode = await tvCard.$('.n-input-number input')
await tvCode.click()
await tvCode.type('1')
const tvName = await tvCard.$('input[placeholder="如 0.1.8"]')
await tvName.type('0.0.1-ui-test')
const tvNotes = await tvCard.$('textarea')
await tvNotes.type('UI 链路测试发布（P2 验证用，非真实 APK）')
await page.evaluate((card) => {
  const btn = [...card.querySelectorAll('button')].find((b) => b.textContent.trim() === '发布')
  btn.click()
}, tvCard)
await sleep(3000)
const t2 = await page.evaluate(() => document.body.innerText)
log('  after publish. shows 0.0.1-ui-test:', t2.includes('0.0.1-ui-test'), '| versionCode 1 tag:', /versionCode 1\b/.test(t2))
const msg2 = await page.evaluate(() => document.querySelector('.n-message-container')?.textContent || '(no toast)')
log('  toast:', msg2)
await shot('ui-2-tv-published')

// ---- Mobile card conflict path (code=8 == current, expect error toast, no change) ----
log('4) Mobile card: pick APK + publish code=8 (expect conflict)')
await fileInputs[0].uploadFile(APK)
await sleep(400)
const mobCard = await page.evaluateHandle((els) => els[0], cards)
const mobCode = await mobCard.$('.n-input-number input')
await mobCode.click()
await mobCode.type('8')
const mobName = await mobCard.$('input[placeholder="如 0.1.8"]')
await mobName.type('0.1.7-dup')
await page.evaluate((card) => {
  const btn = [...card.querySelectorAll('button')].find((b) => b.textContent.trim() === '发布')
  btn.click()
}, mobCard)
await sleep(2000)
const msg3 = await page.evaluate(() => document.querySelector('.n-message-container')?.textContent || '(no toast)')
log('  conflict toast:', msg3)
const t3 = await page.evaluate(() => document.body.innerText)
log('  mobile still on 0.1.7-test:', t3.includes('0.1.7-test'), '| dup NOT present:', !t3.includes('0.1.7-dup'))
await shot('ui-3-mobile-conflict')

await browser.close()
log('done')
