import * as Cesium from 'cesium'
import { cadToLonLat } from './geo'

export type CargoKind = 'steel' | 'fly_ash' | 'cement'
export type PersonnelStatus = 'normal' | 'warning' | 'restricted'
export type OperationStatus = 'running' | 'queued' | 'warning' | 'done'

export interface CargoItem {
  id: string
  name: string
  kind: CargoKind
  cadX: number
  cadY: number
  quantity: number
  capacity: number
  warning: boolean
}

export interface PersonnelItem {
  id: string
  name: string
  role: string
  cadX: number
  cadY: number
  status: PersonnelStatus
  enteredAt: string
}

export interface OperationTask {
  id: string
  name: string
  equipment: string
  progress: number
  status: OperationStatus
}

export const MOCK_CARGO: CargoItem[] = [
  { id: 'CARGO-01', name: '钢筋露天仓位汇总', kind: 'steel', cadX: 3741.1065, cadY: 1710.019, quantity: 12500, capacity: 16000, warning: false },
  { id: 'CARGO-02', name: '物料加工批次 A', kind: 'fly_ash', cadX: 3688, cadY: 1821, quantity: 800, capacity: 1200, warning: false },
  { id: 'CARGO-03', name: '钢筋仓位 07', kind: 'steel', cadX: 3741.1065, cadY: 1710.019, quantity: 840, capacity: 1200, warning: false },
]

export const MOCK_PERSONNEL: PersonnelItem[] = [
  { id: 'P-01', name: '张建国', role: '龙门吊司机', cadX: 3820, cadY: 1710, status: 'normal', enteredAt: '07:42' },
  { id: 'P-02', name: '李敏', role: '现场调度', cadX: 3690, cadY: 1816, status: 'normal', enteredAt: '07:55' },
  { id: 'P-03', name: '王磊', role: '安全员', cadX: 3648, cadY: 1778, status: 'warning', enteredAt: '08:03' },
]

export const MOCK_OPERATIONS: OperationTask[] = [
  { id: 'OP-240801', name: '钢材卸车', equipment: 'CR-02', progress: 62, status: 'running' },
  { id: 'OP-240802', name: '物料加工准备', equipment: 'PROC-01', progress: 84, status: 'running' },
  { id: 'OP-240803', name: '钢筋料位复核', equipment: 'REBAR-BAY-07', progress: 15, status: 'queued' },
]

const CARGO_COLORS: Record<CargoKind, Cesium.Color> = {
  steel: Cesium.Color.fromCssColorString('#60A5FA'),
  fly_ash: Cesium.Color.fromCssColorString('#F5B84C'),
  cement: Cesium.Color.fromCssColorString('#E5E7EB'),
}

const PERSONNEL_COLORS: Record<PersonnelStatus, Cesium.Color> = {
  normal: Cesium.Color.fromCssColorString('#34D399'),
  warning: Cesium.Color.fromCssColorString('#F5B84C'),
  restricted: Cesium.Color.fromCssColorString('#EF4444'),
}

/** Phase 6 mock layer: cargo inventory, personnel locations, and operation paths. */
export class Phase6LayerController {
  private readonly viewer: Cesium.Viewer
  private readonly dataSource = new Cesium.CustomDataSource('dt-phase6')
  private readonly cargo = new Map<string, Cesium.Entity>()
  private readonly personnel = new Map<string, Cesium.Entity>()
  private readonly trails = new Map<string, Cesium.Entity>()

  constructor(viewer: Cesium.Viewer) {
    this.viewer = viewer
    viewer.dataSources.add(this.dataSource)
  }

  syncCargo(items: CargoItem[]) {
    for (const item of items) {
      const [lon, lat] = cadToLonLat(item.cadX, item.cadY)
      const ratio = item.quantity / item.capacity
      const height = 3 + ratio * 12
      const color = item.warning ? Cesium.Color.fromCssColorString('#EF4444') : CARGO_COLORS[item.kind]
      const position = Cesium.Cartesian3.fromDegrees(lon, lat, height / 2)
      let entity = this.cargo.get(item.id)
      if (!entity) {
        entity = this.dataSource.entities.add({ id: item.id })
        this.cargo.set(item.id, entity)
      }
      entity.position = new Cesium.ConstantPositionProperty(position)
      entity.cylinder = new Cesium.CylinderGraphics({
        length: height,
        topRadius: 10,
        bottomRadius: 12,
        material: color.withAlpha(0.65),
        outline: true,
        outlineColor: color,
        distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, 360),
      })
      entity.label = new Cesium.LabelGraphics({
        text: `${item.name}\n${item.quantity.toLocaleString()}t`,
        font: '13px Microsoft YaHei',
        fillColor: Cesium.Color.WHITE,
        outlineColor: Cesium.Color.BLACK,
        outlineWidth: 2,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        pixelOffset: new Cesium.Cartesian2(0, -32),
        distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, 300),
      })
    }
    this.viewer.scene.requestRender()
  }

  syncPersonnel(items: PersonnelItem[]) {
    for (const item of items) {
      const [lon, lat] = cadToLonLat(item.cadX, item.cadY)
      const position = Cesium.Cartesian3.fromDegrees(lon, lat, 2)
      const color = PERSONNEL_COLORS[item.status]
      let entity = this.personnel.get(item.id)
      if (!entity) {
        entity = this.dataSource.entities.add({ id: item.id })
        this.personnel.set(item.id, entity)
      }
      entity.position = new Cesium.ConstantPositionProperty(position)
      entity.point = new Cesium.PointGraphics({
        pixelSize: 12,
        color,
        outlineColor: Cesium.Color.WHITE,
        outlineWidth: 2,
        distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, 260),
      })
      entity.label = new Cesium.LabelGraphics({
        text: `${item.name} · ${item.role}`,
        font: '12px Microsoft YaHei',
        fillColor: Cesium.Color.WHITE,
        outlineColor: Cesium.Color.BLACK,
        outlineWidth: 2,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        pixelOffset: new Cesium.Cartesian2(0, -20),
        distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, 220),
      })
      const previous = this.trails.get(item.id)
      if (previous?.polyline?.positions) {
        const points = previous.polyline.positions.getValue(this.viewer.clock.currentTime) ?? []
        points.push(position)
        previous.polyline.positions = points.slice(-12)
      } else {
        const trail = this.dataSource.entities.add({
          id: `${item.id}-trail`,
          polyline: { positions: [position], width: 2, material: color.withAlpha(0.7) },
        })
        this.trails.set(item.id, trail)
      }
    }
    this.viewer.scene.requestRender()
  }

  setCargoVisible(visible: boolean) {
    for (const entity of this.cargo.values()) entity.show = visible
  }

  setPersonnelVisible(visible: boolean) {
    for (const entity of [...this.personnel.values(), ...this.trails.values()]) entity.show = visible
  }

  destroy() {
    this.viewer.dataSources.remove(this.dataSource)
  }
}
