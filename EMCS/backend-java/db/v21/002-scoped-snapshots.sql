ALTER TABLE public.ai_inspection_report ADD COLUMN project_id VARCHAR(64);
ALTER TABLE public.ai_inspection_report ADD COLUMN region_id VARCHAR(64);
ALTER TABLE public.ai_inspection_report ADD COLUMN subsystem_code VARCHAR(64);
ALTER TABLE public.e_cost_recompute_record ADD COLUMN project_id VARCHAR(64);
ALTER TABLE public.e_cost_recompute_record ADD COLUMN region_id VARCHAR(64);
ALTER TABLE public.e_cost_recompute_record ADD COLUMN subsystem_code VARCHAR(64);