package com.inventoryerp.procurement.application;

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
 */
@Service
public class ReplenishmentService {

    /** Rule body. */
    public record RuleInput(String productId, String locationId, String branchId, String method,
            BigDecimal minQuantity, BigDecimal maxQuantity, BigDecimal safetyStock, Integer leadTimeDays,
            BigDecimal orderMultiple, String preferredSupplierId, Boolean autoRaise, Boolean isActive) {}

    private static final BigDecimal DAYS = BigDecimal.valueOf(90);

    private final JdbcClient jdbc;
    private final PurchaseRequestsService requests;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public ReplenishmentService(JdbcClient jdbc, PurchaseRequestsService requests, AuditService audit,
            PlatformTransactionManager txm) {
        this.jdbc = jdbc;
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
        if (jdbc.sql("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND \"organizationId\" = ?")
                .params(in.locationId(), orgId).query(Long.class).single() == 0) {
            throw ApiException.notFound("Store not found");
        }
        BigDecimal min = nz(in.minQuantity());
        BigDecimal max = nz(in.maxQuantity());
        if (max.compareTo(min) < 0) {
            throw ApiException.badRequest("Maximum must not be below minimum");
        }
        String id = jdbc.sql("INSERT INTO \"ReorderRule\" (id, \"organizationId\", \"productId\", \"locationId\", \"branchId\","
                        + " method, \"minQuantity\", \"maxQuantity\", \"safetyStock\", \"leadTimeDays\", \"orderMultiple\","
                        + " \"preferredSupplierId\", \"autoRaise\", \"isActive\", \"createdBy\", \"createdAt\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ", " + NOW + ") ON CONFLICT"
                        + " (\"organizationId\", \"productId\", \"locationId\") DO UPDATE SET \"branchId\" ="
                        + " EXCLUDED.\"branchId\", method = EXCLUDED.method, \"minQuantity\" = EXCLUDED.\"minQuantity\","
                        + " \"maxQuantity\" = EXCLUDED.\"maxQuantity\", \"safetyStock\" = EXCLUDED.\"safetyStock\","
                        + " \"leadTimeDays\" = EXCLUDED.\"leadTimeDays\", \"orderMultiple\" = EXCLUDED.\"orderMultiple\","
                        + " \"preferredSupplierId\" = EXCLUDED.\"preferredSupplierId\", \"autoRaise\" = EXCLUDED.\"autoRaise\","
                        + " \"isActive\" = EXCLUDED.\"isActive\", \"updatedAt\" = " + NOW + " RETURNING id")
                .params(UUID.randomUUID().toString(), orgId, in.productId(), in.locationId(), in.branchId(),
                        in.method() == null ? "min_max" : in.method(), min, max, nz(in.safetyStock()),
                        in.leadTimeDays() == null ? 7 : in.leadTimeDays(), in.orderMultiple(), in.preferredSupplierId(),
                        Boolean.TRUE.equals(in.autoRaise()), in.isActive() == null || in.isActive(), user())
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
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql(RULES + " WHERE r.\"organizationId\" = ? AND r.\"isActive\""
                + " ORDER BY p.name, l.name").param(org()))) {
            Map<String, Object> s = evaluate(r);
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
        List<Map<String, Object>> picked = new ArrayList<>();
        for (Map<String, Object> s : suggestions(false)) {
            boolean listed = ruleIds == null || ruleIds.isEmpty() || ruleIds.contains((String) s.get("ruleId"));
            if (listed && (!autoOnly || Boolean.TRUE.equals(s.get("autoRaise")))) {
                picked.add(s);
            }
        }
        if (picked.isEmpty()) {
            return null;
        }
        return tx.execute(st -> {
            List<PurchaseRequestsService.LineInput> lines = new ArrayList<>();
            for (Map<String, Object> s : picked) {
                lines.add(new PurchaseRequestsService.LineInput((String) s.get("productId"), "Reorder: " + s.get("productName")
                        + " for " + s.get("locationName"), new BigDecimal((String) s.get("suggestedQuantity")), null,
                        s.get("lastPrice") == null ? null : new BigDecimal((String) s.get("lastPrice")), null, null,
                        (String) s.get("locationId"), (String) s.get("preferredSupplierId"), null, null, "reorder",
                        (String) s.get("ruleId")));
            }
            String branchId = (String) picked.getFirst().get("branchId");
            Map<String, Object> pr = requests.create(new PurchaseRequestsService.CreateInput(null, branchId,
                    "Replenishment " + java.time.LocalDate.now(), null, lines));
            jdbc.sql("UPDATE \"ReorderRule\" SET \"lastRaisedAt\" = " + NOW + " WHERE \"organizationId\" = ? AND id ="
                    + " ANY(?::text[])").params(org(), picked.stream().map(s -> (String) s.get("ruleId"))
                    .toArray(String[]::new)).update();
            audit.recordInTx(AuditInput.of("PurchaseRequest", (String) pr.get("id"), "create", null,
                    Obj.of("source", "replenishment", "rules", picked.size(), "by", user() == null ? "job" : user())));
            return pr;
        });
    }

    private Map<String, Object> evaluate(Map<String, Object> r) {
        String orgId = org();
        String productId = (String) r.get("productId");
        BigDecimal onHand = Money.dec(jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ?").params(orgId, productId,
                r.get("locationId")).query(BigDecimal.class).single());
        BigDecimal onOrder = Money.dec(jdbc.sql("SELECT COALESCE(SUM(GREATEST(0, l.quantity - l.\"receivedQuantity\""
                + " - l.\"cancelledQuantity\") * COALESCE(l.\"uomFactor\", 1)), 0) FROM \"PurchaseOrderLine\" l JOIN"
                + " \"PurchaseOrder\" o ON o.id = l.\"purchaseOrderId\" WHERE l.\"organizationId\" = ? AND l.\"productId\" = ?"
                + " AND o.status::text IN ('draft', 'active', 'partially_received') AND o.\"deletedAt\" IS NULL")
                .params(orgId, productId).query(BigDecimal.class).single());
        BigDecimal requested = Money.dec(jdbc.sql("SELECT COALESCE(SUM(GREATEST(0, l.quantity - l.\"orderedQuantity\")), 0)"
                + " FROM \"PurchaseRequestLine\" l JOIN \"PurchaseRequest\" q ON q.id = l.\"purchaseRequestId\" WHERE"
                + " l.\"organizationId\" = ? AND l.\"productId\" = ? AND q.status::text IN ('draft', 'submitted', 'approved')"
                + " AND q.\"deletedAt\" IS NULL").params(orgId, productId).query(BigDecimal.class).single());
        BigDecimal used = Money.dec(jdbc.sql("SELECT COALESCE(-SUM(\"quantityChange\"), 0) FROM \"InventoryLedger\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ? AND \"quantityChange\" < 0 AND"
                + " type::text NOT IN ('transfer_out', 'reversal_out', 'return_to_supplier') AND \"createdAt\" >= "
                + NOW + " - interval '90 days'").params(orgId, productId, r.get("locationId")).query(BigDecimal.class)
                .single());
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
            + " \"locationName\", s.name AS \"supplierName\" FROM \"ReorderRule\" r JOIN \"Product\" p ON p.id = r.\"productId\""
            + " JOIN \"InventoryLocation\" l ON l.id = r.\"locationId\" LEFT JOIN \"Partner\" s ON s.id ="
            + " r.\"preferredSupplierId\"";

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
