package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrDb.nz;
import static com.inventoryerp.hr.application.HrDb.org;
import static com.inventoryerp.hr.application.HrDb.truthy;
import static com.inventoryerp.hr.application.HrDb.user;

import com.inventoryerp.hr.domain.HrAttendance;
import com.inventoryerp.hr.domain.HrDepartment;
import com.inventoryerp.hr.domain.HrEmployee;
import com.inventoryerp.hr.domain.HrLeaveBalance;
import com.inventoryerp.hr.domain.HrLeaveRequest;
import com.inventoryerp.hr.domain.HrLeaveType;
import com.inventoryerp.hr.domain.HrPayrollAllowance;
import com.inventoryerp.hr.domain.HrPayrollDeduction;
import com.inventoryerp.hr.domain.HrPayrollItem;
import com.inventoryerp.hr.domain.HrPayrollPeriod;
import com.inventoryerp.hr.domain.HrPayrollRun;
import com.inventoryerp.hr.domain.HrPayslip;
import com.inventoryerp.hr.domain.HrPerformanceReview;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
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
 * Dashboard KPIs, cross-vertical registers (attendance, payroll, leave) and performance reviews (port of
 * {@code hr-reports.service.ts}).
 */
@Service
public class HrReportsService {

    private final HrDb hr;

    public HrReportsService(HrDb hr) {
        this.hr = hr;
    }

    /** Landing dashboard: headcount, today's attendance, open leave, last run. */
    public Map<String, Object> dashboard() {
        ZoneId tz = hr.timezone(org());
        Instant today = HrDb.attendanceDay(Instant.now(), tz);
        Object from = SqlRows.ts(today);
        Object to = SqlRows.ts(HrDb.addDays(today, 1));
        long headcount = hr.where(HrEmployee.class).count();
        long activeCount = hr.where(HrEmployee.class).eq("isActive", true).count();
        long todayAttendance = hr.where(HrAttendance.class).and("date >= ? AND date < ?", from, to).count();
        long present = hr.where(HrAttendance.class).and("date >= ? AND date < ?", from, to)
                .and("\"checkInAt\" IS NOT NULL").count();
        long late = hr.where(HrAttendance.class).and("date >= ? AND date < ?", from, to).eq("status", "LATE").count();
        long absent = hr.where(HrAttendance.class).and("date >= ? AND date < ?", from, to).eq("status", "ABSENT")
                .count();
        long pendingLeave = hr.where(HrLeaveRequest.class).eq("status", "PENDING").count();
        long openPeriods = hr.where(HrPayrollPeriod.class).eq("status", "OPEN").count();
        Map<String, Object> lastRun = hr.where(HrPayrollRun.class).first("\"createdAt\" DESC");
        hr.one(lastRun, "period", HrPayrollPeriod.class, lastRun == null ? null : lastRun.get("periodId"));
        long deptCount = hr.where(HrDepartment.class).count();
        return Obj.of("headcount", headcount, "activeCount", activeCount, "departments", deptCount,
                "today", Obj.of("attendanceRecords", todayAttendance, "present", present, "late", late,
                        "absent", absent),
                "pendingLeave", pendingLeave, "openPeriods", openPeriods, "lastRun", lastRun);
    }

    /** Hires per month for the last {@code months} months (server-local = UTC months). */
    public List<Map<String, Object>> headcountTrend(double months) {
        HrDb.Where w = hr.where(HrEmployee.class).and("\"hireDate\" IS NOT NULL");
        List<Map<String, Object>> rows = SqlRows.list(hr.jdbc().sql("SELECT \"hireDate\", count(*) AS n FROM"
                + " \"HrEmployee\" WHERE " + w.sql() + " GROUP BY \"hireDate\"").params(w.args()));
        List<Map<String, Object>> buckets = new ArrayList<>();
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        for (double i = months - 1; i >= 0; i--) {
            LocalDate d = now.withDayOfMonth(1).minusMonths((long) i);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("month", String.format("%d-%02d", d.getYear(), d.getMonthValue()));
            b.put("hires", 0L);
            buckets.add(b);
        }
        for (Map<String, Object> r : rows) {
            Instant hire = (Instant) r.get("hireDate");
            var h = hire.atOffset(ZoneOffset.UTC);
            String key = String.format("%d-%02d", h.getYear(), h.getMonthValue());
            for (Map<String, Object> b : buckets) {
                if (b.get("month").equals(key)) {
                    b.put("hires", (Long) b.get("hires") + ((Number) r.get("n")).longValue());
                    break;
                }
            }
        }
        return buckets;
    }

