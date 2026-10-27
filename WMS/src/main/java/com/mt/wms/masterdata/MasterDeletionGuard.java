package com.mt.wms.masterdata;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Called while the parent row is locked, so new FK references cannot race its deletion. */
@Repository
class MasterDeletionGuard {
    private final JdbcClient jdbc;
    MasterDeletionGuard(JdbcClient jdbc) { this.jdbc = jdbc; }

    void check(String table, long id) {
        List<Reference> references = jdbc.sql("""
            SELECT child.relname AS table_name, a.attname AS column_name,
                   EXISTS (SELECT 1 FROM pg_attribute s WHERE s.attrelid=child.oid AND s.attname='status' AND NOT s.attisdropped) AS has_status
              FROM pg_constraint fk
              JOIN pg_class parent ON parent.oid=fk.confrelid
              JOIN pg_namespace ns ON ns.oid=parent.relnamespace
              JOIN pg_class child ON child.oid=fk.conrelid
              JOIN pg_attribute a ON a.attrelid=child.oid AND a.attnum=fk.conkey[1]
             WHERE fk.contype='f' AND ns.nspname=current_schema() AND parent.relname=:table
            """).param("table",table).query(Reference.class).list();
        for (Reference ref : references) {
            // Warehouse/owner authorization is an access link, not a business dependency.
            if (ref.tableName().equals("auth_user_warehouse") || ref.tableName().equals("auth_user_owner")) continue;
            String sql="SELECT count(*) FROM " + quote(ref.tableName()) + " WHERE " + quote(ref.columnName()) + "=:id";
            if (ref.hasStatus()) sql += " AND status<>'DELETED'";
            if (jdbc.sql(sql).param("id",id).query(Long.class).single()>0)
                throw new IllegalArgumentException("资料已被 " + ref.tableName() + " 引用，请先处理关联资料；已有业务记录请使用停用");
        }
    }

    private String quote(String name) { return "\""+name.replace("\"","\"\"")+"\""; }
    record Reference(String tableName, String columnName, boolean hasStatus) {}
}
