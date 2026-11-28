package com.deihs.mms.service;

import com.deihs.mms.dao.AuditDao;
import com.deihs.mms.dao.LookupDao;
import com.deihs.mms.dao.RequestDao;
import com.deihs.mms.dao.ScheduleDao;
import com.deihs.mms.model.CreatedRequest;
import com.deihs.mms.model.LookupOption;
import com.deihs.mms.model.RequestDraft;
import com.deihs.mms.model.Schedule;
import com.deihs.mms.model.ScheduleDraft;
import com.deihs.mms.model.User;

import java.util.List;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

public class ScheduleService {

    private static final Set<String> FREQUENCIES =
            Set.of("DAILY", "WEEKLY", "MONTHLY", "QUARTERLY", "SEMI_ANNUAL", "ANNUAL");

    private final ScheduleDao scheduleDao = new ScheduleDao();
    private final AuditDao auditDao = new AuditDao();
    private final RequestDao requestDao = new RequestDao();
    private final LookupDao lookupDao = new LookupDao();
    private final RequestDetailService detailService = new RequestDetailService();
    private final NotificationService notifications = new NotificationService();

    public int create(ScheduleDraft draft, User user) throws ValidationException, SQLException {
        requireManage(user);
        ScheduleDraft clean = clean(draft);
        validate(clean);
        int id = scheduleDao.create(clean, user.id());
        auditDao.log(user.id(), "CREATE_SCHEDULE", "schedules", String.valueOf(id), clean.title());
        return id;
    }

    public void update(int id, ScheduleDraft draft, User user) throws ValidationException, SQLException {
        requireManage(user);
        ScheduleDraft clean = clean(draft);
        validate(clean);
        scheduleDao.update(id, clean);
        auditDao.log(user.id(), "UPDATE_SCHEDULE", "schedules", String.valueOf(id), clean.title());
    }

    public void setActive(int id, String title, boolean active, User user)
            throws ValidationException, SQLException {
        requireManage(user);
        scheduleDao.setActive(id, active);
        auditDao.log(user.id(), active ? "RESTORE_SCHEDULE" : "ARCHIVE_SCHEDULE",
                "schedules", String.valueOf(id), title);
    }

    public void markDone(int id, String title, String frequency, LocalDate doneDate, User user)
            throws ValidationException, SQLException {
        // Allow managers and personnel to mark schedules done
        if (!Permissions.canManageSchedules(user) && user.role() != com.deihs.mms.model.Role.PERSONNEL) {
            throw new ValidationException("Your role cannot mark schedules as done.");
        }
        LocalDate done = doneDate == null ? LocalDate.now() : doneDate;
        LocalDate next = advance(done, frequency);
        scheduleDao.markDone(id, done, next);
        auditDao.log(user.id(), "COMPLETE_SCHEDULE", "schedules", String.valueOf(id),
                title + " done " + done + ", next " + next);
    }

    /**
     * Creates preventive maintenance work orders for due active schedules that
     * do not already have an open linked request. Returns the number created.
     */
    public int generateDueWorkOrders(User actor) throws ValidationException, SQLException {
        requireManage(actor);
        List<Schedule> due = scheduleDao.findDueForWorkOrderGeneration();
        int categoryId = resolvePmCategoryId();
        int created = 0;

        for (Schedule s : due) {
            RequestDraft draft = new RequestDraft(
                    "PM: " + trimTo(s.title(), 140),
                    categoryId,
                    s.facilityId(),
                    s.equipmentId(),
                    "MEDIUM",
                    buildPmDescription(s),
                    s.nextDueDate(),
                    null
            );
            CreatedRequest req = requestDao.create(draft, actor.id(), null);
            scheduleDao.setLastRequestId(s.id(), req.id());
            auditDao.log(actor.id(), "GENERATE_PM_WORK_ORDER", "maintenance_requests",
                    req.ticketNo(), "From schedule #" + s.id() + " - " + s.title());
            notifications.notifyRoles(java.util.List.of(com.deihs.mms.model.Role.ADMIN, com.deihs.mms.model.Role.MAINT_HEAD),
                    "Preventive maintenance work order created",
                    req.ticketNo() + " was generated from schedule \"" + s.title() + "\".",
                    "request", req.id(), null);

            if (s.assignedTo() != null && s.assignedTo() > 0) {
                try {
                    detailService.assign(req.id(), s.assignedTo(),
                            "Auto-assigned from preventive maintenance schedule.", actor);
                } catch (ValidationException | SQLException ignored) {
                    // Work order was created; assignment can be fixed manually.
                }
            }
            created++;
        }
        return created;
    }

