-- ============================================================
-- B-Demo 能源域业务表 DDL（V001）
-- 依据：知识库《demo-演示数据构造规范》§1 对象模型、§3 曲线形态、§4 异常注入清单
--        PRD-能源管控系统 §5 页面字段、§7 参数与规则库、§8 数据需求、附录 C 状态/口径
-- 蓝本纪律：MyEMS database/install schema 只作参考结构（空间-计量点-能源类别-统计四层），
--          本文件表名/列名/注释均按知识库对象模型自行命名，不复制其 SQL 文本、不引入品牌资源。
-- 骨架：与 datagen/skeleton/skeleton-init.sql（RuoYi-Vue3-FastAPI MIT）同库；
--       系统表 sys_* 保持骨架不动，能源域表统一 e_ 前缀。
-- 约定：MySQL 8 + utf8mb4；不使用 FK 约束（与 RuoYi 骨架一致）；
--       所有业务时间字段用 datetime；金额/能耗用 decimal(20,4)。
-- ============================================================

-- ------------------------------------------------------------
-- 1. e_dict_energy_type ｜ 能源类型字典
--    REQ 锚点：REQ-002（采集点单位与能源类型匹配）、PRD §7.1 电/水/压缩空气
-- ------------------------------------------------------------
drop table if exists e_dict_energy_type;
create table e_dict_energy_type (
  type_code      varchar(32)   not null                comment '能源类型编码',
  type_name      varchar(64)   not null                comment '能源类型名称',
  base_unit      varchar(16)   not null                comment '基础计量单位（kWh/m3）',
  sort_no        int(4)        default 0               comment '显示顺序',
  remark         varchar(255)  default null            comment '备注',
  primary key (type_code)
) engine=innodb comment = '能源类型字典（电/水/压缩空气）';

-- ------------------------------------------------------------
-- 2. e_area ｜ 装卸区（AREA-A / AREA-B）
--    REQ 锚点：REQ-057（总览按区分组）、REQ-071~076（数据范围绑定 dept_id）
--    数据来源：构造规范 §1.1；dept_id 关联 sys_dept 供 dispatch 角色本区数据范围
-- ------------------------------------------------------------
drop table if exists e_area;
create table e_area (
  area_id        bigint(20)    not null auto_increment comment '装卸区ID',
  area_code      varchar(32)   not null                comment '装卸区编码（AREA-A/AREA-B）',
  area_name      varchar(64)   not null                comment '装卸区名称',
  cargo_type     varchar(32)   not null                comment '货类（钢材/粉煤灰，对齐 S208）',
  dept_id        bigint(20)    default null            comment '关联部门ID（sys_dept，dispatch 本区数据范围）',
  status         char(1)       default '0'             comment '状态（0启用 1停用）',
  remark         varchar(255)  default null            comment '备注',
  create_time    datetime      default null            comment '创建时间',
  update_time    datetime      default null            comment '更新时间',
  primary key (area_id),
  unique key uk_area_code (area_code)
) engine=innodb auto_increment=1 comment = '装卸区';

-- ------------------------------------------------------------
-- 3. e_equipment ｜ 设备台账（12 台）
--    REQ 锚点：REQ-033~035（设备画像）、构造规范 §1.2
-- ------------------------------------------------------------
drop table if exists e_equipment;
create table e_equipment (
  equipment_id   bigint(20)    not null auto_increment comment '设备ID',
  equipment_code varchar(32)   not null                comment '设备编码（GC-A1/AC-B1 等）',
  equipment_name varchar(64)   not null                comment '设备名称',
  area_id        bigint(20)    not null                comment '所属装卸区ID',
  equipment_type varchar(32)   not null                comment '设备类型（装卸主/动力/输送/辅助/照明/转运）',
  rated_power_kw decimal(10,2) default null            comment '额定功率(kW)',
  energy_types   varchar(64)   default null            comment '涉及能源类型（逗号分隔：electricity,water,air）',
  demo_role      varchar(64)   default null            comment '五幕角色标注（第三幕主角/正常对照组等）',
  status         char(1)       default '0'             comment '状态（0启用 1停用）',
  remark         varchar(255)  default null            comment '备注',
  create_time    datetime      default null            comment '创建时间',
  update_time    datetime      default null            comment '更新时间',
  primary key (equipment_id),
  unique key uk_equipment_code (equipment_code),
  key idx_area (area_id)
) engine=innodb auto_increment=1 comment = '设备台账';

-- ------------------------------------------------------------
-- 4. e_meter_point ｜ 采集点主数据（约 48 个）
--    REQ 锚点：REQ-002 必填七项（编号/名称/单位/采集周期/接口来源/倍率/所属设备）、
--             REQ-006 配备状态、REQ-007 未映射/停用不进正式统计、REQ-103 外置接入字段
--    数据来源：构造规范 §1.3 采集点类别与数量
-- ------------------------------------------------------------
drop table if exists e_meter_point;
create table e_meter_point (
  point_id           bigint(20)    not null auto_increment comment '采集点ID',
  point_code         varchar(64)   not null                comment '采集点编号（全局唯一，停用后保留历史）',
  point_name         varchar(128)  not null                comment '显示名称',
  area_id            bigint(20)    not null                comment '所属装卸区ID',
  equipment_id       bigint(20)    default null            comment '所属设备ID（环境/照明点可空）',
  point_category     varchar(32)   not null                comment '采集点类别（device_meter/area_meter/branch_meter/water/air_flow/status/env）',
  energy_type_code   varchar(32)   default null            comment '能源类型编码（关联 e_dict_energy_type，状态/环境点可空）',
  unit               varchar(16)   not null                comment '计量单位（kWh/m3/none）',
  sample_period_sec  int(6)        not null                comment '采样周期（秒）：电/水 900、压缩空气 300、状态 300',
  source_type        varchar(32)   not null                comment '接口来源（gateway/data_platform/offline_import/manual）',
  multiplier         decimal(10,4) default 1.0000          comment '倍率（换表带生效时间，附录C）',
  multiplier_effective_time datetime default null          comment '倍率生效时间',
  range_min          decimal(20,4) default null            comment '量程下限（超量程质量判定 REQ-021）',
  range_max          decimal(20,4) default null            comment '量程上限',
  provision_status   varchar(32)   default 'active'        comment '配备状态（active/pending/manual/estimated/reserved/excluded，REQ-006）',
  access_mode        varchar(16)   default 'direct'        comment '接入方式（direct直连 / external外置接入，REQ-103）',
  protocol_type      varchar(32)   default null            comment '协议类型（MODBUS/OPC_UA/MQTT/HTTP，外置必填）',
  edge_gateway       varchar(64)   default null            comment '边缘网关来源（外置必填）',
  owner_role         varchar(32)   default null            comment '责任对象角色',
  status             varchar(16)   default 'enabled'       comment '状态（pending_mapping/enabled/disabled/maintenance/replaced/archived，附录C）',
  remark             varchar(255)  default null            comment '备注',
  create_time        datetime      default null            comment '创建时间',
  update_time        datetime      default null            comment '更新时间',
  primary key (point_id),
  unique key uk_point_code (point_code),
  key idx_area_equipment (area_id, equipment_id),
  key idx_energy_type (energy_type_code),
  key idx_status (status)
) engine=innodb auto_increment=1 comment = '采集点主数据';

