package com.deihs.mms.service;

import com.deihs.mms.dao.AuditDao;
import com.deihs.mms.dao.ChecklistDao;
import com.deihs.mms.model.*;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ChecklistService {
    private final ChecklistDao dao = new ChecklistDao();
    private final AuditDao auditDao = new AuditDao();

    public List<ChecklistTemplate> templates(boolean includeInactive) throws SQLException { return dao.listTemplates(includeInactive); }

    public int saveTemplate(Integer id, String name, String category, String description, boolean active, List<String> items, User actor) throws ValidationException, SQLException {
        requireManage(actor);
        String n = name == null ? "" : name.trim();
        if (n.length() < 2) throw new ValidationException("Template name is required.");
        List<String> clean = new ArrayList<>();
        if (items != null) for (String i : items) if (i != null && !i.trim().isEmpty()) clean.add(i.trim());
        if (clean.isEmpty()) throw new ValidationException("Add at least one checklist item.");
        int out;
        if (id == null) {
            out = dao.createTemplate(n, category, description, clean, actor.id());
            auditDao.log(actor.id(), "CREATE_CHECKLIST_TEMPLATE", "checklist_templates", String.valueOf(out), n);
        } else {
            dao.updateTemplate(id, n, category, description, active, clean);
            auditDao.log(actor.id(), "UPDATE_CHECKLIST_TEMPLATE", "checklist_templates", String.valueOf(id), n);
            out = id;
        }
        return out;
    }

    public List<RequestChecklist> requestChecklists(int requestId) throws SQLException { return dao.listRequestChecklists(requestId); }

    public void applyTemplate(int requestId, int templateId, User actor) throws SQLException, ValidationException {
        if (actor == null) throw new ValidationException("Not signed in.");
        dao.applyTemplate(requestId, templateId, actor.id());
        auditDao.log(actor.id(), "APPLY_CHECKLIST_TEMPLATE", "maintenance_requests", String.valueOf(requestId), "template=" + templateId);
    }

    public void setItemDone(int requestId, int itemId, boolean done, User actor) throws SQLException {
        dao.setItemDone(itemId, done, actor.id());
        auditDao.log(actor.id(), done ? "CHECKLIST_ITEM_DONE" : "CHECKLIST_ITEM_UNDONE", "maintenance_requests", String.valueOf(requestId), "item=" + itemId);
    }

    private void requireManage(User user) throws ValidationException {
        if (user == null || !(user.role() == Role.ADMIN || user.role() == Role.MAINT_HEAD))
            throw new ValidationException("Only administrators and maintenance heads can manage checklist templates.");
    }
}
