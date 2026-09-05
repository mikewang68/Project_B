# C13 UI-012 Screenshot Index

- Captured by: `e2e/ui-012-settings-system.spec.ts`
- Capture result: exactly 8 PNG files
- Dimension result: 4 × `1440×900`, 4 × `1280×720`
- Browser assertion: `documentElement` and `body` horizontal overflow are each at most 1 px before every capture
- Visual review: every PNG inspected at original detail after the final responsive-label fix

| Screenshot | State | Actual size | Bytes | Visual result |
| --- | --- | ---: | ---: | --- |
| `C13-UI012-SCN01-OVERVIEW-1440x900.png` | Store-owned DO-015 overview | 1440×900 | 249107 | PASS — three-column layout, selected group, metadata, API-022 receipt, fixed summary, Demo warning and disabled reset are readable. |
| `C13-UI012-SCN01-OVERVIEW-1280x720.png` | Responsive overview | 1280×720 | 247016 | PASS — two-column page flow, header facts and primary form remain readable without horizontal clipping. |
| `C13-UI012-SCN01-EDITING-1440x900.png` | Two-field draft preview | 1440×900 | 247308 | PASS — editable controls and right sticky summary clearly distinguish before/after values; no drawer/modal is present. |
| `C13-UI012-SCN01-EDITING-1280x720.png` | Responsive draft preview | 1280×720 | 40874 | PASS — summary follows the form in normal flow; reason and save/discard controls fit without horizontal overflow. |
| `C13-UI012-SCN01-VALIDATION-1440x900.png` | Invalid name and blank reason | 1440×900 | 201080 | PASS — inline field error and fixed-summary error aggregation are visible and not confused with API feedback. |
| `C13-UI012-SCN01-VALIDATION-1280x720.png` | Responsive validation | 1280×720 | 38775 | PASS — validation aggregation, reason error and disabled reset explanation are fully visible in flow. |
| `C13-UI012-SCN01-SAVED-1440x900.png` | Successful API-023 edit | 1440×900 | 254605 | PASS — Store value/version update and fixed feedback show transport `AUD-0004` separately from domain `AUD-C13-001`. |
| `C13-UI012-SCN01-SAVED-1280x720.png` | Responsive save feedback | 1280×720 | 47938 | PASS — success, version, command/trace and both audit identities are visible without clipping. |

## Visual conclusions

- The 1440 viewport uses the approved left-group / center-form / right-sticky-summary layout.
- The 1280 viewport moves the summary after the form; focused captures prove edit, validation and save feedback remain usable in normal page flow.
- Chinese labels, long timestamps, IDs and audit identities remain readable. Read-only time labels no longer split between Chinese characters.
- No screenshot contains horizontal overflow, browser chrome, debug UI, a modal, or a drawer.
