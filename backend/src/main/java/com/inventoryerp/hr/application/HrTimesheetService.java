package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrDb.nz;
import static com.inventoryerp.hr.application.HrDb.org;
import static com.inventoryerp.hr.application.HrDb.truthy;
import static com.inventoryerp.hr.application.HrDb.user;

import com.inventoryerp.hr.domain.HrEmployee;
import com.inventoryerp.hr.domain.HrTimesheet;
import com.inventoryerp.hr.domain.HrTimesheetEntry;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Timesheets with per-day entries (port of {@code hr-timesheet.service.ts}); entries may link to a Task or
 * RepairOrder. DRAFT/REJECTED → SUBMITTED → APPROVED/REJECTED, totals recomputed from live entries.
 */
@Service
public class HrTimesheetService {

    static final List<String> WORK_TYPES = List.of("REPAIR", "HOTEL", "SCHOOL", "RENTAL", "CLEANING", "MANUFACTURING",
            "PROJECT", "CUSTOMER", "OTHER");
    static final List<String> LINKED_TYPES = List.of("Task", "RepairOrder");

    private final HrDb hr;
    private final SequenceService seq;

    public HrTimesheetService(HrDb hr, SequenceService seq) {
        this.hr = hr;
        this.seq = seq;
    }

    private static boolean editable(Object status) {
        return "DRAFT".equals(status) || "REJECTED".equals(status);
    }

    public Map<String, Object> list(Map<String, String> query) {
        HrDb.Where w = hr.where(HrTimesheet.class).eqIf("employeeId", query.get("employeeId"))
                .eqIf("status", query.get("status"));
        List<Map<String, Object>> rows = w.list("\"periodStart\" DESC", HrDb.take(query.get("take"), 50, 200),
                HrDb.skip(query.get("skip")));
        hr.oneAll(rows, "employee", HrEmployee.class, "employeeId", HrAttendanceService.EMPLOYEE_REF);
        hr.counts(rows, "entries", "HrTimesheetEntry", "timesheetId");
        return HrDb.rows(rows, w.count());
    }

    public Map<String, Object> get(String id) {
        Map<String, Object> row = hr.byId(HrTimesheet.class, id);
        if (row == null) {
            throw ApiException.notFound("Timesheet not found");
        }
        hr.onePick(row, "employee", HrEmployee.class, row.get("employeeId"), HrAttendanceService.EMPLOYEE_REF);
        row.put("entries", hr.many(HrTimesheetEntry.class, "timesheetId", id, null, "date ASC"));
        return row;
    }

    public Map<String, Object> create(Map<String, Object> dto) {
        if (!truthy(dto.get("employeeId")) || !truthy(dto.get("periodStart")) || !truthy(dto.get("periodEnd"))) {
            throw ApiException.badRequest("employeeId, periodStart and periodEnd are required");
        }
        Instant periodStart = HrDb.jsDate(dto.get("periodStart"));
        Instant periodEnd = HrDb.jsDate(dto.get("periodEnd"));
        if (periodEnd.isBefore(periodStart)) {
            throw ApiException.badRequest("periodEnd must be after periodStart");
        }
        String code = seq.next("hr_timesheet", "TS-", 6);
        return hr.insert(HrTimesheet.class, Obj.of("organizationId", org(), "timesheetCode", code,
                "employeeId", dto.get("employeeId"), "periodStart", periodStart, "periodEnd", periodEnd,
                "status", "DRAFT", "notes", dto.get("notes"), "createdBy", user()));
    }

    public Map<String, Object> update(String id, Map<String, Object> dto) {
        Map<String, Object> row = hr.byId(HrTimesheet.class, id);
        if (row == null) {
            throw ApiException.notFound("Timesheet not found");
        }
        if (!editable(row.get("status"))) {
            throw ApiException.badRequest("Only DRAFT/REJECTED timesheets can be edited");
        }
        return hr.update(HrTimesheet.class, id, Obj.of(
                "periodStart", truthy(dto.get("periodStart")) ? HrDb.jsDate(dto.get("periodStart")) : row.get("periodStart"),
                "periodEnd", truthy(dto.get("periodEnd")) ? HrDb.jsDate(dto.get("periodEnd")) : row.get("periodEnd"),
                "notes", nz(dto.get("notes"), row.get("notes")), "updatedBy", user()));
    }

