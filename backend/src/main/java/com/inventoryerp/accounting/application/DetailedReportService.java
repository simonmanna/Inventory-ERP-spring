package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.application.PostingService.plain;

import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Detailed Accounting Report — the "general ledger detail" statement (port of {@code detailed-report.service.ts}):
 * per account the opening balance carried in from before {@code from}, every balance-affecting line in the window
 * with a running balance, and the closing balance. Balances are debit-positive; the client flips credit-normal
 * accounts using {@code normalBalance}.
 */
@Service
public class DetailedReportService {

    /** Hard ceiling on transaction lines returned in one call. */
    private static final int MAX_LINES = 20_000;
    private static final int DEFAULT_MAX_LINES = 5_000;
    private static final BigDecimal EPSILON = new BigDecimal("0.0001");

    private final JdbcClient jdbc;
    private final AccountResolverService accounts;

    public DetailedReportService(JdbcClient jdbc, AccountResolverService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    /** Query parameters ({@code DetailedReportQuery}). */
    public record Query(String from, String to, String accountIds, String classification, String branchId,
            String costCenterId, String journalId, String partnerId, boolean includeZero, boolean summaryOnly,
            Double maxLines) {}

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detailed(Query query) {
        Instant from = LedgerQueries.date(query.from(), AccountingDay.zone(jdbc));
        Instant to = LedgerQueries.date(query.to(), AccountingDay.zone(jdbc));
        double requested = query.maxLines() == null || query.maxLines().isNaN() || query.maxLines() == 0
                ? DEFAULT_MAX_LINES : query.maxLines();
        int maxLines = (int) Math.min(Math.max(requested, 1), MAX_LINES);

        List<String> accountFilter = query.accountIds() == null ? List.of()
                : Arrays.stream(query.accountIds().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();

        Map<String, AccountMeta> allMeta = accounts.allMeta();
        List<String> scoped = null;
        if (!accountFilter.isEmpty()) {
            scoped = accountFilter;
        } else if (query.classification() != null && !query.classification().isEmpty()) {
            scoped = allMeta.values().stream().filter(m -> query.classification().equals(m.classification()))
                    .map(AccountMeta::id).toList();
            // An empty classification match must return nothing, not everything.
            if (scoped.isEmpty()) {
                return empty(from, to, maxLines);
            }
        }

        Map<String, Object> params = new LinkedHashMap<>();
        String dims = dimensionSql(query, scoped, params);

        // Opening balances (everything strictly before `from`).
        Map<String, BigDecimal> opening = new HashMap<>();
        if (from != null) {
            Map<String, Object> p = new LinkedHashMap<>(params);
            p.put("from", FiscalPeriodControl.ts(from));
            for (Map<String, Object> r : SqlRows.list(spec("SELECT l.\"accountId\", sum(l.\"baseDebit\") AS d,"
                    + " sum(l.\"baseCredit\") AS c" + dims + " AND e.\"postingDate\" < :from GROUP BY l.\"accountId\"", p))) {
                opening.put((String) r.get("accountId"), SqlRows.dec(r, "d").subtract(SqlRows.dec(r, "c")));
            }
        }

        // Period movement.
        Map<String, Object> periodParams = new LinkedHashMap<>(params);
        StringBuilder period = new StringBuilder(dims);
        if (from != null) {
            period.append(" AND e.\"postingDate\" >= :from");
            periodParams.put("from", FiscalPeriodControl.ts(from));
        }
        if (to != null) {
            period.append(" AND e.\"postingDate\" <= :to");
            periodParams.put("to", FiscalPeriodControl.ts(to));
        }
        Map<String, BigDecimal[]> movement = new HashMap<>();
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(spec("SELECT l.\"accountId\", sum(l.\"baseDebit\") AS d,"
                + " sum(l.\"baseCredit\") AS c, count(*) AS n" + period + " GROUP BY l.\"accountId\"", periodParams))) {
            String id = (String) r.get("accountId");
            movement.put(id, new BigDecimal[] {SqlRows.dec(r, "d"), SqlRows.dec(r, "c")});
            counts.put(id, ((Number) r.get("n")).longValue());
        }
        long totalLines = counts.values().stream().mapToLong(Long::longValue).sum();
        boolean truncated = !query.summaryOnly() && totalLines > maxLines;

        // Transaction detail.
        Map<String, List<Map<String, Object>>> linesByAccount = new LinkedHashMap<>();
        if (!query.summaryOnly()) {
            Map<String, Object> p = new LinkedHashMap<>(periodParams);
            p.put("lim", maxLines);
            for (Map<String, Object> l : SqlRows.list(spec("SELECT l.id, l.\"accountId\", l.\"partnerId\", l.\"branchId\","
                    + " l.\"costCenterId\", l.description, l.\"baseDebit\", l.\"baseCredit\", e.id AS entry_id,"
                    + " e.\"postingDate\", e.\"entryNumber\", e.status::text AS entry_status, e.\"sourceType\", e.\"sourceId\","
                    + " e.description AS entry_description, j.code AS journal_code, j.name AS journal_name" + period
                    + " ORDER BY e.\"postingDate\" ASC, l.\"journalEntryId\" ASC, l.\"lineNumber\" ASC LIMIT :lim", p))) {
                linesByAccount.computeIfAbsent((String) l.get("accountId"), k -> new ArrayList<>()).add(l);
            }
        }
        List<Map<String, Object>> allLines = linesByAccount.values().stream().flatMap(List::stream).toList();
        Map<String, String> partners = names("Partner", allLines, "partnerId");
        Map<String, String> branches = names("Branch", allLines, "branchId");
        Map<String, String> costCenters = names("CostCenter", allLines, "costCenterId");

        Set<String> accountIds = new LinkedHashSet<>(opening.keySet());
        accountIds.addAll(movement.keySet());
        if (scoped != null && query.includeZero()) {
            accountIds.addAll(scoped);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalOpening = BigDecimal.ZERO;
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (String accountId : accountIds) {
            AccountMeta meta = allMeta.get(accountId);
            if (meta == null || (scoped != null && !scoped.contains(accountId))) {
                continue;
            }
            BigDecimal open = opening.getOrDefault(accountId, BigDecimal.ZERO);
            BigDecimal[] move = movement.getOrDefault(accountId, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            long count = counts.getOrDefault(accountId, 0L);
            BigDecimal net = move[0].subtract(move[1]);
            if (!query.includeZero() && open.signum() == 0 && count == 0) {
                continue;
            }
            BigDecimal running = open;
            List<Map<String, Object>> lines = new ArrayList<>();
            for (Map<String, Object> l : linesByAccount.getOrDefault(accountId, List.of())) {
                running = running.add(SqlRows.dec(l, "baseDebit")).subtract(SqlRows.dec(l, "baseCredit"));
                String partnerId = (String) l.get("partnerId");
                String branchId = (String) l.get("branchId");
                String costCenterId = (String) l.get("costCenterId");
                lines.add(body("id", l.get("id"), "date", l.get("postingDate"), "entryId", l.get("entry_id"),
                        "entryNumber", l.get("entryNumber"), "entryStatus", l.get("entry_status"),
                        "journalCode", l.get("journal_code"), "journalName", l.get("journal_name"),
                        "sourceType", l.get("sourceType"), "sourceId", l.get("sourceId"),
                        "partnerName", partnerId != null ? partners.get(partnerId) : null,
                        "branchName", branchId != null ? branches.get(branchId) : null,
                        "costCenterName", costCenterId != null ? costCenters.get(costCenterId) : null,
                        "description", l.get("description") != null ? l.get("description") : l.get("entry_description"),
                        "debit", plain(SqlRows.dec(l, "baseDebit")), "credit", plain(SqlRows.dec(l, "baseCredit")),
                        "balance", plain(running)));
            }
            totalOpening = totalOpening.add(open);
            totalDebit = totalDebit.add(move[0]);
            totalCredit = totalCredit.add(move[1]);
            rows.add(body("accountId", accountId, "code", meta.code(), "name", meta.name(),
                    "classification", meta.classification(), "categoryKey", meta.categoryKey(),
                    "normalBalance", meta.normalBalance(), "opening", plain(open), "debit", plain(move[0]),
                    "credit", plain(move[1]), "movement", plain(net), "closing", plain(open.add(net)),
                    "lineCount", count, "lines", lines));
        }
        rows.sort(Comparator.<Map<String, Object>>comparingInt(r -> allMeta.get((String) r.get("accountId")).sortOrder())
                .thenComparing(r -> (String) r.get("code")));

        boolean filtered = scoped != null || has(query.branchId()) || has(query.costCenterId()) || has(query.journalId())
                || has(query.partnerId());
        return body("period", body("from", from, "to", to),
                "filters", body("accountIds", accountFilter, "classification", query.classification(),
                        "branchId", query.branchId(), "costCenterId", query.costCenterId(),
                        "journalId", query.journalId(), "partnerId", query.partnerId(),
                        "includeZero", query.includeZero(), "summaryOnly", query.summaryOnly()),
                "accounts", rows,
                "totals", body("opening", plain(totalOpening), "debit", plain(totalDebit), "credit", plain(totalCredit),
                        "movement", plain(totalDebit.subtract(totalCredit)),
                        "closing", plain(totalOpening.add(totalDebit).subtract(totalCredit)), "lineCount", totalLines),
                "balanced", totalDebit.subtract(totalCredit).abs().compareTo(EPSILON) <= 0,
                "filtered", filtered,
                "truncated", truncated,
                "maxLines", maxLines);
    }

    /** JavaScript truthiness of an optional query string. */
    private static boolean has(String s) {
        return s != null && !s.isEmpty();
    }

    /** Shared FROM/WHERE: org-scoped, balance-affecting only, plus whatever dimensions were requested. */
    private static String dimensionSql(Query query, List<String> accountIds, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder(" FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                + " LEFT JOIN \"Journal\" j ON j.id = e.\"journalId\""
                + " WHERE l.\"organizationId\" = :org AND e.status::text IN ('posted', 'reversed')");
        params.put("org", TenantContext.organizationId());
        if (has(query.journalId())) {
            sql.append(" AND e.\"journalId\" = :journal");
            params.put("journal", query.journalId());
        }
        if (accountIds != null) {
            sql.append(" AND l.\"accountId\" IN (:accounts)");
            params.put("accounts", accountIds);
        }
        if (has(query.branchId())) {
            sql.append(" AND l.\"branchId\" = :branch");
            params.put("branch", query.branchId());
        }
        if (has(query.costCenterId())) {
            sql.append(" AND l.\"costCenterId\" = :cc");
            params.put("cc", query.costCenterId());
        }
        if (has(query.partnerId())) {
            sql.append(" AND l.\"partnerId\" = :partner");
            params.put("partner", query.partnerId());
        }
        return sql.toString();
    }

    /** One batched id → name lookup per dimension. */
    private Map<String, String> names(String table, Collection<Map<String, Object>> lines, String column) {
        List<String> ids = lines.stream().map(l -> (String) l.get(column)).filter(Objects::nonNull).distinct().toList();
        Map<String, String> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, name FROM \"" + table + "\""
                + " WHERE \"organizationId\" = :org AND id IN (:ids)").param("org", TenantContext.organizationId()).param("ids", ids))) {
            out.put((String) r.get("id"), (String) r.get("name"));
        }
        return out;
    }

    private JdbcClient.StatementSpec spec(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec s = jdbc.sql(sql);
        for (Map.Entry<String, Object> p : params.entrySet()) {
            s = s.param(p.getKey(), p.getValue());
        }
        return s;
    }

    private static Map<String, Object> empty(Instant from, Instant to, int maxLines) {
        return body("period", body("from", from, "to", to), "filters", Map.of(), "accounts", List.of(),
                "totals", body("opening", "0", "debit", "0", "credit", "0", "movement", "0", "closing", "0", "lineCount", 0),
                "balanced", true, "filtered", true, "truncated", false, "maxLines", maxLines);
    }
}
