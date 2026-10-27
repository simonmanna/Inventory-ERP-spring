package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Budgets and budget-vs-actual (ADR-S005). A budget holds monthly amounts ({@code YYYY-MM}) per postable account,
 * optionally scoped to a branch or cost centre. Lines are editable only while the budget is {@code draft};
 * {@code approve} locks it. Actuals are posted GL lines in each account's natural sign, filtered by the budget's
 * branch / cost centre.
 */
@Service
public class BudgetService {

    /** One budget line. */
    public record LineInput(String accountId, String period, BigDecimal amount, String notes) {}

    /** Create / update body. */
    public record BudgetInput(String name, Integer fiscalYear, String startDate, String endDate, String branchId,
            String costCenterId, String notes, List<LineInput> lines) {}

    static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private final JdbcClient jdbc;
    private final AccountResolverService accounts;

    public BudgetService(JdbcClient jdbc, AccountResolverService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static String plain(BigDecimal v) {
        return PostingService.plain(v.setScale(2, RoundingMode.HALF_UP));
    }

    // ------------------------------------------------------------------ reads

    /** {@code {data, meta}} newest first; optional {@code search} (name), {@code status}, {@code fiscalYear}. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(int page, int pageSize, String search, String status,
            Integer fiscalYear) {
        StringBuilder where = new StringBuilder(" WHERE b.\"organizationId\" = ? AND b.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (search != null && !search.isBlank()) {
            where.append(" AND b.name ILIKE ?");
            params.add("%" + BaseCrudService.escapeLike(search) + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND b.status = ?");
            params.add(status);
        }
        if (fiscalYear != null) {
            where.append(" AND b.\"fiscalYear\" = ?");
            params.add(fiscalYear);
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT b.*, (SELECT COALESCE(sum(l.amount), 0) FROM"
                + " \"BudgetLine\" l WHERE l.\"budgetId\" = b.id) AS \"totalAmount\", (SELECT count(*) FROM \"BudgetLine\" l"
                + " WHERE l.\"budgetId\" = b.id) AS \"lineCount\" FROM \"Budget\" b" + where
                + " ORDER BY b.\"createdAt\" DESC OFFSET " + (long) (page - 1) * pageSize + " LIMIT " + pageSize)
                .params(params));
        long total = jdbc.sql("SELECT count(*) FROM \"Budget\" b" + where).params(params).query(Long.class).single();
        return PageResult.of(rows, page, pageSize, total);
    }

    private Map<String, Object> header(String id) {
        Map<String, Object> b = SqlRows.one(jdbc.sql("SELECT * FROM \"Budget\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(id, org()));
        if (b == null) {
            throw ApiException.notFound("Budget " + id + " not found");
        }
        return b;
    }

    /** Header with its lines (account code / name) and the total. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> b = header(id);
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT l.id, l.\"accountId\", a.code AS \"accountCode\","
                + " a.name AS \"accountName\", l.period, l.amount, l.notes FROM \"BudgetLine\" l JOIN \"Account\" a ON"
                + " a.id = l.\"accountId\" WHERE l.\"budgetId\" = ? ORDER BY a.code, l.period").param(id));
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            total = total.add(SqlRows.dec(l, "amount"));
        }
        b.put("lines", lines);
        b.put("totalAmount", total);
        return b;
    }

    // ------------------------------------------------------------------ writes

    /** Create a draft; dates default to the fiscal year (Jan 1 – Dec 31). */
    @Transactional
    public Map<String, Object> create(BudgetInput in) {
        if (in.name() == null || in.name().isBlank()) {
            throw ApiException.badRequest("Budget name is required");
        }
        if (in.fiscalYear() == null) {
            throw ApiException.badRequest("fiscalYear is required");
        }
        LocalDate start = in.startDate() != null ? LocalDate.parse(in.startDate().substring(0, 10))
                : LocalDate.of(in.fiscalYear(), 1, 1);
        LocalDate end = in.endDate() != null ? LocalDate.parse(in.endDate().substring(0, 10))
                : LocalDate.of(in.fiscalYear(), 12, 31);
        if (end.isBefore(start)) {
            throw ApiException.badRequest("endDate must be on or after startDate");
        }
        assertUniqueName(in.name().trim(), null);
        assertScope(in.branchId(), in.costCenterId());
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Budget\" (id, \"organizationId\", name, \"fiscalYear\", \"startDate\", \"endDate\","
                        + " \"branchId\", \"costCenterId\", notes, \"createdBy\", \"createdAt\", \"updatedAt\") VALUES"
                        + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ", " + NOW + ")")
                .params(id, org(), in.name().trim(), in.fiscalYear(), start, end, blank(in.branchId()),
                        blank(in.costCenterId()), in.notes(), TenantContext.userId())
                .update();
        if (in.lines() != null) {
            writeLines(id, start, end, in.lines());
        }
        return findOne(id);
    }

