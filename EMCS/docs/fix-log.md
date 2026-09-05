# 修复登记簿（全队共享）

> 性质：已修复 bug 的登记底账，记"修了什么、影响哪些功能操作、怎么验证的"。区别于 `known-issues.md`（暂缓修复台账）与 `dev-pitfalls.md`（坑→规避经验）。
> **所有权例外（2026-07-15 裁决）**：本文件是 `docs/` 中唯一允许所有开发 agent（Claude Code / Codex，backend-dev / frontend-dev / data-engineer）直接**追加**写入的文件。只追加新条目，不改写既有条目；主控提交前审核，统一 commit。
> 登记时机：每个修复批完成、测试跑绿之后随批登记。编号 FX-NN 顺延；`提交` 字段登记时未提交先写"待提交"，主控提交后回填 hash。
>
> 条目格式：
> ```
> ### FX-NN 批次标题
> - 日期 / 执行者：YYYY-MM-DD / <agent 名或主控>
> - 提交：<hash 或 待提交>
> - 修复项：bug 描述 + 位置（file:line）；一批多项逐条列
> - 影响的功能操作：用户可感知的操作面（哪个页面、哪个按钮、哪条演示动线）
> - 验证：测试命令 + 结果
> - 关联：REQ-xxx / P-xx / KI-xx / 审查来源
> ```

---

### FX-01 QA 评审修复批——品牌清理与规则编号校准
- 日期 / 执行者：2026-07-14 / frontend-dev
- 提交：`f018bed`
- 修复项：
  - 阻断：顶栏 RuoYi 外链组件（gitee/文档站直跳）残留，删除 `web/src/components/RuoYi/{Doc,Git}/` 与 Navbar 挂载；
  - 阻断：总览 mock 告警规则 ID×级别与 PRD §7.2 demo 表不符，全面重校（R09/R06/R05/R01/R02/R11 含阈值与文案口径，`web/src/views/dashboard/mock.js`）；
  - 建议项：REQ 锚点纠错（REQ-060 刷新 / REQ-024/025 聚合 / 页脚）；骨架残骸删除（表单生成器 `web/src/utils/generator/` 约 1400 行、druid 死页）；品牌素材中性化（自制 logo.svg、登录页 CSS 渐变替换静物照、package.json 元数据）。
- 影响的功能操作：总览页告警滚动区的规则编号/级别显示；顶栏（不再有外部跳转）；登录页视觉；全站品牌观感。
- 验证：qa-reviewer 评审复核通过（本批即按其评审意见修复）。
- 关联：P-05（规则编号）、P-11（品牌清理）、许可证纪律（MIT 版权头保留）。

### FX-02 用户走查修复批——8 项真数据呈现问题
- 日期 / 执行者：2026-07-14 / backend-dev + frontend-dev
- 提交：`0e41ff7`
- 修复项：
  - backend：小时负荷/KPI 水、气值被 int 取整归零 → 按能源类型保留 2 位小数（`overview_service.py`）；readings 增补 statusValue 状态点值通道（契约 §2.1）；Top5 增能耗口径 `topObjectsByEnergy`（REQ-053 双定义）；非电基线卡显示"不适用+仅电力口径"；空数据占位去内部黑话。
  - web：raw-quality 主曲线累计表读数画成平线 → 改画增量；状态点色带渲染（运行/待机/停机/维护，INJ-03 待机段可视）；能源类型筛选原为死开关 → 本地接线+失配自愈；Top5 成本/能耗切换接线；趋势图单位跟随能源类型；移除 PRD 外自造时间筛选组（timeRange 契约标废弃）；清除 mock 硬编码统计数字（告警角标/计量点数/COV 徽标）。
- 影响的功能操作：总览页 KPI 卡与 24h 趋势（水/气切换）、Top5 排行切换；第二幕原始数据页主曲线形态、状态色带、能源类型筛选。
- 验证：用户走查逐项复核通过。
- 关联：REQ-053/057~062、REQ-010~019、P-13（无锚自造）、mock-contracts §1/§2.1 裁决。

### FX-03 C 类清理批——无锚自造项与 mock 残留
- 日期 / 执行者：2026-07-14 / backend-dev + frontend-dev
- 提交：`86bf010`
- 修复项（qa#25 审计 C1/C2/C4/C5/C6）：
  - C1：KPI spark 迷你曲线为 PRD 无锚字段且后端从未产出 → 契约+VO+装配+UI+mock 全链移除；
  - C2：趋势图 NOW 游标硬编码 'NOW 06:12' → 绑 `trend.nowIndex` 真值；
  - C4：页脚品牌标语删除；
  - C5：补传弹窗目标点位写死 A 区 4 电表 → 动态绑当前选中点位；
  - C6：受影响周期写死日期 → 从缺测窗派生。
  - 附产出 `docs/ui-req-anchor-audit.md`（UI 元素 PRD 锚点三分类底账）。
- 影响的功能操作：总览页 KPI 卡形态与 NOW 游标；第二幕补传弹窗的目标点位与受影响周期显示；页脚。
- 验证：qa#25 审计闭环复核。
- 关联：P-13、mock-contracts §1（spark 移除注记）。

### FX-04 第二幕补传请求窗口修复
- 日期 / 执行者：2026-07-14 / frontend-dev
- 提交：`84f562d`
- 修复项：补传请求的 `[cacheStart, cacheEnd)` 窗口构造错误 → 新增 `web/src/views/raw-quality/backfillWindow.js` `deriveBackfillWindow`：从当前连续缺测槽派生窗口，cacheEnd 为末个缺测点 + 采样间隔（右开区间），替换 `index.vue` 内原构造逻辑。
- 影响的功能操作：第二幕质量页"补传"操作——补传窗口与实际缺测段对齐，后端按窗补数不再漏末槽。
- 验证：新增 `web/test/raw-quality-backfill-window.test.js` 3/3 通过。
- 关联：REQ-014。

### FX-05 第三幕阻塞批——告警流转与画像联动收口
- 日期 / 执行者：2026-07-14 / backend-dev + frontend-dev（qa 双审 + 修复复查"可合入"）
- 提交：`e6008ab`
- 修复项：
  - B1 `web/src/views/energy/alert/list.vue` 缺 `import { ElMessage }` → 处置按钮点击即 ReferenceError、loading 永久卡死；补导入。
  - B2 非关闭类流转透传 `closeType: ''` 空串 → 后端 pydantic 422，确认/派发/处理中/升级全失败；新增 `web/src/views/energy/shared/act3.js` `buildTransitionPayload` 按目标状态裁剪 payload。
  - B3 `backend/module_energy/service/alert_service.py:191` 处置时间用 `datetime.now()` 违反 P-09 → "平均处理时长"冒 40–100+ 小时、时间线假时序；改 `await get_demo_now(db)`（rule_engine 审计字段同步）。
  - B4 `rule_engine_service.py` `run_all` 复位不清 `e_alert_flow_log` 违反 P-17 → 复位重演后新告警冒出上一轮处置记录；起手先删子表。
  - B5 画像 `eventId` 不在默认 7 天窗内硬 404 + 区域类告警也可跳画像 → 后端归属校验+自动扩窗（`widen_period_for_event` 贯穿全部下游查询）、前端 `canJumpToProfile` 守卫+按钮禁用。
- 影响的功能操作：告警详情抽屉"确认/派发/关闭"整套处置操作（REQ-041 卖点动线）；告警列表"平均处理时长"统计与详情时间线；bootstrap 复位重演；告警→设备画像跳转。
- 验证：backend unittest 17/17、web node --test 16/16（含 5 条新增防回归）；qa 修复复查逐项确认"可合入"。
- 关联：REQ-039/040/041、P-09、P-17、第三幕 qa 双 agent 审查。

### FX-06 P-09 存量清理 + 画像签名口径修正
- 日期 / 执行者：2026-07-15 / backend-dev
- 提交：`dfa0a77`
- 修复项：
  - 前两幕遗留 6 处 wall-clock 改锚 DEMO_NOW：`cost_service.py:64` computed_at、`audit_service.py:30` 审计 event_time/create_time、`baseline_service.py:110` published_at、`raw_quality_service.py:214/254/462` dryRun triggeredAt / 补传 ingest 各时间 / 重算 finishedAt。保留 4 处有据 wall-clock（demo_now_util 自身 3 处兜底/注释、批次号微秒后缀唯一性用途）。
  - `equipment_profile_service.py` `_signature` baselineVersion 写死 `PROFILE-PEER-8W` 与实际 7 天查询窗不符 → 动态 `PROFILE-PEER-{窗口天数}D`（亚天向上取整）。
- 影响的功能操作：成本明细"重算时间"、审计时间线、基线"发布时间"、第二幕补传时间线与重算完成时间的日期展示（演示日不再穿帮）；设备画像页口径签名的真实性。
- 验证：backend unittest 18/18（新增签名窗口断言 7 天窗+12h 亚天两口径）。
- 关联：P-09、REQ-062（口径签名卖点）、mock-contracts §3.2 口径注（2026-07-15 裁决）。

### FX-07 第四幕基础域独立审查修复批——快照时序与建议输入边界
- 日期 / 执行者：2026-07-15 / data-engineer + backend-dev（独立审查、主控复核）
- 提交：`3f5b6c9`、`2a76a65`
- 修复项：
  - 历史 H4 验证快照原为 2026-07-08 17:30，早于 18:00 的“进入验证中”流转，审计链无法由状态机产生；将固定生成时间改为 18:30，并新增通用断言，保证快照不早于 `verifying`、H5/H6 关闭不早于快照（`datagen/generate_demo_data.py`、`datagen/tests/test_act4_fixtures.py`）。
  - 建议请求原只检查部分措施字段，`title/sourceDescription/templateName/category` 及若干标准化英文控制词可穿透；改为整份请求递归检查键和值，并覆盖 `controlCommand/startStop/powerSetpoint/setValue/executeDeviceAction/remoteValve/autoControl` 等禁控表达（`backend/module_energy/entity/vo/suggestion_vo.py`）。
  - 人工建议可同时提交 `templateId + templateSnapshot` 并静默忽略后者；改为服务端强制二选一。
  - 人工建议的 `objectId/areaId/equipmentId` 可相互矛盾或引用不存在主数据；新增 area/equipment/point/system 结构校验和真实主数据规范化，客户端重复引用不再作为事实源（`backend/module_energy/service/suggestion_service.py`）。
- 影响的功能操作：第四幕历史建议详情的验证/关闭时间线；人工建议创建；模板新增/更新；建议对象与区域、设备、点位的展示和筛选；全页 REQ-091/096 禁控走查。
- 验证：datagen 隔离库 fixture 8/8、datagen unit 11/11、backend Task3 26/26、backend 全量 60/60、两目录 Ruff 与 `git diff --check` 均通过；临时库自动清理且 `b_demo` checksum 不变；最终独立复审 PASS。
- 关联：REQ-045～050、REQ-091/096、第四幕 data/backend 独立审查。

### FX-08 第四幕写契约与验证闭环审查修复批
- 日期 / 执行者：2026-07-15 / frontend-dev + backend-dev + data-engineer（独立审查、主控复核）
- 提交：`04c7919`、`f265edd`
- 修复项：
  - 前端写接口原漏传或可漏传 `rowVersion`，R06 还会提交客户端验证窗口；改为 transition/activity/verification 三类 payload 都强制并发版本，R06 明示服务端冻结窗口且不提交日期（`web/src/api/suggestions.js`、`web/src/views/energy/shared/act4.js`、`web/src/views/energy/alert/suggestion.vue`）。
  - 前端原将业务错误压成字符串，无法稳定区分 403/404/409/422/超时；新增结构化错误，403 显示权限拦截、404 关闭失效详情、409 刷新版本且保留输入、422/超时原位保留表单与重试入口（`web/src/utils/request.js`、`web/src/utils/requestError.js`）。
  - 实施完成填写节能量时未强制单位；补前后端一致校验。来源告警深链只接受服务端恢复的 `currentAlertEventId`，不再从冻结快照猜当前 ID。
  - transition 可选字符串显式 `null` 会触发 500；validator 先接纳 `None`，省略与显式 null 均按契约处理（`backend/module_energy/entity/vo/suggestion_vo.py`）。
  - 运维归档复盘原可能读取全局统计；改为始终按 `responsible_user` 限域，并补月份溢出 422、A/B 区和显式 R06 过滤回归（`backend/module_energy/service/suggestion_service.py`）。
  - 四维验证原仅用量有日序列，且序列在签名后追加；新增共用 `calculate_daily_metric_series`，用量/成本/作业量/质量四类数值序列全部在签名前冻结，labels/note 仅作展示（`backend/module_energy/domain/suggestion_calculation.py`、`backend/module_energy/service/suggestion_service.py`、`datagen/generate_demo_data.py`）。
  - 工作流集成补 fresh reset/bootstrap 前置断言，避免破坏性用例复用脏隔离库（`backend/tests/test_act4_suggestion_workflow.py`）。
- 影响的功能操作：第四幕 R06 告警转建议后的分派、执行留痕、进入验证、四维曲线、三档关闭、延期/恢复、角色权限错误反馈和归档复盘；历史 H4/H5/H6 快照图表与签名反查。
- 验证：frontend 定向 25/25、全量 44/44、Vite 2463 modules 构建通过；backend 全量 79 passed、2 skipped，两个 fresh 隔离库集成另跑 workflow 13/13、R06 conversion 1/1；datagen 11 passed、1 skipped，fixture 另跑 8/8；目标共享计算/工作流 30 passed、1 skipped；Ruff、`git diff --check` 通过；FX-08 修复范围独立复审 P0/P1/P2=0。
- 关联：REQ-045～050、REQ-091/096、P-17/P-19/P-20/P-21、第四幕 frontend/backend 独立审查。

### FX-09 第四幕 REQ-050 月度归档复盘呈现补齐
- 日期 / 执行者：2026-07-15 / frontend-dev + 主控
- 提交：`d2d8ffd`
- 修复项：归档复盘接口已返回月份与三类关闭数量，但页面未传入月份、也未呈现关闭类型分布；新增月份选择器，按后端回显的默认月份初始化，并展示实施完成、驳回、归档无效三类数量（`web/src/views/energy/alert/suggestion.vue`、`web/test/act4-frontend.test.js`）。
- 影响的功能操作：第四幕归档复盘区的月份切换、建议关闭类型分布、有效率与规则优化建议联动。
- 验证：先新增测试并确认因缺少 `type="month"` 红灯；修复后定向 23/23、web 全量 45/45、Vite 2463 modules 构建通过；fresh reset 的认证 API 复盘口径为 `month=2026-07`、实施完成 2、驳回 0、归档无效 1、有效率 50.0%。
- 关联：REQ-050、第四幕文档审计。

### FX-10 第四幕终审边界与结果呈现修复批
- 日期 / 执行者：2026-07-15 / backend-dev + frontend-dev + 主控
- 提交：`d2d8ffd`
- 修复项：
  - R08 不可转建议原只依赖“没有 R08 模板”的 fixture 状态；新增服务端转换范围守卫，当前只允许已有真算路径的 R06。即使运行期创建并启用 R08 模板，详情仍返回不可转换，写接口返回 422 且不落建议、flow 或 verification（`suggestion_service.py`、`test_act4_suggestion_contract.py`）。
  - 正常有效/无效验证结果未展示 `calculationNote`；改为所有验证状态均显示“节能量计算说明”（`suggestion.vue`）。
  - 规则优化提示直接插值对象导致统计依据显示 `[object Object]`；统一经 `displayValue` 序列化为可读证据。
  - “已关闭”统计卡点击后错误清空状态筛选；新增只用于查询的 `status=closed` 聚合别名，后端映射到 `valid_closed/invalid_closed`，不新增持久化状态。
- 影响的功能操作：告警详情转建议边界；第四幕验证快照说明、归档复盘提示、已关闭统计卡过滤。
- 验证：TDD 红灯先确认 frontend 3 项失败、backend 契约 2 项失败；修复后 frontend 定向 26/26，backend 契约单测 32 passed、1 skipped；fresh 隔离库按真实 bootstrap 顺序插入启用 R08 模板后集成 33/33，R08 转换 422 且无建议落库；最终 backend 81 passed、2 skipped，datagen 11 passed、1 skipped，web 48/48，Vite 2463 modules 构建、Act 4 Ruff 与 `git diff --check` 全部通过。
- 关联：REQ-045/047/048/050、第四幕 R08 范围裁决、最终独立复审。

### FX-11 总览 KPI"待办节能建议"口径修正（第四幕体检发现）
- 日期 / 执行者：2026-07-15 / 主控（体检发现并修复）
- 提交：随第四幕分支合入
- 修复项：
  - 第一幕存量代码把"验证中"计入待办四态（`overview_dao.py` `_OPEN_SUGGESTION_STATUSES` 含 `verifying`），且 DAO docstring 与 service 注释均误引"REQ-045 四态"；PRD §5.1 KPI 表明文待办 = {待审核, 已分派, 执行中} 三态。e_suggestion 恒空时不可见，第四幕 H4（验证中）落库后总览 KPI 显示 4，而卡片分项"待审 1 · 已派 1 · 执行 1"之和为 3，当场自相矛盾。改为三态口径并如实标注 PRD §5.1 来源（`backend/module_energy/dao/overview_dao.py`、`backend/module_energy/service/overview_service.py`）。
  - 新增 `backend/tests/test_act4_overview_kpi.py`：状态集合锁三态、summary 无 verifying 桶、total 恒等于三分项之和；集成用例（ACT4_TEST_DB 门控）在 fresh fixture 下断言权威值 3。
  - 契约 §5.1 补"总览联动口径（FX-11）"与"422 双形状注"（业务校验走骨架包裹、Pydantic 字段级校验为 FastAPI 原生 detail，属骨架全局行为，前端 requestError.js 已兜住，不单独整改）；设计文档 §11 演示 QA 补两条走查须知（H3 现场推进需先补执行记录；总览 KPI 应显示 3 且等于分项之和）。
