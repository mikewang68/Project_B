import { onMounted, onUnmounted, onActivated, onDeactivated } from "vue";
// A cached page keeps its filters, but stops polling when another page or a detail is open.
export function usePolling(
  refresh: () => Promise<void>,
  enabled: () => boolean,
  interval = 5000,
) {
  let active = true;
  let timer: ReturnType<typeof setInterval>;
  onActivated(() => {
    active = true;
  });
  onDeactivated(() => {
    active = false;
  });
  onMounted(() => {
    timer = setInterval(async () => {
      if (!active || document.hidden || !enabled()) return;
      try {
        await refresh();
      } catch {}
    }, interval);
  });
  onUnmounted(() => clearInterval(timer));
}
