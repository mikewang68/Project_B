import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  allowsAction,
  assertSuggestionRequestSafe,
  boardColumn,
  buildActivityPayload,
  buildTransitionPayload,
  buildVerificationPayload,
  canConvertAlert,
  changeCloseType,
  currentSourceAlertEventId,
  implementedTargetStatus,
  itemsForColumn,
  sortFlows,
  stripOneShotQuery,
  suggestionDetailTarget,
  usesServerFixedVerificationWindow,
  validateClose,
  validateTransition,
  writeFailurePolicy
} from '../src/views/energy/shared/act4.js'

test('REQ-047: seven statuses map to five columns while deferred stays separate', () => {
  assert.equal(boardColumn('pending'), 'pending')
  assert.equal(boardColumn('dispatched'), 'dispatched')
  assert.equal(boardColumn('executing'), 'executing')
  assert.equal(boardColumn('verifying'), 'verifying')
  assert.equal(boardColumn('valid_closed'), 'closed')
  assert.equal(boardColumn('invalid_closed'), 'closed')
  assert.equal(boardColumn('deferred'), null)
  assert.equal(boardColumn('unknown'), null)
})

test('REQ-049: cards retain backend order inside each board column', () => {
  const rows = [
    { suggestionId: 9, status: 'valid_closed', priority: { score: 10 } },
    { suggestionId: 2, status: 'pending', priority: { score: 99 } },
    { suggestionId: 7, status: 'invalid_closed', priority: { score: 90 } },
    { suggestionId: 3, status: 'deferred', priority: { score: 100 } }
  ]
  assert.deepEqual(itemsForColumn(rows, 'closed').map((row) => row.suggestionId), [9, 7])
  assert.deepEqual(itemsForColumn(rows, 'deferred').map((row) => row.suggestionId), [3])
  assert.deepEqual(rows.map((row) => row.suggestionId), [9, 2, 7, 3])
})

test('REQ-047: equal demo timestamps are ordered only by numeric flowId', () => {
  const flows = [
    { flowId: 12, occurTime: '2026-07-12 23:59:00' },
    { flowId: 3, occurTime: '2026-07-12 23:59:00' },
    { flowId: 8, occurTime: '2026-07-01 00:00:00' }
  ]
  assert.deepEqual(sortFlows(flows).map((flow) => flow.flowId), [3, 8, 12])
  assert.deepEqual(flows.map((flow) => flow.flowId), [12, 3, 8])
})

test('REQ-047: implemented close requires result evidence and an attachment', () => {
  assert.match(validateClose('implemented', {}), /节能量或效果结论/)
  assert.match(validateClose('implemented', { effectSummary: '有效' }), /附件/)
  assert.match(validateClose('implemented', {
    savingValue: 2.5, attachments: [{ name: 'inspection.pdf' }]
  }), /单位/)
  assert.equal(validateClose('implemented', {
    effectSummary: '有效', attachments: [{ name: 'inspection.pdf', url: '/upload/inspection.pdf' }]
  }), '')
  assert.equal(validateClose('implemented', {
    savingValue: 2.5, savingUnit: 'm³', attachments: [{ name: 'inspection.pdf' }]
  }), '')
})

test('REQ-047: rejected and archived-invalid closes enforce their own required fields', () => {
  assert.match(validateClose('rejected', { rejectionReason: '不适用' }), /责任人/)
  assert.equal(validateClose('rejected', {
    rejectionReason: '不适用', responsibleUser: 'ops_user'
  }), '')
  assert.match(validateClose('archived_invalid', { invalidCategory: 'duplicate' }), /关闭原因/)
  assert.equal(validateClose('archived_invalid', {
    invalidCategory: 'duplicate', closeReason: '与既有建议重复'
  }), '')
})

test('REQ-047: switching close tier removes every field owned by the previous tier', () => {
  const implemented = {
    closeType: 'implemented', savingValue: 2.5, savingUnit: 'm³', effectSummary: '有效',
    attachments: [{ name: 'a' }], rejectionReason: 'stale', responsibleUser: 'stale',
    invalidCategory: 'stale', closeReason: 'stale', remark: '保留审计备注'
  }
  assert.deepEqual(changeCloseType(implemented, 'rejected'), {
    closeType: 'rejected', savingValue: '', savingUnit: '', effectSummary: '', attachments: [],
    rejectionReason: '', responsibleUser: '', invalidCategory: '', closeReason: '',
    remark: '保留审计备注'
  })
})

