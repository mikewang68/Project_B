import { json, post } from "../../shared/http/client";
export const listEvents = (query: string, page: number) =>
  json(`/events?q=${encodeURIComponent(query)}&page=${page}&size=20`);
export const getEvent = (id: string) => json(`/events/${id}`);
export const submitEvent = (body: unknown, previous?: string) =>
  post(previous ? `/events/${previous}/corrections` : "/events", body);
export function importEvents(file: File) {
  const data = new FormData();
  data.append("file", file);
  return json("/imports", { method: "POST", body: data });
}
