/**
 * 告警与安全联锁回执 store（承接 S4 安全联锁监控 + S3 EHMS + S2 超储预警）
 */
import { ref } from 'vue'
import { defineStore } from 'pinia'

export interface InterlockReceipt {
  sourceSystem: 'S4-SCS' | 'S3-EHMS' | 'S1-PSMS' | 'S2-WMS'
  safetyDistanceM?: number
  actions: Array<
    | '声光报警'
    | '大屏弹窗'
    | '信息通知'
    | '联锁停机'
    | '核查厂家PLC与视频'
    | '查看厂家状态及视频'
    | '转S1装卸工艺管理确认'
    | '转S1装卸工艺管理处置'
  >
}

export interface AlertItem {
  id: string
  level: 'info' | 'warning' | 'critical'
  targetId: string
  targetName: string
  message: string
  time: string
  handled: boolean
  interlockReceipt?: InterlockReceipt
}

export const useAlertStore = defineStore('alert', () => {
  const alerts = ref<AlertItem[]>([])
  const unhandledCount = ref(0)

  function addAlert(alert: Omit<AlertItem, 'time' | 'handled'>) {
    const receipt: InterlockReceipt = alert.interlockReceipt ?? {
      sourceSystem: alert.level === 'critical' ? 'S4-SCS' : 'S2-WMS',
      actions: alert.level === 'critical' ? ['声光报警', '大屏弹窗', '信息通知', '联锁停机'] : ['大屏弹窗', '信息通知'],
    }
    const existing = alerts.value.find((a) => a.id === alert.id)
    if (existing) {
      existing.level = alert.level
      existing.targetId = alert.targetId
      existing.targetName = alert.targetName
      existing.message = alert.message
      existing.interlockReceipt = receipt
      existing.time = new Date().toISOString()
    } else {
      alerts.value.unshift({
        ...alert,
        interlockReceipt: receipt,
        time: new Date().toISOString(),
        handled: false,
      })
      if (alerts.value.length > 100) alerts.value.length = 100
    }
    unhandledCount.value = alerts.value.filter((a) => !a.handled).length
  }

  function markHandled(id: string) {
    const alert = alerts.value.find((a) => a.id === id)
    if (alert) {
      alert.handled = true
      unhandledCount.value = alerts.value.filter((a) => !a.handled).length
    }
  }

  function clear() {
    alerts.value = []
    unhandledCount.value = 0
  }

  return { alerts, unhandledCount, addAlert, markHandled, clear }
})
