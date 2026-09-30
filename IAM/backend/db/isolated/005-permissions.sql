SET search_path TO iam;
INSERT INTO iam_permission (id,parent_id,node_type,permission_code,permission_name,system_code,route,icon,sort_order,status) VALUES
('iam',NULL,'SYSTEM',NULL,'统一身份与权限管理','iam',NULL,'User',0,'active'),
('iam-auth','iam','MENU',NULL,'认证管理','iam',NULL,NULL,1,'active'),
('iam-auth-login','iam-auth','MENU',NULL,'登录认证','iam',NULL,NULL,2,'active'),
('iam:auth:login:view','iam-auth-login','BUTTON','iam:auth:login:view','登录认证-查看','iam',NULL,NULL,3,'active'),
('iam:auth:login:execute','iam-auth-login','BUTTON','iam:auth:login:execute','登录认证-执行','iam',NULL,NULL,4,'active'),
('iam-auth-password','iam-auth','MENU',NULL,'密码修改与重置','iam',NULL,NULL,5,'active'),
('iam:auth:password:view','iam-auth-password','BUTTON','iam:auth:password:view','密码修改与重置-查看','iam',NULL,NULL,6,'active'),
('iam:auth:password:edit','iam-auth-password','BUTTON','iam:auth:password:edit','密码修改与重置-编辑','iam',NULL,NULL,7,'active'),
('iam:auth:password:execute','iam-auth-password','BUTTON','iam:auth:password:execute','密码修改与重置-执行','iam',NULL,NULL,8,'active'),
('iam-user','iam','MENU',NULL,'用户管理','iam',NULL,NULL,9,'active'),
('iam-user-list','iam-user','MENU',NULL,'用户列表','iam',NULL,NULL,10,'active'),
('iam:user:list:view','iam-user-list','BUTTON','iam:user:list:view','用户列表-查看','iam',NULL,NULL,11,'active'),
('iam:user:list:export','iam-user-list','BUTTON','iam:user:list:export','用户列表-导出','iam',NULL,NULL,12,'active'),
('iam-user-add','iam-user','MENU',NULL,'新增用户','iam',NULL,NULL,13,'active'),
('iam:user:add:add','iam-user-add','BUTTON','iam:user:add:add','新增用户-新增','iam',NULL,NULL,14,'active'),
('iam-user-edit','iam-user','MENU',NULL,'编辑用户','iam',NULL,NULL,15,'active'),
('iam:user:edit:edit','iam-user-edit','BUTTON','iam:user:edit:edit','编辑用户-编辑','iam',NULL,NULL,16,'active'),
('iam-user-delete','iam-user','MENU',NULL,'删除/停用用户','iam',NULL,NULL,17,'active'),
('iam:user:delete:delete','iam-user-delete','BUTTON','iam:user:delete:delete','删除/停用用户-删除','iam',NULL,NULL,18,'active'),
('iam:user:delete:execute','iam-user-delete','BUTTON','iam:user:delete:execute','删除/停用用户-执行','iam',NULL,NULL,19,'active'),
('iam-user-role','iam-user','MENU',NULL,'分配角色','iam',NULL,NULL,20,'active'),
('iam:user:role:edit','iam-user-role','BUTTON','iam:user:role:edit','分配角色-编辑','iam',NULL,NULL,21,'active'),
('iam:user:role:execute','iam-user-role','BUTTON','iam:user:role:execute','分配角色-执行','iam',NULL,NULL,22,'active'),
('iam-role','iam','MENU',NULL,'角色管理','iam',NULL,NULL,23,'active'),
('iam-role-list','iam-role','MENU',NULL,'角色列表','iam',NULL,NULL,24,'active'),
('iam:role:list:view','iam-role-list','BUTTON','iam:role:list:view','角色列表-查看','iam',NULL,NULL,25,'active'),
('iam-role-add','iam-role','MENU',NULL,'新增角色','iam',NULL,NULL,26,'active'),
('iam:role:add:add','iam-role-add','BUTTON','iam:role:add:add','新增角色-新增','iam',NULL,NULL,27,'active'),
('iam-role-edit','iam-role','MENU',NULL,'编辑角色','iam',NULL,NULL,28,'active'),
('iam:role:edit:edit','iam-role-edit','BUTTON','iam:role:edit:edit','编辑角色-编辑','iam',NULL,NULL,29,'active'),
('iam-role-delete','iam-role','MENU',NULL,'删除角色','iam',NULL,NULL,30,'active'),
('iam:role:delete:delete','iam-role-delete','BUTTON','iam:role:delete:delete','删除角色-删除','iam',NULL,NULL,31,'active'),
('iam-role-perm','iam-role','MENU',NULL,'分配权限','iam',NULL,NULL,32,'active'),
('iam:role:perm:edit','iam-role-perm','BUTTON','iam:role:perm:edit','分配权限-编辑','iam',NULL,NULL,33,'active'),
('iam:role:perm:execute','iam-role-perm','BUTTON','iam:role:perm:execute','分配权限-执行','iam',NULL,NULL,34,'active'),
('iam-menu','iam','MENU',NULL,'菜单与权限管理','iam',NULL,NULL,35,'active'),
('iam-menu-tree','iam-menu','MENU',NULL,'六系统菜单树','iam',NULL,NULL,36,'active'),
('iam:menu:tree:view','iam-menu-tree','BUTTON','iam:menu:tree:view','六系统菜单树-查看','iam',NULL,NULL,37,'active'),
('iam-menu-perm','iam-menu','MENU',NULL,'权限点查看','iam',NULL,NULL,38,'active'),
('iam:menu:perm:view','iam-menu-perm','BUTTON','iam:menu:perm:view','权限点查看-查看','iam',NULL,NULL,39,'active'),
('iam-menu-sync','iam-menu','MENU',NULL,'菜单同步与刷新','iam',NULL,NULL,40,'active'),
('iam:menu:sync:execute','iam-menu-sync','BUTTON','iam:menu:sync:execute','菜单同步与刷新-执行','iam',NULL,NULL,41,'active'),
('iam-log','iam','MENU',NULL,'审计日志','iam',NULL,NULL,42,'active'),
('iam-log-login','iam-log','MENU',NULL,'登录日志','iam',NULL,NULL,43,'active'),
('iam:log:login:view','iam-log-login','BUTTON','iam:log:login:view','登录日志-查看','iam',NULL,NULL,44,'active'),
('iam:log:login:export','iam-log-login','BUTTON','iam:log:login:export','登录日志-导出','iam',NULL,NULL,45,'active'),
('iam-log-op','iam-log','MENU',NULL,'操作日志','iam',NULL,NULL,46,'active'),
('iam:log:op:view','iam-log-op','BUTTON','iam:log:op:view','操作日志-查看','iam',NULL,NULL,47,'active'),
('iam:log:op:export','iam-log-op','BUTTON','iam:log:op:export','操作日志-导出','iam',NULL,NULL,48,'active');
INSERT INTO iam_permission(id,parent_id,node_type,permission_name,system_code,sort_order) VALUES ('trust',NULL,'SYSTEM','可信存证与身份','trust',1000),('trust-identity','trust','MENU','用户身份','trust',1001),('iam-identity','iam','MENU','用户身份操作','iam',1002);
INSERT INTO iam_permission(id,parent_id,node_type,permission_name,system_code,sort_order) VALUES ('trust-event','trust','MENU','事件','trust',1100);
INSERT INTO iam_permission(id,parent_id,node_type,permission_name,system_code,sort_order) VALUES ('trust-evidence','trust','MENU','证据','trust',1101);
INSERT INTO iam_permission(id,parent_id,node_type,permission_name,system_code,sort_order) VALUES ('trust-task','trust','MENU','任务','trust',1102);
INSERT INTO iam_permission(id,parent_id,node_type,permission_name,system_code,sort_order) VALUES ('trust-operations','trust','MENU','运行状态','trust',1103);
INSERT INTO iam_permission(id,parent_id,node_type,permission_name,system_code,sort_order) VALUES ('trust-audit','trust','MENU','审计','trust',1104);
INSERT INTO iam_permission(id,parent_id,node_type,permission_name,system_code,sort_order) VALUES ('trust-wallet','trust','MENU','托管签名身份','trust',1105);
INSERT INTO iam_permission(id,parent_id,node_type,permission_name,system_code,sort_order) VALUES ('iam-identity-retry','iam-identity','MENU','重试供给','iam',1200);
INSERT INTO iam_permission(id,parent_id,node_type,permission_name,system_code,sort_order) VALUES ('iam-identity-rotate','iam-identity','MENU','轮换证书','iam',1201);
INSERT INTO iam_permission(id,parent_id,node_type,permission_name,system_code,sort_order) VALUES ('iam-identity-revoke','iam-identity','MENU','撤销证书','iam',1202);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('trust:event:read','trust-event','BUTTON','trust:event:read','查看事件','trust',1010);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('trust:event:submit','trust-event','BUTTON','trust:event:submit','提交事件','trust',1011);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('trust:event:correct','trust-event','BUTTON','trust:event:correct','追加更正','trust',1012);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('trust:evidence:read','trust-evidence','BUTTON','trust:evidence:read','查看证据','trust',1013);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('trust:evidence:upload','trust-evidence','BUTTON','trust:evidence:upload','上传证据','trust',1014);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('trust:evidence:verify','trust-evidence','BUTTON','trust:evidence:verify','核验证据','trust',1015);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('trust:evidence:export','trust-evidence','BUTTON','trust:evidence:export','导出证据','trust',1016);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('trust:task:read','trust-task','BUTTON','trust:task:read','查看任务','trust',1017);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('trust:task:retry','trust-task','BUTTON','trust:task:retry','重试任务','trust',1018);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('trust:operations:read','trust-operations','BUTTON','trust:operations:read','运行状态','trust',1019);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('trust:audit:read','trust-audit','BUTTON','trust:audit:read','查看审计','trust',1020);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('trust:wallet:read','trust-wallet','BUTTON','trust:wallet:read','查看托管签名身份','trust',1021);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('trust:wallet:manage','trust-wallet','BUTTON','trust:wallet:manage','申请签名身份变更','trust',1022);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('trust:wallet:review','trust-wallet','BUTTON','trust:wallet:review','复核签名身份变更','trust',1023);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('trust:identity:read','trust-identity','BUTTON','trust:identity:read','查看用户身份','trust',1024);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('iam:identity:retry:execute','iam-identity-retry','BUTTON','iam:identity:retry:execute','重试身份供给','iam',1025);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('iam:identity:rotate:execute','iam-identity-rotate','BUTTON','iam:identity:rotate:execute','轮换用户证书','iam',1026);
INSERT INTO iam_permission(id,parent_id,node_type,permission_code,permission_name,system_code,sort_order) VALUES ('iam:identity:revoke:execute','iam-identity-revoke','BUTTON','iam:identity:revoke:execute','撤销用户证书','iam',1027);
INSERT INTO iam_role(id,role_code,role_name) VALUES ('role-super-admin','super_admin','系统管理员');
INSERT INTO iam_role(id,role_code,role_name) VALUES ('role-trust-requester','trust_requester','TRUST 申请员');
INSERT INTO iam_role(id,role_code,role_name) VALUES ('role-trust-reviewer','trust_reviewer','TRUST 复核员');
INSERT INTO iam_role(id,role_code,role_name) VALUES ('role-trust-denied','trust_denied','无 TRUST 权限测试角色');
INSERT INTO iam_role(id,role_code,role_name) VALUES ('role-trust-org2','trust_org2','另一组织复核员');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-requester','trust:event:read');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-requester','trust:evidence:read');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-requester','trust:wallet:read');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-requester','trust:identity:read');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-requester','trust:wallet:manage');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-requester','trust:event:submit');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-requester','trust:evidence:upload');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-requester','trust:evidence:verify');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-requester','trust:task:read');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-reviewer','trust:event:read');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-reviewer','trust:evidence:read');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-reviewer','trust:wallet:read');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-reviewer','trust:identity:read');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-reviewer','trust:wallet:review');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-org2','trust:event:read');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-org2','trust:evidence:read');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-org2','trust:wallet:read');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-org2','trust:identity:read');
INSERT INTO iam_role_permission(role_id,permission_id) VALUES ('role-trust-org2','trust:wallet:review');
INSERT INTO iam_role_permission(role_id,permission_id) SELECT 'role-super-admin',permission_code FROM iam_permission WHERE node_type='BUTTON';
INSERT INTO iam_org(id,parent_id,org_type,org_code,org_name) VALUES ('z-ne',NULL,'zone','z-ne','东北区域'),('c-dl-port','z-ne','company','c-dl-port','大连智慧港务有限公司'),('z-sw',NULL,'zone','z-sw','西南区域'),('c-my-factory','z-sw','company','c-my-factory','绵阳智能制造公司');
