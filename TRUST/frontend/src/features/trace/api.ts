import { json } from "../../shared/http/client";
export const traceEvents = (kind: string, value: string) =>
  json(`/trace?kind=${kind}&value=${encodeURIComponent(value)}`);
