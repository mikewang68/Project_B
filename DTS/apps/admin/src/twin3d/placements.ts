import { SITE_LAYOUT_V4 } from './siteLayoutV4'

/**
 * Single authoritative V4 placement manifest backed by 给大连大.dwg (siteLayoutV4).
 * CGCS2000 coordinates are normalized via PIVOT_OFFSET = [512000, 3254935].
 */
export type PlacementType =
  | 'gantry_crane'
  | 'bridge_crane'
  | 'mobile_tipper'
  | 'silo_loader'
  | 'elevator'
  | 'wagon'
  | 'bulk_container'
  | 'tank_truck'
  | 'road_truck'
  | 'rebar_bay'
  | 'rebar_warehouse'
  | 'ash_line_silo'
  | 'ash_round_silo'
  | 'ash_conveyor'
  | 'unloading_room'
  | 'core_environment'
  | 'ash_environment'
  | 'site_shell'
  | 'weighbridge'
  | 'support_facility'
  | 'processing_zone'
  | 'planned_warehouse'

export type SiteZoneId =
  | 'site_boundary'
  | 'rebar_corridor'
  | 'rebar_bays'
  | 'ash_corridor'
  | 'reserved_rebar'
  | 'reserved_wh'
  | 'mech_warehouse'
  | 'processing_zone'
  | 'weighbridge_gate'

export type SiteState = 'existing' | 'planned'
export type PlacementSource = 'surveyed-cad-dxf' | 'site-plan-vector' | 'cad-track-axis-inference' | 'simulation-route'
export type Confidence = 'confirmed' | 'inferred'

export interface CadPolygon {
  points: Array<[number, number]>
}

export interface SiteZone {
  id: SiteZoneId
  name: string
  state: SiteState
  polygon: CadPolygon
  source: PlacementSource
  confidence: Confidence
  notes: string
}

export interface AssetPlacement {
  id: string
  name: string
  type: PlacementType
  zone: SiteZoneId
  state: SiteState
  cadX: number
  cadY: number
  headingDeg: number
  height?: number
  scale?: number
  modelKey?: string
  spanM?: number
  capacityT?: number
  source: PlacementSource
  confidence: Confidence
  notes: string
}

const WH = SITE_LAYOUT_V4.rebar.warehouse
const REBAR_MIN_X = SITE_LAYOUT_V4.rebar.trackRangeX[0]
const REBAR_MAX_X = SITE_LAYOUT_V4.rebar.trackRangeX[1]
const ASH_MIN_X = SITE_LAYOUT_V4.ash.trackRangeX[0]
const ASH_MAX_X = SITE_LAYOUT_V4.ash.trackRangeX[1]

