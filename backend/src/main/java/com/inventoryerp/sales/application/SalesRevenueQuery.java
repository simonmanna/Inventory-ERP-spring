package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.org;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Product revenue lines of posted customer documents (ADR-S011 S9), the common base of rebates, commissions and
 * targets. Invoices count positive, credit notes negative (returns claw back); lines without a product (deposits,
 * fees, rebate settlements) are left out. Each line is attributed to a salesperson — the invoice's, else the owner of
 * its sales order (or of the order of the invoice a credit note reverses), else the customer's — and to the order's
 * team. Amounts are in document currency, net of tax and line discount; cost is the product's current cost.
 */
@Component
public class SalesRevenueQuery {

    private static final String SIGN = "CASE WHEN d.\"documentType\" = 'credit_note' THEN -1 ELSE 1 END";

    private static final String COLUMNS = """
            SELECT d.id AS "documentId", d."documentNumber" AS number, d."documentType"::text AS type,
                   d."issueDate"::date AS date, d."partnerId", p."categoryId" AS "partnerCategoryId", d."branchId",
                   COALESCE(d."salespersonId", so."ownerId", rso."ownerId", p."salespersonId") AS "salespersonId",
                   COALESCE(so."teamId", rso."teamId") AS "teamId", l."productId",
                   pr."categoryId" AS "productCategoryId", l.description,
            """;

    private static final String JOINS = """
              FROM "DocumentLine" l JOIN "Document" d ON d.id = l."documentId"
              LEFT JOIN "Partner" p ON p.id = d."partnerId"
              LEFT JOIN "Product" pr ON pr.id = l."productId"
              LEFT JOIN "SalesOrder" so ON d."sourceType" = 'sales_order' AND so.id = d."sourceId"
              LEFT JOIN "Document" rd ON rd.id = d."reversedDocumentId"
              LEFT JOIN "SalesOrder" rso ON rd."sourceType" = 'sales_order' AND rso.id = rd."sourceId"
            """;

    private static final String SCOPE = " d.\"organizationId\" = ? AND d.\"documentType\" IN ('sales_invoice',"
            + " 'credit_note') AND d.status IN ('posted', 'paid') AND l.\"productId\" IS NOT NULL";

    private final SalesDb db;
    private final SalesReportFacts facts;

    public SalesRevenueQuery(SalesDb db, SalesReportFacts facts) {
        this.db = db;
        this.facts = facts;
    }

    /**
     * Lines of documents issued in {@code [from, to]} (basis {@code invoiced}), read from the report center's fact so that
     * commissions, targets, analytics and reports share one revenue: net of tax and discount, in base currency, at actual
     * cost where the ledger knows it.
     */
    List<Map<String, Object>> invoiced(LocalDate from, LocalDate to) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> f : facts.lines(SalesReportKit.Filter.parse(Map.of("from", from.toString(), "to", to.toString())), null,
                "f.date, f.number")) {
            Map<String, Object> m = new java.util.LinkedHashMap<>(f);
            m.put("productCategoryId", f.get("categoryId"));
            m.put("partnerCategoryId", f.get("customerCategoryId"));
            m.put("description", f.get("productName") != null ? f.get("productName") : f.get("number"));
            m.put("basis", "invoiced");
            out.add(m);
        }
        return out;
    }

    /**
     * Basis {@code paid}: invoice lines pro rata to the payment allocations made in {@code [from, to]}, plus credit
     * notes issued in the period (clawback).
     */
    List<Map<String, Object>> paid(LocalDate from, LocalDate to) {
        String share = "(a.amount - COALESCE(a.\"refundedAmount\", 0)) / NULLIF(d.\"totalAmount\", 0)";
        List<Map<String, Object>> out = new java.util.ArrayList<>(db.rows(COLUMNS + "l.quantity * " + share
                + " AS quantity, l.subtotal * d.\"exchangeRate\" * " + share + " AS revenue, l.quantity * COALESCE(pr.\"costPrice\", 0) * "
                + share + " AS cost, 'paid' AS basis, a.\"paymentId\"" + JOINS + " JOIN \"PaymentAllocation\" a ON COALESCE(a.\"documentId\","
                + " a.\"invoiceId\") = d.id WHERE" + SCOPE + " AND d.\"documentType\" = 'sales_invoice' AND"
                + " a.\"createdAt\" >= ? AND a.\"createdAt\" < ? ORDER BY a.\"createdAt\"", org(), from, to.plusDays(1)));
        out.addAll(db.rows(COLUMNS + "-l.quantity AS quantity, -l.subtotal * d.\"exchangeRate\" AS revenue, -l.quantity *"
                + " COALESCE(pr.\"costPrice\", 0) AS cost, 'paid' AS basis" + JOINS + " WHERE" + SCOPE
                + " AND d.\"documentType\" = 'credit_note' AND d.\"issueDate\" >= ? AND d.\"issueDate\" < ?", org(), from,
                to.plusDays(1)));
        return out;
    }
}
