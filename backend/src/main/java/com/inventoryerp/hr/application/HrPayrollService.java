package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrDb.nz;
import static com.inventoryerp.hr.application.HrDb.org;
import static com.inventoryerp.hr.application.HrDb.truthy;
import static com.inventoryerp.hr.application.HrDb.user;
import static com.inventoryerp.pos.application.PosSql.decOf;
import static com.inventoryerp.pos.application.PosSql.num;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.hr.domain.HrAttendance;
import com.inventoryerp.hr.domain.HrBankPayment;
import com.inventoryerp.hr.domain.HrBankPaymentLine;
import com.inventoryerp.hr.domain.HrDepartment;
import com.inventoryerp.hr.domain.HrEmployee;
import com.inventoryerp.hr.domain.HrEmployeeLoan;
import com.inventoryerp.hr.domain.HrPayrollAllowance;
import com.inventoryerp.hr.domain.HrPayrollComponent;
import com.inventoryerp.hr.domain.HrPayrollDeduction;
import com.inventoryerp.hr.domain.HrPayrollItem;
import com.inventoryerp.hr.domain.HrPayrollPeriod;
import com.inventoryerp.hr.domain.HrPayrollRun;
import com.inventoryerp.hr.domain.HrPayslip;
import com.inventoryerp.hr.domain.HrPosition;
import com.inventoryerp.hr.domain.HrSalaryAdvance;
import com.inventoryerp.hr.domain.HrTaxBracket;
import com.inventoryerp.hr.domain.HrTaxTable;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

/**
 * The payroll engine (port of {@code hr-payroll.service.ts}): components, tax tables, periods, runs (calculate →
 * approve → payslips + GL journal → bank payment), salary advances and employee loans. Amounts are JS doubles stored
 * through {@code new Decimal(number)}, exactly as Nest computes them.
 *
 * <p>GL ({@code payroll_run:<runId>}, journal GEN): Dr salaries expense (gross); Cr net pay / PAYE / pension / SSF /
 * insurance payables, loan / advance receivables, other deductions contra.
 */
@Service
public class HrPayrollService {

    static final List<String> COMPONENT_TYPES = List.of("ALLOWANCE", "DEDUCTION");
    static final List<String> CALC_METHODS = List.of("FIXED", "PERCENTAGE");
    static final List<String> TAX_TYPES = List.of("PAYE", "PENSION", "SOCIAL_SECURITY", "LOCAL");
    static final List<String> PERIOD_TYPES = List.of("WEEKLY", "BIWEEKLY", "MONTHLY", "CUSTOM");
    static final List<String> PAYMENT_METHODS = List.of("BANK", "MOBILE_MONEY", "CASH", "CHEQUE");
    static final List<String> BANK_PAYMENT_STATUSES = List.of("DRAFT", "GENERATED", "SENT", "PAID");
    static final double MONTHLY_HOURS = 173.33;
    static final int MONTHS_PER_YEAR = 12;
    static final String[] EMPLOYEE_REF = HrAttendanceService.EMPLOYEE_REF;
    static final String[] EMPLOYEE_ITEM_REF = {"id", "employeeCode", "firstName", "lastName"};
    static final String[] EMPLOYEE_BANK_REF = {"id", "employeeCode", "firstName", "lastName", "bankName",
        "bankAccountName", "bankAccountNumber", "mobileMoneyProvider", "mobileMoneyNumber"};

    private final HrDb hr;
    private final SequenceService seq;
    private final PostingService posting;
    private final AccountDeterminationService accounts;
    private final HrPayrollInputService payrollInputs;
    private final HrOvertimeService overtime;

    public HrPayrollService(HrDb hr, SequenceService seq, PostingService posting,
            AccountDeterminationService accounts, HrPayrollInputService payrollInputs, HrOvertimeService overtime) {
        this.hr = hr;
        this.seq = seq;
        this.posting = posting;
        this.accounts = accounts;
        this.payrollInputs = payrollInputs;
        this.overtime = overtime;
    }

    private static String invalid(String what, Object v) {
        return "Invalid " + what + ": " + (v == null ? "undefined" : PosSql.jsString(v));
    }

    // ─── components ───

    public Map<String, Object> listComponents(Map<String, String> query) {
        HrDb.Where w = hr.where(HrPayrollComponent.class).eqIf("componentType", query.get("componentType"));
        return HrDb.rows(w.list("name ASC", HrDb.take(query.get("take"), 100, 200), HrDb.skip(query.get("skip"))),
                w.count());
    }

    public Map<String, Object> createComponent(Map<String, Object> dto) {
        if (!truthy(dto.get("code")) || !truthy(dto.get("name"))) {
            throw ApiException.badRequest("code and name are required");
        }
        if (!truthy(dto.get("componentType")) || !COMPONENT_TYPES.contains(dto.get("componentType"))) {
            throw ApiException.badRequest(invalid("componentType", dto.get("componentType")));
        }
        if (truthy(dto.get("calcMethod")) && !CALC_METHODS.contains(dto.get("calcMethod"))) {
            throw ApiException.badRequest(invalid("calcMethod", dto.get("calcMethod")));
        }
        if ("FIXED".equals(dto.get("calcMethod")) && !dto.containsKey("amount")) {
            throw ApiException.badRequest("FIXED components need an amount");
        }
        if ("PERCENTAGE".equals(dto.get("calcMethod")) && !dto.containsKey("rate")) {
            throw ApiException.badRequest("PERCENTAGE components need a rate");
        }
        if (hr.where(HrPayrollComponent.class).eq("code", dto.get("code")).first() != null) {
            throw ApiException.badRequest("Component code \"" + PosSql.jsString(dto.get("code")) + "\" already exists");
        }
        return hr.insert(HrPayrollComponent.class, Obj.of("organizationId", org(),
                "code", HrOrgService.upper(dto.get("code")), "name", dto.get("name"),
                "componentType", dto.get("componentType"), "calcMethod", nz(dto.get("calcMethod"), "FIXED"),
                "amount", dto.get("amount"), "rate", dto.get("rate"), "isTaxable", nz(dto.get("isTaxable"), true),
                "isRecurring", nz(dto.get("isRecurring"), true), "appliesTo", dto.get("appliesTo"),
                "isActive", nz(dto.get("isActive"), true), "createdBy", user()));
    }

    public Map<String, Object> updateComponent(String id, Map<String, Object> dto) {
        if (hr.byId(HrPayrollComponent.class, id) == null) {
            throw ApiException.notFound("Component not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (String f : List.of("name", "componentType", "calcMethod", "amount", "rate", "isTaxable", "isRecurring",
                "appliesTo", "isActive")) {
            if (dto.containsKey(f)) {
                data.put(f, dto.get(f));
            }
        }
        data.put("updatedBy", user());
        return hr.update(HrPayrollComponent.class, id, data);
    }

    public Map<String, Object> deleteComponent(String id) {
        if (hr.byId(HrPayrollComponent.class, id) == null) {
            throw ApiException.notFound("Component not found");
        }
        return hr.softDelete(HrPayrollComponent.class, id, Obj.of("isActive", false, "updatedBy", user()));
    }

    // ─── tax tables ───

    private List<Map<String, Object>> brackets(Object tableId) {
        return hr.many(HrTaxBracket.class, "taxTableId", tableId, null, "\"fromAmount\" ASC");
    }

