# C02A 契约基线修复差异

## 权威决定

- `/mock/**` 运行时对象统一采用严格 camelCase，`additionalProperties: false`。
- 需求中的 snake_case 字段只作为 `x-source-field` / `x-source-fields` 追踪元数据保留。
- `docs/baseline/openapi.yaml` 与 `docs/baseline/demo-fixtures.json` 由同一个契约目录生成，C02 不得另写第二套 fixture。
- 领域对象固定为 14 个；共享信封、请求、枚举和页面结果 Schema 不计入 14 个领域对象。

## 六份基线哈希

| 文件 | 修复前 SHA-256 | 修复后 SHA-256 |
|---|---|---|
| `README.md` | `52089817bc1dd71dbe9f148e39df6b87da4e5f124a564849100d98508ade8eb0` | `bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34` |
| `package-baseline.json` | `ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810` | `ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810` |
| `openapi.yaml` | `395429c7c73fb887ef52a6075d1919f44c09a771995d5b89aa29dbe59e03f030` | `1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83` |
| `demo-fixtures.json` | `864905b76ea6d2d88a898c3d08471523b35b872a62f376e628ef76301f6b673b` | `b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba` |
| `page-task-matrix.csv` | `c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b` | 同前 |
| `traceability.csv` | `a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2` | 同前 |

## 14 个领域对象的规范字段

| 对象 | 唯一权威字段 |
|---|---|
| DO-001 | id, planBatchNo, trainNo, arrivalDepartureTime, trackNo, cargoType, status, sourceSystem, sourceTime, missingFields, conflicts, version, createdAt, updatedAt |
| DO-002 | id, waybillNo, containerNo, cargoType, quantity, unit, weight, version, createdAt, updatedAt |
| DO-003 | id, trackNo, occupyStatus, estimateReleaseTime, compatibleCargoTypes, version, updatedAt |
| DO-004 | id, materialId, containerNoRfid, cargoType, quantityWeight, locationCode, version, updatedAt |
| DO-005 | id, workOrderNo, planId, parentId, type, title, priority, status, ackStatus, ruleVersion, resourceId, teamId, blockReason, version, createdAt, updatedAt |
| DO-006 | id, nodeNo, workOrderNo, sequence, status, plannedStartTime, plannedFinishTime, actualStartTime, actualFinishTime, version, updatedAt |
| DO-007 | id, deviceId, personId, resourceType, capabilityTags, status, workArea, location, version, updatedAt |
| DO-008 | id, reservationNo, vehicleNo, driverId, status, queueNo, gateStatus, version, createdAt, updatedAt |
| DO-009 | id, exceptionNo, type, level, status, owner, dueAt, evidence, version, createdAt, updatedAt |
| DO-010 | id, interlockNo, riskType, actionLevel, status, inputSnapshot, receiptStatus, resetRequest, approvalChain, version, createdAt, updatedAt |
| DO-011 | id, offlinePackageNo, terminalId, workOrderNo, packageVersion, serverVersion, validation, mergeStatus, version, createdAt, updatedAt |
| DO-012 | id, reportType, period, generateStatus, metrics, generatedAt |
| DO-013 | id, actorId, operatorTerminal, action, objectType, objectId, before, after, reason, traceId, occurredAt |
| DO-014 | id, roleCode, dataScope, status, createdAt, updatedAt |

Fixture 数量固定为 `3/8/4/8/12/12/10/6/5/4/4/3/9/13`，每条均能被对应严格 Schema 解析。

## OpenAPI 修复

- 保留 25 个 operation；每个 operation 均有 `x-api-id` 和 `x-msw-path`。
- OpenAPI 路径采用标准 `{id}`，MSW 冒号路径仅存在于 `x-msw-path`。
- GET 全部使用 query/path `parameters`，不再使用 requestBody。
- POST 使用命名请求 Schema；`reason` 为真正可选字段，`ruleVersion` 为带字符串示例的 string。
- 200/400/403/409 均有 Schema 引用和可验证示例。
- 成功/失败信封均含 `traceId` 与 `auditLogId`；失败信封还含 `errorCode` 与 `message`。
- 当前共有 54 个组件 Schema，其中 14 个带 `x-domain-object-id` 的领域 Schema、24 个直接枚举 Schema、14 个命名请求 Schema；另有 3 个复用错误响应组件。

## 错误码决定

唯一公开集合为：`TOS-EXT-001`、`TOS-EXT-002`、`TOS-EXT-003`、`TOS-WO-001`、`TOS-IL-001`、`TOS-OFF-001`、`TOS-AUTH-001`、`DEMO-VERSION-001`、`DEMO-SCENARIO-001`。`TOS-DATA-001` 已从所有权威输出移除。

## 场景修复

| 场景 | 种子 | 故障 | 错误码 | 延迟 |
|---|---|---|---|---:|
| SCN-01 | PLAN-001 | NONE | — | 300ms |
| SCN-02 | PLAN-002 | VALIDATION_MISSING_FIELD；运行时省略 trackNo | TOS-EXT-002 | 300ms |
| SCN-03 | PLAN-003 | INTERFACE_TIMEOUT | TOS-EXT-001 | 1500ms |
| SCN-04 | WO-005 | DEVICE_OFFLINE | TOS-WO-001 | 800ms |
| SCN-05 | WO-006、IL-001 | INTERLOCK_FORCE_STOP | TOS-IL-001 | 500ms |
| SCN-06 | OFF-001 | OFFLINE_VERSION_CONFLICT | TOS-OFF-001 | 700ms |
| SCN-07 | APT-006 | SOURCE_DATA_CONFLICT | TOS-EXT-003 | 600ms |
