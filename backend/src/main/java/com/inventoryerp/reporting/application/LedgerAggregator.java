package com.inventoryerp.reporting.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.reporting.spi.ReportContext;
import com.inventoryerp.reporting.spi.ReportPeriod;
import com.inventoryerp.reporting.spi.ReportRequest;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The one place the engine reads the ledger: base-currency debit / credit per account for a period, with every shared
 * filter (branch, cost center, partner, account, journal, source, analytic dimensions, posting status). Balance
 * affecting entries are {@code posted} and {@code reversed} (a reversal is its own posted entry, so the pair nets to
 * zero); {@code ALL} adds drafts. Branch and cost center are the line's, falling back to the entry's.
 */
@Component
public class LedgerAggregator {

    /** Account with its effective report section (account override, else category) and category key. */
    public record AccountInfo(String id, String code, String name, String category, String section,
            String normalBalance) {}

    /** Empty instead of null, so category and section tests never see null. */
    static String nz(String s) {
        return s == null ? "" : s;
    }

    /** A SQL {@code WHERE} fragment over {@code l} (line) and {@code e} (entry) with positional parameters. */
    record Where(String sql, List<Object> params) {}

    private static final Pattern DIMENSION_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private final JdbcClient jdbc;

    public LedgerAggregator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every account of the organization, ordered by code. */
    public Map<String, AccountInfo> accounts(String org) {
        Map<String, AccountInfo> out = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT a.id, a.code, a.name, c.key AS category,
                               coalesce(a."reportSection"::text, c."reportSection"::text) AS section,
                               a."normalBalance"::text AS nb
                        FROM "Account" a LEFT JOIN "AccountCategory" c ON c.id = a."categoryId"
                        WHERE a."organizationId" = ? ORDER BY a.code""")
                .param(org)
                .query((rs, n) -> out.put(rs.getString("id"), new AccountInfo(rs.getString("id"), rs.getString("code"),
                        rs.getString("name"), nz(rs.getString("category")), nz(rs.getString("section")), nz(rs.getString("nb")))))
                .list();
        return out;
    }

    /** accountId → [Σ baseDebit, Σ baseCredit] for one period (a point-in-time period has no lower bound). */
    public Map<String, BigDecimal[]> balances(ReportContext ctx, ReportPeriod period) {
        Where w = where(ctx.organizationId(), ctx.request(), ctx.zone(), period.from(), period.to());
        Map<String, BigDecimal[]> out = new LinkedHashMap<>();
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT l.\"accountId\", sum(l.\"baseDebit\") AS d,"
                + " sum(l.\"baseCredit\") AS c FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                + w.sql() + " GROUP BY l.\"accountId\"").params(w.params());
        for (Map<String, Object> r : SqlRows.list(spec)) {
            out.put((String) r.get("accountId"), new BigDecimal[] {SqlRows.dec(r, "d"), SqlRows.dec(r, "c")});
        }
        return out;
    }

    /** How balances can be split into columns. */
    public enum Segment {
        BRANCH, COST_CENTER, PARTNER, CURRENCY, MONTH, PROJECT, JOURNAL_TYPE
    }

    /** segment value → accountId → [Σ baseDebit, Σ baseCredit, Σ debit, Σ credit] (txn currency in 2 and 3). */
    public Map<String, Map<String, BigDecimal[]>> balancesBy(ReportContext ctx, ReportPeriod period, Segment seg) {
        Where w = where(ctx.organizationId(), ctx.request(), ctx.zone(), period.from(), period.to());
        String key = switch (seg) {
            case BRANCH -> "coalesce(l.\"branchId\", e.\"branchId\")";
            case COST_CENTER -> "coalesce(l.\"costCenterId\", e.\"costCenterId\")";
            case PARTNER -> "l.\"partnerId\"";
            case CURRENCY -> "coalesce(l.\"currencyId\", e.\"currencyId\")";
            case MONTH -> "to_char((e.\"postingDate\" AT TIME ZONE 'UTC') AT TIME ZONE '" + ctx.zone().getId()
                    .replace("'", "") + "', 'YYYY-MM')";
            case PROJECT -> "l.dimensions ->> 'projectId'";
            case JOURNAL_TYPE -> "(SELECT j.\"journalType\"::text FROM \"Journal\" j WHERE j.id = e.\"journalId\")";
        };
        Map<String, Map<String, BigDecimal[]>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT " + key + " AS seg, l.\"accountId\","
                + " sum(l.\"baseDebit\") AS d, sum(l.\"baseCredit\") AS c, sum(l.debit) AS td, sum(l.credit) AS tc"
                + " FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\"" + w.sql()
                + " GROUP BY 1, 2").params(w.params()))) {
            String k = r.get("seg") == null ? "" : r.get("seg").toString();
            out.computeIfAbsent(k, x -> new LinkedHashMap<>()).put((String) r.get("accountId"), new BigDecimal[] {
                SqlRows.dec(r, "d"), SqlRows.dec(r, "c"), SqlRows.dec(r, "td"), SqlRows.dec(r, "tc")});
        }
        return out;
    }

    /** Journal lines ordered by account code, then date (general ledger, books). */
    public List<Map<String, Object>> linesByAccount(String org, ReportRequest r, ZoneId zone, LocalDate from,
            LocalDate to, int limit) {
        Where w = where(org, r, zone, from, to);
        List<Object> params = new ArrayList<>(w.params());
        params.add(limit);
        return SqlRows.list(jdbc.sql("""
                SELECT l.id, l."journalEntryId", e."entryNumber", e."postingDate", e."createdAt", e.status::text AS status,
                       e."sourceType", e."sourceId", e.description AS "entryDescription", l.description,
                       l."baseDebit" AS debit, l."baseCredit" AS credit, l.debit AS "txnDebit", l.credit AS "txnCredit",
                       a.id AS "accountId", a.code AS "accountCode", a.name AS "accountName", j.name AS journal,
                       p.name AS partner, b.name AS branch, cc.name AS "costCenter", cur.code AS currency,
                       coalesce(nullif(concat_ws(' ', cu."firstName", cu."lastName"), ''), e."createdBy") AS "createdBy",
                       coalesce(nullif(concat_ws(' ', u."firstName", u."lastName"), ''), e."postedBy") AS "postedBy"
                FROM "JournalLine" l
                JOIN "JournalEntry" e ON e.id = l."journalEntryId"
                JOIN "Account" a ON a.id = l."accountId"
                LEFT JOIN "Journal" j ON j.id = e."journalId"
                LEFT JOIN "Partner" p ON p.id = l."partnerId"
                LEFT JOIN "Branch" b ON b.id = coalesce(l."branchId", e."branchId")
                LEFT JOIN "CostCenter" cc ON cc.id = coalesce(l."costCenterId", e."costCenterId")
                LEFT JOIN "Currency" cur ON cur.id = coalesce(l."currencyId", e."currencyId")
                LEFT JOIN "User" u ON u.id = e."postedBy"
                LEFT JOIN "User" cu ON cu.id = e."createdBy"
                """ + w.sql() + " ORDER BY a.code, e.\"postingDate\", e.\"entryNumber\", l.\"lineNumber\", l.id LIMIT ?")
                .params(params));
    }

    /** Journal lines of a period, oldest first, with entry, account, partner, branch and cost center names. */
    public List<Map<String, Object>> lines(String org, ReportRequest r, ZoneId zone, LocalDate from, LocalDate to,
            int limit, int offset) {
        Where w = where(org, r, zone, from, to);
        List<Object> params = new ArrayList<>(w.params());
        params.add(limit);
        params.add(offset);
        return SqlRows.list(jdbc.sql("""
                SELECT l.id, l."journalEntryId", e."entryNumber", e."postingDate", e.status::text AS status,
                       e."sourceType", e."sourceId", e.description AS "entryDescription", l.description,
                       l."baseDebit" AS debit, l."baseCredit" AS credit, a.id AS "accountId", a.code AS "accountCode",
                       a.name AS "accountName", j.name AS journal, p.name AS partner, b.name AS branch,
                       cc.name AS "costCenter", coalesce(nullif(concat_ws(' ', u."firstName", u."lastName"), ''), e."postedBy") AS "postedBy"
                FROM "JournalLine" l
                JOIN "JournalEntry" e ON e.id = l."journalEntryId"
                JOIN "Account" a ON a.id = l."accountId"
                LEFT JOIN "Journal" j ON j.id = e."journalId"
                LEFT JOIN "Partner" p ON p.id = l."partnerId"
                LEFT JOIN "Branch" b ON b.id = coalesce(l."branchId", e."branchId")
                LEFT JOIN "CostCenter" cc ON cc.id = coalesce(l."costCenterId", e."costCenterId")
                LEFT JOIN "User" u ON u.id = e."postedBy"
                """ + w.sql() + " ORDER BY e.\"postingDate\", e.\"entryNumber\", l.\"lineNumber\", l.id LIMIT ? OFFSET ?")
                .params(params));
    }

    /** Number of lines and their debit / credit totals for the same filter. */
    public Map<String, Object> lineTotals(String org, ReportRequest r, ZoneId zone, LocalDate from, LocalDate to) {
        Where w = where(org, r, zone, from, to);
        return SqlRows.one(jdbc.sql("SELECT count(*) AS n, coalesce(sum(l.\"baseDebit\"), 0) AS d,"
                + " coalesce(sum(l.\"baseCredit\"), 0) AS c FROM \"JournalLine\" l JOIN \"JournalEntry\" e"
                + " ON e.id = l.\"journalEntryId\"" + w.sql()).params(w.params()));
    }

    /** Debit / credit of the first {@code skip} lines in listing order (running balance of a later page). */
    public Map<String, Object> skippedTotals(String org, ReportRequest r, ZoneId zone, LocalDate from, LocalDate to,
            int skip) {
        Where w = where(org, r, zone, from, to);
        List<Object> params = new ArrayList<>(w.params());
        params.add(skip);
        return SqlRows.one(jdbc.sql("SELECT coalesce(sum(d), 0) AS d, coalesce(sum(c), 0) AS c FROM (SELECT"
                + " l.\"baseDebit\" AS d, l.\"baseCredit\" AS c FROM \"JournalLine\" l JOIN \"JournalEntry\" e"
                + " ON e.id = l.\"journalEntryId\"" + w.sql() + " ORDER BY e.\"postingDate\", e.\"entryNumber\","
                + " l.\"lineNumber\", l.id LIMIT ?) s").params(params));
    }

    Where where(String org, ReportRequest r, ZoneId zone, LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder(" WHERE l.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>();
        params.add(org);
        String status = r.postingStatus() == null ? "POSTED" : r.postingStatus();
        switch (status) {
            case "POSTED" -> sql.append(" AND e.status::text IN ('posted', 'reversed')");
            case "ALL" -> sql.append(" AND e.status::text IN ('draft', 'posted', 'reversed')");
            default -> throw ApiException.badRequest("postingStatus must be one of: POSTED, ALL");
        }
        if (from != null) {
            sql.append(" AND e.\"postingDate\" >= ?");
            params.add(SqlRows.ts(from.atStartOfDay(zone).toInstant()));
            // ACC-1 (B1): a movement over a period reports the economic activity; the period / year closing transfer
            // to retained earnings is left out. Point-in-time balances (no lower bound) keep it.
            if (r.sourceTypes() == null || r.sourceTypes().isEmpty()) {
                sql.append(" AND coalesce(e.\"sourceType\", '') NOT IN ('period_close', 'fiscal_year_close')");
            }
        }
        if (to != null) {
            sql.append(" AND e.\"postingDate\" < ?");
            params.add(SqlRows.ts(to.plusDays(1).atStartOfDay(zone).toInstant()));
        }
        any(sql, params, "coalesce(l.\"branchId\", e.\"branchId\")", r.branchIds());
        any(sql, params, "coalesce(l.\"costCenterId\", e.\"costCenterId\")", r.costCenterIds());
        any(sql, params, "l.\"partnerId\"", r.partnerIds());
        any(sql, params, "l.\"accountId\"", r.accountIds());
        any(sql, params, "e.\"journalId\"", r.journalIds());
        any(sql, params, "e.\"sourceType\"", r.sourceTypes());
        for (Map.Entry<String, List<String>> d : r.dimensions().entrySet()) {
            if (!DIMENSION_KEY.matcher(d.getKey()).matches()) {
                throw ApiException.badRequest("Invalid dimension key " + d.getKey());
            }
            if (d.getValue() != null && !d.getValue().isEmpty()) {
                sql.append(" AND l.dimensions ->> ? = ANY(?::text[])");
                params.add(d.getKey());
                params.add(d.getValue().toArray(String[]::new));
            }
        }
        return new Where(sql.toString(), params);
    }

    private static void any(StringBuilder sql, List<Object> params, String column, List<String> values) {
        if (!values.isEmpty()) {
            sql.append(" AND ").append(column).append(" = ANY(?::text[])");
            params.add(values.toArray(String[]::new));
        }
    }
}
