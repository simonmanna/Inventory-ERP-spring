package com.inventoryerp.sync.application;

import com.inventoryerp.credit.application.CreditService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * ADR-S083 O5 (Extension): field-sales pull scopes, sent only when named and always in full. Limited to the device's
 * branch when it has one: customers whose home branch is that branch (or none), and their documents.
 * <ul>
 *   <li>{@code priceLists}: active pricelists (branch or none) with their current rules — the device's price estimate;
 *   the server reprices every offline order (the O5 price audit).</li>
 *   <li>{@code paymentTerms}: active payment terms.</li>
 *   <li>{@code customerCredit}: per customer its pricelist, payment term, salesperson, limit, hold and, where a limit
 *   applies, the exposure and headroom at pull time.</li>
 *   <li>{@code openSalesOrders}: orders of the last 90 days not done or cancelled, with owner — the device shows a rep
 *   their own.</li>
 *   <li>{@code openInvoices}: customer invoices with a balance — what a rep can collect against.</li>
 * </ul>
 */
@Component
public class SyncSalesScopes {

    private static final Logger log = LoggerFactory.getLogger(SyncSalesScopes.class);

    public static final List<String> SCOPES = List.of("priceLists", "paymentTerms", "customerCredit", "openSalesOrders",
            "openInvoices");
    static final int CUSTOMER_LIMIT = 5000;

    private final JdbcClient jdbc;
    private final CreditService credit;

    public SyncSalesScopes(PosDb db, CreditService credit) {
        this.jdbc = db.jdbc();
        this.credit = credit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Rows of one O5 scope for a device bound to {@code branch} (null = every branch). */
    public List<Map<String, Object>> read(String scope, String branch) {
        return switch (scope) {
            case "priceLists" -> priceLists(branch);
            case "paymentTerms" -> SqlRows.list(jdbc.sql("SELECT id, code, name, \"netDays\", \"discountDays\","
                    + " \"discountPercent\", method::text AS method, \"updatedAt\" FROM \"PaymentTerm\" WHERE"
                    + " \"organizationId\" = ? AND \"isActive\" AND \"deletedAt\" IS NULL ORDER BY \"sortOrder\", name")
                    .param(org())).stream().map(SyncSalesScopes::numbers).toList();
            case "customerCredit" -> customerCredit(branch);
            case "openSalesOrders" -> openSalesOrders(branch);
            case "openInvoices" -> openInvoices(branch);
            default -> List.of();
        };
    }

    /** {@code AND <alias>."homeBranchId" …} for the device's branch. */
    private static String customerBranch(String alias, String branch, List<Object> params) {
        if (branch == null) {
            return "";
        }
        params.add(branch);
        return " AND (" + alias + ".\"homeBranchId\" = ? OR " + alias + ".\"homeBranchId\" IS NULL)";
    }

    private List<Map<String, Object>> priceLists(String branch) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String b = "";
        if (branch != null) {
            b = " AND (\"branchId\" = ? OR \"branchId\" IS NULL)";
            params.add(branch);
        }
        List<Map<String, Object>> lists = SqlRows.list(jdbc.sql("SELECT id, code, name, type::text AS type, \"currencyCode\","
                + " \"branchId\", \"basePricelistId\", \"validFrom\", \"validTo\", \"isDefault\", \"customerSiteId\","
                + " \"updatedAt\" FROM \"Pricelist\" WHERE \"organizationId\" = ? AND \"isActive\" AND \"deletedAt\" IS NULL"
                + " AND (\"validTo\" IS NULL OR \"validTo\" >= CURRENT_DATE)" + b + " ORDER BY \"isDefault\" DESC, name")
                .params(params));
        if (lists.isEmpty()) {
            return lists;
        }
        Map<Object, List<Map<String, Object>>> items = new LinkedHashMap<>();
        for (Map<String, Object> i : SqlRows.list(jdbc.sql("SELECT id, \"pricelistId\", scope, \"productId\","
                + " \"productCategoryId\", \"partnerId\", \"partnerCategoryId\", \"variantId\", \"uomId\", \"minQuantity\","
                + " \"branchId\", \"validFrom\", \"validTo\", compute, value, priority, \"createdAt\" FROM \"PricelistItem\""
                + " WHERE \"organizationId\" = ? AND \"pricelistId\" = ANY(?::text[]) AND (\"validTo\" IS NULL OR"
                + " \"validTo\" >= CURRENT_DATE)").params(org(), lists.stream().map(l -> String.valueOf(l.get("id")))
                        .toArray(String[]::new)))) {
            items.computeIfAbsent(i.remove("pricelistId"), k -> new ArrayList<>()).add(numbers(i));
        }
        lists.forEach(l -> l.put("items", items.getOrDefault(l.get("id"), List.of())));
        return lists;
    }

