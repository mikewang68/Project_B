// UI actions are serialized by useFeedback. Polling is suspended while busy.
let currentId = "";
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
