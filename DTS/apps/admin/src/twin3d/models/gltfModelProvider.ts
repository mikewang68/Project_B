import * as Cesium from 'cesium'
import { STATUS_COLORS, STATUS_NAMES, type DeviceStatusCode, type EquipmentDef } from '../constants'
import { cadToCartesian3 } from '../geo'
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
    if (siteShell) result.push(this.registerStatic(siteShell, 'site-shell'))
    result.push(this.registerStatic(rebarEnvironment, 'core-environment'))
    result.push(this.registerStatic(ashEnvironment, 'ash-environment'))

    for (const placement of CORE_PLACEMENTS.filter(
      (item) =>
        item.type === 'wagon' ||
        item.type === 'bulk_container' ||
        item.type === 'tank_truck' ||
        item.type === 'road_truck',
    )) {
      const modelKey = MODEL_KEY_BY_TYPE[placement.type]
      if (modelKey) result.push(this.registerStatic(placement, modelKey))
    }

    // Dynamic detached steel cargo actor (used by gantry -> transfer -> bridge crane handoff)
    result.push(
      this.registerStatic(
        {
          id: 'CARGO-STEEL-ACTIVE',
          name: '钢材吊运货捆',
          type: 'bulk_container',
          zone: 'rebar_corridor',
          state: 'existing',
          cadX: 3737.06,
          cadY: 1641.546,
          height: 2.0,
          headingDeg: 0,
          source: 'simulation-route',
          confidence: 'confirmed',
          notes: '双级联合吊装活动钢材货捆',
        },
        'steel-cargo',
      ),
    )

    this.addNightSceneAccents()
    for (const planned of STATION_PLACEMENTS.filter((item) => item.state === 'planned')) {
      const entities = this.addPlannedOverlay(planned)
      const asset = { placement: planned, entities }
      this.staticAssets.set(planned.id, asset)
      result.push(asset)
    }
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

    const cable = this.dataSource.entities.add({
      id: `${def.id}-cable`,
      polyline: {
        positions: new Cesium.CallbackProperty(() => {
          const upper = cadToCartesian3(pose.gantryX, pose.gantryY + pose.trolleyOffset, trolleyZ + 0.5)
          const lower = cadToCartesian3(pose.gantryX, pose.gantryY + pose.trolleyOffset, pose.hoistHeight)
          return [upper, lower]
        }, false),
        width: 2.6,
        material: new Cesium.PolylineOutlineMaterialProperty({
          color: Cesium.Color.fromCssColorString('#38BDF8'),
          outlineColor: Cesium.Color.fromCssColorString('#0F172A'),
          outlineWidth: 1,
        }),
      },
      properties: { parent: def.id, type: def.type },
    })

    const updatePose = (nextPose: CranePose) => {
      pose = nextPose
      updateEntityPosition(frame, placement, pose.gantryX, pose.gantryY, 0)
      updateEntityPosition(trolley, placement, pose.gantryX, pose.gantryY + pose.trolleyOffset, trolleyZ)
      updateEntityPosition(hoist, placement, pose.gantryX, pose.gantryY + pose.trolleyOffset, pose.hoistHeight)
      updateEntityPosition(beacon, placement, pose.gantryX, pose.gantryY - 12, trolleyZ + 2.5)
    }

    return {
      def,
      entities: [frame, trolley, hoist, cable, beacon, billboard.entity],
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

    const cable = this.dataSource.entities.add({
      id: `${def.id}-cable`,
      polyline: {
        positions: new Cesium.CallbackProperty(() => {
          const upper = cadToCartesian3(pose.cadX + pose.trolleyOffsetX, pose.bridgeY, railZ + 2.2)
          const lower = cadToCartesian3(pose.cadX + pose.trolleyOffsetX, pose.bridgeY, pose.hoistHeight)
          return [upper, lower]
        }, false),
        width: 2.0,
        material: new Cesium.ColorMaterialProperty(Cesium.Color.fromCssColorString('#F59E0B')),
      },
      properties: { parent: def.id, type: def.type },
    })

    const updateBridgePose = (nextPose: BridgeCranePose) => {
      pose = nextPose
      updateEntityPosition(frame, placement, pose.cadX, pose.bridgeY, railZ)
      updateEntityPosition(trolley, placement, pose.cadX + pose.trolleyOffsetX, pose.bridgeY, railZ + 2.2)
      updateEntityPosition(hoist, placement, pose.cadX + pose.trolleyOffsetX, pose.bridgeY, pose.hoistHeight)
      updateEntityPosition(beacon, placement, pose.cadX - 10, pose.bridgeY, railZ + 3.2)
    }

    return {
      def,
      entities: [frame, trolley, hoist, cable, beacon, billboard.entity],
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
    const pivotY = placement.cadY + 1.85
    const pivotZ = 3.2
    const cradle = this.dataSource.entities.add({
      id: `${def.id}-cradle`,
      position: cadToCartesian3(placement.cadX, pivotY, pivotZ),
      orientation: orientation(cadToCartesian3(placement.cadX, pivotY, pivotZ), placement.headingDeg),
      model: {
        uri: getModel('tipper-cradle').modelUrl,
        scale: 1,
        minimumPixelSize: 18,
        maximumScale: 1,
      },
      properties: { parent: def.id, type: def.type, name: def.name },
    })
    const beacon = this.addStatusBeacon(def, placement, 5.2)

    let pose: TipperPose = { cadX: placement.cadX, cadY: placement.cadY, tiltDeg: 0 }
    const billboard = this.addStatusBillboard(
      def,
      placement,
      8.2,
      () =>
        `${def.id} · ${STATUS_NAMES[def.status] ?? def.status}${
          pose.tiltDeg > 1 ? `\n翻转 ${Math.round(pose.tiltDeg)}°${pose.vibrating ? ' · 振动卸料' : ''}` : ''
        }`,
      () => cadToCartesian3(pose.cadX, pose.cadY - 3.0, 8.2),
      460,
    )

    const updateTipperPose = (nextPose: TipperPose) => {
      pose = nextPose
      updateEntityPosition(frame, placement, pose.cadX, pose.cadY, 0)
      // Cradle rotates backward/northward (-roll around CAD +X axis toward unloading hopper)
      updateEntityPosition(cradle, placement, pose.cadX, pose.cadY + 1.85, pivotZ, placement.headingDeg, 0, -pose.tiltDeg)
      updateEntityPosition(beacon, placement, pose.cadX - 5, pose.cadY, 5.2)
    }

    return {
      def,
      entities: [frame, cradle, beacon, billboard.entity],
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
    const pickVolume = this.dataSource.entities.add({
      id: `${def.id}-pickbox`,
      position: cadToCartesian3(placement.cadX, placement.cadY, boxCenterZ),
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
    if (placement.state === 'planned') entities.push(...this.addPlannedOverlay(placement))
    const asset = { placement, entities }
    this.staticAssets.set(placement.id, asset)
    return asset
  }

  private addNightSceneAccents(): void {
    const warmLight = Cesium.Color.fromCssColorString('#FFB45C')
    const coolLight = Cesium.Color.fromCssColorString('#78D7FF')
    const accents: Array<{ id: string; cadX: number; cadY: number; height: number; color: Cesium.Color; size?: number }> = [
      { id: 'NIGHT-REBAR-01', cadX: 3560, cadY: 1632, height: 14, color: warmLight },
      { id: 'NIGHT-REBAR-02', cadX: 3680, cadY: 1632, height: 14, color: warmLight },
      { id: 'NIGHT-REBAR-03', cadX: 3751, cadY: 1632, height: 14, color: warmLight },
      { id: 'NIGHT-REBAR-04', cadX: 3860, cadY: 1632, height: 14, color: warmLight },
      { id: 'NIGHT-REBAR-05', cadX: 3940, cadY: 1632, height: 14, color: warmLight },
      { id: 'NIGHT-WH-01', cadX: 3600, cadY: 1704, height: 16, color: coolLight, size: 0.36 },
      { id: 'NIGHT-WH-02', cadX: 3720, cadY: 1704, height: 16, color: coolLight, size: 0.36 },
      { id: 'NIGHT-WH-03', cadX: 3840, cadY: 1704, height: 16, color: coolLight, size: 0.36 },
      { id: 'NIGHT-ASH-01', cadX: 3781, cadY: 1510, height: 12, color: warmLight },
      { id: 'NIGHT-ASH-02', cadX: 3851, cadY: 1510, height: 12, color: warmLight },
      { id: 'NIGHT-ASH-03', cadX: 3935, cadY: 1510, height: 12, color: warmLight },
      { id: 'NIGHT-ASH-04', cadX: 4005, cadY: 1510, height: 12, color: warmLight },
    ]

    for (const accent of accents) {
      this.dataSource.entities.add({
        id: accent.id,
        position: cadToCartesian3(accent.cadX, accent.cadY, accent.height),
        ellipsoid: {
          radii: new Cesium.Cartesian3(accent.size ?? 0.44, accent.size ?? 0.44, accent.size ?? 0.44),
          material: new Cesium.ColorMaterialProperty(accent.color.withAlpha(0.92)),
        },
        properties: { type: 'night_light', parent: 'ENV-CORE-01', state: 'existing' },
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
      position: cadToCartesian3(placement.cadX, placement.cadY, 15),
      label: {
        text: `规划缓建\n${placement.name.split('（')[0]}`,
        font: '13px Microsoft YaHei',
        fillColor: Cesium.Color.fromCssColorString('#8CE9FF'),
        outlineColor: Cesium.Color.fromCssColorString('#071228'),
        outlineWidth: 3,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        pixelOffset: new Cesium.Cartesian2(0, -14),
        distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, 650),
      },
      properties: { parent: placement.id, type: placement.type, state: 'planned' },
    })
    return [boundary, label]
  }
}
