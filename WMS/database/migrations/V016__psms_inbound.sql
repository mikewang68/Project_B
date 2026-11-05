BEGIN;

-- Additive only: existing stock, receipt and authentication records stay unchanged.
CREATE TABLE wms_psms_task (
    id BIGSERIAL PRIMARY KEY,
    source_system VARCHAR(16) NOT NULL DEFAULT 'PSMS' CHECK (source_system='PSMS'),
    task_id VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL CHECK (version=1),
    event_id VARCHAR(96) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    request_body TEXT NOT NULL,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    company_code VARCHAR(64) NOT NULL,
    owner_code VARCHAR(64) NOT NULL,
    project_id VARCHAR(64) NOT NULL,
    region_id VARCHAR(64) NOT NULL,
    subsystem_code VARCHAR(32) NOT NULL DEFAULT 'WMS' CHECK(subsystem_code='WMS'),
    plan_id VARCHAR(64) NOT NULL,
    work_order_id VARCHAR(64) NOT NULL,
    expected_arrival_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('WAITING_WAREHOUSE','WAREHOUSE_CONFIRMED','PARTIALLY_RECEIVED','FULLY_RECEIVED','EXCEPTION')),
    warehouse_id BIGINT REFERENCES wms_warehouse(id),
    warehouse_code VARCHAR(64),
    wms_order_id BIGINT UNIQUE REFERENCES stockin_order(id),
    wms_order_code VARCHAR(64),
    last_sequence INTEGER NOT NULL DEFAULT 0 CHECK(last_sequence>=0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(source_system,company_id,owner_id,task_id),
    UNIQUE(source_system,event_id)
);
CREATE INDEX idx_wms_psms_scope ON wms_psms_task(company_id,owner_id,project_id,region_id,warehouse_id,status);

CREATE TABLE wms_psms_task_line (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES wms_psms_task(id),
    line_id VARCHAR(64) NOT NULL,
    good_id BIGINT NOT NULL REFERENCES wms_good(id),
    good_code VARCHAR(64) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    planned_qty NUMERIC(18,3) NOT NULL CHECK(planned_qty>0 AND planned_qty=trunc(planned_qty)),
    batch_code VARCHAR(64),
    supplier_code VARCHAR(64),
    order_line_id BIGINT UNIQUE REFERENCES stockin_order_line(id),
    UNIQUE(task_id,line_id)
);

