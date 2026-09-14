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