export const SITE_ZONES: SiteZone[] = [
  {
    id: 'site_boundary',
    name: '中沙坝转运站红线范围',
    state: 'existing',
    polygon: { points: SITE_LAYOUT_V4.siteShell.bounds },
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '给大连大.dwg 实测围墙与站坪范围',
  },
  {
    id: 'rebar_corridor',
    name: '钢材装卸双轨区（货3 / 货4 + 3×40t门吊）',
    state: 'existing',
    polygon: {
      points: [
        [REBAR_MIN_X, SITE_LAYOUT_V4.rebar.craneRunway.southRailY],
        [REBAR_MAX_X, SITE_LAYOUT_V4.rebar.craneRunway.southRailY],
        [REBAR_MAX_X, SITE_LAYOUT_V4.rebar.craneRunway.northRailY],
        [REBAR_MIN_X, SITE_LAYOUT_V4.rebar.craneRunway.northRailY],
      ],
    },
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '货3（560m）与货4（564m）及 30m 跨度门式起重机轨道廊道',
  },
  {
    id: 'rebar_bays',
    name: '钢筋仓库（23,882 m² · 1~13 跨）',
    state: 'existing',
    polygon: {
      points: [
        [WH.minX, WH.minY],
        [WH.maxX, WH.minY],
        [WH.maxX, WH.maxY],
        [WH.minX, WH.maxY],
      ],
    },
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '13 跨室内库房，轨顶 13.0m，配 9×20t 磁吸 + 4×32t C 型吊具桥吊',
  },
  {
    id: 'ash_corridor',
    name: '散装水泥/粉煤灰双轨及筒仓群',
    state: 'existing',
    polygon: {
      points: [
        [ASH_MIN_X, SITE_LAYOUT_V4.ash.craneRunway.southRailY],
        [ASH_MAX_X, SITE_LAYOUT_V4.ash.craneRunway.southRailY],
        [ASH_MAX_X, 1575.0],
        [ASH_MIN_X, 1575.0],
      ],
    },
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '货1/货2 + 3台集装箱门吊 + 4套翻箱卸料室 + 40座3000T筒仓',
  },
  {
    id: 'reserved_rebar',
    name: '备用钢筋仓库（缓建 120×35 m）',
    state: 'planned',
    polygon: {
      points: [
        [3589.28, 1763.05],
        [3709.29, 1763.05],
        [3709.29, 1798.05],
        [3589.28, 1798.05],
      ],
    },
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '施工图规划缓建仓库',
  },
  {
    id: 'reserved_wh',
    name: '备用仓库（缓建 100×35 m）',
    state: 'planned',
    polygon: {
      points: [
        [3719.28, 1763.04],
        [3819.29, 1763.04],
        [3819.29, 1798.05],
        [3719.28, 1798.05],
      ],
    },
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '施工图规划缓建仓库',
  },
  {
    id: 'mech_warehouse',
    name: '机电仓库（5,036 m² · 2×50t桥吊）',
    state: 'existing',
    polygon: {
      points: [
        [4347.54, 1512.79],
        [4475.49, 1512.79],
        [4475.49, 1551.89],
        [4347.54, 1551.89],
      ],
    },
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '机电仓库，配 2 台 Q=50t, S=16.5m 桥式起重机',
  },
  {
    id: 'processing_zone',
    name: '钢筋加工区',
    state: 'existing',
    polygon: {
      points: [
        [3598.78, 1812.54],
        [3799.48, 1812.54],
        [3799.48, 1840.02],
        [3598.78, 1840.02],
      ],
    },
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '钢筋仓库北侧加工区',
  },
  {
    id: 'weighbridge_gate',
    name: '东入口计量与综合办公区',
    state: 'existing',
    polygon: {
      points: [
        [4395.86, 1560.0],
        [4570.0, 1560.0],
        [4570.0, 1675.0],
        [4395.86, 1675.0],
      ],
    },
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '含 2 台 100t 汽车衡、门卫室、综合楼及宿舍',
  },
]

const REBAR_GANTRIES: AssetPlacement[] = SITE_LAYOUT_V4.rebar.gantries.map((g) => ({
  id: g.id,
  name: g.name,
  type: 'gantry_crane',
  zone: 'rebar_corridor',
  state: 'existing',
  cadX: g.cadX,
  cadY: g.cadY,
  headingDeg: 0,
  height: SITE_LAYOUT_V4.rebar.craneRunway.girderHeightM,
  spanM: g.spanM,
  capacityT: g.capacityT,
  source: 'surveyed-cad-dxf',
  confidence: 'confirmed',
  notes: `钢材线 40t/30m 门吊（${g.powerKw}kW），跨越货3/货4与交接区`,
}))

const BULK_GANTRIES: AssetPlacement[] = SITE_LAYOUT_V4.ash.bulkGantries.map((g) => ({
  id: g.id,
  name: g.name,
  type: 'gantry_crane',
  zone: 'ash_corridor',
  state: 'existing',
  cadX: g.cadX,
  cadY: g.cadY,
  headingDeg: 0,
  height: SITE_LAYOUT_V4.ash.craneRunway.girderHeightM,
  spanM: g.spanM,
  capacityT: g.capacityT,
  source: 'surveyed-cad-dxf',
  confidence: 'confirmed',
  notes: `散料线 40t/30m 集装箱门吊（${g.powerKw}kW），跨越货1/货2与翻箱工位`,
}))

