# 系统设置与维护系统（SYS）

B 项目面向各业务系统的**系统设置与维护后台**，在 Project_B 仓库中作为独立模块目录 `SYS/`，与 IAM（统一身份与权限）、DTS（数字孪生）等平级，独立开发、独立构建、独立部署。

> 边界：用户 / 角色 / 菜单权限的维护在 **IAM**；SYS 只负责数据字典、操作日志、系统配置等通用运维设置。账号与权限体系与 IAM 同构，后端统一认证中心就绪后共用同一套账号。

## 一、功能范围

- **数据字典**：字典分类 + 字典项两级维护，完整增删改查、启停、排序、标签样式、同分类值唯一校验；删除分类连带清理其字典项（二次确认）。
- **操作日志**：登录日志 / 操作日志统一留痕，支持按类别、模块、结果、时间范围、关键词筛选，可导出带 BOM 的 CSV（Excel 中文不乱码），导出动作本身也记日志。
- **系统配置**：按「基础设置 / 安全策略 / 会话设置」分组管理参数，支持文本 / 数值 / 开关 / 下拉四种类型，分组保存、只更新发生变化的项。
- **权限闭环**：路由级拦截 + 菜单动态显隐 + `v-perm` 按钮级控制，只读账号看不到任何写操作按钮。
- **外观可换肤**：与 IAM 一致的 4 套皮肤 × 3 种布局，顶栏「外观设置」在线切换，偏好存 `localStorage(b-sys-prefs)`。

## 二、技术栈（与开发服务器基线、IAM 对齐）

Vue 3.5 + TypeScript + Vite、Pinia、Vue Router 4、Element Plus、Sass；pnpm（≥9）、Node ≥ 20.19。

## 三、权限编码（前缀 `sys`）

| 模块 | 功能点 | 编码 |
|---|---|---|
| 数据字典 | 字典分类 | `sys:dict:type:view/add/edit/delete` |
| 数据字典 | 字典项 | `sys:dict:item:view/add/edit/delete` |
| 操作日志 | 日志查询 | `sys:log:list:view/export` |
| 系统配置 | 参数配置 | `sys:config:list:view/edit` |

> 同一套 `sys:*` 编码已同步登记到 IAM 的全平台权限菜单树，由 IAM 统一给角色授权。

## 四、本地运行

```bash
cd SYS
npx -y pnpm@10.34.5 install --frozen-lockfile
npx -y pnpm@10.34.5 dev      # http://localhost:5175
```

| 账号 | 密码 | 说明 |
|---|---|---|
| admin | Admin@123 | 超级管理员，全部维护权限 |
| viewer | Viewer@123 | 运维只读：可查看、可导出日志，无增改按钮 |

当前为纯前端 mock，数据存浏览器 `localStorage`（前缀 `b-sys-`）；控制台执行 `resetAllMockData()` 可恢复初始数据。

## 五、构建与部署

```bash
npx -y pnpm@10.34.5 build    # 先 vue-tsc 类型检查，产物在 dist/
npx -y pnpm@10.34.5 preview  # 本地预览生产产物（4176）
```

- 根路径部署：把 `dist/` 交 Nginx；子路径（如 `/sys/`）：`vite build --base=/sys/` 或设 `VITE_BASE=/sys/`。
- 后端接口默认同源 `/api/v1`（规划 `/api/v1/sys/*`），由 Nginx 反代到网关。

## 六、目录结构

```
SYS/
├─ index.html / vite.config.ts / tsconfig*.json
└─ src/
   ├─ main.ts App.vue
   ├─ sys/          # types 数据模型、sys-menu 权限树、mock-data 持久化
   ├─ permissions/  # 权限判断 + v-perm 指令
   ├─ stores/       # auth（认证）、sys（字典/配置/日志）、preference（皮肤布局）
   ├─ styles/       # tokens.scss 4 皮肤变量、index.scss
   ├─ router/ layouts/
   └─ views/
      ├─ LoginView.vue / ForbiddenView.vue
      └─ maintain/  # DictView 数据字典 / LogView 操作日志 / ConfigView 系统配置
```

## 七、对接真实后端

把 `src/sys/mock-data.ts` 的读写替换为 `/api/v1/sys/*` REST 调用即可，数据模型与 store 调用形态保持不变；登录改为统一认证中心签发 JWT。
