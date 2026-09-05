# 第五幕后端成本核算与报表 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. The steps below are ordered implementation instructions.

**Goal:** 在不 seed 成本结果的前提下，实现不可变 costVersion、current-only 消费、重算差异、三步反查、五段式报表、Excel 双落位、归档冻结和成本异常转建议闭环。

**Architecture:** 先把成本计算、Decimal 归一化和 canonical SHA-256 做成无 SQLAlchemy/wall-clock 依赖的纯领域模块；DDL 就绪后，成本服务以完整“月份×介质”批次插入不可变版本并原子切换 current。页面查询、总览、R10、报表装配和 Excel 均消费同一组 current/version DTO；报表预览、导出、归档共享唯一装配器，只有显式归档写入冻结 payload。

**Tech Stack:** Python 3.10+、FastAPI、Pydantic v2、SQLAlchemy 2.0 async、MySQL 8、openpyxl 3.1.5、Decimal、SHA-256、unittest、ruff。

## Global Constraints

- 事实来源：PRD §5.9、需求基线 REQ-051～062/073/074/076、`docs/mock-contracts.md` §6（commit `6071dfe`）。
- 只允许固定种子构造数据；`e_cost_record`、`e_cost_recompute_record`、`e_report_archive` 绝不 seed。
- 时间统一取 `DEMO_NOW=2026-07-12 23:59:00`；报表 generatedAt、重算时间和审计时间不得使用 wall-clock。
- 成本版本行的金额、用量、输入快照和签名写后不可变；生命周期字段不进入成本签名。
- 所有 `e_cost_record` 聚合必须显式限定 `is_current=1`，除非接口明确按 costVersion 读取历史。
- R10 告警只由规则引擎真算；成本页只读取事件冻结 `snapshot_json`，不复制规则阈值。
- `energy_mgr`：单价维护、重算发起；`finance`：单价/成本口径/分摊维护、重算发起与复核；`admin` 业务只读；`ops/dispatch` 无成本权限。
- backend-dev 只改 `backend/`；data-engineer 只改 `datagen/`；frontend-dev 只改 `web/`；主控维护 `docs/`。
- 执行 agent 不自行提交；每个任务绿灯后由主控复核并创建小提交。
- 每个新接口、领域函数和业务规则实现处标注对应 REQ ID。

---

## File Structure

### 新建

- `backend/module_energy/domain/cost_calculation.py`：纯成本计算、冻结输入 DTO、Decimal 规范化、成本签名和 diff。
- `backend/module_energy/domain/report_canonical.py`：报表 canonical payload、相关版本裁剪、稳定排序与 SHA-256。
- `backend/module_energy/dao/cost_dao.py`：current/history 成本、单价、分摊、重算与反查查询。
- `backend/module_energy/entity/do/cost_alloc_rule_do.py`：分摊规则 ORM。
- `backend/module_energy/entity/do/cost_recompute_record_do.py`：重算差异 ORM。
- `backend/module_energy/entity/do/report_archive_do.py`：冻结报表 ORM。
- `backend/module_energy/entity/vo/cost_vo.py`：成本、单价、分摊、重算请求模型。
- `backend/module_energy/entity/vo/report_vo.py`：预览/导出/归档请求模型。
- `backend/module_energy/service/cost_query_service.py`：月度视图、Top5、三步反查和 R10 evidence 装配。
- `backend/module_energy/service/report_service.py`：日报/月报/专项 canonical payload 装配与归档读取。
- `backend/module_energy/service/report_excel_service.py`：openpyxl 五行顶部块、数据表与口径说明页。
- `backend/module_energy/controller/report_controller.py`：模板、预览、导出、归档端点。
- `backend/tests/test_act5_cost_calculation.py`
- `backend/tests/test_act5_cost_versioning.py`
- `backend/tests/test_act5_cost_consumers.py`
- `backend/tests/test_act5_cost_contract.py`
- `backend/tests/test_act5_cost_query.py`
- `backend/tests/test_act5_report.py`
- `backend/tests/test_act5_cost_suggestion.py`
- `backend/tests/test_act5_workflow.py`

### 修改

