package com.inventoryerp.core.application;

import com.inventoryerp.core.application.PriceListService.PriceQuery;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Immutable pricing snapshots (ADR-S022 §4): when a transaction is committed commercially, every line's agreed price
 * and the central engine's breakdown at that moment are written to {@code PriceSnapshot} (insert-only, enforced by a
 * trigger). Price-list or rule changes afterwards never touch the document lines or their snapshots.
 *
 * <p>Document types: {@code sales_order} (confirmation), {@code invoice} (posting), {@code order} (billed to a POS
 * invoice), {@code delivery_order} (manual delivery order created). Recording the same agreed values twice is a no-op;
 * an amended price adds a new row, so the history stays complete.
 */
@Service
public class PriceSnapshotService {

    private record Source(String header, String lines) {}

    private static final Map<String, Source> SOURCES = Map.of(
            "sales_order", new Source(
                    "SELECT \"partnerId\", \"branchId\", \"siteId\", NULL::date AS \"on\" FROM \"SalesOrder\" WHERE id = ?"
                            + " AND \"organizationId\" = ?",
                    "SELECT id, \"productId\", quantity, \"uomId\", \"unitPrice\", \"discountPercent\", \"taxId\" FROM"
                            + " \"SalesOrderLine\" WHERE \"orderId\" = ? AND \"organizationId\" = ? ORDER BY \"sortOrder\", id"),
            "invoice", new Source(
                    "SELECT \"partnerId\", \"branchId\", NULL AS \"siteId\", \"issueDate\"::date AS \"on\" FROM \"Document\""
                            + " WHERE id = ? AND \"organizationId\" = ?",
                    "SELECT id, \"productId\", quantity, \"uomId\", \"unitPrice\", \"discountPercent\", \"taxId\" FROM"
                            + " \"DocumentLine\" WHERE \"documentId\" = ? AND \"organizationId\" = ? ORDER BY \"lineNumber\", id"),
            "order", new Source(
                    "SELECT \"partnerId\", \"branchId\", NULL AS \"siteId\", NULL::date AS \"on\" FROM \"Order\" WHERE id = ?"
                            + " AND \"organizationId\" = ?",
                    "SELECT id, \"productId\", quantity, NULL AS \"uomId\", \"unitPrice\", \"discountPercent\", \"taxId\""
                            + " FROM \"OrderItem\" WHERE \"orderId\" = ? AND \"organizationId\" = ? AND NOT cancelled ORDER BY"
                            + " \"lineNumber\", id"),
            "delivery_order", new Source(
                    "SELECT \"partnerId\", \"branchId\", \"siteId\", \"requestedDate\"::date AS \"on\" FROM"
                            + " \"FleetDeliveryOrder\" WHERE id = ? AND \"organizationId\" = ?",
                    "SELECT id, \"productId\", \"orderedQty\" AS quantity, \"uomId\", \"unitPrice\", \"discountPercent\","
                            + " \"taxId\" FROM \"FleetDeliveryOrderLine\" WHERE \"deliveryOrderId\" = ? AND \"organizationId\""
                            + " = ? ORDER BY \"lineNumber\", id"));

    private final JdbcClient jdbc;
    private final ObjectProvider<PricingEngine> engine;
    private final ObjectMapper mapper;

    public PriceSnapshotService(JdbcClient jdbc, ObjectProvider<PricingEngine> engine, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.engine = engine;
        this.mapper = mapper;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Snapshot every line of a document; returns the rows written (0 when they were already recorded). */
    @Transactional
    public int record(String documentType, String documentId) {
        Source s = SOURCES.get(documentType);
        if (s == null) {
            throw ApiException.badRequest("Unknown snapshot document type: " + documentType);
        }
        Map<String, Object> h = SqlRows.one(jdbc.sql(s.header()).params(documentId, org()));
        if (h == null) {
            return 0;
        }
        String partnerId = (String) h.get("partnerId");
        LocalDate on = h.get("on") instanceof java.sql.Date d ? d.toLocalDate() : LocalDate.now();
        PricingEngine e = engine.getIfAvailable();
        int written = 0;
        for (Map<String, Object> l : SqlRows.list(jdbc.sql(s.lines()).params(documentId, org()))) {
            BigDecimal qty = SqlRows.dec(l, "quantity");
            BigDecimal unit = SqlRows.dec(l, "unitPrice");
            BigDecimal disc = l.get("discountPercent") == null ? BigDecimal.ZERO : SqlRows.dec(l, "discountPercent");
            String productId = (String) l.get("productId");
            Map<String, Object> breakdown = new LinkedHashMap<>();
            boolean manual = true;
            // A walk-in POS ticket has no customer to price for: its terminal price is the snapshot.
            boolean priceable = productId != null && e != null && !("order".equals(documentType) && partnerId == null);
            PricingEngine.Quote q = null;
            if (priceable) {
                try {
                    q = e.quote(new PriceQuery(partnerId, productId, qty, (String) l.get("uomId"),
                            (String) h.get("branchId"), (String) h.get("siteId"), on));
                } catch (ApiException unpriceable) {
                    q = null; // e.g. a unit with no conversion: the agreed price stands alone
                }
            }
            if (q != null) {
                breakdown.putAll(q.breakdown());
                BigDecimal enginePrice = q.unitPrice() != null ? q.unitPrice()
                        : new BigDecimal(String.valueOf(q.breakdown().get("basePrice")));
                manual = enginePrice.setScale(4, RoundingMode.HALF_UP).compareTo(unit.setScale(4, RoundingMode.HALF_UP))
                        != 0 || q.discountPercent().compareTo(disc) != 0;
            } else {
                breakdown.put("source", priceable ? "manual" : "pos_terminal");
            }
            breakdown.put("agreed", Map.of("unitPrice", JsNumbers.js(unit), "discountPercent", JsNumbers.js(disc),
                    "quantity", JsNumbers.js(qty)));
            String hash = hash(String.join("|", productId == null ? "" : productId, plain(qty), plain(unit), plain(disc),
                    String.valueOf(l.get("taxId"))));
            written += jdbc.sql("INSERT INTO \"PriceSnapshot\" (id, \"organizationId\", \"documentType\", \"documentId\","
                    + " \"lineId\", \"productId\", \"partnerId\", quantity, \"unitPrice\", \"discountPercent\", \"taxId\","
                    + " breakdown, \"manualPrice\", \"agreedHash\", \"createdById\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                    + " ?, ?::jsonb, ?, ?, ?) ON CONFLICT (\"organizationId\", \"documentType\", \"documentId\", \"lineId\","
                    + " \"agreedHash\") DO NOTHING").params(UUID.randomUUID().toString(), org(), documentType, documentId,
                    l.get("id"), productId, partnerId, qty, unit, disc, l.get("taxId"), json(breakdown), manual, hash,
                    TenantContext.userId()).update();
        }
        return written;
    }

    /** The snapshots of one document, oldest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String documentType, String documentId) {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"PriceSnapshot\" WHERE \"organizationId\" = ? AND \"documentType\" = ?"
                + " AND \"documentId\" = ? ORDER BY \"createdAt\", id").params(org(), documentType, documentId));
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private String json(Object v) {
        return mapper.writeValueAsString(v);
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
