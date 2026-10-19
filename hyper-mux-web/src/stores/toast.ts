import { defineStore } from 'pinia'

interface ToastItem {
  id: number
  msg: string
  type: 'info' | 'error'
}

let seq = 1

/** 轻量全局提示（替代 UI 库 message） */
export const useToastStore = defineStore('toast', {
  state: () => ({
    items: [] as ToastItem[],
  }),
  actions: {
    show(msg: string, type: 'info' | 'error' = 'info', duration = 2400) {
      const id = seq++
      this.items.push({ id, msg, type })
      setTimeout(() => this.dismiss(id), duration)
    },
    error(msg: string) {
      this.show(msg, 'error', 3200)
    },
    dismiss(id: number) {
      const i = this.items.findIndex((t) => t.id === id)
      if (i >= 0) this.items.splice(i, 1)
    },
  },
})
