/**
 * 设备状态与健康遥测 store（对接 S3 EHMS + 厂家 PLC 只读实时数据）
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { STATUS_NAMES } from '@/twin3d/constants'

export type DeviceStatusCode = 'ok' | 'standby' | 'fault' | 'offline' | 'maintenance'

export interface DeviceTelemetry {
  loadTons: number
  windSpeedMps: number
  hydraulicPsi: number
  pneumaticKpa: number
  runHours: number
  todayCycles: number
  maintenanceDue: string
}

export interface EhmsHealthSnapshot {
  healthScore: number
  healthLevel: 'healthy' | 'attention' | 'risk'
  riskSubsystem: string
  riskReason: string
  predictedRulDays: number
}

export interface DeviceState {
  id: string
  name: string
  status: DeviceStatusCode
  updatedAt: string
  telemetry?: DeviceTelemetry
  ehms?: EhmsHealthSnapshot
}

export function buildDefaultDeviceSnapshot(id: string, name: string, status: DeviceStatusCode): DeviceState {
  const hash = id.split('').reduce((acc, ch) => acc + ch.charCodeAt(0), 0)
  const isTipper = id.startsWith('TIPPER')
  const isCrane = id.startsWith('CR-') || id.startsWith('BC-')
  const isLoader = id.startsWith('LOADER')
  const healthBase = status === 'fault' ? 61 : status === 'maintenance' ? 74 : status === 'standby' ? 88 : 91 + (hash % 8)

  const riskSubsystem = isTipper
    ? '液压翻转锁紧机构'
    : isCrane
      ? id.startsWith('BC-')
        ? '小车起升制动与磁吸控制器'
        : '大车行走变频与起升钢丝绳'
      : isLoader
        ? '电动回转阀与伸缩波纹溜管'
        : '高原电机轴承与料斗料位计'

  return {
    id,
    name,
    status,
    updatedAt: new Date().toISOString(),
    telemetry: {
      loadTons: status === 'ok' ? Number((14 + (hash % 18) * 1.1).toFixed(1)) : 0,
      windSpeedMps: Number((3.2 + (hash % 5) * 0.6).toFixed(1)),
      hydraulicPsi: isTipper ? 18.6 : isCrane ? 14.2 : 8.5,
      pneumaticKpa: isLoader ? 615 : 540,
      runHours: 1180 + (hash % 940),
      todayCycles: 6 + (hash % 17),
      maintenanceDue: isCrane ? '距下次维保 14 天' : '距下次维保 26 天',
    },
    ehms: {
      healthScore: healthBase,
      healthLevel: healthBase < 75 ? 'risk' : healthBase < 86 ? 'attention' : 'healthy',
      riskSubsystem,
      riskReason: healthBase < 86 ? `${riskSubsystem}温升波动（建议计划维保复检）` : '运行平稳 · 机构BOM状态良好',
      predictedRulDays: healthBase < 75 ? 12 : healthBase < 86 ? 38 : 110 + (hash % 60),
    },
  }
}

export const useDeviceStore = defineStore('device', () => {
  const devices = ref<Record<string, DeviceState>>({})
  const lastUpdate = ref('')

  function updateDevice(id: string, status: DeviceStatusCode, telemetryPatch?: Partial<DeviceTelemetry>) {
    const now = new Date().toISOString()
    const existing = devices.value[id]
    const base = existing ?? buildDefaultDeviceSnapshot(id, id, status)
    devices.value[id] = {
      ...base,
      status,
      updatedAt: now,
      telemetry: telemetryPatch
        ? { ...(base.telemetry ?? buildDefaultDeviceSnapshot(id, base.name, status).telemetry!), ...telemetryPatch }
        : base.telemetry,
    }
    lastUpdate.value = now
  }

  function batchUpdate(list: Array<Pick<DeviceState, 'id' | 'name' | 'status' | 'updatedAt'> & Partial<DeviceState>>) {
    for (const item of list) {
      const existing = devices.value[item.id]
      const defaults = buildDefaultDeviceSnapshot(item.id, item.name, item.status)
      devices.value[item.id] = {
        ...defaults,
        ...existing,
        ...item,
        telemetry: item.telemetry ?? existing?.telemetry ?? defaults.telemetry,
        ehms: item.ehms ?? existing?.ehms ?? defaults.ehms,
      }
    }
    lastUpdate.value = new Date().toISOString()
  }

  function getStatusName(id: string): string {
    const st = devices.value[id]?.status
    return st ? (STATUS_NAMES[st] ?? st) : '未知'
  }

  const ehmsTopRisks = computed(() =>
    Object.values(devices.value)
      .filter((d) => d.ehms)
      .sort((a, b) => (a.ehms?.healthScore ?? 100) - (b.ehms?.healthScore ?? 100))
      .slice(0, 6),
  )

  function getSummary() {
    const counts: Record<string, number> = { ok: 0, standby: 0, fault: 0, offline: 0, maintenance: 0 }
    for (const d of Object.values(devices.value)) {
      counts[d.status] = (counts[d.status] ?? 0) + 1
    }
    return { total: Object.keys(devices.value).length, counts }
  }

  return { devices, lastUpdate, ehmsTopRisks, updateDevice, batchUpdate, getStatusName, getSummary }
})
