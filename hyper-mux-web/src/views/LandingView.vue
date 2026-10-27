<script setup lang="ts">
/**
 * 产品落地页（web 端根路由）：产品介绍 + 各平台下载 + 进入网页端。
 * 设备示意图全部 CSS 手绘（手机/平板/TV/桌面/浏览器），风格对齐各端真实 UI。
 * 下载走固定公开域名 hypermux-console.hypermacro.cn 的匿名端点：
 * /api/app/update/check（code=0 取最新发布）与 /api/app/update/download。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Icon from '@/components/Icon.vue'
import { useThemeStore } from '@/stores/theme'
import { useUserStore } from '@/stores/user'
import { fmtBytes } from '@/stores/desktop'
import phoneLight from '@/assets/landing/phone-light.jpg'
import phoneDark from '@/assets/landing/phone-dark.jpg'
import padLight from '@/assets/landing/pad-light.png'
import padDark from '@/assets/landing/pad-dark.png'
import tvLight from '@/assets/landing/tv-light.png'
import tvDark from '@/assets/landing/tv-dark.png'
import winLight from '@/assets/landing/win-light.png'
import winDark from '@/assets/landing/win-dark.png'
import webLight from '@/assets/landing/web-light.png'
import webDark from '@/assets/landing/web-dark.png'

const router = useRouter()
const theme = useThemeStore()
const user = useUserStore()

/** 入口按钮：已登录直达应用首页，未登录进登录页 */
function goEntry() {
  router.push(user.isLoggedIn ? '/home' : '/login')
}

/* ---------- 平台切换（全端覆盖区） ---------- */
type Plat = 'mobile' | 'tablet' | 'tv' | 'windows' | 'web'
type PlatRel = 'mobile' | 'tv' | 'windows'

const activePlat = ref<Plat>('mobile')

const PLATS: Record<Plat, { title: string; icon: string; desc: string; rel: PlatRel | null }> = {
  mobile: { title: '手机端', icon: 'smartphone', desc: '随手浏览追剧，喜欢的剧集缓存下来离线看。', rel: 'mobile' },
  tablet: { title: '平板端', icon: 'tablet', desc: '与手机装同一个版本，横屏大屏看着更舒服。', rel: 'mobile' },
  tv: { title: 'TV 端', icon: 'tv', desc: '遥控器操作，手机扫码就能登录，大屏追剧首选。', rel: 'tv' },
  windows: { title: '电脑端', icon: 'monitor', desc: '把剧下载到电脑里离线看，随时暂停、之后接着下。', rel: 'windows' },
  web: { title: '网页端', icon: 'globe', desc: '免安装，浏览器直接看；账号登录即达片库。', rel: null },
}
const plat = computed(() => PLATS[activePlat.value])

interface ReleaseInfo {
  ok: boolean
  versionName: string
  sizeBytes: number
}

const emptyRelease = (): ReleaseInfo => ({ ok: false, versionName: '', sizeBytes: 0 })
const releases = ref<Record<'mobile' | 'tv' | 'windows', ReleaseInfo>>({
  mobile: emptyRelease(),
  tv: emptyRelease(),
  windows: emptyRelease(),
})

/** 公开 API 入口：固定 HTTPS 域名，版本查询与下载直链都走这里 */
const PUBLIC_API = 'https://hypermux-console.hypermacro.cn/api/app'

function dlUrl(platform: string): string {
  return `${PUBLIC_API}/update/download?platform=${platform}`
}

function metaLine(r: ReleaseInfo): string {
  if (!r.ok) return '暂未发布'
  return `v${r.versionName} · ${fmtBytes(r.sizeBytes)}`
}

onMounted(async () => {
  for (const p of ['mobile', 'tv', 'windows'] as const) {
    try {
      const resp = await fetch(`${PUBLIC_API}/update/check?code=0&platform=${p}`, {
        headers: { Accept: 'application/json' },
      })
      if (resp.status === 204) continue
      const body = await resp.json().catch(() => null)
      const d = body?.data
      if (d?.versionName) {
        releases.value[p] = { ok: true, versionName: d.versionName, sizeBytes: d.sizeBytes || 0 }
      }
    } catch {
      /* 服务器不可达时保持「暂未发布」 */
    }
  }
})

