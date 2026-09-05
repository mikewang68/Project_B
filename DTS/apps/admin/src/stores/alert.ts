/**
 * 告警 store（模块B：告警联动）
 * 承接 SMS/设备告警，驱动三维场景高亮与弹窗
 */
import { ref } from 'vue'
import { defineStore } from 'pinia'

export interface AlertItem {
  id: string
  /** 告警级别：一般/严重/紧急 */
  level: 'info' | 'warning' | 'critical'
  /** 关联设备/对象 */
  targetId: string
  targetName: string
  message: string
  time: string
  /** 处置状态 */
  handled: boolean
}

export const useAlertStore = defineStore('alert', () => {
  const alerts = ref<AlertItem[]>([])
  const unhandledCount = ref(0)

  function addAlert(alert: Omit<AlertItem, 'time' | 'handled'>) {
    alerts.value.unshift({
      ...alert,
      time: new Date().toISOString(),
      handled: false,
    })
    unhandledCount.value = alerts.value.filter((a) => !a.handled).length
    // 最多保留 100 条
    if (alerts.value.length > 100) alerts.value.length = 100
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
