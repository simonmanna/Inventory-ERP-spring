package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrRows.dec;
import static com.inventoryerp.hr.application.HrRows.org;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Workforce and payroll reports (HP-10, ADR-S048): payroll register (and CSV), payroll summary by period, payroll cost
 * by department, headcount / hires / leavers / turnover by month, salary change audit, statutory totals per year and
 * leave liability. All figures come from approved (or paid) runs and reconcile to the payroll journals.
 */
@Service
public class HrWorkforceReportsService {

    private final HrRows rows;
    private final HrPayrollEngineV2 engine;
    private final HrLeavePolicyService leave;

    public HrWorkforceReportsService(HrRows rows, HrPayrollEngineV2 engine, HrLeavePolicyService leave) {
        this.rows = rows;
        this.engine = engine;
        this.leave = leave;
    }

    /** Employees × pay codes of one run, with totals. */
    public Map<String, Object> register(String runId) {
        Map<String, Object> run = engine.requireRun(runId);
        List<Map<String, Object>> items = engine.itemRows(runId);
        Set<String> codes = new LinkedHashSet<>();
        Map<String, Map<String, BigDecimal>> byEmp = new LinkedHashMap<>();
        List<Map<String, Object>> lines = rows.query("SELECT \"employeeId\", code, name, kind, amount, \"sortOrder\" FROM"
                + " \"HrPayrollResultLine\" WHERE \"runId\" = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL AND"
                + " kind <> 'INFO' ORDER BY \"sortOrder\", code", runId, org());
        for (Map<String, Object> l : lines) {
            String col = l.get("kind").equals("EMPLOYER_CONTRIBUTION") ? l.get("code") + " (ER)" : (String) l.get("code");
            codes.add(col);
            byEmp.computeIfAbsent((String) l.get("employeeId"), k -> new LinkedHashMap<>()).merge(col,
                    dec(l.get("amount")), BigDecimal::add);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (Map<String, Object> i : items) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("employeeId", i.get("employeeId"));
            r.put("employeeCode", i.get("employeeCode"));
            r.put("name", i.get("firstName") + (i.get("lastName") == null ? "" : " " + i.get("lastName")));
            Map<String, BigDecimal> amounts = byEmp.getOrDefault(i.get("employeeId"), Map.of());
            for (String c : codes) {
                BigDecimal v = amounts.getOrDefault(c, BigDecimal.ZERO);
                r.put(c, v);
                totals.merge(c, v, BigDecimal::add);
            }
            for (String f : List.of("grossPay", "totalDeductions", "netPay")) {
                r.put(f, dec(i.get(f)));
                totals.merge(f, dec(i.get(f)), BigDecimal::add);
            }
            out.add(r);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("run", run);
        res.put("columns", new ArrayList<>(codes));
        res.put("rows", out);
        res.put("totals", totals);
        return res;
    }

    public HrPayrollOpsService.FileOut registerCsv(String runId) {
        Map<String, Object> reg = register(runId);
        @SuppressWarnings("unchecked")
        List<String> cols = (List<String>) reg.get("columns");
        StringBuilder sb = new StringBuilder("employeeCode,name");
        cols.forEach(c -> sb.append(',').append(c));
        sb.append(",grossPay,totalDeductions,netPay\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) reg.get("rows");
        for (Map<String, Object> r : list) {
            sb.append(r.get("employeeCode")).append(",\"").append(String.valueOf(r.get("name")).replace("\"", "\"\""))
                    .append('"');
            cols.forEach(c -> sb.append(',').append(((BigDecimal) r.get(c)).toPlainString()));
            sb.append(',').append(((BigDecimal) r.get("grossPay")).toPlainString()).append(',')
                    .append(((BigDecimal) r.get("totalDeductions")).toPlainString()).append(',')
                    .append(((BigDecimal) r.get("netPay")).toPlainString()).append('\n');
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> run = (Map<String, Object>) reg.get("run");
        return new HrPayrollOpsService.FileOut("register-" + run.get("runNumber") + ".csv", "text/csv",
                sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Approved runs between two dates (period end): gross, deductions, net, employer cost, headcount. */
    public Map<String, Object> payrollSummary(Map<String, String> q) {
        LocalDate[] r = range(q);
        List<Map<String, Object>> list = rows.query("SELECT p.\"periodCode\", p.\"startDate\", p.\"endDate\", r.id AS"
                + " \"runId\", r.\"runNumber\", r.status, count(i.id) AS headcount, coalesce(sum(i.\"grossPay\"), 0) AS"
                + " gross, coalesce(sum(i.\"totalDeductions\"), 0) AS deductions, coalesce(sum(i.\"netPay\"), 0) AS net,"
                + " coalesce(sum(i.\"taxAmount\"), 0) AS tax, (SELECT coalesce(sum(l.amount), 0) FROM"
                + " \"HrPayrollResultLine\" l WHERE l.\"runId\" = r.id AND l.kind = 'EMPLOYER_CONTRIBUTION' AND"
                + " l.\"deletedAt\" IS NULL) AS employer FROM \"HrPayrollRun\" r JOIN \"HrPayrollPeriod\" p ON p.id ="
                + " r.\"periodId\" LEFT JOIN \"HrPayrollItem\" i ON i.\"runId\" = r.id AND i.\"deletedAt\" IS NULL WHERE"
                + " r.\"organizationId\" = ? AND r.\"deletedAt\" IS NULL AND r.status::text IN ('APPROVED', 'PAID') AND"
                + " p.\"endDate\" >= ? AND p.\"endDate\" < ? GROUP BY p.id, r.id ORDER BY p.\"startDate\"", org(),
                ts(r[0]), ts(r[1].plusDays(1)));
        for (Map<String, Object> m : list) {
            m.put("totalCost", dec(m.get("gross")).add(dec(m.get("employer"))));
        }
        return HrRows.page(list, list.size());
    }

    /** Payroll cost (gross + employer contributions) per department between two dates. */
    public Map<String, Object> costByDepartment(Map<String, String> q) {
        LocalDate[] r = range(q);
        List<Map<String, Object>> list = rows.query("SELECT d.id AS \"departmentId\", coalesce(d.name, 'Unassigned') AS"
                + " department, count(DISTINCT i.\"employeeId\") AS employees, coalesce(sum(i.\"grossPay\"), 0) AS gross,"
                + " coalesce(sum((SELECT coalesce(sum(l.amount), 0) FROM \"HrPayrollResultLine\" l WHERE l.\"itemId\" ="
                + " i.id AND l.kind = 'EMPLOYER_CONTRIBUTION' AND l.\"deletedAt\" IS NULL)), 0) AS employer FROM"
                + " \"HrPayrollItem\" i JOIN \"HrPayrollRun\" r ON r.id = i.\"runId\" JOIN \"HrPayrollPeriod\" p ON p.id ="
                + " r.\"periodId\" JOIN \"HrEmployee\" e ON e.id = i.\"employeeId\" LEFT JOIN \"HrDepartment\" d ON d.id ="
                + " e.\"departmentId\" WHERE i.\"organizationId\" = ? AND i.\"deletedAt\" IS NULL AND r.status::text IN"
                + " ('APPROVED', 'PAID') AND p.\"endDate\" >= ? AND p.\"endDate\" < ? GROUP BY d.id, d.name ORDER BY 4 DESC",
                org(), ts(r[0]), ts(r[1].plusDays(1)));
        for (Map<String, Object> m : list) {
            m.put("totalCost", dec(m.get("gross")).add(dec(m.get("employer"))));
        }
        return HrRows.page(list, list.size());
    }

    /** Month by month: headcount at month end, hires, leavers and turnover % (leavers ÷ average headcount). */
    public Map<String, Object> headcount(Map<String, String> q) {
        LocalDate[] r = range(q);
        List<Map<String, Object>> out = new ArrayList<>();
        for (YearMonth m = YearMonth.from(r[0]); !m.isAfter(YearMonth.from(r[1])); m = m.plusMonths(1)) {
            LocalDate s = m.atDay(1);
            LocalDate e = m.atEndOfMonth();
            Map<String, Object> row = rows.queryOne("SELECT"
                    + " count(*) FILTER (WHERE (\"hireDate\" IS NULL OR \"hireDate\" < ?) AND (\"terminationDate\" IS NULL"
                    + " OR \"terminationDate\" >= ?)) AS opening,"
                    + " count(*) FILTER (WHERE (\"hireDate\" IS NULL OR \"hireDate\" < ?) AND (\"terminationDate\" IS NULL"
                    + " OR \"terminationDate\" >= ?)) AS closing,"
                    + " count(*) FILTER (WHERE \"hireDate\" >= ? AND \"hireDate\" < ?) AS hires,"
                    + " count(*) FILTER (WHERE \"terminationDate\" >= ? AND \"terminationDate\" < ?) AS leavers"
                    + " FROM \"HrEmployee\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL",
                    ts(s), ts(s), ts(e.plusDays(1)), ts(e.plusDays(1)), ts(s), ts(e.plusDays(1)), ts(s), ts(e.plusDays(1)),
                    org());
            BigDecimal avg = BigDecimal.valueOf(((Number) row.get("opening")).longValue()
                    + ((Number) row.get("closing")).longValue()).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
            BigDecimal leavers = BigDecimal.valueOf(((Number) row.get("leavers")).longValue());
            row.put("month", m.toString());
            row.put("turnoverPct", avg.signum() == 0 ? BigDecimal.ZERO
                    : leavers.multiply(BigDecimal.valueOf(100)).divide(avg, 2, RoundingMode.HALF_UP));
            out.add(row);
        }
        return HrRows.page(out, out.size());
    }

    /** Approved salary revisions between two dates (effective date) with the change in %. */
    public Map<String, Object> salaryChanges(Map<String, String> q) {
        LocalDate[] r = range(q);
        List<Map<String, Object>> list = rows.query("SELECT c.*, e.\"employeeCode\", e.\"firstName\", e.\"lastName\" FROM"
                + " \"HrEmployeeCompensation\" c JOIN \"HrEmployee\" e ON e.id = c.\"employeeId\" WHERE c.\"organizationId\""
                + " = ? AND c.\"deletedAt\" IS NULL AND c.\"approvedAt\" IS NOT NULL AND c.\"effectiveFrom\" BETWEEN ? AND ?"
                + " ORDER BY c.\"effectiveFrom\" DESC", org(), r[0], r[1]);
        for (Map<String, Object> m : list) {
            BigDecimal prev = dec(m.get("previousBasic"));
            BigDecimal now = dec(m.get("basicSalary"));
            m.put("changePct", prev == null || prev.signum() == 0 ? null
                    : now.subtract(prev).multiply(BigDecimal.valueOf(100)).divide(prev, 2, RoundingMode.HALF_UP));
        }
        return HrRows.page(list, list.size());
    }

    /** Statutory totals by code and month of a year (employee and employer). */
    public Map<String, Object> statutory(Map<String, String> q) {
        int year = q.get("year") == null ? LocalDate.now().getYear() : Integer.parseInt(q.get("year"));
        List<Map<String, Object>> list = rows.query("SELECT l.\"statutoryCode\", to_char(p.\"endDate\", 'YYYY-MM') AS month,"
                + " coalesce(sum(l.amount) FILTER (WHERE l.kind = 'DEDUCTION'), 0) AS employee,"
                + " coalesce(sum(l.amount) FILTER (WHERE l.kind = 'EMPLOYER_CONTRIBUTION'), 0) AS employer FROM"
                + " \"HrPayrollResultLine\" l JOIN \"HrPayrollRun\" r ON r.id = l.\"runId\" JOIN \"HrPayrollPeriod\" p ON"
                + " p.id = r.\"periodId\" WHERE l.\"organizationId\" = ? AND l.\"deletedAt\" IS NULL AND r.status::text IN"
                + " ('APPROVED', 'PAID') AND l.\"statutoryCode\" IS NOT NULL AND extract(year FROM p.\"endDate\") = ? GROUP"
                + " BY 1, 2 ORDER BY 1, 2", org(), year);
        return HrRows.page(list, list.size());
    }

    /** Leave liability: remaining paid-leave days × basic ÷ 30 per employee for a year. */
    public Map<String, Object> leaveLiability(Map<String, String> q) {
        int year = q.get("year") == null ? LocalDate.now().getYear() : Integer.parseInt(q.get("year"));
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> b : rows.query("SELECT b.\"employeeId\", e.\"employeeCode\", e.\"firstName\", e.\"lastName\","
                + " sum(b.\"accruedDays\" + b.\"adjustedDays\" - b.\"usedDays\") AS days FROM \"HrLeaveBalance\" b JOIN"
                + " \"HrLeaveType\" t ON t.id = b.\"leaveTypeId\" JOIN \"HrEmployee\" e ON e.id = b.\"employeeId\" WHERE"
                + " b.\"organizationId\" = ? AND b.year = ? AND b.\"deletedAt\" IS NULL AND t.\"isPaid\" = true AND"
                + " e.\"isActive\" = true GROUP BY 1, 2, 3, 4 HAVING sum(b.\"accruedDays\" + b.\"adjustedDays\" -"
                + " b.\"usedDays\") > 0 ORDER BY 2", org(), year)) {
            BigDecimal days = dec(b.get("days"));
            BigDecimal amount = leave.dailyRate((String) b.get("employeeId")).multiply(days)
                    .setScale(2, RoundingMode.HALF_UP);
            b.put("amount", amount);
            total = total.add(amount);
            out.add(b);
        }
        Map<String, Object> res = HrRows.page(out, out.size());
        res.put("liability", total);
        return res;
    }

    private static LocalDate[] range(Map<String, String> q) {
        LocalDate from = HrRows.date(q.get("from"));
        LocalDate to = HrRows.date(q.get("to"));
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.badRequest("from and to are required, to not before from");
        }
        if (from.plusYears(5).isBefore(to)) {
            throw ApiException.badRequest("The range cannot exceed five years");
        }
        return new LocalDate[] {from, to};
    }

    private static java.sql.Timestamp ts(LocalDate d) {
        return SqlRows.ts(HrPayrollEngineV2.utc(d));
    }
}
