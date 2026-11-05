# TRUST 源码更新与对接

本包交付可信存证系统的 IAM V2.1 接入、区域授权与核验/提交动作分离。
版本、分支及提交号见总 README。逐文件合并，保留身份、组织、钱包及链上业务实现。
本包仅交接 TRUST 源码增量；构建公共 SDK 时使用完整仓库相应目录。

## 需要合并的内容

- 现有 SSO 兑换和会话检查中校验中央主体、受众、授权版本、菜单动作与范围。
- 事件、证据、追踪和任务根据不可变根记录执行区域范围，原组织边界继续有效。
- `AUDIT_VERIFY` 只核验，`CHAIN_SUBMIT` 才提交上链；下载/导出使用独立权限。
- 全局身份和状态页面仅对具有明确查看权限的 L1 开放。
- 前端同步权限刷新、只读行为、追踪与事件权限分离及实际失败展示。
- 详细接口动作矩阵见 `TRUST/docs/central-scope-v21.md`。

## 公共依赖与构建

需要 `IAM/context-client`，Maven 坐标 `com.bproject.iam:context-client:2.1.0`。
使用 Java `17`、Maven；前端使用与 Vite 8 兼容的 Node 版本和 pnpm `10.34.5`。
在实验室从完整仓库根目录执行：

```sh
mvn -f IAM/context-client/pom.xml clean install
mvn -f TRUST/backend/pom.xml clean package
pnpm --dir TRUST/frontend install --ignore-workspace --frozen-lockfile
pnpm --dir TRUST/frontend run test:unit
pnpm --dir TRUST/frontend run build
```

后端产物在 `TRUST/backend/target`，前端产物在 `TRUST/frontend/dist`。
真实 Fabric、IPFS、数据库与故障演练按 TRUST 既有测试配置分别执行。
普通构建通过不能代替真实链写入、文件存取与身份供给检查。

## 数据库与配置

核对 `TRUST/backend/db/v21/001-central-scope.sql`：为事件与证据根记录增加三属性及索引。
现有事件规范文档、保留记录、签名内容和历史组织归属不重写。
普通 V2 区域读取通过根记录限制任务与追踪；旧数据保留 `LEGACY / UNASSIGNED`。
既有 Liquibase 与独立增量脚本分别核对，不重复执行已经应用的迁移。

| 配置项 | 填写规则 |
|---|---|
| `trust.sso.enabled` | IAM 登记及原生映射完成后设为 `true` |
| `trust.sso.iam-base-url` | `<IAM地址>/api/v1/iam` |
| `trust.sso.service-token` | 受保护配置注入 |
| `TRUST_DB_URL / TRUST_DB_USER / TRUST_DB_PASSWORD` | 使用本环境的低权数据库连接 |
| `TRUST_ROOT` | `<受保护运行数据目录>` |
| Fabric / IPFS 配置 | 沿用已登记的组织、证书、密钥引用及服务连接 |

实际部署配置按 `TRUST/deploy/deployment.example.json` 准备，真实值不放源码包。
服务受众 `TRUST` 与项目/区域归属分别校验；不能把组织 ID 当区域 ID。
机器入站保持独立服务身份契约，不能复用浏览器会话的范围。

## 更新后的最小检查

1. 门户进入 TRUST，读取本范围事件、追踪与任务，跨项目/区域访问拒绝。
2. 只读账号执行已授权核验、下载和导出，提交上链及修改被拒绝。
3. 普通业务身份不能读取全局身份/状态；L1 仍需对应查看权限。
4. 新事件与证据带可信归属；上传、追加更正、重试分别核对权限和幂等性。
5. 撤销角色或统一退出后，旧会话不能继续读写；临时网络故障不新增授权。
6. 区分受理、签名、链上确认、文件可取与核验结果，按实际结果记录。

## 负责人继续完成

真实业务事件语义、组织身份、链网络与文件保留策略由 TRUST 负责人接续维护。
新增业务资源先实现不可变归属与动作适配，不通过放开全局查询兼容 V2。
保留失败历史、原事件 ID 和业务引用；已完成任务不能用重复重试制造新成功记录。
IAM/SYS 审计链路与 TRUST 业务链路分别确认，不能互相替代验收。
合并后反馈提交号、迁移结果和具体链路错误；中央身份与权限接入由公共能力维护方协助。
