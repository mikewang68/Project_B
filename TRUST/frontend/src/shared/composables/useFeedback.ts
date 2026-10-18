import { ref, provide, inject, type InjectionKey } from "vue";
import {
  performAction,
  reportAction,
  type PageAction,
  type PendingResult,
} from "../../features/audit/pageActions";
function createFeedback() {
  const busy = ref(false),
    error = ref(""),
    notice = ref("");
  const pendingResults = ref<PendingResult[]>([]);
  async function act(meta: PageAction, fn: () => void | Promise<void>) {
    await run(() =>
      performAction(meta, fn, (result) => pendingResults.value.push(result)),
    );
  }
  async function retryResults() {
    await run(async () => {
      for (const result of [...pendingResults.value]) {
        await reportAction(result);
        pendingResults.value = pendingResults.value.filter(
          (r) => r.requestId !== result.requestId,
        );
      }
    });
  }
  function clearResults() {
    pendingResults.value = [];
  }
  async function run(fn: () => Promise<void>) {
    if (busy.value) return;
    busy.value = true;
    error.value = "";
    notice.value = "";
    try {
      await fn();
    } catch (e) {
      error.value = (e as Error).message;
    } finally {
      busy.value = false;
    }
  }
  return {
    busy,
    error,
    notice,
    run,
    act,
    pendingResults,
    retryResults,
    clearResults,
  };
}
const key: InjectionKey<ReturnType<typeof createFeedback>> = Symbol("feedback");
export function provideFeedback() {
  const state = createFeedback();
  provide(key, state);
  return state;
}
export function useFeedback() {
  const state = inject(key);
  if (!state) throw new Error("Feedback provider is missing");
  return state;
}