const BRIDGE_CRANES: AssetPlacement[] = SITE_LAYOUT_V4.rebar.bays.map((b) => ({
  id: b.bridgeCraneId,
  name: `${b.bayIndex}# 跨室内桥吊（${b.craneCapacityT}t ${b.bridgeType === 'chook_32t' ? 'C型吊具' : '磁吸式'}）`,
  type: 'bridge_crane',
  zone: 'rebar_bays',
  state: 'existing',
  cadX: b.cadX,
  cadY: b.bridgeParkY,
  headingDeg: 0,
  height: b.railHeightM,
  spanM: b.craneSpanM,
  capacityT: b.craneCapacityT,
  modelKey: b.bridgeType === 'chook_32t' ? 'bridge-crane-chook' : 'bridge-crane-magnet',
  source: 'surveyed-cad-dxf',
  confidence: 'confirmed',
  notes: `钢筋库第 ${b.bayIndex} 跨室内桥吊（编号 ${b.cadEquipmentCode}），轨顶 ${b.railHeightM}m`,
}))

const MOBILE_TIPPERS: AssetPlacement[] = SITE_LAYOUT_V4.ash.unloadingComplexes.map((u, idx) => ({
  id: u.tipperId,
  name: `${idx + 1}# 移动式翻箱机`,
  type: 'mobile_tipper',
  zone: 'ash_corridor',
  state: 'existing',
  cadX: u.tipperParkX,
  cadY: u.tipperRailY,
  headingDeg: 0,
  height: 0,
  source: 'surveyed-cad-dxf',
  confidence: 'confirmed',
  notes: `对接 ${u.id} 半封闭卸料室（42m³料斗），支持 0~135° 翻转与振动卸料`,
}))

const MECH_BRIDGE_CRANES: AssetPlacement[] = [
  {
    id: 'BC-MECH-01',
    name: '机电仓库 1# 50t 桥式起重机',
    type: 'bridge_crane',
    zone: 'mech_warehouse',
    state: 'existing',
    cadX: 4390.0,
    cadY: 1532.3,
    headingDeg: 0,
    height: 10.5,
    spanM: 16.5,
    capacityT: 50,
    modelKey: 'bridge-crane-chook',
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '机电仓库（5,036 m²）1# Q=50t, S=16.5m 桥式起重机（110kW）',
  },
  {
    id: 'BC-MECH-02',
    name: '机电仓库 2# 50t 桥式起重机',
    type: 'bridge_crane',
    zone: 'mech_warehouse',
    state: 'existing',
    cadX: 4435.0,
    cadY: 1532.3,
    headingDeg: 0,
    height: 10.5,
    spanM: 16.5,
    capacityT: 50,
    modelKey: 'bridge-crane-chook',
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '机电仓库（5,036 m²）2# Q=50t, S=16.5m 桥式起重机（110kW）',
  },
]

const BUCKET_ELEVATORS: AssetPlacement[] = SITE_LAYOUT_V4.ash.unloadingComplexes.map((u, idx) => ({
  id: u.elevatorId,
  name: `${idx + 1}# 高原斗式提升机`,
  type: 'elevator',
  zone: 'ash_corridor',
  state: 'existing',
  cadX: u.elevatorCadX,
  cadY: u.elevatorCadY,
  headingDeg: 0,
  height: u.elevatorHeightM,
  source: 'surveyed-cad-dxf',
  confidence: 'confirmed',
  notes: `40m 垂直斗式提升机（185~264kW），衔接 ${u.id} 料斗与仓顶空气斜槽`,
}))