    public Map<String, Object> submit(String id) {
        String userId = user();
        Map<String, Object> row = hr.byId(HrTimesheet.class, id);
        if (row == null) {
            throw ApiException.notFound("Timesheet not found");
        }
        long entries = hr.jdbc().sql("SELECT count(*) FROM \"HrTimesheetEntry\" WHERE \"timesheetId\" = ?").param(id)
                .query(Long.class).single();
        if (!editable(row.get("status"))) {
            throw ApiException.badRequest("Only DRAFT/REJECTED timesheets can be submitted");
        }
        if (entries == 0) {
            throw ApiException.badRequest("Add at least one entry before submitting");
        }
        return hr.audited(() -> hr.update(HrTimesheet.class, id, Obj.of("status", "SUBMITTED",
                "submittedAt", PosDb.raw(PosSql.NOW), "updatedBy", userId)),
                r -> AuditInput.of("HrTimesheet", id, "update", Obj.of("status", row.get("status")),
                        Obj.of("status", "SUBMITTED", "submittedBy", userId, "entries", entries)));
    }

    public Map<String, Object> approve(String id) {
        String userId = user();
        Map<String, Object> row = hr.byId(HrTimesheet.class, id);
        if (row == null) {
            throw ApiException.notFound("Timesheet not found");
        }
        if (!"SUBMITTED".equals(row.get("status"))) {
            throw ApiException.badRequest("Only SUBMITTED timesheets can be approved");
        }
        return hr.audited(() -> hr.update(HrTimesheet.class, id, Obj.of("status", "APPROVED", "approvedById", userId,
                "approvedAt", PosDb.raw(PosSql.NOW), "updatedBy", userId)),
                r -> AuditInput.of("HrTimesheet", id, "approve", Obj.of("status", row.get("status")),
                        Obj.of("status", "APPROVED", "approvedById", userId, "employeeId", row.get("employeeId"))));
    }

    public Map<String, Object> reject(String id, Map<String, Object> dto) {
        String userId = user();
        Map<String, Object> row = hr.byId(HrTimesheet.class, id);
        if (row == null) {
            throw ApiException.notFound("Timesheet not found");
        }
        if (!"SUBMITTED".equals(row.get("status"))) {
            throw ApiException.badRequest("Only SUBMITTED timesheets can be rejected");
        }
        Object notes = truthy(dto.get("reason"))
                ? ((row.get("notes") == null ? "" : row.get("notes")) + "\nRejected: " + PosSql.jsString(dto.get("reason")))
                        .trim()
                : row.get("notes");
        return hr.audited(() -> hr.update(HrTimesheet.class, id, Obj.of("status", "REJECTED", "notes", notes,
                "updatedBy", userId)),
                r -> AuditInput.of("HrTimesheet", id, "reject", Obj.of("status", row.get("status")),
                        Obj.of("status", "REJECTED", "rejectedBy", userId, "reason", dto.get("reason"))));
    }

    public Map<String, Object> delete(String id) {
        Map<String, Object> row = hr.byId(HrTimesheet.class, id);
        if (row == null) {
            throw ApiException.notFound("Timesheet not found");
        }
        if ("APPROVED".equals(row.get("status"))) {
            throw ApiException.badRequest("Approved timesheets cannot be deleted");
        }
        return hr.softDelete(HrTimesheet.class, id, Obj.of("updatedBy", user()));
    }

    // ─── entries ───

