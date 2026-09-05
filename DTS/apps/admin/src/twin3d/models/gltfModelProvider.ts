/** Offline GLB provider for the production core scene. */
import * as Cesium from 'cesium'
import type { EquipmentDef } from '../constants'
import { STATUS_COLORS } from '../constants'
import { cadToCartesian3 } from '../geo'
import {
  CORE_PLACEMENTS,
  getPlacement,
  getZone,
  type AssetPlacement,
  type PlacementType,
} from '../placements'
import { getModel } from './modelManifest'
import type { BuiltEquipment, CranePose } from './buildEquipment'

function statusColor(status: string): Cesium.Color {
  return Cesium.Color.fromCssColorString(STATUS_COLORS[status] ?? '#64748B')
}

// Blender assets are authored with CAD +X as their longitudinal axis.
// Cesium Heading 0 points north, while CAD +X is the local east direction.
// Keep this conversion in the single GLB provider so placements never carry
// ad-hoc 90-degree fixes and all static/movable components stay aligned.
const CAD_X_TO_CESIUM_HEADING_DEG = 90

function orientation(position: Cesium.Cartesian3, headingDeg: number): Cesium.Quaternion {
  return Cesium.Transforms.headingPitchRollQuaternion(
    position,
    new Cesium.HeadingPitchRoll(Cesium.Math.toRadians(headingDeg + CAD_X_TO_CESIUM_HEADING_DEG)),
  )
}

