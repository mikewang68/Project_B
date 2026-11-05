import { ref, onMounted, onBeforeUnmount } from "vue";
import { currentUser, login, logout } from "./api";
import type { ApiRecord } from "../../shared/types";
import { post, resetCsrf } from "../../shared/http/client";
export function useSession() {
  const me = ref<ApiRecord | null>(null);
  let generation = 0, refreshing = false;
  let refreshTimer: ReturnType<typeof setInterval> | undefined;
  async function refreshAccess() {
    if (!me.value || refreshing) return;
    const user = me.value, version = generation;
    refreshing = true;
    try {
      const current = await currentUser();
      if (version === generation && user === me.value && JSON.stringify(current) !== JSON.stringify(user))
        me.value = current;
    } catch (error) {
      const status = (error as { status?: number }).status;
      if (version === generation && user === me.value && (status === 401 || status === 403))
        me.value = null;
    } finally { refreshing = false; }
  }
  onMounted(async () => {
    window.addEventListener("trust-access-changed", refreshAccess);
    window.addEventListener("focus", refreshAccess);
    refreshTimer = setInterval(() => {
      if (me.value?.contextContractVersion === 2) void refreshAccess();
    }, 15000);
    try {
      me.value = await currentUser();
    } catch {}
  });
  onBeforeUnmount(() => {
    window.removeEventListener("trust-access-changed", refreshAccess);
    window.removeEventListener("focus", refreshAccess);
    if (refreshTimer) clearInterval(refreshTimer);
  });
  async function signIn(username: string, password: string) {
    generation++;
    me.value = await login(username, password);
  }
  async function signOut() {
    await logout();
    generation++;
    me.value = null;
  }
  async function quickSignIn(username: string) {
    generation++;
    await resetCsrf();
    await post("/dev-login", { username });
    await resetCsrf();
    me.value = await currentUser();
  }
  async function iamSignIn(username: string, password: string, orgId: string) {
    generation++;
    await resetCsrf();
    await post("/iam/login", { username, password, orgId });
    await resetCsrf();
    me.value = await currentUser();
  }
  return { me, signIn, signOut, quickSignIn, iamSignIn };
}
