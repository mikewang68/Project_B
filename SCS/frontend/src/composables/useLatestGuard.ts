/**
 * 复查-P1：请求竞态守卫。
 * 每次加载调用 next() 取号，响应落位前用 isLatest(token) 校验仍是最新一轮，
 * 旧响应（网络慢 / WS 与筛选并发触发导致的乱序）直接丢弃，
 * 杜绝"筛选已变而数据未变"的错位显示。
 */
export interface LatestGuard {
  next: () => number
  isLatest: (token: number) => boolean
}

export function createLatestGuard(): LatestGuard {
  let seq = 0
  return {
    next: () => ++seq,
    isLatest: (token: number) => token === seq,
  }
}
