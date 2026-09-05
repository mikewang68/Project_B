# C02A 契约基线修复设计

## 1. 背景与结论

C02 在契约一致性闸门停止是正确行为。当前 `openapi.yaml`、`demo-fixtures.json` 与文字技术设计不是同一套可执行契约，继续编写 C02 源码会把实现变成新的隐形权威。

根因位于文档包生成源，而不是 C02：对象 Schema 从需求分析字段表提取为严格 snake_case，fixture 又由另一段常量按 camelCase 手写；接口生成器把 GET query 生成为 requestBody，并且只生成响应 example；现有自动化测试主要检查数量和 operationId，没有验证 fixture、Schema、枚举、请求、响应和场景引用的语义一致性。

本设计增加独立的 C02A“契约基线修复”步骤。C02A 只修生成源、技术设计交付物和机器基线，不编写 `src/contracts/**`、`src/mocks/**` 或页面源码。修复后的基线通过独立提交冻结，随后 C02 从契约层红灯继续。

## 2. 已比较方案与最终选择

### 方案 A：修复生成源并重新生成基线（采用）

优点是消除根因，文字文档、OpenAPI、fixture 和后续实现共同依赖一套目录；C03～C13 不会重复遇到同类歧义。代价是 C02 前增加一次受控基线变更。

### 方案 B：仅批准运行时字段映射（不采用）

snake_case 与 camelCase 可以通过 adapter 互转，但无法解决缺少必填字段、响应 Schema、枚举、GET 参数、场景种子和确定故障配置等问题。

### 方案 C：由 C02 实现自行定义契约（不采用）

开始编码最快，但实现会脱离权威文档，后续页面、测试和演示数据无法可靠追溯。

## 3. 权威层级与边界

修复后的权威顺序为：

1. 需求分析中的业务含义、中文字段、权威来源和业务约束；
2. v0.3 技术设计中的 Demo 对象、状态机、固定种子和页面消费要求；
3. 修订后的统一契约目录；
4. 由目录生成的 Mock 接口文档、`openapi.yaml` 和 `demo-fixtures.json`；
5. C02 的 Zod、TypeScript 和 MSW 实现。

`/mock/**` 是浏览器 Demo 内部接口，不是生产外部接口。因此唯一运行时对象形态采用严格 camelCase。需求表中的 snake_case 作为来源字段保留在 Schema 属性的 `x-source-field` 或 `x-source-fields` 中，用于追溯，不直接作为浏览器 DTO 字段。

C02A 不修改依赖版本、13 个路由、C01 页面骨架、Store、状态机实现、RBAC、审计持久化或业务页面。

## 4. 十四类对象的唯一形态

Schema 使用 `additionalProperties: false`。对象字段取“需求必需字段 + 技术设计 Demo 字段”的并集，fixture 必须逐条通过对应 Schema。可变对象使用稳定 `id`、整数 `version`、ISO 8601 时间；追加式或快照对象可用 `occurredAt`、`generatedAt`、`traceId` 等等价审计字段代替更新版本。

需求字段表标记为必填的字段在 camelCase 映射后仍为必填；可变对象的 `id`、`version`、`createdAt`、`updatedAt` 必填。只有业务状态确实允许暂缺的补充字段可以声明为 optional 或 nullable。演示缺字段、非法枚举和冲突不得通过制造一个本身不合法的基础对象实现，而应写入场景的 `fault.mutations`，由 handler 在故障分支按规则注入。

| 对象 | 规范化后的核心字段 |
|---|---|
| DO-001 Plan | `id, planBatchNo, trainNo, arrivalDepartureTime, trackNo, cargoType, status, sourceSystem, sourceTime, missingFields, conflicts, version, createdAt, updatedAt` |
| DO-002 Waybill | `id, waybillNo, containerNo, cargoType, quantity, unit, weight, version, createdAt, updatedAt` |
| DO-003 Track | `id, trackNo, occupyStatus, estimateReleaseTime, compatibleCargoTypes, version, updatedAt` |
| DO-004 Material | `id, materialId, containerNoRfid, cargoType, quantityWeight, locationCode, version, updatedAt` |
| DO-005 WorkOrder | `id, workOrderNo, planId, parentId, type, title, priority, status, ackStatus, ruleVersion, resourceId, teamId, blockReason, version, createdAt, updatedAt` |
| DO-006 WorkNode | `id, nodeNo, workOrderNo, sequence, status, plannedStartTime, plannedFinishTime, actualStartTime, actualFinishTime, version, updatedAt` |
| DO-007 Resource | `id, deviceId, personId, resourceType, capabilityTags, status, workArea, location, version, updatedAt` |
| DO-008 Appointment | `id, reservationNo, vehicleNo, driverId, status, queueNo, gateStatus, version, createdAt, updatedAt` |
| DO-009 Exception | `id, exceptionNo, type, level, status, owner, dueAt, evidence, version, createdAt, updatedAt` |
| DO-010 Interlock | `id, interlockNo, riskType, actionLevel, status, inputSnapshot, receiptStatus, resetRequest, approvalChain, version, createdAt, updatedAt` |
| DO-011 OfflinePacket | `id, offlinePackageNo, terminalId, workOrderNo, packageVersion, serverVersion, validation, mergeStatus, version, createdAt, updatedAt` |
| DO-012 Report | `id, reportType, period, generateStatus, metrics, generatedAt` |
| DO-013 AuditLog | `id, actorId, operatorTerminal, action, objectType, objectId, before, after, reason, traceId, occurredAt` |
| DO-014 UserRole | `id, roleCode, dataScope, status, createdAt, updatedAt` |

