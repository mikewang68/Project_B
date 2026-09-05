# C06 UI-004 Screenshot Index

Inspection date: `2026-07-21`

Scenario: `SCN-01`

Source E2E: `e2e/ui-004-task-decomposition.spec.ts`

Capture mode: Playwright `fullPage: true`; viewport was reset to the document origin before every capture.

The PNG height is the full rendered document height; the viewport encoded in each filename is the capture viewport. Horizontal document/body overflow was measured in the E2E before every screenshot and was at most `1px`.

## Inspection legend

- **H**: document/body horizontal overflow ≤ 1px
- **C**: no clipping or overlap
- **T**: task sequence and direct dependency text readable
- **R**: rule and resource labels visible
- **D**: drawer/modal workflow usable and closed cleanly for the captured state
- **G**: Chinese glyphs intact
- **B**: no abnormal blank region
- **U**: READY-only UI-005 entry visible (`N/A` for non-READY states)

## Original-resolution results

| File | State | E2E test | PNG dimensions | H | C | T | R | D | G | B | U | Result |
| --- | --- | --- | ---: | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `C06-UI004-SCN01-GENERATED-1440x900.png` | GENERATED / G001 | generated and READY mainline | 1440×1581 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | N/A | PASS |
| `C06-UI004-SCN01-GENERATED-1280x720.png` | GENERATED / G001 | generated and READY mainline | 1280×2271 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | N/A | PASS |
| `C06-UI004-SCN01-READY-1440x900.png` | READY / G001 | generated and READY mainline | 1440×1670 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS |
| `C06-UI004-SCN01-READY-1280x720.png` | READY / G001 | generated and READY mainline | 1280×2360 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS |
| `C06-UI004-SCN01-EDITED-1440x900.png` | EDITED / split G001 | edit and regenerate | 1440×1729 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | N/A | PASS |
| `C06-UI004-SCN01-EDITED-1280x720.png` | EDITED / split G001 | edit and regenerate | 1280×2507 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | N/A | PASS |
| `C06-UI004-SCN01-REGENERATED-1440x900.png` | REGENERATED / G002 | edit and regenerate | 1440×1581 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | N/A | PASS |
| `C06-UI004-SCN01-REGENERATED-1280x720.png` | REGENERATED / G002 | edit and regenerate | 1280×2271 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | N/A | PASS |

## Visual QA conclusion

All eight files passed original-resolution inspection. The 1440px captures retain the bounded two-column task/resource layout; the 1280px captures switch to the planned single-column layout without document-level horizontal scrolling. READY visibly retains `WAITING` node state, empty resource-instance assignment as `待 UI-005 分配`, and the UI-005 navigation entry; it does not depict formal dispatch execution.
