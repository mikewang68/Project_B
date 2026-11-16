import { SITE_LAYOUT_V4 } from './siteLayoutV4'

/**
 * Calibration & overlay geometry directly sourced from 给大连大.dwg (siteLayoutV4).
 */
export interface CadPoint {
  cadX: number
  cadY: number
}

export type ServiceLine = 'rebar' | 'ash'

export interface CalibrationRail {
  id: string
  serviceLine: ServiceLine
  start: CadPoint
  end: CadPoint
  railEdgesY: readonly [number, number]
}

export interface CalibrationGantry {
  id: string
  serviceLine: ServiceLine
  center: CadPoint
  confidence: 'surveyed-cad-geometry'
}

export interface CalibrationBay {
  id: string
  center: CadPoint
  width: number
  depth: number
  bridgeType: 'magnetic_20t' | 'chook_32t'
  confidence: 'surveyed-cad-geometry'
}

export interface CalibrationZone {
  id: string
  kind: 'cement-silo' | 'flyash-silo' | 'reserved-silo'
  label: string
  points: CadPoint[]
  confidence: 'surveyed-cad-geometry'
}

export const CALIBRATION_RAILS: CalibrationRail[] = [
  ...SITE_LAYOUT_V4.rebar.tracks.map((t) => ({
    id: t.id,
    serviceLine: 'rebar' as const,
    start: { cadX: SITE_LAYOUT_V4.rebar.trackRangeX[0], cadY: t.cadY },
    end: { cadX: SITE_LAYOUT_V4.rebar.trackRangeX[1], cadY: t.cadY },
    railEdgesY: [t.cadY - 0.72, t.cadY + 0.72] as const,
  })),
  ...SITE_LAYOUT_V4.ash.tracks.map((t) => ({
    id: t.id,
    serviceLine: 'ash' as const,
    start: { cadX: SITE_LAYOUT_V4.ash.trackRangeX[0], cadY: t.cadY },
    end: { cadX: SITE_LAYOUT_V4.ash.trackRangeX[1], cadY: t.cadY },
    railEdgesY: [t.cadY - 0.72, t.cadY + 0.72] as const,
  })),
]

export const CALIBRATION_GANTRIES: CalibrationGantry[] = [
  ...SITE_LAYOUT_V4.rebar.gantries.map((g) => ({
    id: g.id,
    serviceLine: 'rebar' as const,
    center: { cadX: g.cadX, cadY: g.cadY },
    confidence: 'surveyed-cad-geometry' as const,
  })),
  ...SITE_LAYOUT_V4.ash.bulkGantries.map((g) => ({
    id: g.id,
    serviceLine: 'ash' as const,
    center: { cadX: g.cadX, cadY: g.cadY },
    confidence: 'surveyed-cad-geometry' as const,
  })),
]

export const CALIBRATION_REBAR_BAYS: CalibrationBay[] = SITE_LAYOUT_V4.rebar.bays.map((b) => ({
  id: b.id,
  center: { cadX: b.cadX, cadY: b.cadY },
  width: b.maxX - b.minX,
  depth: b.maxY - b.minY,
  bridgeType: b.bridgeType,
  confidence: 'surveyed-cad-geometry',
}))

export const CALIBRATION_ZONES: CalibrationZone[] = SITE_LAYOUT_V4.ash.siloModules.map((m) => {
  const [x0, y0, x1, y1] = m.bounds
  return {
    id: m.id,
    kind: m.material === 'cement' ? 'cement-silo' : 'flyash-silo',
    label: m.name,
    points: [
      { cadX: x0, cadY: y0 },
      { cadX: x1, cadY: y0 },
      { cadX: x1, cadY: y1 },
      { cadX: x0, cadY: y1 },
    ],
    confidence: 'surveyed-cad-geometry',
  }
})
