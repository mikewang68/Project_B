import * as Cesium from 'cesium'
import { STATUS_COLORS, STATUS_NAMES, type DeviceStatusCode, type EquipmentDef } from '../constants'
import { cadToCartesian3 } from '../geo'
import { SITE_LAYOUT_V4 } from '../siteLayoutV4'
import cadBuildingsV54 from '../cadBuildingsV54.json'
import {
  CORE_PLACEMENTS,
  STATION_PLACEMENTS,
  getPlacement,
  getZone,
  type AssetPlacement,
  type PlacementType,
} from '../placements'
import { getModel } from './modelManifest'
import type {
  BridgeCranePose,
  BuiltEquipment,
  CranePose,
  SiloLoaderPose,
  TipperPose,
} from './buildEquipment'

function statusColor(status: DeviceStatusCode): Cesium.Color {
  return Cesium.Color.fromCssColorString(STATUS_COLORS[status] ?? '#64748B')
}

const CAD_X_TO_CESIUM_HEADING_DEG = 90

function orientation(
  position: Cesium.Cartesian3,
  headingDeg: number,
  pitchDeg = 0,
  rollDeg = 0,
): Cesium.Quaternion {
  return Cesium.Transforms.headingPitchRollQuaternion(
    position,
    new Cesium.HeadingPitchRoll(
      Cesium.Math.toRadians(headingDeg + CAD_X_TO_CESIUM_HEADING_DEG),
      Cesium.Math.toRadians(pitchDeg),
      Cesium.Math.toRadians(rollDeg),
    ),
  )
}

const DEFAULT_IBL_FACTOR = new Cesium.Cartesian2(1.0, 1.0)
const DEFAULT_MODEL_LIGHT_COLOR = new Cesium.Color(2.35, 2.40, 2.48, 1.0)

function modelEntity(
  dataSource: Cesium.CustomDataSource,
  id: string,
  modelKey: string,
  placement: AssetPlacement,
  height = placement.height ?? 0,
  parentId?: string,
): Cesium.Entity {
  const descriptor = getModel(modelKey)
  const position = cadToCartesian3(placement.cadX, placement.cadY, height)
  return dataSource.entities.add({
    id,
    position,
    orientation: orientation(position, placement.headingDeg),
    model: {
      uri: descriptor.modelUrl,
      scale: placement.scale ?? 1,
      minimumPixelSize: descriptor.minimumPixelSize ?? 0,
      maximumScale: descriptor.maximumScale ?? 1,
      imageBasedLightingFactor: DEFAULT_IBL_FACTOR,
      lightColor: DEFAULT_MODEL_LIGHT_COLOR,
    },
    properties: {
      parent: parentId ?? placement.id,
      type: placement.type,
      name: placement.name,
      source: placement.source,
      confidence: placement.confidence,
      state: placement.state,
    },
  })
}

function updateEntityPosition(
  entity: Cesium.Entity,
  placement: AssetPlacement,
  cadX: number,
  cadY: number,
  height: number,
  headingDeg = placement.headingDeg,
  pitchDeg = 0,
  rollDeg = 0,
) {
  const position = cadToCartesian3(cadX, cadY, height)
  entity.position = new Cesium.ConstantPositionProperty(position)
  entity.orientation = new Cesium.ConstantProperty(orientation(position, headingDeg, pitchDeg, rollDeg))
}

export interface StaticSiteAsset {
  placement: AssetPlacement
  entities: Cesium.Entity[]
}

const MODEL_KEY_BY_TYPE: Partial<Record<PlacementType, string>> = {
  wagon: 'wagon',
  bulk_container: 'bulk-container',
  tank_truck: 'tank-truck',
  road_truck: 'road-truck',
  rebar_bay: 'core-environment',
  site_shell: 'site-shell',
}

/**
 * V4 GLB 模型供给器（给大连大.dwg + Blender 5.2 MCP 全套高精度离线资产）：
 * 支持 40t/30m 门吊、室内桥吊（20t磁吸/32t C型）、移动翻箱机（0~135°翻转）、库底伸缩散装机、
 * 35t 散料箱体与罐车/货车联动姿态及 Slide 41 三维空间 Billboard 标签。
 */
export class GltfModelProvider {
  private readonly dataSource: Cesium.CustomDataSource
  private readonly staticAssets = new Map<string, StaticSiteAsset>()
  private readonly nightAccentEntities: Array<{ entity: Cesium.Entity; baseRadius: number; nightRadius: number; isNightPool?: boolean }> = []
  private activeContainerDoors = { leftDoorDeg: 0, rightDoorDeg: 0 }

  constructor(dataSource: Cesium.CustomDataSource) {
    this.dataSource = dataSource
  }