    /** Payroll register — one row per employee of a run, with allowances / deductions / payslip. */
    public Map<String, Object> payrollRegister(String runId) {
        Map<String, Object> run = hr.byId(HrPayrollRun.class, runId);
        if (run == null) {
            throw ApiException.notFound("Payroll run not found");
        }
        hr.one(run, "period", HrPayrollPeriod.class, run.get("periodId"));
        List<Map<String, Object>> items = SqlRows.list(HrPayrollItem.class, hr.jdbc().sql("SELECT i.* FROM"
                + " \"HrPayrollItem\" i LEFT JOIN \"HrEmployee\" e ON e.id = i.\"employeeId\" WHERE i.\"runId\" = ?"
                + " ORDER BY e.\"employeeCode\" ASC").param(runId));
        hr.oneAll(items, "employee", HrEmployee.class, "employeeId", "id", "firstName", "lastName", "employeeCode",
                "departmentId", "positionId");
        for (Map<String, Object> it : items) {
            it.put("allowances", hr.many(HrPayrollAllowance.class, "itemId", it.get("id"), null, null));
            it.put("deductions", hr.many(HrPayrollDeduction.class, "itemId", it.get("id"), null, null));
            it.put("payslip", hr.db().first(HrPayslip.class, "\"itemId\" = ?", it.get("id")));
        }
        run.put("items", items);
        LinkedHashSet<Object> deptIds = new LinkedHashSet<>();
        for (Map<String, Object> it : items) {
            Map<String, Object> e = PosSql.map(it.get("employee"));
            if (e != null && truthy(e.get("departmentId"))) {
                deptIds.add(e.get("departmentId"));
            }
        }
        Map<Object, Object> deptName = new HashMap<>();
        if (!deptIds.isEmpty()) {
            hr.where(HrDepartment.class).in("id", deptIds).list(null).forEach(d -> deptName.put(d.get("id"), d.get("name")));
        }
        List<Map<String, Object>> register = new ArrayList<>();
        for (Map<String, Object> it : items) {
            Map<String, Object> r = new LinkedHashMap<>(it);
            Map<String, Object> e = PosSql.map(it.get("employee"));
            r.put("department", e == null ? null : deptName.get(e.get("departmentId")));
            register.add(r);
        }
        return Obj.of("run", run, "register", register);
    }

    /** Attendance register (window starts six days before {@code from}, as in Nest). */
    public Map<String, Object> attendanceRegister(Map<String, String> query) {
        ZoneId tz = hr.timezone(org());
        Instant from = HrDb.addDays(HrDb.attendanceDay(query.get("from") != null ? query.get("from") : Instant.now(), tz),
                -6);
        Instant to = HrDb.addDays(HrDb.attendanceDay(query.get("to") != null ? query.get("to") : Instant.now(), tz), 1);
        HrDb.Where w = hr.where(HrAttendance.class).and("date >= ? AND date < ?", SqlRows.ts(from), SqlRows.ts(to))
                .eqIf("employeeId", query.get("employeeId"));
        List<Map<String, Object>> rows = SqlRows.list(HrAttendance.class, hr.jdbc().sql("SELECT \"HrAttendance\".* FROM"
                + " \"HrAttendance\" LEFT JOIN \"HrEmployee\" e ON e.id = \"HrAttendance\".\"employeeId\" WHERE "
                + w.sql().replace("\"organizationId\"", "\"HrAttendance\".\"organizationId\"")
                        .replace("\"deletedAt\"", "\"HrAttendance\".\"deletedAt\"")
                        .replace("\"employeeId\"::text", "\"HrAttendance\".\"employeeId\"::text")
                + " ORDER BY \"HrAttendance\".date ASC, e.\"employeeCode\" ASC").params(w.args()));
        hr.oneAll(rows, "employee", HrEmployee.class, "employeeId", "id", "firstName", "lastName", "employeeCode",
                "departmentId");
        return Obj.of("from", from, "to", to, "rows", rows);
    }

