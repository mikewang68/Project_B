/**
 * 运维监控 + 云边断网自治 API（Backend Demo；SIMULATED EDGE AUTONOMY）。
 * 路径严格对齐 docs/backend-demo-api-inventory.json 的 /ops、/edge 段。
 */
import { apiRequest, postJson } from './http'

// ---------- 后端 DTO（与 com.bproject.safety.module.ops 模型字段对齐） ----------

export type BackendNodeStatus = 'ONLINE' | 'DEGRADED' | 'OFFLINE' | 'RECOVERING' | 'ERROR'
export type PendingEventStatus = 'PENDING' | 'SYNCING' | 'SYNCED' | 'FAILED' | 'DUPLICATE'
export type PhaseStatus = 'WAIT' | 'RUNNING' | 'DONE' | 'FAILED' | 'BLOCKED'

export interface BackendRecoveryPhase {
  key: string
  label: string
  status: PhaseStatus
  startedAt?: string
  completedAt?: string
  message?: string
}

export interface BackendCachePart {
  label: string
  percent: number
}

export interface BackendEdgeNode {
  id: string
  name: string
  area: string
  ip: string
  status: BackendNodeStatus
  cloudConnected: boolean
  autonomyActive: boolean
  lastHeartbeat?: string
  latencyMs: number | null
  cpuUsage: number
  memoryUsage: number
  diskUsage: number
  temperature: number
  queueDepth: number
  cachedEventCount: number
  activeRuleVersion: string
  expectedRuleVersion: string
  activeFenceVersion: string
  expectedFenceVersion: string
  clockOffsetMs: number | null
  agentVersion: string
  uptimeSec: number
  lastSyncAt?: string
  lastError?: string | null
  recoveryPhases?: BackendRecoveryPhase[]
  recoveryPhase?: string | null
  cacheParts: BackendCachePart[]
}

export interface BackendLocalLinkage {
  alarm: boolean
  screen: boolean
  localVoice: boolean
  localLight: boolean
  plcStop: 'success' | 'fail' | 'skipped' | string
  actions: string[]
}

export interface BackendPayloadSummary {
  person?: string
  fence?: string
  device?: string
  risk?: string
  businessId?: string
  title?: string
  area?: string
  detail?: string
}

export interface BackendPendingEvent {
  eventId: string
  edgeNodeId: string
  eventType: string
  businessKey: string
  edgeOccurredAt: string
  receivedAt: string
  serverReceivedAt?: string
  syncedAt?: string
  idempotencyKey: string
  clockOffsetAtOccurrence: number | null
  ruleVersionUsed: string
  risk: string
  payloadSummary: BackendPayloadSummary
  localLinkage: BackendLocalLinkage
  status: PendingEventStatus
  retryCount: number
  lastRetryAt?: string
  lastError?: string | null
  linkedAlertId?: string
  syncDelaySec?: number
}

export interface BackendOpsDevice {
  id: string
  name: string
  type: string
  category: string
  area: string
  edgeNodeId: string
  status: 'online' | 'offline' | 'fault'
  lastSeen?: string
  latencyMs?: number
  health?: number
  issue?: string | null
}

export interface BackendOpsInterface {
  id: string
  name: string
  type: string
  status: 'normal' | 'degraded' | 'fault'
  latencyMs: number
  successRate: number
  lastCheck?: string
  message?: string
}

export interface BackendOpsEvent {
  id: string
  ts?: string
  time: string
  nodeId?: string
  level: 'info' | 'warning' | 'error'
  type: string
  text: string
}

export interface BackendComponentHealth {
  key: string
  name: string
  capabilityState: 'UP' | 'DEGRADED' | 'DISABLED' | 'UNAVAILABLE'
  state: string
  message?: string
}

export interface BackendCloudLink {
  state: 'online' | 'link-error' | 'disconnected' | 'recovering'
  label: string
  simulated: boolean
  nodes: { nodeId: string; cloudConnected: boolean; autonomyActive: boolean; status: BackendNodeStatus }[]
  updatedAt?: string
}

