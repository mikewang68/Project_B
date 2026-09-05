import '@testing-library/jest-dom/vitest';

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const browserGetComputedStyle = window.getComputedStyle.bind(window);

Object.defineProperty(window, 'ResizeObserver', {
  configurable: true,
  writable: true,
  value: ResizeObserverStub,
});

Object.defineProperty(window, 'matchMedia', {
  configurable: true,
  writable: true,
  value: (query: string): MediaQueryList =>
    ({
      matches: query.includes('min-width'),
      media: query,
      onchange: null,
      addListener() {},
      removeListener() {},
      addEventListener() {},
      removeEventListener() {},
      dispatchEvent: () => false,
    }) as MediaQueryList,
});

Object.defineProperty(window, 'getComputedStyle', {
  configurable: true,
  writable: true,
  value: (element: Element, pseudoElement?: string | null) =>
    pseudoElement === '::-webkit-scrollbar'
      ? browserGetComputedStyle(element)
      : browserGetComputedStyle(element, pseudoElement),
});