    public Map<String, Object> addEntry(String timesheetId, Map<String, Object> dto) {
        Map<String, Object> ts = hr.byId(HrTimesheet.class, timesheetId);
        if (ts == null) {
            throw ApiException.notFound("Timesheet not found");
        }
        if (!editable(ts.get("status"))) {
            throw ApiException.badRequest("Only DRAFT/REJECTED timesheets accept entries");
        }
        if (!truthy(dto.get("date")) || !truthy(dto.get("startAt")) || !truthy(dto.get("endAt"))) {
            throw ApiException.badRequest("date, startAt and endAt are required");
        }
        assertWorkType(dto);
        if (truthy(dto.get("linkedEntityType")) && !LINKED_TYPES.contains(dto.get("linkedEntityType"))) {
            throw ApiException.badRequest("Invalid linkedEntityType: " + PosSql.jsString(dto.get("linkedEntityType")));
        }
        Instant startAt = HrDb.jsDate(dto.get("startAt"));
        Instant endAt = HrDb.jsDate(dto.get("endAt"));
        if (!endAt.isAfter(startAt)) {
            throw ApiException.badRequest("endAt must be after startAt");
        }
        long minutes = Math.round((endAt.toEpochMilli() - startAt.toEpochMilli()) / 60000.0);
        Map<String, Object> entry = hr.insert(HrTimesheetEntry.class, Obj.of("organizationId", org(),
                "timesheetId", timesheetId, "date", HrDb.jsDate(dto.get("date")), "startAt", startAt, "endAt", endAt,
                "minutes", minutes, "workType", nz(dto.get("workType"), "OTHER"), "description", dto.get("description"),
                "linkedEntityType", dto.get("linkedEntityType"), "linkedEntityId", dto.get("linkedEntityId"),
                "isBillable", nz(dto.get("isBillable"), false), "createdBy", user()));
        recomputeTotal(timesheetId);
        return entry;
    }

    private static void assertWorkType(Map<String, Object> dto) {
        if (truthy(dto.get("workType")) && !WORK_TYPES.contains(dto.get("workType"))) {
            throw ApiException.badRequest("Invalid workType: " + PosSql.jsString(dto.get("workType")));
        }
    }

    private Map<String, Object> editableEntry(String entryId) {
        Map<String, Object> entry = hr.byId(HrTimesheetEntry.class, entryId);
        if (entry == null) {
            throw ApiException.notFound("Entry not found");
        }
        Map<String, Object> ts = hr.byId(HrTimesheet.class, entry.get("timesheetId"));
        if (ts != null && !editable(ts.get("status"))) {
            throw ApiException.badRequest("Only DRAFT/REJECTED timesheets accept entry edits");
        }
        return entry;
    }

    public Map<String, Object> updateEntry(String entryId, Map<String, Object> dto) {
        Map<String, Object> entry = editableEntry(entryId);
        assertWorkType(dto);
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.containsKey("date")) {
            data.put("date", HrDb.jsDate(dto.get("date")));
        }
        if (dto.containsKey("startAt") || dto.containsKey("endAt")) {
            Instant startAt = truthy(dto.get("startAt")) ? HrDb.jsDate(dto.get("startAt")) : (Instant) entry.get("startAt");
            Instant endAt = truthy(dto.get("endAt")) ? HrDb.jsDate(dto.get("endAt")) : (Instant) entry.get("endAt");
            if (!endAt.isAfter(startAt)) {
                throw ApiException.badRequest("endAt must be after startAt");
            }
            data.put("startAt", startAt);
            data.put("endAt", endAt);
            data.put("minutes", Math.round((endAt.toEpochMilli() - startAt.toEpochMilli()) / 60000.0));
        }
        for (String f : List.of("workType", "description", "linkedEntityType", "linkedEntityId", "isBillable")) {
            if (dto.containsKey(f)) {
                data.put(f, dto.get(f));
            }
        }
        data.put("updatedBy", user());
        Map<String, Object> updated = hr.update(HrTimesheetEntry.class, entryId, data);
        recomputeTotal((String) entry.get("timesheetId"));
        return updated;
    }

    public void deleteEntry(String entryId) {
        Map<String, Object> entry = editableEntry(entryId);
        hr.softDelete(HrTimesheetEntry.class, entryId, Obj.of("updatedBy", user()));
        recomputeTotal((String) entry.get("timesheetId"));
    }

    private void recomputeTotal(String timesheetId) {
        Long sum = hr.jdbc().sql("SELECT sum(minutes) FROM \"HrTimesheetEntry\" WHERE \"timesheetId\" = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(timesheetId, org()).query(Long.class)
                .optional().orElse(null);
        hr.update(HrTimesheet.class, timesheetId, Obj.of("totalMinutes", sum == null ? 0 : sum));
    }
}
