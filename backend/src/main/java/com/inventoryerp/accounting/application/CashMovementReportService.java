package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.application.MoneyActivityTaxonomy.toFixed2;

import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash Flow (movement) report (port of {@code cash-movement-report.service.ts}): every journal line on a
 * cash-equivalent account, with summary, per-account / per-category rollups, a trend series and contra
 * counterparties, all derived from one filtered movement set.
 */
@Service
public class CashMovementReportService {

    /** Report filters (raw query values). */
    public record Filters(String from, String to, List<String> accountIds, List<String> accountTypes,
            List<String> categories, String direction, String search, Double minAmount, String groupBy, double page,
            double pageSize) {}

    private static final String FROM = " FROM \"JournalLine\" jl JOIN \"JournalEntry\" je ON je.id = jl.\"journalEntryId\""
            + " JOIN \"Account\" a ON a.id = jl.\"accountId\"";

    private final JdbcClient jdbc;
    private final AccountResolverService accounts;

    public CashMovementReportService(JdbcClient jdbc, AccountResolverService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** SQL fragment + its named parameters. */
    private record Where(String sql, Map<String, Object> params) {}

    private JdbcClient.StatementSpec spec(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec s = jdbc.sql(sql);
        for (Map.Entry<String, Object> p : params.entrySet()) {
            s = s.param(p.getKey(), p.getValue());
        }
        return s;
    }

    private static double num(Map<String, Object> row, String key) {
        return row == null ? 0 : SqlRows.dec(row, key).doubleValue();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> report(Filters f) {
        String orgId = TenantContext.organizationId();
        ZoneId zone = OrgDates.orgTimezone(jdbc, orgId);
        Instant from = OrgDates.orgDateBound(f.from(), "from", zone, true);
        Instant to = OrgDates.orgDateBound(f.to(), "to", zone, false);
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("`from` must be on or before `to`");
        }
        int page = Math.max(1, Double.isNaN(f.page()) || f.page() == 0 ? 1 : (int) f.page());
        int pageSize = Math.min(500, Math.max(1, Double.isNaN(f.pageSize()) || f.pageSize() == 0 ? 50 : (int) f.pageSize()));
        String groupBy = f.groupBy() == null ? "day" : f.groupBy();

        List<AccountMeta> cash = accounts.cashEquivalentAccounts(false);
        Map<String, AccountMeta> meta = new HashMap<>();
        cash.forEach(a -> meta.put(a.id(), a));
        List<String> scoped = cash.stream().map(AccountMeta::id).toList();
        if (f.accountTypes() != null && !f.accountTypes().isEmpty()) {
            Set<String> wanted = Set.copyOf(f.accountTypes());
            scoped = scoped.stream().filter(id -> wanted.contains(meta.get(id).categoryKey() == null ? "" : meta.get(id).categoryKey())).toList();
        }
        if (f.accountIds() != null && !f.accountIds().isEmpty()) {
            Set<String> wanted = Set.copyOf(f.accountIds());
            scoped = scoped.stream().filter(wanted::contains).toList();
        }
        List<Map<String, Object>> accountOptions = cash.stream().map(a -> body("id", a.id(), "code", a.code(), "name", a.name(),
                "accountType", a.categoryKey())).toList();
        Map<String, Object> filterEcho = body("from", f.from(), "to", f.to(),
                "accountIds", f.accountIds() == null ? List.of() : f.accountIds(),
                "accountTypes", f.accountTypes() == null ? List.of() : f.accountTypes(),
                "categories", f.categories() == null ? List.of() : f.categories(),
                "direction", f.direction() == null ? "all" : f.direction(), "search", f.search() == null ? "" : f.search(),
                "minAmount", f.minAmount(), "groupBy", groupBy);
        if (scoped.isEmpty()) {
            return body("filters", filterEcho, "summary", body("openingBalance", "0.00", "cashIn", "0.00", "cashOut", "0.00",
                            "netChange", "0.00", "closingBalance", "0.00", "movementCount", 0, "inflowCount", 0, "outflowCount", 0,
                            "largestInflow", "0.00", "largestOutflow", "0.00", "externalIn", "0.00", "externalOut", "0.00",
                            "internalMoved", "0.00"),
                    "data", List.of(), "byAccount", List.of(), "byCategory", List.of(), "series", List.of(),
                    "accountOptions", accountOptions, "categoryOptions", MoneyActivityTaxonomy.CATEGORY_OPTIONS,
                    "page", page, "pageSize", pageSize, "total", 0, "totalPages", 1);
        }
        Map<String, Object> scopeParams = new LinkedHashMap<>();
        scopeParams.put("org", orgId);
        scopeParams.put("scoped", scoped);
        String scope = " jl.\"organizationId\" = :org AND jl.\"accountId\" IN (:scoped) AND je.status::text IN ('posted', 'reversed')";
        Where where = movementFilters(f, from, to, scope, scopeParams);

        Map<String, Object> totals = SqlRows.one(spec("SELECT COUNT(*) AS count, COALESCE(SUM(jl.\"baseDebit\"), 0) AS inflow,"
                + " COALESCE(SUM(jl.\"baseCredit\"), 0) AS outflow, COUNT(*) FILTER (WHERE jl.\"baseDebit\" > 0) AS inflow_count,"
                + " COUNT(*) FILTER (WHERE jl.\"baseCredit\" > 0) AS outflow_count, COALESCE(MAX(jl.\"baseDebit\"), 0) AS max_in,"
                + " COALESCE(MAX(jl.\"baseCredit\"), 0) AS max_out" + FROM + " WHERE" + where.sql(), where.params()));
        Map<String, Object> entryTotals = SqlRows.one(spec("SELECT COALESCE(SUM(GREATEST(x.d - x.c, 0)), 0) AS external_in,"
                + " COALESCE(SUM(GREATEST(x.c - x.d, 0)), 0) AS external_out, COALESCE(SUM(LEAST(x.d, x.c)), 0) AS internal_moved"
                + " FROM (SELECT je.id, SUM(jl.\"baseDebit\") AS d, SUM(jl.\"baseCredit\") AS c" + FROM + " WHERE" + where.sql()
                + " GROUP BY je.id) x", where.params()));
        Map<String, Object> rowParams = new LinkedHashMap<>(where.params());
        rowParams.put("lim", pageSize);
        rowParams.put("off", (page - 1) * pageSize);
        List<Map<String, Object>> rows = SqlRows.list(spec("SELECT jl.id, jl.\"journalEntryId\", je.\"entryNumber\", je.\"postingDate\","
                + " COALESCE(je.description, jl.description) AS description, je.\"sourceType\", je.\"sourceId\", jl.\"baseDebit\","
                + " jl.\"baseCredit\", jl.\"accountId\", a.code AS \"accountCode\", a.name AS \"accountName\", ac.key AS \"accountType\","
                + " br.name AS \"branchName\", cc.name AS \"costCenterName\"" + FROM
                + " LEFT JOIN \"AccountCategory\" ac ON ac.id = a.\"categoryId\""
                + " LEFT JOIN \"Branch\" br ON br.id = COALESCE(jl.\"branchId\", je.\"branchId\")"
                + " LEFT JOIN \"CostCenter\" cc ON cc.id = COALESCE(jl.\"costCenterId\", je.\"costCenterId\")"
                + " WHERE jl.\"organizationId\" = :org AND" + where.sql()
                + " ORDER BY je.\"postingDate\" DESC, je.\"entryNumber\" DESC, jl.\"lineNumber\" ASC LIMIT :lim OFFSET :off", rowParams));
        List<Map<String, Object>> byAccount = SqlRows.list(spec("SELECT jl.\"accountId\", COALESCE(SUM(jl.\"baseDebit\"), 0) AS inflow,"
                + " COALESCE(SUM(jl.\"baseCredit\"), 0) AS outflow, COUNT(*) AS count" + FROM + " WHERE" + where.sql()
                + " GROUP BY jl.\"accountId\"", where.params()));
        byAccount.sort(Comparator.comparingDouble((Map<String, Object> r) -> -(num(r, "inflow") + num(r, "outflow"))));
        List<Map<String, Object>> byCategoryRaw = SqlRows.list(spec("SELECT " + MoneyActivityTaxonomy.EFFECTIVE_SOURCE_TYPE
                + " AS \"sourceType\", COALESCE(SUM(jl.\"baseDebit\"), 0) AS inflow, COALESCE(SUM(jl.\"baseCredit\"), 0) AS outflow,"
                + " COUNT(*) AS count" + FROM + " WHERE" + where.sql() + " GROUP BY 1", where.params()));
        Map<String, double[]> byCategory = new LinkedHashMap<>();
        for (Map<String, Object> r : byCategoryRaw) {
            String cat = MoneyActivityTaxonomy.categoryOf((String) r.get("sourceType"));
            double[] cur = byCategory.computeIfAbsent(cat, k -> new double[3]);
            cur[0] += num(r, "inflow");
            cur[1] += num(r, "outflow");
            cur[2] += ((Number) r.get("count")).longValue();
        }
        String unit = "month".equals(groupBy) ? "month" : "week".equals(groupBy) ? "week" : "day";
        Map<String, Object> seriesParams = new LinkedHashMap<>(where.params());
        seriesParams.put("unit", unit);
        seriesParams.put("tz", zone instanceof java.time.ZoneOffset ? "UTC" : zone.getId());
        List<Map<String, Object>> series = SqlRows.list(spec("SELECT date_trunc(:unit, (je.\"postingDate\" AT TIME ZONE 'UTC')"
                + " AT TIME ZONE :tz) AS period, COALESCE(SUM(jl.\"baseDebit\"), 0) AS inflow, COALESCE(SUM(jl.\"baseCredit\"), 0)"
                + " AS outflow, COUNT(*) AS count" + FROM + " WHERE" + where.sql() + " GROUP BY 1 ORDER BY 1 ASC", seriesParams));
        double openingIn = 0;
        double openingOut = 0;
        if (from != null) {
            Map<String, Object> openParams = new LinkedHashMap<>(scopeParams);
            openParams.put("before", FiscalPeriodControl.ts(from));
            Map<String, Object> opening = SqlRows.one(spec("SELECT COALESCE(SUM(jl.\"baseDebit\"), 0) AS inflow,"
                    + " COALESCE(SUM(jl.\"baseCredit\"), 0) AS outflow" + FROM + " WHERE" + scope + " AND je.\"postingDate\" < :before",
                    openParams));
            openingIn = num(opening, "inflow");
            openingOut = num(opening, "outflow");
        }
        Map<Object, List<Map<String, Object>>> counterparties = counterparties(orgId, rows);
        double cashIn = num(totals, "inflow");
        double cashOut = num(totals, "outflow");
        double openingBalance = openingIn - openingOut;
        long count = totals == null ? 0 : ((Number) totals.get("count")).longValue();

        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            double inflow = num(r, "baseDebit");
            double outflow = num(r, "baseCredit");
            String category = MoneyActivityTaxonomy.categoryOf((String) r.get("sourceType"));
            data.add(body("id", r.get("id"), "journalEntryId", r.get("journalEntryId"), "entryNumber", r.get("entryNumber"),
                    "date", r.get("postingDate"), "description", r.get("description"), "sourceType", r.get("sourceType"),
                    "sourceId", r.get("sourceId"), "category", category,
                    "categoryLabel", MoneyActivityTaxonomy.CATEGORY_LABEL.getOrDefault(category, "Other"),
                    "accountId", r.get("accountId"), "accountCode", r.get("accountCode"), "accountName", r.get("accountName"),
                    "accountType", r.get("accountType"), "branchName", r.get("branchName"), "costCenterName", r.get("costCenterName"),
                    "direction", inflow > 0 ? "in" : "out", "inflow", toFixed2(inflow), "outflow", toFixed2(outflow),
                    "amount", toFixed2(inflow > 0 ? inflow : outflow), "signedAmount", toFixed2(inflow - outflow),
                    "counterparties", counterparties.getOrDefault(r.get("id"), List.of())));
        }
        List<Map<String, Object>> accountRows = new ArrayList<>();
        for (Map<String, Object> a : byAccount) {
            AccountMeta m = meta.get((String) a.get("accountId"));
            double in = num(a, "inflow");
            double out = num(a, "outflow");
            accountRows.add(body("accountId", a.get("accountId"), "code", m == null ? "" : m.code(), "name", m == null ? "" : m.name(),
                    "accountType", m == null ? null : m.categoryKey(), "cashIn", toFixed2(in), "cashOut", toFixed2(out),
                    "net", toFixed2(in - out), "movementCount", ((Number) a.get("count")).longValue()));
        }
        List<Map.Entry<String, double[]>> cats = new ArrayList<>(byCategory.entrySet());
        cats.sort(Comparator.comparingDouble((Map.Entry<String, double[]> e) -> -(e.getValue()[0] + e.getValue()[1])));
        List<Map<String, Object>> categoryRows = cats.stream().map(e -> body("category", e.getKey(),
                "label", MoneyActivityTaxonomy.CATEGORY_LABEL.getOrDefault(e.getKey(), "Other"), "cashIn", toFixed2(e.getValue()[0]),
                "cashOut", toFixed2(e.getValue()[1]), "net", toFixed2(e.getValue()[0] - e.getValue()[1]),
                "movementCount", (long) e.getValue()[2])).toList();
        List<Map<String, Object>> seriesRows = new ArrayList<>();
        double running = openingBalance;
        for (Map<String, Object> s : series) {
            double in = num(s, "inflow");
            double out = num(s, "outflow");
            running += in - out;
            seriesRows.add(body("period", s.get("period"), "cashIn", toFixed2(in), "cashOut", toFixed2(out),
                    "net", toFixed2(in - out), "runningBalance", toFixed2(running), "movementCount", ((Number) s.get("count")).longValue()));
        }
        return body("filters", filterEcho,
                "summary", body("openingBalance", toFixed2(openingBalance), "cashIn", toFixed2(cashIn), "cashOut", toFixed2(cashOut),
                        "netChange", toFixed2(cashIn - cashOut), "closingBalance", toFixed2(openingBalance + cashIn - cashOut),
                        "movementCount", count, "inflowCount", ((Number) totals.get("inflow_count")).longValue(),
                        "outflowCount", ((Number) totals.get("outflow_count")).longValue(),
                        "largestInflow", toFixed2(num(totals, "max_in")), "largestOutflow", toFixed2(num(totals, "max_out")),
                        "externalIn", toFixed2(num(entryTotals, "external_in")),
                        "externalOut", toFixed2(num(entryTotals, "external_out")),
                        "internalMoved", toFixed2(num(entryTotals, "internal_moved"))),
                "data", data, "byAccount", accountRows, "byCategory", categoryRows, "series", seriesRows,
                "accountOptions", accountOptions, "categoryOptions", MoneyActivityTaxonomy.CATEGORY_OPTIONS,
                "page", page, "pageSize", pageSize, "total", count, "totalPages", Math.max(1, (long) Math.ceil(count / (double) pageSize)));
    }

