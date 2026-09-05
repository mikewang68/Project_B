import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import { writeFailurePolicy } from '../src/views/energy/shared/act4.js'
import { createBusinessError, getErrorStatus } from '../src/utils/requestError.js'

test('request errors preserve business code and HTTP status without copying response payloads', () => {
  for (const code of [403, 404, 409]) {
    const error = createBusinessError(`business-${code}`, code, 200)
    assert.equal(error instanceof Error, true)
    assert.equal(error.message, `business-${code}`)
    assert.equal(error.code, code)
    assert.equal(error.status, 200)
    assert.equal(error.response, undefined)
    assert.equal(getErrorStatus(error), code)
  }
  assert.equal(getErrorStatus(createBusinessError('HTTP conflict', undefined, 409)), 409)
})

test('business envelopes 403/404/409 reach the Act 4 permission, close and refresh policies', () => {
  const expected = {
    403: 'permissionBlocked',
    404: 'closeDetail',
    409: 'refresh'
  }
  for (const [code, branch] of Object.entries(expected)) {
    const error = createBusinessError(`业务错误 ${code}`, Number(code), 200)
    assert.equal(writeFailurePolicy(getErrorStatus(error))[branch], true)
  }
})

test('global response interceptor rejects structured errors instead of bare strings', async () => {
  const source = await readFile(new URL('../src/utils/request.js', import.meta.url), 'utf8')
  assert.match(source, /createBusinessError/)
  assert.match(source, /code !== 200[\s\S]*Promise\.reject\(createBusinessError\(msg, code, res\.status\)\)/)
  assert.match(source, /responseMsg[\s\S]*Promise\.reject\(createBusinessError\(responseMsg, responseCode, responseStatus\)\)/)
  assert.doesNotMatch(source, /Promise\.reject\(['"](?:error|无效的会话)/)
})
