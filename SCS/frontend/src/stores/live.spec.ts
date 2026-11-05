import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
vi.mock('@/api/live', () => ({ isLiveEvent: () => false, sharedLiveSocket: { connect: vi.fn(), onState: vi.fn(), onMessage: vi.fn(), onReconnected: vi.fn() } }))
import { useLiveStore } from './live'
let unsubscribe: (() => void) | undefined
beforeEach(() => { vi.useFakeTimers(); setActivePinia(createPinia()); Object.defineProperty(document, 'hidden', { configurable: true, value: false }) })
afterEach(() => { unsubscribe?.(); unsubscribe=undefined; vi.useRealTimers() })
it('reconciles database reads even when no WebSocket change was emitted', async () => {
  const read=vi.fn();unsubscribe=useLiveStore().onReconnected(read)
  await vi.advanceTimersByTimeAsync(30_000);expect(read).toHaveBeenCalledTimes(1)
  Object.defineProperty(document, 'hidden', { configurable: true, value: true })
  await vi.advanceTimersByTimeAsync(30_000);expect(read).toHaveBeenCalledTimes(1)
  Object.defineProperty(document, 'hidden', { configurable: true, value: false })
  await vi.advanceTimersByTimeAsync(30_000);expect(read).toHaveBeenCalledTimes(2)
  unsubscribe();unsubscribe=undefined
  await vi.advanceTimersByTimeAsync(60_000);expect(read).toHaveBeenCalledTimes(2)
})
it('waits for an outstanding read and resumes after a rejected read', async () => {
  let reject!: (reason: Error) => void
  const read=vi.fn().mockImplementationOnce(() => new Promise((_, fail) => { reject=fail })).mockResolvedValue(undefined)
  unsubscribe=useLiveStore().onReconnected(read)
  await vi.advanceTimersByTimeAsync(60_000);expect(read).toHaveBeenCalledTimes(1)
  reject(new Error('disconnected'))
  await vi.advanceTimersByTimeAsync(30_000);expect(read).toHaveBeenCalledTimes(2)
})
