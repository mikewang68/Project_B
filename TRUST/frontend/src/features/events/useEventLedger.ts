import { usePageLoad } from "../../shared/composables/usePageLoad";
import { ref } from "vue";
import type { ApiRecord } from "../../shared/types";
import { useFeedback } from "../../shared/composables/useFeedback";
import { usePolling } from "../../shared/composables/usePolling";
import { listEvents } from "./api";
export function useEventLedger() {
  const rows = ref<ApiRecord[]>([]),
    total = ref(0),
    page = ref(0),
    query = ref("");
  const { busy, run } = useFeedback();
  async function loadEvents() {
    const result = await listEvents(query.value, page.value);
    rows.value = result.items;
    total.value = result.total;
  }
  usePageLoad(loadEvents);
  usePolling(loadEvents, () => !busy.value);
  return { rows, total, page, query, loadEvents, busy, run };
}
