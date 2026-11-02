package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrRows.org;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.hr.domain.HrEmployee;
import com.inventoryerp.hr.domain.HrPayrollAllowance;
import com.inventoryerp.hr.domain.HrPayrollDeduction;
import com.inventoryerp.hr.domain.HrPayrollItem;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

/**
 * Payroll engine v2 (HP-2/HP-3, ADR-S048): gathers each employee's facts for a run, calls {@link HrPayrollCalculator},
 * and writes the Nest item / allowance / deduction rows plus {@code HrPayrollResultLine}s. Also builds the v2 credit
 * side of the approval journal (per statutory payable, employer contributions), retro pay, and the run variance.
 */
@Service
public class HrPayrollEngineV2 {

    static final MathContext MC = MathContext.DECIMAL64;

    private final HrDb hr;
    private final HrRows rows;
    private final HrPayrollSetupService setup;
    private final HrCompensationService compensation;
    private final HrOvertimeService overtime;
    private final AccountResolverService accountsResolver;

    public HrPayrollEngineV2(HrDb hr, HrRows rows, HrPayrollSetupService setup, HrCompensationService compensation,
            HrOvertimeService overtime, AccountResolverService accountsResolver) {
        this.hr = hr;
        this.rows = rows;
        this.setup = setup;
        this.compensation = compensation;
        this.overtime = overtime;
        this.accountsResolver = accountsResolver;
    }

    /** Engine v2 is on once the organisation saved settings with {@code engine = v2}. */
    public boolean enabled() {
        Map<String, Object> s = setup.settingsRow();
        return s != null && "v2".equals(s.get("engine"));
    }

    public boolean isV2Run(String runId) {
        return "v2".equals(rows.jdbc().sql("SELECT engine FROM \"HrPayrollRun\" WHERE id = ? AND \"organizationId\" = ?")
                .params(runId, org()).query(String.class).optional().orElse(null));
    }

    static int periodsPerYear(Object periodType) {
        return switch (String.valueOf(periodType)) {
            case "WEEKLY" -> 52;
            case "BIWEEKLY" -> 26;
            default -> 12;
        };
    }

    /** Statutory rate rows of a pack in force on {@code on} (latest per code). */
    public List<HrStatutory.Rate> rates(String pack, LocalDate on) {
        List<HrStatutory.Rate> out = new ArrayList<>();
        if (pack == null || "NONE".equals(pack)) {
            return out;
        }
        for (Map<String, Object> r : HrRows.rows(rows.jdbc().sql("SELECT DISTINCT ON (code) * FROM \"HrStatutoryRate\""
                + " WHERE \"packCode\" = ? AND \"effectiveFrom\" <= ? ORDER BY code, \"effectiveFrom\" DESC")
                .params(pack, java.sql.Date.valueOf(on)))) {
            out.add(new HrStatutory.Rate(pack, (String) r.get("code"), (String) r.get("name"), PosSql.map(r.get("params"))));
        }
        return out;
    }

    public List<Map<String, Object>> rateRows(String pack) {
        return HrRows.rows(rows.jdbc().sql("SELECT * FROM \"HrStatutoryRate\" WHERE (CAST(? AS text) IS NULL OR \"packCode\" = ?)"
                + " ORDER BY \"packCode\", code, \"effectiveFrom\" DESC").params(pack, pack));
    }

