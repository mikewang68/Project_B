/**
 * Immutable, human-readable audit trail for spatial calibration decisions.
 *
 * This module is deliberately not imported by placements.ts or the model
 * loader.  A ledger entry documents evidence and review state; it never grants
 * permission to change a production placement by itself.
 */
export type CalibrationDecision =
  | 'baseline-created'
  | 'relationship-confirmed'
  | 'correction-applied-awaiting-signoff'
  | 'production-replacement-authorized'

export interface CalibrationLedgerEntry {
  id: string
  recordedOn: string
  reviewItemIds: readonly string[]
  decision: CalibrationDecision
  source: 'user-annotation' | 'cad-dxf-geometry' | 'blender-calibration-render'
  summary: string
  evidencePaths: readonly string[]
  productionReplacementAllowed: boolean
}

/**
 * The ledger records both the original review baseline and the later explicit
 * authorization to publish the reviewed production baseline.
 */
export const CORE_AREA_CALIBRATION_LEDGER: readonly CalibrationLedgerEntry[] = [
  {
    id: 'CAL-20260810-BASELINE-01',
    recordedOn: '2026-08-10',
    reviewItemIds: ['STEEL-TRACKS', 'ASH-TRACKS'],
    decision: 'baseline-created',
    source: 'cad-dxf-geometry',
    summary: '钢材两轨与筒仓下方煤灰两轨作为独立作业线建立校核基线。',
    evidencePaths: ['assets/3d/blender/calibration-renders/steel-line-overview.png', 'assets/3d/blender/calibration-renders/ash-line-overview.png'],
    productionReplacementAllowed: false,
  },
  {
    id: 'CAL-20260810-REBAR-BAYS-02',
    recordedOn: '2026-08-10',
    reviewItemIds: ['STEEL-BAYS'],
    decision: 'correction-applied-awaiting-signoff',
    source: 'user-annotation',
    summary: '13 个钢筋仓位已在校核场景北移至钢材轨道上方，避免铁轨穿过仓位。',
    evidencePaths: ['assets/3d/blender/calibration-renders/steel-line-overview.png', 'assets/3d/blender/calibration-renders/steel-line-rebar-bays-close.png'],
    productionReplacementAllowed: false,
  },
  {
    id: 'CAL-20260810-ASH-SILOS-03',
    recordedOn: '2026-08-10',
    reviewItemIds: ['ASH-GREEN-SILOS', 'ASH-PURPLE-SILOS-CONVEYOR'],
    decision: 'relationship-confirmed',
    source: 'user-annotation',
    summary: '煤灰筒仓的拓扑关系为左绿色段、中央紫色组、右绿色段；精确数量、边界和输送带方向仍待截图确认。',
    evidencePaths: ['assets/3d/blender/calibration-renders/ash-line-overview.png', 'assets/3d/blender/calibration-renders/ash-line-green-silos.png', 'assets/3d/blender/calibration-renders/ash-line-purple-conveyor.png'],
    productionReplacementAllowed: false,
  },
  {
    id: 'CAL-20260810-PRODUCTION-04',
    recordedOn: '2026-08-10',
    reviewItemIds: ['STEEL-TRACKS', 'STEEL-GANTRIES', 'STEEL-WAGONS', 'STEEL-BAYS', 'ASH-TRACKS', 'ASH-GREEN-SILOS', 'ASH-PURPLE-SILOS-CONVEYOR'],
    decision: 'production-replacement-authorized',
    source: 'user-annotation',
    summary: '用户授权跳过逐图确认，按当前校正基线直接导出并接入生产场景；所有未测绘尺寸继续作为比例推断显示。',
    evidencePaths: ['assets/3d/blender/rebar_line_calibration.blend', 'assets/3d/blender/ash_line_calibration.blend'],
    productionReplacementAllowed: true,
  },
]

export function canReplaceProductionFromLedger(entry: CalibrationLedgerEntry): boolean {
  return entry.productionReplacementAllowed
}
