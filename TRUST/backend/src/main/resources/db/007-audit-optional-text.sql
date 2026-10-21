-- openGauss A compatibility treats empty VARCHAR values as NULL. These wire fields are optional;
-- retain their absence in SQL and normalize to empty strings when constructing the AUDIT event.
-- Required identity, scope, action and immutable JSON documents remain NOT NULL.
ALTER TABLE audit_delivery ALTER COLUMN actor DROP NOT NULL;
ALTER TABLE audit_delivery ALTER COLUMN object_id DROP NOT NULL;
ALTER TABLE audit_delivery ALTER COLUMN menu_id DROP NOT NULL;
ALTER TABLE audit_delivery ALTER COLUMN method DROP NOT NULL;
ALTER TABLE audit_delivery ALTER COLUMN path DROP NOT NULL;
ALTER TABLE audit_delivery ALTER COLUMN summary DROP NOT NULL;
ALTER TABLE audit_delivery ALTER COLUMN trace_id DROP NOT NULL;
