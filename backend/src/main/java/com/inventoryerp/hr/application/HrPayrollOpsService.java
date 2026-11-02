package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrRows.dec;
import static com.inventoryerp.hr.application.HrRows.org;
import static com.inventoryerp.hr.application.HrRows.str;
import static com.inventoryerp.hr.application.HrRows.user;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Payroll operations around a run (HP-2, HP-3, HP-7): manual one-off inputs, statutory remittance schedules and
 * payments, net-pay settlement, bank / mobile-money / UAE WPS files, payslip PDFs and e-mail, employee payment
 * details.
 */
@Service
public class HrPayrollOpsService {

    /** A generated file. */
    public record FileOut(String filename, String contentType, byte[] content) {}

    private final HrRows rows;
    private final HrDb hr;
    private final HrPayrollInputService inputs;
    private final HrPayrollEngineV2 engine;
    private final HrPayrollSetupService setup;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final NotificationsService notifications;

    public HrPayrollOpsService(HrRows rows, HrDb hr, HrPayrollInputService inputs, HrPayrollEngineV2 engine,
            HrPayrollSetupService setup, PostingService posting, AccountResolverService accounts,
            NotificationsService notifications) {
        this.rows = rows;
        this.hr = hr;
        this.inputs = inputs;
        this.engine = engine;
        this.setup = setup;
        this.posting = posting;
        this.accounts = accounts;
        this.notifications = notifications;
    }

    // ------------------------------------------------------------------ one-off inputs (HP-2)

    public Map<String, Object> listInputs(Map<String, String> query) {
        String status = query.getOrDefault("status", "pending");
        List<Map<String, Object>> list = rows.query("SELECT i.*, e.\"employeeCode\", e.\"firstName\", e.\"lastName\""
                + " FROM \"HrPayrollInput\" i JOIN \"HrEmployee\" e ON e.id = i.\"employeeId\" WHERE i.\"organizationId\""
                + " = ? AND i.status = ? ORDER BY i.\"createdAt\" DESC LIMIT 500", org(), status);
        return HrRows.page(list, list.size());
    }

    /** Bonus, commission, arrears, reimbursement or a deduction, paid / recovered in the next run. */
    public Map<String, Object> addInput(Map<String, Object> dto) {
        String employeeId = str(dto.get("employeeId"));
        String name = str(dto.get("name"));
        BigDecimal amount = dec(dto.get("amount"));
        if (employeeId == null || name == null || amount == null) {
            throw ApiException.badRequest("employeeId, name and amount are required");
        }
        LocalDate on = HrRows.date(dto.get("date"));
        if (on == null) {
            on = LocalDate.now(ZoneOffset.UTC);
        }
        String kind = String.valueOf(dto.getOrDefault("kind", "earning"));
        HrRows.oneOf("kind", kind, List.of("earning", "deduction"));
        String sourceId = UUID.randomUUID().toString();
        LocalDate date = on;
        return hr.inTx(() -> "earning".equals(kind)
                ? inputs.addEarning(employeeId, name, amount, date, !Boolean.FALSE.equals(dto.get("isTaxable")), null,
                        "manual", sourceId)
                : inputs.addDeduction(employeeId, name, amount, date, null, "manual", sourceId));
    }

    public Map<String, Object> cancelInput(String id, Map<String, Object> dto) {
        Map<String, Object> in = rows.queryOne("SELECT source, \"sourceId\" FROM \"HrPayrollInput\" WHERE id = ? AND"
                + " \"organizationId\" = ?", id, org());
        if (in == null) {
            throw ApiException.notFound("Payroll input not found");
        }
        if (!"manual".equals(in.get("source"))) {
            throw ApiException.badRequest("Only manual inputs can be cancelled here; cancel it in its source document");
        }
        return hr.inTx(() -> inputs.cancel("manual", (String) in.get("sourceId"),
                str(dto == null ? null : dto.get("reason"))));
    }

    // ------------------------------------------------------------------ statutory (HP-3)

    public List<Map<String, Object>> statutoryRates(String pack) {
        return engine.rateRows(pack);
    }

