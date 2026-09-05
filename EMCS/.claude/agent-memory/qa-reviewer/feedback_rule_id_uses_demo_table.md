---
name: feedback-rule-id-uses-demo-table
description: 评审告警/规则相关代码时，判据是 PRD §7.2 demo 规则库 R01–R11，不是需求基线 K.7；两套编号语义不同、禁止混用。
metadata:
  type: feedback
---

评审代码、mock、告警数据里的规则编号，一律按 **PRD §7.2 demo 规则库 R01–R11** 判据，不是需求基线附录 K.7 的原编号。团队约定的全局裁决记录在 `docs/mock-contracts.md`「全局约定：规则编号（2026-07-13 主控裁决）」。

**Why：** 两套编号语义完全不同。举例：demo-R01=采集离线（对应 K.7-R02），demo-R02=数据迟到（demo 特有，K.7 无对应），demo-R03=连续零值（demo 特有），demo-R04=数据跳变（对应 K.7-R03），demo-R06=非作业气流量异常（对应 K.7-R06，但级别 K.7 建议"一般" → demo 已升"严重"），demo-R11=覆盖率不足（对应 K.7-R04）。同一个编号 R03 在 K.7 是"数据跳变"、在 demo 表是"连续零值"，套错必错。K.7 只作 REQ 层判定方向参考，demo 表是代码/mock 的事实来源。

**How to apply：** 评审告警/规则相关文件时（`mock.js` alarms / trend anomalies、`R\d+` 规则 ID 引用、datagen INJ 注入表、backend 告警规则表）：
1. 先读 `docs/mock-contracts.md` 顶部「全局约定」段确认 demo 编号事实来源；
2. 逐条对照 PRD §7.2 表判定编号语义、级别、映射；
3. 需与 K.7 对照时，走 §7.2 表的"对应 K.7"列（部分规则明确写"—"表示 demo 特有）；
4. 报告里避免直接引"K.7-Rxx" 作为"应挂"结论，说"demo-Rxx"或"PRD §7.2 Rxx"。

**触发案例：** 任务 #13 评审 3d27427 时，我按 K.7 判 mock.js 告警规则，A3 里 4 条错误方向成立但正确映射错了：mock 里 R02"采集离线 15 分钟"我判为"挂对"，按 demo 表实际是"采集离线应挂 demo-R01，demo-R02 是数据迟到"；R06 级别我说"K.7 是一般"，demo 表已升为"严重"。团队重裁后重新派发给 frontend-dev。此裁决时间（2026-07-13）晚于我开工，`docs/mock-contracts.md` 后来增补的「全局约定」段是最新事实。
