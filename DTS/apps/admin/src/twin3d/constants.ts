/**
 * 三维建模常量：状态色映射（对齐统一基线表18）
 * 设备状态：运行绿 / 待机黄 / 故障红 / 离线灰 / 维护蓝
 */
export const STATUS_COLORS: Record<string, string> = {
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

/** 设备类型定义 */
export interface EquipmentDef {
  id: string
  name: string
  /** 设备类型 */
  type: 'gantry_crane' | 'silo' | 'belt' | 'yard'
  /** CAD 坐标（米） */
  cadX: number
  cadY: number
  /** 高度（米） */
  height?: number
  status: keyof typeof STATUS_COLORS
  /** 详情参数 */
  params: Record<string, string>
}

/** 场站中心（占位经纬度，与 Phase2 一致） */
export const YARD_CENTER: [number, number] = [91.0, 29.0]

/** 状态过渡动画时长（毫秒） */
export const STATUS_TRANSITION_MS = 500