test('REQ-047: transition payload carries only fields for its action and close tier', () => {
  const rejected = buildTransitionPayload({
    action: 'close', toStatus: 'invalid_closed', closeType: 'rejected',
    rejectionReason: '超出范围', responsibleUser: 'ops_user', remark: '复核',
    assignedTo: 'must-not-leak', savingValue: 9, invalidCategory: 'must-not-leak', rowVersion: 5
  })
  assert.deepEqual(rejected, {
    toStatus: 'invalid_closed', closeType: 'rejected', rejectionReason: '超出范围',
    responsibleUser: 'ops_user', remark: '复核', rowVersion: 5
  })

  assert.deepEqual(buildTransitionPayload({
    action: 'defer', toStatus: 'deferred', deferReason: '等待备件',
    deferUntil: '2026-07-20', remark: '人工跟进', closeType: 'implemented', rowVersion: 6
  }), {
    toStatus: 'deferred', deferReason: '等待备件', deferUntil: '2026-07-20',
    remark: '人工跟进', rowVersion: 6
  })
  assert.throws(() => buildTransitionPayload({ toStatus: 'executing', remark: '缺少版本' }), /rowVersion/)
})

test('REQ-047: activity payload is limited to audit fields from the form and row version', () => {
  assert.deepEqual(buildActivityPayload({
    remark: '已完成人工巡检', attachments: [{ name: '记录.pdf' }], rowVersion: 7,
    operator: 'forged_user', operatorRole: 'forged_role', status: 'valid_closed'
  }), {
    remark: '已完成人工巡检', attachments: [{ name: '记录.pdf' }], rowVersion: 7
  })
  assert.throws(() => buildActivityPayload({ remark: '缺少版本' }), /rowVersion/)
})

test('REQ-048: verification generation always carries the current row version', () => {
  assert.deepEqual(buildVerificationPayload(9), { rowVersion: 9 })
  assert.throws(() => buildVerificationPayload(), /rowVersion/)
})

test('REQ-047: entering verification does not invent a client-only date requirement', () => {
  assert.equal(validateTransition({
    action: 'startVerification', toStatus: 'verifying', remark: '已有人工执行记录'
  }), '')
})

test('REQ-047/048: R06 uses the server-fixed verification window while manual suggestions retain dates', () => {
  const fixedDetail = {
    suggestion: { sourceType: 'rule', ruleCode: 'R06' },
    sourceSnapshot: { ruleCode: 'R06' }
  }
  assert.equal(usesServerFixedVerificationWindow(fixedDetail), true)
  assert.equal(usesServerFixedVerificationWindow({
    suggestion: { sourceType: 'manual' }, sourceSnapshot: { ruleCode: 'R06' }
  }), false)
  assert.equal(usesServerFixedVerificationWindow(null), false)

  const form = {
    toStatus: 'verifying', rowVersion: 3, remark: '执行记录已齐全',
    verifyStart: '2026-07-01', verifyEnd: '2026-07-12'
  }
  assert.deepEqual(buildTransitionPayload(form, { includeVerificationWindow: false }), {
    toStatus: 'verifying', rowVersion: 3, remark: '执行记录已齐全'
  })
  assert.deepEqual(buildTransitionPayload(form), {
    toStatus: 'verifying', rowVersion: 3, remark: '执行记录已齐全',
    verifyStart: '2026-07-01', verifyEnd: '2026-07-12'
  })
})

test('REQ-047/048: implemented close target follows the latest verification result', () => {
  assert.equal(implementedTargetStatus({ status: 'effective' }), 'valid_closed')
  assert.equal(implementedTargetStatus({ status: 'ineffective' }), 'invalid_closed')
  assert.equal(implementedTargetStatus({ status: 'insufficient' }), null)
  assert.equal(implementedTargetStatus({ status: 'waiting' }), null)
  assert.equal(implementedTargetStatus(null), null)
})

test('REQ-047: action visibility consumes backend allowedActions without role inference', () => {
  assert.equal(allowsAction({ allowedActions: ['addActivity'] }, 'addActivity'), true)
  assert.equal(allowsAction({ allowedActions: ['addActivity'] }, 'close'), false)
  assert.equal(allowsAction({ allowedActions: [] }, 'dispatch'), false)
  assert.equal(allowsAction({}, 'dispatch'), false)
})

test('REQ-045: conversion reads the backend boolean and never branches on a rule code', () => {
  assert.equal(canConvertAlert({ canConvertToSuggestion: true, event: { ruleCode: 'R08' } }), true)
  assert.equal(canConvertAlert({ canConvertToSuggestion: false, event: { ruleCode: 'R06' } }), false)
  assert.equal(canConvertAlert({ event: { ruleCode: 'R06' } }), false)
})

test('REQ-045: suggestion deep links are restorable and one-shot query state is removed', () => {
  assert.deepEqual(suggestionDetailTarget(42), {
    path: '/energy/alert/suggestion', query: { suggestionId: 42 }
  })
  assert.deepEqual(stripOneShotQuery({ suggestionId: '42', action: 'close', create: '1', zone: 'B' }), {
    suggestionId: '42', zone: 'B'
  })
})

