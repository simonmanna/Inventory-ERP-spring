package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrDb.nz;
import static com.inventoryerp.hr.application.HrDb.org;
import static com.inventoryerp.hr.application.HrDb.truthy;
import static com.inventoryerp.hr.application.HrDb.user;

import com.inventoryerp.hr.domain.HrAttendance;
import com.inventoryerp.hr.domain.HrAttendanceLog;
import com.inventoryerp.hr.domain.HrDepartment;
import com.inventoryerp.hr.domain.HrEmployee;
import com.inventoryerp.hr.domain.HrShift;
import com.inventoryerp.hr.domain.HrShiftAssignment;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Clock events, daily attendance rows and the worked / overtime / late / early-leave numbers payroll consumes (port
 * of {@code hr-attendance.service.ts}). One {@code HrAttendance} per employee per organisation-calendar day; raw
 * events in {@code HrAttendanceLog} are replayed into it.
 */
@Service
public class HrAttendanceService {

    static final List<String> EVENT_TYPES = List.of("CHECK_IN", "CHECK_OUT", "BREAK_START", "BREAK_END");
    static final List<String> METHODS = List.of("PIN", "RFID", "QR", "FACE", "FINGERPRINT", "MANUAL", "APP");
    static final String[] EMPLOYEE_REF = {"id", "firstName", "lastName", "employeeCode"};

    private final HrDb hr;

    private final HrPayrollSetupService setup;

    public HrAttendanceService(HrDb hr, HrPayrollSetupService setup) {
        this.hr = hr;
        this.setup = setup;
    }

    private ZoneId timeZone() {
        return hr.timezone(org());
    }

    /** "HH:mm" → minutes since midnight (JS {@code split(':').map(Number)}). */
    static double toMinutes(Object t) {
        String[] parts = String.valueOf(t).split(":", -1);
        double h = HrDb.jsNumber(parts[0]);
        double m = parts.length > 1 ? HrDb.jsNumber(parts[1]) : Double.NaN;
        return h * 60 + (Double.isNaN(m) || m == 0 ? 0 : m);
    }

    private Map<String, Object> dayRow(Object employeeId, Instant day) {
        return hr.where(HrAttendance.class).eq("employeeId", employeeId).and("date = ?", SqlRows.ts(day)).first();
    }

    // ─── POS sign-in / sign-off ───

    /** First PIN sign-in of the day is the arrival; later ones never move it. */
    public void posSignIn(String employeeId, Instant at, String deviceId) {
        Map<String, Object> existing = dayRow(employeeId, HrDb.attendanceDay(at, timeZone()));
        if (existing != null && existing.get("checkInAt") != null) {
            return;
        }
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("employeeId", employeeId);
        dto.put("eventType", "CHECK_IN");
        dto.put("timestamp", at);
        dto.put("method", "PIN");
        dto.put("deviceId", deviceId);
        dto.put("note", "Clocked in automatically at POS sign-in");
        clock(dto);
    }

    /** Last log-off of the day is the departure; no check-in → nothing recorded. */
    public void posSignOff(String employeeId, Instant at, String deviceId) {
        Map<String, Object> existing = dayRow(employeeId, HrDb.attendanceDay(at, timeZone()));
        if (existing == null || existing.get("checkInAt") == null || ((Instant) existing.get("checkInAt")).isAfter(at)) {
            return;
        }
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("employeeId", employeeId);
        dto.put("eventType", "CHECK_OUT");
        dto.put("timestamp", at);
        dto.put("method", "PIN");
        dto.put("deviceId", deviceId);
        dto.put("force", true);
        dto.put("note", "Clocked out automatically at POS log-off");
        clock(dto);
    }

    // ─── clock events ───