// One active animated under-silo quantitative telescopic loader per silo module (represents 40 under-silo chutes)
const SILO_LOADERS: AssetPlacement[] = [
  { id: 'LOADER-01', siloIdx: 0, name: '水泥仓库底定量散装机 #01', note: '1# 水泥筒仓底部伸缩散装机' },
  { id: 'LOADER-02', siloIdx: 8, name: '粉煤灰一区库底散装机 #09', note: '9# 粉煤灰筒仓底部伸缩散装机' },
  { id: 'LOADER-03', siloIdx: 16, name: '粉煤灰二区库底散装机 #17', note: '17# 粉煤灰筒仓底部伸缩散装机' },
  { id: 'LOADER-04', siloIdx: 24, name: '粉煤灰三区库底散装机 #25', note: '25# 粉煤灰筒仓底部伸缩散装机' },
].map((item) => ({
  id: item.id,
  name: item.name,
  type: 'silo_loader',
  zone: 'ash_corridor',
  state: 'existing',
  cadX: SITE_LAYOUT_V4.ash.silos[item.siloIdx].cadX,
  cadY: SITE_LAYOUT_V4.ash.silos[item.siloIdx].cadY,
  headingDeg: 0,
  height: 5.5,
  source: 'surveyed-cad-dxf',
  confidence: 'confirmed',
  notes: `${item.note}（散料智能入库/库底定量装车控制系统）`,
}))

export const CORE_PLACEMENTS: AssetPlacement[] = [
  {
    id: 'ENV-SITE-SHELL-01',
    name: '中沙坝全站场地外壳与配套建筑',
    type: 'site_shell',
    zone: 'site_boundary',
    state: 'existing',
    cadX: SITE_LAYOUT_V4.siteShellCenter.cadX,
    cadY: SITE_LAYOUT_V4.siteShellCenter.cadY,
    headingDeg: 0,
    height: -0.04,
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '含围墙、站场道路、机电仓库(2×50t)、综合楼、宿舍、变电所、地磅基础、预留缓建区',
  },
  {
    id: 'ENV-REBAR-LINE-01',
    name: '钢材双轨及钢筋仓库（13跨）环境',
    type: 'core_environment',
    zone: 'rebar_corridor',
    state: 'existing',
    cadX: SITE_LAYOUT_V4.rebar.originPoint.cadX,
    cadY: SITE_LAYOUT_V4.rebar.originPoint.cadY,
    headingDeg: 0,
    height: 0.0,
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '货3/货4 双轨、门吊轨道、钢筋仓库厂房钢架及 1~13 跨地面库位',
  },
  {
    id: 'ENV-ASH-LINE-01',
    name: '散料双轨·卸料室·提升机·40座3000T筒仓环境',
    type: 'ash_environment',
    zone: 'ash_corridor',
    state: 'existing',
    cadX: SITE_LAYOUT_V4.ash.originPoint.cadX,
    cadY: SITE_LAYOUT_V4.ash.originPoint.cadY,
    headingDeg: 0,
    height: -0.02,
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '货1/货2 双轨、4座卸料室(42m³料斗)、4座40m提升机塔、空气斜槽及 40 座 Φ12m×33.38m 筒仓',
  },
  ...REBAR_GANTRIES,
  ...BULK_GANTRIES,
  ...BRIDGE_CRANES,
  ...MECH_BRIDGE_CRANES,
  ...MOBILE_TIPPERS,
  ...BUCKET_ELEVATORS,
  ...SILO_LOADERS,
  // Rail flatcars on Steel track 货4
  ...SITE_LAYOUT_V4.rebar.wagonXs.map((cadX, index): AssetPlacement => ({
    id: `WAGON-FLAT-0${index + 1}`,
    name: `钢材铁路敞车 ${index + 1}`,
    type: 'wagon',
    zone: 'rebar_corridor',
    state: 'existing',
    cadX,
    cadY: SITE_LAYOUT_V4.rebar.tracks[0].cadY,
    headingDeg: 0,
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '停靠货4股道的钢材铁路平车',
  })),
  // Rail container flatcars on Bulk track 货2
  ...[3755.0, 3772.0, 3789.0].map((cadX, index): AssetPlacement => ({
    id: `WAGON-BULK-0${index + 1}`,
    name: `散料集装箱平车 ${index + 1}`,
    type: 'wagon',
    zone: 'ash_corridor',
    state: 'existing',
    cadX,
    cadY: SITE_LAYOUT_V4.ash.tracks[1].cadY,
    headingDeg: 0,
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '停靠货2股道的 35t 散装物料集装箱平车',
  })),
  // Active dynamic actors for dual-process simulation
  {
    id: 'CONT-BULK-01',
    name: '35t 散装粉料集装箱 #01',
    type: 'bulk_container',
    zone: 'ash_corridor',
    state: 'existing',
    cadX: 3772.0,
    cadY: SITE_LAYOUT_V4.ash.tracks[1].cadY,
    headingDeg: 0,
    height: 1.65,
    source: 'simulation-route',
    confidence: 'confirmed',
    notes: '随门吊起吊与移动翻箱机翻转的 35t 散料箱体',
  },
  {
    id: 'TRUCK-TANK-01',
    name: '散装粉料罐车（青B·88219）',
    type: 'tank_truck',
    zone: 'weighbridge_gate',
    state: 'existing',
    cadX: SITE_LAYOUT_V4.weighbridges[1].cadX,
    cadY: SITE_LAYOUT_V4.weighbridges[1].cadY,
    headingDeg: 180,
    source: 'simulation-route',
    confidence: 'confirmed',
    notes: '过 100t 地磅后进入筒仓库底接受定量散装的公路罐车',
  },
  {
    id: 'TRUCK-FLAT-01',
    name: '钢材运输平板挂车（藏A·63052）',
    type: 'road_truck',
    zone: 'rebar_corridor',
    state: 'existing',
    cadX: 3737.0,
    cadY: SITE_LAYOUT_V4.rebar.truckLoadingY,
    headingDeg: 180,
    source: 'simulation-route',
    confidence: 'confirmed',
    notes: '钢筋库南侧装车工位等待桥吊拣货装车的公路货车',
  },
]

