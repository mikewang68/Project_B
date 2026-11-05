-- Compatible additions. Preserve historical rows with unknown area as NULL.
BEGIN;
DO $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='audit_logs' AND column_name='work_area') THEN
  ALTER TABLE public.audit_logs ADD COLUMN work_area TEXT;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='config_versions' AND column_name='work_area') THEN
  ALTER TABLE public.config_versions ADD COLUMN work_area TEXT;
  ALTER TABLE public.config_versions ADD COLUMN submitted_by TEXT;
  ALTER TABLE public.config_versions ADD COLUMN approved_by TEXT;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='offline_packets' AND column_name='last_submission_sha') THEN
  ALTER TABLE public.offline_packets ADD COLUMN last_submission_sha TEXT;
  ALTER TABLE public.offline_packets ADD COLUMN last_submission_version INTEGER;
  ALTER TABLE public.offline_packets ADD COLUMN pending_payload JSONB;
 END IF;
END $$;
CREATE TABLE IF NOT EXISTS public.interlock_requests (
 id TEXT PRIMARY KEY,
 interlock_id TEXT NOT NULL REFERENCES public.interlocks(id),
 work_area TEXT NOT NULL,
 command TEXT NOT NULL CHECK(command IN ('OVERRIDE','RESET')),
 status TEXT NOT NULL CHECK(status IN ('REQUESTED','APPROVED','CANCELLED')),
 requested_by TEXT NOT NULL,
 approved_by TEXT,
 reason TEXT NOT NULL,
 version INTEGER NOT NULL DEFAULT 1,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_interlock_requests_area ON public.interlock_requests(work_area,updated_at);
CREATE INDEX IF NOT EXISTS idx_audit_logs_area ON public.audit_logs(work_area,occurred_at);
COMMIT;
