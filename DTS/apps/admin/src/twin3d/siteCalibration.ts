import { SITE_LAYOUT_V3 } from './siteLayoutV3'

/**
 * Fact-check-only layout data. This module intentionally stays separate from
 * production placements until the annotated plan has been signed off.
 *
 * This is the V3 correction pass against the annotated layout plan.
 * The steel line is the two-track set adjacent to the 13 rebar bays. The ash
 * line is a separate two-track set in the B bundle below the ash-silo groups.
 * No calibration geometry in this module is consumed by the production scene.
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
  /** Source CAD rail-edge lines used to calculate this track centreline. */
  railEdgesY: readonly [number, number]
}

export interface CalibrationGantry {
  id: string
  serviceLine: 'rebar'
  center: CadPoint
  confidence: 'candidate-from-annotated-plan'
}

export interface CalibrationBay {
  id: string
  center: CadPoint
  width: number
  depth: number
  confidence: 'confirmed-from-cad-geometry'
}

export interface CalibrationZone {
  id: string
  kind: 'line-silo' | 'round-silo'
  label: string
  points: CadPoint[]
  confidence: 'candidate-from-annotated-plan'
}

function trackCenter(edges: readonly [number, number]) {
  return (edges[0] + edges[1]) / 2
}

/**
 * Each pair contains CAD edge-lines that together represent one track. The
 * former Y=1695–1728 interpretation is intentionally not present here: those
 * lines are not an operating steel railway line.
 */
const REBAR_TRACK_EDGES = SITE_LAYOUT_V3.rebar.trackEdgePairsY as readonly (readonly [number, number])[]
const ASH_TRACK_EDGES = SITE_LAYOUT_V3.ash.trackEdgePairsY as readonly (readonly [number, number])[]

function createTracks(
  prefix: string,
  serviceLine: ServiceLine,
  startX: number,
  endX: number,
  edgePairs: readonly (readonly [number, number])[],
): CalibrationRail[] {
  return edgePairs.map((railEdgesY, index) => ({
    id: `${prefix}-${String(index + 1).padStart(2, '0')}`,
    serviceLine,
    start: { cadX: startX, cadY: trackCenter(railEdgesY) },
    end: { cadX: endX, cadY: trackCenter(railEdgesY) },
    railEdgesY,
  }))
}

export const CALIBRATION_RAILS: CalibrationRail[] = [
  ...createTracks('REBAR-TRACK', 'rebar', SITE_LAYOUT_V3.rebar.trackRangeX[0], SITE_LAYOUT_V3.rebar.trackRangeX[1], REBAR_TRACK_EDGES),
  ...createTracks('ASH-TRACK', 'ash', SITE_LAYOUT_V3.ash.trackRangeX[0], SITE_LAYOUT_V3.ash.trackRangeX[1], ASH_TRACK_EDGES),
]

/**
 * The annotated PDF identifies three gantry cranes over the steel unloading
 * line. Their exact longitudinal coordinates remain candidates only; their
 * span is deliberately limited to the two REBAR tracks and never crosses the
 * ash-silo tracks.
 */
export const CALIBRATION_GANTRIES: CalibrationGantry[] = SITE_LAYOUT_V3.rebar.gantries.map(({ id, cadX, cadY }) => ({
  id, serviceLine: 'rebar', center: { cadX, cadY }, confidence: 'candidate-from-annotated-plan',
}))

/**
 * Thirteen grey rebar warehouse bays extracted from their repeated closed
 * rectangles on the PDF_0 CAD layer.  The duplicated outer/interior outlines
 * were deduplicated before this list was created.  This is calibration-only
 * geometry; no production placement consumes it until user sign-off.
 */
const REBAR_BAY_X_BOUNDS = SITE_LAYOUT_V3.rebar.bayBoundsX

/**
 * User-corrected calibration candidate.  The source rectangles are lower than
 * the intended steel storage area; this northward review position prevents the
 * steel rails from crossing the bays.  Production placements remain untouched.
 */
const REBAR_BAY_MIN_Y = SITE_LAYOUT_V3.rebar.bayBoundsY[0]
const REBAR_BAY_MAX_Y = SITE_LAYOUT_V3.rebar.bayBoundsY[1]

export const CALIBRATION_REBAR_BAYS: CalibrationBay[] = REBAR_BAY_X_BOUNDS.map(([minX, maxX], index) => ({
  id: `REBAR-BAY-${String(index + 1).padStart(2, '0')}`,
  center: { cadX: (minX + maxX) / 2, cadY: (REBAR_BAY_MIN_Y + REBAR_BAY_MAX_Y) / 2 },
  width: maxX - minX,
  depth: REBAR_BAY_MAX_Y - REBAR_BAY_MIN_Y,
  confidence: 'confirmed-from-cad-geometry',
}))

/**
 * Silo group extents are retained only as review envelopes. They are not
 * production placement geometry or evidence of exact individual silo counts.
 */
export const CALIBRATION_ZONES: CalibrationZone[] = [
  {
    id: 'ASH-SILO-GREEN-LEFT-REVIEW',
    kind: 'line-silo',
    label: '左侧绿色线性煤灰筒仓组（范围待编号复核）',
    confidence: 'candidate-from-annotated-plan',
    points: [
      { cadX: SITE_LAYOUT_V3.ash.greenLeftBounds[0], cadY: 1534 }, { cadX: SITE_LAYOUT_V3.ash.greenLeftBounds[1], cadY: 1534 },
      { cadX: SITE_LAYOUT_V3.ash.greenLeftBounds[1], cadY: 1562 }, { cadX: SITE_LAYOUT_V3.ash.greenLeftBounds[0], cadY: 1562 },
    ],
  },
  {
    id: 'ASH-SILO-ROUND-REVIEW',
    kind: 'round-silo',
    label: '中部紫色圆形煤灰筒仓与输送带（范围待编号复核）',
    confidence: 'candidate-from-annotated-plan',
    points: [
      { cadX: SITE_LAYOUT_V3.ash.purpleMiddleBounds[0], cadY: 1534 }, { cadX: SITE_LAYOUT_V3.ash.purpleMiddleBounds[1], cadY: 1534 },
      { cadX: SITE_LAYOUT_V3.ash.purpleMiddleBounds[1], cadY: 1562 }, { cadX: SITE_LAYOUT_V3.ash.purpleMiddleBounds[0], cadY: 1562 },
    ],
  },
  {
    id: 'ASH-SILO-GREEN-RIGHT-REVIEW',
    kind: 'line-silo',
    label: '右侧绿色线性煤灰筒仓组（范围待编号复核）',
    confidence: 'candidate-from-annotated-plan',
    points: [
      { cadX: SITE_LAYOUT_V3.ash.greenRightBounds[0], cadY: 1534 }, { cadX: SITE_LAYOUT_V3.ash.greenRightBounds[1], cadY: 1534 },
      { cadX: SITE_LAYOUT_V3.ash.greenRightBounds[1], cadY: 1562 }, { cadX: SITE_LAYOUT_V3.ash.greenRightBounds[0], cadY: 1562 },
    ],
  },
]
