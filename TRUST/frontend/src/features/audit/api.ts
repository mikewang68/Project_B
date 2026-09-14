import { json } from "../../shared/http/client";
export const listAudit = () => json("/audit");
