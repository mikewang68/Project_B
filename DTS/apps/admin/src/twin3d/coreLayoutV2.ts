import definition from './coreLayoutV2.json'

export type CadPoint = { cadX: number; cadY: number }
export type CoreLineId = 'rebar' | 'ash'

type Pair = [number, number]
type LayoutDefinition = {
  version: string
  axis: 'cad-x'
  source: string
  confidence: string
  rebar: {
    origin: Pair
    trackRangeX: Pair
    trackEdgePairsY: Pair[]
    gantries: [string, number][]
    wagonXs: number[]
    bayBoundsX: Pair[]
    bayBoundsY: Pair
  }
  ash: {
    origin: Pair
    trackRangeX: Pair
    trackEdgePairsY: Pair[]
    siloCenterY: number
    greenLeftBounds: Pair
    purpleMiddleBounds: Pair
    greenRightBounds: Pair
    conveyor: [number, number, number]
  }
}

const layout = definition as LayoutDefinition

const midpoint = (values: readonly [number, number]) => (values[0] + values[1]) / 2

const rebarTrackCenters = layout.rebar.trackEdgePairsY.map(midpoint)
const ashTrackCenters = layout.ash.trackEdgePairsY.map(midpoint)

export const CORE_LAYOUT_V2 = {
  ...layout,
  rebar: {
    ...layout.rebar,
    origin: { cadX: layout.rebar.origin[0], cadY: layout.rebar.origin[1] },
    trackCentersY: rebarTrackCenters,
    gantries: layout.rebar.gantries.map(([id, cadX]) => ({ id, cadX, cadY: layout.rebar.origin[1] })),
    bays: layout.rebar.bayBoundsX.map(([minX, maxX], index) => ({
      id: `REBAR-BAY-${String(index + 1).padStart(2, '0')}`,
      minX,
      maxX,
      minY: layout.rebar.bayBoundsY[0],
      maxY: layout.rebar.bayBoundsY[1],
      cadX: midpoint([minX, maxX]),
      cadY: midpoint(layout.rebar.bayBoundsY),
    })),
  },
  ash: {
    ...layout.ash,
    origin: { cadX: layout.ash.origin[0], cadY: layout.ash.origin[1] },
    trackCentersY: ashTrackCenters,
  },
} as const

export function assertCoreLayoutV2(): void {
  const rebar = CORE_LAYOUT_V2.rebar
  const ash = CORE_LAYOUT_V2.ash
  const maxRebarTrackY = Math.max(...rebar.trackCentersY)
  const maxAshTrackY = Math.max(...ash.trackCentersY)

  if (rebar.bayBoundsY[0] <= maxRebarTrackY) throw new Error('coreLayoutV2: steel tracks cross rebar bays')
  if (ash.siloCenterY <= maxAshTrackY) throw new Error('coreLayoutV2: ash tracks cross silo row')
  if (!(ash.greenLeftBounds[1] < ash.purpleMiddleBounds[0] && ash.purpleMiddleBounds[1] < ash.greenRightBounds[0])) {
    throw new Error('coreLayoutV2: ash silos must remain green-left, purple-middle, green-right')
  }
  if (layout.axis !== 'cad-x') throw new Error('coreLayoutV2: all core longitudinal assets must follow CAD X')
}

assertCoreLayoutV2()
