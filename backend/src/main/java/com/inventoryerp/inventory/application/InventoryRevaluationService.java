package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.inventory.application.CostLineageService.AdjustmentInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory revaluation and IAS 2 net-realisable-value review documents (ADR-S031), draft → posted → reversed.
 *
 * <ul>
 *   <li><b>revaluation</b>: sets the unit cost of AVCO / FIFO stock at a location (all active lots for FIFO). Dr / Cr
 *       the product's stock account against the revaluation account (5968 or the document's). STANDARD items are
 *       revalued by activating a new standard; serial items are not revalued here.</li>
 *   <li><b>nrv</b>: per line the net realisable value per unit; the allowance is <em>remeasured</em> to
 *       max(0, carrying − NRV) × quantity: a larger gap writes down (Dr 5975 / Cr 1397 allowance), a smaller one
 *       reverses — never beyond the allowance already held (IAS 2.33). The allowance is a contra-inventory adjunct
 *       released to cost of sales as the goods are consumed.</li>
 * </ul>
 *
 * <p>Every posting writes value-only stock-ledger rows and a {@code CostValueAdjustment}; reversal is refused once stock
 * has moved (revaluation) or the allowance was partly released (NRV) — post a new document instead.
 */
@Service
public class InventoryRevaluationService {

    /** One document line. */
    public record LineInput(String productId, String variantId, String locationId, BigDecimal unitCostAfter,
            BigDecimal netRealisableValue) {}

    /** Document body. */
    public record RevaluationInput(String kind, String date, String reason, String accountId, List<LineInput> lines) {}

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final PostingService posting;
    private final StockPostingService stockPosting;
    private final CostLineageService lineage;
    private final AuditService audit;

    public InventoryRevaluationService(JdbcClient jdbc, SequenceService seq, PostingService posting,
            StockPostingService stockPosting, CostLineageService lineage, AuditService audit) {
        this.jdbc = jdbc;
        this.seq = seq;
        this.posting = posting;
        this.stockPosting = stockPosting;
        this.lineage = lineage;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    // ---- queries ----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String kind) {
        if (kind != null && !kind.isBlank()) {
            return SqlRows.list(jdbc.sql("SELECT * FROM \"InventoryRevaluation\" WHERE \"organizationId\" = ? AND kind = ?"
                    + " ORDER BY \"createdAt\" DESC").params(org(), kind));
        }
        return SqlRows.list(jdbc.sql("SELECT * FROM \"InventoryRevaluation\" WHERE \"organizationId\" = ? ORDER BY"
                + " \"createdAt\" DESC").param(org()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> doc = doc(id, false);
        doc.put("lines", SqlRows.list(jdbc.sql("SELECT l.*, p.name AS \"productName\", p.\"costingMethod\"::text AS"
                + " \"costingMethod\" FROM \"InventoryRevaluationLine\" l JOIN \"Product\" p ON p.id = l.\"productId\""
                + " WHERE l.\"revaluationId\" = ? ORDER BY p.name").param(id)));
        doc.put("adjustments", lineage.adjustments("nrv".equals(doc.get("kind")) ? "inventory_nrv"
                : "inventory_revaluation", id));
        return doc;
    }

    private Map<String, Object> doc(String id, boolean lock) {
        Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT * FROM \"InventoryRevaluation\" WHERE id = ? AND"
                + " \"organizationId\" = ?" + (lock ? " FOR UPDATE" : "")).params(id, org()));
        if (d == null) {
            throw ApiException.notFound("Revaluation not found");
        }
        return d;
    }

    // ---- commands ---------------------------------------------------------------------------------------------------

