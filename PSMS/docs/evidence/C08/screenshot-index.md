# C08 / UI-008 截图索引

- 采集日期：2026-07-21（Asia/Shanghai）
- 采集命令：`node_modules\.bin\playwright.CMD test e2e/ui-008-exception-handling.spec.ts`
- 页面：`UI-008 异常处置`，场景：`SCN-01`
- 采集方式：按 1440×900、1280×720 两个浏览器视口执行全页截图。

| 状态 | 视口 | 文件 | PNG 原始尺寸 | 检查结果 |
|---|---:|---|---:|---|
| OPEN_QUEUE | 1440×900 | `C08-UI008-SCN01-OPEN_QUEUE-1440x900.png` | 1440×1732 | 通过 |
| OPEN_QUEUE | 1280×720 | `C08-UI008-SCN01-OPEN_QUEUE-1280x720.png` | 1280×2227 | 通过 |
| HANDLING | 1440×900 | `C08-UI008-SCN01-HANDLING-1440x900.png` | 1440×1788 | 通过 |
| HANDLING | 1280×720 | `C08-UI008-SCN01-HANDLING-1280x720.png` | 1280×2283 | 通过 |
| REVIEW | 1440×900 | `C08-UI008-SCN01-REVIEW-1440x900.png` | 1440×1859 | 通过 |
| REVIEW | 1280×720 | `C08-UI008-SCN01-REVIEW-1280x720.png` | 1280×2325 | 通过 |
| CLOSED | 1440×900 | `C08-UI008-SCN01-CLOSED-1440x900.png` | 1440×1831 | 通过 |
| CLOSED | 1280×720 | `C08-UI008-SCN01-CLOSED-1280x720.png` | 1280×2325 | 通过 |

## 视觉检查

- 8/8 文件存在，命名与冻结计划完全一致，没有额外 `C08-UI008-*.png`。
- 两个视口均由 E2E 断言 `document` 与 `body` 横向溢出不超过 1px。
- 1440 视口为台账、详情、动作三列；1280 视口为台账与详情两列、动作面板整行下置。
- 六个动作按钮均完整可见，无裁切或互相覆盖；长状态、时间、证据和审计文本可换行且不重叠。
- `OPEN_QUEUE`、`HANDLING`、`REVIEW`、`CLOSED` 当前状态均在台账和详情状态流中可辨认。
- `前往 UI-009 安全联锁` 在全部截图中可见；页面未提供联锁解除、覆盖或复位操作。
