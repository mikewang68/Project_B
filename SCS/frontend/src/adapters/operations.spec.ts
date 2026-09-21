import { describe, expect, it } from 'vitest'
import {
  NODE_STATUS_LABEL,
  onlineCount,
  toCloudLink,
  toDeviceCategories,
  toEdgeNode,
  toLocalEvents,
  toOpsEvents,
  toOpsInterfaces,
  toRecoverySteps,
} from './operations'
import type {
  BackendEdgeNode,
  BackendOpsDevice,
  BackendOpsEvent,
  BackendOpsInterface,
  BackendPendingEvent,
  BackendRecoveryPhase,
} from '@/api/operations'

function baseNode(over: Partial<BackendEdgeNode> = {}): BackendEdgeNode {
  return {
    id: 'EDGE-03',
    name: '3 号边缘节点 · 翻箱机区',
    area: '翻箱机区',
    ip: '10.24.1.13',
    status: 'ONLINE',
    cloudConnected: true,
    autonomyActive: false,
    lastHeartbeat: '2026-09-10T12:00:00+08:00',
    latencyMs: 57,
    cpuUsage: 44,
    memoryUsage: 58,
    diskUsage: 68,
    temperature: 28,
    queueDepth: 0,
    cachedEventCount: 1512,
    activeRuleVersion: 'v3.3',
    expectedRuleVersion: 'v3.3',
    activeFenceVersion: 'v3.3',
    expectedFenceVersion: 'v3.3',
    clockOffsetMs: 32,
    agentVersion: 'edge-agent 1.4.2',
    uptimeSec: 100,
    cacheParts: [],
    ...over,
  }
}