    /** Calculate every employee of the run; the caller already removed the run's previous items. */
    List<Map<String, Object>> calculate(String runId, Map<String, Object> period, List<Map<String, Object>> employees,
            List<Map<String, Object>> inputs, String userId) {
        Map<String, Object> s = setup.effectiveSettings();
        LocalDate start = HrRows.date(period.get("startDate"));
        LocalDate end = HrRows.date(period.get("endDate"));
        int ppy = periodsPerYear(period.get("periodType"));
        int scale = ((Number) s.get("roundingScale")).intValue();
        Set<DayOfWeek> weekend = new HashSet<>();
        for (String d : String.valueOf(s.get("weekendDays")).split(",")) {
            if (!d.isBlank()) {
                weekend.add(DayOfWeek.valueOf(d.trim()));
            }
        }
        Predicate<LocalDate> holiday = overtime.holidays();
        String pack = (String) s.get("statutoryPack");
        List<HrStatutory.Rate> rates = rates(pack, end);
        List<Map<String, Object>> otRules = overtime.activeRules();
        rows.jdbc().sql("DELETE FROM \"HrPayrollResultLine\" WHERE \"runId\" = ? AND \"organizationId\" = ?")
                .params(runId, org()).update();
        Instant runDate = Instant.now();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> emp : employees) {
            String empId = (String) emp.get("id");
            Map<String, Object> comp = compensation.compensationOn(empId, end);
            BigDecimal basicMonthly = comp != null ? HrRows.dec(comp.get("basicSalary"))
                    : HrRows.decOr(emp.get("baseSalary"), BigDecimal.ZERO);
            Map<String, Object> att = rows.queryOne("SELECT coalesce(sum(\"workedMinutes\"), 0) AS w,"
                    + " coalesce(sum(\"lateMinutes\"), 0) AS l FROM \"HrAttendance\" WHERE \"organizationId\" = ? AND"
                    + " \"employeeId\" = ? AND \"deletedAt\" IS NULL AND date >= ? AND date < ?", org(), empId,
                    SqlRows.ts(utc(start)), SqlRows.ts(utc(end.plusDays(1))));
            BigDecimal workedHours = new BigDecimal(String.valueOf(att.get("w"))).divide(BigDecimal.valueOf(60), MC);
            Map<LocalDate, BigDecimal> otByDay = new TreeMap<>();
            for (Map<String, Object> a : rows.query("SELECT date, \"overtimeMinutes\", \"overtimeApproved\" FROM"
                    + " \"HrAttendance\" WHERE \"organizationId\" = ? AND \"employeeId\" = ? AND \"deletedAt\" IS NULL"
                    + " AND \"overtimeMinutes\" > 0 AND date >= ? AND date < ?", org(), empId,
                    SqlRows.ts(utc(start)), SqlRows.ts(utc(end.plusDays(1))))) {
                if (Boolean.TRUE.equals(s.get("requireOvertimeApproval"))
                        && !Boolean.TRUE.equals(a.get("overtimeApproved"))) {
                    continue;
                }
                otByDay.merge(HrRows.date(a.get("date")), new BigDecimal(String.valueOf(a.get("overtimeMinutes"))),
                        BigDecimal::add);
            }
            BigDecimal otMinutes = otByDay.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Object> rule = HrOvertimeCalculator.pick(otRules, emp);
            BigDecimal otWeighted = rule != null ? HrOvertimeCalculator.weightedHours(otByDay, rule, holiday)
                    : otMinutes.multiply(new BigDecimal("1.5")).divide(BigDecimal.valueOf(60), MC);
            BigDecimal unpaid = unpaidLeaveDays(empId, start, end, weekend, holiday);
            if (Boolean.TRUE.equals(s.get("absenceDeduction"))) {
                unpaid = unpaid.add(absenceDays(empId, start, end));
            }
            List<HrPayrollCalculator.Input> myInputs = new ArrayList<>();
            for (Map<String, Object> in : inputs) {
                if (empId.equals(in.get("employeeId"))) {
                    myInputs.add(new HrPayrollCalculator.Input((String) in.get("id"), (String) in.get("kind"),
                            (String) in.get("name"), SqlRows.dec(in, "amount"), Boolean.TRUE.equals(in.get("isTaxable"))));
                }
            }
            HrPayrollCalculator.Context ctx = new HrPayrollCalculator.Context(start, end, ppy,
                    String.valueOf(s.get("prorationBasis")), scale, HrRows.dec(s.get("standardHoursPerDay")), weekend,
                    holiday, HrRows.date(emp.get("hireDate")), HrRows.date(emp.get("terminationDate")), basicMonthly,
                    "HOURLY".equals(emp.get("payFrequency")), HrRows.dec(emp.get("hourlyRate")), workedHours,
                    otWeighted, otMinutes.divide(BigDecimal.valueOf(60), MC), unpaid,
                    new BigDecimal(String.valueOf(att.get("l"))), Boolean.TRUE.equals(s.get("lateDeduction")),
                    compensation.elementsFor(empId, start, end), myInputs, retroArrears(empId, start, basicMonthly),
                    pack, rates, (String) emp.get("nationality"), recoveries(empId, runDate));
            HrPayrollCalculator.Result res = HrPayrollCalculator.calculate(ctx);
            items.add(write(runId, empId, res, unpaid, workedHours, otMinutes, userId));
        }
        rows.jdbc().sql("UPDATE \"HrPayrollRun\" SET engine = 'v2' WHERE id = ? AND \"organizationId\" = ?")
                .params(runId, org()).update();
        return items;
    }

    private Map<String, Object> write(String runId, String empId, HrPayrollCalculator.Result res, BigDecimal unpaid,
            BigDecimal workedHours, BigDecimal otMinutes, String userId) {
        String orgId = org();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("organizationId", orgId);
        data.put("runId", runId);
        data.put("employeeId", empId);
        data.put("baseSalary", res.basic());
        data.put("hourlyRate", BigDecimal.ZERO);
        data.put("regularHours", workedHours.setScale(0, RoundingMode.HALF_UP).intValue());
        data.put("overtimeHours", otMinutes.divide(BigDecimal.valueOf(60), MC).setScale(0, RoundingMode.HALF_UP)
                .intValue());
        data.put("overtimePay", res.overtimePay());
        data.put("allowancesTotal", res.gross().subtract(res.basic()).subtract(res.overtimePay()));
        data.put("commissionAmount", BigDecimal.ZERO);
        data.put("bonusAmount", BigDecimal.ZERO);
        data.put("grossPay", res.gross());
        data.put("taxAmount", res.tax());
        data.put("pensionAmount", res.pension());
        data.put("socialSecurityAmount", res.socialSecurity());
        data.put("loanDeduction", res.loanDeduction());
        data.put("advanceDeduction", res.advanceDeduction());
        data.put("insuranceAmount", BigDecimal.ZERO);
        data.put("otherDeductions", res.otherDeductions());
        data.put("totalDeductions", res.totalDeductions());
        data.put("netPay", res.netPay());
        data.put("absenceDays", unpaid);
        data.put("createdBy", userId);
        Map<String, Object> item = hr.insert(HrPayrollItem.class, data);
        String itemId = (String) item.get("id");
        for (HrPayrollCalculator.Line l : res.lines()) {
            if ("EARNING".equals(l.kind()) && !"BASIC".equals(l.category()) && !"OVERTIME".equals(l.category())) {
                hr.insert(HrPayrollAllowance.class, nestLine(orgId, itemId, l, userId));
            } else if ("DEDUCTION".equals(l.kind())) {
                hr.insert(HrPayrollDeduction.class, nestLine(orgId, itemId, l, userId));
            }
            Map<String, Object> rl = new LinkedHashMap<>();
            rl.put("runId", runId);
            rl.put("itemId", itemId);
            rl.put("employeeId", empId);
            rl.put("code", l.code());
            rl.put("name", l.name());
            rl.put("kind", l.kind());
            rl.put("category", l.category());
            rl.put("statutoryCode", l.statutoryCode());
            rl.put("amount", l.amount());
            rl.put("base", l.base());
            rl.put("rate", l.rate());
            rl.put("quantity", l.quantity());
            rl.put("isTaxable", l.taxable());
            rl.put("glAccountId", l.glAccountId());
            rl.put("payableKey", l.payableKey() == null ? l.fallbackPayableKey() : resolvableKey(l));
            rl.put("expenseKey", l.expenseKey());
            rl.put("sourceId", l.sourceId());
            rl.put("sortOrder", l.sortOrder());
            rows.insert("HrPayrollResultLine", rl);
        }
        return item;
    }

    private String resolvableKey(HrPayrollCalculator.Line l) {
        if (l.fallbackPayableKey() == null || accountsResolver.byMappingOptional(l.payableKey()) != null) {
            return l.payableKey();
        }
        return l.fallbackPayableKey();
    }

    private static Map<String, Object> nestLine(String orgId, String itemId, HrPayrollCalculator.Line l,
            String userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("organizationId", orgId);
        m.put("itemId", itemId);
        m.put("name", l.name());
        m.put("amount", l.amount());
        m.put("isTaxable", l.taxable());
        m.put("createdBy", userId);
        return m;
    }

    /** Working days of approved unpaid leave inside the period. */
    BigDecimal unpaidLeaveDays(String empId, LocalDate start, LocalDate end, Set<DayOfWeek> weekend,
            Predicate<LocalDate> holiday) {
        BigDecimal days = BigDecimal.ZERO;
        for (Map<String, Object> l : rows.query("SELECT r.\"startDate\", r.\"endDate\", r.days FROM \"HrLeaveRequest\" r"
                + " JOIN \"HrLeaveType\" t ON t.id = r.\"leaveTypeId\" WHERE r.\"organizationId\" = ? AND r.\"employeeId\" = ?"
                + " AND r.\"deletedAt\" IS NULL AND r.status = 'APPROVED' AND t.\"isPaid\" = false AND r.\"startDate\" < ?"
                + " AND r.\"endDate\" >= ?", org(), empId, SqlRows.ts(utc(end.plusDays(1))), SqlRows.ts(utc(start)))) {
            LocalDate from = max(HrRows.date(l.get("startDate")), start);
            LocalDate to = min(HrRows.date(l.get("endDate")), end);
            long wd = HrPayrollCalculator.workingDays(from, to, weekend, holiday);
            // A half-day request (0.5) inside one day counts as requested.
            BigDecimal requested = HrRows.dec(l.get("days"));
            BigDecimal counted = BigDecimal.valueOf(wd);
            if (requested != null && requested.compareTo(counted) < 0) {
                counted = requested;
            }
            days = days.add(counted);
        }
        return days;
    }

    /** ABSENT attendance days in the period not covered by approved leave. */
    BigDecimal absenceDays(String empId, LocalDate start, LocalDate end) {
        Long n = rows.jdbc().sql("SELECT count(*) FROM \"HrAttendance\" a WHERE a.\"organizationId\" = ? AND"
                + " a.\"employeeId\" = ? AND a.\"deletedAt\" IS NULL AND a.status = 'ABSENT' AND a.date >= ? AND a.date < ?"
                + " AND NOT EXISTS (SELECT 1 FROM \"HrLeaveRequest\" r WHERE r.\"employeeId\" = a.\"employeeId\" AND"
                + " r.status = 'APPROVED' AND r.\"deletedAt\" IS NULL AND a.date BETWEEN r.\"startDate\" AND r.\"endDate\")")
                .params(org(), empId, SqlRows.ts(utc(start)), SqlRows.ts(utc(end.plusDays(1)))).query(Long.class)
                .single();
        return BigDecimal.valueOf(n);
    }

    /** Arrears of approved back-dated revisions for months already paid at the old rate. */
    BigDecimal retroArrears(String empId, LocalDate periodStart, BigDecimal currentBasic) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> rev : rows.list("HrEmployeeCompensation", "\"employeeId\" = ? AND status IN ('ACTIVE',"
                + " 'SUPERSEDED') AND \"approvedAt\" IS NOT NULL AND \"retroRunId\" IS NULL AND \"previousBasic\" IS NOT"
                + " NULL AND \"effectiveFrom\" < ?", "\"effectiveFrom\" ASC", empId, periodStart)) {
            LocalDate from = HrRows.date(rev.get("effectiveFrom"));
            LocalDate paidUntil = paidUntil(empId);
            if (paidUntil == null || paidUntil.isBefore(from)) {
                continue;
            }
            LocalDate to = min(paidUntil, periodStart.minusDays(1));
            LocalDate revTo = HrRows.date(rev.get("effectiveTo"));
            if (revTo != null) {
                to = min(to, revTo);
            }
            BigDecimal diff = HrRows.dec(rev.get("basicSalary")).subtract(HrRows.dec(rev.get("previousBasic")));
            total = total.add(diff.multiply(monthsBetween(from, to), MC));
        }
        return total;
    }

    /** Fractional months from {@code from} to {@code to} inclusive (each month by its own length). */
    static BigDecimal monthsBetween(LocalDate from, LocalDate to) {
        BigDecimal m = BigDecimal.ZERO;
        LocalDate d = from;
        while (!d.isAfter(to)) {
            LocalDate monthEnd = d.withDayOfMonth(d.lengthOfMonth());
            LocalDate segEnd = min(monthEnd, to);
            long days = ChronoUnit.DAYS.between(d, segEnd) + 1;
            m = m.add(BigDecimal.valueOf(days).divide(BigDecimal.valueOf(d.lengthOfMonth()), MC));
            d = segEnd.plusDays(1);
        }
        return m;
    }

    private LocalDate paidUntil(String empId) {
        Map<String, Object> r = rows.queryOne("SELECT max(p.\"endDate\") AS e FROM \"HrPayrollItem\" i JOIN"
                + " \"HrPayrollRun\" r ON r.id = i.\"runId\" JOIN \"HrPayrollPeriod\" p ON p.id = r.\"periodId\" WHERE"
                + " i.\"organizationId\" = ? AND i.\"employeeId\" = ? AND r.status::text IN ('APPROVED', 'PAID')", org(),
                empId);
        return r == null ? null : HrRows.date(r.get("e"));
    }

    private List<HrPayrollCalculator.Recovery> recoveries(String empId, Instant runDate) {
        List<HrPayrollCalculator.Recovery> out = new ArrayList<>();
        for (Map<String, Object> l : rows.query("SELECT id, \"loanCode\", \"installmentAmount\", balance FROM"
                + " \"HrEmployeeLoan\" WHERE \"organizationId\" = ? AND \"employeeId\" = ? AND \"deletedAt\" IS NULL AND"
                + " status::text = 'ACTIVE' AND balance > 0 ORDER BY \"createdAt\"", org(), empId)) {
            out.add(new HrPayrollCalculator.Recovery((String) l.get("id"), "LOAN", (String) l.get("loanCode"),
                    HrRows.dec(l.get("installmentAmount")), HrRows.dec(l.get("balance"))));
        }
        for (Map<String, Object> a : rows.query("SELECT id, \"advanceCode\", \"monthlyDeduction\", balance FROM"
                + " \"HrSalaryAdvance\" WHERE \"organizationId\" = ? AND \"employeeId\" = ? AND \"deletedAt\" IS NULL AND"
                + " status::text IN ('APPROVED', 'PAID') AND balance > 0 AND ((\"paidAt\" IS NULL AND \"approvedAt\" <= ?)"
                + " OR \"paidAt\" <= ?) ORDER BY \"createdAt\"", org(), empId, SqlRows.ts(runDate), SqlRows.ts(runDate))) {
            out.add(new HrPayrollCalculator.Recovery((String) a.get("id"), "ADVANCE", (String) a.get("advanceCode"),
                    HrRows.dec(a.get("monthlyDeduction")), HrRows.dec(a.get("balance"))));
        }
        return out;
    }

    // ------------------------------------------------------------------ approval journal (v2 credit side)

    /**
     * Debits and credits of a v2 run besides the gross salary debit and the payroll-input lines the v1 journal keeps:
     * net pay, each statutory payable, employer contributions (expense and payable), element deductions,
     * lateness, loans and advances.
     */
    void journalLines(String runId, List<PostingRequest.Line> lines, String salaryExpense, String netPayable,
            String loanReceivable, String advanceReceivable) {
        BigDecimal net = BigDecimal.ZERO;
        Map<String, BigDecimal> credits = new LinkedHashMap<>();
        Map<String, BigDecimal> debits = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Map<String, Object> it : rows.query("SELECT \"netPay\" FROM \"HrPayrollItem\" WHERE \"runId\" = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL", runId, org())) {
            net = net.add(HrRows.dec(it.get("netPay")));
        }
        for (Map<String, Object> l : rows.list("HrPayrollResultLine", "\"runId\" = ?", null, runId)) {
            String kind = (String) l.get("kind");
            String category = (String) l.get("category");
            BigDecimal amount = HrRows.dec(l.get("amount"));
            if ("DEDUCTION".equals(kind) && !"INPUT_DEDUCTION".equals(category)) {
                String account = switch (category) {
                    case "LOAN" -> loanReceivable;
                    case "ADVANCE" -> advanceReceivable;
                    case "TAX", "STATUTORY" -> account((String) l.get("payableKey"), salaryExpense);
                    default -> l.get("glAccountId") != null ? (String) l.get("glAccountId") : salaryExpense;
                };
                credits.merge(account, amount, BigDecimal::add);
                names.putIfAbsent(account, label(category, l));
            } else if ("EMPLOYER_CONTRIBUTION".equals(kind)) {
                String expense = l.get("expenseKey") != null ? account((String) l.get("expenseKey"), salaryExpense)
                        : salaryExpense;
                String payable = l.get("statutoryCode") != null ? account((String) l.get("payableKey"), salaryExpense)
                        : l.get("glAccountId") != null ? (String) l.get("glAccountId") : salaryExpense;
                debits.merge(expense, amount, BigDecimal::add);
                credits.merge(payable, amount, BigDecimal::add);
                names.putIfAbsent(payable, label(category, l));
            }
        }
        debits.forEach((acc, amt) -> {
            if (amt.signum() > 0) {
                lines.add(PostingRequest.Line.debit(acc, amt, "Employer contributions"));
            }
        });
        if (net.signum() > 0) {
            lines.add(PostingRequest.Line.credit(netPayable, net, "Net pay payable"));
        }
        credits.forEach((acc, amt) -> {
            if (amt.signum() > 0) {
                lines.add(PostingRequest.Line.credit(acc, amt, names.getOrDefault(acc, "Payroll deductions")));
            }
        });
    }

    private static String label(String category, Map<String, Object> l) {
        return switch (category) {
            case "LOAN" -> "Employee loan installments";
            case "ADVANCE" -> "Employee advance installments";
            case "TAX", "STATUTORY" -> l.get("statutoryCode") + " payable";
            default -> "Payroll deductions";
        };
    }

    private String account(String key, String fallback) {
        if (key == null) {
            return fallback;
        }
        String id = accountsResolver.byMappingOptional(key);
        if (id != null) {
            return id;
        }
        if (key.endsWith("_expense")) {
            return fallback;
        }
        throw ApiException.badRequest("Account mapping '" + key + "' is not configured. Set it under Accounting >"
                + " Account Mapping.");
    }

    /** On approval: arrears paid by this run are marked so they are not paid twice. */
    void onApproved(String runId) {
        for (Map<String, Object> l : rows.list("HrPayrollResultLine", "\"runId\" = ? AND category = 'RETRO'", null,
                runId)) {
            rows.jdbc().sql("UPDATE \"HrEmployeeCompensation\" SET \"retroRunId\" = ? WHERE \"employeeId\" = ? AND"
                    + " \"organizationId\" = ? AND \"retroRunId\" IS NULL AND status IN ('ACTIVE', 'SUPERSEDED') AND"
                    + " \"approvedAt\" IS NOT NULL").params(runId, l.get("employeeId"), org()).update();
        }
    }

    void onReversed(String runId) {
        rows.jdbc().sql("UPDATE \"HrEmployeeCompensation\" SET \"retroRunId\" = NULL WHERE \"retroRunId\" = ? AND"
                + " \"organizationId\" = ?").params(runId, org()).update();
    }

    // ------------------------------------------------------------------ reads

    /** Result lines of a run (v2), optionally for one employee. */
    public Map<String, Object> resultLines(String runId, String employeeId) {
        requireRun(runId);
        List<Map<String, Object>> list = employeeId == null
                ? rows.list("HrPayrollResultLine", "\"runId\" = ?", "\"employeeId\", \"sortOrder\"", runId)
                : rows.list("HrPayrollResultLine", "\"runId\" = ? AND \"employeeId\" = ?", "\"sortOrder\"", runId,
                        employeeId);
        return HrRows.page(list, list.size());
    }

    /**
     * Run variance: each employee's gross and net against the previous approved run of the same pay group (or any
     * previous approved run), flagged above the threshold, plus joiners and leavers.
     */
    public Map<String, Object> variance(String runId) {
        Map<String, Object> run = requireRun(runId);
        Map<String, Object> s = setup.effectiveSettings();
        BigDecimal threshold = HrRows.decOr(s.get("varianceThresholdPct"), BigDecimal.TEN);
        Map<String, Object> period = rows.queryOne("SELECT * FROM \"HrPayrollPeriod\" WHERE id = ?", run.get("periodId"));
        Map<String, Object> prev = rows.queryOne("SELECT r.id, r.\"runNumber\" FROM \"HrPayrollRun\" r JOIN"
                + " \"HrPayrollPeriod\" p ON p.id = r.\"periodId\" WHERE r.\"organizationId\" = ? AND r.\"deletedAt\" IS"
                + " NULL AND r.status::text IN ('APPROVED', 'PAID') AND r.id <> ? AND p.\"startDate\" < ? AND"
                + " p.\"payGroupId\" IS NOT DISTINCT FROM CAST(? AS text) ORDER BY p.\"startDate\" DESC LIMIT 1", org(), runId,
                period.get("startDate"), period.get("payGroupId"));
        Map<String, Map<String, Object>> before = new LinkedHashMap<>();
        if (prev != null) {
            for (Map<String, Object> i : itemRows((String) prev.get("id"))) {
                before.put((String) i.get("employeeId"), i);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int flagged = 0;
        for (Map<String, Object> i : itemRows(runId)) {
            Map<String, Object> b = before.remove(i.get("employeeId"));
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("employeeId", i.get("employeeId"));
            v.put("employeeCode", i.get("employeeCode"));
            v.put("name", i.get("firstName") + (i.get("lastName") == null ? "" : " " + i.get("lastName")));
            BigDecimal gross = HrRows.dec(i.get("grossPay"));
            BigDecimal net = HrRows.dec(i.get("netPay"));
            v.put("gross", gross);
            v.put("net", net);
            if (b == null) {
                v.put("status", "NEW");
                v.put("flagged", prev != null);
            } else {
                BigDecimal pg = HrRows.dec(b.get("grossPay"));
                BigDecimal pn = HrRows.dec(b.get("netPay"));
                v.put("previousGross", pg);
                v.put("previousNet", pn);
                BigDecimal pct = pn.signum() == 0 ? (net.signum() == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(100))
                        : net.subtract(pn).multiply(BigDecimal.valueOf(100)).divide(pn.abs(), 2, RoundingMode.HALF_UP);
                v.put("netChangePct", pct);
                v.put("status", "CHANGED");
                v.put("flagged", pct.abs().compareTo(threshold) > 0);
            }
            if (Boolean.TRUE.equals(v.get("flagged"))) {
                flagged++;
            }
            out.add(v);
        }
        for (Map<String, Object> b : before.values()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("employeeId", b.get("employeeId"));
            v.put("employeeCode", b.get("employeeCode"));
            v.put("name", b.get("firstName") + (b.get("lastName") == null ? "" : " " + b.get("lastName")));
            v.put("previousGross", b.get("grossPay"));
            v.put("previousNet", b.get("netPay"));
            v.put("status", "MISSING");
            v.put("flagged", true);
            flagged++;
            out.add(v);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("runId", runId);
        res.put("previousRun", prev);
        res.put("thresholdPct", threshold);
        res.put("flagged", flagged);
        res.put("rows", out);
        return res;
    }

    List<Map<String, Object>> itemRows(String runId) {
        return rows.query("SELECT i.*, e.\"employeeCode\", e.\"firstName\", e.\"lastName\", e.\"departmentId\" FROM"
                + " \"HrPayrollItem\" i JOIN \"HrEmployee\" e ON e.id = i.\"employeeId\" WHERE i.\"runId\" = ? AND"
                + " i.\"organizationId\" = ? AND i.\"deletedAt\" IS NULL ORDER BY e.\"employeeCode\"", runId, org());
    }

    Map<String, Object> requireRun(String runId) {
        Map<String, Object> run = rows.queryOne("SELECT * FROM \"HrPayrollRun\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL", runId, org());
        if (run == null) {
            throw ApiException.notFound("Payroll run not found");
        }
        return run;
    }

    static Instant utc(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    static List<Map<String, Object>> employeesOf(HrDb hr) {
        return hr.where(HrEmployee.class).eq("isActive", true).list(null);
    }
}
