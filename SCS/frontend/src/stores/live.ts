import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { isLiveEvent, sharedLiveSocket, type LiveConnection, type LiveEvent } from '@/api/live'

type AlertEventListener = (event: LiveEvent) => void
type ReconnectedListener = () => void

/**
 * 全局实时连接 store：统一管理 /ws/live 单例连接的状态展示与事件分发。
 * WS 只通知“数据变了”，各页面收到事件后自行 REST 重拉；重连成功后统一触发一次全量刷新。
 */
export const useLiveStore = defineStore('live', () => {
  const status = ref<LiveConnection>('connecting')
  const lastEventAt = ref('')

  const alertListeners = new Set<AlertEventListener>()
  const reconnectedListeners = new Set<ReconnectedListener>()
  let started = false

  const connected = computed(() => status.value === 'connected')
  /** 移动端 / 大屏顶栏文案状态：在线 / 重连中 / 已断开 */
  const online = computed(() => status.value === 'connected')
  const label = computed(() => {
    switch (status.value) {
      case 'connected': return '实时连接正常'
      case 'connecting': return '正在连接'
      case 'reconnecting': return '实时连接中断，正在重连'
      default: return '实时连接已断开'
    }
  })

  function start(): void {
    if (started) {
      sharedLiveSocket.connect()
      return
    }
    started = true
    sharedLiveSocket.onState((s) => { status.value = s })
    sharedLiveSocket.onMessage((raw) => {
      if (isLiveEvent(raw)) {
        lastEventAt.value = raw.ts ?? new Date().toISOString()
        alertListeners.forEach((listener) => listener(raw as LiveEvent))
      }
    })
    sharedLiveSocket.onReconnected(() => {
      // 重连后主动全量同步，避免断线期间遗漏增量
      reconnectedListeners.forEach((listener) => listener())
    })
    sharedLiveSocket.connect()
  }

  /** 订阅告警实时事件，返回取消订阅函数 */
  function onAlertEvent(listener: AlertEventListener): () => void {
    alertListeners.add(listener)
    return () => alertListeners.delete(listener)
  }

  /** 订阅“重连成功”事件（用于 REST 全量 refresh） */
  function onReconnected(listener: ReconnectedListener): () => void {
    reconnectedListeners.add(listener)
    return () => reconnectedListeners.delete(listener)
  }

  return { status, lastEventAt, connected, online, label, start, onAlertEvent, onReconnected }
})
