package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Shared ledger aggregations over balance-affecting entries ({@code posted} + {@code reversed}, Nest
 * {@code BALANCE_AFFECTING_STATUSES}) for the financial reports.
 */
@Component
public class LedgerQueries {

    private final JdbcClient jdbc;

    public LedgerQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** JS {@code new Date(value)}: ISO date or date-time; invalid input is an unhandled error (Prisma 500). */
    public static Instant date(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        Instant d = OrgDates.parse(value.trim());
        if (d == null) {
            throw new IllegalArgumentException("Invalid Date: " + value);
        }
        return d;
    }

    /**
     * Source types of the period / fiscal-year closing entries (and their reversals on reopen). Excluded from profit
     * and loss and from the pre-closing trial balance (ACC-1, B1); kept in the balance sheet and post-closing views.
     */
    public static final String CLOSING_FILTER =
            " AND coalesce(e.\"sourceType\", '') NOT IN ('period_close', 'fiscal_year_close')";

    /** accountId → [Σ baseDebit, Σ baseCredit] with optional {@code postingDate >= from} / {@code <= to} / {@code < before}. */
    public Map<String, BigDecimal[]> balances(String organizationId, Instant from, Instant to, Instant before) {
        return balances(organizationId, from, to, before, false);
    }

    /** As {@link #balances(String, Instant, Instant, Instant)}; {@code excludeClosing} drops the closing entries. */
    public Map<String, BigDecimal[]> balances(String organizationId, Instant from, Instant to, Instant before,
            boolean excludeClosing) {
        StringBuilder sql = new StringBuilder("SELECT l.\"accountId\", sum(l.\"baseDebit\") AS d, sum(l.\"baseCredit\") AS c"
                + " FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = :org"
                + " AND e.status::text IN ('posted', 'reversed')" + (excludeClosing ? CLOSING_FILTER : ""));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("org", organizationId);
        if (from != null) {
            sql.append(" AND e.\"postingDate\" >= :from");
            params.put("from", FiscalPeriodControl.ts(from));
        }
        if (to != null) {
            sql.append(" AND e.\"postingDate\" <= :to");
            params.put("to", FiscalPeriodControl.ts(to));
        }
        if (before != null) {
            sql.append(" AND e.\"postingDate\" < :before");
            params.put("before", FiscalPeriodControl.ts(before));
        }
        sql.append(" GROUP BY l.\"accountId\" ORDER BY min(l.\"createdAt\")");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        for (Map.Entry<String, Object> p : params.entrySet()) {
            spec = spec.param(p.getKey(), p.getValue());
        }
        Map<String, BigDecimal[]> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(spec)) {
            out.put((String) r.get("accountId"), new BigDecimal[] {SqlRows.dec(r, "d"), SqlRows.dec(r, "c")});
        }
        return out;
    }

    /** Count of balance-affecting lines with {@code after < postingDate <= upTo} (snapshot staleness). */
    public long linesBetween(String organizationId, Instant after, Instant upTo) {
        return jdbc.sql("SELECT count(*) FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                        + " WHERE l.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" > ?"
                        + " AND e.\"postingDate\" <= ?")
                .params(organizationId, FiscalPeriodControl.ts(after), FiscalPeriodControl.ts(upTo)).query(Long.class).single();
    }
}