    private Map<String, Object> withBrackets(Map<String, Object> table) {
        if (table != null) {
            table.put("brackets", brackets(table.get("id")));
        }
        return table;
    }

    public Map<String, Object> listTaxTables(Map<String, String> query) {
        HrDb.Where w = hr.where(HrTaxTable.class).eqIf("taxType", query.get("taxType"));
        List<Map<String, Object>> rows = w.list("\"effectiveFrom\" DESC", HrDb.take(query.get("take"), 100, 200),
                HrDb.skip(query.get("skip")));
        rows.forEach(this::withBrackets);
        return HrDb.rows(rows, w.count());
    }

    private void createBrackets(String tableId, Object raw) {
        for (Object o : PosSql.list(raw)) {
            Map<String, Object> b = PosSql.map(o);
            hr.insert(HrTaxBracket.class, Obj.of("organizationId", org(), "taxTableId", tableId,
                    "fromAmount", b.get("fromAmount"), "toAmount", b.get("toAmount"), "rate", b.get("rate"),
                    "createdBy", user()));
        }
    }

    public Map<String, Object> createTaxTable(Map<String, Object> dto) {
        if (!truthy(dto.get("code")) || !truthy(dto.get("name")) || !truthy(dto.get("effectiveFrom"))) {
            throw ApiException.badRequest("code, name and effectiveFrom are required");
        }
        if (truthy(dto.get("taxType")) && !TAX_TYPES.contains(dto.get("taxType"))) {
            throw ApiException.badRequest(invalid("taxType", dto.get("taxType")));
        }
        if (hr.where(HrTaxTable.class).eq("code", dto.get("code")).first() != null) {
            throw ApiException.badRequest("Tax table code \"" + PosSql.jsString(dto.get("code")) + "\" already exists");
        }
        Object brackets = dto.get("brackets") instanceof List<?> l ? l : List.of();
        return hr.inTx(() -> {
            Map<String, Object> table = hr.insert(HrTaxTable.class, Obj.of("organizationId", org(),
                    "code", HrOrgService.upper(dto.get("code")), "name", dto.get("name"),
                    "countryCode", dto.get("countryCode"), "taxType", nz(dto.get("taxType"), "PAYE"),
                    "effectiveFrom", HrDb.jsDate(dto.get("effectiveFrom")), "isActive", nz(dto.get("isActive"), true),
                    "createdBy", user()));
            createBrackets((String) table.get("id"), brackets);
            return withBrackets(hr.byId(HrTaxTable.class, table.get("id")));
        });
    }

    public Map<String, Object> updateTaxTable(String id, Map<String, Object> dto) {
        Map<String, Object> row = hr.byId(HrTaxTable.class, id);
        if (row == null) {
            throw ApiException.notFound("Tax table not found");
        }
        return hr.inTx(() -> {
            hr.update(HrTaxTable.class, id, Obj.of("name", nz(dto.get("name"), row.get("name")),
                    "countryCode", HrDb.def(dto, "countryCode", row.get("countryCode")),
                    "taxType", nz(dto.get("taxType"), row.get("taxType")),
                    "effectiveFrom", truthy(dto.get("effectiveFrom")) ? HrDb.jsDate(dto.get("effectiveFrom"))
                            : row.get("effectiveFrom"),
                    "isActive", nz(dto.get("isActive"), row.get("isActive")), "updatedBy", user()));
            if (dto.get("brackets") instanceof List<?> list) {
                hr.db().updateMany(HrTaxBracket.class, Obj.of("deletedAt", PosDb.raw(PosSql.NOW)),
                        "\"taxTableId\" = ? AND \"organizationId\" = ?", id, org());
                createBrackets(id, list);
            }
            return withBrackets(hr.byId(HrTaxTable.class, id));
        });
    }

    public Map<String, Object> deleteTaxTable(String id) {
        if (hr.byId(HrTaxTable.class, id) == null) {
            throw ApiException.notFound("Tax table not found");
        }
        return hr.softDelete(HrTaxTable.class, id, Obj.of("isActive", false, "updatedBy", user()));
    }

    /** Progressive tax: sum of (bracket ∩ income) × rate. */
    static double computeProgressive(double income, List<Map<String, Object>> brackets) {
        double tax = 0;
        for (Map<String, Object> b : brackets) {
            double from = num(b.get("fromAmount"));
            double to = b.get("toAmount") == null ? Double.POSITIVE_INFINITY : num(b.get("toAmount"));
            double low = Math.max(from, 0);
            double high = Math.min(to, income);
            if (high > low) {
                tax += (high - low) * num(b.get("rate"));
            }
        }
        return tax;
    }

    private Map<String, Object> activeTaxTable(String taxType, Instant runDate) {
        return withBrackets(hr.where(HrTaxTable.class).eq("taxType", taxType).eq("isActive", true)
                .and("\"effectiveFrom\" <= ?", SqlRows.ts(runDate)).first("\"effectiveFrom\" DESC"));
    }

    // ─── periods ───

    public Map<String, Object> listPeriods(Map<String, String> query) {
        HrDb.Where w = hr.where(HrPayrollPeriod.class);
        List<Map<String, Object>> rows = w.list("\"startDate\" DESC", HrDb.take(query.get("take"), 50, 200),
                HrDb.skip(query.get("skip")));
        hr.counts(rows, "runs", "HrPayrollRun", "periodId");
        return HrDb.rows(rows, w.count());
    }

    public Map<String, Object> createPeriod(Map<String, Object> dto) {
        if (!truthy(dto.get("startDate")) || !truthy(dto.get("endDate"))) {
            throw ApiException.badRequest("startDate and endDate are required");
        }
        if (truthy(dto.get("periodType")) && !PERIOD_TYPES.contains(dto.get("periodType"))) {
            throw ApiException.badRequest(invalid("periodType", dto.get("periodType")));
        }
        Instant startDate = HrDb.jsDate(dto.get("startDate"));
        Instant endDate = HrDb.jsDate(dto.get("endDate"));
        if (endDate.isBefore(startDate)) {
            throw ApiException.badRequest("endDate must be after startDate");
        }
        var s = startDate.atOffset(ZoneOffset.UTC);
        Object code = dto.get("periodCode") != null ? dto.get("periodCode")
                : String.format("PRD-%d-%02d", s.getYear(), s.getMonthValue());
        if (hr.where(HrPayrollPeriod.class).eq("periodCode", code).first() != null) {
            throw ApiException.badRequest("Period code \"" + PosSql.jsString(code) + "\" already exists");
        }
        return hr.insert(HrPayrollPeriod.class, Obj.of("organizationId", org(), "periodCode", code,
                "periodType", nz(dto.get("periodType"), "MONTHLY"), "startDate", startDate, "endDate", endDate,
                "status", nz(dto.get("status"), "OPEN"), "createdBy", user()));
    }

