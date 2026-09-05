import { SITE_LAYOUT_V3, assertSiteLayoutV3 } from './siteLayoutV3'

/**
 * Production placement source of truth.
 *
 * Coordinates are CAD-local metres. The production baseline was explicitly
 * authorized from the reviewed layout; unsurveyed dimensions remain marked as
 * inferred-proportional instead of being presented as measured facts.
 */
export type PlacementType =
  | 'gantry_crane'
  | 'wagon'
  | 'rebar_bay'
  | 'core_environment'
  | 'ash_environment'
  | 'ash_line_silo_group'
  | 'ash_round_silo_group'
  | 'ash_conveyor'
  | 'material_processing'
  | 'support_facilities'
  | 'planned_transfer'
  | 'site_shell'
  | 'bulk_truck'
  | 'forklift'

export type SiteZoneId =
  | 'core-loading'
  | 'rebar-storage'
  | 'ash-rail-silo'
  | 'material-processing'
  | 'support-services'
  | 'planned-transfer'
  | 'site-shell'

export type SiteState = 'existing' | 'planned'
export type PlacementSource = 'site-plan-vector' | 'cad-track-axis-inference' | 'simulation-route'
export type Confidence = 'confirmed' | 'inferred'

export interface CadPolygon {
  points: Array<[number, number]>
}

export interface SiteZone {
  id: SiteZoneId
  name: string
  function: string
  state: SiteState
  source: 'site-plan-vector' | 'cad-track-axis-inference'
  confidence: Confidence
  dimensionSource: 'site-plan-vector' | 'cad-track-axis-inference'
  polygon: CadPolygon
}

export interface AssetPlacement {
  id: string
  name: string
  type: PlacementType
  cadX: number
  cadY: number
  headingDeg: number
  scale: number
  zone: SiteZoneId
  state: SiteState
  source: PlacementSource
  confidence: Confidence
  height?: number
  dimensions?: { length: number; width: number; height: number }
  /** A confirmed location/boundary can still use non-surveyed 3D proportions. */
  geometryStatus?: 'confirmed' | 'inferred-proportional'
}

export const SITE_ZONES: SiteZone[] = [
  {
    id: 'site-shell',
    name: '全站场地外壳',
    function: '总平面边界、道路、围栏、绿化与低细节功能区概形',
    state: 'existing',
    source: 'site-plan-vector',
    confidence: 'confirmed',
    dimensionSource: 'site-plan-vector',
    polygon: { points: SITE_LAYOUT_V3.siteShell.bounds },
  },
  {
    id: 'core-loading',
    name: '钢材铁路装卸作业线',
    function: '两条钢材铁路股道与三台龙门吊作业',
    state: 'existing',
    source: 'cad-track-axis-inference',
    confidence: 'confirmed',
    dimensionSource: 'cad-track-axis-inference',
    polygon: { points: [[SITE_LAYOUT_V3.rebar.trackRangeX[0], 1660], [SITE_LAYOUT_V3.rebar.trackRangeX[1], 1660], [SITE_LAYOUT_V3.rebar.trackRangeX[1], 1688], [SITE_LAYOUT_V3.rebar.trackRangeX[0], 1688]] },
  },
  {
    id: 'rebar-storage',
    name: '钢筋露天仓位区',
    function: '13 个独立钢筋露天料位',
    state: 'existing',
    source: 'cad-track-axis-inference',
    confidence: 'confirmed',
    dimensionSource: 'cad-track-axis-inference',
    polygon: { points: [[SITE_LAYOUT_V3.rebar.bayBoundsX[0][0], SITE_LAYOUT_V3.rebar.bayBoundsY[0]], [SITE_LAYOUT_V3.rebar.bayBoundsX.at(-1)![1], SITE_LAYOUT_V3.rebar.bayBoundsY[0]], [SITE_LAYOUT_V3.rebar.bayBoundsX.at(-1)![1], SITE_LAYOUT_V3.rebar.bayBoundsY[1]], [SITE_LAYOUT_V3.rebar.bayBoundsX[0][0], SITE_LAYOUT_V3.rebar.bayBoundsY[1]]] },
  },
  {
    id: 'ash-rail-silo',
    name: '煤灰铁路与筒仓作业区',
    function: '两条煤灰铁路股道、绿色线性筒仓组、紫色圆形筒仓组及输送带',
    state: 'existing',
    source: 'site-plan-vector',
    confidence: 'confirmed',
    dimensionSource: 'site-plan-vector',
    polygon: { points: [[3470, 1500], [4068, 1500], [4068, 1562], [3470, 1562]] },
  },
  {
    id: 'material-processing',
    name: '物料加工区',
    function: '物料加工与装运准备',
    state: 'existing',
    source: 'site-plan-vector',
    confidence: 'confirmed',
    dimensionSource: 'site-plan-vector',
    polygon: { points: [[3588, 1807], [3788.7, 1807], [3788.7, 1834.5], [3588, 1834.5]] },
  },
  {
    id: 'support-services',
    name: '配套服务区',
    function: '门卫、休息室、公卫、变电所与出入口服务',
    state: 'existing',
    source: 'site-plan-vector',
    confidence: 'confirmed',
    dimensionSource: 'site-plan-vector',
    polygon: { points: [[4428, 1494], [4556, 1494], [4556, 1555], [4428, 1555]] },
  },
  {
    id: 'planned-transfer',
    name: '转运站初步方案区',
    function: '规划转运站',
    state: 'planned',
    source: 'site-plan-vector',
    confidence: 'confirmed',
    dimensionSource: 'site-plan-vector',
    polygon: { points: [[4334.9, 1557.3], [4482.3, 1557.3], [4482.3, 1603.6], [4334.9, 1603.6]] },
  },
]

