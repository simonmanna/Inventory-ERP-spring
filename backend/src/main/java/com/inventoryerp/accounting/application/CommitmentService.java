package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Budget commitments (ADR-PROCUREMENT-001, {@code docs/spec/procurement-postings.md} §9): memo records, never journals.
 * A source document (an approved purchase order) commits the value of each line against a budget account, branch,
 * cost centre and month; receipts relieve it and a cancel or short-close releases the rest. Callers re-state the open
 * value of every line with {@link #sync} after each change, so the records are idempotent and self-healing.
 *
 * <p>{@link #check} answers "is there budget left?" for a set of lines: approved budget for the account over the
 * budget's dates, minus posted actuals (same numbers as budget versus actual), minus other open commitments.
 */
@Service
public class CommitmentService {

    /** One committed line. {@code period} is {@code YYYY-MM}; amounts are functional currency. */
    public record Line(String sourceLineId, String accountId, String branchId, String costCenterId, String projectId,
            String period, BigDecimal amount, BigDecimal openAmount) {}

    /** Shortfall against one approved budget for one account. */
    public record Shortfall(String budgetId, String budgetName, String accountId, String accountCode,
            String accountName, BigDecimal budget, BigDecimal actual, BigDecimal committed, BigDecimal requested,
            BigDecimal available, BigDecimal overBy) {

        /** Wire map (amounts as plain strings). */
        public Map<String, Object> toMap() {
            return Obj.of("budgetId", budgetId, "budgetName", budgetName, "accountId", accountId, "accountCode",
                    accountCode, "accountName", accountName, "budget", plain(budget), "actual", plain(actual),
                    "committed", plain(committed), "requested", plain(requested), "available", plain(available),
                    "overBy", plain(overBy));
        }
    }

    static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private final JdbcClient jdbc;
    private final AccountResolverService accounts;

    public CommitmentService(JdbcClient jdbc, AccountResolverService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    static String plain(BigDecimal v) {
        return PostingService.plain(v.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Re-state the commitments of one source document: each given line is upserted with its open amount (closed at
     * zero); lines of the source no longer listed are released. Call inside the caller's transaction.
     */
    public void sync(String sourceType, String sourceId, String sourceNumber, List<Line> lines) {
        String orgId = org();
        Set<String> keep = new HashSet<>();
        for (Line l : lines) {
            keep.add(l.sourceLineId());
            BigDecimal open = l.openAmount().max(BigDecimal.ZERO);
            BigDecimal amount = l.amount().max(open);
            jdbc.sql("INSERT INTO \"Commitment\" (id, \"organizationId\", \"sourceType\", \"sourceId\", \"sourceLineId\","
                            + " \"sourceNumber\", \"accountId\", \"branchId\", \"costCenterId\", \"projectId\", period, amount,"
                            + " \"openAmount\", status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                            + " ON CONFLICT (\"organizationId\", \"sourceType\", \"sourceLineId\") DO UPDATE SET"
                            + " \"accountId\" = EXCLUDED.\"accountId\", \"branchId\" = EXCLUDED.\"branchId\","
                            + " \"costCenterId\" = EXCLUDED.\"costCenterId\", \"projectId\" = EXCLUDED.\"projectId\","
                            + " period = EXCLUDED.period, amount = EXCLUDED.amount, \"openAmount\" = EXCLUDED.\"openAmount\","
                            + " status = EXCLUDED.status, \"sourceNumber\" = EXCLUDED.\"sourceNumber\", \"updatedAt\" = " + NOW)
                    .params(UUID.randomUUID().toString(), orgId, sourceType, sourceId, l.sourceLineId(), sourceNumber,
                            l.accountId(), l.branchId(), l.costCenterId(), l.projectId(), l.period(), amount, open,
                            open.signum() > 0 ? "open" : "closed")
                    .update();
        }
        jdbc.sql("UPDATE \"Commitment\" SET \"openAmount\" = 0, status = 'closed', \"updatedAt\" = " + NOW
                        + " WHERE \"organizationId\" = ? AND \"sourceType\" = ? AND \"sourceId\" = ? AND status = 'open'"
                        + " AND NOT (\"sourceLineId\" = ANY(?::text[]))")
                .params(orgId, sourceType, sourceId, keep.toArray(String[]::new)).update();
    }

    /** Release every open commitment of a source (cancel / close). */
    public void release(String sourceType, String sourceId) {
        sync(sourceType, sourceId, null, List.of());
    }

    /**
     * Shortfalls for the requested lines against every approved budget that covers them (matching account and month,
     * and the budget's branch / cost centre when it has one). Empty = within budget or no budget applies. Open
     * commitments of {@code excludeSourceId} are not counted (the document being checked).
     */
    public List<Shortfall> check(List<Line> requested, String excludeSourceId) {
        String orgId = org();
        Map<String, BigDecimal> perBudgetAccount = new LinkedHashMap<>();
        Map<String, Map<String, Object>> budgets = new HashMap<>();
        for (Line l : requested) {
            if (l.openAmount().signum() <= 0) {
                continue;
            }
            for (Map<String, Object> b : SqlRows.list(jdbc.sql("SELECT DISTINCT b.id, b.name, b.\"startDate\","
                            + " b.\"endDate\", b.\"branchId\", b.\"costCenterId\" FROM \"Budget\" b JOIN \"BudgetLine\" bl ON"
                            + " bl.\"budgetId\" = b.id WHERE b.\"organizationId\" = ? AND b.\"deletedAt\" IS NULL AND"
                            + " b.status = 'approved' AND bl.\"accountId\" = ? AND bl.period = ? AND (b.\"branchId\" IS NULL"
                            + " OR b.\"branchId\" = ?) AND (b.\"costCenterId\" IS NULL OR b.\"costCenterId\" = ?)")
                    .params(orgId, l.accountId(), l.period(), l.branchId(), l.costCenterId()))) {
                String id = (String) b.get("id");
                budgets.put(id, b);
                perBudgetAccount.merge(id + "|" + l.accountId(), l.openAmount(), BigDecimal::add);
            }
        }
        if (perBudgetAccount.isEmpty()) {
            return List.of();
        }
        ZoneId zone = OrgDates.orgTimezone(jdbc, orgId);
        Map<String, AccountMeta> meta = accounts.meta(perBudgetAccount.keySet().stream()
                .map(k -> k.substring(k.indexOf('|') + 1)).collect(java.util.stream.Collectors.toSet()));
        List<Shortfall> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : perBudgetAccount.entrySet()) {
            String budgetId = e.getKey().substring(0, e.getKey().indexOf('|'));
            String accountId = e.getKey().substring(e.getKey().indexOf('|') + 1);
            Map<String, Object> b = budgets.get(budgetId);
            YearMonth first = YearMonth.from((LocalDate) b.get("startDate"));
            YearMonth last = YearMonth.from((LocalDate) b.get("endDate"));
            BigDecimal budget = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM \"BudgetLine\" WHERE \"budgetId\" = ? AND"
                    + " \"accountId\" = ?").params(budgetId, accountId).query(BigDecimal.class).single();
            AccountMeta a = meta.get(accountId);
            BigDecimal actual = actual(orgId, accountId, a, b, first, last, zone);
            BigDecimal committed = committed(orgId, accountId, (String) b.get("branchId"),
                    (String) b.get("costCenterId"), first.toString(), last.toString(), excludeSourceId);
            BigDecimal available = budget.subtract(actual).subtract(committed);
            BigDecimal overBy = e.getValue().subtract(available);
            if (overBy.signum() > 0) {
                out.add(new Shortfall(budgetId, (String) b.get("name"), accountId, a == null ? null : a.code(),
                        a == null ? null : a.name(), budget, actual, committed, e.getValue(), available, overBy));
            }
        }
        return out;
    }

    /** Open commitments for an account (optionally narrowed to a branch / cost centre) between two months. */
    public BigDecimal committed(String orgId, String accountId, String branchId, String costCenterId, String fromPeriod,
            String toPeriod, String excludeSourceId) {
        return jdbc.sql("SELECT COALESCE(SUM(\"openAmount\"), 0) FROM \"Commitment\" WHERE \"organizationId\" = ? AND"
                        + " status = 'open' AND \"accountId\" = ? AND period >= ? AND period <= ? AND (?::text IS NULL OR"
                        + " \"branchId\" = ?) AND (?::text IS NULL OR \"costCenterId\" = ?) AND (?::text IS NULL OR"
                        + " \"sourceId\" <> ?)")
                .params(orgId, accountId, fromPeriod, toPeriod, branchId, branchId, costCenterId, costCenterId,
                        excludeSourceId, excludeSourceId)
                .query(BigDecimal.class).single();
    }

    /** Open commitments per account and month for budget versus actual (filtered like the budget). */
    public Map<String, Map<String, BigDecimal>> committedByAccount(String branchId, String costCenterId,
            String fromPeriod, String toPeriod) {
        Map<String, Map<String, BigDecimal>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"accountId\", period, SUM(\"openAmount\") AS v FROM"
                        + " \"Commitment\" WHERE \"organizationId\" = ? AND status = 'open' AND period >= ? AND period <= ?"
                        + " AND (?::text IS NULL OR \"branchId\" = ?) AND (?::text IS NULL OR \"costCenterId\" = ?)"
                        + " GROUP BY 1, 2")
                .params(org(), fromPeriod, toPeriod, branchId, branchId, costCenterId, costCenterId))) {
            out.computeIfAbsent((String) r.get("accountId"), k -> new LinkedHashMap<>())
                    .put((String) r.get("period"), SqlRows.dec(r, "v"));
        }
        return out;
    }

    /** Commitments register: {@code {data, meta}} newest first. */
    public PageResult<Map<String, Object>> list(String sourceType, String sourceId, String accountId, String status,
            int page, int limit) {
        StringBuilder where = new StringBuilder(" WHERE c.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (sourceType != null && !sourceType.isBlank()) {
            where.append(" AND c.\"sourceType\" = ?");
            params.add(sourceType);
        }
        if (sourceId != null && !sourceId.isBlank()) {
            where.append(" AND c.\"sourceId\" = ?");
            params.add(sourceId);
        }
        if (accountId != null && !accountId.isBlank()) {
            where.append(" AND c.\"accountId\" = ?");
            params.add(accountId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND c.status = ?");
            params.add(status);
        }
        long total = jdbc.sql("SELECT count(*) FROM \"Commitment\" c" + where).params(params).query(Long.class)
                .single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(limit);
        pageParams.add((long) (page - 1) * limit);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT c.*, a.code AS \"accountCode\", a.name AS"
                + " \"accountName\" FROM \"Commitment\" c LEFT JOIN \"Account\" a ON a.id = c.\"accountId\"" + where
                + " ORDER BY c.\"updatedAt\" DESC, c.id LIMIT ? OFFSET ?").params(pageParams))) {
            r.put("amount", plain(SqlRows.dec(r, "amount")));
            r.put("openAmount", plain(SqlRows.dec(r, "openAmount")));
            rows.add(r);
        }
        return PageResult.of(rows, page, limit, total);
    }

    private BigDecimal actual(String orgId, String accountId, AccountMeta a, Map<String, Object> b, YearMonth first,
            YearMonth last, ZoneId zone) {
        Instant start = first.atDay(1).atStartOfDay(zone).toInstant();
        Instant endExcl = last.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT COALESCE(SUM(l.\"baseDebit\"), 0) AS d,"
                        + " COALESCE(SUM(l.\"baseCredit\"), 0) AS c FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON"
                        + " e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND l.\"accountId\" = ? AND"
                        + " e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" >= ? AND e.\"postingDate\" < ?"
                        + " AND (?::text IS NULL OR l.\"branchId\" = ?) AND (?::text IS NULL OR l.\"costCenterId\" = ?)")
                .params(orgId, accountId, FiscalPeriodControl.ts(start), FiscalPeriodControl.ts(endExcl),
                        b.get("branchId"), b.get("branchId"), b.get("costCenterId"), b.get("costCenterId")));
        BigDecimal net = SqlRows.dec(r, "d").subtract(SqlRows.dec(r, "c"));
        return a == null ? net : AccountClassification.displayBalance(net, a.normalBalance());
    }
}
