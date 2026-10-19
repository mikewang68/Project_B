import { api, json, resetCsrf, clearCsrf } from "../../shared/http/client";
export const currentUser = () => json("/me");
export async function login(username: string, password: string) {
  await resetCsrf();
  await api("/login", {
    method: "POST",
    body: new URLSearchParams({ username, password }),
  });
  await resetCsrf();
  return currentUser();
}
export async function logout() {
  await api("/logout", { method: "POST" });
  // The next login fetches its own CSRF token. A failed post-logout GET must not
  // hide that the server has already invalidated this session.
  clearCsrf();
}
