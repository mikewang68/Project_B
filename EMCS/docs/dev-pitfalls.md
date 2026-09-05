# 开发坑点底账（持续维护，更新至 2026-07-16）

> 性质：主控维护的内部经验记录，不是需求文档。每条格式：**坑 → 真实案例 → 规避动作**。
> 新增坑随幕收口时补录；开发 agent 开工前必读（b-demo-conventions skill 有指引）。
> 各 agent 角色私有教训在 `.claude/agent-memory/<角色>/`，本文件是全队公共层。

## 一、报告与验收类（最高频翻车区）

### P-01 回报数字 ≠ 落库实况
- **案例**：data-engineer 四次口子——DDL 报"23 表齐"实际 22 张（导入被截断没数）、终报工单数 692 实数 687（引用 dry-run 仿真值）、被指出后回信仍写"应等于 692"（同 session 二次估算）。
- **规避**：终报每个量化字段（表数/行数/命中数/状态分布）必须先跑查询、粘输出、再誊报告。"预计/约/应为/应该是"禁入实测表格。主控验收一律查库/curl 复测，不信回报数字。

### P-02 告警数据 seed 化冲动
- **案例**：backend-dev 三次想直接 seed `e_alert_event` 走捷径。告警必须规则引擎对构造数据真算产出——"20 条告警全引擎真算"是核心卖点纪律，seed 了演示时重算/差异叙事就是假的。
- **规避**：`e_alert_event` 绝不 seed；INJ 注入在 datagen 层做，告警由 backend 引擎跑出来。验收看"INJ×9 全命中"而不是行数。

### P-03 裁决执行遗漏后当新问题重报
- **案例**：backend-dev 漏执行一条已有裁决，后来把该行为当 bug 报成"待裁决"，浪费一轮往返。
- **规避**：开工前通读 `docs/mock-contracts.md` 全部"裁决"段落（有时间戳，可能晚于你上次开工）；报"待裁决"前先搜该文档确认是否已裁过。

### P-04 多 agent 消息交错丢指令
- **案例**：并行派工时 agent 消息高频交错，指令被吞或执行序错乱。
- **规避**：主控给指令必须设检查点（"做完 X 回报 Y 的查询输出"），事后逐点核对；不依赖 agent 自觉。agent 不自行 commit，主控统一提交。

## 二、契约与编号类

### P-05 两套规则编号混用必错
- **案例**：qa-reviewer 按需求基线 K.7 编号评审 mock 告警，4 条映射判错（K.7-R03=数据跳变 vs demo-R03=连续零值，同号异义），整轮评审重裁重发。
- **规避**：代码/mock/数据一律用 PRD §7.2 demo 表 R01–R11；对照 K.7 只走 §7.2 表的"对应 K.7"列。全局裁决在 mock-contracts.md「全局约定：规则编号」。

### P-06 引用不存在的契约来源
- **案例**：早期团队约定引用"PRD 第 10 章 mock 契约"，经核实该章根本不存在（第 10 章是非功能需求），下游按幻影契约空转。
- **规避**：契约事实来源优先级已裁定：PRD §5 页面字段表 > `docs/mock-contracts.md` > 代码内 mock 头注释；引用知识库章节前先翻原文确认存在。

### P-07 契约歧义自行拍板
- **规避**：契约有歧义先登记、主控裁决后动代码（已入 skill 第 6 条）。裁决结果必须当天落进 mock-contracts.md，否则等于没裁（见 P-03）。

## 三、数据口径类

### P-08 跨计量层级相加
- **规避**：聚合严禁跨层级相加——区级=区总表、设备级=设备表、system=区之和。装表结构里区总表 ≠ ∑设备表（有公摊/损耗），加错口径签名就对不上。

### P-09 "当日/now" 用 wall-clock
- **案例**：构造数据止于 2026-07-12，用真实时钟算"今日"全部为空。
- **规避**：一律用 `DEMO_NOW`（.env.dev，2026-07-12 23:59）；趋势 NOW 游标、"今日触发"计数同源。

