import { appBaseUrl, withAppBasePath } from './appBasePath';

export async function enableMocking(): Promise<void> {
  if (typeof window === 'undefined') return;
  const { worker } = await import('../mocks/browser');
  await worker.start({
    serviceWorker: {
      url: withAppBasePath('/mockServiceWorker.js'),
      options: { scope: appBaseUrl },
    },
    onUnhandledRequest(request, print) {
      const pathname = new URL(request.url).pathname;
      if (pathname.startsWith(withAppBasePath('/mock/'))) print.error();
    },
  });
}
