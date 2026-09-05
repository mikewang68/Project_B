---
name: reference-contract-sources
description: 评审 B-demo 时的契约事实来源与优先级：PRD §5 页面字段 / §7 运行参数 · docs/mock-contracts.md 全局约定 · 需求基线 REQ 条目。
metadata:
  type: reference
---

评审 B-demo 时的契约与规则事实来源，按优先级降序：

1. **PRD §5 各页面级字段表**（页面契约事实来源）——路径 `/Users/jzy/Documents/Obsidian/AI-Knowledge-Vault/20_项目执行/B项目/能源管控系统/04_PRD与演示/PRD-能源管控系统.md`。5.1 总览在第 140 行起，5.3 原始数据与质量在 202 行起，其余按目录索引。
2. **PRD §7 运行参数与初始规则**（同文件，370 行起）——§7.2 是 demo 告警规则库 R01-R11 事实来源，字段：编号 / 规则名 / 判定 / 级别 / 对应 K.7 / 承接场景。
3. **`docs/mock-contracts.md`**（前后端契约内部落点）——顶部裁决背景说明 PRD 第 10 章不存在的旧口径已废；含「全局约定」段（如规则编号裁决）。契约字段以 `overview.js` 头注释 + `mock.js` 构造函数为可执行契约。
4. **需求基线**（REQ-001–105 与附录 K.6/K.7）——路径 `/Users/jzy/Documents/Obsidian/AI-Knowledge-Vault/20_项目执行/B项目/能源管控系统/02_同步视图/需求基线.md`。K.7 是 REQ 层"判定方向参考"，不是 demo 代码事实来源，评审规则时不能直接用 K.7 编号做结论（见 [[feedback-rule-id-uses-demo-table]]）。

**冲突时的裁决顺序：** 知识库 PRD 优先于 `docs/mock-contracts.md`；`docs/mock-contracts.md` 优先于代码里的 mock/契约头注释；主控在 `docs/mock-contracts.md` 里显式写"裁决"的段落是最新事实（有时间戳），可能晚于评审 agent 的开工时间。
