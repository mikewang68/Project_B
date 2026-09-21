package com.bproject.safety.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Base support class for openGauss Compatibility Spike Tests.
 * Loads connection settings strictly from environment variables or system properties.
 * All tests automatically skip in standard CI unless RUN_OPENGAUSS_SPIKE=true.
 */
public abstract class OpenGaussSpikeSupport {

    protected static HikariDataSource dataSource;

    public static final String ENV_SPIKE_ENABLED = "RUN_OPENGAUSS_SPIKE";
    public static final String ENV_HOST = "OPENGAUSS_HOST";
    public static final String ENV_PORT = "OPENGAUSS_PORT";
    public static final String ENV_DATABASE = "OPENGAUSS_DATABASE";
    public static final String ENV_USERNAME = "OPENGAUSS_USERNAME";
    public static final String ENV_PASSWORD = "OPENGAUSS_PASSWORD";
    public static final String ENV_JDBC_URL = "OPENGAUSS_JDBC_URL";

    @BeforeAll
    static void setUpDataSource() {
        if (!isSpikeEnabled()) {
            return;
        }

        String jdbcUrl = getEnvOrProp(ENV_JDBC_URL, null);
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            String host = getEnvOrProp(ENV_HOST, "127.0.0.1");
            String port = getEnvOrProp(ENV_PORT, "5432");
            String db = getEnvOrProp(ENV_DATABASE, "b_project");
            jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db + "?currentSchema=safety";
        }

        String username = getEnvOrProp(ENV_USERNAME, "safety_admin");
        String password = getEnvOrProp(ENV_PASSWORD, "");

        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(3000);
        config.setPoolName("OpenGaussSpikePool");

        dataSource = new HikariDataSource(config);
    }

    @AfterAll
    static void tearDownDataSource() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    protected static boolean isSpikeEnabled() {
        String val = getEnvOrProp(ENV_SPIKE_ENABLED, "false");
        return "true".equalsIgnoreCase(val.trim());
    }

    protected static String getEnvOrProp(String key, String defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) {
            val = System.getProperty(key);
        }
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    protected Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new IllegalStateException("DataSource not initialized. Is RUN_OPENGAUSS_SPIKE=true?");
        }
        return dataSource.getConnection();
    }
}