const REBAR_BAY_X_BOUNDS = SITE_LAYOUT_V3.rebar.bayBoundsX
const REBAR_BAY_Y_BOUNDS = SITE_LAYOUT_V3.rebar.bayBoundsY
const REBAR_LINE_CENTER_Y = SITE_LAYOUT_V3.rebar.origin.cadY
const REBAR_TRACK_CENTER_YS = SITE_LAYOUT_V3.rebar.trackCentersY
const ASH_TRACK_CENTER_YS = SITE_LAYOUT_V3.ash.trackCentersY
const ASH_SILO_CENTER_Y = SITE_LAYOUT_V3.ash.siloCenterY
const ASH_GREEN_LEFT_X_BOUNDS = SITE_LAYOUT_V3.ash.greenLeftBounds
const ASH_PURPLE_MIDDLE_X_BOUNDS = SITE_LAYOUT_V3.ash.purpleMiddleBounds
const ASH_GREEN_RIGHT_X_BOUNDS = SITE_LAYOUT_V3.ash.greenRightBounds
const GANTRY_PLACEMENTS = SITE_LAYOUT_V3.rebar.gantries.map(({ id, cadX }) => [id, `龙门吊 ${id.slice(-1)}`, cadX] as const)

export const CORE_PLACEMENTS: AssetPlacement[] = [
  {
    id: 'ENV-REBAR-LINE-01', name: '钢材两轨作业环境', type: 'core_environment', cadX: SITE_LAYOUT_V3.rebar.origin.cadX, cadY: REBAR_LINE_CENTER_Y,
    headingDeg: 0, scale: 1, zone: 'core-loading', state: 'existing',
    source: 'cad-track-axis-inference', confidence: 'confirmed', dimensions: { length: 393.179, width: 96, height: 24 },
  },
  {
    id: 'ENV-ASH-LINE-01', name: '煤灰两轨作业环境', type: 'ash_environment', cadX: SITE_LAYOUT_V3.ash.origin.cadX, cadY: SITE_LAYOUT_V3.ash.origin.cadY,
    headingDeg: 0, scale: 1, zone: 'ash-rail-silo', state: 'existing',
    source: 'cad-track-axis-inference', confidence: 'confirmed',
    dimensions: { length: 343.154, width: 120, height: 0.28 }, geometryStatus: 'confirmed',
  },
  {
    id: 'ASH-SILO-GREEN-LEFT-01', name: '左侧绿色线性煤灰筒仓组', type: 'ash_line_silo_group',
    cadX: (ASH_GREEN_LEFT_X_BOUNDS[0] + ASH_GREEN_LEFT_X_BOUNDS[1]) / 2, cadY: ASH_SILO_CENTER_Y,
    headingDeg: 0, scale: 1, zone: 'ash-rail-silo', state: 'existing',
    source: 'site-plan-vector', confidence: 'inferred',
    dimensions: { length: ASH_GREEN_LEFT_X_BOUNDS[1] - ASH_GREEN_LEFT_X_BOUNDS[0], width: 28, height: 16 }, geometryStatus: 'inferred-proportional',
  },
  {
    id: 'ASH-SILO-PURPLE-MIDDLE-01', name: '中央紫色圆形煤灰筒仓组', type: 'ash_round_silo_group',
    cadX: (ASH_PURPLE_MIDDLE_X_BOUNDS[0] + ASH_PURPLE_MIDDLE_X_BOUNDS[1]) / 2, cadY: ASH_SILO_CENTER_Y,
    headingDeg: 0, scale: 1, zone: 'ash-rail-silo', state: 'existing',
    source: 'site-plan-vector', confidence: 'inferred',
    dimensions: { length: ASH_PURPLE_MIDDLE_X_BOUNDS[1] - ASH_PURPLE_MIDDLE_X_BOUNDS[0], width: 28, height: 20 }, geometryStatus: 'inferred-proportional',
  },
  {
    id: 'ASH-SILO-GREEN-RIGHT-01', name: '右侧绿色线性煤灰筒仓组', type: 'ash_line_silo_group',
    cadX: (ASH_GREEN_RIGHT_X_BOUNDS[0] + ASH_GREEN_RIGHT_X_BOUNDS[1]) / 2, cadY: ASH_SILO_CENTER_Y,
    headingDeg: 0, scale: 1, zone: 'ash-rail-silo', state: 'existing',
    source: 'site-plan-vector', confidence: 'inferred',
    dimensions: { length: ASH_GREEN_RIGHT_X_BOUNDS[1] - ASH_GREEN_RIGHT_X_BOUNDS[0], width: 28, height: 16 }, geometryStatus: 'inferred-proportional',
  },
  {
    id: 'ASH-CONVEYOR-01', name: '煤灰筒仓进料输送带', type: 'ash_conveyor', cadX: 3765, cadY: 1547,
    headingDeg: 0, scale: 1, zone: 'ash-rail-silo', state: 'existing',
    source: 'site-plan-vector', confidence: 'inferred',
    dimensions: { length: 190, width: 1.5, height: 8 }, geometryStatus: 'inferred-proportional',
  },
  ...GANTRY_PLACEMENTS.map(([id, name, cadX]): AssetPlacement => ({
    id, name, type: 'gantry_crane', cadX, cadY: REBAR_LINE_CENTER_Y,
    headingDeg: 0, scale: 1, zone: 'core-loading', state: 'existing',
    source: 'cad-track-axis-inference', confidence: 'inferred', height: 24,
    dimensions: { length: 12, width: 80.104, height: 24 }, geometryStatus: 'inferred-proportional',
  })),
  ...SITE_LAYOUT_V3.rebar.wagonXs.map((cadX, index): AssetPlacement => ({
    id: `WAGON-FLAT-${String(index + 1).padStart(2, '0')}`, name: `钢材铁路平车 ${index + 1}`, type: 'wagon', cadX, cadY: REBAR_TRACK_CENTER_YS[0],
    headingDeg: 0, scale: 1, zone: 'core-loading', state: 'existing',
    source: 'cad-track-axis-inference', confidence: 'inferred', dimensions: { length: 13.2, width: 3.35, height: 3.1 },
  })),
  ...REBAR_BAY_X_BOUNDS.map(([minX, maxX], index): AssetPlacement => ({
    id: `REBAR-BAY-${String(index + 1).padStart(2, '0')}`, name: `钢筋仓位 ${String(index + 1).padStart(2, '0')}`, type: 'rebar_bay',
    cadX: (minX + maxX) / 2, cadY: (REBAR_BAY_Y_BOUNDS[0] + REBAR_BAY_Y_BOUNDS[1]) / 2,
    headingDeg: 0, scale: 1, zone: 'rebar-storage', state: 'existing',
    source: 'cad-track-axis-inference', confidence: 'confirmed',
    dimensions: { length: maxX - minX, width: REBAR_BAY_Y_BOUNDS[1] - REBAR_BAY_Y_BOUNDS[0], height: 3.2 },
  })),
]