    /** Register a clock event and replay it into the daily attendance row. */
    public Map<String, Object> clock(Map<String, Object> dto) {
        String orgId = org();
        String userId = user();
        if (!truthy(dto.get("employeeId"))) {
            throw ApiException.badRequest("employeeId is required");
        }
        if (!truthy(dto.get("eventType")) || !EVENT_TYPES.contains(dto.get("eventType"))) {
            throw ApiException.badRequest("Invalid eventType: " + PosSql.jsString(dto.get("eventType")));
        }
        if (truthy(dto.get("method")) && !METHODS.contains(dto.get("method"))) {
            throw ApiException.badRequest("Invalid method: " + PosSql.jsString(dto.get("method")));
        }
        Instant timestamp = truthy(dto.get("timestamp")) ? HrDb.jsDate(dto.get("timestamp")) : Instant.now();
        if (hr.where(HrEmployee.class).eq("id", dto.get("employeeId")).first() == null) {
            throw ApiException.notFound("Employee not found");
        }
        ZoneId tz = timeZone();
        Object employeeId = dto.get("employeeId");
        boolean force = truthy(dto.get("force"));
        return hr.inTx(() -> {
            Map<String, Object> log = hr.insert(HrAttendanceLog.class, Obj.of("organizationId", orgId,
                    "employeeId", employeeId, "eventType", dto.get("eventType"), "timestamp", timestamp,
                    "method", nz(dto.get("method"), "MANUAL"), "deviceId", dto.get("deviceId"),
                    "ipAddress", dto.get("ipAddress"), "gpsLat", dto.get("gpsLat"), "gpsLng", dto.get("gpsLng"),
                    "note", dto.get("note"), "createdBy", userId));
            Instant date = HrDb.attendanceDay(timestamp, tz);
            Map<String, Object> attendance = dayRow(employeeId, date);
            if (attendance == null) {
                Map<String, Object> assignment = hr.where(HrShiftAssignment.class).eq("employeeId", employeeId)
                        .eq("isActive", true).first("\"effectiveFrom\" DESC");
                attendance = hr.insert(HrAttendance.class, Obj.of("organizationId", orgId, "employeeId", employeeId,
                        "date", date, "shiftId", assignment == null ? null : assignment.get("shiftId"),
                        "createdBy", userId));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("updatedBy", userId);
            Instant checkInAt = (Instant) attendance.get("checkInAt");
            switch ((String) dto.get("eventType")) {
                case "CHECK_IN" -> {
                    if (checkInAt != null && !checkInAt.isAfter(timestamp) && !force) {
                        throw ApiException.badRequest("Already checked in today");
                    }
                    data.put("checkInAt", timestamp);
                    data.put("source", nz(dto.get("method"), attendance.get("source")));
                }
                case "BREAK_START" -> data.put("breakStartAt", timestamp);
                case "BREAK_END" -> {
                    Instant breakStart = (Instant) attendance.get("breakStartAt");
                    if (breakStart == null) {
                        throw ApiException.badRequest("No break start recorded yet");
                    }
                    long breakMinutes = Math.round((timestamp.toEpochMilli() - breakStart.toEpochMilli()) / 60000.0);
                    data.put("breakStartAt", null);
                    data.put("breakEndAt", timestamp);
                    data.put("totalBreakMinutes", PosSql.intOf(attendance.get("totalBreakMinutes"))
                            + Math.max(0, breakMinutes));
                }
                default -> {
                    if (attendance.get("checkOutAt") != null && !force) {
                        throw ApiException.badRequest("Already checked out today");
                    }
                    data.put("checkOutAt", timestamp);
                }
            }
            String attendanceId = (String) attendance.get("id");
            Map<String, Object> updated = hr.update(HrAttendance.class, attendanceId, data);
            hr.one(updated, "shift", HrShift.class, updated.get("shiftId"));
            recompute(attendanceId, tz);
            hr.db().update(HrAttendanceLog.class, Obj.of("attendanceId", attendanceId), "id = ?", log.get("id"));
            return updated;
        });
    }

    /** Recompute worked / overtime / late / early-leave minutes of a daily row. */
    private void recompute(String attendanceId, ZoneId tz) {
        Map<String, Object> row = hr.byId(HrAttendance.class, attendanceId);
        if (row == null) {
            return;
        }
        Map<String, Object> shift = row.get("shiftId") == null ? null : hr.byId(HrShift.class, row.get("shiftId"));
        double workedMinutes = 0;
        double overtimeMinutes = 0;
        double lateMinutes = 0;
        double earlyLeaveMinutes = 0;
        Object status = row.get("status");
        Instant in = (Instant) row.get("checkInAt");
        Instant out = (Instant) row.get("checkOutAt");
        int totalBreak = PosSql.intOf(row.get("totalBreakMinutes"));
        if (in != null && out != null) {
            long total = Math.round((out.toEpochMilli() - in.toEpochMilli()) / 60000.0);
            workedMinutes = Math.max(0, total - totalBreak);
            if (shift != null) {
                double shiftStart = toMinutes(shift.get("startTime"));
                double shiftEnd = toMinutes(shift.get("endTime"));
                double shiftLength = shiftEnd > shiftStart ? shiftEnd - shiftStart : 24 * 60 - shiftStart + shiftEnd;
                double scheduleLen = Math.max(0, shiftLength - totalBreak);
                int grace = PosSql.intOf(shift.get("graceMinutes"));
                int checkInMin = HrDb.minutesOfDay(in, tz);
                if (checkInMin > shiftStart + grace) {
                    lateMinutes = checkInMin - shiftStart;
                    if ("PRESENT".equals(status)) {
                        status = "LATE";
                    }
                }
                int checkOutMin = HrDb.minutesOfDay(out, tz);
                if (checkOutMin < shiftEnd - grace) {
                    earlyLeaveMinutes = shiftEnd - checkOutMin;
                    if ("PRESENT".equals(status)) {
                        status = "EARLY_LEAVE";
                    }
                }
                if (workedMinutes > scheduleLen) {
                    overtimeMinutes = workedMinutes - scheduleLen;
                    Object max = shift.get("maxOvertimeMinutes");
                    if (max != null && PosSql.intOf(max) != 0 && overtimeMinutes > PosSql.intOf(max)) {
                        overtimeMinutes = PosSql.intOf(max);
                    }
                    workedMinutes = scheduleLen;
                }
            }
        } else if (in != null) {
            long elapsed = Math.round((System.currentTimeMillis() - in.toEpochMilli()) / 60000.0);
            workedMinutes = Math.max(0, elapsed - totalBreak);
        }
        hr.update(HrAttendance.class, attendanceId, Obj.of("workedMinutes", (int) workedMinutes,
                "overtimeMinutes", (int) overtimeMinutes, "lateMinutes", (int) lateMinutes,
                "earlyLeaveMinutes", (int) earlyLeaveMinutes, "status", status, "updatedAt", PosDb.raw(PosSql.NOW)));
    }

    /** Mark a calendar day as approved leave (called inside the leave approval transaction). */
    public void markLeaveDay(String employeeId, Instant date, String notes) {
        String orgId = org();
        Instant dateStart = date.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<String, Object> existing = dayRow(employeeId, dateStart);
        if (existing != null) {
            hr.update(HrAttendance.class, (String) existing.get("id"), Obj.of("status", "ON_LEAVE",
                    "notes", nz(notes, existing.get("notes"))));
            return;
        }
        hr.insert(HrAttendance.class, Obj.of("organizationId", orgId, "employeeId", employeeId, "date", dateStart,
                "status", "ON_LEAVE", "notes", notes));
    }

    // ─── queries ───

    public Map<String, Object> listAttendance(Map<String, String> query) {
        HrDb.Where w = hr.where(HrAttendance.class).eqIf("employeeId", query.get("employeeId"))
                .eqIf("status", query.get("status"));
        if (truthy(query.get("from")) || truthy(query.get("to"))) {
            ZoneId tz = timeZone();
            if (truthy(query.get("from"))) {
                w.and("date >= ?", SqlRows.ts(HrDb.attendanceDay(query.get("from"), tz)));
            }
            if (truthy(query.get("to"))) {
                w.and("date < ?", SqlRows.ts(HrDb.addDays(HrDb.attendanceDay(query.get("to"), tz), 1)));
            }
        }
        List<Map<String, Object>> rows = w.list("date DESC, \"createdAt\" DESC", HrDb.take(query.get("take"), 50, 200),
                HrDb.skip(query.get("skip")));
        hr.oneAll(rows, "employee", HrEmployee.class, "employeeId", EMPLOYEE_REF);
        hr.oneAll(rows, "shift", HrShift.class, "shiftId");
        return HrDb.rows(rows, w.count());
    }

    public Map<String, Object> getAttendance(String id) {
        Map<String, Object> row = hr.byId(HrAttendance.class, id);
        if (row == null) {
            throw ApiException.notFound("Attendance record not found");
        }
        hr.onePick(row, "employee", HrEmployee.class, row.get("employeeId"), EMPLOYEE_REF);
        hr.one(row, "shift", HrShift.class, row.get("shiftId"));
        row.put("logs", hr.many(HrAttendanceLog.class, "attendanceId", id, null, "\"timestamp\" ASC"));
        return row;
    }

    public Map<String, Object> listLogs(Map<String, String> query) {
        HrDb.Where w = hr.where(HrAttendanceLog.class).eqIf("employeeId", query.get("employeeId"))
                .eqIf("eventType", query.get("eventType"));
        List<Map<String, Object>> rows = w.list("\"timestamp\" DESC", HrDb.take(query.get("take"), 50, 200),
                HrDb.skip(query.get("skip")));
        hr.oneAll(rows, "employee", HrEmployee.class, "employeeId", EMPLOYEE_REF);
        return HrDb.rows(rows, w.count());
    }

    /** Attendance totals per active employee (window starts 30 days before {@code from}, as in Nest). */
    public Map<String, Object> summary(Map<String, String> query) {
        String orgId = org();
        ZoneId tz = timeZone();
        Instant from = HrDb.addDays(HrDb.attendanceDay(query.get("from") != null ? query.get("from") : Instant.now(), tz),
                -30);
        Instant to = HrDb.addDays(HrDb.attendanceDay(query.get("to") != null ? query.get("to") : Instant.now(), tz), 1);
        Map<String, Map<String, Object>> agg = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(hr.jdbc().sql("SELECT \"employeeId\", count(*) AS n,"
                + " sum(\"workedMinutes\") AS worked, sum(\"overtimeMinutes\") AS overtime, sum(\"lateMinutes\") AS late"
                + " FROM \"HrAttendance\" WHERE \"organizationId\" = ? AND date >= ? AND date < ? AND \"deletedAt\" IS NULL"
                + " GROUP BY \"employeeId\"").params(orgId, SqlRows.ts(from), SqlRows.ts(to)))) {
            agg.put((String) r.get("employeeId"), r);
        }
        List<Map<String, Object>> employees = hr.where(HrEmployee.class).eq("isActive", true).list(null);
        LinkedHashSet<Object> deptIds = new LinkedHashSet<>();
        employees.forEach(e -> {
            if (truthy(e.get("departmentId"))) {
                deptIds.add(e.get("departmentId"));
            }
        });
        Map<Object, Object> deptName = new HashMap<>();
        if (!deptIds.isEmpty()) {
            hr.where(HrDepartment.class).in("id", deptIds).list(null).forEach(d -> deptName.put(d.get("id"), d.get("name")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : employees) {
            Map<String, Object> a = agg.get(e.get("id"));
            out.add(Obj.of("employeeId", e.get("id"), "employeeCode", e.get("employeeCode"),
                    "name", e.get("firstName") + (truthy(e.get("lastName")) ? " " + e.get("lastName") : ""),
                    "department", deptName.get(e.get("departmentId")),
                    "daysPresent", a == null ? 0 : ((Number) a.get("n")).longValue(),
                    "workedMinutes", a == null || a.get("worked") == null ? 0 : ((Number) a.get("worked")).longValue(),
                    "overtimeMinutes", a == null || a.get("overtime") == null ? 0
                            : ((Number) a.get("overtime")).longValue(),
                    "lateMinutes", a == null || a.get("late") == null ? 0 : ((Number) a.get("late")).longValue()));
        }
        return Obj.of("from", from, "to", to, "employees", out);
    }

    /** Create or hand-edit an attendance day (audited: it changes pay). */
    public Map<String, Object> upsertManual(Map<String, Object> dto) {
        String orgId = org();
        String userId = user();
        if (!truthy(dto.get("employeeId")) || !truthy(dto.get("date"))) {
            throw ApiException.badRequest("employeeId and date are required");
        }
        Instant date = HrDb.attendanceDay(dto.get("date") instanceof String s ? s : HrDb.jsDate(dto.get("date")),
                timeZone());
        // Additive (HP-5): no edits inside a locked payroll period once payroll settings are saved.
        setup.assertDateOpen(String.valueOf(dto.get("employeeId")), date);
        Map<String, Object> existing = dayRow(dto.get("employeeId"), date);
        if (existing != null) {
            String id = (String) existing.get("id");
            Map<String, Object> data = Obj.of(
                    "checkInAt", truthy(dto.get("checkInAt")) ? HrDb.jsDate(dto.get("checkInAt")) : existing.get("checkInAt"),
                    "checkOutAt", truthy(dto.get("checkOutAt")) ? HrDb.jsDate(dto.get("checkOutAt"))
                            : existing.get("checkOutAt"),
                    "status", nz(dto.get("status"), existing.get("status")),
                    "notes", nz(dto.get("notes"), existing.get("notes")), "updatedBy", userId);
            return hr.audited(() -> hr.update(HrAttendance.class, id, data),
                    updated -> AuditInput.of("HrAttendance", id, "adjust",
                            Obj.of("checkInAt", existing.get("checkInAt"), "checkOutAt", existing.get("checkOutAt"),
                                    "status", existing.get("status")),
                            Obj.of("checkInAt", updated.get("checkInAt"), "checkOutAt", updated.get("checkOutAt"),
                                    "status", updated.get("status"), "employeeId", dto.get("employeeId"),
                                    "date", date)));
        }
        return hr.audited(() -> hr.insert(HrAttendance.class, Obj.of("organizationId", orgId,
                "employeeId", dto.get("employeeId"), "date", date,
                "checkInAt", truthy(dto.get("checkInAt")) ? HrDb.jsDate(dto.get("checkInAt")) : null,
                "checkOutAt", truthy(dto.get("checkOutAt")) ? HrDb.jsDate(dto.get("checkOutAt")) : null,
                "status", nz(dto.get("status"), "PRESENT"), "source", nz(dto.get("source"), "MANUAL"),
                "notes", dto.get("notes"), "createdBy", userId)),
                row -> AuditInput.of("HrAttendance", (String) row.get("id"), "create", null, Obj.of(
                        "employeeId", dto.get("employeeId"), "date", date, "status", row.get("status"),
                        "source", row.get("source"), "checkInAt", row.get("checkInAt"),
                        "checkOutAt", row.get("checkOutAt"))));
    }
}