  loadEnvironment(): StaticSiteAsset[] {
    const rebarEnvironment = getPlacement('ENV-REBAR-LINE-01')
    const ashEnvironment = getPlacement('ENV-ASH-LINE-01')
    if (!rebarEnvironment || !ashEnvironment) {
      throw new Error('Missing V4 confirmed rail-line environment placement')
    }
    const result: StaticSiteAsset[] = []
    const siteShell = getPlacement('ENV-SITE-SHELL-01')

    for (const placement of CORE_PLACEMENTS.filter(
      (item) =>
        item.type === 'wagon' ||
        item.type === 'bulk_container' ||
        item.type === 'tank_truck' ||
        item.type === 'road_truck',
    )) {
      const modelKey = placement.modelKey ?? MODEL_KEY_BY_TYPE[placement.type]
      if (modelKey) result.push(this.registerStatic(placement, modelKey))
    }

    // Build the paired North-rail mobile container tilters (TIPPER-05..08) so all 4 groups × 4 East-West rails = 8 tilters render in 3D
    for (const northTipper of CORE_PLACEMENTS.filter(
      (item) => item.type === 'mobile_tipper' && item.id.startsWith('TIPPER-0') && Number(item.id.slice(-2)) >= 5,
    )) {
      const built = this.buildMobileTipper(
        {
          id: northTipper.id,
          name: northTipper.name,
          type: 'mobile_tipper',
          cadX: northTipper.cadX,
          cadY: northTipper.cadY,
          status: 'standby',
          height: 5.5,
          params: { tiltAngle: '0°', cycleRate: '30 t/h' },
        },
        northTipper,
      )
      result.push({ placement: northTipper, entities: built.entities })
    }

    // Dynamic detached steel cargo actor (open flatbed railcar -> handover apron -> Bay 07 empty slot -> road truck)
    result.push(
      this.registerStatic(
        {
          id: 'CARGO-STEEL-ACTIVE',
          name: '钢材吊运货捆',
          type: 'bulk_container',
          zone: 'rebar_corridor',
          state: 'existing',
          cadX: SITE_LAYOUT_V4.rebar.bays[6].cadX,
          cadY: SITE_LAYOUT_V4.rebar.tracks[0].cadY,
          height: 2.02,
          headingDeg: 0,
          source: 'simulation-route',
          confidence: 'confirmed',
          notes: '双级联合吊装活动钢材货捆（直接露天板车装运，无集装箱）',
        },
        'steel-cargo',
      ),
    )

    // V6 Dedicated 32t Hot-Rolled Steel Coil Actor (`steel-coil-v6`, auto-swapped when switching to 03跨 C型卷材钩!)
    result.push(
      this.registerStatic(
        {
          id: 'CARGO-COIL-ACTIVE',
          name: '32t 热轧钢卷（03跨 C型钩专用）',
          type: 'bulk_container',
          zone: 'rebar_corridor',
          state: 'existing',
          cadX: SITE_LAYOUT_V4.rebar.bays[2].cadX,
          cadY: SITE_LAYOUT_V4.rebar.tracks[0].cadY,
          height: 2.02,
          headingDeg: 0,
          source: 'simulation-route',
          confidence: 'confirmed',
          notes: '32t 卧式热轧钢卷与 V 型防滑鞍座，专供 03 跨 C 型吊具联合吊装演示',
        },
        'steel-coil',
      ),
    )
    this.setStaticVisible('CARGO-COIL-ACTIVE', false)

    // V6 Dynamic DF7G Diesel-Electric Shunting Locomotives pulling both Steel (货4) and Bulk (货2) trains!
    const steelTrackY = SITE_LAYOUT_V4.rebar.tracks[0].cadY
    const bulkTrackY = SITE_LAYOUT_V4.ash.tracks[1]?.cadY ?? SITE_LAYOUT_V4.ash.tracks[0].cadY
    result.push(
      this.registerStatic(
        {
          id: 'LOCO-STEEL-01',
          name: 'DF7G 内燃调车机车（钢材专列牵引）',
          type: 'wagon',
          zone: 'rebar_corridor',
          state: 'existing',
          cadX: SITE_LAYOUT_V4.rebar.bays[6].cadX + 31.6,
          cadY: steelTrackY,
          height: 0.25,
          headingDeg: 0,
          source: 'simulation-route',
          confidence: 'confirmed',
          notes: '牵引货4线露天钢材平车编组进站调车机车',
        },
        'df7g-locomotive',
      ),
    )
    result.push(
      this.registerStatic(
        {
          id: 'LOCO-BULK-01',
          name: 'DF7G 内燃调车机车（散料专列牵引）',
          type: 'wagon',
          zone: 'ash_corridor',
          state: 'existing',
          cadX: (SITE_LAYOUT_V4.ash.unloadingComplexes[0].tipperParkX ?? 3799.2) + 33.2,
          cadY: bulkTrackY,
          height: 0.25,
          headingDeg: 0,
          source: 'simulation-route',
          confidence: 'confirmed',
          notes: '牵引散料 20 英尺集装箱专列进站调车机车',
        },
        'df7g-locomotive',
      ),
    )

    this.addNightSceneAccents()
    this.addSiteBuildingLabels()
    for (const planned of STATION_PLACEMENTS.filter((item) => item.state === 'planned')) {
      const entities: Cesium.Entity[] = []
      if (planned.modelKey) {
        entities.push(modelEntity(this.dataSource, planned.id, planned.modelKey, planned, 0, planned.id))
      }
      entities.push(...this.addPlannedOverlay(planned))
      const asset = { placement: planned, entities }
      this.staticAssets.set(planned.id, asset)
      result.push(asset)
    }

    // Register the 3 primary site environment GLBs LAST so CesiumJS's LIFO Draco/WebP queue
    // decodes & displays the full station shell, 40 silos, and 13-bay rebar warehouse FIRST!
    result.push(this.registerStatic(ashEnvironment, 'ash-environment'))
    result.push(this.registerStatic(rebarEnvironment, 'core-environment'))
    if (siteShell) result.push(this.registerStatic(siteShell, 'site-shell'))

    return result
  }

  getStaticAssets(): StaticSiteAsset[] {
    return [...this.staticAssets.values()]
  }