function scrollToPlatforms() {
  document.getElementById('landing-platforms')?.scrollIntoView({ behavior: 'smooth' })
}

function onThemeToggle(e: MouseEvent) {
  theme.toggleAt(e.clientX, e.clientY)
}
</script>

<template>
  <div class="landing">
    <!-- 顶栏 -->
    <nav class="landing-nav">
      <span class="landing-logo">
        <span class="logo-mark"><Icon name="play" :size="14" /></span>
        HyperMux<span class="logo-sub">影视</span>
      </span>
      <span class="landing-nav-right">
        <button
          class="icon-btn"
          type="button"
          :title="theme.theme === 'dark' ? '切换到浅色' : '切换到深色'"
          @click="onThemeToggle"
        >
          <Icon :name="theme.theme === 'dark' ? 'sun' : 'moon'" />
        </button>
        <button class="btn btn-primary btn-sm" type="button" @click="goEntry">进入网页端</button>
      </span>
    </nav>

    <!-- 首屏 -->
    <header class="landing-hero">
      <div class="landing-hero-text">
        <h1>多源影视，<br />一个 HyperMux</h1>
        <p>
          追剧一站式体验：手机、平板、TV、Windows 桌面与网页全端覆盖，
          海量影视即点即播；支持缓存离线观看、下载暂停续传，观看进度多端同步。
        </p>
        <div class="landing-hero-cta">
          <button class="btn btn-primary" type="button" @click="goEntry">进入网页端</button>
          <button class="btn btn-ghost" type="button" @click="scrollToPlatforms">
            <Icon name="download" :size="16" /> 下载客户端
          </button>
        </div>
      </div>

      <div class="landing-hero-mock">
        <div class="mk mk-win mk-win-back">
          <div class="mk-screen mk-shot">
            <img class="shot shot-light" :src="winLight" alt="HyperMux 电脑端界面（浅色主题）" />
            <img class="shot shot-dark" :src="winDark" alt="HyperMux 电脑端界面（深色主题）" />
          </div>
        </div>
        <div class="mk mk-phone mk-phone-front">
          <div class="mk-screen mk-shot">
            <img class="shot shot-light" :src="phoneLight" alt="HyperMux 手机端界面（浅色主题）" />
            <img class="shot shot-dark" :src="phoneDark" alt="HyperMux 手机端界面（深色主题）" />
          </div>
        </div>
      </div>
    </header>

    <!-- 平台下载：切换按钮 + 左右结构内容面板 -->
    <section id="landing-platforms" class="landing-section">
      <h2>全端覆盖</h2>
      <p class="landing-section-sub">同一账号，随设备选最顺手的那一端</p>

      <div class="landing-tabs" role="tablist">
        <button
          v-for="(p, key) in PLATS"
          :key="key"
          class="landing-tab"
          :class="{ on: activePlat === key }"
          type="button"
          role="tab"
          :aria-selected="activePlat === key"
          @click="activePlat = key"
        >
          <Icon :name="p.icon" :size="15" /> {{ p.title }}
        </button>
      </div>

      <transition name="plat" mode="out-in">
        <div class="landing-plat" :key="activePlat">
          <div class="landing-plat-mock">
            <div v-if="activePlat === 'mobile'" class="mk mk-phone">
              <div class="mk-screen mk-shot">
                <img class="shot shot-light" :src="phoneLight" alt="HyperMux 手机端界面（浅色主题）" />
                <img class="shot shot-dark" :src="phoneDark" alt="HyperMux 手机端界面（深色主题）" />
              </div>
            </div>

            <div v-else-if="activePlat === 'tablet'" class="mk mk-tablet">
              <div class="mk-screen mk-shot">
                <img class="shot shot-light" :src="padLight" alt="HyperMux 平板端界面（浅色主题）" />
                <img class="shot shot-dark" :src="padDark" alt="HyperMux 平板端界面（深色主题）" />
              </div>
            </div>

            <div v-else-if="activePlat === 'tv'" class="mk mk-tv">
              <div class="mk-screen mk-shot">
                <img class="shot shot-light" :src="tvLight" alt="HyperMux TV 端界面（浅色主题）" />
                <img class="shot shot-dark" :src="tvDark" alt="HyperMux TV 端界面（深色主题）" />
              </div>
              <span class="mk-tv-stand"></span>
            </div>

            <div v-else-if="activePlat === 'windows'" class="mk mk-win">
              <div class="mk-screen mk-shot">
                <img class="shot shot-light" :src="winLight" alt="HyperMux 电脑端界面（浅色主题）" />
                <img class="shot shot-dark" :src="winDark" alt="HyperMux 电脑端界面（深色主题）" />
              </div>
            </div>

            <div v-else class="mk mk-browser">
              <div class="mk-browser-bar">
                <span class="mk-dots"><i></i><i></i><i></i></span>
                <span class="mk-url">HyperMux · 网页端</span>
              </div>
              <div class="mk-screen mk-shot">
                <img class="shot shot-light" :src="webLight" alt="HyperMux 网页端界面（浅色主题）" />
                <img class="shot shot-dark" :src="webDark" alt="HyperMux 网页端界面（深色主题）" />
              </div>
            </div>
          </div>

          <div class="landing-plat-info">
            <h3><Icon :name="plat.icon" :size="18" /> {{ plat.title }}</h3>
            <p>{{ plat.desc }}</p>
            <template v-if="plat.rel">
              <span v-if="releases[plat.rel].ok" class="landing-card-meta">{{ metaLine(releases[plat.rel]) }}</span>
              <a class="btn btn-primary" :href="dlUrl(plat.rel)">
                <Icon name="download" :size="15" /> 下载{{ plat.title }}安装包
              </a>
            </template>
            <template v-else>
              <span class="landing-card-meta">即开即用</span>
              <button class="btn btn-primary" type="button" @click="goEntry">进入网页端</button>
            </template>
          </div>
        </div>
      </transition>
    </section>

    <!-- 特性 -->
    <section class="landing-section">
      <h2>产品特性</h2>
      <div class="landing-features">
        <div class="landing-feature">
          <span class="landing-feature-ico"><Icon name="film" :size="18" /></span>
          <div><b>海量影视聚合</b><i>多路影视资源统一目录，即点即播</i></div>
        </div>
        <div class="landing-feature">
          <span class="landing-feature-ico"><Icon name="folder" :size="18" /></span>
          <div><b>缓存离线看</b><i>喜欢的剧集存到本地，没网也能看</i></div>
        </div>
        <div class="landing-feature">
          <span class="landing-feature-ico"><Icon name="refresh" :size="18" /></span>
          <div><b>下载可暂停</b><i>大文件下载随时暂停，之后接着下</i></div>
        </div>
        <div class="landing-feature">
          <span class="landing-feature-ico"><Icon name="globe" :size="18" /></span>
          <div><b>多端进度同步</b><i>手机上看到哪儿，换台接着看</i></div>
        </div>
        <div class="landing-feature">
          <span class="landing-feature-ico"><Icon name="user" :size="18" /></span>
          <div><b>扫码登录</b><i>电视上免打字，手机扫一扫就登上去</i></div>
        </div>
        <div class="landing-feature">
          <span class="landing-feature-ico"><Icon name="moon" :size="18" /></span>
          <div><b>深色 / 浅色主题</b><i>白天黑夜都舒服，全端一个样</i></div>
        </div>
      </div>
    </section>

    <footer class="landing-footer">HyperMux 影视 · 全端影视聚合客户端</footer>
  </div>
