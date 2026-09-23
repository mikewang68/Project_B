import { ref, computed, onMounted } from "vue";
import { currentUser, login, logout } from "./api";
import type { ApiRecord } from "../../shared/types";
import { post, resetCsrf } from "../../shared/http/client";
export function useSession() {
  const me = ref<ApiRecord | null>(null);
  const canWrite = computed(() =>
    Boolean(
      me.value?.roles.some((r: string) =>
        ["ROLE_ADMIN", "ROLE_EDITOR"].includes(r),
      ),
    ),
  );
  const canAdmin = computed(() =>
    Boolean(me.value?.roles.includes("ROLE_ADMIN")),
  );
  onMounted(async () => {
    try {
      me.value = await currentUser();
    } catch {}
  });
  async function signIn(username: string, password: string) {
    me.value = await login(username, password);
  }
  async function signOut() {
    await logout();
    me.value = null;
  }
  async function quickSignIn(username: string) {
    await resetCsrf();
    await post("/dev-login", { username });
    await resetCsrf();
    me.value = await currentUser();
  }
  return { me, canWrite, canAdmin, signIn, signOut, quickSignIn };
}
