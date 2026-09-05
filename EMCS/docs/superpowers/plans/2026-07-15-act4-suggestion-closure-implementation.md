# 第四幕节能建议闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成 REQ-045～050 的 R06 真实告警转建议、五列看板、责任执行、四维验证、三档关闭和归档复盘，并确定性恢复 8 条人工历史建议。

**Architecture:** 先在 `backend/module_energy/domain/suggestion_calculation.py` 建立无 SQLAlchemy、环境变量和 wall-clock 依赖的纯计算核心；backend 与 datagen 只负责把数据库行适配成同一组 dataclass。共享计算核心绿灯后，backend 与 datagen 按目录所有权并行，frontend 按已冻结契约开发。

**Tech Stack:** Python 3.10+、dataclass/Decimal/hashlib、FastAPI、SQLAlchemy 2.0 async、MySQL 8、Vue 3、Element Plus、ECharts、Node `node:test`。

## Global Constraints

- 上游事实源：知识库《demo-演示数据构造规范》§8、PRD §5.8、需求基线 REQ-045～050；接口为 `docs/mock-contracts.md` §5。
- R06 规则来源建议绝不 seed；R08 不新增模板、建议、特判或转换入口。
- reset 在 bootstrap 前恢复 3 个历史快照；只能读取 raw/meter/tariff/work_order，禁止读取 `e_stat_*` 或 `e_cost_record`。
- H1～H8 均为 `manual` 且无告警关联；运行期 R06 使用稳定业务指纹幂等。
- 时间统一取 `DEMO_NOW=2026-07-12 23:59:00`；不增加定时验证任务。
- 请求、响应、数据库、页面和导出均不得含设备启停、远程关阀、功率设定、自动控制或指令下发（REQ-091/096）。
- backend-dev 只改 `backend/`；data-engineer 只改 `datagen/`；frontend-dev 只改 `web/`；主控维护 `docs/`。
- 执行 agent 不自行提交，由主控在每个绿灯检查点创建小提交；不使用 `bd`。

## 已通过的前置闸门

- [x] 固定种子 `20260713` 已在隔离库完整重放，未改共享 `b_demo`。
- [x] 知识库已改为固定点位与等长窗口：

| 样本 | 点位 | 基线 / 报告 | usageSaving | costSaving | intensitySaving | coverage | status |
| --- | --- | --- | ---: | ---: | ---: | --- | --- |
| H4 | SF-B1-E | 06-24～07-01 / 07-01～07-08 | 9.61 | 15.52 | 2.38 | 100/100 | effective |
| H5 | AC-B2-E | 06-24～07-01 / 07-01～07-08 | 8.50 | 14.78 | 1.18 | 100/100 | effective |
| H6 | BC-A1-E | 06-28～07-05 / 07-05～07-12 | -4.76 | -5.29 | -14.04 | 99.85/100 | ineffective |

这些数字只作共享公式复算断言，禁止作为 INSERT 字面值。

---

### Task 1: 单一共享计算核心

**Owner:** backend-dev

**Files:**
- Create: `backend/module_energy/domain/__init__.py`
- Create: `backend/module_energy/domain/suggestion_calculation.py`
- Create: `backend/tests/test_act4_calculation.py`

**Interfaces:**
- Produces:
  - `calculate_history_verification(input: VerificationInput) -> VerificationResult`
  - `calculate_r06_verification(input: VerificationInput) -> VerificationResult`
  - `calculate_r06_priority(input: R06PriorityInput) -> PriorityResult`
  - `build_calculation_signature(payload: Mapping[str, object]) -> str`

- [ ] **Step 1: 写失败测试和公开 DTO**

测试直接构造以下类型，不连接数据库：

```python
@dataclass(frozen=True)
class ReadingSample:
    sample_time: datetime
    incremental_value: Decimal | None
    quality_state: str

@dataclass(frozen=True)
class TariffRate:
    energy_type: str
    tou_period: str
    price: Decimal
    effective_from: date
    effective_to: date | None
    version: int

@dataclass(frozen=True)
class WorkloadSample:
    start_time: datetime
    value: Decimal
    unit: str
    status: str

@dataclass(frozen=True)
class VerificationWindow:
    start: datetime
    end: datetime
    sample_period_seconds: int
    point_code: str
    energy_type: str
    unit: str
    readings: tuple[ReadingSample, ...]
    tariffs: tuple[TariffRate, ...]
    workloads: tuple[WorkloadSample, ...]

@dataclass(frozen=True)
class VerificationInput:
    baseline: VerificationWindow
    report: VerificationWindow
    formula_version: str
```