</template>

<style scoped>
.landing {
  min-height: 100vh;
  background: var(--bg);
  color: var(--text);
}

/* ---------- 顶栏 ---------- */
.landing-nav {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 32px;
  background: color-mix(in srgb, var(--bg) 82%, transparent);
  backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--line);
}
.landing-logo {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  font-weight: 700;
  font-size: 19px;
}
.landing-logo .logo-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: var(--primary-grad);
  color: #fff;
}
.landing-logo .logo-sub {
  margin-left: 2px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-3);
  white-space: nowrap;
}
.landing-nav-right {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}
.landing-nav-right .btn {
  font-size: 14.5px;
  padding: 9px 18px;
  white-space: nowrap;
}

/* ---------- 首屏 ---------- */
.landing-hero {
  display: grid;
  grid-template-columns: minmax(300px, 1fr) minmax(340px, 1fr);
  gap: 44px;
  align-items: center;
  max-width: 1140px;
  margin: 0 auto;
  padding: 72px 32px 56px;
}
.landing-hero-text h1 {
  margin: 0 0 16px;
  font-size: 40px;
  line-height: 1.25;
}
.landing-hero-text p {
  margin: 0 0 26px;
  font-size: 16.5px;
  line-height: 1.75;
  color: var(--text-2);
  max-width: 480px;
}
.landing-hero-cta {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.landing-hero-cta .btn {
  font-size: 15.5px;
  padding: 12px 24px;
}
.landing-hero-mock {
  position: relative;
  height: 430px;
}

/* ---------- 区块 ---------- */
.landing-section {
  max-width: 1140px;
  margin: 0 auto;
  padding: 44px 32px 56px;
}
.landing-section h2 {
  margin: 0 0 8px;
  font-size: 26px;
}
.landing-section-sub {
  margin: 0 0 24px;
  color: var(--text-2);
  font-size: 15.5px;
}
.landing-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 22px;
}
.landing-tab {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 11px 20px;
  border-radius: var(--r-pill);
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--text-2);
  font-size: 15px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.landing-tab:hover {
  border-color: var(--line-strong);
  color: var(--text);
}
.landing-tab.on {
  background: var(--primary-soft);
  border-color: color-mix(in srgb, var(--primary) 45%, transparent);
  color: var(--primary);
  font-weight: 600;
}
.landing-plat {
  display: grid;
  grid-template-columns: minmax(420px, 1.2fr) minmax(300px, 0.9fr);
  gap: 48px;
  align-items: center;
  padding: 40px 4px 12px;
}
.landing-plat-mock {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 620px;
}
/* 面板内设备放大一档（首屏 hero 不受影响） */
.landing-plat-mock .mk-phone { width: 275px; height: 580px; }
.landing-plat-mock .mk-phone::before { width: 76px; height: 14px; top: 18px; }
.landing-plat-mock .mk-tablet { width: 484px; height: 282px; }
.landing-plat-mock .mk-tv { width: 556px; height: 319px; }
.landing-plat-mock .mk-tv-stand { width: 150px; height: 20px; bottom: -22px; }
.landing-plat-mock .mk-win,
.landing-plat-mock .mk-browser { width: 540px; height: 290px; }
.landing-plat-mock .mk-browser { height: 323px; }
.landing-plat-info h3 {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 14px;
  font-size: 28px;
}
.landing-plat-info p {
  margin: 0 0 18px;
  font-size: 17px;
  line-height: 1.75;
  color: var(--text-2);
  max-width: 460px;
}
.landing-plat-info .btn {
  font-size: 16px;
  padding: 13px 26px;
}
.landing-card-meta {
  display: block;
  margin-bottom: 18px;
  font-size: 14px;
  color: var(--text-3);
}
/* 平台切换过渡 */
.plat-enter-active,
.plat-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}
.plat-enter-from {
  opacity: 0;
  transform: translateY(10px);
}
.plat-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* ---------- 特性 ---------- */
.landing-features {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 16px;
  margin-top: 24px;
}
.landing-feature {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: var(--r);
  background: var(--surface);
}
.landing-feature-ico {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  flex: none;
  border-radius: 12px;
  background: var(--primary-soft);
  color: var(--primary);
}
.landing-feature b {
  display: block;
  font-size: 16px;
  margin-bottom: 4px;
}
.landing-feature i {
  display: block;
  font-style: normal;
  font-size: 14px;
  color: var(--text-2);
  line-height: 1.6;
}

