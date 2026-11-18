import * as Cesium from 'cesium'
import { STATUS_COLORS, STATUS_NAMES, type EquipmentDef } from './constants'
import { cadToCartesian3, cadToLonLat, cartesian3ToCad } from './geo'
import { MOCK_EQUIPMENT } from './mock/mockData'
import { SITE_LAYOUT_V4 } from './siteLayoutV4'
import {
  buildEquipment,
  type BridgeCranePose,
  type BuiltEquipment,
  type CranePose,
  type SiloLoaderPose,
  type TipperPose,
} from './models/buildEquipment'
import { GltfModelProvider, type StaticSiteAsset } from './models/gltfModelProvider'
import { VehicleRouteAnimator, type VehiclePose } from './vehicleAnimator'
import { VEHICLE_ROUTES } from './vehicleRoutes'
import type { ProcessHighlightState } from './processTypes'

export interface SelectedInfo {
  id: string
  name: string
  type: string
  statusName: string
  params: Record<string, string>
}

export interface Twin3dControllerOptions {
  onSelect?: (info: SelectedInfo | null) => void
  useProceduralFallback?: boolean
}

export class Twin3dController {
  private viewer: Cesium.Viewer
  private dataSource: Cesium.CustomDataSource
  private equipmentMap = new Map<string, BuiltEquipment>()
  private mockTimer: ReturnType<typeof setInterval> | null = null
  private options: Twin3dControllerOptions
  private allEquipment: EquipmentDef[]
  private readonly useProceduralFallback: boolean
  private readonly staticAssetMap = new Map<string, StaticSiteAsset>()
  private vehicleAnimator: VehicleRouteAnimator | null = null
  private gltfProvider: GltfModelProvider | null = null

  // Dynamic process visualizers (bulk air-slide stream, silo spout stream, laser safety beam, electronic fences, weighbridge & bay alerts)
  private elevFlowEntity: Cesium.Entity | null = null
  private slideFlowEntity: Cesium.Entity | null = null
  private spoutStreamEntity: Cesium.Entity | null = null
  private gapIndicatorEntity: Cesium.Entity | null = null
  private laserBeamEntity: Cesium.Entity | null = null
  private loadingFenceEntity: Cesium.Entity | null = null
  private underSiloFenceEntity: Cesium.Entity | null = null
  private wbInHighlightEntity: Cesium.Entity | null = null
  private wbOutHighlightEntity: Cesium.Entity | null = null
  private bayWarningEntity: Cesium.Entity | null = null
  private siloHighlightEntity: Cesium.Entity | null = null
  private renderPumpRaf: number | null = null
  private renderPumpTimers: Array<ReturnType<typeof setTimeout>> = []
  private lastLabelTexts = {
    gap: '',
    gapColor: '',
    fence: '',
    underFence: '',
    wbIn: '',
    wbOut: '',
    bayWarn: '',
  }
  private selectedSiloPos = { cadX: SITE_LAYOUT_V4.ash.silos[0].cadX, cadY: SITE_LAYOUT_V4.ash.silos[0].cadY }
  private processHighlight: ProcessHighlightState = {
    elevatorActive: false,
    airSlideActive: false,
    spoutActive: false,
    spoutX: SITE_LAYOUT_V4.ash.silos[0].cadX,
    spoutY: SITE_LAYOUT_V4.ash.silos[0].cadY,
    antiCollisionActive: false,
    gapLabelPos: { cadX: 3800, cadY: 1525 },
    gapDistanceM: 28.5,
    laserFromPos: null,
    laserTargetPos: null,
    fenceActive: false,
    fencePos: { cadX: SITE_LAYOUT_V4.rebar.bays[6].cadX, cadY: SITE_LAYOUT_V4.rebar.truckLoadingY },
    fenceLabel: '',
    underSiloFenceActive: false,
    underSiloFencePos: { cadX: SITE_LAYOUT_V4.ash.silos[0].cadX, cadY: SITE_LAYOUT_V4.ash.silos[0].cadY },
    underSiloFenceText: '',
    wbInText: '',
    wbOutText: '',
    bayWarningPos: null,
    bayWarningText: '',
  }

  constructor(viewer: Cesium.Viewer, options: Twin3dControllerOptions = {}) {
    this.viewer = viewer
    this.options = options
    this.useProceduralFallback = options.useProceduralFallback ?? false
    this.dataSource = new Cesium.CustomDataSource('dt-twin3d-v4')
    this.allEquipment = [...MOCK_EQUIPMENT]
    viewer.dataSources.add(this.dataSource)

    if (!this.useProceduralFallback) {
      this.gltfProvider = new GltfModelProvider(this.dataSource)
      for (const staticAsset of this.gltfProvider.loadEnvironment()) {
        this.staticAssetMap.set(staticAsset.placement.id, staticAsset)
      }
    }

    this.loadAll()
    this.initProcessVisualizers()
    this.vehicleAnimator = new VehicleRouteAnimator(
      VEHICLE_ROUTES,
      (vehicleId, pose) => this.setVehiclePose(vehicleId, pose),
      () => this.viewer.scene.requestRender(),
    )
    this.setupPickHandler()
  }

