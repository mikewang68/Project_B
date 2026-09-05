// 第五幕成本与报表共享交互辅助｜REQ-051–062、073/074

const costFilterKeys = Object.freeze([
  'statMonth', 'zone', 'energyType', 'groupBy', 'focus', 'sourceEventId'
])

export function compactPayload(value = {}) {
  return Object.fromEntries(
    Object.entries(value).filter(([, item]) => item !== '' && item != null)
  )
}

// REQ-051：默认值只能由后端 filters 给出；route 仅覆盖显式深链参数。
export function costFiltersFromRoute(query = {}, serverDefaults = {}) {
  const routeFilters = Object.fromEntries(
    costFilterKeys
      .filter((key) => query[key] !== '' && query[key] != null)
      .map((key) => [key, query[key]])
  )
  return { ...compactPayload(serverDefaults), ...routeFilters }
}

// REQ-056：异常标记和告警详情均原样消费后端 drillParams/costDeepLink。
export function costDrillTarget(drillParams = {}) {
  return { path: '/energy/cost/record', query: compactPayload(drillParams) }
}

export function costTraceTarget(row = {}, costVersion) {
  return compactPayload({
    statMonth: row.statMonth,
    objectType: row.objectType,
    objectId: row.objectId,
    energyType: row.energyType,
    costVersion
  })
}

export function costWriteFailurePolicy(status) {
  const code = Number(status)
  return {
    permissionBlocked: code === 403,
    refresh: code === 409,
    preserveInput: code !== 403
  }
}

export function reportFileName(headers = {}, fallback = 'energy-report.xlsx') {
  const disposition = headers['content-disposition'] || headers['Content-Disposition'] || ''
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  if (encoded) {
    try { return decodeURIComponent(encoded) } catch { return encoded }
  }
  return disposition.match(/filename="?([^";]+)"?/i)?.[1] || fallback
}

export function errorStatus(error) {
  return Number(error?.status || error?.responseStatus || error?.response?.status || error?.code)
}

// REQ-073/074：骨架把 admin 当全局角色，成本业务写入口必须显式收窄。
export function costBusinessAccess(roles = []) {
  const values = new Set(roles)
  const readOnlyAdmin = values.has('admin')
  const finance = !readOnlyAdmin && values.has('finance')
  const manager = !readOnlyAdmin && values.has('energy_mgr')
  return {
    tariff: finance || manager,
    allocation: finance,
    recompute: finance || manager,
    review: finance,
    suggestion: finance || manager,
    // 契约 §6.8：报表预览/导出/归档仅 finance / energy_mgr，admin 无权限。
    report: finance || manager
  }
}

// REQ-030/062：items 之外的统计、复核状态与质量摘要也属于 canonical section。
export function reportSectionMeta(section = {}) {
  return Object.fromEntries(Object.entries(section || {}).filter(([key]) => key !== 'items'))
}

// REQ-055：COST_DIFF 的对象差异必须展开，不得因主表列裁剪而丢失。
export function reportDiffRows(section = {}) {
  return (section.items || []).flatMap((item) => (item.diffSummary || []).map((diff) => ({
    recomputeId: item.recomputeId,
    oldCostVersion: item.oldCostVersion,
    newCostVersion: item.newCostVersion,
    ...diff
  })))
}

export const periodStateLabels = Object.freeze({
  complete: '完整月', partial: '部分月', inProgress: '进行中'
})

export const costStatusLabels = Object.freeze({
  draft: '草稿', pendingReview: '待审核', reviewed: '已复核', frozen: '已冻结',
  pendingRecompute: '重算待复核', void: '已作废', missingTariff: '单价缺失'
})

export const energyTypeLabels = Object.freeze({
  electricity: '电力', water: '水', compressed_air: '压缩空气'
})

// 报表 canonical section 的中文标签（后端键 → 展示层措辞，对照 PRD §5.9 / 契约 §6）
export const reportSectionLabels = Object.freeze({
  usageSection: '用量',
  costSection: '成本',
  alertSection: '异常',
  suggestionSection: '建议',
  qualitySection: '质量说明'
})

// section 内 meta 字段的中文标签（items 之外的聚合统计）
export const reportSectionFieldLabels = Object.freeze({
  totalCost: '本期总成本',
  totalUsage: '本期用量',
  reviewState: '复核状态',
  statistics: '统计摘要',
  effectiveRate: '建议有效率',
  qualityDistribution: '质量分布',
  usageQty: '用量',
  coverageRate: '覆盖率',
  period: '周期',
  signature: '口径签名',
  versionSnapshots: '参与版本快照',
  items: '明细',
  count: '数量',
  status: '状态',
  ruleCode: '规则编号',
  note: '说明',
  qualityRate: '质量率',
  suggestionCount: '建议数量',
  closedCount: '已关闭数量'
})

// 重算差异行的中文列名
export const diffSummaryFieldLabels = Object.freeze({
  recomputeId: '重算编号',
  oldCostVersion: '旧版本',
  newCostVersion: '新版本',
  objectType: '对象类型',
  objectId: '对象编号',
  objectCode: '对象编码',
  objectName: '对象名称',
  metric: '指标',
  oldValue: '旧值',
  newValue: '新值',
  deltaValue: '差额',
  deltaPct: '差异率'
})

// 报表模板代号 → 中文名称
export const reportTemplateLabels = Object.freeze({
  ENERGY_DAILY: '能源日报',
  ENERGY_MONTHLY: '能源月报（五段式）',
  EQUIPMENT_PROFILE: '设备画像专项',
  SUGGESTION_RETROSPECTIVE: '建议复盘专项',
  COST_DIFF: '成本差异专项'
})

// 模板内含 section 段名的中文映射（用于下拉候选说明）
export function reportTemplateSectionSummary(sections = []) {
  return sections.map((section) => reportSectionLabels[section] || section).join(' / ')
}

// 复核状态展示（reviewState 与成本状态复用同一枚举集）
export const reviewStateLabels = costStatusLabels

// 三步反查步骤中文说明
export const traceStepLabels = Object.freeze({
  usage: '用量证据',
  tariff: '单价版本',
  allocation: '分摊规则'
})

// anomalyEvidence.source 中文（后端 cost_query_service._anomaly_evidence 硬编码为 'alertSnapshot'，
// 契约 §5.9 line 496 亦锁定唯一取值；实测 focus=R10 返回该值，无 R10 时 anomalyEvidence 整体为 null）
export const anomalyEvidenceSourceLabels = Object.freeze({
  alertSnapshot: '告警冻结快照'
})

// 分摊方法中文
export const allocationMethodLabels = Object.freeze({
  ratio: '比例',
  weight: '权重',
  workload: '工作量',
  manual: '人工指定'
})

// section meta 字段中文标签查询（未命中原样返回，避免"看不到就以为丢字段"）
export function reportSectionFieldLabel(field) {
  return reportSectionFieldLabels[field] || field
}

// diff 列中文标签查询
export function diffSummaryFieldLabel(field) {
  return diffSummaryFieldLabels[field] || field
}
