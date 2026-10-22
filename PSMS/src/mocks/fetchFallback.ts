import { getResponse, type HttpHandler } from 'msw';
import { withAppBasePath } from '../runtime/appBasePath';

/** Keep simulated business APIs available when HTTPS prevents Service Worker registration. */
export function installFetchMocks(handlers: HttpHandler[]): void {
  const original = globalThis.fetch.bind(globalThis);
  globalThis.fetch = async (input, init) => {
    const url = new URL(input instanceof Request ? input.url : String(input), window.location.href);
    if (url.origin !== window.location.origin || !url.pathname.startsWith(withAppBasePath('/mock/'))) return original(input, init);
    const request = input instanceof Request ? new Request(input, init) : new Request(url, init);
    request.signal.throwIfAborted();
    const response = await getResponse(handlers, request);
    request.signal.throwIfAborted();
    if (!response) throw new Error(`未找到模拟接口：${url.pathname}`);
    return response;
  };
}