    private int resolvePmCategoryId() throws SQLException {
        List<LookupOption> categories = lookupDao.categories();
        for (LookupOption c : categories) {
            String name = c.label() == null ? "" : c.label().toLowerCase(Locale.ROOT);
            if (name.contains("general")) {
                return c.id();
            }
        }
        if (!categories.isEmpty()) {
            return categories.get(0).id();
        }
        throw new SQLException("No active maintenance category is available for PM work orders.");
    }

    private static String buildPmDescription(Schedule s) {
        StringBuilder sb = new StringBuilder();
        sb.append("Automatically generated from preventive maintenance schedule.\n\n");
        sb.append("Schedule: ").append(s.title()).append('\n');
        sb.append("Frequency: ").append(s.frequency() == null ? "MONTHLY" : s.frequency()).append('\n');
        sb.append("Next due: ").append(s.nextDueDate() == null ? "-" : s.nextDueDate()).append('\n');
        if (s.description() != null && !s.description().isBlank()) {
            sb.append("\nSchedule notes:\n").append(s.description().trim());
        }
        return sb.toString();
    }

    private static String trimTo(String value, int max) {
        if (value == null) return "";
        String v = value.trim();
        return v.length() <= max ? v : v.substring(0, max);
    }

    public static LocalDate advance(LocalDate from, String frequency) {
        String f = frequency == null ? "MONTHLY" : frequency.trim().toUpperCase(Locale.ROOT);
        return switch (f) {
            case "DAILY" -> from.plusDays(1);
            case "WEEKLY" -> from.plusWeeks(1);
            case "MONTHLY" -> from.plusMonths(1);
            case "QUARTERLY" -> from.plusMonths(3);
            case "SEMI_ANNUAL" -> from.plusMonths(6);
            case "ANNUAL" -> from.plusYears(1);
            default -> from.plusMonths(1);
        };
    }

    private void requireManage(User user) throws ValidationException {
        if (!Permissions.canManageSchedules(user)) {
            throw new ValidationException("Your role cannot change maintenance schedules.");
        }
    }

    private ScheduleDraft clean(ScheduleDraft d) {
        if (d == null) return null;
        Integer fid = d.facilityId();
        Integer eid = d.equipmentId();
        Integer aid = d.assignedTo();
        if (fid != null && fid <= 0) fid = null;
        if (eid != null && eid <= 0) eid = null;
        if (aid != null && aid <= 0) aid = null;
        return new ScheduleDraft(
                fid, eid,
                text(d.title()),
                text(d.description()),
                d.frequency() == null ? "MONTHLY" : d.frequency().trim().toUpperCase(Locale.ROOT),
                d.nextDueDate(),
                aid
        );
    }

    private void validate(ScheduleDraft d) throws ValidationException {
        if (d == null) throw new ValidationException("Nothing to save.");
        if (d.title() == null || d.title().length() < 3) {
            throw new ValidationException("Enter a schedule title (at least 3 characters).");
        }
        if (d.title().length() > 150) {
            throw new ValidationException("Title is too long.");
        }
        if (d.facilityId() == null && d.equipmentId() == null) {
            throw new ValidationException("Select a facility and/or equipment for this schedule.");
        }
        if (!FREQUENCIES.contains(d.frequency())) {
            throw new ValidationException("Choose a valid frequency.");
        }
        if (d.nextDueDate() == null) {
            throw new ValidationException("Choose the next due date.");
        }
    }

    private static String text(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
