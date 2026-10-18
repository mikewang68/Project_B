import { onMounted, onUnmounted, onActivated, onDeactivated } from "vue";
// A cached page keeps its filters, but stops polling when another page or a detail is open.
export function usePolling(
  refresh: () => Promise<void>,
  enabled: () => boolean,
  interval = 5000,
) {
  let active = true;
  let refreshing = false;
  let timer: ReturnType<typeof setInterval>;
  onActivated(() => {
    active = true;
  });
  onDeactivated(() => {
    active = false;
  });
  onMounted(() => {
    timer = setInterval(async () => {
      if (!active || refreshing || document.hidden || !enabled()) return;
      refreshing = true;
      try {
        await refresh();
      } catch {
        // Foreground refresh reports failures; background polling stays quiet.
      } finally {
        refreshing = false;
      }
    }, interval);
  });
  onUnmounted(() => clearInterval(timer));
}
