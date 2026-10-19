package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.application.PostingService.plain;

import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money Activity (port of {@code money-activity.service.ts}): one row per journal entry touching a money
 * account, classified by the shared taxonomy with every money-account leg attached; classification, direction
 * and filters all run in SQL so counts and totals cover every matching entry.
 */
@Service
public class MoneyActivityService {

    private static final Set<String> SESSION_SOURCE_TYPES = Set.of("cash_session_opening", "cash_session_banking",
            "cash_session_variance");

    /** Query filters. */
    public record Filters(String from, String to, List<String> categories, String accountId, String branchId,
            String direction, String search, double page, double pageSize) {}

    private final JdbcClient jdbc;
    private final AccountResolverService accounts;

    public MoneyActivityService(JdbcClient jdbc, AccountResolverService accounts) {
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

    private static int clampPage(double v, int fallback, int max) {
        int n = Double.isNaN(v) || v == 0 ? fallback : (int) v;
        return Math.min(max, Math.max(1, n));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(Filters filters) {
        String orgId = TenantContext.organizationId();
        int page = Math.max(1, Double.isNaN(filters.page()) || filters.page() == 0 ? 1 : (int) filters.page());
        int pageSize = clampPage(filters.pageSize(), 25, 200);
        Map<String, Object> org = SqlRows.one(jdbc.sql("SELECT \"currencyCode\", timezone FROM \"Organization\" WHERE id = ?").param(orgId));
        Object currencyCode = org == null ? null : org.get("currencyCode");
        ZoneId zone = OrgDates.orgTimezone(jdbc, orgId);
        String timezone = org == null || org.get("timezone") == null || ((String) org.get("timezone")).isEmpty() ? "UTC"
                : (String) org.get("timezone");
        Instant from = OrgDates.orgDateBound(filters.from(), "from", zone, true);
        Instant to = OrgDates.orgDateBound(filters.to(), "to", zone, false);
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("`from` must be on or before `to`");
        }
        List<AccountMeta> money = accounts.cashEquivalentAccounts(false);
        Map<String, AccountMeta> meta = new HashMap<>();
        money.forEach(a -> meta.put(a.id(), a));
        List<String> moneyIds = money.stream().map(AccountMeta::id).toList();
        if (filters.accountId() != null && !moneyIds.contains(filters.accountId())) {
            throw ApiException.badRequest("Account is not an active cash, bank or mobile-money account");
        }
        List<String> branchAccountIds = filters.branchId() == null || moneyIds.isEmpty() ? null
                : jdbc.sql("SELECT \"defaultAccountId\" FROM \"CashRegister\" WHERE \"organizationId\" = :org AND \"branchId\" = :b"
                                + " AND \"deletedAt\" IS NULL AND \"defaultAccountId\" IN (:ids)")
                        .param("org", orgId).param("b", filters.branchId()).param("ids", moneyIds).query(String.class).list();
        Map<String, Object> zero = body("externalIn", "0.00", "externalOut", "0.00", "internalMoved", "0.00");
        String today = OrgDates.orgToday(zone, Instant.now());
        if (moneyIds.isEmpty()) {
            return body("data", List.of(), "total", 0, "page", page, "pageSize", pageSize, "totalPages", 1,
                    "currencyCode", currencyCode, "timezone", timezone, "today", today, "totals", zero, "pageTotals", zero,
                    "categoryOptions", MoneyActivityTaxonomy.CATEGORY_OPTIONS);
        }
        List<String> where = new ArrayList<>(List.of("je.\"organizationId\" = :org",
                "je.status::text IN ('posted', 'reversed')", "jl.\"accountId\" IN (:money)"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("org", orgId);
        params.put("money", moneyIds);
        if (filters.accountId() != null) {
            where.add("EXISTS (SELECT 1 FROM \"JournalLine\" al WHERE al.\"journalEntryId\" = je.id AND al.\"accountId\" = :acct)");
            params.put("acct", filters.accountId());
        }
        if (filters.branchId() != null) {
            String touches = branchAccountIds != null && !branchAccountIds.isEmpty()
                    ? " OR EXISTS (SELECT 1 FROM \"JournalLine\" bl WHERE bl.\"journalEntryId\" = je.id AND bl.\"accountId\" IN (:bacc))" : "";
            where.add("(je.\"branchId\" = :branch" + touches + ")");
            params.put("branch", filters.branchId());
            if (!touches.isEmpty()) {
                params.put("bacc", branchAccountIds);
            }
        }
        if (from != null) {
            where.add("je.\"postingDate\" >= :from");
            params.put("from", FiscalPeriodControl.ts(from));
        }
        if (to != null) {
            where.add("je.\"postingDate\" <= :to");
            params.put("to", FiscalPeriodControl.ts(to));
        }
        if (filters.categories() != null && !filters.categories().isEmpty()) {
            where.add(MoneyActivityTaxonomy.categorySql(filters.categories()));
        }
        String q = filters.search() == null ? null : filters.search().trim();
        if (q != null && !q.isEmpty()) {
            where.add("(je.\"entryNumber\" ILIKE :like OR je.description ILIKE :like OR EXISTS ("
                    + " SELECT 1 FROM \"JournalLine\" sl JOIN \"Account\" sa ON sa.id = sl.\"accountId\""
                    + " WHERE sl.\"journalEntryId\" = je.id AND (sa.name ILIKE :like OR sa.code ILIKE :like)))");
            params.put("like", "%" + q + "%");
        }
        String directionFilter = "";
        if (filters.direction() != null && !"all".equals(filters.direction())) {
            directionFilter = " WHERE " + MoneyActivityTaxonomy.DIRECTION_SQL + " = :dir";
            params.put("dir", filters.direction());
        }
        String base = " FROM (SELECT je.id, je.\"postingDate\", je.\"createdAt\","
                + " (je.status::text = 'reversed' OR je.\"reversalOfId\" IS NOT NULL) AS voided, "
                + MoneyActivityTaxonomy.EFFECTIVE_SOURCE_TYPE + " AS eff, SUM(jl.\"baseDebit\") AS d, SUM(jl.\"baseCredit\") AS c"
                + " FROM \"JournalEntry\" je JOIN \"JournalLine\" jl ON jl.\"journalEntryId\" = je.id"
                + " WHERE " + String.join(" AND ", where) + " GROUP BY je.id, je.status, je.\"reversalOfId\") x" + directionFilter;
        var countSpec = jdbc.sql("SELECT COUNT(*) AS n,"
                + " COALESCE(SUM(CASE WHEN NOT x.voided THEN GREATEST(x.d - x.c, 0) END), 0) AS external_in,"
                + " COALESCE(SUM(CASE WHEN NOT x.voided THEN GREATEST(x.c - x.d, 0) END), 0) AS external_out,"
                + " COALESCE(SUM(CASE WHEN NOT x.voided THEN LEAST(x.d, x.c) END), 0) AS internal_moved" + base);
        var idSpec = jdbc.sql("SELECT x.id, x.eff" + base + " ORDER BY x.\"postingDate\" DESC, x.\"createdAt\" DESC, x.id DESC"
                + " LIMIT :lim OFFSET :off");
        for (Map.Entry<String, Object> p : params.entrySet()) {
            countSpec = countSpec.param(p.getKey(), p.getValue());
            idSpec = idSpec.param(p.getKey(), p.getValue());
        }
        idSpec = idSpec.param("lim", pageSize).param("off", (page - 1) * pageSize);
        Map<String, Object> counts = SqlRows.one(countSpec);
        List<Map<String, Object>> ids = SqlRows.list(idSpec);
        long total = counts == null ? 0 : ((Number) counts.get("n")).longValue();
        Set<String> moneySet = Set.copyOf(moneyIds);
        List<Map<String, Object>> data = new ArrayList<>();
        double pin = 0;
        double pout = 0;
        double pint = 0;
        for (Map<String, Object> r : ids) {
            Map<String, Object> entry = SqlRows.one(jdbc.sql("SELECT id, \"entryNumber\", \"postingDate\", description,"
                    + " \"sourceType\", \"sourceId\", status::text AS status FROM \"JournalEntry\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(r.get("id"), orgId));
            if (entry == null) {
                continue;
            }
            List<MoneyActivityTaxonomy.MoneyLine> lines = new ArrayList<>();
            for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT l.\"accountId\", l.\"baseDebit\", l.\"baseCredit\", a.name"
                    + " FROM \"JournalLine\" l LEFT JOIN \"Account\" a ON a.id = l.\"accountId\" WHERE l.\"journalEntryId\" = ?"
                    + " ORDER BY l.\"lineNumber\" ASC").param(r.get("id")))) {
                String accountId = (String) l.get("accountId");
                if (!moneySet.contains(accountId)) {
                    continue;
                }
                AccountMeta m = meta.get(accountId);
                lines.add(new MoneyActivityTaxonomy.MoneyLine(accountId,
                        m != null ? m.name() : l.get("name") != null ? (String) l.get("name") : "—",
                        m == null ? null : m.categoryKey(), plain(SqlRows.dec(l, "baseDebit")), plain(SqlRows.dec(l, "baseCredit"))));
            }
            Map<String, Object> row = body("id", entry.get("id"), "journalEntryId", entry.get("id"),
                    "entryNumber", entry.get("entryNumber"), "occurredAt", entry.get("postingDate"), "currencyCode", currencyCode,
                    "description", entry.get("description"), "sourceType", entry.get("sourceType"), "sourceId", entry.get("sourceId"),
                    "status", "reversed".equals(entry.get("status")) ? "reversed" : "posted", "register", null);
            String eff = r.get("eff") != null ? (String) r.get("eff") : (String) entry.get("sourceType");
            row.putAll(MoneyActivityTaxonomy.classify(eff, lines));
            if (!"reversed".equals(row.get("status"))) {
                pin += Double.parseDouble((String) row.get("externalIn"));
                pout += Double.parseDouble((String) row.get("externalOut"));
                pint += Double.parseDouble((String) row.get("internalMoved"));
            }
            data.add(row);
        }
        attachRegisters(orgId, data);
        return body("data", data, "total", total, "page", page, "pageSize", pageSize,
                "totalPages", Math.max(1, (long) Math.ceil(total / (double) pageSize)), "currencyCode", currencyCode,
                "timezone", timezone, "today", today,
                "totals", body("externalIn", fixed(counts, "external_in"), "externalOut", fixed(counts, "external_out"),
                        "internalMoved", fixed(counts, "internal_moved")),
                "pageTotals", body("externalIn", MoneyActivityTaxonomy.toFixed2(pin),
                        "externalOut", MoneyActivityTaxonomy.toFixed2(pout), "internalMoved", MoneyActivityTaxonomy.toFixed2(pint)),
                "categoryOptions", MoneyActivityTaxonomy.CATEGORY_OPTIONS);
    }

    private static String fixed(Map<String, Object> counts, String key) {
        BigDecimal v = counts == null ? BigDecimal.ZERO : SqlRows.dec(counts, key);
        return MoneyActivityTaxonomy.toFixed2(v.doubleValue());
    }

    private void attachRegisters(String orgId, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<Object> entryIds = rows.stream().map(r -> r.get("journalEntryId")).toList();
        Map<String, String> sessionOfEntry = new HashMap<>();
        for (Map<String, Object> m : SqlRows.list(jdbc.sql("SELECT \"journalEntryId\", \"cashSessionId\" FROM \"CashMovement\""
                + " WHERE \"organizationId\" = :org AND \"journalEntryId\" IN (:ids)").param("org", orgId).param("ids", entryIds))) {
            if (m.get("journalEntryId") != null && m.get("cashSessionId") != null) {
                sessionOfEntry.put((String) m.get("journalEntryId"), (String) m.get("cashSessionId"));
            }
        }
        for (Map<String, Object> m : SqlRows.list(jdbc.sql("SELECT \"journalEntryId\", \"cashSessionId\" FROM \"TenderSettlement\""
                + " WHERE \"organizationId\" = :org AND \"journalEntryId\" IN (:ids) AND \"cashSessionId\" IS NOT NULL")
                .param("org", orgId).param("ids", entryIds))) {
            sessionOfEntry.put((String) m.get("journalEntryId"), (String) m.get("cashSessionId"));
        }
        List<Object> paymentIds = rows.stream().filter(r -> "payment".equals(r.get("sourceType")) && r.get("sourceId") != null)
                .map(r -> r.get("sourceId")).toList();
        if (!paymentIds.isEmpty()) {
            for (Map<String, Object> m : SqlRows.list(jdbc.sql("SELECT \"journalEntryId\", \"cashSessionId\" FROM \"Payment\""
                    + " WHERE \"organizationId\" = :org AND id IN (:ids) AND \"cashSessionId\" IS NOT NULL")
                    .param("org", orgId).param("ids", paymentIds))) {
                if (m.get("journalEntryId") != null) {
                    sessionOfEntry.put((String) m.get("journalEntryId"), (String) m.get("cashSessionId"));
                }
            }
        }
        for (Map<String, Object> r : rows) {
            if (r.get("sourceType") != null && SESSION_SOURCE_TYPES.contains(r.get("sourceType")) && r.get("sourceId") != null) {
                sessionOfEntry.put((String) r.get("journalEntryId"), (String) r.get("sourceId"));
            }
        }
        if (sessionOfEntry.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> s : SqlRows.list(jdbc.sql("SELECT s.id, r.id AS rid, r.name AS rname FROM \"CashSession\" s"
                + " JOIN \"CashRegister\" r ON r.id = s.\"cashRegisterId\" WHERE s.\"organizationId\" = :org AND s.id IN (:ids)")
                .param("org", orgId).param("ids", List.copyOf(new java.util.HashSet<>(sessionOfEntry.values()))))) {
            byId.put((String) s.get("id"), s);
        }
        for (Map<String, Object> r : rows) {
            Map<String, Object> s = byId.get(sessionOfEntry.getOrDefault((String) r.get("journalEntryId"), ""));
            if (s != null) {
                r.put("register", body("id", s.get("rid"), "name", s.get("rname"), "sessionId", s.get("id")));
            }
        }
    }
}
