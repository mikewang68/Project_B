BEGIN;

-- Reuse the existing DELETED status; retain business codes and all historical references.
ALTER TABLE wms_good_component ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ENABLED';
ALTER TABLE wms_good_component ADD CONSTRAINT ck_component_status CHECK (status IN ('ENABLED','DISABLED','DELETED'));
ALTER TABLE wms_good_component ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE auth_user ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE auth_user ADD COLUMN deleted_by BIGINT REFERENCES auth_user(id);
ALTER TABLE wms_warehouse ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE wms_warehouse ADD COLUMN deleted_by BIGINT REFERENCES auth_user(id);
ALTER TABLE wms_owner ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE wms_owner ADD COLUMN deleted_by BIGINT REFERENCES auth_user(id);
ALTER TABLE wms_area ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE wms_area ADD COLUMN deleted_by BIGINT REFERENCES auth_user(id);
ALTER TABLE wms_work_area ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE wms_work_area ADD COLUMN deleted_by BIGINT REFERENCES auth_user(id);
ALTER TABLE wms_location ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE wms_location ADD COLUMN deleted_by BIGINT REFERENCES auth_user(id);
ALTER TABLE wms_partner ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE wms_partner ADD COLUMN deleted_by BIGINT REFERENCES auth_user(id);
ALTER TABLE wms_category ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE wms_category ADD COLUMN deleted_by BIGINT REFERENCES auth_user(id);
ALTER TABLE wms_good ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE wms_good ADD COLUMN deleted_by BIGINT REFERENCES auth_user(id);
ALTER TABLE wms_good_component ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE wms_good_component ADD COLUMN deleted_by BIGINT REFERENCES auth_user(id);

-- Revoked assignments remain stored. A later grant reactivates the same unique relationship.
ALTER TABLE auth_user_role ADD COLUMN revoked_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE auth_user_warehouse ADD COLUMN revoked_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE auth_user_owner ADD COLUMN revoked_at TIMESTAMP WITH TIME ZONE;

-- Preserve each cancelled picking attempt, including its original allocation and time.
ALTER TABLE stockout_serial_pick ADD COLUMN cancelled_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE stockout_serial_pick DROP CONSTRAINT uk_stockout_serial_pick;
CREATE UNIQUE INDEX uk_stockout_serial_pick_active ON stockout_serial_pick(order_id,serial_id) WHERE cancelled_at IS NULL;

-- The runtime account must never physically delete records from this dedicated WMS database.
REVOKE DELETE ON ALL TABLES IN SCHEMA public FROM wms_app;
ALTER DEFAULT PRIVILEGES FOR USER omm IN SCHEMA public REVOKE DELETE ON TABLES FROM wms_app;
-- The lab deployment uses a dedicated runtime role instead of the default wms_app.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='wms_zxj') THEN
        EXECUTE 'REVOKE DELETE ON ALL TABLES IN SCHEMA public FROM wms_zxj';
        EXECUTE 'ALTER DEFAULT PRIVILEGES FOR USER omm IN SCHEMA public REVOKE DELETE ON TABLES FROM wms_zxj';
    END IF;
END $$;
-- A retained parent row still satisfies an FK. Recheck its validity under a shared
-- row lock so a concurrent logical deletion cannot leave a new invalid reference.
CREATE OR REPLACE FUNCTION wms_check_live_reference() RETURNS trigger AS $$
DECLARE
    new_id TEXT;
    old_id TEXT;
    parent_status TEXT;
BEGIN
    new_id := row_to_json(NEW)->>TG_ARGV[1];
    IF new_id IS NULL THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' THEN
        old_id := row_to_json(OLD)->>TG_ARGV[1];
        IF old_id IS NOT DISTINCT FROM new_id THEN RETURN NEW; END IF;
    END IF;
    EXECUTE 'SELECT status FROM public.' || quote_ident(TG_ARGV[0]) || ' WHERE id=$1 FOR SHARE'
        INTO parent_status USING new_id::BIGINT;
    IF parent_status='DELETED' THEN
        RAISE EXCEPTION 'Referenced % record % is deleted', TG_ARGV[0], new_id USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
    dep_row RECORD;
BEGIN
    FOR dep_row IN
        SELECT fk.oid,child.relname AS child_table,parent.relname AS parent_table,a.attname AS child_column
        FROM pg_constraint fk
        JOIN pg_class parent ON parent.oid=fk.confrelid
        JOIN pg_namespace pn ON pn.oid=parent.relnamespace
        JOIN pg_class child ON child.oid=fk.conrelid
        JOIN pg_namespace cn ON cn.oid=child.relnamespace
        JOIN pg_attribute a ON a.attrelid=child.oid AND a.attnum=fk.conkey[1]
        WHERE fk.contype='f' AND pn.nspname='public' AND cn.nspname='public'
          AND parent.relname IN ('auth_user','wms_warehouse','wms_owner','wms_area','wms_work_area','wms_location','wms_partner','wms_category','wms_good','wms_good_component')
    LOOP
        EXECUTE 'CREATE TRIGGER ' || quote_ident('wms_live_ref_' || dep_row.oid::TEXT)
            || ' BEFORE INSERT OR UPDATE ON public.' || quote_ident(dep_row.child_table)
            || ' FOR EACH ROW EXECUTE PROCEDURE public.wms_check_live_reference('
            || quote_literal(dep_row.parent_table) || ',' || quote_literal(dep_row.child_column) || ')';
    END LOOP;
END $$;

COMMIT;
