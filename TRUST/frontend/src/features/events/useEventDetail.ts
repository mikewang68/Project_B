import { ref, computed, watch, type Ref } from "vue";
import type { ApiRecord } from "../../shared/types";
import { useFeedback } from "../../shared/composables/useFeedback";
import { usePolling } from "../../shared/composables/usePolling";
import { getEvent } from "./api";
import { verifyEvent } from "../verification/api";
export function useEventDetail(id: Ref<string>) {
  const selected = ref<ApiRecord | null>(null),
    check = ref<ApiRecord | null>(null);
  const detailEvent = computed(() =>
    selected.value ? JSON.parse(selected.value.canonical_json).event : null,
  );
  const { busy, run } = useFeedback();
  async function load() {
    const requested = id.value;
    const record = await getEvent(requested);
    if (id.value === requested) selected.value = record;
  }
  let pendingId = id.value;
  watch(
    id,
    () => {
      selected.value = null;
      check.value = null;
      pendingId = id.value;
    },
    { flush: "sync" },
  );
  watch(
    [id, busy],
    () => {
      if (busy.value || pendingId !== id.value) return;
      pendingId = "";
      void run(load);
    },
    { immediate: true, flush: "post" },
  );
  usePolling(load, () => !busy.value);
  async function verify() {
    await run(async () => {
      check.value = await verifyEvent(id.value);
    });
  }
  return { selected, check, detailEvent, busy, run, verify };
}
