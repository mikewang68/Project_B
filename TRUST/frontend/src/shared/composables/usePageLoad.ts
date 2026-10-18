import { ref, watch, onActivated, onDeactivated } from "vue";
import { useFeedback } from "./useFeedback";
import { pageReadId, withPageAction } from "../http/actionContext";

// Load a cached page when it is visible and the action that opened it has finished.
export function usePageLoad(load: () => Promise<void>, menuId?: string) {
  const active = ref(false),
    pending = ref(false);
  const { busy, run } = useFeedback();
  onActivated(() => {
    active.value = true;
    pending.value = true;
  });
  onDeactivated(() => {
    active.value = false;
  });
  watch([active, pending, busy], () => {
    if (!active.value || !pending.value || busy.value) return;
    pending.value = false;
    const id = menuId ? pageReadId(menuId) : "";
    void run(() => withPageAction(id, load));
  });
}
