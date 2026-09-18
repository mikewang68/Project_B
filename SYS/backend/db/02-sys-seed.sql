-- ============================================================
-- SYS 种子数据（由 tmp/gen_seed.mjs 从前端权威 TS 源自动生成，请勿手改）
-- 来源：SYS/src/sys/mock-data.ts（5 字典分类 / 15 字典项 / 10 配置 / 初始日志）
-- ============================================================
SET search_path TO sys;

INSERT INTO sys_dict_type
  (id, dict_code, dict_name, remark, status, sort_order, created_at, updated_at, deleted)
VALUES
('dt-user_status','user_status','用户状态','账号启用/停用状态','active',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('dt-gender','gender','性别','人员性别','active',2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('dt-device_status','device_status','设备状态','现场设备运行状态','active',3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('dt-alert_level','alert_level','告警级别','告警严重程度分级','active',4,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('dt-yes_no','yes_no','是否','通用布尔选项','active',5,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0);

INSERT INTO sys_dict_item
  (id, type_code, item_value, item_label, tag_type, sort_order, status, remark, created_at, updated_at, deleted)
VALUES
('di-user_status-active','user_status','active','启用','success',1,'active',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('di-user_status-disabled','user_status','disabled','停用','info',2,'active',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('di-gender-male','gender','male','男','primary',1,'active',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('di-gender-female','gender','female','女','danger',2,'active',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('di-gender-unknown','gender','unknown','未知','info',3,'active',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('di-device_status-running','device_status','running','运行中','success',1,'active',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('di-device_status-idle','device_status','idle','待机','primary',2,'active',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('di-device_status-maintenance','device_status','maintenance','维护中','warning',3,'active',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('di-device_status-fault','device_status','fault','故障','danger',4,'active',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('di-alert_level-low','alert_level','low','低','info',1,'active',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('di-alert_level-medium','alert_level','medium','中','primary',2,'active',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('di-alert_level-high','alert_level','high','高','warning',3,'active',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('di-alert_level-critical','alert_level','critical','紧急','danger',4,'active',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('di-yes_no-Y','yes_no','Y','是','success',1,'active',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
('di-yes_no-N','yes_no','N','否','info',2,'active',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0);

INSERT INTO sys_config
  (id, config_key, config_name, config_value, config_type, config_group, options_json, unit, remark, sort_order, editable, created_at, updated_at)
VALUES
('cfg-name','sys.name','系统名称','B项目智慧管控平台','string','基础设置',NULL,NULL,'登录页与浏览器标题展示',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('cfg-version','sys.version','系统版本','v1.0.0','string','基础设置',NULL,NULL,NULL,2,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('cfg-footer','sys.footer','页脚文案','B项目 数字孪生与智慧管控示范工程','string','基础设置',NULL,NULL,NULL,3,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('cfg-pwdlen','security.pwdMinLen','密码最小长度','8','number','安全策略',NULL,'位',NULL,4,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('cfg-pwdcomplex','security.pwdComplex','密码复杂度','必须含大小写字母和数字','select','安全策略','["不限制","必须含字母和数字","必须含大小写字母和数字"]',NULL,NULL,5,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('cfg-faillock','security.loginFailLock','登录失败锁定阈值','5','number','安全策略',NULL,'次','连续失败达到该次数后临时锁定账号',6,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('cfg-captcha','security.captcha','登录验证码','false','boolean','安全策略',NULL,NULL,NULL,7,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('cfg-timeout','session.timeout','会话超时时长','120','number','会话设置',NULL,'分钟',NULL,8,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('cfg-single','session.single','单点登录','true','boolean','会话设置',NULL,NULL,'同一账号仅允许一处在线',9,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('cfg-remember','session.remember','允许记住登录','true','boolean','会话设置',NULL,NULL,NULL,10,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

INSERT INTO sys_operation_log
  (id, kind, username, user_id, module, action, target, detail, request_method, request_uri, ip, result, duration_ms, created_at)
VALUES
('log-init-0','login','admin',NULL,'auth','login',NULL,'管理员登录成功',NULL,NULL,'192.168.11.21','success',NULL,'2026-09-18 07:58:31'),
('log-init-1','operation','admin',NULL,'dict','edit','device_status','修改字典分类「设备状态」备注',NULL,NULL,'192.168.11.21','success',NULL,'2026-09-18 07:24:31'),
('log-init-2','operation','admin',NULL,'dict','add','alert_level.critical','新增字典项「紧急」',NULL,NULL,'192.168.11.21','success',NULL,'2026-09-18 06:41:31'),
('log-init-3','login','viewer',NULL,'auth','login',NULL,'运维观摩登录成功',NULL,NULL,'192.168.11.33','success',NULL,'2026-09-18 05:56:31'),
('log-init-4','operation','admin',NULL,'config','edit','session.timeout','会话超时由 60 调整为 120 分钟',NULL,NULL,'192.168.11.21','success',NULL,'2026-09-18 04:56:31'),
('log-init-5','operation','admin',NULL,'log','export','操作日志','导出近 7 天操作日志 CSV',NULL,NULL,'192.168.11.21','success',NULL,'2026-09-18 03:56:31'),
('log-init-6','login','unknown',NULL,'auth','login',NULL,'用户名不存在',NULL,NULL,'10.12.3.9','fail',NULL,'2026-09-18 02:46:31'),
('log-init-7','operation','admin',NULL,'dict','delete','gender.other','删除字典项「其他」',NULL,NULL,'192.168.11.21','success',NULL,'2026-09-18 01:16:31');
