-- B-Demo schema snapshot. WARNING: drops existing project tables.
-- Source: datagen/skeleton + V001 + V003. REQ anchors remain in source DDL.

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
DROP TABLE IF EXISTS `ai_chat_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_chat_config` (
  `chat_config_id` bigint NOT NULL AUTO_INCREMENT COMMENT '配置主键',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `temperature` float DEFAULT NULL COMMENT '默认温度',
  `add_history_to_context` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '是否添加历史记录(0是, 1否)',
  `num_history_runs` int DEFAULT NULL COMMENT '历史记录条数',
  `system_prompt` text COLLATE utf8mb4_unicode_ci COMMENT '系统提示词',
  `metrics_default_visible` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '默认显示指标(0是, 1否)',
  `vision_enabled` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '1' COMMENT '是否开启视觉(0是, 1否)',
  `image_max_size_mb` int DEFAULT NULL COMMENT '图片最大大小(MB)',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`chat_config_id`),
  UNIQUE KEY `user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI对话配置表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `ai_inspection_report`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_inspection_report` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_date` date NOT NULL COMMENT '报告日期（取 DEMO_NOW 的日期，非真实系统日期）',
  `trigger_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发类型（scheduled 定时 / manual 手动）',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '报告状态（success 成功 / failed 失败）',
  `summary` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '巡检结论摘要（LLM 汇总）',
  `findings_json` json DEFAULT NULL COMMENT '结构化发现列表（category/severity/evidence/suggestion/related_ids）',
  `stats_json` json DEFAULT NULL COMMENT '统计快照（近7天缺数/告警/成本环比等汇总指标）',
  `model_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '本次巡检使用的模型标识（含 base_url 后段+model 名，便于回溯）',
  `elapsed_ms` int DEFAULT NULL COMMENT '巡检 workflow 端到端耗时（毫秒）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录写入时间',
  PRIMARY KEY (`id`),
  KEY `idx_report_date` (`report_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 自主巡检报告（agent_inspection_service 唯一写入点）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `ai_models`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_models` (
  `model_id` bigint NOT NULL AUTO_INCREMENT COMMENT '模型主键',
  `model_code` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型编码',
  `model_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型名称',
  `provider` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '提供商',
  `model_sort` int NOT NULL COMMENT '显示顺序',
  `api_key` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'API Key',
  `base_url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Base URL',
  `model_type` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型类型',
  `max_tokens` int DEFAULT NULL COMMENT '最大输出token',
  `temperature` float DEFAULT NULL COMMENT '默认温度',
  `support_reasoning` char(1) COLLATE utf8mb4_unicode_ci DEFAULT 'N' COMMENT '是否支持推理',
  `support_images` char(1) COLLATE utf8mb4_unicode_ci DEFAULT 'N' COMMENT '是否支持图片',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '模型状态',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `dept_id` bigint DEFAULT NULL COMMENT '部门ID',
  `create_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI模型表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_alert_event`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_alert_event` (
  `event_id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件ID',
  `rule_id` bigint NOT NULL COMMENT '规则ID',
  `rule_code` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '规则编码（冗余便于查询）',
  `rule_version_no` int NOT NULL COMMENT '触发时规则版本号（REQ-040）',
  `object_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '涉及对象类型（area/equipment/point）',
  `object_id` bigint NOT NULL COMMENT '对象ID',
  `area_id` bigint DEFAULT NULL COMMENT '装卸区ID（冗余便于数据范围过滤）',
  `level` varchar(8) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '级别（notice/normal/severe）',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态（new新告警/ack已确认/dispatched已派发/processing处理中/closed已关闭/false_closed误报关闭/escalated已升级）',
  `first_occur_time` datetime NOT NULL COMMENT '首次触发时刻',
  `last_occur_time` datetime NOT NULL COMMENT '最近触发时刻',
  `occur_count` int DEFAULT '1' COMMENT '合并触发次数（REQ-042）',
  `snapshot_json` longtext COLLATE utf8mb4_unicode_ci COMMENT '触发时刻曲线快照（json）',
  `assigned_to` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '派发对象',
  `closed_at` datetime DEFAULT NULL COMMENT '关闭时间',
  `close_reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '关闭原因（必填）',
  `close_type` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '关闭类型（valid/false_positive）',
  `notification_json` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '通知记录（json，REQ-043）',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`event_id`),
  KEY `idx_status_level` (`status`,`level`,`first_occur_time`),
  KEY `idx_object` (`object_type`,`object_id`,`first_occur_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警事件';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_alert_flow_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_alert_flow_log` (
  `flow_id` bigint NOT NULL AUTO_INCREMENT COMMENT '流转记录ID',
  `event_id` bigint NOT NULL COMMENT '告警事件ID',
  `from_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '流转前状态',
  `to_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '流转后状态',
  `operator` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '处理人',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '处理备注',
  `occur_time` datetime NOT NULL COMMENT '流转发生时间',
  PRIMARY KEY (`flow_id`),
  KEY `idx_event_time` (`event_id`,`occur_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警状态流转留痕（REQ-041）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_alert_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_alert_rule` (
  `rule_id` bigint NOT NULL AUTO_INCREMENT COMMENT '规则ID',
  `rule_code` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '规则编码（R01..R11）',
  `rule_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '规则名称',
  `rule_category` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规则分类（quality/energy/cost/security）',
  `expression` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '判定表达式（可读描述）',
  `threshold_json` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '阈值参数（json，可运维维护）',
  `level` varchar(8) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '级别（notice提示/normal一般/severe严重）',
  `enabled` tinyint(1) DEFAULT '1' COMMENT '启用（0停用 1启用）',
  `effective_from` datetime DEFAULT NULL COMMENT '生效起',
  `deprecated_at` datetime DEFAULT NULL COMMENT '停用时间',
  `version_no` int DEFAULT '1' COMMENT '当前版本号',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注（对应 K.7 / 服务幕次）',
  `create_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建者',
  `update_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '更新者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`rule_id`),
  UNIQUE KEY `uk_rule_code` (`rule_code`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警规则库';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_alert_rule_version`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_alert_rule_version` (
  `version_id` bigint NOT NULL AUTO_INCREMENT COMMENT '版本ID',
  `rule_id` bigint NOT NULL COMMENT '规则ID',
  `version_no` int NOT NULL COMMENT '版本号',
  `snapshot_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '规则快照（json）',
  `effective_from` datetime DEFAULT NULL COMMENT '版本生效起',
  `effective_to` datetime DEFAULT NULL COMMENT '版本生效止',
  `create_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`version_id`),
  UNIQUE KEY `uk_rule_version` (`rule_id`,`version_no`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警规则版本历史';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_area`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_area` (
  `area_id` bigint NOT NULL AUTO_INCREMENT COMMENT '装卸区ID',
  `area_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '装卸区编码（AREA-A/AREA-B）',
  `area_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '装卸区名称',
  `cargo_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '货类（钢材/粉煤灰，对齐 S208）',
  `dept_id` bigint DEFAULT NULL COMMENT '关联部门ID（sys_dept，dispatch 本区数据范围）',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态（0启用 1停用）',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`area_id`),
  UNIQUE KEY `uk_area_code` (`area_code`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='装卸区';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_audit_security`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_audit_security` (
  `audit_id` bigint NOT NULL AUTO_INCREMENT COMMENT '审计ID',
  `event_time` datetime NOT NULL COMMENT '事件时刻',
  `event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型（unauthorized_access越权/abnormal_login异常登录/auth_failed鉴权失败）',
  `user_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作账号',
  `user_role` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作角色',
  `target_module` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '目标模块（如 cost）',
  `target_resource` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '目标资源URL/标识',
  `client_ip` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户端IP',
  `user_agent` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'UA',
  `action_result` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT 'blocked' COMMENT '处置结果（blocked/allowed_flagged）',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`audit_id`),
  KEY `idx_time_type` (`event_time`,`event_type`),
  KEY `idx_user` (`user_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='安全审计事件';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_collect_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_collect_task` (
  `task_id` bigint NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  `batch_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '批次号',
  `area_id` bigint DEFAULT NULL COMMENT '涉及装卸区（可空表示跨区）',
  `point_scope` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '涉及采集点范围（CSV 或空表示全量）',
  `scheduled_time` datetime NOT NULL COMMENT '计划执行时间',
  `actual_ingest_time` datetime DEFAULT NULL COMMENT '实际入库时间',
  `task_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务状态（success/failed/late/backfilled）',
  `affected_point_count` int DEFAULT '0' COMMENT '受影响采集点数',
  `failure_reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因',
  `retry_count` int DEFAULT '0' COMMENT '重试次数',
  `outage_start` datetime DEFAULT NULL COMMENT '断传起始时间（缓存起 REQ-014）',
  `outage_end` datetime DEFAULT NULL COMMENT '断传结束时间（缓存止）',
  `parent_task_id` bigint DEFAULT NULL COMMENT '父任务ID（补传任务指向原离线任务）',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`task_id`),
  KEY `idx_batch` (`batch_no`),
  KEY `idx_status_time` (`task_status`,`scheduled_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1683 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采集任务与批次';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_cost_alloc_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_cost_alloc_rule` (
  `rule_id` bigint NOT NULL AUTO_INCREMENT COMMENT '规则ID',
  `rule_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '规则名称',
  `scope` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '适用范围（area/system）',
  `method` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '分摊方法（rated_power_weight 等）',
  `config_json` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规则配置（json）',
  `effective_from` date NOT NULL COMMENT '生效起',
  `effective_to` date DEFAULT NULL COMMENT '生效止',
  `version_no` int DEFAULT '1' COMMENT '版本号',
  `create_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`rule_id`),
  KEY `idx_effective_from` (`effective_from`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成本分摊规则';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_cost_recompute_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_cost_recompute_record` (
  `recompute_id` bigint NOT NULL AUTO_INCREMENT COMMENT '成本重算记录ID',
  `period_key` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '重算周期业务键',
  `stat_month` char(7) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '统计月份（YYYY-MM）',
  `energy_type_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '能源类型',
  `scope` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '完整重算范围',
  `old_cost_version` int NOT NULL COMMENT '旧成本版本',
  `new_cost_version` int NOT NULL COMMENT '新成本版本',
  `trigger_reason` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '重算触发原因',
  `trigger_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发类型',
  `triggered_by` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '发起账号',
  `triggered_at` datetime NOT NULL COMMENT '发起时间',
  `tariff_snapshot_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '重算实际采用的单价冻结快照（json）',
  `alloc_rule_snapshot_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '重算实际采用的分摊规则冻结快照（json）',
  `diff_summary_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '按对象冻结的版本差异（json）',
  `review_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'pending' COMMENT '复核状态（pending/approved/rejected）',
  `reviewed_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '复核账号',
  `reviewed_at` datetime DEFAULT NULL COMMENT '复核时间',
  `review_remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '复核意见',
  PRIMARY KEY (`recompute_id`),
  KEY `idx_recompute_period` (`stat_month`,`energy_type_code`,`review_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成本重算版本差异记录';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_cost_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_cost_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `object_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '对象类型（area/equipment/system）',
  `object_id` bigint DEFAULT NULL COMMENT '对象ID',
  `normalized_object_id` bigint GENERATED ALWAYS AS (ifnull(`object_id`,0)) STORED COMMENT '唯一键对象ID（system空ID归一为0）',
  `stat_month` char(7) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '统计月份（YYYY-MM）',
  `energy_type_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '能源类型',
  `cost_version` int NOT NULL DEFAULT '1' COMMENT '成本版本（接口展示为v1/v2）',
  `is_current` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否当前版本（0否 1是）',
  `current_guard` varchar(160) COLLATE utf8mb4_unicode_ci GENERATED ALWAYS AS ((case when (`is_current` = 1) then concat(`object_type`,_utf8mb4':',ifnull(`object_id`,0),_utf8mb4':',`stat_month`,_utf8mb4':',`energy_type_code`) else NULL end)) STORED COMMENT '仅current行生成的业务唯一键',
  `usage_qty` decimal(20,4) DEFAULT '0.0000' COMMENT '合格用量',
  `peak_qty` decimal(20,4) DEFAULT '0.0000' COMMENT '峰段用量',
  `flat_qty` decimal(20,4) DEFAULT '0.0000' COMMENT '平段用量',
  `valley_qty` decimal(20,4) DEFAULT '0.0000' COMMENT '谷段用量',
  `peak_cost` decimal(20,4) DEFAULT '0.0000' COMMENT '峰段成本',
  `flat_cost` decimal(20,4) DEFAULT '0.0000' COMMENT '平段成本',
  `valley_cost` decimal(20,4) DEFAULT '0.0000' COMMENT '谷段成本',
  `total_cost` decimal(20,4) DEFAULT '0.0000' COMMENT '总成本',
  `tariff_version_no` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '单价版本快照（多档拼接便于追溯）',
  `alloc_rule_version_no` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '分摊规则版本快照',
  `formula_version` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '成本计算公式版本',
  `tariff_snapshot_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '实际参与计算的单价冻结快照（json）',
  `alloc_rule_snapshot_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '实际参与计算的分摊规则冻结快照（json）',
  `source_stat_snapshot_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '来源统计版本与质量冻结快照（json）',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT 'draft' COMMENT '状态（draft测算/pendingReview待复核/reviewed已复核/frozen已冻结/pendingRecompute重算待复核/void已作废）',
  `reviewed_at` datetime DEFAULT NULL COMMENT '复核时间',
  `signature` varchar(96) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '完整版本化成本签名（COST-SHA256-V1:<64hex>，REQ-062）',
  `computed_by` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '计算账号/任务标识',
  `computed_at` datetime DEFAULT NULL COMMENT '计算时间',
  `frozen_at` datetime DEFAULT NULL COMMENT '冻结时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cost_version` (`object_type`,`normalized_object_id`,`stat_month`,`energy_type_code`,`cost_version`),
  UNIQUE KEY `uk_cost_current` (`current_guard`),
  KEY `idx_month_status` (`stat_month`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='月度成本版本记录';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_dict_energy_type`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_dict_energy_type` (
  `type_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '能源类型编码',
  `type_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '能源类型名称',
  `base_unit` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '基础计量单位（kWh/m3）',
  `sort_no` int DEFAULT '0' COMMENT '显示顺序',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`type_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='能源类型字典（电/水/压缩空气）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_energy_baseline`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_energy_baseline` (
  `baseline_id` bigint NOT NULL AUTO_INCREMENT COMMENT '基线ID',
  `baseline_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '基线编号',
  `object_scope` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '范围（area/equipment/system）',
  `object_id` bigint DEFAULT NULL COMMENT '对象ID（system 时空）',
  `energy_type_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '能源类型',
  `baseline_start` date NOT NULL COMMENT '基线期起',
  `baseline_end` date NOT NULL COMMENT '基线期止',
  `method` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '方法（bucket分桶 / linear线性回归）',
  `formula_version` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '公式版本',
  `buckets_json` longtext COLLATE utf8mb4_unicode_ci COMMENT '分桶均值+σ（json：{weekday_peak: {mean, sigma}, ...}）',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT 'draft' COMMENT '状态（draft草稿/published已发布/archived已归档）',
  `adjustment_reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '调整原因（附录C）',
  `published_at` datetime DEFAULT NULL COMMENT '发布时间',
  `published_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '发布人',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`baseline_id`),
  UNIQUE KEY `uk_baseline_code` (`baseline_code`),
  KEY `idx_scope_type` (`object_scope`,`object_id`,`energy_type_code`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='能源基线';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_equipment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_equipment` (
  `equipment_id` bigint NOT NULL AUTO_INCREMENT COMMENT '设备ID',
  `equipment_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '设备编码（GC-A1/AC-B1 等）',
  `equipment_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '设备名称',
  `area_id` bigint NOT NULL COMMENT '所属装卸区ID',
  `equipment_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '设备类型（装卸主/动力/输送/辅助/照明/转运）',
  `rated_power_kw` decimal(10,2) DEFAULT NULL COMMENT '额定功率(kW)',
  `energy_types` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '涉及能源类型（逗号分隔：electricity,water,air）',
  `demo_role` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '五幕角色标注（第三幕主角/正常对照组等）',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态（0启用 1停用）',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`equipment_id`),
  UNIQUE KEY `uk_equipment_code` (`equipment_code`),
  KEY `idx_area` (`area_id`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备台账';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_equipment_profile`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_equipment_profile` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `equipment_id` bigint NOT NULL COMMENT '设备ID',
  `energy_type_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '能源类型',
  `period_start` datetime NOT NULL COMMENT '统计周期起',
  `period_end` datetime NOT NULL COMMENT '统计周期止',
  `work_energy` decimal(20,4) DEFAULT '0.0000' COMMENT '作业能耗',
  `standby_energy` decimal(20,4) DEFAULT '0.0000' COMMENT '待机能耗',
  `aux_energy` decimal(20,4) DEFAULT '0.0000' COMMENT '辅助能耗',
  `peak_load_kw` decimal(12,4) DEFAULT NULL COMMENT '峰值负荷(kW)',
  `peak_load_time` datetime DEFAULT NULL COMMENT '峰值负荷出现时间',
  `cost` decimal(20,4) DEFAULT '0.0000' COMMENT '成本',
  `abnormal_count` int DEFAULT '0' COMMENT '异常数',
  `quality_summary` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '质量摘要',
  `computed_at` datetime DEFAULT NULL COMMENT '计算时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_equipment_period` (`equipment_id`,`energy_type_code`,`period_start`,`period_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备能耗画像';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_equipment_status_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_equipment_status_log` (
  `log_id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `equipment_id` bigint NOT NULL COMMENT '设备ID',
  `event_time` datetime NOT NULL COMMENT '事件时刻',
  `status_value` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '运行状态（running/standby/stopped/maintenance）',
  `event_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型（event/heartbeat）',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`log_id`),
  KEY `idx_equipment_time` (`equipment_id`,`event_time`)
) ENGINE=InnoDB AUTO_INCREMENT=26968 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备运行状态日志';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_forecast_result`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_forecast_result` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `scope` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '范围（area/equipment/system）',
  `scope_id` bigint DEFAULT NULL COMMENT '对象ID',
  `energy_type_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '能源类型',
  `horizon_hours` int NOT NULL COMMENT '预测跨度（24/168）',
  `target_time` datetime NOT NULL COMMENT '预测目标时间',
  `predicted_value` decimal(20,4) NOT NULL COMMENT '预测值',
  `ci_lower` decimal(20,4) DEFAULT NULL COMMENT '置信下界（±1.96σ）',
  `ci_upper` decimal(20,4) DEFAULT NULL COMMENT '置信上界',
  `method_version` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '方法版本',
  `history_window_weeks` int DEFAULT NULL COMMENT '历史窗口（周）',
  `quality_flag` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT 'normal' COMMENT '质量标记（normal/insufficient）',
  `computed_at` datetime DEFAULT NULL COMMENT '计算时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scope_target` (`scope`,`scope_id`,`energy_type_code`,`target_time`,`horizon_hours`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='短期预测结果';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_meter_point`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_meter_point` (
  `point_id` bigint NOT NULL AUTO_INCREMENT COMMENT '采集点ID',
  `point_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '采集点编号（全局唯一，停用后保留历史）',
  `point_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '显示名称',
  `area_id` bigint NOT NULL COMMENT '所属装卸区ID',
  `equipment_id` bigint DEFAULT NULL COMMENT '所属设备ID（环境/照明点可空）',
  `point_category` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '采集点类别（device_meter/area_meter/branch_meter/water/air_flow/status/env）',
  `energy_type_code` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '能源类型编码（关联 e_dict_energy_type，状态/环境点可空）',
  `unit` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '计量单位（kWh/m3/none）',
  `sample_period_sec` int NOT NULL COMMENT '采样周期（秒）：电/水 900、压缩空气 300、状态 300',
  `source_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '接口来源（gateway/data_platform/offline_import/manual）',
  `multiplier` decimal(10,4) DEFAULT '1.0000' COMMENT '倍率（换表带生效时间，附录C）',
  `multiplier_effective_time` datetime DEFAULT NULL COMMENT '倍率生效时间',
  `range_min` decimal(20,4) DEFAULT NULL COMMENT '量程下限（超量程质量判定 REQ-021）',
  `range_max` decimal(20,4) DEFAULT NULL COMMENT '量程上限',
  `provision_status` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT 'active' COMMENT '配备状态（active/pending/manual/estimated/reserved/excluded，REQ-006）',
  `access_mode` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT 'direct' COMMENT '接入方式（direct直连 / external外置接入，REQ-103）',
  `protocol_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '协议类型（MODBUS/OPC_UA/MQTT/HTTP，外置必填）',
  `edge_gateway` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '边缘网关来源（外置必填）',
  `owner_role` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '责任对象角色',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT 'enabled' COMMENT '状态（pending_mapping/enabled/disabled/maintenance/replaced/archived，附录C）',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`point_id`),
  UNIQUE KEY `uk_point_code` (`point_code`),
  KEY `idx_area_equipment` (`area_id`,`equipment_id`),
  KEY `idx_energy_type` (`energy_type_code`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=49 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采集点主数据';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_raw_reading`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_raw_reading` (
  `reading_id` bigint NOT NULL AUTO_INCREMENT COMMENT '原始读数ID',
  `point_id` bigint NOT NULL COMMENT '采集点ID',
  `sample_time` datetime NOT NULL COMMENT '采样时间（业务时刻）',
  `cumulative_value` decimal(20,4) DEFAULT NULL COMMENT '累计值（表计读数，状态点为空）',
  `incremental_value` decimal(20,4) DEFAULT NULL COMMENT '本周期增量（负值进质量事件）',
  `status_value` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '状态值（running/standby/stopped/maintenance，状态点用）',
  `unit` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '单位（冗余，便于导出）',
  `quality_state` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '质量状态八态短码（ok正常/miss缺测/late迟到/dup重复/jump跳变/est估算/fix人工修正/frozen冻结疑似，对齐 docs/mock-contracts.md §2.1，附录C）',
  `source_batch` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源批次号（关联 e_collect_task.batch_no）',
  `ingest_time` datetime NOT NULL COMMENT '入库时间（用于迟到判定 R02）',
  `is_backfill` tinyint(1) DEFAULT '0' COMMENT '是否补传（0否 1是，REQ-014）',
  `is_estimated` tinyint(1) DEFAULT '0' COMMENT '是否估算填补',
  `correction_reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '人工修正原因（走审批 REQ-074）',
  PRIMARY KEY (`reading_id`),
  UNIQUE KEY `uk_point_sample` (`point_id`,`sample_time`),
  KEY `idx_ingest_time` (`ingest_time`),
  KEY `idx_quality` (`quality_state`),
  KEY `idx_batch` (`source_batch`)
) ENGINE=InnoDB AUTO_INCREMENT=564481 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='原始采集读数（时序）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_recompute_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_recompute_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `target_table` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '重算目标表名',
  `target_key_json` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '目标定位键（json：object_type/id/period）',
  `old_version_no` int DEFAULT NULL COMMENT '旧版本号',
  `new_version_no` int DEFAULT NULL COMMENT '新版本号',
  `delta_value` decimal(20,4) DEFAULT NULL COMMENT '数值差异',
  `delta_pct` decimal(8,4) DEFAULT NULL COMMENT '差异百分比',
  `trigger_reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '触发原因（补传/换表/规则调整）',
  `operator` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人',
  `created_at` datetime DEFAULT NULL COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_table`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='统计重算日志';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_report_archive`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_report_archive` (
  `archive_id` bigint NOT NULL AUTO_INCREMENT COMMENT '报表归档ID',
  `template_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '报表模板编码',
  `template_version` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '报表模板版本',
  `period_start` date NOT NULL COMMENT '报告周期起',
  `period_end` date NOT NULL COMMENT '报告周期止',
  `filters_snapshot_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '规范化查询条件冻结快照（json）',
  `payload_snapshot_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '完整 canonical report payload（json）',
  `version_snapshots_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '实际参与的业务版本冻结快照（json）',
  `full_signature` varchar(96) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '完整报表签名（REPORT-SHA256-V1:<64hex>，REQ-062）',
  `generated_at` datetime NOT NULL COMMENT '系统统计时钟生成时间',
  `archived_by` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '归档账号',
  `archived_at` datetime NOT NULL COMMENT '归档时间',
  PRIMARY KEY (`archive_id`),
  KEY `idx_report_archive_period` (`template_code`,`period_start`,`period_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='报表快照归档';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_stat_day`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_stat_day` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `object_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '统计对象（point/equipment/area）',
  `object_id` bigint NOT NULL COMMENT '对象ID',
  `energy_type_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '能源类型',
  `stat_date` date NOT NULL COMMENT '统计日期',
  `total_value` decimal(20,4) DEFAULT '0.0000' COMMENT '当日总量',
  `peak_value` decimal(20,4) DEFAULT '0.0000' COMMENT '峰段量',
  `flat_value` decimal(20,4) DEFAULT '0.0000' COMMENT '平段量',
  `valley_value` decimal(20,4) DEFAULT '0.0000' COMMENT '谷段量',
  `coverage_ratio` decimal(6,4) DEFAULT NULL COMMENT '覆盖率（0–1）',
  `workday_flag` tinyint(1) DEFAULT '1' COMMENT '是否工作日（0周末 1工作日）',
  `baseline_id` bigint DEFAULT NULL COMMENT '关联基线ID',
  `baseline_deviation_pct` decimal(8,4) DEFAULT NULL COMMENT '基线偏差百分比',
  `version_no` int DEFAULT '1' COMMENT '版本号',
  `has_recompute_pending` tinyint(1) DEFAULT '0' COMMENT '待重算标记（REQ-020）',
  `computed_at` datetime DEFAULT NULL COMMENT '计算时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_object_date_type` (`object_type`,`object_id`,`energy_type_code`,`stat_date`),
  KEY `idx_stat_date` (`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='日统计';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_stat_hour`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_stat_hour` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `object_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '统计对象（point/equipment/area）',
  `object_id` bigint NOT NULL COMMENT '对象ID',
  `energy_type_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '能源类型',
  `stat_time` datetime NOT NULL COMMENT '小时时间桶（整点）',
  `total_value` decimal(20,4) DEFAULT '0.0000' COMMENT '本小时增量',
  `avg_power_kw` decimal(12,4) DEFAULT NULL COMMENT '平均功率(kW)（电类）',
  `coverage_ratio` decimal(6,4) DEFAULT NULL COMMENT '覆盖率（0–1）',
  `quality_summary` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '质量摘要（正常X/迟到Y/缺测Z）',
  `tou_period` varchar(8) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '峰平谷（peak/flat/valley）',
  `version_no` int DEFAULT '1' COMMENT '版本号（重算+1）',
  `computed_at` datetime DEFAULT NULL COMMENT '计算时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_object_time_type` (`object_type`,`object_id`,`energy_type_code`,`stat_time`),
  KEY `idx_stat_time` (`stat_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='小时统计';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_stat_month`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_stat_month` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `object_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '统计对象',
  `object_id` bigint NOT NULL COMMENT '对象ID',
  `energy_type_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '能源类型',
  `stat_month` char(7) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '统计月份（YYYY-MM）',
  `total_value` decimal(20,4) DEFAULT '0.0000' COMMENT '当月总量',
  `peak_value` decimal(20,4) DEFAULT '0.0000' COMMENT '峰段量',
  `flat_value` decimal(20,4) DEFAULT '0.0000' COMMENT '平段量',
  `valley_value` decimal(20,4) DEFAULT '0.0000' COMMENT '谷段量',
  `coverage_ratio` decimal(6,4) DEFAULT NULL COMMENT '覆盖率',
  `version_no` int DEFAULT '1' COMMENT '版本号',
  `computed_at` datetime DEFAULT NULL COMMENT '计算时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_object_month_type` (`object_type`,`object_id`,`energy_type_code`,`stat_month`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='月统计';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_suggestion`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_suggestion` (
  `suggestion_id` bigint NOT NULL AUTO_INCREMENT COMMENT '建议ID',
  `source_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '来源（rule/manual）',
  `source_alert_id` bigint DEFAULT NULL COMMENT '来源告警ID（冻结弱引用，不建FK）',
  `source_fingerprint` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源业务指纹',
  `source_snapshot_json` longtext COLLATE utf8mb4_unicode_ci COMMENT '来源冻结快照（json）',
  `template_id` bigint DEFAULT NULL COMMENT '生成时模板ID',
  `template_version` int DEFAULT NULL COMMENT '生成时模板版本',
  `template_snapshot_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模板或人工建议冻结快照（json）',
  `trigger_basis` text COLLATE utf8mb4_unicode_ci COMMENT '触发/人工来源依据',
  `rule_code` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规则编码（PRD §7.2）',
  `title` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
  `measure_content` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '人工措施内容（无控制指令）',
  `responsible_user` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '责任账号',
  `responsible_role` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '责任角色',
  `verify_start` date DEFAULT NULL COMMENT '验证期起（列表兼容字段）',
  `verify_end` date DEFAULT NULL COMMENT '验证期止（列表兼容字段）',
  `area_id` bigint DEFAULT NULL COMMENT '装卸区ID',
  `equipment_id` bigint DEFAULT NULL COMMENT '设备ID',
  `object_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '建议对象类型',
  `object_id` bigint DEFAULT NULL COMMENT '建议对象ID（system对象可空）',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'pending/dispatched/executing/verifying/valid_closed/invalid_closed/deferred',
  `priority_score` decimal(6,2) NOT NULL COMMENT '后端保存的综合分',
  `priority_band` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'high/medium/low',
  `priority_formula_version` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '优先级公式版本',
  `priority_factors_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '五因子、权重与依据（json）',
  `repair_at` datetime DEFAULT NULL COMMENT '修复分界',
  `baseline_start` datetime DEFAULT NULL COMMENT '基线窗口左边界',
  `baseline_end` datetime DEFAULT NULL COMMENT '基线窗口右开边界',
  `report_start` datetime DEFAULT NULL COMMENT '报告窗口左边界',
  `report_end` datetime DEFAULT NULL COMMENT '报告窗口右开边界',
  `saving_value` decimal(20,4) DEFAULT NULL COMMENT '通用节能量',
  `saving_unit` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '通用节能量单位',
  `close_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'implemented/rejected/archived_invalid',
  `close_reason` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '关闭原因',
  `rejection_reason` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '驳回原因',
  `invalid_category` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '无效分类',
  `deferred_from_status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '延期前状态',
  `defer_reason` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '延期原因',
  `defer_until` date DEFAULT NULL COMMENT '延期恢复日期',
  `effect_summary` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '效果说明',
  `attachments_json` longtext COLLATE utf8mb4_unicode_ci COMMENT '占位附件元数据（json）',
  `created_by` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '创建账号',
  `closed_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '关闭账号',
  `closed_at` datetime DEFAULT NULL COMMENT '关闭时间',
  `row_version` int NOT NULL DEFAULT '1' COMMENT '乐观锁版本',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`suggestion_id`),
  UNIQUE KEY `uk_source_template` (`source_fingerprint`,`template_id`),
  KEY `idx_status` (`status`),
  KEY `idx_source_alert` (`source_alert_id`),
  KEY `idx_rule_area` (`rule_code`,`area_id`),
  KEY `idx_priority` (`priority_score`,`suggestion_id`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='节能建议实例（REQ-046~050）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_suggestion_flow_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_suggestion_flow_log` (
  `flow_id` bigint NOT NULL AUTO_INCREMENT COMMENT '流转记录ID（时间线唯一排序键）',
  `suggestion_id` bigint NOT NULL COMMENT '建议ID',
  `from_status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '流转前状态（创建时null）',
  `to_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '流转后状态',
  `operator` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务端确认的操作账号',
  `operator_role` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务端确认的操作角色',
  `action` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '流转动作',
  `remark` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '人工备注',
  `payload_snapshot_json` longtext COLLATE utf8mb4_unicode_ci COMMENT '流转证据快照（json）',
  `occur_time` datetime NOT NULL COMMENT '流转发生时间（可相同）',
  PRIMARY KEY (`flow_id`),
  KEY `idx_suggestion_flow` (`suggestion_id`,`flow_id`)
) ENGINE=InnoDB AUTO_INCREMENT=26 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='建议流转留痕（REQ-047）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_suggestion_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_suggestion_template` (
  `template_id` bigint NOT NULL AUTO_INCREMENT COMMENT '模板ID',
  `template_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模板编码',
  `template_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模板名称',
  `category` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '建议分类',
  `source_rule_code` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '适用规则（PRD §7.2）',
  `applicable_object_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '适用对象类型',
  `action_content` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '人工措施内容（REQ-091/096）',
  `required_data` text COLLATE utf8mb4_unicode_ci COMMENT '所需数据说明',
  `estimated_saving` text COLLATE utf8mb4_unicode_ci COMMENT '预估节能量口径',
  `cost_impact` text COLLATE utf8mb4_unicode_ci COMMENT '成本影响口径',
  `reliability_impact` text COLLATE utf8mb4_unicode_ci COMMENT '可靠性影响',
  `verification_method` text COLLATE utf8mb4_unicode_ci COMMENT '验证方式',
  `default_implementation_difficulty` decimal(5,2) NOT NULL COMMENT '默认实施难度（0~100）',
  `default_safety_impact` decimal(5,2) NOT NULL COMMENT '默认安全影响（0~100）',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `version` int NOT NULL DEFAULT '1' COMMENT '模板版本',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`template_id`),
  UNIQUE KEY `uk_template_code_version` (`template_code`,`version`),
  KEY `idx_template_rule_enabled` (`source_rule_code`,`enabled`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='节能建议模板（REQ-045）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_suggestion_verification`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_suggestion_verification` (
  `verification_id` bigint NOT NULL AUTO_INCREMENT COMMENT '验证快照ID',
  `suggestion_id` bigint NOT NULL COMMENT '建议ID',
  `version` int NOT NULL COMMENT '验证版本',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'waiting/effective/ineffective/insufficient',
  `repair_at` datetime NOT NULL COMMENT '修复分界',
  `baseline_start` datetime NOT NULL COMMENT '基线窗口左边界',
  `baseline_end` datetime NOT NULL COMMENT '基线窗口右开边界',
  `report_start` datetime NOT NULL COMMENT '报告窗口左边界',
  `report_end` datetime NOT NULL COMMENT '报告窗口右开边界',
  `usage_comparison_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用量对比（json）',
  `cost_comparison_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '成本对比（json）',
  `workload_comparison_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '作业量对比（json）',
  `quality_comparison_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '数据质量对比（json）',
  `saving_value` decimal(20,4) DEFAULT NULL COMMENT '节能量',
  `saving_unit` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '节能量单位',
  `saving_pct` decimal(9,4) DEFAULT NULL COMMENT '用量率改善百分比',
  `calculation_note` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '计算说明',
  `formula_version` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '公式版本',
  `signature` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '口径签名',
  `generated_by` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '生成账号',
  `generated_at` datetime NOT NULL COMMENT '生成时间',
  PRIMARY KEY (`verification_id`),
  UNIQUE KEY `uk_suggestion_verification_version` (`suggestion_id`,`version`),
  KEY `idx_verification_status` (`status`,`generated_at`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='建议验证快照（REQ-048）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_tariff_version`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_tariff_version` (
  `tariff_id` bigint NOT NULL AUTO_INCREMENT COMMENT '单价版本ID',
  `energy_type_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '能源类型',
  `tou_period` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '峰平谷（peak/flat/valley/flat_only）',
  `price` decimal(10,4) NOT NULL COMMENT '单价',
  `currency` varchar(8) COLLATE utf8mb4_unicode_ci DEFAULT 'CNY' COMMENT '币种',
  `effective_from` date NOT NULL COMMENT '生效起',
  `effective_to` date DEFAULT NULL COMMENT '生效止（null 表示当前有效）',
  `version_no` int DEFAULT '1' COMMENT '版本号',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`tariff_id`),
  UNIQUE KEY `uk_type_period_from` (`energy_type_code`,`tou_period`,`effective_from`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='单价版本表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `e_work_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `e_work_order` (
  `work_order_id` bigint NOT NULL AUTO_INCREMENT COMMENT '工单ID',
  `order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '工单号',
  `area_id` bigint NOT NULL COMMENT '装卸区ID',
  `equipment_id` bigint DEFAULT NULL COMMENT '主作业设备ID（可空表示未指定）',
  `cargo_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '货类（钢材/粉煤灰）',
  `workload_value` decimal(12,2) NOT NULL COMMENT '作业量',
  `workload_unit` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '作业量单位（吨）',
  `start_time` datetime NOT NULL COMMENT '起始时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间（in_progress 可空）',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态（completed/in_progress/cancelled）',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`work_order_id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_area_time` (`area_id`,`start_time`),
  KEY `idx_equipment_time` (`equipment_id`,`start_time`)
) ENGINE=InnoDB AUTO_INCREMENT=688 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='mock 作业工单';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `gen_table`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `gen_table` (
  `table_id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `table_name` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '表名称',
  `table_comment` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '表描述',
  `sub_table_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '关联子表的表名',
  `sub_table_fk_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '子表关联的外键名',
  `class_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '实体类名称',
  `tpl_category` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT 'crud' COMMENT '使用的模板（crud单表操作 tree树表操作）',
  `tpl_web_type` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '前端模板类型（element-ui模版 element-plus模版）',
  `package_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '生成包路径',
  `module_name` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '生成模块名',
  `business_name` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '生成业务名',
  `function_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '生成功能名',
  `function_author` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '生成功能作者',
  `gen_type` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '生成代码方式（0zip压缩包 1自定义路径）',
  `gen_path` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '/' COMMENT '生成路径（不填默认项目路径）',
  `options` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '其它生成选项',
  `create_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`table_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='代码生成业务表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `gen_table_column`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `gen_table_column` (
  `column_id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `table_id` bigint DEFAULT NULL COMMENT '归属表编号',
  `column_name` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列名称',
  `column_comment` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列描述',
  `column_type` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '列类型',
  `python_type` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'PYTHON类型',
  `python_field` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'PYTHON字段名',
  `is_pk` char(1) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '是否主键（1是）',
  `is_increment` char(1) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '是否自增（1是）',
  `is_required` char(1) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '是否必填（1是）',
  `is_unique` char(1) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '是否唯一（1是）',
  `is_insert` char(1) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '是否为插入字段（1是）',
  `is_edit` char(1) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '是否编辑字段（1是）',
  `is_list` char(1) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '是否列表字段（1是）',
  `is_query` char(1) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '是否查询字段（1是）',
  `query_type` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT 'EQ' COMMENT '查询方式（等于、不等于、大于、小于、范围）',
  `html_type` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '显示类型（文本框、文本域、下拉框、复选框、单选框、日期控件）',
  `dict_type` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '字典类型',
  `sort` int DEFAULT NULL COMMENT '排序',
  `create_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`column_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='代码生成业务表字段';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_config` (
  `config_id` int NOT NULL AUTO_INCREMENT COMMENT '参数主键',
  `config_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '参数名称',
  `config_key` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '参数键名',
  `config_value` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '参数键值',
  `config_type` char(1) COLLATE utf8mb4_unicode_ci DEFAULT 'N' COMMENT '系统内置（Y是 N否）',
  `create_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`config_id`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='参数配置表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_dept`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_dept` (
  `dept_id` bigint NOT NULL AUTO_INCREMENT COMMENT '部门id',
  `parent_id` bigint DEFAULT '0' COMMENT '父部门id',
  `ancestors` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '祖级列表',
  `dept_name` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '部门名称',
  `order_num` int DEFAULT '0' COMMENT '显示顺序',
  `leader` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '负责人',
  `phone` varchar(11) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '联系电话',
  `email` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '部门状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `create_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`dept_id`)
) ENGINE=InnoDB AUTO_INCREMENT=200 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='部门表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_dict_data`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_dict_data` (
  `dict_code` bigint NOT NULL AUTO_INCREMENT COMMENT '字典编码',
  `dict_sort` int DEFAULT '0' COMMENT '字典排序',
  `dict_label` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '字典标签',
  `dict_value` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '字典键值',
  `dict_type` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '字典类型',
  `css_class` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '样式属性（其他样式扩展）',
  `list_class` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '表格回显样式',
  `is_default` char(1) COLLATE utf8mb4_unicode_ci DEFAULT 'N' COMMENT '是否默认（Y是 N否）',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态（0正常 1停用）',
  `create_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`dict_code`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典数据表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_dict_type`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_dict_type` (
  `dict_id` bigint NOT NULL AUTO_INCREMENT COMMENT '字典主键',
  `dict_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '字典名称',
  `dict_type` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '字典类型',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态（0正常 1停用）',
  `create_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`dict_id`),
  UNIQUE KEY `dict_type` (`dict_type`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典类型表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_job`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_job` (
  `job_id` bigint NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  `job_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '任务名称',
  `job_group` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '任务组名',
  `job_executor` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT 'default' COMMENT '任务执行器',
  `invoke_target` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '调用目标字符串',
  `job_args` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '位置参数',
  `job_kwargs` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '关键字参数',
  `cron_expression` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT 'cron执行表达式',
  `misfire_policy` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT '3' COMMENT '计划执行错误策略（1立即执行 2执行一次 3放弃执行）',
  `concurrent` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '1' COMMENT '是否并发执行（0允许 1禁止）',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '状态（0正常 1暂停）',
  `create_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '备注信息',
  PRIMARY KEY (`job_id`,`job_name`,`job_group`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='定时任务调度表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_job_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_job_log` (
  `job_log_id` bigint NOT NULL AUTO_INCREMENT COMMENT '任务日志ID',
  `job_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务名称',
  `job_group` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务组名',
  `job_executor` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务执行器',
  `invoke_target` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '调用目标字符串',
  `job_args` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '位置参数',
  `job_kwargs` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '关键字参数',
  `job_trigger` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '任务触发器',
  `job_message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '日志信息',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '执行状态（0正常 1失败）',
  `exception_info` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '异常信息',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`job_log_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='定时任务调度日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_logininfor`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_logininfor` (
  `info_id` bigint NOT NULL AUTO_INCREMENT COMMENT '访问ID',
  `user_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '用户账号',
  `ipaddr` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '登录IP地址',
  `login_location` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '登录地点',
  `browser` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '浏览器类型',
  `os` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '操作系统',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '登录状态（0成功 1失败）',
  `msg` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '提示消息',
  `login_time` datetime DEFAULT NULL COMMENT '访问时间',
  PRIMARY KEY (`info_id`),
  KEY `idx_sys_logininfor_s` (`status`),
  KEY `idx_sys_logininfor_lt` (`login_time`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统访问记录';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_menu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_menu` (
  `menu_id` bigint NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
  `menu_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '菜单名称',
  `parent_id` bigint DEFAULT '0' COMMENT '父菜单ID',
  `order_num` int DEFAULT '0' COMMENT '显示顺序',
  `path` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '路由地址',
  `component` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '组件路径',
  `query` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '路由参数',
  `route_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '路由名称',
  `is_frame` int DEFAULT '1' COMMENT '是否为外链（0是 1否）',
  `is_cache` int DEFAULT '0' COMMENT '是否缓存（0缓存 1不缓存）',
  `menu_type` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '菜单类型（M目录 C菜单 F按钮）',
  `visible` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '菜单状态（0显示 1隐藏）',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '菜单状态（0正常 1停用）',
  `perms` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '权限标识',
  `icon` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT '#' COMMENT '菜单图标',
  `create_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '备注',
  PRIMARY KEY (`menu_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2016 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜单权限表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_notice`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_notice` (
  `notice_id` int NOT NULL AUTO_INCREMENT COMMENT '公告ID',
  `notice_title` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '公告标题',
  `notice_type` char(1) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '公告类型（1通知 2公告）',
  `notice_content` longblob COMMENT '公告内容',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '公告状态（0正常 1关闭）',
  `create_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`notice_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知公告表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_oper_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_oper_log` (
  `oper_id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志主键',
  `title` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '模块标题',
  `business_type` int DEFAULT '0' COMMENT '业务类型（0其它 1新增 2修改 3删除）',
  `method` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '方法名称',
  `request_method` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '请求方式',
  `operator_type` int DEFAULT '0' COMMENT '操作类别（0其它 1后台用户 2手机端用户）',
  `oper_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '操作人员',
  `dept_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '部门名称',
  `oper_url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '请求URL',
  `oper_ip` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '主机地址',
  `oper_location` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '操作地点',
  `oper_param` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '请求参数',
  `json_result` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '返回参数',
  `status` int DEFAULT '0' COMMENT '操作状态（0正常 1异常）',
  `error_msg` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '错误消息',
  `oper_time` datetime DEFAULT NULL COMMENT '操作时间',
  `cost_time` bigint DEFAULT '0' COMMENT '消耗时间',
  PRIMARY KEY (`oper_id`),
  KEY `idx_sys_oper_log_bt` (`business_type`),
  KEY `idx_sys_oper_log_s` (`status`),
  KEY `idx_sys_oper_log_ot` (`oper_time`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志记录';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_post`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_post` (
  `post_id` bigint NOT NULL AUTO_INCREMENT COMMENT '岗位ID',
  `post_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '岗位编码',
  `post_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '岗位名称',
  `post_sort` int NOT NULL COMMENT '显示顺序',
  `status` char(1) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态（0正常 1停用）',
  `create_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`post_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='岗位信息表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role` (
  `role_id` bigint NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `role_name` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色名称',
  `role_key` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色权限字符串',
  `role_sort` int NOT NULL COMMENT '显示顺序',
  `data_scope` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '1' COMMENT '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限）',
  `menu_check_strictly` tinyint(1) DEFAULT '1' COMMENT '菜单树选择项是否关联显示',
  `dept_check_strictly` tinyint(1) DEFAULT '1' COMMENT '部门树选择项是否关联显示',
  `status` char(1) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `create_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色信息表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_role_dept`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_dept` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `dept_id` bigint NOT NULL COMMENT '部门ID',
  PRIMARY KEY (`role_id`,`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色和部门关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_role_menu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_menu` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `menu_id` bigint NOT NULL COMMENT '菜单ID',
  PRIMARY KEY (`role_id`,`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色和菜单关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user` (
  `user_id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `dept_id` bigint DEFAULT NULL COMMENT '部门ID',
  `user_name` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户账号',
  `nick_name` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户昵称',
  `user_type` varchar(2) COLLATE utf8mb4_unicode_ci DEFAULT '00' COMMENT '用户类型（00系统用户）',
  `email` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '用户邮箱',
  `phonenumber` varchar(11) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '手机号码',
  `sex` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '用户性别（0男 1女 2未知）',
  `avatar` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '头像地址',
  `password` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '密码',
  `status` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '帐号状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_unicode_ci DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `login_ip` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '最后登录IP',
  `login_date` datetime DEFAULT NULL COMMENT '最后登录时间',
  `pwd_update_date` datetime DEFAULT NULL COMMENT '密码最后更新时间',
  `create_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=104 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户信息表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_user_post`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user_post` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `post_id` bigint NOT NULL COMMENT '岗位ID',
  PRIMARY KEY (`user_id`,`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户与岗位关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_user_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user_role` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  PRIMARY KEY (`user_id`,`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户和角色关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

