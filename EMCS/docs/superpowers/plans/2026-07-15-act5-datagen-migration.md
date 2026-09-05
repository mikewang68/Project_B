# 第五幕 Datagen DDL 与迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. The steps below are ordered implementation instructions.

**Goal:** 为不可变 costVersion、重算差异和报表归档提供 fresh/reset 与共享 `b_demo` 两条确定性 DDL 路径，不构造或 seed 任何成本结果。

**Architecture:** fresh fixture 直接由 V001 创建第五幕最终表结构；已有共享库使用 V002 创建影子成本表与两张新表，再由主控原子 rename、backend 真实 pipeline 重建 v1。generator 只负责结构、清空顺序、菜单开放和隔离库入口，成本版本/差异/归档始终由 backend 运行期生成。

**Tech Stack:** MySQL 8、PyMySQL、Python unittest、固定随机种子 `20260713`。

## Global Constraints

- 事实来源：`docs/mock-contracts.md` §6、后端计划的 Data-engineer Schema Gate、PRD §5.9。
- 禁止在 SQL 或 Python 中 INSERT/UPSERT `e_cost_record`、`e_cost_recompute_record`、`e_report_archive`。
- 不新增/修改 2 区、12 设备、48 点、10 周曲线、INJ-01～08 或历史建议 fixture。
- reset 后三张成本结果表为空；bootstrap 才生成 v1 current。
- V001 只服务 fresh reset；V002 只服务已有共享库升级，generator 不自动执行 V002。
- MySQL 导入与查询使用 utf8mb4；破坏性迁移只在 disposable `codex_*_test` 先演练。
- data-engineer 只改 `datagen/`；执行 agent 不提交，由主控创建检查点提交。

---

### Task 1: V001 最终成本版本结构

**Owner:** data-engineer

**Files:**
- Modify: `datagen/ddl/V001__energy_domain.sql`
- Create: `datagen/tests/test_act5_schema.py`

**Interfaces:**
- Produces: backend 计划 Data-engineer Schema Gate 的三张最终表。

**Step 1: 写失败 schema 测试**

测试解析 V001 并断言：

```python
required_cost_fields = (
    "cost_version", "is_current", "normalized_object_id", "current_guard",
    "formula_version", "tariff_snapshot_json", "alloc_rule_snapshot_json",
    "source_stat_snapshot_json", "computed_by",
)
```

同时断言 `uk_cost_version`、`uk_cost_current`、`e_cost_recompute_record`、`e_report_archive` 及其契约字段存在；旧 `uk_object_month_type` 不存在。

**Step 2: 运行并确认红灯**

```bash
PYTHONPATH=backend backend/.venv/bin/python -m unittest datagen.tests.test_act5_schema -v
```

Expected: FAIL，新列/新表尚不存在。

**Step 3: 修改 V001**

`normalized_object_id` 使用 `IFNULL(object_id,0)` 解决 system NULL 唯一键；`current_guard` 仅 current 行生成非 NULL 业务键，从数据库层保证每个对象×月×介质只有一条 current。快照和签名列全部 NOT NULL。

`e_cost_recompute_record` 保存 header + `diff_summary_json longtext`；`e_report_archive` 保存 `filters_snapshot_json/payload_snapshot_json/version_snapshots_json/full_signature`。两表不外键绑定会被 reset 重建的告警/建议 ID。

成本与报表完整签名列使用 `varchar(96)`，容纳 `COST-SHA256-V1:<64hex>` / `REPORT-SHA256-V1:<64hex>`；不得收窄为 `char(64)` 截断版本前缀。

**Step 4: 验证**

```bash
PYTHONPATH=backend backend/.venv/bin/python -m unittest datagen.tests.test_act5_schema -v
```

Expected: all PASS。

**Step 5: 主控检查点提交**

```bash
git add datagen/ddl/V001__energy_domain.sql datagen/tests/test_act5_schema.py
git commit -m "feat(act5): add versioned cost schema"
```

---

### Task 2: V002 影子表共享库迁移

**Owner:** data-engineer

**Files:**
- Create: `datagen/ddl/V002__act5_cost_versioning.sql`
- Create: `datagen/tests/fixtures/pre_act5_cost_record.sql`
- Create: `datagen/tests/test_act5_migration.py`

**Interfaces:**
- Produces: `e_cost_record_act5` 影子表、新差异表、新归档表；不自动 rename。

**Step 1: 写失败 migration 测试**

在 disposable DB 执行 `datagen/tests/fixtures/pre_act5_cost_record.sql` 创建 commit `6071dfe` 时的旧成本表形状并插入一条最小旧成本行，再执行 V002。fixture 必须完整写出旧表 DDL，不能从已修改的新 V001 动态截取。断言旧 `e_cost_record` 行数/金额不变，影子表为空且结构与新 V001 的 `e_cost_record` 等价，新表存在。

**Step 2: 实现幂等 V002**

V002 只允许：

```sql
CREATE TABLE e_cost_record_act5 (...第五幕最终结构...);
CREATE TABLE e_cost_recompute_record (...);
CREATE TABLE e_report_archive (...);
```

脚本不得 DROP/ALTER/UPDATE/INSERT/DELETE 旧 `e_cost_record`，也不得执行 rename。三个目标表已存在时必须直接失败，禁止 `IF NOT EXISTS` 静默复用脏影子表。rename 是停服后的主控动作，避免脚本执行一半让旧 backend 读到新空表。

**Step 3: 增加负向源码断言**

测试拒绝 V002 中出现 `DROP / ALTER / RENAME / INSERT / UPDATE / DELETE / IF NOT EXISTS` 或伪造 snapshot JSON。

**Step 4: 验证**

