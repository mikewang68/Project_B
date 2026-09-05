import type { AlertEvent, AlertStatus, LinkageStepState } from '@/types/alert'
import { mapAlertEvent } from './alert'
import type { IncidentSource, IncidentStatus, MobileIncident } from '@/types/incident'

/**
 * Alert（权威源）→ 移动端 / 大屏 ViewModel 适配层。
 * 三端共享同一 alertId；移动端六态由 Alert 主状态 + mobileStage 投影得到。
 * 距离、地图坐标无真实定位来源，按 id 稳定派生（Demo，刷新不变）。
 */

const INCIDENT_SOURCES: IncidentSource[] = ['人员安全', '设备防碰撞', 'AI违规', '设备异常', '系统异常']

/** 主状态 + 现场阶段 → 移动端展示状态 */
export function mapMobileStatus(alertStatus: AlertStatus, mobileStage?: string | null): IncidentStatus {
  switch (alertStatus) {
    case '已关闭':
      return '已关闭'
    case '待复核':
      return '待复核'
    case '处理中':
      return '处理中'
    case '待处理':
      if (mobileStage === 'ACCEPTED') return '已接单'
      if (mobileStage === 'ARRIVED') return '已到场'
      return '待接单'
    case '已升级':
      return mobileStage === 'ACCEPTED' ? '已接单' : mobileStage === 'ARRIVED' ? '已到场' : '处理中'
    default:
      // 待确认 / 待派单 / 已确认：尚未派发现场，列表层会过滤，这里给待接单占位
      return '待接单'
  }
}

/** 字符串稳定哈希（用于派生稳定的 Demo 距离/坐标） */
function hashOf(text: string): number {
  let hash = 0
  for (let i = 0; i < text.length; i += 1) {
    hash = (hash * 31 + text.charCodeAt(i)) >>> 0
  }
  return hash
}

function stablePos(id: string): { x: number; y: number } {
  const h = hashOf(id)
  return { x: 18 + (h % 70), y: 16 + ((h >> 8) % 62) }
}

function stableDistance(id: string): number {
  return 42 + (hashOf(`dist-${id}`) % 188)
}

function hmm(isoOrHms?: string | null): string | undefined {
  if (!isoOrHms) return undefined
  // 已是 HH:mm:ss 直接截成 HH:mm
  const match = /(\d{2}:\d{2})(:\d{2})?/.exec(isoOrHms)
  return match ? match[1] : undefined
}

type MobileLinkage = MobileIncident['linkage']

function stepStateToTone(state: LinkageStepState | undefined): NonNullable<MobileLinkage>['soundLight'] {
  if (state === 'success') return 'done'
  if (state === 'running') return 'running'
  if (state === 'failed') return 'failed'
  return 'idle'
}

function mapLinkage(alert: AlertEvent): MobileLinkage {
  if (!alert.linkageAvailable && alert.linkage.length === 0) return undefined
  const byId = new Map(alert.linkage.map((s) => [s.id, s.state]))
  const requested = alert.linkage.some((s) => s.state !== 'wait')
  return {
    soundLight: stepStateToTone(byId.get('sound-light')),
    shutdown: stepStateToTone(byId.get('shutdown')),
    plc: stepStateToTone(byId.get('plc')),
    requested,
  }
}

const FALLBACK_EVIDENCE: MobileIncident['evidence'] = {
  kind: 'system-metric',
  metrics: [],
  description: '事件证据加载中',
}

/** AlertEvent → MobileIncident（单条投影） */
export function mapAlertToMobileIncident(input: AlertEvent | unknown): MobileIncident {
  const alert = mapAlertEvent(input)
  const mobileStatus = mapMobileStatus(alert.status, alert.mobileStage)
  const treatment = alert.treatment
  const attachment = treatment?.attachment ?? ''
  const photoMatch = /(\d+)\s*张/.exec(attachment)
  const photos = photoMatch
    ? [{ id: `ph-${alert.id}`, name: '现场照片（Mock）', thumb: 'mock', time: hmm(treatment?.submitTime) ?? '' }]
    : []
  const source = (INCIDENT_SOURCES as string[]).includes(alert.source)
    ? (alert.source as IncidentSource)
    : '系统异常'

  return {
    id: alert.id,
    title: alert.title,
    risk: alert.risk,
    source,
    area: alert.area,
    target: alert.target,
    distanceM: stableDistance(alert.id),
    time: hmm(alert.time) ?? alert.time,
    status: mobileStatus,
    alertStatus: alert.status,
    mobileStage: alert.mobileStage,
    pos: stablePos(alert.id),
    evidence: alert.evidence ?? FALLBACK_EVIDENCE,
    timeline: alert.timeline,
    assignee: alert.assignee,
    acceptTime: hmm(alert.acceptedAt) ?? hmm(alert.acceptTime),
    arriveTime: hmm(alert.arrivedAt),
    handleTime: hmm(alert.acceptTime),
    submitTime: hmm(treatment?.submitTime),
    measures: treatment?.measures ?? [],
    siteNote: treatment?.result ?? '',
    riskCleared: treatment ? treatment.result?.includes('解除') ?? false : null,
    photos,
    note: treatment?.note ?? '',
    upgradedFrom: alert.upgradedFrom,
    occurredAt: alert.occurredAt,
    linkage: mapLinkage(alert),
  }
}

export function mapAlertsToMobileIncidents(list: unknown[]): MobileIncident[] {
  return list.map(mapAlertToMobileIncident)
}
