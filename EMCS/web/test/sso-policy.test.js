import test from 'node:test'
import assert from 'node:assert/strict'
import { safeSsoDestination, validPortalOrigin, verifiedTicketMessage, readOnlyRequestAllowed } from '../src/utils/ssoPolicy.js'

test('SSO accepts only a configured exact origin, expected window and nonce', () => {
  const source = {}, expected = { source, origin: 'https://portal.example.test', nonce: 'nonce' }
  const event = { source, origin: expected.origin, data: { type: 'EMCS_SSO_TICKET', nonce: 'nonce', ticket: 'one-time' } }
  assert.equal(verifiedTicketMessage(event, expected), 'one-time')
  for (const bad of [{ ...event, source: {} }, { ...event, origin: 'https://evil.test' }, { ...event, data: { ...event.data, nonce: 'wrong' } }])
    assert.equal(verifiedTicketMessage(bad, expected), null)
  assert.equal(verifiedTicketMessage(event, { ...expected, origin: '' }), null)
  assert.equal(verifiedTicketMessage({ ...event, data: { ...event.data, ticket: 'x'.repeat(8193) } }, expected), null)
})
test('redirects cannot leave the supported energy routes', () => {
  assert.equal(safeSsoDestination('/energy/overview'), '/energy/overview')
  for (const value of ['//evil.test', 'https://evil.test', '/\\evil.test', '/%2Fevil', '/system/user', '/index#ticket=secret', undefined])
    assert.equal(safeSsoDestination(value), '/index')
})
test('origin configuration rejects paths, credentials and non-HTTP protocols', () => {
  assert.equal(validPortalOrigin('https://portal.example.test'), 'https://portal.example.test')
  for (const value of ['*', 'null', 'https://portal.example.test/path', 'https://user:pass@portal.example.test', 'file:///tmp'])
    assert.equal(validPortalOrigin(value), '')
})
test('readonly requests reject mutations and GET exports but allow preview and logout', () => {
  assert.equal(readOnlyRequestAllowed('GET', '/overview/summary'), true)
  assert.equal(readOnlyRequestAllowed('POST', '/reports/preview'), true)
  assert.equal(readOnlyRequestAllowed('POST', '/logout'), true)
  for (const [method, url] of [['POST', '/reports/archives'], ['GET', '/reports/archives/1/export'], ['DELETE', '/monitor/cache/clearCacheAll'], ['POST', '/raw-quality/recompute']])
    assert.equal(readOnlyRequestAllowed(method, url), false)
})
