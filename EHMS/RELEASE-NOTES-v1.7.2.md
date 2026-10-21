# EHM 1.7.2-server

本轮将公共日志客户端升级为audit-client 1.0.1，继续使用openGauss/openGemini服务器方案。原日志CRUD覆盖和持久化队列保留，旧Mongo目录未覆盖。

公共服务同步升级为AUDIT 1.1.0：新增TRUST通用OPERATION_LOG适配，保存首次请求原文和远端回执，按作用域选择独立来源，区分日志入库、TRUST受理及有效提交。SDK修复旧拒绝状态映射；未知旧结果不再默认成功。

业务模块仍只调用公共日志API，不持有TRUST来源凭据。真实通用日志启用仍需非加密完整JSON归档许可、来源矩阵、独立凭据和真实联调，不因TRUST代码已发布而自动启用。

本次未完成新增菜单深链或完整IAM业务RBAC；E21菜单确认结论仍为PARTIAL，不将日志升级当作Portal接入完成。未推送GitHub、未部署服务器。升级资料见AUDIT/docs/client-v1.0.1.md及trust-generic-v1.md。
