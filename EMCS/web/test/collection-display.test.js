import test from 'node:test'
import assert from 'node:assert/strict'
import { displayReading } from '../src/views/raw-quality/collectionDisplay.js'
test('collection preserves zero/false and distinguishes unknown from zero',()=>{
  assert.equal(displayReading('0'),'0');assert.equal(displayReading('false'),'false')
  assert.equal(displayReading('null'),'未知（未提供）');assert.equal(displayReading('bad-json'),'无效读数')
})