    private Where movementFilters(Filters f, Instant from, Instant to, String scope, Map<String, Object> scopeParams) {
        StringBuilder sql = new StringBuilder(scope);
        Map<String, Object> params = new LinkedHashMap<>(scopeParams);
        if (from != null) {
            sql.append(" AND je.\"postingDate\" >= :from");
            params.put("from", FiscalPeriodControl.ts(from));
        }
        if (to != null) {
            sql.append(" AND je.\"postingDate\" <= :to");
            params.put("to", FiscalPeriodControl.ts(to));
        }
        if ("in".equals(f.direction())) {
            sql.append(" AND jl.\"baseDebit\" > 0");
        }
        if ("out".equals(f.direction())) {
            sql.append(" AND jl.\"baseCredit\" > 0");
        }
        if (f.categories() != null && !f.categories().isEmpty()) {
            sql.append(" AND ").append(MoneyActivityTaxonomy.categorySql(f.categories()));
        }
        String q = f.search() == null ? null : f.search().trim();
        if (q != null && !q.isEmpty()) {
            sql.append(" AND (je.\"entryNumber\" ILIKE :like OR je.description ILIKE :like OR jl.description ILIKE :like"
                    + " OR a.name ILIKE :like OR a.code ILIKE :like)");
            params.put("like", "%" + q + "%");
        }
        if (f.minAmount() != null && !f.minAmount().isNaN() && !f.minAmount().isInfinite() && f.minAmount() > 0) {
            sql.append(" AND GREATEST(jl.\"baseDebit\", jl.\"baseCredit\") >= :min");
            params.put("min", new BigDecimal(f.minAmount().toString()));
        }
        return new Where(" " + sql, params);
    }

