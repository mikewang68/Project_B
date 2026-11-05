# IAM context-client 2.1

Java 17 的服务端范围工具。SSO 外层契约保持 1.3，新增的 userContext.contextVersion 为 2。普通网页拿到的副本只用于展示。

构建：mvn -f IAM/context-client/pom.xml install。IAM/backend 与 SYS/backend 均依赖 com.bproject.iam:context-client:2.1.0；先安装此模块，再构建应用。

目标系统通过已登记的服务身份调用 IAM 的验票及 session/introspect；检查 active、subject、sessionId、audience、localSubject 后，将可信响应反序列化为 UserContext，调用 ContextRules.validate(context, subject, "WMS")。每次请求重新查询中央授权；中央不可用或 DENY 时拒绝。不要从浏览器 Header、表单或 JWT 自定义字段接收范围，不共享 IAM 签名密钥，也不复制一套用户。

## 查询

ScopeSql.build(mode, activeScope) 同时生成 my_info（三属性）、my_project_zone_info（项目/区域）及参数模板和绑定字典。

    SELECT * FROM stock WHERE (status = :s1 OR status = :s2)
      + filters.my_info_template()

绑定原参数与 filters.scope_params()；纯 SQL 方式也必须将原 OR 条件括起来。无范围返回 AND 1=0。L3/L4只有一个固定三属性；L2仅获批项目内的完整元组；可信L1全局模式才可 AND 1=1。多元组使用括号内 OR，不组合成三个独立 IN 集合。标识值校验 ASCII 白名单及长度；列名只能使用预编译 Columns 枚举（STANDARD 或 ORDER），不接受客户端 SQL、列名或别名。

共享表不可自行删除 subsystem_code。服务端使用 X-SSO-Service-Token 调用 POST /auth/sso/resource-filter，提交 subject、sessionId、audience、resourceKey、menuKey、action；IAM重新检查会话、动作、已登记资源及显式共享授权，返回 filter.condition、template、parameters。未登记、停用或缺授权时拒绝或返回 AND 1=0；返回值只供同一请求内的后台查询。生产配置必须使用已批准的私网/TLS通道及独立服务凭据。

## 动作与写入

有效动作以 menuOperations 为准，不根据角色名或权限码后缀推断。只读硬否决允许 VIEW、DOWNLOAD、EXPORT、AUDIT_VERIFY 中明确获授的动作；CHAIN_SUBMIT 与 AUDIT_VERIFY 分开。

读取、详情、统计、导出、修改与删除都须检查同一范围。修改/删除使用 WHERE id=:id AND (...)，检查受影响行数。创建时从可信具体元组赋值；范围不唯一时先明确选择且校验授权，禁止写入空归属。全局字典/配置不要机械添加区域；历史未核定归属保持 LEGACY/UNASSIGNED，不能填正常区域冒充已完成。

L3人员/岗位管理直接使用 IAM 的 /management 接口，归属由服务端锁定继承；六模块后续嵌入页面时复用同一接口，不能另建登录用户体系。其他语言按同一 JSON 和校验规范实现，不必引入 Java SDK。
