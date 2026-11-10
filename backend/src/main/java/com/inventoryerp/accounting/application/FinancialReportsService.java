package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.application.PostingService.plain;

import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.accounting.application.AccountingCatalogs.ReportSectionDef;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trial balance, account ledger, general ledger, P&amp;L, balance sheet and the direct-method cash-flow
 * statement (ports of {@code accounting-reporting}, {@code pnl-report}, {@code balance-sheet-report} and
 * {@code cash-flow-report} services). Snapshot-first where Nest is (served only when nothing balance-affecting
 * posted since), live otherwise.
 */
@Service
public class FinancialReportsService {

    private static final BigDecimal TB_EPSILON = new BigDecimal("0.0001");
    private static final BigDecimal BS_EPSILON = new BigDecimal("0.01");

    private final JdbcClient jdbc;
    private final LedgerQueries ledger;
    private final AccountResolverService accounts;

    public FinancialReportsService(JdbcClient jdbc, LedgerQueries ledger, AccountResolverService accounts) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.accounts = accounts;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** The organisation's zone: request dates are org-local days (ADR-S070). */
    private ZoneId zone() {
        return AccountingDay.zone(jdbc);
    }

    /** Latest snapshot ≤ asOf in {@code table}, served only when no balance-affecting line posted since. */
    private Instant findSnapshot(String table, boolean versioned, Instant asOf) {
        Instant snap = jdbc.sql("SELECT \"asOf\" FROM \"" + table + "\" WHERE \"organizationId\" = ? AND \"asOf\" <= ?"
                        + (versioned ? " AND \"schemaVersion\" >= 2" : "") + " ORDER BY \"asOf\" DESC LIMIT 1")
                .params(org(), FiscalPeriodControl.ts(asOf))
                .query((rs, n) -> rs.getTimestamp(1).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC))
                .optional().orElse(null);
        if (snap == null) {
            return null;
        }
        return ledger.linesBetween(org(), snap, asOf) > 0 ? null : snap;
    }

    // ------------------------------------------------------------------ trial balance / ledgers

    @Transactional(readOnly = true)
    public Map<String, Object> trialBalance(String from, String to) {
        return trialBalance(from, to, null);
    }

    /**
     * Trial balance. {@code view = pre_closing} leaves out the period / fiscal-year closing entries (P&L accounts keep
     * their period activity); default / {@code post_closing} includes them. Snapshots are cumulative, so they answer
     * only an open-ended post-closing request (ACC-1, B10).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> trialBalance(String from, String to, String view) {
        boolean preClosing = "pre_closing".equals(view);
        boolean ranged = from != null && !from.isEmpty();
        Instant asOf = to != null && !to.isEmpty() ? LedgerQueries.date(to, zone()) : Instant.now();
        Instant snap = preClosing || ranged ? null : findSnapshot("ReportTrialBalanceSnapshot", true, asOf);
        if (snap != null) {
            BigDecimal td = BigDecimal.ZERO;
            BigDecimal tc = BigDecimal.ZERO;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT * FROM \"ReportTrialBalanceSnapshot\" WHERE \"organizationId\" = ?"
                    + " AND \"asOf\" = ? ORDER BY \"accountCode\" ASC").params(org(), FiscalPeriodControl.ts(snap)))) {
                td = td.add(SqlRows.dec(r, "debit"));
                tc = tc.add(SqlRows.dec(r, "credit"));
                rows.add(body("accountId", r.get("accountId"), "code", r.get("accountCode"), "name", r.get("accountName"),
                        "categoryKey", r.get("accountCategoryKey"), "classification", r.get("classification"),
                        "normalBalance", r.get("normalBalance"), "accountType", r.get("accountType"),
                        "debit", plain(SqlRows.dec(r, "debit")), "credit", plain(SqlRows.dec(r, "credit")),
                        "balance", plain(SqlRows.dec(r, "balance"))));
            }
            return body("rows", rows, "totals", body("debit", plain(td), "credit", plain(tc)),
                    "balanced", td.subtract(tc).abs().compareTo(TB_EPSILON) <= 0, "source", "snapshot", "asOf", snap);
        }
        Map<String, BigDecimal[]> grouped = ledger.balances(org(), LedgerQueries.date(from, zone()), LedgerQueries.date(to, zone()), null,
                preClosing);
        Map<String, AccountMeta> meta = accounts.meta(grouped.keySet());
        BigDecimal td = BigDecimal.ZERO;
        BigDecimal tc = BigDecimal.ZERO;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> g : grouped.entrySet()) {
            AccountMeta a = meta.get(g.getKey());
            BigDecimal d = g.getValue()[0];
            BigDecimal c = g.getValue()[1];
            td = td.add(d);
            tc = tc.add(c);
            rows.add(body("accountId", g.getKey(), "code", a == null ? "" : a.code(), "name", a == null ? "" : a.name(),
                    "categoryKey", a == null ? null : a.categoryKey(), "classification", a == null ? null : a.classification(),
                    "normalBalance", a == null ? "debit" : a.normalBalance(), "sortOrder", a == null ? 0 : a.sortOrder(),
                    "debit", plain(d), "credit", plain(c), "balance", plain(d.subtract(c))));
        }
        rows.sort(Comparator.<Map<String, Object>>comparingInt(r -> (Integer) r.get("sortOrder"))
                .thenComparing(r -> (String) r.get("code"), AccountResolverService::localeCompare));
        Map<String, Object> out = body("rows", rows, "totals", body("debit", plain(td), "credit", plain(tc)),
                "balanced", td.subtract(tc).abs().compareTo(TB_EPSILON) <= 0, "source", "live");
        if (view != null) {
            out.put("view", preClosing ? "pre_closing" : "post_closing");
        }
        return out;
    }

    private String rangeSql(Map<String, Object> params, String from, String to) {
        StringBuilder sql = new StringBuilder();
        ZoneId zone = zone();
        Instant f = LedgerQueries.date(from, zone);
        Instant t = LedgerQueries.date(to, zone);
        if (f != null) {
            sql.append(" AND e.\"postingDate\" >= :from");
            params.put("from", FiscalPeriodControl.ts(f));
        }
        if (t != null) {
            sql.append(" AND e.\"postingDate\" <= :to");
            params.put("to", FiscalPeriodControl.ts(t));
        }
        return sql.toString();
    }

    private JdbcClient.StatementSpec spec(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec s = jdbc.sql(sql);
        for (Map.Entry<String, Object> p : params.entrySet()) {
            s = s.param(p.getKey(), p.getValue());
        }
        return s;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> accountLedger(String accountId, String from, String to) {
        Map<String, Object> account = SqlRows.one(jdbc.sql("SELECT * FROM \"Account\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(accountId, org()));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("org", org());
        params.put("acct", accountId);
        String range = rangeSql(params, from, to);
        BigDecimal running = BigDecimal.ZERO;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> l : SqlRows.list(spec("SELECT l.id, e.\"postingDate\", e.\"entryNumber\", l.description,"
                + " l.\"baseDebit\", l.\"baseCredit\" FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                + " WHERE l.\"organizationId\" = :org AND l.\"accountId\" = :acct AND e.status::text IN ('posted', 'reversed')" + range
                + " ORDER BY e.\"postingDate\" ASC, l.\"lineNumber\" ASC", params))) {
            running = running.add(SqlRows.dec(l, "baseDebit")).subtract(SqlRows.dec(l, "baseCredit"));
            rows.add(body("id", l.get("id"), "date", l.get("postingDate"), "entryNumber", l.get("entryNumber"),
                    "description", l.get("description"), "debit", plain(SqlRows.dec(l, "baseDebit")),
                    "credit", plain(SqlRows.dec(l, "baseCredit")), "balance", plain(running)));
        }
        return body("account", account, "lines", rows, "closingBalance", plain(running));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> generalLedger(String from, String to, int page, int pageSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("org", org());
        String base = " FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                + " JOIN \"Account\" a ON a.id = l.\"accountId\" WHERE l.\"organizationId\" = :org"
                + " AND e.status::text IN ('posted', 'reversed')" + rangeSql(params, from, to);
        long total = spec("SELECT count(*)" + base, params).query(Long.class).single();
        Map<String, Object> pageParams = new LinkedHashMap<>(params);
        pageParams.put("lim", pageSize);
        pageParams.put("off", (page - 1) * pageSize);
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> l : SqlRows.list(spec("SELECT l.id, e.\"postingDate\", e.\"entryNumber\", a.code, a.name, l.description,"
                + " l.\"baseDebit\", l.\"baseCredit\"" + base + " ORDER BY e.\"postingDate\" ASC, l.\"lineNumber\" ASC"
                + " LIMIT :lim OFFSET :off", pageParams))) {
            data.add(body("id", l.get("id"), "date", l.get("postingDate"), "entryNumber", l.get("entryNumber"),
                    "accountCode", l.get("code"), "accountName", l.get("name"), "description", l.get("description"),
                    "debit", plain(SqlRows.dec(l, "baseDebit")), "credit", plain(SqlRows.dec(l, "baseCredit"))));
        }
        return body("data", data, "meta", body("page", page, "pageSize", pageSize, "total", total,
                "totalPages", Math.max(1, (long) Math.ceil(total / (double) pageSize))));
    }

    // ------------------------------------------------------------------ P&L

    @Transactional(readOnly = true)
    public Map<String, Object> pnl(String from, String to) {
        // ACC-1 (B1, B10): always from the ledger. The cumulative snapshot cannot answer a date range and carries no
        // other income / expense; closing entries are left out so a closed period still reports its result.
        Map<String, BigDecimal[]> grouped = ledger.balances(org(), LedgerQueries.date(from, zone()), LedgerQueries.date(to, zone()), null,
                true);
        Map<String, AccountMeta> meta = accounts.meta(grouped.keySet());
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal contra = BigDecimal.ZERO;
        BigDecimal cogs = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        BigDecimal otherIncome = BigDecimal.ZERO;
        BigDecimal otherExpense = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal[]> g : grouped.entrySet()) {
            AccountMeta a = meta.get(g.getKey());
            if (a == null) {
                continue;
            }
            BigDecimal v = AccountClassification.displayBalance(g.getValue()[0].subtract(g.getValue()[1]), a.normalBalance());
            switch (String.valueOf(a.reportSection())) {
                case "revenue" -> revenue = revenue.add(v);
                case "contra_revenue" -> contra = contra.add(v);
                case "cogs" -> cogs = cogs.add(v);
                case "operating_expense" -> expense = expense.add(v);
                case "other_income" -> otherIncome = otherIncome.add(v);
                case "other_expense" -> otherExpense = otherExpense.add(v);
                default -> { }
            }
        }
        BigDecimal netRevenue = revenue.subtract(contra);
        BigDecimal gross = netRevenue.subtract(cogs);
        BigDecimal operating = gross.subtract(expense);
        BigDecimal net = operating.add(otherIncome).subtract(otherExpense);
        return body("revenue", plain(revenue), "contraRevenue", plain(contra), "netRevenue", plain(netRevenue),
                "cogs", plain(cogs), "grossProfit", plain(gross), "expense", plain(expense), "otherIncome", plain(otherIncome),
                "otherExpense", plain(otherExpense), "operatingProfit", plain(operating), "netProfit", plain(net), "source", "live");
    }

    // ------------------------------------------------------------------ balance sheet

    private static final class Totals {
        BigDecimal assets = BigDecimal.ZERO;
        BigDecimal liabilities = BigDecimal.ZERO;
        BigDecimal equity = BigDecimal.ZERO;
        BigDecimal earnings = BigDecimal.ZERO;

        void accumulate(String section, BigDecimal value) {
            String side = AccountClassification.balanceSheetSideOf(section);
            if ("asset".equals(side)) {
                assets = assets.add(value);
                return;
            }
            if ("liability".equals(side)) {
                liabilities = liabilities.add(value);
                return;
            }
            if ("equity".equals(side)) {
                equity = equity.add(value);
                return;
            }
            switch (String.valueOf(section)) {
                case "revenue", "other_income" -> earnings = earnings.add(value);
                case "contra_revenue", "cogs", "operating_expense", "other_expense" -> earnings = earnings.subtract(value);
                default -> { }
            }
        }
    }

    private Map<String, Object> summary(String asOf, Totals t, String source) {
        BigDecimal le = t.liabilities.add(t.equity).add(t.earnings);
        return body("asOf", asOf, "totalAssets", plain(t.assets), "totalLiabilities", plain(t.liabilities),
                "totalEquity", plain(t.equity), "currentYearEarnings", plain(t.earnings), "totalLiabilitiesAndEquity", plain(le),
                "balanced", t.assets.subtract(le).abs().compareTo(BS_EPSILON) <= 0, "source", source,
                "totals", body("assets", plain(t.assets), "liabilities", plain(t.liabilities), "equity", plain(t.equity),
                        "liabilitiesAndEquity", plain(le)));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> balanceSheet(String asOf) {
        Instant requested = LedgerQueries.date(asOf, zone());
        if (requested == null) {
            throw new IllegalArgumentException("Invalid Date: " + asOf);
        }
        Instant snap = findSnapshot("ReportBalanceSheetSnapshot", true, requested);
        if (snap != null) {
            Totals totals = new Totals();
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"reportSection\", \"normalBalance\", balance FROM"
                    + " \"ReportBalanceSheetSnapshot\" WHERE \"organizationId\" = ? AND \"asOf\" = ? ORDER BY \"accountCode\" ASC")
                    .params(org(), FiscalPeriodControl.ts(snap)))) {
                // Snapshot rows hold the display balance (normal direction); recover the ledger net first.
                String normal = (String) r.get("normalBalance");
                BigDecimal net = AccountClassification.displayBalance(SqlRows.dec(r, "balance"), normal);
                totals.accumulate((String) r.get("reportSection"),
                        AccountClassification.balanceSheetBalance(net, (String) r.get("reportSection"), normal));
            }
            return summary(asOf, totals, "snapshot");
        }
        return summary(asOf, aggregate(requested, null), "live");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> balanceSheetDetailed(String asOf) {
        Instant requested = LedgerQueries.date(asOf, zone());
        if (requested == null) {
            throw new IllegalArgumentException("Invalid Date: " + asOf);
        }
        Map<String, List<Map<String, Object>>> rowsBySection = new LinkedHashMap<>();
        Totals totals = aggregate(requested, rowsBySection);
        List<Map<String, Object>> sections = new ArrayList<>();
        for (ReportSectionDef def : AccountClassification.BALANCE_SHEET_SECTIONS) {
            List<Map<String, Object>> rows = rowsBySection.getOrDefault(def.key(), List.of());
            BigDecimal subtotal = BigDecimal.ZERO;
            for (Map<String, Object> r : rows) {
                subtotal = subtotal.add(new BigDecimal((String) r.get("balance")));
            }
            sections.add(body("key", def.key(), "label", def.label(), "type", def.side(), "rows", rows, "subtotal", plain(subtotal)));
        }
        Map<String, Object> out = summary(asOf, totals, "live");
        out.put("sections", sections);
        return out;
    }

    private Totals aggregate(Instant asOf, Map<String, List<Map<String, Object>>> rowsBySection) {
        Map<String, BigDecimal[]> grouped = ledger.balances(org(), null, asOf, null);
        Map<String, AccountMeta> meta = accounts.meta(grouped.keySet());
        Totals totals = new Totals();
        for (Map.Entry<String, BigDecimal[]> g : grouped.entrySet()) {
            AccountMeta a = meta.get(g.getKey());
            // ACC-1 (B10): an inactive account that still carries a balance stays on the balance sheet.
            if (a == null) {
                continue;
            }
            BigDecimal display = AccountClassification.balanceSheetBalance(g.getValue()[0].subtract(g.getValue()[1]),
                    a.reportSection(), a.normalBalance());
            totals.accumulate(a.reportSection(), display);
            if (rowsBySection != null && AccountClassification.balanceSheetSideOf(a.reportSection()) != null) {
                rowsBySection.computeIfAbsent(a.reportSection(), k -> new ArrayList<>()).add(body("accountId", a.id(),
                        "code", a.code(), "name", a.name(), "balance", plain(display), "sortOrder", a.sortOrder()));
            }
        }
        if (rowsBySection != null) {
            for (List<Map<String, Object>> list : rowsBySection.values()) {
                list.sort(Comparator.<Map<String, Object>>comparingInt(r -> (Integer) r.get("sortOrder"))
                        .thenComparing(r -> (String) r.get("code"), AccountResolverService::localeCompare));
            }
        }
        return totals;
    }

    // ------------------------------------------------------------------ cash-flow statement

    /** Direct-method cash flow: cash moved attributed by the counter account's section. */
    @Transactional(readOnly = true)
    public Map<String, Object> cashFlow(String fromRaw, String toRaw) {
        ZoneId zone = zone();
        Instant from = parseDate(fromRaw, "from", false, zone);
        Instant to = parseDate(toRaw, "to", true, zone);
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("`from` must be on or before `to`");
        }
        Set<String> cash = new HashSet<>(accounts.cashEquivalentIds());
        if (cash.isEmpty()) {
            return body("from", fromRaw, "to", toRaw, "openingCash", "0", "operating", "0", "investing", "0", "financing", "0",
                    "netCashFlow", "0", "closingCash", "0", "actualClosingCash", "0", "reconciled", true);
        }
        BigDecimal opening = from == null ? BigDecimal.ZERO : sumCash(cash, null, from);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("org", org());
        params.put("cash", cash);
        StringBuilder period = new StringBuilder();
        if (from != null) {
            period.append(" AND e.\"postingDate\" >= :from");
            params.put("from", FiscalPeriodControl.ts(from));
        }
        if (to != null) {
            period.append(" AND e.\"postingDate\" <= :to");
            params.put("to", FiscalPeriodControl.ts(to));
        }
        List<Map<String, Object>> lines = SqlRows.list(spec("SELECT l.\"accountId\", l.\"baseDebit\", l.\"baseCredit\" FROM \"JournalLine\" l"
                + " WHERE l.\"journalEntryId\" IN (SELECT DISTINCT cl.\"journalEntryId\" FROM \"JournalLine\" cl JOIN \"JournalEntry\" e"
                + " ON e.id = cl.\"journalEntryId\" WHERE cl.\"organizationId\" = :org AND cl.\"accountId\" IN (:cash)"
                + " AND e.status::text IN ('posted', 'reversed')" + period + ")", params));
        Set<String> ids = new HashSet<>();
        lines.forEach(l -> ids.add((String) l.get("accountId")));
        Map<String, AccountMeta> meta = accounts.meta(ids);
        BigDecimal operating = BigDecimal.ZERO;
        BigDecimal investing = BigDecimal.ZERO;
        BigDecimal financing = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            String accountId = (String) l.get("accountId");
            if (cash.contains(accountId)) {
                continue;
            }
            BigDecimal contribution = SqlRows.dec(l, "baseCredit").subtract(SqlRows.dec(l, "baseDebit"));
            if (contribution.signum() == 0) {
                continue;
            }
            AccountMeta a = meta.get(accountId);
            String section = a == null ? "operating" : AccountClassification.cashFlowSectionOf(a.classification(), a.cashFlowClass());
            switch (section) {
                case "operating" -> operating = operating.add(contribution);
                case "investing" -> investing = investing.add(contribution);
                default -> financing = financing.add(contribution);
            }
        }
        BigDecimal net = operating.add(investing).add(financing);
        BigDecimal closing = opening.add(net);
        BigDecimal actual = sumCash(cash, to, null);
        return body("from", fromRaw, "to", toRaw, "openingCash", plain(opening), "operating", plain(operating),
                "investing", plain(investing), "financing", plain(financing), "netCashFlow", plain(net),
                "closingCash", plain(closing), "actualClosingCash", plain(actual),
                "reconciled", closing.subtract(actual).abs().compareTo(new BigDecimal("0.01")) <= 0);
    }

    private BigDecimal sumCash(Set<String> cash, Instant upTo, Instant before) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("org", org());
        params.put("cash", cash);
        StringBuilder sql = new StringBuilder("SELECT coalesce(sum(l.\"baseDebit\"), 0) - coalesce(sum(l.\"baseCredit\"), 0)"
                + " FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = :org"
                + " AND l.\"accountId\" IN (:cash) AND e.status::text IN ('posted', 'reversed')");
        if (upTo != null) {
            sql.append(" AND e.\"postingDate\" <= :upTo");
            params.put("upTo", FiscalPeriodControl.ts(upTo));
        }
        if (before != null) {
            sql.append(" AND e.\"postingDate\" < :before");
            params.put("before", FiscalPeriodControl.ts(before));
        }
        return spec(sql.toString(), params).query(BigDecimal.class).single();
    }

    /** A bare date is an org-local day (its first instant, or its last with {@code endOfDay}); ISO taken as-is. */
    private static Instant parseDate(String value, String label, boolean endOfDay, ZoneId zone) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        Instant d = AccountingDay.parse(value, zone);
        if (d == null) {
            throw ApiException.badRequest("Invalid `" + label + "` date: " + value);
        }
        if (endOfDay && AccountingDay.isDateOnly(value)) {
            d = AccountingDay.endInclusive(java.time.LocalDate.parse(value.trim()), zone);
        }
        return d;
    }
}
