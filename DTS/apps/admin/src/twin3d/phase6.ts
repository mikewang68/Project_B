import * as Cesium from 'cesium'
import { cadToLonLat } from './geo'

import { SITE_LAYOUT_V4 } from './siteLayoutV4'

export type CargoKind = 'steel' | 'fly_ash' | 'cement' | 'coil'
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

const BAY07 = SITE_LAYOUT_V4.rebar.bays[6]
const SILO01 = SITE_LAYOUT_V4.ash.silos[0]
const SILO09 = SITE_LAYOUT_V4.ash.silos[8]

export const MOCK_CARGO: CargoItem[] = [
  { id: 'CARGO-01', name: '钢筋仓库（1~13跨总库存）', kind: 'steel', cadX: SITE_LAYOUT_V4.rebar.warehouse.minX + 196, cadY: BAY07.cadY, quantity: 14200, capacity: 20000, warning: false },
  { id: 'CARGO-02', name: '水泥筒仓组（8×3000T）', kind: 'cement', cadX: 3781, cadY: 1561, quantity: 15600, capacity: 24000, warning: false },
  { id: 'CARGO-04', name: '粉煤灰筒仓组（32×3000T）', kind: 'fly_ash', cadX: SILO09.cadX + 90, cadY: SILO09.cadY, quantity: 58400, capacity: 96000, warning: false },
  { id: 'CARGO-03', name: '钢筋 07 跨仓位（20t磁吸）', kind: 'steel', cadX: BAY07.cadX, cadY: BAY07.cadY, quantity: 1180, capacity: 1600, warning: false },
]

export const MOCK_PERSONNEL: PersonnelItem[] = [
  { id: 'SCS-ZONE-01', name: '2#门吊-07跨跨间交接防撞联锁区', role: 'S4 光电测距 (预警15m/停6m)', cadX: SITE_LAYOUT_V4.rebar.gantries[1].cadX, cadY: 1650, status: 'normal', enteredAt: '联锁在线' },
  { id: 'SCS-ZONE-02', name: '1#卸料室与翻箱机协同电子围栏', role: 'S4 门吊吊具离空闭锁', cadX: SITE_LAYOUT_V4.ash.unloadingComplexes[0].cadX, cadY: 1532, status: 'normal', enteredAt: '联锁在线' },
  { id: 'SCS-ZONE-03', name: 'SILO-01 库底定量散装电子围栏', role: 'S4 静电接地与红外围栏', cadX: SILO01.cadX, cadY: 1568, status: 'warning', enteredAt: '接地闭锁' },
]

export const MOCK_OPERATIONS: OperationTask[] = [
  { id: 'OP-240801', name: '钢材联合吊装入库（货4→转接区B→07跨）', equipment: 'CR-02 / BC-07', progress: 0, status: 'queued' },
  { id: 'OP-240802', name: '水泥散料翻箱入仓与库底定量装车', equipment: 'CR-BULK-01 / TIPPER-01 / LOADER-01', progress: 0, status: 'queued' },
  { id: 'OP-240803', name: '07跨跨间交接等待（防撞驻车 · 待S1调度确认）', equipment: 'CR-02 ↔ BC-07', progress: 65, status: 'warning' },
]

const CARGO_COLORS: Record<CargoKind, Cesium.Color> = {
  steel: Cesium.Color.fromCssColorString('#60A5FA'),
  coil: Cesium.Color.fromCssColorString('#38BDF8'),
  fly_ash: Cesium.Color.fromCssColorString('#F5B84C'),
  cement: Cesium.Color.fromCssColorString('#34D399'),
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
      const position = Cesium.Cartesian3.fromDegrees(lon, lat, 1.6)
      const color = PERSONNEL_COLORS[item.status]
      let entity = this.personnel.get(item.id)
      if (!entity) {
        entity = this.dataSource.entities.add({ id: item.id })
        this.personnel.set(item.id, entity)
      }
      entity.position = new Cesium.ConstantPositionProperty(position)
      entity.box = new Cesium.BoxGraphics({
        dimensions: new Cesium.Cartesian3(24, 14, 3.2),
        material: color.withAlpha(0.16),
        outline: true,
        outlineColor: color.withAlpha(0.85),
        distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, 480),
      })
      entity.label = new Cesium.LabelGraphics({
        text: `${item.name}\n${item.role}`,
        font: '600 12px Microsoft YaHei',
        fillColor: Cesium.Color.WHITE,
        outlineColor: Cesium.Color.BLACK,
        outlineWidth: 2,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        pixelOffset: new Cesium.Cartesian2(0, -24),
        distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, 360),
      })
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