-- ------------------------------------------------------------
-- 5. e_raw_reading ｜ 原始采集读数
--    REQ 锚点：REQ-011 幂等键（采集点+采样时间）、REQ-014 补传标记、
--             REQ-015 保留原始值、REQ-020 迟到/估算/修正质量标记
--    数据来源：构造规范 §3 曲线形态、§4 异常注入 INJ-01/02
-- ------------------------------------------------------------
drop table if exists e_raw_reading;
create table e_raw_reading (
  reading_id         bigint(20)    not null auto_increment comment '原始读数ID',
  point_id           bigint(20)    not null                comment '采集点ID',
  sample_time        datetime      not null                comment '采样时间（业务时刻）',
  cumulative_value   decimal(20,4) default null            comment '累计值（表计读数，状态点为空）',
  incremental_value  decimal(20,4) default null            comment '本周期增量（负值进质量事件）',
  status_value       varchar(16)   default null            comment '状态值（running/standby/stopped/maintenance，状态点用）',
  unit               varchar(16)   default null            comment '单位（冗余，便于导出）',
  quality_state      varchar(16)   not null                comment '质量状态八态短码（ok正常/miss缺测/late迟到/dup重复/jump跳变/est估算/fix人工修正/frozen冻结疑似，对齐 docs/mock-contracts.md §2.1，附录C）',
  source_batch       varchar(64)   default null            comment '来源批次号（关联 e_collect_task.batch_no）',
  ingest_time        datetime      not null                comment '入库时间（用于迟到判定 R02）',
  is_backfill        tinyint(1)    default 0               comment '是否补传（0否 1是，REQ-014）',
  is_estimated       tinyint(1)    default 0               comment '是否估算填补',
  correction_reason  varchar(255)  default null            comment '人工修正原因（走审批 REQ-074）',
  primary key (reading_id),
  unique key uk_point_sample (point_id, sample_time),
  key idx_ingest_time (ingest_time),
  key idx_quality (quality_state),
  key idx_batch (source_batch)
) engine=innodb auto_increment=1 comment = '原始采集读数（时序）';

-- ------------------------------------------------------------
-- 6. e_collect_task ｜ 采集任务与批次
--    REQ 锚点：REQ-013 3× 周期上限 15 分钟异常判定、REQ-014 缓存起止/补传批次/重复处理/失败原因、
--             REQ-068 重试策略、REQ-079 数据延迟监控
--    数据来源：INJ-01 A 区 4 表 07-06 09:20–13:40 离线 + 13:40 后补传
-- ------------------------------------------------------------
drop table if exists e_collect_task;
create table e_collect_task (
  task_id            bigint(20)    not null auto_increment comment '任务ID',
  batch_no           varchar(64)   not null                comment '批次号',
  area_id            bigint(20)    default null            comment '涉及装卸区（可空表示跨区）',
  point_scope        varchar(255)  default null            comment '涉及采集点范围（CSV 或空表示全量）',
  scheduled_time     datetime      not null                comment '计划执行时间',
  actual_ingest_time datetime      default null            comment '实际入库时间',
  task_status        varchar(16)   not null                comment '任务状态（success/failed/late/backfilled）',
  affected_point_count int(6)      default 0               comment '受影响采集点数',
  failure_reason     varchar(255)  default null            comment '失败原因',
  retry_count        int(4)        default 0               comment '重试次数',
  outage_start       datetime      default null            comment '断传起始时间（缓存起 REQ-014）',
  outage_end         datetime      default null            comment '断传结束时间（缓存止）',
  parent_task_id     bigint(20)    default null            comment '父任务ID（补传任务指向原离线任务）',
  create_time        datetime      default null            comment '创建时间',
  primary key (task_id),
  key idx_batch (batch_no),
  key idx_status_time (task_status, scheduled_time)
) engine=innodb auto_increment=1 comment = '采集任务与批次';

-- ------------------------------------------------------------
-- 7. e_equipment_status_log ｜ 设备运行状态日志
--    REQ 锚点：PRD §5.5 状态叠加曲线、PRD §7.1 事件驱动 + 1 分钟心跳
--    数据来源：构造规范 §3 工作日双峰、INJ-03 GC-A1 07-08 待机但高功率
-- ------------------------------------------------------------
drop table if exists e_equipment_status_log;
create table e_equipment_status_log (
  log_id         bigint(20)    not null auto_increment comment '日志ID',
  equipment_id   bigint(20)    not null                comment '设备ID',
  event_time     datetime      not null                comment '事件时刻',
  status_value   varchar(16)   not null                comment '运行状态（running/standby/stopped/maintenance）',
  event_type     varchar(16)   not null                comment '事件类型（event/heartbeat）',
  remark         varchar(255)  default null            comment '备注',
  primary key (log_id),
  key idx_equipment_time (equipment_id, event_time)
) engine=innodb auto_increment=1 comment = '设备运行状态日志';

