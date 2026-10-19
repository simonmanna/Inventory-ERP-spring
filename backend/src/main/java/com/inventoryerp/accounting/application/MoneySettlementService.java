package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
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
 * Provider settlements read side (port of {@code money-settlement.service.ts}): settleable provider accounts
 * with balance and POS receipts since the last settlement, and the settlement history.
 */
@Service
public class MoneySettlementService {

    private final JdbcClient jdbc;

    public MoneySettlementService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String fixed2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> sources() {
        String orgId = TenantContext.organizationId();
        List<Map<String, Object>> methods = SqlRows.list(jdbc.sql("SELECT m.id, m.label, m.kind, m.\"isActive\", m.\"accountId\","
                + " a.code AS acode, a.name AS aname, a.\"isActive\" AS aactive, a.\"deletedAt\" AS adeleted, c.key AS akey"
                + " FROM \"PosPaymentMethod\" m LEFT JOIN \"Account\" a ON a.id = m.\"accountId\""
                + " LEFT JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE m.\"organizationId\" = ? AND m.\"deletedAt\" IS NULL"
                + " AND m.kind IN ('mobile_money', 'card') AND m.\"accountId\" IS NOT NULL ORDER BY m.\"sortOrder\" ASC, m.label ASC")
                .param(orgId));
        Map<String, Map<String, Object>> byAccount = new LinkedHashMap<>();
        for (Map<String, Object> m : methods) {
            if (m.get("acode") == null || !Boolean.TRUE.equals(m.get("aactive")) || m.get("adeleted") != null) {
                continue;
            }
            Map<String, Object> cur = byAccount.computeIfAbsent((String) m.get("accountId"), k -> body("account", m,
                    "methods", new ArrayList<Map<String, Object>>()));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> chips = (List<Map<String, Object>>) cur.get("methods");
            chips.add(body("id", m.get("id"), "label", m.get("label"), "kind", m.get("kind"), "isActive", m.get("isActive")));
        }
        Map<String, Object[]> lastOf = new HashMap<>();
        if (!byAccount.isEmpty()) {
            for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT \"sourceAccountId\", max(\"settledAt\") AS last, count(*) AS n"
                    + " FROM \"TenderSettlement\" WHERE \"organizationId\" = :org AND \"sourceAccountId\" IN (:ids)"
                    + " GROUP BY \"sourceAccountId\"").param("org", orgId).param("ids", byAccount.keySet()))) {
                lastOf.put((String) l.get("sourceAccountId"), new Object[] {l.get("last"), ((Number) l.get("n")).longValue()});
            }
        }
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : byAccount.entrySet()) {
            String accountId = e.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> account = (Map<String, Object>) e.getValue().get("account");
            BigDecimal balance = SessionReconciliation.accountLedgerBalance(jdbc, orgId, accountId);
            Object[] last = lastOf.get(accountId);
            Instant lastAt = last == null ? null : (Instant) last[0];
            BigDecimal posReceipts = posReceiptsSince(orgId, accountId, lastAt);
            BigDecimal suggested = BigDecimal.ZERO.max(posReceipts.min(balance));
            sources.add(body("accountId", accountId, "accountCode", account.get("acode"), "accountName", account.get("aname"),
                    "accountType", account.get("akey"), "methods", e.getValue().get("methods"), "balance", fixed2(balance),
                    "posReceiptsSinceLastSettlement", fixed2(posReceipts), "suggestedAmount", fixed2(suggested),
                    "lastSettledAt", lastAt, "settlementCount", last == null ? 0L : last[1]));
        }
        List<Map<String, Object>> destinations = SqlRows.list(jdbc.sql("SELECT a.id, a.code, a.name FROM \"Account\" a"
                + " JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE a.\"organizationId\" = ? AND a.\"isActive\" = true"
                + " AND a.\"deletedAt\" IS NULL AND a.\"isPostable\" = true AND c.key = 'bank' ORDER BY a.code ASC").param(orgId));
        List<Map<String, Object>> feeAccounts = SqlRows.list(jdbc.sql("SELECT a.id, a.code, a.name FROM \"Account\" a"
                + " JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE a.\"organizationId\" = ? AND a.\"isActive\" = true"
                + " AND a.\"deletedAt\" IS NULL AND a.\"isPostable\" = true AND c.classification = 'expense' ORDER BY a.code ASC")
                .param(orgId));
        Object currency = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(orgId)
                .query(String.class).optional().orElse(null);
        return body("currencyCode", currency, "sources", sources, "destinations", destinations, "feeAccounts", feeAccounts);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> history(String sourceAccountId, String fromRaw, String toRaw, Double pageRaw, Double pageSizeRaw) {
        String orgId = TenantContext.organizationId();
        int page = Math.max(1, pageRaw == null || pageRaw.isNaN() || pageRaw == 0 ? 1 : pageRaw.intValue());
        int pageSize = Math.min(100, Math.max(1, pageSizeRaw == null || pageSizeRaw.isNaN() || pageSizeRaw == 0 ? 25
                : pageSizeRaw.intValue()));
        ZoneId zone = OrgDates.orgTimezone(jdbc, orgId);
        Instant from = OrgDates.orgDateBound(fromRaw, "from", zone, true);
        Instant to = OrgDates.orgDateBound(toRaw, "to", zone, false);
        StringBuilder where = new StringBuilder(" WHERE \"organizationId\" = :org");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("org", orgId);
        if (sourceAccountId != null) {
            where.append(" AND \"sourceAccountId\" = :src");
            params.put("src", sourceAccountId);
        }
        if (from != null) {
            where.append(" AND \"settledAt\" >= :from");
            params.put("from", FiscalPeriodControl.ts(from));
        }
        if (to != null) {
            where.append(" AND \"settledAt\" <= :to");
            params.put("to", FiscalPeriodControl.ts(to));
        }
        JdbcClient.StatementSpec count = jdbc.sql("SELECT count(*) FROM \"TenderSettlement\"" + where);
        JdbcClient.StatementSpec list = jdbc.sql("SELECT * FROM \"TenderSettlement\"" + where
                + " ORDER BY \"settledAt\" DESC, id DESC LIMIT :lim OFFSET :off");
        for (Map.Entry<String, Object> p : params.entrySet()) {
            count = count.param(p.getKey(), p.getValue());
            list = list.param(p.getKey(), p.getValue());
        }
        long total = count.query(Long.class).single();
        List<Map<String, Object>> rows = SqlRows.list(list.param("lim", pageSize).param("off", (page - 1) * pageSize));
        Set<Object> accountIds = new LinkedHashSet<>();
        Set<Object> sessionIds = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            for (String k : List.of("sourceAccountId", "destinationAccountId", "feeAccountId")) {
                if (r.get(k) != null) {
                    accountIds.add(r.get(k));
                }
            }
            if (r.get("cashSessionId") != null) {
                sessionIds.add(r.get("cashSessionId"));
            }
        }
        Map<Object, Map<String, Object>> acc = new HashMap<>();
        if (!accountIds.isEmpty()) {
            SqlRows.list(jdbc.sql("SELECT id, code, name FROM \"Account\" WHERE \"organizationId\" = :org AND id IN (:ids)")
                    .param("org", orgId).param("ids", accountIds)).forEach(a -> acc.put(a.get("id"), a));
        }
        Map<Object, Map<String, Object>> ses = new HashMap<>();
        if (!sessionIds.isEmpty()) {
            SqlRows.list(jdbc.sql("SELECT s.id, s.\"openedAt\", r.name AS rname FROM \"CashSession\" s LEFT JOIN \"CashRegister\" r"
                    + " ON r.id = s.\"cashRegisterId\" WHERE s.\"organizationId\" = :org AND s.id IN (:ids)")
                    .param("org", orgId).param("ids", sessionIds)).forEach(s -> ses.put(s.get("id"), s));
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal gross = SqlRows.dec(r, "grossAmount");
            BigDecimal fee = SqlRows.dec(r, "feeAmount");
            Map<String, Object> session = r.get("cashSessionId") == null ? null : ses.get(r.get("cashSessionId"));
            data.add(body("id", r.get("id"), "settledAt", r.get("settledAt"), "reference", r.get("reference"),
                    "source", acc.getOrDefault(r.get("sourceAccountId"), body("id", r.get("sourceAccountId"), "code", "", "name", "—")),
                    "destination", acc.getOrDefault(r.get("destinationAccountId"),
                            body("id", r.get("destinationAccountId"), "code", "", "name", "—")),
                    "feeAccount", r.get("feeAccountId") == null ? null : acc.get(r.get("feeAccountId")),
                    "grossAmount", fixed2(gross), "feeAmount", fixed2(fee), "netAmount", fixed2(gross.subtract(fee)),
                    "session", session == null ? null : body("id", session.get("id"), "registerName", session.get("rname"),
                            "openedAt", session.get("openedAt")),
                    "journalEntryId", r.get("journalEntryId")));
        }
        return body("data", data, "total", total, "page", page, "pageSize", pageSize,
                "totalPages", Math.max(1, (long) Math.ceil(total / (double) pageSize)));
    }

    private BigDecimal posReceiptsSince(String orgId, String accountId, Instant since) {
        List<String> types = new ArrayList<>(MoneyActivityTaxonomy.POS_SALE_SOURCE_TYPES);
        types.add("pos_refund");
        types.add("pos_payment_refund");
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT COALESCE(SUM(jl.\"baseDebit\" - jl.\"baseCredit\"), 0) FROM \"JournalLine\" jl"
                + " JOIN \"JournalEntry\" je ON je.id = jl.\"journalEntryId\" WHERE jl.\"organizationId\" = :org"
                + " AND jl.\"accountId\" = :acct AND je.status::text IN ('posted', 'reversed') AND "
                + MoneyActivityTaxonomy.EFFECTIVE_SOURCE_TYPE + " IN (" + MoneyActivityTaxonomy.sqlList(types) + ")"
                + (since != null ? " AND je.\"postingDate\" > :since" : ""))
                .param("org", orgId).param("acct", accountId);
        if (since != null) {
            spec = spec.param("since", FiscalPeriodControl.ts(since));
        }
        return spec.query(BigDecimal.class).single();
    }
}
