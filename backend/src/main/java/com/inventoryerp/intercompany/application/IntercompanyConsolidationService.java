package com.inventoryerp.intercompany.application;

import com.inventoryerp.accounting.application.AccountClassification;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.accounting.application.AccountingDay;
import com.inventoryerp.accounting.application.CurrencyService;
import com.inventoryerp.accounting.application.LedgerQueries;
import com.inventoryerp.intercompany.application.CompanyGroupService.Group;
import com.inventoryerp.intercompany.application.CompanyGroupService.Member;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Group consolidation with intercompany eliminations and the intercompany reconciliation (ADR-S028 §6). Parent company
 * only. Each member is read as its own tenant inside one read-only transaction opened without a tenant; accounts are
 * matched by code and translated to the group currency at one rate per member (closing rate for {@code tb}, average
 * rate of the period for {@code pl}).
 */
@Service
public class IntercompanyConsolidationService {

    public static final String ELIMINATIONS = "eliminations";
    public static final String CONSOLIDATED = "consolidated";
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final JdbcClient jdbc;
    private final CompanyGroupService groups;
    private final IntercompanyPartnerService partners;
    private final AccountResolverService accounts;
    private final CurrencyService currencies;
    private final TransactionTemplate readTx;

    public IntercompanyConsolidationService(JdbcClient jdbc, CompanyGroupService groups,
            IntercompanyPartnerService partners, AccountResolverService accounts, CurrencyService currencies,
            PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.groups = groups;
        this.partners = partners;
        this.accounts = accounts;
        this.currencies = currencies;
        this.readTx = new TransactionTemplate(txm);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setReadOnly(true);
    }

    /** One member's ledger: net (debit − credit) per account and the eliminated part, in its own currency. */
    private record Ledger(Map<String, BigDecimal> net, Map<String, BigDecimal> eliminated, Map<String, AccountMeta> meta,
            Map<String, Trade> trade, String cogsAccountId, String stockAccountId) {}

    /**
     * What one member did with one counterpart in the range, in its own currency. As seller: intercompany revenue on
     * the invoices and the cost of goods its stock issues booked for them. As buyer: purchase expense eliminated with
     * the payable, goods received from the counterpart on purchase orders, and the part of them still on hand.
     */
    private static final class Trade {
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal onHand = BigDecimal.ZERO;
    }

    /** A consolidated row under construction (keyed by account code). */
    private static final class Row {
        String code;
        String name;
        String classification;
        String reportSection;
        String normalBalance;
        boolean pnl;
        boolean income;
        final Map<String, BigDecimal> raw = new HashMap<>();
        final Map<String, BigDecimal> elim = new HashMap<>();
    }

    private <T> T unscopedRead(Supplier<T> body) {
        return TenantContext.unscoped(() -> readTx.execute(s -> body.get()));
    }