    /** Leave balances per employee per type for a year. */
    public Map<String, Object> leaveOverview(Map<String, String> query) {
        double y = query.get("year") != null ? HrDb.jsNumber(query.get("year")) : LocalDate.now(ZoneOffset.UTC).getYear();
        if (Double.isNaN(y) || y != Math.rint(y)) {
            throw new IllegalStateException("Invalid value for argument `year`: Expected Int.");
        }
        int year = (int) y;
        HrDb.Where w = hr.where(HrLeaveBalance.class).and("year = ?", year).eqIf("employeeId", query.get("employeeId"));
        String where = w.sql().replace("\"organizationId\"", "b.\"organizationId\"")
                .replace("\"deletedAt\"", "b.\"deletedAt\"").replace("\"employeeId\"::text", "b.\"employeeId\"::text")
                .replace("(year = ?)", "(b.year = ?)");
        List<Map<String, Object>> balances = SqlRows.list(HrLeaveBalance.class, hr.jdbc().sql("SELECT b.* FROM"
                + " \"HrLeaveBalance\" b LEFT JOIN \"HrEmployee\" e ON e.id = b.\"employeeId\" LEFT JOIN \"HrLeaveType\" t"
                + " ON t.id = b.\"leaveTypeId\" WHERE " + where + " ORDER BY e.\"employeeCode\" ASC, t.name ASC")
                .params(w.args()));
        hr.oneAll(balances, "employee", HrEmployee.class, "employeeId", HrAttendanceService.EMPLOYEE_REF);
        hr.oneAll(balances, "leaveType", HrLeaveType.class, "leaveTypeId");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> b : balances) {
            Map<String, Object> e = PosSql.map(b.get("employee"));
            Map<String, Object> t = PosSql.map(b.get("leaveType"));
            rows.add(Obj.of("employeeId", b.get("employeeId"), "employeeCode", e.get("employeeCode"),
                    "name", e.get("firstName") + (truthy(e.get("lastName")) ? " " + e.get("lastName") : ""),
                    "leaveType", t.get("name"), "accrued", b.get("accruedDays"), "adjusted", b.get("adjustedDays"),
                    "used", b.get("usedDays"), "remaining", PosSql.js(PosSql.num(b.get("accruedDays"))
                            + PosSql.num(b.get("adjustedDays")) - PosSql.num(b.get("usedDays")))));
        }
        return Obj.of("year", year, "rows", rows);
    }

    // ─── performance reviews ───

    public Map<String, Object> listReviews(Map<String, String> query) {
        HrDb.Where w = hr.where(HrPerformanceReview.class).eqIf("employeeId", query.get("employeeId"));
        List<Map<String, Object>> rows = w.list("\"reviewDate\" DESC", HrDb.take(query.get("take"), 50, 200),
                HrDb.skip(query.get("skip")));
        hr.oneAll(rows, "employee", HrEmployee.class, "employeeId", HrAttendanceService.EMPLOYEE_REF);
        return HrDb.rows(rows, w.count());
    }

    static final List<String> REVIEW_FIELDS = List.of("reviewDate", "periodStart", "periodEnd", "attendanceRate",
            "lateRate", "absenteeismDays", "jobsCompleted", "billableHours", "overtimeHours", "kpiScore", "rating", "notes",
            "status");

    public Map<String, Object> createReview(Map<String, Object> dto) {
        if (!truthy(dto.get("employeeId"))) {
            throw ApiException.notFound("employeeId is required");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("organizationId", org());
        data.put("employeeId", dto.get("employeeId"));
        data.put("reviewDate", truthy(dto.get("reviewDate")) ? HrDb.jsDate(dto.get("reviewDate")) : PosDb.raw(PosSql.NOW));
        data.put("periodStart", truthy(dto.get("periodStart")) ? HrDb.jsDate(dto.get("periodStart")) : null);
        data.put("periodEnd", truthy(dto.get("periodEnd")) ? HrDb.jsDate(dto.get("periodEnd")) : null);
        for (String f : REVIEW_FIELDS.subList(3, 12)) {
            data.put(f, dto.get(f));
        }
        data.put("status", nz(dto.get("status"), "DRAFT"));
        data.put("reviewerId", user());
        data.put("createdBy", user());
        return hr.insert(HrPerformanceReview.class, data);
    }

    public Map<String, Object> updateReview(String id, Map<String, Object> dto) {
        if (hr.byId(HrPerformanceReview.class, id) == null) {
            throw ApiException.notFound("Review not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (String f : REVIEW_FIELDS) {
            if (dto.containsKey(f)) {
                boolean date = f.startsWith("period") || "reviewDate".equals(f);
                data.put(f, date ? (truthy(dto.get(f)) ? HrDb.jsDate(dto.get(f)) : null) : dto.get(f));
            }
        }
        data.put("updatedBy", user());
        return hr.update(HrPerformanceReview.class, id, data);
    }

    public Map<String, Object> deleteReview(String id) {
        if (hr.byId(HrPerformanceReview.class, id) == null) {
            throw ApiException.notFound("Review not found");
        }
        return hr.softDelete(HrPerformanceReview.class, id, Obj.of("updatedBy", user()));
    }
}