export const STATION_PLACEMENTS: AssetPlacement[] = [
  {
    id: 'ENV-SITE-SHELL-01', name: '全站场地外壳', type: 'site_shell',
    cadX: (SITE_LAYOUT_V3.siteShell.bounds[0][0] + SITE_LAYOUT_V3.siteShell.bounds[1][0]) / 2,
    cadY: (SITE_LAYOUT_V3.siteShell.bounds[0][1] + SITE_LAYOUT_V3.siteShell.bounds[2][1]) / 2,
    headingDeg: 0, scale: 1, zone: 'site-shell', state: 'existing',
    source: 'site-plan-vector', confidence: 'confirmed',
    dimensions: { length: 1400, width: 480, height: 8 }, geometryStatus: 'inferred-proportional',
  },
  {
    id: 'PROC-01', name: '物料加工区', type: 'material_processing', cadX: 3688, cadY: 1820.8,
    headingDeg: 0, scale: 1, zone: 'material-processing', state: 'existing',
    source: 'site-plan-vector', confidence: 'confirmed', dimensions: { length: 72, width: 30, height: 17 },
  },
  {
    id: 'SUPPORT-01', name: '配套服务区', type: 'support_facilities', cadX: 4492, cadY: 1524,
    headingDeg: 0, scale: 1, zone: 'support-services', state: 'existing',
    source: 'site-plan-vector', confidence: 'confirmed', dimensions: { length: 90, width: 62, height: 12 },
  },
  {
    id: 'PLAN-TRANSFER-01', name: '转运站（规划中）', type: 'planned_transfer', cadX: 4408.6, cadY: 1580.5,
    headingDeg: 0, scale: 1, zone: 'planned-transfer', state: 'planned',
    source: 'site-plan-vector', confidence: 'confirmed', dimensions: { length: 128, width: 46, height: 16 },
  },
]

