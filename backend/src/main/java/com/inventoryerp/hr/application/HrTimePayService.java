package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrRows.org;
import static com.inventoryerp.hr.application.HrRows.user;

import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Time to pay (HP-5, ADR-S048): the pre-payroll review of a period (hours, overtime by approval state, absences,
 * lateness, unpaid leave per employee) and overtime approval. With {@code requireOvertimeApproval} engine v2 pays only
 * approved overtime; locked periods refuse attendance edits ({@link HrPayrollSetupService#assertDateOpen}).
 */
@Service
public class HrTimePayService {

    static final MathContext MC = MathContext.DECIMAL64;

    private final HrRows rows;
    private final HrDb hr;
    private final HrPayrollSetupService setup;
    private final HrPayrollEngineV2 engine;
    private final HrLeavePolicyService leave;
    private final HrOvertimeService overtime;

    public HrTimePayService(HrRows rows, HrDb hr, HrPayrollSetupService setup, HrPayrollEngineV2 engine,
            HrLeavePolicyService leave, HrOvertimeService overtime) {
        this.rows = rows;
        this.hr = hr;
        this.setup = setup;
        this.engine = engine;
        this.leave = leave;
        this.overtime = overtime;
    }

    /** Per employee of the period's pay group: worked hours, overtime (approved / pending / rejected), absences. */
    public Map<String, Object> periodSummary(String periodId) {
        Map<String, Object> period = rows.queryOne("SELECT * FROM \"HrPayrollPeriod\" WHERE id = ? AND \"organizationId\""
                + " = ? AND \"deletedAt\" IS NULL", periodId, org());
        if (period == null) {
            throw ApiException.notFound("Payroll period not found");
        }
        LocalDate start = HrRows.date(period.get("startDate"));
        LocalDate end = HrRows.date(period.get("endDate"));
        Set<String> members = setup.periodEmployees(periodId);
        Set<DayOfWeek> weekend = leave.weekend();
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal pendingTotal = BigDecimal.ZERO;
        for (Map<String, Object> a : rows.query("SELECT e.id, e.\"employeeCode\", e.\"firstName\", e.\"lastName\","
                + " coalesce(sum(a.\"workedMinutes\"), 0) AS worked, coalesce(sum(a.\"overtimeMinutes\"), 0) AS ot,"
                + " coalesce(sum(a.\"overtimeMinutes\") FILTER (WHERE a.\"overtimeApproved\" = true), 0) AS ot_ok,"
                + " coalesce(sum(a.\"overtimeMinutes\") FILTER (WHERE a.\"overtimeApproved\" = false), 0) AS ot_no,"
                + " coalesce(sum(a.\"lateMinutes\"), 0) AS late, count(*) FILTER (WHERE a.status = 'ABSENT') AS absent,"
                + " count(a.id) AS days FROM \"HrEmployee\" e LEFT JOIN \"HrAttendance\" a ON a.\"employeeId\" = e.id AND"
                + " a.\"deletedAt\" IS NULL AND a.date >= ? AND a.date < ? WHERE e.\"organizationId\" = ? AND"
                + " e.\"isActive\" = true AND e.\"deletedAt\" IS NULL GROUP BY e.id ORDER BY e.\"employeeCode\"",
                SqlRows.ts(HrPayrollEngineV2.utc(start)), SqlRows.ts(HrPayrollEngineV2.utc(end.plusDays(1))), org())) {
            if (members != null && !members.contains(a.get("id"))) {
                continue;
            }
            BigDecimal ot = new BigDecimal(String.valueOf(a.get("ot")));
            BigDecimal ok = new BigDecimal(String.valueOf(a.get("ot_ok")));
            BigDecimal no = new BigDecimal(String.valueOf(a.get("ot_no")));
            BigDecimal pending = ot.subtract(ok).subtract(no);
            pendingTotal = pendingTotal.add(pending);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("employeeId", a.get("id"));
            r.put("employeeCode", a.get("employeeCode"));
            r.put("name", a.get("firstName") + (a.get("lastName") == null ? "" : " " + a.get("lastName")));
            r.put("attendanceDays", a.get("days"));
            r.put("workedHours", hours(a.get("worked")));
            r.put("overtimeHours", hours(ot));
            r.put("overtimeApprovedHours", hours(ok));
            r.put("overtimeRejectedHours", hours(no));
            r.put("overtimePendingHours", hours(pending));
            r.put("lateMinutes", a.get("late"));
            r.put("absentDays", a.get("absent"));
            r.put("unpaidLeaveDays", engine.unpaidLeaveDays((String) a.get("id"), start, end, weekend,
                    overtime.holidays()));
            out.add(r);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("periodId", periodId);
        res.put("periodCode", period.get("periodCode"));
        res.put("status", period.get("status"));
        res.put("requireOvertimeApproval", setup.effectiveSettings().get("requireOvertimeApproval"));
        res.put("overtimePendingHours", hours(pendingTotal));
        res.put("rows", out);
        return res;
    }

    private static BigDecimal hours(Object minutes) {
        return new BigDecimal(String.valueOf(minutes)).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
    }

    /** Attendance rows with overtime in a date range; {@code status}: pending (default), approved, rejected, all. */
    public Map<String, Object> overtime(Map<String, String> q) {
        LocalDate from = HrRows.date(q.get("from"));
        LocalDate to = HrRows.date(q.get("to"));
        if (from == null || to == null) {
            throw ApiException.badRequest("from and to are required");
        }
        String status = q.getOrDefault("status", "pending");
        String filter = switch (status) {
            case "approved" -> " AND a.\"overtimeApproved\" = true";
            case "rejected" -> " AND a.\"overtimeApproved\" = false";
            case "all" -> "";
            default -> " AND a.\"overtimeApproved\" IS NULL";
        };
        List<Map<String, Object>> list = rows.query("SELECT a.id, a.\"employeeId\", e.\"employeeCode\", e.\"firstName\","
                + " e.\"lastName\", a.date, a.\"workedMinutes\", a.\"overtimeMinutes\", a.\"overtimeApproved\","
                + " a.\"overtimeReviewedAt\" FROM \"HrAttendance\" a JOIN \"HrEmployee\" e ON e.id = a.\"employeeId\" WHERE"
                + " a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL AND a.\"overtimeMinutes\" > 0 AND a.date >= ? AND"
                + " a.date < ?" + filter + " ORDER BY a.date, e.\"employeeCode\" LIMIT 1000", org(),
                SqlRows.ts(HrPayrollEngineV2.utc(from)), SqlRows.ts(HrPayrollEngineV2.utc(to.plusDays(1))));
        return HrRows.page(list, list.size());
    }

    /** Approve or reject overtime on attendance rows; refused for days in a locked period. */
    public Map<String, Object> reviewOvertime(Map<String, Object> dto) {
        List<Object> ids = PosSql.list(dto.get("attendanceIds"));
        if (ids.isEmpty() || !(dto.get("approved") instanceof Boolean approved)) {
            throw ApiException.badRequest("attendanceIds and approved (boolean) are required");
        }
        return hr.inTx(() -> {
            int n = 0;
            for (Object id : ids) {
                Map<String, Object> a = rows.queryOne("SELECT id, \"employeeId\", date FROM \"HrAttendance\" WHERE id = ?"
                        + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", String.valueOf(id), org());
                if (a == null) {
                    throw ApiException.notFound("Attendance not found");
                }
                setup.assertDateOpen((String) a.get("employeeId"), (java.time.Instant) a.get("date"));
                n += rows.jdbc().sql("UPDATE \"HrAttendance\" SET \"overtimeApproved\" = ?, \"overtimeReviewedBy\" = ?,"
                        + " \"overtimeReviewedAt\" = CURRENT_TIMESTAMP WHERE id = ? AND \"organizationId\" = ?")
                        .params(approved, user(), a.get("id"), org()).update();
            }
            hr.auditInTx(AuditInput.of("HrAttendance", String.valueOf(ids.getFirst()), approved ? "approve" : "reject",
                    null, Map.of("attendanceIds", ids, "overtimeApproved", approved)));
            return Map.of("updated", n, "approved", approved);
        });
    }
}