    private Map<Object, List<Map<String, Object>>> counterparties(String orgId, List<Map<String, Object>> rows) {
        Map<Object, List<Map<String, Object>>> result = new HashMap<>();
        if (rows.isEmpty()) {
            return result;
        }
        Set<Object> entryIds = new LinkedHashSet<>();
        rows.forEach(r -> entryIds.add(r.get("journalEntryId")));
        Map<Object, List<Map<String, Object>>> byEntry = new HashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT jl.\"journalEntryId\", jl.id AS \"lineId\", a.code, a.name,"
                + " jl.\"baseDebit\", jl.\"baseCredit\" FROM \"JournalLine\" jl JOIN \"Account\" a ON a.id = jl.\"accountId\""
                + " WHERE jl.\"organizationId\" = :org AND jl.\"journalEntryId\" IN (:ids)").param("org", orgId).param("ids", entryIds))) {
            byEntry.computeIfAbsent(l.get("journalEntryId"), k -> new ArrayList<>()).add(l);
        }
        for (Map<String, Object> r : rows) {
            boolean inflow = num(r, "baseDebit") > 0;
            Set<Object> seen = new LinkedHashSet<>();
            List<Map<String, Object>> contra = new ArrayList<>();
            for (Map<String, Object> s : byEntry.getOrDefault(r.get("journalEntryId"), List.of())) {
                if (s.get("lineId").equals(r.get("id"))) {
                    continue;
                }
                if (inflow ? num(s, "baseCredit") > 0 : num(s, "baseDebit") > 0) {
                    if (seen.add(s.get("code")) && contra.size() < 4) {
                        contra.add(body("code", s.get("code"), "name", s.get("name")));
                    }
                }
            }
            result.put(r.get("id"), contra);
        }
        return result;
    }
}
