import { applicationPath } from "./basePath";
// Bound both response headers and body reads; a lost write response is not a failed write.
export async function request<T>(
  url: string,
  options: RequestInit,
  read: (response: Response) => Promise<T>,
  timeoutMs = 30_000,
): Promise<T> {
  const controller = new AbortController();
  let timedOut = false;
  const cancel = () => controller.abort();
  if (options.signal?.aborted) cancel();
  options.signal?.addEventListener("abort", cancel, { once: true });
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  const writing = !["GET", "HEAD"].includes(
    (options.method || "GET").toUpperCase(),
  );
  try {
    const response = await fetch(applicationPath(url), { ...options, signal: controller.signal });
    if ([401, 403].includes(response.status) && !["/api/v1/me", "/api/v1/identity-mode", "/api/v1/csrf"].includes(url))
      window.dispatchEvent(new Event("trust-access-changed"));
    return await read(response);
  } catch (error) {
    if (timedOut)
      throw new Error(
        writing
          ? "请求超时，处理结果尚不确定。请先查询事件或申请记录，再决定是否重试；事件重试须保留原事件号和内容。"
          : "查询超时，请检查网络后点击刷新重试。",
      );
    if (writing && error instanceof TypeError)
      throw new Error(
        "连接中断，处理结果尚不确定。请先查询事件或申请记录，不要直接重复提交。",
      );
    throw error;
  } finally {
    clearTimeout(timer);
    options.signal?.removeEventListener("abort", cancel);
  }
}