### P-10 数据设计约束 scope 没写清被误读
- **案例**：工单生成"不产生 cancelled"被读成全局承诺，实际只限核心覆盖段；PN-B1 padding 段允许 cancelled（供归因页多态过滤）。
- **规避**：描述构造约束必须限定到具体路径/代码段，两条路径行为写在同一句里。

### P-11 骨架种子清毒不彻底
- **案例**：中性化只清了派工点名的表，sys_notice 里两条 vfadmin 品牌通知漏网。
- **规避**：清理/扫毒类任务除点名表外，主动 grep 全库同类字段（sys_* 的 leader/email/phone/title/content），命中数写进报告。

## 四、前端呈现类（走查修复批 0e41ff7 的教训）

### P-12 mock 残留复制进新页
- **案例**：C 类清理批清出 6 项——硬编码 "NOW 06:12" 游标、写死的告警角标/计量点统计数、写死的补传目标点位与受影响日期。新页照抄旧页模式时把 mock 时代硬编码一起带过去。
- **规避**：开发新页复用旧页代码时，逐项检查常量是否应绑契约字段；QA 每批扫"写死的数字/日期/点位名"。

### P-13 无 PRD 锚点的自造 UI/字段
- **案例**：KPI spark 迷你曲线（PRD 无此字段、后端从未产出）、总览时间筛选组（§5.1 KPI 是固定口径）——都做了又拆，白费两轮。
- **规避**：每个 UI 元素/契约字段先找 PRD 锚点，找不到就停下问主控（skill 第 1 条）；`docs/ui-req-anchor-audit.md` 是三分类底账，每开发一页要扩充。

### P-14 数值取整与曲线形态
- **案例**：小时负荷用 int 取整，水/气小量值曲线整条归零；累计表读数直接画曲线成一条平线。
- **规避**：非电介质保留 2 位小数；曲线画增量（delta）不画累计值；状态类点位走色带渲染，不进数值曲线。

## 五、环境与流程类

### P-15 为图省事改演示配置
- **案例**：curl 验证嫌验证码麻烦，申请"临时关闭验证码"。
- **规避**：不许改 sys_config/演示配置绕行，会留配置漂移。全队通用配方：captchaImage 取 uuid → `docker exec bdemo-redis redis-cli -n 2 get "captcha_codes:<uuid>"` → form-urlencoded 登录（token 在响应顶层 `token` 字段，不是 data.token）。完整脚本见 `.claude/agent-memory/frontend-dev/curl-auth-recipe.md`。

### P-16 MySQL 导入乱码
- **规避**：mysql 导入/连接必带 `--default-character-set=utf8mb4`，否则中文种子数据乱码。

### P-17 写操作破坏演示可复位性
- **规避**：任何写接口（补传/重算）产生的状态变化，必须能通过 `python datagen/generate_demo_data.py --reset` + `POST /pipeline/bootstrap?force_republish=true` 完整复位。新写接口交付时把复位验证跑一遍。

### P-18 后台服务被宿主回收
- **规避**：dev 环境会周期性回收后台进程，backend/web 服务掉了直接重启即可，不要当故障排查。

### P-19 可选字符串校验器误伤显式 null
- **案例**：第四幕 transition VO 的通用字符串 validator 直接对值调用 `.strip()`；客户端按契约显式提交 `null` 时触发 `AttributeError`，本应可选的字段变成 500。
- **规避**：Pydantic 可选字段的 `before` validator 先处理 `None`，再做字符串规整；每个可选字段同时覆盖“省略”和“显式 null”两类回归测试。

