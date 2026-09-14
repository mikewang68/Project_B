import { ref, computed, watch, type Ref } from "vue";
import type { ApiRecord } from "../../shared/types";
import { useFeedback } from "../../shared/composables/useFeedback";
import { localTime, emptyForm, split } from "./eventForm";
import { submitEvent as saveEvent, importEvents } from "./api";
import { uploadEvidence } from "../evidence/api";
export function useEventEntry(
  previous: Ref<ApiRecord | null>,
  saved: (id: string) => void,
  cancel: () => void,
) {
  const form = ref(emptyForm()),
    uploaded = ref<ApiRecord[]>([]),
    imports = ref<ApiRecord | null>(null);
  const correction = computed(() => previous.value?.id || "");
  const { busy, notice, run } = useFeedback();
  watch(
    previous,
    (record) => {
      if (!record) return;
      const e = JSON.parse(record.canonical_json).event;
      form.value = {
        ...emptyForm(),
        ...e,
        sourceEventId:
          e.sourceEventId + "-CORR-" + Date.now().toString().slice(-6),
        occurredAt: localTime(new Date(e.occurredAt)),
        quantity: e.quantity == null ? "" : String(e.quantity),
        bundleText: e.bundleIds.join(","),
        relatedBatchText: e.relatedBatchIds.join(","),
        relatedEventText: e.relatedEventRefs.join(","),
        note: e.details?.note || "",
      };
      uploaded.value = [...record.evidence];
    },
    { immediate: true },
  );
  function reset() {
    form.value = emptyForm();
    uploaded.value = [];
    cancel();
  }
  async function uploadFiles(e: Event) {
    const target = e.target as HTMLInputElement;
    await run(async () => {
      for (const f of Array.from(target.files || [])) {
        uploaded.value.push(await uploadEvidence(f));
      }
      notice.value = "文件已可靠暂存，将随事件归档到 IPFS。";
    });
    target.value = "";
  }
  async function submitEvent() {
    await run(async () => {
      const f = form.value;
      const body = {
        sourceSystem: f.sourceSystem,
        sourceEventId: f.sourceEventId,
        eventType: f.eventType,
        businessObjectId: f.businessObjectId,
        batchId: f.batchId,
        occurredAt: new Date(f.occurredAt).toISOString(),
        quantity: f.quantity || null,
        unit: f.unit,
        location: f.location,
        supplier: f.supplier,
        receiver: f.receiver,
        handoverId: f.handoverId,
        bundleIds: split(f.bundleText),
        relatedBatchIds: split(f.relatedBatchText),
        relatedEventRefs: split(f.relatedEventText),
        evidenceIds: uploaded.value.map((e) => e.id),
        details: { note: f.note },
      };
      const result = await saveEvent(body, correction.value);
      reset();
      saved(result.id);
      notice.value = "事件已接收，文件保存和上链将在后台继续。";
    });
  }
  async function importFile(e: Event) {
    const target = e.target as HTMLInputElement;
    const file = target.files?.[0];
    if (!file) return;
    await run(async () => {
      imports.value = await importEvents(file);
      notice.value = `已接收 ${imports.value!.accepted} 条事件，请检查逐行结果。`;
    });
    target.value = "";
  }
  return {
    form,
    uploaded,
    imports,
    correction,
    busy,
    uploadFiles,
    submitEvent,
    importFile,
    reset,
  };
}
