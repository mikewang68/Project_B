import { SITE_LAYOUT_V4 } from './siteLayoutV4'

export interface VehicleRoute {
  id: string
  vehicleId: string
  source: 'surveyed-cad-dxf' | 'simulation-route'
  confidence: 'confirmed' | 'inferred'
  speedMps: number
  dwellMs: number
  points: Array<[number, number]>
}

const H4_Y = SITE_LAYOUT_V4.rebar.tracks[0].cadY
const H2_Y = SITE_LAYOUT_V4.ash.tracks[1].cadY

export const VEHICLE_ROUTES: VehicleRoute[] = [
  {
    id: 'ROUTE-WAGON-FLAT-01',
    vehicleId: 'WAGON-FLAT-01',
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    speedMps: 5.5,
    dwellMs: 1800,
    points: [
      [3530, H4_Y],
      [3660, H4_Y],
      [SITE_LAYOUT_V4.rebar.gantries[1].cadX, H4_Y],
      [3780, H4_Y],
      [SITE_LAYOUT_V4.rebar.gantries[1].cadX, H4_Y],
      [3660, H4_Y],
    ],
  },
  {
    id: 'ROUTE-WAGON-BULK-01',
    vehicleId: 'WAGON-BULK-01',
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    speedMps: 5.5,
    dwellMs: 1800,
    points: [
      [3720, H2_Y],
      [SITE_LAYOUT_V4.ash.bulkGantries[0].cadX, H2_Y],
      [SITE_LAYOUT_V4.ash.bulkGantries[1].cadX, H2_Y],
      [SITE_LAYOUT_V4.ash.bulkGantries[0].cadX, H2_Y],
    ],
  },
  {
    id: 'ROUTE-TRUCK-TANK-01',
    vehicleId: 'TRUCK-TANK-01',
    source: 'simulation-route',
    confidence: 'confirmed',
    speedMps: 5.5,
    dwellMs: 1800,
    points: [
      [SITE_LAYOUT_V4.weighbridges[1].cadX, SITE_LAYOUT_V4.weighbridges[1].cadY],
      [4525, SITE_LAYOUT_V4.weighbridges[1].cadY],
      [4525, 1616.5],
      [4120, 1616.5],
      [4120, 1587.0],
      [3738, 1587.0],
      [3738, SITE_LAYOUT_V4.ash.silos[0].cadY],
      [SITE_LAYOUT_V4.ash.silos[0].cadX + 1.65, SITE_LAYOUT_V4.ash.silos[0].cadY],
      [4044, SITE_LAYOUT_V4.ash.silos[0].cadY],
      [4044, 1587.0],
      [4120, 1587.0],
      [4120, 1616.5],
      [4525, 1616.5],
      [4525, SITE_LAYOUT_V4.weighbridges[0].cadY],
      [SITE_LAYOUT_V4.weighbridges[0].cadX, SITE_LAYOUT_V4.weighbridges[0].cadY],
    ],
  },
]
