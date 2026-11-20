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
    speedMps: 4.5,
    dwellMs: 2400,
    points: [
      [3570, H4_Y],
      [3716, H4_Y],
      [3570, H4_Y],
    ],
  },
  {
    id: 'ROUTE-WAGON-BULK-01',
    vehicleId: 'WAGON-BULK-01',
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    speedMps: 4.5,
    dwellMs: 2400,
    points: [
      [3650, H2_Y],
      [3772, H2_Y],
      [3650, H2_Y],
    ],
  },
  {
    id: 'ROUTE-TRUCK-TANK-01',
    vehicleId: 'TRUCK-TANK-01',
    source: 'surveyed-cad-dxf',
    confidence: 'confirmed',
    speedMps: 6.0,
    dwellMs: 2000,
    points: [
      [SITE_LAYOUT_V4.weighbridges[1].cadX, SITE_LAYOUT_V4.weighbridges[1].cadY],
      [4521.8, 1616.9],
      [4518.4, 1616.5],
      [4212.0, 1616.5],
      [4212.0, 1588.0],
      [3736.0, 1588.0],
      [3736.0, SITE_LAYOUT_V4.ash.silos[0].cadY],
      [SITE_LAYOUT_V4.ash.silos[0].cadX + 1.65, SITE_LAYOUT_V4.ash.silos[0].cadY],
      [4058.0, SITE_LAYOUT_V4.ash.silos[0].cadY],
      [4058.0, 1588.0],
      [4212.0, 1588.0],
      [4212.0, 1616.5],
      [4324.1, 1616.5],
      [4324.1, 1560.8],
      [4524.2, 1560.8],
      [SITE_LAYOUT_V4.weighbridges[0].cadX, SITE_LAYOUT_V4.weighbridges[0].cadY],
    ],
  },
]
