import { post, download } from "../../shared/http/client";
export const verifyEvent = (id: string) => post(`/events/${id}/verify`);
export const exportEvent = (id: string, sourceEventId: string) =>
  download(`/events/${id}/export`, `存证-${sourceEventId}.zip`, "POST");
