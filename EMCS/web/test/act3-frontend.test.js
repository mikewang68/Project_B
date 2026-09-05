import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  activeAlertEventId,
  buildProfileQuery,
  buildStateAreas,
  buildTransitionPayload,
  canJumpToProfile,
  compactQuery,
  withoutAlertContext,
  validateTransition
} from '../src/views/energy/shared/act3.js'

test('REQ-041: dispatch requires an assignee', () => {
  assert.equal(
    validateTransition('dispatched', { assignedTo: '', remark: 'check' }),
    '派发人工处置时必须选择处理人'
  )
  assert.equal(
    validateTransition('dispatched', { assignedTo: 'operator', remark: 'check' }),
    ''
  )
})

test('REQ-041: close transitions require a reason', () => {
  assert.equal(validateTransition('closed', { closeReason: '' }), '关闭告警时必须填写原因')
  assert.equal(validateTransition('false_closed', { closeReason: 'verified', remark: 'checked' }), '')
})

test('REQ-041: every transition requires an audit remark', () => {
  assert.equal(validateTransition('ack', { remark: '' }), '人工处置必须填写留痕备注')
  assert.equal(validateTransition('ack', { remark: 'checked' }), '')
})

test('REQ-039: optional alert filters do not send invalid empty literals', () => {
  assert.deepEqual(compactQuery({ level: '', status: null, zone: 'ALL', pageNum: 1 }), {
    zone: 'ALL', pageNum: 1
  })
})

test('REQ-033/035: alert context only survives on the original electric equipment', () => {
  const query = { eventId: 'event-ref', equipmentId: 'equipment-ref' }
  assert.equal(activeAlertEventId(query, 'equipment-ref', 'ELEC'), 'event-ref')
  assert.equal(activeAlertEventId(query, 'another-equipment', 'ELEC'), undefined)
  assert.equal(activeAlertEventId(query, 'equipment-ref', 'WATER'), undefined)
})

test('REQ-033/035: switching profile context removes alert and dispatch query state', () => {
  assert.deepEqual(
    withoutAlertContext({ eventId: 'event-ref', action: 'dispatch', zone: 'A' }),
    { zone: 'A' }
  )
})

test('REQ-033/035: alert detail produces a restorable equipment-profile query', () => {
  const query = buildProfileQuery({
    event: { eventId: 'event-ref', object: { code: 'equipment-ref' } },
    curveSnapshot: { window: { start: 'window-start', end: 'window-end' } }
  })

  assert.deepEqual(query, {
    equipmentId: 'equipment-ref',
    eventId: 'event-ref',
    periodStart: 'window-start',
    periodEnd: 'window-end'
  })
})

test('REQ-033: state segments become ECharts mark areas without inventing values', () => {
  const areas = buildStateAreas([
    { start: 'segment-start', end: 'segment-end', state: 'standby' }
  ])

  assert.deepEqual(areas, [[
    {
      xAxis: 'segment-start',
      name: '待机',
      itemStyle: { color: '#F5A524', opacity: 0.14 }
    },
    { xAxis: 'segment-end' }
  ]])
})

test('REQ-041: non-close transitions do not send empty closeType (backend 422)', () => {
  const payload = buildTransitionPayload({
    toStatus: 'dispatched', assignedTo: 'operator', remark: 'go',
    closeType: '', closeReason: ''
  })
  assert.equal(payload.toStatus, 'dispatched')
  assert.equal(payload.assignedTo, 'operator')
  assert.equal(payload.remark, 'go')
  assert.ok(!('closeType' in payload), 'closeType must be omitted for non-close transitions')
  assert.ok(!('closeReason' in payload), 'closeReason must be omitted for non-close transitions')
})

test('REQ-041: close transitions carry closeType and closeReason', () => {
  const closed = buildTransitionPayload({ toStatus: 'closed', closeReason: '已核实', remark: 'ok' })
  assert.equal(closed.closeType, 'valid')
  assert.equal(closed.closeReason, '已核实')
  const falseClosed = buildTransitionPayload({ toStatus: 'false_closed', closeReason: '误报', remark: 'ok' })
  assert.equal(falseClosed.closeType, 'false_positive')
})

test('REQ-041: ack/processing/escalated transitions strip optional close fields', () => {
  for (const toStatus of ['ack', 'processing', 'escalated']) {
    const payload = buildTransitionPayload({ toStatus, remark: 'r', closeType: '', closeReason: '' })
    assert.ok(!('closeType' in payload), `${toStatus} must not carry closeType`)
    assert.ok(!('assignedTo' in payload), `${toStatus} must not carry empty assignedTo`)
  }
})

test('REQ-039: only equipment-scoped alerts can jump to equipment profile', () => {
  assert.equal(canJumpToProfile({ event: { object: { type: 'equipment' } } }), true)
  assert.equal(canJumpToProfile({ event: { object: { type: 'area' } } }), false)
  assert.equal(canJumpToProfile({ event: { object: { type: 'point' } } }), false)
  assert.equal(canJumpToProfile({}), false)
})

test('REQ-039–044: alert page restores query detail and links to profile', async () => {
  const source = await readFile(
    new URL('../src/views/energy/alert/list.vue', import.meta.url),
    'utf8'
  )

  assert.match(source, /route\.query\.eventId/)
  assert.match(source, /buildProfileQuery\(detail\.value\)/)
  assert.match(source, /派发人工处置/)
  assert.match(source, /transitionAlert/)
  assert.match(source, /formatDateTime\(order\.startTime\)/)
  assert.doesNotMatch(source, /ack:\s*\[[^\]]*escalated/)
})

test('REQ-033–035: profile page wires charts, work orders, evidence and drilldown', async () => {
  const source = await readFile(
    new URL('../src/views/energy/analysis/profile.vue', import.meta.url),
    'utf8'
  )

  assert.match(source, /stateEnergySeries/)
  assert.match(source, /workOrderMatch/)
  assert.match(source, /shiftComparison/)
  assert.match(source, /inefficiencyEvidence/)
  assert.match(source, /\/raw-quality/)
  assert.match(source, /pointId:\s*point\.pointCode/)
  assert.match(source, /formatDateTime\(row\.startTime\)/)
})

test('REQ-035/041: profile returns to alert detail and opens manual dispatch', async () => {
  const profileSource = await readFile(
    new URL('../src/views/energy/analysis/profile.vue', import.meta.url),
    'utf8'
  )
  const alertSource = await readFile(
    new URL('../src/views/energy/alert/list.vue', import.meta.url),
    'utf8'
  )

  assert.match(profileSource, /path:\s*'\/energy\/alert\/alert-list'/)
  assert.match(profileSource, /action:\s*'dispatch'/)
  assert.match(profileSource, /查看并派发/)
  assert.match(alertSource, /route\.query\.action === 'dispatch'/)
  assert.match(alertSource, /openTransition\('dispatched'\)/)
})

test('REQ-034: equipment-card unit follows the selected energy filter', async () => {
  const source = await readFile(
    new URL('../src/views/energy/analysis/profile.vue', import.meta.url),
    'utf8'
  )

  assert.match(source, /energyUnit\(\)/)
  assert.match(source, /function energyUnit\(\)[\s\S]*filters\.energyType/)
  assert.doesNotMatch(source, /energyUnit\(card\.energyTypes\)/)
})
