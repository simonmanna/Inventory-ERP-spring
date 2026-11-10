package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.application.PostingService.plain;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Independent POS → GL reconciliation (C-20) and inventory/COGS lag monitor (C-08) (port of
 * {@code pos-gl-reconciliation.service.ts}). Re-derives both sides from independent sources: POS invoices (+ sales
 * invoices) and refund line fractions vs ledger lines bucketed by report section; stock-ledger issue value vs GL
 * COGS, plus the async stock-posting backlog. Every query is explicitly org-scoped.
 */
@Service
public class PosGlReconciliationService {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final JdbcClient jdbc;
    private final AccountResolverService accounts;

    public PosGlReconciliationService(JdbcClient jdbc, AccountResolverService accounts) {
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

    private static boolean within(BigDecimal v) {
        return v.abs().compareTo(TOLERANCE) <= 0;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> reconcile(String fromRaw, String toRaw) {
        String org = TenantContext.organizationId();
        Instant from = fromRaw != null && !fromRaw.isEmpty() ? LedgerQueries.date(fromRaw, AccountingDay.zone(jdbc)) : Instant.now().minus(Duration.ofDays(30));
        Instant to = toRaw != null && !toRaw.isEmpty() ? LedgerQueries.date(toRaw, AccountingDay.zone(jdbc)) : Instant.now();
        if (to.isBefore(from)) {
            throw new IllegalStateException("`to` must be after `from`");
        }
        Timestamp f = FiscalPeriodControl.ts(from);
        Timestamp t = FiscalPeriodControl.ts(to);

        // POS side: POS invoices (by issueDate) + back-office sales invoices; refunds by createdAt.
        Map<String, Object> pos = SqlRows.one(jdbc.sql("SELECT coalesce(sum(subtotal), 0) AS subtotal,"
                + " coalesce(sum(\"taxAmount\"), 0) AS tax, coalesce(sum(\"discountTotal\"), 0) AS discount, count(*) AS n"
                + " FROM \"Invoice\" WHERE \"organizationId\" = ? AND \"issueDate\" >= ? AND \"issueDate\" <= ?"
                + " AND status::text IN ('posted', 'paid', 'refunded')").params(org, f, t));
        Map<String, Object> doc = SqlRows.one(jdbc.sql("SELECT coalesce(sum(subtotal), 0) AS subtotal,"
                + " coalesce(sum(\"taxAmount\"), 0) AS tax, coalesce(sum(\"discountTotal\"), 0) AS discount, count(*) AS n"
                + " FROM \"Document\" WHERE \"organizationId\" = ? AND \"documentType\"::text = 'sales_invoice'"
                + " AND \"issueDate\" >= ? AND \"issueDate\" <= ? AND status::text IN ('posted', 'paid')").params(org, f, t));
        Map<String, Object> backlog = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT status, count(*) AS n FROM \"StockPostingJob\""
                + " WHERE \"organizationId\" = ? AND \"createdAt\" >= ? AND \"createdAt\" <= ? GROUP BY status").params(org, f, t))) {
            backlog.put((String) r.get("status"), ((Number) r.get("n")).longValue());
        }
        BigDecimal stockIssues = jdbc.sql("SELECT coalesce(sum(\"totalValue\"), 0) FROM \"InventoryLedger\""
                + " WHERE \"organizationId\" = ? AND \"createdAt\" >= ? AND \"createdAt\" <= ?"
                + " AND \"referenceType\" IN ('pos_invoice', 'pos_invoice_extra', 'menu_recipe') AND \"quantityChange\" < 0")
                .params(org, f, t).query(BigDecimal.class).single();
        Map<String, Object> refunds = SqlRows.one(jdbc.sql("SELECT count(*) AS n, coalesce(sum(amount), 0) AS total"
                + " FROM \"PosRefund\" WHERE \"organizationId\" = ? AND \"createdAt\" >= ? AND \"createdAt\" <= ?").params(org, f, t));
        // R-1: split refunds into revenue vs tax from the immutable PosRefund.items line fractions.
        Map<String, Object> refundSplit = SqlRows.one(jdbc.sql("SELECT coalesce(sum(coalesce((it->>'subtotal')::numeric, 0)), 0) AS revenue,"
                + " coalesce(sum(coalesce((it->>'taxAmount')::numeric, 0)), 0) AS tax FROM \"PosRefund\" r,"
                + " jsonb_array_elements(CASE WHEN jsonb_typeof(r.items) = 'array' THEN r.items ELSE '[]'::jsonb END) it"
                + " WHERE r.\"organizationId\" = ? AND r.\"createdAt\" >= ? AND r.\"createdAt\" <= ?").params(org, f, t));
        // Refund restocks reverse COGS at the issue cost; the stock side must net them.
        BigDecimal stockReturns = jdbc.sql("SELECT coalesce(sum(\"totalValue\"), 0) FROM \"InventoryLedger\""
                + " WHERE \"organizationId\" = ? AND \"createdAt\" >= ? AND \"createdAt\" <= ? AND \"referenceType\" = 'pos_refund'"
                + " AND \"quantityChange\" > 0").params(org, f, t).query(BigDecimal.class).single();
        // Output-VAT accounts are the ones a Tax row posts to.
        Set<String> taxAccountIds = jdbc.sql("SELECT \"accountId\" FROM \"Tax\" WHERE \"organizationId\" = ? AND \"accountId\" IS NOT NULL")
                .param(org).query(String.class).list().stream().collect(Collectors.toSet());

