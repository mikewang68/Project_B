export const nav = [
  ["events", "事件台账", "01"],
  ["create", "录入与导入", "02"],
  ["trace", "批次溯源", "03"],
  ["tasks", "补办任务", "04"],
  ["status", "运行状态", "05"],
];
export const descriptions: Record<string, string> = {
  events: "关联业务事件、原始证据与链上记录。",
  create: "业务记录接收后，归档和上链自动在后台完成。",
  trace: "沿批次和交接关系，查看来源与去向。",
  tasks: "查看处理进度、失败原因和补办结果。",
  status: "查看独立组件状态与最近操作。",
};