```bash
PYTHONPATH=backend backend/.venv/bin/python -m unittest datagen.tests.test_act5_migration -v
```

Expected: all PASS；旧表未变，影子表 0 行。

**Step 5: 主控检查点提交**

```bash
git add datagen/ddl/V002__act5_cost_versioning.sql datagen/tests/fixtures/pre_act5_cost_record.sql datagen/tests/test_act5_migration.py
git commit -m "feat(act5): add shadow cost migration"
```

---

### Task 3: reset/truncate、菜单开放与 ACT5 隔离库

**Owner:** data-engineer

**Files:**
- Modify: `datagen/generate_demo_data.py`
- Modify: `datagen/tests/test_act5_schema.py`
- Create: `datagen/tests/test_act5_reset.py`

**Interfaces:**
- Produces: backend Task 9 可重复使用的 `ACT5_TEST_DB` fresh fixture。

**Step 1: 写 reset 失败测试**

断言 truncate 顺序中 `e_report_archive < e_cost_recompute_record < e_cost_record`，三表在 generator 中无 INSERT；菜单父目录 2012 与子项 2013/2014 三项同步由隐藏改为可见，finance/energy_mgr 保持 K.6 菜单范围，ops/dispatch 不获成本菜单。

**Step 2: 泛化 disposable DB 环境变量**

```python
ACT5_TEST_DB = os.environ.get("ACT5_TEST_DB")
ACT4_TEST_DB = os.environ.get("ACT4_TEST_DB")
if ACT5_TEST_DB and ACT4_TEST_DB and ACT5_TEST_DB != ACT4_TEST_DB:
    raise ValueError("ACT5_TEST_DB and ACT4_TEST_DB must match when both are set")
TEST_DB = ACT5_TEST_DB or ACT4_TEST_DB
if TEST_DB and not re.fullmatch(r"codex_[a-z0-9_]*_test", TEST_DB):
    raise ValueError("test database must match codex_*_test")
```

保持 ACT4 兼容，不允许任意库名绕过安全检查。

**Step 3: 调整 reset/truncate 和菜单**

V001 reset 后三张成本结果表为空。幂等模式先清 archive/diff/cost，再清 tariff/alloc/stat；不要调用 backend 计算。开放“成本与报表”整枝时必须联动修改 2012/2013/2014 的 visible 与 remark，不能只开放两个子项；role_menu 保持不动，不扩大 ops/dispatch 权限，INJ-08 拦截语义不变。

**Step 4: 验证 fresh reset**

```bash
ACT5_TEST_DB=codex_act5_datagen_test PYTHONPATH=backend backend/.venv/bin/python datagen/generate_demo_data.py --reset
PYTHONPATH=backend backend/.venv/bin/python -m unittest datagen.tests.test_act5_reset -v
```

Expected: reset PASS；cost/recompute/archive=0；tariff=5；分摊规则为既有确定性配置；告警仍为 0；2012/2013/2014 均可见且既有 role_menu 集合不变。

**Step 5: 主控检查点提交**

```bash
git add datagen/generate_demo_data.py datagen/tests/test_act5_schema.py datagen/tests/test_act5_reset.py
git commit -m "feat(act5): prepare deterministic cost reset"
```

---

### Task 4: 共享库迁移演练与回滚证据

**Owner:** data-engineer + 主控

**Files:**
- Modify: `datagen/tests/test_act5_migration.py`

**Step 1: 在 disposable DB 重放旧结构和旧 v1**

记录旧表行数及 05/06/07 系统电费 138014.50/161920.03/59501.99。

**Step 2: 执行 V002 并原子切换**

```sql
RENAME TABLE
  e_cost_record TO e_cost_record_pre_act5,
  e_cost_record_act5 TO e_cost_record;
```

切换后不运行 R10；由 backend `initialize_v1` 写满 current 快照后才允许规则引擎运行。

**Step 3: 验证 Schema Gate 成功路径**

原子切换后断言新的 `e_cost_record` 是结构正确的空表，`e_cost_record_pre_act5` 仍完整保存旧行、05/06/07 金额和旧签名；差异表与归档表仍为空。data-engineer 不插入 v1、不调用规则引擎，也不伪造 current/快照来满足后端验收。

**Step 4: 验证失败回滚**

模拟 bootstrap 闸门失败，停止 backend 后执行：

```sql
RENAME TABLE
  e_cost_record TO e_cost_record_act5_failed,
  e_cost_record_pre_act5 TO e_cost_record;
```

旧 backend 查询再次得到迁移前金额；失败新表保留供分析，不直接 DROP。

**Step 5: 明确 backend 接棒闸门**

本 Task 只证明影子换表与反向 rename 可用。`current` 唯一、快照非空、金额锚定、R10=1、告警总数=20 必须在 backend Tasks 2～9 实现 `initialize_v1` 后，由 backend Task 9 在同一迁移流程上验证；未通过前不得升级共享 `b_demo`。

**Step 6: 全 datagen 回归**

```bash
PYTHONPATH=backend backend/.venv/bin/python -m unittest discover -s datagen/tests -p 'test_*.py' -v
```

Expected: all PASS；源码扫描确认三张成本结果表无 seed。

**Step 7: 主控检查点提交**

```bash
git add datagen/tests/test_act5_migration.py
git commit -m "test(act5): rehearse cost schema migration"
```

---

## Datagen Completion Gate

1. V001 fresh schema 与 V002 影子表结构一致。
2. reset 后 cost/recompute/archive 全为 0；bootstrap 前不得出现 v1。
3. 全仓搜索无三张成本结果表的 INSERT/UPSERT fixture。
4. 共享库迁移成功与反向 rename 回滚均在 disposable DB 实测。
5. 不改变既有对象数、点位数、时间范围、INJ 命中和历史建议 fixture。
