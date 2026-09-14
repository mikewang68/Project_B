import { ref, provide, inject, type InjectionKey } from "vue";
function createFeedback() {
  const busy = ref(false),
    error = ref(""),
    notice = ref("");
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
  return { busy, error, notice, run };
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
