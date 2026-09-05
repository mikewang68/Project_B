package com.bproject.safety.infrastructure.database;

import com.bproject.safety.infrastructure.health.InfrastructureProbe;
import com.bproject.safety.infrastructure.health.ProbeResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** openGauss 连接探针：执行 SELECT 1 验证连接池与数据库可用性。 */
@Component
public class DatabaseProbe implements InfrastructureProbe {
    private final JdbcTemplate jdbcTemplate;

    public DatabaseProbe(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String componentName() {
        return "database";
    }

    @Override
    public ProbeResult probe() {
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (Integer.valueOf(1).equals(one)) {
                return ProbeResult.up("openGauss SELECT 1 OK");
            }
            return ProbeResult.down("unexpected SELECT 1 result: " + one);
        } catch (RuntimeException ex) {
            return ProbeResult.down(rootCause(ex));
        }
    }

    private String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