CREATE TABLE wms_psms_outbox (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES wms_psms_task(id),
    event_id VARCHAR(96) NOT NULL UNIQUE,
    sequence INTEGER NOT NULL CHECK(sequence>0),
    payload TEXT NOT NULL,
    state VARCHAR(16) NOT NULL CHECK(state IN ('PENDING','SENDING','DELIVERED','BLOCKED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_until TIMESTAMP WITH TIME ZONE,
    lease_token VARCHAR(64),
    last_error VARCHAR(256),
    delivered_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(task_id,sequence)
);
CREATE INDEX idx_wms_psms_outbox_pending ON wms_psms_outbox(state,next_attempt_at);

-- A replay returns the original receipt result, even when the order has since finished.
CREATE TABLE wms_stockin_request (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES wms_owner(id),
    order_id BIGINT NOT NULL REFERENCES stockin_order(id),
    request_key VARCHAR(80) NOT NULL,
    request_kind VARCHAR(16) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(company_id,request_key)
);

-- Version marker checked on every application start; this migration is never run by startup.
CREATE FUNCTION public.wms_psms_guard_version() RETURNS TEXT AS $$
    SELECT '20261104-v1'::TEXT;
$$ LANGUAGE SQL IMMUTABLE;

-- The transaction marker is a version/path guard, not a substitute for business authorization.
-- A legacy WMS connected to this same database never sets it and cannot mutate linked orders.
-- Ordinary orders return immediately and retain their existing behavior.
CREATE FUNCTION public.wms_psms_guard_stockin_write() RETURNS trigger AS $$
DECLARE
    guarded_order BIGINT;
    link public.wms_psms_task%ROWTYPE;
    mapped public.stockin_order_line%ROWTYPE;
    marker TEXT;
BEGIN
    IF TG_TABLE_NAME='stockin_order' THEN
        guarded_order := OLD.id;
    ELSIF TG_OP='INSERT' THEN
        guarded_order := NEW.order_id;
    ELSE
        guarded_order := OLD.order_id;
    END IF;

    -- openGauss may raise NO_DATA_FOUND for a zero-row SELECT INTO even without STRICT.
    -- Ordinary orders are the expected empty case; decide it before materializing the row.
    IF NOT EXISTS(SELECT 1 FROM public.wms_psms_task WHERE wms_order_id=guarded_order) THEN
        -- Also reject moving an ordinary line/receipt into an already linked order.
        IF TG_OP='UPDATE' AND TG_TABLE_NAME<>'stockin_order' THEN
            IF EXISTS(SELECT 1 FROM public.wms_psms_task WHERE wms_order_id=NEW.order_id) THEN
                RAISE EXCEPTION 'PSMS-linked order identity is immutable' USING ERRCODE='23514';
            END IF;
        END IF;
        IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
    END IF;
    SELECT * INTO link FROM public.wms_psms_task WHERE wms_order_id=guarded_order;

    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'PSMS-linked business records cannot be deleted' USING ERRCODE='23514';
    END IF;

    BEGIN
        marker := current_setting('wms.psms_write');
    EXCEPTION WHEN OTHERS THEN
        marker := '';
    END;
    IF marker IS DISTINCT FROM guarded_order::TEXT THEN
        RAISE EXCEPTION 'PSMS-linked orders require the unified WMS receipt transaction' USING ERRCODE='23514';
    END IF;

    IF TG_TABLE_NAME='stockin_order' THEN
        IF NEW.id IS DISTINCT FROM OLD.id
          OR NEW.company_id IS DISTINCT FROM OLD.company_id
          OR NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id
          OR NEW.owner_id IS DISTINCT FROM OLD.owner_id
          OR NEW.order_code IS DISTINCT FROM OLD.order_code
          OR NEW.external_order_code IS DISTINCT FROM OLD.external_order_code
          OR NEW.related_order_code IS DISTINCT FROM OLD.related_order_code
          OR NEW.inbound_type IS DISTINCT FROM OLD.inbound_type
          OR NEW.source IS DISTINCT FROM OLD.source
          OR NEW.partner_id IS DISTINCT FROM OLD.partner_id
          OR NEW.planned_date IS DISTINCT FROM OLD.planned_date
          OR NEW.total_amount IS DISTINCT FROM OLD.total_amount
          OR NEW.created_by IS DISTINCT FROM OLD.created_by
          OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
          OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'PSMS-linked order identity and plan are immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.state NOT IN ('RECEIVING','COMPLETED') OR OLD.state IN ('COMPLETED','CANCELLED') THEN
            RAISE EXCEPTION 'PSMS-linked order state may change only through actual receipt' USING ERRCODE='23514';
        END IF;
        IF NEW.state='COMPLETED' AND (
            NOT EXISTS(SELECT 1 FROM public.stockin_order_line WHERE order_id=guarded_order)
            OR EXISTS(SELECT 1 FROM public.stockin_order_line WHERE order_id=guarded_order AND received_qty<>planned_qty)) THEN
            RAISE EXCEPTION 'PSMS-linked order cannot complete before full receipt' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    ELSIF TG_TABLE_NAME='stockin_order_line' THEN
        IF TG_OP='INSERT' THEN
            RAISE EXCEPTION 'PSMS-linked order cannot gain new lines after confirmation' USING ERRCODE='23514';
        END IF;
        IF NEW.id IS DISTINCT FROM OLD.id
          OR NEW.order_id IS DISTINCT FROM OLD.order_id
          OR NEW.line_no IS DISTINCT FROM OLD.line_no
          OR NEW.good_id IS DISTINCT FROM OLD.good_id
          OR NEW.planned_qty IS DISTINCT FROM OLD.planned_qty
          OR NEW.preferred_location_id IS DISTINCT FROM OLD.preferred_location_id
          OR NEW.supplier_code IS DISTINCT FROM OLD.supplier_code
          OR NEW.quality_type IS DISTINCT FROM OLD.quality_type
          OR NEW.product_date IS DISTINCT FROM OLD.product_date
          OR NEW.expire_date IS DISTINCT FROM OLD.expire_date
          OR NEW.batch_code IS DISTINCT FROM OLD.batch_code
          OR NEW.unit_price IS DISTINCT FROM OLD.unit_price
          OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'PSMS-linked line identity and planned quantity are immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.received_qty<OLD.received_qty OR NEW.received_qty>OLD.planned_qty
           OR NEW.received_qty<>trunc(NEW.received_qty) THEN
            RAISE EXCEPTION 'PSMS-linked receipt quantity must be integral, monotonic and within plan' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS(SELECT 1 FROM public.wms_psms_task_line
                      WHERE task_id=link.id AND order_line_id=OLD.id AND good_id=OLD.good_id AND planned_qty=OLD.planned_qty) THEN
            RAISE EXCEPTION 'PSMS-linked stockin line mapping does not match' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    ELSIF TG_TABLE_NAME='stockin_receipt' THEN
        IF TG_OP<>'INSERT' THEN
            RAISE EXCEPTION 'PSMS-linked receipt history is immutable' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS(SELECT 1 FROM public.stockin_order_line WHERE id=NEW.order_line_id AND order_id=guarded_order) THEN
            RAISE EXCEPTION 'PSMS receipt line is outside the linked order' USING ERRCODE='23514';
        END IF;
        SELECT * INTO mapped FROM public.stockin_order_line WHERE id=NEW.order_line_id AND order_id=guarded_order;
        IF NEW.company_id IS DISTINCT FROM link.company_id
          OR NEW.owner_id IS DISTINCT FROM link.owner_id
          OR NEW.warehouse_id IS DISTINCT FROM link.warehouse_id
          OR NEW.good_id IS DISTINCT FROM mapped.good_id
          OR NEW.batch_code IS DISTINCT FROM mapped.batch_code
          OR NEW.quality_type IS DISTINCT FROM mapped.quality_type
          OR NEW.supplier_code IS DISTINCT FROM mapped.supplier_code
          OR NEW.quantity<=0 OR NEW.quantity<>trunc(NEW.quantity)
          OR NEW.quantity>mapped.planned_qty-mapped.received_qty THEN
            RAISE EXCEPTION 'PSMS receipt identity or quantity does not match the linked task' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS(SELECT 1 FROM public.wms_psms_task_line
                      WHERE task_id=link.id AND order_line_id=mapped.id AND good_id=mapped.good_id AND planned_qty=mapped.planned_qty)
          OR NOT EXISTS(SELECT 1 FROM public.wms_location
                        WHERE id=NEW.location_id AND company_id=link.company_id AND warehouse_id=link.warehouse_id AND status='ENABLED') THEN
            RAISE EXCEPTION 'PSMS receipt mapping or warehouse location is invalid' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Unexpected PSMS write guard target' USING ERRCODE='23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_wms_psms_order_guard BEFORE UPDATE OR DELETE ON public.stockin_order
FOR EACH ROW EXECUTE PROCEDURE public.wms_psms_guard_stockin_write();
CREATE TRIGGER trg_wms_psms_line_guard BEFORE INSERT OR UPDATE OR DELETE ON public.stockin_order_line
FOR EACH ROW EXECUTE PROCEDURE public.wms_psms_guard_stockin_write();
CREATE TRIGGER trg_wms_psms_receipt_guard BEFORE INSERT OR UPDATE OR DELETE ON public.stockin_receipt
FOR EACH ROW EXECUTE PROCEDURE public.wms_psms_guard_stockin_write();

-- The runtime role has no DELETE permission. Do not use broad ALL TABLES grants.
DO $$
DECLARE role_name TEXT;
BEGIN
    FOR role_name IN SELECT rolname FROM pg_roles WHERE rolname IN ('wms_app','wms_zxj') LOOP
        -- Explicit least-privilege runtime access, independent of omm's default function ACL.
        EXECUTE 'GRANT USAGE ON SCHEMA public TO '||quote_ident(role_name);
        EXECUTE 'GRANT EXECUTE ON FUNCTION public.wms_psms_guard_version(),public.wms_psms_guard_stockin_write() TO '||quote_ident(role_name);
        EXECUTE 'GRANT SELECT,INSERT,UPDATE ON wms_psms_task,wms_psms_task_line,wms_psms_outbox,wms_stockin_request TO '||quote_ident(role_name);
        EXECUTE 'GRANT USAGE,SELECT,UPDATE ON SEQUENCE wms_psms_task_id_seq,wms_psms_task_line_id_seq,wms_psms_outbox_id_seq,wms_stockin_request_id_seq TO '||quote_ident(role_name);
    END LOOP;
END $$;

COMMIT;