.landing-footer {
  padding: 30px 28px 38px;
  text-align: center;
  font-size: 14px;
  color: var(--text-3);
  border-top: 1px solid var(--line);
}

/* ---------- 设备 mockup（CSS 手绘） ---------- */
.mk {
  position: relative;
  background: #101318;
  border-radius: 22px;
  padding: 8px;
  box-shadow: var(--shadow-lg);
}
.mk-screen {
  display: flex;
  flex-direction: column;
  gap: 8px;
  height: 100%;
  border-radius: 14px;
  overflow: hidden;
  background: var(--bg);
  padding: 10px;
}
.mk-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-weight: 700;
  color: var(--text);
}
.mk-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: 5px;
  background: var(--primary-grad);
  color: #fff;
}
.mk-search {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10px;
  color: var(--text-3);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  padding: 5px 10px;
}
.mk-grid {
  display: grid;
  gap: 7px;
}
.mk-grid.cols-2 { grid-template-columns: repeat(2, 1fr); }
.mk-grid.cols-4 { grid-template-columns: repeat(4, 1fr); }
.mk-grid.cols-5 { grid-template-columns: repeat(5, 1fr); }
.mk-grid.grow { flex: 1; align-content: start; }
.mk-poster {
  position: relative;
  aspect-ratio: 2 / 3;
  border-radius: 6px;
  background: linear-gradient(135deg, var(--cover-bg-a), var(--cover-bg-b));
}
.mk-poster.t1 { background: linear-gradient(135deg, color-mix(in srgb, var(--primary) 34%, var(--cover-bg-a)), var(--cover-bg-b)); }
.mk-poster.t2 { background: linear-gradient(135deg, var(--cover-bg-a), color-mix(in srgb, var(--rating) 30%, var(--cover-bg-b))); }
.mk-poster.t3 { background: linear-gradient(135deg, color-mix(in srgb, var(--accent) 26%, var(--cover-bg-a)), color-mix(in srgb, var(--cover-bg-b) 70%, var(--primary))); }
.mk-poster.has-play b {
  position: absolute;
  inset: 0;
  margin: auto;
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--primary-grad);
  color: #fff;
}
.mk-tabs {
  display: flex;
  justify-content: space-around;
  align-items: center;
  border-top: 1px solid var(--line);
  padding-top: 7px;
  color: var(--text-3);
}
.mk-tabs :first-child { color: var(--primary); }

