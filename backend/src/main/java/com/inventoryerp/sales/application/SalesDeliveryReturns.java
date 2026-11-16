package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Returns shown on the delivery tab of a document with a single handover (sales invoice, branch cash sale): the credit
 * notes raised against its invoice(s), with their product lines. Only posted / paid credit notes count towards the
 * returned quantity of a line; drafts are listed so the user sees what is pending.
 */
final class SalesDeliveryReturns {

    private SalesDeliveryReturns() {}

    /** Credit notes against {@code invoiceId} (cancelled ones left out), each with {@code lines}. */
    static List<Map<String, Object>> creditNotes(SalesDb db, String invoiceId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : db.rows("SELECT d.id, d.\"documentNumber\" AS number, d.\"issueDate\" AS date,"
                + " d.status::text AS status, d.\"totalAmount\" AS total, d.notes FROM \"Document\" d WHERE"
                + " d.\"organizationId\" = ? AND d.\"reversedDocumentId\" = ? AND d.\"documentType\" = 'credit_note' AND"
                + " d.status::text NOT IN ('cancelled', 'rejected') ORDER BY d.\"issueDate\", d.\"documentNumber\"", org(),
                invoiceId)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", "credit_note");
            r.put("id", d.get("id"));
            r.put("number", d.get("number"));
            r.put("date", d.get("date"));
            r.put("status", d.get("status"));
            r.put("total", d.get("total"));
            r.put("reason", d.get("notes"));
            r.put("counted", List.of("posted", "paid").contains(str(d.get("status"))));
            r.put("lines", db.rows("SELECT l.\"productId\", COALESCE(pr.name, l.description) AS \"productName\","
                    + " l.quantity, u.code AS \"uomCode\" FROM \"DocumentLine\" l LEFT JOIN \"Product\" pr ON pr.id ="
                    + " l.\"productId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id = l.\"uomId\" WHERE l.\"documentId\" = ? AND"
                    + " l.\"organizationId\" = ? AND l.\"lineType\" = 'product' ORDER BY l.\"lineNumber\"", d.get("id"),
                    org()));
            out.add(r);
        }
        return out;
    }

    /**
     * Spread the counted returns over the document lines by product, in line order, each line taking at most its own
     * quantity; writes {@code returnedQty} on every line ({@code qtyKey} names the line's quantity field).
     */
    @SuppressWarnings("unchecked")
    static void allocate(List<Map<String, Object>> lines, List<Map<String, Object>> returns, String qtyKey) {
        Map<String, BigDecimal> pool = new HashMap<>();
        for (Map<String, Object> r : returns) {
            if (!Boolean.TRUE.equals(r.get("counted"))) {
                continue;
            }
            for (Map<String, Object> l : (List<Map<String, Object>>) r.get("lines")) {
                String p = str(l.get("productId"));
                if (p != null) {
                    pool.merge(p, dec(l.get("quantity")), BigDecimal::add);
                }
            }
        }
        for (Map<String, Object> l : lines) {
            String p = str(l.get("productId"));
            BigDecimal left = p == null ? BigDecimal.ZERO : pool.getOrDefault(p, BigDecimal.ZERO);
            BigDecimal take = left.min(dec(l.get(qtyKey))).max(BigDecimal.ZERO);
            l.put("returnedQty", take);
            if (p != null && take.signum() > 0) {
                pool.put(p, left.subtract(take));
            }
        }
    }
}
