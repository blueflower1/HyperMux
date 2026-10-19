/**
 * 远程图片统一走本服务 /img 代理：
 * 后端会带 Referer 下载并落盘缓存（豆瓣图床直连会 HTTP 403 防盗链，所以必须代理）。
 * 已在本站（含 OpenList 自有直链经代理）或非 http 的地址原样返回。
 */
export function imgUrl(url?: string | null): string {
  if (!url) return ''
  const u = url.trim()
  if (!u) return ''
  if (!/^https?:\/\//i.test(u)) return u
  if (u.startsWith(location.origin)) return u
  return `${location.origin}/img?u=${encodeURIComponent(u)}`
}