首批测试：H4/H5/H6 golden vectors 返回 effective/effective/ineffective 且误差 ≤0.01；历史口径下覆盖率 94.99% 或作业量 0 返回 insufficient；jump/miss/dup/frozen 不计入有效值。R06 另测 79.99% 返回 insufficient、80.00%～94.99% 返回带降级提示的确定性结论、95% 起不带降级提示。

- [ ] **Step 2: 运行并确认红灯**

```bash
cd backend
.venv/bin/python -m unittest tests.test_act4_calculation -v
```

Expected: FAIL，目标模块尚不存在。

- [ ] **Step 3: 实现 SUGGESTION-HISTORY-VERIFY-V1**

固定口径：

```python
VALID_QUALITY_CODES = frozenset({"ok", "late", "est", "fix"})
coverage = valid_count / theoretical_count
valid_hours = Decimal(valid_count * sample_period_seconds) / Decimal(3600)
usage_rate = valid_usage / valid_hours
cost_rate = valid_cost / valid_hours
usage_intensity = valid_usage / area_workload
saving_pct = (baseline - report) / baseline * Decimal(100)
```

单价按日期匹配生效版本，电价时段为峰 08–11/18–21、谷 22–06、其余平。任一 coverage<0.95、任一窗口 workload≤0、或基线用量/成本/强度为 0 时 insufficient；否则 usageSaving≥5.00、costSaving≥5.00、intensitySaving≥1.00 时 effective，其余 ineffective。全部用 Decimal，百分比 `ROUND_HALF_UP` 两位。

- [ ] **Step 4: 实现 R06 专用验证**

只取 `AF-B-MAIN` 谷时段（22:00～次日 06:00）合格原始读数，理论点数也只按窗口内谷时段采样槽计算，不能用总窗时长或 `len(readings)`：基线 `[07-02,07-10)`、报告 `[07-10,DEMO_NOW]`。适配器把演示时钟转换为 `floorToSample(DEMO_NOW) + sampleInterval` 的等价右开边界；300 秒采样下 `2026-07-12 23:59:00` 精确归一为 `2026-07-13 00:00:00`，因此基线理论点数为 `8×8×12=768`、报告为 `3×8×12=288`。AREA-B 工单只作背景，不作零工单分母。任一窗口 coverage `<80%` 返回 `insufficient`；`[80%,95%)` 仍按归一化用量给出 `effective/ineffective`，同时返回降级提示；两窗均 `>=95%` 时不带降级提示。报告期谷段有效小时用量均值严格低于基线即 `effective`，否则 `ineffective`，不增设 5% 门槛；成本、作业量和质量仍完整进入对比结果。版本 `SUGGESTION-R06-VERIFY-V1`。

- [ ] **Step 5: 实现 R06 三个 0～100 真算因子**

输入字段：`e_alert_event.first_occur_time/last_occur_time/occur_count/snapshot_json`、`AF-B-MAIN` raw、压缩空气生效单价、模板 difficulty=35/safety=70。

窗口：

`observationStart=floorHour(first)`；`observationEnd=floorHour(last)+1h`；reference 为 observation 前置同长窗口。reference/observation 用量率只取谷时段合格样点。

公式：

```text
avoidableUsage = max(0, (observedRate-referenceRate) × observedValidHours)
energyScale = clamp(avoidableUsage / observedValleyUsage × 100, 0, 100)
avoidableCost = avoidableUsage × (observedValleyCost / observedValleyUsage)
costImpact = clamp(avoidableCost / observationAllHoursCost × 100, 0, 100)
duration = clamp(occurCount / candidateValleyHours × 100, 0, 100)
```

任一分母为 0 时对应因子为 0；三项 `ROUND_HALF_UP` 两位，版本 `PRIORITY-R06-V1`。综合分：

```python
score = (
    energy_scale * Decimal("0.25")
    + cost_impact * Decimal("0.25")
    + duration * Decimal("0.20")
    + (Decimal(100) - implementation_difficulty) * Decimal("0.10")
    + safety_impact * Decimal("0.20")
).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
```

score≥75 高，50≤score<75 中，其余低。

- [ ] **Step 6: 固定签名**

对 canonical JSON（formulaVersion、pointCode、energyType、两窗口、合格质量码、单价版本、四维舍入结果）取 SHA-256 前 16 位，返回 `SV1-<hex>`；generatedAt/operator/数据库 ID 不进签名。

- [ ] **Step 7: 验证**

