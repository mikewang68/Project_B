-- OPTIONAL DEMO-ONLY STEP. Run only after seed-freight-demo.ps1 in the demo database.
-- Fix simulated dates to 2026-10-01..07; never run on real operational receipts.
BEGIN;
CREATE TEMP TABLE freight_demo_dates(seed_key VARCHAR(80) PRIMARY KEY, received_at TIMESTAMP WITH TIME ZONE);
INSERT INTO freight_demo_dates VALUES
 ('DEMO-FREIGHT-20261007-R01','2026-10-05 09:10:00+08'),
 ('DEMO-FREIGHT-20261007-R02','2026-10-06 10:20:00+08'),
 ('DEMO-FREIGHT-20261007-R03','2026-10-01 11:30:00+08'),
 ('DEMO-FREIGHT-20261007-R04','2026-10-05 13:15:00+08'),
 ('DEMO-FREIGHT-20261007-R05','2026-10-05 15:40:00+08'),
 ('DEMO-FREIGHT-20261007-R06','2026-10-07 09:00:00+08'),
 ('DEMO-FREIGHT-20261007-R07','2026-10-04 10:10:00+08'),
 ('DEMO-FREIGHT-20261007-R08','2026-10-03 14:20:00+08'),
 ('DEMO-FREIGHT-20261007-R09','2026-10-05 08:45:00+08'),
 ('DEMO-FREIGHT-20261007-R10','2026-10-06 16:25:00+08'),
 ('DEMO-FREIGHT-20261007-R11','2026-10-04 11:50:00+08'),
 ('DEMO-FREIGHT-20261007-R12','2026-10-02 09:35:00+08'),
 ('DEMO-FREIGHT-20261007-R13','2026-10-06 13:05:00+08'),
 ('DEMO-FREIGHT-20261007-R14','2026-10-01 08:20:00+08'),
 ('DEMO-FREIGHT-20261007-R15','2026-10-07 10:30:00+08');
CREATE TEMP TABLE freight_demo_receipts AS
 SELECT r.id,r.company_id,r.order_id,r.order_line_id,r.operation_code,r.balance_id,d.received_at
 FROM stockin_receipt r JOIN stockin_order o ON o.id=r.order_id
 JOIN freight_demo_dates d ON o.idempotency_key=d.seed_key
 JOIN auth_company c ON c.id=r.company_id
 JOIN wms_warehouse w ON w.id=r.warehouse_id JOIN wms_owner ow ON ow.id=r.owner_id
 WHERE c.code='default' AND w.code='default' AND ow.code='default'
   AND r.idempotency_key=d.seed_key||':RECEIVE:L'||r.order_line_id::text
   AND r.remark LIKE 'DEMO-FREIGHT-20261007%'
   AND o.remark LIKE 'DEMO-FREIGHT-20261007%';
DO $$ BEGIN
 IF (SELECT COUNT(*) FROM freight_demo_receipts) <> 15 THEN
  RAISE EXCEPTION 'Expected exactly 15 owned demo receipts; refusing timestamp changes';
 END IF;
END $$;
UPDATE stockin_receipt r SET created_at=d.received_at,
 remark=split_part(r.remark,' [simulated history]',1)||' [simulated history]'
 FROM freight_demo_receipts d WHERE r.id=d.id;
UPDATE stockin_order o SET created_at=d.received_at-INTERVAL '1 hour',planned_date=(d.received_at AT TIME ZONE 'Asia/Shanghai')::date,
 received_at=d.received_at,finished_at=d.received_at,updated_at=CURRENT_TIMESTAMP,
 remark=split_part(o.remark,' [simulated history]',1)||' [simulated history]'
 FROM freight_demo_receipts d WHERE o.id=d.order_id;
UPDATE stockin_order_line l SET created_at=d.received_at-INTERVAL '1 hour',updated_at=CURRENT_TIMESTAMP
 FROM freight_demo_receipts d WHERE l.id=d.order_line_id;
UPDATE inv_operation o SET created_at=d.received_at,completed_at=d.received_at
 FROM freight_demo_receipts d WHERE o.company_id=d.company_id AND o.operation_code=d.operation_code AND o.operation_type='STOCK_IN';
UPDATE inv_transaction t SET created_at=d.received_at
 FROM freight_demo_receipts d WHERE t.company_id=d.company_id AND t.operation_code=d.operation_code AND t.transaction_type='STOCK_IN';
UPDATE inv_balance b SET created_at=d.received_at
 FROM freight_demo_receipts d WHERE b.id=d.balance_id;
COMMIT;
