import { json, post } from "../../shared/http/client";
export const listTasks = () => json("/tasks");
export const retryTask = (id: string) => post(`/events/${id}/retry`);
