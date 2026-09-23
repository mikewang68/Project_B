import { ref, computed, onMounted } from "vue";
import { currentUser, login, logout } from "./api";
import type { ApiRecord } from "../../shared/types";
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
  return { me, canWrite, canAdmin, signIn, signOut };
}