    /** Create a draft. */
    @Transactional
    public Map<String, Object> create(RevaluationInput in) {
        String kind = in.kind() == null ? "revaluation" : in.kind();
        if (!"revaluation".equals(kind) && !"nrv".equals(kind)) {
            throw ApiException.badRequest("kind must be one of the following values: revaluation, nrv");
        }
        LocalDate date = day(in.date());
        if (in.lines() == null || in.lines().isEmpty()) {
            throw ApiException.badRequest("Add at least one line");
        }
        if (in.accountId() != null && jdbc.sql("SELECT count(*) FROM \"Account\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(in.accountId(), org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Account not found");
        }
        String id = UUID.randomUUID().toString();
        String number = "nrv".equals(kind) ? seq.next("inventory_nrv", "NRV/", 5)
                : seq.next("inventory_revaluation", "IRV/", 5);
        jdbc.sql("INSERT INTO \"InventoryRevaluation\" (id, \"organizationId\", number, kind, \"revaluationDate\", reason,"
                        + " \"accountId\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), number, kind, java.sql.Date.valueOf(date), in.reason(), in.accountId(),
                        TenantContext.userId())
                .update();
        for (LineInput l : in.lines()) {
            Map<String, Object> product = product(l.productId());
            String method = (String) product.get("method");
            if (l.locationId() == null || jdbc.sql("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(l.locationId(), org()).query(Long.class).single() == 0) {
                throw ApiException.badRequest("Location not found");
            }
            if ("revaluation".equals(kind)) {
                if ("STANDARD".equals(method)) {
                    throw ApiException.badRequest(product.get("name") + " is carried at standard cost; revalue it by"
                            + " activating a new standard cost");
                }
                if (Boolean.TRUE.equals(product.get("serialTracking"))) {
                    throw ApiException.badRequest(product.get("name") + " is serial-tracked; serials keep their own cost");
                }
                if (l.unitCostAfter() == null || l.unitCostAfter().signum() < 0) {
                    throw ApiException.badRequest("unitCostAfter must not be less than 0");
                }
            } else if (l.netRealisableValue() == null || l.netRealisableValue().signum() < 0) {
                throw ApiException.badRequest("netRealisableValue must not be less than 0");
            }
            BigDecimal[] carrying = carrying(product, l.productId(), l.variantId(), l.locationId());
            jdbc.sql("INSERT INTO \"InventoryRevaluationLine\" (id, \"organizationId\", \"revaluationId\", \"productId\","
                            + " \"variantId\", \"locationId\", quantity, \"unitCostBefore\", \"unitCostAfter\","
                            + " \"netRealisableValue\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), id, l.productId(), l.variantId(), l.locationId(),
                            carrying[0], carrying[1], l.unitCostAfter(), l.netRealisableValue())
                    .update();
        }
        audit.recordInTx(AuditInput.of("InventoryRevaluation", id, "create", null, Obj.of("number", number, "kind",
                kind)));
        return get(id);
    }

    /** Delete a draft. */
    @Transactional
    public Map<String, Object> delete(String id) {
        Map<String, Object> d = doc(id, true);
        if (!"draft".equals(d.get("status"))) {
            throw ApiException.badRequest("Only a draft revaluation can be deleted");
        }
        jdbc.sql("DELETE FROM \"InventoryRevaluation\" WHERE id = ?").param(id).update();
        audit.recordInTx(AuditInput.of("InventoryRevaluation", id, "delete", d, null));
        return Obj.of("id", id, "deleted", true);
    }

    /** Post: re-measure every line at the current quantity, adjust costs / allowance, one journal entry. */
    @Transactional
    public Map<String, Object> post(String id) {
        Map<String, Object> d = doc(id, true);
        if (!"draft".equals(d.get("status"))) {
            throw ApiException.badRequest("Only a draft revaluation can be posted");
        }
        boolean nrv = "nrv".equals(d.get("kind"));
        String sourceType = nrv ? "inventory_nrv" : "inventory_revaluation";
        Instant date = ((LocalDate) toDate(d.get("revaluationDate"))).atStartOfDay(ZoneOffset.UTC).toInstant();
        String offset = d.get("accountId") != null ? (String) d.get("accountId")
                : nrv ? lineage.nrvExpenseAccount() : lineage.revaluationAccount();
        String allowance = nrv ? lineage.nrvAllowanceAccount() : null;
        List<Line> lines = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT * FROM \"InventoryRevaluationLine\" WHERE"
                + " \"revaluationId\" = ? ORDER BY id").param(id))) {
            String productId = (String) l.get("productId");
            String variantId = (String) l.get("variantId");
            String locationId = (String) l.get("locationId");
            Map<String, Object> product = product(productId);
            lockQuant(productId, variantId, locationId);
            BigDecimal[] carrying = carrying(product, productId, variantId, locationId);
            BigDecimal qty = carrying[0];
            BigDecimal before = carrying[1];
            BigDecimal amount;
            BigDecimal allowanceBefore = BigDecimal.ZERO;
            String text = (nrv ? "NRV review " : "Inventory revaluation ") + d.get("number") + " · " + product.get("name");
            if (nrv) {
                allowanceBefore = openAllowance(productId, variantId, locationId);
                BigDecimal gap = before.subtract(SqlRows.dec(l, "netRealisableValue")).max(BigDecimal.ZERO);
                BigDecimal target = gap.multiply(qty.max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP).negate();
                amount = target.subtract(allowanceBefore);
            } else {
                BigDecimal after = SqlRows.dec(l, "unitCostAfter");
                amount = after.subtract(before).multiply(qty.max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);
                if (qty.signum() > 0) {
                    setUnitCost(product, productId, variantId, locationId, after);
                }
            }
            jdbc.sql("UPDATE \"InventoryRevaluationLine\" SET quantity = ?, \"unitCostBefore\" = ?, \"allowanceBefore\" ="
                    + " ?, amount = ? WHERE id = ?").params(qty, before, allowanceBefore, amount, l.get("id")).update();
            if (amount.signum() == 0) {
                continue;
            }
            String debitSide = nrv ? allowance : stockPosting.stockAccount(productId, amount.abs());
            String v = JsNumbers.dec(amount.abs());
            if (amount.signum() > 0) {
                lines.add(Line.debit(debitSide, v, text));
                lines.add(Line.credit(offset, v, text));
            } else {
                lines.add(Line.debit(offset, v, text));
                lines.add(Line.credit(debitSide, v, text));
            }
            rows.add(new Object[] {productId, variantId, locationId, amount});
            total = total.add(amount);
        }
        String entryId = null;
        if (!lines.isEmpty()) {
            Map<String, Object> je = posting.post(PostingRequest.of("INV", date, (nrv ? "NRV review " : "Inventory"
                    + " revaluation ") + d.get("number"), sourceType, id, "inventory:" + sourceType + ":" + org() + ":"
                    + id, lines));
            entryId = je == null ? null : (String) je.get("id");
        }
        for (Object[] r : rows) {
            BigDecimal amount = (BigDecimal) r[3];
            String component = nrv ? "nrv_allowance" : null;
            lineage.recordValueOnly((String) r[0], (String) r[1], (String) r[2], null, amount, sourceType, id, component,
                    (nrv ? "NRV allowance " : "Revaluation ") + d.get("number"));
            lineage.recordAdjustment(new AdjustmentInput(nrv ? "nrv" : "revaluation", sourceType, id, (String) r[0],
                    (String) r[1], (String) r[2], null, null, amount, component, entryId, null));
        }
        jdbc.sql("UPDATE \"InventoryRevaluation\" SET status = 'posted', \"totalAmount\" = ?, \"journalEntryId\" = ?,"
                + " \"postedAt\" = now(), \"postedBy\" = ? WHERE id = ?").params(total, entryId, TenantContext.userId(), id)
                .update();
        audit.recordInTx(AuditInput.of("InventoryRevaluation", id, "post", null, Obj.of("total", total,
                "journalEntryId", entryId)));
        return get(id);
    }

    /** Reverse a posted document (mirrored entry, costs / allowance restored) while nothing has moved since. */
    @Transactional
    public Map<String, Object> reverse(String id) {
        Map<String, Object> d = doc(id, true);
        if (!"posted".equals(d.get("status"))) {
            throw ApiException.badRequest("Only a posted revaluation can be reversed");
        }
        boolean nrv = "nrv".equals(d.get("kind"));
        String sourceType = nrv ? "inventory_nrv" : "inventory_revaluation";
        List<Map<String, Object>> docLines = SqlRows.list(jdbc.sql("SELECT * FROM \"InventoryRevaluationLine\" WHERE"
                + " \"revaluationId\" = ? AND amount <> 0").param(id));
        if (nrv) {
            long released = jdbc.sql("SELECT count(*) FROM \"CostValueAdjustment\" WHERE \"organizationId\" = ? AND"
                    + " \"sourceType\" = ? AND \"sourceId\" = ? AND \"openAmount\" <> amount").params(org(), sourceType, id)
                    .query(Long.class).single();
            if (released > 0) {
                throw ApiException.badRequest("Part of this allowance was already released to cost of sales; post a new"
                        + " NRV review instead");
            }
        } else {
            for (Map<String, Object> l : docLines) {
                long moved = jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                                + " \"productId\" = ? AND coalesce(\"variantId\", '') = ? AND \"locationId\" = ? AND"
                                + " \"quantityChange\" <> 0 AND \"createdAt\" > ?")
                        .params(org(), l.get("productId"), l.get("variantId") == null ? "" : l.get("variantId"),
                                l.get("locationId"), CostLineageService.tsOf(d.get("postedAt")))
                        .query(Long.class).single();
                if (moved > 0) {
                    throw ApiException.badRequest("Stock has moved since the revaluation; post a new revaluation"
                            + " instead");
                }
            }
        }
        String reversal = null;
        if (d.get("journalEntryId") != null) {
            Map<String, Object> rev = posting.reverse((String) d.get("journalEntryId"), Instant.now(),
                    "Reversal of " + d.get("number"));
            reversal = (String) rev.get("id");
        }
        for (Map<String, Object> l : docLines) {
            String productId = (String) l.get("productId");
            if (!nrv && SqlRows.dec(l, "quantity").signum() > 0) {
                Map<String, Object> product = product(productId);
                lockQuant(productId, (String) l.get("variantId"), (String) l.get("locationId"));
                setUnitCost(product, productId, (String) l.get("variantId"), (String) l.get("locationId"),
                        SqlRows.dec(l, "unitCostBefore"));
            }
            lineage.recordValueOnly(productId, (String) l.get("variantId"), (String) l.get("locationId"), null,
                    SqlRows.dec(l, "amount").negate(), "reversal", (String) d.get("journalEntryId"),
                    nrv ? "nrv_allowance" : null, "Reversal of " + d.get("number"));
        }
        lineage.closeAdjustments(sourceType, id);
        jdbc.sql("UPDATE \"InventoryRevaluation\" SET status = 'reversed', \"reversalEntryId\" = ?, \"reversedAt\" ="
                + " now() WHERE id = ?").params(reversal, id).update();
        audit.recordInTx(AuditInput.of("InventoryRevaluation", id, "cancel", null, Obj.of("reversalEntryId",
                reversal)));
        return get(id);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private Map<String, Object> product(String productId) {
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT id, name, \"costingMethod\"::text AS method, \"costPrice\","
                + " \"batchTracking\", \"serialTracking\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                .params(productId, org()));
        if (p == null) {
            throw ApiException.badRequest("Product not found");
        }
        return p;
    }

    private void lockQuant(String productId, String variantId, String locationId) {
        String variantKey = variantId == null ? "" : variantId;
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("avco:" + org() + ":" + productId + ":"
                + variantKey + ":" + locationId).query().listOfRows();
    }

    /** {quantity, carrying unit cost} of one pool; STANDARD includes its open price difference per unit. */
    BigDecimal[] carrying(Map<String, Object> product, String productId, String variantId, String locationId) {
        String variantKey = variantId == null ? "" : variantId;
        Map<String, Object> q = SqlRows.one(jdbc.sql("SELECT coalesce(sum(quantity), 0) AS qty,"
                        + " coalesce(max(\"runningAverageCost\"), 0) AS avg FROM \"StockItem\" WHERE \"organizationId\" = ?"
                        + " AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ?")
                .params(org(), productId, variantKey, locationId));
        BigDecimal qty = SqlRows.dec(q, "qty");
        if (qty.signum() <= 0) {
            return new BigDecimal[] {qty, BigDecimal.ZERO};
        }
        String method = (String) product.get("method");
        BigDecimal unit;
        if ("STANDARD".equals(method)) {
            BigDecimal diff = jdbc.sql("SELECT coalesce(sum(\"totalValue\"), 0) FROM \"InventoryLedger\" WHERE"
                            + " \"organizationId\" = ? AND \"productId\" = ? AND coalesce(\"variantId\", '') = ? AND"
                            + " \"locationId\" = ? AND \"valuationComponent\" = 'price_difference'")
                    .params(org(), productId, variantKey, locationId).query(BigDecimal.class).single();
            unit = SqlRows.dec(product, "costPrice").add(diff.divide(qty, 6, RoundingMode.HALF_UP));
        } else if ("FIFO".equals(method) || Boolean.TRUE.equals(product.get("batchTracking"))) {
            Map<String, Object> lot = SqlRows.one(jdbc.sql("SELECT coalesce(sum(quantity), 0) AS qty,"
                            + " coalesce(sum(quantity * coalesce(\"unitCost\", 0)), 0) AS value FROM \"InventoryBatch\""
                            + " WHERE \"organizationId\" = ? AND \"productId\" = ? AND coalesce(\"variantId\", '') = ? AND"
                            + " \"locationId\" = ? AND \"isActive\" = true AND quantity > 0")
                    .params(org(), productId, variantKey, locationId));
            BigDecimal lotQty = SqlRows.dec(lot, "qty");
            BigDecimal avg = SqlRows.dec(q, "avg");
            BigDecimal value = SqlRows.dec(lot, "value").add(qty.subtract(lotQty).max(BigDecimal.ZERO).multiply(avg));
            unit = value.divide(qty, 6, RoundingMode.HALF_UP);
        } else {
            unit = SqlRows.dec(q, "avg");
        }
        return new BigDecimal[] {qty, unit.setScale(6, RoundingMode.HALF_UP)};
    }

    private void setUnitCost(Map<String, Object> product, String productId, String variantId, String locationId,
            BigDecimal unit) {
        String variantKey = variantId == null ? "" : variantId;
        jdbc.sql("UPDATE \"StockItem\" SET \"runningAverageCost\" = ?, \"updatedAt\" = now() WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ?")
                .params(unit, org(), productId, variantKey, locationId).update();
        if ("FIFO".equals(product.get("method")) || Boolean.TRUE.equals(product.get("batchTracking"))) {
            jdbc.sql("UPDATE \"InventoryBatch\" SET \"unitCost\" = ?, \"updatedAt\" = now() WHERE \"organizationId\" = ?"
                    + " AND \"productId\" = ? AND coalesce(\"variantId\", '') = ? AND \"locationId\" = ? AND \"isActive\" ="
                    + " true AND quantity > 0").params(unit, org(), productId, variantKey, locationId).update();
        }
    }

    /** Open NRV allowance of a pool (≤ 0). */
    private BigDecimal openAllowance(String productId, String variantId, String locationId) {
        return jdbc.sql("SELECT coalesce(sum(\"totalValue\"), 0) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                        + " \"productId\" = ? AND coalesce(\"variantId\", '') = ? AND \"locationId\" = ? AND"
                        + " \"valuationComponent\" = 'nrv_allowance'")
                .params(org(), productId, variantId == null ? "" : variantId, locationId).query(BigDecimal.class)
                .single();
    }

    private static Object toDate(Object v) {
        if (v instanceof java.sql.Date sd) {
            return sd.toLocalDate();
        }
        return v instanceof LocalDate ? v : LocalDate.parse(v.toString().substring(0, 10));
    }

    private static LocalDate day(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.now(ZoneOffset.UTC);
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("date must be a valid ISO 8601 date string");
        }
    }
}
