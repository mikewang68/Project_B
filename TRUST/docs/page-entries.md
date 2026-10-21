# 页面直达与统一入口接入

实例基址由维护方给出；以下路径不表示具体实例已开放。

| Query | 页面 | 真实 IAM 读取权限 |
|---|---|---|
| `?navTab=events` | 事件台账 | trust:event:read |
| `?navTab=trace` | 批次溯源 | trust:event:read |
| `?navTab=tasks` | 补办任务列表 | trust:task:read |
| `?navTab=status` | 运行状态 | trust:operations:read；审计记录另需 trust:audit:read |
| `?navTab=wallets` | 托管钱包 | trust:wallet:read |
| `?navTab=identities` | 用户身份 | trust:identity:read |

无参数进入台账；重复或非法 navTab 提示并返回默认页。未登录保留目标，登录并持久记录导航后再读取。切页同步地址；录入与导入不提供只读直达入口，刷新回到台账。Query 不携带身份、不授予权限、不自动补办、写链或操作钱包；服务端仍检查每个请求的权限与组织。

页面写操作仍取决于实际权限，链接本身不是只读凭据。接收方使用单独获准的只读身份验证写接口拒绝，不将所有用户映射为管理员。

当前真实 IAM 登录调用 IAM 登录与当前用户核验，不构成 Portal 一次性票据 SSO。仍须确认 issuer、audience、subject、签名验证、有效期、jti 防重放、组织/权限映射及注销；契约明确前不发布票据交换接口，不使用固定管理员代登冒充 SSO。