    /**
     * Remittance schedule of a period: per statutory code, employee and employer amounts from the approved v2 runs,
     * the per-employee breakdown, and what was already paid.
     */
    public Map<String, Object> remittances(String periodId) {
        if (periodId == null) {
            throw ApiException.badRequest("periodId is required");
        }
        List<Map<String, Object>> lines = rows.query("SELECT l.\"statutoryCode\", l.kind, l.\"employeeId\","
                + " e.\"employeeCode\", e.\"firstName\", e.\"lastName\", e.\"taxNumber\", e.\"socialSecurityNumber\","
                + " e.\"pensionNumber\", sum(l.amount) AS amount, max(l.base) AS base FROM \"HrPayrollResultLine\" l"
                + " JOIN \"HrPayrollRun\" r ON r.id = l.\"runId\" JOIN \"HrEmployee\" e ON e.id = l.\"employeeId\""
                + " WHERE l.\"organizationId\" = ? AND l.\"deletedAt\" IS NULL AND r.\"periodId\" = ? AND"
                + " r.status::text IN ('APPROVED', 'PAID') AND l.\"statutoryCode\" IS NOT NULL AND l.kind <> 'INFO'"
                + " GROUP BY 1, 2, 3, 4, 5, 6, 7, 8, 9 ORDER BY 1, 4", org(), periodId);
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> l : lines) {
            String code = (String) l.get("statutoryCode");
            Map<String, Object> c = byCode.computeIfAbsent(code, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("statutoryCode", k);
                m.put("employee", BigDecimal.ZERO);
                m.put("employer", BigDecimal.ZERO);
                m.put("employees", new ArrayList<Map<String, Object>>());
                return m;
            });
            BigDecimal amount = dec(l.get("amount"));
            String side = "EMPLOYER_CONTRIBUTION".equals(l.get("kind")) ? "employer" : "employee";
            c.put(side, ((BigDecimal) c.get(side)).add(amount));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> emps = (List<Map<String, Object>>) c.get("employees");
            Map<String, Object> row = emps.stream().filter(x -> x.get("employeeId").equals(l.get("employeeId")))
                    .findFirst().orElse(null);
            if (row == null) {
                row = new LinkedHashMap<>();
                row.put("employeeId", l.get("employeeId"));
                row.put("employeeCode", l.get("employeeCode"));
                row.put("name", l.get("firstName") + (l.get("lastName") == null ? "" : " " + l.get("lastName")));
                row.put("taxNumber", l.get("taxNumber"));
                row.put("socialSecurityNumber", l.get("socialSecurityNumber"));
                row.put("pensionNumber", l.get("pensionNumber"));
                row.put("base", l.get("base"));
                row.put("employee", BigDecimal.ZERO);
                row.put("employer", BigDecimal.ZERO);
                emps.add(row);
            }
            row.put(side, ((BigDecimal) row.get(side)).add(amount));
        }
        for (Map<String, Object> c : byCode.values()) {
            c.put("total", ((BigDecimal) c.get("employee")).add((BigDecimal) c.get("employer")));
            Map<String, Object> paid = rows.first("HrPayrollPayment", "\"paymentType\" = 'REMITTANCE' AND \"periodId\" = ?"
                    + " AND \"statutoryCode\" = ?", null, periodId, c.get("statutoryCode"));
            c.put("payment", paid);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodId", periodId);
        out.put("rows", new ArrayList<>(byCode.values()));
        return out;
    }

    /** Pay a statutory payable for a period: Dr the payable, Cr the bank / cash account. */
    public Map<String, Object> payRemittance(Map<String, Object> dto) {
        String periodId = str(dto.get("periodId"));
        String code = str(dto.get("statutoryCode"));
        String accountId = str(dto.get("accountId"));
        if (periodId == null || code == null || accountId == null) {
            throw ApiException.badRequest("periodId, statutoryCode and accountId are required");
        }
        Map<String, Object> schedule = remittances(periodId);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) schedule.get("rows")).stream()
                .filter(r -> code.equals(r.get("statutoryCode"))).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Nothing to remit for " + code + " in this period"));
        if (row.get("payment") != null) {
            throw ApiException.badRequest(code + " for this period is already paid");
        }
        BigDecimal total = (BigDecimal) row.get("total");
        Map<String, Object> sample = rows.queryOne("SELECT l.\"payableKey\" FROM \"HrPayrollResultLine\" l JOIN"
                + " \"HrPayrollRun\" r ON r.id = l.\"runId\" WHERE l.\"organizationId\" = ? AND r.\"periodId\" = ? AND"
                + " l.\"statutoryCode\" = ? AND l.\"payableKey\" IS NOT NULL LIMIT 1", org(), periodId, code);
        String payable = accounts.byMapping((String) sample.get("payableKey"));
        LocalDate paidOn = HrRows.date(dto.get("paidOn")) == null ? LocalDate.now(ZoneOffset.UTC)
                : HrRows.date(dto.get("paidOn"));
        return hr.inTx(() -> {
            String id = UUID.randomUUID().toString();
            Map<String, Object> journal = posting.post(new PostingRequest("GEN", HrPayrollEngineV2.utc(paidOn),
                    code + " remittance", null, null, "payroll_remittance", id, "primary", "payroll_remittance:" + id,
                    null, null, null, List.of(PostingRequest.Line.debit(payable, total, code + " payable cleared"),
                            PostingRequest.Line.credit(accountId, total, code + " remittance"))));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("paymentType", "REMITTANCE");
            data.put("periodId", periodId);
            data.put("statutoryCode", code);
            data.put("accountId", accountId);
            data.put("amount", total);
            data.put("paidOn", paidOn);
            data.put("reference", str(dto.get("reference")));
            data.put("journalEntryId", journal.get("id"));
            Map<String, Object> saved = rows.insert("HrPayrollPayment", data);
            hr.auditInTx(AuditInput.of("HrPayrollPayment", id, "post", null, saved));
            return saved;
        });
    }

    // ------------------------------------------------------------------ net pay settlement (HP-7)

    /** Pay an approved run's net pay from one account: Dr net pay payable, Cr the account; run and payslips PAID. */
    public Map<String, Object> settleRun(String runId, Map<String, Object> dto) {
        Map<String, Object> run = engine.requireRun(runId);
        if (!"APPROVED".equals(run.get("status"))) {
            throw ApiException.badRequest("Only an APPROVED payroll run can be paid");
        }
        String accountId = str(dto.get("accountId"));
        if (accountId == null) {
            throw ApiException.badRequest("accountId is required");
        }
        BigDecimal net = dec(run.get("totalNet"));
        if (net == null || net.signum() <= 0) {
            throw ApiException.badRequest("The run has no net pay to settle");
        }
        LocalDate paidOn = HrRows.date(dto.get("paidOn")) == null ? LocalDate.now(ZoneOffset.UTC)
                : HrRows.date(dto.get("paidOn"));
        String netPayable = accounts.byMapping("net_pay_payable");
        return hr.inTx(() -> {
            String id = UUID.randomUUID().toString();
            Map<String, Object> journal = posting.post(new PostingRequest("GEN", HrPayrollEngineV2.utc(paidOn),
                    "Net pay " + run.get("runNumber"), null, null, "payroll_payment", id, "primary",
                    "payroll_payment:" + id, null, null, null, List.of(
                            PostingRequest.Line.debit(netPayable, net, "Net pay payable cleared"),
                            PostingRequest.Line.credit(accountId, net, "Salaries paid " + run.get("runNumber")))));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("paymentType", "NET_PAY");
            data.put("runId", runId);
            data.put("periodId", run.get("periodId"));
            data.put("accountId", accountId);
            data.put("amount", net);
            data.put("paidOn", paidOn);
            data.put("reference", str(dto.get("reference")));
            data.put("journalEntryId", journal.get("id"));
            Map<String, Object> saved = rows.insert("HrPayrollPayment", data);
            rows.jdbc().sql("UPDATE \"HrPayrollRun\" SET status = 'PAID', \"updatedAt\" = CURRENT_TIMESTAMP,"
                    + " \"updatedBy\" = ? WHERE id = ? AND \"organizationId\" = ?").params(user(), runId, org()).update();
            rows.jdbc().sql("UPDATE \"HrPayslip\" SET status = 'PAID', \"paidAt\" = CURRENT_TIMESTAMP,"
                    + " \"updatedAt\" = CURRENT_TIMESTAMP WHERE \"organizationId\" = ? AND \"itemId\" IN (SELECT id FROM"
                    + " \"HrPayrollItem\" WHERE \"runId\" = ?)").params(org(), runId).update();
            hr.auditInTx(AuditInput.of("HrPayrollRun", runId, "post", Map.of("status", "APPROVED"),
                    Map.of("status", "PAID", "paymentId", id, "journalEntryId", journal.get("id"))));
            return saved;
        });
    }

    public Map<String, Object> payments(Map<String, String> query) {
        String runId = query.get("runId");
        List<Map<String, Object>> list = runId == null
                ? rows.list("HrPayrollPayment", null, "\"paidOn\" DESC")
                : rows.list("HrPayrollPayment", "\"runId\" = ?", "\"paidOn\" DESC", runId);
        return HrRows.page(list, list.size());
    }

    // ------------------------------------------------------------------ employee payment details

    public Map<String, Object> payInfo(String employeeId) {
        Map<String, Object> row = rows.first("HrEmployeePayInfo", "\"employeeId\" = ?", null, employeeId);
        return row == null ? Map.of("employeeId", employeeId) : row;
    }

    public Map<String, Object> savePayInfo(String employeeId, Map<String, Object> dto) {
        if (rows.queryOne("SELECT id FROM \"HrEmployee\" WHERE id = ? AND \"organizationId\" = ?", employeeId,
                org()) == null) {
            throw ApiException.notFound("Employee not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (String f : List.of("iban", "routingCode", "wpsPersonId", "bankBranch")) {
            if (dto.containsKey(f)) {
                String v = str(dto.get(f));
                data.put(f, v == null ? null : v.replace(" ", "").toUpperCase());
            }
        }
        Object iban = data.get("iban");
        if (iban != null && !validIban(String.valueOf(iban))) {
            throw ApiException.badRequest("Invalid IBAN");
        }
        Map<String, Object> existing = rows.first("HrEmployeePayInfo", "\"employeeId\" = ?", null, employeeId);
        return hr.inTx(() -> {
            if (existing == null) {
                data.put("employeeId", employeeId);
                return rows.insert("HrEmployeePayInfo", data);
            }
            return rows.update("HrEmployeePayInfo", (String) existing.get("id"), data);
        });
    }

    /** ISO 13616 mod-97 check. */
    static boolean validIban(String iban) {
        if (!iban.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}")) {
            return false;
        }
        String r = iban.substring(4) + iban.substring(0, 4);
        StringBuilder num = new StringBuilder();
        for (char c : r.toCharArray()) {
            num.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new java.math.BigInteger(num.toString()).mod(java.math.BigInteger.valueOf(97)).intValue() == 1;
    }

    // ------------------------------------------------------------------ bank files (HP-7)

    public FileOut bankFile(String runId, String format) {
        Map<String, Object> run = engine.requireRun(runId);
        if (!List.of("APPROVED", "PAID").contains(String.valueOf(run.get("status")))) {
            throw ApiException.badRequest("Bank files are produced for approved runs only");
        }
        List<Map<String, Object>> items = rows.query("SELECT i.*, e.\"employeeCode\", e.\"firstName\", e.\"lastName\","
                + " e.\"bankName\", e.\"bankAccountName\", e.\"bankAccountNumber\", e.\"mobileMoneyProvider\","
                + " e.\"mobileMoneyNumber\", p.iban, p.\"routingCode\", p.\"wpsPersonId\" FROM \"HrPayrollItem\" i JOIN"
                + " \"HrEmployee\" e ON e.id = i.\"employeeId\" LEFT JOIN \"HrEmployeePayInfo\" p ON p.\"employeeId\" ="
                + " e.id AND p.\"deletedAt\" IS NULL WHERE i.\"runId\" = ? AND i.\"organizationId\" = ? AND"
                + " i.\"deletedAt\" IS NULL AND i.\"netPay\" > 0 ORDER BY e.\"employeeCode\"", runId, org());
        String number = String.valueOf(run.get("runNumber"));
        Map<String, Object> s = setup.effectiveSettings();
        int scale = ((Number) s.get("roundingScale")).intValue();
        StringBuilder out = new StringBuilder();
        switch (format == null ? "CSV" : format.toUpperCase()) {
            case "CSV" -> {
                out.append("employeeCode,name,bankName,accountName,accountNumber,iban,amount,reference\n");
                for (Map<String, Object> i : items) {
                    out.append(csv(i.get("employeeCode"))).append(',').append(csv(name(i))).append(',')
                            .append(csv(i.get("bankName"))).append(',').append(csv(i.get("bankAccountName"))).append(',')
                            .append(csv(i.get("bankAccountNumber"))).append(',').append(csv(i.get("iban"))).append(',')
                            .append(amount(i.get("netPay"), scale)).append(',').append(csv(number)).append('\n');
                }
                return new FileOut("payroll-" + number + ".csv", "text/csv", out.toString().getBytes(StandardCharsets.UTF_8));
            }
            case "MOBILE_MONEY" -> {
                out.append("msisdn,amount,name,provider,reference\n");
                for (Map<String, Object> i : items) {
                    if (i.get("mobileMoneyNumber") == null) {
                        continue;
                    }
                    out.append(csv(i.get("mobileMoneyNumber"))).append(',').append(amount(i.get("netPay"), scale))
                            .append(',').append(csv(name(i))).append(',').append(csv(i.get("mobileMoneyProvider")))
                            .append(',').append(csv(number)).append('\n');
                }
                return new FileOut("payroll-" + number + "-mobile.csv", "text/csv",
                        out.toString().getBytes(StandardCharsets.UTF_8));
            }
            case "WPS_SIF" -> {
                return wps(run, items, s);
            }
            default -> throw ApiException.badRequest("format must be one of CSV, MOBILE_MONEY, WPS_SIF");
        }
    }

    /**
     * UAE Wage Protection System SIF: one EDR line per employee (person id, agent routing code, IBAN, pay start / end,
     * days, fixed and variable salary, leave days) and the SCR trailer (employer id, bank code, created date / time,
     * salary month, count, total, currency, reference).
     */
    private FileOut wps(Map<String, Object> run, List<Map<String, Object>> items, Map<String, Object> s) {
        String employer = str(s.get("wpsEmployerId"));
        String bank = str(s.get("wpsBankCode"));
        List<String> missing = new ArrayList<>();
        if (employer == null || bank == null) {
            missing.add("payroll settings: WPS employer id and bank code");
        }
        for (Map<String, Object> i : items) {
            if (i.get("wpsPersonId") == null || i.get("routingCode") == null || i.get("iban") == null) {
                missing.add(i.get("employeeCode") + ": WPS person id, routing code and IBAN");
            }
        }
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("WPS file needs: " + String.join("; ", missing));
        }
        Map<String, Object> period = rows.queryOne("SELECT \"startDate\", \"endDate\" FROM \"HrPayrollPeriod\" WHERE id = ?",
                run.get("periodId"));
        LocalDate start = HrRows.date(period.get("startDate"));
        LocalDate end = HrRows.date(period.get("endDate"));
        DateTimeFormatter d = DateTimeFormatter.ISO_LOCAL_DATE;
        StringBuilder out = new StringBuilder();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> i : items) {
            BigDecimal net = dec(i.get("netPay")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal variable = dec(i.get("overtimePay")).add(variableEarnings((String) i.get("id")))
                    .min(net).setScale(2, RoundingMode.HALF_UP);
            BigDecimal fixed = net.subtract(variable);
            total = total.add(net);
            out.append(String.join(",", "EDR", String.valueOf(i.get("wpsPersonId")), String.valueOf(i.get("routingCode")),
                    String.valueOf(i.get("iban")), start.format(d), end.format(d),
                    String.valueOf(java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1), fixed.toPlainString(),
                    variable.toPlainString(), dec(i.get("absenceDays")).setScale(0, RoundingMode.HALF_UP).toPlainString()))
                    .append('\n');
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        out.append(String.join(",", "SCR", employer, bank, now.format(d), now.format(DateTimeFormatter.ofPattern("HHmm")),
                end.format(DateTimeFormatter.ofPattern("MMyyyy")), String.valueOf(items.size()),
                total.setScale(2, RoundingMode.HALF_UP).toPlainString(), "AED", String.valueOf(run.get("runNumber"))))
                .append('\n');
        String name = employer + now.format(DateTimeFormatter.ofPattern("yyMMddHHmmss")) + ".SIF";
        return new FileOut(name, "text/plain", out.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private BigDecimal variableEarnings(String itemId) {
        Map<String, Object> r = rows.queryOne("SELECT coalesce(sum(amount), 0) AS a FROM \"HrPayrollResultLine\" WHERE"
                + " \"itemId\" = ? AND \"organizationId\" = ? AND kind = 'EARNING' AND category IN ('INPUT', 'RETRO')",
                itemId, org());
        return dec(r.get("a"));
    }

    private static String name(Map<String, Object> i) {
        return i.get("firstName") + (i.get("lastName") == null ? "" : " " + i.get("lastName"));
    }

    private static String csv(Object v) {
        if (v == null) {
            return "";
        }
        String s = String.valueOf(v);
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static String amount(Object v, int scale) {
        return dec(v).setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    // ------------------------------------------------------------------ payslips (HP-7)

    /** The payslip PDF; {@code employeeId} non-null restricts it to that employee's own payslip (self-service). */
    public FileOut payslipPdf(String payslipId, String employeeId) {
        Map<String, Object> slip = rows.queryOne("SELECT s.\"payslipNumber\", s.status, i.*, r.\"runNumber\","
                + " r.\"paymentDate\", p.\"periodCode\", p.\"startDate\", p.\"endDate\", e.\"employeeCode\", e.\"firstName\","
                + " e.\"lastName\", e.\"taxNumber\", e.\"socialSecurityNumber\", e.\"bankName\", e.\"bankAccountNumber\","
                + " e.\"departmentId\", e.email FROM \"HrPayslip\" s JOIN \"HrPayrollItem\" i ON i.id = s.\"itemId\" JOIN"
                + " \"HrPayrollRun\" r ON r.id = i.\"runId\" JOIN \"HrPayrollPeriod\" p ON p.id = r.\"periodId\" JOIN"
                + " \"HrEmployee\" e ON e.id = i.\"employeeId\" WHERE s.id = ? AND s.\"organizationId\" = ? AND"
                + " s.\"deletedAt\" IS NULL", payslipId, org());
        if (slip == null || (employeeId != null && !employeeId.equals(slip.get("employeeId")))) {
            throw ApiException.notFound("Payslip not found");
        }
        return new FileOut(slip.get("payslipNumber") + ".pdf", "application/pdf", render(slip));
    }

    private byte[] render(Map<String, Object> slip) {
        Map<String, Object> s = setup.effectiveSettings();
        int scale = ((Number) s.get("roundingScale")).intValue();
        Map<String, Object> org = rows.queryOne("SELECT name, \"currencyCode\" FROM \"Organization\" WHERE id = ?", org());
        String itemId = (String) slip.get("id");
        List<HrPayslipPdf.Row> earnings = new ArrayList<>();
        List<HrPayslipPdf.Row> deductions = new ArrayList<>();
        List<HrPayslipPdf.Row> employer = new ArrayList<>();
        List<Map<String, Object>> lines = rows.list("HrPayrollResultLine", "\"itemId\" = ?", "\"sortOrder\"", itemId);
        if (!lines.isEmpty()) {
            for (Map<String, Object> l : lines) {
                HrPayslipPdf.Row row = new HrPayslipPdf.Row((String) l.get("name"), dec(l.get("amount")));
                switch (String.valueOf(l.get("kind"))) {
                    case "EARNING" -> earnings.add(row);
                    case "DEDUCTION" -> deductions.add(row);
                    case "EMPLOYER_CONTRIBUTION" -> employer.add(row);
                    default -> { }
                }
            }
        } else {
            earnings.add(new HrPayslipPdf.Row("Basic salary", dec(slip.get("baseSalary"))));
            if (dec(slip.get("overtimePay")).signum() > 0) {
                earnings.add(new HrPayslipPdf.Row("Overtime", dec(slip.get("overtimePay"))));
            }
            for (Map<String, Object> a : rows.query("SELECT name, amount FROM \"HrPayrollAllowance\" WHERE \"itemId\" = ?",
                    itemId)) {
                earnings.add(new HrPayslipPdf.Row((String) a.get("name"), dec(a.get("amount"))));
            }
            for (Map<String, Object> d : rows.query("SELECT name, amount FROM \"HrPayrollDeduction\" WHERE \"itemId\" = ?",
                    itemId)) {
                deductions.add(new HrPayslipPdf.Row((String) d.get("name"), dec(d.get("amount"))));
            }
        }
        LocalDate end = HrRows.date(slip.get("endDate"));
        Map<String, Object> ytd = rows.queryOne("SELECT coalesce(sum(i.\"grossPay\"), 0) AS g, coalesce(sum(i.\"taxAmount\"),"
                + " 0) AS t, coalesce(sum(i.\"netPay\"), 0) AS n FROM \"HrPayrollItem\" i JOIN \"HrPayrollRun\" r ON r.id ="
                + " i.\"runId\" JOIN \"HrPayrollPeriod\" p ON p.id = r.\"periodId\" WHERE i.\"organizationId\" = ? AND"
                + " i.\"employeeId\" = ? AND i.\"deletedAt\" IS NULL AND r.status::text IN ('APPROVED', 'PAID') AND"
                + " p.\"endDate\" >= ? AND p.\"endDate\" <= ?", org(), slip.get("employeeId"),
                java.sql.Timestamp.valueOf(end.withDayOfYear(1).atStartOfDay()),
                java.sql.Timestamp.valueOf(end.atStartOfDay()));
        Map<String, String> header = new LinkedHashMap<>();
        header.put("Employee", slip.get("employeeCode") + " · " + name(slip));
        header.put("Payslip", (String) slip.get("payslipNumber"));
        header.put("Period", slip.get("periodCode") + " (" + HrRows.date(slip.get("startDate")) + " – " + end + ")");
        header.put("Payment date", slip.get("paymentDate") == null ? "" : String.valueOf(HrRows.date(slip.get("paymentDate"))));
        header.put("Tax number", str(slip.get("taxNumber")));
        header.put("Social security no.", str(slip.get("socialSecurityNumber")));
        header.put("Bank", (slip.get("bankName") == null ? "" : slip.get("bankName") + " ")
                + mask(str(slip.get("bankAccountNumber"))));
        header.put("Days unpaid", String.valueOf(dec(slip.get("absenceDays")).stripTrailingZeros().toPlainString()));
        return HrPayslipPdf.payslip((String) org.get("name"), "Payslip", header, earnings, deductions, employer,
                dec(slip.get("grossPay")), dec(slip.get("totalDeductions")), dec(slip.get("netPay")),
                List.of(new HrPayslipPdf.Row("Gross pay", dec(ytd.get("g"))), new HrPayslipPdf.Row("Tax", dec(ytd.get("t"))),
                        new HrPayslipPdf.Row("Net pay", dec(ytd.get("n")))),
                String.valueOf(org.get("currencyCode")), scale, str(s.get("payslipNote")));
    }

    static String mask(String account) {
        if (account == null || account.length() <= 4) {
            return account == null ? "" : account;
        }
        return "••••" + account.substring(account.length() - 4);
    }

    /** E-mail every payslip of an approved run to employees with an address; returns sent / skipped. */
    public Map<String, Object> emailPayslips(String runId) {
        Map<String, Object> run = engine.requireRun(runId);
        if (!List.of("APPROVED", "PAID").contains(String.valueOf(run.get("status")))) {
            throw ApiException.badRequest("Payslips are e-mailed for approved runs only");
        }
        int sent = 0;
        List<String> skipped = new ArrayList<>();
        for (Map<String, Object> p : rows.query("SELECT s.id, e.email, e.\"employeeCode\" FROM \"HrPayslip\" s JOIN"
                + " \"HrPayrollItem\" i ON i.id = s.\"itemId\" JOIN \"HrEmployee\" e ON e.id = i.\"employeeId\" WHERE"
                + " i.\"runId\" = ? AND s.\"organizationId\" = ? AND s.\"deletedAt\" IS NULL", runId, org())) {
            String email = str(p.get("email"));
            if (email == null) {
                skipped.add((String) p.get("employeeCode"));
                continue;
            }
            FileOut pdf = payslipPdf((String) p.get("id"), null);
            boolean ok = notifications.sendExternalEmail(email, "Your payslip " + run.get("runNumber"),
                    "Please find your payslip attached.", new NotificationsService.Attachment(pdf.filename(),
                            pdf.contentType(), pdf.content()));
            if (ok) {
                sent++;
            } else {
                skipped.add((String) p.get("employeeCode"));
            }
        }
        int count = sent;
        hr.inTx(() -> {
            hr.auditInTx(AuditInput.of("HrPayrollRun", runId, "issue", null, Map.of("payslipsEmailed", count)));
            return null;
        });
        return Map.of("sent", count, "skipped", skipped);
    }

    /** Annual earnings certificate of one employee (year of period end) from approved runs. */
    public Map<String, Object> annualSummary(String employeeId, int year) {
        Map<String, Object> e = rows.queryOne("SELECT id, \"employeeCode\", \"firstName\", \"lastName\", \"taxNumber\","
                + " \"socialSecurityNumber\" FROM \"HrEmployee\" WHERE id = ? AND \"organizationId\" = ?", employeeId, org());
        if (e == null) {
            throw ApiException.notFound("Employee not found");
        }
        Map<String, Object> t = rows.queryOne("SELECT coalesce(sum(i.\"grossPay\"), 0) AS gross, coalesce(sum(i.\"taxAmount\"),"
                + " 0) AS tax, coalesce(sum(i.\"pensionAmount\"), 0) AS pension, coalesce(sum(i.\"socialSecurityAmount\"), 0)"
                + " AS social, coalesce(sum(i.\"totalDeductions\"), 0) AS deductions, coalesce(sum(i.\"netPay\"), 0) AS net,"
                + " count(*) AS periods FROM \"HrPayrollItem\" i JOIN \"HrPayrollRun\" r ON r.id = i.\"runId\" JOIN"
                + " \"HrPayrollPeriod\" p ON p.id = r.\"periodId\" WHERE i.\"organizationId\" = ? AND i.\"employeeId\" = ? AND"
                + " i.\"deletedAt\" IS NULL AND r.status::text IN ('APPROVED', 'PAID') AND extract(year FROM p.\"endDate\") = ?",
                org(), employeeId, year);
        Map<String, Object> out = new LinkedHashMap<>(e);
        out.put("year", year);
        out.putAll(t);
        out.put("byCode", rows.query("SELECT l.\"statutoryCode\", l.kind, sum(l.amount) AS amount FROM"
                + " \"HrPayrollResultLine\" l JOIN \"HrPayrollRun\" r ON r.id = l.\"runId\" JOIN \"HrPayrollPeriod\" p ON"
                + " p.id = r.\"periodId\" WHERE l.\"organizationId\" = ? AND l.\"employeeId\" = ? AND l.\"deletedAt\" IS NULL"
                + " AND r.status::text IN ('APPROVED', 'PAID') AND l.\"statutoryCode\" IS NOT NULL AND"
                + " extract(year FROM p.\"endDate\") = ? GROUP BY 1, 2 ORDER BY 1, 2", org(), employeeId, year));
        return out;
    }

    public FileOut annualCertificate(String employeeId, int year) {
        Map<String, Object> s = annualSummary(employeeId, year);
        Map<String, Object> org = rows.queryOne("SELECT name, \"currencyCode\" FROM \"Organization\" WHERE id = ?", org());
        Map<String, String> header = new LinkedHashMap<>();
        header.put("Employee", s.get("employeeCode") + " · " + name(s));
        header.put("Tax number", str(s.get("taxNumber")));
        header.put("Social security no.", str(s.get("socialSecurityNumber")));
        header.put("Year", String.valueOf(year));
        List<HrPayslipPdf.Row> totals = new ArrayList<>(List.of(new HrPayslipPdf.Row("Gross pay", dec(s.get("gross"))),
                new HrPayslipPdf.Row("Income tax withheld", dec(s.get("tax"))),
                new HrPayslipPdf.Row("Pension (employee)", dec(s.get("pension"))),
                new HrPayslipPdf.Row("Social security (employee)", dec(s.get("social"))),
                new HrPayslipPdf.Row("Total deductions", dec(s.get("deductions"))),
                new HrPayslipPdf.Row("Net pay", dec(s.get("net")))));
        int scale = ((Number) setup.effectiveSettings().get("roundingScale")).intValue();
        return new FileOut("earnings-" + s.get("employeeCode") + "-" + year + ".pdf", "application/pdf",
                HrPayslipPdf.certificate((String) org.get("name"), "Annual earnings and tax certificate " + year, header,
                        totals, String.valueOf(org.get("currencyCode")), scale));
    }

    static Instant now() {
        return Instant.now();
    }
}
