import { ref, computed, watch, onMounted, onUnmounted, onActivated, onDeactivated, type Ref } from "vue";
import type { ApiRecord } from "../../shared/types";
import { useFeedback } from "../../shared/composables/useFeedback";
import { pageReadId, withPageAction } from "../../shared/http/actionContext";
import { getEvent } from "./api";
import { verifyEvent } from "../verification/api";
export function useEventDetail(id: Ref<string>) {
  const selected = ref<ApiRecord | null>(null),
    check = ref<ApiRecord | null>(null);
  const detailEvent = computed(() =>
    selected.value ? JSON.parse(selected.value.canonical_json).event : null,
  );
  const { busy, run, act } = useFeedback();
  async function load() {
    const requested = id.value;
    const record = await getEvent(requested);
    if (id.value === requested) {
      if (selected.value && (selected.value.version !== record.version
          || selected.value.canonical_json !== record.canonical_json
          || selected.value.chain_state !== record.chain_state
          || selected.value.file_state !== record.file_state)) check.value = null;
      selected.value = record;
    }
  }
  let visible = true;
  let timer: ReturnType<typeof setInterval> | undefined;
  onActivated(() => { visible = true; });
  onDeactivated(() => { visible = false; });
  onMounted(() => { timer = setInterval(() => {
    if (!visible || !id.value || busy.value || document.hidden) return;
    if (document.activeElement?.matches('input,textarea,select,[contenteditable="true"]')) return;
    void run(() => withPageAction(pageReadId(`event:${id.value}`), load));
  }, 30_000); });
  onUnmounted(() => { visible = false; clearInterval(timer); });
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
      const actionId = pageReadId(`event:${id.value}`);
      void run(() => withPageAction(actionId, load));
    },
    { immediate: true, flush: "post" },
  );
  async function refresh() {
    await act(
      {
        action: "READ",
        menuId: "events",
        objectId: id.value,
        summary: "刷新事件状态",
      },
      async () => {
        await load();
        check.value = null;
      },
    );
  }
  async function verify() {
    await act(
      {
        action: "READ",
        menuId: "events",
        objectId: id.value,
        summary: "核验证据",
      },
      async () => {
        check.value = await verifyEvent(id.value);
      },
    );
  }
  return { selected, check, detailEvent, busy, act, verify, refresh };
}