-- ------------------------------------------------------------
-- 8. e_work_order ｜ mock 工单（作业数据）
--    REQ 锚点：REQ-031/032/037 作业归因、PRD §9 接口契约（待对齐）
--    数据来源：构造规范 §5 mock 工单节奏；INJ-03 时段刻意不排工单
-- ------------------------------------------------------------
drop table if exists e_work_order;
create table e_work_order (
  work_order_id  bigint(20)    not null auto_increment comment '工单ID',
  order_no       varchar(64)   not null                comment '工单号',
  area_id        bigint(20)    not null                comment '装卸区ID',
  equipment_id   bigint(20)    default null            comment '主作业设备ID（可空表示未指定）',
  cargo_type     varchar(32)   not null                comment '货类（钢材/粉煤灰）',
  workload_value decimal(12,2) not null                comment '作业量',
  workload_unit  varchar(16)   not null                comment '作业量单位（吨）',
  start_time     datetime      not null                comment '起始时间',
  end_time       datetime      default null            comment '结束时间（in_progress 可空）',
  status         varchar(16)   not null                comment '状态（completed/in_progress/cancelled）',
  remark         varchar(255)  default null            comment '备注',
  create_time    datetime      default null            comment '创建时间',
  primary key (work_order_id),
  unique key uk_order_no (order_no),
  key idx_area_time (area_id, start_time),
  key idx_equipment_time (equipment_id, start_time)
) engine=innodb auto_increment=1 comment = 'mock 作业工单';

-- ------------------------------------------------------------
-- 9. e_stat_hour ｜ 小时统计（backend 计算后写入）
--    REQ 锚点：REQ-024~028 多维统计、REQ-060 总览刷新
--    维度：point / equipment / area 三选一（object_type/object_id 通用）
-- ------------------------------------------------------------
drop table if exists e_stat_hour;
create table e_stat_hour (
  id                 bigint(20)    not null auto_increment comment '主键',
  object_type        varchar(16)   not null                comment '统计对象（point/equipment/area）',
  object_id          bigint(20)    not null                comment '对象ID',
  energy_type_code   varchar(32)   not null                comment '能源类型',
  stat_time          datetime      not null                comment '小时时间桶（整点）',
  total_value        decimal(20,4) default 0               comment '本小时增量',
  avg_power_kw       decimal(12,4) default null            comment '平均功率(kW)（电类）',
  coverage_ratio     decimal(6,4)  default null            comment '覆盖率（0–1）',
  quality_summary    varchar(255)  default null            comment '质量摘要（正常X/迟到Y/缺测Z）',
  tou_period         varchar(8)    default null            comment '峰平谷（peak/flat/valley）',
  version_no         int(6)        default 1               comment '版本号（重算+1）',
  computed_at        datetime      default null            comment '计算时间',
  primary key (id),
  unique key uk_object_time_type (object_type, object_id, energy_type_code, stat_time),
  key idx_stat_time (stat_time)
) engine=innodb auto_increment=1 comment = '小时统计';

-- ------------------------------------------------------------
-- 10. e_stat_day ｜ 日统计
--     REQ 锚点：REQ-024/029（基线偏差）、REQ-020（重算标记）
-- ------------------------------------------------------------
drop table if exists e_stat_day;
create table e_stat_day (
  id                 bigint(20)    not null auto_increment comment '主键',
  object_type        varchar(16)   not null                comment '统计对象（point/equipment/area）',
  object_id          bigint(20)    not null                comment '对象ID',
  energy_type_code   varchar(32)   not null                comment '能源类型',
  stat_date          date          not null                comment '统计日期',
  total_value        decimal(20,4) default 0               comment '当日总量',
  peak_value         decimal(20,4) default 0               comment '峰段量',
  flat_value         decimal(20,4) default 0               comment '平段量',
  valley_value       decimal(20,4) default 0               comment '谷段量',
  coverage_ratio     decimal(6,4)  default null            comment '覆盖率（0–1）',
  workday_flag       tinyint(1)    default 1               comment '是否工作日（0周末 1工作日）',
  baseline_id        bigint(20)    default null            comment '关联基线ID',
  baseline_deviation_pct decimal(8,4) default null         comment '基线偏差百分比',
  version_no         int(6)        default 1               comment '版本号',
  has_recompute_pending tinyint(1) default 0               comment '待重算标记（REQ-020）',
  computed_at        datetime      default null            comment '计算时间',
  primary key (id),
  unique key uk_object_date_type (object_type, object_id, energy_type_code, stat_date),
  key idx_stat_date (stat_date)
) engine=innodb auto_increment=1 comment = '日统计';

-- ------------------------------------------------------------
-- 11. e_stat_month ｜ 月统计
-- ------------------------------------------------------------
drop table if exists e_stat_month;
create table e_stat_month (
  id                 bigint(20)    not null auto_increment comment '主键',
  object_type        varchar(16)   not null                comment '统计对象',
  object_id          bigint(20)    not null                comment '对象ID',
  energy_type_code   varchar(32)   not null                comment '能源类型',
  stat_month         char(7)       not null                comment '统计月份（YYYY-MM）',
  total_value        decimal(20,4) default 0               comment '当月总量',
  peak_value         decimal(20,4) default 0               comment '峰段量',
  flat_value         decimal(20,4) default 0               comment '平段量',
  valley_value       decimal(20,4) default 0               comment '谷段量',
  coverage_ratio     decimal(6,4)  default null            comment '覆盖率',
  version_no         int(6)        default 1               comment '版本号',
  computed_at        datetime      default null            comment '计算时间',
  primary key (id),
  unique key uk_object_month_type (object_type, object_id, energy_type_code, stat_month)
) engine=innodb auto_increment=1 comment = '月统计';

