package com.inventoryerp.invoicing.application;

import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockReservationService;
import com.inventoryerp.inventory.application.StockReversalService;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IPR Phase 1a (F1, owner decision D4): the stock side of a sales invoice whose goods leave through deliveries.
 *
 * <p>A sales invoice either issues its own stock when it posts ({@code sales_invoice} rows), or hands the issue to a
 * delivery ({@code data.stockIssuedBy} = the delivery order code, or the sales order / cash sale that issues it). When
 * the delivery order that carried an invoice's goods is cancelled, or short-closed, with goods still undelivered, the
 * invoice goes back to <b>awaiting delivery</b>: {@code data.stockIssuedBy} is replaced by
 * {@code data.stockAwaitingDelivery} (the code of the released delivery order). From there either a new delivery order
 * issues the rest at dispatch, or {@link #issueUndelivered} issues it at once for a customer collection.
 *
 * <p>Lock order (R2): invoice {@code Document} row (L1) → stock cells (L3 → L4 → L5, taken by the reservation check and
 * the engine, in the engine's line order) → journal (L7).
 */
@Service
public class InvoiceGoodsService {

    /** {@code Document.data} key of an invoice whose goods wait for a delivery or a collection. */
    public static final String AWAITING = "stockAwaitingDelivery";

    /** What one invoice line still has to deliver, in the line's unit ({@code quantity}) and in base units. */
    public record UndeliveredLine(String lineId, String productId, String variantId, String description, String uomId,
            BigDecimal uomFactor, BigDecimal quantity, BigDecimal baseQuantity, BigDecimal unitPrice,
            BigDecimal discountPercent, String taxId) {}

    private final JdbcClient jdbc;
    private final StockService stock;
    private final StockReversalService reversals;
    private final StockReservationService reservations;
    private final UomConversionService uoms;
    private final DocumentBuilderService builder;
    private final BranchScopeService scopes;
    private final AuditService audit;

    public InvoiceGoodsService(JdbcClient jdbc, StockService stock, StockReversalService reversals,
            StockReservationService reservations, UomConversionService uoms, DocumentBuilderService builder,
            BranchScopeService scopes, AuditService audit) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.reversals = reversals;
        this.reservations = reservations;
        this.uoms = uoms;
        this.builder = builder;
        this.scopes = scopes;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static BigDecimal d(Object v) {
        return Money.dec(v);
    }

    // ------------------------------------------------------------------ the issue loop

    /**
     * The invoice auto-issue loop (extracted from the invoice post workflow): every tracked product line leaves stock
     * as a {@code sales_invoice} movement naming its invoice line, in the engine's lock order, from {@code location}
     * when given, else from the branch warehouse (a lot-tracked line from a warehouse that holds the lot). IPR F5: stock
     * reserved for sales orders at the cell is left alone. Runs in the caller's transaction, under its invoice lock.
     */
    void issueStock(String org, Map<String, Object> doc, List<Map<String, Object>> lines, Instant date, String location,
            String notes) {
        for (Map<String, Object> line : StockMove.inLockOrder(lines)) {
            String productId = (String) line.get("productId");
            Map<String, Object> product = productId == null ? null : product(productId);
            if (product == null || !Boolean.TRUE.equals(product.get("trackInventory"))) {
                continue;
            }
            BigDecimal qty = d(line.get("quantity"));
            String from = location;
            if (from == null) {
                from = warehouse(org, doc.get("branchId"));
                if (Boolean.TRUE.equals(product.get("batchTracking"))) {
                    from = lotWarehouse(org, productId, line, from);
                }
            }
            if (from == null) {
                throw new IllegalStateException("No warehouse location for stockable line " + product.get("name"));
            }
            reservations.assertNotReservedForSalesOrders(productId, (String) line.get("variantId"), from,
                    baseQuantity(productId, qty, (String) line.get("uomId"), line.get("uomFactor")));
            StockMove m = StockMove.of(productId, from, qty).variant((String) line.get("variantId"))
                    .uom((String) line.get("uomId"), line.get("uomFactor") == null ? null : d(line.get("uomFactor")))
                    .date(date).source("sales_invoice", (String) doc.get("id")).sourceLine((String) line.get("id"))
                    .notes(notes);
            stock.issue(m);
        }
    }

    /**
     * A lot-tracked sale ships from a warehouse that holds it (ADR-S060): the default warehouse when it has enough,
     * otherwise the one with the oldest lot that covers the line. No such warehouse keeps the default, so the issue
     * reports the shortage.
     */
    private String lotWarehouse(String org, String productId, Map<String, Object> line, String fallback) {
        BigDecimal factor = line.get("uomFactor") == null ? BigDecimal.ONE : d(line.get("uomFactor"));
        return jdbc.sql("SELECT b.\"locationId\" FROM \"InventoryBatch\" b JOIN \"InventoryLocation\" l ON l.id ="
                        + " b.\"locationId\" WHERE b.\"organizationId\" = ? AND b.\"productId\" = ? AND b.quantity > 0 AND"
                        + " b.\"isActive\" = true AND l.type = 'warehouse' AND l.\"isActive\" = true AND l.\"deletedAt\" IS"
                        + " NULL GROUP BY b.\"locationId\" HAVING SUM(b.quantity) >= ? ORDER BY (b.\"locationId\" = ?) DESC,"
                        + " MIN(b.\"receivedAt\") LIMIT 1")
                .params(org, productId, d(line.get("quantity")).multiply(factor), fallback).query(String.class)
                .optional().orElse(fallback);
    }

    /**
     * The document's stock location: a warehouse of its branch when it has one (sales readiness INV-10), else the
     * organisation's oldest warehouse (Nest behaviour).
     */
    String warehouse(String org, Object branchId) {
        return jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND type = 'warehouse'"
                        + " AND \"isActive\" = true AND \"deletedAt\" IS NULL ORDER BY (\"branchId\" IS NOT DISTINCT FROM"
                        + " ?::text AND ?::text IS NOT NULL) DESC, \"createdAt\" ASC LIMIT 1")
                .params(org, branchId, branchId).query(String.class).optional().orElse(null);
    }

    private Map<String, Object> product(String id) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"Product\" WHERE id = ? AND \"deletedAt\" IS NULL LIMIT 1").param(id));
    }

    /**
     * IPR E-2a: base units of a line quantity by the engine's one rule ({@link StockService#returnBaseQuantity}: the
     * snapshot factor, else the live conversion, else the quantity); a zero snapshot counts as none.
     */
    private BigDecimal baseQuantity(String productId, BigDecimal qty, String uomId, Object snapshot) {
        BigDecimal factor = snapshot != null && d(snapshot).signum() > 0 ? d(snapshot) : null;
        return stock.returnBaseQuantity(productId, qty, uomId, factor);
    }

    private BigDecimal factor(String productId, String uomId, Object snapshot) {
        if (snapshot != null && d(snapshot).signum() > 0) {
            return d(snapshot);
        }
        if (uomId != null) {
            BigDecimal f = uoms.factorToBase(productId, uomId);
            if (f != null && f.signum() > 0) {
                return f;
            }
        }
        return BigDecimal.ONE;
    }

    // ------------------------------------------------------------------ where the goods left

    /**
     * Ledger codes of every stock issue made for an invoice's goods, whichever document made it: its own
     * {@code sales_invoice} rows (post, or a collection of undelivered goods), the {@code cash_sale} rows of the cash
     * sale it bills, and the {@code fleet_trip} rows of the loads of the delivery orders that carried its goods (from the
     * invoice, its sales order, or allocated to it). Outflow rows only, oldest first.
     */
    public List<String> issueLedgerCodes(String invoiceId) {
        Map<String, Object> doc = SqlRows.one(jdbc.sql("SELECT \"sourceType\", \"sourceId\" FROM \"Document\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(invoiceId, org()));
        if (doc == null) {
            return List.of();
        }
        String cashSale = "cash_sale".equals(doc.get("sourceType")) ? (String) doc.get("sourceId") : null;
        String salesOrder = "sales_order".equals(doc.get("sourceType")) ? (String) doc.get("sourceId") : null;
        List<String> loads = loadsOf(invoiceId, salesOrder);
        return jdbc.sql("SELECT g.\"ledgerCode\" FROM \"InventoryLedger\" g WHERE g.\"organizationId\" = ? AND"
                        + " g.\"quantityChange\" < 0 AND g.\"ledgerCode\" IS NOT NULL AND ((g.\"referenceType\" ="
                        + " 'sales_invoice' AND g.\"referenceId\" = ?) OR (g.\"referenceType\" = 'cash_sale' AND"
                        + " g.\"referenceId\" = ?) OR (g.\"referenceType\" = 'fleet_trip' AND g.\"referenceId\" ="
                        + " ANY(?::text[]))) GROUP BY g.\"ledgerCode\" ORDER BY MIN(g.\"createdAt\"), MIN(g.seq)")
                .params(org(), invoiceId, cashSale == null ? "" : cashSale, loads.toArray(String[]::new))
                .query(String.class).list();
    }

    /**
     * The invoice's own stock issue, for a put-back of goods a delivery carried in stock mode {@code invoiced}: its
     * {@code sales_invoice} rows, or the {@code cash_sale} rows of the cash sale it bills. Null when nothing was issued.
     */
    public StockReversalService.IssueSource ownIssueSource(String invoiceId) {
        Map<String, Object> doc = SqlRows.one(jdbc.sql("SELECT \"sourceType\", \"sourceId\" FROM \"Document\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(invoiceId, org()));
        if (doc == null) {
            return null;
        }
        boolean cash = "cash_sale".equals(doc.get("sourceType")) && doc.get("sourceId") != null;
        String type = cash ? "cash_sale" : "sales_invoice";
        String ref = cash ? (String) doc.get("sourceId") : invoiceId;
        long rows = jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"referenceType\""
                + " = ? AND \"referenceId\" = ? AND \"quantityChange\" < 0").params(org(), type, ref).query(Long.class)
                .single();
        return rows == 0 ? null : StockReversalService.IssueSource.of(type, ref);
    }

    private List<String> loadsOf(String invoiceId, String salesOrder) {
        return jdbc.sql("SELECT DISTINCT l.id FROM \"FleetLoad\" l JOIN \"FleetDeliveryOrder\" o ON o.id ="
                        + " l.\"deliveryOrderId\" WHERE l.\"organizationId\" = ? AND o.\"organizationId\" = ? AND"
                        + " ((o.\"sourceType\" = 'invoice' AND o.\"sourceId\" = ?) OR o.\"goodsInvoiceId\" = ? OR"
                        + " (o.\"sourceType\" = 'sales_order' AND o.\"sourceId\" = ?) OR o.id IN (SELECT"
                        + " \"deliveryOrderId\" FROM \"FleetDeliveryInvoiceLine\" WHERE \"documentId\" = ? AND"
                        + " \"organizationId\" = ?))")
                .params(org(), org(), invoiceId, invoiceId, salesOrder, invoiceId, org()).query(String.class).list();
    }

    // ------------------------------------------------------------------ what is still undelivered

    /**
     * Per tracked product line of the invoice, what has not left stock yet: the invoiced quantity, net of posted credit
     * notes against the invoice, minus what is still out (issued by the invoice, its cash sale or its delivery trips,
     * net of every linked return, and of put-backs written before IPR F3 without a link). Computed per product and
     * variant in base units, then given to the lines of that product in line order. Lines with nothing left are omitted.
     */
    public List<UndeliveredLine> undelivered(String invoiceId) {
        List<Map<String, Object>> lines = builder.lines(invoiceId, true);
        Map<List<String>, BigDecimal> pool = new LinkedHashMap<>();
        List<Object[]> tracked = new ArrayList<>(); // {line, factor, base}
        for (Map<String, Object> l : lines) {
            String productId = (String) l.get("productId");
            if (productId == null) {
                continue;
            }
            Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT \"trackInventory\", \"productType\"::text AS t FROM"
                    + " \"Product\" WHERE id = ?").param(productId));
            if (p == null || !Boolean.TRUE.equals(p.get("trackInventory"))
                    || !("stockable".equals(p.get("t")) || "consumable".equals(p.get("t")))) {
                continue;
            }
            BigDecimal qty = d(l.get("quantity"));
            if (qty.signum() <= 0) {
                continue;
            }
            BigDecimal factor = factor(productId, (String) l.get("uomId"), l.get("uomFactor"));
            BigDecimal base = baseQuantity(productId, qty, (String) l.get("uomId"), l.get("uomFactor"));
            tracked.add(new Object[] {l, factor, base});
            pool.merge(Arrays.asList(productId, (String) l.get("variantId")), base, BigDecimal::add);
        }
        if (tracked.isEmpty()) {
            return List.of();
        }
        // credited (posted credit notes against the invoice)
        for (Map<String, Object> c : SqlRows.list(jdbc.sql("SELECT cl.\"productId\", cl.\"variantId\","
                + " SUM(cl.quantity * COALESCE(NULLIF(cl.\"uomFactor\", 0), 1)) AS q FROM \"Document\" c JOIN"
                + " \"DocumentLine\" cl ON cl.\"documentId\" = c.id WHERE c.\"organizationId\" = ? AND"
                + " c.\"reversedDocumentId\" = ? AND c.\"documentType\" = 'credit_note' AND c.status::text IN ('posted',"
                + " 'paid') AND cl.\"productId\" IS NOT NULL GROUP BY 1, 2").params(org(), invoiceId))) {
            pool.computeIfPresent(Arrays.asList((String) c.get("productId"), (String) c.get("variantId")),
                    (k, v) -> v.subtract(d(c.get("q"))));
        }
        // still out: issued net of linked returns
        List<String> codes = issueLedgerCodes(invoiceId);
        if (!codes.isEmpty()) {
            for (StockReversalService.Returnable r : reversals.returnableByProduct(
                    StockReversalService.IssueSource.ledgerCodes(codes))) {
                pool.computeIfPresent(Arrays.asList(r.productId(), r.variantId()), (k, v) -> v.subtract(r.quantity()));
            }
        }
        // put-backs written before IPR F3 (no reversal link) against the invoice's trips
        Map<String, Object> doc = SqlRows.one(jdbc.sql("SELECT \"sourceType\", \"sourceId\" FROM \"Document\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(invoiceId, org()));
        String salesOrder = doc != null && "sales_order".equals(doc.get("sourceType")) ? (String) doc.get("sourceId")
                : null;
        List<String> loads = loadsOf(invoiceId, salesOrder);
        if (!loads.isEmpty()) {
            for (Map<String, Object> b : SqlRows.list(jdbc.sql("SELECT \"productId\", \"variantId\","
                    + " SUM(\"quantityChange\") AS q FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                    + " \"referenceType\" = 'fleet_trip_return' AND \"referenceId\" = ANY(?::text[]) AND"
                    + " \"reversesLedgerId\" IS NULL AND \"quantityChange\" > 0 GROUP BY 1, 2")
                    .params(org(), loads.toArray(String[]::new)))) {
                pool.computeIfPresent(Arrays.asList((String) b.get("productId"), (String) b.get("variantId")),
                        (k, v) -> v.add(d(b.get("q"))));
            }
        }
        List<UndeliveredLine> out = new ArrayList<>();
        for (Object[] t : tracked) {
            @SuppressWarnings("unchecked")
            Map<String, Object> l = (Map<String, Object>) t[0];
            BigDecimal factor = (BigDecimal) t[1];
            List<String> key = Arrays.asList((String) l.get("productId"), (String) l.get("variantId"));
            BigDecimal left = pool.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal base = Money.min((BigDecimal) t[2], left.max(BigDecimal.ZERO));
            if (base.compareTo(new BigDecimal("0.0005")) < 0) {
                continue;
            }
            pool.put(key, left.subtract(base));
            // IPR review (unit rounding over-issue): 2 base units left in packs of 3 used to read 0.667 pack
            // (HALF_UP), which issues 2.001. The line unit is kept only when it states the base quantity exactly;
            // otherwise the line is given in base units (no unit, factor 1), so what is issued never exceeds what is
            // undelivered.
            String uomId = (String) l.get("uomId");
            BigDecimal unitPrice = l.get("unitPrice") == null ? null : d(l.get("unitPrice"));
            BigDecimal qty = lineQuantity(base, factor);
            if (qty == null) {
                uomId = null;
                unitPrice = unitPrice == null || factor.signum() <= 0 ? unitPrice : Money.div(unitPrice, factor);
                factor = BigDecimal.ONE;
                qty = base.setScale(3, RoundingMode.DOWN);
            }
            if (qty.signum() <= 0) {
                continue;
            }
            out.add(new UndeliveredLine((String) l.get("id"), (String) l.get("productId"), (String) l.get("variantId"),
                    (String) l.get("description"), uomId, factor, qty, base, unitPrice,
                    l.get("discountPercent") == null ? null : d(l.get("discountPercent")), (String) l.get("taxId")));
        }
        return out;
    }

    /**
     * IPR review (unit rounding over-issue): {@code base} base units in the line unit of {@code factor} base units, when
     * that states them exactly at 3 decimals (rounded down, never above); null when it cannot (2 base units in packs of
     * 3 = 0.666… pack): the caller then gives the line in base units.
     */
    static BigDecimal lineQuantity(BigDecimal base, BigDecimal factor) {
        if (factor == null || factor.signum() <= 0) {
            return null;
        }
        BigDecimal qty = base.divide(factor, 3, RoundingMode.DOWN);
        return qty.multiply(factor).compareTo(base) == 0 ? qty : null;
    }

    // ------------------------------------------------------------------ issue undelivered (customer collection)

    /**
     * {@code POST /invoices/:id/issue-undelivered} (IPR F1, owner decision D4): the customer collects the goods of a
     * posted invoice that is awaiting delivery. Under the invoice's row lock, refused unless the invoice is posted (or
     * paid), awaiting delivery and has no open delivery order; then every undelivered quantity leaves stock as a
     * {@code sales_invoice} movement on its invoice line (so an invoice cancel or a credit note returns it like an
     * invoice's own issue), from {@code locationId}, else from the location of the delivery order that last carried
     * the goods, else the branch warehouse. The invoice stops awaiting delivery. A replay with the same idempotency key
     * returns the first answer; a second call finds nothing awaiting and is refused.
     */
    @Transactional
    public Map<String, Object> issueUndelivered(String invoiceId, String locationId) {
        Map<String, Object> doc = SqlRows.one(jdbc.sql("SELECT id, \"documentNumber\", status::text AS status,"
                + " \"branchId\", data->>'" + AWAITING + "' AS awaiting FROM \"Document\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"documentType\" = 'sales_invoice' FOR UPDATE").params(invoiceId, org()));
        if (doc == null) {
            throw ApiException.notFound("Invoice not found");
        }
        scopes.assertBranch((String) doc.get("branchId"));
        String number = (String) doc.get("documentNumber");
        if (!"posted".equals(doc.get("status")) && !"paid".equals(doc.get("status"))) {
            throw ApiException.badRequest("Invoice " + number + " is " + doc.get("status")
                    + "; only a posted invoice can issue its undelivered goods");
        }
        if (doc.get("awaiting") == null) {
            throw ApiException.badRequest("Invoice " + number + " is not awaiting delivery; its goods were issued or a"
                    + " delivery order carries them");
        }
        String open = openDeliveryOrder(invoiceId);
        if (open != null) {
            throw ApiException.conflict("Invoice " + number + " has open delivery order " + open
                    + "; deliver through it or cancel it first");
        }
        String from = locationId == null || locationId.isBlank() ? null : locationId;
        if (from != null) {
            long found = jdbc.sql("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND \"organizationId\" = ? AND"
                    + " \"isActive\" = true AND \"deletedAt\" IS NULL").params(from, org()).query(Long.class).single();
            if (found == 0) {
                throw ApiException.badRequest("Location not found");
            }
            if (!scopes.scope().allowsLocation(from)) {
                throw ApiException.forbidden("This location is outside your data access");
            }
        } else {
            from = jdbc.sql("SELECT \"locationId\" FROM \"FleetDeliveryOrder\" WHERE \"organizationId\" = ? AND"
                    + " \"sourceType\" = 'invoice' AND \"sourceId\" = ? AND \"locationId\" IS NOT NULL ORDER BY"
                    + " \"createdAt\" DESC LIMIT 1").params(org(), invoiceId).query(String.class).optional()
                    .orElse(null);
        }
        List<UndeliveredLine> left = undelivered(invoiceId);
        if (left.isEmpty()) {
            throw ApiException.badRequest("Invoice " + number + " has no undelivered goods to issue");
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal units = BigDecimal.ZERO;
        for (UndeliveredLine u : left) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("id", u.lineId());
            l.put("productId", u.productId());
            l.put("variantId", u.variantId());
            l.put("uomId", u.uomId());
            l.put("uomFactor", u.uomFactor());
            l.put("quantity", u.quantity());
            lines.add(l);
            units = units.add(u.quantity());
        }
        issueStock(org(), doc, lines, Instant.now(), from, "Collection of undelivered goods of invoice " + number);
        jdbc.sql("UPDATE \"Document\" SET data = COALESCE(data, '{}'::jsonb) - '" + AWAITING + "', \"updatedAt\" = "
                + DocumentBuilderService.NOW + " WHERE id = ? AND \"organizationId\" = ?").params(invoiceId, org())
                .update();
        audit.recordInTx(AuditInput.of("Document", invoiceId, "update", Obj.of(AWAITING, doc.get("awaiting")),
                Obj.of("issuedUndelivered", true, "lines", left.size(), "quantity", JsNumbers.dec(units), "locationId", from)));
        return Obj.of("invoiceId", invoiceId, "lines", left.size());
    }

    /** The code of a delivery order still carrying the invoice's goods (not cancelled, not closed), or null. */
    public String openDeliveryOrder(String invoiceId) {
        return jdbc.sql("SELECT code FROM \"FleetDeliveryOrder\" WHERE \"organizationId\" = ? AND ((\"sourceType\" ="
                        + " 'invoice' AND \"sourceId\" = ?) OR \"goodsInvoiceId\" = ?) AND status NOT IN ('cancelled',"
                        + " 'closed') ORDER BY \"createdAt\" LIMIT 1")
                .params(org(), invoiceId, invoiceId).query(String.class).optional().orElse(null);
    }
}
