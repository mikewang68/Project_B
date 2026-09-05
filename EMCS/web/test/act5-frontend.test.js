import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'

import {
  compactPayload,
  costBusinessAccess,
  costDrillTarget,
  costFiltersFromRoute,
  costTraceTarget,
  costWriteFailurePolicy,
  reportDiffRows,
  reportSectionMeta,
  reportFileName
} from '../src/views/energy/shared/act5.js'

const root = new URL('../src/', import.meta.url)

test('REQ-051: real defaults come from the server and route only overrides explicit fields', () => {
  const defaults = { statMonth: 'server-month', zone: 'ALL', energyType: 'electricity', groupBy: 'area' }
  assert.deepEqual(costFiltersFromRoute({}, defaults), defaults)
  assert.deepEqual(costFiltersFromRoute({ zone: 'B', focus: 'R10', empty: '' }, defaults), {
    ...defaults, zone: 'B', focus: 'R10'
  })
})

test('REQ-056: R10 drill parameters are preserved including source event', () => {
  const drillParams = {
    statMonth: 'report-month', zone: 'B', energyType: 'electricity',
    focus: 'R10', sourceEventId: 18
  }
  assert.deepEqual(costDrillTarget(drillParams), {
    path: '/energy/cost/record', query: drillParams
  })
})

test('REQ-051/055: trace query is precise and never invents version data', () => {
  const row = { statMonth: 'report-month', objectType: 'area', objectId: 2, energyType: 'electricity' }
  assert.deepEqual(costTraceTarget(row), row)
  assert.deepEqual(costTraceTarget(row, 'v2'), { ...row, costVersion: 'v2' })
  assert.deepEqual(compactPayload({ a: 0, b: '', c: null, d: false }), { a: 0, d: false })
})

test('REQ-073/074: cost write failures preserve contract behavior', () => {
  assert.deepEqual(costWriteFailurePolicy(403), {
    permissionBlocked: true, refresh: false, preserveInput: false
  })
  assert.deepEqual(costWriteFailurePolicy(409), {
    permissionBlocked: false, refresh: true, preserveInput: true
  })
  assert.deepEqual(costWriteFailurePolicy(422), {
    permissionBlocked: false, refresh: false, preserveInput: true
  })
})

test('REQ-059/062: report filename accepts RFC and ordinary content disposition', () => {
  assert.equal(reportFileName({ 'content-disposition': "attachment; filename*=UTF-8''energy%20report.xlsx" }, 'fallback.xlsx'), 'energy report.xlsx')
  assert.equal(reportFileName({ 'content-disposition': 'attachment; filename="report.xlsx"' }, 'fallback.xlsx'), 'report.xlsx')
  assert.equal(reportFileName({}, 'fallback.xlsx'), 'fallback.xlsx')
})