  updateStaticPose(
    id: string,
    cadX: number,
    cadY: number,
    headingDeg: number,
    height = 0,
    pitchDeg = 0,
    rollDeg = 0,
  ) {
    const asset = this.staticAssets.get(id)
    if (!asset) return
    updateEntityPosition(asset.entities[0], asset.placement, cadX, cadY, height, headingDeg, pitchDeg, rollDeg)
    if (asset.entities.length >= 3 && (id === 'CONT-BULK-01' || id === 'CONT-STEEL-BUFFER-01')) {
      const isBulkActive = id === 'CONT-BULK-01'
      const leftDeg = isBulkActive ? this.activeContainerDoors.leftDoorDeg : 0
      const rightDeg = isBulkActive ? this.activeContainerDoors.rightDoorDeg : 0
      this.updateArticulatedContainerDoors(
        asset.entities[1],
        asset.entities[2],
        cadX,
        cadY,
        height,
        headingDeg,
        pitchDeg,
        rollDeg,
        leftDeg,
        rightDeg,
      )
    }
  }

  private updateArticulatedContainerDoors(
    doorLeft: Cesium.Entity,
    doorRight: Cesium.Entity,
    cadX: number,
    cadY: number,
    height: number,
    headingDeg: number,
    pitchDeg: number,
    rollDeg: number,
    leftDoorDeg: number,
    rightDoorDeg: number,
  ) {
    const rollRad = Cesium.Math.toRadians(rollDeg)
    const headRad = Cesium.Math.toRadians(headingDeg)
    const dxLocal = 3.029 * Math.cos(rollRad)
    const dzLocal = 3.029 * Math.sin(rollRad)

    const placeDoor = (doorEntity: Cesium.Entity, hingeYLocal: number, swingDeg: number) => {
      const hx = cadX + dxLocal * Math.cos(headRad) + hingeYLocal * Math.sin(headRad)
      const hy = cadY - dxLocal * Math.sin(headRad) + hingeYLocal * Math.cos(headRad)
      const hz = height + dzLocal
      const pos = cadToCartesian3(hx, hy, hz)
      const qBase = orientation(pos, headingDeg, pitchDeg, rollDeg)
      const qSwing = Cesium.Quaternion.fromAxisAngle(Cesium.Cartesian3.UNIT_Z, Cesium.Math.toRadians(swingDeg))
      const qFinal = Cesium.Quaternion.multiply(qBase, qSwing, new Cesium.Quaternion())
      doorEntity.position = new Cesium.ConstantPositionProperty(pos)
      doorEntity.orientation = new Cesium.ConstantProperty(qFinal)
    }

    placeDoor(doorLeft, -1.219, -leftDoorDeg)
    placeDoor(doorRight, 1.219, rightDoorDeg)
  }

  setStaticVisible(id: string, visible: boolean) {
    const asset = this.staticAssets.get(id)
    if (!asset) return
    for (const entity of asset.entities) entity.show = visible
  }

  buildEquipment(definition: EquipmentDef): BuiltEquipment | null {
    const placement = getPlacement(definition.id)
    if (!placement) return null
    const def: EquipmentDef = {
      ...definition,
      cadX: placement.cadX,
      cadY: placement.cadY,
      height: placement.height ?? definition.height,
    }

    switch (placement.type) {
      case 'gantry_crane':
        return this.buildGantry(def, placement)
      case 'bridge_crane':
        return this.buildBridgeCrane(def, placement)
      case 'mobile_tipper':
        return this.buildMobileTipper(def, placement)
      case 'silo_loader':
        return this.buildSiloLoader(def, placement)
      case 'elevator':
      case 'weighbridge':
        return this.buildBeaconOnlyEquipment(def, placement, placement.height ?? 3.5)
      default:
        return null
    }
  }

  private buildGantry(def: EquipmentDef, placement: AssetPlacement): BuiltEquipment {
    const frame = modelEntity(this.dataSource, `${def.id}-frame`, 'gantry-frame', placement, 0, def.id)
    const craneHeight = placement.height ?? 22
    const trolleyZ = craneHeight + 2.0
    const trolley = modelEntity(this.dataSource, `${def.id}-trolley`, 'gantry-trolley', placement, trolleyZ, def.id)
    const hoist = modelEntity(this.dataSource, `${def.id}-hoist`, 'gantry-hoist', placement, 14, def.id)
    const beacon = this.addStatusBeacon(def, placement, trolleyZ + 2.8)
    let pose: CranePose = { gantryX: placement.cadX, gantryY: placement.cadY, trolleyOffset: 0, hoistHeight: 14 }
    const billboard = this.addStatusBillboard(
      def,
      placement,
      trolleyZ + 6.5,
      () => `${def.id} · ${STATUS_NAMES[def.status] ?? def.status}\n吊具下 ${(pose.payloadTons ?? 0).toFixed(1)} t`,
      () => cadToCartesian3(pose.gantryX, pose.gantryY + pose.trolleyOffset * 0.4, trolleyZ + 6.5),
    )

    const cableOffsets: Array<[number, number]> = [
      [-2.6, -0.24],
      [-2.6, 0.24],
      [2.6, -0.24],
      [2.6, 0.24],
    ]
    const cables = cableOffsets.map(([dx, dy], idx) =>
      this.dataSource.entities.add({
        id: `${def.id}-cable-${idx}`,
        polyline: {
          positions: new Cesium.CallbackProperty(() => {
            const topZ = trolleyZ + 0.55
            const botZ = Math.min(topZ - 0.35, pose.hoistHeight + 0.52)
            return [
              cadToCartesian3(pose.gantryX + dx, pose.gantryY + pose.trolleyOffset + dy, topZ),
              cadToCartesian3(pose.gantryX + dx, pose.gantryY + pose.trolleyOffset + dy, botZ),
            ]
          }, false),
          width: 2.2,
          material: new Cesium.PolylineOutlineMaterialProperty({
            color: Cesium.Color.fromCssColorString('#CBD5E1'),
            outlineColor: Cesium.Color.fromCssColorString('#0F172A'),
            outlineWidth: 1,
          }),
        },
        properties: { parent: def.id, type: def.type },
      }),
    )

    const updatePose = (nextPose: CranePose) => {
      pose = nextPose
      updateEntityPosition(frame, placement, pose.gantryX, pose.gantryY, 0)
      updateEntityPosition(trolley, placement, pose.gantryX, pose.gantryY + pose.trolleyOffset, trolleyZ)
      updateEntityPosition(hoist, placement, pose.gantryX, pose.gantryY + pose.trolleyOffset, pose.hoistHeight)
      updateEntityPosition(beacon, placement, pose.gantryX, pose.gantryY - 12, trolleyZ + 2.5)
    }

    return {
      def,
      entities: [frame, trolley, hoist, ...cables, beacon, billboard.entity],
      setStatus: (status) => {
        def.status = status
        beacon.ellipsoid!.material = new Cesium.ColorMaterialProperty(statusColor(status))
      },
      setLabelText: (text) => billboard.setOverride(text),
      updatePose,
    }
  }