- `backend/module_energy/domain/__init__.py`：导出成本/报表纯函数。
- `backend/module_energy/entity/do/cost_record_do.py`：映射 costVersion/current/快照/生成列。
- `backend/module_energy/entity/do/__init__.py`、`backend/module_energy/entity/vo/__init__.py`：导出新模型。
- `backend/module_energy/service/cost_service.py`：废除金额 UPSERT；实现 v1 初始化、重算和复核事务。
- `backend/module_energy/service/alert_service.py`：抽取月度 statistics 共用函数；详情增加成本深链。
- `backend/module_energy/service/rule_engine_service.py`：R10 全部 SQL current-only。
- `backend/module_energy/service/suggestion_service.py`：可信成本来源校验和财务窄授权。
- `backend/module_energy/entity/vo/suggestion_vo.py`：增加 `sourceContext`。
- `backend/module_energy/controller/suggestion_controller.py`：按 sourceContext 做角色分支，不扩大通用写权。
- `backend/module_energy/controller/cost_controller.py`：扩展成本读写端点并保留存量兼容端点。
- `backend/module_energy/controller/pipeline_controller.py`：bootstrap 空表初始化 v1；规则运行前强制 current 完整性闸门。
- `backend/module_energy/dao/overview_dao.py`：COST_MTD/Top5 current-only。
- `backend/module_energy/__init__.py`：将 §5.9 标记为已实现。
- `backend/tests/test_fx12_cost_fx13_baseline.py`：锚定查询 current-only。

---

## Data-engineer Schema Gate（后端任务 2 前必须绿灯）

backend-dev 不修改 DDL；data-engineer 必须先交付以下精确表形状：

```sql
-- e_cost_record 关键约束
cost_version int not null,
is_current tinyint(1) not null,
normalized_object_id bigint generated always as (ifnull(object_id, 0)) stored,
current_guard varchar(160) generated always as (
  case when is_current = 1
  then concat(object_type, ':', ifnull(object_id, 0), ':', stat_month, ':', energy_type_code)
  else null end
) stored,
formula_version varchar(64) not null,
tariff_snapshot_json longtext not null,
alloc_rule_snapshot_json longtext not null,
source_stat_snapshot_json longtext not null,
computed_by varchar(64) not null,
unique key uk_cost_version (
  object_type, normalized_object_id, stat_month, energy_type_code, cost_version
),
unique key uk_cost_current (current_guard)
```

还需新建 `e_cost_recompute_record` 与 `e_report_archive`，字段逐项对应契约 §6.5/§6.6；reset 顺序为 archive → recompute → cost。

### 唯一推荐迁移路径

不采用“原表 ALTER 后给旧行伪填 JSON 快照”。旧 v1 没有实际参与版本的完整冻结证据，SQL 回填会重演 FX-12 的签名失实。采用**影子表切换 + 后端真实 pipeline 重建 v1**：

1. fresh reset：直接更新 `V001__energy_domain.sql` 为新表结构；reset 后成本/差异/归档为空。
2. 共享 `b_demo`：`V002__act5_cost_versioning.sql` 只创建 `e_cost_record_act5`、差异表和归档表，不改旧 `e_cost_record`。
3. 停止 backend 写流量；记录旧表行数和三介质锚定值。
4. 原子执行 `RENAME TABLE e_cost_record TO e_cost_record_pre_act5, e_cost_record_act5 TO e_cost_record`。
5. 部署新 backend；只运行 `aggregation → baseline → CostService.initialize_v1 → rules`。R10 不得在 v1 current 完整性闸门通过前运行。
6. 验证 current 唯一性、v1 快照非空、金额锚点、R10=1、告警总数=20 后恢复服务；旧表保留到幕收口。
7. 回滚点：闸门失败时停止 backend，执行反向 rename 恢复 `e_cost_record_pre_act5`，不允许把不完整 act5 表继续提供给总览/R10。

---

### Task 1: 纯成本计算、冻结快照与签名

**Owner:** backend-dev

**Files:**
- Create: `backend/module_energy/domain/cost_calculation.py`
- Modify: `backend/module_energy/domain/__init__.py`
- Create: `backend/tests/test_act5_cost_calculation.py`

**Interfaces:**
- Produces:
  - `calculate_cost(input: CostCalculationInput) -> CostCalculationResult`
  - `build_cost_signature(result: CostCalculationResult) -> str`
  - `build_cost_diff(old: CostCalculationResult, new: CostCalculationResult) -> tuple[CostDiffItem, ...]`

**Step 1: 写失败测试和公开 DTO**

