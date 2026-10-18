import { post } from "../../shared/http/client";
import { withPageAction } from "../../shared/http/actionContext";

export type PageAction = {
  action:
    | "READ"
    | "CREATE"
    | "UPDATE"
    | "DELETE"
    | "LOGIN"
    | "LOGOUT"
    | "EXPORT"
    | "IMPORT"
    | "UPLOAD"
    | "DOWNLOAD"
    | "BACKUP"
    | "RESTORE"
    | "TRAIN"
    | "EXECUTE"
    | "NAVIGATE";
  menuId: string;
  objectId?: string;
  summary: string;
};
export type PendingResult = {
  requestId: string;
  outcome: "SUCCESS" | "FAILED";
  summary: string;
};

// Only fixed action descriptions and opaque resource IDs are accepted by callers.
// Never send form values, search terms, credentials, filenames or error bodies.
export async function recordAction(meta: PageAction): Promise<string> {
  const requestId = crypto.randomUUID();
  const receipt = await post("/audit/page-actions", {
    ...meta,
    objectId: meta.objectId || "",
    requestId,
    path: location.pathname,
  });
  if (receipt.requestId !== requestId || receipt.state !== "RECORDED")
    throw new Error("操作留痕回执无效，本次操作尚未执行。请恢复服务后重试。");
  return requestId;
}

export async function reportAction(result: PendingResult) {
  const receipt = await post(
    `/audit/page-actions/${encodeURIComponent(result.requestId)}/result`,
    {
      outcome: result.outcome,
      summary: result.summary,
    },
  );
  if (receipt.requestId !== result.requestId || receipt.state !== "RECORDED")
    throw new Error("操作结果留痕回执无效");
}

// This records a client-observed outcome, never a ledger or business success claim.
export async function performAction(
  meta: PageAction,
  fn: () => void | Promise<void>,
  pending: (result: PendingResult) => void,
) {
  let requestId: string;
  try {
    requestId = await recordAction(meta);
  } catch {
    throw new Error(
      "操作留痕暂不可用，本次操作尚未执行。请检查登录和网络后重试。",
    );
  }
  let outcome: PendingResult["outcome"] = "SUCCESS";
  try {
    await withPageAction(requestId, fn);
  } catch (error) {
    outcome = "FAILED";
    throw error;
  } finally {
    const result = {
      requestId,
      outcome,
      summary:
        outcome === "SUCCESS"
          ? "客户端处理完成；不代表业务或上链完成"
          : "客户端处理失败或结果不确定；请查询业务记录",
    };
    // A successful logout ends the authenticated session; its authoritative
    // outcome is recorded by the server, not by a now unauthenticated callback.
    if (meta.action !== "LOGOUT" || outcome !== "SUCCESS") {
      try {
        await reportAction(result);
      } catch {
        pending(result);
      }
    }
  }
}