    /** Rename / re-note any time; dates and scope only while draft. */
    @Transactional
    public Map<String, Object> update(String id, BudgetInput in) {
        Map<String, Object> b = header(id);
        boolean draft = "draft".equals(b.get("status"));
        boolean structural = in.startDate() != null || in.endDate() != null || in.branchId() != null
                || in.costCenterId() != null || in.fiscalYear() != null || in.lines() != null;
        if (!draft && structural) {
            throw ApiException.badRequest("Approved budgets are locked. Reopen the budget to change it.");
        }
        if (in.name() != null) {
            if (in.name().isBlank()) {
                throw ApiException.badRequest("Budget name is required");
            }
            assertUniqueName(in.name().trim(), id);
        }
        assertScope(in.branchId(), in.costCenterId());
        LocalDate start = in.startDate() != null ? LocalDate.parse(in.startDate().substring(0, 10))
                : (LocalDate) b.get("startDate");
        LocalDate end = in.endDate() != null ? LocalDate.parse(in.endDate().substring(0, 10))
                : (LocalDate) b.get("endDate");
        if (end.isBefore(start)) {
            throw ApiException.badRequest("endDate must be on or after startDate");
        }
        jdbc.sql("UPDATE \"Budget\" SET name = COALESCE(?, name), \"fiscalYear\" = COALESCE(?, \"fiscalYear\"),"
                        + " \"startDate\" = ?, \"endDate\" = ?, \"branchId\" = COALESCE(?, \"branchId\"),"
                        + " \"costCenterId\" = COALESCE(?, \"costCenterId\"), notes = COALESCE(?, notes), \"updatedAt\" = "
                        + NOW + " WHERE id = ?")
                .params(in.name() == null ? null : in.name().trim(), in.fiscalYear(), start, end, blank(in.branchId()),
                        blank(in.costCenterId()), in.notes(), id)
                .update();
        if (in.lines() != null) {
            writeLines(id, start, end, in.lines());
        }
        return findOne(id);
    }

    /** Replace every line (draft only). */
    @Transactional
    public Map<String, Object> replaceLines(String id, List<LineInput> lines) {
        Map<String, Object> b = header(id);
        if (!"draft".equals(b.get("status"))) {
            throw ApiException.badRequest("Approved budgets are locked. Reopen the budget to change it.");
        }
        writeLines(id, (LocalDate) b.get("startDate"), (LocalDate) b.get("endDate"), lines == null ? List.of() : lines);
        jdbc.sql("UPDATE \"Budget\" SET \"updatedAt\" = " + NOW + " WHERE id = ?").param(id).update();
        return findOne(id);
    }

