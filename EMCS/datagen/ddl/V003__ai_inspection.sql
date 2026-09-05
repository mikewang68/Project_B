-- ============================================================
-- B-Demo AI Agent 域业务表 DDL（V003）
-- 依据：docs/agent-ai-design.md §6（自主巡检）
-- REQ 锚点：REQ-AGENT-TBD（demo 范围外新增，PRD 增补待办）
-- 蓝本纪律：本表由真实巡检 workflow 写入，datagen 不种假数据；
--          仅创建结构，报告数据必须由 backend 侧 agent_inspection_service 产出。
-- 约定：MySQL 8 + utf8mb4；不使用 FK 约束（与既有 e_* 表一致）；
--       findings_json / stats_json 使用原生 JSON 类型便于后续检索与前端渲染。
-- ============================================================

-- ------------------------------------------------------------
-- 1. ai_inspection_report ｜ AI 自主巡检报告（REQ-AGENT-TBD）
--    结构对齐 docs/agent-ai-design.md §6 草案；
--    trigger_type：scheduled=APScheduler 每日 07:00 触发；manual=/agent/inspections/run 手动触发；
--    status：success/failed；LLM 不可用时任务记 failed，不产半成品。
-- ------------------------------------------------------------
drop table if exists ai_inspection_report;
create table ai_inspection_report (
  id             bigint(20)   not null auto_increment comment '主键',
  report_date    date         not null                comment '报告日期（取 DEMO_NOW 的日期，非真实系统日期）',
  trigger_type   varchar(16)  not null                comment '触发类型（scheduled 定时 / manual 手动）',
  status         varchar(16)  not null                comment '报告状态（success 成功 / failed 失败）',
  summary        varchar(1024) default null           comment '巡检结论摘要（LLM 汇总）',
  findings_json  json         default null            comment '结构化发现列表（category/severity/evidence/suggestion/related_ids）',
  stats_json     json         default null            comment '统计快照（近7天缺数/告警/成本环比等汇总指标）',
  model_name     varchar(64)  default null            comment '本次巡检使用的模型标识（含 base_url 后段+model 名，便于回溯）',
  elapsed_ms     int(11)      default null            comment '巡检 workflow 端到端耗时（毫秒）',
  created_at     datetime     not null default current_timestamp comment '记录写入时间',
  primary key (id),
  key idx_report_date (report_date)
) engine=innodb auto_increment=1 comment = 'AI 自主巡检报告（agent_inspection_service 唯一写入点）';
