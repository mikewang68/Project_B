// UI actions and one-time page reads are serialized by useFeedback.
let currentId = "";
const pageReads = new Map<string, string>();
export function preparePageRead(page: string) {
  if (!currentId) throw new Error("页面进入留痕尚未确认");
  pageReads.set(page, currentId);
}
export function pageReadId(page: string) {
  return pageReads.get(page) || "";
}
export function clearPageReads() {
  pageReads.clear();
}
export function pageActionId() {
  return currentId;
}
export async function withPageAction(
  id: string,
  fn: () => void | Promise<void>,
) {
  const previous = currentId;
  currentId = id;
  try {
    await fn();
  } finally {
    currentId = previous;
  }
}