  private loadAll() {
    for (const def of this.allEquipment) {
      const built = !this.useProceduralFallback && this.gltfProvider
        ? this.gltfProvider.buildEquipment(def) ?? buildEquipment(this.dataSource, def)
        : buildEquipment(this.dataSource, def)
      this.equipmentMap.set(def.id, built)
    }
    this.viewer.scene.requestRender()
  }

  private initProcessVisualizers() {
    const complex1 = SITE_LAYOUT_V4.ash.unloadingComplexes[0]
    const silo1 = SITE_LAYOUT_V4.ash.silos[0]

    // 1. Vertical Elevator & Air-Slide Material Flow Pulse
    this.elevFlowEntity = this.dataSource.entities.add({
      id: 'BULK-FLOW-ELEVATOR',
      show: false,
      polyline: {
        positions: [
          cadToCartesian3(complex1.cadX, complex1.cadY, 2.5),
          cadToCartesian3(complex1.elevatorCadX, complex1.elevatorCadY, 6.5),
          cadToCartesian3(complex1.elevatorCadX, complex1.elevatorCadY, 38.5),
        ],
        width: 8,
        material: new Cesium.PolylineGlowMaterialProperty({
          glowPower: 0.45,
          color: Cesium.Color.fromCssColorString('#34D399'),
        }),
      },
    })

    this.slideFlowEntity = this.dataSource.entities.add({
      id: 'BULK-FLOW-AIRSLIDE',
      show: false,
      polyline: {
        positions: [
          cadToCartesian3(complex1.elevatorCadX, complex1.elevatorCadY, 38.2),
          cadToCartesian3(silo1.cadX, silo1.cadY, 35.6),
          cadToCartesian3(silo1.cadX, silo1.cadY, 32.0),
        ],
        width: 8,
        material: new Cesium.PolylineGlowMaterialProperty({
          glowPower: 0.45,
          color: Cesium.Color.fromCssColorString('#00E5FF'),
        }),
      },
    })

    // 2. Under-Silo Spout Powder Discharge Stream (aligned with telescoping dust skirt 5.0m -> tank dome 3.65m)
    this.spoutStreamEntity = this.dataSource.entities.add({
      id: 'BULK-FLOW-SPOUT',
      show: false,
      polyline: {
        positions: new Cesium.CallbackProperty(() => [
          cadToCartesian3(this.processHighlight.spoutX, this.processHighlight.spoutY, 5.1),
          cadToCartesian3(this.processHighlight.spoutX, this.processHighlight.spoutY, 3.65),
        ], false),
        width: 12,
        material: new Cesium.PolylineGlowMaterialProperty({
          glowPower: 0.65,
          color: Cesium.Color.fromCssColorString('#F5B84C'),
        }),
      },
    })

    // 3. Anti-Collision Safety Distance Indicator (SCS Interlock)
    this.gapIndicatorEntity = this.dataSource.entities.add({
      id: 'SCS-SAFETY-GAP-BADGE',
      show: false,
      position: new Cesium.CallbackPositionProperty(
        () => cadToCartesian3(this.processHighlight.gapLabelPos.cadX, this.processHighlight.gapLabelPos.cadY, 21.0),
        false,
      ) as unknown as Cesium.PositionProperty,
      label: {
        text: '防碰撞测距 28.5 m (安全)',
        font: '600 12px Microsoft YaHei',
        fillColor: Cesium.Color.fromCssColorString('#34D399'),
        outlineColor: Cesium.Color.fromCssColorString('#020A17'),
        outlineWidth: 2,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        backgroundColor: Cesium.Color.fromCssColorString('#051424').withAlpha(0.88),
        showBackground: true,
        backgroundPadding: new Cesium.Cartesian2(8, 4),
        verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      },
    })

    // 3b. Dynamic 3D SCS Anti-Collision Laser Scan Beam
    this.laserBeamEntity = this.dataSource.entities.add({
      id: 'SCS-LASER-BEAM',
      show: false,
      polyline: {
        positions: new Cesium.CallbackProperty(() => {
          const from = this.processHighlight.laserFromPos
          const to = this.processHighlight.laserTargetPos
          if (!from || !to) return []
          return [
            cadToCartesian3(from.cadX, from.cadY, from.cadZ ?? 12.0),
            cadToCartesian3(to.cadX, to.cadY, to.cadZ ?? 3.5),
          ]
        }, false),
        width: 7,
        material: new Cesium.PolylineGlowMaterialProperty({
          glowPower: 0.65,
          color: new Cesium.CallbackProperty(
            () =>
              Cesium.Color.fromCssColorString(
                this.processHighlight.gapDistanceM <= 6
                  ? '#EF4444'
                  : this.processHighlight.gapDistanceM <= 15
                    ? '#F5B84C'
                    : '#34D399',
              ),
            false,
          ),
        }),
      },
    })

    // 3c. Loading Bay Electronic Fence Perimeter
    this.loadingFenceEntity = this.dataSource.entities.add({
      id: 'SCS-LOADING-FENCE',
      show: false,
      position: new Cesium.CallbackPositionProperty(
        () => cadToCartesian3(this.processHighlight.fencePos.cadX, this.processHighlight.fencePos.cadY, 1.5),
        false,
      ) as unknown as Cesium.PositionProperty,
      box: {
        dimensions: new Cesium.Cartesian3(19.0, 6.2, 3.0),
        material: new Cesium.ColorMaterialProperty(
          new Cesium.CallbackProperty(
            () =>
              Cesium.Color.fromCssColorString(
                this.processHighlight.gapDistanceM <= 15 ? '#F5B84C' : '#00E5FF',
              ).withAlpha(0.18),
            false,
          ) as unknown as Cesium.Color,
        ),
        outline: true,
        outlineColor: new Cesium.CallbackProperty(
          () =>
            Cesium.Color.fromCssColorString(
              this.processHighlight.gapDistanceM <= 15 ? '#FBBF24' : '#38BDF8',
            ),
          false,
        ),
      },
      label: {
        text: '装车电子围栏闭锁 · 司机离车确认',
        font: '700 12px Microsoft YaHei',
        fillColor: Cesium.Color.fromCssColorString('#FDE68A'),
        outlineColor: Cesium.Color.fromCssColorString('#020A17'),
        outlineWidth: 2,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        backgroundColor: Cesium.Color.fromCssColorString('#051424').withAlpha(0.9),
        showBackground: true,
        backgroundPadding: new Cesium.Cartesian2(8, 4),
        verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
        pixelOffset: new Cesium.Cartesian2(0, -36),
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      },
    })

    // 3d. Under-Silo Electronic Fence & Grounding Indicator
    this.underSiloFenceEntity = this.dataSource.entities.add({
      id: 'SILO-UNDER-FENCE',
      show: false,
      position: new Cesium.CallbackPositionProperty(
        () => cadToCartesian3(this.processHighlight.underSiloFencePos.cadX, this.processHighlight.underSiloFencePos.cadY, 1.7),
        false,
      ) as unknown as Cesium.PositionProperty,
      box: {
        dimensions: new Cesium.Cartesian3(17.5, 5.2, 3.4),
        material: new Cesium.ColorMaterialProperty(Cesium.Color.fromCssColorString('#10B981').withAlpha(0.20)),
        outline: true,
        outlineColor: Cesium.Color.fromCssColorString('#34D399'),
      },
      label: {
        text: '库底电子围栏闭锁 · 静电接地 < 4Ω',
        font: '700 12px Microsoft YaHei',
        fillColor: Cesium.Color.fromCssColorString('#6EE7B7'),
        outlineColor: Cesium.Color.fromCssColorString('#020A17'),
        outlineWidth: 2,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        backgroundColor: Cesium.Color.fromCssColorString('#051424').withAlpha(0.92),
        showBackground: true,
        backgroundPadding: new Cesium.Cartesian2(8, 4),
        verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
        pixelOffset: new Cesium.Cartesian2(0, -42),
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      },
    })

    // 3e. Weighbridge Scale Highlights (WB-TRUCK-02 Inbound & WB-TRUCK-01 Outbound)
    const wbOut = SITE_LAYOUT_V4.weighbridges[0]
    const wbIn = SITE_LAYOUT_V4.weighbridges[1]
    this.wbInHighlightEntity = this.dataSource.entities.add({
      id: 'WB-IN-HIGHLIGHT',
      show: false,
      position: cadToCartesian3(wbIn.cadX, wbIn.cadY, 0.75),
      box: {
        dimensions: new Cesium.Cartesian3(19.5, 5.8, 1.5),
        material: new Cesium.ColorMaterialProperty(Cesium.Color.fromCssColorString('#00E5FF').withAlpha(0.24)),
        outline: true,
        outlineColor: Cesium.Color.fromCssColorString('#38BDF8'),
      },
      label: {
        text: 'WB-TRUCK-02 初磅过磅中',
        font: '700 13px Microsoft YaHei',
        fillColor: Cesium.Color.fromCssColorString('#7DD3FC'),
        outlineColor: Cesium.Color.fromCssColorString('#020A17'),
        outlineWidth: 2,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        backgroundColor: Cesium.Color.fromCssColorString('#051424').withAlpha(0.92),
        showBackground: true,
        backgroundPadding: new Cesium.Cartesian2(10, 5),
        verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
        pixelOffset: new Cesium.Cartesian2(0, -90),
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      },
    })

    this.wbOutHighlightEntity = this.dataSource.entities.add({
      id: 'WB-OUT-HIGHLIGHT',
      show: false,
      position: cadToCartesian3(wbOut.cadX, wbOut.cadY, 0.75),
      box: {
        dimensions: new Cesium.Cartesian3(19.5, 5.8, 1.5),
        material: new Cesium.ColorMaterialProperty(Cesium.Color.fromCssColorString('#10B981').withAlpha(0.26)),
        outline: true,
        outlineColor: Cesium.Color.fromCssColorString('#34D399'),
      },
      label: {
        text: 'WB-TRUCK-01 复磅计量中',
        font: '700 13px Microsoft YaHei',
        fillColor: Cesium.Color.fromCssColorString('#6EE7B7'),
        outlineColor: Cesium.Color.fromCssColorString('#020A17'),
        outlineWidth: 2,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        backgroundColor: Cesium.Color.fromCssColorString('#051424').withAlpha(0.92),
        showBackground: true,
        backgroundPadding: new Cesium.Cartesian2(10, 5),
        verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
        pixelOffset: new Cesium.Cartesian2(0, -90),
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      },
    })

    // 3f. Active Rebar Bay High-Inventory Warning Volume (3D Pulsing Highlight)
    this.bayWarningEntity = this.dataSource.entities.add({
      id: 'REBAR-BAY-WARNING',
      show: false,
      position: new Cesium.CallbackPositionProperty(
        () => {
          const p = this.processHighlight.bayWarningPos ?? SITE_LAYOUT_V4.rebar.bays[6]
          return cadToCartesian3(p.cadX, p.cadY, 5.0)
        },
        false,
      ) as unknown as Cesium.PositionProperty,
      box: {
        dimensions: new Cesium.Cartesian3(27.5, 52.0, 9.6),
        material: new Cesium.ColorMaterialProperty(Cesium.Color.fromCssColorString('#EF4444').withAlpha(0.24)),
        outline: true,
        outlineColor: Cesium.Color.fromCssColorString('#FCA5A5'),
      },
      label: {
        text: '库位高库存预警 (≥80%)',
        font: '700 13px Microsoft YaHei',
        fillColor: Cesium.Color.fromCssColorString('#FECACA'),
        outlineColor: Cesium.Color.fromCssColorString('#240505'),
        outlineWidth: 2,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        backgroundColor: Cesium.Color.fromCssColorString('#450A0A').withAlpha(0.92),
        showBackground: true,
        backgroundPadding: new Cesium.Cartesian2(10, 5),
        verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
        pixelOffset: new Cesium.Cartesian2(58, 36),
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
      },
    })

    // 3g. Pre-warm Cesium SDF Glyph TextureAtlas at startup so dynamic process labels never drop glyphs on Frame 1 under requestRenderMode
    const prewarmGlyphChars = [
      ...new Set(
        '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ·./:-%<>Ω✓…()（），, ' +
          '防碰撞测距安全预警减速联锁停机装车电子围栏闭锁司机离车确认门吊慢速落钩库底静电接地对位检测中' +
          '伸缩散装头下落密封定量停料溜管回升复位完成初磅皮重过磅罐车满车复磅计量净重净装高库存余量待机运行吊具下吨',
      ),
    ].join('')
    const prewarmPos = cadToCartesian3(SITE_LAYOUT_V4.siteShellCenter.cadX, 1625, 2.0)
    for (const weight of ['700', '600'] as const) {
      this.dataSource.entities.add({
        id: `PREWARM-GLYPHS-${weight}`,
        show: true,
        position: prewarmPos,
        label: {
          text: prewarmGlyphChars,
          font: `${weight} 12px Microsoft YaHei`,
          fillColor: new Cesium.Color(1, 1, 1, 0.004),
          style: Cesium.LabelStyle.FILL,
          showBackground: false,
          scale: 0.01,
          verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
          disableDepthTestDistance: Number.POSITIVE_INFINITY,
        },
      })
    }
    this.scheduleRenderPump()

    // 4. Individual Silo Selection Highlight Ring (for 40 Silos SILO-01..40)
    this.siloHighlightEntity = this.dataSource.entities.add({
      id: 'SILO-SELECT-HIGHLIGHT',
      show: false,
      position: new Cesium.CallbackPositionProperty(
        () => cadToCartesian3(this.selectedSiloPos.cadX, this.selectedSiloPos.cadY, 17.0),
        false,
      ) as unknown as Cesium.PositionProperty,
      cylinder: {
        length: 34.0,
        topRadius: 7.75,
        bottomRadius: 7.75,
        material: new Cesium.ColorMaterialProperty(Cesium.Color.fromCssColorString('#00E5FF').withAlpha(0.22)),
        outline: true,
        outlineColor: Cesium.Color.fromCssColorString('#00E5FF'),
        numberOfVerticalLines: 16,
      },
    })
  }