-- ------------------------------------------------------------
-- 12. e_recompute_log ｜ 重算记录（REQ-020 差异说明）
-- ------------------------------------------------------------
drop table if exists e_recompute_log;
create table e_recompute_log (
  id                 bigint(20)    not null auto_increment comment '主键',
  target_table       varchar(32)   not null                comment '重算目标表名',
  target_key_json    varchar(512)  not null                comment '目标定位键（json：object_type/id/period）',
  old_version_no     int(6)        default null            comment '旧版本号',
  new_version_no     int(6)        default null            comment '新版本号',
  delta_value        decimal(20,4) default null            comment '数值差异',
  delta_pct          decimal(8,4)  default null            comment '差异百分比',
  trigger_reason     varchar(255)  default null            comment '触发原因（补传/换表/规则调整）',
  operator           varchar(64)   default null            comment '操作人',
  created_at         datetime      default null            comment '记录时间',
  primary key (id),
  key idx_target (target_table, created_at)
) engine=innodb auto_increment=1 comment = '统计重算日志';

-- ------------------------------------------------------------
-- 13. e_energy_baseline ｜ 能源基线（REQ-029 演示态）
--     数据来源：PRD §7.3 前 8 周分桶均值 ±1σ；报告期偏差 R09
-- ------------------------------------------------------------
drop table if exists e_energy_baseline;
create table e_energy_baseline (
  baseline_id        bigint(20)    not null auto_increment comment '基线ID',
  baseline_code      varchar(64)   not null                comment '基线编号',
  object_scope       varchar(16)   not null                comment '范围（area/equipment/system）',
  object_id          bigint(20)    default null            comment '对象ID（system 时空）',
  energy_type_code   varchar(32)   not null                comment '能源类型',
  baseline_start     date          not null                comment '基线期起',
  baseline_end       date          not null                comment '基线期止',
  method             varchar(32)   not null                comment '方法（bucket分桶 / linear线性回归）',
  formula_version    varchar(32)   default null            comment '公式版本',
  buckets_json       longtext      default null            comment '分桶均值+σ（json：{weekday_peak: {mean, sigma}, ...}）',
  status             varchar(16)   default 'draft'         comment '状态（draft草稿/published已发布/archived已归档）',
  adjustment_reason  varchar(255)  default null            comment '调整原因（附录C）',
  published_at       datetime      default null            comment '发布时间',
  published_by       varchar(64)   default null            comment '发布人',
  remark             varchar(255)  default null            comment '备注',
  create_time        datetime      default null            comment '创建时间',
  update_time        datetime      default null            comment '更新时间',
  primary key (baseline_id),
  unique key uk_baseline_code (baseline_code),
  key idx_scope_type (object_scope, object_id, energy_type_code)
) engine=innodb auto_increment=1 comment = '能源基线';

-- ------------------------------------------------------------
-- 14. e_equipment_profile ｜ 设备能耗画像（REQ-033~035）
-- ------------------------------------------------------------
drop table if exists e_equipment_profile;
create table e_equipment_profile (
  id                 bigint(20)    not null auto_increment comment '主键',
  equipment_id       bigint(20)    not null                comment '设备ID',
  energy_type_code   varchar(32)   not null                comment '能源类型',
  period_start       datetime      not null                comment '统计周期起',
  period_end         datetime      not null                comment '统计周期止',
  work_energy        decimal(20,4) default 0               comment '作业能耗',
  standby_energy     decimal(20,4) default 0               comment '待机能耗',
  aux_energy         decimal(20,4) default 0               comment '辅助能耗',
  peak_load_kw       decimal(12,4) default null            comment '峰值负荷(kW)',
  peak_load_time     datetime      default null            comment '峰值负荷出现时间',
  cost               decimal(20,4) default 0               comment '成本',
  abnormal_count     int(6)        default 0               comment '异常数',
  quality_summary    varchar(255)  default null            comment '质量摘要',
  computed_at        datetime      default null            comment '计算时间',
  primary key (id),
  unique key uk_equipment_period (equipment_id, energy_type_code, period_start, period_end)
) engine=innodb auto_increment=1 comment = '设备能耗画像';

-- ------------------------------------------------------------
-- 15. e_forecast_result ｜ 短期预测（REQ-104）
-- ------------------------------------------------------------
drop table if exists e_forecast_result;
create table e_forecast_result (
  id                 bigint(20)    not null auto_increment comment '主键',
  scope              varchar(16)   not null                comment '范围（area/equipment/system）',
  scope_id           bigint(20)    default null            comment '对象ID',
  energy_type_code   varchar(32)   not null                comment '能源类型',
  horizon_hours      int(4)        not null                comment '预测跨度（24/168）',
  target_time        datetime      not null                comment '预测目标时间',
  predicted_value    decimal(20,4) not null                comment '预测值',
  ci_lower           decimal(20,4) default null            comment '置信下界（±1.96σ）',
  ci_upper           decimal(20,4) default null            comment '置信上界',
  method_version     varchar(32)   default null            comment '方法版本',
  history_window_weeks int(4)      default null            comment '历史窗口（周）',
  quality_flag       varchar(16)   default 'normal'        comment '质量标记（normal/insufficient）',
  computed_at        datetime      default null            comment '计算时间',
  primary key (id),
  unique key uk_scope_target (scope, scope_id, energy_type_code, target_time, horizon_hours)
) engine=innodb auto_increment=1 comment = '短期预测结果';