export const ALL_PLACEMENTS = [...CORE_PLACEMENTS, ...STATION_PLACEMENTS]

function assertCoreLayoutConstraints(): void {
  assertSiteLayoutV3()
  const maxSteelTrackY = Math.max(...REBAR_TRACK_CENTER_YS)
  const maxAshTrackY = Math.max(...ASH_TRACK_CENTER_YS)
  const minSiloX = ASH_GREEN_LEFT_X_BOUNDS[0]
  const maxSiloX = ASH_GREEN_RIGHT_X_BOUNDS[1]

  if (REBAR_BAY_Y_BOUNDS[0] <= maxSteelTrackY) {
    throw new Error('Invalid core layout: rebar bays must remain above both steel tracks.')
  }
  if (ASH_SILO_CENTER_Y <= maxAshTrackY) {
    throw new Error('Invalid core layout: ash silos must remain above both ash tracks.')
  }
  if (!(ASH_GREEN_LEFT_X_BOUNDS[1] < ASH_PURPLE_MIDDLE_X_BOUNDS[0]
    && ASH_PURPLE_MIDDLE_X_BOUNDS[1] < ASH_GREEN_RIGHT_X_BOUNDS[0])) {
    throw new Error('Invalid core layout: ash silo topology must remain green-left, purple-middle, green-right.')
  }
  for (const placement of CORE_PLACEMENTS.filter((item) => item.type === 'rebar_bay' || item.type.startsWith('ash_'))) {
    if (placement.headingDeg !== 0) {
      throw new Error(`Invalid core layout: ${placement.id} must remain parallel to the rail direction.`)
    }
  }
  if (minSiloX >= maxSiloX) throw new Error('Invalid core layout: ash silo envelope is empty.')
}

assertCoreLayoutConstraints()

export function getPlacement(id: string): AssetPlacement | undefined {
  return ALL_PLACEMENTS.find((placement) => placement.id === id)
}

export function getZone(id: SiteZoneId): SiteZone {
  const zone = SITE_ZONES.find((entry) => entry.id === id)
  if (!zone) throw new Error(`Unknown site zone: ${id}`)
  return zone
}