describe('operations adapter', () => {
  it('maps node status to centralized Chinese labels', () => {
    expect(NODE_STATUS_LABEL.ONLINE).toBe('正常')
    expect(NODE_STATUS_LABEL.DEGRADED).toBe('降级')
    expect(NODE_STATUS_LABEL.OFFLINE).toBe('离线')
    expect(NODE_STATUS_LABEL.RECOVERING).toBe('恢复中')
    expect(NODE_STATUS_LABEL.ERROR).toBe('异常')
  })

  it('maps online node: online=true, small clock offset hidden as normal', () => {
    const n = toEdgeNode(baseNode({ clockOffsetMs: 120 }))
    expect(n.online).toBe(true)
    expect(n.autonomy).toBe(false)
    expect(n.ruleVersion).toBe('v3.3')
    expect(n.platformVersion).toBe('v3.3')
    expect(n.timeOffsetMs).toBeNull()
    expect(n.lastHeartbeat).toBe('刚刚')
    expect(n.recentIssue).toContain('缓存占用偏高')
  })

  it('maps offline autonomous node: frozen heartbeat, autonomy on, drift shown', () => {
    const n = toEdgeNode(baseNode({
      status: 'OFFLINE',
      cloudConnected: false,
      autonomyActive: true,
      activeRuleVersion: 'v3.2',
      clockOffsetMs: 2800,
      queueDepth: 3,
    }))
    expect(n.online).toBe(false)
    expect(n.autonomy).toBe(true)
    expect(n.timeOffsetMs).toBe(2800)
    expect(n.cacheEvents).toBe(3)
    expect(n.lastHeartbeat).toBe('已中断')
    expect(n.recentIssue).toContain('边缘本地自治')
  })

  it('maps RECOVERING node as online-ish with mismatch issue', () => {
    const n = toEdgeNode(baseNode({
      status: 'RECOVERING',
      cloudConnected: true,
      activeRuleVersion: 'v3.2',
      expectedRuleVersion: 'v3.3',
      clockOffsetMs: 120,
    }))
    expect(n.online).toBe(true)
    expect(n.recentIssue).toContain('规则版本')
  })

  it('groups flat devices into five categories and maps health state', () => {
    const devices: BackendOpsDevice[] = [
      { id: 'CAM-01', name: '摄像头 1', type: '摄像头', category: '视频感知', area: 'A', edgeNodeId: 'EDGE-01', status: 'online' },
      { id: 'CAM-02', name: '摄像头 2', type: '摄像头', category: '视频感知', area: 'A', edgeNodeId: 'EDGE-01', status: 'fault', issue: '无响应' },
      { id: 'PLC-01', name: 'PLC 1', type: 'PLC 控制器', category: '设备控制', area: 'A', edgeNodeId: 'EDGE-01', status: 'offline' },
    ]
    const cats = toDeviceCategories(devices)
    expect(cats.map((c) => c.kind)).toEqual(['摄像头', '雷达', '定位基站', 'PLC接口', '声光设备'])
    const cam = cats.find((c) => c.kind === '摄像头')!
    expect(cam.total).toBe(2)
    expect(cam.devices[0]!.state).toBe('normal')
    expect(cam.devices[1]!.state).toBe('fault')
    const plc = cats.find((c) => c.kind === 'PLC接口')!
    expect(plc.devices[0]!.state).toBe('offline')
    expect(onlineCount(cam.devices)).toBe(1)
  })

  it('maps interfaces with cloud-side flag (IF-PLC stays local)', () => {
    const list: BackendOpsInterface[] = [
      { id: 'IF-SCHED', name: '规则下发', type: '规则引擎', status: 'normal', latencyMs: 36, successRate: 99.98 },
      { id: 'IF-PLC', name: 'PLC 控制', type: '设备控制', status: 'normal', latencyMs: 31, successRate: 99.99 },
      { id: 'IF-TWIN', name: '孪生', type: '孪生', status: 'degraded', latencyMs: 136, successRate: 98.7 },
    ]
    const mapped = toOpsInterfaces(list)
    expect(mapped[0]!.cloudSide).toBe(true)
    expect(mapped[1]!.cloudSide).toBe(false)
    expect(mapped[2]!.state).toBe('degraded')
    expect(mapped[2]!.errorCount).toBe(1)
  })

  it('maps ops event levels by type (offline/fault/degraded/warning/info)', () => {
    const events: BackendOpsEvent[] = [
      { id: '1', time: '12:00:00', nodeId: 'EDGE-03', level: 'error', type: 'NODE_OFFLINE', text: '断网' },
      { id: '2', time: '12:01:00', nodeId: 'CAM-1', level: 'error', type: 'DEVICE_FAULT', text: '故障' },
      { id: '3', time: '12:02:00', nodeId: 'EDGE-03', level: 'warning', type: 'RULE_MISMATCH', text: '版本不一致' },
      { id: '4', time: '12:03:00', nodeId: 'EDGE-03', level: 'warning', type: 'LOCAL_JUDGEMENT', text: '本地判定' },
      { id: '5', time: '12:04:00', nodeId: 'EDGE-03', level: 'info', type: 'NODE_ONLINE', text: '上线' },
    ]
    const mapped = toOpsEvents(events)
    expect(mapped.map((e) => e.level)).toEqual(['offline', 'fault', 'degraded', 'warning', 'info'])
    expect(mapped[0]!.target).toBe('EDGE-03')
  })

  it('maps queue status to Chinese labels and prefers payload title', () => {
    const events: BackendPendingEvent[] = [
      {
        eventId: 'E1', edgeNodeId: 'EDGE-03', eventType: 'person-intrusion', businessKey: 'k1',
        edgeOccurredAt: '2026-09-10T12:00:00+08:00', receivedAt: '2026-09-10T12:00:00+08:00',
        idempotencyKey: 'idem-1', clockOffsetAtOccurrence: 2800, ruleVersionUsed: 'v3.2', risk: '紧急',
        payloadSummary: { title: '人员进入危险围栏' },
        localLinkage: { alarm: true, screen: true, localVoice: true, localLight: true, plcStop: 'success', actions: ['现场声光报警', 'PLC 停车指令已执行'] },
        status: 'PENDING', retryCount: 0,
      },
      {
        eventId: 'E2', edgeNodeId: 'EDGE-03', eventType: 'collision-risk', businessKey: 'k2',
        edgeOccurredAt: '2026-09-10T12:01:00+08:00', receivedAt: '2026-09-10T12:01:00+08:00',
        idempotencyKey: 'idem-2', clockOffsetAtOccurrence: 2800, ruleVersionUsed: 'v3.2', risk: '严重',
        payloadSummary: {},
        localLinkage: { alarm: true, screen: false, localVoice: true, localLight: true, plcStop: 'success', actions: [] },
        status: 'SYNCED', retryCount: 0, linkedAlertId: 'ALM-1',
      },
      {
        eventId: 'E3', edgeNodeId: 'EDGE-03', eventType: 'ppe-violation', businessKey: 'k3',
        edgeOccurredAt: '2026-09-10T12:02:00+08:00', receivedAt: '2026-09-10T12:02:00+08:00',
        idempotencyKey: 'idem-3', clockOffsetAtOccurrence: 2800, ruleVersionUsed: 'v3.2', risk: '预警',
        payloadSummary: {}, localLinkage: { alarm: false, screen: true, localVoice: true, localLight: false, plcStop: 'skipped', actions: [] },
        status: 'DUPLICATE', retryCount: 0,
      },
    ]
    const mapped = toLocalEvents(events)
    expect(mapped.map((e) => e.status)).toEqual(['待补传', '已补传', '重复'])
    expect(mapped[0]!.type).toBe('人员进入危险围栏')
    expect(mapped[1]!.type).toBe('设备距离风险')
    expect(mapped[0]!.localActions).toHaveLength(2)
    expect(mapped[0]!.dedupKey).toBe('idem-1')
  })

  it('maps failed replay back to pending-looking status for retry', () => {
    const mapped = toLocalEvents([{
      eventId: 'E', edgeNodeId: 'EDGE-03', eventType: 'person-intrusion', businessKey: 'k',
      edgeOccurredAt: '2026-09-10T12:00:00+08:00', receivedAt: '2026-09-10T12:00:00+08:00',
      idempotencyKey: 'i', clockOffsetAtOccurrence: 0, ruleVersionUsed: 'v3.2', risk: '严重',
      payloadSummary: {}, localLinkage: { alarm: true, screen: true, localVoice: true, localLight: true, plcStop: 'success', actions: [] },
      status: 'FAILED', retryCount: 1, lastError: 'PLATFORM_TEMPORARILY_UNAVAILABLE',
    }])
    expect(mapped[0]!.status).toBe('待补传')
  })

  it('maps recovery phases to step states', () => {
    const phases: BackendRecoveryPhase[] = [
      { key: 'CONNECTIVITY', label: '恢复连接', status: 'DONE', message: '云边链路已恢复' },
      { key: 'CLOCK_RECONCILIATION', label: '时间对账', status: 'BLOCKED', message: '偏差 2800ms' },
      { key: 'RULE_RECONCILIATION', label: '规则版本对账', status: 'WAIT' },
      { key: 'EVENT_REPLAY', label: '缓存事件补传', status: 'RUNNING' },
      { key: 'FINAL_CHECK', label: '最终检查', status: 'FAILED' },
    ]
    const steps = toRecoverySteps(phases)
    expect(steps.map((s) => s.state)).toEqual(['done', 'fail', 'wait', 'running', 'fail'])
    expect(steps[1]!.detail).toBe('偏差 2800ms')
    expect(toRecoverySteps(undefined)).toEqual([])
  })

  it('maps backend cloud link states', () => {
    expect(toCloudLink('disconnected')).toBe('disconnected')
    expect(toCloudLink('recovering')).toBe('recovering')
    expect(toCloudLink('link-error')).toBe('link-error')
    expect(toCloudLink('online')).toBe('online')
    expect(toCloudLink(undefined)).toBe('online')
  })
})
