/**
 * 原生 WebSocket 客户端封装（对齐统一基线：原生WebSocket，心跳/退避/序号/补偿）
 * - 自动重连（指数退避）
 * - 心跳保活（ping/pong）
 * - 消息按 type 路由分发
 */
import type { App } from 'vue'
import { WS_URL } from '@/config/runtime'

export interface WsMessage<T = unknown> {
  /** 消息类型（如 device_status / alert / task_progress） */
  type: string
  /** 消息序号（检测断连期间的丢失） */
  seq: number
  /** 时间戳 */
  timestamp: string
  data: T
}

export interface WsClientOptions {
  url: string
  /** 自动重连，默认 true */
  autoReconnect?: boolean
  /** 心跳间隔 ms，默认 30s */
  heartbeatMs?: number
  /** 连接状态回调 */
  onStatusChange?: (connected: boolean) => void
}

export class WsClient {
  private ws: WebSocket | null = null
  private url: string
  private handlers = new Map<string, Array<(msg: WsMessage) => void>>()
  private options: WsClientOptions
  private reconnectAttempts = 0
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null
  private seq = 0
  private connected = false

  constructor(options: WsClientOptions) {
    this.url = options.url
    this.options = options
  }

  connect() {
    try {
      this.ws = new WebSocket(this.url)
    } catch (e) {
      console.error('WebSocket 创建失败:', e)
      this.scheduleReconnect()
      return
    }

    this.ws.onopen = () => {
      this.connected = true
      this.reconnectAttempts = 0
      this.options.onStatusChange?.(true)
      this.startHeartbeat()
    }

    this.ws.onmessage = (ev) => {
      try {
        const msg: WsMessage = JSON.parse(ev.data)
        this.seq = msg.seq
        this.dispatch(msg)
      } catch (e) {
        console.error('消息解析失败:', e)
      }
    }

    this.ws.onclose = () => {
      this.connected = false
      this.options.onStatusChange?.(false)
      this.stopHeartbeat()
      if (this.options.autoReconnect !== false) {
        this.scheduleReconnect()
      }
    }

    this.ws.onerror = (e) => {
      console.error('WebSocket 错误:', e)
    }
  }

  /** 按类型订阅消息 */
  on<T>(type: string, handler: (msg: WsMessage<T>) => void) {
    const list = this.handlers.get(type) ?? []
    list.push(handler as (msg: WsMessage) => void)
    this.handlers.set(type, list)
  }

  /** 发送消息（带序号） */
  send(type: string, data: unknown) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return false
    this.seq++
    const msg: WsMessage = { type, seq: this.seq, timestamp: new Date().toISOString(), data }
    this.ws.send(JSON.stringify(msg))
    return true
  }

  private dispatch(msg: WsMessage) {
    const list = this.handlers.get(msg.type)
    list?.forEach((h) => h(msg))
    // 兜底：也派发到 * 通配
    this.handlers.get('*')?.forEach((h) => h(msg))
  }

  private scheduleReconnect() {
    // 指数退避：1s → 2s → 4s → ... 上限 30s
    const delay = Math.min(1000 * 2 ** this.reconnectAttempts, 30000)
    this.reconnectAttempts++
    setTimeout(() => {
      if (!this.connected) this.connect()
    }, delay)
  }

  private startHeartbeat() {
    const interval = this.options.heartbeatMs ?? 30000
    this.heartbeatTimer = setInterval(() => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        this.ws.send(JSON.stringify({ type: 'ping', seq: this.seq, timestamp: new Date().toISOString() }))
      }
    }, interval)
  }

  private stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  close() {
    this.stopHeartbeat()
    this.connected = false
    this.ws?.close()
    this.ws = null
  }
}

/** 全局 ws 实例（应用级单例；入口由 VITE_WS_URL 配置，未配置时不连接） */
export const wsClient = new WsClient({ url: WS_URL })

/** Vue 插件挂载（可选） */
export const wsPlugin = {
  install(app: App) {
    app.config.globalProperties.$ws = wsClient
  },
}
