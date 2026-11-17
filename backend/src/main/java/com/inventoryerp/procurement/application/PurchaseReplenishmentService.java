package com.inventoryerp.procurement.application;

import com.inventoryerp.core.application.ProductVariantService;
import com.inventoryerp.core.application.UomConversionService;
import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Replenishment (ADR-PROCUREMENT-001 wave 7): reorder rules per product and store, a suggestion engine and raising
 * purchase requests from the suggestions (demand source {@code reorder}).
 *
 * <p>Position = on hand at the store + open purchase-order quantity + open request quantity for the product. Average
 * daily use = outflows of the last 90 days at the store (issues, consumption, waste — not transfers or reversals) / 90.
 * <ul>
 *   <li>{@code min_max}: suggest when position &lt; min; order up to max.</li>
 *   <li>{@code reorder_point}: reorder point = daily use × lead time + safety stock; suggest when position ≤ reorder
 *       point; order up to max(max, reorder point + daily use × lead time).</li>
 * </ul>
 * Quantities round up to the rule's order multiple.
 *
 * <p>h-04 F10 (readiness gaps 2026-11-17): the open order and request quantities are those for the rule's store, as
 * in the inventory replenishment scan ({@link PurchaseIncomingSupply}): an order line counts for the store its request
 * line delivers to, a request line for its delivery location, both in the product's base unit; lines with no store
 * count only for a product's only rule. A rule's branch and preferred supplier must belong to the organisation, and
 * raising locks the rules before it re-reads the suggestions, so two raises at once cannot both order the same gap.
 */
@Service
public class PurchaseReplenishmentService {

    /** Rule body. */
    public record RuleInput(String productId, String locationId, String branchId, String method,
            BigDecimal minQuantity, BigDecimal maxQuantity, BigDecimal safetyStock, Integer leadTimeDays,
            BigDecimal orderMultiple, String preferredSupplierId, Boolean autoRaise, Boolean isActive, String variantId) {

        public RuleInput(String productId, String locationId, String branchId, String method, BigDecimal minQuantity,
                BigDecimal maxQuantity, BigDecimal safetyStock, Integer leadTimeDays, BigDecimal orderMultiple,
                String preferredSupplierId, Boolean autoRaise, Boolean isActive) {
            this(productId, locationId, branchId, method, minQuantity, maxQuantity, safetyStock, leadTimeDays,
                    orderMultiple, preferredSupplierId, autoRaise, isActive, null);
        }
    }

    private static final BigDecimal DAYS = BigDecimal.valueOf(90);

    private final JdbcClient jdbc;
    private final PurchaseRequestsService requests;
    private final AuditService audit;
    private final TransactionTemplate tx;

    private final ProductVariantService variants;
    private final UomConversionService uom;

