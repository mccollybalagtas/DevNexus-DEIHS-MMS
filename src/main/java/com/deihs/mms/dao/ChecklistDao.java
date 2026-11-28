package com.deihs.mms.dao;

import com.deihs.mms.db.Database;
import com.deihs.mms.model.*;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

public class ChecklistDao {

    public List<ChecklistTemplate> listTemplates(boolean includeInactive) throws SQLException {
        String sql = "SELECT template_id, name, category, description, is_active FROM checklist_templates "
                + (includeInactive ? "" : "WHERE is_active=1 ") + "ORDER BY name";
        List<ChecklistTemplate> out = new ArrayList<>();
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt("template_id");
                out.add(new ChecklistTemplate(id, rs.getString("name"), rs.getString("category"), rs.getString("description"), rs.getInt("is_active")==1, listTemplateItems(c, id)));
            }
        }
        return out;
    }

    private List<String> listTemplateItems(Connection c, int templateId) throws SQLException {
        List<String> items = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT item_text FROM checklist_template_items WHERE template_id=? ORDER BY sort_order, item_id")) {
            ps.setInt(1, templateId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) items.add(rs.getString(1)); }
        }
        return items;
    }

    public int createTemplate(String name, String category, String description, List<String> items, int actorId) throws SQLException {
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                int id;
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO checklist_templates(name,category,description,created_by) VALUES(?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, name); ps.setString(2, blank(category)); ps.setString(3, blank(description)); ps.setInt(4, actorId);
                    ps.executeUpdate();
                    try (ResultSet k = ps.getGeneratedKeys()) { id = k.next() ? k.getInt(1) : 0; }
                }
                insertTemplateItems(c, id, items);
                c.commit(); return id;
            } catch (SQLException e) { c.rollback(); throw e; } finally { c.setAutoCommit(true); }
        }
    }

    public void updateTemplate(int templateId, String name, String category, String description, boolean active, List<String> items) throws SQLException {
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("UPDATE checklist_templates SET name=?,category=?,description=?,is_active=? WHERE template_id=?")) {
                    ps.setString(1, name); ps.setString(2, blank(category)); ps.setString(3, blank(description)); ps.setInt(4, active?1:0); ps.setInt(5, templateId); ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM checklist_template_items WHERE template_id=?")) { ps.setInt(1, templateId); ps.executeUpdate(); }
                insertTemplateItems(c, templateId, items);
                c.commit();
            } catch (SQLException e) { c.rollback(); throw e; } finally { c.setAutoCommit(true); }
        }
    }

    private void insertTemplateItems(Connection c, int templateId, List<String> items) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO checklist_template_items(template_id,item_text,sort_order) VALUES(?,?,?)")) {
            int i = 0;
            for (String it : items) {
                if (it == null || it.trim().isEmpty()) continue;
                ps.setInt(1, templateId); ps.setString(2, it.trim()); ps.setInt(3, i++); ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public int applyTemplate(int requestId, int templateId, int actorId) throws SQLException {
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                String name;
                List<String> items = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT name FROM checklist_templates WHERE template_id=?")) {
                    ps.setInt(1, templateId);
                    try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new SQLException("Template not found."); name = rs.getString(1); }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT item_text FROM checklist_template_items WHERE template_id=? ORDER BY sort_order,item_id")) {
                    ps.setInt(1, templateId); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) items.add(rs.getString(1)); }
                }
                int checklistId;
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO request_checklists(request_id,template_id,name,created_by) VALUES(?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, requestId); ps.setInt(2, templateId); ps.setString(3, name); ps.setInt(4, actorId); ps.executeUpdate();
                    try (ResultSet k = ps.getGeneratedKeys()) { checklistId = k.next() ? k.getInt(1) : 0; }
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO request_checklist_items(checklist_id,item_text,sort_order) VALUES(?,?,?)")) {
                    for (int i=0;i<items.size();i++){ ps.setInt(1, checklistId); ps.setString(2, items.get(i)); ps.setInt(3, i); ps.addBatch(); }
                    ps.executeBatch();
                }
                c.commit(); return checklistId;
            } catch (SQLException e) { c.rollback(); throw e; } finally { c.setAutoCommit(true); }
        }
    }

    public List<RequestChecklist> listRequestChecklists(int requestId) throws SQLException {
        String sql = "SELECT checklist_id, request_id, name, created_at FROM request_checklists WHERE request_id=? ORDER BY checklist_id";
        List<RequestChecklist> out = new ArrayList<>();
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("checklist_id");
                    Timestamp ts = rs.getTimestamp("created_at");
                    out.add(new RequestChecklist(id, requestId, rs.getString("name"), ts==null?null:ts.toLocalDateTime(), listRequestItems(c, id)));
                }
            }
        }
        return out;
    }

    private List<RequestChecklistItem> listRequestItems(Connection c, int checklistId) throws SQLException {
        String sql = "SELECT i.item_id,i.checklist_id,i.item_text,i.is_done,i.done_at,i.sort_order,COALESCE(CONCAT(u.first_name,' ',u.last_name),u.username) done_by FROM request_checklist_items i LEFT JOIN users u ON u.user_id=i.done_by WHERE i.checklist_id=? ORDER BY i.sort_order,i.item_id";
        List<RequestChecklistItem> items = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, checklistId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("done_at");
                    items.add(new RequestChecklistItem(rs.getInt("item_id"), checklistId, rs.getString("item_text"), rs.getInt("is_done")==1, rs.getString("done_by"), ts==null?null:ts.toLocalDateTime(), rs.getInt("sort_order")));
                }
            }
        }
        return items;
    }

    public void setItemDone(int itemId, boolean done, int actorId) throws SQLException {
        String sql = "UPDATE request_checklist_items SET is_done=?, done_by=?, done_at=? WHERE item_id=?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, done?1:0);
            if (done) { ps.setInt(2, actorId); ps.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now())); }
            else { ps.setNull(2, Types.INTEGER); ps.setNull(3, Types.TIMESTAMP); }
            ps.setInt(4, itemId);
            ps.executeUpdate();
        }
    }

    private static String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }
}