  private setupPickHandler() {
    const handler = new Cesium.ScreenSpaceEventHandler(this.viewer.scene.canvas)
    handler.setInputAction((click: { position: Cesium.Cartesian2 }) => {
      const picked = this.viewer.scene.pick(click.position)
      const position = this.viewer.scene.pickPosition(click.position) ?? this.viewer.camera.pickEllipsoid(click.position)

      if (Cesium.defined(picked) && picked.id) {
        const entity = picked.id as Cesium.Entity
        const id = this.resolveRootId(entity)
        if (this.equipmentMap.has(id)) {
          this.selectEquipment(id)
          return
        }
        // When clicking baked environment meshes, resolve individual 3000T Silos (SILO-01..40) or Indoor Steel Bays (REBAR-BAY-01..13)
        if (position && (id === 'ENV-ASH-LINE-01' || id === 'ENV-REBAR-LINE-01' || id === 'ENV-SITE-SHELL-01')) {
          const { cadX, cadY } = cartesian3ToCad(position)
          const siloId = this.findClickedSilo(cadX, cadY)
          if (siloId) {
            this.selectSilo(siloId)
            return
          }
          const bayId = this.findClickedRebarBay(cadX, cadY)
          if (bayId) {
            this.selectRebarBay(bayId)
            return
          }
        }
        if (this.staticAssetMap.has(id)) {
          this.selectStaticAsset(id)
          return
        }
      }
      if (position) {
        const { cadX, cadY } = cartesian3ToCad(position)
        const siloId = this.findClickedSilo(cadX, cadY)
        if (siloId) {
          this.selectSilo(siloId)
          return
        }
      }
      const nearbyId = position ? this.findNearbyEquipment(position, 28) : null
      if (nearbyId) this.selectEquipment(nearbyId)
      else this.clearSelection()
    }, Cesium.ScreenSpaceEventType.LEFT_CLICK)
  }

