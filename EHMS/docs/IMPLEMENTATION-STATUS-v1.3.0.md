# EHM server implementation status (v1.3.0)

Only the `openGauss + openGemini` server line is counted here. The MongoDB demo line is deprecated.

## Persisted server functions

- Asset register, component BOM and measurement-point CRUD.
- Telemetry sample write/query and data-quality snapshots.
- Alarm acknowledgement, controlled batch assignment, closure and transition history.
- Alarm evidence, human diagnosis, work-order conversion, execution, retest and closure.
- Health assessment, review and work-order conversion.
- Maintenance plans, inspections, defects and work-order lifecycle.
- Spare-part register, stock, receipt, reservation, issue, release, return and replenishment advice.
- FMECA, alarm rules, verified knowledge cases and SLA policies.
- Runtime readiness, capability registry, write-operation audit and audit evidence export.

## Completed in v1.3.0

- Four themes and three layouts are now packaged in the server web bundle.
- Packaging fails when critical theme resources are missing.
- Alarm selection, select-all and batch assignment now call the backend and persist to openGauss.
- Domain rules, OpenAPI contract and tests cover batch assignment.

## Partial or placeholder functions

- Personal tasks and shift handover need independent persisted aggregates.
- Device map, real-time monitoring and edge topology need confirmed site inventories and point lists.
- Templates, calibration and configuration-change history need dedicated models.
- Gateway, protocol/point-list, acceptance and replay pages need complete configuration CRUD.
- OEE, cost and periodic reporting need scheduled calculations and report archives.
- Model registry, datasets, inference lineage, release and drift monitoring await the AI runtime.
- IAM is a design matrix only until central SSO/JWT is connected and enforced server-side.

## External dependencies

- PLC/OPC UA/Modbus/MQTT site integration.
- RocketMQ event bus.
- Blockchain event API, credentials, receipts and verification API.
- Validated AI models, datasets and inference hardware.
- Central IAM, TLS, backup/restore and disaster-recovery infrastructure.

## Recommended next increments

1. Personal tasks and persisted shift handover.
2. Device templates, calibration and configuration history.
3. Gateway, protocol/point-list, access acceptance and replay CRUD.
4. KPI/OEE/cost report jobs, export and archive.
5. Central IAM enforcement.
6. Transactional outbox and blockchain adapter.
7. AI model governance and validated predictive inference.