-- ------------------------------------------------------------
-- 16. e_alert_rule ｜ 告警规则库（R01–R11）
--     REQ 锚点：REQ-039 级别三档、REQ-040 规则版本
--     数据来源：PRD §7.2 初始规则库
-- ------------------------------------------------------------
drop table if exists e_alert_rule;
create table e_alert_rule (
  rule_id            bigint(20)    not null auto_increment comment '规则ID',
  rule_code          varchar(16)   not null                comment '规则编码（R01..R11）',
  rule_name          varchar(64)   not null                comment '规则名称',
  rule_category      varchar(32)   default null            comment '规则分类（quality/energy/cost/security）',
  expression         varchar(512)  default null            comment '判定表达式（可读描述）',
  threshold_json     varchar(512)  default null            comment '阈值参数（json，可运维维护）',
  level              varchar(8)    not null                comment '级别（notice提示/normal一般/severe严重）',
  enabled            tinyint(1)    default 1               comment '启用（0停用 1启用）',
  effective_from     datetime      default null            comment '生效起',
  deprecated_at      datetime      default null            comment '停用时间',
  version_no         int(6)        default 1               comment '当前版本号',
  remark             varchar(255)  default null            comment '备注（对应 K.7 / 服务幕次）',
  create_by          varchar(64)   default null            comment '创建者',
  update_by          varchar(64)   default null            comment '更新者',
  create_time        datetime      default null            comment '创建时间',
  update_time        datetime      default null            comment '更新时间',
  primary key (rule_id),
  unique key uk_rule_code (rule_code)
) engine=innodb auto_increment=1 comment = '告警规则库';

-- ------------------------------------------------------------
-- 17. e_alert_rule_version ｜ 规则版本历史（REQ-040 历史告警按触发时版本展示）
-- ------------------------------------------------------------
drop table if exists e_alert_rule_version;
create table e_alert_rule_version (
  version_id         bigint(20)    not null auto_increment comment '版本ID',
  rule_id            bigint(20)    not null                comment '规则ID',
  version_no         int(6)        not null                comment '版本号',
  snapshot_json      longtext      not null                comment '规则快照（json）',
  effective_from     datetime      default null            comment '版本生效起',
  effective_to       datetime      default null            comment '版本生效止',
  create_by          varchar(64)   default null            comment '创建者',
  create_time        datetime      default null            comment '创建时间',
  primary key (version_id),
  unique key uk_rule_version (rule_id, version_no)
) engine=innodb auto_increment=1 comment = '告警规则版本历史';

-- ------------------------------------------------------------
-- 18. e_alert_event ｜ 告警事件（backend 由规则真实算出，本表 DDL 先落位）
--     REQ 锚点：REQ-041~044 状态机与合并窗口、REQ-043 通知记录
-- ------------------------------------------------------------
drop table if exists e_alert_event;
create table e_alert_event (
  event_id           bigint(20)    not null auto_increment comment '事件ID',
  rule_id            bigint(20)    not null                comment '规则ID',
  rule_code          varchar(16)   not null                comment '规则编码（冗余便于查询）',
  rule_version_no    int(6)        not null                comment '触发时规则版本号（REQ-040）',
  object_type        varchar(16)   not null                comment '涉及对象类型（area/equipment/point）',
  object_id          bigint(20)    not null                comment '对象ID',
  area_id            bigint(20)    default null            comment '装卸区ID（冗余便于数据范围过滤）',
  level              varchar(8)    not null                comment '级别（notice/normal/severe）',
  status             varchar(16)   not null                comment '状态（new新告警/ack已确认/dispatched已派发/processing处理中/closed已关闭/false_closed误报关闭/escalated已升级）',
  first_occur_time   datetime      not null                comment '首次触发时刻',
  last_occur_time    datetime      not null                comment '最近触发时刻',
  occur_count        int(6)        default 1               comment '合并触发次数（REQ-042）',
  snapshot_json      longtext      default null            comment '触发时刻曲线快照（json）',
  assigned_to        varchar(64)   default null            comment '派发对象',
  closed_at          datetime      default null            comment '关闭时间',
  close_reason       varchar(255)  default null            comment '关闭原因（必填）',
  close_type         varchar(16)   default null            comment '关闭类型（valid/false_positive）',
  notification_json  varchar(1024) default null            comment '通知记录（json，REQ-043）',
  create_time        datetime      default null            comment '创建时间',
  update_time        datetime      default null            comment '更新时间',
  primary key (event_id),
  key idx_status_level (status, level, first_occur_time),
  key idx_object (object_type, object_id, first_occur_time)
) engine=innodb auto_increment=1 comment = '告警事件';

-- ------------------------------------------------------------
-- 18a. e_alert_flow_log ｜ 告警状态流转留痕（REQ-041）
-- ------------------------------------------------------------
drop table if exists e_alert_flow_log;
create table e_alert_flow_log (
  flow_id            bigint(20)    not null auto_increment comment '流转记录ID',
  event_id           bigint(20)    not null                comment '告警事件ID',
  from_status        varchar(16)   not null                comment '流转前状态',
  to_status          varchar(16)   not null                comment '流转后状态',
  operator           varchar(64)   not null                comment '处理人',
  remark             varchar(255)  default null            comment '处理备注',
  occur_time         datetime      not null                comment '流转发生时间',
  primary key (flow_id),
  key idx_event_time (event_id, occur_time)
) engine=innodb auto_increment=1 comment = '告警状态流转留痕（REQ-041）';

-- ------------------------------------------------------------
-- 19. suggestion domain ｜ 节能建议闭环
--     REQ 锚点：REQ-045~050；REQ-091/096 禁止控制指令
-- ------------------------------------------------------------
drop table if exists e_suggestion_flow_log;
drop table if exists e_suggestion_verification;
drop table if exists e_suggestion;
drop table if exists e_suggestion_template;