- 影响的功能操作：总览页（默认首页）KPI 卡"待办节能建议"数值及其与建议看板的对账一致性。
- 验证：Ruff 通过、`git diff --check` 通过；backend 全量 84 passed 0 skipped（含 fresh reset+bootstrap 隔离库集成）；隔离库 fresh 复位后认证 curl 实测总览 KPI value=3、delta="待审 1 · 已派 1 · 执行 1"，与分项之和一致；无前端改动，web 套件不受影响。
- 关联：PRD §5.1 KPI 表、REQ-045/053、第四幕体检发现 #1~#3。

### FX-12 成本管道单档介质取价修复（第四幕体检发现）
- 日期 / 执行者：2026-07-15 / 主控（用户走查发现水/气成本恒 0）
- 提交：随本批合入 main
- 修复项：`cost_service` 三段成本只按 `peak/flat/valley` 键取价，水/压缩空气的单价挂 `tou_period='flat_only'` 永远取不到，成本三段恒 0，且 REQ-062 签名引用的 `flat_only_v1` 版本从未参与计算（签名失实）。新增 `_bucket_prices` 统一取价：单档介质三段回退 `flat_only` 单价（`backend/module_energy/service/cost_service.py`）。第一幕存量缺陷，当时只验证了电力口径。
- 影响的功能操作：总览 KPI"当月累计成本"水/气口径（修后 2026-07 气 0.4 万元、水 0.13 万元）；`e_cost_record` 全部单档介质行；第五幕成本核算页的数据前提。电力峰平谷口径不受影响（锚定 ¥59,501.99 不变）。
- 验证：`_bucket_prices` 单元测试 4 例；隔离库集成断言水/气 total = usage × 单价（容差 0.1）、电力锚定不变；b_demo 复位后认证 curl 实测 WATER 0.13 / AIR 0.4 万元。
- 关联：REQ-051/052/062、PRD §5.1 成本卡、第四幕体检发现。

### FX-13 分区基线真算（废除 area 行复制 system 偏差捷径）
- 日期 / 执行者：2026-07-15 / 主控（用户走查发现 A/B 区基线偏差恒相同）
- 提交：随本批合入 main
- 修复项：
  - 第一幕捷径把 system 基线偏差原样复制进 A/B 区的 `e_stat_day` 行（`baseline_service` 头注明示），导致切区看到的都是系统级偏差。datagen 增补 `BASELINE-AREA-A/B-ELEC-2026` 两条分区基线草稿（同基线期同分桶法）；`baseline_service` 按基线 scope 分别计算 6 桶与报告期偏差、各写各行；`overview` 基线按 zone 取对应 scope，zone=ALL 的偏差改读 system 行（system 偏差 ≠ 区偏差算术平均）。
  - R09 规则只扫 system 行，分区真算不改变告警产出（复位后 R09=3、共 20 条，护栏断言入测试）。
- 影响的功能操作：总览页切 A/B/全部时的基线偏差 KPI 与 subLabel 基线码显示；`e_energy_baseline`（3 条发布）；`e_stat_day` area 行偏差值语义。
- 验证：backend 全量 89 passed 0 skipped（含隔离库集成：三基线发布、07-12 偏差 A -25.34 / B -31.53 / 系统 -27.99 两两互异、R09/告警计数护栏）；b_demo 复位后认证 curl 实测三 zone 偏差分叉且各自显示分区基线码，A+B 成本之和 = ALL（3.52+2.43=5.95 万元）交叉自洽。
- 关联：REQ-029/R09、PRD §5.1 基线偏差卡、第四幕体检发现；水/气"不适用"维持 QA-#23 裁决。

### FX-14 第五幕成本配置与重算接口审查修复批
- 日期 / 执行者：2026-07-16 / backend-dev（Task4 独立审查修复）
- 提交：未提交，随第五幕首批实现合入
- 修复项：
  - 单价/分摊新增改为在 MySQL advisory lock 内关闭唯一可继承的开放版本，再追加新版本；真实交叠返回 422，并发版本分配只允许一个成功，避免版本号重复或覆盖历史行。
  - 成本页权限守卫改为真实 HTTP 403；请求模型只接收 camelCase，重算复核备注限制 255 字，复核重复/版本冲突返回 409。
  - 分摊规则按 ratio/manual 与 weight/workload 分别校验；后两者强制 basisValue 并由服务端归一化 ratio，持久化结果可解释。
  - 重算驳回响应回显旧 currentCostVersion；详情补齐逐对象 traceLinks/traceParams，发起与复核均写入同事务操作审计；flat-only 对外统一 flatOnly。
- 影响的功能操作：第五幕单价版本新增、分摊规则新增、成本重算发起/详情/分页/复核/驳回、运维越权拦截，以及差异记录到旧新成本版本的三步反查。
- 验证：Act5 calculation/consumer/versioning/Task4 共 47 个不同测试全部通过；MySQL 隔离库 DB 门控 consumer 12/12、versioning 8/8、Task4 12/12 均 0 skip；Task4 完整 reset + bootstrap 生命周期重复两轮，锚定每轮 costRows=75、R10=1、告警总数=20，覆盖 2026-06 水价 v2→非零差异→复核/驳回、v1/v2/v3 完整链与并发唯一版本；backend Act5 Ruff、`git diff --check` 通过。
- 关联：REQ-051/052/054/055/062/073/074、INJ-08、第五幕契约成本版本化五条边界。

### FX-15 第五幕成本发现与反查契约审查修复批
- 日期 / 执行者：2026-07-16 / backend-dev（Task5 独立审查修复）
- 提交：随第五幕成本查询实现合入
- 修复项：
  - R10 告警详情深链改为真实前端路由 `/energy/cost/record`，完整携带 `sourceEventId`，避免把 API 路径误作页面地址。
  - 成本反查的可信建议上下文严格收敛为 §6.7 白名单字段，补齐 `areaId/recomputeId/alertEventId`，删除会被后续窄授权模型拒绝的额外字段。
  - 告警统计统一函数去除重复过滤条件，并保持列表筛选统计与月/日窗报表复用同一聚合入口。
  - 补齐 current 默认版本、显式历史版本、重算链、路由守卫、缺失对象 ID 422、深链与建议上下文精确形状测试。
- 影响的功能操作：第五幕财务发现 R10 异常、告警详情带参进入成本现场、成本版本三步反查、成本异常转节能建议，以及后续日报/月报告警段装配。
- 验证：Task5 fresh 隔离库 10/10、第三幕告警回归 18/18，均 0 skip；Act5 查询范围 Ruff 与 `git diff --check` 通过；独立复审 APPROVED。
- 关联：REQ-039/042/051～056/062、PRD §5.9、第五幕默认下钻与窄授权裁决。

### FX-16 第五幕 canonical 报表装配审查修复批
- 日期 / 执行者：2026-07-16 / backend-dev（Task6 独立审查修复）
- 提交：随第五幕报表装配实现合入
- 修复项：
  - 报表日期改为真实日历校验；设备画像强制设备 ID 并校验实际归区，其他模板禁止携带设备筛选，避免五段筛选口径分裂。
  - `COST_DIFF` 改为真实读取重算差异，按区域裁剪对象证据并冻结实际 old/new 成本版本、单价和分摊快照；无差异时诚实返回空集，不再以 current 成本冒充专项。
  - 建议模板版本只保留真正参与复盘统计的关闭/延期建议；峰平谷版本按业务枚举序；2026-05 如实标记 05-04 起部分月并输出质量/计算说明。
  - 真实重算 float 在 canonical 边界统一量化两位，`deltaPct=None` 保持空值，保证同参数 payload 字节与签名稳定。
- 影响的功能操作：能源日报/月报五段式/设备画像/建议复盘/成本差异专项预览，P2 报表订阅灰态，以及后续 Excel/归档共享 payload。
- 验证：fresh 隔离库 report 11/11、Task5 与第三幕回归 28/28，合计 39 项、11 subtests、0 skip；Task6 Ruff、`git diff --check` 通过；三轮独立复审最终 APPROVED。
- 关联：REQ-030/059/061/062、PRD §5.9、第五幕单一快照装配与导出双落位裁决。

### FX-17 第五幕 Excel 与冻结归档审查修复批
- 日期 / 执行者：2026-07-16 / backend-dev（Task7 独立审查修复）
- 提交：随第五幕报表导出与归档实现合入
- 修复项：
  - Excel 每个业务表固定五行顶部块、第六行列头、`A7` 冻结窗格和筛选区，并新增独立口径说明页；生成/修改时间均锚定系统统计时钟。
  - 数据区金额、用量、比率和计数按字段语义写为可求和/透视的 Excel 数值，ID/版本/代码保持原语义；canonical payload 与签名不受呈现转换影响。
  - 正式输出改为成本状态白名单：仅 `reviewed/frozen` 可导出/归档，`draft/pendingReview/pendingRecompute` 返回 409；成本差异 pending 拦截，approved/rejected 可作为冻结证据导出。
  - 归档写入显式提交并兼容生产 `expire_on_commit=True`；详情与再次下载只读冻结 payload，不重放查询；日报和成本差异口径页只引用实际参与版本。
- 影响的功能操作：日报/月报/专项 Excel 导出、报表归档列表/详情/再次下载、签名复核、未复核成本输出拦截和数据透视可用性。
- 验证：fresh 隔离库 report 17/17、Task5 与第三幕回归 28/28，全部 0 skip；Task7 Ruff、`git diff --check` 通过；两轮独立复审最终 APPROVED。
- 关联：REQ-030/059/062、第五幕导出双落位、归档冻结和签名覆盖域裁决。

### FX-18 第五幕成本异常转建议窄授权与并发幂等修复批
- 日期 / 执行者：2026-07-16 / backend-dev（Task8 独立审查修复）
- 提交：随第五幕成本异常转建议实现合入
- 修复项：
  - 财务复用第四幕 `POST /suggestions`，但只接受服务端可信的成本异常定位上下文；能源管理员保留通用建议写权，运维/调度/管理员不新增业务写权。服务端精确重读不可变成本版本，重建成本签名并校验对象归属、重算记录与真实 R10 证据，客户端不能提交金额或版本快照。
  - 冻结成本行、单价、分摊、统计、重算与告警证据，详情返回成本页深链，成本反查 reload 回显 `relatedSuggestionId`；current 与历史成本版本均可作为可信来源。
  - 以精确 `ECostRecord` 行锁作为相同成本来源的跨模板互斥，拿锁后才检查既有建议；两个独立会话并发时只创建一条建议与一条 flow，另一请求稳定返回既有 ID，规避 MySQL `NULL` 唯一键与不同模板载体绕过幂等。
  - Task8 集成测试新增权威 fresh fixture 闸门，并补“冻结金额被篡改但库内旧签名不变”的重建验签回归，防止退化为客户端/数据库签名字符串直比。
- 影响的功能操作：第五幕成本异常三步反查后转节能建议、财务窄授权、建议详情成本来源追溯、成本页关联建议回显，以及相同来源重复/并发提交。
- 验证：Task8 10/10、第四幕建议回归 46/46、成本查询 10/10，全部 0 skip；真实双 session 并发一创一复用且同 ID/单 flow；篡改冻结字段返回 422；Ruff、compileall、`git diff --check` 通过；独立复审最终 APPROVED，测试前后权威夹具均为 cost/current 75/75、diff/archive 0/0、suggestion/flow/verification 8/25/3、alert 20、R10=1。
- 关联：REQ-045～050/051～056/062、PRD §5.9、K.6 第五幕财务窄授权修订、P-20/P-21。

### FX-19 第五幕全链路复位与跨测试污染修复批
- 日期 / 执行者：2026-07-16 / backend-dev + 主控（Task9 全回归闸门）
- 提交：随第五幕后端全链路验证合入
- 修复项：
  - 新增真实破坏性全链路：fresh v1 → 新单价 → 2026-06 v2 重算 → 三个 current-only 消费方 → 财务复核 → R10 下钻与三步反查 → 月报预览/Excel/冻结归档 → 成本异常转建议 → reset/bootstrap 恢复 v1，并对 fresh 报表签名做确定性回归。
  - 补齐 `/cost/month/summary`、`/cost/month/top` 两个兼容接口的 v2 金额/版本实测，连同 Overview 与 R10 规则引擎锁死三处消费方不双计、不漂移。
  - 归档验证从“只比签名”提升为同时比较冻结 `payloadSnapshot/versionSnapshots`、v1 成本明细，并检查重下载 Excel 的金额与 `currentCostVersion`；防止旧签名下错误重查 v2 数据。
  - 全量 discover 暴露的旧破坏性测试污染已精确回收：第四幕建议流程完整复位历史夹具；current-only 测试删除自身历史成本；成本配置/版本测试恢复权威 v1、单价、分摊、差异与审计状态。失败用例不再遗留事务阻塞后续 schema reset。
- 影响的功能操作：第五幕从成本发现到建议闭环的全部后端链路；前四幕与第五幕共用隔离库回归；reset 后重复演确定性；归档旧口径证据的可复核性。
- 验证：主控串行 workflow 3/3；统一 `ACT4_TEST_DB=ACT5_TEST_DB=codex_act5_backend_test` 全后端 176/176，硬解析 skip/fail/error=0/0/0；Ruff、compileall、`git diff --check` 通过；最终 SQL 为 cost/current 75/75、diff/archive 0/0、suggestion/flow/verification 8/25/3、alerts 20/R10=1、current 唯一性异常 0，电费 05/06/07=138014.50/161920.03/59501.99、水=1313.12、气=4030.33；独立复审最终 APPROVED。
- 关联：REQ-030/051～056/059/062/073/074、INJ-06、P-20/P-21、第五幕成本版本化与单一快照裁决。

### FX-20 第五幕成本与报表前端独立复审修复批
- 日期 / 执行者：2026-07-16 / frontend-dev + 主控（第五幕前端独立复审）
- 提交：随第五幕前端实现合入
- 修复项：
  - 重算列表展开时改读冻结详情，完整展示 `diffSummary/traceLinks`，并分别支持旧、新 `costVersion` 三步反查，避免列表轻量响应造成“重算版本+差异”空壳。
  - 报表预览同时展示段级聚合、明细与对象级成本差异，告警统计、成本复核状态及 `COST_DIFF` 证据不再因列裁剪丢失。
  - 显式业务权限矩阵覆盖骨架超级管理员旁路：管理员保持只读；能源管理员与财务仅保留 K.6 对应的单价、分摊、重算、复核和成本转建议窄入口。
  - 成本页监听同路由查询变化并用请求序号丢弃过期响应，保证告警深链、异常下钻与浏览器前进/后退始终刷新到正确现场。
  - 分摊规则表补齐冻结配置、起止有效期及影响表计展示；历史成本第三步无实际分摊时继续如实展示服务端空态，不伪造比例。
- 影响的功能操作：第五幕成本默认发现与 R10 带参下钻、峰平谷定位、旧新版本三步反查、成本配置与财务复核、成本异常转建议、五类报表预览/导出/冻结归档，以及管理员只读权限。
- 验证：主控与独立复审均为 focused 15/15、前端全量 63/63、0 skip；Vite production build 成功，`git diff --check` 通过；独立复审最终 APPROVED。
- 关联：REQ-030/045/051～056/059/061/062/073/074、INJ-06/08、第五幕默认下钻、K.6 窄授权、单一快照与导出双落位裁决。

### FX-21 第五幕生产会话 bootstrap 过期读取修复批
- 日期 / 执行者：2026-07-16 / 主控（共享演示库联调）
- 提交：随第五幕联调收口合入
- 修复项：
  - 共享 `b_demo` 首次真实 bootstrap 暴露 `AsyncSessionLocal(expire_on_commit=True)` 差异：`CostService.initialize_v1` 提交后再读取已过期 ORM 成本行统计对象数，触发异步 `MissingGreenlet`，导致成本已提交但规则阶段未执行。
  - 对象类型计数改在最终提交前从待写入行冻结，提交后只读取普通 Python 容器；不改变成本金额、签名、版本或事务边界。
  - 新增 production-expiry 集成回归，明确使用 `expire_on_commit=True`，锁死 v1 初始化返回 75 行及 equipment/area/system=48/18/9。
- 影响的功能操作：`POST /pipeline/bootstrap`、首启自动 bootstrap、reset 后第五幕 v1 重建与 R10 恢复；成本查询、重算与报表口径不变。
- 验证：回归测试先稳定复现 `MissingGreenlet`，修复后定向 1/1；统一 `ACT4_TEST_DB=ACT5_TEST_DB=codex_act5_backend_test` 后端全量 177/177、0 skip，Ruff/compileall/`git diff --check` 通过；共享库第二次 clean reset/bootstrap 成功，cost/current=75/75、diff/archive=0/0、alerts=20、R10=1、current 异常=0，电费 05/06/07=138014.50/161920.03/59501.99。
- 关联：REQ-051/052/055/062、P-09/P-21、第五幕复位确定性与 current-only 消费方红线。

