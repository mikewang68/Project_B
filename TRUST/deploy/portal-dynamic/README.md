# 动态导航门户（IAM 驱动）

本目录是 **IAM 数据库驱动**的 Portal 前端实现：登录后左侧菜单由
`GET /api/v1/iam/navigation/tree` 返回的 **APP → DIRECTORY → PAGE** 三级结构渲染，
不再读取静态 `services.json` 目录。

---

## 与 `deploy/portal/`（静态目录版）的关系

| 项 | `deploy/portal/`（既有） | `deploy/portal-dynamic/`（本目录） |
|---|---|---|
| 数据来源 | `portal-assets/services.json`（由 `gateway_portal.py` 按 deployment 配置生成） | `GET /api/v1/iam/navigation/tree`（IAM 数据库） |
| 菜单形态 | 扁平卡片网格 + 分类筛选 | 三级树：APP → DIRECTORY → PAGE，目录可折叠 |
| 认证 | 无（纯静态入口页） | 同源会话（`/api/v1/portal/session/me`），401 显示受控登录表单 |
| 权限 | 无（所有卡片对所有人可见） | IAM 导航树按角色下发；`scoped_user` 只见被授权页 |
| 降级 | 无 | **默认拒绝**：导航 API 失败时显示错误态，不回退静态卡片 |

**两者不是替代关系，是并存关系。** `deploy/portal/` 继续服务
`gateway_portal.py` 生成的 public / internal 静态目录（Nginx 直发，无后端依赖）；
本目录供需要 IAM 授权菜单的内部门户使用。

---

## 目录内容

| 文件 | 说明 |
|---|---|
| `index.html` | 动态门户外壳：系统导航侧栏、叶子卡片、错误态、登录表单、外观设置 |
| `portal.css` | 样式：主题（4 套）、布局（左/顶/窄栏）、响应式抽屉 |
| `portal.js` | 逻辑：契约校验、递归渲染、路由适配、会话与登出 |

---

## 依赖的后端契约

### 1. 导航树

```
GET /api/v1/iam/navigation/tree
```

响应必须满足 v3 契约（`schemaVersion`、`systems[]`、`userContext`）。核心字段：

```json
{
  "code": 0,
  "data": {
    "schemaVersion": "3.0",
    "userContext": {
      "displayName": "超级管理员",
      "accessMode": "FULL_ADMIN",
      "readOnly": false
    },
    "systems": [
      {
        "appCode": "PSMS",
        "displayName": "生产调度系统",
        "entryBasePath": "/production-dispatch/",
        "children": [
          {
            "stableMenuKey": "PSMS:dir-dispatch",
            "menuName": "调度",
            "nodeType": "DIRECTORY",
            "children": [
              {
                "stableMenuKey": "PSMS:ui-001",
                "menuName": "调度总览",
                "nodeType": "PAGE",
                "entryMode": "SPA_ROUTE",
                "entryPath": "/production-dispatch/dispatch/overview"
              }
            ]
          }
        ]
      }
    ]
  }
}
```

约束（`NavigationModel.validateV3Contract` 强制校验）：

- `nodeType` 只能是 `PAGE` 或 `DIRECTORY`
- `DIRECTORY` 不得携带 `entryPath`
- 层级上限 6 层
- `appCode` 必须属于允许集合：`WMS PSMS SCS EHMS EMCS TRUST IAM SYS`
- 拒绝旧版根级 `categories` 结构

### 2. 会话

```
GET  /api/v1/portal/session/me     → { code: 0, data: { authenticated, token, accessMode, readOnly } }
POST /api/v1/portal/session/logout → 200
```

会话失效时 `portal.js` 显示登录表单（凭据只存内存，不写 localStorage/sessionStorage）。

---

## 部署方式

本目录**不包含**部署脚本，也不修改任何 Nginx 配置。接入方式由部署方决定，例如：

- 由反向代理把内部门户根路径映射到本目录，并把 `/api/v1/iam/**` 转发到 IAM；
- 或由 BFF 静态服务本目录（隔离演示环境采用此方式）。

**不要**用本目录覆盖正式 Portal 路径（例如 `/data/app/bdemo-44e32a9/portal/`），
除非有该具体部署操作的授权与回滚窗口。

---

## 验证清单

浏览器实测（需登录态）：

| # | 检查 | 预期 |
|---|---|---|
| 1 | admin 登录 | 左侧 8 个系统 |
| 2 | 展开含目录的系统 | 目录可展开，子页缩进 |
| 3 | 点击目录标题 | 不跳转，只折叠/展开 |
| 4 | 点击叶子页 | 卡片显示 `entryPath`，「尝试打开服务页面」指向该路径 |
| 5 | 空目录 | 不显示（后端已剪枝） |
| 6 | `scoped_user` | 只显示被授权页面 |
| 7 | 未登录 | 显示登录表单，不显示任何菜单 |
| 8 | 导航 API 返回 500 | 显示错误态，**不回退静态卡片** |
| 9 | 刷新 | 菜单结构一致 |
| 10 | 主题/布局切换 | 4 主题 × 3 布局均生效 |

自动化脚本见仓库 `TRUST/tests/`（既有 `portal-browser.cjs` 面向静态目录版，不适用于本目录）。

---

## 已知限制

- 不读取 `services.json`，因此不适用于纯静态托管、无 IAM 后端的场景。
- 依赖 IAM 导航树契约；IAM 未部署动态导航时无法工作（会显示错误态，属预期行为）。
- `EMCS` / `TRUST` 等需原生登录的系统，点击后仍会进入其自身登录页——本门户不伪造 SSO。
