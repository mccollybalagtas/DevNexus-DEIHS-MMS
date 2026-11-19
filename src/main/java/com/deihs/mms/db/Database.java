package com.deihs.mms.db;

import com.deihs.mms.config.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Single HikariCP pool shared by the whole application.
 */
public final class Database {

    private static HikariDataSource dataSource;

    private Database() {
    }

    public static synchronized DataSource get() {
        if (dataSource == null) {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(AppConfig.jdbcUrl());
            cfg.setUsername(AppConfig.get("db.user", "root"));
            cfg.setPassword(AppConfig.get("db.password", ""));
            cfg.setMaximumPoolSize(AppConfig.getInt("db.pool.size", 10));
            cfg.setMinimumIdle(2);
            cfg.setPoolName("deihs-pool");
            cfg.setConnectionTimeout(5000);
            cfg.setValidationTimeout(3000);
            cfg.setInitializationFailTimeout(-1); // don't crash the UI if the DB is down
            dataSource = new HikariDataSource(cfg);
        }
        return dataSource;
    }

    public static Connection getConnection() throws SQLException {
        return get().getConnection();
    }

    /** Quick connectivity probe used by the login screen. */
    public static void testConnection() throws SQLException {
        try (Connection c = getConnection()) {
            if (!c.isValid(3)) {
                throw new SQLException("Database connection is not valid.");
            }
        }
    }

    public static synchronized void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }
}
