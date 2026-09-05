# C07 UI-005 Screenshot Index

Inspection date: 2026-07-21 (Asia/Shanghai)

All screenshots were captured as full-page PNGs after setting the named viewport. The image width therefore equals the viewport width while the height records the complete page content. Before every capture, Playwright verified both document and body horizontal overflow were at most 1 px.

| Screenshot | Original PNG size | State evidence | Visual inspection |
| --- | ---: | --- | --- |
| `C07-UI005-SCN01-READY_QUEUE-1440x900.png` | 1440x1267 | READY_QUEUE, READY=4, RESOURCE-001 selected and assignable | PASS: three-column board; context, KPI, queue, detail, resource pool, actions, and disclosure visible; no clipping, overlap, or horizontal overflow |
| `C07-UI005-SCN01-READY_QUEUE-1280x720.png` | 1280x1750 | READY_QUEUE, READY=4, RESOURCE-007 BUSY/disabled | PASS: queue/detail main columns and resource pool below; compact plan times remain readable; no clipping, overlap, or horizontal overflow |
| `C07-UI005-SCN01-ASSIGNED-1440x900.png` | 1440x1350 | ASSIGNED, bound KPI=1, DB-01 trace/audit | PASS: RESOURCE-001 binding and enabled dispatch action are visible; no clipping, overlap, or horizontal overflow |
| `C07-UI005-SCN01-ASSIGNED-1280x720.png` | 1280x1806 | ASSIGNED, WorkOrder remains READY, node remains WAITING | PASS: responsive stacking preserves queue, detail, resource truth, and action readability |
| `C07-UI005-SCN01-DISPATCHED-1440x900.png` | 1440x1350 | DISPATCHED, dispatched KPI=1, node READY, DB-02 trace/audit | PASS: receive action is visible and the resource bind action is disabled; no clipping or overlap |
| `C07-UI005-SCN01-DISPATCHED-1280x720.png` | 1280x1806 | DISPATCHED, RESOURCE-001 retained, DB-01/DB-02 audit chain | PASS: all status labels and the exception entry remain readable without horizontal overflow |
| `C07-UI005-SCN01-EXECUTING-1440x900.png` | 1440x1364 | EXECUTING, WorkOrder/WorkNode IN_PROGRESS, DB-01..DB-04 | PASS: pause and complete actions, execution KPI, audit rows, and Demo-local disclosure are visible |
| `C07-UI005-SCN01-EXECUTING-1280x720.png` | 1280x1817 | EXECUTING, actual start time, RESOURCE-001 truth | PASS: responsive two-column layout plus resource section remains readable; no clipping, overlap, or horizontal overflow |

Result: **8/8 PASS**. The final re-capture contains exactly the eight required names and no additional C07 UI-005 PNGs.
