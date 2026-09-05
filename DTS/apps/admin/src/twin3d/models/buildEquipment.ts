/**
 * 程序化建模：根据设备定义创建 Cesium 三维实体
 * - 筒仓：Cylinder（上下半径 + 高度，材质=状态色）
 * - 龙门吊：门式框架（两立柱+横梁） + 小车 + 吊具（支持三轴动画 updatePose）
 * - 堆场：Rectangle（带高度色块）
 * 所有实体挂状态色材质，支持状态切换时更新颜色
 */
import * as Cesium from 'cesium'
import { STATUS_COLORS, type EquipmentDef } from '../constants'
import { cadToLonLat } from '../geo'

/** 龙门吊三轴姿态（供动画驱动） */
export interface CranePose {
  /** 大车 CAD 位置 */
  gantryX: number
  gantryY: number
  /** 小车相对大车 X 偏移（米） */
  trolleyOffset: number
  /** 吊具相对地面的高度（米） */
  hoistHeight: number
}

/** 设备实体包装 */
export interface BuiltEquipment {
  def: EquipmentDef
  entities: Cesium.Entity[]
  /** 更新状态色 */
  setStatus(status: EquipmentDef['status']): void
  /** 更新龙门吊三轴姿态（仅 gantry_crane 支持） */
  updatePose?(pose: CranePose): void
}

/** CAD 坐标换算为经纬度并转 Cartographic */
function toCarto(cadX: number, cadY: number): Cesium.Cartographic {
  const [lon, lat] = cadToLonLat(cadX, cadY)
  return Cesium.Cartographic.fromDegrees(lon, lat)
}

/** 状态色 → Cesium Color */
function statusColor(status: string, alpha = 1): Cesium.Color {
  return Cesium.Color.fromCssColorString(STATUS_COLORS[status] ?? '#64748B').withAlpha(alpha)
}

/** 状态色 → Cesium 材质 */
function statusMaterial(status: string, alpha = 1): Cesium.ColorMaterialProperty {
  return new Cesium.ColorMaterialProperty(statusColor(status, alpha))
}

/** 经纬度+高度 → Cartesian3 */
function cartoToPos(carto: Cesium.Cartographic, height: number): Cesium.Cartesian3 {
  return Cesium.Cartesian3.fromRadians(carto.longitude, carto.latitude, height)
}

/**
 * 创建筒仓实体（cylinder）
 */
function buildSilo(dataSource: Cesium.CustomDataSource, def: EquipmentDef): Cesium.Entity[] {
  const carto = toCarto(def.cadX, def.cadY)
  const height = def.height ?? 30
  const radius = 8

  const entity = dataSource.entities.add({
    id: def.id,
    position: cartoToPos(carto, 0),
    cylinder: {
      length: height,
      topRadius: radius,
      bottomRadius: radius,
      material: statusMaterial(def.status),
      outline: true,
      outlineColor: new Cesium.ConstantProperty(Cesium.Color.WHITE.withAlpha(0.6)),
      numberOfVerticalLines: 16,
    },
    properties: { type: def.type, name: def.name },
  })
  return [entity]
}

/**
 * 创建龙门吊实体（门式框架 + 小车 + 吊具，支持三轴动画）
 */
