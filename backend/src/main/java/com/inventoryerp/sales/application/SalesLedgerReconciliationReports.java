package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesReportKit.drill;
import static com.inventoryerp.sales.application.SalesReportKit.money;
import static com.inventoryerp.sales.application.SalesReportKit.route;
import static com.inventoryerp.sales.application.SalesReportKit.table;
import static com.inventoryerp.sales.application.SalesReportSet.def;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.sales.application.SalesReportKit.Def;
import com.inventoryerp.sales.application.SalesReportKit.Filter;
import com.inventoryerp.sales.application.SalesReportKit.Report;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Sales against the general ledger (ADR-S094 C7, §9.2): every measure of the report center reconciled to the journals
 * its documents posted, per document, with a tolerance of 0.01 in the functional currency.
 *
 * <ul>
 *   <li>R5a: each posted sales invoice and credit note — its revenue lines (product lines, and lines on a revenue
 *       account) against the revenue its own live journal posted; a legacy branch cash sale's invoice against the
 *       revenue accrued at showroom confirm.
 *   <li>R5b: each POS invoice and each POS refund or settled void against its {@code pos_invoice} /
 *       {@code pos_refund} journal.
 *   <li>R5c: the same documents' tax against the movement of the tax accounts in those journals.
 *   <li>R5d: each customer receipt, customer refund and deposit received against the tender account of its journal.
 *   <li>R5e: each sales stock issue or return (invoice, cash sale, delivery trip, return, POS) against the cost of
 *       sales its journal posted.
 *   <li>R5f: billing (M3) bridged to recognised revenue (M5): accruals at confirm, deferrals and releases,
 *       period-end accruals of delivered, not invoiced goods, cancellations and posting-date shifts.
 *   <li>R5g: VAT charged on advances (down-payment invoices) of every completed order nets to zero after the
 *       deductions and credits (V1: VAT is never counted twice).
 * </ul>
 *
 * <p>The checks read the ledger as it is; they never write. Down-payment invoices post to the deposit account, not to
 * revenue, so they count as advances, not sales.
 */
@Component
class SalesLedgerReconciliationReports implements SalesReportSet {

    static final String CAT = "reconciliation";

    static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    static final List<String> CHECKS = List.of("r5a", "r5b", "r5c", "r5d", "r5e", "r5g");

    /** Journal source of the period-end accrual of delivered, not invoiced sales (C7b), and of its reversal. */
    static final String UNBILLED_ACCRUAL = "sales_unbilled_accrual";

    static final String UNBILLED_ACCRUAL_REVERSAL = "sales_unbilled_accrual_reversal";

    private static final List<String> PERIOD = List.of("period");

    /** Stock ledger references of sales issues and their returns (M2); each posts its own journal. */
    static final List<String> COST_SOURCES = List.of("sales_invoice", "sales_invoice_cancel", "credit_note",
            "credit_note_cancel", "cash_sale", "cash_sale_void", "fleet_trip", "fleet_trip_return", "sales_return",
            "pos_invoice", "pos_order", "menu_recipe", "pos_invoice_combo", "pos_invoice_extra", "pos_refund");

    /** Journal sources of recognised revenue (M5). */
    static final List<String> REVENUE_SOURCES = List.of("invoice", "credit_note", "pos_invoice", "pos_refund",
            "cash_sale", "cash_sale_void", "sales_revenue_deferral", "sales_revenue_release",
            UNBILLED_ACCRUAL, UNBILLED_ACCRUAL_REVERSAL);

    private static final String ACCOUNTS = """
            acc AS (SELECT a.id, c.classification FROM "Account" a LEFT JOIN "AccountCategory" c ON c.id = a."categoryId"
                     WHERE a."organizationId" = ?)""";

    private final SalesDb db;

    SalesLedgerReconciliationReports(SalesDb db) {
        this.db = db;
    }

