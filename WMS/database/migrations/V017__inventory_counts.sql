BEGIN;

CREATE TABLE public.inv_count (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES public.auth_company(id),
    warehouse_id BIGINT NOT NULL REFERENCES public.wms_warehouse(id),
    owner_id BIGINT NOT NULL REFERENCES public.wms_owner(id),
    balance_id BIGINT NOT NULL REFERENCES public.inv_balance(id),
    balance_version BIGINT NOT NULL,
    good_code VARCHAR(64) NOT NULL,
    good_name VARCHAR(256) NOT NULL,
    location_code VARCHAR(64) NOT NULL,
    batch_code VARCHAR(64) NOT NULL,
    quality_type VARCHAR(8) NOT NULL,
    supplier_code VARCHAR(64) NOT NULL,
    lpn VARCHAR(128) NOT NULL,
    unit VARCHAR(32),
    book_qty NUMERIC(18,3) NOT NULL CHECK (book_qty >= 0),
    actual_qty NUMERIC(18,3) NOT NULL CHECK (actual_qty >= 0),
    difference_qty NUMERIC(18,3) NOT NULL CHECK (difference_qty = actual_qty - book_qty),
    loss_qty NUMERIC(18,3) NOT NULL CHECK (loss_qty >= 0),
    missing_qty NUMERIC(18,3) NOT NULL CHECK (missing_qty >= 0),
    reason VARCHAR(256),
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    request_key VARCHAR(80) NOT NULL,
    created_by BIGINT NOT NULL REFERENCES public.auth_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by BIGINT REFERENCES public.auth_user(id),
    reviewed_at TIMESTAMP WITH TIME ZONE,
    review_note VARCHAR(256),
    operation_id BIGINT UNIQUE REFERENCES public.inv_operation(id),
    UNIQUE (company_id, warehouse_id, owner_id, request_key),
    CHECK (loss_qty + missing_qty <= GREATEST(-difference_qty, 0)),
    CHECK (difference_qty = 0 OR (reason IS NOT NULL AND length(trim(reason)) > 0)),
    CHECK (
        (state='PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL AND operation_id IS NULL)
        OR (state='POSTED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND operation_id IS NOT NULL)
        OR (state='REJECTED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
            AND review_note IS NOT NULL AND length(trim(review_note)) > 0 AND operation_id IS NULL)
    )
);
CREATE INDEX idx_inv_count_scope ON public.inv_count(company_id, warehouse_id, owner_id, state, id);
CREATE UNIQUE INDEX uk_inv_count_pending ON public.inv_count(balance_id) WHERE state='PENDING';

DO $$
DECLARE role_name TEXT;
BEGIN
    FOR role_name IN SELECT rolname FROM pg_roles WHERE rolname IN ('wms_app','wms_zxj') LOOP
        EXECUTE 'REVOKE DELETE ON public.inv_count FROM ' || quote_ident(role_name);
        EXECUTE 'GRANT SELECT, INSERT ON public.inv_count TO ' || quote_ident(role_name);
        EXECUTE 'REVOKE UPDATE ON public.inv_count FROM ' || quote_ident(role_name);
        EXECUTE 'GRANT UPDATE(state,reviewed_by,reviewed_at,review_note,operation_id) ON public.inv_count TO ' || quote_ident(role_name);
        EXECUTE 'GRANT USAGE, SELECT ON SEQUENCE public.inv_count_id_seq TO ' || quote_ident(role_name);
    END LOOP;
END $$;

COMMIT;