```python
@dataclass(frozen=True)
class TariffSnapshot:
    tariff_id: int
    energy_type: str
    tou_period: str
    price: Decimal
    currency: str
    effective_from: date
    effective_to: date | None
    version_no: int

@dataclass(frozen=True)
class CostCalculationInput:
    object_type: str
    object_id: int | None
    stat_month: str
    energy_type: str
    cost_version: int
    usage_qty: Decimal
    peak_qty: Decimal
    flat_qty: Decimal
    valley_qty: Decimal
    tariffs: tuple[TariffSnapshot, ...]
    alloc_rule_snapshot: Mapping[str, object]
    source_stat_snapshot: Mapping[str, object]
    formula_version: str = "COST-V1"
```

测试必须覆盖：电峰/平/谷、flatOnly 三桶回退、缺单价抛 `MissingTariffError`、金额四位内部精度、签名对金额/用量/costVersion/实际参与版本敏感、签名对 isCurrent/status 不敏感、映射键序变化不改变签名。

**Step 2: 运行并确认红灯**

```bash
cd backend
.venv/bin/python -m unittest tests.test_act5_cost_calculation -v
```

Expected: FAIL，`module_energy.domain.cost_calculation` 尚不存在。

**Step 3: 实现 Decimal 计算与实际参与版本裁剪**

```python
def _bucket_prices(tariffs: tuple[TariffSnapshot, ...]) -> dict[str, Decimal]:
    by_period = {item.tou_period: item.price for item in tariffs}
    flat = by_period.get("flat", by_period.get("flat_only"))
    if flat is None:
        raise MissingTariffError("flat/flatOnly tariff is required")
    return {
        "peak": by_period.get("peak", flat),
        "flat": flat,
        "valley": by_period.get("valley", flat),
    }
```

结果只冻结实际参与的电三档或单档 flatOnly，禁止把查询到但未参与计算的版本签入 payload。

**Step 4: 实现 canonical SHA-256 与对象 diff**

稳定 JSON 使用 `sort_keys=True, separators=(",", ":"), ensure_ascii=False`；Decimal 先转固定字符串，完整签名格式 `COST-SHA256-V1:<64hex>`。diff 固定输出 `usageQty/peakCost/flatCost/valleyCost/totalCost`，金额与比率保留 2 位。

**Step 5: 验证**

```bash
cd backend
.venv/bin/python -m unittest tests.test_act5_cost_calculation -v
.venv/bin/python -m ruff check module_energy/domain/cost_calculation.py tests/test_act5_cost_calculation.py
```

Expected: all PASS；ruff 无输出。

**Step 6: 主控检查点提交**

```bash
git add backend/module_energy/domain backend/tests/test_act5_cost_calculation.py
git commit -m "feat(act5): add immutable cost calculations"
```

---

### Task 2: ORM、v1 初始化与原子 costVersion 服务

**Owner:** backend-dev（依赖 Data-engineer Schema Gate）

**Files:**
- Modify: `backend/module_energy/entity/do/cost_record_do.py`
- Create: `backend/module_energy/entity/do/cost_alloc_rule_do.py`
- Create: `backend/module_energy/entity/do/cost_recompute_record_do.py`
- Create: `backend/module_energy/entity/do/report_archive_do.py`
- Modify: `backend/module_energy/entity/do/__init__.py`
- Create: `backend/module_energy/dao/cost_dao.py`
- Modify: `backend/module_energy/service/cost_service.py`
- Create: `backend/tests/test_act5_cost_versioning.py`

**Interfaces:**
- Consumes: Task 1 纯函数和新 DDL。
- Produces:
  - `CostService.initialize_v1(db, *, computed_by: str) -> CostBatchResult`
  - `CostService.recompute(db, request, *, operator: str) -> CostRecomputeResult`
  - `CostService.review(db, recompute_id, action, remark, *, reviewer: str) -> CostRecomputeResult`

**Step 1: 写 ORM/事务失败测试**

测试覆盖：空表 bootstrap 为每个 `e_stat_month` 业务键生成 v1 reviewed current；第二次初始化幂等；同月/介质重算对全部对象生成同一 v2；旧行金额/签名未变；每个业务键仅一条 current；拒绝复核将 v2 void 并恢复 v1 current；冻结后只允许新版本。

**Step 2: 运行并确认红灯**

```bash
cd backend
ACT5_TEST_DB=codex_act5_backend_test .venv/bin/python -m unittest tests.test_act5_cost_versioning -v
```

