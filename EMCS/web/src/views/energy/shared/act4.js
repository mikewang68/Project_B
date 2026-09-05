// 第四幕共享展示与交互辅助｜REQ-045–050、REQ-091/096

export const suggestionStatusLabels = Object.freeze({
  pending: '待审核',
  dispatched: '已分派',
  executing: '执行中',
  verifying: '验证中',
  valid_closed: '有效关闭',
  invalid_closed: '无效关闭',
  deferred: '已延期'
})

export const priorityBandLabels = Object.freeze({ high: '高', medium: '中', low: '低' })

export const verificationStatusLabels = Object.freeze({
  waiting: '等待验证',
  effective: '验证有效',
  ineffective: '验证无效',
  insufficient: '数据不足'
})

export const boardColumnDefinitions = Object.freeze([
  { key: 'pending', label: '待审核' },
  { key: 'dispatched', label: '已分派' },
  { key: 'executing', label: '执行中' },
  { key: 'verifying', label: '验证中' },
  { key: 'closed', label: '已关闭' }
])

const statusToColumn = Object.freeze({
  pending: 'pending',
  dispatched: 'dispatched',
  executing: 'executing',
  verifying: 'verifying',
  valid_closed: 'closed',
  invalid_closed: 'closed'
})

const closeOwnedFields = Object.freeze([
  'savingValue', 'savingUnit', 'effectSummary', 'attachments',
  'rejectionReason', 'responsibleUser', 'invalidCategory', 'closeReason'
])

const forbiddenText = Object.freeze([
  '设备启停', '远程启停', '远程关阀', '远程开阀', '功率调节', '功率设定',
  '设定值调节', '自动控制', '下发指令'
])

const forbiddenFieldMarkers = Object.freeze([
  'controlcommand', 'devicecommand', 'remotevalve', 'controlinstruction',
  'poweradjustment', 'powersetpoint', 'startstop', 'setpoint',
  'setvalue', 'automaticcontrol', 'autocontrol', 'executedeviceaction'
])

export function boardColumn(status) {
  return statusToColumn[status] || null
}

// REQ-049：只过滤后端已经排好序的数组，不在前端重排。
export function itemsForColumn(items = [], column) {
  if (column === 'deferred') return items.filter((item) => item.status === 'deferred')
  return items.filter((item) => boardColumn(item.status) === column)
}

// REQ-047：DEMO_NOW 可令多条记录同刻；flowId 是唯一排序键。
export function sortFlows(flows = []) {
  return [...flows].sort((left, right) => Number(left.flowId) - Number(right.flowId))
}

function nonBlank(value) {
  return typeof value === 'string' ? Boolean(value.trim()) : value !== '' && value != null
}

export function validateClose(closeType, form = {}) {
  if (closeType === 'implemented') {
    const hasSavingValue = nonBlank(form.savingValue)
    if (!hasSavingValue && !nonBlank(form.effectSummary)) {
      return '实施完成必须填写节能量或效果结论'
    }
    if (hasSavingValue && !nonBlank(form.savingUnit)) return '填写节能量时必须填写单位'
    if (!Array.isArray(form.attachments) || form.attachments.length === 0) {
      return '实施完成必须至少保留一个附件证据'
    }
    return ''
  }
  if (closeType === 'rejected') {
    if (!nonBlank(form.rejectionReason)) return '驳回必须填写原因'
    if (!nonBlank(form.responsibleUser)) return '驳回必须明确责任人'
    return ''
  }
  if (closeType === 'archived_invalid') {
    if (!nonBlank(form.invalidCategory)) return '归档无效必须选择分类'
    if (!nonBlank(form.closeReason)) return '归档无效必须填写关闭原因'
    return ''
  }
  return '请选择关闭类型'
}

// REQ-047：切换三档时先清空所有档位字段，防止隐藏值串档。
export function changeCloseType(form = {}, closeType = '') {
  const next = { ...form, closeType }
  for (const field of closeOwnedFields) next[field] = field === 'attachments' ? [] : ''
  return next
}

export function validateTransition(form = {}) {
  if (!nonBlank(form.toStatus)) return '接口未生成合法的目标状态'
  if (!nonBlank(form.remark)) return '请填写本次人工处理备注'
  if (form.toStatus === 'dispatched' && !nonBlank(form.assignedTo)) return '派发必须填写责任人'
  if (form.toStatus === 'deferred') {
    if (!nonBlank(form.deferReason)) return '延期必须填写原因'
    if (!nonBlank(form.deferUntil)) return '延期必须填写恢复日期'
  }
  if (form.action === 'close' || form.closeType) return validateClose(form.closeType, form)
  return ''
}

// REQ-047/048：实施完成的最终状态由最新后端验证结论决定。
export function implementedTargetStatus(latestVerification) {
  if (latestVerification?.status === 'effective') return 'valid_closed'
  if (latestVerification?.status === 'ineffective') return 'invalid_closed'
  return null
}

function copyIfPresent(target, source, field) {
  if (nonBlank(source[field])) target[field] = source[field]
}

