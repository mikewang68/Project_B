import { usePageLoad } from "../../shared/composables/usePageLoad";
import { ref } from "vue";
import type { ApiRecord } from "../../shared/types";
import { useFeedback } from "../../shared/composables/useFeedback";
import { listEvents } from "./api";
export function useEventLedger() {
  const rows = ref<ApiRecord[]>([]),
    total = ref(0),
    page = ref(0),
    query = ref("");
  const { busy, run, act } = useFeedback();
  async function loadEvents() {
    const result = await listEvents(query.value, page.value);
    rows.value = result.items;
    total.value = result.total;
  }
  usePageLoad(loadEvents, "events");
  return { rows, total, page, query, loadEvents, busy, run, act };
}
