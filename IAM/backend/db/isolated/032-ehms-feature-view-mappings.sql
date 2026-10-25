-- Read permissions are separate from feature publication and business write permissions.
-- Import the feature catalog first; existing VIEW mappings and release decisions are preserved.
BEGIN;
WITH features(mapping_id,menu_key,code,name,fingerprint) AS (VALUES
 ('EHMS_FEATURE_VIEW_MY_TASKS','EHMS:my-tasks','ehealth:feature:my-tasks:view','My tasks','e87b16e0b6fd6cd6b1cc076e6526744c32c5525d50cc8e0103532a58ab2581ac'),
 ('EHMS_FEATURE_VIEW_SHIFT_HANDOVER','EHMS:shift-handover','ehealth:feature:shift-handover:view','Shift handover','4cf4092fad9836913ad1546a78cfda7e2e5edc68a53dfa2f480d22cc2e1a1d61'),
 ('EHMS_FEATURE_VIEW_FLEET','EHMS:fleet','ehealth:feature:fleet:view','Device fleet','e2edf6f1c272c02a94bad361b52f726a2d152e94036b14bc9d97e216bf1792db'),
 ('EHMS_FEATURE_VIEW_DEVICE_TEMPLATE','EHMS:device-template','ehealth:feature:device-template:view','Device templates','825ce229c55c76e5d5d94438b63f9274b92ae768c90d1c51e996d1e2574d19cc'),
 ('EHMS_FEATURE_VIEW_SENSOR_CAL','EHMS:sensor-cal','ehealth:feature:sensor-cal:view','Calibration records','6fbf17a4f600882c37fffabc2b2622a7627e2aae6b5b34bdbccd634b7afa1c7f'),
 ('EHMS_FEATURE_VIEW_CONFIG_CHANGE','EHMS:config-change','ehealth:feature:config-change:view','Configuration changes','4485e5b5cc19b9e1b50e3274c22d5710dbb8ec4223013fb0edefbfcfa40bd6db'),
 ('EHMS_FEATURE_VIEW_FMECA','EHMS:fmeca','ehealth:feature:fmeca:view','Failure modes','2cd2a8e447f2ffe2477abdf75970639869f5d8306a23a187907a714d86017740'),
 ('EHMS_FEATURE_VIEW_ALARM_RULES','EHMS:alarm-rules','ehealth:feature:alarm-rules:view','Alarm rules','f36fd33a1c15d8efecdedb96c1ba193717a7e86729fa03cc4d68eb16d788012d'),
 ('EHMS_FEATURE_VIEW_KNOWLEDGE','EHMS:knowledge','ehealth:feature:knowledge:view','Knowledge cases','03ff973c503b7fcf1e1fcce5a5e5e2c917a32e4cc6b64867a6771aaa9a6845f4'),
 ('EHMS_FEATURE_VIEW_FAULT_CODES','EHMS:fault-codes','ehealth:feature:fault-codes:view','Fault codes','6b7cb1decbe4018f43438f52f350cbd1d1131b9bf9f81f65a584f8b516ec139c'),
 ('EHMS_FEATURE_VIEW_SLA','EHMS:sla','ehealth:feature:sla:view','SLA policies','1d2040d2c6c4ea8438251cb6275558fc499aa82a7efecbf58feb51db757b987c'),
 ('EHMS_FEATURE_VIEW_WORKORDERS','EHMS:workorders','ehealth:feature:workorders:view','Work orders','499ea4673c8929a245ee452be509df51f86110fe8392480f846b3dceec345dd6'),
 ('EHMS_FEATURE_VIEW_ALARM_CENTER','EHMS:alarm-center','ehealth:feature:alarm-center:view','Alarm center','1e5ffc6ab7935929e830dadec1b1e3cc3e48b61717a9bff10c94777e807e5f07')
)
INSERT INTO iam.iam_permission(id,node_type,permission_code,permission_name,system_code,status)
SELECT code,'BUTTON',code,name,'ehealth','active' FROM features v
WHERE NOT EXISTS(SELECT 1 FROM iam.iam_permission p WHERE p.permission_code=v.code);