    @Override
    public List<Def> definitions() {
        List<Def> d = new ArrayList<>();
        d.add(def("ledger-reconciliation", CAT, "Sales Ledger Reconciliation", "Report-center measures against the"
                + " journals they posted: invoice and credit-note revenue, POS revenue, output VAT, receipts, cost of"
                + " sales, billing to recognised revenue and VAT on advances. Each check lists the documents that"
                + " differ.", Permissions.REPORT__ACCOUNTING, PERIOD, this::reconciliation));
        d.add(def("revenue-bridge", CAT, "Billing to Recognised Revenue", "From invoiced sales (POS included) to the"
                + " revenue in the ledger: revenue accrued at confirm, deferred and released, accrued at period end,"
                + " cancelled documents and posting-date shifts.", Permissions.REPORT__ACCOUNTING, PERIOD,
                this::bridgeReport));
        d.add(def("reconciliation-exceptions", CAT, "Reconciliation Differences", "Every document whose report"
                + " amount and ledger amount differ by more than 0.01, for one check or all of them.",
                Permissions.REPORT__ACCOUNTING, List.of("period", "status", "limit"), this::exceptions));
        return d;
    }

    // ------------------------------------------------------------------ data

    private static LocalDate end(Filter f) {
        return f.to().plusDays(1);
    }

    /** Posted sales invoices and credit notes dated in the period, with report and ledger revenue and tax. */
    List<Map<String, Object>> documents(Filter f) {
        String sql = "WITH " + ACCOUNTS + """
                ,
                     tax AS (SELECT DISTINCT "accountId" AS id FROM "Tax" WHERE "organizationId" = ? AND "accountId" IS NOT NULL),
                     docs AS (SELECT d.id, d."documentNumber" AS number, d."documentType"::text AS type,
                                     d."issueDate"::date AS date, d."sourceKind" AS kind,
                                     d.data ->> 'revenueAccruedBy' AS accrual,
                                     CASE WHEN d."documentType" = 'credit_note' THEN -1 ELSE 1 END AS sg,
                                     d."exchangeRate" AS rate
                                FROM "Document" d
                               WHERE d."organizationId" = ? AND d."documentType" IN ('sales_invoice', 'credit_note')
                                 AND d.status IN ('posted', 'paid') AND d."issueDate" >= ? AND d."issueDate" < ?),
                     rep AS (SELECT x.id,
                                    sum(x.sg * l.subtotal * x.rate)
                                        FILTER (WHERE l."accountId" IS NULL OR a.classification = 'revenue') AS revenue,
                                    sum(x.sg * COALESCE(l."taxAmount", 0) * x.rate) AS tax
                               FROM docs x JOIN "DocumentLine" l ON l."documentId" = x.id
                               LEFT JOIN acc a ON a.id = l."accountId"
                              GROUP BY 1),
                     ent AS (SELECT x.id AS doc, e.id AS entry, false AS accrual, e."postingDate"
                               FROM docs x JOIN "JournalEntry" e ON e."sourceType" IN ('invoice', 'credit_note')
                                AND e."sourceId" = x.id AND e."reversedEntryId" IS NULL
                             UNION ALL
                             SELECT x.id, e.id, true, e."postingDate" FROM docs x JOIN "JournalEntry" e ON e.id = x.accrual),
                     gl AS (SELECT n.doc,
                                   sum(jl."baseCredit" - jl."baseDebit")
                                       FILTER (WHERE a.classification = 'revenue' AND NOT n.accrual) AS revenue,
                                   sum(jl."baseCredit" - jl."baseDebit")
                                       FILTER (WHERE a.classification = 'revenue' AND n.accrual) AS accrued,
                                   sum(jl."baseCredit" - jl."baseDebit")
                                       FILTER (WHERE a.classification = 'revenue' AND NOT n.accrual
                                                 AND (n."postingDate" < ? OR n."postingDate" >= ?)) AS outside,
                                   sum(jl."baseCredit" - jl."baseDebit") FILTER (WHERE jl."accountId" IN (SELECT id FROM tax)) AS tax
                              FROM ent n JOIN "JournalLine" jl ON jl."journalEntryId" = n.entry
                              LEFT JOIN acc a ON a.id = jl."accountId"
                             GROUP BY 1)
                SELECT x.id, x.number, x.type, x.date, x.kind, COALESCE(r.revenue, 0) AS "reportRevenue",
                       COALESCE(r.tax, 0) AS "reportTax", COALESCE(g.revenue, 0) + COALESCE(g.accrued, 0) AS "ledgerRevenue",
                       COALESCE(g.accrued, 0) AS "accruedRevenue", COALESCE(g.outside, 0) AS "outsideRevenue",
                       COALESCE(g.tax, 0) AS "ledgerTax"
                  FROM docs x LEFT JOIN rep r ON r.id = x.id LEFT JOIN gl g ON g.doc = x.id
                 ORDER BY x.date, x.number
                """;
        return db.rows(SalesReportFacts.atCarryingRate(sql), List.of(org(), org(), org(), f.from(), end(f), f.from(),
                end(f)));
    }