  private findClickedSilo(cadX: number, cadY: number): string | null {
    let bestId: string | null = null
    let bestDist = 7.9
    for (const silo of SITE_LAYOUT_V4.ash.silos) {
      const dist = Math.hypot(cadX - silo.cadX, cadY - silo.cadY)
      if (dist <= bestDist) {
        bestDist = dist
        bestId = silo.id
      }
    }
    return bestId
  }

  private findClickedRebarBay(cadX: number, cadY: number): string | null {
    const wh = SITE_LAYOUT_V4.rebar.warehouse
    if (cadY < wh.minY || cadY > wh.maxY || cadX < wh.minX || cadX > wh.maxX) return null
    for (const bay of SITE_LAYOUT_V4.rebar.bays) {
      if (cadX >= bay.minX && cadX <= bay.maxX) return bay.id
    }
    return null
  }

  private findNearbyEquipment(position: Cesium.Cartesian3, thresholdM = 40): string | null {
    let bestId: string | null = null
    let bestDistance = thresholdM
    for (const def of this.allEquipment) {
      const center = cadToCartesian3(def.cadX, def.cadY, (def.height ?? 10) / 2)
      const distance = Cesium.Cartesian3.distance(position, center)
      if (distance < bestDistance) {
        bestDistance = distance
        bestId = def.id
      }
    }
    return bestId
  }

