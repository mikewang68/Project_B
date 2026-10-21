export const directPages = new Set([
  "events",
  "trace",
  "tasks",
  "status",
  "wallets",
  "identities",
]);

export function readPage(url = new URL(location.href)) {
  const values = url.searchParams.getAll("navTab");
  const valid =
    values.length === 0 || (values.length === 1 && directPages.has(values[0]));
  return {
    page: valid ? values[0] || "events" : "events",
    warning: valid ? "" : "页面入口参数无效，已返回事件台账。",
  };
}

export function writePage(page: string) {
  const url = new URL(location.href);
  // Entry/import is intentionally not a direct read-only entry.
  url.searchParams.set("navTab", directPages.has(page) ? page : "events");
  history.replaceState(null, "", url);
}