create table e_suggestion_template (
  template_id                       bigint(20)    not null auto_increment comment '模板ID',
  template_code                     varchar(64)   not null                comment '模板编码',
  template_name                     varchar(128)  not null                comment '模板名称',
  category                          varchar(64)   not null                comment '建议分类',
  source_rule_code                  varchar(16)   default null            comment '适用规则（PRD §7.2）',
  applicable_object_type            varchar(32)   not null                comment '适用对象类型',
  action_content                    text          not null                comment '人工措施内容（REQ-091/096）',
  required_data                     text          default null            comment '所需数据说明',
  estimated_saving                  text          default null            comment '预估节能量口径',
  cost_impact                       text          default null            comment '成本影响口径',
  reliability_impact                text          default null            comment '可靠性影响',
  verification_method               text          default null            comment '验证方式',
  default_implementation_difficulty decimal(5,2)  not null                comment '默认实施难度（0~100）',
  default_safety_impact             decimal(5,2)  not null                comment '默认安全影响（0~100）',
  enabled                           tinyint(1)     not null default 1      comment '是否启用',
  version                           int(6)        not null default 1      comment '模板版本',
  create_time                       datetime      not null                comment '创建时间',
  update_time                       datetime      not null                comment '更新时间',
  primary key (template_id),
  unique key uk_template_code_version (template_code, version),
  key idx_template_rule_enabled (source_rule_code, enabled)
) engine=innodb auto_increment=1 comment = '节能建议模板（REQ-045）';

create table e_suggestion (
  suggestion_id            bigint(20)    not null auto_increment comment '建议ID',
  source_type              varchar(16)   not null                comment '来源（rule/manual）',
  source_alert_id          bigint(20)    default null            comment '来源告警ID（冻结弱引用，不建FK）',
  source_fingerprint       varchar(128)  default null            comment '来源业务指纹',
  source_snapshot_json     longtext      default null            comment '来源冻结快照（json）',
  template_id              bigint(20)    default null            comment '生成时模板ID',
  template_version         int(6)        default null            comment '生成时模板版本',
  template_snapshot_json   longtext      not null                comment '模板或人工建议冻结快照（json）',
  trigger_basis            text          default null            comment '触发/人工来源依据',
  rule_code                varchar(16)   default null            comment '规则编码（PRD §7.2）',
  title                    varchar(128)  not null                comment '标题',
  measure_content          text          not null                comment '人工措施内容（无控制指令）',
  responsible_user         varchar(64)   default null            comment '责任账号',
  responsible_role         varchar(32)   default null            comment '责任角色',
  verify_start             date          default null            comment '验证期起（列表兼容字段）',
  verify_end               date          default null            comment '验证期止（列表兼容字段）',
  area_id                  bigint(20)    default null            comment '装卸区ID',
  equipment_id             bigint(20)    default null            comment '设备ID',
  object_type              varchar(32)   not null                comment '建议对象类型',
  object_id                bigint(20)    default null            comment '建议对象ID（system对象可空）',
  status                   varchar(16)   not null                comment 'pending/dispatched/executing/verifying/valid_closed/invalid_closed/deferred',
  priority_score           decimal(6,2)  not null                comment '后端保存的综合分',
  priority_band            varchar(16)   not null                comment 'high/medium/low',
  priority_formula_version varchar(32)   not null                comment '优先级公式版本',
  priority_factors_json    longtext      not null                comment '五因子、权重与依据（json）',
  repair_at                datetime      default null            comment '修复分界',
  baseline_start           datetime      default null            comment '基线窗口左边界',
  baseline_end             datetime      default null            comment '基线窗口右开边界',
  report_start             datetime      default null            comment '报告窗口左边界',
  report_end               datetime      default null            comment '报告窗口右开边界',
  saving_value             decimal(20,4) default null            comment '通用节能量',
  saving_unit              varchar(16)   default null            comment '通用节能量单位',
  close_type               varchar(32)   default null            comment 'implemented/rejected/archived_invalid',
  close_reason             varchar(512)  default null            comment '关闭原因',
  rejection_reason         varchar(512)  default null            comment '驳回原因',
  invalid_category         varchar(32)   default null            comment '无效分类',
  deferred_from_status     varchar(16)   default null            comment '延期前状态',
  defer_reason             varchar(512)  default null            comment '延期原因',
  defer_until              date          default null            comment '延期恢复日期',
  effect_summary           varchar(1024) default null            comment '效果说明',
  attachments_json         longtext      default null            comment '占位附件元数据（json）',
  created_by               varchar(64)   not null                comment '创建账号',
  closed_by                varchar(64)   default null            comment '关闭账号',
  closed_at                datetime      default null            comment '关闭时间',
  row_version              int(11)       not null default 1      comment '乐观锁版本',
  create_time              datetime      not null                comment '创建时间',
  update_time              datetime      not null                comment '更新时间',
  primary key (suggestion_id),
  unique key uk_source_template (source_fingerprint, template_id),
  key idx_status (status),
  key idx_source_alert (source_alert_id),
  key idx_rule_area (rule_code, area_id),
  key idx_priority (priority_score, suggestion_id)
) engine=innodb auto_increment=1 comment = '节能建议实例（REQ-046~050）';

create table e_suggestion_flow_log (
  flow_id               bigint(20)    not null auto_increment comment '流转记录ID（时间线唯一排序键）',
  suggestion_id         bigint(20)    not null                comment '建议ID',
  from_status           varchar(16)   default null            comment '流转前状态（创建时null）',
  to_status             varchar(16)   not null                comment '流转后状态',
  operator              varchar(64)   not null                comment '服务端确认的操作账号',
  operator_role         varchar(32)   not null                comment '服务端确认的操作角色',
  action                varchar(32)   not null                comment '流转动作',
  remark                varchar(512)  default null            comment '人工备注',
  payload_snapshot_json longtext      default null            comment '流转证据快照（json）',
  occur_time            datetime      not null                comment '流转发生时间（可相同）',
  primary key (flow_id),
  key idx_suggestion_flow (suggestion_id, flow_id)
) engine=innodb auto_increment=1 comment = '建议流转留痕（REQ-047）';

