let token = "";
async function csrf() {
  const r = await fetch("/api/v1/csrf");
  const data = await r.json();
  token = data.token;
}
export async function api(path: string, options: RequestInit = {}) {
  const method = options.method || "GET";
  if (method !== "GET" && !token) await csrf();
  const headers = new Headers(options.headers);
  if (method !== "GET") headers.set("X-CSRF-TOKEN", token);
  const r = await fetch("/api/v1" + path, { ...options, headers });
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
  return r;
}
export async function json(path: string, options: RequestInit = {}) {
  return (await api(path, options)).json();
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
  token = "";
  await csrf();
}
export async function download(path: string, filename: string, method = "GET") {
  const r = await api(path, { method });
  const url = URL.createObjectURL(await r.blob());
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
