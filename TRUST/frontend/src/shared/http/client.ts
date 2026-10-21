import { request } from "./request";
import { pageActionId } from "./actionContext";
let token = "";
async function csrf() {
  const data = await request("/api/v1/csrf", {}, async (r) => {
    if (!r.ok) throw new Error("无法获取会话校验信息，请刷新后重试");
    return r.json();
  });
  token = data.token;
}
async function consume<T>(
  path: string,
  options: RequestInit,
  read: (r: Response) => Promise<T>,
) {
  const method = options.method || "GET";
  if (method !== "GET" && !token) await csrf();
  const headers = new Headers(options.headers);
  const actionId = pageActionId();
  if (actionId && !path.startsWith("/audit/page-actions"))
    headers.set("X-TRUST-Page-Action-Id", actionId);
  if (method !== "GET") headers.set("X-CSRF-TOKEN", token);
  return request(
    "/api/v1" + path,
    { ...options, headers },
    async (r) => {
      if (!r.ok) {
        const data = await r.json().catch(() => ({}));
        throw new Error(
          data.message ||
            (
              {
                401: "请先登录",
                403: "当前账号没有操作权限",
                503: "服务暂不可用，请稍后重试",
              } as Record<number, string>
            )[r.status] ||
            `请求失败 (${r.status})`,
        );
      }
      return read(r);
    },
    path === "/evidence" || path === "/imports" || path.endsWith("/export")
      ? 120_000
      : 30_000,
  );
}
export async function api(path: string, options: RequestInit = {}) {
  return consume(path, options, async (r) => {
    const body = await r.arrayBuffer();
    return new Response([204, 205, 304].includes(r.status) ? null : body, {
      status: r.status,
      statusText: r.statusText,
      headers: r.headers,
    });
  });
}
export async function json(path: string, options: RequestInit = {}) {
  return consume(path, options, (r) => r.json());
}
export function post(path: string, body?: unknown) {
  return json(path, {
    method: "POST",
    ...(body === undefined
      ? {}
      : {
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        }),
  });
}

export async function resetCsrf() {
  clearCsrf();
  await csrf();
}
export function clearCsrf() {
  token = "";
}
export async function download(path: string, filename: string, method = "GET") {
  const blob = await consume(path, { method }, (r) => r.blob());
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
