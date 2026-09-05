# C05 截图索引

## 验证结论

下列 8 张 PNG 均由正式 C05 Playwright 场景生成，并在 2026-07-21（Asia/Shanghai）以原始分辨率逐张复核。两种视口均通过 document/body 横向溢出 ≤1px 断言；候选评分、时间轴、排除项、确认反馈和调整表单均可读，中文字符完整，无裁切、重叠或异常空白。

截图采用 full-page 模式，因此文件宽度等于目标视口宽度，内容超过首屏时文件高度大于目标视口高度。

| # | 文件 | 页面 | 场景 | 状态 | 视口 | E2E 测试 | 像素尺寸 | 原图检查 |
| ---: | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `C05-UI003-SCN01-CALCULATED-1440x900.png` | UI-003 | SCN-01 / PLAN-001 | CALCULATED，系统推荐 T1 | 1440×900 | `UI-003 SCN-01 calculates and confirms the system-recommended T1` | 1440×1564 | PASS：T1/T3/T2 顺序、100/62/52 分、T4 排除项和时间轴完整，无溢出或乱码 |
| 2 | `C05-UI003-SCN01-CALCULATED-1280x720.png` | UI-003 | SCN-01 / PLAN-001 | CALCULATED，系统推荐 T1 | 1280×720 | `UI-003 SCN-01 calculates and confirms the system-recommended T1` | 1280×2066 | PASS：响应式单列布局完整，候选评分、时间轴、排除项及确认按钮清晰，无横向溢出 |
| 3 | `C05-UI003-SCN01-CONFIRMED-1440x900.png` | UI-003 | SCN-01 / PLAN-001 | CONFIRMED，选中 T1 | 1440×900 | `UI-003 SCN-01 calculates and confirms the system-recommended T1` | 1440×1575 | PASS：确认抽屉已稳定关闭，T1 选择、RC-04 审计和 UI-004 入口可读，无瞬态残影 |
| 4 | `C05-UI003-SCN01-CONFIRMED-1280x720.png` | UI-003 | SCN-01 / PLAN-001 | CONFIRMED，选中 T1 | 1280×720 | `UI-003 SCN-01 calculates and confirms the system-recommended T1` | 1280×2077 | PASS：确认状态、T1、审计追踪和任务拆解入口完整，无裁切或异常空白 |
| 5 | `C05-UI003-SCN01-ADJUSTMENT-1440x900.png` | UI-003 | SCN-01 / PLAN-001 | 调整中，人工选择 T3 | 1440×900 | `UI-003 SCN-01 confirms a reviewed T3 alternative and preserves provenance` | 1440×1564 | PASS：抽屉内 T3、4 条影响工单、调整原因、USER-001 与提交按钮全部可见且可用 |
| 6 | `C05-UI003-SCN01-ADJUSTMENT-1280x720.png` | UI-003 | SCN-01 / PLAN-001 | 调整中，人工选择 T3 | 1280×720 | `UI-003 SCN-01 confirms a reviewed T3 alternative and preserves provenance` | 1280×2123 | PASS：窄视口抽屉表单及底部按钮完整，背景时间轴和排除项可读，无横向溢出 |
| 7 | `C05-UI003-SCN02-CONFIRMED-1440x900.png` | UI-003 | SCN-02 / PLAN-002 | CONFIRMED v3，补录后连续完成 | 1440×900 | `UI-003 continues from resolved SCN-02 PLAN-002 through recommendation confirmation` | 1440×1575 | PASS：PLAN-002 v3、T1 推荐、时间轴、RC-04 审计和 UI-004 入口清晰，已无 TOS-EXT-002 泄漏 |
| 8 | `C05-UI003-SCN02-CONFIRMED-1280x720.png` | UI-003 | SCN-02 / PLAN-002 | CONFIRMED v3，补录后连续完成 | 1280×720 | `UI-003 continues from resolved SCN-02 PLAN-002 through recommendation confirmation` | 1280×2077 | PASS：补录后的确认结果在单列布局完整可读，无裁切、重叠、乱码或异常空白 |

## 文件计数与尺寸

- PNG 文件数：精确 `8`。
- 目标宽度：4 张 `1440`，4 张 `1280`。
- 目标视口：`1440×900` 与 `1280×720`。
- 原始分辨率视觉复核：精确 `8/8` 通过。
