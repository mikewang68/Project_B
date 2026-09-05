# C12 / UI-013 screenshot index

All captures were produced by `e2e/ui-013-governance-audit.spec.ts` against SCN-01. The PNG
dimensions were read from the image headers. All eight images were inspected at original
resolution after the drawer-close visual fix.

| File | Viewport | Evidence state |
| --- | ---: | --- |
| `C12-UI013-SCN01-OVERVIEW-1440x900.png` | 1440×900 | UI-013 identity, API-024 read observation, disclosure, seven KPI cards, filters, and ledger/trace workspace |
| `C12-UI013-SCN01-OVERVIEW-1280x720.png` | 1280×720 | Responsive top view with all primary KPI values and the filter boundary visible without document overflow |
| `C12-UI013-SCN01-FILTERED-1440x900.png` | 1440×900 | BASELINE + USER-001 combination, one truthful AUD-001 row, and empty trace prompt |
| `C12-UI013-SCN01-FILTERED-1280x720.png` | 1280×720 | Stacked ledger/trace layout with readable controls, row values, and detail action |
| `C12-UI013-SCN01-DETAIL-1440x900.png` | 1440×900 | Strict DO-013 fields, deterministic before/after JSON, missing metadata explanation, and trace/close actions |
| `C12-UI013-SCN01-DETAIL-1280x720.png` | 1280×720 | Responsive scrollable drawer with an always-visible top close affordance and untruncated strict fields |
| `C12-UI013-SCN01-TRACE-1440x900.png` | 1440×900 | TRACE-001 one-record chain beside the filtered ledger after complete drawer teardown |
| `C12-UI013-SCN01-TRACE-1280x720.png` | 1280×720 | Stacked trace chain with count, audit identity, source, result, action/object, and occurredAt |

Visual QA result: exactly eight C12 PNGs exist. Filenames and raw dimensions match; no document
horizontal overflow, clipped Chinese glyphs, control/tag overlap, obscured ledger action, stale
drawer mask, blank drawer, or clipped trace content remains. The 1280 detail drawer is vertically
scrollable and its standard top close control remains visible.
