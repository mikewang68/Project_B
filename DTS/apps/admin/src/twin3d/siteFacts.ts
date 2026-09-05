/**
 * Single source of truth for the core-area facts confirmed from the annotated
 * layout plan.  This is deliberately a semantic manifest, not a placement
 * manifest: a confirmed facility can still have a position awaiting review.
 */
export type SiteFactStatus = 'confirmed' | 'position-review' | 'inferred' | 'inferred-proportional'

export interface SiteFact {
  id: string
  category: 'rail' | 'equipment' | 'storage' | 'process'
  name: string
  statement: string
  status: SiteFactStatus
  source: 'annotated-steel-layout-plan' | 'cad-dxf-geometry'
}

export const SITE_FACTS: SiteFact[] = [
  {
    id: 'FACT-REBAR-RAIL-LINE',
    category: 'rail',
    name: '钢材作业线',
    statement: '钢筋仓库前有 2 条铁路轨道；其服务对象不包含煤灰筒仓。',
    status: 'confirmed',
    source: 'annotated-steel-layout-plan',
  },
  {
    id: 'FACT-ASH-RAIL-LINE',
    category: 'rail',
    name: '煤灰作业线',
    statement: '绿色、紫色煤灰筒仓组下方的 B 线束内有 2 条铁路轨道，不位于钢筋仓位附近。',
    status: 'confirmed',
    source: 'annotated-steel-layout-plan',
  },
  {
    id: 'FACT-GANTRY-CRANES',
    category: 'equipment',
    name: '三台龙门吊',
    statement: '图纸标注为铁路货车卸货用龙门吊；网页中仅作为钢材线候选，纵向落位需继续复核。',
    status: 'position-review',
    source: 'annotated-steel-layout-plan',
  },
  {
    id: 'FACT-REBAR-WAREHOUSES',
    category: 'storage',
    name: '13 个钢筋仓位',
    statement: '中部灰色矩形为钢筋仓库，共 13 个；已按 CAD 中 13 个重复闭合矩形提取编号，蓝线表示整体范围。',
    status: 'confirmed',
    source: 'cad-dxf-geometry',
  },
  {
    id: 'FACT-ASH-SILO-LINE',
    category: 'storage',
    name: '绿色煤灰筒仓组',
    statement: '用户已确认绿色煤灰筒仓组应分为左右两段，紫色筒仓组夹在中间；精确单体数量、直径、高度和边界仍待校核。',
    status: 'position-review',
    source: 'annotated-steel-layout-plan',
  },
  {
    id: 'FACT-ASH-SILO-ROUND',
    category: 'storage',
    name: '紫色煤灰筒仓与输送带',
    statement: '用户已确认紫色圆形煤灰筒仓组位于两段绿色组之间；黑色线为向其供料的输送带，精确单体尺寸与支撑细节仍待校核。',
    status: 'position-review',
    source: 'annotated-steel-layout-plan',
  },
  {
    id: 'FACT-CAD-RAIL-EDGES',
    category: 'rail',
    name: 'CAD 轨道几何',
    statement: '钢材线使用 Y=1661–1687 的两组轨道边线；煤灰线使用筒仓下方 B 线束 Y=1505–1512 的两组轨道边线。',
    status: 'confirmed',
    source: 'cad-dxf-geometry',
  },
]

export const SITE_FACT_STATUS_LABEL: Record<SiteFactStatus, string> = {
  confirmed: '已确认',
  'position-review': '待落位复核',
  inferred: '推断',
  'inferred-proportional': '比例推断',
}
