package com.inventoryerp.invoicing.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.LedgerQueries;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tax (VAT) summary for a period (ADR-S005). Output tax = posted sales invoices minus credit notes plus POS
 * invoices (net of refunded quantities); input tax = posted vendor bills minus debit notes. Each side is grouped by
 * tax with the taxable base, in base currency. A GL cross-check sums the movement on the tax accounts (each tax's
 * account plus the {@code tax_payable} / {@code tax_receivable} mappings).
 */
@Service
public class TaxReportService {

    private static final String POSTED = "('posted', 'paid', 'issued')";

    private final JdbcClient jdbc;
    private final AccountDeterminationService determination;

    public TaxReportService(JdbcClient jdbc, AccountDeterminationService determination) {
        this.jdbc = jdbc;
        this.determination = determination;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static final class Bucket {
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        long documents;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(String fromRaw, String toRaw, String branchId) {
        Instant from = LedgerQueries.date(fromRaw);
        Instant to = LedgerQueries.date(toRaw);
        Map<String, Bucket> output = new LinkedHashMap<>();
        Map<String, Bucket> input = new LinkedHashMap<>();

        // Back-office documents: invoices / credit notes (output), bills / debit notes (input).
        StringBuilder docSql = new StringBuilder("SELECT d.\"documentType\"::text AS type, dl.\"taxId\","
                + " sum(dl.subtotal * d.\"exchangeRate\") AS taxable, sum(dl.\"taxAmount\" * d.\"exchangeRate\") AS tax,"
                + " count(DISTINCT d.id) AS docs FROM \"DocumentLine\" dl JOIN \"Document\" d ON d.id = dl.\"documentId\""
                + " WHERE d.\"organizationId\" = ? AND d.\"documentType\"::text IN ('sales_invoice', 'credit_note',"
                + " 'vendor_bill', 'debit_note') AND d.status::text IN " + POSTED
                + " AND NOT EXISTS (SELECT 1 FROM \"DocumentLineTax\" x WHERE x.\"documentLineId\" = dl.id)");
        List<Object> params = new ArrayList<>(List.of(org()));
        range(docSql, params, "d.\"issueDate\"", from, to);
        if (branchId != null && !branchId.isBlank()) {
            docSql.append(" AND d.\"branchId\" = ?");
            params.add(branchId);
        }
        docSql.append(" GROUP BY 1, 2");
        // ADR-S071: multi-tax lines report per tax from their breakdown; reverse charge is output AND input VAT.
        StringBuilder brkSql = new StringBuilder("SELECT d.\"documentType\"::text AS type, x.\"taxId\", x.kind,"
                + " sum(x.base * d.\"exchangeRate\") AS taxable, sum(x.amount * d.\"exchangeRate\") AS tax,"
                + " count(DISTINCT d.id) AS docs FROM \"DocumentLineTax\" x JOIN \"Document\" d ON d.id = x.\"documentId\""
                + " WHERE d.\"organizationId\" = ? AND x.kind IN ('tax', 'reverse_charge') AND d.\"documentType\"::text"
                + " IN ('sales_invoice', 'credit_note', 'vendor_bill', 'debit_note') AND d.status::text IN " + POSTED);
        List<Object> brkParams = new ArrayList<>(List.of(org()));
        range(brkSql, brkParams, "d.\"issueDate\"", from, to);
        if (branchId != null && !branchId.isBlank()) {
            brkSql.append(" AND d.\"branchId\" = ?");
            brkParams.add(branchId);
        }
        brkSql.append(" GROUP BY 1, 2, 3");
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(brkSql.toString()).params(brkParams))) {
            String type = (String) r.get("type");
            boolean sales = "sales_invoice".equals(type) || "credit_note".equals(type);
            boolean negative = "credit_note".equals(type) || "debit_note".equals(type);
            long docs = ((Number) r.get("docs")).longValue();
            if ("reverse_charge".equals(r.get("kind"))) {
                add(output, (String) r.get("taxId"), SqlRows.dec(r, "taxable"), SqlRows.dec(r, "tax"), docs, negative);
                add(input, (String) r.get("taxId"), SqlRows.dec(r, "taxable"), SqlRows.dec(r, "tax"), docs, negative);
            } else {
                add(sales ? output : input, (String) r.get("taxId"), SqlRows.dec(r, "taxable"), SqlRows.dec(r, "tax"),
                        docs, negative);
            }
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(docSql.toString()).params(params))) {
            String type = (String) r.get("type");
            boolean sales = "sales_invoice".equals(type) || "credit_note".equals(type);
            boolean negative = "credit_note".equals(type) || "debit_note".equals(type);
            add(sales ? output : input, (String) r.get("taxId"), SqlRows.dec(r, "taxable"), SqlRows.dec(r, "tax"),
                    ((Number) r.get("docs")).longValue(), negative);
        }

