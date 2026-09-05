---
name: b-demo-conventions
description: B 项目演示 demo 的共享开发约定——技术栈、REQ 锚定、数据安全、许可证纪律、目录所有权。所有开发 agent 开工前必须遵守。
---

# B-Demo 开发约定（团队共享）

## 技术栈（已决策，不重议）

FastAPI + SQLAlchemy 2.0 + MySQL 8 单库 + APScheduler + Vue3/TS/Element Plus + ECharts + openpyxl + statsmodels。骨架 RuoYi-Vue3-FastAPI（MIT），MyEMS schema 只作建模蓝本。

## 需求来源

一切"做什么"以知识库为准（只读引用，绝不写回）：

- PRD：`/Users/jzy/Documents/Obsidian/AI-Knowledge-Vault/20_项目执行/B项目/能源管控系统/04_PRD与演示/PRD-能源管控系统.md`
- 演示主线：同目录 `demo-演示主线与范围切分.md`（五幕、必演 7 页）
- 数据构造规范：同目录 `demo-演示数据构造规范.md`（INJ-01–08、账号范围）
- 需求基线 REQ-001–105：`/Users/jzy/Documents/Obsidian/AI-Knowledge-Vault/20_项目执行/B项目/能源管控系统/02_同步视图/需求基线.md`

## 编码约定

1. 每个页面/接口/定时任务/业务规则，在实现处注释标注 REQ ID（格式 `# REQ-045` / `// REQ-045`）。找不到对应 REQ 就停下来问主控，不许自造需求。
2. 数据只用构造值：固定随机种子，2 区 / 12 设备 / 48 计量点 / 10 周历史。禁止真实计量数据、真实人名工号。
3. AGPL 开源项目只看设计不抄代码；MyEMS 不复制代码与品牌资源，只参考表结构。
4. 后端 API 返回结构、错误码遵循 RuoYi-Vue3-FastAPI 骨架既有约定；前端组件风格跟随 Element Plus 默认设计语言，不引入新 UI 库。
5. 目录所有权：`backend/`=backend-dev，`web/`=frontend-dev，`datagen/`=data-engineer；跨目录需求交给主控协调，不越界修改。
6. mock 与联调：契约事实来源为 PRD §5 各页面级字段表，API 形态落点见仓库 `docs/mock-contracts.md`（2026-07-13 主控裁决：原引用的"PRD 第 10 章 mock 契约"经核实不存在）；契约有歧义时先在任务列表登记，由主控裁决后再动代码。
7. 开工前必读 `docs/dev-pitfalls.md`（全队坑点底账 P-01–P-18）：终报数字必须查库实测、告警绝不 seed、规则编号一律 PRD §7.2、新页警惕 mock 残留复制、"当日"用 DEMO_NOW。踩到清单外的新坑，任务收尾时报主控补录。
8. 完工登记：修复类任务完成、测试跑绿后，必须在 `docs/fix-log.md` **追加**登记条目（编号 FX-NN 顺延；记 日期/执行者、提交号或"待提交"、修复项含 file:line、影响的功能操作、验证命令与结果、关联 REQ/P/KI，格式详见该文件头注）。该文件是 `docs/` 所有权的唯一追加例外——只追加不改写既有条目，主控提交前审核。
