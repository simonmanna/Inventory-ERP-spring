package com.inventoryerp.expenses.application;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.expenses.application.ExpensesService.CreateInput;
import com.inventoryerp.expenses.application.ExpensesService.Details;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expense schedules (EX-5, ADR-S052): recurring expense templates (each run raises a CREDIT expense through the normal
 * approval path, with a reminder to the owner {@code reminderDays} before), period-end accruals that reverse
 * themselves, and the daily run that also releases prepaid expenses.
 */
@Service
public class ExpenseScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseScheduleService.class);
    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";
    static final String ACCRUAL_SOURCE = "expense_period_accrual";

    /** Recurring template body. */
    public record RecurrenceInput(String name, String title, String description, BigDecimal amount, String categoryId,
            String supplierId, String branchId, String departmentId, String costCenterId, String projectId,
            String taxId, String frequency, Integer intervalCount, String nextRunDate, String endDate, Integer dueDays,
            Integer reminderDays) {}

    /** Period-end accrual body; {@code accountId} or {@code categoryId} names the expense account. */
    public record AccrualInput(String title, BigDecimal amount, String accountId, String categoryId, String supplierId,
            String branchId, String costCenterId, String accrualDate, String reversalDate, String notes) {}

    private final JdbcClient jdbc;
    private final ExpensesService expenses;
    private final ExpensePrepaymentService prepayments;
    private final ExpenseControlService controls;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;
    private final TransactionTemplate newTx;

    public ExpenseScheduleService(JdbcClient jdbc, ExpensesService expenses, ExpensePrepaymentService prepayments,
            ExpenseControlService controls, PostingService posting, AccountResolverService accounts,
            SequenceService sequence, AuditService audit, EventBus events, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.expenses = expenses;
        this.prepayments = prepayments;
        this.controls = controls;
        this.posting = posting;
        this.accounts = accounts;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static String actor() {
        String id = TenantContext.userId();
        if (id == null) {
            throw ApiException.forbidden("An authenticated user is required");
        }
        return id;
    }

    /** Accrued expenses liability (created with its mapping on first use). */
    String accruedAccount() {
        return accounts.ensureByCode("2158", "Accrued Expenses", "current_liability", "accrued_expense");
    }

    // ------------------------------------------------------------------ recurring

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recurrences(String status) {
        boolean all = status == null || status.isBlank();
        return SqlRows.list(jdbc.sql("SELECT * FROM \"ExpenseRecurrence\" WHERE \"organizationId\" = ?"
                + (all ? "" : " AND status = ?") + " ORDER BY \"nextRunDate\", name")
                .params(all ? List.of(org()) : List.of(org(), status))).stream().map(ExpenseScheduleService::amounts)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> recurrence(String id) {
        Map<String, Object> r = amounts(requireRecurrence(id));
        r.put("expenses", SqlRows.list(jdbc.sql("SELECT id, \"expenseCode\", amount, status::text AS status,"
                + " \"expenseDate\" FROM \"Expense\" WHERE \"recurrenceId\" = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL ORDER BY \"expenseDate\" DESC LIMIT 24").params(id, org())));
        return r;
    }

    private static Map<String, Object> amounts(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        m.put("amount", JsNumbers.js(Money.dec(r.get("amount"))));
        return m;
    }

    private Map<String, Object> requireRecurrence(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"ExpenseRecurrence\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Recurring expense not found");
        }
        return r;
    }

    @Transactional
    public Map<String, Object> createRecurrence(RecurrenceInput in) {
        if (in.name() == null || in.name().isBlank() || in.title() == null || in.title().isBlank()) {
            throw ApiException.badRequest("A name and a title are required");
        }
        if (in.amount() == null || in.amount().signum() <= 0) {
            throw ApiException.badRequest("amount must be a positive number");
        }
        if (in.frequency() == null || in.nextRunDate() == null) {
            throw ApiException.badRequest("frequency and nextRunDate are required");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ExpenseRecurrence\" (id, \"organizationId\", name, title, description, amount, \"categoryId\","
                        + " \"supplierId\", \"branchId\", \"departmentId\", \"costCenterId\", \"projectId\", \"taxId\", frequency,"
                        + " \"intervalCount\", \"nextRunDate\", \"endDate\", \"dueDays\", \"reminderDays\", \"createdById\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::date, ?::date, ?, ?, ?)")
                .params(id, org(), in.name().trim(), in.title().trim(), in.description(), in.amount(),
                        blank(in.categoryId()), blank(in.supplierId()), blank(in.branchId()), blank(in.departmentId()),
                        blank(in.costCenterId()), blank(in.projectId()), blank(in.taxId()), in.frequency(),
                        in.intervalCount() == null ? 1 : in.intervalCount(), in.nextRunDate(), blank(in.endDate()),
                        in.dueDays() == null ? 0 : in.dueDays(), in.reminderDays(), actor())
                .update();
        audit.recordInTx(AuditInput.of("ExpenseRecurrence", id, "create", null, Obj.of("name", in.name(), "amount",
                JsNumbers.js(in.amount()))));
        return recurrence(id);
    }

    @Transactional
    public Map<String, Object> updateRecurrence(String id, RecurrenceInput in) {
        Map<String, Object> before = requireRecurrence(id);
        List<Object> params = new ArrayList<>();
        StringBuilder set = new StringBuilder("\"updatedAt\" = " + NOW);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", in.name());
        fields.put("title", in.title());
        fields.put("description", in.description());
        fields.put("amount", in.amount());
        fields.put("categoryId", in.categoryId());
        fields.put("supplierId", in.supplierId());
        fields.put("branchId", in.branchId());
        fields.put("departmentId", in.departmentId());
        fields.put("costCenterId", in.costCenterId());
        fields.put("projectId", in.projectId());
        fields.put("taxId", in.taxId());
        fields.put("frequency", in.frequency());
        fields.put("intervalCount", in.intervalCount());
        fields.put("dueDays", in.dueDays());
        fields.put("reminderDays", in.reminderDays());
        fields.forEach((k, v) -> {
            if (EntityPatch.present(k, v)) {
                set.append(", \"").append(k).append("\" = ?");
                params.add(v instanceof String str && str.isBlank() ? null : v);
            }
        });
        for (String k : List.of("nextRunDate", "endDate")) {
            String v = "nextRunDate".equals(k) ? in.nextRunDate() : in.endDate();
            if (EntityPatch.present(k, v)) {
                set.append(", \"").append(k).append("\" = ?::date");
                params.add(blank(v));
            }
        }
        params.add(id);
        params.add(org());
        jdbc.sql("UPDATE \"ExpenseRecurrence\" SET " + set + " WHERE id = ? AND \"organizationId\" = ?").params(params)
                .update();
        audit.recordInTx(AuditInput.of("ExpenseRecurrence", id, "update", before, null));
        return recurrence(id);
    }

    @Transactional
    public Map<String, Object> setRecurrenceStatus(String id, String status) {
        requireRecurrence(id);
        jdbc.sql("UPDATE \"ExpenseRecurrence\" SET status = ?, \"updatedAt\" = " + NOW + " WHERE id = ?").params(status, id)
                .update();
        audit.recordInTx(AuditInput.of("ExpenseRecurrence", id, "update", null, Obj.of("status", status)));
        return recurrence(id);
    }

    /** Raise the template's next expense now (whatever its date), then advance the schedule. */
    @Transactional
    public Map<String, Object> runRecurrence(String id) {
        Map<String, Object> r = requireRecurrence(id);
        if (!"active".equals(r.get("status"))) {
            throw ApiException.badRequest("Recurring expense is " + r.get("status"));
        }
        generate(r);
        return recurrence(id);
    }

    /** One run of a template: a CREDIT expense dated {@code nextRunDate}; the schedule moves on (or ends). */
    private void generate(Map<String, Object> r) {
        LocalDate runDate = LocalDate.parse(String.valueOf(r.get("nextRunDate")));
        int dueDays = ((Number) r.get("dueDays")).intValue();
        Map<String, Object> created = expenses.create(new CreateInput((String) r.get("title"),
                (String) r.get("description"), Money.dec(r.get("amount")), (String) r.get("categoryId"),
                runDate.toString(), "Recurring: " + r.get("name"), (String) r.get("supplierId"), "CREDIT", null, null,
                null), null, new Details((String) r.get("branchId"), (String) r.get("departmentId"),
                (String) r.get("costCenterId"), (String) r.get("projectId"), null,
                dueDays > 0 ? runDate.plusDays(dueDays).toString() : null, null, null, null, null, null,
                (String) r.get("taxId"), null, null, null, null, null));
        String expenseId = (String) created.get("id");
        jdbc.sql("UPDATE \"Expense\" SET \"recurrenceId\" = ? WHERE id = ?").params(r.get("id"), expenseId).update();
        LocalDate next = advance(runDate, (String) r.get("frequency"), ((Number) r.get("intervalCount")).intValue());
        Object end = r.get("endDate");
        boolean ended = end != null && next.isAfter(LocalDate.parse(String.valueOf(end)));
        jdbc.sql("UPDATE \"ExpenseRecurrence\" SET \"nextRunDate\" = ?::date, \"lastRunAt\" = " + NOW + ", \"lastExpenseId\""
                + " = ?, \"runCount\" = \"runCount\" + 1, status = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(next.toString(), expenseId, ended ? "ended" : "active", r.get("id")).update();
        audit.recordInTx(AuditInput.of("ExpenseRecurrence", (String) r.get("id"), "post", null, Obj.of("expenseId",
                expenseId, "runDate", runDate.toString())));
    }

    static LocalDate advance(LocalDate d, String frequency, int n) {
        return switch (frequency) {
            case "weekly" -> d.plusWeeks(n);
            case "quarterly" -> d.plusMonths(3L * n);
            case "yearly" -> d.plusYears(n);
            default -> d.plusMonths(n);
        };
    }

    // ------------------------------------------------------------------ period-end accruals

    @Transactional(readOnly = true)
    public List<Map<String, Object>> accruals(String status) {
        boolean all = status == null || status.isBlank();
        return SqlRows.list(jdbc.sql("SELECT * FROM \"ExpenseAccrual\" WHERE \"organizationId\" = ?"
                + (all ? "" : " AND status = ?") + " ORDER BY \"accrualDate\" DESC LIMIT 500")
                .params(all ? List.of(org()) : List.of(org(), status))).stream().map(ExpenseScheduleService::amounts)
                .toList();
    }

    private Map<String, Object> requireAccrual(String id) {
        Map<String, Object> a = SqlRows.one(jdbc.sql("SELECT * FROM \"ExpenseAccrual\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
        if (a == null) {
            throw ApiException.notFound("Expense accrual not found");
        }
        return a;
    }

    /** Post a period-end accrual: Dr expense / Cr accrued expenses on the accrual date. */
    @Transactional
    public Map<String, Object> createAccrual(AccrualInput in) {
        if (in.title() == null || in.title().isBlank() || in.amount() == null || in.amount().signum() <= 0
                || in.accrualDate() == null) {
            throw ApiException.badRequest("title, a positive amount and accrualDate are required");
        }
        String account = blank(in.accountId());
        if (account == null && blank(in.categoryId()) != null) {
            account = jdbc.sql("SELECT \"ledgerAccountId\" FROM \"ExpenseCategory\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(in.categoryId(), org()).query(String.class).optional().orElse(null);
        }
        if (account == null) {
            account = accounts.byMapping("default_expense");
        }
        LocalDate date = LocalDate.parse(in.accrualDate().substring(0, 10));
        LocalDate reversal = in.reversalDate() == null || in.reversalDate().isBlank()
                ? date.withDayOfMonth(1).plusMonths(1) : LocalDate.parse(in.reversalDate().substring(0, 10));
        if (!reversal.isAfter(date)) {
            throw ApiException.badRequest("The reversal date must be after the accrual date");
        }
        String id = UUID.randomUUID().toString();
        String number = sequence.next("expense_accrual", "ACR-", 5);
        String value = JsNumbers.dec(in.amount());
        String label = "Accrual " + number + " - " + in.title().trim();
        Map<String, Object> entry = posting.post(PostingRequest.of("GEN", date.atStartOfDay().toInstant(ZoneOffset.UTC),
                label, ACCRUAL_SOURCE, id, ACCRUAL_SOURCE + ":" + id, List.of(
                        new Line(account, value, null, null, label, blank(in.branchId()), blank(in.costCenterId()), null),
                        new Line(accruedAccount(), null, value, blank(in.supplierId()), label, null, null, null))));
        jdbc.sql("INSERT INTO \"ExpenseAccrual\" (id, \"organizationId\", \"accrualNumber\", title, amount, \"accountId\","
                        + " \"categoryId\", \"supplierId\", \"branchId\", \"costCenterId\", \"accrualDate\", \"reversalDate\","
                        + " \"journalEntryId\", notes, \"createdById\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::date, ?::date,"
                        + " ?, ?, ?)")
                .params(id, org(), number, in.title().trim(), in.amount(), account, blank(in.categoryId()),
                        blank(in.supplierId()), blank(in.branchId()), blank(in.costCenterId()), date.toString(),
                        reversal.toString(), entry.get("id"), in.notes(), actor())
                .update();
        audit.recordInTx(AuditInput.of("ExpenseAccrual", id, "post", null, Obj.of("accrualNumber", number, "amount",
                value)));
        return amounts(requireAccrual(id));
    }

    /** Reverse an accrual now (or on its reversal date when the job runs). */
    @Transactional
    public Map<String, Object> reverseAccrual(String id) {
        Map<String, Object> a = requireAccrual(id);
        if (!"posted".equals(a.get("status"))) {
            throw ApiException.badRequest("Accrual is already reversed");
        }
        LocalDate due = LocalDate.parse(String.valueOf(a.get("reversalDate")));
        LocalDate today = LocalDate.now(BusinessZone.get());
        LocalDate date = due.isAfter(today) ? today : due;
        reverse(a, date);
        return amounts(requireAccrual(id));
    }

    private void reverse(Map<String, Object> a, LocalDate date) {
        Map<String, Object> rev = posting.reverse((String) a.get("journalEntryId"),
                date.atStartOfDay().toInstant(ZoneOffset.UTC), "Reversal of accrual " + a.get("accrualNumber"));
        jdbc.sql("UPDATE \"ExpenseAccrual\" SET status = 'reversed', \"reversalEntryId\" = ?, \"updatedAt\" = " + NOW
                + " WHERE id = ?").params(rev == null ? null : rev.get("id"), a.get("id")).update();
        audit.recordInTx(AuditInput.of("ExpenseAccrual", (String) a.get("id"), "cancel", null, Obj.of("reversedOn",
                date.toString())));
    }

    // ------------------------------------------------------------------ daily run

    /**
     * Everything due by {@code asOf} for the caller's organization: reminders, recurring runs (as their owner, catching
     * up at most 12 runs each), accrual reversals and prepaid releases. Each item commits on its own; failures are
     * logged and listed.
     */
    public Map<String, Object> runDue(LocalDate asOf) {
        String orgId = org();
        List<String> errors = new ArrayList<>();
        int reminders = 0;
        int generated = 0;
        int reversed = 0;
        int defaultReminder = ((Number) controls.settings().get("recurringReminderDays")).intValue();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT * FROM \"ExpenseRecurrence\" WHERE \"organizationId\" = ?"
                + " AND status = 'active' ORDER BY \"nextRunDate\"").param(orgId))) {
            String id = (String) r.get("id");
            String owner = (String) r.get("createdById");
            try {
                for (int i = 0; i < 12; i++) {
                    Map<String, Object> cur = SqlRows.one(jdbc.sql("SELECT * FROM \"ExpenseRecurrence\" WHERE id = ?")
                            .param(id));
                    if (!"active".equals(cur.get("status"))
                            || LocalDate.parse(String.valueOf(cur.get("nextRunDate"))).isAfter(asOf)) {
                        break;
                    }
                    TenantContext.run(TenantStore.user(orgId, owner), () -> newTx.executeWithoutResult(s -> generate(cur)));
                    generated++;
                }
                Map<String, Object> cur = SqlRows.one(jdbc.sql("SELECT * FROM \"ExpenseRecurrence\" WHERE id = ?").param(id));
                int days = cur.get("reminderDays") != null ? ((Number) cur.get("reminderDays")).intValue() : defaultReminder;
                LocalDate next = LocalDate.parse(String.valueOf(cur.get("nextRunDate")));
                if ("active".equals(cur.get("status")) && days > 0 && !asOf.isBefore(next.minusDays(days))
                        && asOf.isBefore(next) && !String.valueOf(next).equals(String.valueOf(cur.get("lastReminderFor")))) {
                    newTx.executeWithoutResult(s -> {
                        jdbc.sql("UPDATE \"ExpenseRecurrence\" SET \"lastReminderFor\" = ?::date WHERE id = ?")
                                .params(next.toString(), id).update();
                        controls.notifyAlways(owner, "Recurring expense due " + next, cur.get("name") + ": "
                                + JsNumbers.dec(Money.dec(cur.get("amount"))), Obj.of("recurrenceId", id, "dueDate",
                                next.toString()));
                        events.publish("expense.recurring_due", Obj.of("organizationId", orgId, "recurrenceId", id,
                                "dueDate", next.toString()));
                    });
                    reminders++;
                }
            } catch (RuntimeException err) {
                log.warn("recurring expense {} failed: {}", id, err.toString());
                errors.add(id + ": " + err.getMessage());
            }
        }
        for (Map<String, Object> a : SqlRows.list(jdbc.sql("SELECT * FROM \"ExpenseAccrual\" WHERE \"organizationId\" = ?"
                + " AND status = 'posted' AND \"reversalDate\" <= ?::date").params(orgId, asOf.toString()))) {
            try {
                newTx.executeWithoutResult(s -> reverse(a, LocalDate.parse(String.valueOf(a.get("reversalDate")))));
                reversed++;
            } catch (RuntimeException err) {
                log.warn("accrual reversal {} failed: {}", a.get("id"), err.toString());
                errors.add(a.get("id") + ": " + err.getMessage());
            }
        }
        Map<String, Object> released = prepayments.runDue(asOf);
        @SuppressWarnings("unchecked")
        List<String> releaseErrors = (List<String>) released.get("errors");
        errors.addAll(releaseErrors);
        return Obj.of("asOf", asOf.toString(), "remindersSent", reminders, "expensesGenerated", generated,
                "accrualsReversed", reversed, "prepaidReleased", released.get("released"), "prepaidAmount",
                released.get("amount"), "errors", errors);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