### P-20 展示序列未纳入口径签名
- **案例**：第四幕四维验证先对整窗聚合结果签名，再追加日序列；日序列数值被替换时签名不变，归档快照无法证明图表数据未漂移。
- **规避**：所有可影响判断或图表的数值先进入 canonical payload，再生成签名；仅 labels/note 等纯展示文本可在签名后追加。backend/datagen 共用同一日序列计算函数，并用“改任一点则签名变化”的反向测试守住。

### P-21 破坏性集成测试复用了脏 fixture
- **案例**：第四幕工作流集成会移动建议状态并新增日志；若直接复用上一轮隔离库，测试可能靠残留状态通过或产生不稳定失败。
- **规避**：破坏性集成测试开场断言 reset/bootstrap 的权威计数与初始状态；每轮使用独立数据库并在结束后删除。未配置隔离库时可以显式 skip；已经配置但 fixture 非 fresh 必须 fail，不得把脏库也静默跳过。

### P-22 列表限域但聚合统计旁路
- **案例**：第四幕运维列表/详情已按责任账号限域，归档复盘的 count、有效率和规则提示却曾读取全局数据，形成侧信道式越权。
- **规避**：同一页面的 list/detail/count/hint/summary 必须复用同一权限范围；每个聚合接口至少用全局角色与受限角色跑一组对照测试，不能只验证明细列表。

### P-23 业务边界只靠“缺少配置”维持
- **案例**：第四幕 R08 在 reset 后没有建议模板，因此表面上不可转换；但运行期一旦误建 R08 模板，通用转换接口会继续进入只适用于 R06 的优先级公式。
- **规避**：范围边界必须在服务端读状态和写接口同时显式守卫，不能把“当前没有模板/数据”当授权条件；负向集成测试要主动插入越界配置，确认状态仍为不可用、写入仍被拒绝且数据库计数不变。

### P-24 破坏性测试失败后遗留污染扩散到全量 discover
- **案例**：第五幕全量 discover 暴露：前四幕多个旧破坏性测试各自留过尾巴——建议流程没复位历史夹具、current-only 测试没删自建历史成本、配置测试没恢复权威单价/分摊；单独跑各自绿，全量串跑互相污染。更隐蔽的是失败用例中途退出时遗留未提交事务，阻塞后一个测试的 schema reset，报错现场在无辜的下游测试（FX-19 修复批）。
- **规避**：破坏性测试的清理必须放 teardown/finally（失败也执行），且清理含"回滚/关闭本测试打开的事务与连接"；新增破坏性测试交付时必须跑一次全量串行验证，不许只交 focused 结果。排查全量挂而单跑绿时，先查上游测试的遗留事务与残留数据，再怀疑当前用例。

### P-25 隔离库测试配置掩盖生产 session 行为差异
- **案例**：第五幕测试 session 工厂默认 `expire_on_commit=False`，全绿；共享库首次真实 bootstrap 用生产 `AsyncSessionLocal(expire_on_commit=True)`，`CostService.initialize_v1` 提交后再读已过期 ORM 行触发异步 `MissingGreenlet`——成本已提交但规则阶段中断（FX-21 修复批）。
- **规避**：commit 后不许再碰 ORM 实例属性；需要统计/回显的值在提交前冻结到普通 Python 容器。凡走生产 session 工厂的入口（bootstrap/定时任务），至少配一条显式 `expire_on_commit=True` 的集成回归；隔离库跑绿不等于生产配置跑绿。

### P-26 AgentScope 自定义工具默认 ASK 权限，agent 静默卡在确认等待
- **案例**：任务 0 穿刺（2026-07-20）：AgentScope 2.0 的 `FunctionTool.check_permissions` 默认返回 `ASK`（"Custom function tools must be explicitly allowed by the user"），`reply_stream` 走到 `RequireUserConfirmEvent` 后停住等确认——外层若只消费文本/工具事件，表现为"模型反复发起工具调用但永远没有结果、没有回答"，无任何报错。`is_read_only=True` 不影响该行为（它只是元数据标记）。
- **规避**：只读查询工具统一用子类覆写 `check_permissions` 返回 `PermissionDecision(behavior=PermissionBehavior.ALLOW, message=...)`（message 为必填位置参数，漏了是 TypeError）。消费 `reply_stream` 的外层必须显式处理 `RequireUserConfirmEvent`（至少记日志报警），防止未来新增工具忘覆写时静默挂死。另两个同批小坑：`Msg(content=...)` 只收内容块列表（`[{"type":"text","text":...}]`），裸字符串是 pydantic ValidationError；`ReActConfig.max_iters` 默认 20，问数链路须显式收紧到 6。