    /** POS invoices dated in the period and POS refunds / settled voids made in it, with report and ledger amounts. */
    List<Map<String, Object>> pos(Filter f) {
        String sql = "WITH " + ACCOUNTS + """
                ,
                     tax AS (SELECT "accountId" AS id FROM "Tax" WHERE "organizationId" = ? AND "accountId" IS NOT NULL
                             UNION
                             SELECT "taxAccountId" FROM "InvoiceItem" WHERE "organizationId" = ? AND "taxAccountId" IS NOT NULL),
                     pos AS (SELECT v.id, v."invoiceNumber" AS number, 'pos_invoice' AS type, v."issueDate"::date AS date,
                                    COALESCE(sum(i.subtotal), 0) * v."exchangeRate" AS revenue,
                                    COALESCE(sum(COALESCE(i."taxAmount", 0)), 0) * v."exchangeRate" AS tax
                               FROM "Invoice" v LEFT JOIN "InvoiceItem" i ON i."invoiceId" = v.id
                              WHERE v."organizationId" = ? AND v.status::text IN ('posted', 'paid', 'refunded')
                                AND v."issueDate" >= ? AND v."issueDate" < ?
                              GROUP BY v.id
                             UNION ALL
                             SELECT r.id, v."invoiceNumber", 'pos_refund', r."createdAt"::date,
                                    -COALESCE((SELECT sum(COALESCE((e ->> 'subtotal')::numeric, 0))
                                                 FROM jsonb_array_elements(r.items::jsonb) e), 0) * rr.rate,
                                    -COALESCE((SELECT sum(COALESCE((e ->> 'taxAmount')::numeric, 0))
                                                 FROM jsonb_array_elements(r.items::jsonb) e), 0) * rr.rate
                               FROM "PosRefund" r JOIN "Invoice" v ON v.id = r."invoiceId"
                              CROSS JOIN LATERAL (SELECT {refundRate} AS rate) rr
                              WHERE r."organizationId" = ? AND r."createdAt" >= ? AND r."createdAt" < ?),
                     gl AS (SELECT x.id,
                                   sum(jl."baseCredit" - jl."baseDebit") FILTER (WHERE a.classification = 'revenue') AS revenue,
                                   sum(jl."baseCredit" - jl."baseDebit") FILTER (WHERE a.classification = 'revenue'
                                       AND (e."postingDate" < ? OR e."postingDate" >= ?)) AS outside,
                                   sum(jl."baseCredit" - jl."baseDebit") FILTER (WHERE jl."accountId" IN (SELECT id FROM tax)) AS tax
                              FROM pos x JOIN "JournalEntry" e ON e."sourceType" = x.type AND e."sourceId" = x.id
                               AND e."reversedEntryId" IS NULL
                              JOIN "JournalLine" jl ON jl."journalEntryId" = e.id
                              LEFT JOIN acc a ON a.id = jl."accountId"
                             GROUP BY 1)
                SELECT x.id, x.number, x.type, x.date, 'pos' AS kind, x.revenue AS "reportRevenue", x.tax AS "reportTax",
                       COALESCE(g.revenue, 0) AS "ledgerRevenue", 0 AS "accruedRevenue",
                       COALESCE(g.outside, 0) AS "outsideRevenue", COALESCE(g.tax, 0) AS "ledgerTax"
                  FROM pos x LEFT JOIN gl g ON g.id = x.id
                 ORDER BY x.date, x.number
                """;
        return db.rows(SalesReportFacts.atCarryingRate(sql.replace("{refundRate}", SalesReportFacts.refundRate("r", "v")), "v",
                SalesReportFacts.posRate("v")), List.of(org(), org(), org(),
                org(), f.from(), end(f), org(), f.from(), end(f), f.from(), end(f)));
    }

