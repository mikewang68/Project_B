# AI Agent 功能设计（问数 + 自主巡检）

> 状态：设计稿 v1（2026-07-17），待评审后拆任务开发。
> REQ 锚定：本能力为 demo 范围外新增，知识库 PRD 增补待办（口径：先原型后补录，代码注释统一标 `REQ-AGENT-TBD`）。
> 选型结论已实测验证（AgentScope 2.0.4 API 冒烟通过），详见 §2。

## 1. 功能定位

在现有五幕演示系统之上新增两个 AI 能力，共用一套工具封装层：

| 能力 | 形态 | 触发方 | 实现模式 |
|---|---|---|---|
| **智能问数** | 对话面板（全站可唤起） | 用户自然语言提问 | ReAct agent（模型自主选工具循环） |
| **自主巡检** | 巡检报告页 | APScheduler 定时 / 手动触发 | 固定 workflow（代码编排步骤，LLM 只做判读与报告生成） |

设计原则（对齐业界共识）："能 workflow 不 agent"；工具只读；模型不碰 SQL。

## 2. 技术选型（已定，不再重议）

| 项 | 选择 | 说明 |
|---|---|---|
| agent 框架 | **AgentScope 2.0**（Apache-2.0） | 阿里出品，生产级；`FunctionTool` 从类型标注+docstring 自动生成工具 Schema（已实测，中文 docstring 解析正常）；内置权限钩子/middleware/MCP 支持。注意：网上 1.x 教程已过时，以官方 2.0 文档为准 |
| 模型接入 | OpenAI 兼容接口，双配置 | `OpenAICredential(base_url, api_key)` 指向服务器大模型（部署中）；开发期可切本机 Ollama（`OllamaChatModel`）。切换零代码改动 |
| 模型 | Qwen3 系列（服务器版本以实际部署为准） | function calling 能力是硬要求；接入后必须先跑 §7 回归问题集 |
| 新增依赖 | `agentscope` | 仅此一个（其传递依赖含 openai SDK） |

配置项（`backend/.env.dev`，不入库）：

```
AGENT_LLM_BASE_URL=<服务器地址>/v1     # 或 http://localhost:11434/v1 (Ollama)
AGENT_LLM_API_KEY=<key>
AGENT_LLM_MODEL=<模型名>
AGENT_LLM_VERIFY_SSL=false             # 服务器为自签证书
```

## 3. 总体架构

```
web/  对话面板(SSE流式) ─┐                  ┌─ 巡检报告页
                          │                  │
backend/module_energy/
  controller/agent_controller.py   /agent/chat  /agent/inspections*
  service/agent_chat_service.py    ← AgentScope ReAct Agent + Toolkit
  service/agent_inspection_service.py ← 巡检 workflow（APScheduler 注册）
  service/agent_tools.py           ← 工具封装层（唯一的"语义层"）
        │ 直接调用（进程内，不走 HTTP）
  service/{overview,alert,cost_query,raw_quality,suggestion,equipment_profile}_service.py
                          │
                        MySQL（只读) + ai_inspection_report（新表，唯一写入点）
```

## 4. 工具清单（工具封装层 `agent_tools.py`）

全部**只读**，直接复用现有 Service 类方法。参数一律枚举/格式锁死，模型无法传出界。

| # | 工具名 | 复用方法 | 参数（Schema 约束） | 用途 |
|---|---|---|---|---|
| 1 | `query_overview` | `OverviewService.get_overview_summary` | `date`(YYYY-MM-DD) | 总览：能耗/成本/告警概况 |
| 2 | `query_alerts` | `AlertService.list_alerts` + `get_statistics` | `severity`(enum)、`status`(enum)、`area`(enum: A区/B区/全部)、`date_range` | 告警列表与统计 |
| 3 | `get_alert_detail` | `AlertService.get_detail` | `event_id`(int) | 单告警证据链详情 |
| 4 | `query_cost_month` | `CostQueryService.get_month_view` | `month`(YYYY-MM)、`area`(enum) | 月度成本视图（含重算版本） |
| 5 | `query_cost_trace` | `CostQueryService.get_trace` | `month`、`version`(int, 可选) | 成本口径追溯 / 版本差异（差异化卖点） |
| 6 | `query_data_quality` | `RawQualityService.get_summary` | `date_range`、`area`(enum) | 数据质量着色汇总（缺数/补传） |
| 7 | `query_suggestions` | `SuggestionService.list_suggestions` | `status`(enum 三档闭环)、`date_range` | 节能建议工单看板 |
| 8 | `query_equipment_profile` | `EquipmentProfileService.list_profiles` / `get_profile` | `equipment_id`(enum 12 台)、`period` | 设备画像/班次对比 |

