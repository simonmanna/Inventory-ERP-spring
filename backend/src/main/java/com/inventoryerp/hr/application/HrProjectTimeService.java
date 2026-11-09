package com.inventoryerp.hr.application;

import com.inventoryerp.hr.domain.HrTimesheetEntry;
import com.inventoryerp.kernel.common.Obj;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

/**
 * Project time mirrored into HR timesheets (ADR-S062). The project module records time on tasks; this service keeps
 * one {@code HrTimesheetEntry} per project timesheet line in the employee's weekly timesheet (Monday to Sunday, created
 * as DRAFT when missing), with {@code linkedEntityType = 'project'} so the approved entries reach the project cost
 * capture of ADR-S038. Entries of a submitted or approved timesheet are locked: the methods report it instead of
 * changing them. Runs inside the caller's tenant and transaction; permission checks are the caller's job.
 */
@Service
@NamedInterface("project-time")
public class HrProjectTimeService {

    /** Outcome of a sync: the entry id (null when the user has no employee record) and whether it is locked. */
    public record Mirror(String entryId, boolean locked) {}

    private final HrDb hr;
    private final HrTimesheetService timesheets;

    public HrProjectTimeService(HrDb hr, HrTimesheetService timesheets) {
        this.hr = hr;
        this.timesheets = timesheets;
    }

    /** The active employee linked to the user, or {@code null}. */
    public String employeeOfUser(String userId) {
        if (userId == null) {
            return null;
        }
        List<String> ids = hr.jdbc().sql("SELECT id FROM \"HrEmployee\" WHERE \"organizationId\" = ? AND \"userId\" = ?"
                + " AND \"deletedAt\" IS NULL ORDER BY \"createdAt\" LIMIT 1").params(HrDb.org(), userId)
                .query(String.class).list();
        return ids.isEmpty() ? null : ids.getFirst();
    }

    /** Is the HR entry part of a submitted or approved timesheet (or gone)? */
    public boolean isLocked(String entryId) {
        if (entryId == null) {
            return false;
        }
        List<String> status = hr.jdbc().sql("SELECT t.status::text FROM \"HrTimesheetEntry\" e JOIN \"HrTimesheet\" t ON"
                + " t.id = e.\"timesheetId\" WHERE e.id = ? AND e.\"organizationId\" = ? AND e.\"deletedAt\" IS NULL")
                .params(entryId, HrDb.org()).query(String.class).list();
        return !status.isEmpty() && !("DRAFT".equals(status.getFirst()) || "REJECTED".equals(status.getFirst()));
    }

    /** HR status of the entry's timesheet ({@code DRAFT}, {@code SUBMITTED}, {@code APPROVED}, ...) or null. */
    public String status(String entryId) {
        if (entryId == null) {
            return null;
        }
        List<String> status = hr.jdbc().sql("SELECT t.status::text FROM \"HrTimesheetEntry\" e JOIN \"HrTimesheet\" t ON"
                + " t.id = e.\"timesheetId\" WHERE e.id = ? AND e.\"organizationId\" = ? AND e.\"deletedAt\" IS NULL")
                .params(entryId, HrDb.org()).query(String.class).list();
        return status.isEmpty() ? null : status.getFirst();
    }

    /**
     * Create or replace the mirror of a project timesheet line. A replaced entry that sits in a locked timesheet is
     * left alone and reported as locked.
     */
    public Mirror upsert(String existingEntryId, String employeeId, LocalDate date, int minutes, String description,
            String projectId, boolean billable) {
        if (employeeId == null) {
            return new Mirror(null, false);
        }
        if (existingEntryId != null) {
            if (isLocked(existingEntryId)) {
                return new Mirror(existingEntryId, true);
            }
            remove(existingEntryId);
        }
        String timesheetId = editableTimesheet(employeeId, date);
        Instant start = date.atTime(8, 0).toInstant(ZoneOffset.UTC);
        Map<String, Object> entry = timesheets.addEntry(timesheetId, Obj.of("date", date.atStartOfDay()
                        .toInstant(ZoneOffset.UTC), "startAt", start, "endAt", start.plusSeconds(minutes * 60L),
                "workType", "PROJECT", "description", description, "isBillable", billable));
        String id = (String) entry.get("id");
        // linkedEntityType 'project' is not accepted by the Nest-compatible entry route; set it here.
        hr.update(HrTimesheetEntry.class, id, Obj.of("linkedEntityType", "project", "linkedEntityId", projectId));
        return new Mirror(id, false);
    }

    /** Remove the mirror (no-op when it is locked or already gone). Returns false when locked. */
    public boolean remove(String entryId) {
        if (entryId == null) {
            return true;
        }
        if (isLocked(entryId)) {
            return false;
        }
        Map<String, Object> e = hr.byId(HrTimesheetEntry.class, entryId);
        if (e != null && e.get("deletedAt") == null) {
            timesheets.deleteEntry(entryId);
        }
        return true;
    }

    private String editableTimesheet(String employeeId, LocalDate date) {
        Instant day = date.atStartOfDay().toInstant(ZoneOffset.UTC);
        List<String> ids = hr.jdbc().sql("SELECT id FROM \"HrTimesheet\" WHERE \"organizationId\" = ? AND"
                + " \"employeeId\" = ? AND \"deletedAt\" IS NULL AND status::text IN ('DRAFT', 'REJECTED') AND"
                + " \"periodStart\" <= ? AND \"periodEnd\" >= ? ORDER BY \"periodStart\" DESC LIMIT 1")
                .params(HrDb.org(), employeeId, java.sql.Timestamp.from(day), java.sql.Timestamp.from(day))
                .query(String.class).list();
        if (!ids.isEmpty()) {
            return ids.getFirst();
        }
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Map<String, Object> created = timesheets.create(Obj.of("employeeId", employeeId, "periodStart",
                monday.atStartOfDay().toInstant(ZoneOffset.UTC), "periodEnd", monday.plusDays(6).atStartOfDay()
                        .toInstant(ZoneOffset.UTC), "notes", "Project time"));
        return (String) created.get("id");
    }
}