    /** Customer receipts, customer refunds and deposits received in the period, with their tender-account movement. */
    List<Map<String, Object>> receipts(Filter f) {
        String sql = """
                WITH r AS (SELECT p.id, p."paymentNumber" AS number,
                                  CASE WHEN p.direction::text = 'inbound' THEN 'receipt' ELSE 'refund' END AS type,
                                  p."paymentDate"::date AS date, p."paymentMethod"::text AS method,
                                  CASE WHEN p.direction::text = 'inbound' THEN 1 ELSE -1 END
                                      * (p.amount - COALESCE(p."withholdingAmount", 0)) * COALESCE(p."exchangeRate", 1) AS amount,
                                  p."accountId", p."journalEntryId" AS entry
                             FROM "Payment" p
                            WHERE p."organizationId" = ? AND p.status::text = 'posted'
                              AND (p.direction::text = 'inbound' OR p."refundOfId" IS NOT NULL)
                              AND p."paymentDate" >= ? AND p."paymentDate" < ?
                           UNION ALL
                           SELECT c.id, c.number, 'deposit', c."receivedDate", c."paymentMethod"::text,
                                  c.amount * COALESCE(c."exchangeRate", 1), c."accountId", c."journalEntryId"
                             FROM "CustomerDeposit" c
                            WHERE c."organizationId" = ? AND c.source::text IN ('receipt', 'cash_sale')
                              AND c.status::text <> 'void' AND c."receivedDate" >= ? AND c."receivedDate" < ?)
                SELECT r.id, r.number, r.type, r.date, r.method AS kind, r.amount AS report,
                       COALESCE((SELECT sum(jl."baseDebit" - jl."baseCredit") FROM "JournalLine" jl
                                  WHERE jl."journalEntryId" = r.entry AND jl."accountId" = r."accountId"), 0) AS ledger
                  FROM r ORDER BY r.date, r.number
                """;
        return db.rows(sql, List.of(org(), f.from(), end(f), org(), f.from(), end(f)));
    }

    /** Sales stock issues and returns with a movement in the period: ledger value against posted cost of sales. */
    List<Map<String, Object>> costs(Filter f) {
        String in = SalesDb.marks(COST_SOURCES.size());
        String sql = "WITH " + ACCOUNTS + """
                ,
                     refs AS (SELECT DISTINCT il."referenceType" AS type, il."referenceId" AS id FROM "InventoryLedger" il
                               WHERE il."organizationId" = ? AND il."referenceType" IN (%1$s)
                                 AND il."createdAt" >= ? AND il."createdAt" < ?),
                     m AS (SELECT r.type, r.id, min(il."createdAt")::date AS date,
                                  sum(CASE WHEN il."quantityChange" < 0 THEN il."totalValue" ELSE -il."totalValue" END) AS cost
                             FROM refs r JOIN "InventoryLedger" il ON il."referenceType" = r.type AND il."referenceId" = r.id
                            GROUP BY 1, 2),
                     g AS (SELECT r.type, r.id,
                                  sum(jl."baseDebit" - jl."baseCredit") FILTER (WHERE a.classification = 'expense') AS cogs
                             FROM refs r JOIN "JournalEntry" e ON e."sourceType" = r.type AND e."sourceId" = r.id
                             JOIN "JournalLine" jl ON jl."journalEntryId" = e.id
                             LEFT JOIN acc a ON a.id = jl."accountId"
                            GROUP BY 1, 2)
                SELECT m.id, m.type || ' ' || left(m.id, 8) AS number, m.type, m.date, m.type AS kind, m.cost AS report,
                       COALESCE(g.cogs, 0) AS ledger
                  FROM m LEFT JOIN g ON g.type = m.type AND g.id = m.id
                 ORDER BY m.date, m.type, m.id
                """.formatted(in);
        List<Object> p = new ArrayList<>(List.of(org(), org()));
        p.addAll(COST_SOURCES);
        p.add(f.from());
        p.add(end(f));
        return db.rows(sql, p);
    }