export const STATION_PLACEMENTS: AssetPlacement[] = [
  ...SITE_LAYOUT_V4.weighbridges.map((w): AssetPlacement => ({
    id: w.id,
    name: w.name,
    type: 'weighbridge',
    zone: 'weighbridge_gate',
    state: 'existing',
    cadX: w.cadX,
    cadY: w.cadY,
    headingDeg: 0,
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: `${w.capacityT}t 计量秤（自动采集毛重/皮重/净重）`,
  })),
  {
    id: 'ZONE-RSV-REBAR-PLANNED',
    name: '备用钢筋仓库（缓建 120×35 m）',
    type: 'planned_warehouse',
    zone: 'reserved_rebar',
    state: 'planned',
    cadX: 3649.285,
    cadY: 1780.55,
    headingDeg: 0,
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '预留规划仓库（蓝色线框示意）',
  },
  {
    id: 'ZONE-RSV-WH-PLANNED',
    name: '备用仓库（缓建 100×35 m）',
    type: 'planned_warehouse',
    zone: 'reserved_wh',
    state: 'planned',
    cadX: 3769.285,
    cadY: 1780.545,
    headingDeg: 0,
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    notes: '预留规划仓库（蓝色线框示意）',
  },
]

export const ALL_PLACEMENTS = [...CORE_PLACEMENTS, ...STATION_PLACEMENTS]

export function getPlacement(id: string): AssetPlacement | undefined {
  return ALL_PLACEMENTS.find((entry) => entry.id === id)
}

export function getZone(id: SiteZoneId): SiteZone {
  const zone = SITE_ZONES.find((entry) => entry.id === id)
  if (!zone) throw new Error(`Unknown site zone: ${id}`)
  return zone
}
