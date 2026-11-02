package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrRows.dec;
import static com.inventoryerp.hr.application.HrRows.org;
import static com.inventoryerp.hr.application.HrRows.str;
import static com.inventoryerp.hr.application.HrRows.user;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Separation (HP-6, ADR-S048): offboarding with a clearance checklist, final settlement (unpaid salary to the last
 * day, leave encashment, notice pay or recovery, end-of-service gratuity, statutory deductions, loan / advance
 * recovery) posted to the GL on approval, payment, and the monthly gratuity provision (IAS 19, statutory formula).
 */
@Service
public class HrSeparationService {

    static final MathContext MC = MathContext.DECIMAL64;
    static final List<String> TYPES = List.of("RESIGNATION", "TERMINATION", "END_OF_CONTRACT", "RETIREMENT", "DEATH",
            "REDUNDANCY", "DISMISSAL");
    static final List<String[]> DEFAULT_TASKS = List.of(
            new String[] {"Hand over duties and documents", "Department"},
            new String[] {"Return company assets (laptop, phone, keys, ID card)", "IT / Admin"},
            new String[] {"Revoke system and building access", "IT"},
            new String[] {"Clear outstanding loans, advances and expenses", "Finance"},
            new String[] {"Exit interview", "HR"});

    private final HrRows rows;
    private final HrDb hr;
    private final SequenceService seq;
    private final HrPayrollSetupService setup;
    private final HrCompensationService compensation;
    private final HrPayrollEngineV2 engine;
    private final HrLifecycleService lifecycle;
    private final PostingService posting;
    private final AccountResolverService accounts;

    public HrSeparationService(HrRows rows, HrDb hr, SequenceService seq, HrPayrollSetupService setup,
            HrCompensationService compensation, HrPayrollEngineV2 engine, HrLifecycleService lifecycle,
            PostingService posting, AccountResolverService accounts) {
        this.rows = rows;
        this.hr = hr;
        this.seq = seq;
        this.setup = setup;
        this.compensation = compensation;
        this.engine = engine;
        this.lifecycle = lifecycle;
        this.posting = posting;
        this.accounts = accounts;
    }

    public Map<String, Object> list(Map<String, String> q) {
        String status = q.get("status");
        List<Map<String, Object>> list = rows.query("SELECT s.*, e.\"employeeCode\", e.\"firstName\", e.\"lastName\" FROM"
                + " \"HrSeparation\" s JOIN \"HrEmployee\" e ON e.id = s.\"employeeId\" WHERE s.\"organizationId\" = ? AND"
                + " s.\"deletedAt\" IS NULL AND (CAST(? AS text) IS NULL OR s.status = ?) ORDER BY s.\"createdAt\" DESC LIMIT 500", org(),
                status, status);
        return HrRows.page(list, list.size());
    }

    public Map<String, Object> get(String id) {
        Map<String, Object> s = rows.queryOne("SELECT s.*, e.\"employeeCode\", e.\"firstName\", e.\"lastName\","
                + " e.\"hireDate\" FROM \"HrSeparation\" s JOIN \"HrEmployee\" e ON e.id = s.\"employeeId\" WHERE s.id = ? AND"
                + " s.\"organizationId\" = ? AND s.\"deletedAt\" IS NULL", id, org());
        if (s == null) {
            throw ApiException.notFound("Separation not found");
        }
        s.put("tasks", rows.list("HrSeparationTask", "\"separationId\" = ?", "\"sortOrder\", \"createdAt\"", id));
        return s;
    }