以下映射必须显式记录：`work_order_status → status`、`exception_level → level`、`merge_status → mergeStatus`；`start_finish_time` 分解为计划/实际开始与结束时间；`before_after_value` 分解为 `before` 和 `after`。不得在 C02 再定义另一套别名。

## 5. 枚举冻结规则

状态字段使用技术设计已经列出的状态机全集。非状态枚举在统一目录中显式冻结，至少包括：

- `cargoType`: `FLY_ASH, STEEL, CEMENT, GENERAL_CARGO`；
- `occupyStatus`: `FREE, OCCUPIED, RELEASING, BLOCKED`；
- `priority`: `LOW, MEDIUM, HIGH, URGENT`；
- `resourceType`: `TIPPER, CRANE, CONVEYOR, SILO, AGV, TEAM`；
- `resourceStatus`: `AVAILABLE, BUSY, OFFLINE, MAINTENANCE, LOCKED`；
- `exceptionLevel`: `INFO, MINOR, MAJOR, CRITICAL`；
- `actionLevel`: `WARN, PAUSE, FORCE_STOP`；
- `reportType`: `SHIFT, DAILY, MONTHLY, CUSTOM`；
- `generateStatus`: `PENDING, RUNNING, SUCCESS, FAILED`；
- `roleCode`: 现有 13 个角色编码；
- `userStatus`: `ACTIVE, DISABLED, LOCKED`。

请求中的 `action` 和 `command` 只能取相应状态机已经定义的命令集合。fixture 和 OpenAPI example 中出现的每个枚举值都必须属于同一枚举定义；非法枚举测试必须失败。

## 6. OpenAPI 修复规则

1. 保持 25 个 API 编号、业务含义和 operationId 稳定。
2. OpenAPI 路径使用标准 `{id}`；每个含路径参数的 operation 显式声明 path parameter。MSW 使用的 `:id` 路径写入 `x-msw-path`。
3. GET 参数使用 `parameters`，GET 不得包含 requestBody。
4. POST 请求使用具名 request Schema。字面量 `reason?` 修正为可选的 `reason`。
5. `ruleVersion` 类型固定为字符串，示例固定为 `RULE-1.0`。
6. 十四个领域 Schema 与公共枚举放在 `components.schemas`；公共响应放在 `components.responses`。组件总数不再等于 14，验收指标改为“恰好 14 个领域对象 Schema”。
7. 每个 200、400、403、409 响应均包含可执行 Schema；通过 `$ref` 或带 `$ref` 的 `allOf` 引用公共信封和具体 payload。
8. 成功信封为 `ok, data, auditLogId, traceId`；失败信封为 `ok, errorCode, message, details, auditLogId, traceId`。`details` 可选，其余字段必填。
9. 网络中断可由 MSW 抛出网络错误；只要返回了 JSON 失败响应，就必须符合失败信封。

## 7. 错误码裁决

继续保持现有 9 个错误码，不增加第 10 个：

`TOS-EXT-001`、`TOS-EXT-002`、`TOS-EXT-003`、`TOS-WO-001`、`TOS-IL-001`、`TOS-OFF-001`、`TOS-AUTH-001`、`DEMO-VERSION-001`、`DEMO-SCENARIO-001`。

文档命令管线中的孤立 `TOS-DATA-001` 必须删除。缺少权威输入字段返回 `TOS-EXT-002`；多源数据冲突返回 `TOS-EXT-003`；其他请求结构、非法枚举、状态或场景不允许统一返回 `DEMO-SCENARIO-001`。Zod 内部诊断保留在 `details.issues`，不新增公开错误码。

## 8. Fixture 与七套场景

