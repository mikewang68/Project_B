import { onMounted, onUnmounted } from 'vue'

/** Refresh the existing authorized read; never trigger a business command. */
export function useLiveRead(read: () => Promise<unknown>, ready: () => boolean = () => true, interval = 15_000) {
  let stopped = false, pending = false
  let timer: ReturnType<typeof setTimeout> | undefined
  const editing = () => document.activeElement?.matches('input,textarea,select,[contenteditable="true"]')
    || [...document.querySelectorAll('.el-dialog,.el-drawer,[role="dialog"]')]
      .some(node => (node as HTMLElement).getClientRects().length > 0)
  async function refresh() {
    if (stopped || pending || document.hidden || editing() || !ready()) return
    pending = true
    try { await read() } catch { /* The page's existing read error remains authoritative. */ }
    finally { pending = false }
  }
  async function tick() {
    await refresh()
    if (!stopped) timer = setTimeout(tick, interval)
  }
  const visible = () => { if (!document.hidden) void refresh() }
  onMounted(() => {
    timer = setTimeout(tick, interval)
    document.addEventListener('visibilitychange', visible)
    window.addEventListener('focus', visible)
  })
  onUnmounted(() => {
    stopped = true; clearTimeout(timer)
    document.removeEventListener('visibilitychange', visible)
    window.removeEventListener('focus', visible)
  })
}
