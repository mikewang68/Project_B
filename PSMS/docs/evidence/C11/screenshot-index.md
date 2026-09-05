# C11 / UI-011 screenshot index

All captures were produced by `e2e/ui-011-reports-operations.spec.ts` against SCN-01. The PNG dimensions were read from the image headers and all eight images were visually inspected at original resolution.

| File | Viewport | Evidence state |
| --- | ---: | --- |
| `C11-UI011-SCN01-OVERVIEW-1440x900.png` | 1440×900 | Page identity, deterministic disclosure, 8 KPIs, 5 efficiency rates, and four rendered ECharts distributions |
| `C11-UI011-SCN01-OVERVIEW-1280x720.png` | 1280×720 | Responsive overview with 4-column KPI grid, readable efficiency panel, and first two distributions before the fold |
| `C11-UI011-SCN01-FILTERED-1440x900.png` | 1440×900 | DAILY / 2026-07-17 / FAILED filter, RP-002 selected, strict ledger and detail fields |
| `C11-UI011-SCN01-FILTERED-1280x720.png` | 1280×720 | Responsive filtered ledger with no document-level horizontal overflow |
| `C11-UI011-SCN01-GENERATED-1440x900.png` | 1440×900 | RP-002 SUCCESS snapshot, Demo time, reason, primary action, command/trace/audit feedback |
| `C11-UI011-SCN01-GENERATED-1280x720.png` | 1280×720 | Responsive strict DO-012 detail, 13-item derived snapshot summary, and command feedback |
| `C11-UI011-SCN01-METRICS-1440x900.png` | 1440×900 | Large metric drawer with deterministic source and formula descriptions |
| `C11-UI011-SCN01-METRICS-1280x720.png` | 1280×720 | Responsive metric drawer without clipping or obscured close control |

Visual QA result: no horizontal document overflow, overlap, clipped primary actions, missing Chinese glyphs, stale loading masks, or blank chart captures were observed. ECharts animation is disabled under `prefers-reduced-motion` and every chart also has textual values and an accessible label.
