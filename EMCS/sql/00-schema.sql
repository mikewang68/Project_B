-- EMCS openGauss 6.0.x; database compatibility PG. Destructive reset of project tables only.
\set ON_ERROR_STOP on
SET search_path TO public;
SET timezone TO 'Asia/Shanghai';
BEGIN;
DROP TABLE IF EXISTS "sys_user_role" CASCADE;
DROP TABLE IF EXISTS "sys_user_post" CASCADE;
DROP TABLE IF EXISTS "sys_user" CASCADE;
DROP TABLE IF EXISTS "sys_role_menu" CASCADE;
DROP TABLE IF EXISTS "sys_role_dept" CASCADE;
DROP TABLE IF EXISTS "sys_role" CASCADE;
DROP TABLE IF EXISTS "sys_post" CASCADE;
DROP TABLE IF EXISTS "sys_oper_log" CASCADE;
DROP TABLE IF EXISTS "sys_notice" CASCADE;
DROP TABLE IF EXISTS "sys_menu" CASCADE;
DROP TABLE IF EXISTS "sys_logininfor" CASCADE;
DROP TABLE IF EXISTS "sys_job_log" CASCADE;
DROP TABLE IF EXISTS "sys_job" CASCADE;
DROP TABLE IF EXISTS "sys_dict_type" CASCADE;
DROP TABLE IF EXISTS "sys_dict_data" CASCADE;
DROP TABLE IF EXISTS "sys_dept" CASCADE;
DROP TABLE IF EXISTS "sys_config" CASCADE;
DROP TABLE IF EXISTS "gen_table_column" CASCADE;
DROP TABLE IF EXISTS "gen_table" CASCADE;
DROP TABLE IF EXISTS "e_work_order" CASCADE;
DROP TABLE IF EXISTS "e_tariff_version" CASCADE;
DROP TABLE IF EXISTS "e_suggestion_verification" CASCADE;
DROP TABLE IF EXISTS "e_suggestion_template" CASCADE;
DROP TABLE IF EXISTS "e_suggestion_flow_log" CASCADE;
DROP TABLE IF EXISTS "e_suggestion" CASCADE;
DROP TABLE IF EXISTS "e_stat_month" CASCADE;
DROP TABLE IF EXISTS "e_stat_hour" CASCADE;
DROP TABLE IF EXISTS "e_stat_day" CASCADE;
DROP TABLE IF EXISTS "e_report_archive" CASCADE;
DROP TABLE IF EXISTS "e_recompute_log" CASCADE;
DROP TABLE IF EXISTS "e_raw_reading" CASCADE;
DROP TABLE IF EXISTS "e_meter_point" CASCADE;
DROP TABLE IF EXISTS "e_forecast_result" CASCADE;
DROP TABLE IF EXISTS "e_equipment_status_log" CASCADE;
DROP TABLE IF EXISTS "e_equipment_profile" CASCADE;
DROP TABLE IF EXISTS "e_equipment" CASCADE;
DROP TABLE IF EXISTS "e_energy_baseline" CASCADE;
DROP TABLE IF EXISTS "e_dict_energy_type" CASCADE;
DROP TABLE IF EXISTS "e_cost_record" CASCADE;
DROP TABLE IF EXISTS "e_cost_recompute_record" CASCADE;
DROP TABLE IF EXISTS "e_cost_alloc_rule" CASCADE;
DROP TABLE IF EXISTS "e_collect_task" CASCADE;
DROP TABLE IF EXISTS "e_audit_security" CASCADE;
DROP TABLE IF EXISTS "e_area" CASCADE;
DROP TABLE IF EXISTS "e_alert_rule_version" CASCADE;
DROP TABLE IF EXISTS "e_alert_rule" CASCADE;
DROP TABLE IF EXISTS "e_alert_flow_log" CASCADE;
DROP TABLE IF EXISTS "e_alert_event" CASCADE;
DROP TABLE IF EXISTS "ai_models" CASCADE;
DROP TABLE IF EXISTS "ai_inspection_report" CASCADE;
DROP TABLE IF EXISTS "ai_chat_config" CASCADE;
CREATE TABLE "ai_chat_config" (
  "chat_config_id" bigserial NOT NULL ,
  "user_id" bigint NOT NULL,
  "temperature" float DEFAULT NULL,
  "add_history_to_context" char(1) DEFAULT '0',
  "num_history_runs" int DEFAULT NULL,
  "system_prompt" text,
  "metrics_default_visible" char(1) DEFAULT '0',
  "vision_enabled" char(1) DEFAULT '1',
  "image_max_size_mb" int DEFAULT NULL,
  "create_time" timestamp DEFAULT NULL,
  "update_time" timestamp DEFAULT NULL,
  PRIMARY KEY ("chat_config_id"),
  UNIQUE ("user_id")
);
CREATE TABLE "ai_inspection_report" (
  "id" bigserial NOT NULL ,
  "report_date" date NOT NULL,
  "trigger_type" varchar(16) NOT NULL,
  "status" varchar(16) NOT NULL,
  "summary" varchar(1024) DEFAULT NULL,
  "findings_json" json DEFAULT NULL,
  "stats_json" json DEFAULT NULL,
  "model_name" varchar(64) DEFAULT NULL,
  "elapsed_ms" int DEFAULT NULL,
  "created_at" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id")
);
CREATE INDEX "ai_inspection_report_idx_report_date" ON "ai_inspection_report" ("report_date");
CREATE TABLE "ai_models" (
  "model_id" bigserial NOT NULL ,
  "model_code" varchar(100) NOT NULL,
  "model_name" varchar(100) DEFAULT NULL,
  "provider" varchar(50) NOT NULL,
  "model_sort" int NOT NULL,
  "api_key" varchar(255) DEFAULT NULL,
  "base_url" varchar(255) DEFAULT NULL,
  "model_type" varchar(50) DEFAULT NULL,
  "max_tokens" int DEFAULT NULL,
  "temperature" float DEFAULT NULL,
  "support_reasoning" char(1) DEFAULT 'N',
  "support_images" char(1) DEFAULT 'N',
  "status" char(1) DEFAULT '0',
  "user_id" bigint DEFAULT NULL,
  "dept_id" bigint DEFAULT NULL,
  "create_by" varchar(64) DEFAULT '',
  "create_time" timestamp DEFAULT NULL,
  "update_by" varchar(64) DEFAULT '',
  "update_time" timestamp DEFAULT NULL,
  "remark" varchar(500) DEFAULT NULL,
  PRIMARY KEY ("model_id")
);
CREATE TABLE "e_alert_event" (
  "event_id" bigserial NOT NULL ,
  "rule_id" bigint NOT NULL,
  "rule_code" varchar(16) NOT NULL,
  "rule_version_no" int NOT NULL,
  "object_type" varchar(16) NOT NULL,
  "object_id" bigint NOT NULL,
  "area_id" bigint DEFAULT NULL,
  "level" varchar(8) NOT NULL,
  "status" varchar(16) NOT NULL,
  "first_occur_time" timestamp NOT NULL,
  "last_occur_time" timestamp NOT NULL,
  "occur_count" int DEFAULT '1',
  "snapshot_json" text,
  "assigned_to" varchar(64) DEFAULT NULL,
  "closed_at" timestamp DEFAULT NULL,
  "close_reason" varchar(255) DEFAULT NULL,
  "close_type" varchar(16) DEFAULT NULL,
  "notification_json" varchar(1024) DEFAULT NULL,
  "create_time" timestamp DEFAULT NULL,
  "update_time" timestamp DEFAULT NULL,
  PRIMARY KEY ("event_id")
);
CREATE INDEX "e_alert_event_idx_status_level" ON "e_alert_event" ("status","level","first_occur_time");
CREATE INDEX "e_alert_event_idx_object" ON "e_alert_event" ("object_type","object_id","first_occur_time");
CREATE TABLE "e_alert_flow_log" (
  "flow_id" bigserial NOT NULL ,
  "event_id" bigint NOT NULL,
  "from_status" varchar(16) NOT NULL,
  "to_status" varchar(16) NOT NULL,
  "operator" varchar(64) NOT NULL,
  "remark" varchar(255) DEFAULT NULL,
  "occur_time" timestamp NOT NULL,
  PRIMARY KEY ("flow_id")
);
CREATE INDEX "e_alert_flow_log_idx_event_time" ON "e_alert_flow_log" ("event_id","occur_time");
CREATE TABLE "e_alert_rule" (
  "rule_id" bigserial NOT NULL ,
  "rule_code" varchar(16) NOT NULL,
  "rule_name" varchar(64) NOT NULL,
  "rule_category" varchar(32) DEFAULT NULL,
  "expression" varchar(512) DEFAULT NULL,
  "threshold_json" varchar(512) DEFAULT NULL,
  "level" varchar(8) NOT NULL,
  "enabled" smallint DEFAULT '1',
  "effective_from" timestamp DEFAULT NULL,
  "deprecated_at" timestamp DEFAULT NULL,
  "version_no" int DEFAULT '1',
  "remark" varchar(255) DEFAULT NULL,
  "create_by" varchar(64) DEFAULT NULL,
  "update_by" varchar(64) DEFAULT NULL,
  "create_time" timestamp DEFAULT NULL,
  "update_time" timestamp DEFAULT NULL,
  PRIMARY KEY ("rule_id"),
  UNIQUE ("rule_code")
);
CREATE TABLE "e_alert_rule_version" (
  "version_id" bigserial NOT NULL ,
  "rule_id" bigint NOT NULL,
  "version_no" int NOT NULL,
  "snapshot_json" text NOT NULL,
  "effective_from" timestamp DEFAULT NULL,
  "effective_to" timestamp DEFAULT NULL,
  "create_by" varchar(64) DEFAULT NULL,
  "create_time" timestamp DEFAULT NULL,
  PRIMARY KEY ("version_id"),
  UNIQUE ("rule_id","version_no")
);
CREATE TABLE "e_area" (
  "area_id" bigserial NOT NULL ,
  "area_code" varchar(32) NOT NULL,
  "area_name" varchar(64) NOT NULL,
  "cargo_type" varchar(32) NOT NULL,
  "dept_id" bigint DEFAULT NULL,
  "status" char(1) DEFAULT '0',
  "remark" varchar(255) DEFAULT NULL,
  "create_time" timestamp DEFAULT NULL,
  "update_time" timestamp DEFAULT NULL,
  PRIMARY KEY ("area_id"),
  UNIQUE ("area_code")
);
CREATE TABLE "e_audit_security" (
  "audit_id" bigserial NOT NULL ,
  "event_time" timestamp NOT NULL,
  "event_type" varchar(32) NOT NULL,
  "user_name" varchar(64) DEFAULT NULL,
  "user_role" varchar(32) DEFAULT NULL,
  "target_module" varchar(64) DEFAULT NULL,
  "target_resource" varchar(128) DEFAULT NULL,
  "client_ip" varchar(64) DEFAULT NULL,
  "user_agent" varchar(255) DEFAULT NULL,
  "action_result" varchar(16) DEFAULT 'blocked',
  "remark" varchar(255) DEFAULT NULL,
  "create_time" timestamp DEFAULT NULL,
  PRIMARY KEY ("audit_id")
);
CREATE INDEX "e_audit_security_idx_time_type" ON "e_audit_security" ("event_time","event_type");
CREATE INDEX "e_audit_security_idx_user" ON "e_audit_security" ("user_name");
CREATE TABLE "e_collect_task" (
  "task_id" bigserial NOT NULL ,
  "batch_no" varchar(64) NOT NULL,
  "area_id" bigint DEFAULT NULL,
  "point_scope" varchar(255) DEFAULT NULL,
  "scheduled_time" timestamp NOT NULL,
  "actual_ingest_time" timestamp DEFAULT NULL,
  "task_status" varchar(16) NOT NULL,
  "affected_point_count" int DEFAULT '0',
  "failure_reason" varchar(255) DEFAULT NULL,
  "retry_count" int DEFAULT '0',
  "outage_start" timestamp DEFAULT NULL,
  "outage_end" timestamp DEFAULT NULL,
  "parent_task_id" bigint DEFAULT NULL,
  "create_time" timestamp DEFAULT NULL,
  PRIMARY KEY ("task_id")
);
CREATE INDEX "e_collect_task_idx_batch" ON "e_collect_task" ("batch_no");
CREATE INDEX "e_collect_task_idx_status_time" ON "e_collect_task" ("task_status","scheduled_time");
CREATE TABLE "e_cost_alloc_rule" (
  "rule_id" bigserial NOT NULL ,
  "rule_name" varchar(64) NOT NULL,
  "scope" varchar(32) DEFAULT NULL,
  "method" varchar(32) NOT NULL,
  "config_json" varchar(1024) DEFAULT NULL,
  "effective_from" date NOT NULL,
  "effective_to" date DEFAULT NULL,
  "version_no" int DEFAULT '1',
  "create_by" varchar(64) DEFAULT NULL,
  "create_time" timestamp DEFAULT NULL,
  PRIMARY KEY ("rule_id")
);
CREATE INDEX "e_cost_alloc_rule_idx_effective_from" ON "e_cost_alloc_rule" ("effective_from");
CREATE TABLE "e_cost_recompute_record" (
  "recompute_id" bigserial NOT NULL ,
  "period_key" varchar(64) NOT NULL,
  "stat_month" char(7) NOT NULL,
  "energy_type_code" varchar(32) NOT NULL,
  "scope" varchar(64) NOT NULL,
  "old_cost_version" int NOT NULL,
  "new_cost_version" int NOT NULL,
  "trigger_reason" varchar(255) NOT NULL,
  "trigger_type" varchar(32) NOT NULL,
  "triggered_by" varchar(64) NOT NULL,
  "triggered_at" timestamp NOT NULL,
  "tariff_snapshot_json" text NOT NULL,
  "alloc_rule_snapshot_json" text NOT NULL,
  "diff_summary_json" text NOT NULL,
  "review_status" varchar(16) NOT NULL DEFAULT 'pending',
  "reviewed_by" varchar(64) DEFAULT NULL,
  "reviewed_at" timestamp DEFAULT NULL,
  "review_remark" varchar(255) DEFAULT NULL,
  PRIMARY KEY ("recompute_id")
);
CREATE INDEX "e_cost_recompute_record_idx_recompute_period" ON "e_cost_recompute_record" ("stat_month","energy_type_code","review_status");
CREATE TABLE "e_cost_record" (
  "id" bigserial NOT NULL ,
  "object_type" varchar(16) NOT NULL,
  "object_id" bigint DEFAULT NULL,
  "normalized_object_id" bigint GENERATED ALWAYS AS (coalesce("object_id",0)) STORED,
  "stat_month" char(7) NOT NULL,
  "energy_type_code" varchar(32) NOT NULL,
  "cost_version" int NOT NULL DEFAULT '1',
  "is_current" smallint NOT NULL DEFAULT '1',
  "current_guard" varchar(160) GENERATED ALWAYS AS ((case when ("is_current" = 1) then ("object_type" || ':' || coalesce("object_id",0)::text || ':' || "stat_month" || ':' || "energy_type_code") else NULL end)) STORED,
  "usage_qty" decimal(20,4) DEFAULT '0.0000',
  "peak_qty" decimal(20,4) DEFAULT '0.0000',
  "flat_qty" decimal(20,4) DEFAULT '0.0000',
  "valley_qty" decimal(20,4) DEFAULT '0.0000',
  "peak_cost" decimal(20,4) DEFAULT '0.0000',
  "flat_cost" decimal(20,4) DEFAULT '0.0000',
  "valley_cost" decimal(20,4) DEFAULT '0.0000',
  "total_cost" decimal(20,4) DEFAULT '0.0000',
  "tariff_version_no" varchar(64) DEFAULT NULL,
  "alloc_rule_version_no" varchar(64) DEFAULT NULL,
  "formula_version" varchar(64) NOT NULL,
  "tariff_snapshot_json" text NOT NULL,
  "alloc_rule_snapshot_json" text NOT NULL,
  "source_stat_snapshot_json" text NOT NULL,
  "status" varchar(16) DEFAULT 'draft',
  "reviewed_at" timestamp DEFAULT NULL,
  "signature" varchar(96) NOT NULL,
  "computed_by" varchar(64) NOT NULL,
  "computed_at" timestamp DEFAULT NULL,
  "frozen_at" timestamp DEFAULT NULL,
  PRIMARY KEY ("id"),
  UNIQUE ("object_type","normalized_object_id","stat_month","energy_type_code","cost_version"),
  UNIQUE ("current_guard")
);
CREATE INDEX "e_cost_record_idx_month_status" ON "e_cost_record" ("stat_month","status");
CREATE TABLE "e_dict_energy_type" (
  "type_code" varchar(32) NOT NULL,
  "type_name" varchar(64) NOT NULL,
  "base_unit" varchar(16) NOT NULL,
  "sort_no" int DEFAULT '0',
  "remark" varchar(255) DEFAULT NULL,
  PRIMARY KEY ("type_code")
);
CREATE TABLE "e_energy_baseline" (
  "baseline_id" bigserial NOT NULL ,
  "baseline_code" varchar(64) NOT NULL,
  "object_scope" varchar(16) NOT NULL,
  "object_id" bigint DEFAULT NULL,
  "energy_type_code" varchar(32) NOT NULL,
  "baseline_start" date NOT NULL,
  "baseline_end" date NOT NULL,
  "method" varchar(32) NOT NULL,
  "formula_version" varchar(32) DEFAULT NULL,
  "buckets_json" text,
  "status" varchar(16) DEFAULT 'draft',
  "adjustment_reason" varchar(255) DEFAULT NULL,
  "published_at" timestamp DEFAULT NULL,
  "published_by" varchar(64) DEFAULT NULL,
  "remark" varchar(255) DEFAULT NULL,
  "create_time" timestamp DEFAULT NULL,
  "update_time" timestamp DEFAULT NULL,
  PRIMARY KEY ("baseline_id"),
  UNIQUE ("baseline_code")
);
CREATE INDEX "e_energy_baseline_idx_scope_type" ON "e_energy_baseline" ("object_scope","object_id","energy_type_code");
CREATE TABLE "e_equipment" (
  "equipment_id" bigserial NOT NULL ,
  "equipment_code" varchar(32) NOT NULL,
  "equipment_name" varchar(64) NOT NULL,
  "area_id" bigint NOT NULL,
  "equipment_type" varchar(32) NOT NULL,
  "rated_power_kw" decimal(10,2) DEFAULT NULL,
  "energy_types" varchar(64) DEFAULT NULL,
  "demo_role" varchar(64) DEFAULT NULL,
  "status" char(1) DEFAULT '0',
  "remark" varchar(255) DEFAULT NULL,
  "create_time" timestamp DEFAULT NULL,
  "update_time" timestamp DEFAULT NULL,
  PRIMARY KEY ("equipment_id"),
  UNIQUE ("equipment_code")
);
CREATE INDEX "e_equipment_idx_area" ON "e_equipment" ("area_id");
CREATE TABLE "e_equipment_profile" (
  "id" bigserial NOT NULL ,
  "equipment_id" bigint NOT NULL,
  "energy_type_code" varchar(32) NOT NULL,
  "period_start" timestamp NOT NULL,
  "period_end" timestamp NOT NULL,
  "work_energy" decimal(20,4) DEFAULT '0.0000',
  "standby_energy" decimal(20,4) DEFAULT '0.0000',
  "aux_energy" decimal(20,4) DEFAULT '0.0000',
  "peak_load_kw" decimal(12,4) DEFAULT NULL,
  "peak_load_time" timestamp DEFAULT NULL,
  "cost" decimal(20,4) DEFAULT '0.0000',
  "abnormal_count" int DEFAULT '0',
  "quality_summary" varchar(255) DEFAULT NULL,
  "computed_at" timestamp DEFAULT NULL,
  PRIMARY KEY ("id"),
  UNIQUE ("equipment_id","energy_type_code","period_start","period_end")
);
CREATE TABLE "e_equipment_status_log" (
  "log_id" bigserial NOT NULL ,
  "equipment_id" bigint NOT NULL,
  "event_time" timestamp NOT NULL,
  "status_value" varchar(16) NOT NULL,
  "event_type" varchar(16) NOT NULL,
  "remark" varchar(255) DEFAULT NULL,
  PRIMARY KEY ("log_id")
);
CREATE INDEX "e_equipment_status_log_idx_equipment_time" ON "e_equipment_status_log" ("equipment_id","event_time");
CREATE TABLE "e_forecast_result" (
  "id" bigserial NOT NULL ,
  "scope" varchar(16) NOT NULL,
  "scope_id" bigint DEFAULT NULL,
  "energy_type_code" varchar(32) NOT NULL,
  "horizon_hours" int NOT NULL,
  "target_time" timestamp NOT NULL,
  "predicted_value" decimal(20,4) NOT NULL,
  "ci_lower" decimal(20,4) DEFAULT NULL,
  "ci_upper" decimal(20,4) DEFAULT NULL,
  "method_version" varchar(32) DEFAULT NULL,
  "history_window_weeks" int DEFAULT NULL,
  "quality_flag" varchar(16) DEFAULT 'normal',
  "computed_at" timestamp DEFAULT NULL,
  PRIMARY KEY ("id"),
  UNIQUE ("scope","scope_id","energy_type_code","target_time","horizon_hours")
);
CREATE TABLE "e_meter_point" (
  "point_id" bigserial NOT NULL ,
  "point_code" varchar(64) NOT NULL,
  "point_name" varchar(128) NOT NULL,
  "area_id" bigint NOT NULL,
  "equipment_id" bigint DEFAULT NULL,
  "point_category" varchar(32) NOT NULL,
  "energy_type_code" varchar(32) DEFAULT NULL,
  "unit" varchar(16) NOT NULL,
  "sample_period_sec" int NOT NULL,
  "source_type" varchar(32) NOT NULL,
  "multiplier" decimal(10,4) DEFAULT '1.0000',
  "multiplier_effective_time" timestamp DEFAULT NULL,
  "range_min" decimal(20,4) DEFAULT NULL,
  "range_max" decimal(20,4) DEFAULT NULL,
  "provision_status" varchar(32) DEFAULT 'active',
  "access_mode" varchar(16) DEFAULT 'direct',
  "protocol_type" varchar(32) DEFAULT NULL,
  "edge_gateway" varchar(64) DEFAULT NULL,
  "owner_role" varchar(32) DEFAULT NULL,
  "status" varchar(16) DEFAULT 'enabled',
  "remark" varchar(255) DEFAULT NULL,
  "create_time" timestamp DEFAULT NULL,
  "update_time" timestamp DEFAULT NULL,
  PRIMARY KEY ("point_id"),
  UNIQUE ("point_code")
);
CREATE INDEX "e_meter_point_idx_area_equipment" ON "e_meter_point" ("area_id","equipment_id");
CREATE INDEX "e_meter_point_idx_energy_type" ON "e_meter_point" ("energy_type_code");
CREATE INDEX "e_meter_point_idx_status" ON "e_meter_point" ("status");
CREATE TABLE "e_raw_reading" (
  "reading_id" bigserial NOT NULL ,
  "point_id" bigint NOT NULL,
  "sample_time" timestamp NOT NULL,
  "cumulative_value" decimal(20,4) DEFAULT NULL,
  "incremental_value" decimal(20,4) DEFAULT NULL,
  "status_value" varchar(16) DEFAULT NULL,
  "unit" varchar(16) DEFAULT NULL,
  "quality_state" varchar(16) NOT NULL,
  "source_batch" varchar(64) DEFAULT NULL,
  "ingest_time" timestamp NOT NULL,
  "is_backfill" smallint DEFAULT '0',
  "is_estimated" smallint DEFAULT '0',
  "correction_reason" varchar(255) DEFAULT NULL,
  PRIMARY KEY ("reading_id"),
  UNIQUE ("point_id","sample_time")
);
CREATE INDEX "e_raw_reading_idx_ingest_time" ON "e_raw_reading" ("ingest_time");
CREATE INDEX "e_raw_reading_idx_quality" ON "e_raw_reading" ("quality_state");
CREATE INDEX "e_raw_reading_idx_batch" ON "e_raw_reading" ("source_batch");
CREATE TABLE "e_recompute_log" (
  "id" bigserial NOT NULL ,
  "target_table" varchar(32) NOT NULL,
  "target_key_json" varchar(512) NOT NULL,
  "old_version_no" int DEFAULT NULL,
  "new_version_no" int DEFAULT NULL,
  "delta_value" decimal(20,4) DEFAULT NULL,
  "delta_pct" decimal(8,4) DEFAULT NULL,
  "trigger_reason" varchar(255) DEFAULT NULL,
  "operator" varchar(64) DEFAULT NULL,
  "created_at" timestamp DEFAULT NULL,
  PRIMARY KEY ("id")
);
CREATE INDEX "e_recompute_log_idx_target" ON "e_recompute_log" ("target_table","created_at");
CREATE TABLE "e_report_archive" (
  "archive_id" bigserial NOT NULL ,
  "template_code" varchar(64) NOT NULL,
  "template_version" varchar(32) NOT NULL,
  "period_start" date NOT NULL,
  "period_end" date NOT NULL,
  "filters_snapshot_json" text NOT NULL,
  "payload_snapshot_json" text NOT NULL,
  "version_snapshots_json" text NOT NULL,
  "full_signature" varchar(96) NOT NULL,
  "generated_at" timestamp NOT NULL,
  "archived_by" varchar(64) NOT NULL,
  "archived_at" timestamp NOT NULL,
  PRIMARY KEY ("archive_id")
);
CREATE INDEX "e_report_archive_idx_report_archive_period" ON "e_report_archive" ("template_code","period_start","period_end");
CREATE TABLE "e_stat_day" (
  "id" bigserial NOT NULL ,
  "object_type" varchar(16) NOT NULL,
  "object_id" bigint NOT NULL,
  "energy_type_code" varchar(32) NOT NULL,
  "stat_date" date NOT NULL,
  "total_value" decimal(20,4) DEFAULT '0.0000',
  "peak_value" decimal(20,4) DEFAULT '0.0000',
  "flat_value" decimal(20,4) DEFAULT '0.0000',
  "valley_value" decimal(20,4) DEFAULT '0.0000',
  "coverage_ratio" decimal(6,4) DEFAULT NULL,
  "workday_flag" smallint DEFAULT '1',
  "baseline_id" bigint DEFAULT NULL,
  "baseline_deviation_pct" decimal(8,4) DEFAULT NULL,
  "version_no" int DEFAULT '1',
  "has_recompute_pending" smallint DEFAULT '0',
  "computed_at" timestamp DEFAULT NULL,
  PRIMARY KEY ("id"),
  UNIQUE ("object_type","object_id","energy_type_code","stat_date")
);
CREATE INDEX "e_stat_day_idx_stat_date" ON "e_stat_day" ("stat_date");
CREATE TABLE "e_stat_hour" (
  "id" bigserial NOT NULL ,
  "object_type" varchar(16) NOT NULL,
  "object_id" bigint NOT NULL,
  "energy_type_code" varchar(32) NOT NULL,
  "stat_time" timestamp NOT NULL,
  "total_value" decimal(20,4) DEFAULT '0.0000',
  "avg_power_kw" decimal(12,4) DEFAULT NULL,
  "coverage_ratio" decimal(6,4) DEFAULT NULL,
  "quality_summary" varchar(255) DEFAULT NULL,
  "tou_period" varchar(8) DEFAULT NULL,
  "version_no" int DEFAULT '1',
  "computed_at" timestamp DEFAULT NULL,
  PRIMARY KEY ("id"),
  UNIQUE ("object_type","object_id","energy_type_code","stat_time")
);
CREATE INDEX "e_stat_hour_idx_stat_time" ON "e_stat_hour" ("stat_time");
CREATE TABLE "e_stat_month" (
  "id" bigserial NOT NULL ,
  "object_type" varchar(16) NOT NULL,
  "object_id" bigint NOT NULL,
  "energy_type_code" varchar(32) NOT NULL,
  "stat_month" char(7) NOT NULL,
  "total_value" decimal(20,4) DEFAULT '0.0000',
  "peak_value" decimal(20,4) DEFAULT '0.0000',
  "flat_value" decimal(20,4) DEFAULT '0.0000',
  "valley_value" decimal(20,4) DEFAULT '0.0000',
  "coverage_ratio" decimal(6,4) DEFAULT NULL,
  "version_no" int DEFAULT '1',
  "computed_at" timestamp DEFAULT NULL,
  PRIMARY KEY ("id"),
  UNIQUE ("object_type","object_id","energy_type_code","stat_month")
);
CREATE TABLE "e_suggestion" (
  "suggestion_id" bigserial NOT NULL ,
  "source_type" varchar(16) NOT NULL,
  "source_alert_id" bigint DEFAULT NULL,
  "source_fingerprint" varchar(128) DEFAULT NULL,
  "source_snapshot_json" text,
  "template_id" bigint DEFAULT NULL,
  "template_version" int DEFAULT NULL,
  "template_snapshot_json" text NOT NULL,
  "trigger_basis" text,
  "rule_code" varchar(16) DEFAULT NULL,
  "title" varchar(128) NOT NULL,
  "measure_content" text NOT NULL,
  "responsible_user" varchar(64) DEFAULT NULL,
  "responsible_role" varchar(32) DEFAULT NULL,
  "verify_start" date DEFAULT NULL,
  "verify_end" date DEFAULT NULL,
  "area_id" bigint DEFAULT NULL,
  "equipment_id" bigint DEFAULT NULL,
  "object_type" varchar(32) NOT NULL,
  "object_id" bigint DEFAULT NULL,
  "status" varchar(16) NOT NULL,
  "priority_score" decimal(6,2) NOT NULL,
  "priority_band" varchar(16) NOT NULL,
  "priority_formula_version" varchar(32) NOT NULL,
  "priority_factors_json" text NOT NULL,
  "repair_at" timestamp DEFAULT NULL,
  "baseline_start" timestamp DEFAULT NULL,
  "baseline_end" timestamp DEFAULT NULL,
  "report_start" timestamp DEFAULT NULL,
  "report_end" timestamp DEFAULT NULL,
  "saving_value" decimal(20,4) DEFAULT NULL,
  "saving_unit" varchar(16) DEFAULT NULL,
  "close_type" varchar(32) DEFAULT NULL,
  "close_reason" varchar(512) DEFAULT NULL,
  "rejection_reason" varchar(512) DEFAULT NULL,
  "invalid_category" varchar(32) DEFAULT NULL,
  "deferred_from_status" varchar(16) DEFAULT NULL,
  "defer_reason" varchar(512) DEFAULT NULL,
  "defer_until" date DEFAULT NULL,
  "effect_summary" varchar(1024) DEFAULT NULL,
  "attachments_json" text,
  "created_by" varchar(64) NOT NULL,
  "closed_by" varchar(64) DEFAULT NULL,
  "closed_at" timestamp DEFAULT NULL,
  "row_version" int NOT NULL DEFAULT '1',
  "create_time" timestamp NOT NULL,
  "update_time" timestamp NOT NULL,
  PRIMARY KEY ("suggestion_id"),
  UNIQUE ("source_fingerprint","template_id")
);
CREATE INDEX "e_suggestion_idx_status" ON "e_suggestion" ("status");
CREATE INDEX "e_suggestion_idx_source_alert" ON "e_suggestion" ("source_alert_id");
CREATE INDEX "e_suggestion_idx_rule_area" ON "e_suggestion" ("rule_code","area_id");
CREATE INDEX "e_suggestion_idx_priority" ON "e_suggestion" ("priority_score","suggestion_id");
CREATE TABLE "e_suggestion_flow_log" (
  "flow_id" bigserial NOT NULL ,
  "suggestion_id" bigint NOT NULL,
  "from_status" varchar(16) DEFAULT NULL,
  "to_status" varchar(16) NOT NULL,
  "operator" varchar(64) NOT NULL,
  "operator_role" varchar(32) NOT NULL,
  "action" varchar(32) NOT NULL,
  "remark" varchar(512) DEFAULT NULL,
  "payload_snapshot_json" text,
  "occur_time" timestamp NOT NULL,
  PRIMARY KEY ("flow_id")
);
CREATE INDEX "e_suggestion_flow_log_idx_suggestion_flow" ON "e_suggestion_flow_log" ("suggestion_id","flow_id");
CREATE TABLE "e_suggestion_template" (
  "template_id" bigserial NOT NULL ,
  "template_code" varchar(64) NOT NULL,
  "template_name" varchar(128) NOT NULL,
  "category" varchar(64) NOT NULL,
  "source_rule_code" varchar(16) DEFAULT NULL,
  "applicable_object_type" varchar(32) NOT NULL,
  "action_content" text NOT NULL,
  "required_data" text,
  "estimated_saving" text,
  "cost_impact" text,
  "reliability_impact" text,
  "verification_method" text,
  "default_implementation_difficulty" decimal(5,2) NOT NULL,
  "default_safety_impact" decimal(5,2) NOT NULL,
  "enabled" smallint NOT NULL DEFAULT '1',
  "version" int NOT NULL DEFAULT '1',
  "create_time" timestamp NOT NULL,
  "update_time" timestamp NOT NULL,
  PRIMARY KEY ("template_id"),
  UNIQUE ("template_code","version")
);
CREATE INDEX "e_suggestion_template_idx_template_rule_enabled" ON "e_suggestion_template" ("source_rule_code","enabled");
CREATE TABLE "e_suggestion_verification" (
  "verification_id" bigserial NOT NULL ,
  "suggestion_id" bigint NOT NULL,
  "version" int NOT NULL,
  "status" varchar(16) NOT NULL,
  "repair_at" timestamp NOT NULL,
  "baseline_start" timestamp NOT NULL,
  "baseline_end" timestamp NOT NULL,
  "report_start" timestamp NOT NULL,
  "report_end" timestamp NOT NULL,
  "usage_comparison_json" text NOT NULL,
  "cost_comparison_json" text NOT NULL,
  "workload_comparison_json" text NOT NULL,
  "quality_comparison_json" text NOT NULL,
  "saving_value" decimal(20,4) DEFAULT NULL,
  "saving_unit" varchar(16) NOT NULL,
  "saving_pct" decimal(9,4) DEFAULT NULL,
  "calculation_note" text NOT NULL,
  "formula_version" varchar(64) NOT NULL,
  "signature" varchar(64) NOT NULL,
  "generated_by" varchar(64) NOT NULL,
  "generated_at" timestamp NOT NULL,
  PRIMARY KEY ("verification_id"),
  UNIQUE ("suggestion_id","version")
);
CREATE INDEX "e_suggestion_verification_idx_verification_status" ON "e_suggestion_verification" ("status","generated_at");
CREATE TABLE "e_tariff_version" (
  "tariff_id" bigserial NOT NULL ,
  "energy_type_code" varchar(32) NOT NULL,
  "tou_period" varchar(16) NOT NULL,
  "price" decimal(10,4) NOT NULL,
  "currency" varchar(8) DEFAULT 'CNY',
  "effective_from" date NOT NULL,
  "effective_to" date DEFAULT NULL,
  "version_no" int DEFAULT '1',
  "remark" varchar(255) DEFAULT NULL,
  "create_by" varchar(64) DEFAULT NULL,
  "create_time" timestamp DEFAULT NULL,
  PRIMARY KEY ("tariff_id"),
  UNIQUE ("energy_type_code","tou_period","effective_from")
);
CREATE TABLE "e_work_order" (
  "work_order_id" bigserial NOT NULL ,
  "order_no" varchar(64) NOT NULL,
  "area_id" bigint NOT NULL,
  "equipment_id" bigint DEFAULT NULL,
  "cargo_type" varchar(32) NOT NULL,
  "workload_value" decimal(12,2) NOT NULL,
  "workload_unit" varchar(16) NOT NULL,
  "start_time" timestamp NOT NULL,
  "end_time" timestamp DEFAULT NULL,
  "status" varchar(16) NOT NULL,
  "remark" varchar(255) DEFAULT NULL,
  "create_time" timestamp DEFAULT NULL,
  PRIMARY KEY ("work_order_id"),
  UNIQUE ("order_no")
);
CREATE INDEX "e_work_order_idx_area_time" ON "e_work_order" ("area_id","start_time");
CREATE INDEX "e_work_order_idx_equipment_time" ON "e_work_order" ("equipment_id","start_time");
CREATE TABLE "gen_table" (
  "table_id" bigserial NOT NULL ,
  "table_name" varchar(200) DEFAULT '',
  "table_comment" varchar(500) DEFAULT '',
  "sub_table_name" varchar(64) DEFAULT NULL,
  "sub_table_fk_name" varchar(64) DEFAULT NULL,
  "class_name" varchar(100) DEFAULT '',
  "tpl_category" varchar(200) DEFAULT 'crud',
  "tpl_web_type" varchar(30) DEFAULT '',
  "package_name" varchar(100) DEFAULT NULL,
  "module_name" varchar(30) DEFAULT NULL,
  "business_name" varchar(30) DEFAULT NULL,
  "function_name" varchar(50) DEFAULT NULL,
  "function_author" varchar(50) DEFAULT NULL,
  "gen_type" char(1) DEFAULT '0',
  "gen_path" varchar(200) DEFAULT '/',
  "options" varchar(1000) DEFAULT NULL,
  "create_by" varchar(64) DEFAULT '',
  "create_time" timestamp DEFAULT NULL,
  "update_by" varchar(64) DEFAULT '',
  "update_time" timestamp DEFAULT NULL,
  "remark" varchar(500) DEFAULT NULL,
  PRIMARY KEY ("table_id")
);
CREATE TABLE "gen_table_column" (
  "column_id" bigserial NOT NULL ,
  "table_id" bigint DEFAULT NULL,
  "column_name" varchar(200) DEFAULT NULL,
  "column_comment" varchar(500) DEFAULT NULL,
  "column_type" varchar(100) DEFAULT NULL,
  "python_type" varchar(500) DEFAULT NULL,
  "python_field" varchar(200) DEFAULT NULL,
  "is_pk" char(1) DEFAULT NULL,
  "is_increment" char(1) DEFAULT NULL,
  "is_required" char(1) DEFAULT NULL,
  "is_unique" char(1) DEFAULT NULL,
  "is_insert" char(1) DEFAULT NULL,
  "is_edit" char(1) DEFAULT NULL,
  "is_list" char(1) DEFAULT NULL,
  "is_query" char(1) DEFAULT NULL,
  "query_type" varchar(200) DEFAULT 'EQ',
  "html_type" varchar(200) DEFAULT NULL,
  "dict_type" varchar(200) DEFAULT '',
  "sort" int DEFAULT NULL,
  "create_by" varchar(64) DEFAULT '',
  "create_time" timestamp DEFAULT NULL,
  "update_by" varchar(64) DEFAULT '',
  "update_time" timestamp DEFAULT NULL,
  PRIMARY KEY ("column_id")
);
CREATE TABLE "sys_config" (
  "config_id" serial NOT NULL ,
  "config_name" varchar(100) DEFAULT '',
  "config_key" varchar(100) DEFAULT '',
  "config_value" varchar(500) DEFAULT '',
  "config_type" char(1) DEFAULT 'N',
  "create_by" varchar(64) DEFAULT '',
  "create_time" timestamp DEFAULT NULL,
  "update_by" varchar(64) DEFAULT '',
  "update_time" timestamp DEFAULT NULL,
  "remark" varchar(500) DEFAULT NULL,
  PRIMARY KEY ("config_id")
);
CREATE TABLE "sys_dept" (
  "dept_id" bigserial NOT NULL ,
  "parent_id" bigint DEFAULT '0',
  "ancestors" varchar(50) DEFAULT '',
  "dept_name" varchar(30) DEFAULT '',
  "order_num" int DEFAULT '0',
  "leader" varchar(20) DEFAULT NULL,
  "phone" varchar(11) DEFAULT NULL,
  "email" varchar(50) DEFAULT NULL,
  "status" char(1) DEFAULT '0',
  "del_flag" char(1) DEFAULT '0',
  "create_by" varchar(64) DEFAULT '',
  "create_time" timestamp DEFAULT NULL,
  "update_by" varchar(64) DEFAULT '',
  "update_time" timestamp DEFAULT NULL,
  PRIMARY KEY ("dept_id")
);
CREATE TABLE "sys_dict_data" (
  "dict_code" bigserial NOT NULL ,
  "dict_sort" int DEFAULT '0',
  "dict_label" varchar(100) DEFAULT '',
  "dict_value" varchar(100) DEFAULT '',
  "dict_type" varchar(100) DEFAULT '',
  "css_class" varchar(100) DEFAULT NULL,
  "list_class" varchar(100) DEFAULT NULL,
  "is_default" char(1) DEFAULT 'N',
  "status" char(1) DEFAULT '0',
  "create_by" varchar(64) DEFAULT '',
  "create_time" timestamp DEFAULT NULL,
  "update_by" varchar(64) DEFAULT '',
  "update_time" timestamp DEFAULT NULL,
  "remark" varchar(500) DEFAULT NULL,
  PRIMARY KEY ("dict_code")
);
CREATE TABLE "sys_dict_type" (
  "dict_id" bigserial NOT NULL ,
  "dict_name" varchar(100) DEFAULT '',
  "dict_type" varchar(100) DEFAULT '',
  "status" char(1) DEFAULT '0',
  "create_by" varchar(64) DEFAULT '',
  "create_time" timestamp DEFAULT NULL,
  "update_by" varchar(64) DEFAULT '',
  "update_time" timestamp DEFAULT NULL,
  "remark" varchar(500) DEFAULT NULL,
  PRIMARY KEY ("dict_id"),
  UNIQUE ("dict_type")
);
CREATE TABLE "sys_job" (
  "job_id" bigserial NOT NULL ,
  "job_name" varchar(64) NOT NULL DEFAULT '',
  "job_group" varchar(64) NOT NULL DEFAULT 'default',
  "job_executor" varchar(64) DEFAULT 'default',
  "invoke_target" varchar(500) NOT NULL,
  "job_args" varchar(255) DEFAULT '',
  "job_kwargs" varchar(255) DEFAULT '',
  "cron_expression" varchar(255) DEFAULT '',
  "misfire_policy" varchar(20) DEFAULT '3',
  "concurrent" char(1) DEFAULT '1',
  "status" char(1) DEFAULT '0',
  "create_by" varchar(64) DEFAULT '',
  "create_time" timestamp DEFAULT NULL,
  "update_by" varchar(64) DEFAULT '',
  "update_time" timestamp DEFAULT NULL,
  "remark" varchar(500) DEFAULT '',
  PRIMARY KEY ("job_id","job_name","job_group")
);
CREATE TABLE "sys_job_log" (
  "job_log_id" bigserial NOT NULL ,
  "job_name" varchar(64) NOT NULL,
  "job_group" varchar(64) NOT NULL,
  "job_executor" varchar(64) NOT NULL,
  "invoke_target" varchar(500) NOT NULL,
  "job_args" varchar(255) DEFAULT '',
  "job_kwargs" varchar(255) DEFAULT '',
  "job_trigger" varchar(255) DEFAULT '',
  "job_message" varchar(500) DEFAULT NULL,
  "status" char(1) DEFAULT '0',
  "exception_info" varchar(2000) DEFAULT '',
  "create_time" timestamp DEFAULT NULL,
  PRIMARY KEY ("job_log_id")
);
CREATE TABLE "sys_logininfor" (
  "info_id" bigserial NOT NULL ,
  "user_name" varchar(50) DEFAULT '',
  "ipaddr" varchar(128) DEFAULT '',
  "login_location" varchar(255) DEFAULT '',
  "browser" varchar(50) DEFAULT '',
  "os" varchar(50) DEFAULT '',
  "status" char(1) DEFAULT '0',
  "msg" varchar(255) DEFAULT '',
  "login_time" timestamp DEFAULT NULL,
  PRIMARY KEY ("info_id")
);
CREATE INDEX "sys_logininfor_idx_sys_logininfor_s" ON "sys_logininfor" ("status");
CREATE INDEX "sys_logininfor_idx_sys_logininfor_lt" ON "sys_logininfor" ("login_time");
CREATE TABLE "sys_menu" (
  "menu_id" bigserial NOT NULL ,
  "menu_name" varchar(50) NOT NULL,
  "parent_id" bigint DEFAULT '0',
  "order_num" int DEFAULT '0',
  "path" varchar(200) DEFAULT '',
  "component" varchar(255) DEFAULT NULL,
  "query" varchar(255) DEFAULT NULL,
  "route_name" varchar(50) DEFAULT '',
  "is_frame" int DEFAULT '1',
  "is_cache" int DEFAULT '0',
  "menu_type" char(1) DEFAULT '',
  "visible" char(1) DEFAULT '0',
  "status" char(1) DEFAULT '0',
  "perms" varchar(100) DEFAULT NULL,
  "icon" varchar(100) DEFAULT '#',
  "create_by" varchar(64) DEFAULT '',
  "create_time" timestamp DEFAULT NULL,
  "update_by" varchar(64) DEFAULT '',
  "update_time" timestamp DEFAULT NULL,
  "remark" varchar(500) DEFAULT '',
  PRIMARY KEY ("menu_id")
);
CREATE TABLE "sys_notice" (
  "notice_id" serial NOT NULL ,
  "notice_title" varchar(50) NOT NULL,
  "notice_type" char(1) NOT NULL,
  "notice_content" bytea,
  "status" char(1) DEFAULT '0',
  "create_by" varchar(64) DEFAULT '',
  "create_time" timestamp DEFAULT NULL,
  "update_by" varchar(64) DEFAULT '',
  "update_time" timestamp DEFAULT NULL,
  "remark" varchar(255) DEFAULT NULL,
  PRIMARY KEY ("notice_id")
);
CREATE TABLE "sys_oper_log" (
  "oper_id" bigserial NOT NULL ,
  "title" varchar(50) DEFAULT '',
  "business_type" int DEFAULT '0',
  "method" varchar(100) DEFAULT '',
  "request_method" varchar(10) DEFAULT '',
  "operator_type" int DEFAULT '0',
  "oper_name" varchar(50) DEFAULT '',
  "dept_name" varchar(50) DEFAULT '',
  "oper_url" varchar(255) DEFAULT '',
  "oper_ip" varchar(128) DEFAULT '',
  "oper_location" varchar(255) DEFAULT '',
  "oper_param" varchar(2000) DEFAULT '',
  "json_result" varchar(2000) DEFAULT '',
  "status" int DEFAULT '0',
  "error_msg" varchar(2000) DEFAULT '',
  "oper_time" timestamp DEFAULT NULL,
  "cost_time" bigint DEFAULT '0',
  PRIMARY KEY ("oper_id")
);
CREATE INDEX "sys_oper_log_idx_sys_oper_log_bt" ON "sys_oper_log" ("business_type");
CREATE INDEX "sys_oper_log_idx_sys_oper_log_s" ON "sys_oper_log" ("status");
CREATE INDEX "sys_oper_log_idx_sys_oper_log_ot" ON "sys_oper_log" ("oper_time");
CREATE TABLE "sys_post" (
  "post_id" bigserial NOT NULL ,
  "post_code" varchar(64) NOT NULL,
  "post_name" varchar(50) NOT NULL,
  "post_sort" int NOT NULL,
  "status" char(1) NOT NULL,
  "create_by" varchar(64) DEFAULT '',
  "create_time" timestamp DEFAULT NULL,
  "update_by" varchar(64) DEFAULT '',
  "update_time" timestamp DEFAULT NULL,
  "remark" varchar(500) DEFAULT NULL,
  PRIMARY KEY ("post_id")
);
CREATE TABLE "sys_role" (
  "role_id" bigserial NOT NULL ,
  "role_name" varchar(30) NOT NULL,
  "role_key" varchar(100) NOT NULL,
  "role_sort" int NOT NULL,
  "data_scope" char(1) DEFAULT '1',
  "menu_check_strictly" smallint DEFAULT '1',
  "dept_check_strictly" smallint DEFAULT '1',
  "status" char(1) NOT NULL,
  "del_flag" char(1) DEFAULT '0',
  "create_by" varchar(64) DEFAULT '',
  "create_time" timestamp DEFAULT NULL,
  "update_by" varchar(64) DEFAULT '',
  "update_time" timestamp DEFAULT NULL,
  "remark" varchar(500) DEFAULT NULL,
  PRIMARY KEY ("role_id")
);
CREATE TABLE "sys_role_dept" (
  "role_id" bigint NOT NULL,
  "dept_id" bigint NOT NULL,
  PRIMARY KEY ("role_id","dept_id")
);
CREATE TABLE "sys_role_menu" (
  "role_id" bigint NOT NULL,
  "menu_id" bigint NOT NULL,
  PRIMARY KEY ("role_id","menu_id")
);
CREATE TABLE "sys_user" (
  "user_id" bigserial NOT NULL ,
  "dept_id" bigint DEFAULT NULL,
  "user_name" varchar(30) NOT NULL,
  "nick_name" varchar(30) NOT NULL,
  "user_type" varchar(2) DEFAULT '00',
  "email" varchar(50) DEFAULT '',
  "phonenumber" varchar(11) DEFAULT '',
  "sex" char(1) DEFAULT '0',
  "avatar" varchar(100) DEFAULT '',
  "password" varchar(100) DEFAULT '',
  "status" char(1) DEFAULT '0',
  "del_flag" char(1) DEFAULT '0',
  "login_ip" varchar(128) DEFAULT '',
  "login_date" timestamp DEFAULT NULL,
  "pwd_update_date" timestamp DEFAULT NULL,
  "create_by" varchar(64) DEFAULT '',
  "create_time" timestamp DEFAULT NULL,
  "update_by" varchar(64) DEFAULT '',
  "update_time" timestamp DEFAULT NULL,
  "remark" varchar(500) DEFAULT NULL,
  PRIMARY KEY ("user_id")
);
CREATE TABLE "sys_user_post" (
  "user_id" bigint NOT NULL,
  "post_id" bigint NOT NULL,
  PRIMARY KEY ("user_id","post_id")
);
CREATE TABLE "sys_user_role" (
  "user_id" bigint NOT NULL,
  "role_id" bigint NOT NULL,
  PRIMARY KEY ("user_id","role_id")
);
COMMIT;