```bash
cd backend
.venv/bin/python -m unittest tests.test_act4_calculation -v
.venv/bin/python -m ruff check module_energy/domain/suggestion_calculation.py tests/test_act4_calculation.py
```

Expected: all PASS，ruff 无输出。

- [ ] **Step 8: 主控提交**

```bash
git add backend/module_energy/domain backend/tests/test_act4_calculation.py
git commit -m "feat(act4): add shared suggestion calculations"
```

---

### Task 2: 建议域 DDL、默认模板与历史 fixture

**Owner:** data-engineer

**Files:**
- Modify: `datagen/ddl/V001__energy_domain.sql`
- Modify: `datagen/generate_demo_data.py`
- Create: `datagen/tests/test_act4_schema.py`
- Create: `datagen/tests/test_act4_fixtures.py`

**Interfaces:**
- Consumes: Task 1 纯函数与知识库 §8。
- Produces: template/suggestion/flow_log/verification 四表及 1/8/25/3 fixture。

- [ ] **Step 1: 先写 DDL 失败测试**

断言：template 含契约 §5.2 字段与 `uk_template_code_version`；suggestion 含来源快照、对象、五因子、通用 saving、验证、三档、延期、rowVersion 与 `uk_source_template`；flow 含 `payload_snapshot_json` 且索引 `(suggestion_id,flow_id)`；verification 唯一 `(suggestion_id,version)`。`source_alert_id` 不建外键。

- [ ] **Step 2: 运行并确认红灯**

```bash
PYTHONPATH=backend backend/.venv/bin/python -m unittest discover -s datagen/tests -p 'test_act4_schema.py' -v
```

Expected: FAIL，三张新表与主表扩展尚不存在。

- [ ] **Step 3: 实现 DDL/reset**

reset 清理顺序：flow_log、verification、suggestion、template，再清既有业务表。随后无条件 seed `TPL-R06-AIR-LEAK-DEFAULT`（R06/area/35/70/version1/enabled）。

- [ ] **Step 4: 实现 raw 适配器和 H fixture**

新增精确接口：

```python
def load_verification_input(
    conn, *, point_code: str, area_code: str,
    baseline_start: datetime, baseline_end: datetime,
    report_start: datetime, report_end: datetime,
) -> VerificationInput: ...

def seed_historical_suggestions(conn, ids: dict) -> dict[str, int]: ...
```

适配器只查 raw/meter/tariff/work_order；H4/H5/H6 调 Task 1。状态或数值锚点误差 >0.01 时回滚 reset。H 路径精确生成 25 条 flow；时间线以 flowId 排序；H5/H6 仅用占位附件元数据。

- [ ] **Step 5: 写并运行隔离库 fixture 测试**

测试查库断言 template=1、manual=8、rule=0、flow=25、verification=3、七态计数 `1/1/1/1/1/2/1`、R06 linked=0、R08 related=0；从数据库读回快照并用共享函数复算 ≤0.01。

```bash
docker exec bdemo-mysql mysql -uroot -pbdemo_root -e "DROP DATABASE IF EXISTS codex_act4_fixture_test; CREATE DATABASE codex_act4_fixture_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; GRANT ALL PRIVILEGES ON codex_act4_fixture_test.* TO 'demo'@'%';"
ACT4_TEST_DB=codex_act4_fixture_test PYTHONPATH=backend backend/.venv/bin/python -m unittest datagen.tests.test_act4_fixtures -v
docker exec bdemo-mysql mysql -uroot -pbdemo_root -e "DROP DATABASE IF EXISTS codex_act4_fixture_test;"
```

Expected: PASS；临时库删除；共享 `b_demo` 未改。

- [ ] **Step 6: 主控提交**

```bash
git add datagen/ddl/V001__energy_domain.sql datagen/generate_demo_data.py datagen/tests/test_act4_schema.py datagen/tests/test_act4_fixtures.py
git commit -m "feat(act4): seed suggestion history from raw inputs"
```

---

### Task 3: 后端模型、列表、详情与模板

**Owner:** backend-dev

**Files:**
- Modify: `backend/module_energy/entity/do/suggestion_do.py`
- Create: `backend/module_energy/entity/do/suggestion_template_do.py`
- Create: `backend/module_energy/entity/do/suggestion_flow_log_do.py`
- Create: `backend/module_energy/entity/do/suggestion_verification_do.py`
- Create: `backend/module_energy/entity/vo/suggestion_vo.py`
- Create: `backend/module_energy/service/suggestion_service.py`
- Create: `backend/module_energy/controller/suggestion_controller.py`
- Create: `backend/tests/test_act4_suggestion_contract.py`

