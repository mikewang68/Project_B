# 阶段 1 进度

日期：2026-08-19

状态：已完成

## 已完成

- 建立单项目目录和 Maven 父工程。
- 锁定Spring Boot 3.5.16、Java 17编译级别、MyBatis、Spring Security、Actuator和openGauss JDBC依赖。
- 建立 Vue 3.5.41、TypeScript 5.9.3 严格模式、Vite 7.3.6、Vue Router 4.6.4、Pinia 3.0.4、Element Plus 2.14.4、ECharts 6.1.0 工程。
- 提交 pnpm lockfile和构建脚本白名单。
- 建立统一响应、请求 ID、异常处理、基础安全配置、存活/就绪/版本接口。
- 建立根路径Hash路由，不需要网关基路径或Spring History回退。
- 完成简洁管理端主题、工作台、库存占位页、加载/错误/无权限/404状态。
- 打通前端 `Axios -> /api/v1/dashboard/summary` 的契约代码。
- 建立独立 `mt_wms` 数据库、V001版本表和V002应用账号最小权限脚本。
- 安装并验证 Microsoft OpenJDK 17.0.20，使用项目 Maven Wrapper 3.9.11。
- 完成前端内嵌、Spring Boot 单 JAR 打包及一键启动/停止脚本。
- 修正 openGauss JDBC 6.0.0 实际驱动类为 `org.postgresql.Driver`。
- 下载并导入官方 `opengauss:6.0.5` x86_64镜像，建立Docker Compose和持久化卷。
- 建立可校验SHA-256、拒绝已执行脚本被篡改的数据库迁移执行器。
- 完成 `run-local.cmd -> openGauss -> 数据库迁移 -> 单JAR` 一键启动链路。

## 已验证

| 检查 | 结果 |
|---|---|
| `pnpm install --frozen-lockfile` | 通过 |
| `vue-tsc --noEmit` | 通过 |
| Vitest | 1 个测试通过 |
| Vite 生产构建 | 通过，产物写入 `target/classes/static` |
| Java/Maven 编译 | Java 17.0.20、Maven Wrapper 3.9.11，编译和 1 个后端测试通过 |
| 单 JAR 打包 | 通过，生成 `target/mt-wms.jar` |
| 页面/API 验证 | 首页、工作台 API、存活接口均返回 200 |
| 浏览器验证 | 工作台和库存页面渲染、Hash 路由跳转通过，控制台无错误 |
| openGauss 6.0.5 连接 | 通过，数据库版本实测为openGauss 6.0.5，容器健康 |
| 版本化迁移 | V001、V002执行并登记成功，重复执行可安全跳过 |
| 应用数据库就绪 | `/health/ready` 返回200，`database=UP` |

## 已知事项

- Maven仓库使用 openGauss JDBC 6.0.0；目标数据库仍为 openGauss 6.0.5。官方另提供 JDBC 6.0.5 下载包，接库时再决定是否以内部制品方式升级并实测。
- 当前前端主包约 1 MB，Vite 提示大包警告；将在页面数量增加时结合路由懒加载和组件按需引入处理。
- 当前安全配置只放行骨架工作台与健康接口；本地Session登录、菜单和权限在阶段2完成。

## 阶段结论

阶段1完成标准已经满足，后续工作进入阶段2：登录、权限和租户。