    /**
     * Completed order-type sales (done in the period): VAT on their advance lines (lines on a liability account — the
     * customer-deposit account of a down-payment invoice, its deductions and credits) must net to zero.
     */
    List<Map<String, Object>> advanceVat(Filter f) {
        String sql = "WITH " + ACCOUNTS + """
                ,
                     sales AS (SELECT so.id, so.number, so."doneAt"::date AS date FROM "SalesOrder" so
                                WHERE so."organizationId" = ? AND so.status = 'done' AND so."saleType" = 'order'
                                  AND so."doneAt" >= ? AND so."doneAt" < ?),
                     docs AS (SELECT s.id AS sale, d.id, CASE WHEN d."documentType" = 'credit_note' THEN -1 ELSE 1 END AS sg,
                                     d."exchangeRate" AS rate
                                FROM sales s JOIN "Document" d ON d."sourceType" = 'sales_order' AND d."sourceId" = s.id
                               WHERE d."documentType" IN ('sales_invoice', 'credit_note') AND d.status IN ('posted', 'paid')
                              UNION
                              SELECT s.id, c.id, -1, c."exchangeRate"
                                FROM sales s JOIN "Document" d ON d."sourceType" = 'sales_order' AND d."sourceId" = s.id
                                JOIN "Document" c ON c."reversedDocumentId" = d.id AND c."documentType" = 'credit_note'
                                 AND c.status IN ('posted', 'paid'))
                SELECT s.id, s.number, 'sale' AS type, s.date, 'order' AS kind,
                       COALESCE(sum(x.sg * l."taxAmount" * x.rate) FILTER (WHERE x.sg * l."taxAmount" > 0), 0) AS report,
                       COALESCE(-sum(x.sg * l."taxAmount" * x.rate) FILTER (WHERE x.sg * l."taxAmount" < 0), 0) AS ledger
                  FROM sales s
                  LEFT JOIN docs x ON x.sale = s.id
                  LEFT JOIN "DocumentLine" l ON l."documentId" = x.id
                   AND l."accountId" IN (SELECT id FROM acc WHERE classification = 'liability')
                 GROUP BY 1, 2, 3, 4
                 ORDER BY s.date, s.number
                """;
        return db.rows(SalesReportFacts.atCarryingRate(SalesReportFacts.atCarryingRate(sql), "c",
                SalesReportFacts.docRate("c")),
                List.of(org(), org(), f.from(), end(f)));
    }

    /** Revenue of the sales sources' entries posted in the period, by bridge group (R5f). */
    Map<String, BigDecimal> recognised(Filter f) {
        String in = SalesDb.marks(REVENUE_SOURCES.size());
        String sql = "WITH " + ACCOUNTS + """
                ,
                     e AS (SELECT e.id, e."sourceType" AS st, e."sourceId" AS sid, e."reversedEntryId" AS rev,
                                  o."sourceType" AS ost
                             FROM "JournalEntry" e LEFT JOIN "JournalEntry" o ON o.id = e."reversalOfId"
                            WHERE e."organizationId" = ? AND e."postingDate" >= ? AND e."postingDate" < ?
                              AND (e."sourceType" IN (%1$s) OR (e."sourceType" = 'reversal' AND o."sourceType" IN (%1$s)))),
                     cls AS (SELECT e.id, CASE
                               WHEN e.st IN ('invoice', 'credit_note') AND e.rev IS NULL AND d.id IS NOT NULL
                                    THEN CASE WHEN d."issueDate" >= ? AND d."issueDate" < ? THEN 'doc_in' ELSE 'doc_out' END
                               WHEN e.st = 'pos_invoice' AND e.rev IS NULL AND v.id IS NOT NULL
                                    THEN CASE WHEN v."issueDate" >= ? AND v."issueDate" < ? THEN 'doc_in' ELSE 'doc_out' END
                               WHEN e.st = 'pos_refund' AND e.rev IS NULL AND r.id IS NOT NULL
                                    THEN CASE WHEN r."createdAt" >= ? AND r."createdAt" < ? THEN 'doc_in' ELSE 'doc_out' END
                               WHEN COALESCE(e.ost, e.st) IN ('invoice', 'credit_note', 'pos_invoice', 'pos_refund') THEN 'other'
                               WHEN COALESCE(e.ost, e.st) IN ('cash_sale', 'cash_sale_void') THEN 'confirm_accrual'
                               WHEN COALESCE(e.ost, e.st) = 'sales_revenue_deferral' THEN 'deferral'
                               WHEN COALESCE(e.ost, e.st) = 'sales_revenue_release' THEN 'release'
                               ELSE 'unbilled_accrual' END AS grp
                              FROM e
                              LEFT JOIN "Document" d ON e.st IN ('invoice', 'credit_note') AND d.id = e.sid
                               AND d.status IN ('posted', 'paid') AND d."documentType" IN ('sales_invoice', 'credit_note')
                              LEFT JOIN "Invoice" v ON e.st = 'pos_invoice' AND v.id = e.sid
                               AND v.status::text IN ('posted', 'paid', 'refunded')
                              LEFT JOIN "PosRefund" r ON e.st = 'pos_refund' AND r.id = e.sid)
                SELECT c.grp, sum(jl."baseCredit" - jl."baseDebit") AS revenue
                  FROM cls c JOIN "JournalLine" jl ON jl."journalEntryId" = c.id
                  JOIN acc a ON a.id = jl."accountId" AND a.classification = 'revenue'
                 GROUP BY 1
                """.formatted(in);
        List<Object> p = new ArrayList<>(List.of(org(), org(), f.from(), end(f)));
        p.addAll(REVENUE_SOURCES);
        p.addAll(REVENUE_SOURCES);
        for (int i = 0; i < 3; i++) {
            p.add(f.from());
            p.add(end(f));
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map<String, Object> r : db.rows(sql, p)) {
            out.put(str(r.get("grp")), dec(r.get("revenue")));
        }
        return out;
    }