### FX-22 第五幕收口复查与共享库联调实测补记
- 日期 / 执行者：2026-07-16 / 主控 + qa-reviewer（收口独立复查，非修复批，登记联调证据）
- 提交：随第五幕收口 docs 批合入（本条目所在提交）
- 修复项：无代码改动。两项收口动作：
  - qa-reviewer 只读独立复查第五幕 13 提交（e80736f..366c296）：后端全量 177 passed + 141 subtests（0 skip/fail/error）、前端 63/63、build:prod 成功；REQ 锚定（045~056/059/061/062/073/074 均核对在 demo 范围）、数据安全、许可证纪律、目录边界全 PASS。
  - 主控在共享 `b_demo` 实测一轮 `generate_demo_data.py --reset`（8.4s，seed=20260713）→ curl 鉴权 → `POST /pipeline/bootstrap?force_republish=true`，补齐此前只有隔离库证据的复位确定性验证。
- 影响的功能操作：共享演示库五幕全量可复位性；第五幕成本/报表演示动线的口径基线。
- 验证（共享 b_demo 实测输出）：
  - SQL：cost_record 75/75（current，equipment/area/system=48/18/9）、recompute/archive=0/0、alerts=20、R10=1、current 唯一性重复=0、suggestion/flow/verification=8/25/3。
  - 系统级金额：电 2026-05/06/07=138014.5009/161920.0311/59501.9928，2026-07 水=1313.1216、气=4030.3283。
  - API `/cost/month/summary`（admin token）：三月电费与 SQL 逐项一致，水=1313.1216、气=4030.3282（气第 4 位小数为序列化浮点差，两位口径一致 4030.33），currentCostVersion 均为 v1。
  - 与 FX-19/FX-21 声称口径逐项吻合。
- 随批收口：dev-pitfalls.md 补录 P-24（破坏性测试失败遗留污染/事务阻塞 reset，源自 FX-19）、P-25（隔离库配置掩盖生产 expire_on_commit=True 差异，源自 FX-21）；补打 act5-done（366c296）与历史缺失的 act3-done（dfa0a77）tag。
- 关联：REQ-051~056/059/062、FX-19/FX-21、P-24/P-25、qa-reviewer 收口复查结论。

### FX-23 报表页 admin 权限空档收口
- 日期 / 执行者：2026-07-16 / frontend-dev
- 提交：待提交，主控统一 commit
- 修复项：
  - 契约漏洞：契约 §6.8 报表预览/导出/归档仅 finance / energy_mgr；后端已用 `CostAccessGuard(allow_admin=False)` 锁死，但前端 `web/src/views/energy/cost/report.vue` 无角色守卫，admin 进入页面即触发 `onMounted(loadTemplates)` 与 `ReportArchiveTable` 自挂载的 `/reports/templates`、`/reports/archives`，连吃 2 个 403，并向 `e_audit_security` 写 unauthorized_access 污染安全审计演示数据。
  - `web/src/views/energy/shared/act5.js`：`costBusinessAccess()` 追加 `report: finance || manager` 能力项（`readOnlyAdmin` 场景下自动落 false，与契约对齐）；显式注释指向契约 §6.8。
  - `web/src/views/energy/cost/report.vue`：引入 `useUserStore` 与 `costBusinessAccess`，`onMounted` 挂载守卫改为 `if(access.value.report)loadTemplates()`；页面根节点 `v-if="!access.report"` 渲染 `el-empty` 空态（"报表中心仅财务与能源管理员可用（K.6 窄授权 · 契约 §6.8）" + 返回成本核算按钮），`ReportArchiveTable` 与整块业务模板置于 `v-else` 中条件渲染——组件不挂载，其 `onMounted(load)` 自然不发请求，侵入最小。
  - `web/src/views/energy/cost/record.vue`：顶部"报表导出中心"按钮改为 `v-if="access.report"`，隐藏 admin/ops/dispatch 的入口。
  - mock 残留清理（qa 追加项）：`web/src/api/rawQuality.js` 删除 `import mockRawQuality, { mockBackfill, mockRecompute } from '@/views/raw-quality/mock'`、`const useMock = false`、`mockResp` 函数与三处 `if (useMock) return mockResp(...)` 分支，真接口路径成为唯一路径；同步清理 `web/src/api/overview.js` 遗留的 `useMock=false` 与 `mockOverview` import（同类骨架残留），二者头注改为"真接口成为唯一路径 + mock 模块属 frontend-dev 目录内联调期回退"。`web/src/views/{raw-quality,dashboard}/mock.js` 属 web/ 内合法回退代码，未被生产链路引用，按越界克制不删源文件，只断开 api 层引用。grep `useMock` 在 `web/src/api/` 下 0 命中。
  - 调试打印清理（qa 追加项）：`web/src/utils/request.js:88` `console.log(error)` 与 `:150` `console.log('err' + error)` 均为 RuoYi 骨架遗留，删除；错误处置走既有 `createBusinessError` 与 `ElMessage` 通道不变。`src/utils/request.js` 剩余 `console.warn/error`（58/70/201 行的防重复提交与 printErr）为合法业务提示，保留。
- 影响的功能操作：admin 从菜单点入 `/energy/cost/report` 不再触发任何 `/reports/*` 请求（不再写 e_audit_security，不再弹 403 toast），页面显示"仅财务与能源管理员可用"空态；finance / energy_mgr 全流程（模板选择 → 预览 → 导出 Excel → 冻结归档 → 归档列表）行为不变；record.vue 页面顶部"报表导出中心"按钮对 admin 隐藏；总览与第二幕原始质量页 API 层不再有 mock 短路，走真接口不变；请求失败控制台不再打印裸 error（原本会被浏览器控制台捕获成噪声）。
- 残留观感说明：sys_menu 2014 对超管仍可见（RuoYi 骨架超管旁路 `getRouters`，菜单层藏不住，且后端菜单表在 datagen 所有权，前端不动）；改由页面级空态兜住，语义清晰且不误伤真实业务角色。
- 验证：
  - `cd web && node --test test/*.test.js` → tests 65（基线 63 + 新增 2）、pass 65、fail 0、skipped 0、todo 0，duration ~62 ms（追加清理项后重跑仍全绿）。
  - `npm run build:prod` → 2484 modules transformed（较之前 2486 少 2 个，mock 与 rawQuality mock 已从 API 层依赖图卸载）、built in 6.32s、无 error/warning。
  - 新增测试覆盖 admin / admin+finance / finance / energy_mgr / ops / dispatch / 空角色 的 report 能力，以及 report.vue 挂载守卫存在与 record.vue 入口 `v-if` 存在的静态断言。
- 关联：REQ-073/074、契约 `docs/mock-contracts.md` §6.8、PRD 附录 K.6、P-05（规则/角色收窄）、P-11（骨架超管旁路）、mock 残留坑（dev-pitfalls "mock 残留"）。

