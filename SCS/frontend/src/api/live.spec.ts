import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { isDomainLiveEvent, isLiveEvent, sharedLiveSocket } from './live'

class MockWebSocket {
  static instances: MockWebSocket[] = []
  static reset(): void { MockWebSocket.instances = [] }
  readyState = 0
  sent: string[] = []
  onopen: ((e: Event) => void) | null = null
  onmessage: ((e: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  constructor(public url: string) { MockWebSocket.instances.push(this) }
  send(data: string): void { this.sent.push(data) }
  close(): void { this.readyState = 3; this.onclose?.() }
  open(): void { this.readyState = 1; this.onopen?.(new Event('open')) }
  emit(obj: unknown): void { this.onmessage?.({ data: JSON.stringify(obj) }) }
}

describe('live event parsing', () => {
  it('recognises alert.* / ai.* / person.* / fence.* / collision.* / system.* live events only', () => {
    expect(isLiveEvent({ type: 'alert.changed', data: {} })).toBe(true)
    expect(isLiveEvent({ type: 'alert.escalated' })).toBe(true)
    expect(isLiveEvent({ type: 'ai.new' })).toBe(true)
    expect(isLiveEvent({ type: 'ai.reviewed' })).toBe(true)
    expect(isLiveEvent({ type: 'person.moved' })).toBe(true)
    expect(isLiveEvent({ type: 'fence.changed' })).toBe(true)
    expect(isLiveEvent({ type: 'collision.risk.changed' })).toBe(true)
    expect(isLiveEvent({ type: 'rule.published' })).toBe(true)
    expect(isLiveEvent({ type: 'rule.sync.changed' })).toBe(true)
    expect(isLiveEvent({ type: 'system.notice' })).toBe(true)
    expect(isLiveEvent({ type: 'ops.node.changed' })).toBe(true)
    expect(isLiveEvent({ type: 'ops.queue.changed', data: { nodeId: 'EDGE-03' } })).toBe(true)
    expect(isLiveEvent({ type: 'ops.recovery.changed' })).toBe(true)
    expect(isLiveEvent({ type: 'pong' })).toBe(false)
    expect(isLiveEvent({ schemaVersion: 1 })).toBe(false)
  })

  it('isDomainLiveEvent matches personnel/fence/collision/rule events', () => {
    expect(isDomainLiveEvent({ type: 'person.moved' })).toBe(true)
    expect(isDomainLiveEvent({ type: 'fence.changed' })).toBe(true)
    expect(isDomainLiveEvent({ type: 'collision.changed' })).toBe(true)
    expect(isDomainLiveEvent({ type: 'rule.changed' })).toBe(true)
    expect(isDomainLiveEvent({ type: 'alert.new' })).toBe(false)
    expect(isDomainLiveEvent({ type: 'ai.new' })).toBe(false)
  })
})

describe('shared LiveSocket', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    MockWebSocket.reset()
    ;(globalThis as { WebSocket: unknown }).WebSocket = MockWebSocket as unknown
  })
  afterEach(() => {
    sharedLiveSocket.stop()
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('moves connecting → connected and sends 20s ping heartbeat', () => {
    const states: string[] = []
    const off = sharedLiveSocket.onState((s) => states.push(s))
    sharedLiveSocket.connect()
    expect(states.at(-1)).toBe('connecting')
    const sock = MockWebSocket.instances.at(-1)!
    sock.open()
    expect(states.at(-1)).toBe('connected')
    vi.advanceTimersByTime(20_100)
    expect(sock.sent.some((m) => m.includes('ping'))).toBe(true)
    off()
  })

  it('reconnects after drop and fires onReconnected for REST resync', () => {
    const reconnects = vi.fn()
    const offR = sharedLiveSocket.onReconnected(reconnects)
    sharedLiveSocket.connect()
    MockWebSocket.instances.at(-1)!.open()
    const callsBeforeDrop = reconnects.mock.calls.length
    // 服务端断开
    MockWebSocket.instances.at(-1)!.close()
    const droppedCount = MockWebSocket.instances.length
    vi.advanceTimersByTime(2_000)
    expect(MockWebSocket.instances).toHaveLength(droppedCount + 1)
    // 重连成功：相比断线前多触发一次全量重同步回调
    MockWebSocket.instances.at(-1)!.open()
    expect(reconnects.mock.calls.length).toBe(callsBeforeDrop + 1)
    offR()
  })

  it('delivers parsed alert events to subscribers', () => {
    const received: Array<Record<string, unknown>> = []
    const off = sharedLiveSocket.onMessage((raw) => received.push(raw))
    sharedLiveSocket.connect()
    MockWebSocket.instances.at(-1)!.open()
    MockWebSocket.instances.at(-1)!.emit({ type: 'alert.closed', eventId: 'e1', data: { alertId: 'A1' } })
    expect(received).toHaveLength(1)
    expect(received[0]!.type).toBe('alert.closed')
    off()
  })
})