  private buildBridgeCrane(def: EquipmentDef, placement: AssetPlacement): BuiltEquipment {
    const railZ = placement.height ?? 13.0
    const hoistKey = placement.modelKey === 'bridge-crane-chook' ? 'bridge-hoist-chook' : 'bridge-hoist-magnet'
    const frame = modelEntity(this.dataSource, `${def.id}-frame`, 'bridge-frame', placement, railZ, def.id)
    const trolley = modelEntity(this.dataSource, `${def.id}-trolley`, 'bridge-trolley', placement, railZ + 2.2, def.id)
    const hoist = modelEntity(this.dataSource, `${def.id}-hoist`, hoistKey, placement, 7.5, def.id)
    const beacon = this.addStatusBeacon(def, placement, railZ + 3.2)

    let pose: BridgeCranePose = {
      cadX: placement.cadX,
      bridgeY: placement.cadY,
      trolleyOffsetX: 0,
      hoistHeight: 7.5,
    }

    const showTag = def.id === 'BC-01' || def.id === 'BC-03' || def.id === 'BC-07' || def.id === 'BC-13'
    const billboard = this.addStatusBillboard(
      def,
      placement,
      railZ + 4.8,
      () =>
        `${def.id} · ${STATUS_NAMES[def.status] ?? def.status}\n${
          pose.payloadTons && pose.payloadTons > 0 ? `吊载 ${pose.payloadTons.toFixed(1)}t` : placement.modelKey === 'bridge-crane-chook' ? '32t C型' : '20t 磁吸'
        }`,
      () => cadToCartesian3(pose.cadX + pose.trolleyOffsetX, pose.bridgeY, railZ + 4.8),
      showTag ? 420 : 180,
    )

    const bcCableOffsets: Array<[number, number]> =
      placement.modelKey === 'bridge-crane-chook'
        ? [
            [-0.35, -0.22],
            [0.35, 0.22],
          ]
        : [
            [-2.1, -0.18],
            [-2.1, 0.18],
            [2.1, -0.18],
            [2.1, 0.18],
          ]
    const cables = bcCableOffsets.map(([dx, dy], idx) =>
      this.dataSource.entities.add({
        id: `${def.id}-cable-${idx}`,
        polyline: {
          positions: new Cesium.CallbackProperty(() => {
            const topZ = railZ + 2.55
            const botZ = Math.min(topZ - 0.3, pose.hoistHeight + 0.5)
            return [
              cadToCartesian3(pose.cadX + pose.trolleyOffsetX + dx, pose.bridgeY + dy, topZ),
              cadToCartesian3(pose.cadX + pose.trolleyOffsetX + dx, pose.bridgeY + dy, botZ),
            ]
          }, false),
          width: 1.9,
          material: new Cesium.PolylineOutlineMaterialProperty({
            color: Cesium.Color.fromCssColorString('#CBD5E1'),
            outlineColor: Cesium.Color.fromCssColorString('#0F172A'),
            outlineWidth: 1,
          }),
        },
        properties: { parent: def.id, type: def.type },
      }),
    )

    const updateBridgePose = (nextPose: BridgeCranePose) => {
      pose = nextPose
      updateEntityPosition(frame, placement, pose.cadX, pose.bridgeY, railZ)
      updateEntityPosition(trolley, placement, pose.cadX + pose.trolleyOffsetX, pose.bridgeY, railZ + 2.2)
      updateEntityPosition(hoist, placement, pose.cadX + pose.trolleyOffsetX, pose.bridgeY, pose.hoistHeight)
      updateEntityPosition(beacon, placement, pose.cadX - 10, pose.bridgeY, railZ + 3.2)
    }

    return {
      def,
      entities: [frame, trolley, hoist, ...cables, beacon, billboard.entity],
      setStatus: (status) => {
        def.status = status
        beacon.ellipsoid!.material = new Cesium.ColorMaterialProperty(statusColor(status))
      },
      setLabelText: (text) => billboard.setOverride(text),
      updateBridgePose,
    }
  }