    // ------------------------------------------------------------------ checks

    /** One reconciliation check: its document rows reduced to report / ledger / difference. */
    record Check(String key, String title, String basis, List<Map<String, Object>> rows) {

        BigDecimal report() {
            return sum("report");
        }

        BigDecimal ledger() {
            return sum("ledger");
        }

        List<Map<String, Object>> exceptions() {
            return rows.stream().filter(r -> dec(r.get("report")).subtract(dec(r.get("ledger"))).abs()
                    .compareTo(TOLERANCE) > 0).toList();
        }

        private BigDecimal sum(String k) {
            return rows.stream().map(r -> dec(r.get(k))).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private static List<Map<String, Object>> project(List<Map<String, Object>> rows, String report, String ledger) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("report", dec(r.get(report)));
            m.put("ledger", dec(r.get(ledger)));
            out.add(m);
        }
        return out;
    }

    /** Every per-document check of the period (R5a–R5e, R5g). */
    Map<String, Check> checks(Filter f) {
        List<Map<String, Object>> docs = documents(f);
        List<Map<String, Object>> pos = pos(f);
        List<Map<String, Object>> tax = new ArrayList<>(project(docs, "reportTax", "ledgerTax"));
        tax.addAll(project(pos, "reportTax", "ledgerTax"));
        Map<String, Check> out = new LinkedHashMap<>();
        out.put("r5a", new Check("r5a", "Invoice and credit-note revenue", "Revenue lines of posted sales invoices and"
                + " credit notes = revenue their journals posted (legacy cash sales: the accrual at confirm)",
                project(docs, "reportRevenue", "ledgerRevenue")));
        out.put("r5b", new Check("r5b", "POS revenue", "POS invoices − refunds and settled voids = revenue of their"
                + " pos_invoice / pos_refund journals", project(pos, "reportRevenue", "ledgerRevenue")));
        out.put("r5c", new Check("r5c", "Output VAT", "Tax of the same invoices, credit notes and POS documents ="
                + " movement of the tax accounts in their journals", tax));
        out.put("r5d", new Check("r5d", "Receipts", "Customer receipts − refunds + deposits received = movement of"
                + " their tender account", receipts(f)));
        out.put("r5e", new Check("r5e", "Cost of sales", "Value of sales stock issues − returns in the stock ledger ="
                + " cost of sales their journals posted", costs(f)));
        out.put("r5g", new Check("r5g", "VAT on advances", "Completed orders: VAT charged on advances = VAT deducted or"
                + " credited (V1, never counted twice)", advanceVat(f)));
        return out;
    }