`demo-fixtures.json` 仍是唯一运行时种子文件，不允许在 C02 源码再手写一套业务 fixture。基础对象数量固定为：DO-001 3 条、DO-002 8 条、DO-003 4 条、DO-004 8 条、DO-005 12 条、DO-006 12 条、DO-007 10 条、DO-008 6 条、DO-009 5 条、DO-010 4 条、DO-011 4 条、DO-012 3 条、DO-013 9 条、DO-014 13 条。`PLAN-003`、`WO-005`、`WO-006`、`APT-006` 必须真实存在。

上述基础对象全部必须通过严格领域 Schema。SCN-02 的“字段缺失”等有意非法状态不得存放在基础对象集合中；场景通过独立、同样受 Schema 约束的 `fault.mutations` 描述 `omitFields`、`replaceValues` 或冲突来源。reset 每次先恢复合法基础对象，再应用场景故障描述，因此正常数据和故障注入可以分别验证。

七套场景增加结构化 `seedRefs` 和 `fault`：

| 场景 | 关键种子 | 故障 | 错误码 | 延迟 |
|---|---|---|---|---:|
| SCN-01 | `PLAN-001` | `NONE` | 无 | 300ms |
| SCN-02 | `PLAN-002` | `VALIDATION_MISSING_FIELD` | `TOS-EXT-002` | 300ms |
| SCN-03 | `PLAN-003` | `INTERFACE_TIMEOUT` | `TOS-EXT-001` | 1500ms |
| SCN-04 | `WO-005` | `DEVICE_OFFLINE` | `TOS-WO-001` | 800ms |
| SCN-05 | `WO-006, IL-001` | `INTERLOCK_FORCE_STOP` | `TOS-IL-001` | 500ms |
| SCN-06 | `OFF-001` | `OFFLINE_VERSION_CONFLICT` | `TOS-OFF-001` | 700ms |
| SCN-07 | `APT-006` | `SOURCE_DATA_CONFLICT` | `TOS-EXT-003` | 600ms |

场景 reset 必须恢复：基础对象、场景覆盖、固定时钟、traceId/auditLogId 计数器和故障状态。所有时间、ID 和延迟均确定，不读取系统当前时间，不使用不可控随机数。

## 9. 生成源与交付物

C02A 从生成源修复，不直接长期维护生成结果。需要同步修改：

- `work/demo_doc_pack/source_extract.py`：统一对象目录、fixture、枚举、请求、响应和场景；
- `work/demo_doc_pack/machine_outputs.py`：生成标准 OpenAPI 参数、引用和信封；
- `work/demo_doc_pack/validate.py` 与测试：增加语义校验；
- Mock 接口与数据契约、主技术设计中受影响的字段、信封、错误码和场景说明；
- `openapi.yaml`、`demo-fixtures.json`、附件包和哈希清单。

Demo 工程接收修订后的基线文件，并在 `docs/evidence/C02A/` 保存旧/新 SHA-256、差异摘要和验证结果。基线修复使用独立提交 `fix(c02): reconcile generated demo contracts`，不得与 C02 源码提交混合。

## 10. 验证与完成条件

C02A 必须新增并通过以下自动校验：

1. 十四类对象的全部基础 fixture 通过对应严格 Schema；对象数量精确等于第 8 节冻结值；未知字段和非法枚举失败。
2. 恰好 14 个领域对象 ID 和领域 Schema，DO-001～DO-014 无缺失、无重复。
3. 25 个 operation 的方法、标准路径、operationId 和 `x-msw-path` 唯一对应。
4. GET 无 requestBody，query/path 参数声明完整。
5. 每个 operation 的请求和 200/400/403/409 响应都有 Schema；示例可通过 Schema。
6. `reason?` 不再出现，`reason` 可选，`ruleVersion` 的 Schema 与示例均为字符串。
7. 成功和失败 JSON 信封均包含 `traceId` 与 `auditLogId`。
8. 公开错误码集合恰好为上述 9 个，交付物中不再引用 `TOS-DATA-001`。
9. SCN-01～SCN-07 的 `seedRefs` 全部存在，故障和延迟等于本设计表格；`fault.mutations` 与基础对象分开校验，reset 输入确定。
10. 文档包生成、文档包原有测试、C01 单元测试、构建和 14 条 E2E 全部通过。
11. Demo 工程在写入任何 C02 源码前保持干净，并能明确识别 C01 提交与 C02A 基线修复提交。

完成 C02A 后，新的 C02 提示词必须引用修订后文档和基线哈希，删除“六个基线绝对不可修改”的旧前提，改为“C02 不得再次修改已由 C02A 冻结的基线”，并从 `tsc-before`、契约红灯和 Zod 实现继续。