function modelEntity(
  dataSource: Cesium.CustomDataSource,
  id: string,
  modelKey: string,
  placement: AssetPlacement,
  height = 0,
  parent?: string,
): Cesium.Entity {
  const descriptor = getModel(modelKey)
  const position = cadToCartesian3(placement.cadX, placement.cadY, height)
  const planned = placement.state === 'planned'
  return dataSource.entities.add({
    id,
    position,
    orientation: orientation(position, placement.headingDeg),
    model: {
      uri: descriptor.modelUrl,
      scale: placement.scale,
      minimumPixelSize: descriptor.minimumPixelSize,
      maximumScale: descriptor.maximumScale,
      runAnimations: false,
      incrementallyLoadTextures: false,
      // Keep the authored PBR paint, concrete and emissive-window materials
      // readable against the intentionally dark industrial night background.
      lightColor: Cesium.Color.fromCssColorString('#B7D6FF'),
      imageBasedLightingFactor: new Cesium.Cartesian2(0.95, 0.72),
      color: planned ? Cesium.Color.fromCssColorString('#24B6E8').withAlpha(0.48) : undefined,
      colorBlendMode: planned ? Cesium.ColorBlendMode.MIX : undefined,
      colorBlendAmount: planned ? 0.72 : undefined,
      silhouetteColor: planned ? Cesium.Color.fromCssColorString('#52D9FF').withAlpha(0.92) : undefined,
      silhouetteSize: planned ? 1.4 : undefined,
    },
    properties: {
      parent: parent ?? id,
      type: placement.type,
      zone: placement.zone,
      state: placement.state,
      source: placement.source,
      confidence: placement.confidence,
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
) {
  const position = cadToCartesian3(cadX, cadY, height)
  entity.position = new Cesium.ConstantPositionProperty(position)
  entity.orientation = new Cesium.ConstantProperty(orientation(position, headingDeg))
}

export interface StaticSiteAsset {
  placement: AssetPlacement
  entities: Cesium.Entity[]
}

const MODEL_KEY_BY_TYPE: Partial<Record<PlacementType, string>> = {
  wagon: 'wagon',
  rebar_bay: 'rebar-bay',
  site_shell: 'site-shell',
}

/**
 * Replaces primitive Cesium boxes/cylinders in the production path.
 * GLB meshes remain offline; a small status beacon keeps state colour separate from asset materials.
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
    if (!rebarEnvironment || !ashEnvironment) throw new Error('Missing confirmed rail-line environment placement')
    const result: StaticSiteAsset[] = []
    const siteShell = getPlacement('ENV-SITE-SHELL-01')
    if (siteShell) result.push(this.registerStatic(siteShell, 'site-shell'))
    result.push(this.registerStatic(rebarEnvironment, 'core-environment'))
    result.push(this.registerStatic(ashEnvironment, 'ash-environment'))
    // The approved ash-line relationship is authored as one Blender GLB at
    // ENV-ASH-LINE-01.  Do not independently place the green/purple groups or
    // conveyor here: that was the source of their visible separation in web.
    for (const placement of CORE_PLACEMENTS.filter((item) => (
      item.type === 'wagon' || item.type === 'rebar_bay'
    ))) {
      const modelKey = MODEL_KEY_BY_TYPE[placement.type]
      if (modelKey) result.push(this.registerStatic(placement, modelKey))
    }
    // site-shell-v1 is the only production source for the outer station
    // blockout.  Do not also load the legacy per-zone GLBs here; doing so
    // duplicates processing/support/planned masses and makes the overview look
    // spatially wrong even when the shared CAD coordinates are correct.
    this.addNightSceneAccents()
    return result
  }

  getStaticAssets(): StaticSiteAsset[] {
    return [...this.staticAssets.values()]
  }

  updateStaticPose(id: string, cadX: number, cadY: number, headingDeg: number) {
    const asset = this.staticAssets.get(id)
    if (!asset) return
    updateEntityPosition(asset.entities[0], asset.placement, cadX, cadY, 0, headingDeg)
  }

  buildEquipment(definition: EquipmentDef): BuiltEquipment | null {
    const placement = getPlacement(definition.id)
    if (!placement) return null
    const def: EquipmentDef = { ...definition, cadX: placement.cadX, cadY: placement.cadY, height: placement.height ?? definition.height }

    if (placement.type === 'gantry_crane') return this.buildGantry(def, placement)
    return null
  }

  private buildGantry(def: EquipmentDef, placement: AssetPlacement): BuiltEquipment {
    const frame = modelEntity(this.dataSource, `${def.id}-frame`, 'gantry-frame', placement, 0, def.id)
    const craneHeight = placement.height ?? 24
    const trolley = modelEntity(this.dataSource, `${def.id}-trolley`, 'gantry-trolley', placement, craneHeight, def.id)
    const hoist = modelEntity(this.dataSource, `${def.id}-hoist`, 'gantry-hoist', placement, 16, def.id)
    const beacon = this.addStatusBeacon(def, placement, craneHeight + 2)
    let pose: CranePose = { gantryX: placement.cadX, gantryY: placement.cadY, trolleyOffset: 0, hoistHeight: 16 }

    const cable = this.dataSource.entities.add({
      id: `${def.id}-cable`,
      polyline: {
        positions: new Cesium.CallbackProperty(() => {
          const upper = cadToCartesian3(pose.gantryX, pose.gantryY + pose.trolleyOffset, craneHeight + 0.8)
          const lower = cadToCartesian3(pose.gantryX, pose.gantryY + pose.trolleyOffset, pose.hoistHeight)
          return [upper, lower]
        }, false),
        width: 2.5,
        material: new Cesium.PolylineOutlineMaterialProperty({
          color: Cesium.Color.fromCssColorString('#1E293B'),
          outlineColor: Cesium.Color.fromCssColorString('#64748B'),
          outlineWidth: 1,
        }),
      },
      properties: { parent: def.id, type: def.type },
    })

    const updatePose = (nextPose: CranePose) => {
      pose = nextPose
      updateEntityPosition(frame, placement, pose.gantryX, pose.gantryY, 0)
      updateEntityPosition(trolley, placement, pose.gantryX, pose.gantryY + pose.trolleyOffset, craneHeight)
      updateEntityPosition(hoist, placement, pose.gantryX, pose.gantryY + pose.trolleyOffset, pose.hoistHeight)
      updateEntityPosition(beacon, placement, pose.gantryX - 2, pose.gantryY, craneHeight + 2)
    }

    return {
      def,
      entities: [frame, trolley, hoist, cable, beacon],
      setStatus: (status) => {
        def.status = status
        beacon.ellipsoid!.material = new Cesium.ColorMaterialProperty(statusColor(status))
      },
      updatePose,
    }
  }

  private addStatusBeacon(def: EquipmentDef, placement: AssetPlacement, height: number): Cesium.Entity {
    const position = cadToCartesian3(placement.cadX - 2, placement.cadY, height)
    return this.dataSource.entities.add({
      id: `${def.id}-status`,
      position,
      ellipsoid: {
        radii: new Cesium.Cartesian3(0.55, 0.55, 0.55),
        material: new Cesium.ColorMaterialProperty(statusColor(def.status)),
        outline: true,
        outlineColor: Cesium.Color.WHITE.withAlpha(0.6),
      },
      properties: { parent: def.id, type: def.type },
    })
  }

  private registerStatic(placement: AssetPlacement, modelKey: string): StaticSiteAsset {
    const entities = [modelEntity(this.dataSource, placement.id, modelKey, placement)]
    if (placement.state === 'planned') entities.push(...this.addPlannedOverlay(placement))
    const asset = { placement, entities }
    this.staticAssets.set(placement.id, asset)
    return asset
  }

  /**
   * Small emissive work-light markers complement the lamp geometry authored in
   * Blender.  They are intentionally not status indicators and therefore never
   * change colour with alarms or planned/existing state.
   */
  private addNightSceneAccents(): void {
    const warmLight = Cesium.Color.fromCssColorString('#FFB45C')
    const coolLight = Cesium.Color.fromCssColorString('#78D7FF')
    const accents: Array<{ id: string; cadX: number; cadY: number; height: number; color: Cesium.Color; size?: number }> = [
      { id: 'NIGHT-REBAR-01', cadX: 3600, cadY: 1655, height: 12, color: warmLight },
      { id: 'NIGHT-REBAR-02', cadX: 3680, cadY: 1655, height: 12, color: warmLight },
      { id: 'NIGHT-REBAR-03', cadX: 3760, cadY: 1655, height: 12, color: warmLight },
      { id: 'NIGHT-REBAR-04', cadX: 3840, cadY: 1655, height: 12, color: warmLight },
      { id: 'NIGHT-REBAR-05', cadX: 3920, cadY: 1655, height: 12, color: warmLight },
      { id: 'NIGHT-PROC-01', cadX: 3622, cadY: 1801, height: 11, color: warmLight },
      { id: 'NIGHT-PROC-02', cadX: 3690, cadY: 1801, height: 11, color: warmLight },
      { id: 'NIGHT-PROC-03', cadX: 3755, cadY: 1801, height: 11, color: warmLight },
      { id: 'NIGHT-BAY-01', cadX: 3600, cadY: 1710, height: 4, color: coolLight, size: 0.3 },
      { id: 'NIGHT-BAY-02', cadX: 3680, cadY: 1710, height: 4, color: coolLight, size: 0.3 },
      { id: 'NIGHT-BAY-03', cadX: 3760, cadY: 1710, height: 4, color: coolLight, size: 0.3 },
      { id: 'NIGHT-BAY-04', cadX: 3840, cadY: 1710, height: 4, color: coolLight, size: 0.3 },
      { id: 'NIGHT-BAY-05', cadX: 3920, cadY: 1710, height: 4, color: coolLight, size: 0.3 },
      // These are environmental work lights only.  Ash-silo material colours
      // and the conveyor never become status/alert colours.
      { id: 'NIGHT-ASH-01', cadX: 3740, cadY: 1518, height: 10, color: warmLight },
      { id: 'NIGHT-ASH-02', cadX: 3830, cadY: 1518, height: 10, color: warmLight },
      { id: 'NIGHT-ASH-03', cadX: 3930, cadY: 1518, height: 10, color: warmLight },
      { id: 'NIGHT-ASH-04', cadX: 4015, cadY: 1518, height: 10, color: warmLight },
    ]

    for (const accent of accents) {
      this.dataSource.entities.add({
        id: accent.id,
        position: cadToCartesian3(accent.cadX, accent.cadY, accent.height),
        ellipsoid: {
          radii: new Cesium.Cartesian3(accent.size ?? 0.42, accent.size ?? 0.42, accent.size ?? 0.42),
          material: new Cesium.ColorMaterialProperty(accent.color.withAlpha(0.95)),
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
      position: cadToCartesian3(placement.cadX, placement.cadY, 18),
      label: {
        text: '规划中\n转运站初步方案',
        font: '14px Microsoft YaHei',
        fillColor: Cesium.Color.fromCssColorString('#8CE9FF'),
        outlineColor: Cesium.Color.fromCssColorString('#071228'),
        outlineWidth: 3,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        pixelOffset: new Cesium.Cartesian2(0, -18),
      },
      properties: { parent: placement.id, type: placement.type, state: 'planned' },
    })
    return [boundary, label]
  }
}
