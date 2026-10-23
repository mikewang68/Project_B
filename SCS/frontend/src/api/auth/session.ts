/**
 * SCS 可信会话接入层。
 *
 * 身份链路（与后端 `ScsSsoController` 对齐）：
 *
 *   IAM 登录 → 取得一次性短票 → POST /api/v1/auth/sso/exchange
 *            → 后端调 IAM verify-and-consume 原子消费
 *            → 后端建立 HttpOnly 会话并下发 JSESSIONID
 *            → 后续请求由浏览器自动携带 Cookie
 *
 * 设计约束：
 * - 短票**不落 localStorage**，只在内存中短暂持有，交换后立即丢弃；
 * - 不保存任何 JWT，身份完全由服务端会话承载；
 * - 注销由 IAM 侧驱动，本模块只负责在收到 401 时清理本地 UI 状态。
 */

const API_BASE = (import.meta.env.VITE_API_BASE || '/api/v1').replace(/\/$/, '')

/** 当前会话主体（来自 exchange 响应，仅用于 UI 展示，不作为权限判据）。 */
export interface SessionSubject {
  subject: string
  roles: string[]
  permissions: string[]
  scope: string
}

let current: SessionSubject | null = null

export function currentSession(): SessionSubject | null {
  return current
}

export function isAuthenticated(): boolean {
  return current !== null
}

export function clearSession(): void {
  current = null
}

/** 后端响应体。成功时 code 为 0（数字），失败时为字符串错误码。 */
interface ExchangeBody {
  code?: number | string
  message?: string
  data?: SessionSubject
  traceId?: string
}

/**
 * 用 IAM 短票换取本地会话。
 *
 * @param ticket IAM 签发的一次性短票（aud 必须为 `scs`）
 * @throws 交换失败时抛出带业务码的错误
 */
export async function exchangeTicket(ticket: string): Promise<SessionSubject> {
  if (!ticket) {
    throw new Error('缺少短票')
  }

  const response = await fetch(`${API_BASE}/auth/sso/exchange`, {
    method: 'POST',
    // 票据放请求头，不进 URL（避免落入访问日志与浏览器历史）
    headers: { 'Content-Type': 'application/json', 'X-SSO-Ticket': ticket },
    credentials: 'include',
    body: '{}',
  })

  const body = (await response.json().catch(() => ({}))) as ExchangeBody

  // 后端在业务失败时仍返回 HTTP 200，必须看 code
  const ok = response.ok && (body.code === 0 || body.code === '0')
  if (!ok) {
    const reason = body.message || String(body.code ?? '') || `HTTP ${response.status}`
    throw new Error(`会话建立失败：${reason}`)
  }
  if (!body.data || !body.data.subject) {
    throw new Error('会话建立失败：后端未返回主体')
  }

  current = body.data
  return body.data
}

/** 从 URL 查询参数读取票据后立即从地址栏抹除（防止分享链接泄露票据）。 */
/**
 * 模块加载时**立即**提取并清除 URL 中的票据。
 *
 * 为什么必须在此刻做：Vue Router 使用 createWebHistory，初始化时会读取并
 * 可能重写 location。若等到 main.ts 调用 bootstrapSession() 再清理，
 * 路由已经接管 URL，replaceState 会被后续导航覆盖，票据残留在地址栏
 * （可被截图、分享链接、浏览器历史泄露）。
 */
declare global {
  interface Window {
    /** index.html 在模块加载前捕获的票据（URL 已于首帧清除）。 */
    __scsSsoTicket?: string | undefined
  }
}

let pendingTicket: string | null = null

function captureTicketNow(): void {
  if (typeof window === 'undefined') return
  // 优先取 index.html 内联脚本捕获的值——那是最早的执行点
  if (window.__scsSsoTicket) {
    pendingTicket = window.__scsSsoTicket
    try { delete window.__scsSsoTicket } catch { window.__scsSsoTicket = undefined }
    return
  }
  try {
    const url = new URL(window.location.href)
    const ticket = url.searchParams.get('sso_ticket')
    if (!ticket) return
    pendingTicket = ticket
    url.searchParams.delete('sso_ticket')
    window.history.replaceState({}, '', url.toString())
  } catch {
    // 极旧浏览器或缺 history API 时忽略；票据仍会被消费方取走
  }
}

captureTicketNow()

export function takeTicketFromLocation(): string | null {
  // 优先返回模块加载时捕获的票据
  if (pendingTicket) {
    const t = pendingTicket
    pendingTicket = null
    return t
  }
  // 兜底：若模块加载后 URL 才被写入（例如 SPA 内部跳转）
  const url = new URL(window.location.href)
  const ticket = url.searchParams.get('sso_ticket')
  if (!ticket) return null
  url.searchParams.delete('sso_ticket')
  window.history.replaceState({}, '', url.toString())
  return ticket
}

/**
 * 处理进入 SCS 的入口：若 URL 带票据则交换，否则视为未登录。
 * 返回是否已建立会话。
 */
export async function bootstrapSession(): Promise<boolean> {
  const ticket = takeTicketFromLocation()
  if (!ticket) {
    return current !== null
  }
  try {
    await exchangeTicket(ticket)
    return true
  } catch {
    current = null
    return false
  }
}

// 后端返回 401 时清空本地 UI 状态（会话已被 IAM 注销或过期）
if (typeof window !== 'undefined') {
  window.addEventListener('scs:unauthenticated', () => {
    current = null
  })
}