create table e_suggestion_verification (
  verification_id        bigint(20)    not null auto_increment comment '验证快照ID',
  suggestion_id          bigint(20)    not null                comment '建议ID',
  version                int(6)        not null                comment '验证版本',
  status                 varchar(16)   not null                comment 'waiting/effective/ineffective/insufficient',
  repair_at              datetime      not null                comment '修复分界',
  baseline_start         datetime      not null                comment '基线窗口左边界',
  baseline_end           datetime      not null                comment '基线窗口右开边界',
  report_start           datetime      not null                comment '报告窗口左边界',
  report_end             datetime      not null                comment '报告窗口右开边界',
  usage_comparison_json  longtext      not null                comment '用量对比（json）',
  cost_comparison_json   longtext      not null                comment '成本对比（json）',
  workload_comparison_json longtext    not null                comment '作业量对比（json）',
  quality_comparison_json longtext     not null                comment '数据质量对比（json）',
  saving_value           decimal(20,4) default null            comment '节能量',
  saving_unit            varchar(16)   not null                comment '节能量单位',
  saving_pct             decimal(9,4)  default null            comment '用量率改善百分比',
  calculation_note       text          not null                comment '计算说明',
  formula_version        varchar(64)   not null                comment '公式版本',
  signature              varchar(64)   not null                comment '口径签名',
  generated_by           varchar(64)   not null                comment '生成账号',
  generated_at           datetime      not null                comment '生成时间',
  primary key (verification_id),
  unique key uk_suggestion_verification_version (suggestion_id, version),
  key idx_verification_status (status, generated_at)
) engine=innodb auto_increment=1 comment = '建议验证快照（REQ-048）';

-- ------------------------------------------------------------
-- 20. e_tariff_version ｜ 电/水/气 单价版本（REQ-051/052）
--     数据来源：PRD §7.1 峰 1.20 / 平 0.75 / 谷 0.40；水 4.50；气 0.12
-- ------------------------------------------------------------
drop table if exists e_tariff_version;
create table e_tariff_version (
  tariff_id          bigint(20)    not null auto_increment comment '单价版本ID',
  energy_type_code   varchar(32)   not null                comment '能源类型',
  tou_period         varchar(16)   not null                comment '峰平谷（peak/flat/valley/flat_only）',
  price              decimal(10,4) not null                comment '单价',
  currency           varchar(8)    default 'CNY'           comment '币种',
  effective_from     date          not null                comment '生效起',
  effective_to       date          default null            comment '生效止（null 表示当前有效）',
  version_no         int(6)        default 1               comment '版本号',
  remark             varchar(255)  default null            comment '备注',
  create_by          varchar(64)   default null            comment '创建者',
  create_time        datetime      default null            comment '创建时间',
  primary key (tariff_id),
  unique key uk_type_period_from (energy_type_code, tou_period, effective_from)
) engine=innodb auto_increment=1 comment = '单价版本表';

-- ------------------------------------------------------------
-- 21. e_cost_alloc_rule ｜ 成本分摊规则（REQ-055）
-- ------------------------------------------------------------
drop table if exists e_cost_alloc_rule;
create table e_cost_alloc_rule (
  rule_id            bigint(20)    not null auto_increment comment '规则ID',
  rule_name          varchar(64)   not null                comment '规则名称',
  scope              varchar(32)   default null            comment '适用范围（area/system）',
  method             varchar(32)   not null                comment '分摊方法（rated_power_weight 等）',
  config_json        varchar(1024) default null            comment '规则配置（json）',
  effective_from     date          not null                comment '生效起',
  effective_to       date          default null            comment '生效止',
  version_no         int(6)        default 1               comment '版本号',
  create_by          varchar(64)   default null            comment '创建者',
  create_time        datetime      default null            comment '创建时间',
  primary key (rule_id),
  key idx_effective_from (effective_from)
) engine=innodb auto_increment=1 comment = '成本分摊规则';

-- ------------------------------------------------------------
-- 22. e_cost_record ｜ 月度成本版本记录（REQ-051~056/062/073/074，第五幕）
-- ------------------------------------------------------------
-- reset 顺序：归档 → 重算证据 → 成本版本；随后按依赖正序重建三表。
drop table if exists e_report_archive;
drop table if exists e_cost_recompute_record;
drop table if exists e_cost_record;
create table e_cost_record (
  id                 bigint(20)    not null auto_increment comment '主键',
  object_type        varchar(16)   not null                comment '对象类型（area/equipment/system）',
  object_id          bigint(20)    default null            comment '对象ID',
  normalized_object_id bigint(20)  generated always as (ifnull(object_id, 0)) stored comment '唯一键对象ID（system空ID归一为0）',
  stat_month         char(7)       not null                comment '统计月份（YYYY-MM）',
  energy_type_code   varchar(32)   not null                comment '能源类型',
  cost_version       int(6)        not null default 1      comment '成本版本（接口展示为v1/v2）',
  is_current         tinyint(1)    not null default 1      comment '是否当前版本（0否 1是）',
  current_guard      varchar(160)  generated always as (case when is_current = 1 then concat(object_type, ':', ifnull(object_id, 0), ':', stat_month, ':', energy_type_code) else null end) stored comment '仅current行生成的业务唯一键',
  usage_qty          decimal(20,4) default 0               comment '合格用量',
  peak_qty           decimal(20,4) default 0               comment '峰段用量',
  flat_qty           decimal(20,4) default 0               comment '平段用量',
  valley_qty         decimal(20,4) default 0               comment '谷段用量',
  peak_cost          decimal(20,4) default 0               comment '峰段成本',
  flat_cost          decimal(20,4) default 0               comment '平段成本',
  valley_cost        decimal(20,4) default 0               comment '谷段成本',
  total_cost         decimal(20,4) default 0               comment '总成本',
  tariff_version_no  varchar(64)   default null            comment '单价版本快照（多档拼接便于追溯）',
  alloc_rule_version_no varchar(64) default null           comment '分摊规则版本快照',
  formula_version    varchar(64)   not null                comment '成本计算公式版本',
  tariff_snapshot_json longtext    not null                comment '实际参与计算的单价冻结快照（json）',
  alloc_rule_snapshot_json longtext not null               comment '实际参与计算的分摊规则冻结快照（json）',
  source_stat_snapshot_json longtext not null              comment '来源统计版本与质量冻结快照（json）',
  status             varchar(16)   default 'draft'         comment '状态（draft测算/pendingReview待复核/reviewed已复核/frozen已冻结/pendingRecompute重算待复核/void已作废）',
  reviewed_at        datetime      default null            comment '复核时间',
  signature          varchar(96)   not null                comment '完整版本化成本签名（COST-SHA256-V1:<64hex>，REQ-062）',
  computed_by        varchar(64)   not null                comment '计算账号/任务标识',
  computed_at        datetime      default null            comment '计算时间',
  frozen_at          datetime      default null            comment '冻结时间',
  primary key (id),
  unique key uk_cost_version (object_type, normalized_object_id, stat_month, energy_type_code, cost_version),
  unique key uk_cost_current (current_guard),
  key idx_month_status (stat_month, status)
) engine=innodb auto_increment=1 comment = '月度成本版本记录';

