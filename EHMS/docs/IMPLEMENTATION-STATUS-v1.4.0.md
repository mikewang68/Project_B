# EHM server implementation status (v1.4.0)

Only the `openGauss + openGemini` server line is counted. MongoDB remains a deprecated local compatibility path.

## Persisted and operable

- Asset register, component BOM and measurement-point CRUD.
- Telemetry, data quality, alarm lifecycle and diagnosis-to-work-order closure.
- Health assessment, maintenance planning, inspection, defects, spares and reliability governance.
- Personal task CRUD and completion lifecycle.
- Shift-handover draft, automatic aggregation, submission and receiving acknowledgement.
- Device-template draft editing and controlled publication.
- Append-only calibration records with certificate and expiry tracking.
- Configuration-change before/after snapshots, approval and effective-state history.
- Runtime readiness, capability registry, write-operation audit and OpenAPI contract.

## Still dependent on external confirmation

- Site topology, live equipment inventory, PLC/OPC UA/Modbus/MQTT point lists and edge gateways.
- Central IAM/JWT enforcement, production TLS, backup/restore and disaster recovery.
- RocketMQ cross-system events and blockchain evidence API.
- Validated AI models, datasets, accelerator hardware and model-governance policy.
- KPI/OEE/cost scheduled jobs and formally approved reporting definitions.

## Next increments

1. Gateway, protocol/point-list, access-acceptance and replay CRUD.
2. Transactional outbox and blockchain evidence adapter.
3. Central IAM enforcement and role/data-scope authorization.
4. Scheduled KPI/OEE/cost reports and archive.
5. AI model registry and validated predictive inference.