### P-27 AgentScope 1.x/2.0 API 混淆 + EP 2.13 custom-class 失效（AI 面板双故障）
- **案例**：AI 问数二问起"不可用"——`agent_chat_service` 用了 `agent.memory.add()`（AgentScope 1.x API），2.0 的 `Agent` 无 `memory` 属性，`AttributeError` 死在首个 SSE 事件前（首问偶尔能答是因为无历史不走该分支）。穿刺只测了单轮，漏了多轮。同批 AI 对话抽屉纯黑无样式——Element Plus 2.13 起 `el-drawer`/`el-dialog` 的 `custom-class` prop 已移除（`drawer2.mjs` 用 `mergeProps($attrs)` 只认原生 `class`），`custom-class="cockpit-modal"` 被当未知 HTML 属性丢弃，`.cockpit-modal.el-drawer` 皮不落 → cockpit 变量全空。**唯独 AI 抽屉崩**是因为它 `:append-to-body="true"` 且挂在 layout 层（无 `.cockpit-page` 祖先）teleport 到裸 body；五幕抽屉都不 append-to-body（EP drawer 默认 `appendToBody=false` → teleport disabled → 原地渲染），留在 `.cockpit-page` 内继承变量，故 custom-class 失效对它们只是少了头部边框等额外皮，核心配色仍在。
- **规避**：①AgentScope 2.0 无 `agent.memory`，多轮历史随 `reply_stream([*history, msg])` 传入；接新框架穿刺必须覆盖多轮，不止单轮。②EP 2.13+ 抽屉/对话框一律用原生 `class` 不用 `custom-class`；teleport 到 body 的弹层若依赖 CSS 变量，变量必须在弹层 root（`.cockpit-modal`）自声明，不能指望页面祖先。③列表接口 `{data:{items:[]}}` 前端解析先取 `data.items` 再兜底，勿让 `res.data`（对象）先命中被 `Array.isArray` 判空。

### P-28 给 LLM 的工具返回裁剪必须是"代表性摘要"，不许头部切片
- **案例**：`query_equipment_profile` detail 模式为控上下文，把 168 个小时点的 `points` 只回 `hourly_preview: hourly[:12]`。用户问"龙门吊1号运行状态"，模型只看见窗口最前 12 小时（多为周日凌晨的 stopped/standby），自信下结论"全天没有运行"；前端页面基于全部 168 点画的状态时序里明明有运行段——AI 与页面口径不符（FX-37）。头部切片的问题是**样本非代表性**：时间序列的头部片段通常是最规律/最边缘的一段（早晨/凌晨），完全丢掉了工作时段。
- **规避**：LLM 工具返回若必须缩量，只允许两种形态：①**整窗口聚合统计**（各类别 count/hours/sum 及占比）——不丢分布；②**去冗余压缩**（连续同状态段合并成 `{from,to,state}` 段列表；段数过阈按更粗粒度再聚一次，如按天/按小时桶）——保结构。严禁 `list[:N]` / `list[-N:]` 头/尾切片交给模型判断——模型不会知道 "N 之外的数据你没看到"，会拿手里那截当全貌。同时 docstring 的 `Returns:` 段必须精确描述现在给的是"整窗口摘要"（模型看到的工具说明就这一份），否则模型会按老直觉当作原始点序列使用。
