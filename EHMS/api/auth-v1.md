# 登录与两角色权限 API（1.8.0）

基址`/api/ehm/v1/auth`。本地模式使用HttpOnly会话Cookie；写请求除登录外均附`X-EHM-CSRF`，值从登录响应或`GET /me`获得。不接受浏览器自报角色、用户ID或伪造Authorization作为本地权限。响应不返回会话bearer和密码哈希，返回`Cache-Control: no-store`。

| 方法 | 路径 | 权限 / 内容 |
|---|---|---|
| POST | `/login` | JSON username/password；失败401、触发限速429；成功设置Cookie并返回user/csrfToken/capabilities |
| GET | `/me` | 当前用户、角色和CSRF；匿名401 |
| POST | `/logout` | 撤销当前会话、清Cookie；不等于IAM全局注销 |
| POST | `/password` | 本人currentPassword/newPassword；撤销该用户所有会话 |
| GET | `/users` | 仅SUPER_ADMIN；不返回密码哈希 |
| POST | `/users` | 仅SUPER_ADMIN；username/displayName/password/role |
| PUT | `/users/{id}` | 仅SUPER_ADMIN；displayName/role/enabled；停用或角色改变撤销会话 |
| POST | `/users/{id}/password` | 仅SUPER_ADMIN重置其他账号；password；撤销目标全部会话 |

角色固定`USER`、`SUPER_ADMIN`。USER可GET/HEAD业务数据、查看本人日志，允许发送AI只读问答及页面意图日志，不允许创建/更新/删除业务、审批、执行后台任务、敏感导出或管理账号。SUPER_ADMIN可执行当前已实现的业务管理接口；原有能力开关和专项导出开关仍生效。不提供用户硬删除，也不删除审计历史。

用户名以字母开头的3–32位字母/数字/._-，统一转小写；密码12–128位非控制字符。密码随机盐PBKDF2-SHA256 600000轮；会话仅保存token的SHA256。默认8小时绝对有效期、30分钟空闲有效期。失败登录按用户名及来源地址限速5分钟，真实代理地址策略需要部署方配置，不信任客户端自报X-Forwarded-For。

空账号库需要私有bootstrap JSON数组：每项为username/displayName/password/role，且至少一个SUPER_ADMIN。不提供默认账号或公开注册。只在账号表为空时导入，不覆盖已有密码/角色。通过安全渠道交付初始密码，首次使用后自行修改；文件不得纳入Git/压缩包。

先使用迁移身份执行004_local_auth.sql，并按实际schema/表名前缀调整。运行身份仅给相应表的必要权限，关闭自动DDL。`EHM_AUTH_COOKIE_SECURE=true`为默认值，正式环境须HTTPS；只在隔离HTTP/SSH预览中显式false。网关要透传Cookie、Set-Cookie、X-EHM-CSRF，不能缓存身份或登录响应。

统一IAM模式使用`EHM_AUTH_MODE=iam`，不混用本地账户的Cookie，也不代管IAM账号。本地认证与IAM互斥装配，IAM角色与只读字段仍须按真实契约联调；不宣称已完成Portal SSO。