        // POS invoices, net of refunded quantities.
        StringBuilder posSql = new StringBuilder("SELECT ii.\"taxId\", sum(ii.subtotal * i.\"exchangeRate\" * (1 -"
                + " CASE WHEN ii.quantity > 0 THEN LEAST(ii.\"refundedQty\", ii.quantity) / ii.quantity ELSE 0 END)) AS taxable,"
                + " sum(ii.\"taxAmount\" * i.\"exchangeRate\" * (1 - CASE WHEN ii.quantity > 0 THEN LEAST(ii.\"refundedQty\","
                + " ii.quantity) / ii.quantity ELSE 0 END)) AS tax, count(DISTINCT i.id) AS docs FROM \"InvoiceItem\" ii"
                + " JOIN \"Invoice\" i ON i.id = ii.\"invoiceId\" WHERE i.\"organizationId\" = ? AND i.status::text IN"
                + " ('posted', 'paid', 'refunded')");
        List<Object> posParams = new ArrayList<>(List.of(org()));
        range(posSql, posParams, "i.\"issueDate\"", from, to);
        if (branchId != null && !branchId.isBlank()) {
            posSql.append(" AND i.\"branchId\" = ?");
            posParams.add(branchId);
        }
        posSql.append(" GROUP BY 1");
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(posSql.toString()).params(posParams))) {
            add(output, (String) r.get("taxId"), SqlRows.dec(r, "taxable"), SqlRows.dec(r, "tax"),
                    ((Number) r.get("docs")).longValue(), false);
        }

        // Procurement debit notes (returns to suppliers) reduce input tax.
        StringBuilder dnSql = new StringBuilder("SELECT l.\"taxId\", sum(l.subtotal) AS taxable, sum(l.\"taxAmount\") AS"
                + " tax, count(DISTINCT n.id) AS docs FROM \"DebitNoteLine\" l JOIN \"DebitNote\" n ON n.id = l.\"debitNoteId\""
                + " WHERE n.\"organizationId\" = ? AND n.status = 'posted' AND n.\"deletedAt\" IS NULL");
        List<Object> dnParams = new ArrayList<>(List.of(org()));
        range(dnSql, dnParams, "n.\"issueDate\"", from, to);
        dnSql.append(" GROUP BY 1");
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(dnSql.toString()).params(dnParams))) {
            add(input, (String) r.get("taxId"), SqlRows.dec(r, "taxable"), SqlRows.dec(r, "tax"),
                    ((Number) r.get("docs")).longValue(), true);
        }

        Set<String> taxIds = new LinkedHashSet<>(output.keySet());
        taxIds.addAll(input.keySet());
        taxIds.remove("none");
        Map<String, Map<String, Object>> taxes = new LinkedHashMap<>();
        if (!taxIds.isEmpty()) {
            for (Map<String, Object> t : SqlRows.list(jdbc.sql("SELECT id, name, code, type::text AS type, rate,"
                    + " \"vatCategory\", \"accountId\" FROM \"Tax\" WHERE id IN (:ids)").param("ids", taxIds))) {
                taxes.put((String) t.get("id"), t);
            }
        }
        Map<String, Object> out = side(output, taxes);
        Map<String, Object> in = side(input, taxes);
        BigDecimal net = ((BigDecimal) out.remove("_tax")).subtract((BigDecimal) in.remove("_tax"));
        return Obj.of("from", fromRaw, "to", toRaw, "branchId", branchId, "output", out, "input", in,
                "netTaxPayable", StatementService.plain(net), "position", net.signum() >= 0 ? "payable" : "refundable",
                "gl", gl(from, to, branchId, taxes.values()));
    }

    /**
     * Generic tax return pack (FIN-3): the summary laid out as numbered boxes by VAT category, with the rate detail and
     * the GL cross-check. It names no jurisdiction; a country form maps these boxes onto its own lines.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> pack(String fromRaw, String toRaw, String branchId) {
        Map<String, Object> summary = summary(fromRaw, toRaw, branchId);
        Map<String, Object> output = (Map<String, Object>) summary.get("output");
        Map<String, Object> input = (Map<String, Object>) summary.get("input");
        Map<String, Object> outCat = (Map<String, Object>) output.get("byCategory");
        Map<String, Object> inCat = (Map<String, Object>) input.get("byCategory");
        BigDecimal net = new BigDecimal((String) summary.get("netTaxPayable"));
        BigDecimal glNet = new BigDecimal((String) ((Map<String, Object>) summary.get("gl")).get("net"));
        List<Map<String, Object>> boxes = new ArrayList<>();
        addBox(boxes, "Standard-rated sales", amount(outCat, "standard"));
        addBox(boxes, "Zero-rated sales", amount(outCat, "zero_rated"));
        addBox(boxes, "Exempt sales", amount(outCat, "exempt"));
        addBox(boxes, "Out-of-scope and untaxed sales",
                amount(outCat, "out_of_scope").add(amount(outCat, "no_tax")));
        addBox(boxes, "Total sales", new BigDecimal((String) output.get("taxableTotal")));
        addBox(boxes, "Output tax", new BigDecimal((String) output.get("taxTotal")));
        addBox(boxes, "Standard-rated purchases", amount(inCat, "standard"));
        addBox(boxes, "Zero-rated purchases", amount(inCat, "zero_rated"));
        addBox(boxes, "Exempt purchases", amount(inCat, "exempt"));
        addBox(boxes, "Out-of-scope and untaxed purchases",
                amount(inCat, "out_of_scope").add(amount(inCat, "no_tax")));
        addBox(boxes, "Total purchases", new BigDecimal((String) input.get("taxableTotal")));
        addBox(boxes, "Input tax", new BigDecimal((String) input.get("taxTotal")));
        addBox(boxes, "Net tax payable (refundable if negative)", net);
        BigDecimal difference = net.subtract(glNet);
        return Obj.of("from", fromRaw, "to", toRaw, "branchId", branchId, "boxes", boxes, "position",
                summary.get("position"), "glNet", StatementService.plain(glNet), "glDifference",
                StatementService.plain(difference), "reconciled",
                        difference.abs().compareTo(new BigDecimal("0.01")) <= 0,
                "output", output.get("lines"), "input", input.get("lines"));
    }

    private static BigDecimal amount(Map<String, Object> byCategory, String category) {
        Object v = byCategory.get(category);
        return v == null ? BigDecimal.ZERO : new BigDecimal((String) v);
    }

    private static void addBox(List<Map<String, Object>> boxes, String label, BigDecimal value) {
        boxes.add(Obj.of("box", boxes.size() + 1, "label", label, "amount", StatementService.plain(value)));
    }

    private static void range(StringBuilder sql, List<Object> params, String column, Instant from, Instant to) {
        if (from != null) {
            sql.append(" AND ").append(column).append(" >= ?");
            params.add(SqlRows.ts(from));
        }
        if (to != null) {
            sql.append(" AND ").append(column).append(" <= ?");
            params.add(SqlRows.ts(to));
        }
    }

    private static void add(Map<String, Bucket> side, String taxId, BigDecimal taxable, BigDecimal tax, long docs,
            boolean negative) {
        Bucket b = side.computeIfAbsent(taxId == null ? "none" : taxId, k -> new Bucket());
        b.taxable = negative ? b.taxable.subtract(taxable) : b.taxable.add(taxable);
        b.tax = negative ? b.tax.subtract(tax) : b.tax.add(tax);
        b.documents += docs;
    }

    private static Map<String, Object> side(Map<String, Bucket> buckets, Map<String, Map<String, Object>> taxes) {
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal untaxed = BigDecimal.ZERO;
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        for (Map.Entry<String, Bucket> e : buckets.entrySet()) {
            Map<String, Object> t = taxes.get(e.getKey());
            Bucket b = e.getValue();
            String category = t == null ? "no_tax" : String.valueOf(t.get("vatCategory"));
            lines.add(Obj.of("taxId", t == null ? null : e.getKey(), "name", t == null ? "No tax" : t.get("name"),
                    "code", t == null ? null : t.get("code"), "rate", t == null ? null : t.get("rate"),
                    "vatCategory", category, "taxableAmount", StatementService.plain(b.taxable),
                    "taxAmount", StatementService.plain(b.tax), "documents", b.documents));
            taxable = taxable.add(b.taxable);
            tax = tax.add(b.tax);
            if (t == null) {
                untaxed = untaxed.add(b.taxable);
            }
            byCategory.merge(category, b.taxable, BigDecimal::add);
        }
        Map<String, Object> cats = new LinkedHashMap<>();
        byCategory.forEach((k, v) -> cats.put(k, StatementService.plain(v)));
        Map<String, Object> m = Obj.of("lines", lines, "taxableTotal", StatementService.plain(taxable), "taxTotal",
                StatementService.plain(tax), "untaxedAmount", StatementService.plain(untaxed), "byCategory", cats);
        m.put("_tax", tax);
        return m;
    }

    /** Movement on the tax accounts in the period: credit - debit (positive = owed to the authority). */
    private Map<String, Object> gl(Instant from, Instant to, String branchId,
            java.util.Collection<Map<String, Object>> taxes) {
        Set<String> accounts = new LinkedHashSet<>();
        for (String key : List.of("tax_payable", "tax_receivable")) {
            try {
                accounts.add(determination.mapped(key));
            } catch (RuntimeException ignored) {
                // mapping not configured: the cross-check covers the tax accounts that are
            }
        }
        for (Map<String, Object> t : taxes) {
            if (t.get("accountId") != null) {
                accounts.add((String) t.get("accountId"));
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal net = BigDecimal.ZERO;
        if (!accounts.isEmpty()) {
            StringBuilder sql = new StringBuilder("SELECT a.id, a.code, a.name, COALESCE(sum(l.\"baseDebit\"), 0) AS d,"
                    + " COALESCE(sum(l.\"baseCredit\"), 0) AS c FROM \"Account\" a LEFT JOIN (\"JournalLine\" l JOIN"
                    + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" AND e.status::text IN ('posted', 'reversed')");
            List<Object> params = new ArrayList<>();
            if (from != null) {
                sql.append(" AND e.\"postingDate\" >= ?");
                params.add(SqlRows.ts(from));
            }
            if (to != null) {
                sql.append(" AND e.\"postingDate\" <= ?");
                params.add(SqlRows.ts(to));
            }
            if (branchId != null && !branchId.isBlank()) {
                sql.append(" AND l.\"branchId\" = ?");
                params.add(branchId);
            }
            sql.append(") ON l.\"accountId\" = a.id WHERE a.\"organizationId\" = ? AND a.id IN (");
            params.add(org());
            List<String> ids = new ArrayList<>(accounts);
            for (int i = 0; i < ids.size(); i++) {
                sql.append(i == 0 ? "?" : ", ?");
                params.add(ids.get(i));
            }
            sql.append(") GROUP BY a.id, a.code, a.name ORDER BY a.code");
            for (Map<String, Object> r : SqlRows.list(jdbc.sql(sql.toString()).params(params))) {
                BigDecimal d = SqlRows.dec(r, "d");
                BigDecimal c = SqlRows.dec(r, "c");
                net = net.add(c.subtract(d));
                rows.add(Obj.of("accountId", r.get("id"), "code", r.get("code"), "name", r.get("name"), "debit",
                        StatementService.plain(d), "credit", StatementService.plain(c), "net",
                        StatementService.plain(c.subtract(d))));
            }
        }
        return Obj.of("accounts", rows, "net", StatementService.plain(net));
    }
}