  private resolveRootId(entity: Cesium.Entity | undefined): string {
    const raw = entity?.id ?? ''
    if (this.equipmentMap.has(raw) || this.staticAssetMap.has(raw)) return raw
    const propParent = entity?.properties?.parent?.getValue?.()
    if (typeof propParent === 'string' && propParent.length > 0) return propParent
    return raw
  }

  selectEquipment(id: string): SelectedInfo | null {
    if (id.startsWith('SILO-')) return this.selectSilo(id)
    if (id.startsWith('REBAR-BAY-')) return this.selectRebarBay(id)
    const built = this.equipmentMap.get(id)
    if (!built) return null
    if (this.siloHighlightEntity) this.siloHighlightEntity.show = false
    const info: SelectedInfo = {
      id: built.def.id,
      name: built.def.name,
      type: built.def.type,
      statusName: STATUS_NAMES[built.def.status] ?? built.def.status,
      params: { ...built.def.params },
    }
    this.options.onSelect?.(info)
    this.viewer.scene.requestRender()
    return info
  }

  selectSilo(siloId: string): SelectedInfo | null {
    const silo = SITE_LAYOUT_V4.ash.silos.find((s) => s.id === siloId)
    if (!silo) return null
    this.selectedSiloPos = { cadX: silo.cadX, cadY: silo.cadY }
    if (this.siloHighlightEntity) this.siloHighlightEntity.show = true
    const idx = SITE_LAYOUT_V4.ash.silos.indexOf(silo)
    const isCement = silo.material === 'cement'
    const fillPct = 62 + ((idx * 7) % 29)
    const currentTons = Math.round((silo.capacityT * fillPct) / 100)
    const levelM = ((silo.totalHeightM - 4.5) * (fillPct / 100)).toFixed(1)
    const highLevelInterlock = fillPct >= 86 ? '高料位预警（备切相邻仓）' : '正常（联锁使能）'
    const info: SelectedInfo = {
      id: silo.id,
      name: `${silo.code} 3000T ${isCement ? '散装水泥筒仓' : '粉煤灰筒仓'}`,
      type: 'silo',
      statusName: fillPct >= 86 ? '高料位预警' : '正常运行',
      params: {
        仓位编码: `${silo.id}（${silo.row}排-${String(silo.col).padStart(2, '0')}列）`,
        存储物料: isCement ? '散装水泥（堆积密度 1.3 t/m³）' : 'I级粉煤灰（堆积密度 1.1 t/m³）',
        结构尺寸: `Φ${silo.diameterM}m × 高 ${silo.totalHeightM}m（单仓 3,000 t）`,
        实时库存: `${currentTons.toLocaleString()} t（料位 ${levelM}m / ${fillPct}%）`,
        仓顶除尘压差: `${(1.15 + (idx % 5) * 0.08).toFixed(2)} kPa（脉冲反吹正常）`,
        流化气源压力: `${92 + (idx % 6)} kPa（罗茨风机供气）`,
        料位联锁状态: highLevelInterlock,
        控制子系统: '散料智能入库/库底定量装车控制系统',
      },
    }
    this.options.onSelect?.(info)
    this.viewer.scene.requestRender()
    return info
  }

