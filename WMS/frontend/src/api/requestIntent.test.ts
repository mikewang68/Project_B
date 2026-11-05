import { describe, expect, it } from 'vitest'
import { createRequestIntentStore } from './requestIntent'

describe('inventory write intentions', () => {
  it('reuses the request key after a lost response, including dialog reopen', () => {
    let id = 0; const store = createRequestIntentStore(() => `request-${++id}`)
    const request = { orderId: 1, lineId: 2, quantity: 60, locationCode: 'A' }
    expect(store.forPayload(request)).toBe(store.forPayload({ ...request }))
    expect(store.forPayload({ ...request, quantity: 40 })).not.toBe(store.forPayload(request))
    expect(store.forPayload(request)).toBe('request-1')
  })
  it('only creates another key for the same quantity after confirmed success', () => {
    let id = 0; const store = createRequestIntentStore(() => `request-${++id}`)
    const request = { scope:'company/warehouse/owner', orderId:1, quantity:10 }
    const first = store.forPayload(request)
    store.completed(request)
    expect(store.forPayload(request)).not.toBe(first)
    expect(store.forPayload({ ...request, scope:'company/other/owner' })).not.toBe(store.forPayload(request))
  })
})
