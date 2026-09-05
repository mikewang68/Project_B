# C09 / UI-009 截图索引

- 采集日期：2026-07-21（Asia/Shanghai）
- 采集命令：`node_modules\.bin\playwright.CMD test e2e/ui-009-safety-interlock.spec.ts`
- 页面：`UI-009 安全联锁`；场景：`SCN-01`
- 采集方式：在 1440×900、1280×720 两个浏览器视口执行全页截图。
- 数量检查：`C09-UI009-*.png` 恰好 8 个，文件名与冻结计划完全一致。

| 进度状态 | 视口 | 文件 | PNG 原始尺寸 | 原图检查 |
|---|---:|---|---:|---|
| LOCKED | 1440×900 | `C09-UI009-SCN01-LOCKED-1440x900.png` | 1440×1447 | 通过 |
| LOCKED | 1280×720 | `C09-UI009-SCN01-LOCKED-1280x720.png` | 1280×2099 | 通过 |
| RESETTING | 1440×900 | `C09-UI009-SCN01-RESETTING-1440x900.png` | 1440×1531 | 通过 |
| RESETTING | 1280×720 | `C09-UI009-SCN01-RESETTING-1280x720.png` | 1280×2155 | 通过 |
| RESTORED | 1440×900 | `C09-UI009-SCN01-RESTORED-1440x900.png` | 1440×1475 | 通过 |
| RESTORED | 1280×720 | `C09-UI009-SCN01-RESTORED-1280x720.png` | 1280×2155 | 通过 |
| OVERRIDE | 1440×900 | `C09-UI009-SCN01-OVERRIDE-1440x900.png` | 1440×1503 | 通过 |
| OVERRIDE | 1280×720 | `C09-UI009-SCN01-OVERRIDE-1280x720.png` | 1280×2155 | 通过 |

## 原始分辨率视觉检查

- 横向滚动：8/8 通过。E2E 对 `document` 与 `body` 的横向溢出逐次断言不超过 1px。
- 响应式：1440 视口为台账、详情、处置三栏；1280 视口为台账与详情两列，处置面板整行下置。
- 裁切与重叠：8/8 未发现按钮裁切、文字重叠或卡片越界；每次截图前确认 7 个处置按钮均有非零尺寸。
- 中文与状态流：8/8 未发现中文缺字；`LOCKED`、`RESET_REQUESTED`、`RESTORED`、`OVERRIDDEN` 当前节点与四个业务进度标签均可辨认。
- FORCE_STOP：8/8 均可见 `FORCE_STOP` KPI、IL-003 台账标识以及“不执行 PLC/ECS 控制”的设备边界声明；第 4 条 E2E 另行选中 IL-003，验证专用 FORCE_STOP 安全警告与 `FAILED` 回执提示。
- 跨页返回：8/8 均可见来源上下文中的“返回 UI-008 异常处置”入口，顶部面包屑也保留 UI-008 返回路径。
- 安全边界：截图中的恢复与旁路均明确为 Demo 记录，不表示真实设备已复位，也未提供真实 PLC/ECS 控制入口。
