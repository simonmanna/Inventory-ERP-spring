package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * Dimension-filtered ledger reports (roadmap 2.4, ADR-S025). Posted entries only (posted and reversed, so a reversal
 * nets its original), base currency. A filter {@code project:P1,region:EAST} keeps lines that carry every listed
 * dimension value, a value's child values included; {@code costCenter:<id>} filters the cost-centre column.
 * Amounts: {@code debit}, {@code credit}, {@code balance} = debit − credit; P&amp;L figures are signed the way the
 * P&amp;L reads (income positive, expense positive, {@code net} = income − expense). Lines without the grouped
 * dimension are reported as {@code unassigned}, so a summary always ties to the unfiltered P&amp;L.
 */
@Service
public class AnalyticReportService {

    private static final Set<String> INCOME = Set.of("revenue", "other_income", "contra_revenue");
    private static final Set<String> PNL = Set.of("revenue", "contra_revenue", "cogs", "operating_expense",
            "other_income", "other_expense");
    private static final int MAX_LINES = 1000;

    private final JdbcClient jdbc;

    public AnalyticReportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** SQL fragment + parameters for a parsed filter. */
    private record Where(String sql, List<Object> params) {}

    /** Parse {@code code:value,code:value} into dimension → values (each value expanded to its descendants). */
    Map<String, List<String>> parseFilter(String filter) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (filter == null || filter.isBlank()) {
            return out;
        }
        for (String part : filter.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int i = p.indexOf(':');
            if (i <= 0 || i == p.length() - 1) {
                throw ApiException.badRequest("filter must look like dimension:value,dimension:value");
            }
            String key = p.substring(0, i).trim();
            String value = p.substring(i + 1).trim();
            List<String> values = out.computeIfAbsent(key, k -> new ArrayList<>());
            if (AnalyticDimensionService.COST_CENTER.equals(key)) {
                values.addAll(costCenterSubtree(value));
            } else {
                values.addAll(valueSubtree(key, value));
            }
        }
        return out;
    }

    private List<String> valueSubtree(String dimension, String code) {
        List<String> codes = jdbc.sql("WITH RECURSIVE t AS (SELECT v.id, v.code FROM \"AnalyticDimensionValue\" v JOIN"
                        + " \"AnalyticDimension\" d ON d.id = v.\"dimensionId\" WHERE d.\"organizationId\" = ? AND"
                        + " d.code = ? AND v.code = ? UNION ALL SELECT c.id, c.code FROM \"AnalyticDimensionValue\" c"
                        + " JOIN t ON c.\"parentId\" = t.id) SELECT code FROM t")
                .params(org(), dimension, code).query(String.class).list();
        return codes.isEmpty() ? List.of(code) : codes;
    }

    private List<String> costCenterSubtree(String id) {
        List<String> ids = jdbc.sql("WITH RECURSIVE t AS (SELECT id FROM \"CostCenter\" WHERE"
                        + " \"organizationId\" = ? AND id = ? UNION ALL SELECT c.id FROM \"CostCenter\" c"
                        + " JOIN t ON c.\"parentId\" = t.id)"
                        + " SELECT id FROM t").params(org(), id).query(String.class).list();
        return ids.isEmpty() ? List.of(id) : ids;
    }

    private Where where(LocalDate from, LocalDate to, Map<String, List<String>> filter, String accountId) {
        StringBuilder sql = new StringBuilder(" FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                + " l.\"journalEntryId\" JOIN \"Account\" a ON a.id = l.\"accountId\" LEFT JOIN \"AccountCategory\" c"
                + " ON c.id = a.\"categoryId\" WHERE l.\"organizationId\" = ? AND e.status IN ('posted', 'reversed')");
        List<Object> params = new ArrayList<>();
        params.add(org());
        if (from != null) {
            sql.append(" AND e.\"postingDate\" >= ?");
            params.add(from);
        }
        if (to != null) {
            sql.append(" AND e.\"postingDate\" < ?");
            params.add(to.plusDays(1));
        }
        if (accountId != null) {
            sql.append(" AND l.\"accountId\" = ?");
            params.add(accountId);
        }
        for (Map.Entry<String, List<String>> f : filter.entrySet()) {
            if (AnalyticDimensionService.COST_CENTER.equals(f.getKey())) {
                sql.append(" AND l.\"costCenterId\" = ANY(?::text[])");
            } else {
                sql.append(" AND l.dimensions ->> ? = ANY(?::text[])");
                params.add(f.getKey());
            }
            params.add(f.getValue().toArray(String[]::new));
        }
        return new Where(sql.toString(), params);
    }

    private static String plain(BigDecimal v) {
        return PostingService.plain(v == null ? BigDecimal.ZERO : v);
    }

    /**
     * P&amp;L by the values of {@code dimension} (or {@code costCenter}): per value income, expense, net, the
     * per-account detail and a roll-up over child values; filtered by {@code filter}.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(String dimension, LocalDate from, LocalDate to, String filter) {
        if (dimension == null || dimension.isBlank()) {
            throw ApiException.badRequest("dimension is required");
        }
        boolean cc = AnalyticDimensionService.COST_CENTER.equals(dimension);
        Map<String, Object> meta = cc ? null : SqlRows.one(jdbc.sql("SELECT id, code, name FROM \"AnalyticDimension\""
                + " WHERE \"organizationId\" = ? AND code = ?").params(org(), dimension));
        if (!cc && meta == null) {
            throw ApiException.badRequest("Unknown analytic dimension " + dimension);
        }
        Where w = where(from, to, parseFilter(filter), null);
        String key = cc ? "l.\"costCenterId\"" : "l.dimensions ->> ?";
        List<Object> params = new ArrayList<>();
        if (!cc) {
            params.add(dimension);
        }
        params.addAll(w.params());
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT " + key + " AS value, l.\"accountId\", a.code,"
                + " a.name, c.\"reportSection\"::text AS section, SUM(l.\"baseDebit\") AS debit,"
                + " SUM(l.\"baseCredit\") AS credit" + w.sql() + " GROUP BY 1, 2, 3, 4, 5 ORDER BY 3")
                .params(params.toArray()));

        // Value master: code → (label, parent code)
        Map<String, String[]> master = new LinkedHashMap<>();
        if (cc) {
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT c.id, c.code, c.name, c.\"parentId\" FROM"
                    + " \"CostCenter\" c WHERE c.\"organizationId\" = ? AND c.\"deletedAt\" IS NULL ORDER BY c.code")
                    .param(org()))) {
                master.put((String) r.get("id"), new String[] {r.get("code") + " " + r.get("name"),
                        (String) r.get("parentId")});
            }
        } else {
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT v.code, v.name, p.code AS parent FROM"
                    + " \"AnalyticDimensionValue\" v LEFT JOIN \"AnalyticDimensionValue\" p ON p.id = v.\"parentId\""
                    + " WHERE v.\"dimensionId\" = ? ORDER BY v.code").param(meta.get("id")))) {
                master.put((String) r.get("code"), new String[] {(String) r.get("name"), (String) r.get("parent")});
            }
        }

        Map<String, BigDecimal[]> own = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> accountsBy = new HashMap<>();
        BigDecimal[] total = {BigDecimal.ZERO, BigDecimal.ZERO};
        for (Map<String, Object> r : rows) {
            String section = String.valueOf(r.get("section"));
            if (!PNL.contains(section)) {
                continue;
            }
            BigDecimal net = SqlRows.dec(r, "debit").subtract(SqlRows.dec(r, "credit"));
            boolean income = INCOME.contains(section);
            BigDecimal amount = income ? net.negate() : net;
            String v = r.get("value") == null ? "" : (String) r.get("value");
            BigDecimal[] t = own.computeIfAbsent(v, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            t[income ? 0 : 1] = t[income ? 0 : 1].add(amount);
            total[income ? 0 : 1] = total[income ? 0 : 1].add(amount);
            Map<String, Object> acc = new LinkedHashMap<>();
            acc.put("accountId", r.get("accountId"));
            acc.put("code", r.get("code"));
            acc.put("name", r.get("name"));
            acc.put("section", section);
            acc.put("amount", plain(amount));
            accountsBy.computeIfAbsent(v, k -> new ArrayList<>()).add(acc);
        }

        Set<String> keys = new LinkedHashSet<>(master.keySet());
        keys.addAll(own.keySet());
        keys.remove("");
        Map<String, BigDecimal[]> rollup = new HashMap<>();
        for (String k : keys) {
            BigDecimal[] t = own.get(k);
            if (t == null) {
                continue;
            }
            String cursor = k;
            Set<String> seen = new LinkedHashSet<>();
            while (cursor != null && seen.add(cursor)) {
                BigDecimal[] r = rollup.computeIfAbsent(cursor, x -> new BigDecimal[] {BigDecimal.ZERO,
                        BigDecimal.ZERO});
                r[0] = r[0].add(t[0]);
                r[1] = r[1].add(t[1]);
                String[] m = master.get(cursor);
                cursor = m == null ? null : m[1];
            }
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (String k : keys) {
            BigDecimal[] t = own.getOrDefault(k, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal[] r = rollup.getOrDefault(k, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            if (t[0].signum() == 0 && t[1].signum() == 0 && r[0].signum() == 0 && r[1].signum() == 0) {
                continue;
            }
            String[] m = master.get(k);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("value", k);
            row.put("name", m == null ? k : m[0]);
            row.put("parent", m == null ? null : m[1]);
            row.put("income", plain(t[0]));
            row.put("expense", plain(t[1]));
            row.put("net", plain(t[0].subtract(t[1])));
            row.put("rollupIncome", plain(r[0]));
            row.put("rollupExpense", plain(r[1]));
            row.put("rollupNet", plain(r[0].subtract(r[1])));
            row.put("accounts", accountsBy.getOrDefault(k, List.of()));
            values.add(row);
        }
        BigDecimal[] u = own.getOrDefault("", new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        Map<String, Object> unassigned = new LinkedHashMap<>();
        unassigned.put("income", plain(u[0]));
        unassigned.put("expense", plain(u[1]));
        unassigned.put("net", plain(u[0].subtract(u[1])));
        unassigned.put("accounts", accountsBy.getOrDefault("", List.of()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dimension", dimension);
        out.put("dimensionName", cc ? "Cost center" : meta.get("name"));
        out.put("from", from == null ? null : from.toString());
        out.put("to", to == null ? null : to.toString());
        out.put("filter", filter);
        out.put("values", values);
        out.put("unassigned", unassigned);
        out.put("totals", Map.of("income", plain(total[0]), "expense", plain(total[1]), "net",
                plain(total[0].subtract(total[1]))));
        return out;
    }

    /** Trial balance of the lines matching {@code filter}: per account debit, credit and balance. */
    @Transactional(readOnly = true)
    public Map<String, Object> trialBalance(LocalDate from, LocalDate to, String filter) {
        Where w = where(from, to, parseFilter(filter), null);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT l.\"accountId\", a.code, a.name,"
                + " c.\"reportSection\"::text AS section, SUM(l.\"baseDebit\") AS debit,"
                + " SUM(l.\"baseCredit\") AS credit"
                + w.sql() + " GROUP BY 1, 2, 3, 4 ORDER BY 2").params(w.params().toArray()));
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal d = SqlRows.dec(r, "debit");
            BigDecimal c = SqlRows.dec(r, "credit");
            debit = debit.add(d);
            credit = credit.add(c);
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("accountId", r.get("accountId"));
            a.put("code", r.get("code"));
            a.put("name", r.get("name"));
            a.put("section", r.get("section"));
            a.put("debit", plain(d));
            a.put("credit", plain(c));
            a.put("balance", plain(d.subtract(c)));
            accounts.add(a);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from == null ? null : from.toString());
        out.put("to", to == null ? null : to.toString());
        out.put("filter", filter);
        out.put("accounts", accounts);
        out.put("totals", Map.of("debit", plain(debit), "credit", plain(credit), "balance",
                plain(debit.subtract(credit))));
        return out;
    }

    /** Drill-down: the journal lines behind a figure, newest first (at most 1,000). */
    @Transactional(readOnly = true)
    public Map<String, Object> lines(LocalDate from, LocalDate to, String filter, String accountId, Integer limit,
            Integer offset) {
        int take = limit == null || limit <= 0 ? 100 : Math.min(limit, MAX_LINES);
        int skip = offset == null || offset < 0 ? 0 : offset;
        Where w = where(from, to, parseFilter(filter), accountId);
        long total = jdbc.sql("SELECT count(*)" + w.sql()).params(w.params().toArray()).query(Long.class).single();
        List<Object> params = new ArrayList<>(w.params());
        params.add(take);
        params.add(skip);
        List<Map<String, Object>> data = SqlRows.list(jdbc.sql("SELECT l.id, e.id AS \"journalEntryId\","
                + " e.\"entryNumber\", e.\"postingDate\", e.\"sourceType\", e.\"sourceId\", l.\"accountId\","
                + " a.code AS \"accountCode\", a.name AS \"accountName\", l.description, l.\"baseDebit\" AS debit,"
                + " l.\"baseCredit\" AS credit, l.\"costCenterId\", l.dimensions::text AS dimensions" + w.sql()
                + " ORDER BY e.\"postingDate\" DESC, e.\"entryNumber\" DESC, l.\"lineNumber\" LIMIT ? OFFSET ?")
                .params(params.toArray()));
        for (Map<String, Object> r : data) {
            Object d = r.get("dimensions");
            r.put("dimensions", d == null ? null : com.inventoryerp.shared.web.EntityJson.parseJson(String.valueOf(d)));
            r.put("debit", plain((BigDecimal) r.get("debit")));
            r.put("credit", plain((BigDecimal) r.get("credit")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", data);
        out.put("total", total);
        out.put("limit", take);
        out.put("offset", skip);
        return out;
    }
}