**Interfaces:**
- Produces: list/detail、template GET/POST/PUT、manual POST。

- [ ] **Step 1: 写路由/权限/序列化失败测试**

用 `create_app().routes` 断言契约 §5 端点、camelCase、`energy:alert:suggestion` 菜单权限和角色守卫；断言系统管理员无业务写权限。

- [ ] **Step 2: 运行并确认红灯**

```bash
cd backend
.venv/bin/python -m unittest tests.test_act4_suggestion_contract -v
```

Expected: FAIL，路由/模型不存在。

- [ ] **Step 3: 映射 ORM/VO**

字段逐项对应 DDL 与 mock-contracts §5.2；JSON 列在 API 边界解析为对象，响应统一 `{code,msg,data}`。

- [ ] **Step 4: 实现列表与详情**

列表用 SQL count+offset/limit，默认 `priority_score DESC,suggestion_id ASC`；boardCounts 真查库，deferred 不进入五列。详情按 `flow_id ASC` 返回来源/模板快照、priority、verificationHistory、closeInfo 和服务端计算的 allowedActions。

- [ ] **Step 5: 实现模板与人工创建**

模板变更生成新版本且不回写历史实例。人工创建强制 manual、无告警关联，并要求来源说明、对象、措施与五因子。

- [ ] **Step 6: 验证并提交**

```bash
cd backend
.venv/bin/python -m unittest tests.test_act4_suggestion_contract -v
.venv/bin/python -m ruff check module_energy/entity module_energy/service/suggestion_service.py module_energy/controller/suggestion_controller.py
cd ..
git add backend/module_energy/entity backend/module_energy/service/suggestion_service.py backend/module_energy/controller/suggestion_controller.py backend/tests/test_act4_suggestion_contract.py
git commit -m "feat(act4): add suggestion queries and templates"
```

Expected: tests PASS，ruff 无输出。

---

### Task 4: R06 幂等转换与真实优先级

**Owner:** backend-dev

**Files:**
- Modify: `backend/module_energy/controller/alert_controller.py`
- Modify: `backend/module_energy/service/alert_service.py`
- Modify: `backend/module_energy/service/suggestion_service.py`
- Modify: `backend/tests/test_act4_suggestion_contract.py`

**Interfaces:**
- Produces: `POST /alerts/{eventId}/suggestions`、告警详情的 canConvert/relatedSuggestion。

- [ ] **Step 1: 写失败测试**

覆盖：R06 默认模板回退；重复调用返回同一 ID/created=false；eventId 改变但稳定字段相同则指纹不变；R08 无适用模板、canConvert=false，且实现源码无 `if rule_code == "R08"`。

- [ ] **Step 2: 实现稳定来源指纹**

canonical payload 仅含 `ruleCode,ruleVersion,objectType,objectId,areaId,firstOccurredAt,lastOccurredAt,occurCount,thresholdSnapshot`；SHA-256 全长，不含 eventId。事务内行锁并依赖 `uk_source_template` 幂等。

- [ ] **Step 3: 调用共享优先级**

查询 AF-B-MAIN reference+observation raw、压缩空气单价与模板 35/70；调用 Task 1，冻结五因子、权重、综合分、版本和依据。

- [ ] **Step 4: 冻结来源/模板**

保存 sourceSnapshot/templateSnapshot/triggerBasis。bootstrap 后按指纹恢复深链；当前告警缺失时仍展示冻结快照。

- [ ] **Step 5: 验证并提交**

```bash
cd backend
.venv/bin/python -m unittest tests.test_act4_suggestion_contract.Act4ConversionTest -v
cd ..
git add backend/module_energy/controller/alert_controller.py backend/module_energy/service/alert_service.py backend/module_energy/service/suggestion_service.py backend/tests/test_act4_suggestion_contract.py
git commit -m "feat(act4): convert R06 alerts idempotently"
```

Expected: PASS；第二次调用不增加 suggestion/flow/verification。

---

### Task 5: 状态机、执行记录、验证、关闭与复盘

**Owner:** backend-dev

**Files:**
- Modify: `backend/module_energy/entity/vo/suggestion_vo.py`
- Modify: `backend/module_energy/controller/suggestion_controller.py`
- Modify: `backend/module_energy/service/suggestion_service.py`
- Create: `backend/tests/test_act4_suggestion_workflow.py`