  selectRebarBay(bayId: string): SelectedInfo | null {
    const bay = SITE_LAYOUT_V4.rebar.bays.find((b) => b.id === bayId)
    if (!bay) return null
    if (this.siloHighlightEntity) this.siloHighlightEntity.show = false
    const stockTons = 1420 + bay.bayIndex * 165
    const bayAreaM2 = Math.round((bay.maxX - bay.minX) * (bay.maxY - bay.minY))
    const info: SelectedInfo = {
      id: bay.id,
      name: `钢筋仓库第 ${bay.bayIndex} 跨（配属 ${bay.bridgeCraneId}）`,
      type: 'rebar_bay',
      statusName: '在用库跨',
      params: {
        跨位编号: `第 ${bay.bayIndex} 跨（图纸设备号 ${bay.cadEquipmentCode}）`,
        单跨面积: `${bayAreaM2.toLocaleString()} m²（跨度 ${bay.craneSpanM}m）`,
        配属桥吊: `${bay.bridgeCraneId}（Q=${bay.craneCapacityT}t ${bay.bridgeType === 'chook_32t' ? 'C型卷材吊具' : '电磁吸盘吊具'}）`,
        当前库存: `${stockTons.toLocaleString()} t（S2 WMS 账实同步）`,
        主存品类: bay.bridgeType === 'chook_32t' ? '盘螺 / 热轧卷板（HRB400E）' : '定尺螺纹钢 / 型钢（Φ16~Φ32）',
        安全联锁: '跨间光电防撞 + 库位电子围栏正常',
      },
    }
    this.options.onSelect?.(info)
    this.viewer.scene.requestRender()
    return info
  }

  focusEquipment(id: string): SelectedInfo | null {
    let targetX: number | null = null
    let targetY: number | null = null
    let targetZ = 10
    let viewDistY = 95
    let viewHeight = 52
    let pitchDeg = -26

    const eq = this.equipmentMap.get(id)
    if (eq) {
      targetX = eq.def.cadX
      targetY = eq.def.cadY
      targetZ = Math.min(20, (eq.def.height ?? 12) * 0.5)
      if (eq.def.type === 'bridge_crane') {
        viewDistY = 78
        viewHeight = 42
      } else if (eq.def.type === 'mobile_tipper' || eq.def.type === 'silo_loader') {
        viewDistY = 58
        viewHeight = 30
        pitchDeg = -22
      }
    } else if (id.startsWith('SILO-')) {
      const silo = SITE_LAYOUT_V4.ash.silos.find((s) => s.id === id)
      if (silo) {
        targetX = silo.cadX
        targetY = silo.cadY
        targetZ = 16
        viewDistY = 85
        viewHeight = 48
      }
    } else if (id.startsWith('REBAR-BAY-')) {
      const bay = SITE_LAYOUT_V4.rebar.bays.find((b) => b.id === id)
      if (bay) {
        targetX = bay.cadX
        targetY = bay.cadY
        targetZ = 8
        viewDistY = 90
        viewHeight = 46
      }
    }

    if (targetX !== null && targetY !== null) {
      if (eq?.def.type === 'silo_loader') {
        this.viewer.camera.cancelFlight()
        this.viewer.camera.flyToBoundingSphere(
          new Cesium.BoundingSphere(cadToCartesian3(targetX - 1, targetY, 4.0), 18),
          {
            duration: 1.05,
            offset: new Cesium.HeadingPitchRange(
              Cesium.Math.toRadians(110),
              Cesium.Math.toRadians(-12),
              64,
            ),
          },
        )
      } else {
        const [lon, lat] = cadToLonLat(targetX, targetY - viewDistY)
        this.viewer.camera.flyTo({
          destination: Cesium.Cartesian3.fromDegrees(lon, lat, viewHeight + targetZ * 0.4),
          orientation: {
            heading: Cesium.Math.toRadians(0),
            pitch: Cesium.Math.toRadians(pitchDeg),
            roll: 0,
          },
          duration: 1.05,
        })
      }
    }
    return this.selectEquipment(id)
  }

  setPlannedZonesVisible(visible: boolean) {
    for (const [id, asset] of this.staticAssetMap.entries()) {
      if (asset.placement.state === 'planned') {
        this.gltfProvider?.setStaticVisible(id, visible)
      }
    }
    this.viewer.scene.requestRender()
  }

