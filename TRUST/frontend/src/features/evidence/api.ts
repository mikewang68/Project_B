import { json, download } from "../../shared/http/client";
export function uploadEvidence(file: File) {
  const body = new FormData();
  body.append("file", file);
  return json("/evidence", { method: "POST", body });
}
export const downloadEvidence = (id: string, filename: string) =>
  download(`/evidence/${id}/download`, filename);