约束纪律：
- 工具函数内部固定使用**只读数据范围**（等价 `energy_mgr` 视角），不接受模型传入账号/权限参数。
- 每个工具返回**裁剪后的 JSON**（只留模型需要的字段，控制上下文长度），单工具返回上限 ~4KB，超限自动截断并附 `truncated: true`。
- 工具执行异常不抛出：返回 `{"error": "<人话描述+正确格式提示>"}` 回喂模型自纠。
- 时间语义（"上周""昨天"）统一按 `DEMO_NOW` 解析（复用 `demo_now_util.py`，见 dev-pitfalls 坑点）。

## 5. 智能问数（agent 链路）

- `POST /agent/chat`：请求 `{session_id, message}`；响应 SSE 流。**前端必须用 `fetch` + ReadableStream 读流（禁用原生 EventSource——它带不了 Authorization 头），token 走标准请求头**。**SSE 路径必须绕过 GZipMiddleware**（gzip 的 zlib 缓冲会把逐字 delta 攒成一次性输出——浏览器带 Accept-Encoding 必触发,curl 不带所以测不出来,评审裁决 2026-07-20）。事件类型（含思考链,同日增补）：
  - `thinking_start` / `thinking_delta`: `{text}` / `thinking_end`: `{elapsed_ms}` — 模型思考链,前端渲染为可折叠的"思考中…"暗色块,结束后折叠为"已思考 X 秒"（可展开回看）
  - `tool_call`: `{name, arguments}` — 前端展示"正在查询XX…"过程条（演示亮点）
  - `tool_result_summary`: `{name, ok}`
  - `delta`: 回答文本增量
  - `done`: `{elapsed_ms, tool_calls: n}`
- Agent 配置：`Agent(system_prompt=能源域提示词, model, toolkit)` + ReAct，**最大循环 6 轮**（防失控），单请求超时 60s。
- 会话记忆：内存级（dict[session_id] → 最近 10 轮），demo 不落库。**约束：仅单进程 uvicorn 有效（dev 模式即此），未来多 worker 需外置到 Redis——代码注释标明**。
- system_prompt 要点：角色设定（能源管控助手）、口径声明（数据来自演示环境）、工具使用规则（时间先转 DEMO_NOW 口径）、回答风格（结论先行，引用具体数字）。

## 6. 自主巡检（workflow 链路）

**步骤写死在代码里**，每步产出交给 LLM 判读，最后汇总生成报告：

```
1. query_data_quality(近7天, 全部区)      → LLM 判读：缺数/补传异常点
2. query_alerts(未处理, 全部区)            → LLM 判读：待办告警优先级
3. query_overview + query_cost_month      → LLM 判读：能耗/成本环比异常
4. query_equipment_profile(逐台抽查高耗)   → LLM 判读：设备级异常
5. LLM 汇总 → 结构化巡检报告（JSON）→ 写入 ai_inspection_report
```

- 触发：APScheduler 每日 07:00（`module_task` 注册）+ `POST /agent/inspections/run` 手动触发（演示用）。**`report_date` 一律取 DEMO_NOW 的日期（不是真实系统日期）**，否则报告日期与"近7天"数据窗口对不上（DEMO_NOW 坑点变种）。
- 报告结构：`{summary, findings: [{category, severity, evidence, suggestion, related_ids}], stats}` —— findings 与 INJ-01–08 注入的异常呼应，`related_ids` 可跳转对应页面。
- **定位文案规则（评审裁决 2026-07-17）**：findings 的标题与证据一律用**业务名称链**定位——"区域 → 设备 → 计量对象 + 触发时间"（如"B区 2号装卸机 · 皮带电机分项电表"），**禁止在文案中出现内部编号**（计量点 ID、告警 ID 等）；编号只作为 `related_ids` 跳转参数存在。问数回答同样遵守。system_prompt 与报告生成提示词中需明确此规则，工具返回的 JSON 需同时携带 `id` 与 `display_name` 字段供模型引用后者。
- 接口：`GET /agent/inspections`（列表）、`GET /agent/inspections/{id}`（详情）。
- LLM 不可用时：任务记录失败状态，不产出半成品报告；演示前必须有 ≥1 份成功报告兜底。

新表 DDL（datagen/ddl/ 新增迁移，data-engineer 负责）：