  private buildMobileTipper(def: EquipmentDef, placement: AssetPlacement): BuiltEquipment {
    const frame = modelEntity(this.dataSource, `${def.id}-frame`, 'tipper-frame', placement, 0, def.id)
    const pivotX = placement.cadX - 0.43
    const pivotY = placement.cadY
    const pivotZ = 3.20
    const cradle = this.dataSource.entities.add({
      id: `${def.id}-cradle`,
      position: cadToCartesian3(pivotX, pivotY, pivotZ),
      orientation: orientation(cadToCartesian3(pivotX, pivotY, pivotZ), placement.headingDeg),
      model: {
        uri: getModel('tipper-cradle').modelUrl,
        scale: 1,
        minimumPixelSize: 18,
        maximumScale: 1,
        imageBasedLightingFactor: DEFAULT_IBL_FACTOR,
        lightColor: DEFAULT_MODEL_LIGHT_COLOR,
      },
      properties: { parent: def.id, type: def.type, name: def.name },
    })
    const claws = this.dataSource.entities.add({
      id: `${def.id}-claws`,
      position: cadToCartesian3(pivotX, pivotY, pivotZ),
      orientation: orientation(cadToCartesian3(pivotX, pivotY, pivotZ), placement.headingDeg),
      model: {
        uri: getModel('tipper-claws').modelUrl,
        scale: 1,
        minimumPixelSize: 14,
        maximumScale: 1,
        imageBasedLightingFactor: DEFAULT_IBL_FACTOR,
        lightColor: DEFAULT_MODEL_LIGHT_COLOR,
      },
      properties: { parent: def.id, type: def.type, name: `${def.name}·顶门压爪与开门机构` },
    })
    const groupIdx = Math.max(0, (Number(def.id.slice(-2)) - 1) % SITE_LAYOUT_V4.ash.unloadingComplexes.length)
    const complex = SITE_LAYOUT_V4.ash.unloadingComplexes[groupIdx]
    const unloadStationX = complex?.tipperUnloadX ?? 3787.89
    const bellows = this.dataSource.entities.add({
      id: `${def.id}-bellows`,
      position: cadToCartesian3(unloadStationX - 0.33, placement.cadY, 7.35),
      orientation: orientation(cadToCartesian3(unloadStationX - 0.33, placement.cadY, 7.35), placement.headingDeg),
      model: {
        uri: getModel('tipper-bellows-chute').modelUrl,
        scale: 1,
        minimumPixelSize: 12,
        maximumScale: 1,
        imageBasedLightingFactor: DEFAULT_IBL_FACTOR,
        lightColor: DEFAULT_MODEL_LIGHT_COLOR,
      },
      properties: { parent: def.id, type: def.type, name: `${def.name}·顶部波纹伸缩吸料管` },
    })
    const beacon = this.addStatusBeacon(def, placement, 5.6)

    let pose: TipperPose = {
      cadX: placement.cadX,
      cadY: placement.cadY,
      tiltDeg: 0,
      clampLocked: true,
      pinExtracted: false,
      leftDoorDeg: 0,
      rightDoorDeg: 0,
      bellowsDropM: 0,
    }
    const isPrimaryTipper = Number(def.id.slice(-2)) <= 4
    const billboard = this.addStatusBillboard(
      def,
      placement,
      8.6,
      () => {
        if (pose.stageLabel) return `${def.id} · ${pose.stageLabel}`
        const tiltInfo =
          pose.tiltDeg > 0.5
            ? `\n翻转 ${Math.round(pose.tiltDeg)}°${
                (pose.leftDoorDeg ?? 0) > 20
                  ? ' · 顶门全开'
                  : pose.clampLocked !== false
                    ? ' · 蓝爪压门锁死'
                    : ''
              }${
                (pose.bellowsDropM ?? 0) > 0.2 ? ` · 波纹管下放 ${(pose.bellowsDropM ?? 0).toFixed(1)}m` : ''
              }${pose.vibrating ? ' · 高频震动清仓' : ''}`
            : ''
        return `${def.id} · ${STATUS_NAMES[def.status] ?? def.status}${tiltInfo}`
      },
      () => cadToCartesian3(pose.cadX, pose.cadY - 2.6, 8.6),
      isPrimaryTipper ? 480 : 180,
    )

    const updateTipperPose = (nextPose: TipperPose) => {
      pose = nextPose
      const vibDeg = pose.vibrating ? Math.sin(performance.now() * 0.06) * 0.75 : 0
      const effTilt = pose.tiltDeg + vibDeg
      const rad = Cesium.Math.toRadians(effTilt)
      updateEntityPosition(frame, placement, pose.cadX, pose.cadY, 0)
      // Cradle rotates around rear-upper Y-axis shaft at (cadX - 0.43, cadY, 3.20) by +effTilt (0° -> 90° vertical)
      updateEntityPosition(cradle, placement, pose.cadX - 0.43, pose.cadY, pivotZ, placement.headingDeg, 0, effTilt)

      // Blue hydraulic clamp claws: retract outward along cradle +X when unlocked so crane drops/lifts container with zero clipping
      const clawReleaseOffset = pose.clampLocked === false ? 0.24 : 0
      const clawX = pose.cadX - 0.43 + clawReleaseOffset * Math.cos(rad)
      const clawZ = pivotZ + clawReleaseOffset * Math.sin(rad)
      updateEntityPosition(claws, placement, clawX, pose.cadY, clawZ, placement.headingDeg, 0, effTilt)

      // Overhead corrugated telescopic suction chute stays anchored over the West Unloading Berth (unloadStationX) while tilter rides East-West rails!
      const chuteDrop = pose.bellowsDropM ?? 0
      updateEntityPosition(bellows, placement, unloadStationX - 0.33, placement.cadY, 7.35 - chuteDrop, placement.headingDeg, 0, 0)
      updateEntityPosition(beacon, placement, pose.cadX - 1.45, pose.cadY - 1.75, 5.75)

      if (def.id === 'TIPPER-01') {
        this.activeContainerDoors = {
          leftDoorDeg: pose.leftDoorDeg ?? 0,
          rightDoorDeg: pose.rightDoorDeg ?? 0,
        }
      }
    }

    return {
      def,
      entities: [frame, cradle, claws, bellows, beacon, billboard.entity],
      setStatus: (status) => {
        def.status = status
        beacon.ellipsoid!.material = new Cesium.ColorMaterialProperty(statusColor(status))
      },
      setLabelText: (text) => billboard.setOverride(text),
      updateTipperPose,
    }
  }