    /** {@code GET /intercompany/consolidation}. */
    public Map<String, Object> consolidation(String fromRaw, String toRaw, String statement) {
        String kind = statement == null || statement.isBlank() ? "pl" : statement;
        if (!"pl".equals(kind) && !"tb".equals(kind)) {
            throw ApiException.badRequest("statement must be one of the following values: pl, tb");
        }
        Group g = groups.requireParent();
        Instant from = LedgerQueries.date(fromRaw, AccountingDay.zone(jdbc));
        Instant to = LedgerQueries.date(toRaw, AccountingDay.zone(jdbc));
        Instant asOf = to == null ? Instant.now() : to;

        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (Member m : g.members()) {
            rates.put(m.organizationId(), "pl".equals(kind) ? averageRate(m.currencyCode(), g.currencyCode(), from, asOf)
                    : currencies.getRate(m.currencyCode(), g.currencyCode(), asOf));
        }
        Map<String, Ledger> ledgers = unscopedRead(() -> {
            Map<String, Ledger> out = new LinkedHashMap<>();
            for (Member m : g.members()) {
                out.put(m.organizationId(), TenantContext.run(TenantStore.organization(m.organizationId()),
                        () -> ledger(m.organizationId(), g, from, to)));
            }
            return out;
        });

        BigDecimal unrealised = costOfIntercompanySales(g, ledgers, rates);

        Map<String, Row> rows = new LinkedHashMap<>();
        for (Member m : g.members()) {
            Ledger l = ledgers.get(m.organizationId());
            BigDecimal rate = rates.get(m.organizationId());
            for (Map.Entry<String, BigDecimal> e : l.net().entrySet()) {
                AccountMeta a = l.meta().get(e.getKey());
                if (a == null) {
                    continue;
                }
                boolean pnl = AccountClassification.isProfitAndLoss(a.classification());
                if ("pl".equals(kind) && !pnl) {
                    continue;
                }
                Row r = rows.computeIfAbsent(a.code(), c -> {
                    Row n = new Row();
                    n.code = a.code();
                    n.name = a.name();
                    n.classification = a.classification();
                    n.reportSection = a.reportSection();
                    n.normalBalance = a.normalBalance();
                    n.pnl = pnl;
                    n.income = isIncome(a);
                    return n;
                });
                if (m.parent()) {
                    r.name = a.name(); // the parent's chart names the group's accounts
                }
                r.raw.merge(m.organizationId(), e.getValue().multiply(rate, MC), BigDecimal::add);
                BigDecimal el = l.eliminated().getOrDefault(e.getKey(), BigDecimal.ZERO);
                if (el.signum() != 0) {
                    r.elim.merge(m.organizationId(), el.multiply(rate, MC), BigDecimal::add);
                }
            }
        }

        List<String> columns = new ArrayList<>();
        g.members().forEach(m -> columns.add(m.organizationId()));
        columns.add(ELIMINATIONS);
        columns.add(CONSOLIDATED);
        Map<String, BigDecimal> income = zero(columns);
        Map<String, BigDecimal> expense = zero(columns);
        Map<String, BigDecimal> net = zero(columns);
        Map<String, BigDecimal> memberElimProfit = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        List<Row> sorted = new ArrayList<>(rows.values());
        sorted.sort((x, y) -> x.code.compareTo(y.code));
        for (Row r : sorted) {
            Map<String, Object> byCompany = new LinkedHashMap<>();
            BigDecimal elimTotal = BigDecimal.ZERO;
            BigDecimal total = BigDecimal.ZERO;
            for (Member m : g.members()) {
                BigDecimal v = shown(kind, r, r.raw.getOrDefault(m.organizationId(), BigDecimal.ZERO));
                BigDecimal ev = shown(kind, r, r.elim.getOrDefault(m.organizationId(), BigDecimal.ZERO)).negate();
                byCompany.put(m.organizationId(), money(v));
                total = total.add(v);
                elimTotal = elimTotal.add(ev);
                accumulate(kind, r, m.organizationId(), v, income, expense, net);
                if (r.pnl) {
                    memberElimProfit.merge(m.organizationId(), r.income ? ev : ev.negate(), BigDecimal::add);
                }
            }
            accumulate(kind, r, ELIMINATIONS, elimTotal, income, expense, net);
            BigDecimal consolidated = total.add(elimTotal);
            accumulate(kind, r, CONSOLIDATED, consolidated, income, expense, net);
            byCompany.put(ELIMINATIONS, money(elimTotal));
            byCompany.put(CONSOLIDATED, money(consolidated));
            out.add(Obj.of("code", r.code, "name", r.name, "classification", r.classification, "reportSection",
                    r.reportSection, "type", r.pnl ? (r.income ? "income" : "expense") : r.classification, "byCompany",
                    byCompany));
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        if ("pl".equals(kind)) {
            Map<String, BigDecimal> profit = zero(columns);
            columns.forEach(c -> profit.put(c, income.get(c).subtract(expense.get(c))));
            totals.put("income", columnMap(columns, income));
            totals.put("expense", columnMap(columns, expense));
            totals.put("netProfit", columnMap(columns, profit));
            BigDecimal nci = BigDecimal.ZERO;
            for (Member m : g.members()) {
                if (m.parent()) {
                    continue;
                }
                BigDecimal own = profit.get(m.organizationId())
                        .add(memberElimProfit.getOrDefault(m.organizationId(), BigDecimal.ZERO));
                nci = nci.add(own.multiply(HUNDRED.subtract(m.ownershipPercent()), MC).divide(HUNDRED, MC));
            }
            BigDecimal group = profit.get(CONSOLIDATED);
            totals.put("nonControllingInterest", money(nci));
            totals.put("attributableToParent", money(group.subtract(nci)));
            // Profit eliminated beyond the unrealised profit in inventory = intercompany balances booked on one side only.
            totals.put("unrealisedProfitInInventory", money(unrealised));
            totals.put("intercompanyDifference", money(profit.get(ELIMINATIONS).add(unrealised)));
        } else {
            totals.put("net", columnMap(columns, net));
            // Unmatched intercompany balances (one side not booked yet, FX between members, tax on IC documents).
            totals.put("intercompanyDifference", money(net.get(ELIMINATIONS)));
        }
        List<Map<String, Object>> companies = g.members().stream().map(m -> Obj.of("organizationId",
                m.organizationId(), "code", m.code(), "name", m.name(), "currencyCode", m.currencyCode(),
                "ownershipPercent", m.ownershipPercent().stripTrailingZeros().toPlainString(), "isParent", m.parent(),
                "rate", rates.get(m.organizationId()).stripTrailingZeros().toPlainString())).toList();
        return Obj.of("statement", kind, "from", fromRaw, "to", toRaw, "groupId", g.id(), "groupName", g.name(),
                "currencyCode", g.currencyCode(), "companies", companies, "rows", out, "totals", totals);
    }

    /** {@code GET /intercompany/reconciliation}: each pair's positions with each other in the group currency. */
    public Map<String, Object> reconciliation(String asOfRaw) {
        Group g = groups.requireParent();
        Instant asOfDate = LedgerQueries.date(asOfRaw, AccountingDay.zone(jdbc));
        Instant asOf = asOfDate == null ? Instant.now() : asOfDate;
        Map<String, BigDecimal> rates = new HashMap<>();
        g.members().forEach(m -> rates.put(m.organizationId(), currencies.getRate(m.currencyCode(), g.currencyCode(),
                asOf)));
        // position[x][y] = x's net (debit − credit) on AR/AP control lines with the partner standing for y.
        Map<String, Map<String, BigDecimal>> position = unscopedRead(() -> {
            Map<String, Map<String, BigDecimal>> out = new HashMap<>();
            for (Member m : g.members()) {
                Map<String, String> ic = partners.partnersInGroup(m.organizationId(), g);
                Map<String, BigDecimal> byCounterpart = new HashMap<>();
                if (!ic.isEmpty()) {
                    for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.\"partnerId\", sum(l.\"baseDebit\") AS d,"
                            + " sum(l.\"baseCredit\") AS c FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                            + " l.\"journalEntryId\" JOIN \"Account\" a ON a.id = l.\"accountId\" WHERE"
                            + " l.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed') AND"
                            + " e.\"postingDate\" <= ? AND a.\"controlAccountType\"::text IN ('ar', 'ap') AND"
                            + " l.\"partnerId\" = ANY (?) GROUP BY 1").params(m.organizationId(), Timestamp.from(asOf),
                                    ic.keySet().toArray(String[]::new)))) {
                        byCounterpart.merge(ic.get((String) r.get("partnerId")), SqlRows.dec(r, "d")
                                .subtract(SqlRows.dec(r, "c")).multiply(rates.get(m.organizationId()), MC),
                                BigDecimal::add);
                    }
                }
                out.put(m.organizationId(), byCounterpart);
            }
            return out;
        });
        List<Map<String, Object>> pairs = new ArrayList<>();
        List<Member> ms = g.members();
        for (int i = 0; i < ms.size(); i++) {
            for (int j = i + 1; j < ms.size(); j++) {
                Member a = ms.get(i);
                Member b = ms.get(j);
                BigDecimal ab = position.get(a.organizationId()).getOrDefault(b.organizationId(), BigDecimal.ZERO);
                BigDecimal ba = position.get(b.organizationId()).getOrDefault(a.organizationId(), BigDecimal.ZERO);
                BigDecimal diff = ab.add(ba);
                pairs.add(Obj.of("companyA", Obj.of("organizationId", a.organizationId(), "code", a.code(), "name",
                        a.name()), "companyB", Obj.of("organizationId", b.organizationId(), "code", b.code(), "name",
                        b.name()), "balanceA", money(ab), "balanceB", money(ba), "difference", money(diff),
                        "matched", money(diff).equals(money(BigDecimal.ZERO))));
            }
        }
        return Obj.of("asOf", asOfRaw, "currencyCode", g.currencyCode(), "pairs", pairs);
    }

    // ---- per member -----------------------------------------------------------------------------------------------

    /** Runs as the member's tenant. */
    private Ledger ledger(String org, Group g, Instant from, Instant to) {
        StringBuilder range = new StringBuilder();
        List<Object> rangeParams = new ArrayList<>();
        if (from != null) {
            range.append(" AND e.\"postingDate\" >= ?");
            rangeParams.add(Timestamp.from(from));
        }
        if (to != null) {
            range.append(" AND e.\"postingDate\" <= ?");
            rangeParams.add(Timestamp.from(to));
        }
        Map<String, BigDecimal> net = new LinkedHashMap<>();
        List<Object> p = new ArrayList<>(List.of(org));
        p.addAll(rangeParams);
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.\"accountId\", sum(l.\"baseDebit\") AS d,"
                + " sum(l.\"baseCredit\") AS c FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                + " l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed')"
                + range + " GROUP BY 1").params(p))) {
            net.put((String) r.get("accountId"), SqlRows.dec(r, "d").subtract(SqlRows.dec(r, "c")));
        }
        Map<String, AccountMeta> meta = accounts.meta(net.keySet());
        Map<String, BigDecimal> eliminated = new HashMap<>();
        String[] ic = partners.partnersInGroup(org, g).keySet().toArray(String[]::new);
        if (ic.length > 0) {
            String icLine = "x.\"partnerId\" = ANY (?) AND xa.\"controlAccountType\"::text IN ('ar', 'ap')";
            // (a) intercompany receivables / payables.
            List<Object> pa = new ArrayList<>(List.of(org));
            pa.addAll(rangeParams);
            pa.add(ic);
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT x.\"accountId\", sum(x.\"baseDebit\") AS d,"
                    + " sum(x.\"baseCredit\") AS c FROM \"JournalLine\" x JOIN \"JournalEntry\" e ON e.id ="
                    + " x.\"journalEntryId\" JOIN \"Account\" xa ON xa.id = x.\"accountId\" WHERE x.\"organizationId\" = ?"
                    + " AND e.status::text IN ('posted', 'reversed')" + range + " AND " + icLine + " GROUP BY 1")
                    .params(pa))) {
                eliminated.merge((String) r.get("accountId"), SqlRows.dec(r, "d").subtract(SqlRows.dec(r, "c")),
                        BigDecimal::add);
            }
            // (b) P&L lines of the entries holding an (a) line.
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.\"accountId\", sum(l.\"baseDebit\") AS d,"
                    + " sum(l.\"baseCredit\") AS c FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                    + " l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed')"
                    + range + " AND EXISTS (SELECT 1 FROM \"JournalLine\" x JOIN \"Account\" xa ON xa.id ="
                    + " x.\"accountId\" WHERE x.\"journalEntryId\" = e.id AND " + icLine + ") GROUP BY 1").params(pa))) {
                AccountMeta a = meta.get((String) r.get("accountId"));
                if (a != null && AccountClassification.isProfitAndLoss(a.classification())) {
                    eliminated.merge(a.id(), SqlRows.dec(r, "d").subtract(SqlRows.dec(r, "c")), BigDecimal::add);
                }
            }
        }
        Map<String, String> icPartners = partners.partnersInGroup(org, g);
        Map<String, Trade> trade = new HashMap<>();
        String cogs = mapped(org, "cogs");
        String stock = mapped(org, "stock_valuation");
        if (!icPartners.isEmpty() && cogs != null && stock != null) {
            trade(org, icPartners, range.toString(), rangeParams, from, to, meta, trade);
            List<String> extra = new ArrayList<>();
            for (String id : List.of(cogs, stock)) {
                if (!net.containsKey(id)) {
                    net.put(id, BigDecimal.ZERO);
                    extra.add(id);
                }
            }
            if (!extra.isEmpty()) {
                meta = new HashMap<>(meta);
                meta.putAll(accounts.meta(new HashSet<>(extra)));
            }
        }
        return new Ledger(net, eliminated, meta, trade, cogs, stock);
    }

    private String mapped(String org, String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?"
                + " AND \"deletedAt\" IS NULL").params(org, key).query(String.class).optional().orElse(null);
    }

    /** Fills {@code trade} per counterpart organization (runs as the member's tenant). */
    private void trade(String org, Map<String, String> icPartners, String range, List<Object> rangeParams, Instant from,
            Instant to, Map<String, AccountMeta> meta, Map<String, Trade> trade) {
        String[] ic = icPartners.keySet().toArray(String[]::new);
        // Entries holding an intercompany AR (sale) or AP (purchase) line, with the partner and the control type.
        String icEntries = "SELECT DISTINCT x.\"journalEntryId\" AS id, x.\"partnerId\", xa.\"controlAccountType\"::text"
                + " AS kind FROM \"JournalLine\" x JOIN \"Account\" xa ON xa.id = x.\"accountId\" WHERE"
                + " x.\"organizationId\" = ? AND x.\"partnerId\" = ANY (?) AND xa.\"controlAccountType\"::text IN ('ar', 'ap')";
        List<Object> p = new ArrayList<>(List.of(org, ic, org));
        p.addAll(rangeParams);
        // Revenue (sales) and expense (purchases) on those entries.
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT ic.\"partnerId\", ic.kind, l.\"accountId\","
                + " sum(l.\"baseDebit\") AS d, sum(l.\"baseCredit\") AS c FROM (" + icEntries + ") ic JOIN \"JournalLine\" l"
                + " ON l.\"journalEntryId\" = ic.id JOIN \"JournalEntry\" e ON e.id = ic.id WHERE l.\"organizationId\" = ?"
                + " AND e.status::text IN ('posted', 'reversed')" + range + " GROUP BY 1, 2, 3").params(p))) {
            AccountMeta a = meta.get((String) r.get("accountId"));
            if (a == null || !AccountClassification.isProfitAndLoss(a.classification())) {
                continue;
            }
            Trade tr = trade.computeIfAbsent(icPartners.get((String) r.get("partnerId")), k -> new Trade());
            BigDecimal dc = SqlRows.dec(r, "d").subtract(SqlRows.dec(r, "c"));
            if ("ar".equals(r.get("kind")) && isIncome(a)) {
                tr.revenue = tr.revenue.add(dc.negate());
            } else if ("ap".equals(r.get("kind")) && !isIncome(a)) {
                tr.expense = tr.expense.add(dc);
            }
        }
        // Seller's cost: cost-of-sales lines of the other entries of the same source documents (the stock issue of an
        // intercompany invoice).
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT ic.\"partnerId\", l.\"accountId\","
                + " sum(l.\"baseDebit\") AS d, sum(l.\"baseCredit\") AS c FROM (" + icEntries + ") ic JOIN \"JournalEntry\" s"
                + " ON s.id = ic.id JOIN \"JournalEntry\" e ON e.\"organizationId\" = s.\"organizationId\" AND"
                + " e.\"sourceId\" = s.\"sourceId\" AND e.id <> s.id JOIN \"JournalLine\" l ON l.\"journalEntryId\" = e.id"
                + " WHERE ic.kind = 'ar' AND s.\"sourceId\" IS NOT NULL AND l.\"organizationId\" = ? AND e.status::text IN"
                + " ('posted', 'reversed')" + range + " GROUP BY 1, 2").params(p))) {
            AccountMeta a = meta.get((String) r.get("accountId"));
            if (a == null) {
                a = accounts.meta(java.util.Set.of((String) r.get("accountId"))).get((String) r.get("accountId"));
            }
            if (a != null && "cogs".equals(a.reportSection())) {
                Trade tr = trade.computeIfAbsent(icPartners.get((String) r.get("partnerId")), k -> new Trade());
                tr.cost = tr.cost.add(SqlRows.dec(r, "d").subtract(SqlRows.dec(r, "c")));
            }
        }
        // Buyer's goods received from the counterpart on purchase orders, and the share of them still on hand.
        StringBuilder grRange = new StringBuilder();
        List<Object> gp = new ArrayList<>(List.of(org, ic));
        if (from != null) {
            grRange.append(" AND g.\"receivedAt\" >= ?");
            gp.add(Timestamp.from(from));
        }
        if (to != null) {
            grRange.append(" AND g.\"receivedAt\" <= ?");
            gp.add(Timestamp.from(to));
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT po.\"partnerId\", l.\"productId\", sum(l.quantity) AS q,"
                + " sum(l.quantity * l.\"unitCost\") AS v, (SELECT COALESCE(sum(si.quantity), 0) FROM \"StockItem\" si"
                + " WHERE si.\"organizationId\" = g.\"organizationId\" AND si.\"productId\" = l.\"productId\") AS onhand"
                + " FROM \"GoodsReceiptLine\" l JOIN \"GoodsReceiptNote\" g ON g.id = l.\"goodsReceiptId\""
                + " JOIN \"PurchaseOrder\" po ON po.id = g.\"purchaseOrderId\" WHERE g.\"organizationId\" = ?"
                + " AND po.\"partnerId\" = ANY (?) AND g.status::text = 'posted' AND g.\"deletedAt\" IS NULL"
                + " AND l.\"productId\" IS NOT NULL" + grRange + " GROUP BY po.\"partnerId\", l.\"productId\","
                + " g.\"organizationId\"").params(gp))) {
            BigDecimal q = SqlRows.dec(r, "q");
            BigDecimal v = SqlRows.dec(r, "v");
            BigDecimal share = q.signum() <= 0 ? BigDecimal.ZERO
                    : SqlRows.dec(r, "onhand").max(BigDecimal.ZERO).divide(q, MC).min(BigDecimal.ONE);
            Trade tr = trade.computeIfAbsent(icPartners.get((String) r.get("partnerId")), k -> new Trade());
            tr.received = tr.received.add(v);
            tr.onHand = tr.onHand.add(v.multiply(share, MC));
        }
    }

    /**
     * Cost side of intercompany sales (the revenue, receivable and payable are already eliminated). For each seller to
     * buyer pair the buyer's column takes: cost of sales less (intercompany revenue - purchase expense eliminated with
     * the payable - unrealised profit), and inventory less the unrealised profit, where unrealised profit = the
     * seller's margin on the pair x the goods received from it that the buyer still holds. Goods the buyer consumed or
     * resold count as realised. The two lines offset the buyer's eliminated payable, so the eliminations still net to
     * zero. Known limit: on-hand is today's quantity per product (no product mapping between companies). Returns the
     * unrealised profit in the group currency.
     */
    private BigDecimal costOfIntercompanySales(Group g, Map<String, Ledger> ledgers, Map<String, BigDecimal> rates) {
        BigDecimal total = BigDecimal.ZERO;
        for (Member seller : g.members()) {
            for (Member buyer : g.members()) {
                if (seller.organizationId().equals(buyer.organizationId())) {
                    continue;
                }
                Trade sold = ledgers.get(seller.organizationId()).trade().get(buyer.organizationId());
                Ledger bl = ledgers.get(buyer.organizationId());
                if (sold == null || sold.revenue.signum() <= 0 || bl.cogsAccountId() == null) {
                    continue;
                }
                Trade bought = bl.trade().getOrDefault(seller.organizationId(), new Trade());
                // Everything in the buyer's currency (rates translate each member into the group currency).
                BigDecimal toBuyer = rates.get(seller.organizationId()).divide(rates.get(buyer.organizationId()), MC);
                // Revenue the buyer expensed (services, mirrored bills) is already eliminated against that expense; the
                // rest is goods, and the seller's cost of sales belongs to it.
                BigDecimal goods = sold.revenue.multiply(toBuyer, MC).subtract(bought.expense);
                if (goods.signum() <= 0) {
                    continue;
                }
                BigDecimal margin = goods.subtract(sold.cost.multiply(toBuyer, MC)).divide(goods, MC)
                        .max(BigDecimal.ZERO);
                BigDecimal unrealised = bought.onHand.multiply(margin, MC).min(goods);
                BigDecimal cost = goods.subtract(unrealised);
                bl.eliminated().merge(bl.cogsAccountId(), cost, BigDecimal::add);
                bl.eliminated().merge(bl.stockAccountId(), unrealised, BigDecimal::add);
                total = total.add(unrealised.multiply(rates.get(buyer.organizationId()), MC));
            }
        }
        return total;
    }

    /** Average of the rates dated in the period; the closing rate when there are none. */
    private BigDecimal averageRate(String fromCcy, String toCcy, Instant from, Instant to) {
        if (fromCcy.equals(toCcy)) {
            return BigDecimal.ONE;
        }
        List<BigDecimal> rs = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"fromCode\", rate FROM \"CurrencyRate\" WHERE"
                + " ((\"fromCode\" = ? AND \"toCode\" = ?) OR (\"fromCode\" = ? AND \"toCode\" = ?)) AND \"asOf\" <= ?"
                + (from == null ? "" : " AND \"asOf\" >= ?")).params(from == null
                        ? List.of(fromCcy, toCcy, toCcy, fromCcy, Timestamp.from(to))
                        : List.of(fromCcy, toCcy, toCcy, fromCcy, Timestamp.from(to), Timestamp.from(from))))) {
            BigDecimal v = SqlRows.dec(r, "rate");
            if (v.signum() == 0) {
                continue;
            }
            rs.add(fromCcy.equals(r.get("fromCode")) ? v : BigDecimal.ONE.divide(v, MC));
        }
        if (rs.isEmpty()) {
            return currencies.getRate(fromCcy, toCcy, to);
        }
        return rs.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(rs.size()), MC);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    static boolean isIncome(AccountMeta a) {
        String s = String.valueOf(a.reportSection());
        if ("revenue".equals(s) || "other_income".equals(s)) {
            return true;
        }
        if ("contra_revenue".equals(s) || "cogs".equals(s) || "operating_expense".equals(s)
                || "other_expense".equals(s)) {
            return false;
        }
        return "revenue".equals(a.classification());
    }

    private static BigDecimal shown(String kind, Row r, BigDecimal raw) {
        return "pl".equals(kind) ? AccountClassification.displayBalance(raw, r.normalBalance) : raw;
    }

    private static void accumulate(String kind, Row r, String col, BigDecimal v, Map<String, BigDecimal> income,
            Map<String, BigDecimal> expense, Map<String, BigDecimal> net) {
        if ("pl".equals(kind)) {
            (r.income ? income : expense).merge(col, v, BigDecimal::add);
        } else {
            net.merge(col, v, BigDecimal::add);
        }
    }

    private static Map<String, BigDecimal> zero(List<String> columns) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        columns.forEach(c -> m.put(c, BigDecimal.ZERO));
        return m;
    }

    private static Map<String, Object> columnMap(List<String> columns, Map<String, BigDecimal> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        columns.forEach(c -> out.put(c, money(values.get(c))));
        return out;
    }

    static String money(BigDecimal v) {
        BigDecimal r = v.setScale(2, RoundingMode.HALF_UP);
        return (r.signum() == 0 ? BigDecimal.ZERO.setScale(2) : r).toPlainString();
    }
}