Expected: FAIL，新列/新表/服务接口尚不存在。

**Step 3: 映射新表并实现 v1 初始化**

`initialize_v1` 先检查成本表是否为空；非空时验证全部 current/快照完整后直接返回，不删除历史。空表时读取完整 `e_stat_month`、单价与分摊版本，调用 Task 1，批量 INSERT v1 `reviewed + isCurrent=true`。服务不得调用 datagen 或写统计输入。

**Step 4: 实现原子重算**

```python
current_rows = await CostDao.lock_current_batch(db, stat_month, energy_type)
next_version = max(row.cost_version for row in current_rows) + 1
new_rows = calculate_complete_batch(current_rows, selected_tariffs, selected_alloc, next_version)
await CostDao.insert_versions(db, new_rows)
await CostDao.clear_current(db, stat_month, energy_type)
await CostDao.mark_current(db, stat_month, energy_type, next_version)
await CostDao.insert_recompute_record(db, build_batch_diff(current_rows, new_rows))
```

以上操作处于同一事务；服务中间不得 `commit()`。客户端不能传金额、diff、签名或 operator。

**Step 5: 实现复核通过/拒绝**

approve：pending → approved，v2 `pendingRecompute → reviewed`。reject：v2 `void + isCurrent=false`，oldVersion 恢复 current，差异记录 rejected。两条路径都锁定重算记录和版本批次。

**Step 6: 验证**

```bash
cd backend
ACT5_TEST_DB=codex_act5_backend_test .venv/bin/python -m unittest tests.test_act5_cost_versioning -v
.venv/bin/python -m ruff check module_energy/entity/do module_energy/dao/cost_dao.py module_energy/service/cost_service.py tests/test_act5_cost_versioning.py
```

Expected: all PASS；数据库 current 唯一性查询返回 0 个异常分组。

**Step 7: 主控检查点提交**

```bash
git add backend/module_energy/entity backend/module_energy/dao/cost_dao.py backend/module_energy/service/cost_service.py backend/tests/test_act5_cost_versioning.py
git commit -m "feat(act5): version monthly cost records"
```

---

### Task 3: 三个 current-only 消费方回归

**Owner:** backend-dev

**Files:**
- Modify: `backend/module_energy/dao/overview_dao.py`
- Modify: `backend/module_energy/service/rule_engine_service.py`
- Modify: `backend/module_energy/controller/cost_controller.py`
- Modify: `backend/tests/test_fx12_cost_fx13_baseline.py`
- Create: `backend/tests/test_act5_cost_consumers.py`

**Interfaces:**
- Consumes: Task 2 `ECostRecord.is_current`。
- Produces: 总览、Top5、R10、兼容 cost API 全部 current-only。

**Step 1: 写“历史 v1 导致双计数”的失败测试**

在隔离库已有 v1 current 后插入同金额 v0 history，断言：

```python
self.assertEqual(await overview_cost("electricity"), Decimal("59501.99"))
self.assertEqual(await count_rule("R10"), 1)
self.assertEqual(await count_all_alerts(), 20)
self.assertEqual(await legacy_cost_summary("electricity"), Decimal("59501.99"))
```

Top5 顺序与插入历史前完全相同。

**Step 2: 运行并确认红灯**

```bash
cd backend
ACT5_TEST_DB=codex_act5_backend_test .venv/bin/python -m unittest tests.test_act5_cost_consumers -v
```

Expected: FAIL，至少一个 SUM/R10 查询发生双计数。

**Step 3: 改造 OverviewDao**

`get_cost_month_summary`、`get_top_cost_objects` 的所有 `ECostRecord` 查询增加 `ECostRecord.is_current.is_(True)`；保留 `status != 'void'` 作为防御条件。

**Step 4: 改造 R10 原生 SQL**

月份枚举、baseline CTE、report CTE 和任何 cost fallback 查询都增加 `is_current = 1`。不允许仅在最外层过滤。

**Step 5: 改造存量 cost API**

`/cost/month/summary` 与 `/cost/month/top` 继续兼容现有调用，但只返回 current；响应补 `currentCostVersion`，字段改 camelCase 时保留一次兼容映射测试。

**Step 6: 验证锚定值**

```bash
cd backend
ACT5_TEST_DB=codex_act5_backend_test .venv/bin/python -m unittest tests.test_act5_cost_consumers tests.test_fx12_cost_fx13_baseline -v
```

