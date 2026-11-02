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
