import type { SafetySnapshot } from '@/types/safety'

/** 全局实时连接状态（第四阶段：管理端 / 大屏 / 移动端共用一条 /ws/live） */
export type LiveConnection = 'connecting' | 'connected' | 'reconnecting' | 'disconnected'

/** 旧安全态势 store 使用的三态（保留兼容） */
export type LiveState = 'connecting' | 'online' | 'offline'

/** 后端统一实时事件：{ type, eventId, ts, traceId, data } */
export interface LiveEvent<TData extends Record<string, unknown> = Record<string, unknown>> {
  type: string
  eventId?: string
  ts?: string
  traceId?: string
  data?: TData
}

type MessageHandler = (raw: Record<string, unknown>) => void
type StateHandler = (state: LiveConnection) => void
type ReconnectHandler = () => void

/**
 * 原生 WebSocket 底层客户端：
 * - 20 秒心跳发送 {"type":"ping"}，服务端回 pong；
 * - 指数退避自动重连（1s 起，上限 30s + 抖动）；
 * - 重连成功后触发 onReconnected，调用方据此主动 REST 全量刷新（断线期间增量不丢）；
 * - 只做“通知变了”，不缓存业务数据，REST 永远是权威读取来源。
 */
class LiveSocket {
  private socket?: WebSocket
  private reconnectTimer?: number
  private heartbeatTimer?: number
  private attempt = 0
  private stopped = false
  private everOpened = false

  private readonly messageHandlers = new Set<MessageHandler>()
  private readonly stateHandlers = new Set<StateHandler>()
  private readonly reconnectHandlers = new Set<ReconnectHandler>()

  onMessage(handler: MessageHandler): () => void {
    this.messageHandlers.add(handler)
    return () => this.messageHandlers.delete(handler)
  }

  onState(handler: StateHandler): () => void {
    this.stateHandlers.add(handler)
    return () => this.stateHandlers.delete(handler)
  }

  /** 重连成功回调（首次连接不触发） */
  onReconnected(handler: ReconnectHandler): () => void {
    this.reconnectHandlers.add(handler)
    return () => this.reconnectHandlers.delete(handler)
  }

  private emitState(state: LiveConnection): void {
    this.stateHandlers.forEach((h) => h(state))
  }

  connect(): void {
    this.stopped = false
    if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) {
      return
    }
    this.emitState(this.everOpened ? 'reconnecting' : 'connecting')
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const configuredUrl = import.meta.env.VITE_WS_URL?.trim()
    const websocketPath = import.meta.env.VITE_WS_PATH?.trim() || '/ws/live'
    const websocketUrl = configuredUrl || `${protocol}//${window.location.host}${websocketPath}`

    this.socket = new WebSocket(websocketUrl)
    this.socket.onopen = () => {
      const wasReconnect = this.everOpened
      this.everOpened = true
      this.attempt = 0
      this.emitState('connected')
      this.heartbeatTimer = window.setInterval(() => {
        // readyState === 1 即 OPEN（避免依赖具体 WebSocket 实现上的静态常量）
        if (this.socket?.readyState === 1) this.socket.send('{"type":"ping"}')
      }, 20_000)
      if (wasReconnect) this.reconnectHandlers.forEach((h) => h())
    }
    this.socket.onmessage = (event) => {
      try {
        const raw = JSON.parse(String(event.data)) as Record<string, unknown>
        this.messageHandlers.forEach((h) => h(raw))
      } catch {
        // 非 JSON 消息忽略
      }
    }
    this.socket.onerror = () => this.socket?.close()
    this.socket.onclose = () => {
      if (this.heartbeatTimer) window.clearInterval(this.heartbeatTimer)
      if (this.stopped) {
        this.emitState('disconnected')
        return
      }
      this.emitState(this.everOpened ? 'reconnecting' : 'disconnected')
      const delay = Math.min(30_000, 1_000 * 2 ** this.attempt++) + Math.random() * 500
      this.reconnectTimer = window.setTimeout(() => this.connect(), delay)
    }
  }

  stop(): void {
    this.stopped = true
    if (this.reconnectTimer) window.clearTimeout(this.reconnectTimer)
    if (this.heartbeatTimer) window.clearInterval(this.heartbeatTimer)
    this.socket?.close()
  }
}

/** 模块级单例：整个应用只维护一条 /ws/live 连接 */
export const sharedLiveSocket = new LiveSocket()

/** 判断一条原始 WS 消息是否为后端 LiveEvent（告警/AI/人员/围栏/碰撞/系统） */
export function isLiveEvent(raw: unknown): raw is LiveEvent {
  if (!raw || typeof raw !== 'object' || typeof (raw as { type?: unknown }).type !== 'string') return false
  const type = String((raw as { type: string }).type)
  return (
    type.startsWith('alert.') ||
    type.startsWith('ai.') ||
    type.startsWith('system.') ||
    type.startsWith('person.') ||
    type.startsWith('fence.') ||
    type.startsWith('collision.') ||
    type.startsWith('rule.') ||
    type.startsWith('ops.')
  )
}

/** 判断是否为 AI 相关实时事件 */
export function isAiLiveEvent(raw: unknown): raw is LiveEvent {
  return !!raw && typeof raw === 'object' && String((raw as { type?: unknown }).type ?? '').startsWith('ai.')
}

/** 判断是否为感知域（人员/围栏/防碰撞）实时事件 */
export function isDomainLiveEvent(raw: unknown): raw is LiveEvent {
  if (!raw || typeof raw !== 'object' || typeof (raw as { type?: unknown }).type !== 'string') return false
  const type = String((raw as { type: string }).type)
  return type.startsWith('person.') || type.startsWith('fence.') || type.startsWith('collision.') || type.startsWith('rule.')
}

/**
 * 安全态势首页客户端（兼容旧 stores/safety.ts）：
 * 复用全局单例连接，只消费带 schemaVersion 的快照消息；stop 仅取消自身订阅，不影响其他页面。
 */
export class SafetyLiveClient {
  private readonly disposers: Array<() => void> = []

  constructor(
    private readonly onSnapshot: (snapshot: SafetySnapshot) => void,
    private readonly onState: (state: LiveState) => void,
  ) {}

  connect(): void {
    this.disposers.push(
      sharedLiveSocket.onMessage((raw) => {
        if ('schemaVersion' in raw) this.onSnapshot(raw as unknown as SafetySnapshot)
      }),
    )
    this.disposers.push(
      sharedLiveSocket.onState((s) => {
        this.onState(s === 'connected' ? 'online' : s === 'connecting' ? 'connecting' : 'offline')
      }),
    )
    sharedLiveSocket.connect()
  }

  stop(): void {
    this.disposers.splice(0).forEach((dispose) => dispose())
  }
}