-- ------------------------------------------------------------
-- 23. e_cost_recompute_record ｜ 成本重算版本差异（REQ-051~056/073/074）
--     告警/建议由 reset + bootstrap 重建，本表只存冻结业务证据，不绑定其运行时ID。
-- ------------------------------------------------------------
create table e_cost_recompute_record (
  recompute_id       bigint(20)    not null auto_increment comment '成本重算记录ID',
  period_key         varchar(64)   not null                comment '重算周期业务键',
  stat_month         char(7)       not null                comment '统计月份（YYYY-MM）',
  energy_type_code   varchar(32)   not null                comment '能源类型',
  scope              varchar(64)   not null                comment '完整重算范围',
  old_cost_version   int(6)        not null                comment '旧成本版本',
  new_cost_version   int(6)        not null                comment '新成本版本',
  trigger_reason     varchar(255)  not null                comment '重算触发原因',
  trigger_type       varchar(32)   not null                comment '触发类型',
  triggered_by       varchar(64)   not null                comment '发起账号',
  triggered_at       datetime      not null                comment '发起时间',
  tariff_snapshot_json longtext    not null                comment '重算实际采用的单价冻结快照（json）',
  alloc_rule_snapshot_json longtext not null               comment '重算实际采用的分摊规则冻结快照（json）',
  diff_summary_json  longtext      not null                comment '按对象冻结的版本差异（json）',
  review_status      varchar(16)   not null default 'pending' comment '复核状态（pending/approved/rejected）',
  reviewed_by        varchar(64)   default null            comment '复核账号',
  reviewed_at        datetime      default null            comment '复核时间',
  review_remark      varchar(255)  default null            comment '复核意见',
  primary key (recompute_id),
  key idx_recompute_period (stat_month, energy_type_code, review_status)
) engine=innodb auto_increment=1 comment = '成本重算版本差异记录';

-- ------------------------------------------------------------
-- 24. e_report_archive ｜ 报表 canonical payload 冻结归档（REQ-030/059/062）
-- ------------------------------------------------------------
create table e_report_archive (
  archive_id         bigint(20)    not null auto_increment comment '报表归档ID',
  template_code      varchar(64)   not null                comment '报表模板编码',
  template_version   varchar(32)   not null                comment '报表模板版本',
  period_start       date          not null                comment '报告周期起',
  period_end         date          not null                comment '报告周期止',
  filters_snapshot_json longtext   not null                comment '规范化查询条件冻结快照（json）',
  payload_snapshot_json longtext   not null                comment '完整 canonical report payload（json）',
  version_snapshots_json longtext  not null                comment '实际参与的业务版本冻结快照（json）',
  full_signature     varchar(96)   not null                comment '完整报表签名（REPORT-SHA256-V1:<64hex>，REQ-062）',
  generated_at       datetime      not null                comment '系统统计时钟生成时间',
  archived_by        varchar(64)   not null                comment '归档账号',
  archived_at        datetime      not null                comment '归档时间',
  primary key (archive_id),
  key idx_report_archive_period (template_code, period_start, period_end)
) engine=innodb auto_increment=1 comment = '报表快照归档';

-- ------------------------------------------------------------
-- 25. e_audit_security ｜ 安全审计（REQ-076 越权/异常登录，INJ-08 载体）
--     常规操作审计走骨架 sys_oper_log；此表单独收纳安全类事件便于第五幕/G.12 展示
-- ------------------------------------------------------------
drop table if exists e_audit_security;
create table e_audit_security (
  audit_id           bigint(20)    not null auto_increment comment '审计ID',
  event_time         datetime      not null                comment '事件时刻',
  event_type         varchar(32)   not null                comment '事件类型（unauthorized_access越权/abnormal_login异常登录/auth_failed鉴权失败）',
  user_name          varchar(64)   default null            comment '操作账号',
  user_role          varchar(32)   default null            comment '操作角色',
  target_module      varchar(64)   default null            comment '目标模块（如 cost）',
  target_resource    varchar(128)  default null            comment '目标资源URL/标识',
  client_ip          varchar(64)   default null            comment '客户端IP',
  user_agent         varchar(255)  default null            comment 'UA',
  action_result      varchar(16)   default 'blocked'       comment '处置结果（blocked/allowed_flagged）',
  remark             varchar(255)  default null            comment '备注',
  create_time        datetime      default null            comment '创建时间',
  primary key (audit_id),
  key idx_time_type (event_time, event_type),
  key idx_user (user_name)
) engine=innodb auto_increment=1 comment = '安全审计事件';

-- ============================================================
-- DDL 结束。数据由 datagen/generate_demo_data.py 与 datagen/seeds/*.sql 填充。
-- ============================================================
