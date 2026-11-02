package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrRows.dec;
import static com.inventoryerp.hr.application.HrRows.org;
import static com.inventoryerp.hr.application.HrRows.str;

import com.inventoryerp.hr.domain.HrLeaveBalance;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

/**
 * Leave policies (HP-4, ADR-S048): monthly or up-front accrual with pro-rata and tenure tiers, year-end carry-forward
 * with a cap and an expiry, encashment through payroll, working-day counting and the team calendar. With a policy the
 * accrued days of a balance are the sum of its ACCRUAL ledger entries; carry-forward, expiry and encashment move the
 * adjusted days. Leave types without a policy keep the Nest behaviour.
 */
@Service
public class HrLeavePolicyService {

    static final MathContext MC = MathContext.DECIMAL64;

    private final HrRows rows;
    private final HrDb hr;
    private final HrPayrollSetupService setup;
    private final HrOvertimeService overtime;
    private final HrCompensationService compensation;
    private final HrPayrollInputService inputs;

    public HrLeavePolicyService(HrRows rows, HrDb hr, HrPayrollSetupService setup, HrOvertimeService overtime,
            HrCompensationService compensation, HrPayrollInputService inputs) {
        this.rows = rows;
        this.hr = hr;
        this.setup = setup;
        this.overtime = overtime;
        this.compensation = compensation;
        this.inputs = inputs;
    }

    // ------------------------------------------------------------------ policies

    public Map<String, Object> listPolicies() {
        List<Map<String, Object>> list = rows.query("SELECT p.*, t.code AS \"leaveTypeCode\", t.name AS \"leaveTypeName\""
                + " FROM \"HrLeavePolicy\" p JOIN \"HrLeaveType\" t ON t.id = p.\"leaveTypeId\" WHERE p.\"organizationId\""
                + " = ? AND p.\"deletedAt\" IS NULL ORDER BY t.code", org());
        return HrRows.page(list, list.size());
    }

    public Map<String, Object> savePolicy(String id, Map<String, Object> dto) {
        Map<String, Object> before = id == null ? null : rows.require("HrLeavePolicy", id, "Leave policy not found");
        Map<String, Object> data = new LinkedHashMap<>();
        if (before == null) {
            String typeId = str(dto.get("leaveTypeId"));
            if (typeId == null || rows.queryOne("SELECT id FROM \"HrLeaveType\" WHERE id = ? AND \"organizationId\" = ?"
                    + " AND \"deletedAt\" IS NULL", typeId, org()) == null) {
                throw ApiException.badRequest("leaveTypeId must be an existing leave type");
            }
            if (rows.first("HrLeavePolicy", "\"leaveTypeId\" = ?", null, typeId) != null) {
                throw ApiException.badRequest("This leave type already has a policy");
            }
            data.put("leaveTypeId", typeId);
        }
        if (dto.containsKey("accrualMethod") || before == null) {
            Object m = dto.getOrDefault("accrualMethod", "MONTHLY");
            HrRows.oneOf("accrualMethod", m, List.of("ANNUAL_UPFRONT", "MONTHLY"));
            data.put("accrualMethod", m);
        }
        if (dto.containsKey("daysPerYear") || before == null) {
            BigDecimal d = dec(dto.get("daysPerYear"));
            if (d == null || d.signum() < 0 || d.compareTo(BigDecimal.valueOf(366)) > 0) {
                throw ApiException.badRequest("daysPerYear must be from 0 to 366");
            }
            data.put("daysPerYear", d);
        }
        if (dto.containsKey("tenureTiers")) {
            List<Map<String, Object>> tiers = new ArrayList<>();
            for (Object o : PosSql.list(dto.get("tenureTiers"))) {
                Map<String, Object> t = PosSql.map(o);
                BigDecimal years = dec(t.get("years"));
                BigDecimal days = dec(t.get("daysPerYear"));
                if (years == null || days == null || years.signum() < 0 || days.signum() < 0) {
                    throw ApiException.badRequest("Each tenure tier needs years and daysPerYear");
                }
                tiers.add(Map.of("years", years, "daysPerYear", days));
            }
            tiers.sort((a, b) -> ((BigDecimal) a.get("years")).compareTo((BigDecimal) b.get("years")));
            data.put("tenureTiers", tiers);
        }
        for (String f : List.of("carryForwardMax", "maxEncashDays")) {
            if (dto.containsKey(f)) {
                BigDecimal d = dec(dto.get(f));
                if (d != null && d.signum() < 0) {
                    throw ApiException.badRequest(f + " cannot be negative");
                }
                data.put(f, "carryForwardMax".equals(f) && d == null ? BigDecimal.ZERO : d);
            }
        }
        for (String f : List.of("minServiceMonths", "carryForwardExpiryMonths")) {
            if (dto.containsKey(f)) {
                Object v = dto.get(f);
                if (!(v instanceof Number n) || n.intValue() < 0 || n.intValue() > 120) {
                    throw ApiException.badRequest(f + " must be a whole number from 0 to 120");
                }
                data.put(f, n.intValue());
            }
        }
        for (String f : List.of("proRataOnHire", "encashable", "countWeekends", "countHolidays", "allowHalfDay",
                "isActive")) {
            if (dto.containsKey(f)) {
                data.put(f, Boolean.TRUE.equals(dto.get(f)));
            }
        }
        return hr.inTx(() -> {
            Map<String, Object> row = before == null ? rows.insert("HrLeavePolicy", data)
                    : rows.update("HrLeavePolicy", id, data);
            hr.auditInTx(AuditInput.of("HrLeavePolicy", (String) row.get("id"), before == null ? "create" : "update",
                    before, row));
            return row;
        });
    }

