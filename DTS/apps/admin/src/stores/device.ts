/**
 * 设备状态 store（模块B：设备运行状态）
 * 承接实时数据（WebSocket）驱动，与三维场景联动
 */
import { ref } from 'vue'
import { defineStore } from 'pinia'
import { STATUS_NAMES } from '@/twin3d/constants'

export interface DeviceState {
  id: string
  name: string
  status: 'ok' | 'standby' | 'fault' | 'offline' | 'maintenance'
  updatedAt: string
}

export const useDeviceStore = defineStore('device', () => {
  /** 设备状态表（id → 状态） */
  const devices = ref<Record<string, DeviceState>>({})
  /** 最近更新时间 */
  const lastUpdate = ref('')

  /** 更新单个设备状态（从 WebSocket/数据底座推送） */
  function updateDevice(id: string, status: DeviceState['status']) {
    const now = new Date().toISOString()
    const existing = devices.value[id]
    devices.value[id] = {
      id,
      name: existing?.name ?? id,
      status,
      updatedAt: now,
    }
    lastUpdate.value = now
  }

  /** 批量更新（初始化或全量刷新） */
  function batchUpdate(list: DeviceState[]) {
    for (const d of list) {
      devices.value[d.id] = d
    }
    lastUpdate.value = new Date().toISOString()
  }

  /** 获取设备状态中文名 */
  function getStatusName(id: string): string {
    const st = devices.value[id]?.status
    return st ? (STATUS_NAMES[st] ?? st) : '未知'
  }

  /** 状态汇总统计 */
  function getSummary() {
    const counts: Record<string, number> = { ok: 0, standby: 0, fault: 0, offline: 0, maintenance: 0 }
    for (const d of Object.values(devices.value)) {
      counts[d.status] = (counts[d.status] ?? 0) + 1
    }
    return { total: Object.keys(devices.value).length, counts }
  }

  return { devices, lastUpdate, updateDevice, batchUpdate, getStatusName, getSummary }
})