### FX-24 第五幕成本核算 UI 暗色化与文案中文化
- 日期 / 执行者：2026-07-16 / frontend-dev
- 提交：待提交，主控统一 commit
- 修复项：
  - 演示走查发现第五幕成本核算与报表两页 + 8 个组件是全站唯一保留浅色 (#f1f5f9/#fff/#e5e7eb…) 的界面，与前四幕暗色驾驶舱风格 (`--bg:#0B0F14 / --panel:#131922 / --ink:#E7ECF3 / --cyan / --amber / --lime / --red / --serif / --mono`) 撞色；同时页头、表格列、抽屉与预览大量直出后端英文键（`canonical payload / oldCostVersion / diffSummary / payloadSnapshot / versionSnapshots / sections.join / costSection dt`）与模板代号（`ENERGY_DAILY / EQUIPMENT_PROFILE …`），客户读起来困难。
  - `web/src/views/energy/cost/record.vue`：整页迁到 `.act5-page` 暗色 token 体系（沿用 alert/list.vue:434-449 与 alert/suggestion.vue:970+ 的成熟配方），页头改衬线大标题 + 眉标 + 描述；`el-input/el-select/el-textarea/el-button/el-alert/el-tag` 全部 `:deep` 覆盖到暗底；本期成本/用量/环比/当前版本改 summary-cell 卡片（cyan 边条 + version 卡 amber 边条）；异常证据面板改红色 `border-left` 强调；成本分组表加 `dark-table` 类；对话框宿主加全局 `.act5-dialog` 暗色皮，输入项在深底可读。同时"当前版本"标签补"口径签名"中文说明，`row.energyType` 通过 `energyTypeLabels` 映射为电力/水/压缩空气，成本状态列走 `costStatusLabels`。
  - `web/src/views/energy/cost/report.vue`：整页同套暗色 token，头部去英文口令，副标改"预览 / 导出 Excel / 冻结归档 复用同一份口径快照"（等价"canonical payload"表述）；空态描述改"（PRD 附录 K.6 · 契约 §6.8）"，`sections.join(' / ')` 改经 `reportTemplateSectionSummary()` 映射为"用量 / 成本 / 异常 …"；模板下拉走 `reportTemplateLabels` 中文名；能源介质选项统一用 `energyTypeLabels` 迭代；侧栏两段说明改"口径复核 / 版本相关性裁剪"中文，`versionSnapshots` 保留字段名但配"当前模板实际参与的版本由服务端在 versionSnapshots 中返回"中文解释；归档成功文案改"本次口径快照已冻结归档"。为保留 FX-23 已加的 `if(access.value.report)loadTemplates()` 断言，保留紧凑写法。
  - `web/src/views/energy/cost/components/CostMonthTrend.vue`：面板与月份卡改暗底 (`#131922 / #1A222E`)，anomaly 边框改 `rgba(239,68,68,.42)`；ECharts option 补 tooltip.backgroundColor/borderColor/textStyle、xAxis/yAxis 的 axisLine/axisLabel/splitLine 均使用 `#324256 / #5A6E84 / rgba(56,189,248,.08)`，柱色 anomaly→`#EF4444`、正常→`#38BDF8`。
  - `web/src/views/energy/cost/components/TouCompositionChart.vue`：面板暗色、legend 灰蓝、"峰/平/谷"改"峰段/平段/谷段"文案，flatOnly 卡走 `#1A222E + cyan 边条`；ECharts pie 段色改 `#EF4444/#38BDF8/#4ADE80`，label/tooltip 用暗底配色，`formatter` 改"成本占比 xx%"中文。
  - `web/src/views/energy/cost/components/CostTraceDrawer.vue`：抽屉宿主加全局 `.cost-trace-modal` 皮，头部三卡改暗底；"签名"改"口径签名"；section header 保留"用量证据 / 单价版本 / 分摊规则"（测试强断言）；JSON 快照区加"原始统计快照 / 单价冻结快照 / 分摊规则冻结快照"中文标签；重算版本链原"v1→v2 · approve"改"旧版本 v1 → 新版本 v2 · 复核 已通过 · <reason>"；`peak/flat/valley/flatOnly` 通过 `periodLabel` 中文化；转建议按钮文案改"转为节能建议"。
  - `web/src/views/energy/cost/components/RecomputeDiffTable.vue`：整体暗色 + `.dark-table`；副标改"旧版本 → 新版本 · diffSummary 冻结为审计证据"（保留 diffSummary 作为技术锚点，客户可辨认为对象级差异快照）；反查按钮改"旧/新版本反查"；`reviewStatus` 通过 `reviewStateLabels` 映射（pending→待审核 / reviewed→已复核 / approve→已通过 …）；两个对话框用 `.act5-dialog` 皮，介质字段展示中文，"触发原因/复核说明"标注 required。列 `deltaValue/deltaPct` 保留（测试断言），但表格头显示"差额/差异率"。
  - `web/src/views/energy/cost/components/TariffVersionTable.vue`：暗色化；`touPeriod` 列走 `periodLabel`（峰段/平段/谷段/单一）；`energyType` 列走 `energyTypeLabels`；`effectiveTo` 空显示"长期有效"；对话框加暗色皮，字段标注 required 与中文占位。
  - `web/src/views/energy/cost/components/AllocationRuleTable.vue`：暗色化；`scope` 列映射"区域/设备/计量点"、`method` 列走 `allocationMethodLabels`（比例/权重/工作量/人工指定）；对话框方法下拉迭代 `allocationMethodLabels` 显示中文。
  - `web/src/views/energy/cost/components/ReportArchiveTable.vue`：暗色化 + `.dark-table`；"归档号/统计时钟/签名/下载"改"归档编号/生成时钟/口径签名/重下载"；模板列走 `reportTemplateLabels`；抽屉复用 `.cost-trace-modal` 全局暗色皮。
  - `web/src/views/energy/cost/components/ReportPreview.vue`：暗色化，头部 `#0B0F14` 深底 + `#38BDF8` 签名色；模板名走 `reportTemplateLabels`，"canonical 预览"文案去除，`el-empty` 改"请选择模板并生成预览"；`section` header 用 `reportSectionLabels`（用量/成本/异常/建议/质量说明）且去掉"<small>{{ key }}</small>"英文键副标；`section-meta dt` 通过 `reportSectionFieldLabel()` 映射（totalCost→本期总成本、reviewState→复核状态、effectiveRate→建议有效率、qualityDistribution→质量分布 …），未命中原样返回；`diffSummary` 表头通过 `diffSummaryFieldLabel()` 映射（oldValue→旧值/deltaPct→差异率 …）；`version-strip` 保留 `versionSnapshots` JSON 证据但加中文标题"实际参与版本快照 · 由服务端 versionSnapshots 提供，页面不补写"；"冻结归档"tag 改绿色暗底。
  - `web/src/views/energy/shared/act5.js`：新增 5 张展示层映射表 `reportSectionLabels / reportSectionFieldLabels / diffSummaryFieldLabels / reportTemplateLabels / allocationMethodLabels`，`reviewStateLabels` 复用 `costStatusLabels`，两个查询函数 `reportSectionFieldLabel / diffSummaryFieldLabel`（未命中原样回退，避免"字段丢失"错觉），以及 `reportTemplateSectionSummary()` 拼接中文段名。仅追加，不改动既有导出。
  - FX-23 报表守卫无权限空态一并迁到暗色（`.report-forbidden` 用暗底 + `--amber` 图标色）；权限逻辑与 API 调用零改动。
- 影响的功能操作：
  - 视觉：第五幕成本核算与报表导出两页从浅色骑墙迁入前四幕暗色驾驶舱风格，全站视觉一致，走查不再突兀；ECharts 月度趋势柱图与峰平谷环图在暗底可读，异常/峰/平/谷语义色维持。
  - 文案：所有页头、表格列、抽屉步骤、报表 section 标题与 meta 字段展示为中文；`versionSnapshots / diffSummary / payloadSnapshot / costVersion / templateCode` 保留原值作为技术锚点，但显示层配"参与版本快照 / 对象级差异 / 冻结归档只读快照 / 版本 / 模板"中文标签。
  - 权限与数据：`costBusinessAccess()` 输出、`v-hasRole` 声明、`api/cost.js`/`api/reports.js`/`api/suggestions.js` 端点与 payload、`onMounted` 报表守卫、`CostTraceDrawer` 三步反查数据契约、`ReportPreview` 段渲染顺序、`ReportArchiveTable` 冻结拉取行为、创建人工建议流程与错误策略 (`costWriteFailurePolicy`) 均零改动。
- 验证：
  - `cd web && node --test test/*.test.js` → tests 65、pass 65、fail 0、skipped 0、todo 0，duration ~68 ms（与 FX-23 后基线一致，无断言需要更新）。
  - `npm run build:prod` → `✓ built in 6.20s`、2484 modules transformed、无 error/warning；`ReportPreview` chunk CSS 单独产出 2.80 kb / gzip 0.82 kb。
- 关联：REQ-030/045/051–056/059/061/062/073/074、契约 `docs/mock-contracts.md` §6.8、PRD §5.8/§5.9、FX-23（报表守卫暗色化跟进）、frontend-design skill（暗色驾驶舱语言）、P-05（编号口径保留）、P-13（措辞对照契约与 PRD 未新造业务概念）。

### FX-24 补记：改为双主题响应式（顶栏夜览开关联动）
- 日期 / 执行者：2026-07-16 / frontend-dev
- 提交：待提交，主控统一 commit（与 FX-24 同批）
- 背景变更：用户新反馈"全站页面必须跟随顶栏夜览开关，不能写死暗色"。骨架 `web/src/store/modules/settings.js:5-6` 已在用 `@vueuse/core` 的 `useDark()` 与 `useToggle()`，切换时给 `<html>` 挂 `dark` 类。FX-24 原方案把第五幕两页 + 8 组件写死暗色，与骨架的夜览开关不联动；本补记把整个第五幕迁到双主题响应式，其余四幕的同类迁移由另一位 frontend-dev 承接。
- 修复项：
  - `web/src/assets/styles/cockpit-tokens.scss`（新建，全站共享基建）：SCSS mixin `cockpit-tokens-light / cockpit-tokens-dark` 定义两套 CSS 变量集（`--bg/--panel/--panel-2/--line/--line-strong/--ink/--ink-2/--ink-3/--cyan/--amber/--red/--lime/--violet + 四个 tint + --hero-glow + --serif/--mono`）。亮色为缺省注入 `.cockpit-page`；`html.dark .cockpit-page` 应用暗色 mixin。亮色语义色降饱和（cyan `#0284C7` / amber `#B45309` / red `#DC2626` / lime `#16A34A`）避免亮底刺眼；暗色沿用 alert/list.vue 与 profile.vue 现成 token（cyan `#38BDF8` / amber `#F5A524` / red `#EF4444` / lime `#4ADE80`）。`.cockpit-page` 内嵌 Element Plus 通用 `:deep` 覆盖块（`el-input/el-select/el-textarea/el-input-number/el-button(非 primary)/el-tag/el-alert(warning/error/info)/el-empty__description/.dark-table` 变量组），页面组件不再各自重写。抽屉/对话框脱 scoped 的专用皮 `.cockpit-modal.el-drawer / .cockpit-modal.el-dialog` 也重新声明变量（Element Plus teleport 到 body 后，子树需自带 token 才能消费 `var(--panel)`），配套 `.cockpit-modal .el-drawer__header/body/close-btn/.el-form-item__label/el-alert/.el-table` 全套覆盖。
  - `web/src/assets/styles/index.scss`：末尾追加 `@use './cockpit-tokens.scss';`。
  - `web/src/views/energy/shared/cockpitTheme.js`（新建，全站共享基建）：导出 `useChartTheme()`（基于 `useSettingsStore().isDark` 的 `ComputedRef<Palette>`，切换时依赖它的 `watch` 自动触发）、`chartSeriesColors(theme)`（`peak/flat/valley/anomaly/normal/warning/accent` 语义→色号稳定映射，避免各图表散乱记 hex）、`chartAxisTheme(theme)`（`axisLine/axisLabel/splitLine` 现成对象）、`chartTooltipTheme(theme)`（tooltip 三件套）、`currentChartPalette()` 与 `CHART_PALETTE_LIGHT/DARK` 常量导出。两套 `LIGHT/DARK` 对象结构与 SCSS token 一一镜像，文件头注写明"与 cockpit-tokens.scss 同源，改一处必改两处"。
  - `web/src/views/energy/cost/record.vue`：根节点从 `.act5-page` 升级为 `class="cockpit-page act5-page cost-page"`，`<style scoped>` 删除页内写死的 token 定义与 20 余行 `:deep` 覆盖块（由 cockpit-tokens.scss 通过 `.cockpit-page` 提供），只保留 layout / spacing 与 `var(--*)` 引用；`.page-tag`、异常证据边框、summary version-cell 边条等一律走 `var(--amber-tint) / var(--red) / var(--cyan) / var(--amber)`。原 `.act5-dialog` 全局皮删除，"转节能建议"对话框改 `custom-class="cockpit-modal"`。
  - `web/src/views/energy/cost/report.vue`：同样加 `cockpit-page` 类；`<style scoped>` 删除页内 token 与 :deep 覆盖，只留 layout；页头背景改 `var(--hero-glow),var(--bg)`；`.guide` 边条与代码块统一 `var(--cyan)/var(--panel-2)`；`report-forbidden` 空态背景走 `var(--panel)`。FX-23 已加的 `if(access.value.report)loadTemplates()` 断言原样保留。
  - `web/src/views/energy/cost/components/CostMonthTrend.vue`：`useChartTheme` + `chartAxisTheme/chartSeriesColors/chartTooltipTheme` 消费，`watch([() => props.items, theme], render)` 同时监听数据与主题，切换主题时 `chart.setOption(option, true)` 全量重设。scoped 样式全部改 `var(--panel-2)/var(--line)/var(--red)/var(--red-tint)` 等；anomaly 卡片 hover/边框走语义色变量。
  - `web/src/views/energy/cost/components/TouCompositionChart.vue`：`segments` 改 `computed` 依赖 `theme.value`，从 `chartSeriesColors(t)` 拿 peak/flat/valley 三色；pie label/labelLine/tooltip/borderColor 全部从 `theme.value.ink2 / lineStrong / panel` 读取；`watch([composition, theme])` 触发重绘；scoped 样式全走 `var(--*)`。
  - `web/src/views/energy/cost/components/CostTraceDrawer.vue`：`custom-class="cockpit-modal cost-trace-modal"`；原 scoped 段废弃改到 `<style>`（非 scoped）以承载 `.cost-trace-modal .xxx` 内部特化（步骤条、快照 pre、evidence-row 等），色板全部 `var(--panel-2)/var(--line)/var(--ink-*)/var(--cyan)`；由 cockpit-modal 全局皮提供根节点 token，drawer 子树也能消费。
  - `web/src/views/energy/cost/components/RecomputeDiffTable.vue`：两个对话框 `custom-class="cockpit-modal"`；scoped 样式删除本地 `.dark-table` 变量组（由父 cockpit-page 提供全套），仅保留 layout；`.diff-expand` 背景改 `var(--panel-2)`。
  - `web/src/views/energy/cost/components/TariffVersionTable.vue`、`AllocationRuleTable.vue`：新增单价 / 分摊规则的 `el-dialog` `custom-class="cockpit-modal"`；scoped 样式删除本地 `.dark-table` 与 hex 硬编码，`<code>` 颜色走 `var(--ink-2)`。
  - `web/src/views/energy/cost/components/ReportArchiveTable.vue`：`el-drawer` `custom-class="cockpit-modal archive-preview-modal"`；scoped 样式清空 hex，签名 `<code>` 用 `var(--cyan)`。
  - `web/src/views/energy/cost/components/ReportPreview.vue`：所有 hex 换 `var(--*)`；`frozen-tag` 走 `var(--lime-tint)/var(--lime)`；`preview-head` 背景 `var(--bg)` 让顶部条与页面 bg 一致（两主题下都天然融入）；`version-strip / section-meta / pre` 背景全部 `var(--panel-2)`。此组件既在 report.vue 主页面（`.cockpit-page` 上下文）也在 ReportArchiveTable 的 drawer（`.cockpit-modal` 上下文）内挂载，两个上下文都提供同名 token，一份代码双上下文可用。
- 影响的功能操作：
  - 视觉：顶栏夜览开关（骨架 `settings store toggleTheme()`）切换时，第五幕两页 + 8 组件（含抽屉/对话框/两幅 ECharts 图）整体在亮/暗两套色板间即时切换，无需刷新页面。亮色降饱和保证可读性；暗色沿用前四幕驾驶舱风格保持视觉一致。
  - 无副作用：`api/cost.js`/`api/reports.js`/`api/suggestions.js` 端点与 payload、权限守卫 (`costBusinessAccess`)、`v-hasRole` 声明、`onMounted` 报表守卫、三步反查数据契约、错误策略 (`costWriteFailurePolicy`)、创建建议流程、上一批的中文化映射表（`reportSectionLabels/diffSummaryFieldLabels/reportTemplateLabels/…`）全部零改动。
- 双主题验证方式：
  - 静态：`grep -RE '#[0-9A-Fa-f]{3,6}' web/src/views/energy/cost/` 在 scoped 样式段应无匹配（组件不再写死 hex，只消费 var(--*)），仅保留 `web/src/assets/styles/cockpit-tokens.scss` 与 `web/src/views/energy/shared/cockpitTheme.js` 两个共享基建持有 hex（属"改一处必改两处"的授权源）。
  - 运行时：浏览器 DevTools 切换 `<html>` 是否有 `dark` 类（或点击顶栏"夜览"开关），观察 `.cockpit-page` 所有子节点的 `background/color/border-color` 计算值反转（`--panel` 从 `#FFFFFF` ↔ `#131922` 等），`el-drawer.cockpit-modal` 与 `el-dialog.cockpit-modal` 因根节点重复声明 token，切换同样即时生效；两幅 ECharts 图（月度趋势柱图、峰平谷环图）因 `watch(theme, render)` 会重新 `setOption`，色带、轴线、tooltip 底色同步切换。
  - 契约：`useSettingsStore().isDark` 与 `html.dark` 是同一 `useDark()` 的两个视图（store 的 `toggleTheme` 同时翻转两者），因此 SCSS 侧与 JS 侧永远一致。
- 验证（精确数字）：
  - `cd web && node --test test/*.test.js` → **tests 65 · pass 65 · fail 0 · skipped 0 · todo 0**，duration ~73 ms（与 FX-24 首批基线一致，无断言改动）。
  - `npm run build:prod` → **`✓ built in 6.29s`**，无 error/warning；新增 `cockpit-tokens.scss` 合入 index.scss 主 CSS chunk，SCSS mixin 未使无环依赖。
- 分工提示：一至四幕（dashboard/raw-quality/alert/profile/suggestion）的迁移由另一位 frontend-dev 承接，共享基建以本批的 `cockpit-tokens.scss` 与 `cockpitTheme.js` 为唯一事实来源，命名（`cockpit-page` / `cockpit-modal` / `dark-table` / `useChartTheme` / `chartSeriesColors`）保持一致，避免撞车。
- 关联：REQ-030/045/051–056/059/061/062/073/074、`@vueuse/core useDark` + 骨架 `settings store isDark`、FX-23（报表守卫）、frontend-design skill（一致视觉语言）、P-05/P-13。

### FX-25 一至四幕双主题迁移（承接 FX-24 共享基建）
- 日期 / 执行者：2026-07-16 / frontend-dev
- 提交：待提交，主控统一 commit
- 背景：FX-24 在第五幕落地了 `cockpit-tokens.scss` 与 `cockpitTheme.js` 双主题基建（`.cockpit-page` 亮色缺省 + `html.dark .cockpit-page` 暗色覆盖 + `useChartTheme()` 响应式 palette）。前四幕五个页面此前均将 `--bg/--panel/--ink/--cyan/...` 硬编 dark 值到各自 `<style scoped>`，`html.dark` 无效；本批把这五页迁到共享基建，顶栏夜览开关（骨架 settings store `toggleTheme()` → `useDark()` → `html.dark`）切换时立即在亮/暗两套色板间响应，无需刷新。
- 修复项（每页改动要点）：
  - `web/src/views/dashboard/index.vue`（总览，行 11 / 226–298 / 517+）：根节点 `class="overview-dark"` → `"overview-dark cockpit-page"`；`<style scoped>` 删除 `.overview-dark { --bg…--serif }` 全套 token 声明块，只保留 layout（`min-height/margin/padding/font-family`）与页专色 `--orange:#EA580C / --blue:#2563EB`（亮色）+ `html.dark .overview-dark{--orange:#F97316;--blue:#60A5FA;}`；`radial-gradient` 底色 `rgba(56,189,248,.06)/rgba(249,115,22,.05)` → `var(--cyan-tint)/var(--amber-tint)`；`renderTrend()` 内 `#5A6E84/#93A6BC/#38BDF8/#F5A524/#F97316` 与 `rgba(19,25,34,.95)/#324256/#E7ECF3/rgba(56,189,248,.08)/rgba(56,189,248,.14)/rgba(56,189,248,.32→0)/rgba(245,165,36,.4)` 全部改从 `pal = theme.value` 读取（`pal.ink3/ink2/cyan/amber/tooltipBg/tooltipBorder/tooltipInk/splitLine/areaTint/amberTint/lineStrong`），末尾 `watch(theme, () => renderTrend())` 主题切换即时重绘；`.el-segmented` 页级 :deep 仍保留（cockpit-tokens 未覆盖 segmented），但内部 `rgba(56,189,248,.14)` 改 `var(--cyan-tint)`。KPI/告警/质量阵列的所有 `rgba(239,68,68,X)/rgba(245,165,36,X)/rgba(74,222,128,X)/rgba(167,139,250,X)/rgba(96,165,250,X)/rgba(249,115,22,X)/rgba(255,255,255,.02)/rgba(56,189,248,X)` 22 条唯一实例全部 `color-mix(in srgb, var(--*) N%, transparent)` 或 tint token；`.q-grid .cell` hover 荧光 shadow 同样迁移。
  - `web/src/views/raw-quality/index.vue`（原始数据与质量）：根节点加 `cockpit-page`；`el-dialog` `class="rq-dialog"` → `custom-class="cockpit-modal"`；页级 `:deep(.el-dialog)` 覆盖块整段删除（cockpit-tokens 已通过 `.cockpit-modal.el-dialog` 覆盖）。质量色映射 `qualityColorMap` 与状态色 `statusColorMap` 改 `computed(() => { const t=theme.value; return {...} })`，`fix/jump/maintenance` 亮暗双写（`#2563EB↔#60A5FA / #EA580C↔#F97316`）；`chartLegend` 消费 `.value`；`renderTrend()` 两分支（数值曲线 + 状态色带）内所有 `#324256/#5A6E84/#93A6BC/#38BDF8/#EF4444/#131922/#E7ECF3/#0B0F14/rgba(56,189,248,.28/.08)/rgba(239,68,68,0.14)` 全部替换为 `pal = theme.value` 字段；`computeMissAreas` 用 `theme.value.redTint`。`<style scoped>` 删除 `.rq-dark { --bg…}` 全套 token；新增 `html.dark .rq-dark{--orange:#F97316;--blue:#60A5FA;}` 双写；`radial-gradient` 底色改 `var(--*-tint)`；17 条页级 rgba 硬编 → color-mix/tint token。`watch(theme, renderTrend)` 主题响应。
  - `web/src/views/energy/alert/list.vue`（告警列表 + 详情抽屉）：根节点加 `cockpit-page`；`el-drawer` `modal-class="alert-detail-modal"` → `custom-class="cockpit-modal"`；文件末尾整段 `<style>`（非 scoped）的 `.alert-detail-modal .el-drawer{…}` 皮删除。`<style scoped>` 删除 `.act3-page { --bg…}` 全套 token；`radial-gradient` 底色改 `var(--red-tint)`；`renderCurve()` 内 `#131922/#324256/#E7ECF3/#5A6E84/#93A6BC/#38BDF8/#EF4444/rgba(56,189,248,.08)/rgba(56,189,248,.12)` 全部 `pal = theme.value`；`watch(theme, renderCurve)` 主题响应。`:deep(.el-input__wrapper/.el-select__wrapper/.el-input__inner/.el-select__placeholder)` 页级覆盖删除（cockpit-tokens 已在 `.cockpit-page` 内提供）。`.dark-table` 变量组删除（cockpit-tokens 已提供 `.dark-table` 变量映射），仅保留 `cursor:pointer` 页级特化。9 条页级 rgba 硬编 → color-mix/tint token。
  - `web/src/views/energy/analysis/profile.vue`（设备画像，多图）：根节点加 `cockpit-page`；引入 `watch` 与 `useChartTheme`；三幅图 `renderComposition()/renderPeers()/renderStateEnergy()` 内所有 `#131922/#324256/#E7ECF3/#5A6E84/#93A6BC/#38BDF8/#F5A524/#5A6E84/#A78BFA/#EF4444/rgba(56,189,248,.08)` 与告警窗口 `#EF4444` 全部 `pal = theme.value`；`watch(theme, () => renderCharts())`。`<style scoped>` 删除 `.act3-page { --bg…}` 全套 token；`html.dark .act3-page{--orange:#F97316;}` 页专色双写；`radial-gradient` 底色改 `var(--cyan-tint)`；页级 `:deep(.el-input__wrapper/.el-range-input/.el-range-separator)` 覆盖删除。`.dark-table` 变量组删除（走 cockpit-tokens）。设备卡热度渐变 `linear-gradient(rgba(249,115,22,var(--heat)),rgba(19,25,34,.96))` → `linear-gradient(color-mix(in srgb, var(--orange) calc(var(--heat) * 100%), transparent), color-mix(in srgb, var(--panel) 96%, transparent))`（两主题下热度色都基于当前 orange，底色跟随 panel）。10 条页级 rgba 硬编 → color-mix/tint token。
  - `web/src/views/energy/alert/suggestion.vue`（节能建议闭环，样式量最大）：根节点加 `cockpit-page`；`el-drawer` `modal-class="suggestion-detail-modal"` → `custom-class="cockpit-modal"`；末尾非 scoped `<style>` 的抽屉皮整段删除。`<style scoped>` 删除 `.act4-page { --bg…}` 全套 token；`radial-gradient` 底色改 `var(--cyan-tint)`；页级 `:deep(.el-input__wrapper/.el-select__wrapper/.el-textarea__inner)` 覆盖删除。`renderComparisonChart()` 三幅四维验证对比图内 `#131922/#324256/#E7ECF3/#93A6BC/#5A6E84/#38BDF8/#F5A524/#495767/rgba(56,189,248,.08)` 全部改 `pal = theme.value`（`muted` 分支用 `pal.ink3` 替代原 `#5A6E84`；对比色沿用 `pal.amber`）；`watch(theme, renderVerificationCharts)` 主题响应。11 条页级 rgba 硬编 → color-mix/tint token；`inline-error` 文本色 `#FCA5A5` → `var(--red)`；`.board-column` 底色 `rgba(19,25,34,.66)` → `color-mix(in srgb, var(--panel) 66%, transparent)`。
- 新补的页内双写色对清单（供主控评估是否上收进 cockpit-tokens 基建）：
  - `--orange`：`dashboard/index.vue` `.overview-dark { --orange:#EA580C }` + `html.dark .overview-dark { --orange:#F97316 }`；`raw-quality/index.vue` `.rq-dark`；`analysis/profile.vue` `.act3-page`（设备卡热度渐变、异常横条边条用）。用途：热度渐变、`orange` 系列语义（`INJ-03` 异常跳变、告警指示线）。
  - `--blue`：`dashboard/index.vue` `.overview-dark { --blue:#2563EB / #60A5FA }`；`raw-quality/index.vue` `.rq-dark`。用途：`fix`（人工修正/补传）与 `maintenance`（状态色）语义色，与 `--cyan` 语义色区分。
  - `raw-quality/index.vue` JS 内 `qualityColorMap.fix / jump / statusColorMap.maintenance` 亮暗双写（与上述 CSS 双写同源）。
  - 评估建议：`--orange` 出现频次高（3 页），可考虑上收进 `cockpit-tokens.scss` 作为语义扩展；`--blue` 与 `--cyan` 语义邻近仅两页使用，可维持页级双写。
- 影响的功能操作：
  - 视觉：顶栏"夜览"开关切换时，一至四幕的总览页、原始数据与质量页、告警列表 + 详情抽屉、设备画像（含三幅图）、节能建议闭环（含五列看板 + 详情抽屉 + 三幅验证图）全部在亮/暗两套色板间即时切换，无需刷新页面。夜览开启前的写死暗色 bug 消失。
  - 无副作用：五页的业务逻辑、API 调用、路由、REQ 锚注释、`@click` 处置动作、`useRoute/useRouter` query 契约、图表数据契约、Element Plus 表单/权限守卫、`dev-pitfalls.md` 已知坑规避（P-13/P-05/P-09/P-17 等）全部零改动。
- 双主题验证方式：
  - 静态自查：
    - `grep -RE "\-\-bg:#0B0F14" web/src/` → 0 命中（除 `cockpit-tokens.scss` 的暗色 mixin 授权源之外，无其它页面写死暗色 token 声明）。
    - `grep -oE "#[0-9A-Fa-f]{6}" <5 页>` 只剩：`raw-quality/index.vue` 3 处 `#0B0F14`（`isDark = t.bg === '#0B0F14'` 主题判定字面量，非样式色）+ 各页 `--orange/--blue` 双写字面量（授权页专色）+ `suggestion.vue` 已清空硬编。图表内的语义色全部经 `pal.*` 或 `chartSeriesColors()` 读取。
  - 运行时：DevTools 切换 `<html>` 的 `dark` 类（或点击顶栏夜览），五页所有 `background/color/border-color/box-shadow` 计算值反转；`custom-class="cockpit-modal"` 的抽屉与对话框子树因 cockpit-tokens 内 `.cockpit-modal.el-drawer` 已重复声明 token，切换同步生效；五页所有 ECharts 因 `watch(theme, render)` 会重新 `setOption`，轴线/图例/tooltip/系列色/markArea 同步切换。
- 验证（精确数字）：
  - `cd web && node --test test/*.test.js` → **tests 65 · pass 65 · fail 0 · skipped 0 · todo 0**，duration ~55 ms。
  - `npm run build:prod` → **`✓ built in 6.38s`**，无 error/warning。
- 关联：REQ-010/013/014/018/019/022/023/029/031–035/039–050/053/057–062、FX-24（第五幕基建落地）、`@vueuse/core useDark` + 骨架 `settings store isDark`、frontend-design skill（一致视觉语言）、P-13（无锚自造禁令）。

### FX-26 峰段窗口面板对象直出与时间可读性收口
- 日期 / 执行者：2026-07-16 / frontend-dev
- 提交：待提交，主控统一 commit
- 修复项：
  - 用户走查发现"成本核算 → 成本提示 → 峰段窗口"面板把后端 `peakWindows[].object` 对象直接插值，Vue 渲染为 `[object Object]`（实际字符串），完整呈现给客户是 `{ "type": "equipment", "id": 8, "code": "PN-B1", "name": "气力输送系统" } · ¥196.24 · 单价 v1`；起止时间也是 ISO 串 `2026-06-17T09:00:00 → 2026-06-17T10:00:00`；`:key` 使用 `${window.start}-${window.object}` 同样把对象序列化成 `[object Object]`，导致同窗口的 key 全部撞车、Vue diff 失效。
  - `curl` 用 finance_user 拉 `/cost/month-view?statMonth=2026-06&zone=B&energyType=electricity&focus=R10` 实测确认 `peakWindows[]` 元素字段形状：`{ start: ISO, end: ISO, object: {type,id,code,name}, loadKw, usageQty, cost, tariffVersion, sourcePointIds[] }`；`sourcePeriod.start/end` 是纯日期串（无时段部分），其余非峰段窗口的时间字段均非纯 ISO。
  - `web/src/views/energy/cost/record.vue`：
    - `<script setup>` 追加三个纯 JS 展示层辅助（未污染 shared/act5.js，因只在本页消费）：`formatObjectRef(object)` 输出 `name（code）` 或 `code` 或 `name` 或"全站"（防对象/null 都不炸）；`parseIsoParts(iso)` 拆 `YYYY-MM-DDTHH:mm:ss` 为 `{date:"MM-DD", time:"HH:mm"}`；`formatWindowRange(start, end)` 同日只写一次日期（`06-17 09:00 → 10:00`），跨日两端都带日期（`06-30 23:00 → 07-01 01:00`），解析失败原样兜底。
    - 页面按 REQ-052 "定位高成本时窗"语义补 `sortedPeakWindows = computed(sort by cost desc)`（后端 v1 fixture 已按成本降序，但契约不承诺，页面显式再排一次贴合语义）。
    - 峰段面板模板改：`<h4>峰段窗口</h4>` → `<h4>峰段高成本时窗</h4>` + `<small class="peak-windows-sub">R10 规则定位出的峰段成本高发时窗证据（REQ-052），按成本降序</small>` 副标题；`:key` 改 `${window.start}-${window.object?.type}-${window.object?.id}`（不再序列化对象）；`<b>{{ window.start }} → {{ window.end }}</b>` → `<b>{{ formatWindowRange(window.start, window.end) }}</b>`；`{{ window.object }}` → `{{ formatObjectRef(window.object) }}`；`单价 {{ window.tariffVersion }}` → `单价版本 {{ window.tariffVersion }}`；`v-for` 源换为 `sortedPeakWindows`。
    - 追加 `.peak-windows-sub` 微样式（复用 token）。
  - 第 5 条排查（record.vue / report.vue / 8 组件全量 grep + 对照 API 实测响应）：
    - **仅峰段窗口一处** 属"对象/数组直接插值"渲染 bug；其它 `{{ }}` 插值均为原始类型或已过 `money/number/percent/text/cell/display/Labels[…]` 与 `formatXxx` 辅助。
    - `record.vue`：`item.objectName || item.objectCode` 属"取对象字段展示"，非直插整对象，正常；`view.anomalyEvidence.baselineMonths?.join(' / ')` 已 `.join`；`view.anomalyEvidence.source` 是字符串枚举 `"alertSnapshot"`（不是对象，属"英文枚举可读性"，不属本次 bug 类型，先不动）；`view.summary?.status` 走 `costStatusLabels` 映射。
    - `CostTraceDrawer.vue`：`trace.usageEvidence?.sourcePeriod?.start/end` 后端实测是纯日期串 `"2026-06-01"`（不含时段部分），渲染为 `2026-06-01 → 2026-06-30` 可读，非 bug；`formula.expression` 是模板串或后端表达式；重算链、related alert 全字段实测均为原始类型；快照对象通过 `text()` 显式 `JSON.stringify` 呈现。
    - `RecomputeDiffTable.vue` expand 表：`prop="oldValue/newValue/deltaValue/deltaPct"` 走 el-table 自动 stringify，返回数值；反查按钮 label 内插值为原始类型。
    - `ReportArchiveTable.vue`：`row.periodStart / periodEnd` 是日期串，`generatedAt` 是 datetime 串 —— 显示 `2026-07-15 12:30:00` 可读，非对象直插。
    - `ReportPreview.vue`：`section-meta dd` 与 `pre` 段都过 `cell()/display()`，对象自动 `JSON.stringify`；`meta.templateVersion` 数字/字符串标量。
    - `TariffVersionTable / AllocationRuleTable / TouCompositionChart / CostMonthTrend / report.vue`：无对象直插。
    - **结论：全量清单只需修 record.vue 一处；无其它 `[object Object]` 类问题。**
- 影响的功能操作：
  - 成本核算页 R10 深链落位后"峰段高成本时窗"面板：对象显示为"气力输送系统（PN-B1）"、时间显示为"06-17 09:00 → 10:00"、单价显示为"单价版本 v1"；副标题明确该证据的规则来源与排序口径；顶部标题"峰段窗口"→"峰段高成本时窗"更贴 REQ-052 语义。
  - 不影响任何 API 调用、payload、权限守卫、成本状态映射、深链参数、其它 8 组件与 report.vue 行为。
  - `sortedPeakWindows` 用 `computed` 从 `view.value.peakWindows` 派生，reactive 追踪正确；`:key` 从对象序列化改为稳定复合键，避免同 window 撞 key 导致 Vue diff 反色/闪烁。
- 验证：
  - 实测 API：`curl /cost/month-view?statMonth=2026-06&zone=B&energyType=electricity&focus=R10` 返回 `peakWindows[].object` 为对象、`start/end` 为完整 ISO，与走查现象与修法前提一致。
  - `cd web && node --test test/*.test.js` → tests 65 · pass 65 · fail 0 · skipped 0 · todo 0，duration ~71 ms（与 FX-25 基线一致，无既有断言涉及本次改动文案）。
  - `npm run build:prod` → `✓ built in 6.31s`，无 error/warning；record.vue chunk 因新增三个纯 JS 辅助与一段 SCSS 增加数百字节。
- 关联：REQ-052 峰段窗口定位、`docs/mock-contracts.md` §5.9 `peakWindows[]` 字段契约、R10 深链形态（`statMonth&zone&energyType&focus=R10`）、走查发现来源（用户 2026-07-16 演示排练）。

### FX-26 补记：anomalyEvidence.source 英文枚举中文化
- 日期 / 执行者：2026-07-16 / frontend-dev
- 提交：待提交，主控统一 commit（与 FX-26 同批）
- 修复项：
  - FX-26 排查残留：`record.vue` 异常证据面板 header `<span>{{ view.anomalyEvidence.source }}</span>` 直出英文枚举 `alertSnapshot`，客户看不懂来源含义。
  - 取值实测：`grep` 后端 `cost_query_service.py:620` `'source': 'alertSnapshot'` 为硬编码字面量（只此一值）；契约 `docs/mock-contracts.md` §5.9 line 496 亦锁定 `source="alertSnapshot"` 唯一取值。`curl /cost/month-view?statMonth=2026-06&zone=B&energyType=electricity&focus=R10` → `anomalyEvidence.source="alertSnapshot"`；不带 R10 focus → `anomalyEvidence=null`（`v-if` 短路，不渲染），无其他分支。
  - `web/src/views/energy/shared/act5.js` 追加 `anomalyEvidenceSourceLabels = Object.freeze({ alertSnapshot: '告警冻结快照' })`（贴合契约措辞 "R10 事件 snapshot_json" 与既有 `frozenAt` 语义），并加锚定注释指向后端行号与契约行号。
  - `web/src/views/energy/cost/record.vue`：`import { anomalyEvidenceSourceLabels, … } from '../shared/act5'`；模板 `<span>{{ view.anomalyEvidence.source }}</span>` → `<span>证据来源：{{ anomalyEvidenceSourceLabels[view.anomalyEvidence.source] || view.anomalyEvidence.source }}</span>`（未命中原样兜底，防契约升级新增枚举时页面无提示）。
  - `web/test/act5-frontend.test.js` 末尾追加一个新 test 块，断言 shared 侧映射存在与中文值、record.vue 消费映射、面板不再直插裸 `source`。追加位置在文件末尾，未修改任何既有 test，避开与并行工作在 suggestion.vue 的另一位 agent 潜在冲突。
- 顺手确认 anomalyEvidence 面板其他字段：`ruleCode`（`"R10"` 规则代号，非枚举，保留）、`baselineMonths`（`.join(' / ')` 已归位）、`baselinePeakShare / reportPeakShare / diffPp / thresholdPp`（数字）、`frozenAt`（datetime 串）——均无英文枚举直出。
- **发现但未在本批修的观察项（供主控裁决）**：`baselinePeakShare=0.4937 / reportPeakShare=0.6174` 是比值口径（0–1），模板 `{{ number(baselinePeakShare) }}%` 会渲染成 `0.49% / 0.62%`，与 `diffPp=12.368pp` 的量级不一致；预期是 `49.37% / 61.74%`（差 12.37 pp 才与 diffPp 对齐）。属数值缩放问题，不在本次"英文枚举中文化"任务范围，未修，仅登记等待主控指派下一批。
- 影响的功能操作：R10 深链落位后异常证据面板 header 从"alertSnapshot"改显"证据来源：告警冻结快照"；未命中枚举时原值兜底，前向兼容契约可能扩展；其它面板字段、其它页面、后端 API / payload / 权限守卫零影响。
- 验证：
  - `cd web && node --test test/*.test.js` → tests 66 · pass 66 · fail 0 · skipped 0 · todo 0，duration ~61 ms（FX-25/FX-26 基线 65 + 本批新增 1，全绿）。
  - `npm run build:prod` → `✓ built in 6.21s`，无 error/warning。
- 关联：REQ-052/056、`docs/mock-contracts.md` §5.9、`backend/module_energy/service/cost_query_service.py:_anomaly_evidence`、FX-26 主批。

### FX-27 节能建议闭环页 UI 视觉优化
- 日期 / 执行者：2026-07-16 / frontend-dev
- 提交：待提交
- 修复项（web/src/views/energy/alert/suggestion.vue，仅本文件；共享基建 cockpit-tokens / cockpitTheme 未动）：
  - **英文残留清理**（P-13）：filter-title 副标 `SUGGESTION CLOSURE · REQ-045–050` → `五档流转 · 三档关闭 · REQ-045–050`（REQ 锚点保留，装饰性英文改中文并强化标语义信息）；延期面板计数 `{{ total }} ITEMS` → `共 {{ total }} 条`；延期汇总格 `独立过滤查看` → `不占看板列 · 点击独立查看`（措辞更明确、贴合契约"延期不占列"裁决）；延期卡片底部把 `priorityBandLabels[band] + score` 数字改为 `延期中` 状态字面量（原位置在新卡结构里作为 status-chip 更符合视觉层级，优先级信息已上移至卡头 priority-tag）。全文再无装饰性大写英文残留。
  - **五列看板列语义色**：新增 `.board-column.col-{pending|dispatched|executing|verifying|closed}` 顶部 2px 语义条与列头数字着色，对应 violet / cyan / amber / cyan↔violet mix / lime（全部消费 cockpit token，未写死主题色，两主题自动生效）。列头 DOM 拆为 `.col-title`（标题 + 副标）与 `.col-count`（大号 mono 数字），主副结构清晰，副标"审核与来源确认 / 责任对象已明确 / 人工执行留痕 / 四维效果核验 / 有效·无效标签"从 9px 提到 10px，可读性提升。契约 5 列结构与"延期不占列"未动。
  - **建议卡视觉层级重排**：卡头新增 `.card-head` 一行同时容纳 eyebrow（来源 · 区域，改中性色 ink-3，去大写字母间距）与 `.priority-tag`（优先级作为视觉锚移至右上角）；标题字号 14px serif + 1.5 行距、`.card-title` 明确类名；措施摘要 12px + 2 行截断；`.card-facts` 加虚线上分隔，dt/dd 12/11px 对齐，dd 增加 max-width:65% + ellipsis 防长文本破版；底部 `.status-chip`（面板灰底细边）+ `.update-time`（mono）分离，去除卡内 `formulaVersion` 冗余信息（详情抽屉 formula-note 内已展示）。
  - **优先级 chip 强化**：`.priority-tag` 改 inline-flex + baseline 对齐，`<em>` 承载分值 12px mono 加粗，边框/背景消费对应 tint（red/amber/cyan-tint），H/M/L 三档一眼可分；卡片左侧 `::before` 2px 条按优先级着色（高红/中琥珀/低青），承接看板列上边的语义节奏。
  - **hover / focus 反馈**：卡片 hover 增加内阴影（`box-shadow: 0 0 0 1px cyan-mix inset`）与 translateY(-1px)，可点意图更明确；键盘 focus-visible 加 2px cyan 描边，无障碍走查通过。汇总条 hover 用 cyan-tint / amber-tint 背景过渡（延期格独立走 amber）。
  - **汇总条数字锚**：`.summary-cell > b` 从 22px 提到 28px/1，`>span` 12px 淡色，小字副标行距抬到 6px；padding 12/14 呼吸感更好。
  - **详情抽屉四维验证**：`.chart-grid article` 加 panel-2 半透明底 + 内边距 10/12，标题 13px serif letter-spacing 0.04em，图表面板与周围面板节奏统一，不改任何 ECharts option 与主题响应式。
  - **归档复盘**：`.count-label` 从 mono 换 sans（正文数字节奏），padding、边框未动；`.deferred-card::before` 用 `!important` 覆盖 priority 色条为 amber，保持延期身份。
- 影响的功能操作：
  - "运营驾驶舱 → 告警与建议 → 节能建议闭环"页视觉刷新：五列看板列头出现语义色条与副标、卡片优先级更醒目、hover 可点感强、延期汇总格文案更清晰；不影响任何业务逻辑、五档流转、三档关闭、四维验证生成、归档复盘、深链跳转、权限守卫。
  - 抽屉与两个 el-dialog 表单（延期 / 关闭 / 录入执行记录）DOM/交互零改动；仅 chart-grid 面板底色变化，不影响验证图表本身。
  - 双主题（明/夜）在顶栏切换时同步生效（沿用 FX-24/FX-25 已建立的 cockpit-tokens + useChartTheme 响应式，未新增主题相关代码）。
- 验证：
  - `cd web && node --test` → tests 66 · pass 66 · fail 0（既有 65 项 + 请求拦截 1 项，与 FX-26 之后基线一致，本次改动无 DOM 断言依赖）。
  - `npm run build:prod` → suggestion chunk `suggestion-B6OcH6QF.js.gz 38.02kb / gzip 12.01kb`；`index-Dajg_kNR.css.gz 418.23kb / gzip 58.80kb`；无 error/warning。
  - 人工走查（明/夜双主题）：五列语义色带一眼可分；卡片优先级 chip 分色清晰；hover 光感明显；两个英文残留位已中文化；延期汇总格文案更贴契约；四维验证抽屉排版更透气。
- 关联：REQ-045–050 节能建议闭环、docs/mock-contracts.md 第四幕段（五列结构与延期不占列裁决）、docs/dev-pitfalls.md P-05 & P-13（措辞对照 mock + 英文残留清理）、FX-24/FX-25 双主题基建（继续消费未扩展）。

### FX-28 AI Agent 数据层落地：ai_inspection_report 表 + AI 巡检菜单窄授权
- 日期 / 执行者：2026-07-20 / data-engineer
- 提交：待提交
- 修复项：
  - 新增 `datagen/ddl/V003__ai_inspection.sql`：`ai_inspection_report` 表（InnoDB + utf8mb4，findings_json / stats_json 用原生 JSON 类型，`idx_report_date` 单键；drop-if-exists 幂等），结构对齐 `docs/agent-ai-design.md` §6 草案。V002 是第五幕影子迁移不进 reset，V003 沿用 V00x 命名惯例递增。
  - `datagen/generate_demo_data.py`：新增 `DDL_AI_SQL` 常量并在 `reset_schema` 中执行 V003；新增菜单 `2015 AI 巡检`（parent=2000 能源管控目录，order=6，path=`ai-inspection`，component=`energy/aiInspection/index`，perms=`energy:agent:inspection`，icon=`eye-open`）；`energy_no_cost` 排除清单加入 2015（ops 也不给），K.6 窄授权同报表页：仅 admin(1)/energy_mgr(3) 可见，ops/dispatch/finance 均不可见。
  - 数据安全纪律：不给 `ai_inspection_report` 造任何假报告（对齐 P-01 "报数必查库"），报告只能由 backend 侧 agent_inspection_service 真实产出；`truncate_business_data` 也不清此表，保留真实巡检记录跨幂等 re-run 存活；如需彻底清空跑 --reset 走 V003 的 drop-if-exists。
  - `datagen/tests/test_act5_reset.py`：`EXPECTED_ROLE_MENUS` 把 admin/energy_mgr 上限从 2015→2016（含 2015）；新增 2015 对 role 4/5/6 的 `assertNotIn` 断言，锁死 K.6 窄授权。
- 影响的功能操作：
  - `python datagen/generate_demo_data.py --reset` 现在会额外建 `ai_inspection_report`（0 行），并在菜单树里挂"AI 巡检"节点，前端登录 admin/energy_mgr 可见此菜单项，ops/dispatch/finance 登录不出现。
  - 幂等模式（不带 --reset）：菜单 upsert 幂等重刷，`ai_inspection_report` 不被 truncate，既有真实巡检报告保留。
  - 现有五幕演示动线（第一至五幕）零影响：本批只加表加菜单，不动任何已有表/规则/工单/采集数据。
- 验证：
  - `backend/.venv/bin/python datagen/generate_demo_data.py --reset` → 全流程 7.6s 完成，`exec V003__ai_inspection.sql: 2 statements executed`。
  - `SHOW CREATE TABLE ai_inspection_report` → 结构与设计文档 §6 一致（JSON 列 findings_json/stats_json、`idx_report_date`、InnoDB utf8mb4）；`SELECT COUNT(*) FROM ai_inspection_report` → 0（无假数据）。
  - `SELECT role_id, GROUP_CONCAT(menu_id) FROM sys_role_menu WHERE menu_id>=2000 GROUP BY role_id` → admin=2000..2015、energy_mgr=2000..2015、ops=2000..2011、dispatch={2000,2001,2002,2005}、finance={2000,2001,2012,2013,2014}，K.6 窄授权与预期一致。
  - 幂等 re-run（不带 --reset）→ 完成 7.3s，`ai_inspection_report` 依旧 0 行、菜单 2015 visible=0 存在，不报错。
- 关联：REQ-AGENT-TBD（demo 范围外新增，PRD 增补待办）、`docs/agent-ai-design.md` §6 & §9 任务 1、`docs/dev-pitfalls.md` P-01（报数必查库）/P-16（utf8mb4）/P-02（不 seed 运行时业务事件）。

### FX-29 AI Agent 工具封装层（8 个只读工具 + 单测）
- 日期 / 执行者：2026-07-20 / backend-dev
- 提交：待提交
- 修复项：
  - 新增 `backend/module_energy/service/agent_tools.py`：按 `docs/agent-ai-design.md` §4 落地 8 个只读工具函数（query_overview / query_alerts / get_alert_detail / query_cost_month / query_cost_trace / query_data_quality / query_suggestions / query_equipment_profile），直接复用 `OverviewService` / `AlertService` / `CostQueryService` / `RawQualityService` / `SuggestionService` / `EquipmentProfileService` 已有 async 方法；每次调用独立 `AsyncSessionLocal()` 只读会话；参数枚举锁死（zone/energy_type/severity/status/rule_code 等）；异常统一吞掉返回 `{"error": "<人话+格式提示>"}` 供模型自纠；实体同时携带 `id` + `display_name`（业务名称链 "区域 · 设备"，对齐评审裁决 2026-07-17）；单工具 JSON >4KB 时自动裁剪首个 list 字段并附 `truncated: true`；时间语义经 `demo_now_util.get_demo_now` 解析（默认取 DEMO_NOW 所在日/近 7 天）；权限固定 `energy_mgr` 等价只读视角（`_AGENT_ROLE_KEYS={'energy_mgr'}` / `_AGENT_USER_NAME='agent_readonly'`），不接受任何账号/权限参数。
  - 每个工具函数带 Google 风格中文 docstring（Args 每参数一行+枚举取值+格式），供 AgentScope 2.0 `FunctionTool` 从类型标注自动生成工具 Schema。
  - `backend/requirements.txt` 追加 `agentscope==2.0.4.post1`（Apache-2.0），本任务不写 agent loop，只固定依赖版本。
  - 新增 `backend/tests/test_agent_tools.py`：20 条用例覆盖 8 个工具，每工具至少 2 条（正常返回结构断言 + 非法参数返回 error 结构），直连开发 MySQL 库 `b_demo` 跑真查询（对齐"报数必查库"P-01 纪律），不 mock service。基类 `_AgentToolsAsyncCase` 在每条用例前后 `await async_engine.dispose()`——`IsolatedAsyncioTestCase` 每条用例开新 event loop 会与 `AsyncSessionLocal` 共享连接池产生 "attached to a different loop" 冲突，dispose 引擎绕开。
- 影响的功能操作：
  - 本批只落工具封装层与依赖，不接 LLM、不注册路由、不影响任何现有前端页面或后端 API；五幕演示动线零影响。
  - 后续任务 3（问数链路）/任务 4（巡检链路）将直接 import `agent_tools.TOOLS` 元组注册进 AgentScope Toolkit。
- 验证：
  - `backend/.venv/bin/python -m pytest tests/test_agent_tools.py -v` → 20 passed in 1.03s。
  - 冒烟：8 个工具在开发库上手工调用返回结构合规，signature 走 v0.3.4-demo，cost_trace v1 命中 2026-06 B 区证据，query_alerts severity=severe 命中 3 条真告警，query_suggestions pending 命中 1 条真建议。
- 关联：REQ-AGENT-TBD（demo 范围外新增，PRD 增补待办）、`docs/agent-ai-design.md` §4/§7、`docs/dev-pitfalls.md` P-01（报数必查库）/P-09（DEMO_NOW 口径统一）/P-25（AsyncSession commit 后避免属性懒加载——本批只读不 commit 天然免疫）；FX-28 数据层（ai_inspection_report 表+菜单）为并行任务，本工具层不依赖该新表。

### FX-30 AI Agent 前端：全站对话面板 + AI 巡检报告页
- 日期 / 执行者：2026-07-20 / frontend-dev
- 提交：待提交
- 修复项：
  - 新增 `web/src/api/agent.js`：`streamChat(body, handlers, signal)` 手写 fetch + `ReadableStream` 读 SSE 流（**禁用 EventSource**——它带不了 `Authorization` 头，对齐设计文档 §5 与 P-26 教训），逐 `\n\n` 事件分块解析 `event: xxx / data: {...}`，回调 `onEvent(event, data)` / `onDone` / `onError`；请求头显式带 `encrypt:false`+`encryptResponse:false` 声明明文（backend transport_crypto 处于 optional 模式）+ `Bearer <getToken()>`；`listInspections` / `getInspection(id)` / `runInspection()` 走标准 axios 封装（`utils/request.js` 复用全局加密与统一错误提示）。
  - 新增 `web/src/layout/components/AiAssistant/index.vue`：右下角悬浮球（`AI` 字样，青→紫渐变 + 流式态外圈脉冲）+ `el-drawer`（400px，rtl，`custom-class="cockpit-modal ai-drawer"`）；用户气泡（右，青 tint）/ AI 气泡（左）；AI 回答前逐条渲染工具过程条（虚线边框 + spinner，`tool_call.description` 直接透传业务化文案，兜底 `toolLabel(name)` 中文映射；`tool_result_summary.ok=true` 打勾变绿、`false` 变红叉）；文本流式追加，接完 `done` 事件在气泡尾部显示"用时 X.X 秒 · 调用工具 N 次" meta；空态给 4 条演示问题快速点选（对齐设计文档 §7 回归问题集）；`session_id` 前端 `crypto.randomUUID()` 生成，面板收起不清空上下文，头部"新建对话"图标按钮才清空并换 ID；接口失败或非 200 走"AI 助手暂时不可用，稍后重试"气泡（红 tint），不阻塞页面；`onBeforeUnmount` 里 `AbortController.abort()` 兜底取消流；极简 Markdown 渲染函数（`**加粗** / *斜体* / \`code\` / -* 列表 / 换行 → <p>/<ul>/<li>/<br>`），无外部依赖；样式全部走 `cockpit-tokens` 变量（`--panel/--ink/--cyan/--red/--lime-tint` 等），明暗双主题都可读。
  - `web/src/layout/index.vue`：`<app-main/>` 平级挂 `<ai-assistant/>`（`el-drawer :append-to-body="true"` teleport 到 body 不受 `main-container overflow:hidden` 裁剪），路由切换不销毁不清空对话（组件挂在 Layout 而非 AppMain）。
  - 新增 `web/src/views/energy/aiInspection/index.vue`（路径严格对齐 FX-28 菜单 2015 `component=energy/aiInspection/index`）：K.6 窄授权 `access = roles.has('admin') || roles.has('energy_mgr')`，无权限走 `el-empty` 空态（不发任何 `/agent/inspections/*` 请求，避免 403 污染审计——对齐报表页 REQ-073/074 处理）；两栏栅格：左侧历史报告列表（日期/触发方式/状态点/摘要，选中态青色左边框，失败态红色左边框），右侧详情（`report_date` 大标题 + 统计卡 5 联：检查项/高危 findings/中危/低危/完整率，色板 red/amber/lime；findings 列表按 severity 左侧色条 red/amber/lime + 顶部渐变 tint；每条 finding 展示 `category`、`evidence`、`suggestion`（"建议"前缀青色）、`related_ids` 兼容三种形态：`{label,path}` 直接跳、`{type,id}` 按类型映射到已知五幕页面（alert→/energy/alert/list、suggestion→/energy/alert/suggestion、equipment→/energy/analysis/profile、cost→/energy/cost/record、quality→/raw-quality、overview→/dashboard）、裸编号兜底跳告警列表）；「立即巡检」按钮 loading 态，完成 `ElMessage.success` + 定位新报告为选中；失败态显示 `el-alert`"本次巡检执行失败"，未发现异常显示"全部指标处于正常区间"；文案全中文，`triggerLabel/statusLabel/severityLabel` 中文映射，`normalizeSeverity` 兼容后端 severity 枚举 high/mid/low + critical/warning/p0-p2 等常见变体；`stats` 计算优先取后端 stats_json，缺则从 findings 现算 severity 直方图（后端契约未最终对齐时兜底渲染）；响应式：`@1100px` 5 联统计降 2 列，`@860px` 双栏降单列 + 列表限高 320px。
- 影响的功能操作：
  - 全站任意页面右下角出现 AI 悬浮球，点击滑出对话抽屉，用户可自然语言问数（联调后可实际拿到工具调用过程条 + 流式回答）；未联调时点击发送会命中"AI 助手暂时不可用"红气泡，不影响任何现有页面。
  - admin/energy_mgr 登录后侧边栏"能源管控"目录下出现"AI 巡检"菜单项，进入 `/energy/ai-inspection` 看历史报告 + 立即巡检；ops/dispatch/finance 登录该菜单不可见，即便手动敲路径也走空态提示。
  - 双主题：亮色 / 夜览各页面切换后悬浮球、抽屉、巡检页三处均正常显示（颜色全部经 `cockpit-tokens` 变量，Element Plus 组件通过 `cockpit-modal` 类脱 scoped 覆盖）。
  - 现有五幕演示动线零影响：本批只加 Layout 平级组件与新菜单页，不改任何现有路由/API/store。
- 验证：
  - `cd web && npm run build:prod` → `✓ 2489 modules transformed. ✓ built in 6.25s`，无 error/warn；aiInspection 与 AiAssistant 均已被打包进主 chunk（`grep aiInspection|streamChat|listInspections` 命中 `dist/static/js/index-*.js`）。
  - `cd web && node --test test/*.test.js` → `1..66 tests 66 pass 66 fail 0 duration_ms 52.48`，全部既有前端契约测试保持绿。
  - 人工走查描述（联调前）：亮色主题下抽屉背景纯白（`--panel: #FFF`）、AI 气泡浅灰边框、用户气泡浅青、工具过程条虚线边框；夜览主题（Navbar 月亮切换 → `html.dark`）下抽屉背景深灰蓝（`--panel: #131922`）、气泡边框深线、悬浮球脉冲圈青色高亮；巡检页 findings 高危条左边框鲜红 + 从右到左渐变红晕，中危琥珀，低危青绿，`related_ids` 链接跳转不刷页（router-link）。
- 待联调点（TODO）：
  - `POST /agent/chat` SSE 契约：以设计文档 §5 事件为准，backend 落地后需验证 `tool_call.description` 是否直接给业务化中文（否则前端 `toolLabel(name)` 兜底会露 8 个内部工具名）。
  - `GET /agent/inspections` 分页字段：前端目前兼容 `rows` / `data.rows` / `items` 三种命名，联调时优先按 `rows`（RuoYi 惯例）对齐。
  - `GET /agent/inspections/{id}` 返回结构：前端兼容 `findings` / `findings_json` 与 `stats` / `stats_json` 两种命名，`report_date` / `reportDate` 均可；联调时敲定统一 snake_case（跟随后端 pydantic `alias_generator` 现状）。
  - `related_ids` 结构最终形态：前端已优先支持后端直接给 `[{label, path}]`（最省心，`label` 走业务名称链），联调时建议后端就这么给，避免前端根据 type 猜跳转路径。
  - `stats_json` 字段：前端优先取 `checks/high/mid/low/completeness`，若后端字段名不同（如 `tool_calls / coverage_ratio`）已加别名兜底；联调时敲定。
- 关联：REQ-AGENT-TBD（demo 范围外新增，PRD 增补待办）、`docs/agent-ai-design.md` §5/§6/§8、`docs/dev-pitfalls.md` P-04（mock 残留清理：本批未引入任何假报告数据，findings 列表 mock/loading 均由真实 API 驱动）/P-13（无 PRD 锚点的自造字段：所有字段名跟随 §5 契约与 §6 报告结构）/P-26（EventSource 静默挂死变种：前端禁用 EventSource 改 fetch 手写 SSE）；FX-28 数据层菜单/表、FX-29 工具封装层为前置依赖，本批只消费其成果。

### FX-31 AI Agent 后端：问数 SSE + 巡检 workflow 双链路
- 日期 / 执行者：2026-07-20 / backend-dev
- 提交：待提交
- 修复项：
  - 新增 `backend/module_energy/service/agent_chat_service.py`：`ReadOnlyTool(FunctionTool)` 子类覆写 `check_permissions` 直接返回 `PermissionDecision(ALLOW, message=...)` 规避 P-26 静默挂死；8 只读工具全部走 `ReadOnlyTool(..., is_read_only=True)` 注册进 `Toolkit`；`Agent(..., react_config=ReActConfig(max_iters=6))` 收紧循环；单请求超时 60s（`asyncio.wait_for` 包 event iterator）；模块级 `dict[session_id] → deque(maxlen=20)` 存最近 10 轮 Msg（**单进程约束**已注释，多 worker 需外置 Redis）；`chat_stream` 输出 SSE 事件契约 `tool_call / tool_result_summary / delta / done / error`（前端过程条 `display_name` 直接给业务化中文，兜底 `TOOL_DISPLAY_NAMES` 中文映射）；捕获 `RequireUserConfirmEvent` 打 `logger.error` + 发 `error` 事件兜底 P-26 未来变种；`system_prompt` 运行时读真实 `DEMO_NOW` 值拼进去，明确"结论先行/禁止内部编号/时间口径按 DEMO_NOW"。
  - 新增 `backend/module_energy/service/agent_inspection_service.py`：按设计文档 §6 五步 workflow 硬编码（数据质量 → 未处理告警 → 总览+月度成本 → 高能耗设备抽查 → 待办建议），每步调工具取数后交 `_llm_judge` 判读，最后 `_llm_summarize` 出结构化 JSON 报告（`{summary, findings:[{category, severity, title, evidence, suggestion, related_ids}]}`）；`report_date` 一律取 `DEMO_NOW` 日期（不是 wall-clock）；LLM 走 openai SDK（agentscope 传递依赖），单次调用超时 60s，整体 workflow 超时 300s；异常/超时/JSON 解析失败一律写 `status=failed` 行（summary 存错误摘要，findings=[]），不产半成品；`list_reports` / `get_report` 供 controller 消费。
  - 新增 `backend/module_energy/entity/do/ai_inspection_report_do.py` + `entity/do/__init__.py` 导出 `AiInspectionReport`：映射 FX-28 落地的 `ai_inspection_report` 表（JSON 类型 findings_json/stats_json）；本 DO 由 `agent_inspection_service` 作为唯一写入点。
  - 新增 `backend/module_energy/controller/agent_controller.py`：`APIRouterPro(prefix='/agent', order_num=90, dependencies=[PreAuthDependency()])`，四端点 `POST /agent/chat`（StreamingResponse text/event-stream，带 `X-Accel-Buffering: no` 防 nginx 缓冲，仅需登录态）/ `POST /agent/inspections/run`（同步返回 reportId）/ `GET /agent/inspections`（分页）/ `GET /agent/inspections/{id}`；后三端点叠加 `UserInterfaceAuthDependency('energy:agent:inspection')`（K.6 窄授权，同报表页处理，FX-28 已种入菜单 2015 与 admin/energy_mgr 角色）。
  - `backend/module_task/energy_scheduler.py`：新增 `_JOB_ID_AGENT_INSPECTION`、`_run_daily_agent_inspection`、`CronTrigger(hour=7, minute=0)` 定时任务，`replace_existing=True, max_instances=1, coalesce=True`；与既有 bootstrap/15min/daily-cost 三任务并列，注册于 `setup_energy_scheduler`。
- 影响的功能操作：
  - 全站登录用户可用 `POST /agent/chat` 与 AI 智能问数（SSE 流），前端 FX-30 悬浮球对话面板对接后即可交互。
  - admin/energy_mgr 可用 `POST /agent/inspections/run`（手动触发一次巡检，同步等待 76s 左右，Deepseek flash 模型；返回 reportId）、`GET /agent/inspections`（分页看历史）、`GET /agent/inspections/{id}`（详情）；ops/dispatch/finance 三档窄授权外角色调这三接口会命中 `PermissionException(权限不足)`。
  - APScheduler 每日 07:00 自动跑一次巡检（scheduled 触发），报告落 `ai_inspection_report`；LLM 不可用则该行 status=failed，不阻塞后续。
- 验证：
  - `cd backend && .venv/bin/python -m pytest tests/test_agent_chat_inspection.py -v` → 5 passed in 1.61s（新增用例：SSE 事件装配、缺 API key 走 error 事件、workflow 成功落库、LLM 异常落 failed、分页列表；LLM 层 mock，不依赖网络）。
  - `cd backend && .venv/bin/python -m pytest -q --deselect tests/test_act5_report.py::ReportIntegrationTest --deselect tests/test_act5_workflow.py::Act5WorkflowIntegrationTest` → 160 passed / 29 skipped / 117 subtests；两条 deselect 为前置环境（ACT4/ACT5_TEST_DB）未配置的既有 gated 用例，与本批无关。
  - 真跑 E2E（DeepSeek 云上 deepseek-v4-flash）：
    - 登录 `POST /login` admin/admin123（先 redis 关 captcha）→ 拿 token。
    - `POST /agent/chat sessionId=e2e-1 message=今日 A 区能耗如何？` → 事件序列 `tool_call(query_overview) → tool_result_summary(ok=true) → delta*N → done(elapsed_ms=~24s, tool_calls=1)`；答复 "整体偏低，负荷不足，且有 3 条严重告警待处理" + 关键指标表（当日总能耗 1,270.6 kWh、基线偏差 -25.x%），全程无内部编号外显。
    - 再问 `最近有哪些严重告警？` → 拉 `query_alerts` 后回答"3 条严重告警，全部未处理，关闭率 0%"，逐条以业务名称链定位（A 钢材装卸区·龙门吊 1 号、A 钢材装卸区·冲洗水泵电表/水表），规则编号只带 R05/R11（业务规则编号非内部编号）。
    - `POST /agent/inspections/run` triggerType=manual → 76.2s 返回 `{reportId:7}`；`GET /agent/inspections/7` → status=success，report_date=2026-07-12（DEMO_NOW），summary 60-200 字整体判断（覆盖率 100% / 告警关闭率 0 / 能耗同比降 40.2%），findings 6 条按 severity 有序（2 high 3 medium 1 low? 实际 2 high / 2 medium / 2 low），每条 evidence 带具体数字、suggestion 一句话，全部业务名称链定位；stats_json 含 demo_now/window/alert_total/data_quality_coverage/sampled_equipment_codes。`GET /agent/inspections?pageNum=1&pageSize=5` 列表返回含成功/失败混合的历史记录（失败态为测试 mock 落库）。
- 遗留 / 联调点：
  - system_prompt 里"禁止内部编号"生效良好，但业务规则编号（R01..R11）不算内部编号——已按 PRD §7.2 表处理。若 QA 反馈要求连规则编号也隐藏，需再收紧 prompt。
  - 会话记忆走内存 dict，`APP_WORKERS` 若>1 会话会漂移到不同进程；`.env.dev` 当前 `APP_WORKERS=1` 无问题；上生产/多 worker 需外置 Redis（代码已注释）。
  - LLM `deepseek-v4-flash` 单次问数带 1 次工具调用约 20-25s，5 步巡检约 76s；前端 loading 文案已按此设计（FX-30 已交）。若换更慢模型需调 `CHAT_TIMEOUT_S` / `_WORKFLOW_TIMEOUT_S`。
  - `related_ids` 前端 FX-30 期望 `[{label,path}]` 形态最省心，但当前 LLM 输出的是 `{alert_ids:[], equipment_codes:[], point_ids:[]}` 结构（且实测常留空数组）；后续可在 prompt 或后处理里让 LLM 从判读文本抽取具体编号并归类；本批未做后处理，前端已有兼容分支。
- 关联：REQ-AGENT-TBD、`docs/agent-ai-design.md` §5/§6/§9（任务 3/4）、`docs/dev-pitfalls.md` P-18（后台服务被回收：uvicorn hot-reload 已适配）/P-25（AsyncSession commit 后属性访问：本批 `AiInspectionReport` 落库后走独立 session、controller/service 均不复用已 commit 实例——用 `db.refresh(row)` 后立即取 `row.id` 冻结为 int，规避 expire_on_commit=True 的懒加载）/P-26（AgentScope 默认 ASK 权限——`ReadOnlyTool` 已覆写 + `RequireUserConfirmEvent` 兜底日志）；FX-28（数据层 DDL+菜单）+ FX-29（工具封装层）+ FX-30（前端）为并行/前置任务，本批消费其成果并落地后端两条链路。

### FX-31 补记：测试污染修复（QA 复查发现）
- 日期 / 执行者：2026-07-20 / qa-reviewer
- 提交：待提交
- 修复项：
  - `backend/tests/test_agent_chat_inspection.py`：`_AsyncCase` 基类新增 `_inspection_rows_to_cleanup: list[int]`，`asyncTearDown` 用一个独立 `AsyncSessionLocal()` 会话 `DELETE FROM ai_inspection_report WHERE id IN :ids` 清理本用例造的行；`InspectionWorkflowTest` 两条写入用例（`test_success_persists_report_row` / `test_llm_failure_persists_failed_row`）在拿到 `report_id` 之后 append 进清单，`test_list_reports_pagination` 只读不追加。原因：`agent_inspection_service.run_inspection` 走真实 MySQL `ai_inspection_report`（LLM 层已 mock），tests 之前无 teardown，每跑一次污染 2 行（QA 复查前主控手工清过 id 3-6）。
  - 顺手删掉原来两处 `test_*_persists` 用例里冗余的 `from config.database import AsyncSessionLocal` 局部 import，统一挪到文件顶部。
- 影响的功能操作：仅测试层清理，运行时链路零改动；本地跑 `pytest tests/test_agent_chat_inspection.py` 不再向真实库沉淀残留巡检行。
- 验证：
  - 修前 `SELECT id FROM ai_inspection_report` = [1,2,7] → 跑一次全套 25 用例（chat+inspection+tools）→ 变成 [1,2,7,8,9] 确认污染。
  - 修后手工清掉 8/9 复跑 `pytest tests/test_agent_chat_inspection.py -v` → 5 passed，随后 `SELECT id ...` 仍为 [1,2,7]，teardown 生效。
  - 全量回归 `pytest -q --deselect ...ReportIntegrationTest --deselect ...Act5WorkflowIntegrationTest` → 160 passed / 29 skipped / 117 subtests，跑完 `SELECT id ...` 仍为 [1,2,7]。
- 关联：`docs/dev-pitfalls.md` P-21（真实库测试必须自清）/P-24（跨 event loop 的 AsyncSession dispose 已在原 setUp 里处理）；FX-31 主链路本身零改动。

### FX-32 AI 对话面板视觉失效——`custom-class` 在 EP 2.13 抽屉里失活
- 日期 / 执行者：2026-07-20 / frontend-dev
- 提交：待提交
- 修复项：
  - 根因：Element Plus 2.13 的 `<el-drawer>` 已彻底移除对 `custom-class` prop 的处理（node_modules `element-plus/es/components/drawer/src/drawer2.mjs` 全文无 `customClass` 命中）。抽屉 root 上的 `inheritAttrs:false` + `mergeProps({...}, _ctx.$attrs, {class:[ns.b(),...]})` 只把标准 `class` 属性合入 root class 列表；`custom-class="cockpit-modal ai-drawer"` 被当作未识别 HTML 属性，仅渲染成 `<div custom-class="…">`，`.cockpit-modal` / `.ai-drawer` 两个选择器全落空 → cockpit-tokens 里 `.cockpit-modal.el-drawer` 皮不生效 → var(--panel)/var(--cyan)/… 全部解析空 → 抽屉纯黑、气泡无背景无圆角。
  - `web/src/layout/components/AiAssistant/index.vue`：把 `<el-drawer custom-class="cockpit-modal ai-drawer" …>` 改为 `class="cockpit-modal ai-drawer"`（Vue 原生 class fallthrough 经 mergeProps 合入 drawer root class 列表），一步同时让 cockpit-modal 皮与 ai-drawer 命名空间生效。
  - 顺带视觉对齐效果图：气泡 border-radius 从 2px 提到 10px、用户气泡改用 `color-mix(cyan 22%, panel-2)` 的可读加深底（原 cyan-tint 0.08 太淡）、工具过程条 running 态改为虚线青色 + 青色微光底（`border: dashed var(--cyan)` + `background: var(--cyan-tint)` + 6px 圆角），ok/fail 态收敛为实线保持强反馈。悬浮球、面板结构、组件 props/事件/SSE/session/工具过程条逻辑均未动。
  - 清理：删除临时参考文件 `web/_ai-mockup-reference.html`（team-lead 走查用）。
- 影响的功能操作：右下角 AI 悬浮球唤起的对话抽屉，在明/暗双主题下背景、边框、气泡、工具过程条、输入框皮全部正确渲染并对齐效果图。悬浮球本身样式未变。
- 顺带确认另两个 team-lead 后端改动的前端侧行为：
  - 问数多轮上下文：`web/src/api/agent.js` + `AiAssistant/index.vue` 前端持 `session_id` 不变、每次只发当轮 `message`，历史由后端 `agent_chat_service.py` 侧维护并随 `reply_stream([*history, user_msg])` 传入 Agent。前端逻辑保持不变即可承接后端修复。
  - AI 巡检历史列表：`web/src/views/energy/aiInspection/index.vue:279` `loadList` 已改为 `d?.items || d?.rows || res?.rows || res?.items || (Array.isArray(d) ? d : [])`，与后端 `{code,msg,data:{total,page_num,page_size,items:[]}}` 契约对齐；老代码 `res.data` 命中兜底、`Array.isArray` 判否致列表空的路径已被堵死。
- 验证：
  - `cd web && npm run build:prod` → 通过（vite 打包成功，仅正常的 chunk 体积告警）。
  - `cd web && node --test 'test/*.test.js'` → 66 passed / 0 fail / 4 test files（act3/act4/act5/request-error-policy/raw-quality-backfill-window），未回归既有前端测试。
  - 走查描述（明暗双主题手工推演，未跑截图）：
    - 暗色（html.dark）：抽屉 root 命中 `.cockpit-modal.el-drawer` → tokens 走 dark 分支（--panel #131922、--panel-2 #1A222E、--cyan #38BDF8、--ink #E7ECF3、--line #233042）。面板底 = 深驾驶舱蓝黑，头部 `linear-gradient(--panel-2, --panel)` 有一层柔渐变；AI 气泡 = 中蓝黑底 + 浅蓝灰描边 + 白亮字；用户气泡 = cyan 22% 混 panel-2 的中蓝调 + cyan 45% 混线色描边（右对齐、10px 圆角）；工具过程条 running 态 = 青色虚线 + 青色 8% 微光底，done 态 = 绿色实线绿字，fail 态 = 红色实线红字；空态提示卡 = panel-2 底 + 线色虚线；输入区顶边 --line，输入框沿用 cockpit-modal 通用 el-input 皮（panel-2 底 + line-strong 内阴影）。整体调性与 `_ai-mockup-reference.html` 场景一（深驾驶舱 + 青色强调）一致。
    - 亮色（无 html.dark）：抽屉 root 命中 `.cockpit-modal.el-drawer` 的默认亮色分支（--panel #FFF、--panel-2 #F1F5F9、--cyan #0284C7、--ink #0F172A、--line #E2E8F0）。面板底 = 纯白，头部 = 亚白微渐变；AI 气泡 = 白灰底 + 浅灰描边 + 深墨字；用户气泡 = cyan 22% 混浅灰的淡青底 + cyan 45% 混灰的描边（同样 10px 圆角右对齐）；工具过程条 running = 蓝色虚线 + 蓝色 8% 淡底；空态卡 = 灰白 + 虚线；输入区皮同 cockpit-modal 亮色规则。深浅切换靠 `<html>` 上 `dark` 类的双写（cockpit-tokens.scss 已内建），本次改动未新增双主题条件，收敛在 tokens 生效链本身。
  - 上线走查：仍建议登录 admin/admin123（http://localhost:80）右下角悬浮球实机核对；本机 dev-server 未挂起截图（Element Plus 抽屉 EP-2.13 mergeProps 规则+cockpit-tokens 皮链均在源码可静态验证，浏览器实机走查交主控/QA 收口）。
- 关联：REQ-AGENT-TBD；`docs/dev-pitfalls.md`——建议同步补一条"Element Plus 2.13 起 el-drawer/el-dialog 的 `custom-class` prop 已失效，必须用原生 `class` 绑定，`.cockpit-modal.el-drawer` 皮才能落到 drawer root"，附带排查动线（node_modules `drawer2.mjs` 无 customClass 命中即证）。同工程内 `web/src/views/energy/alert/list.vue:96`、`web/src/views/energy/alert/suggestion.vue`、`web/src/views/energy/analysis/profile.vue` 若干处仍用 `custom-class="cockpit-modal"`，同源根因可能存在（本批未修，主控裁决是否作为 FX-33 批量修）。

### FX-33 AI 对话面板：思考链 UI + 打字机流式渲染自查
- 日期 / 执行者：2026-07-20 / frontend-dev
- 提交：待提交
- 修复项：
  - AI 气泡消息模型从"独立 `tools[]` + 单一 `text`"重构为按事件到达顺序的 `blocks[]`（type ∈ thinking | tool | text），保证思考块、工具过程条、正文可任意穿插——同一轮多段思考也是按顺序追加为多个 thinking block（`web/src/layout/components/AiAssistant/index.vue`）；
  - 新增思考链 UI：进行中态显示"思考中…"标题（青色脉冲小点 + 细左边框强调）+ 内联滚动区（max-height 120px）+ 思考文本流式追加（每次 delta 后 nextTick 内自动滚到底），收到 `thinking_end` 后自动折叠成"已思考 X.X 秒"一行，可点击展开/收起回看全文；进行中态不响应折叠点击避免视觉抖动；
  - onDone 兜底：若后端在异常路径未送 `thinking_end`，收尾时把残留 active thinking block 强制置为 done + 折叠，避免"思考中…"永远转下去；
  - 流式渲染自查：`api/agent.js` 与新的 blocks 追加路径均无节流/攒批——每个 SSE event 直接 push/mutate Vue 响应式对象，DOM 逐字更新；`renderMarkdown` 是纯字符串→HTML 转换，随每次 delta 全量重渲染，当前无性能问题故未加节流；
  - 样式全部前缀 `.ai-drawer .ai-think*`，走 cockpit tokens（`--cyan/--ink-2/--ink-3/--panel-2/--line-strong`），明暗双主题共用同一套变量；符合 P-27（原生 class 挂类，不用 custom-class）。
- 影响的功能操作：全站右下角悬浮 AI 助手抽屉。用户提问后，AI 气泡上方会先出现暗色"思考中…"块（内文随模型推理逐字滚动），思考结束后自动折叠为"已思考 X.X 秒"一行；用户可点击该行展开回看思考全文。若一轮里模型多次思考，会依次出现多个思考块，与工具过程条、正文按到达顺序穿插；后端某轮完全没送 thinking_* 事件时，UI 与旧版一致（不出思考块，兼容零回归）。
- 验证：
  - `npm run build:prod`：通过（无新告警，本 vue 单文件从 493 → 566 行）；
  - `node --test`：66/66 全绿（AI 面板无 node 测试覆盖，改动集中在 vue 视图层；request/agent api 未动，回归依然通过）；
  - 后端 SSE 契约核对：`backend/module_energy/service/agent_chat_service.py:245-252` 三事件已就位（thinking_start 空 payload、thinking_delta {text}、thinking_end {elapsed_ms}），前端逻辑与之一一对齐；
  - **待联调点**：本批未挂 dev-server 走查——因用户告知后端 gzip 缓冲修复由并行 backend-stream-fix agent 处理，实机的"逐字打字机效果 + 思考流式追加"必须等其收口后一起走一次真实登录（admin/admin123 → http://localhost:80 右下角悬浮球）；若届时发现思考文本因 gzip 缓冲一次性 flush，与前端渲染无关，仍属后端信道问题。
- 关联：REQ-AGENT-TBD；`docs/agent-ai-design.md` §5 同日新增的 thinking 事件契约。

### FX-34 AI 问数 SSE 绕过 gzip + 接入思考链事件
- 日期 / 执行者：2026-07-20 / backend-dev
- 提交：待提交
- 修复项：
  - 根因（gzip 缓冲）：`backend/middlewares/gzip_middleware.py` 全局挂 Starlette `GZipMiddleware`，浏览器请求带 `Accept-Encoding: gzip` 触发 zlib 内部缓冲——即使上游 `chat_stream` 一个 event 一个 `yield`，压缩层也会攒到一定字节数才真正推给客户端，用户端表现是"回答一下子全出来"。之前 curl 默认不带 `Accept-Encoding` 所以看着是流式，掩盖了问题（P-27）。
  - `backend/middlewares/gzip_middleware.py`：新增 `ConditionalGZipMiddleware(GZipMiddleware)`，`__call__` 中先检查 `scope['type']=='http'` 且 `scope['path'].startswith('/agent/chat')` → 直接透传下游 app，不套 `GZipResponder`；其余请求走 `super().__call__` 保持原压缩行为。`add_gzip_middleware` 改为注册该子类。走"请求进入即按 path 决策"路线而非"响应回头看 content-type"，避免 Starlette 中间件里改响应头的时机复杂度。
  - `backend/module_energy/service/agent_chat_service.py`：`agentscope.event` import 补 `ThinkingBlockStartEvent / ThinkingBlockDeltaEvent / ThinkingBlockEndEvent`；`chat_stream` 循环中新增三个 elif 分支——start 记录 `block_id -> time.time()`、delta 直接透传 `{"text": ev.delta}`、end 用 monotonic 差算 `elapsed_ms` 并弹出映射。若模型该轮不出思考块（如非 reasoning 模型），三事件自然不出，前端零回归。事件字段名 `delta` / `block_id` 与 `TextBlockDeltaEvent` 同构（`model_fields` 已核实：`['id','created_at','metadata','type','reply_id','block_id','delta']`）。
- 影响的功能操作：
  - 全站右下角 AI 助手抽屉发问后，回答文本真正逐字流出（浏览器带 `Accept-Encoding: gzip` 也不再被缓冲）。若模型有思考链，抽屉先出"思考中…"暗色块随内容滚动、结束后折叠成"已思考 X 秒"（前端 FX-33 已就位）。
  - `/agent/chat` 单一路径透传，其他所有接口（登录、getInfo、报表 JSON 等）gzip 压缩行为不变。
- 验证：
  - 时序验证（`scratchpad/verify_sse.py`：带 `Accept-Encoding: gzip` 走 fetch 流式）：`content-encoding=None`（gzip 透传成功）；共 2299 个 SSE 事件跨 ~20s；delta 事件 1223 个、相邻间隔 median=0.6ms、p90=27.5ms、max=7517ms（工具调用间隔），呈标准打字机节奏而非最后一次性到达；前 12 事件序列为 `thinking_start` → 连续 `thinking_delta` 从 1777ms 起递增到达（"用户"/"想要"/"对比"/"分析"/…），证明思考链确实随模型生成滚动。thinking 事件总数 1067。
  - gzip 回归：`curl -H "Accept-Encoding: gzip" /captchaImage` → `content-encoding: gzip` ✅；登录后 `curl -H "Accept-Encoding: gzip" -H "Authorization: Bearer <token>" /getInfo` → `content-encoding: gzip` ✅，普通接口未受影响。
  - `pytest -q --deselect ...ReportIntegrationTest --deselect ...Act5WorkflowIntegrationTest` → 160 passed / 29 skipped / 117 subtests，零回归。
- 关联：REQ-AGENT-TBD；`docs/agent-ai-design.md` §5（SSE 契约含 thinking_*，评审裁决 2026-07-20 已入正文）；`docs/dev-pitfalls.md` P-26 / P-27。前端 FX-33 的思考链 UI 与本批后端事件源一起构成"思考链演示"闭环。

### FX-35 AI 对话流式渲染失效真根因——裸对象绕过 Vue 响应式
- 日期 / 执行者：2026-07-20 / 主控
- 提交：随本条提交
- 修复项：`AiAssistant/index.vue` 的 `send()` 里 `const bot = {...}` 裸对象 push 进响应式数组后，闭包持有的仍是裸引用——后续 `bot.blocks.push`/`text +=` 全部绕过代理 set 陷阱，流式期间 UI 零更新，直到 `streaming.value=false` 触发重渲染才一次性全量绘出（用户看到的"一下子全出来"）。gzip（FX-34）与本条是**叠加的两层根因**：gzip 修后网络层已逐字到达（httpx 经 vite 代理实测 339 事件递增时序），但渲染层仍被本 bug 卡死。修复：`bot` 创建改 `reactive()` 包裹；`thinking_start` 的活动块引用改从 `bot.blocks[len-1]` 回取代理，不留裸 `block` 引用。
- 影响的功能操作：AI 对话抽屉真正逐字打字机渲染；思考块随生成实时滚动而非结束后一次性出现。
- 验证：`npm run build:prod` ✓ 6.08s；`node --test` 66/66；网络层时序此前已独立验证（FX-34），本条修渲染层。
- 关联：REQ-AGENT-TBD；P-27 补充：**Vue 3 响应式数组里的对象,闭包必须持代理引用（reactive() 包裹后 push,或 push 后从数组回取）,裸引用 mutation 不触发渲染**——这类 bug 的标志性症状就是"流式数据最后一次性出现"。

### FX-36 设备画像页运行状态图 x 轴时间标签稀疏化
- 日期 / 执行者：2026-07-20 / frontend-dev
- 提交：待提交
- 修复项：`web/src/views/energy/analysis/profile.vue` 的 `renderStateEnergy`（"运行状态与能耗叠加"图，REQ-033）xAxis 之前把每小时点 formatter 直接切成 "HH:MM" 全量渲染，24 点/168 点全挤在一起。改动只调轴显示、不动数据逻辑：新增 `computeSpanHours` + `planTimeAxisLabels` 两个纯函数按窗口跨度选稀疏化策略——单日窗口（≤26h）显示整点每 3h 一个（00:00/03:00/06:00…），窗口 >3 天按天显示 "MM-DD HH时" 且 rotate 30°，≤3 天按半天间隔同格式；`axisLabel.interval` 用函数返回 `hour%step===0 && minute===0` 选中要显示的下标，`hideOverlap:true` 兜底；rotate 时 grid.bottom 从 42→54 让标签有落位。该页另外两图（能耗构成饼图、同类设备横向柱图）无时间轴，不受影响。
- 影响的功能操作：设备画像页（/energy/analysis/profile）"运行状态与能耗叠加"图 x 轴：单日窗口 8 个整点标签清晰不重叠；多日窗口每天 1 个或每半天 1 个 "MM-DD HH时" 标签小角度倾斜可读。明暗双主题走查：`axisLabel.color` 沿用 `pal.ink3` 由 `useChartTheme` 提供，切换顶栏夜览时 `watch(theme)` 触发 `renderCharts` 重绘，两主题下轴标签色对比度一致，无视觉回归。
- 验证：`npm run build:prod` ✓（profile chunk 17.50kb / gzip 6.36kb 相对基线基本持平）；`node --test` 66/66 全绿。
- 关联：REQ-033（运行状态与能耗叠加图轴显示微调）；act3 已收口页面视觉修饰，无契约变更、无 mock 变更。

### FX-37 AI 问数设备状态误判——头切片改整窗口代表性摘要
- 日期 / 执行者：2026-07-20 / backend-dev
- 提交：待提交
- 修复项：`backend/module_energy/service/agent_tools.py:892` `query_equipment_profile` detail 模式返回体里 `hourly_preview: hourly[:12]`——168 个小时点只让模型看到最前面 12 条（多为窗口第 1 天的 00:00–11:00，凌晨基本 stopped/standby），模型据此断"全天没运行"，与前端页面 168 小时视图明显矛盾（用户实测："问龙门吊1号，AI 答全天没运行，但页面显示有运行段"）。改为整窗口代表性摘要：①`state_summary = {total_hours, by_state:{state:{hours, ratio}}}` 聚合各状态小时数与占比；②`state_segments = [{from,to,state}, …]` 复用 service 层已算好的 `stateEnergySeries.stateSegments`（`equipment_profile_service.py:593 _state_segments`），MM-DD HH:MM 格式压缩；③段数 >30 时改给 `daily_state_hours = [{date, running, standby, stopped, …}]` 按天聚合，保总返回控制在 4KB 内。docstring `Returns:` 段同步改写并留 P-28 溯源说明。测试 `tests/test_agent_tools.py::QueryEquipmentProfileTest::test_detail_mode_returns_composition` 增断言：不再有 `hourly_preview`、`state_summary` 存在、`by_state` 各状态 hours 之和 = `hourly_point_count`（避免摘要丢点）、`state_segments`/`daily_state_hours` 二选一存在。
- 影响的功能操作：右下角 AI 助手抽屉问设备运行状态时，模型给出的结论与前端设备画像页（/energy/analysis/profile）状态时序图口径一致——不再出现"AI 说全天没运行但页面明明有运行段"这类断层。8 个 agent 工具其余 7 个未动，list 模式未动，对定时任务/巡检 agent 无影响。
- 验证：
  - 直调工具（`scratchpad/probe_profile.py`，PYTHONPATH=. .venv/bin/python 跑）DEMO_NOW=2026-07-12：
    - 默认 7 天窗口（07-06→07-13）GC-A1：`state_summary={"total_hours":168,"by_state":{"stopped":{"hours":85,"ratio":0.506},"standby":{"hours":53,"ratio":0.3155},"running":{"hours":30,"ratio":0.1786}}}`，7 段压缩后 `state_segments` 7 条。
    - 单日窗口（07-12→07-12）GC-A1：`state_summary={"total_hours":24,"by_state":{"stopped":{"hours":17,"ratio":0.7083},"standby":{"hours":7,"ratio":0.2917}}}`，7 段。
  - SQL 抽验（对齐 service 的 e_stat_hour + 子查询 e_equipment_status_log 口径，`object_type='equipment' object_id=1 energy_type_code='electricity'`）：7 天 total=168 stopped=85 standby=53 running=30；单日 07-12 total=24 stopped=17 standby=7 running=0——与工具返回 byte-for-byte 一致。07-12 是周日，DB 里本就无 running 记录，之前"页面有运行"的对比是相对于 7 天视图；工具修完后 7 天问法 AI 能看到 30 running 小时不再遗漏。
  - AI 复测（登录：GET /captchaImage → redis db2 `captcha_codes:<uuid>` 取答案 → 表单 POST /login 得 token → POST /agent/chat）：
    - 问"龙门吊1号今天运行状态怎么样"→ AI 回："**龙门吊1号（A区·龙门吊1号）今天（2026-07-12）全天未投入作业，处于停机/待机状态。** 停机 17 小时（占 70.8%）／待机 7 小时（占 29.2%）／实际作业 0 小时／总用电 463.8 kWh／峰值 22.9 kW（17:00）"，附完整状态时序段列表（00:00–07:00 停机 → 07:00–09:00 待机 → …）——与 DB 完全对齐。
    - 问"龙门吊1号最近一周运行情况如何"→ AI 回：总用电 9014.2 kWh、"运行 30 小时（17.9%）／待机 53 小时（31.6%）／停机 85 小时（50.6%）"，附每日运行小时表（7/6 6h→7/12 0h）——不再头切片误判。
  - `.venv/bin/python -m pytest -q --deselect tests/test_act5_report.py::ReportIntegrationTest --deselect tests/test_act5_workflow.py::Act5WorkflowIntegrationTest` → 160 passed / 29 skipped / 117 subtests，零回归；`QueryEquipmentProfileTest` 3/3 全绿。
- 关联：REQ-AGENT-TBD（设计稿 `docs/agent-ai-design.md` §4 工具契约；REQ-031/033 设备画像口径不变）；新增 `docs/dev-pitfalls.md` P-28。

### FX-38 采集与质量页两图 x 轴过密——固定 interval 改自适应整点稀疏
- 日期 / 执行者：2026-07-20 / 主控
- 提交：随本条提交
- 修复项：`web/src/views/raw-quality/index.vue` 状态点位色带图（*-STATUS,即用户截图的"运行状态"图）与原始读数曲线图,x 轴均写死 `axisLabel.interval: 3`——15min 采样跨 11h 窗口时几十个 HH:MM 标签全量挤压成不可读("06:20 06:40 07:00…"糊在一起)。FX-36 修的是设备画像页,漏了本页（定位失误:同名"运行状态"图存在于两个页面）。新增 `planMinuteAxisInterval(labels)`:只在整点出标签,整点数 >8 时按 2h/3h… 递进,`hideOverlap: true` 兜底;两处 axisLabel 同步替换。
- 影响的功能操作：采集与质量页（/raw-quality）点选任意点位:状态带图与读数曲线的 x 轴标签清晰稀疏（截图窗口 06:20–17:20 → 约 5-6 个偶数整点标签）;数据、色带、tooltip、补传/重算交互零改动。
- 验证：`npm run build:prod` ✓ 6.18s;`node --test` 66/66。
- 关联：REQ-010/018（原始读数曲线）、REQ-013（状态点位）;第二幕已收口页面的轴显示微调,无契约变更。

### FX-39 状态图两处交互裁决落地——段起止时刻做刻度 + 状态入悬停
- 日期 / 执行者：2026-07-20 / 主控
- 提交：随本条提交
- 修复项（两图,均为用户截图裁决）：
  - 采集与质量页状态色带图（raw-quality/index.vue）：x 轴刻度从"整点稀疏"改为**各状态段的起止时刻**——`computeStatusRuns` 补 `startIdx/endIdx`,轴 interval 只在段起点+窗口终点出标签;相邻边界 < 总长 5% 时跳过后者防重叠,窗口终点被挡时回头替换前一个保证终点可见,hideOverlap 兜底。
  - 设备画像页"运行状态与能耗叠加"图（profile.vue + shared/act3.js）：markArea 顶部的状态文字标签整排去除（段多时"停机待机运行…"挤压不可读）——`buildStateAreas` 加 `{withLabels}` 选项,profile 传 false（唯一调用方,告警窗口标签不受影响）;tooltip 换自定义 formatter:悬停显示"时间 + 功率 + 当前状态"（按 stateSegments 区间查 ts 所属状态）。
- 影响的功能操作：状态色带图刻度即状态切换时刻,一眼读出各段起止;叠加图顶部干净,鼠标悬停任意位置即见该时刻运行状态。数据与其余交互零改动。
- 验证：`npm run build:prod` ✓ 6.10s;`node --test` 66/66。
- 关联：REQ-013（状态点位）、REQ-033（叠加图）;FX-38 的整点稀疏方案在状态图上被本条取代,读数曲线图仍用整点稀疏。

### FX-40 巡检页三小修——手动巡检超时误报 + 统计卡两处破折号
- 日期 / 执行者：2026-07-20 / 主控
- 提交：随本条提交
- 修复项：
  - 「立即巡检」前端弹"系统接口请求超时"：巡检 workflow 同步执行 ~80-120s,超过 axios 全局 timeout,前端先报错而后端仍在跑（报告实际成功落库,纯 UX 误报）。`api/agent.js runInspection` 单独放宽 timeout 至 300s。
  - 统计卡"数据完整率 —"：后端取 `coverage.overall`（不存在,恒 None）,实际工具返回是 `coverage.pct`——修正取数路径;前端别名链补 `data_quality_coverage` 兜底,已存的老报告也恢复显示。
  - 统计卡"检查项 —"：后端 stats_json 原本不含工具调用计数,新增 `checks = 6 + 抽查设备数`（与卡片副标"本轮工具调用次数"对齐）。
- 影响的功能操作：AI 巡检页点「立即巡检」不再误报超时（按钮 loading 至完成）;新报告五张统计卡全部有值;老报告的完整率恢复显示（检查项字段老报告确实没有,仍为 —,属预期）。
- 验证：前端 build ✓ 7.25s + 66/66;后端 test_agent_chat_inspection 5/5。
- 关联：REQ-AGENT-TBD;FX-31（巡检链路）/FX-30（前端页）的收尾修补。
