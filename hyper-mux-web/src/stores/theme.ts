import { defineStore } from 'pinia'

export type Theme = 'light' | 'dark'

const THEME_KEY = 'hypermux.web.theme'

interface Reveal {
  /** 点击起点（视口坐标）；null = 立即切换、无涟漪 */
  from: { x: number; y: number } | null
  /** 每次切换 +1 */
  seq: number
  /** 本轮目标主题（涟漪颜色跟随它） */
  target: Theme
}

interface State {
  theme: Theme
  reveal: Reveal
}

function initialTheme(): Theme {
  // index.html 内联脚本已在首 render 前写入 data-theme，这里与其保持一致
  const attr = document.documentElement.getAttribute('data-theme')
  return attr === 'light' ? 'light' : 'dark'
}

export const useThemeStore = defineStore('theme', {
  state: (): State => ({
    theme: initialTheme(),
    reveal: { from: null, seq: 0, target: 'dark' },
  }),
  actions: {
    /** 写 <html data-theme> + localStorage（由 ThemeRevealFX 在效果落定后调用） */
    applyAttr() {
      document.documentElement.setAttribute('data-theme', this.theme)
      localStorage.setItem(THEME_KEY, this.theme)
    },
    /**
     * 切换主题。主题色立即落定（内容全程可见、永不空白）；
     * 传 x/y（点击点）时 ThemeRevealFX 从该点播一圈扩散波纹作为反馈。
     */
    switchTo(t: Theme, x?: number, y?: number) {
      this.theme = t
      this.applyAttr()
      this.reveal = {
        from: x != null && y != null ? { x, y } : null,
        seq: this.reveal.seq + 1,
        target: t,
      }
    },
    toggle() {
      this.switchTo(this.theme === 'dark' ? 'light' : 'dark')
    },
    toggleAt(x: number, y: number) {
      this.switchTo(this.theme === 'dark' ? 'light' : 'dark', x, y)
    },
  },
})