    public Map<String, Object> updatePeriod(String id, Map<String, Object> dto) {
        if (hr.byId(HrPayrollPeriod.class, id) == null) {
            throw ApiException.notFound("Period not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.containsKey("startDate")) {
            data.put("startDate", HrDb.jsDate(dto.get("startDate")));
        }
        if (dto.containsKey("endDate")) {
            data.put("endDate", HrDb.jsDate(dto.get("endDate")));
        }
        if (dto.containsKey("status")) {
            data.put("status", dto.get("status"));
        }
        data.put("updatedBy", user());
        return hr.update(HrPayrollPeriod.class, id, data);
    }

    // ─── runs ───

    public Map<String, Object> listRuns(Map<String, String> query) {
        HrDb.Where w = hr.where(HrPayrollRun.class).eqIf("periodId", query.get("periodId"))
                .eqIf("status", query.get("status"));
        List<Map<String, Object>> rows = w.list("\"createdAt\" DESC", HrDb.take(query.get("take"), 50, 200),
                HrDb.skip(query.get("skip")));
        hr.oneAll(rows, "period", HrPayrollPeriod.class, "periodId");
        hr.counts(rows, "items", "HrPayrollItem", "runId");
        return HrDb.rows(rows, w.count());
    }

    /** Items of a run with employee / allowances / deductions / payslip includes. */
    private List<Map<String, Object>> runItems(String runId, boolean scoped, String[] employeeCols, String orderBy) {
        List<Map<String, Object>> items = scoped
                ? hr.db().list(HrPayrollItem.class, "\"runId\" = ? AND \"organizationId\" = ?"
                        + (orderBy == null ? "" : " ORDER BY " + orderBy), runId, org())
                : hr.many(HrPayrollItem.class, "runId", runId, null, orderBy);
        hr.oneAll(items, "employee", HrEmployee.class, "employeeId", employeeCols);
        for (Map<String, Object> it : items) {
            it.put("allowances", hr.many(HrPayrollAllowance.class, "itemId", it.get("id"), null, null));
            it.put("deductions", hr.many(HrPayrollDeduction.class, "itemId", it.get("id"), null, null));
        }
        return items;
    }

    private void withPayslips(List<Map<String, Object>> items) {
        for (Map<String, Object> it : items) {
            it.put("payslip", hr.db().first(HrPayslip.class, "\"itemId\" = ?", it.get("id")));
        }
    }

    private List<Map<String, Object>> bankPayments(String runId, boolean scoped) {
        List<Map<String, Object>> rows = scoped
                ? hr.db().list(HrBankPayment.class, "\"runId\" = ? AND \"organizationId\" = ?", runId, org())
                : hr.many(HrBankPayment.class, "runId", runId, null, null);
        rows.forEach(bp -> bp.put("lines", hr.many(HrBankPaymentLine.class, "bankPaymentId", bp.get("id"), null, null)));
        return rows;
    }

    public Map<String, Object> getRun(String id) {
        Map<String, Object> row = hr.byId(HrPayrollRun.class, id);
        if (row == null) {
            throw ApiException.notFound("Payroll run not found");
        }
        hr.one(row, "period", HrPayrollPeriod.class, row.get("periodId"));
        List<Map<String, Object>> items = runItems(id, false, new String[] {"id", "firstName", "lastName",
            "employeeCode", "departmentId"}, "\"createdAt\" ASC");
        withPayslips(items);
        row.put("items", items);
        row.put("bankPayments", bankPayments(id, false));
        return row;
    }

    public Map<String, Object> createRun(Map<String, Object> dto) {
        if (!truthy(dto.get("periodId"))) {
            throw ApiException.badRequest("periodId is required");
        }
        Map<String, Object> period = hr.byId(HrPayrollPeriod.class, dto.get("periodId"));
        if (period == null) {
            throw ApiException.notFound("Payroll period not found");
        }
        Map<String, Object> existing = hr.where(HrPayrollRun.class).eq("periodId", period.get("id")).first();
        if (existing != null && !"REVERSED".equals(existing.get("status"))) {
            throw ApiException.badRequest("A run already exists for this period (reverse it first)");
        }
        String runNumber = seq.next("hr_payroll_run", "PR-", 6);
        return hr.insert(HrPayrollRun.class, Obj.of("organizationId", org(), "runNumber", runNumber,
                "periodId", period.get("id"), "status", "DRAFT",
                "paymentDate", truthy(dto.get("paymentDate")) ? HrDb.jsDate(dto.get("paymentDate")) : null,
                "notes", dto.get("notes"), "createdBy", user()));
    }

    private static boolean componentApplies(Object appliesTo, Map<String, Object> emp) {
        String[] parts = String.valueOf(appliesTo).split(":", -1);
        String key = parts[0];
        String value = parts.length > 1 ? parts[1] : null;
        if (key.isEmpty() || value == null || value.isEmpty()) {
            return true;
        }
        return switch (key) {
            case "departmentId" -> value.equals(emp.get("departmentId"));
            case "positionId" -> value.equals(emp.get("positionId"));
            case "employmentType" -> value.equals(emp.get("employmentType"));
            default -> true;
        };
    }

    private static double percentOrFixed(Map<String, Object> c, double base) {
        return "PERCENTAGE".equals(c.get("calcMethod")) ? base * (num(c.get("rate")) / 100)
                : num(nz(c.get("amount"), 0));
    }

    private static double jsRound(double v) {
        return Math.floor(v + 0.5);
    }

    /** Calculate every active employee's pay; re-running wipes and recomputes the items. */
    public Map<String, Object> calculateRun(String id) {
        String orgId = org();
        String userId = user();
        Map<String, Object> run = hr.byId(HrPayrollRun.class, id);
        if (run == null) {
            throw ApiException.notFound("Payroll run not found");
        }
        if (!"DRAFT".equals(run.get("status")) && !"CALCULATED".equals(run.get("status"))) {
            throw ApiException.badRequest("Only DRAFT/CALCULATED runs can be recalculated");
        }
        Map<String, Object> period = hr.db().first(HrPayrollPeriod.class, "id = ?", run.get("periodId"));
        Instant runDate = Instant.now();
        Instant monthStart = (Instant) period.get("startDate");
        Instant monthEnd = (Instant) period.get("endDate");
        return hr.inTx(() -> {
            for (Map<String, Object> si : hr.where(HrPayrollItem.class).eq("runId", id).list(null)) {
                Object itemId = si.get("id");
                hr.jdbc().sql("DELETE FROM \"HrPayrollAllowance\" WHERE \"itemId\" = ? AND \"organizationId\" = ?")
                        .params(itemId, orgId).update();
                hr.jdbc().sql("DELETE FROM \"HrPayrollDeduction\" WHERE \"itemId\" = ? AND \"organizationId\" = ?")
                        .params(itemId, orgId).update();
                hr.jdbc().sql("DELETE FROM \"HrPayslip\" WHERE \"itemId\" = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL").params(itemId, orgId).update();
                hr.jdbc().sql("DELETE FROM \"HrPayrollItem\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL").params(itemId, orgId).update();
            }
            // Additive (ADR-S009 §2): payroll inputs queued by other modules; none → the Nest calculation unchanged.
            payrollInputs.releaseRun(id);
            List<Map<String, Object>> inputs = payrollInputs.pendingUntil(monthEnd);
            List<Map<String, Object>> employees = hr.where(HrEmployee.class).eq("isActive", true).list(null);
            List<Map<String, Object>> components = hr.where(HrPayrollComponent.class).eq("isActive", true).list(null);
            Map<String, Object> payeTable = activeTaxTable("PAYE", runDate);
            List<Map<String, Object>> advances = hr.where(HrSalaryAdvance.class)
                    .and("status::text IN ('APPROVED', 'PAID')")
                    .and("(\"paidAt\" IS NULL AND \"approvedAt\" <= ?) OR \"paidAt\" <= ?", SqlRows.ts(runDate),
                            SqlRows.ts(runDate)).list(null);
            List<Map<String, Object>> loans = hr.where(HrEmployeeLoan.class).eq("status", "ACTIVE").list(null);
            // Additive (HR-3): overtime rules; none → the Nest 1.5 multiplier and its Int-hours failure unchanged.
            List<Map<String, Object>> overtimeRules = overtime.activeRules();
            Predicate<LocalDate> holidays = overtimeRules.isEmpty() ? d -> false : overtime.holidays();
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> emp : employees) {
                Object payFreq = nz(emp.get("payFrequency"), "MONTHLY");
                double baseSalary = truthy(emp.get("baseSalary")) && num(emp.get("baseSalary")) != 0
                        ? num(emp.get("baseSalary")) : 0;
                double hourlyRate = truthy(emp.get("hourlyRate")) && num(emp.get("hourlyRate")) != 0
                        ? num(emp.get("hourlyRate")) : 0;
                Map<String, Object> att = SqlRows.one(hr.jdbc().sql("SELECT sum(\"workedMinutes\") AS w,"
                        + " sum(\"overtimeMinutes\") AS o FROM \"HrAttendance\" WHERE \"organizationId\" = ? AND"
                        + " \"employeeId\" = ? AND \"deletedAt\" IS NULL AND date >= ? AND date < ?")
                        .params(orgId, emp.get("id"), SqlRows.ts(monthStart), SqlRows.ts(monthEnd)));
                double workedMin = att.get("w") == null ? 0 : ((Number) att.get("w")).doubleValue();
                double overtimeMin = att.get("o") == null ? 0 : ((Number) att.get("o")).doubleValue();
                double baseAmount = "HOURLY".equals(payFreq)
                        ? (workedMin / 60) * (hourlyRate != 0 ? hourlyRate : baseSalary / MONTHLY_HOURS) : baseSalary;
                double overtimeHours = overtimeMin / 60;
                double otRate = hourlyRate != 0 ? hourlyRate : baseSalary / MONTHLY_HOURS;
                Map<String, Object> overtimeRule = HrOvertimeCalculator.pick(overtimeRules, emp);
                double overtimePay = overtimeRule == null ? overtimeHours * otRate * 1.5
                        : overtime.weightedHours(overtimeRule, emp.get("id"), monthStart, monthEnd, holidays)
                                .doubleValue() * otRate;

                List<Map<String, Object>> allowanceLines = new ArrayList<>();
                double allowancesTotal = 0;
                for (Map<String, Object> c : components) {
                    if (!"ALLOWANCE".equals(c.get("componentType")) || !Boolean.TRUE.equals(c.get("isRecurring"))) {
                        continue;
                    }
                    if (truthy(c.get("appliesTo")) && !componentApplies(c.get("appliesTo"), emp)) {
                        continue;
                    }
                    double amount = percentOrFixed(c, baseAmount + overtimePay);
                    if (!(amount > 0)) {
                        continue;
                    }
                    allowancesTotal += amount;
                    allowanceLines.add(line(orgId, c.get("name"), amount, c.get("isTaxable"), userId));
                }
                List<String> inputIds = new ArrayList<>();
                double nonTaxableInputs = 0;
                for (Map<String, Object> in : inputs) {
                    if (!emp.get("id").equals(in.get("employeeId")) || "deduction".equals(in.get("kind"))) {
                        continue;
                    }
                    double amount = SqlRows.dec(in, "amount").doubleValue();
                    allowancesTotal += amount;
                    if (!Boolean.TRUE.equals(in.get("isTaxable"))) {
                        nonTaxableInputs += amount;
                    }
                    allowanceLines.add(line(orgId, in.get("name"), amount, in.get("isTaxable"), userId));
                    inputIds.add((String) in.get("id"));
                }
                List<Map<String, Object>> deductionLines = new ArrayList<>();
                double pension = 0;
                double ssf = 0;
                double insurance = 0;
                double other = 0;
                for (Map<String, Object> c : components) {
                    if (!"DEDUCTION".equals(c.get("componentType")) || !Boolean.TRUE.equals(c.get("isRecurring"))) {
                        continue;
                    }
                    if (truthy(c.get("appliesTo")) && !componentApplies(c.get("appliesTo"), emp)) {
                        continue;
                    }
                    double amount = percentOrFixed(c, baseAmount + overtimePay);
                    if (!(amount > 0)) {
                        continue;
                    }
                    String codeUp = String.valueOf(nz(c.get("code"), "")).toUpperCase();
                    if (codeUp.contains("PENSION")) {
                        pension += amount;
                    } else if (codeUp.contains("SSF") || codeUp.contains("SOCIAL")) {
                        ssf += amount;
                    } else if (codeUp.contains("INSURANCE")) {
                        insurance += amount;
                    } else {
                        other += amount;
                    }
                    deductionLines.add(line(orgId, c.get("name"), amount, c.get("isTaxable"), userId));
                }
                double gross = baseAmount + overtimePay + allowancesTotal;
                double taxableGross = Math.max(0, gross - pension - ssf - nonTaxableInputs);
                @SuppressWarnings("unchecked")
                double tax = payeTable != null ? computeProgressive(taxableGross * MONTHS_PER_YEAR,
                        (List<Map<String, Object>>) payeTable.get("brackets")) / MONTHS_PER_YEAR : 0;
                if (tax > 0) {
                    deductionLines.add(line(orgId, "PAYE Tax", tax, false, userId));
                }
                double statutory = tax + pension + ssf + insurance + other;
                double available = Math.max(0, gross - statutory);
                double loanDeduction = 0;
                double advanceDeduction = 0;
                for (Map<String, Object> loan : loans) {
                    if (!loan.get("employeeId").equals(emp.get("id")) || num(loan.get("balance")) <= 0) {
                        continue;
                    }
                    if (available <= 0) {
                        break;
                    }
                    double planned = Math.min(num(loan.get("installmentAmount")), num(loan.get("balance")));
                    double installment = Math.min(planned, available);
                    loanDeduction += installment;
                    available -= installment;
                    deductionLines.add(line(orgId, "Loan " + loan.get("loanCode"), installment, false, userId));
                }
                for (Map<String, Object> adv : advances) {
                    if (!adv.get("employeeId").equals(emp.get("id")) || num(adv.get("balance")) <= 0) {
                        continue;
                    }
                    if (available <= 0) {
                        break;
                    }
                    double planned = Math.min(num(adv.get("monthlyDeduction")), num(adv.get("balance")));
                    double installment = Math.min(planned, available);
                    advanceDeduction += installment;
                    available -= installment;
                    deductionLines.add(line(orgId, "Advance " + adv.get("advanceCode"), installment, false, userId));
                }
                // Additive (fleet 1.9): deduction inputs (e.g. traffic fines charged to the driver) come off the net
                // pay that is left; one that does not fit waits for a later run. None → the Nest calculation unchanged.
                double inputDeductions = 0;
                for (Map<String, Object> in : inputs) {
                    if (!emp.get("id").equals(in.get("employeeId")) || !"deduction".equals(in.get("kind"))) {
                        continue;
                    }
                    double amount = SqlRows.dec(in, "amount").doubleValue();
                    if (amount > available + 1e-9) {
                        continue;
                    }
                    inputDeductions += amount;
                    available -= amount;
                    deductionLines.add(line(orgId, in.get("name"), amount, false, userId));
                    inputIds.add((String) in.get("id"));
                }
                double totalDeductions = statutory + loanDeduction + advanceDeduction + inputDeductions;
                double netPay = gross - totalDeductions;
                double otHoursRounded = jsRound(overtimeHours * 100) / 100;
                if (overtimeRule == null && otHoursRounded != Math.rint(otHoursRounded)) {
                    // HrPayrollItem.overtimeHours is an Int column: Prisma rejects a fractional value (Nest 500).
                    throw new IllegalStateException("Invalid value for argument `overtimeHours`: Expected Int, provided"
                            + " Float.");
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("organizationId", orgId);
                data.put("runId", id);
                data.put("employeeId", emp.get("id"));
                data.put("baseSalary", decOf(baseAmount));
                data.put("hourlyRate", decOf(hourlyRate));
                data.put("regularHours", (int) jsRound(workedMin / 60));
                data.put("overtimeHours", (int) jsRound(otHoursRounded));
                data.put("overtimePay", decOf(overtimePay));
                data.put("allowancesTotal", decOf(allowancesTotal));
                data.put("commissionAmount", BigDecimal.ZERO);
                data.put("bonusAmount", BigDecimal.ZERO);
                data.put("grossPay", decOf(gross));
                data.put("taxAmount", decOf(tax));
                data.put("pensionAmount", decOf(pension));
                data.put("socialSecurityAmount", decOf(ssf));
                data.put("loanDeduction", decOf(loanDeduction));
                data.put("advanceDeduction", decOf(advanceDeduction));
                data.put("insuranceAmount", decOf(insurance));
                data.put("otherDeductions", decOf(other));
                data.put("totalDeductions", decOf(totalDeductions));
                data.put("netPay", decOf(netPay));
                data.put("absenceDays", BigDecimal.ZERO);
                data.put("createdBy", userId);
                Map<String, Object> item = hr.insert(HrPayrollItem.class, data);
                payrollInputs.attach(inputIds, id, (String) item.get("id"));
                for (Map<String, Object> al : allowanceLines) {
                    al.put("itemId", item.get("id"));
                    hr.insert(HrPayrollAllowance.class, al);
                }
                for (Map<String, Object> dl : deductionLines) {
                    dl.put("itemId", item.get("id"));
                    hr.insert(HrPayrollDeduction.class, dl);
                }
                items.add(item);
            }
            double grossSum = 0;
            double dedSum = 0;
            double netSum = 0;
            for (Map<String, Object> i : items) {
                grossSum += num(i.get("grossPay"));
                dedSum += num(i.get("totalDeductions"));
                netSum += num(i.get("netPay"));
            }
            Map<String, Object> run2 = hr.update(HrPayrollRun.class, id, Obj.of("status", "CALCULATED",
                    "totalGross", decOf(grossSum), "totalDeductions", decOf(dedSum), "totalNet", decOf(netSum),
                    "processedById", userId, "processedAt", PosDb.raw(PosSql.NOW), "updatedBy", userId));
            List<Map<String, Object>> itemsFull = runItems(id, true, EMPLOYEE_ITEM_REF, "\"createdAt\" ASC");
            withPayslips(itemsFull);
            Map<String, Object> out = new LinkedHashMap<>(run2);
            out.put("period", hr.byId(HrPayrollPeriod.class, run2.get("periodId")));
            out.put("items", itemsFull);
            out.put("bankPayments", List.of());
            return out;
        });
    }

    private static Map<String, Object> line(String orgId, Object name, double amount, Object isTaxable, String userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("organizationId", orgId);
        m.put("name", name);
        m.put("amount", decOf(amount));
        m.put("isTaxable", isTaxable);
        m.put("createdBy", userId);
        return m;
    }

    /** Approve a calculated run: payslips, the GL journal, loan / advance balances. */
    public Map<String, Object> approveRun(String id) {
        String orgId = org();
        String userId = user();
        Map<String, Object> run = hr.byId(HrPayrollRun.class, id);
        if (run == null) {
            throw ApiException.notFound("Payroll run not found");
        }
        Map<String, Object> period = hr.db().first(HrPayrollPeriod.class, "id = ?", run.get("periodId"));
        List<Map<String, Object>> runItems = runItems(id, false, EMPLOYEE_BANK_REF, null);
        if (!"CALCULATED".equals(run.get("status"))) {
            throw ApiException.badRequest("Only CALCULATED runs can be approved");
        }
        if (runItems.isEmpty()) {
            throw ApiException.badRequest("Run has no items — calculate first");
        }
        return hr.inTx(() -> {
            for (Map<String, Object> item : runItems) {
                String payslipNumber = seq.next("hr_payslip", "PS-", 6);
                hr.insert(HrPayslip.class, Obj.of("organizationId", orgId, "payslipNumber", payslipNumber,
                        "itemId", item.get("id"), "status", "ISSUED", "issuedAt", PosDb.raw(PosSql.NOW),
                        "createdBy", userId));
            }
            String salaryExpense = accounts.mapped("salary_expense");
            String netPayPayable = accounts.mapped("net_pay_payable");
            String payePayable = accounts.mapped("paye_payable");
            String pensionPayable = accounts.mapped("pension_payable");
            String ssfPayable = accounts.mapped("social_security_payable");
            String insurancePayable = accounts.mapped("insurance_payable");
            String loanReceivable = accounts.mapped("employee_loan_receivable");
            String advanceReceivable = accounts.mapped("employee_advance_receivable");
            double gross = sum(runItems, "grossPay");
            double net = sum(runItems, "netPay");
            double tax = sum(runItems, "taxAmount");
            double pension = sum(runItems, "pensionAmount");
            double ssf = sum(runItems, "socialSecurityAmount");
            double insurance = sum(runItems, "insuranceAmount");
            double loans = sum(runItems, "loanDeduction");
            double advances = sum(runItems, "advanceDeduction");
            double other = sum(runItems, "otherDeductions");
            List<PostingRequest.Line> lines = new ArrayList<>();
            // Additive (ADR-S009 §2): payroll inputs with a debit account (another module's clearing liability) are
            // debited there instead of salaries expense; without such inputs the journal is the Nest one.
            List<Map<String, Object>> redirected = payrollInputs.redirectedDebits(id);
            if (redirected.isEmpty()) {
                lines.add(PostingRequest.Line.debit(salaryExpense, decOf(gross), "Salaries & wages"));
            } else {
                BigDecimal moved = BigDecimal.ZERO;
                for (Map<String, Object> r : redirected) {
                    moved = moved.add(SqlRows.dec(r, "amount"));
                }
                BigDecimal rest = decOf(gross).subtract(moved);
                if (rest.signum() > 0) {
                    lines.add(PostingRequest.Line.debit(salaryExpense, rest, "Salaries & wages"));
                }
                for (Map<String, Object> r : redirected) {
                    lines.add(PostingRequest.Line.debit((String) r.get("debitAccountId"), SqlRows.dec(r, "amount"),
                            "Payroll inputs settled"));
                }
            }
            credit(lines, netPayPayable, net, "Net pay payable");
            credit(lines, payePayable, tax, "PAYE tax payable");
            credit(lines, pensionPayable, pension, "Pension payable");
            credit(lines, ssfPayable, ssf, "Social security payable");
            credit(lines, insurancePayable, insurance, "Insurance payable");
            credit(lines, loanReceivable, loans, "Employee loan installments");
            credit(lines, advanceReceivable, advances, "Employee advance installments");
            credit(lines, salaryExpense, other, "Other deductions contra");
            // Additive (fleet 1.9): deduction inputs credit their source's account (none → journal unchanged).
            for (Map<String, Object> dc : payrollInputs.deductionCredits(id)) {
                String account = dc.get("creditAccountId") == null ? salaryExpense : (String) dc.get("creditAccountId");
                credit(lines, account, SqlRows.dec(dc, "amount").doubleValue(), "Payroll deductions withheld");
            }
            Map<String, Object> journal = posting.post(new PostingRequest("GEN", Instant.now(),
                    "Payroll " + run.get("runNumber") + " — " + period.get("periodCode"), null, null, "payroll_run", id,
                    "primary", "payroll_run:" + id, null, null, null, lines));
            payrollInputs.markIncluded(id);
            for (Map<String, Object> loan : hr.where(HrEmployeeLoan.class).eq("status", "ACTIVE").list(null)) {
                Map<String, Object> item = itemFor(runItems, loan.get("employeeId"));
                double installment = item == null ? 0 : num(item.get("loanDeduction"));
                if (installment > 0) {
                    double newBalance = Math.max(0, num(loan.get("balance")) - installment);
                    hr.update(HrEmployeeLoan.class, (String) loan.get("id"), Obj.of("balance", decOf(newBalance),
                            "installmentsPaid", PosSql.intOf(loan.get("installmentsPaid")) + 1,
                            "status", newBalance <= 0 ? "PAID" : loan.get("status"), "updatedBy", userId));
                }
            }
            for (Map<String, Object> adv : hr.where(HrSalaryAdvance.class).and("status::text IN ('APPROVED', 'PAID')")
                    .list(null)) {
                Map<String, Object> item = itemFor(runItems, adv.get("employeeId"));
                double installment = item == null ? 0 : num(item.get("advanceDeduction"));
                if (installment > 0) {
                    double newBalance = Math.max(0, num(adv.get("balance")) - installment);
                    hr.update(HrSalaryAdvance.class, (String) adv.get("id"), Obj.of("balance", decOf(newBalance),
                            "status", newBalance <= 0 ? "SETTLED" : adv.get("status"), "updatedBy", userId));
                }
            }
            Map<String, Object> run2 = hr.update(HrPayrollRun.class, id, Obj.of("status", "APPROVED",
                    "journalEntryId", journal.get("id"), "glPosted", true, "updatedBy", userId));
            hr.auditInTx(AuditInput.of("HrPayrollRun", id, "post", Obj.of("status", run.get("status"), "glPosted", false),
                    Obj.of("status", "APPROVED", "journalEntryId", journal.get("id"), "periodId", run.get("periodId"),
                            "approvedBy", userId, "employeeCount", runItems.size())));
            List<Map<String, Object>> itemsFull = runItems(id, true, EMPLOYEE_ITEM_REF, "\"createdAt\" ASC");
            withPayslips(itemsFull);
            Map<String, Object> out = new LinkedHashMap<>(run2);
            out.put("period", hr.byId(HrPayrollPeriod.class, run2.get("periodId")));
            out.put("items", itemsFull);
            out.put("bankPayments", bankPayments(id, true));
            return out;
        });
    }

    private static Map<String, Object> itemFor(List<Map<String, Object>> items, Object employeeId) {
        for (Map<String, Object> i : items) {
            if (i.get("employeeId").equals(employeeId)) {
                return i;
            }
        }
        return null;
    }

    private static double sum(List<Map<String, Object>> items, String key) {
        double s = 0;
        for (Map<String, Object> i : items) {
            s += num(i.get(key));
        }
        return s;
    }

    private static void credit(List<PostingRequest.Line> lines, String account, double amount, String description) {
        if (amount > 0) {
            lines.add(PostingRequest.Line.credit(account, decOf(amount), description));
        }
    }

    /** Reverse a run: DRAFT/CALCULATED just flip status; APPROVED/PAID also reverse the journal. */
    public Map<String, Object> reverseRun(String id, Map<String, Object> dto) {
        String userId = user();
        Map<String, Object> run = hr.byId(HrPayrollRun.class, id);
        if (run == null) {
            throw ApiException.notFound("Payroll run not found");
        }
        Object status = run.get("status");
        if ("DRAFT".equals(status) || "CALCULATED".equals(status)) {
            return hr.inTx(() -> {
                payrollInputs.releaseRun(id);
                return hr.update(HrPayrollRun.class, id, Obj.of("status", "REVERSED",
                        "notes", nz(dto.get("reason"), run.get("notes")), "updatedBy", userId));
            });
        }
        if (!"APPROVED".equals(status) && !"PAID".equals(status)) {
            throw ApiException.badRequest("Only APPROVED/PAID runs can be reversed");
        }
        return hr.inTx(() -> {
            if (run.get("journalEntryId") != null) {
                posting.reverse((String) run.get("journalEntryId"), null, "Reversal — "
                        + (dto.get("reason") == null ? "payroll run reversal" : PosSql.jsString(dto.get("reason"))));
            }
            payrollInputs.reopenRun(id);
            Map<String, Object> reversed = hr.update(HrPayrollRun.class, id, Obj.of("status", "REVERSED",
                    "glPosted", false, "notes", nz(dto.get("reason"), run.get("notes")), "updatedBy", userId));
            hr.auditInTx(AuditInput.of("HrPayrollRun", id, "cancel", Obj.of("status", status,
                    "journalEntryId", run.get("journalEntryId"), "glPosted", true),
                    Obj.of("status", "REVERSED", "glPosted", false, "reason", dto.get("reason"), "reversedBy", userId)));
            return reversed;
        });
    }

    public Map<String, Object> deleteRun(String id) {
        Map<String, Object> run = hr.byId(HrPayrollRun.class, id);
        if (run == null) {
            throw ApiException.notFound("Payroll run not found");
        }
        if ("APPROVED".equals(run.get("status")) || "PAID".equals(run.get("status"))) {
            throw ApiException.badRequest("Approved/paid runs cannot be deleted — reverse them");
        }
        return hr.inTx(() -> {
            payrollInputs.releaseRun(id);
            return hr.softDelete(HrPayrollRun.class, id, Obj.of("updatedBy", user()));
        });
    }

    // ─── payslips ───

    private Map<String, Object> payslipItem(Object itemId, boolean full) {
        Map<String, Object> item = hr.db().first(HrPayrollItem.class, "id = ?", itemId);
        if (item == null) {
            return null;
        }
        if (full) {
            Map<String, Object> emp = hr.db().first(HrEmployee.class, "id = ?", item.get("employeeId"));
            if (emp != null) {
                hr.one(emp, "department", HrDepartment.class, emp.get("departmentId"));
                hr.one(emp, "position", HrPosition.class, emp.get("positionId"));
            }
            item.put("employee", emp);
        } else {
            hr.onePick(item, "employee", HrEmployee.class, item.get("employeeId"), EMPLOYEE_REF);
        }
        Map<String, Object> run = hr.db().first(HrPayrollRun.class, "id = ?", item.get("runId"));
        hr.one(run, "period", HrPayrollPeriod.class, run == null ? null : run.get("periodId"));
        item.put("run", run);
        item.put("allowances", hr.many(HrPayrollAllowance.class, "itemId", itemId, null, null));
        item.put("deductions", hr.many(HrPayrollDeduction.class, "itemId", itemId, null, null));
        return item;
    }

    public Map<String, Object> listPayslips(Map<String, String> query) {
        HrDb.Where w = hr.where(HrPayslip.class);
        if (truthy(query.get("employeeId"))) {
            w.and("EXISTS (SELECT 1 FROM \"HrPayrollItem\" i WHERE i.id = \"HrPayslip\".\"itemId\" AND"
                    + " i.\"employeeId\" = ?)", query.get("employeeId"));
        }
        w.eqIf("status", query.get("status"));
        List<Map<String, Object>> rows = w.list("\"createdAt\" DESC", HrDb.take(query.get("take"), 50, 200),
                HrDb.skip(query.get("skip")));
        rows.forEach(r -> r.put("item", payslipItem(r.get("itemId"), false)));
        return HrDb.rows(rows, w.count());
    }

    public Map<String, Object> getPayslip(String id) {
        Map<String, Object> row = hr.byId(HrPayslip.class, id);
        if (row == null) {
            throw ApiException.notFound("Payslip not found");
        }
        row.put("item", payslipItem(row.get("itemId"), true));
        return row;
    }

    public Map<String, Object> markPaid(String payslipId, Map<String, Object> dto) {
        if (hr.byId(HrPayslip.class, payslipId) == null) {
            throw ApiException.notFound("Payslip not found");
        }
        if (!truthy(dto.get("paymentMethod")) || !PAYMENT_METHODS.contains(dto.get("paymentMethod"))) {
            throw ApiException.badRequest(invalid("paymentMethod", dto.get("paymentMethod")));
        }
        return hr.update(HrPayslip.class, payslipId, Obj.of("status", "PAID", "paymentMethod", dto.get("paymentMethod"),
                "paidAt", PosDb.raw(PosSql.NOW), "updatedBy", user()));
    }

    // ─── bank payments ───

    public Map<String, Object> listBankPayments(Map<String, String> query) {
        HrDb.Where w = hr.where(HrBankPayment.class).eqIf("runId", query.get("runId")).eqIf("status", query.get("status"));
        List<Map<String, Object>> rows = w.list("\"createdAt\" DESC", HrDb.take(query.get("take"), 50, 200),
                HrDb.skip(query.get("skip")));
        for (Map<String, Object> r : rows) {
            Map<String, Object> run = hr.db().first(HrPayrollRun.class, "id = ?", r.get("runId"));
            hr.one(run, "period", HrPayrollPeriod.class, run == null ? null : run.get("periodId"));
            r.put("run", run);
        }
        hr.counts(rows, "lines", "HrBankPaymentLine", "bankPaymentId");
        return HrDb.rows(rows, w.count());
    }

    public Map<String, Object> generateBankPayment(Map<String, Object> dto) {
        String orgId = org();
        String userId = user();
        if (!truthy(dto.get("runId"))) {
            throw ApiException.badRequest("runId is required");
        }
        Map<String, Object> run = hr.byId(HrPayrollRun.class, dto.get("runId"));
        if (run == null) {
            throw ApiException.notFound("Payroll run not found");
        }
        List<Map<String, Object>> items = hr.many(HrPayrollItem.class, "runId", run.get("id"), null, null);
        hr.oneAll(items, "employee", HrEmployee.class, "employeeId", new String[] {"id", "firstName", "lastName",
            "employeeCode", "bankName", "bankAccountName", "bankAccountNumber", "mobileMoneyProvider",
            "mobileMoneyNumber"});
        if (!"APPROVED".equals(run.get("status")) && !"PAID".equals(run.get("status"))) {
            throw ApiException.badRequest("Only APPROVED/PAID runs can generate bank payments");
        }
        Object method = nz(dto.get("method"), "BANK");
        if (!PAYMENT_METHODS.contains(method)) {
            throw ApiException.badRequest(invalid("method", method));
        }
        return hr.inTx(() -> {
            String paymentCode = seq.next("hr_bank_payment", "BANK-", 6);
            List<Map<String, Object>> payable = items.stream().filter(i -> num(i.get("netPay")) > 0).toList();
            double totalAmount = sum(payable, "netPay");
            Map<String, Object> payment = hr.insert(HrBankPayment.class, Obj.of("organizationId", orgId,
                    "paymentCode", paymentCode, "runId", run.get("id"),
                    "paymentDate", truthy(dto.get("paymentDate")) ? HrDb.jsDate(dto.get("paymentDate"))
                            : PosDb.raw(PosSql.NOW),
                    "method", method, "status", "GENERATED", "totalAmount", decOf(totalAmount), "notes", dto.get("notes"),
                    "createdBy", userId));
            for (Map<String, Object> item : payable) {
                Map<String, Object> emp = PosSql.map(item.get("employee"));
                hr.insert(HrBankPaymentLine.class, Obj.of("organizationId", orgId, "bankPaymentId", payment.get("id"),
                        "employeeId", emp.get("id"), "amount", decOf(num(item.get("netPay"))),
                        "bankName", emp.get("bankName"), "bankAccountName", emp.get("bankAccountName"),
                        "bankAccountNumber", emp.get("bankAccountNumber"),
                        "mobileMoneyProvider", emp.get("mobileMoneyProvider"),
                        "mobileMoneyNumber", emp.get("mobileMoneyNumber"), "createdBy", userId));
            }
            Map<String, Object> out = hr.byId(HrBankPayment.class, payment.get("id"));
            out.put("lines", hr.many(HrBankPaymentLine.class, "bankPaymentId", payment.get("id"), null, null));
            return out;
        });
    }

    public Map<String, Object> updateBankPaymentStatus(String id, Map<String, Object> dto) {
        Map<String, Object> row = hr.byId(HrBankPayment.class, id);
        if (row == null) {
            throw ApiException.notFound("Bank payment not found");
        }
        if (!truthy(dto.get("status")) || !BANK_PAYMENT_STATUSES.contains(dto.get("status"))) {
            throw ApiException.badRequest(invalid("status", dto.get("status")));
        }
        return hr.update(HrBankPayment.class, id, Obj.of("status", dto.get("status"),
                "fileName", nz(dto.get("fileName"), row.get("fileName")), "fileUrl", nz(dto.get("fileUrl"),
                        row.get("fileUrl")), "processedById", user(), "updatedBy", user()));
    }

    // ─── salary advances ───

    public Map<String, Object> listAdvances(Map<String, String> query) {
        HrDb.Where w = hr.where(HrSalaryAdvance.class).eqIf("employeeId", query.get("employeeId"))
                .eqIf("status", query.get("status"));
        List<Map<String, Object>> rows = w.list("\"createdAt\" DESC", HrDb.take(query.get("take"), 50, 200),
                HrDb.skip(query.get("skip")));
        hr.oneAll(rows, "employee", HrEmployee.class, "employeeId", EMPLOYEE_REF);
        return HrDb.rows(rows, w.count());
    }

    public Map<String, Object> createAdvance(Map<String, Object> dto) {
        if (!truthy(dto.get("employeeId")) || !truthy(dto.get("amount"))) {
            throw ApiException.badRequest("employeeId and amount are required");
        }
        String advanceCode = seq.next("hr_advance", "ADV-", 6);
        double amount = HrDb.jsNumber(dto.get("amount"));
        Object months = nz(dto.get("installmentMonths"), 3);
        if (amount <= 0) {
            throw ApiException.badRequest("amount must be positive");
        }
        return hr.insert(HrSalaryAdvance.class, Obj.of("organizationId", org(), "advanceCode", advanceCode,
                "employeeId", dto.get("employeeId"), "amount", decOf(amount), "installmentMonths", months,
                "monthlyDeduction", decOf(amount / HrDb.jsNumber(months)), "balance", decOf(amount),
                "status", "PENDING", "requestedById", user(), "notes", dto.get("notes"), "createdBy", user()));
    }

    private Map<String, Object> advance(String id) {
        Map<String, Object> row = hr.byId(HrSalaryAdvance.class, id);
        if (row == null) {
            throw ApiException.notFound("Advance not found");
        }
        return row;
    }

    public Map<String, Object> approveAdvance(String id) {
        String userId = user();
        Map<String, Object> row = advance(id);
        if (!"PENDING".equals(row.get("status"))) {
            throw ApiException.badRequest("Only PENDING advances can be approved");
        }
        return hr.audited(() -> hr.update(HrSalaryAdvance.class, id, Obj.of("status", "APPROVED", "approvedById", userId,
                "approvedAt", PosDb.raw(PosSql.NOW), "updatedBy", userId)),
                u -> AuditInput.of("HrSalaryAdvance", id, "approve", Obj.of("status", row.get("status")),
                        Obj.of("status", "APPROVED", "approvedById", userId, "employeeId", row.get("employeeId"),
                                "amount", row.get("amount"))));
    }

    public Map<String, Object> markAdvancePaid(String id) {
        String userId = user();
        Map<String, Object> row = advance(id);
        if (!"APPROVED".equals(row.get("status"))) {
            throw ApiException.badRequest("Only APPROVED advances can be marked paid");
        }
        return hr.audited(() -> hr.update(HrSalaryAdvance.class, id, Obj.of("status", "PAID",
                "paidAt", PosDb.raw(PosSql.NOW), "updatedBy", userId)),
                u -> AuditInput.of("HrSalaryAdvance", id, "update", Obj.of("status", row.get("status")),
                        Obj.of("status", "PAID", "paidBy", userId, "employeeId", row.get("employeeId"),
                                "amount", row.get("amount"))));
    }

    public Map<String, Object> rejectAdvance(String id, Map<String, Object> dto) {
        String userId = user();
        Map<String, Object> row = advance(id);
        if (!"PENDING".equals(row.get("status"))) {
            throw ApiException.badRequest("Only PENDING advances can be rejected");
        }
        Object notes = truthy(dto.get("reason"))
                ? ((row.get("notes") == null ? "" : row.get("notes")) + "\nRejected: " + PosSql.jsString(dto.get("reason")))
                        .trim()
                : row.get("notes");
        return hr.audited(() -> hr.update(HrSalaryAdvance.class, id, Obj.of("status", "REJECTED", "notes", notes,
                "updatedBy", userId)),
                u -> AuditInput.of("HrSalaryAdvance", id, "reject", Obj.of("status", row.get("status")),
                        Obj.of("status", "REJECTED", "rejectedBy", userId, "reason", dto.get("reason"))));
    }

    // ─── employee loans ───

    public Map<String, Object> listLoans(Map<String, String> query) {
        HrDb.Where w = hr.where(HrEmployeeLoan.class).eqIf("employeeId", query.get("employeeId"))
                .eqIf("status", query.get("status"));
        List<Map<String, Object>> rows = w.list("\"createdAt\" DESC", HrDb.take(query.get("take"), 50, 200),
                HrDb.skip(query.get("skip")));
        hr.oneAll(rows, "employee", HrEmployee.class, "employeeId", EMPLOYEE_REF);
        return HrDb.rows(rows, w.count());
    }

    public Map<String, Object> createLoan(Map<String, Object> dto) {
        if (!truthy(dto.get("employeeId")) || !truthy(dto.get("principal")) || !truthy(dto.get("installmentsTotal"))) {
            throw ApiException.badRequest("employeeId, principal and installmentsTotal are required");
        }
        String loanCode = seq.next("hr_loan", "LN-", 6);
        double principal = HrDb.jsNumber(dto.get("principal"));
        double installments = HrDb.jsNumber(dto.get("installmentsTotal"));
        double rate = HrDb.jsNumber(nz(dto.get("interestRate"), 0));
        if (principal <= 0 || installments <= 0) {
            throw ApiException.badRequest("principal and installmentsTotal must be positive");
        }
        if (installments != Math.rint(installments)) {
            throw new IllegalStateException("Invalid value for argument `installmentsTotal`: Expected Int, provided Float.");
        }
        double totalPayable = principal * (1 + rate);
        return hr.insert(HrEmployeeLoan.class, Obj.of("organizationId", org(), "loanCode", loanCode,
                "employeeId", dto.get("employeeId"), "principal", decOf(principal), "interestRate", decOf(rate),
                "totalPayable", decOf(totalPayable), "installmentAmount", decOf(totalPayable / installments),
                "installmentsTotal", (int) installments, "installmentsPaid", 0, "balance", decOf(totalPayable),
                "status", "ACTIVE", "disbursedAt", truthy(dto.get("disbursedAt")) ? HrDb.jsDate(dto.get("disbursedAt")) : null,
                "notes", dto.get("notes"), "createdBy", user()));
    }

    public Map<String, Object> updateLoan(String id, Map<String, Object> dto) {
        if (hr.byId(HrEmployeeLoan.class, id) == null) {
            throw ApiException.notFound("Loan not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.containsKey("status")) {
            data.put("status", dto.get("status"));
        }
        if (dto.containsKey("balance")) {
            data.put("balance", decOf(HrDb.jsNumber(dto.get("balance"))));
        }
        if (dto.containsKey("installmentsPaid")) {
            double n = HrDb.jsNumber(dto.get("installmentsPaid"));
            if (n != Math.rint(n)) {
                throw new IllegalStateException("Invalid value for argument `installmentsPaid`: Expected Int.");
            }
            data.put("installmentsPaid", (int) n);
        }
        if (dto.containsKey("disbursedAt")) {
            data.put("disbursedAt", truthy(dto.get("disbursedAt")) ? HrDb.jsDate(dto.get("disbursedAt")) : null);
        }
        if (dto.containsKey("notes")) {
            data.put("notes", dto.get("notes"));
        }
        data.put("updatedBy", user());
        return hr.update(HrEmployeeLoan.class, id, data);
    }

    public Map<String, Object> deleteLoan(String id) {
        if (hr.byId(HrEmployeeLoan.class, id) == null) {
            throw ApiException.notFound("Loan not found");
        }
        return hr.softDelete(HrEmployeeLoan.class, id, Obj.of("status", "PAID", "updatedBy", user()));
    }
}
