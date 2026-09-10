import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { operationsApi } from './operations'

type FetchCall = [string | URL, RequestInit?]
function calls(): FetchCall[] {
  return (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls as FetchCall[]
}
function lastCall(): FetchCall {
  return calls().at(-1)!
}
function mockFetchOnce(payload: unknown): void {
  const response = {
    ok: true,
    status: 200,
    headers: new Headers(),
    json: vi.fn(async () => payload),
    text: vi.fn(async () => JSON.stringify(payload)),
  } as unknown as Response
  globalThis.fetch = vi.fn(async () => response) as unknown as typeof fetch
}

describe('operations api client', () => {
  beforeEach(() => {
    globalThis.crypto.randomUUID = vi.fn(() => 'idem-key-1') as unknown as typeof crypto.randomUUID
  })
  afterEach(() => vi.restoreAllMocks())

  it('uses exact /ops and /edge paths for reads', async () => {
    mockFetchOnce({ list: [] })
    await operationsApi.overview()
    expect(String(lastCall()[0])).toBe('/api/v1/ops/overview')
    await operationsApi.edgeNodes()
    expect(String(lastCall()[0])).toBe('/api/v1/ops/edge-nodes')
    await operationsApi.edgeNode('EDGE-03')
    expect(String(lastCall()[0])).toBe('/api/v1/ops/edge-nodes/EDGE-03')
    await operationsApi.devices()
    expect(String(lastCall()[0])).toBe('/api/v1/ops/devices')
    await operationsApi.interfaces()
    expect(String(lastCall()[0])).toBe('/api/v1/ops/interfaces')
    await operationsApi.link()
    expect(String(lastCall()[0])).toBe('/api/v1/edge/link')
    await operationsApi.ruleReconcileView()
    expect(String(lastCall()[0])).toBe('/api/v1/edge/reconcile/rules')
  })

  it('builds query strings for events and local events', async () => {
    mockFetchOnce({ list: [] })
    await operationsApi.events({ nodeId: 'EDGE-03', level: 'warning', limit: 20 })
    expect(String(lastCall()[0])).toBe('/api/v1/ops/events?nodeId=EDGE-03&level=warning&limit=20')
    await operationsApi.localEvents('EDGE-03', 'PENDING')
    expect(String(lastCall()[0])).toBe('/api/v1/edge/local-events?node=EDGE-03&status=PENDING')
  })

  it('posts disconnect / local event / recover with body', async () => {
    mockFetchOnce({ id: 'EDGE-03' })
    await operationsApi.simulateLink('disconnect', 'EDGE-03')
    let [url, init] = lastCall()
    expect(String(url)).toBe('/api/v1/edge/simulate-link')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(String(init?.body))).toEqual({ state: 'disconnect', nodeId: 'EDGE-03' })

    await operationsApi.createLocalEvent({ nodeId: 'EDGE-03', eventType: 'person-intrusion' })
    ;[url, init] = lastCall()
    expect(String(url)).toBe('/api/v1/edge/local-events')
    expect(JSON.parse(String(init?.body)).eventType).toBe('person-intrusion')

    await operationsApi.recover('EDGE-03')
    expect(String(lastCall()[0])).toBe('/api/v1/edge/recover')
  })

  it('posts reconcile actions with nodeId query and body', async () => {
    mockFetchOnce({ id: 'EDGE-03' })
    await operationsApi.reconcileRules('redeliver', 'EDGE-03')
    const [url, init] = lastCall()
    expect(String(url)).toBe('/api/v1/edge/reconcile/rules?nodeId=EDGE-03')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(String(init?.body))).toEqual({ action: 'redeliver' })

    await operationsApi.reconcileTime('EDGE-02')
    expect(String(lastCall()[0])).toBe('/api/v1/edge/reconcile/time?nodeId=EDGE-02')
  })

  it('posts ops simulate and maintain scenarios', async () => {
    mockFetchOnce({})
    await operationsApi.simulate('timeDrift', 'EDGE-02')
    let [url, init] = lastCall()
    expect(String(url)).toBe('/api/v1/ops/simulate')
    expect(JSON.parse(String(init?.body))).toEqual({ scenario: 'timeDrift', targetId: 'EDGE-02' })

    await operationsApi.maintain('EDGE-02', 'resyncTime')
    ;[url, init] = lastCall()
    expect(String(url)).toBe('/api/v1/ops/edge-nodes/EDGE-02/maintain')
    expect(JSON.parse(String(init?.body))).toEqual({ action: 'resyncTime' })
  })
})