// REQ-047：请求只携带当前动作/关闭档位字段，服务端继续执行最终硬校验。
export function buildTransitionPayload(form = {}, { includeVerificationWindow = true } = {}) {
  if (form.rowVersion == null) throw new Error('rowVersion 为必填并发版本')
  const payload = { toStatus: form.toStatus, remark: form.remark || '' }
  payload.rowVersion = form.rowVersion

  if (form.toStatus === 'dispatched') copyIfPresent(payload, form, 'assignedTo')
  if (form.toStatus === 'verifying' && includeVerificationWindow) {
    copyIfPresent(payload, form, 'verifyStart')
    copyIfPresent(payload, form, 'verifyEnd')
  }
  if (form.toStatus === 'deferred') {
    copyIfPresent(payload, form, 'deferReason')
    copyIfPresent(payload, form, 'deferUntil')
  }

  const closing = ['valid_closed', 'invalid_closed'].includes(form.toStatus) || form.action === 'close'
  if (closing && form.closeType === 'implemented') {
    payload.closeType = 'implemented'
    copyIfPresent(payload, form, 'savingValue')
    copyIfPresent(payload, form, 'savingUnit')
    copyIfPresent(payload, form, 'effectSummary')
    if (Array.isArray(form.attachments) && form.attachments.length) payload.attachments = form.attachments
  } else if (closing && form.closeType === 'rejected') {
    payload.closeType = 'rejected'
    copyIfPresent(payload, form, 'rejectionReason')
    copyIfPresent(payload, form, 'responsibleUser')
  } else if (closing && form.closeType === 'archived_invalid') {
    payload.closeType = 'archived_invalid'
    copyIfPresent(payload, form, 'invalidCategory')
    copyIfPresent(payload, form, 'closeReason')
  }
  return payload
}

// REQ-047：执行留痕只允许表单证据与并发版本进入请求，身份和状态取服务端上下文。
export function buildActivityPayload(form = {}) {
  if (form.rowVersion == null) throw new Error('rowVersion 为必填并发版本')
  return {
    remark: form.remark || '',
    attachments: Array.isArray(form.attachments) ? form.attachments : [],
    rowVersion: form.rowVersion
  }
}

// REQ-048：验证生成是带并发版本的写操作，禁止空请求体。
export function buildVerificationPayload(rowVersion) {
  if (rowVersion == null) throw new Error('rowVersion 为必填并发版本')
  return { rowVersion }
}

// REQ-047/048：运行期 R06 的验证窗口由服务端固定，人工建议仍保留通用日期能力。
export function usesServerFixedVerificationWindow(detail = {}) {
  const suggestion = detail?.suggestion || {}
  const ruleCode = suggestion.ruleCode || detail?.sourceSnapshot?.ruleCode
  return suggestion.sourceType === 'rule' && ruleCode === 'R06'
}

export function allowsAction(detail = {}, action) {
  return Array.isArray(detail.allowedActions) && detail.allowedActions.includes(action)
}

// REQ-045：适用性完全由后端返回，不在前端按规则编号推断。
export function canConvertAlert(detail = {}) {
  return detail.canConvertToSuggestion === true
}

export function suggestionDetailTarget(suggestionId) {
  return { path: '/energy/alert/suggestion', query: { suggestionId } }
}

// REQ-045：深链只消费后端按来源指纹恢复出的当前告警 ID。
export function currentSourceAlertEventId(detail = {}) {
  return detail?.suggestion?.currentAlertEventId ?? null
}

export function writeFailurePolicy(status) {
  const code = Number(status)
  return {
    permissionBlocked: code === 403,
    refresh: code === 409,
    closeDetail: code === 404,
    preserveInput: code !== 403 && code !== 404
  }
}

export function stripOneShotQuery(query = {}) {
  const next = { ...query }
  delete next.action
  delete next.create
  return next
}

export function compactSuggestionQuery(query = {}) {
  return Object.fromEntries(
    Object.entries(query).filter(([, value]) => value !== '' && value != null)
  )
}

function normalizedField(value) {
  return String(value).toLowerCase().replace(/[^a-z0-9]+/g, '')
}

// REQ-091/096：所有建议域写请求在进入网络层前再做一次递归禁控扫描。
export function assertSuggestionRequestSafe(value) {
  if (Array.isArray(value)) {
    value.forEach(assertSuggestionRequestSafe)
    return value
  }
  if (value && typeof value === 'object') {
    for (const [key, nested] of Object.entries(value)) {
      const field = normalizedField(key)
      if (forbiddenFieldMarkers.some((marker) => field.includes(marker))) {
        throw new Error('REQ-091/096：建议请求不得包含设备控制字段')
      }
      assertSuggestionRequestSafe(nested)
    }
    return value
  }
  if (typeof value === 'string') {
    const normalizedValue = normalizedField(value)
    if (
      forbiddenText.some((term) => value.includes(term)) ||
      forbiddenFieldMarkers.some((marker) => normalizedValue.includes(marker))
    ) {
      throw new Error('REQ-091/096：建议请求不得包含设备控制内容')
    }
  }
  return value
}