Expected: 电 59501.99、气 4030.33、水 1313.12、R10=1、总告警=20；历史版本存在时完全一致。

**Step 7: 主控检查点提交**

```bash
git add backend/module_energy/dao/overview_dao.py backend/module_energy/service/rule_engine_service.py backend/module_energy/controller/cost_controller.py backend/tests
git commit -m "fix(act5): scope cost consumers to current versions"
```

---

### Task 4: 单价、分摊、重算与复核 API

**Owner:** backend-dev

**Files:**
- Create: `backend/module_energy/entity/vo/cost_vo.py`
- Modify: `backend/module_energy/entity/vo/__init__.py`
- Modify: `backend/module_energy/dao/cost_dao.py`
- Modify: `backend/module_energy/service/cost_service.py`
- Modify: `backend/module_energy/controller/cost_controller.py`
- Modify: `backend/module_energy/controller/pipeline_controller.py`
- Create: `backend/tests/test_act5_cost_contract.py`

**Interfaces:**
- Produces: §6.4/§6.5 的 tariff、allocation、recompute、review 端点。

**Step 1: 写路由、VO 与权限失败测试**

覆盖：camelCase/extra_forbid；电价三档与水气 flatOnly 校验；日期重叠 422；finance/energy_mgr 均可维护单价和发起重算；只有 finance 可新增分摊规则/复核；admin 读可写禁；ops/dispatch 403。

**Step 2: 定义请求模型**

```python
class CostRecomputeRequest(CamelRequest):
    stat_month: str = Field(pattern=r"^\d{4}-(0[1-9]|1[0-2])$")
    energy_type: Literal["electricity", "water", "compressed_air"]
    trigger_reason: str = Field(min_length=1, max_length=255)
    tariff_version: int | None = Field(default=None, ge=1)
    alloc_rule_version: int | None = Field(default=None, ge=1)

class CostReviewRequest(CamelRequest):
    action: Literal["approve", "reject"]
    remark: str = Field(min_length=1, max_length=512)
```

optional 字段 validator 必须先处理 None（P-19）。

**Step 3: 实现端点与角色守卫**

读端点复用 `CostAccessGuard`；写端点分别使用 `{'energy_mgr','finance'}` 或 `{'finance'}`。admin 通配只用于读，不能绕过业务写守卫。

**Step 4: 改造 bootstrap**

顺序固定为 aggregation → baseline → `initialize_v1` → current 完整性检查 → rules。`/pipeline/cost/rebuild` 改为显式初始化/管理端调用，不能继续做 UPSERT 覆盖。

**Step 5: 验证**

```bash
cd backend
.venv/bin/python -m unittest tests.test_act5_cost_contract -v
.venv/bin/python -m ruff check module_energy/entity/vo/cost_vo.py module_energy/controller/cost_controller.py module_energy/controller/pipeline_controller.py tests/test_act5_cost_contract.py
```

Expected: all PASS；权限矩阵逐角色通过。

**Step 6: 主控检查点提交**

```bash
git add backend/module_energy/entity/vo backend/module_energy/dao/cost_dao.py backend/module_energy/service/cost_service.py backend/module_energy/controller backend/tests/test_act5_cost_contract.py
git commit -m "feat(act5): add tariff and cost recompute APIs"
```

---

### Task 5: 月度视图、R10 evidence 与三步反查

**Owner:** backend-dev

**Files:**
- Create: `backend/module_energy/service/cost_query_service.py`
- Modify: `backend/module_energy/dao/cost_dao.py`
- Modify: `backend/module_energy/service/alert_service.py`
- Modify: `backend/module_energy/controller/alert_controller.py`
- Modify: `backend/module_energy/controller/cost_controller.py`
- Create: `backend/tests/test_act5_cost_query.py`

**Interfaces:**
- Produces: `GET /cost/month-view`、`GET /cost/trace`、告警详情 `costDeepLink`。

**Step 1: 写默认态与深链失败测试**

断言默认 2026-07/ALL/electricity、07=`inProgress`、05=`partial`、06=`complete`；金额为 138014.50/161920.03/59501.99；6 月异常只因 R10 标红；deepLink 精确带 `statMonth=2026-06&zone=B&energyType=electricity&focus=R10`。

**Step 2: 写 R10 evidence 失败测试**