**Interfaces:**
- Produces: transition、activities、verification/generate、retrospective。

- [ ] **Step 1: 写状态机和事务失败测试**

覆盖所有合法路径、非法 409、延期/恢复、同一 DEMO_NOW 下 flowId 顺序，以及失败后主表/flow/verification 行数均不变。

- [ ] **Step 2: 实现行锁状态机**

只允许设计 §4 的迁移；每次写 `SELECT ... FOR UPDATE`；主表更新与 flow 追加同事务。activities 追加 `fromStatus=toStatus=currentStatus` 证据，不修改审批/验证结论。

- [ ] **Step 3: 服务端硬校验三档关闭**

- implemented：仅 verifying；存在当前 verification；savingValue 或 effectSummary 至少一项；至少一个附件；effective→valid_closed，ineffective→invalid_closed。
- rejected：请求显式含专用 `responsibleUser + rejectionReason`；不得复用 assignedTo/当前操作人。
- archived_invalid：必填 invalidCategory + 可读关闭原因。

- [ ] **Step 4: 实现权限**

energy_mgr 负责模板/创建/转换/分派/验证/延期/关闭；ops 仅查看分派给自己的建议并调用 activities，服务端同时校验责任账号；admin、调度、财务不能写建议。

- [ ] **Step 5: 实现同步 R06 验证**

verification/generate 直接查 raw/tariff/work_order/quality 并调用 Task 1，不注册 APScheduler。重新生成追加版本；被关闭引用的快照不可覆盖。

- [ ] **Step 6: 实现复盘**

返回 implemented=2、effective=1、ineffective=1、effectiveRate=50.00、duplicate=1、deferred=1；优化提示由固定规则生成并可反查分类，禁止 AI 文案。

- [ ] **Step 7: 写控制禁令扫描**

扫描 suggestion controller/service/VO，禁止 `controlCommand,startStop,powerSetpoint,setValue,executeDeviceAction,remoteValve,autoControl`。

- [ ] **Step 8: 验证并提交**

```bash
cd backend
.venv/bin/python -m unittest tests.test_act4_suggestion_workflow -v
.venv/bin/python -m unittest discover -s tests -p 'test_*.py' -v
.venv/bin/python -m ruff check module_energy tests
cd ..
git add backend/module_energy backend/tests/test_act4_suggestion_workflow.py
git commit -m "feat(act4): enforce suggestion workflow closure"
```

Expected: all PASS。

---

### Task 6: 第四幕页面与告警深链

**Owner:** frontend-dev

**Files:**
- Create: `web/src/api/suggestions.js`
- Create: `web/src/views/energy/shared/act4.js`
- Create: `web/src/views/energy/alert/suggestion.vue`
- Modify: `web/src/views/energy/alert/list.vue`
- Create: `web/test/act4-frontend.test.js`

**Interfaces:**
- Consumes: mock-contracts §5；allowedActions 是权限/状态事实源。
- Produces: 五列看板、延期过滤、详情抽屉、三档表单、四维图与 R06 深链。

- [ ] **Step 1: 写纯函数失败测试**

测试 `boardColumn(status)` 七态映射五列且 deferred 返回 null；`sortFlows` 只按 flowId；`validateClose/buildTransitionPayload` 三档校验和切档字段裁剪；`canConvertAlert` 只读后端布尔值；`stripOneShotQuery` 清理 create/action。

- [ ] **Step 2: 运行并确认红灯**

```bash
node --test web/test/act4-frontend.test.js
```

Expected: FAIL，act4.js 与页面不存在。

- [ ] **Step 3: 实现 API/看板/延期**

列表、boardCounts、排序全部使用后端响应，列内禁止重排。deferred 只在状态过滤结果中展示，不增加第六列。

- [ ] **Step 4: 实现详情抽屉**

沿用第三幕抽屉，通过 `suggestionId` query 恢复。展示来源/模板、措施/责任、flowId 时间线、四维验证、关闭归档；覆盖 loading/empty/insufficient/404/403/409/422/timeout 重试。

- [ ] **Step 5: 实现三档和权限**

前端校验只改善体验；服务端错误保留表单。按钮完全按 allowedActions。运维文案只写“录入人工执行记录”。

- [ ] **Step 6: 接入告警详情**

canConvert=true 显示“转为节能建议”；relatedSuggestionId 存在则显示“查看关联建议”。创建成功跳建议页并清理一次性 query，刷新不重复 POST。R08 后端 false，前端无规则号分支。

- [ ] **Step 7: 实现四维 ECharts**

