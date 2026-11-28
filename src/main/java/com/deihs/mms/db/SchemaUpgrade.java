package com.deihs.mms.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Applies small, idempotent schema additions that were introduced after the
 * original install script. Every change is guarded by an information_schema
 * lookup, so running this on a fresh database or an already-upgraded one is a
 * no-op. Keeping the upgrade here means an existing school installation does
 * not have to be re-imported just to get the profile / theme columns, the
 * notification inbox, the parts inventory, or the schedule link column.
 */
public final class SchemaUpgrade {

    private static boolean done;

    private SchemaUpgrade() {
    }

    /** Runs the upgrade once per JVM. Safe to call from any thread. */
    public static synchronized void ensure() throws SQLException {
        if (done) {
            return;
        }
        try (Connection c = Database.getConnection()) {
            // Profile / appearance columns added after the first release.
            addColumn(c, "users", "photo_path", "VARCHAR(500) NULL");
            addColumn(c, "users", "theme", "VARCHAR(20) NOT NULL DEFAULT 'green'");
            addColumn(c, "users", "ui_density", "VARCHAR(20) NOT NULL DEFAULT 'comfortable'");
            addColumn(c, "users", "email_verified", "TINYINT(1) NOT NULL DEFAULT 0");
            addColumn(c, "account_requests", "email_verified", "TINYINT(1) NOT NULL DEFAULT 0");

            // Links a preventive-maintenance schedule to the work order it last generated.
            addColumn(c, "maintenance_schedules", "last_request_id", "INT NULL");

            // In-app notification inbox.
            createTable(c, "notifications", """
                    CREATE TABLE IF NOT EXISTS notifications (
                        notification_id INT NOT NULL AUTO_INCREMENT,
                        user_id INT NOT NULL,
                        title VARCHAR(160) NOT NULL,
                        message VARCHAR(600) NOT NULL,
                        link_type VARCHAR(30) NULL,
                        link_id INT NULL,
                        is_read TINYINT(1) NOT NULL DEFAULT 0,
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (notification_id),
                        KEY idx_notifications_user (user_id, is_read, created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            // Parts inventory used by maintenance work.
            createTable(c, "parts", """
                    CREATE TABLE IF NOT EXISTS parts (
                        part_id INT NOT NULL AUTO_INCREMENT,
                        name VARCHAR(120) NOT NULL,
                        sku VARCHAR(60) NULL,
                        unit VARCHAR(20) NOT NULL DEFAULT 'pc',
                        quantity INT NOT NULL DEFAULT 0,
                        reorder_level INT NOT NULL DEFAULT 0,
                        unit_cost DECIMAL(10,2) NOT NULL DEFAULT 0.00,
                        is_active TINYINT(1) NOT NULL DEFAULT 1,
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (part_id),
                        UNIQUE KEY uk_parts_name (name)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            // Parts consumed by a request (decrements stock).
            createTable(c, "request_parts", """
                    CREATE TABLE IF NOT EXISTS request_parts (
                        id INT NOT NULL AUTO_INCREMENT,
                        request_id INT NOT NULL,
                        part_id INT NOT NULL,
                        quantity INT NOT NULL,
                        unit_cost DECIMAL(10,2) NOT NULL DEFAULT 0.00,
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (id),
                        KEY idx_request_parts_request (request_id),
                        KEY idx_request_parts_part (part_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            // The parts actually used by a request are stored separately from the
            // free-text field so the inventory and the repair cost stay in sync.
            addColumn(c, "maintenance_records", "parts_cost", "DECIMAL(10,2) NOT NULL DEFAULT 0.00");
        }
        done = true;
    }

    private static void addColumn(Connection c, String table, String column, String definition)
            throws SQLException {
        if (columnExists(c, table, column)) {
            return;
        }
        try (Statement s = c.createStatement()) {
            s.executeUpdate("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
        }
    }

    private static void createTable(Connection c, String table, String ddl) throws SQLException {
        if (tableExists(c, table)) {
            return;
        }
        try (Statement s = c.createStatement()) {
            s.executeUpdate(ddl);
        }
    }

    private static boolean columnExists(Connection c, String table, String column) throws SQLException {
        String sql = """
                SELECT 1
                  FROM information_schema.columns
                 WHERE table_schema = DATABASE()
                   AND table_name = ?
                   AND column_name = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        String sql = """
                SELECT 1
                  FROM information_schema.tables
                 WHERE table_schema = DATABASE()
                   AND table_name = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