    public Map<String, Object> deletePolicy(String id) {
        rows.require("HrLeavePolicy", id, "Leave policy not found");
        rows.softDelete("HrLeavePolicy", id);
        return Map.of("id", id, "deleted", true);
    }

    /** Days per year for an employee on {@code on}: the highest tenure tier reached, else the policy days. */
    static BigDecimal entitlement(Map<String, Object> policy, LocalDate hire, LocalDate on) {
        BigDecimal days = dec(policy.get("daysPerYear"));
        if (hire == null) {
            return days;
        }
        BigDecimal years = BigDecimal.valueOf(ChronoUnit.MONTHS.between(hire, on)).divide(BigDecimal.valueOf(12), MC);
        for (Object o : PosSql.list(policy.get("tenureTiers"))) {
            Map<String, Object> t = PosSql.map(o);
            if (years.compareTo(dec(t.get("years"))) >= 0) {
                days = dec(t.get("daysPerYear"));
            }
        }
        return days;
    }

    // ------------------------------------------------------------------ accrual

    /**
     * Accrue every active policy up to {@code asOf}: MONTHLY = 1/12 of the entitlement per completed month (the hire
     * month pro rata), ANNUAL_UPFRONT = the year's entitlement on 1 January (pro rata in the hire year). Idempotent per
     * month / year. Also expires carried-forward days past their expiry.
     */
    public Map<String, Object> accrue(LocalDate asOf) {
        int entries = 0;
        int expired = 0;
        for (Map<String, Object> p : rows.list("HrLeavePolicy", "\"isActive\" = true", null)) {
            for (Map<String, Object> e : activeEmployees()) {
                LocalDate hire = HrRows.date(e.get("hireDate"));
                LocalDate eligible = hire == null ? null : hire.plusMonths(PosSql.intOf(p.get("minServiceMonths")));
                if ("ANNUAL_UPFRONT".equals(p.get("accrualMethod"))) {
                    int year = asOf.getYear();
                    LocalDate yStart = LocalDate.of(year, 1, 1);
                    if (eligible != null && eligible.isAfter(asOf)) {
                        continue;
                    }
                    BigDecimal days = entitlement(p, hire, yStart.plusYears(1).minusDays(1));
                    if (hire != null && hire.getYear() == year && Boolean.TRUE.equals(p.get("proRataOnHire"))) {
                        days = days.multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(hire, yStart.plusYears(1))), MC)
                                .divide(BigDecimal.valueOf(yStart.lengthOfYear()), MC);
                    }
                    entries += post(e, p, year, "ACCRUAL", round(days), String.valueOf(year), yStart, "Annual entitlement");
                } else {
                    YearMonth from = YearMonth.of(asOf.getYear(), 1);
                    if (hire != null && YearMonth.from(hire).isAfter(from)) {
                        from = YearMonth.from(hire);
                    }
                    for (YearMonth m = from; !m.atEndOfMonth().isAfter(asOf); m = m.plusMonths(1)) {
                        if (eligible != null && eligible.isAfter(m.atEndOfMonth())) {
                            continue;
                        }
                        BigDecimal days = entitlement(p, hire, m.atEndOfMonth()).divide(BigDecimal.valueOf(12), MC);
                        if (hire != null && YearMonth.from(hire).equals(m) && hire.getDayOfMonth() > 1
                                && Boolean.TRUE.equals(p.get("proRataOnHire"))) {
                            days = days.multiply(BigDecimal.valueOf(m.lengthOfMonth() - hire.getDayOfMonth() + 1), MC)
                                    .divide(BigDecimal.valueOf(m.lengthOfMonth()), MC);
                        }
                        entries += post(e, p, m.getYear(), "ACCRUAL", round(days), m.toString(), m.atEndOfMonth(),
                                "Monthly accrual " + m);
                    }
                }
                expired += expireCarry(e, p, asOf);
            }
        }
        return Map.of("asOf", asOf.toString(), "entries", entries, "expired", expired);
    }

    /** Year end: carry the unused balance up to the cap into next year; the rest lapses. Idempotent per year. */
    public Map<String, Object> yearEnd(int year) {
        int carried = 0;
        for (Map<String, Object> p : rows.list("HrLeavePolicy", "\"isActive\" = true", null)) {
            BigDecimal cap = HrRows.decOr(p.get("carryForwardMax"), BigDecimal.ZERO);
            for (Map<String, Object> b : rows.query("SELECT * FROM \"HrLeaveBalance\" WHERE \"organizationId\" = ? AND"
                    + " \"leaveTypeId\" = ? AND year = ? AND \"deletedAt\" IS NULL", org(), p.get("leaveTypeId"), year)) {
                BigDecimal remaining = dec(b.get("accruedDays")).add(dec(b.get("adjustedDays")))
                        .subtract(dec(b.get("usedDays")));
                if (remaining.signum() <= 0) {
                    continue;
                }
                BigDecimal carry = remaining.min(cap);
                Map<String, Object> emp = Map.of("id", b.get("employeeId"));
                if (carry.signum() > 0) {
                    carried += post(emp, p, year + 1, "CARRY_FORWARD", carry, "CF-" + year, LocalDate.of(year + 1, 1, 1),
                            "Carried from " + year);
                }
                BigDecimal lapsed = remaining.subtract(carry);
                if (lapsed.signum() > 0) {
                    ledgerOnly(emp, p, year, "EXPIRY", lapsed.negate(), "LAPSE-" + year, LocalDate.of(year, 12, 31),
                            "Lapsed at year end");
                }
            }
        }
        return Map.of("year", year, "carried", carried);
    }

    /** Carried days not used by the expiry date lapse (used days consume carried days first). */
    private int expireCarry(Map<String, Object> e, Map<String, Object> p, LocalDate asOf) {
        int months = PosSql.intOf(p.get("carryForwardExpiryMonths"));
        if (months <= 0) {
            return 0;
        }
        int year = asOf.getYear();
        LocalDate expiry = LocalDate.of(year, 1, 1).plusMonths(months);
        if (asOf.isBefore(expiry)) {
            return 0;
        }
        Map<String, Object> cf = rows.first("HrLeaveLedger", "\"employeeId\" = ? AND \"leaveTypeId\" = ? AND"
                + " \"entryType\" = 'CARRY_FORWARD' AND \"periodKey\" = ?", null, e.get("id"), p.get("leaveTypeId"),
                "CF-" + (year - 1));
        if (cf == null) {
            return 0;
        }
        Map<String, Object> b = balance((String) e.get("id"), (String) p.get("leaveTypeId"), year);
        BigDecimal unused = dec(cf.get("days")).subtract(dec(b.get("usedDays")));
        if (unused.signum() <= 0) {
            return 0;
        }
        return post(e, p, year, "EXPIRY", unused.negate(), "CFX-" + (year - 1), expiry, "Carried days expired");
    }

    /** One ledger entry plus its balance effect; 0 when the period key was already posted. */
    private int post(Map<String, Object> e, Map<String, Object> p, int year, String type, BigDecimal days, String key,
            LocalDate on, String note) {
        if (!ledgerOnly(e, p, year, type, days, key, on, note)) {
            return 0;
        }
        String empId = (String) e.get("id");
        String typeId = (String) p.get("leaveTypeId");
        Map<String, Object> b = balance(empId, typeId, year);
        if ("ACCRUAL".equals(type)) {
            Map<String, Object> sum = rows.queryOne("SELECT coalesce(sum(days), 0) AS d FROM \"HrLeaveLedger\" WHERE"
                    + " \"organizationId\" = ? AND \"employeeId\" = ? AND \"leaveTypeId\" = ? AND year = ? AND"
                    + " \"entryType\" = 'ACCRUAL' AND \"deletedAt\" IS NULL", org(), empId, typeId, year);
            rows.jdbc().sql("UPDATE \"HrLeaveBalance\" SET \"accruedDays\" = ?, \"updatedAt\" = CURRENT_TIMESTAMP WHERE"
                    + " id = ?").params(dec(sum.get("d")), b.get("id")).update();
        } else {
            rows.jdbc().sql("UPDATE \"HrLeaveBalance\" SET \"adjustedDays\" = \"adjustedDays\" + ?, \"updatedAt\" ="
                    + " CURRENT_TIMESTAMP WHERE id = ?").params(days, b.get("id")).update();
        }
        return 1;
    }

    private boolean ledgerOnly(Map<String, Object> e, Map<String, Object> p, int year, String type, BigDecimal days,
            String key, LocalDate on, String note) {
        if (key != null && rows.first("HrLeaveLedger", "\"employeeId\" = ? AND \"leaveTypeId\" = ? AND \"entryType\" = ?"
                + " AND \"periodKey\" = ?", null, e.get("id"), p.get("leaveTypeId"), type, key) != null) {
            return false;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("employeeId", e.get("id"));
        data.put("leaveTypeId", p.get("leaveTypeId"));
        data.put("year", year);
        data.put("entryType", type);
        data.put("days", days);
        data.put("postedOn", on);
        data.put("periodKey", key);
        data.put("note", note);
        rows.insert("HrLeaveLedger", data);
        return true;
    }

    /** The balance row of a year; created at zero when missing (a policy owns the accrued days). */
    Map<String, Object> balance(String empId, String typeId, int year) {
        Map<String, Object> b = rows.queryOne("SELECT * FROM \"HrLeaveBalance\" WHERE \"organizationId\" = ? AND"
                + " \"employeeId\" = ? AND \"leaveTypeId\" = ? AND year = ? AND \"deletedAt\" IS NULL", org(), empId, typeId,
                year);
        if (b != null) {
            return b;
        }
        return hr.insert(HrLeaveBalance.class, Map.of("organizationId", org(), "employeeId", empId, "leaveTypeId",
                typeId, "year", year, "accruedDays", BigDecimal.ZERO, "usedDays", BigDecimal.ZERO, "adjustedDays",
                BigDecimal.ZERO));
    }

    private List<Map<String, Object>> activeEmployees() {
        return rows.query("SELECT id, \"hireDate\" FROM \"HrEmployee\" WHERE \"organizationId\" = ? AND \"isActive\" ="
                + " true AND \"deletedAt\" IS NULL", org());
    }

    private static BigDecimal round(BigDecimal d) {
        return d.setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ encashment, ledger

    /** Pay unused days through payroll: a taxable earning at basic ÷ 30 per day; the balance goes down. */
    public Map<String, Object> encash(Map<String, Object> dto) {
        String empId = str(dto.get("employeeId"));
        String typeId = str(dto.get("leaveTypeId"));
        BigDecimal days = dec(dto.get("days"));
        if (empId == null || typeId == null || days == null || days.signum() <= 0) {
            throw ApiException.badRequest("employeeId, leaveTypeId and a positive days are required");
        }
        Map<String, Object> p = rows.first("HrLeavePolicy", "\"leaveTypeId\" = ?", null, typeId);
        if (p == null || !Boolean.TRUE.equals(p.get("encashable"))) {
            throw ApiException.badRequest("This leave type cannot be encashed");
        }
        BigDecimal max = dec(p.get("maxEncashDays"));
        if (max != null && days.compareTo(max) > 0) {
            throw ApiException.badRequest("At most " + max.stripTrailingZeros().toPlainString() + " days can be encashed");
        }
        int year = dto.get("year") instanceof Number n ? n.intValue() : LocalDate.now(ZoneOffset.UTC).getYear();
        Map<String, Object> b = balance(empId, typeId, year);
        BigDecimal remaining = dec(b.get("accruedDays")).add(dec(b.get("adjustedDays"))).subtract(dec(b.get("usedDays")));
        if (days.compareTo(remaining) > 0) {
            throw ApiException.badRequest("Only " + remaining.stripTrailingZeros().toPlainString() + " days are available");
        }
        BigDecimal daily = dailyRate(empId);
        BigDecimal amount = daily.multiply(days, MC).setScale(2, RoundingMode.HALF_UP);
        String key = "ENC-" + UUID.randomUUID();
        return hr.inTx(() -> {
            Map<String, Object> input = inputs.addEarning(empId, "Leave encashment (" + days.stripTrailingZeros()
                    .toPlainString() + " days)", amount, LocalDate.now(ZoneOffset.UTC), true, null, "leave_encashment", key);
            post(Map.of("id", empId), p, year, "ENCASHMENT", days.negate(), key, LocalDate.now(ZoneOffset.UTC),
                    "Encashed through payroll");
            hr.auditInTx(AuditInput.of("HrLeaveBalance", (String) b.get("id"), "adjust", null,
                    Map.of("encashedDays", days, "amount", amount, "payrollInputId", input.get("id"))));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("days", days);
            out.put("dailyRate", daily);
            out.put("amount", amount);
            out.put("payrollInput", input);
            return out;
        });
    }

    /** Basic monthly salary ÷ 30 (current compensation, else the employee's base salary). */
    BigDecimal dailyRate(String empId) {
        Map<String, Object> c = compensation.compensationOn(empId, LocalDate.now(ZoneOffset.UTC));
        BigDecimal basic = c != null ? dec(c.get("basicSalary")) : HrRows.decOr(rows.queryOne("SELECT \"baseSalary\" FROM"
                + " \"HrEmployee\" WHERE id = ?", empId).get("baseSalary"), BigDecimal.ZERO);
        return basic.divide(BigDecimal.valueOf(30), MC);
    }

    public Map<String, Object> ledger(Map<String, String> q) {
        String empId = q.get("employeeId");
        if (empId == null) {
            throw ApiException.badRequest("employeeId is required");
        }
        List<Object> params = new ArrayList<>(List.of(org(), empId));
        String where = "";
        if (q.get("leaveTypeId") != null) {
            where += " AND l.\"leaveTypeId\" = ?";
            params.add(q.get("leaveTypeId"));
        }
        if (q.get("year") != null) {
            where += " AND l.year = ?";
            params.add(Integer.parseInt(q.get("year")));
        }
        List<Map<String, Object>> list = rows.query("SELECT l.*, t.code AS \"leaveTypeCode\" FROM \"HrLeaveLedger\" l JOIN"
                + " \"HrLeaveType\" t ON t.id = l.\"leaveTypeId\" WHERE l.\"organizationId\" = ? AND l.\"employeeId\" = ? AND"
                + " l.\"deletedAt\" IS NULL" + where + " ORDER BY l.\"postedOn\", l.\"createdAt\"", params.toArray());
        return HrRows.page(list, list.size());
    }

    // ------------------------------------------------------------------ day counting, calendar

    /** Leave days between two dates for a leave type: weekends / holidays excluded unless the policy counts them. */
    public Map<String, Object> countDays(Map<String, String> q) {
        LocalDate from = HrRows.date(q.get("from"));
        LocalDate to = HrRows.date(q.get("to"));
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.badRequest("from and to are required, to not before from");
        }
        if (ChronoUnit.DAYS.between(from, to) > 366) {
            throw ApiException.badRequest("The range cannot exceed a year");
        }
        Map<String, Object> p = q.get("leaveTypeId") == null ? null
                : rows.first("HrLeavePolicy", "\"leaveTypeId\" = ?", null, q.get("leaveTypeId"));
        boolean weekends = p != null && Boolean.TRUE.equals(p.get("countWeekends"));
        boolean holidays = p != null && Boolean.TRUE.equals(p.get("countHolidays"));
        Set<DayOfWeek> weekend = weekend();
        Predicate<LocalDate> holiday = overtime.holidays();
        List<String> excluded = new ArrayList<>();
        BigDecimal days = BigDecimal.ZERO;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!weekends && weekend.contains(d.getDayOfWeek())) {
                excluded.add(d + " weekend");
            } else if (!holidays && holiday.test(d)) {
                excluded.add(d + " holiday");
            } else {
                days = days.add(BigDecimal.ONE);
            }
        }
        if ("true".equals(q.get("halfDay")) && from.equals(to) && days.signum() > 0) {
            if (p != null && !Boolean.TRUE.equals(p.get("allowHalfDay"))) {
                throw ApiException.badRequest("Half days are not allowed for this leave type");
            }
            days = new BigDecimal("0.5");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("days", days);
        out.put("excluded", excluded);
        return out;
    }

    Set<DayOfWeek> weekend() {
        Set<DayOfWeek> out = new HashSet<>();
        for (String d : String.valueOf(setup.effectiveSettings().get("weekendDays")).split(",")) {
            if (!d.isBlank()) {
                out.add(DayOfWeek.valueOf(d.trim()));
            }
        }
        return out;
    }

    /** Approved and pending leave overlapping the range, by employee, plus holidays (team calendar). */
    public Map<String, Object> calendar(Map<String, String> q) {
        LocalDate from = HrRows.date(q.get("from"));
        LocalDate to = HrRows.date(q.get("to"));
        if (from == null || to == null) {
            throw ApiException.badRequest("from and to are required");
        }
        List<Object> params = new ArrayList<>(List.of(org(), SqlRows.ts(HrPayrollEngineV2.utc(to.plusDays(1))),
                SqlRows.ts(HrPayrollEngineV2.utc(from))));
        String dept = "";
        if (q.get("departmentId") != null) {
            dept = " AND e.\"departmentId\" = ?";
            params.add(q.get("departmentId"));
        }
        List<Map<String, Object>> leave = rows.query("SELECT r.id, r.\"employeeId\", e.\"employeeCode\", e.\"firstName\","
                + " e.\"lastName\", e.\"departmentId\", t.name AS \"leaveType\", r.\"startDate\", r.\"endDate\", r.days,"
                + " r.status FROM \"HrLeaveRequest\" r JOIN \"HrEmployee\" e ON e.id = r.\"employeeId\" JOIN \"HrLeaveType\""
                + " t ON t.id = r.\"leaveTypeId\" WHERE r.\"organizationId\" = ? AND r.\"deletedAt\" IS NULL AND"
                + " r.status::text IN ('APPROVED', 'PENDING') AND r.\"startDate\" < ? AND r.\"endDate\" >= ?" + dept
                + " ORDER BY r.\"startDate\"", params.toArray());
        List<Map<String, Object>> hol = rows.query("SELECT name, date, \"isRecurring\" FROM \"HrHoliday\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY date", org());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("leave", leave);
        out.put("holidays", hol);
        return out;
    }
}
