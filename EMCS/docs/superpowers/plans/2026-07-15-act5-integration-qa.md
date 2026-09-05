# 第五幕联调、复位与幕收口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. The steps below are ordered implementation instructions.

**Goal:** 在隔离库和共享演示库验证第五幕从真实默认发现到归档导出的完整剧情、权限、签名、复位和前四幕回归，并形成 `act5-done` 收口证据。

**Architecture:** 先用 disposable DB 验证 schema/reset/bootstrap/重算全链，再联调真实 API 和 Vue 两页；Excel 用 openpyxl 反读验证，权限按五账号走查。共享 `b_demo` 只在隔离验证全绿后升级，失败按影子表反向 rename 回滚。

**Tech Stack:** MySQL 8、FastAPI、Vue 3、openpyxl、unittest、Node `node:test`、Vite、curl、浏览器走查。

## Global Constraints

- 告警绝不 seed；成本、差异、归档绝不 seed。
- 所有定量终报先查库；禁止“预计/约/应该”。
- shared `b_demo` 迁移前备份旧成本表并保留到幕收口。
- reset 与写接口必须可重复；破坏性测试只在 `codex_*_test`。
- 修复批测试全绿后追加 `docs/fix-log.md`，只追加不改旧条目。
- 新两页的 REQ 锚定结果追加到 `docs/ui-req-anchor-audit.md`。

---

### Task 1: 隔离库全栈前置闸门

建立 `codex_act5_e2e_test`，运行 datagen reset，确认 cost/diff/archive=0。
启动 backend 指向隔离库，执行 bootstrap，确认每业务键 v1 current 唯一。
查询锚点：05 电 138014.50、06 电 161920.03、07 电 59501.99、07 气 4030.33、07 水 1313.12、R10=1、告警=20。
新增生效单价并重算 2026-06，确认完整 v2、逐对象 diff、v1 金额/签名未改、三消费者只读 v2 current。
finance 复核后生成月报 preview/export/archive；再次重算后旧 archive 不变。
reset/bootstrap，确认只剩 v1 current、diff/archive=0、签名回到 fresh fixture 值。

### Task 2: API 与权限矩阵联调

finance_user 走成本读、分摊维护、重算发起/复核、报表和可信成本来源转建议。
energy_mgr 走成本读、单价维护、重算发起；分摊维护和复核返回 403。
admin 业务写全部 403；审计只读正常。
ops_user 访问成本页/API 返回 403，并现场新增一条 `e_audit_security`；dispatch_user 同样无成本数据。
finance 不带 valid costAnomaly sourceContext 调通用 POST /suggestions 返回 403；合法上下文成功。
422/409/404 均不增加 cost/diff/archive/suggestion 行数。

### Task 3: 页面、Excel 与深链走查

finance 首次进入默认 2026-07/ALL/electricity，07 标进行中，05 标部分月。
点击 6 月异常进入 2026-06/B/electricity；页面展示 R10 snapshot 的 baseline/report/diff/threshold。
告警详情第二入口进入同一筛选；浏览器刷新保持 route query。
三步 trace 展示冻结用量、单价和分摊；old/new version 反查一致。
月报 preview 五段与页面统计一致；Excel 每业务表顶部 5 行、A7 freeze、独立口径页、完整签名。
同参数重导出签名一致；日报没有成本版本；archive 重下载仍为旧 costVersion。
成本异常转建议后跳第四幕详情，并可回成本证据。

### Task 4: 全回归、共享库迁移与收口

同时导出 `ACT5_TEST_DB` 与 `ACT4_TEST_DB` 并指向同一个 fresh 隔离库，再运行 backend 全 unittest + ruff、datagen 全 unittest、web 全 node tests + build；任何 skip 都视为回归闸门失败，不能以“未配置旧幕变量”跳过第四幕集成。
在 disposable DB 演练 V002 正向/反向 rename 后再升级共享 `b_demo`。
共享库执行 bootstrap 与同一锚点查询；完成五账号浏览器彩排。
追加 `docs/ui-req-anchor-audit.md` 第五幕两页锚定；实现/联调产生的修复按批追加 `docs/fix-log.md`。
`rg` 扫描 mock 残留、硬编码锚点、wall-clock、成本结果 seed、非 current 成本 SUM。
主控审核 git diff，创建幕收口提交并打 `act5-done` 标签。

## Final Verification Commands

```bash
export ACT5_TEST_DB=codex_act5_e2e_test
export ACT4_TEST_DB="$ACT5_TEST_DB"
cd backend
.venv/bin/python -m unittest discover -s tests -p 'test_*.py' -v
.venv/bin/python -m ruff check module_energy tests
cd ..
PYTHONPATH=backend backend/.venv/bin/python -m unittest discover -s datagen/tests -p 'test_*.py' -v
cd web
node --test test/*.test.js
npm run build:prod
```

Expected: backend 与 datagen 均为 `0 failure, 0 skipped`，不得出现 `skipped`/`SkipTest`；ruff exit 0，Vite build exit 0。两个环境变量必须在同一 shell 中保持同值，否则 generator 应立即失败。