    private List<Map<String, Object>> customerCredit(String branch) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String b = customerBranch("p", branch, params);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT p.id, p.\"pricelistId\", p.\"paymentTermId\","
                + " p.\"salespersonId\", p.\"homeBranchId\", p.\"creditLimit\", p.\"creditHold\", (p.\"creditLimit\" > 0 OR"
                + " EXISTS (SELECT 1 FROM \"CreditLimit\" l WHERE l.\"organizationId\" = p.\"organizationId\" AND"
                + " l.\"partnerId\" = p.id)) AS limited FROM \"Partner\" p WHERE p.\"organizationId\" = ? AND"
                + " p.\"isCustomer\" AND p.\"deletedAt\" IS NULL" + b + " ORDER BY p.name LIMIT " + CUSTOMER_LIMIT)
                .params(params));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("pricelistId", r.get("pricelistId"));
            m.put("paymentTermId", r.get("paymentTermId"));
            m.put("salespersonId", r.get("salespersonId"));
            m.put("homeBranchId", r.get("homeBranchId"));
            m.put("creditHold", Boolean.TRUE.equals(r.get("creditHold")));
            m.put("limit", 0);
            m.put("exposure", null);
            m.put("headroom", null);
            m.put("overdue", 0);
            if (Boolean.TRUE.equals(r.get("limited"))) {
                try {
                    Map<String, Object> e = credit.exposure(String.valueOf(r.get("id")), branch);
                    m.put("limit", e.get("effectiveLimit"));
                    m.put("exposure", e.get("exposure"));
                    m.put("headroom", e.get("headroom"));
                    m.put("overdue", e.get("overdue") instanceof Map<?, ?> o ? o.get("amount") : 0);
                    m.put("creditHold", Boolean.TRUE.equals(e.get("creditHold")));
                } catch (RuntimeException ex) {
                    log.warn("credit snapshot of customer {} skipped: {}", r.get("id"), ex.getMessage());
                }
            }
            m.put("asOf", Instant.now().toString());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> openSalesOrders(String branch) {
        List<Object> params = new ArrayList<>(List.of(org(), SqlRows.ts(Instant.now().minus(90, ChronoUnit.DAYS))));
        String b = "";
        if (branch != null) {
            b = " AND (o.\"branchId\" = ? OR o.\"branchId\" IS NULL)";
            params.add(branch);
        }
        return SqlRows.list(jdbc.sql("SELECT o.id, o.number, o.status::text AS status, o.\"onHold\", o.\"holdReason\","
                + " o.\"partnerId\", p.name AS \"partnerName\", o.\"ownerId\", o.total, o.\"currencyCode\","
                + " o.\"deliveryStatus\"::text AS \"deliveryStatus\", o.\"invoiceStatus\"::text AS \"invoiceStatus\","
                + " o.\"requestedDate\", o.\"recordedOffline\", o.\"createdAt\", o.\"updatedAt\" FROM \"SalesOrder\" o"
                + " LEFT JOIN \"Partner\" p ON p.id = o.\"partnerId\" WHERE o.\"organizationId\" = ? AND o.\"deletedAt\""
                + " IS NULL AND o.status::text NOT IN ('done', 'cancelled') AND o.\"createdAt\" >= ?" + b
                + " ORDER BY o.\"createdAt\" DESC LIMIT 1000").params(params)).stream().map(SyncSalesScopes::numbers)
                .toList();
    }

    private List<Map<String, Object>> openInvoices(String branch) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String b = "";
        if (branch != null) {
            b = " AND (d.\"branchId\" = ? OR d.\"branchId\" IS NULL OR p.\"homeBranchId\" = ?)";
            params.add(branch);
            params.add(branch);
        }
        return SqlRows.list(jdbc.sql("SELECT d.id, d.\"documentNumber\", d.\"documentType\"::text AS \"documentType\","
                + " d.\"partnerId\", p.name AS \"partnerName\", d.\"issueDate\", d.\"dueDate\", d.\"totalAmount\","
                + " d.\"amountResidual\", d.\"currencyId\", d.\"salespersonId\", d.\"branchId\", d.\"updatedAt\" FROM"
                + " \"Document\" d JOIN \"Partner\" p ON p.id = d.\"partnerId\" WHERE d.\"organizationId\" = ? AND"
                + " d.\"documentType\"::text IN ('sales_invoice', 'debit_note') AND d.status::text IN ('posted',"
                + " 'approved', 'issued') AND d.\"amountResidual\" > 0" + b + " ORDER BY d.\"dueDate\" NULLS LAST,"
                + " d.\"issueDate\" LIMIT 2000").params(params)).stream().map(SyncSalesScopes::numbers).toList();
    }

    /** Decimal columns as JSON numbers. */
    private static Map<String, Object> numbers(Map<String, Object> r) {
        for (Map.Entry<String, Object> e : r.entrySet()) {
            if (e.getValue() instanceof java.math.BigDecimal d) {
                e.setValue(PosSql.js(d));
            }
        }
        return r;
    }
}