    Report reconciliation(Filter f) {
        Map<String, Check> checks = checks(f);
        Bridge bridge = bridge(f, checks);
        List<Map<String, Object>> rows = new ArrayList<>();
        int failed = 0;
        for (Check c : checks.values()) {
            BigDecimal diff = c.report().subtract(c.ledger());
            int exceptions = c.exceptions().size();
            boolean ok = exceptions == 0;
            failed += ok ? 0 : 1;
            Map<String, Object> r = Obj.of("check", c.key().toUpperCase(java.util.Locale.ROOT), "title", c.title(),
                    "basis", c.basis(), "documents", c.rows().size(), "report", money(c.report()), "ledger",
                    money(c.ledger()), "difference", money(diff), "exceptions", exceptions, "status",
                    ok ? "ok" : "difference");
            if (!ok) {
                drill(r, "reconciliation-exceptions", "status", c.key());
            }
            rows.add(r);
            if ("r5e".equals(c.key())) {
                boolean bridgeOk = bridge.ok();
                failed += bridgeOk ? 0 : 1;
                Map<String, Object> b = Obj.of("check", "R5F", "title", "Billing to recognised revenue", "basis",
                        "Billing (M3) + accruals at confirm − deferrals + releases + period-end accruals ± cancellations"
                                + " and posting-date shifts = recognised revenue (M5)", "documents", bridge.documents(),
                        "report", money(bridge.expectedFromBilling()), "ledger", money(bridge.m5()), "difference",
                        money(bridge.expectedFromBilling().subtract(bridge.m5())), "exceptions",
                        checks.get("r5a").exceptions().size() + checks.get("r5b").exceptions().size(), "status",
                        bridgeOk ? "ok" : "difference");
                drill(b, "revenue-bridge");
                rows.add(b);
            }
        }
        Report rep = table(rows, "check|Check|text", "title|Measure|text", "basis|Rule|text",
                "documents|Documents|count", "report|Report|money", "ledger|Ledger|money",
                "difference|Difference|money", "exceptions|Documents differing|count", "status|Status|status");
        rep.kpi("passed", "Checks passed", rows.size() - failed, "count", rows.size() + " checks")
                .kpi("failed", "Checks with differences", failed, "count")
                .kpi("m3", "Billing (M3)", money(bridge.m3()), "money")
                .kpi("m5", "Recognised revenue (M5)", money(bridge.m5()), "money");
        rep.note("Tolerance: 0.01 in the functional currency per document. Amounts are in the functional currency.");
        rep.note("Down-payment invoices post to the customer-deposit account, so they are advances, not sales;"
                + " write-offs of POS invoices are bad debts and stay out of the revenue checks.");
        return rep;
    }

    /** The R5f bridge, from billing to recognised revenue. */
    record Bridge(List<Map<String, Object>> lines, BigDecimal m3, BigDecimal m5, BigDecimal perDocument,
            BigDecimal expected, int documents) {

        /** Billing plus every explained item, without the per-document differences. */
        BigDecimal expectedFromBilling() {
            return expected.subtract(perDocument);
        }

        boolean ok() {
            BigDecimal tol = TOLERANCE.multiply(BigDecimal.valueOf(Math.max(1, documents)));
            return perDocument.abs().compareTo(tol) <= 0 && m5.subtract(expected).abs().compareTo(TOLERANCE) <= 0;
        }
    }

    Bridge bridge(Filter f, Map<String, Check> checks) {
        List<Map<String, Object>> docs = checks.get("r5a").rows();
        List<Map<String, Object>> pos = checks.get("r5b").rows();
        Map<String, BigDecimal> grp = recognised(f);
        Function<String, BigDecimal> g = k -> grp.getOrDefault(k, BigDecimal.ZERO);
        BigDecimal m3Docs = sum(docs, "reportRevenue");
        BigDecimal m3Pos = sum(pos, "reportRevenue");
        BigDecimal perDoc = sum(docs, "ledgerRevenue").subtract(m3Docs).add(sum(pos, "ledgerRevenue")).subtract(m3Pos);
        BigDecimal accruedInvoices = sum(docs, "accruedRevenue").negate();
        BigDecimal shifts = g.apply("doc_out").subtract(sum(docs, "outsideRevenue")).subtract(sum(pos,
                "outsideRevenue"));
        BigDecimal m5 = grp.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal expected = BigDecimal.ZERO;
        Object[][] items = {
            {"billing_documents", "Billing: sales invoices − credit notes (revenue lines)", m3Docs},
            {"billing_pos", "Billing: POS invoices − refunds and settled voids", m3Pos},
            {"per_document", "Documents whose journal differs from the report (R5a, R5b)", perDoc},
            {"accrued_invoices", "Less: legacy cash-sale invoices whose revenue was accrued at confirm",
                accruedInvoices},
            {"confirm_accrual", "Add: legacy cash-sale revenue accrued at confirm in the period, net of voids",
                g.apply("confirm_accrual")},
            {"deferral", "Less: revenue deferred (invoiced before ownership transfer)", g.apply("deferral")},
            {"release", "Add: deferred revenue released", g.apply("release")},
            {"unbilled_accrual", "Add: delivered, not invoiced — period-end accrual net of its reversal",
                g.apply("unbilled_accrual")},
            {"other", "Cancelled or corrected documents, net of their reversals", g.apply("other")},
            {"shifts", "Documents posted in another period than their date", shifts}};
        for (Object[] it : items) {
            BigDecimal v = (BigDecimal) it[2];
            expected = expected.add(v);
            lines.add(Obj.of("key", it[0], "label", it[1], "amount", money(v)));
        }
        return new Bridge(lines, m3Docs.add(m3Pos), m5, perDoc, expected, docs.size() + pos.size());
    }