```sql
CREATE TABLE ai_inspection_report (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  report_date DATE NOT NULL,
  trigger_type VARCHAR(16) NOT NULL,        -- scheduled / manual
  status VARCHAR(16) NOT NULL,              -- success / failed
  summary VARCHAR(1024),
  findings_json JSON,
  stats_json JSON,
  model_name VARCHAR(64),
  elapsed_ms INT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_report_date (report_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 7. 可靠性工程（演示零翻车三件套）

1. **Schema 锁参**：区域/设备/状态全部枚举；日期用格式正则；模型填不出界外值。
2. **错误回喂**：参数校验失败/查询为空时返回可读错误给模型重试（上限 2 次）。
3. **回归问题集**：`backend/tests/test_agent_regression.py` 维护 15+ 演示问题 → 期望工具与参数的断言（mock LLM 不适用，此测试需真模型，标 `@pytest.mark.llm` 默认跳过，演示前手动跑）。首批问题示例：
   - "上周A区能耗为什么偏高" → query_overview + query_alerts
   - "成本第3版和第4版差在哪" → query_cost_trace
   - "哪些计量点最近缺数" → query_data_quality
   - "3号装卸机今天状态怎么样" → query_equipment_profile

## 8. 前端（frontend-dev）

1. **对话面板**：全站右下角悬浮球唤起抽屉；消息流 + 工具调用过程条（"🔍 正在查询A区能耗…"）+ Markdown 渲染 + 打字流式；深浅双主题适配（对齐 FX-24/25 双主题体系）。
2. **巡检报告页**：新菜单"AI 巡检"；报告列表 + 详情（findings 按 severity 着色，related_ids 跳转对应五幕页面）；手动"立即巡检"按钮（loading 态 + 完成刷新）。
3. 权限：菜单挂 `energy_mgr`/`admin` 可见（K.6 窄授权口径同报表页处理）。

## 9. 任务拆解与顺序

| 序 | 任务 | 负责 agent | 依赖 |
|---|---|---|---|
| 0 | ✅ **穿刺验证（2026-07-20 主控完成）**：AgentScope 2.0 + 异步真工具 + DeepSeek 端到端通过。结论：①异步工具/AsyncSession 原生支持；②`reply_stream` 事件与 SSE 契约一一映射（ToolCallStart→tool_call、TextBlockDelta→delta、ReplyEnd→done）；③**必须用 ReadOnlyTool 子类覆写 check_permissions 为 ALLOW**（默认 ASK 会静默挂死，见 dev-pitfalls P-26）；④Msg.content 是内容块列表；⑤ReActConfig.max_iters 默认 20 需收紧为 6；⑥flash 模型单问带一次工具调用约 20s，前端 loading 文案按此设计。参考实现：scratchpad spike3.py 模式 | 主控 | 已完成 |
| 1 | `ai_inspection_report` DDL 迁移 + **「AI 巡检」菜单与 energy_mgr/admin 权限种子（sys_menu）** + 造数脚本兼容（--reset 建表/种子） | data-engineer | — |
| 2 | `agent_tools.py` 工具封装层（8 工具，`id`+`display_name` 双字段）+ 单测（不依赖 LLM） | backend-dev | — |
| 3 | 问数链路（chat service + SSE controller，fetch 流式契约） | backend-dev | 0, 2 |
| 4 | 巡检链路（workflow + APScheduler + 报告落库，report_date=DEMO_NOW） | backend-dev | 1, 2 |
| 5 | 对话面板 + 巡检报告页 | frontend-dev | 3,4 契约（可先按本文档 mock） |
| 6 | 回归问题集 + 演示彩排 | qa-reviewer | 3,4,5 + 服务器模型可用 |

**时间线（里程碑 2026-08-01）**：任务 0/1/2 即刻并行（0.5–1.5 天）；任务 3/4 随后（~2 天）；任务 5 并行推进（~2 天）；**为任务 6 预留 ≥3 天彩排缓冲**，即后端前端应在 7-25 前联调完毕。
**关键路径风险**：服务器大模型就绪日期未定（阻塞任务 6 正式彩排）。缓解：用户云端模型可先行用于任务 0 穿刺与任务 3 开发调试；本机 Ollama（qwen3:8b）为第二备用通道。

## 10. 纪律核对清单

- [x] 许可证：AgentScope Apache-2.0，作为 pip 依赖使用，非二次开发 ✅
- [x] 数据安全：工具只读、全部为构造数据；LLM 部署在自有服务器，数据不出内网 ✅
- [x] API key 只放 `.env.dev`，不入库 ✅
- [ ] 知识库 PRD 增补（用户负责誊写，代码统一标 `REQ-AGENT-TBD`）
- [ ] 演示前跑 `@pytest.mark.llm` 回归问题集全绿
