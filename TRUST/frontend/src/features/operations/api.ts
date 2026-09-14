import { json } from "../../shared/http/client";
export const getStatus = () => json("/status");