接口必须把事件 JSON 的 `baseline_peak_share/report_peak_share/diff_pp/threshold_pp` 映射为 camelCase。测试替换 snapshot_json 任一值后响应同步变化，证明页面没有重算；无事件时为 null。

同时覆盖未实际发生共享分摊的 v1：`allocationEvidence` 必须返回冻结 `allocRuleSnapshot`、`allocationDetails=[]`、`allocationStatus="notApplied"` 和 `message="本对象无共享分摊"`；禁止为了填满第三步伪造共享表计、比例或分摊金额。

**Step 3: 实现月度视图**

monthTrend 按月升序；对象按 normalized ID；金额 2 位、比率 2 位。`costWarnings` 只含 `peakShareAnomaly/tariffMissing`。Top5 从 backend current 查询返回，前端不补算。

**Step 4: 实现三步反查**

历史 costVersion 精确读取冻结快照；省略版本只读 current。响应固定为 usageEvidence、tariffEvidence、allocationEvidence，并带 recomputeChain/relatedAlert/suggestionContext。

第三步区分“没有规则”和“规则存在但未应用”：demo v1 属于后者，仍回显版本行冻结的 `allocRuleSnapshot`，但原始共享表计为空、`allocationDetails=[]`、`allocationStatus="notApplied"`、`message="本对象无共享分摊"`。空态来自真实计算快照，不得补造比例。

**Step 5: 抽取告警共用聚合**

新增唯一聚合函数 `AlertService.get_statistics(db, *, period_start, period_end, zone, rule_code)`，时间窗采用左闭右开 `[period_start, period_end)`，事件归属只按 `firstOccurredAt`。成本月视图和月报传自然月边界；日报传目标日 00:00 到次日 00:00。告警列表、成本页和所有报告装配都调用这一函数，禁止为日报另写日聚合或第二套 SQL。

**Step 6: 验证**

```bash
cd backend
ACT5_TEST_DB=codex_act5_backend_test .venv/bin/python -m unittest tests.test_act5_cost_query -v
```

Expected: all PASS；R10 实库 evidence 差值约 12pp且以冻结值为准。

**Step 7: 主控检查点提交**

```bash
git add backend/module_energy/service/cost_query_service.py backend/module_energy/service/alert_service.py backend/module_energy/controller backend/module_energy/dao/cost_dao.py backend/tests/test_act5_cost_query.py
git commit -m "feat(act5): add cost investigation views"
```

---

### Task 6: canonical 报表装配器与预览

**Owner:** backend-dev

**Files:**
- Create: `backend/module_energy/domain/report_canonical.py`
- Modify: `backend/module_energy/domain/__init__.py`
- Create: `backend/module_energy/entity/vo/report_vo.py`
- Create: `backend/module_energy/service/report_service.py`
- Create: `backend/module_energy/controller/report_controller.py`
- Create: `backend/tests/test_act5_report.py`

**Interfaces:**
- Produces:
  - `ReportService.assemble(db, request) -> CanonicalReportPayload`
  - `build_report_signature(payload) -> str`
  - templates/preview API。

**Step 1: 写确定性与五段来源失败测试**

同参数装配两次必须深度相等且签名相同；对象按 ID、枚举按契约顺序；金额/比率两位。月报调用 `SuggestionService.get_retrospective` 与 `AlertService.get_statistics`；日报也必须调用同一个 `AlertService.get_statistics`，并传目标日的左闭右开窗口。测试用 spy 分别锁定月窗和日窗参数，证明没有第二套告警公式。

**Step 2: 定义 canonical payload**

```python
@dataclass(frozen=True)
class CanonicalReportPayload:
    template_code: str
    template_version: str
    period: Mapping[str, object]
    filters: Mapping[str, object]
    generated_at: datetime
    sections: Mapping[str, object]
    quality_summary: Mapping[str, object]
    version_snapshots: Mapping[str, object]
```

**Step 3: 实现唯一装配器**

日报只含 usage/alerts/quality；月报含 usage/cost/alerts/suggestions/quality；专项按模板裁剪。装配器只能 SELECT，不调用 pipeline、重算、规则或建议写服务。

**Step 4: 实现版本相关性裁剪和签名**

日报签名不得出现 costVersion；不含建议段不得出现 templateVersion。完整 SHA-256 格式 `REPORT-SHA256-V1:<64hex>`，generatedAt 来自 DEMO_NOW。

**Step 5: 实现 templates/preview**