    public PurchaseReplenishmentService(JdbcClient jdbc, PurchaseRequestsService requests, AuditService audit,
            PlatformTransactionManager txm, ProductVariantService variants, UomConversionService uom) {
        this.jdbc = jdbc;
        this.variants = variants;
        this.uom = uom;
        this.requests = requests;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    /** Create or update the rule for (product, store). */
    public Map<String, Object> upsert(RuleInput in) {
        String orgId = org();
        if (jdbc.sql("SELECT count(*) FROM \"Product\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(in.productId(), orgId).query(Long.class).single() == 0) {
            throw ApiException.notFound("Product not found");
        }
        String storeBranch = jdbc.sql("SELECT COALESCE(\"branchId\", '') FROM \"InventoryLocation\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(in.locationId(), orgId)
                .query(String.class).optional().orElse(null);
        if (storeBranch == null) {
            throw ApiException.notFound("Store not found");
        }
        // h-04 F10: ids from the body must belong to this organisation, and the branch must be the store's own.
        String branchId = in.branchId() == null || in.branchId().isBlank() ? null : in.branchId();
        if (branchId != null) {
            if (jdbc.sql("SELECT count(*) FROM \"Branch\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\""
                    + " IS NULL").params(branchId, orgId).query(Long.class).single() == 0) {
                throw ApiException.notFound("Branch not found");
            }
            if (!storeBranch.isEmpty() && !storeBranch.equals(branchId)) {
                throw ApiException.badRequest("The store belongs to another branch; use the store's own branch or"
                        + " leave the branch empty");
            }
        }
        String supplierId = in.preferredSupplierId() == null || in.preferredSupplierId().isBlank() ? null
                : in.preferredSupplierId();
        if (supplierId != null && jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(supplierId, orgId).query(Long.class).single() == 0) {
            throw ApiException.notFound("Supplier not found");
        }
        // Phase I2 (audit V-10, D2): a rule for a product with variants watches one active variant of its own.
        String variantId = in.variantId() == null || in.variantId().isBlank() ? null : in.variantId();
        variants.resolve(in.productId(), variantId);
        BigDecimal min = nz(in.minQuantity());
        BigDecimal max = nz(in.maxQuantity());
        if (max.compareTo(min) < 0) {
            throw ApiException.badRequest("Maximum must not be below minimum");
        }
        String id = jdbc.sql("INSERT INTO \"ReorderRule\" (id, \"organizationId\", \"productId\", \"locationId\", \"branchId\","
                        + " method, \"minQuantity\", \"maxQuantity\", \"safetyStock\", \"leadTimeDays\", \"orderMultiple\","
                        + " \"preferredSupplierId\", \"autoRaise\", \"isActive\", \"createdBy\", \"variantId\", \"createdAt\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ", " + NOW
                        + ") ON CONFLICT (\"organizationId\", \"productId\", (COALESCE(\"variantId\", '')), \"locationId\")"
                        + " DO UPDATE SET \"branchId\" ="
                        + " EXCLUDED.\"branchId\", method = EXCLUDED.method, \"minQuantity\" = EXCLUDED.\"minQuantity\","
                        + " \"maxQuantity\" = EXCLUDED.\"maxQuantity\", \"safetyStock\" = EXCLUDED.\"safetyStock\","
                        + " \"leadTimeDays\" = EXCLUDED.\"leadTimeDays\", \"orderMultiple\" = EXCLUDED.\"orderMultiple\","
                        + " \"preferredSupplierId\" = EXCLUDED.\"preferredSupplierId\", \"autoRaise\" = EXCLUDED.\"autoRaise\","
                        + " \"isActive\" = EXCLUDED.\"isActive\", \"updatedAt\" = " + NOW + " RETURNING id")
                .params(UUID.randomUUID().toString(), orgId, in.productId(), in.locationId(), branchId,
                        in.method() == null ? "min_max" : in.method(), min, max, nz(in.safetyStock()),
                        in.leadTimeDays() == null ? 7 : in.leadTimeDays(), in.orderMultiple(), supplierId,
                        Boolean.TRUE.equals(in.autoRaise()), in.isActive() == null || in.isActive(), user(), variantId)
                .query(String.class).single();
        audit.record(AuditInput.of("ReorderRule", id, "update", null, Obj.of("productId", in.productId(), "locationId",
                in.locationId(), "min", JsNumbers.dec(min), "max", JsNumbers.dec(max))));
        return rule(id);
    }

    /** Delete a rule. */
    public void remove(String id) {
        if (jdbc.sql("DELETE FROM \"ReorderRule\" WHERE id = ? AND \"organizationId\" = ?").params(id, org()).update() == 0) {
            throw ApiException.notFound("Reorder rule not found");
        }
    }

    /** Rules with product and store names. */
    public List<Map<String, Object>> rules() {
        return SqlRows.list(jdbc.sql(RULES + " WHERE r.\"organizationId\" = ? ORDER BY p.name, l.name").param(org()));
    }

    /** Suggestions for every active rule whose stock position is low (all rules when {@code all}). */
    public List<Map<String, Object>> suggestions(boolean all) {
        return suggestions(all, false);
    }

    /** {@link #suggestions(boolean)}; {@code lock}: lock the rules first (inside the caller's transaction). */
    private List<Map<String, Object>> suggestions(boolean all, boolean lock) {
        List<Map<String, Object>> rules = SqlRows.list(jdbc.sql(RULES + " WHERE r.\"organizationId\" = ? AND r.\"isActive\""
                + " ORDER BY p.name, l.name, r.id" + (lock ? " FOR UPDATE OF r" : "")).param(org()));
        Map<String, Integer> perProduct = new java.util.HashMap<>();
        for (Map<String, Object> r : rules) {
            perProduct.merge(r.get("productId") + "|" + r.get("variantId"), 1, Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rules) {
            Map<String, Object> s = evaluate(r, perProduct.get(r.get("productId") + "|" + r.get("variantId")) == 1);
            if (all || Boolean.TRUE.equals(s.get("needed"))) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Raise one draft purchase request from the given rules' current suggestions (or every needed suggestion when
     * {@code ruleIds} is empty). Returns the request, or {@code null} when nothing is needed.
     */
    public Map<String, Object> raise(List<String> ruleIds, boolean autoOnly) {
        // h-04 F10: lock the rules, then read the suggestions: a second raise at the same time waits for this one and
        // then sees its request as open supply, instead of ordering the same gap again.
        return tx.execute(st -> {
            List<Map<String, Object>> picked = new ArrayList<>();
            for (Map<String, Object> s : suggestions(false, true)) {
                boolean listed = ruleIds == null || ruleIds.isEmpty() || ruleIds.contains((String) s.get("ruleId"));
                if (listed && (!autoOnly || Boolean.TRUE.equals(s.get("autoRaise")))) {
                    picked.add(s);
                }
            }
            if (picked.isEmpty()) {
                return null;
            }
            List<PurchaseRequestsService.LineInput> lines = new ArrayList<>();
            for (Map<String, Object> s : picked) {
                lines.add(new PurchaseRequestsService.LineInput((String) s.get("productId"), "Reorder: " + s.get("productName")
                        + " for " + s.get("locationName"), new BigDecimal((String) s.get("suggestedQuantity")), null,
                        s.get("lastPrice") == null ? null : new BigDecimal((String) s.get("lastPrice")), null, null,
                        (String) s.get("locationId"), (String) s.get("preferredSupplierId"), null, null, "reorder",
                        (String) s.get("ruleId"), (String) s.get("variantId")));
            }
            String branchId = (String) picked.getFirst().get("branchId");
            Map<String, Object> pr = requests.create(new PurchaseRequestsService.CreateInput(null, branchId,
                    "Replenishment " + java.time.LocalDate.now(), null, lines, null));
            jdbc.sql("UPDATE \"ReorderRule\" SET \"lastRaisedAt\" = " + NOW + " WHERE \"organizationId\" = ? AND id ="
                    + " ANY(?::text[])").params(org(), picked.stream().map(s -> (String) s.get("ruleId"))
                    .toArray(String[]::new)).update();
            audit.recordInTx(AuditInput.of("PurchaseRequest", (String) pr.get("id"), "create", null,
                    Obj.of("source", "replenishment", "rules", picked.size(), "by", user() == null ? "job" : user())));
            return pr;
        });
    }

    private Map<String, Object> evaluate(Map<String, Object> r, boolean onlyRule) {
        String orgId = org();
        String productId = (String) r.get("productId");
        // Phase I2 (audit V-10): a variant rule measures that variant's stock, orders, requests and use.
        String variantId = (String) r.get("variantId");
        BigDecimal onHand = Money.dec(jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ?"
                + " AND (?::text IS NULL OR \"variantId\" = ?::text)").params(orgId, productId,
                r.get("locationId"), variantId, variantId).query(BigDecimal.class).single());
        // h-04 F10: open orders and requests for this store only (see the class comment), in the base unit.
        Object store = r.get("locationId");
        BigDecimal onOrder = Money.dec(jdbc.sql("SELECT COALESCE(SUM(GREATEST(0, l.quantity - l.\"receivedQuantity\""
                + " - l.\"cancelledQuantity\") * COALESCE(l.\"uomFactor\", 1)), 0) FROM \"PurchaseOrderLine\" l JOIN"
                + " \"PurchaseOrder\" o ON o.id = l.\"purchaseOrderId\""
                + " LEFT JOIN \"PurchaseRequestLine\" rl ON rl.id = l.\"requestLineId\" AND rl.\"organizationId\" = l.\"organizationId\""
                + " WHERE l.\"organizationId\" = ? AND l.\"productId\" = ?"
                + " AND o.status::text IN ('draft', 'active', 'partially_received') AND o.\"deletedAt\" IS NULL"
                + " AND (?::text IS NULL OR l.\"variantId\" = ?::text)"
                + " AND (rl.\"deliveryLocationId\" = ? OR (? AND rl.\"deliveryLocationId\" IS NULL))")
                .params(orgId, productId, variantId, variantId, store, onlyRule).query(BigDecimal.class).single());
        BigDecimal requested = BigDecimal.ZERO;
        for (Map<String, Object> u : SqlRows.list(jdbc.sql("SELECT l.\"unitOfMeasureId\" AS uom,"
                + " COALESCE(SUM(GREATEST(0, l.quantity - l.\"orderedQuantity\")), 0) AS qty"
                + " FROM \"PurchaseRequestLine\" l JOIN \"PurchaseRequest\" q ON q.id = l.\"purchaseRequestId\" WHERE"
                + " l.\"organizationId\" = ? AND l.\"productId\" = ? AND q.status::text IN ('draft', 'submitted', 'approved')"
                + " AND q.\"deletedAt\" IS NULL AND (?::text IS NULL OR l.\"variantId\" = ?::text)"
                + " AND (l.\"deliveryLocationId\" = ? OR (? AND l.\"deliveryLocationId\" IS NULL)) GROUP BY 1")
                .params(orgId, productId, variantId, variantId, store, onlyRule))) {
            BigDecimal factor = uom.tryFactorToBase(productId, (String) u.get("uom"));
            requested = requested.add(SqlRows.dec(u, "qty").multiply(factor == null ? BigDecimal.ONE : factor));
        }
        BigDecimal used = Money.dec(jdbc.sql("SELECT COALESCE(-SUM(\"quantityChange\"), 0) FROM \"InventoryLedger\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ? AND \"quantityChange\" < 0 AND"
                + " type::text NOT IN ('transfer_out', 'reversal_out', 'return_to_supplier') AND \"createdAt\" >= "
                + NOW + " - interval '90 days' AND (?::text IS NULL OR \"variantId\" = ?::text)")
                .params(orgId, productId, r.get("locationId"), variantId, variantId).query(BigDecimal.class).single());
        BigDecimal daily = used.divide(DAYS, 6, RoundingMode.HALF_UP);
        BigDecimal lead = BigDecimal.valueOf(((Number) r.get("leadTimeDays")).longValue());
        BigDecimal safety = Money.dec(r.get("safetyStock"));
        BigDecimal min = Money.dec(r.get("minQuantity"));
        BigDecimal max = Money.dec(r.get("maxQuantity"));
        BigDecimal position = onHand.add(onOrder).add(requested);
        BigDecimal reorderPoint;
        BigDecimal target;
        boolean needed;
        if ("reorder_point".equals(r.get("method"))) {
            reorderPoint = daily.multiply(lead).add(safety);
            target = max.max(reorderPoint.add(daily.multiply(lead)));
            needed = position.compareTo(reorderPoint) <= 0 && target.compareTo(position) > 0;
        } else {
            reorderPoint = min;
            target = max;
            needed = position.compareTo(min) < 0;
        }
        BigDecimal qty = needed ? target.subtract(position).max(BigDecimal.ZERO) : BigDecimal.ZERO;
        BigDecimal multiple = r.get("orderMultiple") == null ? null : Money.dec(r.get("orderMultiple"));
        if (multiple != null && multiple.signum() > 0 && qty.signum() > 0) {
            qty = qty.divide(multiple, 0, RoundingMode.CEILING).multiply(multiple);
        }
        if (qty.signum() == 0) {
            needed = false;
        }
        Map<String, Object> last = SqlRows.one(jdbc.sql("SELECT \"unitPrice\", \"partnerId\" FROM \"SupplierPrice\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND (?::text IS NULL OR \"partnerId\" = ?) ORDER BY"
                + " \"validFrom\" DESC, \"createdAt\" DESC LIMIT 1").params(orgId, productId, r.get("preferredSupplierId"),
                r.get("preferredSupplierId")));
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("ruleId", r.get("id"));
        s.put("productId", productId);
        s.put("productName", r.get("productName"));
        s.put("productCode", r.get("productCode"));
        if (variantId != null) {
            s.put("variantId", variantId);
            s.put("variantName", r.get("variantName"));
        }
        s.put("locationId", r.get("locationId"));
        s.put("locationName", r.get("locationName"));
        s.put("branchId", r.get("branchId"));
        s.put("method", r.get("method"));
        s.put("onHand", JsNumbers.dec(Money.round(onHand, 4)));
        s.put("onOrder", JsNumbers.dec(Money.round(onOrder, 4)));
        s.put("openRequests", JsNumbers.dec(Money.round(requested, 4)));
        s.put("position", JsNumbers.dec(Money.round(position, 4)));
        s.put("averageDailyUse", JsNumbers.dec(Money.round(daily, 4)));
        s.put("reorderPoint", JsNumbers.dec(Money.round(reorderPoint, 4)));
        s.put("target", JsNumbers.dec(Money.round(target, 4)));
        s.put("suggestedQuantity", JsNumbers.dec(Money.round(qty, 4)));
        s.put("daysOfCover", daily.signum() == 0 ? null
                : JsNumbers.dec(onHand.divide(daily, 1, RoundingMode.HALF_UP)));
        s.put("needed", needed);
        s.put("preferredSupplierId", r.get("preferredSupplierId") != null ? r.get("preferredSupplierId")
                : last == null ? null : last.get("partnerId"));
        s.put("preferredSupplierName", r.get("supplierName"));
        s.put("lastPrice", last == null ? null : JsNumbers.dec(Money.dec(last.get("unitPrice"))));
        s.put("autoRaise", r.get("autoRaise"));
        s.put("lastRaisedAt", r.get("lastRaisedAt"));
        return s;
    }

    private Map<String, Object> rule(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql(RULES + " WHERE r.id = ? AND r.\"organizationId\" = ?")
                .params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Reorder rule not found");
        }
        return r;
    }

    private static final String RULES = "SELECT r.*, p.name AS \"productName\", p.code AS \"productCode\", l.name AS"
            + " \"locationName\", s.name AS \"supplierName\", pv.name AS \"variantName\" FROM \"ReorderRule\" r"
            + " JOIN \"Product\" p ON p.id = r.\"productId\" LEFT JOIN \"ProductVariant\" pv ON pv.id = r.\"variantId\""
            + " JOIN \"InventoryLocation\" l ON l.id = r.\"locationId\" LEFT JOIN \"Partner\" s ON s.id ="
            + " r.\"preferredSupplierId\" AND s.\"organizationId\" = r.\"organizationId\"";

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
