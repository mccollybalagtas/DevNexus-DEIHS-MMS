package com.deihs.mms.service;

import com.deihs.mms.dao.AuditDao;
import com.deihs.mms.dao.ScheduleDao;
import com.deihs.mms.model.ScheduleDraft;
import com.deihs.mms.model.User;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

public class ScheduleService {

    private static final Set<String> FREQUENCIES =
            Set.of("DAILY", "WEEKLY", "MONTHLY", "QUARTERLY", "SEMI_ANNUAL", "ANNUAL");

    private final ScheduleDao scheduleDao = new ScheduleDao();
    private final AuditDao auditDao = new AuditDao();

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