test('REQ-051-062: API modules use the frozen endpoints and POST blob export', async () => {
  const cost = await readFile(new URL('../src/api/cost.js', import.meta.url), 'utf8')
  const reports = await readFile(new URL('../src/api/reports.js', import.meta.url), 'utf8')
  for (const endpoint of ['/cost/month-view', '/cost/trace', '/cost/tariffs', '/cost/allocation-rules', '/cost/recomputations']) {
    assert.match(cost, new RegExp(endpoint.replaceAll('/', '\\/')))
  }
  for (const endpoint of ['/reports/templates', '/reports/preview', '/reports/export', '/reports/archives']) {
    assert.match(reports, new RegExp(endpoint.replaceAll('/', '\\/')))
  }
  assert.match(reports, /responseType:\s*['"]blob['"]/) 
  assert.match(reports, /method:\s*['"]post['"]/)
})

test('REQ-051-062: cost pages have no mock imports or frozen demo anchors', async () => {
  const directory = new URL('../src/views/energy/cost/', import.meta.url)
  let files = []
  try {
    files = await readdir(directory, { recursive: true })
  } catch (error) {
    if (error.code === 'ENOENT') return
    throw error
  }
  const source = (await Promise.all(files.filter((file) => file.endsWith('.vue') || file.endsWith('.js')).map((file) => readFile(new URL(file, directory), 'utf8')))).join('\n')
  assert.doesNotMatch(source, /59501\.99|161920\.03|138014\.50|12\.368|2026-07-12/)
  assert.doesNotMatch(source, /(?:from|import\s*\()\s*['"][^'"]*mock/i)
  assert.ok(!path.isAbsolute('web'), 'test must remain workspace-portable')
})

test('REQ-051-056: monthly cost page consumes server discovery and trace evidence', async () => {
  const record = await readFile(new URL('../src/views/energy/cost/record.vue', import.meta.url), 'utf8')
  const trend = await readFile(new URL('../src/views/energy/cost/components/CostMonthTrend.vue', import.meta.url), 'utf8')
  const tou = await readFile(new URL('../src/views/energy/cost/components/TouCompositionChart.vue', import.meta.url), 'utf8')
  const trace = await readFile(new URL('../src/views/energy/cost/components/CostTraceDrawer.vue', import.meta.url), 'utf8')
  for (const field of ['period', 'monthTrend', 'anomalyEvidence', 'touComposition', 'groups', 'topCostObjects', 'costWarnings']) {
    assert.match(record, new RegExp(field))
  }
  assert.match(record, /管理核算口径，不替代财务结算/)
  assert.match(trend, /drillParams/)
  assert.match(tou, /flatOnly/)
  assert.match(trace, /用量证据/)
  assert.match(trace, /单价版本/)
  assert.match(trace, /分摊规则/)
  assert.match(trace, /allocationStatus/)
  assert.doesNotMatch(record + trend, /momPct\s*[><=]|Math\.[^(]+\([^)]*momPct/)
  assert.doesNotMatch(tou, /usageQty\s*\*|price\s*\*/)
})

test('REQ-051/054/055/073/074: cost version controls preserve server ownership and K.6 roles', async () => {
  const files = await Promise.all([
    'TariffVersionTable.vue', 'AllocationRuleTable.vue', 'RecomputeDiffTable.vue'
  ].map((name) => readFile(new URL(`../src/views/energy/cost/components/${name}`, import.meta.url), 'utf8')))
  const [tariff, allocation, recompute] = files
  assert.match(tariff, /v-hasRole="\['energy_mgr', 'finance'\]"/)
  assert.match(allocation, /v-hasRole="\['finance'\]"/)
  assert.match(recompute, /v-hasRole="\['finance'\]"/)
  assert.match(tariff, /recomputeRequired/)
  assert.doesNotMatch(tariff, /createCostRecomputation/)
  assert.match(recompute, /diffSummary/)
  assert.match(recompute, /oldValue/)
  assert.match(recompute, /newValue/)
  assert.match(recompute, /deltaPct/)
  assert.match(recompute, /costWriteFailurePolicy/)
  assert.doesNotMatch(files.join('\n'), /userName|username|finance_user/)
})

test('REQ-030/059/061/062: report page renders canonical sections and frozen archives', async () => {
  const report = await readFile(new URL('../src/views/energy/cost/report.vue', import.meta.url), 'utf8')
  const preview = await readFile(new URL('../src/views/energy/cost/components/ReportPreview.vue', import.meta.url), 'utf8')
  const archives = await readFile(new URL('../src/views/energy/cost/components/ReportArchiveTable.vue', import.meta.url), 'utf8')
  const download = await readFile(new URL('../src/plugins/download.js', import.meta.url), 'utf8')
  assert.match(report, /templates/)
  assert.match(report, /subscription\.enabled/)
  assert.match(report, /:disabled="!subscription\.enabled"/)
  assert.match(report, /defaultPeriod/)
  assert.match(preview, /generatedAt/)
  assert.match(preview, /versionSnapshots/)
  assert.match(preview, /usageSection/)
  assert.match(preview, /costSection/)
  assert.match(preview, /alertSection/)
  assert.match(preview, /suggestionSection/)
  assert.match(preview, /qualitySection/)
  assert.match(archives, /payloadSnapshot/)
  assert.match(archives, /frozen|冻结/i)
  assert.match(download, /postBlob/)
  assert.match(download, /blobValidate/)
  assert.match(download, /printErrMsg/)
})

test('REQ-045/051-056: alert and suggestion deep links consume backend evidence only', async () => {
  const alert = await readFile(new URL('../src/views/energy/alert/list.vue', import.meta.url), 'utf8')
  const suggestion = await readFile(new URL('../src/views/energy/alert/suggestion.vue', import.meta.url), 'utf8')
  const record = await readFile(new URL('../src/views/energy/cost/record.vue', import.meta.url), 'utf8')
  const api = await readFile(new URL('../src/api/suggestions.js', import.meta.url), 'utf8')
  assert.match(alert, /detail\.costDeepLink/)
  assert.match(alert, /goCostEvidence/)
  assert.doesNotMatch(alert, /ruleCode\s*===?\s*['"]R10['"][\s\S]{0,160}cost/)
  assert.match(suggestion, /sourceSnapshot\.deepLink/)
  assert.match(suggestion, /返回成本证据/)
  assert.match(record, /createCostSuggestion/)
  assert.match(record, /sourceContext/)
  assert.match(api, /sourceContext/)
  assert.doesNotMatch(record, /encodeURIComponent\(JSON\.stringify\(sourceContext\)\)/)
})

test('REQ-055: recomputation expansion loads the frozen detail before rendering diffs', async () => {
  const recompute = await readFile(new URL('../src/views/energy/cost/components/RecomputeDiffTable.vue', import.meta.url), 'utf8')
  assert.match(recompute, /getCostRecomputation/)
  assert.match(recompute, /@expand-change="loadDetail"/)
  assert.match(recompute, /detailLoading/)
  assert.match(recompute, /traceLinks/)
})

test('REQ-030/055/062: report helpers preserve section aggregates and nested cost differences', () => {
  const section = {
    items: [{ recomputeId: 7, oldCostVersion: 'v1', newCostVersion: 'v2', diffSummary: [
      { objectType: 'area', objectId: 2, metric: 'totalCost', oldValue: 10, newValue: 12, deltaPct: 20 }
    ] }],
    totalCost: 12,
    reviewState: 'reviewed',
    statistics: { total: 1 },
    effectiveRate: 80,
    qualityDistribution: [{ status: 'qualified', count: 1 }]
  }
  assert.deepEqual(reportSectionMeta(section), {
    totalCost: 12,
    reviewState: 'reviewed',
    statistics: { total: 1 },
    effectiveRate: 80,
    qualityDistribution: [{ status: 'qualified', count: 1 }]
  })
  assert.deepEqual(reportDiffRows(section), [{
    recomputeId: 7, oldCostVersion: 'v1', newCostVersion: 'v2',
    objectType: 'area', objectId: 2, metric: 'totalCost', oldValue: 10, newValue: 12, deltaPct: 20
  }])
})

test('REQ-073/074: admin remains read-only while business roles retain their narrow writes', () => {
  assert.deepEqual(costBusinessAccess(['admin', 'finance']), {
    tariff: false, allocation: false, recompute: false, review: false, suggestion: false, report: false
  })
  assert.deepEqual(costBusinessAccess(['energy_mgr']), {
    tariff: true, allocation: false, recompute: true, review: false, suggestion: true, report: true
  })
  assert.deepEqual(costBusinessAccess(['finance']), {
    tariff: true, allocation: true, recompute: true, review: true, suggestion: true, report: true
  })
})

// FX-23：契约 §6.8 报表能力仅 finance / energy_mgr；admin / ops / dispatch 全部落空态。
test('FX-23 / REQ-073/074: report capability follows contract §6.8 narrow authorization', () => {
  assert.equal(costBusinessAccess(['admin']).report, false)
  assert.equal(costBusinessAccess(['admin', 'finance']).report, false)
  assert.equal(costBusinessAccess(['finance']).report, true)
  assert.equal(costBusinessAccess(['energy_mgr']).report, true)
  assert.equal(costBusinessAccess(['ops']).report, false)
  assert.equal(costBusinessAccess(['dispatch']).report, false)
  assert.equal(costBusinessAccess([]).report, false)
})

// FX-23：report.vue 挂载守卫 + 空态；record.vue 入口对无权限角色隐藏。
test('FX-23 / REQ-073/074: report page gates /reports/* requests and exposes friendly empty state', async () => {
  const report = await readFile(new URL('../src/views/energy/cost/report.vue', import.meta.url), 'utf8')
  assert.match(report, /costBusinessAccess/)
  assert.match(report, /access\.report/)
  assert.match(report, /if\(access\.value\.report\)loadTemplates\(\)/)
  assert.match(report, /报表中心仅财务与能源管理员可用/)
  const record = await readFile(new URL('../src/views/energy/cost/record.vue', import.meta.url), 'utf8')
  assert.match(record, /v-if="access\.report"[^>]*>报表导出中心/)
})

test('REQ-051/054: route changes reload safely and allocation evidence is fully visible', async () => {
  const record = await readFile(new URL('../src/views/energy/cost/record.vue', import.meta.url), 'utf8')
  const allocation = await readFile(new URL('../src/views/energy/cost/components/AllocationRuleTable.vue', import.meta.url), 'utf8')
  assert.match(record, /watch\(\(\) => route\.query/)
  assert.match(record, /requestSequence/)
  assert.doesNotMatch(record, /onMounted\(\(\) => load/)
  assert.match(allocation, /config/)
  assert.match(allocation, /effectiveFrom/)
  assert.match(allocation, /effectiveTo/)
})

// FX-26 补记：契约 §5.9 line 496 与后端 cost_query_service._anomaly_evidence 硬编码
// source='alertSnapshot'；页面必须走展示层映射，不再直出英文枚举。
test("FX-26 fixup: anomalyEvidence.source is mapped to Chinese and record.vue consumes the map", async () => {
  const shared = await readFile(new URL('../src/views/energy/shared/act5.js', import.meta.url), 'utf8')
  assert.match(shared, /anomalyEvidenceSourceLabels/)
  assert.match(shared, /alertSnapshot:\s*'告警冻结快照'/)
  const record = await readFile(new URL('../src/views/energy/cost/record.vue', import.meta.url), 'utf8')
  assert.match(record, /anomalyEvidenceSourceLabels\[view\.anomalyEvidence\.source\]/)
  // 面板不再直插裸 source
  assert.doesNotMatch(record, /\{\{\s*view\.anomalyEvidence\.source\s*\}\}/)
})
