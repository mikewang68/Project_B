import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { useLiveRead } from './useLiveRead'

let wrapper: VueWrapper | undefined
beforeEach(() => { vi.useFakeTimers(); Object.defineProperty(document, 'hidden', { configurable: true, value: false }) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; vi.useRealTimers() })
function start(read: () => Promise<unknown>, ready?: () => boolean) {
  wrapper = mount(defineComponent({ setup() { useLiveRead(read, ready); return () => h('div') } }))
}
describe('authorized live reads', () => {
  it('reads periodically and releases the timer when leaving the page', async () => {
    const read = vi.fn().mockResolvedValue(undefined); start(read)
    await vi.advanceTimersByTimeAsync(15_000); expect(read).toHaveBeenCalledTimes(1)
    wrapper!.unmount(); wrapper = undefined
    await vi.advanceTimersByTimeAsync(60_000); expect(read).toHaveBeenCalledTimes(1)
  })
  it('pauses hidden, disallowed and editable pages, then resumes', async () => {
    let permitted = false
    const read = vi.fn().mockResolvedValue(undefined); start(read, () => permitted)
    await vi.advanceTimersByTimeAsync(15_000); expect(read).not.toHaveBeenCalled()
    permitted = true; Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    await vi.advanceTimersByTimeAsync(15_000); expect(read).not.toHaveBeenCalled()
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    const input = document.createElement('input'); document.body.appendChild(input); input.focus()
    await vi.advanceTimersByTimeAsync(15_000); expect(read).not.toHaveBeenCalled()
    input.blur(); document.dispatchEvent(new Event('visibilitychange')); await Promise.resolve()
    expect(read).toHaveBeenCalledTimes(1)
  })
  it('does not overlap slow reads, and retries after a failed read', async () => {
    let release!: () => void
    const read = vi.fn().mockImplementationOnce(() => new Promise<void>(resolve => { release = resolve }))
      .mockRejectedValueOnce(new Error('disconnected')).mockResolvedValue(undefined)
    start(read); window.dispatchEvent(new Event('focus')); window.dispatchEvent(new Event('focus'))
    expect(read).toHaveBeenCalledTimes(1)
    release(); await Promise.resolve(); await Promise.resolve()
    await vi.advanceTimersByTimeAsync(15_000); expect(read).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(15_000); expect(read).toHaveBeenCalledTimes(3)
  })
})