function buildGantryCrane(
  dataSource: Cesium.CustomDataSource,
  def: EquipmentDef,
): { entities: Cesium.Entity[]; updatePose: (pose: CranePose) => void } {
  const width = 30 // 跨距
  const height = def.height ?? 25
  const gateCarto = toCarto(def.cadX, def.cadY)

  // ── 立柱1、立柱2、横梁（随大车整体移动）──
  const leg1 = dataSource.entities.add({
    id: `${def.id}-leg1`,
    position: cartoToPos(gateCarto, height / 2),
    box: { dimensions: new Cesium.Cartesian3(1, 1, height), material: statusMaterial(def.status) },
    properties: { parent: def.id, type: def.type, name: def.name },
  })
  const leg2Carto = toCarto(def.cadX + width, def.cadY)
  const leg2 = dataSource.entities.add({
    id: `${def.id}-leg2`,
    position: cartoToPos(leg2Carto, height / 2),
    box: { dimensions: new Cesium.Cartesian3(1, 1, height), material: statusMaterial(def.status) },
    properties: { parent: def.id, type: def.type, name: def.name },
  })
  const beamCarto = toCarto(def.cadX + width / 2, def.cadY)
  const beam = dataSource.entities.add({
    id: `${def.id}-beam`,
    position: cartoToPos(beamCarto, height + 1),
    box: { dimensions: new Cesium.Cartesian3(width, 1.5, 1.5), material: statusMaterial(def.status) },
    properties: { parent: def.id, type: def.type, name: def.name },
  })

  // ── 小车（横梁上可移动）──
  const trolley = dataSource.entities.add({
    id: `${def.id}-trolley`,
    position: cartoToPos(gateCarto, height + 2.5),
    box: { dimensions: new Cesium.Cartesian3(3, 2, 2), material: statusMaterial(def.status) },
    properties: { parent: def.id, type: def.type, name: def.name },
  })

  // ── 吊具（小车下可升降）──
  const hoist = dataSource.entities.add({
    id: `${def.id}-hoist`,
    position: cartoToPos(gateCarto, 12),
    box: { dimensions: new Cesium.Cartesian3(2, 2, 1.5), material: statusMaterial(def.status) },
    properties: { parent: def.id, type: def.type, name: def.name },
  })

  /** 更新三轴姿态：大车位置 + 小车偏移 + 吊具高度 */
  function updatePose(pose: CranePose) {
    const carto = toCarto(pose.gantryX, pose.gantryY)
    const trolleyCarto = toCarto(pose.gantryX + pose.trolleyOffset, pose.gantryY)
    const hoistCarto = toCarto(pose.gantryX + pose.trolleyOffset, pose.gantryY)

    // 大车部件
    leg1.position = new Cesium.ConstantPositionProperty(cartoToPos(carto, height / 2))
    leg2.position = new Cesium.ConstantPositionProperty(
      cartoToPos(toCarto(pose.gantryX + width, pose.gantryY), height / 2),
    )
    beam.position = new Cesium.ConstantPositionProperty(
      cartoToPos(toCarto(pose.gantryX + width / 2, pose.gantryY), height + 1),
    )
    // 小车
    trolley.position = new Cesium.ConstantPositionProperty(cartoToPos(trolleyCarto, height + 2.5))
    // 吊具（高度 = hoistHeight）
    hoist.position = new Cesium.ConstantPositionProperty(cartoToPos(hoistCarto, pose.hoistHeight))
  }

  return {
    entities: [leg1, leg2, beam, trolley, hoist],
    updatePose,
  }
}

/**
 * 创建堆场色块实体（rectangle）
 */
function buildYard(dataSource: Cesium.CustomDataSource, def: EquipmentDef): Cesium.Entity[] {
  const [lon, lat] = cadToLonLat(def.cadX, def.cadY)
  const w = 0.005
  const h = 0.004
  const entity = dataSource.entities.add({
    id: def.id,
    rectangle: {
      coordinates: Cesium.Rectangle.fromDegrees(lon - w / 2, lat - h / 2, lon + w / 2, lat + h / 2),
      height: 0,
      material: statusMaterial(def.status, 0.5),
      outline: true,
      outlineColor: new Cesium.ConstantProperty(statusColor(def.status)),
    },
    properties: { type: def.type, name: def.name },
  })
  return [entity]
}

/** 创建设备实体入口 */
export function buildEquipment(
  dataSource: Cesium.CustomDataSource,
  def: EquipmentDef,
): BuiltEquipment {
  let updatePose: ((pose: CranePose) => void) | undefined
  let entities: Cesium.Entity[]

  switch (def.type) {
    case 'silo':
      entities = buildSilo(dataSource, def)
      break
    case 'gantry_crane': {
      const crane = buildGantryCrane(dataSource, def)
      entities = crane.entities
      updatePose = crane.updatePose
      break
    }
    case 'yard':
      entities = buildYard(dataSource, def)
      break
    default:
      entities = []
  }

  const setStatus = (status: EquipmentDef['status']) => {
    def.status = status
    for (const entity of entities) {
      if (entity.cylinder) entity.cylinder.material = statusMaterial(status)
      if (entity.box) entity.box.material = statusMaterial(status)
      if (entity.rectangle) {
        entity.rectangle.material = statusMaterial(status, 0.5)
        entity.rectangle.outlineColor = new Cesium.ConstantProperty(statusColor(status))
      }
    }
  }

  return { def, entities, setStatus, updatePose }
}