  private selectStaticAsset(id: string) {
    const asset = this.staticAssetMap.get(id)
    if (!asset) return
    if (this.siloHighlightEntity) this.siloHighlightEntity.show = false
    const { placement } = asset
    this.options.onSelect?.({
      id: placement.id,
      name: placement.name,
      type: placement.type,
      statusName: placement.state === 'planned' ? '规划缓建' : '现建实景',
      params: {
        坐标基准: '给大连大.dwg CGCS2000',
        CAD坐标: `(${placement.cadX.toFixed(1)}, ${placement.cadY.toFixed(1)})`,
        备注: placement.notes,
      },
    })
    this.viewer.scene.requestRender()
  }

  private clearSelection() {
    if (this.siloHighlightEntity) this.siloHighlightEntity.show = false
    this.options.onSelect?.(null)
    this.viewer.scene.requestRender()
  }

  setStatus(id: string, status: EquipmentDef['status']) {
    const built = this.equipmentMap.get(id)
    if (built) {
      built.setStatus(status)
      this.viewer.scene.requestRender()
    }
  }

  setEquipmentLabel(id: string, text: string) {
    this.equipmentMap.get(id)?.setLabelText?.(text)
    this.viewer.scene.requestRender()
  }

  setCranePose(id: string, pose: CranePose) {
    this.equipmentMap.get(id)?.updatePose?.(pose)
    this.viewer.scene.requestRender()
  }

  setBridgeCranePose(id: string, pose: BridgeCranePose) {
    this.equipmentMap.get(id)?.updateBridgePose?.(pose)
    this.viewer.scene.requestRender()
  }

  setTipperPose(id: string, pose: TipperPose) {
    this.equipmentMap.get(id)?.updateTipperPose?.(pose)
    this.viewer.scene.requestRender()
  }

  setLoaderPose(id: string, pose: SiloLoaderPose) {
    this.equipmentMap.get(id)?.updateLoaderPose?.(pose)
    this.viewer.scene.requestRender()
  }

  setVehiclePose(
    id: string,
    pose: VehiclePose,
    height = 0,
    pitchDeg = 0,
    rollDeg = 0,
  ) {
    this.gltfProvider?.updateStaticPose(id, pose.cadX, pose.cadY, pose.headingDeg, height, pitchDeg, rollDeg)
  }

  setActorVisible(id: string, visible: boolean) {
    this.gltfProvider?.setStaticVisible(id, visible)
  }

  private scheduleRenderPump() {
    this.viewer.scene.requestRender()
    if (this.renderPumpRaf === null) {
      this.renderPumpRaf = requestAnimationFrame(() => {
        this.renderPumpRaf = null
        this.viewer.scene.requestRender()
      })
    }
    for (const timer of this.renderPumpTimers) clearTimeout(timer)
    this.renderPumpTimers = [45, 110, 220].map((delay) =>
      setTimeout(() => {
        this.viewer.scene.requestRender()
      }, delay),
    )
  }

