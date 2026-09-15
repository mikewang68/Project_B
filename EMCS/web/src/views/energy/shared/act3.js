// 第三幕共享展示与交互辅助｜REQ-033–035、REQ-039–041
export const levelLabels = Object.freeze({ notice: '提示', normal: '一般', severe: '严重' })
export const alertStatusLabels = Object.freeze({
  new: '新告警',
  ack: '已确认',
  dispatched: '已派发',
  processing: '处理中',
  closed: '已关闭',
  false_closed: '误报关闭',
  escalated: '已升级'
})
export const equipmentStateLabels = Object.freeze({
  running: '运行',
  standby: '待机',
  stopped: '停机',
  maintenance: '维护'
})

const stateColors = Object.freeze({
  running: '#409EFF',
  standby: '#E6A23C',
  stopped: '#909399',
  maintenance: '#E6A23C'
})

export function validateTransition(toStatus, form = {}) {
  if (toStatus === 'dispatched' && !form.assignedTo?.trim()) {
    return '派发人工处置时必须选择处理人'
  }
  if (['closed', 'false_closed'].includes(toStatus) && !form.closeReason?.trim()) {
    return '关闭告警时必须填写原因'
  }
  if (!form.remark?.trim()) {
    return '人工处置必须填写留痕备注'
  }
  return ''
}

// REQ-041 状态流转：按目标状态裁剪 payload，避免空字符串触发后端 422（close_type Literal 校验）
export function buildTransitionPayload(form = {}) {
  const toStatus = form.toStatus
  const payload = { toStatus, remark: form.remark || '' }
  if (toStatus === 'dispatched') {
    if (form.assignedTo) payload.assignedTo = form.assignedTo
  }
  if (toStatus === 'closed' || toStatus === 'false_closed') {
    payload.closeType = toStatus === 'false_closed' ? 'false_positive' : 'valid'
    if (form.closeReason) payload.closeReason = form.closeReason
  }
  return payload
}

// REQ-039 设备画像跳转：仅设备类告警可跳，区域/点位类无对应画像页
export function canJumpToProfile(detail = {}) {
  return detail?.event?.object?.type === 'equipment'
}

export function buildProfileQuery(detail = {}) {
  const window = detail.curveSnapshot?.window || {}
  return {
    equipmentId: detail.event?.object?.code || detail.event?.object?.id || '',
    eventId: detail.event?.eventId || '',
    periodStart: window.start || '',
    periodEnd: window.end || ''
  }
}

export function compactQuery(query = {}) {
  return Object.fromEntries(
    Object.entries(query).filter(([, value]) => value !== '' && value != null)
  )
}

export function activeAlertEventId(query = {}, equipmentId, energyType) {
  if (!query.eventId || String(query.equipmentId || '') !== String(equipmentId || '')) return undefined
  return energyType === 'ELEC' ? query.eventId : undefined
}

export function withoutAlertContext(query = {}) {
  const nextQuery = { ...query }
  delete nextQuery.eventId
  delete nextQuery.action
  return nextQuery
}

export function buildStateAreas(segments = [], { withLabels = true } = {}) {
  // withLabels=false：不写 name → markArea 顶部不出状态文字（状态改由 tooltip
  // 悬停显示,用户裁决 2026-07-20——段多时顶部标签互相重叠不可读）
  return segments.map((segment) => [
    {
      xAxis: segment.start,
      ...(withLabels ? { name: equipmentStateLabels[segment.state] || segment.state } : {}),
      itemStyle: {
        color: stateColors[segment.state] || stateColors.stopped,
        opacity: 0.14
      }
    },
    { xAxis: segment.end }
  ])
}
