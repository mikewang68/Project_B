# C04 截图索引

## 验证结论

下列 12 张 PNG 均由正式 C04 Playwright 场景生成，并在 2026-07-21（Asia/Shanghai）以原始分辨率逐张复核。两种视口均通过 document/body 横向溢出断言；画面无残留瞬态消息、无乱码，表格、详情抽屉、错误状态与恢复状态可读。

UI-001 的 SCN-03 告警投影由同一正式 E2E 直接断言，不另增截图：API-001 网络面板、接口“不可用”、`TOS-EXT-001` 风险与唯一“重试”按钮必须同时存在，且不以 KPI 缺失代替接口/风险语义。

截图采用 full-page 模式，因此文件宽度等于目标视口宽度，内容超过首屏时文件高度可大于目标视口高度。

| # | 文件 | 页面 / 状态 | E2E 场景 | 视口 | 结果 |
| ---: | --- | --- | --- | --- | --- |
| 1 | `C04-UI001-SCN01-1440x900.png` | UI-001 / SCN-01 正常总览 | UI-001 内容、跨页查询与响应式检查 | 1440×900 | PASS：计划、风险、接口和场区态势清晰，无横向溢出 |
| 2 | `C04-UI001-SCN01-1280x720.png` | UI-001 / SCN-01 正常总览 | UI-001 内容、跨页查询与响应式检查 | 1280×720 | PASS：重点计划单元格与首个资源标记完整可读，无横向溢出 |
| 3 | `C04-UI002-SCN01-CONFIRMED-1440x900.png` | UI-002 / PLAN-001 CONFIRMED v2 | 正常计划确认 | 1440×900 | PASS：五段流程、CONFIRMED 摘要和 v2 原始摘要可见 |
| 4 | `C04-UI002-SCN01-CONFIRMED-1280x720.png` | UI-002 / PLAN-001 CONFIRMED v2 | 正常计划确认 | 1280×720 | PASS：抽屉从确认流程顶部开始，关键确认状态可读 |
| 5 | `C04-UI002-SCN02-MISSING-1440x900.png` | UI-002 / PLAN-002 trackNo 缺失 | SCN-02 补录前 | 1440×900 | PASS：BLOCKED、待补录、未提供股道和 TOS-EXT-002 可见 |
| 6 | `C04-UI002-SCN02-MISSING-1280x720.png` | UI-002 / PLAN-002 trackNo 缺失 | SCN-02 补录前 | 1280×720 | PASS：缺失字段投影和五段流程进度可读 |
| 7 | `C04-UI002-SCN02-CONFIRMED-1440x900.png` | UI-002 / PLAN-002 CONFIRMED v3 | SCN-02 补录、异人复核、确认 | 1440×900 | PASS：补录后 T1、CONFIRMED 和 v3 可见 |
| 8 | `C04-UI002-SCN02-CONFIRMED-1280x720.png` | UI-002 / PLAN-002 CONFIRMED v3 | SCN-02 补录、异人复核、确认 | 1280×720 | PASS：确认流程与补录结果在窄视口完整可读 |
| 9 | `C04-UI002-SCN03-CIRCUIT-1440x900.png` | UI-002 / SCN-03 已熔断 | direct reload、API-002 网络态、三次同步失败与 DISPATCHER 恢复拒绝 | 1440×900 | PASS：唯一健康条保留控制，TOS-EXT-001、重试 3 次、已熔断和网络错误面板可见 |
| 10 | `C04-UI002-SCN03-CIRCUIT-1280x720.png` | UI-002 / SCN-03 已熔断 | direct reload、API-002 网络态、三次同步失败与 DISPATCHER 恢复拒绝 | 1280×720 | PASS：错误面板、熔断原因和禁用同步动作清晰，无横向溢出 |
| 11 | `C04-UI002-SCN03-RECOVERED-1440x900.png` | UI-002 / SCN-01 已恢复 | INTERFACE_OPS 重置并重新同步 | 1440×900 | PASS：INTERFACE_OPS、SCN-01、正常接口和 SYNCED 可见 |
| 12 | `C04-UI002-SCN03-RECOVERED-1280x720.png` | UI-002 / SCN-01 已恢复 | INTERFACE_OPS 重置并重新同步 | 1280×720 | PASS：恢复后状态和同步入口完整，无横向溢出 |

## 文件计数与尺寸

- PNG 文件数：精确 `12`。
- 目标宽度：6 张 `1440`，6 张 `1280`。
- 目标视口：`1440×900` 与 `1280×720`。
- 视觉复核：精确 `12/12` 通过。
