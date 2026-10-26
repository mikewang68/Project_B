import { describe, expect, it } from 'vitest'
import { unwrapApiEnvelope } from './http'

describe('unwrapApiEnvelope', () => {
  it('returns data from the common envelope', async () => {
    expect(unwrapApiEnvelope({ code: 'OK', message: 'ok', requestId: 'r1', data: { count: 3 } })).toEqual({ count: 3 })
  })
})
