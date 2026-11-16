package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Phase A A4 (plan §5): the one place that allocates delivered goods to invoices. The source of a delivery order decides
 * who invoices its goods:
 *
 * <ul>
 *   <li>{@code sales_order} → Sales ({@code SalesInvoiceLink}); Fleet invoices transport charges only. Sales calls
 *       {@link #assertSalesGoods} before it writes its links and {@link #markGoodsInvoiced} after.
 *   <li>any other source → Fleet, through the immutable {@code FleetDeliveryInvoiceLine} ledger
 *       ({@link #allocateFleetGoods}).
 * </ul>
 *
 * <p>Invoiceable = accepted (Σ {@code billableQty} of delivered trips, A3) − live allocations. An allocation is live while
 * its document is not cancelled or rejected; credit notes never release quantity. A Fleet goods invoice from before A4
 * (no ledger rows) counts as having invoiced the delivery order in full.
 */
@Component
public class DeliveryInvoicing {

    /** Document statuses that no longer hold quantity. */
    static final String RELEASED = "('cancelled', 'rejected')";

    private final FleetDb db;
    private final org.springframework.beans.factory.ObjectProvider<DeliverySourcePort> sources;

    public DeliveryInvoicing(FleetDb db, org.springframework.beans.factory.ObjectProvider<DeliverySourcePort> sources) {
        this.db = db;
        this.sources = sources;
    }

    /**
     * Whether Sales owns the goods of a delivery order: its source is a Sales-module order (a {@link DeliverySourcePort}
     * supports it). A {@code sales_order} delivery order of a back-office order of the orders module keeps the Nest
     * behaviour: Fleet invoices its goods.
     */
    public boolean salesOwned(String sourceType, String sourceId) {
        return "sales_order".equals(sourceType) && sourceId != null
                && sources.orderedStream().anyMatch(p -> p.supports(sourceType, sourceId));
    }

    /** One allocation of a goods invoice line to a delivery-order line. */
    public record Allocation(String deliveryOrderLineId, BigDecimal quantity, String uomId) {}

    /**
     * Sales is about to invoice the goods of a sales order: lock its delivery orders (plan §8: after the sales order)
     * and refuse when Fleet holds a goods allocation on one of them. Returns the live goods invoices recorded on the
     * delivery orders ({@code deliveryOrderId, deliveryOrderCode, documentId, documentNumber}); Sales decides which are
     * its own and which are historical Fleet invoices awaiting review.
     */
    public List<Map<String, Object>> assertSalesGoods(String orderId) {
        List<Map<String, Object>> dos = SqlRows.list(db.jdbc().sql("SELECT id, code FROM \"FleetDeliveryOrder\" WHERE"
                + " \"organizationId\" = ? AND \"sourceType\" = 'sales_order' AND \"sourceId\" = ? ORDER BY id FOR UPDATE")
                .params(FleetDb.org(), orderId));
        for (Map<String, Object> d : dos) {
            long n = db.jdbc().sql("SELECT count(*) FROM \"FleetDeliveryInvoiceLine\" a JOIN \"Document\" x ON x.id ="
                    + " a.\"documentId\" WHERE a.\"organizationId\" = ? AND a.\"deliveryOrderId\" = ? AND x.status::text"
                    + " NOT IN " + RELEASED).params(FleetDb.org(), d.get("id")).query(Long.class).single();
            if (n > 0) {
                throw ApiException.conflict("Delivery order " + d.get("code") + " has goods invoiced by Fleet; a sales"
                        + " order invoices its own goods");
            }
        }
        return goodsInvoices(orderId);
    }

    /** Live goods invoices recorded on the delivery orders of a sales order (read-only; see {@link #assertSalesGoods}). */
    public List<Map<String, Object>> goodsInvoices(String orderId) {
        return SqlRows.list(db.jdbc().sql("SELECT o.id AS \"deliveryOrderId\", o.code AS \"deliveryOrderCode\","
                + " o.\"goodsInvoiceId\" AS \"documentId\", x.\"documentNumber\", x.status::text AS status FROM"
                + " \"FleetDeliveryOrder\" o JOIN \"Document\" x ON x.id = o.\"goodsInvoiceId\" WHERE o.\"organizationId\" = ?"
                + " AND o.\"sourceType\" = 'sales_order' AND o.\"sourceId\" = ? AND x.status::text NOT IN " + RELEASED
                + " ORDER BY o.code").params(FleetDb.org(), orderId));
    }

    /**
     * Lines of a historical Fleet goods invoice on a sales order, matched to the order lines through the delivery-order
     * lines (product, variant, unit): {@code documentLineId, productId, variantId, uomId, quantity, orderLineId}.
     */
    public List<Map<String, Object>> legacyInvoiceLines(String deliveryOrderId, String documentId) {
        return SqlRows.list(db.jdbc().sql("""
                SELECT dl.id AS "documentLineId", dl."productId", dl.quantity, dl.description, dl."lineNumber",
                       (SELECT ol."sourceLineId" FROM "FleetDeliveryOrderLine" ol
                         WHERE ol."deliveryOrderId" = ? AND ol."productId" = dl."productId"
                         ORDER BY ol."lineNumber" LIMIT 1) AS "orderLineId"
                  FROM "DocumentLine" dl
                 WHERE dl."documentId" = ? AND dl."organizationId" = ? AND dl."productId" IS NOT NULL
                 ORDER BY dl."lineNumber\"""").params(deliveryOrderId, documentId, FleetDb.org()));
    }

    /** The sales order's goods were invoiced by Sales: record it so a delivery-order invoice carries charges only. */
    public void markGoodsInvoiced(String sourceType, String sourceId, String invoiceId, String invoiceNumber) {
        db.jdbc().sql("UPDATE \"FleetDeliveryOrder\" SET \"goodsInvoiceId\" = ?, \"goodsInvoiceNumber\" = ?,"
                + " \"goodsInvoiceKind\" = 'document', \"updatedAt\" = " + FleetDb.NOW + " WHERE \"organizationId\" = ? AND"
                + " \"sourceType\" = ? AND \"sourceId\" = ? AND \"goodsInvoiceId\" IS NULL AND status <> 'cancelled'")
                .params(invoiceId, invoiceNumber, FleetDb.org(), sourceType, sourceId).update();
    }

    /**
     * Per line of a Fleet-owned delivery order: {@code deliveryOrderLineId → {accepted, allocated, invoiceable}}. A
     * historical goods invoice without ledger rows makes every line fully invoiced.
     */
    public Map<String, Map<String, BigDecimal>> fleetInvoiceable(String deliveryOrderId) {
        Map<String, Object> o = SqlRows.one(db.jdbc().sql("SELECT o.\"goodsInvoiceId\", x.status::text AS status FROM"
                + " \"FleetDeliveryOrder\" o LEFT JOIN \"Document\" x ON x.id = o.\"goodsInvoiceId\" WHERE o.id = ? AND"
                + " o.\"organizationId\" = ?").params(deliveryOrderId, FleetDb.org()));
        long ledger = db.jdbc().sql("SELECT count(*) FROM \"FleetDeliveryInvoiceLine\" WHERE \"organizationId\" = ? AND"
                + " \"deliveryOrderId\" = ?").params(FleetDb.org(), deliveryOrderId).query(Long.class).single();
        boolean legacy = o != null && o.get("goodsInvoiceId") != null && ledger == 0
                && !List.of("cancelled", "rejected").contains(o.get("status"));
        Map<String, Map<String, BigDecimal>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT ol.id,
                       COALESCE((SELECT sum(ll."billableQty") FROM "FleetLoadLine" ll JOIN "FleetLoad" t ON t.id = ll."loadId"
                                  WHERE ll."deliveryOrderLineId" = ol.id
                                    AND t.status IN ('delivered', 'closed', 'returned')), 0) AS accepted,
                       COALESCE((SELECT sum(a.quantity) FROM "FleetDeliveryInvoiceLine" a JOIN "Document" x ON x.id = a."documentId"
                                  WHERE a."deliveryOrderLineId" = ol.id
                                    AND x.status::text NOT IN ('cancelled', 'rejected')), 0) AS allocated
                  FROM "FleetDeliveryOrderLine" ol
                 WHERE ol."organizationId" = ? AND ol."deliveryOrderId" = ?
                 ORDER BY ol."lineNumber\"""").params(FleetDb.org(), deliveryOrderId))) {
            BigDecimal accepted = SqlRows.dec(r, "accepted");
            BigDecimal allocated = legacy ? accepted.max(SqlRows.dec(r, "allocated")) : SqlRows.dec(r, "allocated");
            Map<String, BigDecimal> m = new LinkedHashMap<>();
            m.put("accepted", accepted);
            m.put("allocated", allocated);
            m.put("invoiceable", accepted.subtract(allocated).max(BigDecimal.ZERO));
            out.put((String) r.get("id"), m);
        }
        return out;
    }

    /**
     * Record the goods lines of a Fleet invoice in the ledger. The caller holds the delivery-order lock; the invoiceable
     * quantity is rechecked here, inside the same transaction. {@code allocations} are in the order of the invoice's
     * product lines.
     */
    public void allocateFleetGoods(String deliveryOrderId, String documentId, List<Allocation> allocations) {
        Map<String, Object> o = SqlRows.one(db.jdbc().sql("SELECT code, \"sourceType\", \"sourceId\" FROM \"FleetDeliveryOrder\" WHERE"
                + " id = ? AND \"organizationId\" = ? FOR UPDATE").params(deliveryOrderId, FleetDb.org()));
        if (o == null) {
            throw ApiException.notFound("Delivery order not found");
        }
        if (salesOwned((String) o.get("sourceType"), (String) o.get("sourceId"))) {
            throw ApiException.conflict("Delivery order " + o.get("code") + " belongs to a sales order; Sales invoices"
                    + " its goods");
        }
        Map<String, Map<String, BigDecimal>> open = fleetInvoiceable(deliveryOrderId);
        List<String> docLines = db.jdbc().sql("SELECT id FROM \"DocumentLine\" WHERE \"documentId\" = ? AND"
                + " \"organizationId\" = ? AND \"productId\" IS NOT NULL ORDER BY \"lineNumber\", id")
                .params(documentId, FleetDb.org()).query(String.class).list();
        if (docLines.size() != allocations.size()) {
            throw new IllegalStateException("Invoice goods lines do not match the allocations of delivery order "
                    + o.get("code"));
        }
        for (int i = 0; i < allocations.size(); i++) {
            Allocation a = allocations.get(i);
            Map<String, BigDecimal> line = open.get(a.deliveryOrderLineId());
            BigDecimal left = line == null ? BigDecimal.ZERO : line.get("invoiceable");
            if (a.quantity().compareTo(left) > 0) {
                throw ApiException.conflict("Only " + JsNumbers.dec(left) + " can still be invoiced on a line of"
                        + " delivery order " + o.get("code"));
            }
            line.put("invoiceable", left.subtract(a.quantity()));
            db.jdbc().sql("INSERT INTO \"FleetDeliveryInvoiceLine\" (id, \"organizationId\", \"deliveryOrderId\","
                    + " \"deliveryOrderLineId\", \"documentId\", \"documentLineId\", quantity, \"uomId\", \"createdById\")"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)").params(UUID.randomUUID().toString(), FleetDb.org(),
                            deliveryOrderId, a.deliveryOrderLineId(), documentId, docLines.get(i), a.quantity(), a.uomId(),
                            TenantContext.userId()).update();
        }
    }

    /**
     * Post-time recheck of a Fleet goods invoice (plan §5, defence in depth): every delivery-order line it allocates
     * must still have live allocations ≤ accepted. Returns the problems (empty = fine).
     */
    public List<String> postProblems(String documentId) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT DISTINCT a.\"deliveryOrderId\", o.code FROM"
                + " \"FleetDeliveryInvoiceLine\" a JOIN \"FleetDeliveryOrder\" o ON o.id = a.\"deliveryOrderId\" WHERE"
                + " a.\"documentId\" = ? AND a.\"organizationId\" = ?").params(documentId, FleetDb.org()))) {
            for (Map<String, BigDecimal> l : fleetInvoiceable((String) r.get("deliveryOrderId")).values()) {
                if (l.get("allocated").compareTo(l.get("accepted")) > 0) {
                    out.add("Delivery order " + r.get("code") + " is invoiced for "
                            + JsNumbers.dec(l.get("allocated")) + " but only " + JsNumbers.dec(l.get("accepted"))
                            + " was accepted");
                }
            }
        }
        return out;
    }

    /**
     * Quantity of a Fleet-owned delivery-order line invoiced beyond what is accepted now (0 without ledger rows): the
     * most a late-dispute credit note may carry (plan §13).
     */
    public BigDecimal overInvoiced(String deliveryOrderLineId) {
        Map<String, Object> r = SqlRows.one(db.jdbc().sql("SELECT ol.\"deliveryOrderId\" FROM \"FleetDeliveryOrderLine\" ol"
                + " WHERE ol.id = ? AND ol.\"organizationId\" = ?").params(deliveryOrderLineId, FleetDb.org()));
        if (r == null) {
            return BigDecimal.ZERO;
        }
        Map<String, BigDecimal> l = fleetInvoiceable((String) r.get("deliveryOrderId")).get(deliveryOrderLineId);
        return l == null ? BigDecimal.ZERO : l.get("allocated").subtract(l.get("accepted")).max(BigDecimal.ZERO);
    }

    /** Whether a delivery order has rows in the Fleet goods ledger. */
    public boolean hasLedger(String deliveryOrderId) {
        return db.jdbc().sql("SELECT count(*) FROM \"FleetDeliveryInvoiceLine\" WHERE \"organizationId\" = ? AND"
                + " \"deliveryOrderId\" = ?").params(FleetDb.org(), deliveryOrderId).query(Long.class).single() > 0;
    }
}