月报默认上一完整月 2026-06；进行中月强制 `asOf=2026-07-12`。订阅返回灰态对象，不提供写端点。

**Step 6: 验证**

```bash
cd backend
.venv/bin/python -m unittest tests.test_act5_report.CanonicalReportTest tests.test_act5_report.ReportPreviewTest -v
.venv/bin/python -m ruff check module_energy/domain/report_canonical.py module_energy/service/report_service.py module_energy/controller/report_controller.py tests/test_act5_report.py
```

Expected: all PASS；同参数签名一致。

**Step 7: 主控检查点提交**

```bash
git add backend/module_energy/domain backend/module_energy/entity/vo/report_vo.py backend/module_energy/service/report_service.py backend/module_energy/controller/report_controller.py backend/tests/test_act5_report.py
git commit -m "feat(act5): assemble deterministic energy reports"
```

---

### Task 7: Excel 双落位与冻结归档

**Owner:** backend-dev

**Files:**
- Create: `backend/module_energy/service/report_excel_service.py`
- Modify: `backend/module_energy/service/report_service.py`
- Modify: `backend/module_energy/controller/report_controller.py`
- Modify: `backend/tests/test_act5_report.py`

**Interfaces:**
- Consumes: Task 6 canonical payload。
- Produces: export/archive/list/detail/archive-export。

**Step 1: 写工作簿结构失败测试**

用 `openpyxl.load_workbook(BytesIO(data))` 断言每个业务表第 1～5 行为顶部块、第 6 行列头、`freeze_panes == 'A7'`、存在“口径说明”页、短签名与完整签名一致。日报不出现成本版本；月报出现 current costVersion。

**Step 2: 实现 Excel 生成器**

```python
TOP_BLOCK_ROWS = (
    "统计周期与查询条件",
    "数据来源与统计口径",
    "质量摘要",
    "系统统计时钟生成时间与版本摘要",
    "签名短码与复核提示",
)
```

不合并数据列，不破坏 AutoFilter；口径页写 fullSignature 与“同参数重导出比对签名”。

**Step 3: 实现未复核版本门**

preview 可展示 pendingRecompute；export/archive 遇到 pendingRecompute 返回 409，不产生文件或归档行。

**Step 4: 实现冻结归档**

归档保存完整 payload/signature/version snapshots；归档详情与再次下载只读 payload_snapshot_json，不重查业务表。测试归档后重算成本，旧归档金额/签名不变。

**Step 5: 验证**

```bash
cd backend
ACT5_TEST_DB=codex_act5_backend_test .venv/bin/python -m unittest tests.test_act5_report -v
```

Expected: all PASS；Excel 可重新打开；archive 导出与初次导出签名相同。

**Step 6: 主控检查点提交**

```bash
git add backend/module_energy/service/report_excel_service.py backend/module_energy/service/report_service.py backend/module_energy/controller/report_controller.py backend/tests/test_act5_report.py
git commit -m "feat(act5): export and archive signed reports"
```

---

### Task 8: 财务可信成本来源转人工建议

**Owner:** backend-dev

**Files:**
- Modify: `backend/module_energy/entity/vo/suggestion_vo.py`
- Modify: `backend/module_energy/service/suggestion_service.py`
- Modify: `backend/module_energy/controller/suggestion_controller.py`
- Create: `backend/tests/test_act5_cost_suggestion.py`

**Interfaces:**
- Consumes: Task 5 suggestionContext 与第四幕 `POST /suggestions`。
- Produces: `sourceContext.kind=costAnomaly` 财务窄授权。

**Step 1: 写权限与防伪失败测试**

finance + valid cost context 成功；finance + general manual 403；energy_mgr general manual 仍成功；伪造金额/不存在版本/签名不符 422 且计数不变；重复成本来源返回既有 relatedSuggestionId。

**Step 2: 扩展请求模型**

`sourceContext` 只接收定位键和 costSignature，不接收金额、占比、tariffSnapshot 或 allocRuleSnapshot；extra 字段拒绝。

**Step 3: 服务端重读并冻结证据**

按 object/month/energy/costVersion 查询不可变行，校验签名，关联可选 recompute/R10，生成稳定 sourceFingerprint 与 sourceSnapshot。sourceType 保持 manual。

**Step 4: 收窄 controller 权限**

移除“仅 energy_mgr 的无条件入口”写法，改为先取身份再由 service 判定：energy_mgr 可通用创建；finance 仅 valid costAnomaly。其它建议流转/验证/关闭权限不变。

