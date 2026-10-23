package com.bdemo.auth.sso;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

final class SsoTestDatabase {
    final DriverManagerDataSource source = new DriverManagerDataSource(
            "jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    final JdbcTemplate jdbc = new JdbcTemplate(source);
    SsoTestDatabase() throws Exception {
        script(Path.of("../sql/00-schema.sql"));
        script(Path.of("sql/009-emcs-sso-subject-map.sql"));
    }
    void script(Path file) throws Exception {
        String sql=Files.readString(file).replaceAll("(?m)^\\\\set.*$", "")
                .replace("SET timezone TO 'Asia/Shanghai';", "").replace(" STORED,", ",");
        try(var c=source.getConnection()) {
            ScriptUtils.executeSqlScript(c,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
