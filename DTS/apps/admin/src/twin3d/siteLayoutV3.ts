import definition from './siteLayoutV3.json'

type Pair = [number, number]
type SiteZoneDefinition = { id: string; name: string; state: 'existing' | 'planned'; bounds: [number, number, number, number] }
type LayoutDefinition = {
  version: string
  axis: 'cad-x'
  source: string
  confidence: string
  rebar: { origin: Pair; trackRangeX: Pair; trackEdgePairsY: Pair[]; gantries: [string, number][]; wagonXs: number[]; bayBoundsX: Pair[]; bayBoundsY: Pair }
  ash: { origin: Pair; trackRangeX: Pair; trackEdgePairsY: Pair[]; siloCenterY: number; greenLeftBounds: Pair; purpleMiddleBounds: Pair; greenRightBounds: Pair; conveyor: [number, number, number] }
  siteShell: { bounds: Pair[]; accessRoads: Pair[][]; greenBelts: Pair[][]; zones: SiteZoneDefinition[] }
}
const layout = definition as unknown as LayoutDefinition
const midpoint = (v: Pair) => (v[0] + v[1]) / 2

export const SITE_LAYOUT_V3 = {
  ...layout,
  rebar: {
    ...layout.rebar,
    origin: { cadX: layout.rebar.origin[0], cadY: layout.rebar.origin[1] },
    trackCentersY: layout.rebar.trackEdgePairsY.map(midpoint),
    gantries: layout.rebar.gantries.map(([id, cadX]) => ({ id, cadX, cadY: layout.rebar.origin[1] })),
    bays: layout.rebar.bayBoundsX.map(([minX, maxX], index) => ({
      id: `REBAR-BAY-${String(index + 1).padStart(2, '0')}`, minX, maxX,
      minY: layout.rebar.bayBoundsY[0], maxY: layout.rebar.bayBoundsY[1],
      cadX: midpoint([minX, maxX]), cadY: midpoint(layout.rebar.bayBoundsY),
    })),
  },
  ash: {
    ...layout.ash,
    origin: { cadX: layout.ash.origin[0], cadY: layout.ash.origin[1] },
    trackCentersY: layout.ash.trackEdgePairsY.map(midpoint),
  },
} as const

export function assertSiteLayoutV3(): void {
  const rebar = SITE_LAYOUT_V3.rebar
  const ash = SITE_LAYOUT_V3.ash
  if (rebar.bayBoundsY[0] <= Math.max(...rebar.trackCentersY)) throw new Error('siteLayoutV3: steel rails cannot cross rebar bays')
  if ((rebar.bayBoundsY[1] - rebar.bayBoundsY[0]) >= (rebar.bayBoundsX[0][1] - rebar.bayBoundsX[0][0]) * 1.2) throw new Error('siteLayoutV3: rebar bays must be longitudinal, parallel to CAD X')
  if (ash.siloCenterY <= Math.max(...ash.trackCentersY)) throw new Error('siteLayoutV3: ash rails must remain below silos')
  if (!(ash.greenLeftBounds[1] < ash.purpleMiddleBounds[0] && ash.purpleMiddleBounds[1] < ash.greenRightBounds[0])) throw new Error('siteLayoutV3: silo order must be green-purple-green')
  if (layout.axis !== 'cad-x') throw new Error('siteLayoutV3: longitudinal axis must be CAD X')
}

assertSiteLayoutV3()