test('REQ-045: source alert deep link consumes only the recovered current event id', () => {
  assert.equal(currentSourceAlertEventId({
    suggestion: { currentAlertEventId: 153, sourceAlertId: 99 },
    sourceSnapshot: { currentEventId: 88, relatedEventId: 77, eventId: 66 }
  }), 153)
  assert.equal(currentSourceAlertEventId({
    suggestion: { sourceAlertId: 99 }, sourceSnapshot: { currentEventId: 88 }
  }), null)
  assert.equal(currentSourceAlertEventId(null), null)
})

test('REQ-047/048: write failure policy blocks 403, refreshes 409 and preserves 422 input', () => {
  assert.deepEqual(writeFailurePolicy(403), {
    permissionBlocked: true, refresh: false, closeDetail: false, preserveInput: false
  })
  assert.deepEqual(writeFailurePolicy(409), {
    permissionBlocked: false, refresh: true, closeDetail: false, preserveInput: true
  })
  assert.deepEqual(writeFailurePolicy(422), {
    permissionBlocked: false, refresh: false, closeDetail: false, preserveInput: true
  })
})

test('REQ-091/096: request guard rejects control keys and phrases recursively', () => {
  assert.throws(() => assertSuggestionRequestSafe({ setPoint: 30 }), /不得包含设备控制/)
  assert.throws(() => assertSuggestionRequestSafe({ nested: [{ note: '请远程关阀' }] }), /不得包含设备控制/)
  for (const marker of [
    'controlCommand', 'startStop', 'powerSetpoint', 'setValue',
    'executeDeviceAction', 'remoteValve', 'autoControl'
  ]) {
    assert.throws(
      () => assertSuggestionRequestSafe({ title: `unsafe-${marker}-value` }),
      /不得包含设备控制/
    )
  }
  assert.doesNotThrow(() => assertSuggestionRequestSafe({
    remark: '安排人工巡检并记录处理结果', attachments: [{ name: '记录.pdf' }]
  }))
})

test('REQ-045–050: API module exposes the frozen suggestion endpoints', async () => {
  const source = await readFile(new URL('../src/api/suggestions.js', import.meta.url), 'utf8')
  assert.match(source, /url:\s*'\/suggestions'/)
  assert.match(source, /`\/suggestions\/\$\{suggestionId\}`/)
  assert.match(source, /`\/alerts\/\$\{eventId\}\/suggestions`/)
  assert.match(source, /`\/suggestions\/\$\{suggestionId\}\/transition`/)
  assert.match(source, /`\/suggestions\/\$\{suggestionId\}\/activities`/)
  assert.match(source, /`\/suggestions\/\$\{suggestionId\}\/verification\/generate`/)
  assert.match(source, /url:\s*'\/suggestions\/retrospective'/)
  assert.match(source, /function requireRowVersion\(rowVersion\)[\s\S]*rowVersion == null[\s\S]*throw new Error/)
  assert.match(source, /transitionSuggestion\(suggestionId,\s*body\s*=\s*\{\}\)[\s\S]*rowVersion:\s*requireRowVersion\(body\.rowVersion\)/)
  assert.match(source, /addSuggestionActivity\(suggestionId,\s*\{\s*remark,\s*attachments,\s*rowVersion\s*\}\)/)
  assert.match(source, /data:\s*safeBody\(\{\s*remark,\s*attachments,\s*rowVersion:\s*requireRowVersion\(rowVersion\)\s*\}\)/)
  assert.match(source, /generateSuggestionVerification\(suggestionId,\s*\{\s*rowVersion\s*\}\)/)
  assert.match(source, /verification\/generate`[\s\S]*data:\s*safeBody\(\{\s*rowVersion:\s*requireRowVersion\(rowVersion\)\s*\}\)/)
  assert.doesNotMatch(source, /verification\/generate`[\s\S]*safeBody\(\{\s*\}\)/)
})