/* 手机 */
.mk-phone {
  width: 210px;
  height: 438px;
  border-radius: 30px;
  padding: 10px;
}
.mk-phone .mk-screen { border-radius: 20px; }
.mk-phone::before {
  content: '';
  position: absolute;
  top: 14px;
  left: 50%;
  transform: translateX(-50%);
  width: 60px;
  height: 11px;
  border-radius: 6px;
  background: #101318;
  z-index: 2;
}
.mk-phone .mk-screen { padding-top: 18px; }

/* 真机截图模式：图片铺满屏幕，随 data-theme 切换明暗 */
.mk-shot { padding: 0 !important; }
.mk-shot .shot { width: 100%; height: 100%; object-fit: cover; display: block; }
.mk-shot .shot-dark { display: none; }
:root[data-theme='dark'] .mk-shot .shot-light { display: none; }
:root[data-theme='dark'] .mk-shot .shot-dark { display: block; }

/* 平板（屏幕 16:9，匹配真机截图） */
.mk-tablet {
  width: 354px;
  height: 210px;
  border-radius: 20px;
  padding: 12px;
}
.mk-tablet .mk-screen { border-radius: 11px; }

/* TV（屏幕 16:9，匹配真机截图） */
.mk-tv {
  width: 420px;
  height: 243px;
  border-radius: 12px;
  padding: 8px;
}
.mk-tv .mk-screen { border-radius: 6px; }
.mk-tv-stand {
  position: absolute;
  left: 50%;
  bottom: -18px;
  transform: translateX(-50%);
  width: 116px;
  height: 16px;
  border-radius: 0 0 8px 8px;
  background: #101318;
}

/* Windows 桌面窗口（真机截图铺满；桌面端本身无边框、自带应用顶栏，无需假标题栏） */
.mk-win {
  width: 540px;
  height: 290px;
  border-radius: 14px;
  padding: 0;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--line-strong);
}
.mk-win .mk-screen { border-radius: 13px; }

/* 浏览器窗口（地址栏 + 真机截图） */
.mk-browser {
  width: 540px;
  height: 323px;
  border-radius: 14px;
  padding: 0;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--line-strong);
}
.mk-browser-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding: 0 12px;
  background: var(--surface-2);
  border-bottom: 1px solid var(--line);
}
.mk-dots { display: inline-flex; gap: 5px; }
.mk-dots i { width: 9px; height: 9px; border-radius: 50%; background: var(--line-strong); }
.mk-url {
  flex: 1;
  font-size: 10px;
  color: var(--text-3);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  padding: 4px 10px;
  text-align: center;
}
.mk-browser .mk-screen { border-radius: 0; height: calc(100% - 34px); }

/* 首屏设备叠放 */
.mk-win-back {
  position: absolute;
  right: 0;
  top: 46px;
  transform: rotate(2deg);
}
.mk-phone-front {
  position: absolute;
  left: 26px;
  top: 0;
  transform: rotate(-3deg);
  z-index: 2;
}

