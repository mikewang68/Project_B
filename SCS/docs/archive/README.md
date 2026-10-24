# docs/archive — 历史文档归档

本目录存放**已闭环的开发阶段记录**，仅作回溯用途，不代表现状。当前权威文档：

- 改进路线图与全部发布记录 → `docs/improvement-roadmap-2026-10.md`
- 架构决策（ADR-001~008）→ `docs/architecture-decisions.md`
- 数据库最终权威 DDL → `backend/src/main/resources/db/design/openGauss-schema-final-draft.sql`（文档参考 `docs/database-design/`）
- 接入与协作边界 → 根目录《源码交接说明》与两份对外答复书

## 归档分类

| 子目录 | 内容 | 闭环时点 |
|---|---|---|
| `status-snapshots/` | Demo 架构 Phase B/B5/Fix 状态快照、落库前三轮硬化记录、本地环境勘察报告、前端视觉导航重构记录 | 2026-10 中旬，Phase B 系列全部验收后由路线图接管 |
| `integration-issues/` | 六个域（AI/告警/移动端/运维/规则/安全域）前后端集成问题清单 | 全部条目已在后续版本修复并经两轮全量复查验证 |
| `ai-handoff/` | AI 接手交接系列（01~11）：架构分析、迁移计划（Step 0~7）、阻塞清单、三节点实机验证 | Phase 1 持久化已全部完成（10 个 Jdbc 仓储 + 门禁 41 项），所有 open questions 已闭环 |
| `database-reviews/` | 数据库设计评审 findings、open questions、Phase B/B5 发现、final DDL patch/revision 过程稿 | 最终权威以 `openGauss-schema-final-draft.sql`（26 表）为准 |

归档时间：2026-10-24。若需考证某个决策的历史脉络，先看本 README 定位类别，再看对应文档；结论性现状一律以上方"当前权威文档"为准。
