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

            // SLA / escalation tracking.
            addColumn(c, "maintenance_requests", "sla_due_at", "DATETIME NULL");
            addColumn(c, "maintenance_requests", "sla_breached_at", "DATETIME NULL");
            addColumn(c, "maintenance_requests", "escalated_at", "DATETIME NULL");
            createTable(c, "sla_policies", """
                    CREATE TABLE IF NOT EXISTS sla_policies (
                        policy_id INT NOT NULL AUTO_INCREMENT,
                        priority VARCHAR(20) NOT NULL,
                        target_hours INT NOT NULL,
                        is_active TINYINT(1) NOT NULL DEFAULT 1,
                        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (policy_id),
                        UNIQUE KEY uk_sla_priority (priority)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            seedSlaPolicies(c);

            // Granular audit diffs.
            addColumn(c, "audit_logs", "old_values", "TEXT NULL");
            addColumn(c, "audit_logs", "new_values", "TEXT NULL");

            // Batch 3: asset lifecycle / warranty.
            addColumn(c, "equipment", "warranty_until", "DATE NULL");
            addColumn(c, "equipment", "lifecycle_status", "VARCHAR(30) NOT NULL DEFAULT 'IN_SERVICE'");
            addColumn(c, "equipment", "disposal_date", "DATE NULL");
            addColumn(c, "equipment", "disposal_notes", "TEXT NULL");

            // Batch 3: parts receiving log.
            createTable(c, "part_receivings", """
                    CREATE TABLE IF NOT EXISTS part_receivings (
                        receiving_id INT NOT NULL AUTO_INCREMENT,
                        part_id INT NOT NULL,
                        quantity INT NOT NULL,
                        unit_cost DECIMAL(10,2) NOT NULL DEFAULT 0.00,
                        supplier VARCHAR(120) NULL,
                        reference_no VARCHAR(80) NULL,
                        notes TEXT NULL,
                        received_by INT NULL,
                        received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (receiving_id),
                        KEY idx_pr_part (part_id),
                        KEY idx_pr_received (received_at),
                        CONSTRAINT fk_pr_part FOREIGN KEY (part_id) REFERENCES parts(part_id),
                        CONSTRAINT fk_pr_user FOREIGN KEY (received_by) REFERENCES users(user_id) ON DELETE SET NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            // Batch 3: checklist templates and request checklists.
            createTable(c, "checklist_templates", """
                    CREATE TABLE IF NOT EXISTS checklist_templates (
                        template_id INT NOT NULL AUTO_INCREMENT,
                        name VARCHAR(120) NOT NULL,
                        category VARCHAR(80) NULL,
                        description TEXT NULL,
                        is_active TINYINT(1) NOT NULL DEFAULT 1,
                        created_by INT NULL,
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (template_id),
                        KEY idx_ct_active (is_active)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            createTable(c, "checklist_template_items", """
                    CREATE TABLE IF NOT EXISTS checklist_template_items (
                        item_id INT NOT NULL AUTO_INCREMENT,
                        template_id INT NOT NULL,
                        item_text VARCHAR(255) NOT NULL,
                        sort_order INT NOT NULL DEFAULT 0,
                        PRIMARY KEY (item_id),
                        KEY idx_cti_template (template_id),
                        CONSTRAINT fk_cti_template FOREIGN KEY (template_id) REFERENCES checklist_templates(template_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            createTable(c, "request_checklists", """
                    CREATE TABLE IF NOT EXISTS request_checklists (
                        checklist_id INT NOT NULL AUTO_INCREMENT,
                        request_id INT NOT NULL,
                        template_id INT NULL,
                        name VARCHAR(120) NOT NULL,
                        created_by INT NULL,
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (checklist_id),
                        KEY idx_rc_request (request_id),
                        CONSTRAINT fk_rc_request FOREIGN KEY (request_id) REFERENCES maintenance_requests(request_id) ON DELETE CASCADE,
                        CONSTRAINT fk_rc_template FOREIGN KEY (template_id) REFERENCES checklist_templates(template_id) ON DELETE SET NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            createTable(c, "request_checklist_items", """
                    CREATE TABLE IF NOT EXISTS request_checklist_items (
                        item_id INT NOT NULL AUTO_INCREMENT,
                        checklist_id INT NOT NULL,
                        item_text VARCHAR(255) NOT NULL,
                        is_done TINYINT(1) NOT NULL DEFAULT 0,
                        done_by INT NULL,
                        done_at DATETIME NULL,
                        sort_order INT NOT NULL DEFAULT 0,
                        PRIMARY KEY (item_id),
                        KEY idx_rci_checklist (checklist_id),
                        CONSTRAINT fk_rci_checklist FOREIGN KEY (checklist_id) REFERENCES request_checklists(checklist_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            // Batch 4: high-cost approval workflow.
            createTable(c, "app_settings", """
                    CREATE TABLE IF NOT EXISTS app_settings (
                        setting_key VARCHAR(80) NOT NULL,
                        setting_value TEXT NULL,
                        updated_by INT NULL,
                        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (setting_key)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            addColumn(c, "maintenance_requests", "approval_status", "VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED'");
            addColumn(c, "maintenance_requests", "approval_requested_at", "DATETIME NULL");
            addColumn(c, "maintenance_requests", "approved_by", "INT NULL");
            addColumn(c, "maintenance_requests", "approved_at", "DATETIME NULL");
            addColumn(c, "maintenance_requests", "approval_notes", "TEXT NULL");
            addColumn(c, "maintenance_requests", "approval_cost", "DECIMAL(10,2) NULL");

            // Batch 4: before/after attachments.
            addColumn(c, "attachments", "attachment_kind", "VARCHAR(20) NOT NULL DEFAULT 'GENERAL'");
        }
        done = true;
    }

    private static void seedSlaPolicies(Connection c) throws SQLException {
        String check = "SELECT COUNT(*) FROM sla_policies";
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(check)) {
            if (rs.next() && rs.getInt(1) > 0) return;
        }
        String ins = "INSERT INTO sla_policies (priority, target_hours, is_active) VALUES (?, ?, 1)";
        Object[][] defaults = {{"CRITICAL", 24}, {"HIGH", 72}, {"MEDIUM", 168}, {"LOW", 336}};
        try (PreparedStatement ps = c.prepareStatement(ins)) {
            for (Object[] d : defaults) {
                ps.setString(1, (String) d[0]);
                ps.setInt(2, (Integer) d[1]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
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
