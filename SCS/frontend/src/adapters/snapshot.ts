/**
 * 边缘抓拍地址归一化：SCS 只代理 `/api/v1/ai-events/snapshots/...`。
 * - `/snapshots/...`：AI ingest 的原始相对路径，补 API 前缀；
 * - `/demo-evidence/...`：本地演示占位图，SCS 不提供该静态资源，按“无抓拍图”处理；
 * - 其它绝对/相对 URL 原样返回。
 */
export function normalizeSnapshotUrl(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  const url = value.trim()
  if (!url) return undefined
  if (url.startsWith('/snapshots/')) return `/api/v1/ai-events${url}`
  if (url.startsWith('/demo-evidence/')) return undefined
  return url
}
