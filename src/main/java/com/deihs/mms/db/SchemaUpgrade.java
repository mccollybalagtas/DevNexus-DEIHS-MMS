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
 * not have to be re-imported just to get the profile / theme columns.
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
            addColumn(c, "users", "photo_path", "VARCHAR(500) NULL");
            addColumn(c, "users", "theme", "VARCHAR(20) NOT NULL DEFAULT 'green'");
            addColumn(c, "users", "ui_density", "VARCHAR(20) NOT NULL DEFAULT 'comfortable'");
            addColumn(c, "users", "email_verified", "TINYINT(1) NOT NULL DEFAULT 0");
            addColumn(c, "account_requests", "email_verified", "TINYINT(1) NOT NULL DEFAULT 0");
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
}
