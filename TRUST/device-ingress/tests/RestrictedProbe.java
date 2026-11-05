import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** No HTTP server, no MQ client; one connection, temporary tables only. */
public final class RestrictedProbe {
    static void require(boolean ok,String reason) { if(!ok)throw new IllegalStateException(reason); }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("PROBE_DIRECTORY_REQUIRED");
        Path root=Path.of(args[0]).toAbsolutePath(); Files.createDirectory(root);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("scope","native audit identity and temporary JDBC tables; no ingress/MQ startup");
        try {
            Path file=root.resolve("native-audit.jsonl");
            try(RejectionAudit audit=new RejectionAudit(file,8192)) {
                require(audit.append("synthetic-probe",0,"WRITE_ADMISSION_CHECK","post:events",null),"NATIVE_APPEND_FAILED");
                Files.move(file,root.resolve("native-audit-preserved.jsonl")); Files.createFile(file);
                require(!audit.append("synthetic-probe",0,"TEST","post:events",null),"REPLACEMENT_NOT_BLOCKED");
            }
            Path unlink=root.resolve("unlink-audit.jsonl");
            try(RejectionAudit audit=new RejectionAudit(unlink,8192)) {
                Files.delete(unlink);
                require(!audit.append("synthetic-probe",0,"TEST","post:events",null),"UNLINK_NOT_BLOCKED");
            }
            result.put("native_identity_replace_unlink","PASS");
            Properties props=new Properties(); props.setProperty("user",DeviceIngress.required("DEVICE_DB_USER")); props.setProperty("password",DeviceIngress.required("DEVICE_DB_PASSWORD"));
            props.setProperty("connectTimeout","5");props.setProperty("socketTimeout","10"); DriverManager.setLoginTimeout(5);
            try(Connection c=DriverManager.getConnection(DeviceIngress.required("DEVICE_DB_URL"),props)) {
                c.setAutoCommit(false);
                try {
                    DeviceIngress.execute(c,"CREATE TEMPORARY TABLE scope_probe_assets (sha256 CHAR(64) PRIMARY KEY,content BYTEA NOT NULL) ON COMMIT PRESERVE ROWS");
                    DeviceIngress.execute(c,"CREATE TEMPORARY TABLE scope_probe_bindings (sha256 CHAR(64) NOT NULL,device_id VARCHAR(63) NOT NULL,project_id VARCHAR(63) NOT NULL,area_id VARCHAR(63) NOT NULL,principal_id VARCHAR(63) NOT NULL,PRIMARY KEY(sha256,device_id)) ON COMMIT PRESERVE ROWS");
                    DeviceIngress.execute(c,"CREATE TEMPORARY TABLE scope_probe_events (event_id VARCHAR(212) PRIMARY KEY,committed_ms BIGINT NOT NULL,body TEXT NOT NULL) ON COMMIT PRESERVE ROWS");
                    c.commit();
                    String sha="a".repeat(64);
                    DeviceIngress.execute(c,"INSERT INTO scope_probe_assets VALUES(?,?)",sha,new byte[]{1,2,3});
                    DeviceIngress.execute(c,"INSERT INTO scope_probe_bindings VALUES(?,?,?,?,?)",sha,"SIM-PROBE","SIM-B-INTEGRATION","SIM-AREA-SCAN","synthetic-probe");
                    c.rollback();
                    try(var s=DeviceIngress.sql(c,"SELECT COUNT(*) FROM scope_probe_assets");var r=s.executeQuery()) {r.next();require(r.getInt(1)==0,"ROLLBACK_FAILED");}
                    DeviceIngress.execute(c,"INSERT INTO scope_probe_events VALUES(?,?,?)","synthetic-1",1L,"original bytes"); c.commit();
                    try(var s=DeviceIngress.sql(c,"SELECT committed_ms FROM scope_probe_events ORDER BY committed_ms DESC,event_id DESC LIMIT 1 FOR UPDATE");var r=s.executeQuery()) {require(r.next()&&r.getLong(1)==1L,"WATERMARK_READ_FAILED");}
                    c.rollback(); result.put("temporary_ddl_bytea_binding_transaction_watermark","PASS");
                } finally {c.rollback();} // Session closure removes all probe tables, including on failure.
            }
            Object lock=new Object();
            try(JdbcSession session=new JdbcSession(lock,()->DriverManager.getConnection(DeviceIngress.required("DEVICE_DB_URL"),props),true,new IngressTiming())) {
                synchronized(lock) {
                    try(Connection c=session.borrow()) {
                        DeviceIngress.execute(c,"CREATE TEMPORARY TABLE scope_probe_reuse(id INTEGER PRIMARY KEY) ON COMMIT PRESERVE ROWS");
                        DeviceIngress.execute(c,"INSERT INTO scope_probe_reuse VALUES(1)");
                    }
                    try(Connection c=session.borrow()) {
                        try(var s=DeviceIngress.sql(c,"SELECT COUNT(*) FROM scope_probe_reuse");var r=s.executeQuery()) {r.next();require(r.getInt(1)==1,"SESSION_NOT_REUSED");}
                        c.setAutoCommit(false);
                        try {DeviceIngress.execute(c,"INSERT INTO scope_probe_reuse VALUES(1)");throw new IllegalStateException("EXPECTED_CONSTRAINT_FAILURE");}
                        catch(SQLException expected) {c.rollback();}
                    }
                    require(session.cached==null,"FAILED_CONNECTION_RETAINED");
                    try(Connection c=session.borrow();var s=DeviceIngress.sql(c,"SELECT 1");var r=s.executeQuery()) {require(r.next()&&r.getInt(1)==1,"RECOVERY_QUERY_FAILED");}
                }
            }
            result.put("native_driver_reuse_failure_discard_and_recovery","PASS");
            result.put("status","PASS"); result.put("persistent_tables_modified",false); result.put("consumer_started",false);
        } catch(Exception failure) {
            result.put("status","FAIL"); result.put("error_type",failure.getClass().getSimpleName());
            Files.writeString(root.resolve("probe-result.json"),DeviceIngress.JSON.writeValueAsString(result));
            System.out.println(DeviceIngress.JSON.writeValueAsString(result)); System.exit(1); return;
        }
        Files.writeString(root.resolve("probe-result.json"),DeviceIngress.JSON.writeValueAsString(result));
        System.out.println(DeviceIngress.JSON.writeValueAsString(result));
    }
}
