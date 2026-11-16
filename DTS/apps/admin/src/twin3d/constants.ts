/**
 * 三维建模常量：状态色映射（对齐统一基线表18）
 * 设备状态：运行绿 / 待机黄 / 故障红 / 离线灰 / 维护蓝
 */
import { SITE_LAYOUT_V4 } from './siteLayoutV4'

export type DeviceStatusCode = 'ok' | 'standby' | 'fault' | 'offline' | 'maintenance'

export const STATUS_COLORS: Record<DeviceStatusCode, string> = {
  ok: '#34D399', // 运行正常/完成
  standby: '#F5B84C', // 待机/一般告警
  fault: '#EF4444', // 故障/紧急
  offline: '#64748B', // 离线/未知
  maintenance: '#2B6FE0', // 维护
}

/** 设备状态中文名 */
export const STATUS_NAMES: Record<string, string> = {
  ok: '运行',
  standby: '待机',
  fault: '故障',
  offline: '离线',
  maintenance: '维护',
}

/** 设备类型定义（对齐架构图 1+5+2 与施工图 19 项设备总表） */
export type EquipmentKind =
  | 'gantry_crane'
  | 'bridge_crane'
  | 'mobile_tipper'
  | 'silo_loader'
  | 'elevator'
  | 'silo'
  | 'belt'
  | 'yard'
  | 'weighbridge'

export interface EquipmentDef {
  id: string
  name: string
  /** 设备类型 */
  type: EquipmentKind
  /** CAD 坐标（米，已减去统一基准偏移） */
  cadX: number
  cadY: number
  /** 高度（米） */
  height?: number
  status: DeviceStatusCode
  /** 详情参数 */
  params: Record<string, string>
}

/** 场站地理基准原点（来自 给大连大.dwg CGCS2000 映射） */
export const YARD_CENTER: [number, number] = [
  SITE_LAYOUT_V4.geoOrigin[0],
  SITE_LAYOUT_V4.geoOrigin[1],
]

/** 状态过渡动画时长（毫秒） */
export const STATUS_TRANSITION_MS = 500
