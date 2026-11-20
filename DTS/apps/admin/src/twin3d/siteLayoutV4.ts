import definition from './siteLayoutV4.json'

export type Pair = [number, number]

export interface TrackSpec {
  id: string
  label: string
  cadY: number
  lengthM: number
}

export interface CraneRunwaySpec {
  minX: number
  maxX: number
  southRailY: number
  northRailY: number
  spanM: number
  girderHeightM: number
}

export interface GantrySpec {
  id: string
  name: string
  cadX: number
  cadY: number
  capacityT: number
  spanM: number
  powerKw: number
}

export interface RebarBaySpec {
  id: string
  bayIndex: number
  minX: number
  maxX: number
  minY: number
  maxY: number
  cadX: number
  cadY: number
  bridgeCraneId: string
  cadEquipmentCode: string
  bridgeType: 'magnetic_20t' | 'chook_32t'
  craneCapacityT: number
  craneSpanM: number
  powerKw: number
  railHeightM: number
  bridgeParkY: number
  cargoKind: 'rebar' | 'coil'
}

export interface RebarWarehouseSpec {
  id: string
  name: string
  minX: number
  maxX: number
  minY: number
  maxY: number
  railHeightM: number
  eaveHeightM: number
  ridgeHeightM: number
  areaSqm: number
}

export interface SiloSpec {
  id: string
  code: string
  row: 'A' | 'B'
  col: number
  moduleIndex: number
  material: 'cement' | 'fly_ash'
  cadX: number
  cadY: number
  diameterM: number
  straightHeightM: number
  totalHeightM: number
  legElevationM: number
  capacityT: number
}

export interface SiloModuleSpec {
  id: string
  name: string
  material: 'cement' | 'fly_ash'
  bounds: [number, number, number, number]
}

export interface UnloadingComplexSpec {
  id: string
  tipperId: string
  material: 'cement' | 'fly_ash'
  cadX: number
  cadY: number
  roomWidthM: number
  roomDepthM: number
  workroomX?: number
  tipperParkX: number
  tipperWaitX?: number
  tipperUnloadX: number
  tipperRailMinX?: number
  tipperRailMaxX?: number
  tipperSouthY?: number
  tipperNorthY?: number
  tipperRailY: number
  tipperNorthRailY?: number
  tipperRailGaugeM?: number
  hopperCapacityM3: number
  compressorCadX: number
  compressorCadY: number
  elevatorId: string
  elevatorCadX: number
  elevatorCadY: number
  elevatorHeightM: number
  electricalCadX: number
  electricalCadY: number
  moduleBoundsX: Pair
}

export interface WeighbridgeSpec {
  id: string
  name: string
  kind: 'truck_scale' | 'rail_scale'
  capacityT: number
  cadX: number
  cadY: number
  angleDeg?: number
  lengthM?: number
  widthM?: number
}

export interface SiteZoneDefinition {
  id: string
  name: string
  state: 'existing' | 'planned'
  bounds: [number, number, number, number]
  heightM: number
}

export interface LayoutV4Definition {
  version: string
  project: string
  source: string
  confidence: string
  axis: 'cad-x'
  pivotOffset: Pair
  geoOrigin: Pair
  rebar: {
    origin: Pair
    trackRangeX: Pair
    tracks: TrackSpec[]
    craneRunway: CraneRunwaySpec
    gantries: GantrySpec[]
    wagonXs: number[]
    transferApronY: Pair
    truckLoadingY: number
    warehouse: RebarWarehouseSpec
    bays: RebarBaySpec[]
  }
  ash: {
    origin: Pair
    trackRangeX: Pair
    tracks: TrackSpec[]
    craneRunway: CraneRunwaySpec
    bulkGantries: GantrySpec[]
    siloRowCentersY: Pair
    siloModules: SiloModuleSpec[]
    silos: SiloSpec[]
    reservedSilosCount: number
    reservedSilosBounds: Pair
    unloadingComplexes: UnloadingComplexSpec[]
    tankerRoadY: number
    tankerUnderSiloY: number
  }
  weighbridges: WeighbridgeSpec[]
  siteShell: {
    bounds: Pair[]
    accessRoads: Pair[][]
    zones: SiteZoneDefinition[]
  }
}

const layout = definition as unknown as LayoutV4Definition

export const SITE_LAYOUT_V4 = {
  ...layout,
  siteShellCenter: {
    cadX: (3310.68 + 4584.14) / 2,
    cadY: (1487.79 + 1873.79) / 2,
  },
  rebar: {
    ...layout.rebar,
    originPoint: { cadX: layout.rebar.origin[0], cadY: layout.rebar.origin[1] },
    trackCentersY: layout.rebar.tracks.map((t) => t.cadY),
  },
  ash: {
    ...layout.ash,
    originPoint: { cadX: layout.ash.origin[0], cadY: layout.ash.origin[1] },
    trackCentersY: layout.ash.tracks.map((t) => t.cadY),
  },
} as const

export function assertSiteLayoutV4(): void {
  const { rebar, ash } = SITE_LAYOUT_V4
  if (layout.axis !== 'cad-x') throw new Error('siteLayoutV4: longitudinal axis must be CAD X')
  if (rebar.warehouse.minY <= Math.max(...rebar.trackCentersY)) {
    throw new Error('siteLayoutV4: steel rails cannot cross rebar warehouse')
  }
  if (rebar.bays.length !== 13) {
    throw new Error(`siteLayoutV4: expected 13 rebar bays, got ${rebar.bays.length}`)
  }
  if (ash.silos.length !== 40) {
    throw new Error(`siteLayoutV4: expected 40 active silos, got ${ash.silos.length}`)
  }
  if (Math.min(...ash.silos.map((s) => s.cadY)) <= Math.max(...ash.trackCentersY)) {
    throw new Error('siteLayoutV4: ash silos must stay north of ash rail tracks')
  }
}

assertSiteLayoutV4()
