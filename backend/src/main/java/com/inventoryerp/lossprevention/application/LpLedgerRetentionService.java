package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.common.Obj;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ledger retention (ADR-S077 §4, LP0 §8 item 3): events whose business date is more than {@code retentionMonths}
 * before today are deleted, per organization with a branch override. {@code retentionMonths} 0 keeps every event.
 *
 * <p>Events of a branch and business date that still has a case not closed are kept until the case closes, so an open
 * investigation never loses its evidence. Derived rows (windows, runs, discrepancies, cases, metric days) are kept:
 * they record what was found. Because the ledger no longer holds the events, an event older than the limit is refused
 * at ingest and a replay before the limit is refused; either would reconcile a day against part of its events.
 *
 * <p>Deletes run in batches of {@value #BATCH}, each in its own transaction, so a large first run never holds one long
 * lock on the ledger.
 */
@Service
public class LpLedgerRetentionService {

    static final int BATCH = 5_000;

    private final JdbcClient jdbc;
    private final LpSettingsService settings;
    private final TransactionTemplate tx;
    private Clock clock = Clock.systemUTC();

    public LpLedgerRetentionService(JdbcClient jdbc, LpSettingsService settings, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.tx = new TransactionTemplate(txm);
    }

    /** For tests: the clock that decides today. */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    /** The first business date the ledger keeps for months of retention ({@code null}: everything is kept). */
    static LocalDate cutoff(LocalDate today, Integer months) {
        return months == null || months <= 0 ? null : today.minusMonths(months);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    /** The first business date kept for a branch of the current organization ({@code null}: everything). */
    public LocalDate cutoff(String branchId) {
        return cutoff(today(), settings.effective(branchId).get(LpSettingsService.RETENTION_MONTHS));
    }

    /** The reason an event with this business date is refused under the cutoff, or {@code null}. */
    public static String refusal(LocalDate cutoff, LocalDate businessDate) {
        return cutoff != null && businessDate.isBefore(cutoff)
                ? "businessDate is older than the ledger keeps (before " + cutoff + ")" : null;
    }

    /** The latest cutoff over the organization and its branch overrides: a replay before it may lack events. */
    public LocalDate latestCutoff() {
        LocalDate latest = cutoff((String) null);
        for (String branch : overrides()) {
            LocalDate c = cutoff(branch);
            if (c != null && (latest == null || c.isAfter(latest))) {
                latest = c;
            }
        }
        return latest;
    }

    private List<String> overrides() {
        return jdbc.sql("SELECT \"branchId\" FROM \"LpSetting\" WHERE \"organizationId\" = ? AND \"branchId\" <> ''"
                + " ORDER BY \"branchId\"").param(LpScope.org()).query(String.class).list();
    }

    /**
     * Applies retention for the current organization. Returns {@code deleted} (events), and per scope the cutoff used:
     * {@code ""} for the organization's default, else the branch id.
     */
    public Map<String, Object> apply() {
        String org = LpScope.org();
        List<String> overrides = overrides();
        Map<String, Object> cutoffs = new LinkedHashMap<>();
        long deleted = 0;
        LocalDate orgCutoff = cutoff((String) null);
        cutoffs.put("", orgCutoff == null ? null : orgCutoff.toString());
        if (orgCutoff != null) {
            List<Object> params = new ArrayList<>(List.of(org, java.sql.Date.valueOf(orgCutoff)));
            StringBuilder w = new StringBuilder(" AND e.\"businessDate\" < ?");
            if (!overrides.isEmpty()) {
                w.append(" AND (e.\"branchId\" IS NULL OR NOT (e.\"branchId\" = ANY(?::text[])))");
                params.add(overrides.toArray(String[]::new));
            }
            deleted += deleteBatches(w.toString(), params);
        }
        for (String branch : overrides) {
            LocalDate c = cutoff(branch);
            cutoffs.put(branch, c == null ? null : c.toString());
            if (c != null) {
                deleted += deleteBatches(" AND e.\"branchId\" = ? AND e.\"businessDate\" < ?",
                        new ArrayList<>(List.of(org, branch, java.sql.Date.valueOf(c))));
            }
        }
        return Obj.of("deleted", deleted, "cutoffs", cutoffs);
    }

    private long deleteBatches(String where, List<Object> params) {
        // Events of a day that still has an open case stay until it closes.
        String sql = "DELETE FROM \"LpEvent\" WHERE id IN (SELECT e.id FROM \"LpEvent\" e WHERE e.\"organizationId\" = ?"
                + where + " AND NOT EXISTS (SELECT 1 FROM \"LpCase\" c WHERE c.\"organizationId\" = e.\"organizationId\""
                + " AND c.state <> 'closed' AND c.\"branchId\" = coalesce(e.\"branchId\", '') AND c.\"businessDate\" ="
                + " e.\"businessDate\") LIMIT " + BATCH + ")";
        long total = 0;
        while (true) {
            Integer n = tx.execute(status -> jdbc.sql(sql).params(params).update());
            int count = n == null ? 0 : n;
            total += count;
            if (count < BATCH) {
                return total;
            }
        }
    }
}
