# 统一身份与权限管理系统（IAM）

B 项目面向 6 大业务系统（数字孪生 dt / 生产调度 pps / 仓库管理 wms / 设备健康 ehealth / 能源管控 ems / 安全卡控 safety）的**统一身份认证与权限管控后台**，已从数字孪生前端工程中拆分，作为**独立工程**单独开发、单独构建、单独部署。

## 一、能力范围

- 登录认证（会话、路由守卫、登录拦截与回落）
- 用户管理：用户增删改查、启用/停用、分配角色、重置密码；**所属组织（区域 Zone → 公司 Company → 部门 Dept → 组 Group）四级级联选择录入，只能从内置组织列表点选、不可手输**（数据源 `src/iam/org-tree.ts`，后端就绪后替换为组织接口）；预留区块链身份字段（DID / 钱包地址 / 交易哈希，当前仅存储不上链）
- 角色管理：**完整增删改查 + 启用/停用**、状态筛选、基于 7 系统功能菜单树的细粒度授权（查看/新增/编辑/删除/执行/导入/导出/审批）；停用角色不再参与新分配、其在线权限即时失效；被用户占用的角色禁止删除
- 菜单与权限：全平台 7 系统 519 个权限点目录树、权限检索、个人权限覆盖情况
- 前端权限闭环：路由级拦截 + 菜单动态显隐 + `v-perm` 按钮级控制
- **外观可换肤：4 套统一 CSS 皮肤（科技蓝 / 护眼墨绿 / 典雅紫 / 暗夜黑）× 3 种布局（左侧菜单 / 顶部导航 / 图标窄栏），顶栏「外观设置」在线即时切换，偏好存 localStorage 刷新保留**
- 内建安全保护：超级管理员不可被删/停用/篡改权限；不可停用或清空当前登录账号；授权自动补全查看权限，避免孤立授权

> 说明：本系统维护的权限目录树覆盖全部 7 个系统（含 6 个业务系统），用于给各业务系统统一授权；但**本应用自身只包含 IAM 三个管理页面**，不含数字孪生三维 / 大屏等业务页面。

## 二、技术栈（与开发服务器基线对齐）

- Vue 3.5 + TypeScript + Vite
- Pinia（状态）、Vue Router 4
- Element Plus + @element-plus/icons-vue
- Sass；包管理 pnpm（≥9），Node ≥ 20.19
- 已移除数字孪生专用依赖（cesium / echarts），构建产物更轻

## 三、本地运行

### 依赖清理与恢复记录（2026-09-05）

为缩减本地工程副本体积，已删除 `D:\claude-code-workspace\Project b\Project_B\IAM\node_modules`，删除前约 573.19 MB。项目源码、`package.json`、`pnpm-lock.yaml`、环境配置和现有 `dist/` 构建产物均未删除；已经部署到服务器的静态文件不受影响。

下次本地开发、类型检查或重新构建前，在 IAM 项目根目录执行：

```powershell
cd "D:\claude-code-workspace\Project b\Project_B\IAM"
npx -y pnpm@10.34.5 install --frozen-lockfile
```

该命令会依据 `pnpm-lock.yaml` 重新生成 `node_modules`，不需要逐个安装 Vue、Element Plus、Pinia、Vite、TypeScript 等依赖。首次恢复或本地缓存不完整时需要能够访问 pnpm 依赖源。

```bash
# 需使用 pnpm 9 及以上（本机若为 pnpm 7，可用 npx 方式）
cd iam-system
npx -y pnpm@10.34.5 install --frozen-lockfile
npx -y pnpm@10.34.5 dev
# 默认 http://localhost:5174 （与数字孪生前端 5173 错开）
```

演示账号：

| 账号 | 密码 | 说明 |
|---|---|---|
| admin | Admin@123 | 超级管理员，全部权限 |
| dispatcher01 | Dispatch@123 | 业务角色，无 IAM 后台权限（登录会进入 403，用于验证拦截） |
| operator01 | Operate@123 | 同上 |
| field01 | Field@123 | 同上 |
| viewer | Viewer@123 | 只读业务角色，无 IAM 后台权限 |

> 当前为纯前端 mock，数据存于浏览器 localStorage（key 前缀 `b-iam-`）。清空站点缓存或调用 `resetAllMockData()` 可恢复初始数据。

## 四、生产构建与部署

```bash
npx -y pnpm@10.34.5 build      # 产物在 dist/，先做 vue-tsc 类型检查再构建
npx -y pnpm@10.34.5 preview    # 本地预览生产产物（4174）
```

- 根路径部署：把 `dist/` 交给 Nginx 即可。
- 子路径部署（如 `/iam/`）：`vite build --base=/iam/` 或设置 `VITE_BASE=/iam/`。
- 后端接口：默认同源 `/api/v1`，由 Nginx 反代到网关；本地开发通过 `VITE_DEV_API_TARGET` 代理。

Nginx 片段（history 路由回退）：

```nginx
location /iam/ {
  alias /usr/share/nginx/iam/;
  try_files $uri $uri/ /iam/index.html;
}
location /api/ { proxy_pass http://后端网关; }
```

## 五、目录结构

```
iam-system/
├─ index.html / vite.config.ts / tsconfig*.json
└─ src/
   ├─ main.ts App.vue
   ├─ iam/           # 数据模型 types、权限菜单树 menu-tree、组织架构树 org-tree、mock 持久化
   ├─ permissions/   # 权限判断函数 + v-perm 指令
   ├─ stores/        # auth（认证/权限）、iam（用户角色 CRUD）、preference（皮肤/布局偏好）
   ├─ styles/        # tokens.scss（4 套皮肤语义变量 + Element 主色/暗色覆盖）、index.scss
   ├─ router/        # 路由与守卫
   ├─ layouts/       # 后台布局（三布局切换 + 外观设置抽屉）
   └─ views/
      ├─ LoginView.vue / ForbiddenView.vue
      └─ system/     # 用户管理 / 角色管理 / 菜单与权限
```

## 六、皮肤与布局（在线换肤）

- 皮肤：通过 `<html data-theme="...">` 切换 `src/styles/tokens.scss` 中的 CSS 语义变量（侧栏、主色、内容/卡片表面、文字层级），并同步覆盖 Element Plus 主色阶梯；`暗夜黑` 额外覆盖 Element 表面/文字/边框变量实现整站暗色。
- 布局：根容器 `data-layout` 控制 `side`（经典左侧 220px 菜单，可折叠）/ `top`（顶部横向菜单、内容全宽）/ `compact`（64px 图标窄栏，悬停出名称）。
- 切换入口：后台顶栏右侧「外观设置」（画笔图标）抽屉，皮肤以色块卡、布局以缩略图点选，即时生效；偏好存 `localStorage` key `b-iam-prefs`，刷新与重开保持；`usePreferenceStore()` 提供 `setTheme / setLayout / reset`。
- 新增皮肤：在 `tokens.scss` 增加一个 `[data-theme='xxx']` 变量块，并在 `preference.ts` 的 `THEME_OPTIONS` 登记即可，无需改业务页面（页面颜色一律引用 `var(--iam-*)`，禁止再写死十六进制色值）。

## 七、后续对接真实后端

把 `src/iam/mock-data.ts` 的读写替换为 `/api/v1/iam/*` REST 调用即可，`types.ts` 数据模型与各 store / 页面的调用形态保持不变；登录改为后端签发 JWT，区块链字段在关键操作落链时再启用。
