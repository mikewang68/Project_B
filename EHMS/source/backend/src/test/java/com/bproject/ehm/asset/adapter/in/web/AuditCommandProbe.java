package com.bproject.ehm.asset.adapter.in.web;
import org.springframework.jdbc.core.JdbcTemplate;
/** Disposable transaction probe, never scanned into a running application. */
public class AuditCommandProbe {
    private final JdbcTemplate db;
    public AuditCommandProbe(JdbcTemplate db){this.db=db;}
    public String create(){db.update("INSERT INTO audit_probe_business VALUES('device1')");return "created";}
    public org.springframework.http.ResponseEntity<String> reject(){db.update("INSERT INTO audit_probe_business VALUES('rejected')");return org.springframework.http.ResponseEntity.badRequest().body("rejected");}
}