        Map<String, BigDecimal[]> gl = new LinkedHashMap<>();
        for (Map<String, Object> g : grouped(org, f, t)) {
            gl.put((String) g.get("accountId"), new BigDecimal[] {SqlRows.dec(g, "d"), SqlRows.dec(g, "c")});
        }
        Set<String> metaIds = new LinkedHashSet<>(gl.keySet());
        metaIds.addAll(taxAccountIds);
        Map<String, Section> sections = new HashMap<>();
        accounts.meta(metaIds).forEach((id, m) -> sections.put(id, new Section(m.reportSection(), m.normalBalance())));

        return compute(new Inputs(from, to,
                SqlRows.dec(pos, "subtotal").add(SqlRows.dec(doc, "subtotal")),
                SqlRows.dec(pos, "tax").add(SqlRows.dec(doc, "tax")),
                SqlRows.dec(pos, "discount").add(SqlRows.dec(doc, "discount")),
                ((Number) pos.get("n")).longValue() + ((Number) doc.get("n")).longValue(),
                ((Number) refunds.get("n")).longValue(), SqlRows.dec(refunds, "total"),
                SqlRows.dec(refundSplit, "revenue"), SqlRows.dec(refundSplit, "tax"),
                gl, sections, taxAccountIds, stockIssues, stockReturns, backlog));
    }

    /** An account's report section and normal balance. */
    public record Section(String reportSection, String normalBalance) {}

    /**
     * Everything the reconciliation reads, already aggregated for the window.
     *
     * @param gl accountId to [sum baseDebit, sum baseCredit] of balance-affecting lines in the window
     */
    public record Inputs(Instant from, Instant to, BigDecimal posSubtotal, BigDecimal posTax, BigDecimal posDiscount,
            long invoiceCount, long refundCount, BigDecimal refundTotal, BigDecimal refundedRevenue, BigDecimal refundedTax,
            Map<String, BigDecimal[]> gl, Map<String, Section> sections, Set<String> taxAccountIds, BigDecimal stockIssues,
            BigDecimal stockReturns, Map<String, Object> backlog) {}

    /** The reconciliation math (pure). */
    public static Map<String, Object> compute(Inputs in) {
        Map<String, BigDecimal> glSections = sectionTotals(in);
        BigDecimal glRevenue = glSections.getOrDefault("revenue", BigDecimal.ZERO);
        BigDecimal glOtherIncome = glSections.getOrDefault("other_income", BigDecimal.ZERO);
        BigDecimal glContraRevenue = glSections.getOrDefault("contra_revenue", BigDecimal.ZERO);
        BigDecimal glTax = glSections.getOrDefault("tax", BigDecimal.ZERO).add(taxAccountTotals(in));

        // Invoice.subtotal is already net of discounts (C-09 net method): expected = subtotal - refunded revenue.
        BigDecimal expectedRevenue = in.posSubtotal().subtract(in.refundedRevenue());
        BigDecimal actualRevenue = glRevenue.add(glOtherIncome).subtract(glContraRevenue);
        BigDecimal revenueVariance = actualRevenue.subtract(expectedRevenue);
        BigDecimal expectedTax = in.posTax().subtract(in.refundedTax());
        BigDecimal taxVariance = glTax.subtract(expectedTax);

        BigDecimal cogsGl = glSections.getOrDefault("cogs", BigDecimal.ZERO);
        BigDecimal stockCogsValue = in.stockIssues().subtract(in.stockReturns());
        long unpostedJobs = count(in.backlog(), "pending") + count(in.backlog(), "processing") + count(in.backlog(), "failed");
        BigDecimal cogsVariance = cogsGl.subtract(stockCogsValue);

        return body("window", body("from", in.from(), "to", in.to()),
                "pos", body("invoiceCount", in.invoiceCount(), "grossSubtotal", plain(in.posSubtotal()),
                        "discounts", plain(in.posDiscount()), "tax", plain(in.posTax()),
                        "refundedRevenue", plain(in.refundedRevenue()), "refundedTax", plain(in.refundedTax()),
                        "refundedTotal", plain(in.refundTotal()), "expectedNetRevenue", plain(expectedRevenue),
                        "expectedNetTax", plain(expectedTax), "refundCount", in.refundCount()),
                "gl", body("revenue", plain(glRevenue), "otherIncome", plain(glOtherIncome),
                        "contraRevenue", plain(glContraRevenue), "tax", plain(glTax),
                        "actualNetRevenue", plain(actualRevenue), "cogs", plain(cogsGl)),
                "variance", body("revenue", plain(revenueVariance), "revenueBalanced", within(revenueVariance),
                        "tax", plain(taxVariance), "taxBalanced", within(taxVariance)),
                "inventory", body("stockCogsValue", plain(stockCogsValue), "glCogs", plain(cogsGl),
                        "cogsVariance", plain(cogsVariance), "cogsBalanced", within(cogsVariance),
                        "jobCounts", in.backlog(), "unpostedJobs", unpostedJobs,
                        "note", unpostedJobs > 0
                                ? unpostedJobs + " stock-posting job(s) in this window have not posted COGS yet. "
                                        + "Revenue is booked ahead of COGS; drain the queue (Posting Monitor) before closing the period."
                                : "All stock-posting jobs in this window have posted."),
                "balanced", within(revenueVariance) && within(taxVariance));
    }

    private static long count(Map<String, Object> backlog, String status) {
        Object n = backlog.get(status);
        return n == null ? 0 : ((Number) n).longValue();
    }

    /** Credit-positive movement on output-VAT accounts not already in the {@code tax} report section. */
    private static BigDecimal taxAccountTotals(Inputs in) {
        BigDecimal total = BigDecimal.ZERO;
        for (String accountId : in.taxAccountIds()) {
            BigDecimal[] g = in.gl().get(accountId);
            Section m = in.sections().get(accountId);
            if (g == null || (m != null && "tax".equals(m.reportSection()))) {
                continue;
            }
            total = total.add(g[1].subtract(g[0]));
        }
        return total;
    }

    /** Window movement bucketed by report section, display-signed by each account's normal balance. */
    private static Map<String, BigDecimal> sectionTotals(Inputs in) {
        Map<String, BigDecimal> out = new HashMap<>();
        in.gl().forEach((accountId, g) -> {
            Section acct = in.sections().get(accountId);
            if (acct == null || acct.reportSection() == null) {
                return;
            }
            BigDecimal value = "credit".equals(acct.normalBalance()) ? g[1].subtract(g[0]) : g[0].subtract(g[1]);
            out.merge(acct.reportSection(), value, BigDecimal::add);
        });
        return out;
    }

    private List<Map<String, Object>> grouped(String org, Timestamp f, Timestamp t) {
        return SqlRows.list(jdbc.sql("SELECT l.\"accountId\", coalesce(sum(l.\"baseDebit\"), 0) AS d,"
                + " coalesce(sum(l.\"baseCredit\"), 0) AS c FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                + " WHERE l.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed')"
                + " AND e.\"postingDate\" >= ? AND e.\"postingDate\" <= ? GROUP BY l.\"accountId\"").params(org, f, t));
    }
}