    private void writeLines(String budgetId, LocalDate start, LocalDate end, List<LineInput> lines) {
        YearMonth first = YearMonth.from(start);
        YearMonth last = YearMonth.from(end);
        Set<String> seen = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (LineInput l : lines) {
            if (l.accountId() == null) {
                throw ApiException.badRequest("Each budget line needs an accountId");
            }
            ids.add(l.accountId());
        }
        Map<String, AccountMeta> meta = accounts.meta(ids);
        for (LineInput l : lines) {
            AccountMeta a = meta.get(l.accountId());
            if (a == null) {
                throw ApiException.badRequest("Account " + l.accountId() + " not found");
            }
            if (!a.isPostable()) {
                throw ApiException.badRequest("Account " + a.code() + " is a summary account and cannot be budgeted");
            }
            if (!a.allowBudgeting()) {
                throw ApiException.badRequest("Account " + a.code() + " does not allow budgeting");
            }
            YearMonth ym;
            try {
                ym = YearMonth.parse(l.period() == null ? "" : l.period());
            } catch (RuntimeException e) {
                throw ApiException.badRequest("period must be YYYY-MM");
            }
            if (ym.isBefore(first) || ym.isAfter(last)) {
                throw ApiException.badRequest("Period " + l.period() + " is outside the budget dates");
            }
            if (l.amount() == null || l.amount().signum() < 0) {
                throw ApiException.badRequest("Budget amounts must be zero or positive");
            }
            if (!seen.add(l.accountId() + "|" + l.period())) {
                throw ApiException.badRequest("Duplicate line for account " + a.code() + " in " + l.period());
            }
        }
        jdbc.sql("DELETE FROM \"BudgetLine\" WHERE \"budgetId\" = ?").param(budgetId).update();
        for (LineInput l : lines) {
            jdbc.sql("INSERT INTO \"BudgetLine\" (id, \"organizationId\", \"budgetId\", \"accountId\", period, amount, notes,"
                            + " \"createdAt\") VALUES (?, ?, ?, ?, ?, ?, ?, " + NOW + ")")
                    .params(UUID.randomUUID().toString(), org(), budgetId, l.accountId(), l.period(), l.amount(),
                            l.notes())
                    .update();
        }
    }

    @Transactional
    public Map<String, Object> approve(String id) {
        Map<String, Object> b = header(id);
        if (!"draft".equals(b.get("status"))) {
            throw ApiException.badRequest("Budget is already approved");
        }
        long lines = jdbc.sql("SELECT count(*) FROM \"BudgetLine\" WHERE \"budgetId\" = ?").param(id)
                .query(Long.class).single();
        if (lines == 0) {
            throw ApiException.badRequest("Add at least one budget line before approving");
        }
        jdbc.sql("UPDATE \"Budget\" SET status = 'approved', \"approvedBy\" = ?, \"approvedAt\" = " + NOW
                + ", \"updatedAt\" = " + NOW + " WHERE id = ?").params(TenantContext.userId(), id).update();
        return findOne(id);
    }

    @Transactional
    public Map<String, Object> reopen(String id) {
        Map<String, Object> b = header(id);
        if (!"approved".equals(b.get("status"))) {
            throw ApiException.badRequest("Only approved budgets can be reopened");
        }
        jdbc.sql("UPDATE \"Budget\" SET status = 'draft', \"approvedBy\" = NULL, \"approvedAt\" = NULL, \"updatedAt\" = "
                + NOW + " WHERE id = ?").param(id).update();
        return findOne(id);
    }

