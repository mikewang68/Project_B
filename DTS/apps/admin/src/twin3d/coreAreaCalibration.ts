/**
 * Human-review queue for the signed-off core-area calibration workflow.
 *
 * This is intentionally independent from placements.ts: entries here are
 * evidence and review tasks only. Production placement/model changes require
 * an explicit user decision recorded against the corresponding item.
 */
export type CoreReviewSet = 'steel-line' | 'ash-line'
export type CoreReviewStatus = 'confirmed' | 'position-review' | 'inferred-proportional'
export type UserConfirmation = 'pending' | 'confirmed' | 'needs-correction' | 'implementation-authorized'

export interface CoreAreaReviewItem {
  id: string
  reviewSet: CoreReviewSet
  targetIds: string[]
  cadReference: string
  source: 'annotated-steel-layout-plan' | 'cad-dxf-geometry'
  status: CoreReviewStatus
  question: string
  userConfirmation: UserConfirmation
  productionFrozen: boolean
  /** Calendar date of the most recent user instruction affecting this item. */
  lastReviewedOn: string
  /** Screenshot or document that must be retained before production replacement. */
  requiredEvidence: readonly string[]
}

export const CORE_AREA_REVIEW_ITEMS: CoreAreaReviewItem[] = [
  {
    id: 'STEEL-TRACKS', reviewSet: 'steel-line', targetIds: ['REBAR-TRACK-01', 'REBAR-TRACK-02'],
    cadReference: 'X=3543.936–3937.115; Y=1664.806 / 1682.061', source: 'cad-dxf-geometry',
    status: 'confirmed', question: '两条钢材作业线轨道是否与图纸一致？', userConfirmation: 'confirmed', productionFrozen: false,
    lastReviewedOn: '2026-08-10', requiredEvidence: ['钢材线编号俯视校核图'],
  },
  {
    id: 'STEEL-GANTRIES', reviewSet: 'steel-line', targetIds: ['CR-01', 'CR-02', 'CR-03'],
    cadReference: '共同跨越钢材两轨；X=3600.670 / 3727.235 / 3839.751', source: 'annotated-steel-layout-plan',
    status: 'inferred-proportional', question: '三台龙门吊的纵向先后和实际跨距待现场尺寸表复核。', userConfirmation: 'implementation-authorized', productionFrozen: false,
    lastReviewedOn: '2026-08-10', requiredEvidence: ['钢材线编号俯视校核图', 'CR-02 近景校核图'],
  },
  {
    id: 'STEEL-WAGONS', reviewSet: 'steel-line', targetIds: ['WAGON-FLAT-01', 'WAGON-FLAT-02', 'WAGON-FLAT-03'],
    cadReference: 'REBAR-TRACK-01; X=3656 / 3670 / 3684', source: 'cad-dxf-geometry',
    status: 'inferred-proportional', question: '铁路平车数量、停靠股道和停靠区间待现场调度数据复核。', userConfirmation: 'implementation-authorized', productionFrozen: false,
    lastReviewedOn: '2026-08-10', requiredEvidence: ['钢材线编号俯视校核图'],
  },
  {
    id: 'STEEL-BAYS', reviewSet: 'steel-line', targetIds: Array.from({ length: 13 }, (_, index) => `REBAR-BAY-${String(index + 1).padStart(2, '0')}`),
    cadReference: '原始 CAD 提取 Y=1677.541–1713.579；校核候选北移为 Y=1692.000–1728.038，确保轨道不穿过仓位', source: 'cad-dxf-geometry',
    status: 'inferred-proportional', question: '北移后的 01–13 钢筋仓位已进入生产基线；精确间距待现场尺寸复核。', userConfirmation: 'implementation-authorized', productionFrozen: false,
    lastReviewedOn: '2026-08-10', requiredEvidence: ['钢材线编号俯视校核图', '钢筋仓位近景校核图'],
  },
  {
    id: 'ASH-TRACKS', reviewSet: 'ash-line', targetIds: ['ASH-TRACK-01', 'ASH-TRACK-02'],
    cadReference: '筒仓组下方 B 线束；X=3689.859–4033.013; Y=1506.697 / 1511.675', source: 'cad-dxf-geometry',
    status: 'confirmed', question: '两条煤灰轨道位于筒仓组下方且与筒仓横排平行。', userConfirmation: 'confirmed', productionFrozen: false,
    lastReviewedOn: '2026-08-10', requiredEvidence: ['煤灰线编号俯视校核图'],
  },
  {
    id: 'ASH-GREEN-SILOS', reviewSet: 'ash-line', targetIds: ['ASH-SILO-GREEN-LEFT-01', 'ASH-SILO-GREEN-RIGHT-01'],
    cadReference: '用户已确认绿色组拆分为左、右两段，紫色组位于中间；精确单体数量与边界仍待校核', source: 'annotated-steel-layout-plan',
    status: 'inferred-proportional', question: '左右绿色筒仓段数量、间距与紫色组间隔采用比例推断，待尺寸表复核。', userConfirmation: 'implementation-authorized', productionFrozen: false,
    lastReviewedOn: '2026-08-10', requiredEvidence: ['煤灰线编号俯视校核图', '绿色筒仓近景校核图'],
  },
  {
    id: 'ASH-PURPLE-SILOS-CONVEYOR', reviewSet: 'ash-line', targetIds: ['ASH-SILO-PURPLE-MIDDLE-01', 'ASH-CONVEYOR-01'],
    cadReference: '用户已确认紫色组夹在两段绿色组之间；输送带随紫色组重新校核', source: 'annotated-steel-layout-plan',
    status: 'inferred-proportional', question: '中部紫色筒仓与输送带方向采用比例推断，待现场工艺资料复核。', userConfirmation: 'implementation-authorized', productionFrozen: false,
    lastReviewedOn: '2026-08-10', requiredEvidence: ['煤灰线编号俯视校核图', '紫色筒仓与输送带近景校核图'],
  },
]