  setProcessHighlight(patch: Partial<ProcessHighlightState>) {
    Object.assign(this.processHighlight, patch)
    let labelUpdated = false

    if (this.elevFlowEntity) this.elevFlowEntity.show = this.processHighlight.elevatorActive
    if (this.slideFlowEntity) this.slideFlowEntity.show = this.processHighlight.airSlideActive
    if (this.spoutStreamEntity) this.spoutStreamEntity.show = this.processHighlight.spoutActive
    if (this.gapIndicatorEntity) {
      const nextShow = this.processHighlight.antiCollisionActive
      if (this.gapIndicatorEntity.show !== nextShow) labelUpdated = true
      this.gapIndicatorEntity.show = nextShow
      const gapText = `防碰撞测距 ${this.processHighlight.gapDistanceM.toFixed(1)} m (${
        this.processHighlight.gapDistanceM <= 6 ? '联锁停机' : this.processHighlight.gapDistanceM <= 15 ? '预警减速' : '安全'
      })`
      const gapColorCss =
        this.processHighlight.gapDistanceM <= 6 ? '#EF4444' : this.processHighlight.gapDistanceM <= 15 ? '#F5B84C' : '#34D399'
      if (gapText !== this.lastLabelTexts.gap && this.gapIndicatorEntity.label) {
        this.lastLabelTexts.gap = gapText
        this.gapIndicatorEntity.label.text = new Cesium.ConstantProperty(gapText)
        labelUpdated = true
      }
      if (gapColorCss !== this.lastLabelTexts.gapColor && this.gapIndicatorEntity.label) {
        this.lastLabelTexts.gapColor = gapColorCss
        this.gapIndicatorEntity.label.fillColor = new Cesium.ConstantProperty(Cesium.Color.fromCssColorString(gapColorCss))
        labelUpdated = true
      }
    }
    if (this.laserBeamEntity) {
      this.laserBeamEntity.show =
        this.processHighlight.antiCollisionActive &&
        Boolean(this.processHighlight.laserFromPos) &&
        Boolean(this.processHighlight.laserTargetPos)
    }
    if (this.loadingFenceEntity) {
      const nextShow = Boolean(this.processHighlight.fenceActive)
      if (this.loadingFenceEntity.show !== nextShow) labelUpdated = true
      this.loadingFenceEntity.show = nextShow
      const fenceText = this.processHighlight.fenceLabel || '装车电子围栏闭锁 · 司机离车确认'
      if (fenceText !== this.lastLabelTexts.fence && this.loadingFenceEntity.label) {
        this.lastLabelTexts.fence = fenceText
        this.loadingFenceEntity.label.text = new Cesium.ConstantProperty(fenceText)
        labelUpdated = true
      }
    }
    if (this.underSiloFenceEntity) {
      const nextShow = Boolean(this.processHighlight.underSiloFenceActive)
      if (this.underSiloFenceEntity.show !== nextShow) labelUpdated = true
      this.underSiloFenceEntity.show = nextShow
      const underText = this.processHighlight.underSiloFenceText || '库底电子围栏闭锁 · 静电接地 < 4Ω'
      if (underText !== this.lastLabelTexts.underFence && this.underSiloFenceEntity.label) {
        this.lastLabelTexts.underFence = underText
        this.underSiloFenceEntity.label.text = new Cesium.ConstantProperty(underText)
        labelUpdated = true
      }
    }
    if (this.wbInHighlightEntity) {
      const nextShow = Boolean(this.processHighlight.wbInText)
      if (this.wbInHighlightEntity.show !== nextShow) labelUpdated = true
      this.wbInHighlightEntity.show = nextShow
      const wbInText = this.processHighlight.wbInText || 'WB-TRUCK-02 初磅过磅中'
      if (wbInText !== this.lastLabelTexts.wbIn && this.wbInHighlightEntity.label) {
        this.lastLabelTexts.wbIn = wbInText
        this.wbInHighlightEntity.label.text = new Cesium.ConstantProperty(wbInText)
        labelUpdated = true
      }
    }
    if (this.wbOutHighlightEntity) {
      const nextShow = Boolean(this.processHighlight.wbOutText)
      if (this.wbOutHighlightEntity.show !== nextShow) labelUpdated = true
      this.wbOutHighlightEntity.show = nextShow
      const wbOutText = this.processHighlight.wbOutText || 'WB-TRUCK-01 复磅计量中'
      if (wbOutText !== this.lastLabelTexts.wbOut && this.wbOutHighlightEntity.label) {
        this.lastLabelTexts.wbOut = wbOutText
        this.wbOutHighlightEntity.label.text = new Cesium.ConstantProperty(wbOutText)
        labelUpdated = true
      }
    }
    if (this.bayWarningEntity) {
      const nextShow = Boolean(this.processHighlight.bayWarningPos)
      if (this.bayWarningEntity.show !== nextShow) labelUpdated = true
      this.bayWarningEntity.show = nextShow
      const bayWarnText = this.processHighlight.bayWarningText || '库位高库存预警 (≥80%)'
      if (bayWarnText !== this.lastLabelTexts.bayWarn && this.bayWarningEntity.label) {
        this.lastLabelTexts.bayWarn = bayWarnText
        this.bayWarningEntity.label.text = new Cesium.ConstantProperty(bayWarnText)
        labelUpdated = true
      }
    }
    if (labelUpdated) {
      this.scheduleRenderPump()
    } else {
      this.viewer.scene.requestRender()
    }
  }

  setVehicleRouteAnimation(enabled: boolean) {
    if (!this.vehicleAnimator) return
    if (enabled) this.vehicleAnimator.start()
    else this.vehicleAnimator.stop()
  }

  getStatusSummary() {
    const counts: Record<string, number> = { ok: 0, standby: 0, fault: 0, offline: 0, maintenance: 0 }
    for (const eq of this.equipmentMap.values()) {
      counts[eq.def.status] = (counts[eq.def.status] ?? 0) + 1
    }
    return { total: this.equipmentMap.size, counts }
  }

  startMockSimulation(intervalMs = 3000) {
    this.stopMockSimulation()
    const statuses: Array<EquipmentDef['status']> = ['ok', 'ok', 'ok', 'standby', 'fault', 'maintenance']
    const ids = Array.from(this.equipmentMap.keys())
    this.mockTimer = setInterval(() => {
      if (!ids.length) return
      const id = ids[Math.floor(Math.random() * ids.length)]
      const st = statuses[Math.floor(Math.random() * statuses.length)]
      this.setStatus(id, st)
    }, intervalMs)
  }

  stopMockSimulation() {
    if (this.mockTimer) {
      clearInterval(this.mockTimer)
      this.mockTimer = null
    }
  }

  destroy() {
    this.stopMockSimulation()
    if (this.renderPumpRaf !== null) cancelAnimationFrame(this.renderPumpRaf)
    for (const timer of this.renderPumpTimers) clearTimeout(timer)
    this.renderPumpTimers = []
    this.vehicleAnimator?.stop()
    this.viewer.dataSources.remove(this.dataSource)
  }
}

export { STATUS_COLORS, STATUS_NAMES }
