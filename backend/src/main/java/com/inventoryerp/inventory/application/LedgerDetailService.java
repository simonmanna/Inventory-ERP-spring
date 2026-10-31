package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.InventoryLookups.str;

import com.inventoryerp.inventory.domain.InventoryLedger;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One stock-ledger line plus its source transaction (port of {@code ledger-detail.service.ts}): sibling lines
 * posted under the same reference and the resolved source document (kind/targetId/code/status/date/partner).
 */
@Service
@Transactional(readOnly = true)
public class LedgerDetailService {

    private static final Set<String> CODE_ONLY_TYPES = Set.of("direct_stock_in", "direct_stock_out", "stock_adjust",
            "manual", "min_stock");

    private final JdbcClient jdbc;

    private final LocationScope scope;

    public LedgerDetailService(JdbcClient jdbc, LocationScope scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    static String titleCase(String value) {
        List<String> words = new ArrayList<>();
        for (String w : value.split("_", -1)) {
            words.add(w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return String.join(" ", words);
    }

    /** {@code {entry, lines, totals, source}}. */
    public Map<String, Object> getEntry(String id) {
        Map<String, Object> entry = SqlRows.one(InventoryLedger.class, jdbc.sql("SELECT * FROM \"InventoryLedger\""
                + " WHERE id = ? AND \"organizationId\" = ?").params(id, org()));
        if (entry == null || !scope.permits(str(entry, "locationId"))) {
            throw ApiException.notFound("Ledger entry not found");
        }
        include(entry);
        String refType = str(entry, "referenceType");
        String refId = str(entry, "referenceId");
        List<Map<String, Object>> lines;
        if (refType != null && refId != null) {
            lines = SqlRows.list(InventoryLedger.class, jdbc.sql("SELECT * FROM \"InventoryLedger\" WHERE"
                    + " \"organizationId\" = ? AND \"referenceType\" = ? AND \"referenceId\" = ? ORDER BY \"createdAt\" ASC")
                    .params(org(), refType, refId));
            lines.forEach(this::include);
        } else {
            lines = List.of(entry);
        }
        double in = 0;
        double out = 0;
        double total = 0;
        for (Map<String, Object> l : lines) {
            double change = ((BigDecimal) l.get("quantityChange")).doubleValue();
            if (change >= 0) {
                in += change;
            } else {
                out += Math.abs(change);
            }
            total += ((BigDecimal) l.get("totalValue")).doubleValue();
        }
        return Obj.of("entry", entry, "lines", lines, "totals", Obj.of("lines", lines.size(), "quantityIn",
                JsNumbers.js(in), "quantityOut", JsNumbers.js(out), "totalValue", JsNumbers.js(total)), "source",
                resolveSource(refType, refId));
    }

    private void include(Map<String, Object> r) {
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT p.id, p.code, p.name, u.code AS uom FROM \"Product\" p"
                + " LEFT JOIN \"UnitOfMeasure\" u ON u.id = p.\"uomId\" WHERE p.id = ?").param(r.get("productId")));
        if (p != null) {
            Object uom = p.remove("uom");
            p.put("uom", uom == null ? null : Obj.of("code", uom));
        }
        r.put("product", p);
        r.put("variant", r.get("variantId") == null ? null : SqlRows.one(jdbc.sql("SELECT id, name FROM"
                + " \"ProductVariant\" WHERE id = ?").param(r.get("variantId"))));
        r.put("location", SqlRows.one(jdbc.sql("SELECT id, code, name FROM \"InventoryLocation\" WHERE id = ?")
                .param(r.get("locationId"))));
        r.put("batch", r.get("batchId") == null ? null : SqlRows.one(jdbc.sql("SELECT id, \"batchNumber\","
                + " \"expiryDate\" FROM \"InventoryBatch\" WHERE id = ?").param(r.get("batchId"))));
    }

    private Map<String, Object> resolveSource(String referenceType, String referenceId) {
        if (referenceType == null || referenceId == null) {
            return null;
        }
        Map<String, Object> base = Obj.of("referenceType", referenceType, "referenceId", referenceId, "kind", "none",
                "targetId", null, "code", null, "status", null, "date", null, "partnerName", null, "label",
                titleCase(referenceType));
        if (CODE_ONLY_TYPES.contains(referenceType)) {
            base.put("code", referenceId);
            base.put("label", titleCase(referenceType) + " " + referenceId);
            return base;
        }
        try {
            Map<String, Object> found = switch (referenceType) {
                case "pos_invoice", "pos_invoice_extra", "pos_invoice_writeoff", "menu_recipe", "pos_refund", "pos",
                        "sales_invoice", "invoice", "document" -> resolveSale(base, referenceId);
                case "credit_note" -> doc(base, "credit_note", "SELECT id, \"documentNumber\" AS code, status::text AS status,"
                        + " \"issueDate\" AS date, \"partnerId\" FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?",
                        referenceId, "Credit Note ", false);
                case "goods_receipt" -> doc(base, "goods_receipt", "SELECT id, \"receiptNumber\" AS code, status::text AS"
                        + " status, \"receivedAt\" AS date, \"partnerId\" FROM \"GoodsReceiptNote\" WHERE (id = ? OR"
                        + " \"receiptNumber\" = ?) AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", referenceId,
                        "Goods Receipt ", true);
                case "purchase_order" -> doc(base, "purchase_order", "SELECT id, \"orderNumber\" AS code, status::text AS"
                        + " status, \"orderDate\" AS date, \"partnerId\" FROM \"PurchaseOrder\" WHERE (id = ? OR"
                        + " \"orderNumber\" = ?) AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", referenceId,
                        "Purchase Order ", true);
                case "debit_note" -> doc(base, "debit_note", "SELECT id, \"noteNumber\" AS code, status::text AS status,"
                        + " \"issueDate\" AS date, \"partnerId\" FROM \"DebitNote\" WHERE (id = ? OR \"noteNumber\" = ?)"
                        + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", referenceId, "Debit Note ", true);
                case "production_order", "production_qc", "production_reversal", "production_order_cancel" ->
                        resolveProduction(base, referenceId);
                case "work_order" -> resolveWorkOrder(base, referenceId);
                case "agreement", "rental_checkout", "rental_return", "rental_inspect" -> doc(base, "rental_agreement",
                        "SELECT id, \"agreementNumber\" AS code, status::text AS status, \"startAt\" AS date, \"partnerId\""
                                + " FROM \"RentalAgreement\" WHERE (id = ? OR \"agreementNumber\" = ?) AND \"organizationId\" = ?",
                        referenceId, "Rental ", true);
                case "repair_part_issue" -> doc(base, "repair_order", "SELECT id, \"repairNumber\" AS code, status::text"
                        + " AS status, \"createdAt\" AS date, \"partnerId\" FROM \"RepairOrder\" WHERE (id = ? OR"
                        + " \"repairNumber\" = ?) AND \"organizationId\" = ?", referenceId, "Repair ", true);
                case "stock_out" -> stockDoc(base, "stock_out", "StockOut", "outCode", "Stock Out ", referenceId);
                case "waste", "expiry_write_off" -> stockDoc(base, "waste", "WasteRecord", "wasteCode", "Waste ",
                        referenceId);
                case "stock_transfer" -> stockDoc(base, "stock_transfer", "StockTransfer", "transferCode", "Transfer ",
                        referenceId);
                case "adjustment" -> stockDoc(base, "stock_adjustment", "StockAdjustment", "adjCode", "Adjustment ",
                        referenceId);
                default -> null;
            };
            return found != null ? found : base;
        } catch (RuntimeException e) {
            return base;
        }
    }

    private String partnerName(Object partnerId) {
        if (partnerId == null) {
            return null;
        }
        return jdbc.sql("SELECT name FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(partnerId, org()).query(String.class).optional().orElse(null);
    }

    private Map<String, Object> resolveSale(Map<String, Object> base, String id) {
        Map<String, Object> inv = SqlRows.one(jdbc.sql("SELECT id, \"invoiceNumber\" AS code, status::text AS status,"
                + " \"issueDate\" AS date, \"partnerId\" FROM \"Invoice\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
        if (inv != null) {
            return fill(base, "pos_invoice", inv, "Sale " + inv.get("code"));
        }
        Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT id, \"documentNumber\" AS code, status::text AS status,"
                + " \"issueDate\" AS date, \"partnerId\" FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
        if (d != null) {
            return fill(base, "invoice", d, "Invoice " + d.get("code"));
        }
        return null;
    }

    private Map<String, Object> doc(Map<String, Object> base, String kind, String sql, String ref, String labelPrefix,
            boolean byCode) {
        Map<String, Object> d = SqlRows.one(byCode ? jdbc.sql(sql).params(ref, ref, org()) : jdbc.sql(sql).params(ref, org()));
        return d == null ? null : fill(base, kind, d, labelPrefix + d.get("code"));
    }

    private Map<String, Object> fill(Map<String, Object> base, String kind, Map<String, Object> d, String label) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.put("kind", kind);
        out.put("targetId", d.get("id"));
        out.put("code", d.get("code"));
        out.put("status", d.get("status") == null ? "" : d.get("status"));
        out.put("date", d.get("date"));
        out.put("partnerName", partnerName(d.get("partnerId")));
        out.put("label", label);
        return out;
    }

    private Map<String, Object> resolveProduction(Map<String, Object> base, String ref) {
        Map<String, Object> o = SqlRows.one(jdbc.sql("SELECT id, \"orderCode\" AS code, status::text AS status,"
                + " \"createdAt\" AS date FROM \"ProductionOrder\" WHERE (id = ? OR \"orderCode\" = ?)"
                + " AND \"organizationId\" = ?").params(ref, ref, org()));
        if (o == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.put("kind", "production_order");
        out.put("targetId", o.get("id"));
        out.put("code", o.get("code"));
        out.put("status", o.get("status") == null ? "" : o.get("status"));
        out.put("date", o.get("date"));
        out.put("label", titleCase(str(base, "referenceType")) + " " + o.get("code"));
        return out;
    }

    private Map<String, Object> resolveWorkOrder(Map<String, Object> base, String ref) {
        Map<String, Object> wo = SqlRows.one(jdbc.sql("SELECT id, name, status::text AS status, \"createdAt\" AS date,"
                + " \"productionOrderId\" FROM \"WorkOrder\" WHERE id = ? AND \"organizationId\" = ?").params(ref, org()));
        if (wo == null) {
            return null;
        }
        Map<String, Object> parent = SqlRows.one(jdbc.sql("SELECT id, \"orderCode\" FROM \"ProductionOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(wo.get("productionOrderId"), org()));
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.put("kind", "production_order");
        out.put("targetId", parent == null ? null : parent.get("id"));
        out.put("code", parent != null ? parent.get("orderCode") : wo.get("name"));
        out.put("status", wo.get("status") == null ? "" : wo.get("status"));
        out.put("date", wo.get("date"));
        out.put("label", "Work Order " + wo.get("name"));
        return out;
    }

    private Map<String, Object> stockDoc(Map<String, Object> base, String kind, String table, String codeField,
            String labelPrefix, String ref) {
        Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT id, \"" + codeField + "\" AS code, status::text AS status,"
                + " \"createdAt\" AS date FROM \"" + table + "\" WHERE (id = ? OR \"" + codeField + "\" = ?)"
                + " AND \"organizationId\" = ? LIMIT 1").params(ref, ref, org()));
        if (d == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.put("kind", kind);
        out.put("targetId", d.get("id"));
        out.put("code", d.get("code"));
        out.put("status", d.get("status"));
        out.put("date", d.get("date"));
        out.put("label", labelPrefix + d.get("code"));
        return out;
    }
}