    /** Soft delete (draft only). */
    @Transactional
    public void remove(String id) {
        Map<String, Object> b = header(id);
        if (!"draft".equals(b.get("status"))) {
            throw ApiException.badRequest("Approved budgets cannot be deleted. Reopen the budget first.");
        }
        jdbc.sql("UPDATE \"Budget\" SET \"deletedAt\" = " + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ?")
                .param(id).update();
    }

    // ------------------------------------------------------------------ budget vs actual

    /**
     * Per account: budget, actual, variance (actual - budget), variance % and whether it is favourable (income above
     * / expense below budget), with a monthly breakdown; totals for income, expense and net. {@code from} / {@code to}
     * ({@code YYYY-MM}) narrow the budget's own months.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> vsActual(String id, String fromRaw, String toRaw) {
        Map<String, Object> b = header(id);
        YearMonth first = YearMonth.from((LocalDate) b.get("startDate"));
        YearMonth last = YearMonth.from((LocalDate) b.get("endDate"));
        YearMonth from = fromRaw == null || fromRaw.isBlank() ? first : parseMonth(fromRaw);
        YearMonth to = toRaw == null || toRaw.isBlank() ? last : parseMonth(toRaw);
        if (from.isBefore(first)) {
            from = first;
        }
        if (to.isAfter(last)) {
            to = last;
        }
        if (to.isBefore(from)) {
            throw ApiException.badRequest("from must be on or before to");
        }
        ZoneId zone = OrgDates.orgTimezone(jdbc, org());
        Instant start = from.atDay(1).atStartOfDay(zone).toInstant();
        Instant endExcl = to.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();

        Map<String, Map<String, BigDecimal>> budget = new LinkedHashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT \"accountId\", period, amount FROM \"BudgetLine\""
                + " WHERE \"budgetId\" = ? AND period >= ? AND period <= ?").params(id, from.toString(), to.toString()))) {
            budget.computeIfAbsent((String) l.get("accountId"), k -> new TreeMap<>())
                    .merge((String) l.get("period"), SqlRows.dec(l, "amount"), BigDecimal::add);
        }
        StringBuilder sql = new StringBuilder("SELECT l.\"accountId\", to_char(e.\"postingDate\" AT TIME ZONE 'UTC' AT"
                + " TIME ZONE ?, 'YYYY-MM') AS period, sum(l.\"baseDebit\") AS d, sum(l.\"baseCredit\") AS c FROM"
                + " \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ?"
                + " AND e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" >= ? AND e.\"postingDate\" < ?");
        List<Object> params = new ArrayList<>(List.of(zone.getId(), org(), FiscalPeriodControl.ts(start),
                FiscalPeriodControl.ts(endExcl)));
        if (b.get("branchId") != null) {
            sql.append(" AND l.\"branchId\" = ?");
            params.add(b.get("branchId"));
        }
        if (b.get("costCenterId") != null) {
            sql.append(" AND l.\"costCenterId\" = ?");
            params.add(b.get("costCenterId"));
        }
        sql.append(" GROUP BY l.\"accountId\", 2");
        Map<String, Map<String, BigDecimal[]>> actualRaw = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(sql.toString()).params(params))) {
            actualRaw.computeIfAbsent((String) r.get("accountId"), k -> new TreeMap<>())
                    .put((String) r.get("period"), new BigDecimal[] {SqlRows.dec(r, "d"), SqlRows.dec(r, "c")});
        }
        Set<String> ids = new HashSet<>(budget.keySet());
        // Only P&L actuals matter when the account was not budgeted; balance-sheet movements are noise here.
        Map<String, AccountMeta> metaAll = accounts.meta(actualRaw.keySet());
        for (String accountId : actualRaw.keySet()) {
            AccountMeta a = metaAll.get(accountId);
            if (a != null && AccountClassification.isProfitAndLoss(a.classification())) {
                ids.add(accountId);
            }
        }
        Map<String, AccountMeta> meta = accounts.meta(ids);
        List<String> months = new ArrayList<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            months.add(m.toString());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal incomeBudget = BigDecimal.ZERO;
        BigDecimal incomeActual = BigDecimal.ZERO;
        BigDecimal expenseBudget = BigDecimal.ZERO;
        BigDecimal expenseActual = BigDecimal.ZERO;
        for (String accountId : ids) {
            AccountMeta a = meta.get(accountId);
            if (a == null) {
                continue;
            }
            boolean income = isIncome(a);
            Map<String, BigDecimal> bm = budget.getOrDefault(accountId, Map.of());
            Map<String, BigDecimal[]> am = actualRaw.getOrDefault(accountId, Map.of());
            BigDecimal bTotal = BigDecimal.ZERO;
            BigDecimal aTotal = BigDecimal.ZERO;
            List<Map<String, Object>> monthly = new ArrayList<>();
            for (String m : months) {
                BigDecimal bv = bm.getOrDefault(m, BigDecimal.ZERO);
                BigDecimal[] dc = am.get(m);
                BigDecimal av = dc == null ? BigDecimal.ZERO
                        : AccountClassification.displayBalance(dc[0].subtract(dc[1]), a.normalBalance());
                bTotal = bTotal.add(bv);
                aTotal = aTotal.add(av);
                monthly.add(Obj.of("period", m, "budget", plain(bv), "actual", plain(av)));
            }
            BigDecimal variance = aTotal.subtract(bTotal);
            boolean favorable = income ? variance.signum() >= 0 : variance.signum() <= 0;
            rows.add(Obj.of("accountId", accountId, "code", a.code(), "name", a.name(), "classification",
                    a.classification(), "reportSection", a.reportSection(), "type", income ? "income" : "expense",
                    "budget", plain(bTotal), "actual", plain(aTotal), "variance", plain(variance),
                    "variancePct", bTotal.signum() == 0 ? null
                            : variance.multiply(BigDecimal.valueOf(100)).divide(bTotal, 2, RoundingMode.HALF_UP),
                    "favorable", favorable, "utilizationPct", bTotal.signum() == 0 ? null
                            : aTotal.multiply(BigDecimal.valueOf(100)).divide(bTotal, 2, RoundingMode.HALF_UP),
                    "months", monthly));
            if (income) {
                incomeBudget = incomeBudget.add(bTotal);
                incomeActual = incomeActual.add(aTotal);
            } else {
                expenseBudget = expenseBudget.add(bTotal);
                expenseActual = expenseActual.add(aTotal);
            }
        }
        rows.sort((x, y) -> String.valueOf(x.get("code")).compareTo(String.valueOf(y.get("code"))));
        BigDecimal netBudget = incomeBudget.subtract(expenseBudget);
        BigDecimal netActual = incomeActual.subtract(expenseActual);
        return Obj.of("budget", Obj.of("id", b.get("id"), "name", b.get("name"), "status", b.get("status"),
                        "fiscalYear", b.get("fiscalYear"), "branchId", b.get("branchId"), "costCenterId",
                        b.get("costCenterId")),
                "from", from.toString(), "to", to.toString(), "months", months, "rows", rows,
                "totals", Obj.of(
                        "income", Obj.of("budget", plain(incomeBudget), "actual", plain(incomeActual), "variance",
                                plain(incomeActual.subtract(incomeBudget))),
                        "expense", Obj.of("budget", plain(expenseBudget), "actual", plain(expenseActual), "variance",
                                plain(expenseActual.subtract(expenseBudget))),
                        "net", Obj.of("budget", plain(netBudget), "actual", plain(netActual), "variance",
                                plain(netActual.subtract(netBudget)))));
    }

    /** Income = credit-normal P&L sections (revenue, other income); everything else counts as expense. */
    static boolean isIncome(AccountMeta a) {
        String s = String.valueOf(a.reportSection());
        if ("revenue".equals(s) || "other_income".equals(s)) {
            return true;
        }
        if ("contra_revenue".equals(s) || "cogs".equals(s) || "operating_expense".equals(s)
                || "other_expense".equals(s)) {
            return false;
        }
        return "credit".equals(a.normalBalance());
    }

    private static YearMonth parseMonth(String raw) {
        try {
            return YearMonth.parse(raw.length() > 7 ? raw.substring(0, 7) : raw);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("from / to must be YYYY-MM");
        }
    }

    private void assertUniqueName(String name, String exceptId) {
        long n = jdbc.sql("SELECT count(*) FROM \"Budget\" WHERE \"organizationId\" = ? AND name = ? AND \"deletedAt\""
                        + " IS NULL AND id <> ?").params(org(), name, exceptId == null ? "" : exceptId)
                .query(Long.class).single();
        if (n > 0) {
            throw ApiException.conflict("A budget named \"" + name + "\" already exists");
        }
    }

    private void assertScope(String branchId, String costCenterId) {
        if (blank(branchId) != null && jdbc.sql("SELECT count(*) FROM \"Branch\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(branchId, org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Branch " + branchId + " not found");
        }
        if (blank(costCenterId) != null && jdbc.sql("SELECT count(*) FROM \"CostCenter\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(costCenterId, org())
                .query(Long.class).single() == 0) {
            throw ApiException.badRequest("Cost center " + costCenterId + " not found");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
