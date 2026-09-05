export interface VehicleRoute {
  id: string
  vehicleId: string
  source: 'simulation-route'
  confidence: 'inferred'
  speedMps: number
  dwellMs: number
  points: Array<[number, number]>
}

export const VEHICLE_ROUTES: VehicleRoute[] = [
  {
    id: 'ROUTE-WAGON-FLAT-01', vehicleId: 'WAGON-FLAT-01', source: 'simulation-route', confidence: 'inferred',
    speedMps: 4.5, dwellMs: 1900,
    points: [[3656, 1664.806], [3690, 1664.806], [3727.235, 1664.806], [3782, 1664.806], [3727.235, 1664.806], [3690, 1664.806]],
  },
  {
    id: 'ROUTE-BULK-01', vehicleId: 'VEH-BULK-01', source: 'simulation-route', confidence: 'inferred',
    speedMps: 8, dwellMs: 2300,
    points: [[3688, 1840], [3792, 1840], [3905, 1770], [3980, 1705], [3905, 1770], [3792, 1840]],
  },
  {
    id: 'ROUTE-FORKLIFT-01', vehicleId: 'VEH-FORKLIFT-01', source: 'simulation-route', confidence: 'inferred',
    speedMps: 3.2, dwellMs: 700,
    points: [[3620, 1762], [3662, 1762], [3662, 1781], [3608, 1781], [3608, 1762]],
  },
]
