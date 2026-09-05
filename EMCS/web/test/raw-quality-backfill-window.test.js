import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import { deriveBackfillWindow } from '../src/views/raw-quality/backfillWindow.js'

test('REQ-014: derives an exclusive cache end after the last missing slot', () => {
  const readings = [
    { ts: '2026-07-06 13:15:00', quality: 'miss' },
    { ts: '2026-07-06 13:30:00', quality: 'miss' }
  ]

  assert.deepEqual(deriveBackfillWindow(readings, '15min'), {
    cacheStart: '2026-07-06 13:15:00',
    cacheEnd: '2026-07-06 13:45:00'
  })
})

test('REQ-014: returns null when the current readings have no missing segment', () => {
  assert.equal(
    deriveBackfillWindow([{ ts: '2026-07-06 13:30:00', quality: 'ok' }], '15min'),
    null
  )
})

test('REQ-014: the dialog and POST body share the derived cache window', async () => {
  const viewSource = await readFile(
    new URL('../src/views/raw-quality/index.vue', import.meta.url),
    'utf8'
  )

  assert.match(viewSource, /const backfillWindow = computed\(\(\) => deriveBackfillWindow\(/)
  assert.match(viewSource, /pointId:\s*payload\.value\.selected\.pointId/)
  assert.match(viewSource, /cacheStart:\s*backfillWindow\.value\.cacheStart/)
  assert.match(viewSource, /cacheEnd:\s*backfillWindow\.value\.cacheEnd/)
  assert.match(viewSource, /formatOutageWindow\(backfillWindow\)/)
})