用量/成本/作业量三线直接消费 verification series，质量展示覆盖率/等级；insufficient 时图表灰态且不显示确定性结论。

- [ ] **Step 8: 验证并提交**

```bash
node --test web/test/act4-frontend.test.js
node --test web/test/act3-frontend.test.js
npm --prefix web run build:prod
git add web/src/api/suggestions.js web/src/views/energy/shared/act4.js web/src/views/energy/alert/suggestion.vue web/src/views/energy/alert/list.vue web/test/act4-frontend.test.js
git commit -m "feat(act4): build suggestion closure page"
```

Expected: tests PASS，Vite build 成功。

---

### Task 7: reset/bootstrap、联调与演示 QA

**Owner:** 主控协调三目录 owner

**Files:**
- Modify: `docs/ui-req-anchor-audit.md`（主控追加第四幕锚点）
- Modify only if a new pitfall is found: `docs/dev-pitfalls.md`

- [ ] **Step 1: reset 后查库**

```bash
python datagen/generate_demo_data.py --reset
docker exec bdemo-mysql mysql --default-character-set=utf8mb4 -udemo -pbdemo_dev b_demo -e "SELECT source_type,COUNT(*) FROM e_suggestion GROUP BY source_type; SELECT status,COUNT(*) FROM e_suggestion GROUP BY status; SELECT COUNT(*) flow_count FROM e_suggestion_flow_log; SELECT COUNT(*) verification_count FROM e_suggestion_verification;"
```

Expected: manual=8、rule=0、flow=25、verification=3，七态计数 `1/1/1/1/1/2/1`。

- [ ] **Step 2: bootstrap 不改建议域**

调用 `POST /pipeline/bootstrap?force_republish=true` 后再次查库：建议域不变；R06 真实告警 1 条；R08 合并告警 1 条（首次 07-03 00:00、最近 07-07 23:00、16 次）。

- [ ] **Step 3: 验证 R06 转换幂等**

第一次后总建议=9、rule=1、flow=26；第二次返回同一 suggestionId 且计数不变。详情展示来源快照与 `PRIORITY-R06-V1` 五因子。

- [ ] **Step 4: 走完整状态机和三档负例**

energy_mgr 分派给 ops_user；ops 补执行记录；energy_mgr 生成验证。implemented 缺附件、rejected 缺专用责任人、archived_invalid 缺分类均返回 422 且不落库；合法关闭成功。

- [ ] **Step 5: 走角色与控制边界**

admin/调度/财务不能写；ops 不能关闭或生成结论；源码/页面无控制字段；R08 详情无转换动作。

- [ ] **Step 6: 浏览器视觉 QA**

走查五列、延期过滤、同时间戳 flow 顺序、URL 恢复、四维图、insufficient 灰态、三档表单、五角色按钮、空态和错误态，按 PRD §5.8 留截图证据。

- [ ] **Step 7: 全量回归**

```bash
PYTHONPATH=backend backend/.venv/bin/python -m unittest discover -s datagen/tests -p 'test_*.py' -v
cd backend
.venv/bin/python -m unittest discover -s tests -p 'test_*.py' -v
.venv/bin/python -m ruff check module_energy tests
cd ..
node --test web/test/*.test.js
npm --prefix web run build:prod
```

Expected: all PASS，无新增 warning。

- [ ] **Step 8: 主控提交文档锚点**

```bash
git add docs/ui-req-anchor-audit.md
git commit -m "docs(act4): record suggestion page REQ anchors"
```

## 并行执行边界

1. Task 1 必须先完成并绿灯。
2. Task 1 后，Task 2（datagen）与 Task 3～5（backend）并行，双方不得修改对方目录。
3. Task 6 在 Task 3 响应模型冻结后并行；联调以 Task 4～5 真实 API 为准。
4. Task 7 只在三个目录各自测试通过后开始。

## Plan Self-Review

- 规格覆盖：REQ-045～050、REQ-091/096、K.6、reset/bootstrap、R08 零建议、25/3 fixture、三档、五因子和四维验证均有任务/测试。
- 占位扫描：所有公式、阈值、窗口、函数名、路径和命令均已明确。
- 类型一致：datagen/backend 共用 Task 1 dataclass；API camelCase、数据库 snake_case；responsibleUser 不与 assignedTo 混用。
- 数据时序：历史快照只读 raw/tariff/work_order/quality，不依赖 bootstrap。
- 数值一致：单一 Decimal 实现，回读复算容差 0.01；不符即 reset 失败，禁止硬编码。