  private buildSiloLoader(def: EquipmentDef, placement: AssetPlacement): BuiltEquipment {
    const spoutZ = placement.height ?? 5.5
    const head = modelEntity(this.dataSource, `${def.id}-head`, 'silo-loader-head', placement, spoutZ, def.id)
    const beacon = this.addStatusBeacon(def, placement, 11.5)
    let pose: SiloLoaderPose = { cadX: placement.cadX, cadY: placement.cadY, spoutHeight: spoutZ, active: false }
    const billboard = this.addStatusBillboard(
      def,
      placement,
      14.5,
      () =>
        `${def.id} · ${
          pose.active && pose.targetTons
            ? `定量装车 ${(pose.loadedTons ?? 0).toFixed(1)}/${pose.targetTons}t`
            : STATUS_NAMES[def.status] ?? def.status
        }`,
      () => cadToCartesian3(pose.cadX, pose.cadY, 14.5),
      320,
    )

    const updateLoaderPose = (nextPose: SiloLoaderPose) => {
      pose = nextPose
      updateEntityPosition(head, placement, pose.cadX, pose.cadY, pose.spoutHeight)
      updateEntityPosition(beacon, placement, pose.cadX, pose.cadY, 11.5)
    }

    return {
      def,
      entities: [head, beacon, billboard.entity],
      setStatus: (status) => {
        def.status = status
        beacon.ellipsoid!.material = new Cesium.ColorMaterialProperty(statusColor(status))
      },
      setLabelText: (text) => billboard.setOverride(text),
      updateLoaderPose,
    }
  }

  private buildBeaconOnlyEquipment(def: EquipmentDef, placement: AssetPlacement, height: number): BuiltEquipment {
    const beacon = this.addStatusBeacon(def, placement, height)
    const isElevator = placement.type === 'elevator'
    const boxDims = isElevator ? new Cesium.Cartesian3(4.2, 4.2, height) : new Cesium.Cartesian3(18.0, 4.4, 1.4)
    const boxCenterZ = isElevator ? height / 2 : 0.7
    const boxPos = cadToCartesian3(placement.cadX, placement.cadY, boxCenterZ)
    const pickVolume = this.dataSource.entities.add({
      id: `${def.id}-pickbox`,
      position: boxPos,
      orientation: Cesium.Transforms.headingPitchRollQuaternion(
        boxPos,
        new Cesium.HeadingPitchRoll(Cesium.Math.toRadians(placement.headingDeg), 0, 0),
      ),
      box: {
        dimensions: boxDims,
        material: new Cesium.ColorMaterialProperty(statusColor(def.status).withAlpha(0.14)),
        outline: true,
        outlineColor: statusColor(def.status).withAlpha(0.65),
      },
      properties: { parent: def.id, type: def.type, name: def.name },
    })
    const billboard = this.addStatusBillboard(
      def,
      placement,
      height + 2.4,
      () => `${def.id} · ${STATUS_NAMES[def.status] ?? def.status}`,
      () => cadToCartesian3(placement.cadX, placement.cadY, height + 2.4),
      360,
    )
    return {
      def,
      entities: [pickVolume, beacon, billboard.entity],
      setStatus: (status) => {
        def.status = status
        beacon.ellipsoid!.material = new Cesium.ColorMaterialProperty(statusColor(status))
        pickVolume.box!.material = new Cesium.ColorMaterialProperty(statusColor(status).withAlpha(0.14))
        pickVolume.box!.outlineColor = new Cesium.ConstantProperty(statusColor(status).withAlpha(0.65))
      },
      setLabelText: (text) => billboard.setOverride(text),
    }
  }

  private addStatusBeacon(def: EquipmentDef, placement: AssetPlacement, height: number): Cesium.Entity {
    const position = cadToCartesian3(placement.cadX, placement.cadY, height)
    return this.dataSource.entities.add({
      id: `${def.id}-status`,
      position,
      ellipsoid: {
        radii: new Cesium.Cartesian3(0.62, 0.62, 0.62),
        material: new Cesium.ColorMaterialProperty(statusColor(def.status)),
        outline: true,
        outlineColor: Cesium.Color.WHITE.withAlpha(0.72),
      },
      properties: { parent: def.id, type: def.type },
    })
  }

