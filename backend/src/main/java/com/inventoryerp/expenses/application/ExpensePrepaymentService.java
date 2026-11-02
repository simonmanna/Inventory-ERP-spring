package com.inventoryerp.expenses.application;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
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
 * Prepaid expenses (EX-5, ADR-S052). An expense with {@code prepaidMonths} debits the prepaid expenses asset when it
 * is recognised; this service then keeps its schedule and releases one month at a time (Dr expense account on the
 * expense's branch / cost centre / Cr prepaid), the last month taking the rounding. Voiding the expense reverses the
 * releases.
 */
@Service
public class ExpensePrepaymentService {

    private static final Logger log = LoggerFactory.getLogger(ExpensePrepaymentService.class);
    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";
    static final String RELEASE_SOURCE = "expense_prepaid_release";

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final AuditService audit;
    private final TransactionTemplate newTx;

    public ExpensePrepaymentService(JdbcClient jdbc, PostingService posting, AccountResolverService accounts,
            AuditService audit, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.accounts = accounts;
        this.audit = audit;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** The prepaid expenses asset (created with its mapping on first use). */
    String prepaidAccount() {
        return accounts.ensureByCode("1385", "Prepaid Expenses", "current_asset", "prepaid_expense");
    }

    /** Create the schedule of a recognised prepaid expense (idempotent). */
    void ensureSchedule(Map<String, Object> exp, String expenseAccountId, BigDecimal net) {
        if (exp.get("prepaidMonths") == null) {
            return;
        }
        Object start = exp.get("prepaidStart");
        YearMonth month = start != null ? YearMonth.from(LocalDate.parse(String.valueOf(start)))
                : YearMonth.from(ExpenseControlService.instant(exp.get("expenseDate")).atOffset(ZoneOffset.UTC));
        jdbc.sql("INSERT INTO \"ExpensePrepayment\" (id, \"organizationId\", \"expenseId\", \"prepaidAccountId\","
                        + " \"expenseAccountId\", \"branchId\", \"costCenterId\", \"startMonth\", months, \"totalAmount\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (\"expenseId\") DO UPDATE SET status = 'active',"
                        + " \"totalAmount\" = EXCLUDED.\"totalAmount\", \"expenseAccountId\" = EXCLUDED.\"expenseAccountId\","
                        + " \"startMonth\" = EXCLUDED.\"startMonth\", months = EXCLUDED.months, \"updatedAt\" = " + NOW
                        + " WHERE \"ExpensePrepayment\".\"releasedAmount\" = 0")
                .params(UUID.randomUUID().toString(), org(), exp.get("id"), prepaidAccount(), expenseAccountId,
                        exp.get("branchId"), exp.get("costCenterId"), month.toString(),
                        ((Number) exp.get("prepaidMonths")).intValue(), net)
                .update();
    }

    /** True when the expense's schedule has released anything. */
    boolean hasReleases(String expenseId) {
        return jdbc.sql("SELECT count(*) FROM \"ExpensePrepayment\" WHERE \"expenseId\" = ? AND \"releasedAmount\" > 0")
                .param(expenseId).query(Long.class).single() > 0;
    }

    /** Reverse every release of an expense's schedule and cancel it (void / delete of the expense). */
    void cancelFor(String expenseId, String reason) {
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT * FROM \"ExpensePrepayment\" WHERE \"expenseId\" = ? AND"
                + " \"organizationId\" = ?").params(expenseId, org()));
        if (p == null) {
            return;
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"journalEntryId\" FROM \"ExpensePrepaymentRelease\""
                + " WHERE \"prepaymentId\" = ? AND \"journalEntryId\" IS NOT NULL").param(p.get("id")))) {
            posting.reverse((String) r.get("journalEntryId"), null, reason);
        }
        jdbc.sql("UPDATE \"ExpensePrepayment\" SET status = 'cancelled', \"updatedAt\" = " + NOW + " WHERE id = ?")
                .param(p.get("id")).update();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        boolean all = status == null || status.isBlank();
        return SqlRows.list(jdbc.sql("SELECT p.*, e.\"expenseCode\", e.title FROM \"ExpensePrepayment\" p JOIN \"Expense\" e"
                + " ON e.id = p.\"expenseId\" WHERE p.\"organizationId\" = ?" + (all ? "" : " AND p.status = ?")
                + " ORDER BY p.\"createdAt\" DESC LIMIT 500").params(all ? List.of(org()) : List.of(org(), status)))
                .stream().map(ExpensePrepaymentService::shape).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT p.*, e.\"expenseCode\", e.title FROM \"ExpensePrepayment\" p"
                + " JOIN \"Expense\" e ON e.id = p.\"expenseId\" WHERE p.id = ? AND p.\"organizationId\" = ?")
                .params(id, org()));
        if (p == null) {
            throw ApiException.notFound("Prepaid expense schedule not found");
        }
        Map<String, Object> out = shape(p);
        out.put("releases", SqlRows.list(jdbc.sql("SELECT period, amount, \"journalEntryId\", \"createdAt\" FROM"
                + " \"ExpensePrepaymentRelease\" WHERE \"prepaymentId\" = ? ORDER BY period").param(id)).stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>(r);
                    m.put("amount", JsNumbers.js(Money.dec(r.get("amount"))));
                    return m;
                }).toList());
        return out;
    }

    private static Map<String, Object> shape(Map<String, Object> p) {
        Map<String, Object> m = new LinkedHashMap<>(p);
        BigDecimal total = Money.dec(p.get("totalAmount"));
        BigDecimal released = Money.dec(p.get("releasedAmount"));
        m.put("totalAmount", JsNumbers.js(total));
        m.put("releasedAmount", JsNumbers.js(released));
        m.put("remaining", JsNumbers.js(total.subtract(released)));
        return m;
    }

    /**
     * Release every month due by {@code asOf} (a month is due from its first day) for the caller's organization.
     * One failing schedule (closed period, missing account) is logged and skipped.
     */
    public Map<String, Object> runDue(LocalDate asOf) {
        int released = 0;
        BigDecimal amount = BigDecimal.ZERO;
        List<String> errors = new ArrayList<>();
        for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT p.* FROM \"ExpensePrepayment\" p JOIN \"Expense\" e ON"
                + " e.id = p.\"expenseId\" WHERE p.\"organizationId\" = ? AND p.status = 'active' AND e.\"deletedAt\" IS NULL"
                + " AND e.status::text NOT IN ('VOID', 'CANCELLED', 'REJECTED') ORDER BY p.\"createdAt\"").param(org()))) {
            try {
                BigDecimal[] r = newTx.execute(s -> releaseDue(p, asOf));
                released += r[0].intValue();
                amount = amount.add(r[1]);
            } catch (RuntimeException err) {
                log.warn("prepaid release failed for {}: {}", p.get("id"), err.toString());
                errors.add(p.get("id") + ": " + err.getMessage());
            }
        }
        return Obj.of("released", released, "amount", JsNumbers.js(amount), "errors", errors);
    }

    private BigDecimal[] releaseDue(Map<String, Object> p, LocalDate asOf) {
        String id = (String) p.get("id");
        int months = ((Number) p.get("months")).intValue();
        YearMonth start = YearMonth.parse((String) p.get("startMonth"));
        BigDecimal total = Money.dec(p.get("totalAmount"));
        BigDecimal monthly = total.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        BigDecimal released = Money.dec(p.get("releasedAmount"));
        int count = 0;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < months; i++) {
            YearMonth period = start.plusMonths(i);
            if (period.atDay(1).isAfter(asOf)) {
                break;
            }
            if (jdbc.sql("SELECT count(*) FROM \"ExpensePrepaymentRelease\" WHERE \"prepaymentId\" = ? AND period = ?")
                    .params(id, period.toString()).query(Long.class).single() > 0) {
                continue;
            }
            BigDecimal part = i == months - 1 ? total.subtract(released) : monthly.min(total.subtract(released));
            if (part.signum() <= 0) {
                continue;
            }
            String value = JsNumbers.dec(part);
            String label = "Prepaid release " + period;
            Map<String, Object> entry = posting.post(PostingRequest.of("GEN",
                    period.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC), label, RELEASE_SOURCE,
                    (String) p.get("expenseId"), RELEASE_SOURCE + ":" + id + ":" + period, List.of(
                            new Line((String) p.get("expenseAccountId"), value, null, null, label,
                                    (String) p.get("branchId"), (String) p.get("costCenterId"), null),
                            Line.credit((String) p.get("prepaidAccountId"), value, label))));
            jdbc.sql("INSERT INTO \"ExpensePrepaymentRelease\" (id, \"organizationId\", \"prepaymentId\", period, amount,"
                    + " \"journalEntryId\") VALUES (?, ?, ?, ?, ?, ?)").params(UUID.randomUUID().toString(), org(), id,
                    period.toString(), part, entry.get("id")).update();
            released = released.add(part);
            sum = sum.add(part);
            count++;
        }
        if (count > 0) {
            jdbc.sql("UPDATE \"ExpensePrepayment\" SET \"releasedAmount\" = ?, status = CASE WHEN ? >= \"totalAmount\" THEN"
                    + " 'completed' ELSE status END, \"updatedAt\" = " + NOW + " WHERE id = ?").params(released, released, id)
                    .update();
            audit.recordInTx(AuditInput.of("ExpensePrepayment", id, "post", null, Obj.of("released", JsNumbers.js(sum),
                    "months", count)));
        }
        return new BigDecimal[] {BigDecimal.valueOf(count), sum};
    }
}
