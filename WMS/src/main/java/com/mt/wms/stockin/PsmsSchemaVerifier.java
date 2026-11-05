package com.mt.wms.stockin;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Read-only startup preflight. Missing migration/guards stops startup; it never changes the database. */
@Component
class PsmsSchemaVerifier implements ApplicationRunner {
    private final JdbcClient jdbc;
    PsmsSchemaVerifier(JdbcClient jdbc){this.jdbc=jdbc;}
    @Override public void run(ApplicationArguments arguments){
        try{
            int columns=jdbc.sql("""
                    SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND (
                      (table_name='wms_psms_task' AND column_name IN ('task_id','wms_order_id','last_sequence','request_hash','company_id','owner_id','project_id','region_id','subsystem_code'))
                      OR (table_name='wms_warehouse' AND column_name IN ('project_id','region_id','subsystem_code'))
                      OR (table_name='wms_psms_task_line' AND column_name IN ('task_id','line_id','good_id','order_line_id','planned_qty'))
                      OR (table_name='wms_psms_outbox' AND column_name IN ('task_id','event_id','sequence','payload','state','lease_token','lease_until','next_attempt_at'))
                      OR (table_name='wms_stockin_request' AND column_name IN ('company_id','request_key','order_id','request_kind','request_hash','response_body')))
                    """).query(Integer.class).single();
            String version=jdbc.sql("SELECT public.wms_psms_guard_version()").query(String.class).single();
            int guards=jdbc.sql("""
                    SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
                      JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_proc p ON p.oid=t.tgfoid
                      JOIN pg_namespace f ON f.oid=p.pronamespace
                     WHERE n.nspname='public' AND f.nspname='public' AND p.proname='wms_psms_guard_stockin_write'
                       AND t.tgenabled IN ('O','A') AND (
                         (c.relname='stockin_order' AND t.tgname='trg_wms_psms_order_guard' AND (t.tgtype::INTEGER & 27)=27)
                         OR (c.relname='stockin_order_line' AND t.tgname='trg_wms_psms_line_guard' AND (t.tgtype::INTEGER & 31)=31)
                         OR (c.relname='stockin_receipt' AND t.tgname='trg_wms_psms_receipt_guard' AND (t.tgtype::INTEGER & 31)=31))
                    """).query(Integer.class).single();
            validate(columns,version,guards);
        }catch(RuntimeException failure){
            throw new IllegalStateException("WMS V016数据库迁移或PSMS写保护未就绪；请核验受控迁移，应用不会自动改库",failure);
        }
    }
    static void validate(int columns,String version,int guards){
        if(columns!=31||!"20261104-v1".equals(version)||guards!=3)
            throw new IllegalStateException("WMS PSMS schema/guard version mismatch");
    }
}