export interface OpsOverview {
  environment: string
  simulatedEdgeAutonomy: boolean
  edgeNodesTotal: number
  edgeNodesOnline: number
  edgeNodesOffline: number
  degradedNodes: number
  pendingSyncEvents: number
  failedSyncEvents: number
  ruleMismatchNodes: number
  clockDriftNodes: number
  apiHealthy: number
  apiDegraded: number
  alertPipelineStatus: string
  realtimeStatus: string
  components: BackendComponentHealth[]
  cloudLink: BackendCloudLink
  lastUpdated: string
}

// ---------- API ----------

export const operationsApi = {
  overview: () => apiRequest<OpsOverview>('/ops/overview'),
  topology: () => apiRequest<unknown>('/ops/topology'),
  edgeNodes: () => apiRequest<{ list: BackendEdgeNode[] }>('/ops/edge-nodes'),
  edgeNode: (id: string) => apiRequest<{ node: BackendEdgeNode; trend: Record<string, number[]>; localEvents: BackendPendingEvent[] }>(`/ops/edge-nodes/${id}`),
  devices: () => apiRequest<{ list: BackendOpsDevice[] }>('/ops/devices'),
  reconnectDevice: (id: string) => postJson<BackendOpsDevice>(`/ops/devices/${id}/reconnect`),
  interfaces: () => apiRequest<{ list: BackendOpsInterface[] }>('/ops/interfaces'),
  events: (params: Record<string, string | number | undefined> = {}) => {
    const qs = new URLSearchParams()
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== null && `${v}` !== '') qs.set(k, `${v}`)
    })
    const suffix = qs.toString()
    return apiRequest<{ list: BackendOpsEvent[] }>(`/ops/events${suffix ? `?${suffix}` : ''}`)
  },
  maintain: (id: string, action: 'reconnect' | 'resyncTime' | 'redeliverRule') =>
    postJson<BackendEdgeNode>(`/ops/edge-nodes/${id}/maintain`, { action }),
  simulate: (scenario: 'deviceFault' | 'cacheAlert' | 'timeDrift', targetId?: string) =>
    postJson<Record<string, unknown>>('/ops/simulate', { scenario, targetId }),

  link: () => apiRequest<BackendCloudLink>('/edge/link'),
  simulateLink: (state: 'disconnect' | 'recover', nodeId = 'EDGE-03') =>
    postJson<BackendEdgeNode>('/edge/simulate-link', { state, nodeId }),
  localEvents: (node?: string, status?: string) => {
    const qs = new URLSearchParams()
    if (node) qs.set('node', node)
    if (status) qs.set('status', status)
    const suffix = qs.toString()
    return apiRequest<{ list: BackendPendingEvent[] }>(`/edge/local-events${suffix ? `?${suffix}` : ''}`)
  },
  createLocalEvent: (
    body: { nodeId?: string; eventType?: string; risk?: string; title?: string; detail?: string; failNextReplay?: boolean } = {},
  ) =>
    postJson<BackendPendingEvent>('/edge/local-events', body),
  recover: (nodeId = 'EDGE-03') => postJson<BackendEdgeNode>('/edge/recover', { nodeId }),
  ruleReconcileView: () => apiRequest<{ expectedRuleVersion: string; nodes: unknown[] }>('/edge/reconcile/rules'),
  reconcileRules: (action: 'redeliver' | 'keep', nodeId = 'EDGE-03') =>
    postJson<BackendEdgeNode>(`/edge/reconcile/rules?nodeId=${encodeURIComponent(nodeId)}`, { action }),
  reconcileTime: (nodeId = 'EDGE-03') =>
    postJson<BackendEdgeNode>(`/edge/reconcile/time?nodeId=${encodeURIComponent(nodeId)}`, { nodeId }),
}