/* ---------- 响应式 ---------- */
@media (max-width: 860px) {
  .landing-hero {
    grid-template-columns: 1fr;
    padding-top: 44px;
  }
  .landing-hero-mock {
    height: 400px;
    max-width: 460px;
    margin: 0 auto;
  }
  .mk-win-back { right: -10px; }
  .mk-phone-front { left: 0; }
  .landing-hero-text h1 { font-size: 32px; }
  .landing-plat {
    grid-template-columns: 1fr;
    gap: 22px;
  }
  /* 窄屏：文字在前、设备图在后，去掉占位高度避免大片空白 */
  .landing-plat-mock {
    min-height: 0;
    order: 2;
  }
  .landing-plat-info {
    order: 1;
  }
}

@media (max-width: 640px) {
  .landing-nav {
    padding: 10px 16px;
  }
  .landing-logo { font-size: 17px; }
  .landing-logo .logo-mark { width: 26px; height: 26px; }
  .landing-hero {
    padding: 32px 18px 24px;
  }
  .landing-hero-text h1 { font-size: 28px; }
  .landing-hero-text p { font-size: 15px; }
  .landing-hero-mock {
    height: 420px;
  }
  /* 手机端首屏只保留手机 mockup，居中展示 */
  .mk-win-back { display: none; }
  .mk-phone-front {
    left: 50%;
    top: 8px;
    transform: translateX(-50%) rotate(-2deg);
  }
  .landing-section {
    padding: 26px 18px 36px;
  }
  .landing-section h2 { font-size: 22px; }
  .landing-section-sub { font-size: 14px; margin-bottom: 18px; }
  /* 平台切换：手机端 3+2 排列（上排三个、下排两个居中），不滚动、不出血 */
  .landing-tabs {
    display: grid;
    grid-template-columns: repeat(6, minmax(0, 1fr));
    gap: 8px;
    margin-top: 18px;
  }
  .landing-tab {
    grid-column: span 2;
    min-width: 0;
    justify-content: center;
    white-space: nowrap;
    font-size: 13px;
    padding: 9px 6px;
    gap: 5px;
  }
  .landing-tab:nth-child(4) { grid-column: 2 / span 2; }
  .landing-tab:nth-child(5) { grid-column: 4 / span 2; }
  .landing-tab :deep(.icon svg) { width: 14px; height: 14px; }
  .landing-plat {
    gap: 14px;
    padding-top: 14px;
  }
  .landing-plat-info h3 { font-size: 22px; }
  .landing-plat-info p { font-size: 15px; }
  .landing-plat-info .btn { font-size: 15px; padding: 11px 22px; }
  /* 窄屏设备缩小（面板内规则优先级更高，需成对覆盖） */
  .mk-phone { width: 190px; height: 396px; }
  .mk-tablet { width: 300px; height: 180px; }
  .mk-tv { width: 320px; height: 186px; }
  .mk-win,
  .mk-browser { width: 320px; height: 216px; }
  /* 面板设备：居中展示，宽度不超出屏幕（更窄的手机自动收窄） */
  .landing-plat-mock { width: 100%; }
  .landing-plat-mock .mk-phone { width: 232px; height: 490px; }
  .landing-plat-mock .mk-phone::before { width: 64px; height: 12px; top: 16px; }
  .landing-plat-mock .mk-tablet { width: min(340px, 100%); height: 200px; }
  .landing-plat-mock .mk-tv { width: min(340px, 100%); height: 196px; }
  .landing-plat-mock .mk-tv-stand { width: 96px; height: 14px; bottom: -16px; }
  .landing-plat-mock .mk-win { width: min(340px, 100%); height: 182px; }
  .landing-plat-mock .mk-browser { width: min(340px, 100%); height: 216px; }
  /* 窄屏面板文字居中，配合居中的设备图 */
  .landing-plat-info { text-align: center; }
  .landing-plat-info h3 { justify-content: center; }
  .landing-plat-info p { max-width: none; }
  .landing-features {
    grid-template-columns: 1fr;
  }
  .landing-feature { padding: 16px; }
  .landing-feature b { font-size: 15px; }
  .landing-feature i { font-size: 13.5px; }
  .landing-footer { font-size: 13px; }
}
</style>