    private Bridge bridge(Filter f) {
        Map<String, Check> c = new LinkedHashMap<>();
        c.put("r5a", new Check("r5a", "", "", project(documents(f), "reportRevenue", "ledgerRevenue")));
        c.put("r5b", new Check("r5b", "", "", project(pos(f), "reportRevenue", "ledgerRevenue")));
        return bridge(f, c);
    }

    private static BigDecimal sum(List<Map<String, Object>> rows, String k) {
        return rows.stream().map(r -> dec(r.get(k))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    Report bridgeReport(Filter f) {
        Bridge b = bridge(f);
        List<Map<String, Object>> rows = new ArrayList<>(b.lines());
        rows.add(Obj.of("key", "expected", "label", "Expected recognised revenue", "amount", money(b.expected())));
        rows.add(Obj.of("key", "m5", "label", "Recognised revenue in the ledger (M5)", "amount", money(b.m5())));
        rows.add(Obj.of("key", "difference", "label", "Unexplained difference", "amount",
                money(b.m5().subtract(b.expected()))));
        for (Map<String, Object> r : rows) {
            if ("per_document".equals(r.get("key")) && dec(r.get("amount")).signum() != 0) {
                drill(r, "reconciliation-exceptions", "status", "r5a");
            }
        }
        Report rep = table(rows, "label|Line|text", "amount|Amount|money");
        rep.kpi("m3", "Billing (M3)", money(b.m3()), "money")
                .kpi("m5", "Recognised revenue (M5)", money(b.m5()), "money")
                .kpi("perDocument", "Per-document differences", money(b.perDocument()), "money")
                .kpi("status", "Status", b.ok() ? "ok" : "difference", "text");
        rep.note("Revenue is in the functional currency. Billing counts revenue lines only (down-payment invoices are"
                + " advances). M5 is the revenue posted by invoices, credit notes, POS sales and refunds, the legacy"
                + " cash-sale accrual, revenue deferrals and releases and the period-end accrual.");
        return rep;
    }

    Report exceptions(Filter f) {
        String only = f.status();
        if (only != null && !CHECKS.contains(only)) {
            throw ApiException.badRequest("status must be one of: " + String.join(", ", CHECKS));
        }
        Map<String, Check> checks = checks(f);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Check c : checks.values()) {
            if (only != null && !only.equals(c.key())) {
                continue;
            }
            for (Map<String, Object> r : c.exceptions()) {
                BigDecimal rep = dec(r.get("report"));
                BigDecimal led = dec(r.get("ledger"));
                Map<String, Object> m = Obj.of("check", c.key().toUpperCase(java.util.Locale.ROOT), "date",
                        r.get("date"), "number", r.get("number"), "type", SalesReportKit.label(str(r.get("type"))),
                        "kind", r.get("kind"), "report", money(rep), "ledger", money(led), "difference",
                        money(rep.subtract(led)));
                String path = routeOf(str(r.get("type")), str(r.get("id")));
                out.add(path == null ? m : route(m, path));
            }
        }
        int limit = f.limit(500);
        List<Map<String, Object>> shown = out.subList(0, Math.min(limit, out.size()));
        Report rep = table(new ArrayList<>(shown), "check|Check|text", "date|Date|date", "number|Document|text",
                "type|Type|text", "kind|Source|text", "report|Report|money", "ledger|Ledger|money",
                "difference|Difference|money");
        rep.sumTotals("report", "ledger", "difference");
        rep.total("check", "Total");
        rep.kpi("count", "Documents differing", out.size(), "count");
        if (out.size() > limit) {
            rep.note("Showing " + limit + " of " + out.size() + " documents; narrow the period or raise the limit.");
        }
        return rep;
    }

    /** Where a document of a check opens, when it has a screen. */
    static String routeOf(String type, String id) {
        return switch (type == null ? "" : type) {
            case "sales_invoice" -> "/invoices/" + id;
            case "credit_note" -> "/credit-notes/" + id;
            case "pos_invoice", "pos_refund" -> "/pos/receipts";
            case "receipt", "refund" -> "/payments/" + id;
            case "sale" -> "/sales/orders/" + id;
            default -> null;
        };
    }
}