WITH features(mapping_id,menu_key,code,name,fingerprint) AS (VALUES
 ('EHMS_FEATURE_VIEW_MY_TASKS','EHMS:my-tasks','ehealth:feature:my-tasks:view','My tasks','e87b16e0b6fd6cd6b1cc076e6526744c32c5525d50cc8e0103532a58ab2581ac'),
 ('EHMS_FEATURE_VIEW_SHIFT_HANDOVER','EHMS:shift-handover','ehealth:feature:shift-handover:view','Shift handover','4cf4092fad9836913ad1546a78cfda7e2e5edc68a53dfa2f480d22cc2e1a1d61'),
 ('EHMS_FEATURE_VIEW_FLEET','EHMS:fleet','ehealth:feature:fleet:view','Device fleet','e2edf6f1c272c02a94bad361b52f726a2d152e94036b14bc9d97e216bf1792db'),
 ('EHMS_FEATURE_VIEW_DEVICE_TEMPLATE','EHMS:device-template','ehealth:feature:device-template:view','Device templates','825ce229c55c76e5d5d94438b63f9274b92ae768c90d1c51e996d1e2574d19cc'),
 ('EHMS_FEATURE_VIEW_SENSOR_CAL','EHMS:sensor-cal','ehealth:feature:sensor-cal:view','Calibration records','6fbf17a4f600882c37fffabc2b2622a7627e2aae6b5b34bdbccd634b7afa1c7f'),
 ('EHMS_FEATURE_VIEW_CONFIG_CHANGE','EHMS:config-change','ehealth:feature:config-change:view','Configuration changes','4485e5b5cc19b9e1b50e3274c22d5710dbb8ec4223013fb0edefbfcfa40bd6db'),
 ('EHMS_FEATURE_VIEW_FMECA','EHMS:fmeca','ehealth:feature:fmeca:view','Failure modes','2cd2a8e447f2ffe2477abdf75970639869f5d8306a23a187907a714d86017740'),
 ('EHMS_FEATURE_VIEW_ALARM_RULES','EHMS:alarm-rules','ehealth:feature:alarm-rules:view','Alarm rules','f36fd33a1c15d8efecdedb96c1ba193717a7e86729fa03cc4d68eb16d788012d'),
 ('EHMS_FEATURE_VIEW_KNOWLEDGE','EHMS:knowledge','ehealth:feature:knowledge:view','Knowledge cases','03ff973c503b7fcf1e1fcce5a5e5e2c917a32e4cc6b64867a6771aaa9a6845f4'),
 ('EHMS_FEATURE_VIEW_FAULT_CODES','EHMS:fault-codes','ehealth:feature:fault-codes:view','Fault codes','6b7cb1decbe4018f43438f52f350cbd1d1131b9bf9f81f65a584f8b516ec139c'),
 ('EHMS_FEATURE_VIEW_SLA','EHMS:sla','ehealth:feature:sla:view','SLA policies','1d2040d2c6c4ea8438251cb6275558fc499aa82a7efecbf58feb51db757b987c'),
 ('EHMS_FEATURE_VIEW_WORKORDERS','EHMS:workorders','ehealth:feature:workorders:view','Work orders','499ea4673c8929a245ee452be509df51f86110fe8392480f846b3dceec345dd6'),
 ('EHMS_FEATURE_VIEW_ALARM_CENTER','EHMS:alarm-center','ehealth:feature:alarm-center:view','Alarm center','1e5ffc6ab7935929e830dadec1b1e3cc3e48b61717a9bff10c94777e807e5f07')
)
INSERT INTO iam.iam_navigation_menu_permission
 (mapping_id,stable_menu_key,navigation_app_code,permission_scope,
  source_permission_raw,candidate_permission_code,existing_iam_permission_id,
  existing_iam_system_code,match_kind,mapping_status,approval_status,
  source_evidence,content_sha256,last_operation_source,updated_by,last_batch_id)
SELECT v.mapping_id,v.menu_key,m.navigation_app_code,'VIEW','',v.code,p.id,
 p.system_code,'SEMANTIC_PROPOSAL','PROPOSED','PENDING_DECISION',
 'EHMS/web/app.js; EHMS/web/governance-console.js; EHMS/source/backend/src/main/java',
 v.fingerprint,'SYSTEM_MIGRATION','CATALOG_MIGRATION','EHMS_FEATURE_VIEW_V1'
FROM features v JOIN iam.iam_permission p ON p.permission_code=v.code
JOIN iam.iam_navigation_menu m ON m.stable_menu_key=v.menu_key
WHERE NOT EXISTS(SELECT 1 FROM iam.iam_navigation_menu_permission r
 WHERE r.stable_menu_key=v.menu_key AND r.permission_scope='VIEW');

INSERT INTO iam.iam_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM iam.iam_role r CROSS JOIN iam.iam_permission p
WHERE lower(r.role_code)='platform_auditor_readonly' AND r.deleted=0 AND r.status='active'
 AND p.status='active' AND p.permission_code IN (
 'ehealth:feature:my-tasks:view',
 'ehealth:feature:shift-handover:view',
 'ehealth:feature:fleet:view',
 'ehealth:feature:device-template:view',
 'ehealth:feature:sensor-cal:view',
 'ehealth:feature:config-change:view',
 'ehealth:feature:fmeca:view',
 'ehealth:feature:alarm-rules:view',
 'ehealth:feature:knowledge:view',
 'ehealth:feature:fault-codes:view',
 'ehealth:feature:sla:view',
 'ehealth:feature:workorders:view',
 'ehealth:feature:alarm-center:view'
 ) AND NOT EXISTS(SELECT 1 FROM iam.iam_role_permission rp
 WHERE rp.role_id=r.id AND rp.permission_id=p.id);
COMMIT;
