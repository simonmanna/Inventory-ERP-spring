package com.inventoryerp.expenses.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expense spend controls (EX-2, ADR-S052): GL budget check when an expense is recognised, spending limits per person /
 * category / branch, organization expense settings and the in-app notifications those settings switch on.
 */
@Service
public class ExpenseControlService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseControlService.class);
    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    /** Create / update body of a spending limit. */
    public record LimitInput(String name, String userId, String categoryId, String branchId, String period,
            BigDecimal amount, String action, Boolean isActive) {}

    /** Settings body; absent fields keep their value. */
    public record SettingsInput(Boolean notifyOnDecision, Boolean notifyOnPayment, Boolean notifyApproversOnSubmit,
            Integer recurringReminderDays, Boolean claimReceiptRequired) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final NotificationsService notifications;

    public ExpenseControlService(JdbcClient jdbc, AuditService audit, NotificationsService notifications) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.notifications = notifications;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    static Instant instant(Object d) {
        return d instanceof Timestamp t ? t.toInstant()
                : d instanceof LocalDateTime l ? l.toInstant(ZoneOffset.UTC)
                : d instanceof Instant i ? i : com.inventoryerp.kernel.crud.EntityPatch.toInstant(String.valueOf(d));
    }

    // ------------------------------------------------------------------ budget

    /**
     * GL budget check of a category with {@code budgetControl} warn / block: every approved budget whose line covers
     * {@code accountId} in the expense's month (budget branch / cost centre empty or equal to the expense's) must hold
     * the posted actual of that month plus {@code amount}. Block → 409 {@code EXPENSE_BUDGET_EXCEEDED}; warn → audit.
     */
    public void checkBudget(Map<String, Object> exp, Map<String, Object> category, String accountId,
            BigDecimal amount) {
        String mode = category == null ? null : (String) category.get("budgetControl");
        if (mode == null || "none".equals(mode) || accountId == null || amount.signum() <= 0) {
            return;
        }
        YearMonth month = YearMonth.from(instant(exp.get("expenseDate")).atOffset(ZoneOffset.UTC));
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT b.id, b.name, b.\"branchId\", b.\"costCenterId\","
                        + " l.amount FROM \"BudgetLine\" l JOIN \"Budget\" b ON b.id = l.\"budgetId\" WHERE"
                        + " b.\"organizationId\" = ? AND b.status = 'approved' AND b.\"deletedAt\" IS NULL AND"
                        + " l.\"accountId\" = ? AND l.period = ? AND (b.\"branchId\" IS NULL OR b.\"branchId\" = ?) AND"
                        + " (b.\"costCenterId\" IS NULL OR b.\"costCenterId\" = ?) ORDER BY b.id FOR UPDATE OF b")
                .params(org(), accountId, month.toString(), exp.get("branchId"), exp.get("costCenterId")));
        for (Map<String, Object> b : lines) {
            StringBuilder sql = new StringBuilder("SELECT COALESCE(SUM(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM"
                    + " \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE"
                    + " l.\"organizationId\" = ? AND l.\"accountId\" = ? AND e.status IN ('posted', 'reversed') AND"
                    + " e.\"postingDate\" >= ? AND e.\"postingDate\" < ?");
            List<Object> params = new ArrayList<>(List.of(org(), accountId,
                    Timestamp.valueOf(month.atDay(1).atStartOfDay()),
                    Timestamp.valueOf(month.plusMonths(1).atDay(1).atStartOfDay())));
            if (b.get("branchId") != null) {
                sql.append(" AND COALESCE(l.\"branchId\", e.\"branchId\") = ?");
                params.add(b.get("branchId"));
            }
            if (b.get("costCenterId") != null) {
                sql.append(" AND COALESCE(l.\"costCenterId\", e.\"costCenterId\") = ?");
                params.add(b.get("costCenterId"));
            }
            BigDecimal actual = jdbc.sql(sql.toString()).params(params).query(BigDecimal.class).single();
            BigDecimal budget = SqlRows.dec(b, "amount");
            BigDecimal projected = actual.add(amount);
            if (projected.compareTo(budget) <= 0) {
                continue;
            }
            Map<String, Object> detail = Obj.of("expenseId", exp.get("id"), "budgetId", b.get("id"), "period",
                    month.toString(), "accountId", accountId, "projected", plain(projected), "budget", plain(budget),
                    "mode", mode);
            if ("block".equals(mode)) {
                audit.record(AuditInput.of("Budget", (String) b.get("id"), "reject", null, detail));
                throw ApiException.withBody(HttpStatus.CONFLICT, Obj.of("code", "EXPENSE_BUDGET_EXCEEDED", "message",
                        "This expense takes budget \"" + b.get("name") + "\" over for " + month + ": " + plain(projected)
                                + " of " + plain(budget) + ".",
                        "budgetId", b.get("id"), "projected", projected, "amount", budget, "remaining",
                        budget.subtract(actual)));
            }
            audit.recordInTx(AuditInput.of("Budget", (String) b.get("id"), "update", null, detail));
        }
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // ------------------------------------------------------------------ limits

    /**
     * Spending limits covering an expense (subject = claimant, else raiser). {@code excludeId} is the expense itself
     * when it is being edited. Block → 409 {@code EXPENSE_LIMIT_EXCEEDED}; warn → audit.
     */
    public void checkLimits(Map<String, Object> exp) {
        String subject = exp.get("claimantId") != null ? (String) exp.get("claimantId") : (String) exp.get("createdById");
        BigDecimal amount = Money.dec(exp.get("amount"));
        YearMonth month = YearMonth.from(instant(exp.get("expenseDate")).atOffset(ZoneOffset.UTC));
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT * FROM \"ExpenseLimit\" WHERE \"organizationId\" = ?"
                + " AND \"isActive\" AND (\"userId\" IS NULL OR \"userId\" = ?) AND (\"categoryId\" IS NULL OR"
                + " \"categoryId\" = ?) AND (\"branchId\" IS NULL OR \"branchId\" = ?) ORDER BY \"createdAt\"")
                .params(org(), subject, exp.get("categoryId"), exp.get("branchId")))) {
            BigDecimal projected = amount;
            if ("monthly".equals(l.get("period"))) {
                StringBuilder sql = new StringBuilder("SELECT COALESCE(SUM(amount), 0) FROM \"Expense\" WHERE"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND status::text NOT IN ('VOID',"
                        + " 'CANCELLED', 'REJECTED') AND id <> ? AND \"expenseDate\" >= ? AND \"expenseDate\" < ?");
                List<Object> params = new ArrayList<>(List.of(org(), exp.get("id"),
                        Timestamp.valueOf(month.atDay(1).atStartOfDay()),
                        Timestamp.valueOf(month.plusMonths(1).atDay(1).atStartOfDay())));
                if (l.get("userId") != null) {
                    sql.append(" AND COALESCE(\"claimantId\", \"createdById\") = ?");
                    params.add(l.get("userId"));
                }
                if (l.get("categoryId") != null) {
                    sql.append(" AND \"categoryId\" = ?");
                    params.add(l.get("categoryId"));
                }
                if (l.get("branchId") != null) {
                    sql.append(" AND \"branchId\" = ?");
                    params.add(l.get("branchId"));
                }
                projected = projected.add(jdbc.sql(sql.toString()).params(params).query(BigDecimal.class).single());
            }
            BigDecimal cap = SqlRows.dec(l, "amount");
            if (projected.compareTo(cap) <= 0) {
                continue;
            }
            String scope = "monthly".equals(l.get("period")) ? " this month" : " per expense";
            Map<String, Object> detail = Obj.of("expenseId", exp.get("id"), "limitId", l.get("id"), "projected",
                    plain(projected), "limit", plain(cap), "action", l.get("action"));
            if ("block".equals(l.get("action"))) {
                audit.record(AuditInput.of("ExpenseLimit", (String) l.get("id"), "reject", null, detail));
                throw ApiException.withBody(HttpStatus.CONFLICT, Obj.of("code", "EXPENSE_LIMIT_EXCEEDED", "message",
                        "Expense limit \"" + l.get("name") + "\" exceeded: " + plain(projected) + " of " + plain(cap)
                                + scope + ".",
                        "limitId", l.get("id"), "projected", projected, "amount", cap));
            }
            audit.recordInTx(AuditInput.of("ExpenseLimit", (String) l.get("id"), "update", null, detail));
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listLimits() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"ExpenseLimit\" WHERE \"organizationId\" = ? ORDER BY name")
                .param(org())).stream().map(ExpenseControlService::limitShape).toList();
    }

    private static Map<String, Object> limitShape(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        m.put("amount", JsNumbers.js(Money.dec(r.get("amount"))));
        return m;
    }

    @Transactional
    public Map<String, Object> createLimit(LimitInput in) {
        if (in.name() == null || in.name().isBlank()) {
            throw ApiException.badRequest("Name is required");
        }
        if (in.amount() == null || in.amount().signum() <= 0) {
            throw ApiException.badRequest("amount must be a positive number");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ExpenseLimit\" (id, \"organizationId\", name, \"userId\", \"categoryId\", \"branchId\","
                        + " period, amount, action, \"isActive\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), in.name().trim(), blank(in.userId()), blank(in.categoryId()), blank(in.branchId()),
                        in.period() == null ? "per_expense" : in.period(), in.amount(),
                        in.action() == null ? "block" : in.action(), in.isActive() == null || in.isActive(),
                        TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("ExpenseLimit", id, "create", null, Obj.of("name", in.name(), "amount",
                JsNumbers.js(in.amount()))));
        return limit(id);
    }

    @Transactional
    public Map<String, Object> updateLimit(String id, LimitInput in) {
        Map<String, Object> before = limit(id);
        jdbc.sql("UPDATE \"ExpenseLimit\" SET name = ?, \"userId\" = ?, \"categoryId\" = ?, \"branchId\" = ?, period = ?,"
                        + " amount = ?, action = ?, \"isActive\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                        + " \"organizationId\" = ?")
                .params(in.name() == null ? before.get("name") : in.name().trim(), blank(in.userId()),
                        blank(in.categoryId()), blank(in.branchId()),
                        in.period() == null ? before.get("period") : in.period(),
                        in.amount() == null ? Money.dec(before.get("amount")) : in.amount(),
                        in.action() == null ? before.get("action") : in.action(),
                        in.isActive() == null ? before.get("isActive") : in.isActive(), id, org())
                .update();
        audit.recordInTx(AuditInput.of("ExpenseLimit", id, "update", before, null));
        return limit(id);
    }

    @Transactional
    public void deleteLimit(String id) {
        limit(id);
        jdbc.sql("DELETE FROM \"ExpenseLimit\" WHERE id = ? AND \"organizationId\" = ?").params(id, org()).update();
        audit.recordInTx(AuditInput.of("ExpenseLimit", id, "delete"));
    }

    private Map<String, Object> limit(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"ExpenseLimit\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Expense limit not found");
        }
        return limitShape(r);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ------------------------------------------------------------------ settings

    /** The organization's expense settings (defaults when never saved). */
    @Transactional(readOnly = true)
    public Map<String, Object> settings() {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT \"notifyOnDecision\", \"notifyOnPayment\","
                + " \"notifyApproversOnSubmit\", \"recurringReminderDays\", \"claimReceiptRequired\", \"updatedAt\""
                + " FROM \"ExpenseSettings\" WHERE \"organizationId\" = ?").param(org()));
        return r != null ? r : Obj.of("notifyOnDecision", true, "notifyOnPayment", true, "notifyApproversOnSubmit",
                false, "recurringReminderDays", 3, "claimReceiptRequired", false, "updatedAt", null);
    }

    @Transactional
    public Map<String, Object> updateSettings(SettingsInput in) {
        Map<String, Object> cur = settings();
        jdbc.sql("INSERT INTO \"ExpenseSettings\" (\"organizationId\", \"notifyOnDecision\", \"notifyOnPayment\","
                        + " \"notifyApproversOnSubmit\", \"recurringReminderDays\", \"claimReceiptRequired\", \"updatedBy\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, " + NOW + ") ON CONFLICT (\"organizationId\") DO"
                        + " UPDATE SET \"notifyOnDecision\" = EXCLUDED.\"notifyOnDecision\", \"notifyOnPayment\" ="
                        + " EXCLUDED.\"notifyOnPayment\", \"notifyApproversOnSubmit\" = EXCLUDED.\"notifyApproversOnSubmit\","
                        + " \"recurringReminderDays\" = EXCLUDED.\"recurringReminderDays\", \"claimReceiptRequired\" ="
                        + " EXCLUDED.\"claimReceiptRequired\", \"updatedBy\" = EXCLUDED.\"updatedBy\", \"updatedAt\" ="
                        + " EXCLUDED.\"updatedAt\"")
                .params(org(), or(in.notifyOnDecision(), cur.get("notifyOnDecision")),
                        or(in.notifyOnPayment(), cur.get("notifyOnPayment")),
                        or(in.notifyApproversOnSubmit(), cur.get("notifyApproversOnSubmit")),
                        or(in.recurringReminderDays(), cur.get("recurringReminderDays")),
                        or(in.claimReceiptRequired(), cur.get("claimReceiptRequired")), TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("ExpenseSettings", org(), "update", cur, null));
        return settings();
    }

    private static Object or(Object v, Object fallback) {
        return v != null ? v : fallback;
    }

    boolean setting(String key) {
        return Boolean.TRUE.equals(settings().get(key));
    }

    /**
     * In-app notification to {@code userId} when the setting {@code key} is on. Never fails the caller: a delivery
     * problem is logged.
     */
    void notify(String key, String userId, String title, String body, Map<String, Object> payload) {
        if (userId == null || !setting(key)) {
            return;
        }
        notifyAlways(userId, title, body, payload);
    }

    /** In-app notification regardless of settings (reminders); never fails the caller. */
    void notifyAlways(String userId, String title, String body, Map<String, Object> payload) {
        if (userId == null) {
            return;
        }
        try {
            notifications.send(new SendInput(org(), userId, "in_app", "expenses", title, body, payload));
        } catch (RuntimeException err) {
            log.warn("expense notification to {} failed: {}", userId, err.toString());
        }
    }
}
