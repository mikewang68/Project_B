# 遗留问题台账（主控维护）

> 性质：qa 审查发现、经主控裁决**暂缓修复**的已知问题登记。不是需求文档，不是坑点底账（那是 `dev-pitfalls.md`）。
> 规则：每条记 来源 / 位置 / 影响 / 暂缓理由 / 处理窗口。修掉后移入"已结"段并注明提交号。

## 待办（2026-07-14 第三幕阻塞修复批 qa 审查沉淀）

### KI-01 告警列表假分页
- **位置**：`backend/module_energy/service/alert_service.py` `list_alerts`（拉全量→内存切片）
- **影响**：demo 约 20 条告警无感知；数据量涨到 50+ 后性能与 `pageNum/pageSize` 语义双别扭。
- **暂缓理由**：demo 数据口径固定（INJ-01–08 产出量级小），演示不触发。
- **处理窗口**：第四幕开发顺手改 SQL `offset/limit` + 独立 `count(*)`。

### KI-02 通知渠道仅站内一条（REQ-043 简化）
- **位置**：`backend/module_energy/service/rule_engine_service.py` `build_rule_notifications`
- **影响**：REQ-043 要求覆盖站内/邮件/短信/企微/钉钉可选启用；当前只产 `in_app`，连"其他渠道未启用"占位都没有，演示通知配置时无据可示。
- **暂缓理由**：属演示范围裁剪，需先在 `mock-contracts.md` 登记简化裁决再实现占位结构。
- **处理窗口**：第四幕前补多渠道占位（成功/未启用两态）或正式登记裁决。

### KI-03 画像卡片 abnormalCount 不按能源类型过滤
- **位置**：`backend/module_energy/service/equipment_profile_service.py` 卡片墙 SQL（abnormal_count 子查询）
- **影响**：energyType 切"水"时电类告警（如 R05）仍计入卡片异常数，客户细看会质疑口径。
- **暂缓理由**：当前 seed 配方下不明显，REQ-034 未硬性要求隔离。
- **处理窗口**：第四/五幕涉及画像页复用时加 rule_category 或能源类型过滤。

### KI-04 班次工作量单位互相覆盖
- **位置**：`backend/module_energy/service/equipment_profile_service.py` `_shift_comparison`（`buckets[shift]['unit']` 末单胜出）
- **影响**：同班次混合货类（吨/箱）时单位显示错。当前 seed 数据不混合，未暴露。
- **暂缓理由**：数据配方不触发；改动需定"混合"展示口径。
- **处理窗口**：数据配方引入混合货类前必须先修（至少"混合"兜底）。

### KI-05 规则编号筛选为自由文本
- **位置**：`web/src/views/energy/alert/list.vue` 规则编号筛选框 vs 后端 `pattern=r'^R(0[1-9]|1[01])$'`
- **影响**：用户输 `R1`/小写/带前缀即 422。演示脚本不输错，但客户随手操作会翻车。
- **暂缓理由**：体验优化级，演示走脚本可控。
- **处理窗口**：第四幕前端批改成 el-select 枚举 R01–R11。

### KI-06 压缩空气基线待点亮（可选加分项，非欠账）
- **位置**：`datagen/generate_demo_data.py` 基线草稿 seed 段（FX-13 后 `baseline_service` 已介质无关）
- **内容**：补一条 `BASELINE-SYSTEM-AIR-2026` 草稿行即可让总览气口径基线偏差卡自动点亮；基线期（05-04~06-28）在 INJ-04 泄漏之前是干净的，报告期偏差会自然讲出第四幕泄漏→修复剧情，与 R06/建议闭环互相佐证。水基线永久"待建"（量级小、σ 大，演示负资产）。R09 保持只判电力，气偏差不触发 R09 是自洽口径（气有专属 R06）。
- **暂缓理由**：2026-07-15 用户裁决——REQ-029 本为按介质逐个配置能力，"不适用"灰态合规自洽（QA-#23），现在补多一个维护口径。
- **处理窗口**：第五幕收口后、8 月彩排走完整场时，若压缩空气段需要更多视觉证据再点亮（改动约十分钟：一行 seed + 复位验证）。

### ~~存量 wall-clock（P-09 旧账）~~ / ~~PROFILE-PEER-8W 签名口径不符~~
- 2026-07-14 裁决为"演示前必修"，不入台账，走修复批处理（见当日修复提交）。

### KI-07 旧 schema 库 + 常驻 backend 的 scheduler 报错窗口
- **来源**：2026-07-16 收口后全面 debug，qa 日志排查（`backend/logs/2026/07/16/error.log` 01:44–05:18 四次）
- **位置**：`backend/module_energy/service/rule_engine_service.py:748/762/788/798`（R10 查询 `is_current` 列）、`backend/module_energy/dao/cost_dao.py:248/266`（`normalized_object_id`）
- **影响**：数据库停留在 V001（未跑 V002 成本版本化迁移）而 backend 已是第五幕代码时，APScheduler 定时任务每轮报 `OperationalError 1054` 洗版 error.log，R10/成本任务不产出；跑完 reset+bootstrap 后自愈（08:50 起 scheduler 全绿已确认）。
- **暂缓理由**：正常操作流程（reset → bootstrap）不会进入该状态，仅"忘 reset 的旧库 + 新代码常驻"才触发；加 schema 快速失败校验属演示健壮性增强，非功能缺陷。
- **处理窗口**：彩排前若要加保险，在 bootstrap/启动时对 `e_cost_record.is_current` 做缺列校验，缺列即明确报错提示先跑迁移；演示前一晚按流程 reset 即可规避。