**Step 5: 验证**

```bash
cd backend
.venv/bin/python -m unittest tests.test_act5_cost_suggestion tests.test_act4_suggestion_contract tests.test_act4_suggestion_workflow -v
```

Expected: 第五幕窄授权 PASS；第四幕权限与工作流无回归。

**Step 6: 主控检查点提交**

```bash
git add backend/module_energy/entity/vo/suggestion_vo.py backend/module_energy/service/suggestion_service.py backend/module_energy/controller/suggestion_controller.py backend/tests/test_act5_cost_suggestion.py
git commit -m "feat(act5): hand off cost anomalies to suggestions"
```

---

### Task 9: 隔离库全链路与复位闸门

**Owner:** backend-dev + 主控联调

**Files:**
- Create: `backend/tests/test_act5_workflow.py`
- Modify: `backend/module_energy/__init__.py`
- Modify: `backend/tests/test_fx12_cost_fx13_baseline.py`

**Interfaces:**
- 验证 Tasks 1～8 与 data/frontend handoff 的完整后端契约。

**Step 1: 创建 fresh 隔离库并执行 reset/bootstrap**

```bash
docker exec bdemo-mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS codex_act5_backend_test; CREATE DATABASE codex_act5_backend_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; GRANT ALL PRIVILEGES ON codex_act5_backend_test.* TO '\''demo'\''@'\''%'\'';"'
ACT5_TEST_DB=codex_act5_backend_test PYTHONPATH=backend backend/.venv/bin/python datagen/generate_demo_data.py --reset
```

data-engineer 任务必须让 generator 接受 `ACT5_TEST_DB`，且仍只允许 `codex_*_test`。

**Step 2: 写破坏性工作流测试**

固定链路：bootstrap v1 → 新增生效电价 → 2026-06 重算 v2 → 三消费者 current-only → finance 复核 → 月报 preview/export/archive → 成本异常转建议 → reset/bootstrap 恢复 v1。测试开场断言 fresh fixture，禁止复用脏库（P-21）。

**Step 3: 运行全后端回归**

```bash
cd backend
ACT5_TEST_DB=codex_act5_backend_test .venv/bin/python -m unittest discover -s tests -p 'test_*.py' -v
.venv/bin/python -m ruff check module_energy tests
```

Expected: all PASS；无 skip（除明确不依赖 ACT5 的旧隔离用例）；ruff 无输出。

**Step 4: 查库验收**

```sql
SELECT object_type, IFNULL(object_id,0), stat_month, energy_type_code,
       SUM(is_current) AS current_count
FROM e_cost_record
GROUP BY object_type, IFNULL(object_id,0), stat_month, energy_type_code
HAVING current_count <> 1;

SELECT stat_month, ROUND(total_cost,2), cost_version, is_current
FROM e_cost_record
WHERE object_type='system' AND energy_type_code='electricity'
ORDER BY stat_month, cost_version;
```

Expected: 第一条 0 行；fresh v1 锚定 138014.50/161920.03/59501.99；R10=1、总告警=20。

**Step 5: 复位确定性**

再次 reset 后差异/归档为空；bootstrap 后只有 v1 current；同参数月报签名与首次 fresh fixture 相同。

**Step 6: 清理隔离库并主控提交**

```bash
docker exec bdemo-mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS codex_act5_backend_test;"'
git add backend/tests/test_act5_workflow.py backend/module_energy/__init__.py backend/tests/test_fx12_cost_fx13_baseline.py
git commit -m "test(act5): verify cost and report workflow"
```

---

## Backend Completion Gate

backend-dev 交付必须同时提供：

1. 全测试命令、通过数和 0 failure 输出。
2. current 唯一性查询 0 行。
3. v1 三介质锚定与 05/06/07 电费精确值。
4. 插入历史版本后的总览/Top5/R10/兼容 API 对照结果。
5. v1→v2 diffSummary 样例与复核状态。
6. Excel 工作表名、freeze pane、顶部块、完整签名与 archive 重下载对照。
7. reset→bootstrap→重算→复核→归档→reset 的实测计数。

本计划定稿后先派 data-engineer 落 DDL/迁移；Schema Gate 绿灯后 backend-dev 执行 Tasks 2～9；后端 API/fixture 绿灯后再派 frontend-dev 开两页。不允许 frontend 先按推测字段开工。
