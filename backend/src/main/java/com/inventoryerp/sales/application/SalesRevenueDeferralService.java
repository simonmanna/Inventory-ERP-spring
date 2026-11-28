package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-S094 C2b (§3.6; AD-2 = A, AD-16 = dispatch): revenue of goods invoiced before they leave is a contract liability
 * until they do.
 *
 * <p>Per order line the deferred quantity is {@code T = max(N − (D − Rc − W), 0)}: net invoiced (posted regular links
 * less posted credit-note links) beyond what transferred and was kept (net dispatched, less goods returned and credited,
 * less quantities written off). {@code T} sits on the newest posted invoice lines of the order line ("layers"), each at
 * its own functional amount per unit at its invoice's rate, so the contract liability is never revalued: a dispatch
 * releases the oldest invoiced units, a bring-back holds the newest again, a credit note or a cancelled invoice drops
 * what it no longer bills. Every change posts only the difference to the stored layers: Dr revenue / Cr contract
 * liability to defer, the reverse to release. Lines without a product, and lines worth nothing, never defer.
 *
 * <p>All entry points run inside the transaction of what changed the figures (invoice post or cancel, fleet dispatch,
 * proof of delivery or return, a quantity credit) under the order lines' locks, so the liability always moves with
 * its cause; {@link #resync} repairs anything an event path missed.
 */
@Service
public class SalesRevenueDeferralService {

    static final String DEFERRAL_SOURCE = "sales_revenue_deferral";
    static final String RELEASE_SOURCE = "sales_revenue_release";

    private final SalesDb db;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final AccountDeterminationService determination;

    public SalesRevenueDeferralService(SalesDb db, PostingService posting, AccountResolverService accounts,
            AccountDeterminationService determination) {
        this.db = db;
        this.posting = posting;
        this.accounts = accounts;
        this.determination = determination;
    }

    // ------------------------------------------------------------------ entry points

    /** Inside the invoice's posting transaction: hold the revenue of what it bills beyond what has left. */
    @Transactional
    public void invoicePosted(String documentId) {
        Map<String, Object> d = first(raw("SELECT d.\"documentNumber\", e.\"postingDate\" FROM \"Document\" d LEFT JOIN"
                + " \"JournalEntry\" e ON e.id = d.\"journalEntryId\" WHERE d.id = ? AND d.\"organizationId\" = ?",
                documentId, org()));
        if (d == null) {
            return;
        }
        Instant date = d.get("postingDate") == null ? today() : (Instant) SqlRows.value(d.get("postingDate"));
        for (String orderId : ordersOf(documentId)) {
            sync(orderId, new Cause("invoice_posted", "invoice", documentId, date, "invoice "
                    + d.get("documentNumber")));
        }
    }

    /**
     * Inside the invoice's cancelling transaction (its journal was just reversed): drop what it deferred, on the date
     * the ledger reverses the invoice (its own date while that period is open, else today), so the period nets to zero.
     */
    @Transactional
    public void invoiceCancelled(String documentId) {
        Map<String, Object> d = first(raw("SELECT d.\"documentNumber\", e.\"postingDate\" FROM \"Document\" d LEFT JOIN"
                + " \"JournalEntry\" e ON e.id = d.\"journalEntryId\" WHERE d.id = ? AND d.\"organizationId\" = ?",
                documentId, org()));
        Instant date = d == null || d.get("postingDate") == null ? today()
                : (Instant) SqlRows.value(d.get("postingDate"));
        for (String orderId : ordersOf(documentId)) {
            sync(orderId, new Cause("invoice_cancelled", "invoice", documentId, date, "invoice "
                    + (d == null ? documentId : d.get("documentNumber")) + " cancelled"));
        }
    }

    /**
     * Inside fleet's dispatch, proof-of-delivery or return transaction for trip {@code loadId}, after its quantities
     * are written: release the revenue of what left, hold again what came back.
     */
    @Transactional
    public void deliveryChanged(String orderId, String loadId, Instant at) {
        Map<String, Object> t = first(raw("SELECT code FROM \"FleetLoad\" WHERE id = ? AND \"organizationId\" = ?",
                loadId, org()));
        sync(orderId, new Cause("delivery", "fleet_load", loadId, businessDay(at == null ? Instant.now() : at),
                "trip " + (t == null ? loadId : t.get("code"))));
    }

    /** After a quantity credit, a projection rebuild or the nightly run: bring the order's deferral to its figures. */
    @Transactional
    public void resync(String orderId, String reason) {
        sync(orderId, new Cause(reason, null, null, today(), null));
    }

    // ------------------------------------------------------------------ reads

    /** Invoiced but not yet transferred (and kept) on one order line, live: {@code T} of the class comment. */
    @Transactional(readOnly = true)
    public BigDecimal undelivered(String orderId, String lineId) {
        for (Map<String, Object> l : figures(orderId)) {
            if (lineId.equals(l.get("id"))) {
                return target(l);
            }
        }
        return BigDecimal.ZERO;
    }

    /** Per order line: the quantity and amount deferred now (functional currency). */
    @Transactional(readOnly = true)
    public Map<String, Map<String, Object>> view(String orderId) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (Map<String, Object> r : raw("SELECT \"orderLineId\", sum(quantity) AS qty, sum(amount) AS amount FROM"
                + " \"SalesRevenueDeferral\" WHERE \"orderId\" = ? AND \"organizationId\" = ? GROUP BY 1", orderId, org())) {
            out.put(str(r.get("orderLineId")), Obj.of("qtyDeferred", JsNumbers.js(dec(r.get("qty"))), "amountDeferred",
                    JsNumbers.js(dec(r.get("amount")))));
        }
        return out;
    }

    /** The revenue account an invoice line posted to, as its invoice did: crediting it offsets the release. */
    @Transactional(readOnly = true)
    public String revenueAccount(String documentLineId) {
        Map<String, Object> r = first(raw("SELECT dl.\"accountId\", c.\"incomeAccountId\" FROM \"DocumentLine\" dl LEFT JOIN"
                + " \"Product\" p ON p.id = dl.\"productId\" LEFT JOIN \"ProductCategory\" c ON c.id = p.\"categoryId\""
                + " WHERE dl.id = ? AND dl.\"organizationId\" = ?", documentLineId, org()));
        return determination.incomeAccount(r == null ? null : str(r.get("accountId")),
                r == null ? null : str(r.get("incomeAccountId")));
    }

    /** Total revenue of the order deferred now (functional currency). */
    @Transactional(readOnly = true)
    public BigDecimal deferred(String orderId) {
        Map<String, Object> r = first(raw("SELECT COALESCE(sum(amount), 0) AS amount FROM \"SalesRevenueDeferral\" WHERE"
                + " \"orderId\" = ? AND \"organizationId\" = ?", orderId, org()));
        return r == null ? BigDecimal.ZERO : dec(r.get("amount"));
    }

    // ------------------------------------------------------------------ the sync

    /** What changed the figures: the movement's reason and source, the entries' date and a few words for them. */
    record Cause(String reason, String sourceType, String sourceId, Instant date, String detail) {}

    /** One stored or wanted layer: an invoice line's share of an order line's deferral. */
    private static final class Layer {
        String id;
        String documentLineId;
        String documentId;
        String revenueAccountId;
        String liabilityAccountId;
        BigDecimal unitAmount;
        Map<String, Object> dimensions;
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal wantQty = BigDecimal.ZERO;
    }

    private void sync(String orderId, Cause cause) {
        Map<String, Object> o = first(raw("SELECT id, number, \"partnerId\" FROM \"SalesOrder\" WHERE id = ? AND"
                + " \"organizationId\" = ?", orderId, org()));
        if (o == null) {
            return;
        }
        // The same lock as the fulfilment rebuild: a dispatch and an invoice post of one order serialize here, and each
        // reads the other's committed quantities.
        db.rows("SELECT id FROM \"SalesOrderLine\" WHERE \"orderId\" = ? FOR UPDATE", orderId);
        int scale = baseScale();
        List<Line> defer = new ArrayList<>();
        List<Line> release = new ArrayList<>();
        List<Object[]> moves = new ArrayList<>();
        BigDecimal deferTotal = BigDecimal.ZERO;
        BigDecimal releaseTotal = BigDecimal.ZERO;
        for (Map<String, Object> l : figures(orderId)) {
            if (l.get("productId") == null) {
                continue;
            }
            String lineId = str(l.get("id"));
            Map<String, Layer> layers = layers(lineId);
            BigDecimal remaining = target(l);
            for (Layer c : candidates(lineId)) {
                Layer layer = layers.computeIfAbsent(c.documentLineId, k -> c);
                BigDecimal take = remaining.min(c.wantQty).max(BigDecimal.ZERO);
                layer.wantQty = take;
                remaining = remaining.subtract(take);
            }
            for (Layer layer : layers.values()) {
                BigDecimal wantAmount = layer.wantQty.multiply(layer.unitAmount).setScale(scale, RoundingMode.HALF_UP);
                BigDecimal dq = layer.wantQty.subtract(layer.quantity);
                BigDecimal da = wantAmount.subtract(layer.amount);
                if (dq.signum() == 0 && da.signum() == 0) {
                    continue;
                }
                if (layer.id == null) {
                    layer.id = SalesDb.newId();
                    db.update("INSERT INTO \"SalesRevenueDeferral\" (id, \"organizationId\", \"orderId\", \"orderLineId\","
                            + " \"documentId\", \"documentLineId\", \"revenueAccountId\", \"liabilityAccountId\","
                            + " \"unitAmount\", quantity, amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", layer.id,
                            org(), orderId, lineId, layer.documentId, layer.documentLineId, layer.revenueAccountId,
                            layer.liabilityAccountId, layer.unitAmount, layer.wantQty, wantAmount);
                } else {
                    db.update("UPDATE \"SalesRevenueDeferral\" SET quantity = ?, amount = ?, \"updatedAt\" = now() WHERE"
                            + " id = ? AND \"organizationId\" = ?", layer.wantQty, wantAmount, layer.id, org());
                }
                boolean out = da.signum() < 0 || (da.signum() == 0 && dq.signum() < 0);
                moves.add(new Object[] {layer.id, out ? "release" : "defer", dq.abs(), da.abs()});
                if (da.signum() == 0) {
                    continue;
                }
                // The revenue line mirrors the invoice's own (partner, cost centre and dimensions of the invoice line),
                // so revenue by customer, cost centre or dimension nets exactly.
                String desc = str(l.get("description"));
                String partner = str(o.get("partnerId"));
                String costCenter = layer.dimensions == null ? null : (String) layer.dimensions.get("costCenter");
                Map<String, Object> glDims = glDimensions(layer.dimensions);
                if (out) {
                    release.add(new Line(layer.liabilityAccountId, da.abs(), null, partner, desc, null, null, null));
                    release.add(new Line(layer.revenueAccountId, null, da.abs(), partner, desc, null, costCenter,
                            glDims));
                    releaseTotal = releaseTotal.add(da.abs());
                } else {
                    defer.add(new Line(layer.revenueAccountId, da, null, partner, desc, null, costCenter, glDims));
                    defer.add(new Line(layer.liabilityAccountId, null, da, partner, desc, null, null, null));
                    deferTotal = deferTotal.add(da);
                }
            }
        }
        String deferEntry = defer.isEmpty() ? null : post(DEFERRAL_SOURCE, o, cause, defer, deferTotal);
        String releaseEntry = release.isEmpty() ? null : post(RELEASE_SOURCE, o, cause, release, releaseTotal);
        for (Object[] m : moves) {
            BigDecimal amount = (BigDecimal) m[3];
            String entry = amount.signum() == 0 ? null : "release".equals(m[1]) ? releaseEntry : deferEntry;
            db.update("INSERT INTO \"SalesRevenueMovement\" (id, \"organizationId\", \"deferralId\", kind, quantity, amount,"
                    + " reason, \"sourceType\", \"sourceId\", \"journalEntryId\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?,"
                    + " ?, ?, ?, ?)", SalesDb.newId(), org(), m[0], m[1], m[2], amount, cause.reason(),
                    cause.sourceType(), cause.sourceId(), entry, SalesDb.user());
        }
    }

    private String post(String sourceType, Map<String, Object> o, Cause cause, List<Line> lines, BigDecimal total) {
        String what = DEFERRAL_SOURCE.equals(sourceType) ? "Revenue of " + o.get("number") + " deferred until delivery"
                : "Deferred revenue of " + o.get("number") + " released";
        String desc = what + (cause.detail() == null ? "" : " (" + cause.detail() + ")");
        Map<String, Object> entry = posting.post(new PostingRequest("GEN", posting.openDateOrToday(cause.date()), desc,
                null, null, sourceType, str(o.get("id")), null, sourceType + ":" + SalesDb.newId(), null, null, null,
                lines));
        return str(entry.get("id"));
    }

    // ------------------------------------------------------------------ figures

    /** Per order line of the order: product, net invoiced, net dispatched, returned-and-credited and written off. */
    private List<Map<String, Object>> figures(String orderId) {
        return raw("""
                SELECT l.id, l.description, l."productId", l."qtyReturnCredited" AS rc, l."qtyWrittenOff" AS w,
                       COALESCE((SELECT sum(k.quantity) FROM "SalesInvoiceLink" k JOIN "Document" d ON d.id = k."documentId"
                                  WHERE k."orderLineId" = l.id AND k.kind = 'regular'
                                    AND d.status::text IN ('posted', 'paid')), 0)
                       - COALESCE((SELECT sum(k.quantity) FROM "SalesInvoiceLink" k JOIN "Document" d ON d.id = k."documentId"
                                  WHERE k."orderLineId" = l.id AND k.kind = 'credit_note'
                                    AND d.status::text IN ('posted', 'paid')), 0) AS net,
                       COALESCE((SELECT sum(COALESCE(ll."dispatchedQty", 0) - COALESCE(ll."returnedQty", 0))
                                   FROM "FleetLoadLine" ll JOIN "FleetLoad" t ON t.id = ll."loadId"
                                   JOIN "FleetDeliveryOrderLine" dl ON dl.id = ll."deliveryOrderLineId"
                                   JOIN "FleetDeliveryOrder" fd ON fd.id = dl."deliveryOrderId"
                                  WHERE dl."sourceLineId" = l.id AND fd."sourceType" = 'sales_order'
                                    AND fd.status <> 'cancelled'
                                    AND t.status IN ('dispatched', 'in_transit', 'delivered', 'closed', 'returned')), 0)
                           AS dispatched
                  FROM "SalesOrderLine" l
                 WHERE l."orderId" = ? AND l."organizationId" = ?
                 ORDER BY l."sortOrder", l.id""", orderId, org());
    }

    /** {@code T} for one line of {@link #figures}: never below 0, never above what is net invoiced. */
    private static BigDecimal target(Map<String, Object> l) {
        BigDecimal net = dec(l.get("net"));
        BigDecimal kept = dec(l.get("dispatched")).subtract(dec(l.get("rc"))).subtract(dec(l.get("w")));
        return net.subtract(kept).min(net).max(BigDecimal.ZERO);
    }

    /** The stored layers of an order line, by invoice line. */
    private Map<String, Layer> layers(String lineId) {
        Map<String, Layer> out = new LinkedHashMap<>();
        for (Map<String, Object> r : raw("SELECT r.id, r.\"documentLineId\", r.\"documentId\", r.\"revenueAccountId\","
                + " r.\"liabilityAccountId\", r.\"unitAmount\", r.quantity, r.amount, dl.dimensions::text AS dims FROM"
                + " \"SalesRevenueDeferral\" r LEFT JOIN \"DocumentLine\" dl ON dl.id = r.\"documentLineId\" WHERE"
                + " r.\"orderLineId\" = ? AND r.\"organizationId\" = ?", lineId, org())) {
            Layer x = new Layer();
            x.id = str(r.get("id"));
            x.documentLineId = str(r.get("documentLineId"));
            x.documentId = str(r.get("documentId"));
            x.revenueAccountId = str(r.get("revenueAccountId"));
            x.liabilityAccountId = str(r.get("liabilityAccountId"));
            x.unitAmount = dec(r.get("unitAmount"));
            x.quantity = dec(r.get("quantity"));
            x.amount = dec(r.get("amount"));
            x.dimensions = dimensions(r.get("dims"));
            out.put(x.documentLineId, x);
        }
        return out;
    }

    /**
     * The posted invoice lines that bill the order line and carry revenue, newest post first; {@code wantQty} holds
     * each one's capacity (linked quantity less what credit notes against that invoice took back).
     */
    private List<Layer> candidates(String lineId) {
        List<Layer> out = new ArrayList<>();
        String liability = null;
        for (Map<String, Object> r : raw("""
                SELECT k."documentId", k."documentLineId", k.quantity AS linked, dl.subtotal, dl.quantity AS "docQty",
                       dl."accountId", dl.dimensions::text AS dims, c."incomeAccountId",
                       COALESCE(d."exchangeRate", (SELECT jl."exchangeRate" FROM "JournalLine" jl
                                                    WHERE jl."journalEntryId" = d."journalEntryId"
                                                      AND jl."exchangeRate" IS NOT NULL
                                                    ORDER BY jl."exchangeRate" LIMIT 1), 1) AS rate,
                       COALESCE((SELECT sum(ck.quantity) FROM "SalesInvoiceLink" ck JOIN "Document" cn ON cn.id = ck."documentId"
                                  WHERE ck."orderLineId" = k."orderLineId" AND ck.kind = 'credit_note'
                                    AND cn.status::text IN ('posted', 'paid') AND cn."reversedDocumentId" = k."documentId"), 0)
                           AS credited
                  FROM "SalesInvoiceLink" k
                  JOIN "Document" d ON d.id = k."documentId"
                  JOIN "DocumentLine" dl ON dl.id = k."documentLineId"
                  LEFT JOIN "Product" p ON p.id = dl."productId"
                  LEFT JOIN "ProductCategory" c ON c.id = p."categoryId"
                 WHERE k."orderLineId" = ? AND k."organizationId" = ? AND k.kind = 'regular'
                   AND d.status::text IN ('posted', 'paid')
                 ORDER BY d."postedAt" DESC NULLS LAST, d."createdAt" DESC, k."documentLineId" DESC""", lineId, org())) {
            BigDecimal docQty = dec(r.get("docQty"));
            BigDecimal subtotal = dec(r.get("subtotal"));
            if (docQty.signum() <= 0 || subtotal.signum() <= 0) {
                continue;
            }
            if (liability == null) {
                liability = accounts.ensureByCode("DEFERRED-REVENUE", "Deferred Revenue", "deferred_revenue",
                        "unearned_revenue");
            }
            Layer x = new Layer();
            x.documentLineId = str(r.get("documentLineId"));
            x.documentId = str(r.get("documentId"));
            x.revenueAccountId = determination.incomeAccount(str(r.get("accountId")), str(r.get("incomeAccountId")));
            x.liabilityAccountId = liability;
            x.unitAmount = subtotal.multiply(dec(r.get("rate"))).divide(docQty, 12, RoundingMode.HALF_UP);
            x.dimensions = dimensions(r.get("dims"));
            x.wantQty = dec(r.get("linked")).subtract(dec(r.get("credited"))).max(BigDecimal.ZERO);
            out.add(x);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Set<String> ordersOf(String documentId) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> r : raw("SELECT DISTINCT \"orderId\" FROM \"SalesInvoiceLink\" WHERE \"documentId\" = ?"
                + " AND \"organizationId\" = ? AND kind = 'regular' AND \"orderId\" IS NOT NULL", documentId, org())) {
            out.add(str(r.get("orderId")));
        }
        return out;
    }

    /** The invoice line's dimensions as the invoice's revenue line carried them (non-blank text values). */
    static Map<String, Object> dimensions(Object raw) {
        if (raw == null) {
            return null;
        }
        Object tree = com.inventoryerp.shared.web.EntityJson.parseJson(raw.toString());
        Map<String, Object> m = new TreeMap<>();
        if (tree instanceof tools.jackson.databind.JsonNode node && node.isObject()) {
            node.properties().forEach(e -> {
                if (e.getValue().isString() && !e.getValue().asString().isBlank()) {
                    m.put(e.getKey(), e.getValue().asString());
                }
            });
        }
        return m.isEmpty() ? null : m;
    }

    /** The dimensions of a GL line: the invoice line's, without the cost centre (it has its own column). */
    private static Map<String, Object> glDimensions(Map<String, Object> dims) {
        if (dims == null) {
            return null;
        }
        Map<String, Object> out = new TreeMap<>(dims);
        out.remove("costCenter");
        return out.isEmpty() ? null : out;
    }

    private int baseScale() {
        Map<String, Object> c = first(raw("SELECT \"decimalPlaces\" FROM \"Currency\" WHERE code = ?",
                posting.baseCurrency()));
        return c == null || c.get("decimalPlaces") == null ? 2 : ((Number) c.get("decimalPlaces")).intValue();
    }

    /** The business day of {@code at}, as the ledger dates its entries (midnight UTC of the local date). */
    private static Instant businessDay(Instant at) {
        return LocalDate.ofInstant(at, BusinessZone.get()).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static Instant today() {
        return businessDay(Instant.now());
    }

    /** Rows with exact numbers (the shared helpers turn decimals into JS numbers). */
    private List<Map<String, Object>> raw(String sql, Object... params) {
        return db.jdbc().sql(sql).params(java.util.Arrays.stream(params).map(SalesDb::param).toList()).query()
                .listOfRows();
    }

    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
