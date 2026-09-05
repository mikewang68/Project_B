/** 开发阶段的离线数据流；真实环境由 wsClient 订阅数据底座。 */
import type { useDeviceStore } from '@/stores/device'
import type { useAlertStore } from '@/stores/alert'
import { MOCK_EQUIPMENT } from '@/twin3d/mock/mockData'

const STATUSES: Array<'ok' | 'standby' | 'fault' | 'offline' | 'maintenance'> = [
  'ok', 'ok', 'ok', 'standby', 'fault', 'maintenance',
]

const DEVICE_NAMES = Object.fromEntries(MOCK_EQUIPMENT.map((item) => [item.id, item.name]))

const ALERT_MESSAGES = [
  '设备温度超限',
  '围栏侵入告警',
  '设备振动异常',
  '作业超时提醒',
  '料位接近阈值',
]

export class MockDataService {
  private deviceTimer: ReturnType<typeof setInterval> | null = null
  private alertTimer: ReturnType<typeof setInterval> | null = null
  private readonly deviceIds = Object.keys(DEVICE_NAMES)
  private readonly deviceStore: ReturnType<typeof useDeviceStore>
  private readonly alertStore: ReturnType<typeof useAlertStore>

  constructor(
    deviceStore: ReturnType<typeof useDeviceStore>,
    alertStore: ReturnType<typeof useAlertStore>,
  ) {
    this.deviceStore = deviceStore
    this.alertStore = alertStore
  }

  start(deviceIntervalMs = 4000, alertIntervalMs = 8000) {
    if (this.deviceTimer || this.alertTimer) return
    this.deviceStore.batchUpdate(
      this.deviceIds.map((id) => ({
        id,
        name: DEVICE_NAMES[id],
        status: 'ok',
        updatedAt: new Date().toISOString(),
      })),
    )

    this.deviceTimer = setInterval(() => {
      const id = this.deviceIds[Math.floor(Math.random() * this.deviceIds.length)]
      const status = STATUSES[Math.floor(Math.random() * STATUSES.length)]
      this.deviceStore.updateDevice(id, status)
    }, deviceIntervalMs)

    this.alertTimer = setInterval(() => {
      const targetId = this.deviceIds[Math.floor(Math.random() * this.deviceIds.length)]
      this.alertStore.addAlert({
        id: `ALT-${Date.now()}`,
        level: Math.random() > 0.5 ? 'warning' : 'info',
        targetId,
        targetName: DEVICE_NAMES[targetId],
        message: ALERT_MESSAGES[Math.floor(Math.random() * ALERT_MESSAGES.length)],
      })
    }, alertIntervalMs)
  }

  stop() {
    if (this.deviceTimer) clearInterval(this.deviceTimer)
    if (this.alertTimer) clearInterval(this.alertTimer)
    this.deviceTimer = null
    this.alertTimer = null
  }
}