    public Map<String, Object> create(Map<String, Object> dto) {
        String empId = str(dto.get("employeeId"));
        Map<String, Object> emp = empId == null ? null : rows.queryOne("SELECT id, \"isActive\" FROM \"HrEmployee\" WHERE"
                + " id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", empId, org());
        if (emp == null) {
            throw ApiException.notFound("Employee not found");
        }
        Object type = dto.get("separationType");
        HrRows.oneOf("separationType", type, TYPES);
        LocalDate notice = HrRows.date(dto.get("noticeDate"));
        LocalDate last = HrRows.date(dto.get("lastWorkingDay"));
        if (notice == null || last == null) {
            throw ApiException.badRequest("noticeDate and lastWorkingDay are required");
        }
        if (last.isBefore(notice.minusDays(365))) {
            throw ApiException.badRequest("lastWorkingDay is too far before the notice date");
        }
        if (rows.count("HrSeparation", "\"employeeId\" = ? AND status NOT IN ('CANCELLED', 'PAID')", empId) > 0) {
            throw ApiException.badRequest("This employee already has an open separation");
        }
        int required = dto.get("noticeDaysRequired") instanceof Number n ? n.intValue() : 30;
        long served = Math.max(0, ChronoUnit.DAYS.between(notice, last));
        // Default: an employer-side exit without full notice pays the shortfall; a short resignation pays nothing.
        BigDecimal noticePay = dto.containsKey("noticePayDays") ? dec(dto.get("noticePayDays"))
                : List.of("TERMINATION", "REDUNDANCY").contains(type)
                        ? BigDecimal.valueOf(Math.max(0, required - served)) : BigDecimal.ZERO;
        return hr.inTx(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("separationNumber", seq.next("hr_separation", "SEP-", 5));
            data.put("employeeId", empId);
            data.put("separationType", type);
            data.put("noticeDate", notice);
            data.put("lastWorkingDay", last);
            data.put("noticeDaysRequired", required);
            data.put("noticePayDays", noticePay);
            data.put("reason", str(dto.get("reason")));
            data.put("status", "CLEARANCE");
            Map<String, Object> s = rows.insert("HrSeparation", data);
            int i = 0;
            for (String[] t : DEFAULT_TASKS) {
                rows.insert("HrSeparationTask", Map.of("separationId", s.get("id"), "task", t[0], "department", t[1],
                        "sortOrder", i++));
            }
            hr.auditInTx(AuditInput.of("HrSeparation", (String) s.get("id"), "create", null, s));
            return get((String) s.get("id"));
        });
    }

    public Map<String, Object> addTask(String id, Map<String, Object> dto) {
        Map<String, Object> s = open(id);
        String task = str(dto.get("task"));
        if (task == null) {
            throw ApiException.badRequest("task is required");
        }
        rows.insert("HrSeparationTask", Map.of("separationId", s.get("id"), "task", task, "department",
                String.valueOf(dto.getOrDefault("department", "")), "sortOrder", 100));
        return get(id);
    }

    public Map<String, Object> updateTask(String id, String taskId, Map<String, Object> dto) {
        open(id);
        Map<String, Object> t = rows.require("HrSeparationTask", taskId, "Task not found");
        if (!id.equals(t.get("separationId"))) {
            throw ApiException.notFound("Task not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.containsKey("isDone")) {
            boolean done = Boolean.TRUE.equals(dto.get("isDone"));
            data.put("isDone", done);
            data.put("doneBy", done ? user() : null);
            data.put("doneAt", done ? Instant.now() : null);
        }
        if (dto.containsKey("note")) {
            data.put("note", str(dto.get("note")));
        }
        rows.update("HrSeparationTask", taskId, data);
        return get(id);
    }

    // ------------------------------------------------------------------ settlement

    /** Compute (or recompute) the final settlement. */
    public Map<String, Object> calculate(String id) {
        Map<String, Object> s = open(id);
        String empId = (String) s.get("employeeId");
        Map<String, Object> emp = rows.queryOne("SELECT * FROM \"HrEmployee\" WHERE id = ?", empId);
        Map<String, Object> st = setup.effectiveSettings();
        int scale = ((Number) st.get("roundingScale")).intValue();
        LocalDate last = HrRows.date(s.get("lastWorkingDay"));
        LocalDate hire = HrRows.date(emp.get("hireDate"));
        Map<String, Object> comp = compensation.compensationOn(empId, last);
        BigDecimal basic = comp != null ? dec(comp.get("basicSalary")) : HrRows.decOr(emp.get("baseSalary"),
                BigDecimal.ZERO);
        BigDecimal daily = basic.divide(BigDecimal.valueOf(30), MC);
        List<Map<String, Object>> lines = new ArrayList<>();

        // Salary from the day after the last paid period to the last working day.
        LocalDate paidUntil = paidUntil(empId);
        LocalDate from = paidUntil == null ? (hire != null && hire.isAfter(last.withDayOfMonth(1)) ? hire
                : last.withDayOfMonth(1)) : paidUntil.plusDays(1);
        if (!from.isAfter(last)) {
            BigDecimal months = HrPayrollEngineV2.monthsBetween(from, last);
            line(lines, "SALARY", "Salary " + from + " – " + last, "EARNING", basic.multiply(months, MC), scale, true,
                    months.multiply(BigDecimal.valueOf(30), MC).setScale(2, RoundingMode.HALF_UP));
        }
        // Leave encashment of paid leave types (policy encashable, or no policy).
        BigDecimal leaveDays = BigDecimal.ZERO;
        for (Map<String, Object> b : rows.query("SELECT b.*, t.\"isPaid\", p.encashable FROM \"HrLeaveBalance\" b JOIN"
                + " \"HrLeaveType\" t ON t.id = b.\"leaveTypeId\" LEFT JOIN \"HrLeavePolicy\" p ON p.\"leaveTypeId\" = t.id"
                + " AND p.\"deletedAt\" IS NULL WHERE b.\"organizationId\" = ? AND b.\"employeeId\" = ? AND b.year = ? AND"
                + " b.\"deletedAt\" IS NULL AND t.\"isPaid\" = true AND (p.id IS NULL OR p.encashable = true)", org(), empId,
                last.getYear())) {
            BigDecimal rem = dec(b.get("accruedDays")).add(dec(b.get("adjustedDays"))).subtract(dec(b.get("usedDays")));
            if (rem.signum() > 0) {
                leaveDays = leaveDays.add(rem);
            }
        }
        if (leaveDays.signum() > 0) {
            line(lines, "LEAVE", "Leave encashment (" + leaveDays.stripTrailingZeros().toPlainString() + " days)",
                    "EARNING", daily.multiply(leaveDays, MC), scale, true, leaveDays);
        }
        BigDecimal noticeDays = dec(s.get("noticePayDays"));
        if (noticeDays.signum() > 0) {
            line(lines, "NOTICE", "Notice pay in lieu", "EARNING", daily.multiply(noticeDays, MC), scale, true,
                    noticeDays);
        } else if (noticeDays.signum() < 0) {
            line(lines, "NOTICE_RECOVERY", "Short notice recovery", "DEDUCTION", daily.multiply(noticeDays.abs(), MC),
                    scale, false, noticeDays.abs());
        }
        String pack = (String) st.get("statutoryPack");
        BigDecimal gratuity = gratuity(pack, st, basic, hire, last);
        if (!"DISMISSAL".equals(s.get("separationType")) && gratuity.signum() > 0) {
            line(lines, "GRATUITY", "End-of-service gratuity", "EARNING", gratuity, scale, false, null);
        }
        BigDecimal taxable = sum(lines, "EARNING", true);
        BigDecimal gross = sum(lines, "EARNING", null);
        if (taxable.signum() > 0) {
            for (HrStatutory.Line l : HrStatutory.compute(pack, engine.rates(pack, last),
                    new HrStatutory.Input(taxable, taxable, taxable, 12, last, (String) emp.get("nationality")))) {
                if (!"INFO".equals(l.kind())) {
                    Map<String, Object> m = line(lines, l.code(), l.name(), l.kind(), l.amount(), scale, false, null);
                    m.put("statutoryCode", l.code());
                    m.put("payableKey", l.payableKey() == null ? l.fallbackPayableKey() : l.payableKey());
                    m.put("expenseKey", l.expenseKey());
                }
            }
        }
        for (Map<String, Object> l : rows.query("SELECT id, \"loanCode\" AS code, balance FROM \"HrEmployeeLoan\" WHERE"
                + " \"organizationId\" = ? AND \"employeeId\" = ? AND status::text = 'ACTIVE' AND balance > 0 AND"
                + " \"deletedAt\" IS NULL", org(), empId)) {
            line(lines, "LOAN", "Loan " + l.get("code"), "DEDUCTION", dec(l.get("balance")), scale, false, null)
                    .put("sourceId", l.get("id"));
        }
        for (Map<String, Object> a : rows.query("SELECT id, \"advanceCode\" AS code, balance FROM \"HrSalaryAdvance\" WHERE"
                + " \"organizationId\" = ? AND \"employeeId\" = ? AND status::text IN ('APPROVED', 'PAID') AND balance > 0"
                + " AND \"deletedAt\" IS NULL", org(), empId)) {
            line(lines, "ADVANCE", "Advance " + a.get("code"), "DEDUCTION", dec(a.get("balance")), scale, false, null)
                    .put("sourceId", a.get("id"));
        }
        BigDecimal deductions = sum(lines, "DEDUCTION", null);
        BigDecimal net = gross.subtract(deductions);
        Map<String, Object> settlement = new LinkedHashMap<>();
        settlement.put("basicMonthly", basic);
        settlement.put("dailyRate", daily.setScale(scale + 2, RoundingMode.HALF_UP));
        settlement.put("serviceYears", hire == null ? null : BigDecimal.valueOf(ChronoUnit.DAYS.between(hire, last) + 1)
                .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP));
        settlement.put("lines", lines);
        settlement.put("gross", gross);
        settlement.put("deductions", deductions);
        settlement.put("employerContributions", sum(lines, "EMPLOYER_CONTRIBUTION", null));
        settlement.put("net", net);
        settlement.put("calculatedBy", user());
        settlement.put("calculatedAt", Instant.now().toString());
        return hr.inTx(() -> {
            rows.update("HrSeparation", id, Map.of("settlement", settlement, "netAmount", net, "status", "CALCULATED"));
            return get(id);
        });
    }

    /** Approve: checklist done, maker-checker, GL journal, recoveries closed, employee terminated. */
    public Map<String, Object> approve(String id) {
        Map<String, Object> s = open(id);
        if (!"CALCULATED".equals(s.get("status"))) {
            throw ApiException.badRequest("Calculate the settlement before approving it");
        }
        if (rows.count("HrSeparationTask", "\"separationId\" = ? AND \"isDone\" = false", id) > 0) {
            throw ApiException.badRequest("Complete the clearance checklist first");
        }
        Map<String, Object> settlement = com.inventoryerp.pos.application.PosSql.map(s.get("settlement"));
        Map<String, Object> st = setup.settingsRow();
        boolean separate = st == null || Boolean.TRUE.equals(st.get("requireSeparateApprover"));
        if (separate && user() != null && user().equals(settlement.get("calculatedBy"))) {
            throw ApiException.forbidden("A settlement must be approved by a different user than the one who calculated"
                    + " it");
        }
        String empId = (String) s.get("employeeId");
        String salaryExpense = accounts.byMapping("salary_expense");
        String netPayable = accounts.byMapping("net_pay_payable");
        List<PostingRequest.Line> jl = new ArrayList<>();
        BigDecimal provision = provisionBalance(empId);
        for (Object o : com.inventoryerp.pos.application.PosSql.list(settlement.get("lines"))) {
            Map<String, Object> l = com.inventoryerp.pos.application.PosSql.map(o);
            BigDecimal amount = dec(l.get("amount"));
            if (amount.signum() <= 0) {
                continue;
            }
            String code = String.valueOf(l.get("code"));
            String kind = String.valueOf(l.get("kind"));
            if ("GRATUITY".equals(code)) {
                BigDecimal fromProvision = amount.min(provision);
                if (fromProvision.signum() > 0) {
                    jl.add(PostingRequest.Line.debit(accounts.byMapping("gratuity_provision"), fromProvision,
                            "Gratuity paid from provision"));
                }
                BigDecimal rest = amount.subtract(fromProvision);
                if (rest.signum() > 0) {
                    jl.add(PostingRequest.Line.debit(expenseAccount("gratuity_expense", salaryExpense), rest,
                            "Gratuity expense"));
                }
            } else if ("EARNING".equals(kind)) {
                jl.add(PostingRequest.Line.debit(salaryExpense, amount, String.valueOf(l.get("name"))));
            } else if ("EMPLOYER_CONTRIBUTION".equals(kind)) {
                jl.add(PostingRequest.Line.debit(expenseAccount((String) l.get("expenseKey"), salaryExpense), amount,
                        String.valueOf(l.get("name"))));
                jl.add(PostingRequest.Line.credit(accounts.byMapping(String.valueOf(l.get("payableKey"))), amount,
                        String.valueOf(l.get("name"))));
            } else if ("DEDUCTION".equals(kind)) {
                String account = switch (code) {
                    case "LOAN" -> accounts.byMapping("employee_loan_receivable");
                    case "ADVANCE" -> accounts.byMapping("employee_advance_receivable");
                    case "NOTICE_RECOVERY" -> salaryExpense;
                    default -> accounts.byMapping(String.valueOf(l.get("payableKey")));
                };
                jl.add(PostingRequest.Line.credit(account, amount, String.valueOf(l.get("name"))));
            }
        }
        BigDecimal net = dec(s.get("netAmount"));
        if (net.signum() > 0) {
            jl.add(PostingRequest.Line.credit(netPayable, net, "Final settlement payable"));
        } else if (net.signum() < 0) {
            jl.add(PostingRequest.Line.debit(accounts.byMapping("employee_advance_receivable"), net.negate(),
                    "Settlement owed by the employee"));
        }
        LocalDate last = HrRows.date(s.get("lastWorkingDay"));
        return hr.inTx(() -> {
            Map<String, Object> journal = posting.post(new PostingRequest("GEN", Instant.now(), "Final settlement "
                    + s.get("separationNumber"), null, null, "hr_separation", id, "primary", "hr_separation:" + id, null,
                    null, null, jl));
            rows.jdbc().sql("UPDATE \"HrEmployeeLoan\" SET balance = 0, status = 'PAID', \"updatedAt\" = CURRENT_TIMESTAMP"
                    + " WHERE \"organizationId\" = ? AND \"employeeId\" = ? AND status::text = 'ACTIVE'").params(org(),
                    empId).update();
            rows.jdbc().sql("UPDATE \"HrSalaryAdvance\" SET balance = 0, status = 'SETTLED', \"updatedAt\" ="
                    + " CURRENT_TIMESTAMP WHERE \"organizationId\" = ? AND \"employeeId\" = ? AND status::text IN"
                    + " ('APPROVED', 'PAID') AND balance > 0").params(org(), empId).update();
            BigDecimal provisionUsed = provision;
            if (provisionUsed.signum() > 0) {
                rows.insert("HrGratuityProvision", Map.of("employeeId", empId, "asOf", last.plusDays(1),
                        "liability", BigDecimal.ZERO, "movement", provisionUsed.negate(), "journalEntryId",
                        journal.get("id")));
            }
            Map<String, Object> row = rows.update("HrSeparation", id, Map.of("status", "APPROVED", "journalEntryId",
                    journal.get("id"), "approvedBy", user(), "approvedAt", Instant.now()));
            Map<String, Object> e = rows.queryOne("SELECT \"employmentStatus\" FROM \"HrEmployee\" WHERE id = ?", empId);
            if (!"TERMINATED".equals(String.valueOf(e.get("employmentStatus")))) {
                lifecycle.terminate(empId, new HrLifecycleService.Change(String.valueOf(s.get("separationType")) + ": "
                        + (s.get("reason") == null ? "final settlement" : s.get("reason")), true, null,
                        "RESIGNATION".equals(s.get("separationType")), null, last.toString()));
            }
            hr.auditInTx(AuditInput.of("HrSeparation", id, "approve", Map.of("status", "CALCULATED"), row));
            return get(id);
        });
    }

    /** Pay the approved settlement: Dr net pay payable, Cr the account. */
    public Map<String, Object> pay(String id, Map<String, Object> dto) {
        Map<String, Object> s = get(id);
        if (!"APPROVED".equals(s.get("status"))) {
            throw ApiException.badRequest("Only an approved settlement can be paid");
        }
        String accountId = str(dto.get("accountId"));
        if (accountId == null) {
            throw ApiException.badRequest("accountId is required");
        }
        BigDecimal net = dec(s.get("netAmount"));
        return hr.inTx(() -> {
            Object journalId = null;
            if (net.signum() > 0) {
                Map<String, Object> j = posting.post(new PostingRequest("GEN", Instant.now(), "Settlement paid "
                        + s.get("separationNumber"), null, null, "hr_separation_payment", id, "primary",
                        "hr_separation_payment:" + id, null, null, null, List.of(
                                PostingRequest.Line.debit(accounts.byMapping("net_pay_payable"), net, "Settlement payable"),
                                PostingRequest.Line.credit(accountId, net, "Final settlement paid"))));
                journalId = j.get("id");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "PAID");
            data.put("paidAt", Instant.now());
            data.put("paymentJournalId", journalId);
            rows.update("HrSeparation", id, data);
            hr.auditInTx(AuditInput.of("HrSeparation", id, "post", Map.of("status", "APPROVED"), data));
            return get(id);
        });
    }

    public Map<String, Object> cancel(String id, Map<String, Object> dto) {
        Map<String, Object> s = open(id);
        rows.update("HrSeparation", id, Map.of("status", "CANCELLED", "reason",
                String.valueOf(s.get("reason") == null ? "" : s.get("reason")) + " · cancelled: "
                        + String.valueOf(dto.getOrDefault("reason", ""))));
        return get(id);
    }

    private Map<String, Object> open(String id) {
        Map<String, Object> s = rows.require("HrSeparation", id, "Separation not found");
        if (List.of("APPROVED", "PAID", "CANCELLED").contains(String.valueOf(s.get("status")))) {
            throw ApiException.badRequest("The separation is " + s.get("status"));
        }
        return s;
    }

    // ------------------------------------------------------------------ gratuity provision

    BigDecimal gratuity(String pack, Map<String, Object> settings, BigDecimal basic, LocalDate hire, LocalDate on) {
        Map<String, Object> eosb = null;
        for (HrStatutory.Rate r : engine.rates(pack, on)) {
            if ("EOSB".equals(r.code())) {
                eosb = r.params();
            }
        }
        return HrStatutory.gratuity(eosb, HrRows.dec(settings.get("gratuityDaysPerYear")), basic, hire, on);
    }

    BigDecimal provisionBalance(String empId) {
        Map<String, Object> r = rows.queryOne("SELECT coalesce(sum(movement), 0) AS b FROM \"HrGratuityProvision\" WHERE"
                + " \"organizationId\" = ? AND \"employeeId\" = ? AND \"deletedAt\" IS NULL", org(), empId);
        return dec(r.get("b"));
    }

    /** Month-end provision: liability per active employee on the statutory / contractual formula; posts the movement. */
    public Map<String, Object> accrueProvision(LocalDate asOf) {
        Map<String, Object> st = setup.effectiveSettings();
        String pack = (String) st.get("statutoryPack");
        int scale = ((Number) st.get("roundingScale")).intValue();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal movementTotal = BigDecimal.ZERO;
        List<Map<String, Object>> detail = new ArrayList<>();
        for (Map<String, Object> e : rows.query("SELECT id, \"employeeCode\", \"hireDate\", \"baseSalary\" FROM"
                + " \"HrEmployee\" WHERE \"organizationId\" = ? AND \"isActive\" = true AND \"deletedAt\" IS NULL", org())) {
            String empId = (String) e.get("id");
            if (rows.first("HrGratuityProvision", "\"employeeId\" = ? AND \"asOf\" = ?", null, empId, asOf) != null) {
                continue;
            }
            Map<String, Object> comp = compensation.compensationOn(empId, asOf);
            BigDecimal basic = comp != null ? dec(comp.get("basicSalary")) : HrRows.decOr(e.get("baseSalary"),
                    BigDecimal.ZERO);
            BigDecimal liability = gratuity(pack, st, basic, HrRows.date(e.get("hireDate")), asOf)
                    .setScale(scale, RoundingMode.HALF_UP);
            BigDecimal movement = liability.subtract(provisionBalance(empId));
            if (movement.signum() == 0 && liability.signum() == 0) {
                continue;
            }
            total = total.add(liability);
            movementTotal = movementTotal.add(movement);
            detail.add(new LinkedHashMap<>(Map.of("employeeId", empId, "employeeCode", e.get("employeeCode"),
                    "liability", liability, "movement", movement)));
        }
        if (detail.isEmpty()) {
            return Map.of("asOf", asOf.toString(), "employees", 0, "movement", BigDecimal.ZERO);
        }
        String provisionAcc = accounts.byMapping("gratuity_provision");
        String expenseAcc = expenseAccount("gratuity_expense", accounts.byMapping("salary_expense"));
        BigDecimal mt = movementTotal;
        BigDecimal tt = total;
        return hr.inTx(() -> {
            Object journalId = null;
            if (mt.signum() != 0) {
                List<PostingRequest.Line> jl = mt.signum() > 0
                        ? List.of(PostingRequest.Line.debit(expenseAcc, mt, "Gratuity expense"),
                                PostingRequest.Line.credit(provisionAcc, mt, "Gratuity provision"))
                        : List.of(PostingRequest.Line.debit(provisionAcc, mt.negate(), "Gratuity provision release"),
                                PostingRequest.Line.credit(expenseAcc, mt.negate(), "Gratuity expense"));
                journalId = posting.post(new PostingRequest("GEN", HrPayrollEngineV2.utc(asOf), "Gratuity provision "
                        + asOf, null, null, "hr_gratuity", asOf.toString(), "primary", "hr_gratuity:" + asOf, null, null,
                        null, jl)).get("id");
            }
            for (Map<String, Object> d : detail) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("employeeId", d.get("employeeId"));
                row.put("asOf", asOf);
                row.put("liability", d.get("liability"));
                row.put("movement", d.get("movement"));
                row.put("journalEntryId", journalId);
                rows.insert("HrGratuityProvision", row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("asOf", asOf.toString());
            out.put("employees", detail.size());
            out.put("liability", tt);
            out.put("movement", mt);
            out.put("journalEntryId", journalId);
            out.put("rows", detail);
            return out;
        });
    }

    public Map<String, Object> provisions(Map<String, String> q) {
        List<Map<String, Object>> list = rows.query("SELECT DISTINCT ON (g.\"employeeId\") g.*, e.\"employeeCode\","
                + " e.\"firstName\", e.\"lastName\" FROM \"HrGratuityProvision\" g JOIN \"HrEmployee\" e ON e.id ="
                + " g.\"employeeId\" WHERE g.\"organizationId\" = ? AND g.\"deletedAt\" IS NULL ORDER BY g.\"employeeId\","
                + " g.\"asOf\" DESC, g.\"createdAt\" DESC", org());
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : list) {
            total = total.add(dec(r.get("liability")));
        }
        Map<String, Object> out = HrRows.page(list, list.size());
        out.put("liability", total);
        return out;
    }

    private String expenseAccount(String key, String fallback) {
        String id = key == null ? null : accounts.byMappingOptional(key);
        return id == null ? fallback : id;
    }

    private LocalDate paidUntil(String empId) {
        Map<String, Object> r = rows.queryOne("SELECT max(p.\"endDate\") AS e FROM \"HrPayrollItem\" i JOIN"
                + " \"HrPayrollRun\" r ON r.id = i.\"runId\" JOIN \"HrPayrollPeriod\" p ON p.id = r.\"periodId\" WHERE"
                + " i.\"organizationId\" = ? AND i.\"employeeId\" = ? AND r.status::text IN ('APPROVED', 'PAID')", org(),
                empId);
        return r == null ? null : HrRows.date(r.get("e"));
    }

    private static Map<String, Object> line(List<Map<String, Object>> lines, String code, String name, String kind,
            BigDecimal amount, int scale, boolean taxable, BigDecimal quantity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("kind", kind);
        m.put("amount", amount.setScale(scale, RoundingMode.HALF_UP));
        m.put("taxable", taxable);
        m.put("quantity", quantity);
        lines.add(m);
        return m;
    }

    private static BigDecimal sum(List<Map<String, Object>> lines, String kind, Boolean taxable) {
        BigDecimal s = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            if (kind.equals(l.get("kind")) && (taxable == null || taxable.equals(l.get("taxable")))) {
                s = s.add((BigDecimal) l.get("amount"));
            }
        }
        return s;
    }
}
