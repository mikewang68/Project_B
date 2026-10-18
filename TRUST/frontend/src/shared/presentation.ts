const labels: Record<string, string> = {
  PENDING: "待处理",
  STORED: "已保存",
  CONFIRMING: "确认中",
  COMMITTED: "已上链",
  FAILED: "失败待补办",
  READY: "待补办",
  RUNNING: "处理中",
  DONE: "已完成",
  UP: "正常",
  DOWN: "不可用",
};
export const fmt = (v: any) =>
  v ? new Date(v).toLocaleString("zh-CN", { hour12: false }) : "—";
export const label = (v: string) => labels[v] || v;

export function recoveryHint(error: unknown): string {
  const text = typeof error === "string" ? error : "";
  if (text.includes("签名钱包已停用"))
    return "该事件使用的钱包已停用，重复补办不能恢复签名。请联系维护者核对历史签名身份；不要删除重建事件或直接替换其签名记录。";
  if (text.includes("EndorseException") || text.includes("failed to endorse transaction"))
    return "区块链背书未通过。此提示本身不能确定原因，请由维护者检查签名证书、来源及事件类型授权和背书节点。保留当前事件，确认原因解决后再补办。";
  if (text.includes("等待原版本完成上链"))
    return "这是更正记录，请先完成原版本上链，再补办本条更正。";
  return "";
}
