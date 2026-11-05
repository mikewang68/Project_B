import { ref, watch, onActivated, onDeactivated, onUnmounted } from "vue";
import { useFeedback } from "./useFeedback";
import { pageReadId, withPageAction } from "../http/actionContext";

// Load a cached page when it is visible and the action that opened it has finished.
export function usePageLoad(load: () => Promise<void>, menuId?: string, options: { auto?: boolean } = {}) {
  const active = ref(false),
    pending = ref(false);
  const { busy, run } = useFeedback();
  let timer: ReturnType<typeof setInterval> | undefined;
  const stop = () => { clearInterval(timer); timer = undefined; };
  onActivated(() => {
    active.value = true;
    pending.value = true;
    stop();
    if (options.auto !== false) timer = setInterval(() => {
      if (!active.value || pending.value || busy.value || document.hidden) return;
      if (document.activeElement?.matches('input,textarea,select,[contenteditable="true"]')) return;
      if (Array.from(document.querySelectorAll('[role="dialog"],dialog[open]')).some(node => (node as HTMLElement).getClientRects().length)) return;
      pending.value = true;
    }, 30_000);
  });
  onDeactivated(() => {
    active.value = false;
    stop();
  });
  onUnmounted(stop);
  watch([active, pending, busy], () => {
    if (!active.value || !pending.value || busy.value) return;
    pending.value = false;
    const id = menuId ? pageReadId(menuId) : "";
    void run(() => withPageAction(id, load));
  });
}
