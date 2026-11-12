/** 装卸区状态（后端 /api/v1/zones）。未收到上报时字段为 null、online=false，表示"未知"而非"无人/无车"。 */
export interface ZoneState {
  code: string
  name: string
  /** A 区=限位开关，B 区=位置传感器 */
  sensorType: string
  /** 仅 A 区有意义：作业面上是否有人作业 */
  personnelWorking: boolean | null
  /** 仅 B 区有意义：车位是否有车 */
  vehiclePresent: boolean | null
  /** 仅 B 区有意义：位置传感器测距（mm） */
  distanceMm: number | null
  signal: string
  updatedAt: string | null
  online: boolean
}

/** 区域实时在场人数（固定作业区按矩形归属；fence=已生效临时围栏按多边形判定）。 */
export interface ZonePresence {
  code: string
  name: string
  /** fixed=固定作业区；fence=已生效临时围栏 */
  kind: 'fixed' | 'fence'
  personCount: number
  /** 仅围栏：处于隔离带（缓冲区）但未入核心区的人数 */
  bufferCount: number
  /** 仅围栏：生命周期中文标签 */
  fenceStatus: string | null
  /** 仅围栏：围栏业务编号 */
  fenceId: string | null
}
