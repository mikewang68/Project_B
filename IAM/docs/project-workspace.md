# 项目工作台与分级管理入口

IAM 的 V2 管理入口为 `/system/scope-management`。L1 使用真实项目目录选择工作台，L2 进入所属项目，L3 的项目、区域、子系统保持锁定，L4 使用 `/account/scope` 查看自己的权限。页面显示明确的 L1—L4 身份标识；选项目不会改变账号层级或归属。

## 入口与上下文

- `/system/scope-management?projectId=<编码>`：选择服务端目录中获准管理的项目。
- `/system/scope-management?workspace=projects`：L1 返回项目列表。
- `/system/scope-management?workspace=global`：L1 进入全局基础管理，维护项目目录、子系统和操作权限池。
- 没有项目参数时，若可信上下文仅选定一个项目，沿用该项目；否则 L1 显示项目列表。L2、L3 忽略外部项目选择并使用固定归属。

`GET /auth/workspace` 返回可信 `userContext` 和真实 `projects` 目录。URL 参数仅为界面提示，必须匹配此目录；不能据此扩大身份权限。项目业务范围通过 `POST /auth/context/selection` 提交，随后重新读取 `/auth/me`，成功后才呈现管理内容。进入管理页保留门户已选的同项目区域，不自动扩大为全部区域。

项目目录独立于业务授权。新登记或停用的项目可出现在 L1 目录中；没有业务授权时使用空选择得到 `DENY`，仍可维护获准的管理配置。不得以全局业务范围作为空项目的回退。已认证旧账号返回 `legacy: true` 时继续使用原入口；V2 加载失败只显示错误，不降级为旧全局导航。

工作台接口要求后端启用 `iam.scope-v2.enabled`。创建下级账号需要的组织目录从 `GET /management/organizations` 读取，仅限具有平台配置能力的 L1；不放宽原组织接口的业务访问检查。业务 `DENY` 不取消仍获准的 L1/L2 管理能力，人员读取继续按管理层级与项目归属检查；没有管理能力的账号仍拒绝访问。

## 管理内容

项目工作台只展示该项目的人员、岗位、区域、系统许可和菜单动作许可。创建项目管理员、区域、授权或项目读取岗位时预填所选项目，项目选择锁定。L3 创建 L4 时继续自动继承三个只读归属字段，并保留用户名失焦查重。

全局只读账号在同一目录浏览，所有写入仍由服务端只读规则拦截。前端的表格筛选用于清晰展示；服务端管理员身份、上级授权和归属检查始终是权限依据。

## 返回平台与构建配置

`VITE_PORTAL_URL` 指定“返回平台入口”的目标，只接受 HTTPS 地址或同源绝对路径；默认 `/portal/`。独立域部署应在构建环境提供平台入口地址。配置不得包含凭据，不接受登录 URL 中任意指定的返回地址。

前端检查使用 `pnpm --ignore-workspace run build`。支持 TypeScript 类型剥离的 Node 环境可执行 `node --experimental-strip-types --test tests/scope-selection.test.mjs tests/project-workspace.test.mjs`，检查固定归属、伪造项目、空项目拒绝条件、项目筛选和区域选择保留。