  private addStatusBillboard(
    def: EquipmentDef,
    placement: AssetPlacement,
    height: number,
    defaultTextFn: () => string,
    posFn: () => Cesium.Cartesian3,
    maxDistance = 540,
  ) {
    let overrideText: string | null = null
    const entity = this.dataSource.entities.add({
      id: `${def.id}-billboard`,
      position: new Cesium.CallbackPositionProperty(() => posFn(), false) as unknown as Cesium.PositionProperty,
      label: {
        text: new Cesium.CallbackProperty(() => overrideText ?? defaultTextFn(), false),
        font: '600 12px Microsoft YaHei',
        fillColor: new Cesium.CallbackProperty(() => statusColor(def.status).brighten(0.35, new Cesium.Color()), false),
        backgroundColor: Cesium.Color.fromCssColorString('#061529').withAlpha(0.84),
        showBackground: true,
        backgroundPadding: new Cesium.Cartesian2(8, 5),
        outlineColor: Cesium.Color.fromCssColorString('#020A17'),
        outlineWidth: 2,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
        distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, maxDistance),
      },
      properties: { parent: def.id, type: def.type },
    })
    // Ensure initial static fallback if needed
    void height
    void placement
    return {
      entity,
      setOverride: (txt: string) => {
        overrideText = txt || null
      },
    }
  }

  private registerStatic(placement: AssetPlacement, modelKey: string): StaticSiteAsset {
    const entities = [modelEntity(this.dataSource, placement.id, modelKey, placement)]
    if (modelKey === 'bulk-container') {
      const doorLeft = modelEntity(
        this.dataSource,
        `${placement.id}-door-l`,
        'bulk-container-door-l',
        placement,
        placement.height ?? 0,
        placement.id,
      )
      const doorRight = modelEntity(
        this.dataSource,
        `${placement.id}-door-r`,
        'bulk-container-door-r',
        placement,
        placement.height ?? 0,
        placement.id,
      )
      entities.push(doorLeft, doorRight)
      this.updateArticulatedContainerDoors(
        doorLeft,
        doorRight,
        placement.cadX,
        placement.cadY,
        placement.height ?? 0,
        placement.headingDeg,
        0,
        0,
        0,
        0,
      )
    }
    if (placement.state === 'planned') entities.push(...this.addPlannedOverlay(placement))
    const asset = { placement, entities }
    this.staticAssets.set(placement.id, asset)
    return asset
  }

  private addNightSceneAccents(): void {
    const warmLight = Cesium.Color.fromCssColorString('#FFB45C')
    const coolLight = Cesium.Color.fromCssColorString('#78D7FF')
    const mastLight = Cesium.Color.fromCssColorString('#FFE6A3')
    const accents: Array<{ id: string; cadX: number; cadY: number; height: number; color: Cesium.Color; size?: number; nightSize?: number; pool?: boolean }> = [
      { id: 'NIGHT-REBAR-01', cadX: 3560, cadY: 1632, height: 14, color: warmLight, size: 0.42, nightSize: 1.45 },
      { id: 'NIGHT-REBAR-02', cadX: 3680, cadY: 1632, height: 14, color: warmLight, size: 0.42, nightSize: 1.45 },
      { id: 'NIGHT-REBAR-03', cadX: 3751, cadY: 1632, height: 14, color: warmLight, size: 0.42, nightSize: 1.45 },
      { id: 'NIGHT-REBAR-04', cadX: 3860, cadY: 1632, height: 14, color: warmLight, size: 0.42, nightSize: 1.45 },
      { id: 'NIGHT-REBAR-05', cadX: 3940, cadY: 1632, height: 14, color: warmLight, size: 0.42, nightSize: 1.45 },
      { id: 'NIGHT-WH-01', cadX: 3600, cadY: 1704, height: 16, color: coolLight, size: 0.36, nightSize: 1.85 },
      { id: 'NIGHT-WH-02', cadX: 3720, cadY: 1704, height: 16, color: coolLight, size: 0.36, nightSize: 1.85 },
      { id: 'NIGHT-WH-03', cadX: 3840, cadY: 1704, height: 16, color: coolLight, size: 0.36, nightSize: 1.85 },
      { id: 'NIGHT-ASH-01', cadX: 3781, cadY: 1510, height: 12, color: warmLight, size: 0.42, nightSize: 1.45 },
      { id: 'NIGHT-ASH-02', cadX: 3851, cadY: 1510, height: 12, color: warmLight, size: 0.42, nightSize: 1.45 },
      { id: 'NIGHT-ASH-03', cadX: 3935, cadY: 1510, height: 12, color: warmLight, size: 0.42, nightSize: 1.45 },
      { id: 'NIGHT-ASH-04', cadX: 4005, cadY: 1510, height: 12, color: warmLight, size: 0.42, nightSize: 1.45 },
      // 8 × 25m High-Mast Floodlight Towers (aligned 1:1 with build_site_shell_v4.py tower_coords)
      { id: 'NIGHT-MAST-01', cadX: 3515, cadY: 1604, height: 24.4, color: mastLight, size: 0.9, nightSize: 2.8, pool: true },
      { id: 'NIGHT-MAST-02', cadX: 3760, cadY: 1604, height: 24.4, color: mastLight, size: 0.9, nightSize: 2.8, pool: true },
      { id: 'NIGHT-MAST-03', cadX: 4020, cadY: 1604, height: 24.4, color: mastLight, size: 0.9, nightSize: 2.8, pool: true },
      { id: 'NIGHT-MAST-04', cadX: 4280, cadY: 1604, height: 24.4, color: mastLight, size: 0.9, nightSize: 2.8, pool: true },
      { id: 'NIGHT-MAST-05', cadX: 3515, cadY: 1736, height: 24.4, color: mastLight, size: 0.9, nightSize: 2.8, pool: true },
      { id: 'NIGHT-MAST-06', cadX: 3780, cadY: 1736, height: 24.4, color: mastLight, size: 0.9, nightSize: 2.8, pool: true },
      { id: 'NIGHT-MAST-07', cadX: 3985, cadY: 1768, height: 24.4, color: mastLight, size: 0.9, nightSize: 2.8, pool: true },
      { id: 'NIGHT-MAST-08', cadX: 4345, cadY: 1604, height: 24.4, color: mastLight, size: 0.9, nightSize: 2.8, pool: true },
    ]

    for (const accent of accents) {
      const baseR = accent.size ?? 0.44
      const nightR = accent.nightSize ?? baseR * 2.5
      const entity = this.dataSource.entities.add({
        id: accent.id,
        position: cadToCartesian3(accent.cadX, accent.cadY, accent.height),
        ellipsoid: {
          radii: new Cesium.Cartesian3(baseR, baseR, baseR),
          material: new Cesium.ColorMaterialProperty(accent.color.withAlpha(0.92)),
        },
        properties: { type: 'night_light', parent: 'ENV-CORE-01', state: 'existing' },
      })
      this.nightAccentEntities.push({ entity, baseRadius: baseR, nightRadius: nightR })

      if (accent.pool) {
        const poolEntity = this.dataSource.entities.add({
          id: `${accent.id}-POOL`,
          show: false,
          position: cadToCartesian3(accent.cadX, accent.cadY, 0.06),
          cylinder: {
            length: 0.08,
            topRadius: 14,
            bottomRadius: 18,
            material: new Cesium.ColorMaterialProperty(mastLight.withAlpha(0.06)),
          },
          properties: { type: 'night_light_pool', parent: 'ENV-CORE-01', state: 'existing' },
        })
        this.nightAccentEntities.push({ entity: poolEntity, baseRadius: 14, nightRadius: 18, isNightPool: true })
      }
    }
  }

  setLightingAccentMode(preset: 'daylight' | 'dusk' | 'night'): void {
    const isNight = preset === 'night'
    const isDusk = preset === 'dusk'
    for (const item of this.nightAccentEntities) {
      if (item.isNightPool) {
        item.entity.show = isNight || isDusk
        if (item.entity.cylinder) {
          item.entity.cylinder.material = new Cesium.ColorMaterialProperty(
            Cesium.Color.fromCssColorString(isNight ? '#FFE08A' : '#FFB86C').withAlpha(isNight ? 0.075 : 0.04),
          )
        }
      } else if (item.entity.ellipsoid) {
        const r = isNight ? item.nightRadius : isDusk ? (item.baseRadius + item.nightRadius) * 0.52 : item.baseRadius
        item.entity.ellipsoid.radii = new Cesium.ConstantProperty(new Cesium.Cartesian3(r, r, r))
      }
    }
  }

  private addSiteBuildingLabels(): void {
    // Overview/mid-range 3D nameplates for all surveyed CAD buildings & facilities (auto-hide < 120m so close-up animations stay 100% uncluttered!)
    const buildings = (cadBuildingsV54.buildings ?? []) as Array<{
      id: string
      name: string
      state: string
      heightM: number
      cadX: number
      cadY: number
    }>
    for (const b of buildings) {
      if (b.state === 'planned') continue
      const labelZ = Math.max(4.5, (b.heightM ?? 5.0) + 3.2)
      this.dataSource.entities.add({
        id: `BLDG-LABEL-${b.id}`,
        position: cadToCartesian3(b.cadX, b.cadY, labelZ),
        label: {
          text: b.name,
          font: '600 12px Microsoft YaHei',
          fillColor: Cesium.Color.fromCssColorString('#E2F4FF'),
          outlineColor: Cesium.Color.fromCssColorString('#04101E'),
          outlineWidth: 2,
          style: Cesium.LabelStyle.FILL_AND_OUTLINE,
          backgroundColor: Cesium.Color.fromCssColorString('#06192C').withAlpha(0.76),
          showBackground: true,
          backgroundPadding: new Cesium.Cartesian2(6, 3),
          verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
          scaleByDistance: new Cesium.NearFarScalar(160, 1.0, 920, 0.72),
          distanceDisplayCondition: new Cesium.DistanceDisplayCondition(115, 960),
          disableDepthTestDistance: 120,
        },
        properties: { parent: b.id, type: 'site_building', name: b.name },
      })
    }
  }

  private addPlannedOverlay(placement: AssetPlacement): Cesium.Entity[] {
    const zone = getZone(placement.zone)
    const points = [...zone.polygon.points, zone.polygon.points[0]].map(([x, y]) => cadToCartesian3(x, y, 0.5))
    const boundary = this.dataSource.entities.add({
      id: `${placement.id}-boundary`,
      polyline: {
        positions: points,
        width: 2.2,
        material: new Cesium.PolylineDashMaterialProperty({
          color: Cesium.Color.fromCssColorString('#52D9FF').withAlpha(0.92),
          dashLength: 18,
        }),
      },
      properties: { parent: placement.id, type: placement.type, state: 'planned' },
    })
    const label = this.dataSource.entities.add({
      id: `${placement.id}-label`,
      position: cadToCartesian3(placement.cadX, placement.cadY, 17.5),
      label: {
        text: `规划缓建 · ${placement.name.split('（')[0]}`,
        font: '600 12px Microsoft YaHei',
        fillColor: Cesium.Color.fromCssColorString('#8CE9FF'),
        outlineColor: Cesium.Color.fromCssColorString('#04101E'),
        outlineWidth: 2,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        backgroundColor: Cesium.Color.fromCssColorString('#061D30').withAlpha(0.84),
        showBackground: true,
        backgroundPadding: new Cesium.Cartesian2(7, 3),
        verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
        scaleByDistance: new Cesium.NearFarScalar(160, 1.0, 920, 0.74),
        distanceDisplayCondition: new Cesium.DistanceDisplayCondition(115, 960),
        disableDepthTestDistance: 120,
      },
      properties: { parent: placement.id, type: placement.type, state: 'planned' },
    })
    return [boundary, label]
  }
}