test('REQ-045–050: page consumes board counts, allowed actions and four-dimensional snapshots', async () => {
  const source = await readFile(
    new URL('../src/views/energy/alert/suggestion.vue', import.meta.url), 'utf8'
  )
  assert.match(source, /boardCounts/)
  assert.match(source, /itemsForColumn/)
  assert.match(source, /allowedActions/)
  assert.match(source, /sortFlows/)
  assert.match(source, /usageComparison/)
  assert.match(source, /costComparison/)
  assert.match(source, /workloadComparison/)
  assert.match(source, /qualityComparison/)
  assert.match(source, /latestVerification\.status === 'insufficient'/)
  assert.match(source, /implementedTargetStatus\(latestVerification\.value\)/)
  assert.match(source, /generateSuggestionVerification\([\s\S]*buildVerificationPayload\(detail\.value\.suggestion\.rowVersion\)/)
  assert.match(source, /usesServerFixedVerificationWindow/)
  assert.match(source, /writePermissionError/)
  assert.match(source, /writeFailurePolicy/)
  assert.match(source, /getRequestErrorStatus\(error\)/)
  assert.doesNotMatch(source, /function errorStatus\(/)
  assert.match(source, /currentSourceAlertEventId\(detail\.value\)/)
  assert.doesNotMatch(source, /sourceSnapshot\.value\.(?:currentEventId|relatedEventId)/)
  assert.doesNotMatch(source, /sourceAlertId/)
  const writeHandler = source.slice(
    source.indexOf('async function handleWriteError'),
    source.indexOf('async function refreshSuggestionContext')
  )
  assert.ok(writeHandler.indexOf('policy.permissionBlocked') < writeHandler.indexOf('assignMessage(message)'))
  assert.match(writeHandler, /await refreshSuggestionContext\(\)[\s\S]*refreshedRowVersion[\s\S]*transitionForm\.rowVersion\s*=\s*refreshedRowVersion/)
  assert.match(source, /label="节能量单位"[\s\S]*:required="transitionForm\.savingValue !== '' && transitionForm\.savingValue != null"/)
  assert.doesNotMatch(source, /draggable|vuedraggable/i)
  assert.doesNotMatch(source, /\.sort\s*\(/)
  assert.doesNotMatch(source, /energy_mgr|\bops\b|\badmin\b/)
  assert.match(source, /detail\.value\?\.allowedActions/)
})

test('REQ-050: monthly retrospective renders close-type distribution and sends the selected month', async () => {
  const source = await readFile(
    new URL('../src/views/energy/alert/suggestion.vue', import.meta.url), 'utf8'
  )
  assert.match(source, /type="month"/)
  assert.match(source, /v-model="retrospectiveMonth"/)
  assert.match(source, /closeTypeCounts\?\.implemented/)
  assert.match(source, /closeTypeCounts\?\.rejected/)
  assert.match(source, /closeTypeCounts\?\.archivedInvalid/)
  assert.match(
    source,
    /getSuggestionRetrospective\(compactSuggestionQuery\(\{[\s\S]*month:\s*retrospectiveMonth\.value/
  )
  assert.match(source, /retrospectiveMonth\.value\s*=\s*payload\.filters\?\.month/)
})

test('REQ-048: normal verification results always render the calculation note', async () => {
  const source = await readFile(
    new URL('../src/views/energy/alert/suggestion.vue', import.meta.url), 'utf8'
  )
  assert.match(source, /class="calculation-note"/)
  assert.match(source, /节能量计算说明/)
  assert.match(source, /latestVerification\.calculationNote/)
})

test('REQ-050: rule optimization basis is formatted as structured data', async () => {
  const source = await readFile(
    new URL('../src/views/energy/alert/suggestion.vue', import.meta.url), 'utf8'
  )
  assert.match(
    source,
    /displayValue\(hint\.basis\s*\?\?\s*hint\.statisticalBasis\s*\?\?\s*hint\)/
  )
  assert.doesNotMatch(source, /\{\{\s*hint\.basis\s*\|\|/)
})

test('REQ-047: the closed summary sends the aggregate closed filter', async () => {
  const source = await readFile(
    new URL('../src/views/energy/alert/suggestion.vue', import.meta.url), 'utf8'
  )
  assert.match(source, /filters\.status\s*=\s*column/)
  assert.match(source, /value:\s*['"]closed['"],\s*label:\s*['"]已关闭/)
  assert.doesNotMatch(source, /column\s*===\s*['"]closed['"]\s*\?\s*['"]{2}/)
})

test('REQ-045: alert detail exposes generic conversion and related-suggestion deep link', async () => {
  const source = await readFile(
    new URL('../src/views/energy/alert/list.vue', import.meta.url), 'utf8'
  )
  assert.match(source, /canConvertAlert\(detail\.value\)/)
  assert.match(source, /relatedSuggestionId/)
  assert.match(source, /createSuggestionFromAlert/)
  assert.doesNotMatch(source, /ruleCode\s*===?\s*['"]R08['"]/)
})

test('REQ-091/096: rendered Act 4 pages contain no control affordance or command wording', async () => {
  const sources = await Promise.all([
    readFile(new URL('../src/views/energy/alert/suggestion.vue', import.meta.url), 'utf8'),
    readFile(new URL('../src/views/energy/alert/list.vue', import.meta.url), 'utf8')
  ])
  const forbidden = ['设备启停', '远程关阀', '功率设定', '自动控制', '指令下发']
  for (const source of sources) {
    for (const term of forbidden) assert.doesNotMatch(source, new RegExp(term))
  }
})
