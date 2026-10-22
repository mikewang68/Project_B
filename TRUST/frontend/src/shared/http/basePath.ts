// Two explicit entry forms; arbitrary URL parameters and proxy headers cannot select an origin.
export function applicationPath(path: string): string {
  return location.pathname.startsWith("/trust/") ? "/trust" + path : path;
}
