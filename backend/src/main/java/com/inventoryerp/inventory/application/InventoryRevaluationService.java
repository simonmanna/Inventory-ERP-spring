package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.CostingReversals;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.inventory.application.CostLineageService.AdjustmentInput;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
 * has moved or its cost changed since (revaluation) or the allowance was partly released (NRV) — post a new document
 * instead.
 *
 * <p>Readiness gaps (h-02): one line per pool (F3); a reversal never overwrites a later value change and posting re-checks
 * the item (F4); maker-checker through the {@code inventory_valuation} separation-of-duties pair and the approval engine,
 * and an offset account outside the control, cash and stock accounts (F5); the posted amount is the quant's exact value
 * change, so the GL, the stock ledger and the quants agree to the 6-decimal ledger precision (F9); lines are posted in
 * the stock engine's lock order under the costing locks (F2).
 */
@Service
public class InventoryRevaluationService {

    /** One document line. */
    public record LineInput(String productId, String variantId, String locationId, BigDecimal unitCostAfter,
            BigDecimal netRealisableValue) {}

    /** Document body. */
    public record RevaluationInput(String kind, String date, String reason, String accountId, List<LineInput> lines) {}

    /** Quantity, carrying unit cost (6 dp) and exact carrying value of one pool. */
    record Carrying(BigDecimal qty, BigDecimal unit, BigDecimal value) {}

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final PostingService posting;
    private final CostingReversals reversals;
    private final StockPostingService stockPosting;
    private final CostLineageService lineage;
    private final AuditService audit;
    private final ApprovalsService approvals;
    private final SegregationOfDutiesService sod;
    private final TransactionTemplate newTx;

    public InventoryRevaluationService(JdbcClient jdbc, SequenceService seq, PostingService posting,
            StockPostingService stockPosting, CostLineageService lineage, AuditService audit, CostingReversals reversals,
            ApprovalsService approvals, SegregationOfDutiesService sod, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.seq = seq;
        this.posting = posting;
        this.reversals = reversals;
        this.stockPosting = stockPosting;
        this.lineage = lineage;
        this.audit = audit;
        this.approvals = approvals;
        this.sod = sod;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
        if (in.accountId() != null) {
            lineage.assertOffsetAccount(in.accountId());
        }
        String id = UUID.randomUUID().toString();
        String number = "nrv".equals(kind) ? seq.next("inventory_nrv", "NRV/", 5)
                : seq.next("inventory_revaluation", "IRV/", 5);
        jdbc.sql("INSERT INTO \"InventoryRevaluation\" (id, \"organizationId\", number, kind, \"revaluationDate\", reason,"
                        + " \"accountId\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), number, kind, java.sql.Date.valueOf(date), in.reason(), in.accountId(),
                        TenantContext.userId())
                .update();
        Set<String> pools = new HashSet<>();
        for (LineInput l : in.lines()) {
            Map<String, Object> product = product(l.productId());
            String method = (String) product.get("method");
            if (l.locationId() == null || jdbc.sql("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(l.locationId(), org()).query(Long.class).single() == 0) {
                throw ApiException.badRequest("Location not found");
            }
            if (!pools.add(poolKey(l.productId(), l.variantId(), l.locationId()))) {
                throw duplicate(product);
            }
            if ("revaluation".equals(kind)) {
                assertRevaluable(product, method);
                if (l.unitCostAfter() == null || l.unitCostAfter().signum() < 0) {
                    throw ApiException.badRequest("unitCostAfter must not be less than 0");
                }
            } else if (l.netRealisableValue() == null || l.netRealisableValue().signum() < 0) {
                throw ApiException.badRequest("netRealisableValue must not be less than 0");
            }
            Carrying carrying = carrying(product, l.productId(), l.variantId(), l.locationId());
            jdbc.sql("INSERT INTO \"InventoryRevaluationLine\" (id, \"organizationId\", \"revaluationId\", \"productId\","
                            + " \"variantId\", \"locationId\", quantity, \"unitCostBefore\", \"unitCostAfter\","
                            + " \"netRealisableValue\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), id, l.productId(), l.variantId(), l.locationId(),
                            carrying.qty(), carrying.unit(), l.unitCostAfter(), l.netRealisableValue())
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
        List<Map<String, Object>> docLines = inPoolOrder(SqlRows.list(jdbc.sql("SELECT * FROM"
                + " \"InventoryRevaluationLine\" WHERE \"revaluationId\" = ?").param(id)));
        // h-02 F3: a draft saved before one-line-per-pool was enforced is refused here too.
        Set<String> pools = new HashSet<>();
        for (Map<String, Object> l : docLines) {
            if (!pools.add(poolKey((String) l.get("productId"), (String) l.get("variantId"),
                    (String) l.get("locationId")))) {
                throw duplicate(product((String) l.get("productId")));
            }
        }
        // h-02 F5: the offset follows the control-account policy; maker-checker through the approval engine (when the
        // organisation runs a workflow for inventory valuation changes) and the separation-of-duties pair.
        if (d.get("accountId") != null) {
            lineage.assertOffsetAccount((String) d.get("accountId"));
        }
        gateApproval(d, docLines, nrv);
        List<String> makers = new ArrayList<>();
        makers.add((String) d.get("createdBy"));
        sod.assertSeparated(SegregationOfDutiesService.INVENTORY_VALUATION, "InventoryRevaluation", id, makers);
        String offset = d.get("accountId") != null ? (String) d.get("accountId")
                : nrv ? lineage.nrvExpenseAccount() : lineage.revaluationAccount();
        String allowance = nrv ? lineage.nrvAllowanceAccount() : null;
        String org = org();
        List<Line> lines = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> l : docLines) {
            String productId = (String) l.get("productId");
            String variantId = (String) l.get("variantId");
            String locationId = (String) l.get("locationId");
            // h-02 F2: like a stock move, the method is read under the costing locks, then the quant is locked.
            CostingLocks.shareProduct(jdbc, org, productId);
            Map<String, Object> product = product(productId);
            if (!nrv) {
                // h-02 F4: the item may have been converted to standard cost or serial tracking since the draft.
                assertRevaluable(product, (String) product.get("method"));
            }
            lockQuant(productId, variantId, locationId);
            Carrying carrying = carrying(product, productId, variantId, locationId);
            BigDecimal qty = carrying.qty();
            BigDecimal before = carrying.unit();
            BigDecimal amount;
            BigDecimal allowanceBefore = BigDecimal.ZERO;
            String text = (nrv ? "NRV review " : "Inventory revaluation ") + d.get("number") + " · " + product.get("name");
            if (nrv) {
                allowanceBefore = openAllowance(productId, variantId, locationId);
                BigDecimal gap = before.subtract(SqlRows.dec(l, "netRealisableValue")).max(BigDecimal.ZERO);
                BigDecimal target = gap.multiply(qty.max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP).negate();
                amount = target.subtract(allowanceBefore).setScale(6, RoundingMode.HALF_UP);
            } else {
                BigDecimal after = SqlRows.dec(l, "unitCostAfter");
                // h-02 F9: the quant's exact value change (its carrying value at the new cost minus before), not the
                // unit difference rounded to cents, so the GL and the stock ledger move exactly as the quant does.
                amount = qty.signum() > 0 ? qty.multiply(after).subtract(carrying.value()).setScale(6,
                        RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(6);
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
            // R3: the stock side carries the product (and location) it revalues.
            if (amount.signum() > 0) {
                lines.add(StockPostingService.stampLine(Line.debit(debitSide, v, text), productId, locationId));
                lines.add(Line.credit(offset, v, text));
            } else {
                lines.add(Line.debit(offset, v, text));
                lines.add(StockPostingService.stampLine(Line.credit(debitSide, v, text), productId, locationId));
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

    /**
     * Reverse a posted document (mirrored entry, costs / allowance restored) while nothing has changed since: a
     * revaluation is refused once its stock moved, its cost changed through any other value change (another
     * revaluation, a landed cost, a re-costing — restoring the old unit cost would silently undo it in the quant while
     * its GL and ledger rows stay; h-02 F4), or the item became STANDARD or serial-tracked; an NRV review once part of
     * its allowance was released. Post a new document instead.
     */
    @Transactional
    public Map<String, Object> reverse(String id) {
        Map<String, Object> d = doc(id, true);
        if (!"posted".equals(d.get("status"))) {
            throw ApiException.badRequest("Only a posted revaluation can be reversed");
        }
        boolean nrv = "nrv".equals(d.get("kind"));
        String sourceType = nrv ? "inventory_nrv" : "inventory_revaluation";
        List<String> makers = new ArrayList<>();
        makers.add((String) d.get("createdBy"));
        makers.add((String) d.get("postedBy"));
        sod.assertSeparated(SegregationOfDutiesService.INVENTORY_VALUATION, "InventoryRevaluation", id, makers);
        List<Map<String, Object>> docLines = inPoolOrder(SqlRows.list(jdbc.sql("SELECT * FROM"
                + " \"InventoryRevaluationLine\" WHERE \"revaluationId\" = ? AND amount <> 0").param(id)));
        String org = org();
        List<Map<String, Object>> products = new ArrayList<>();
        for (Map<String, Object> l : docLines) {
            String productId = (String) l.get("productId");
            CostingLocks.shareProduct(jdbc, org, productId);
            products.add(product(productId));
            lockQuant(productId, (String) l.get("variantId"), (String) l.get("locationId"));
        }
        if (nrv) {
            long released = jdbc.sql("SELECT count(*) FROM \"CostValueAdjustment\" WHERE \"organizationId\" = ? AND"
                    + " \"sourceType\" = ? AND \"sourceId\" = ? AND \"openAmount\" <> amount").params(org(), sourceType, id)
                    .query(Long.class).single();
            if (released > 0) {
                throw ApiException.badRequest("Part of this allowance was already released to cost of sales; post a new"
                        + " NRV review instead");
            }
        } else {
            for (int i = 0; i < docLines.size(); i++) {
                Map<String, Object> l = docLines.get(i);
                Map<String, Object> product = products.get(i);
                Object[] pool = {org(), l.get("productId"), l.get("variantId") == null ? "" : l.get("variantId"),
                        l.get("locationId"), CostLineageService.tsOf(d.get("postedAt"))};
                long moved = jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                                + " \"productId\" = ? AND coalesce(\"variantId\", '') = ? AND \"locationId\" = ? AND"
                                + " \"quantityChange\" <> 0 AND \"createdAt\" > ?")
                        .params(pool).query(Long.class).single();
                if (moved > 0) {
                    throw ApiException.badRequest("Stock has moved since the revaluation; post a new revaluation"
                            + " instead");
                }
                if ("STANDARD".equals(product.get("method"))) {
                    throw ApiException.badRequest(product.get("name") + " is now carried at standard cost; its"
                            + " revaluation can no longer be reversed — activate a new standard cost instead");
                }
                if (Boolean.TRUE.equals(product.get("serialTracking"))) {
                    throw ApiException.badRequest(product.get("name") + " is now serial-tracked; serials keep their own"
                            + " cost, so its revaluation can no longer be reversed");
                }
                // Net value-only change of the pool's carrying cost since posting (not the adjunct / allowance
                // components, which sit beside the quant's cost, and not this document's own rows): with the quantity
                // unchanged, zero means the quant still carries exactly what this revaluation left.
                BigDecimal changed = jdbc.sql("SELECT coalesce(sum(\"totalValue\"), 0) FROM \"InventoryLedger\" WHERE"
                                + " \"organizationId\" = ? AND \"productId\" = ? AND coalesce(\"variantId\", '') = ? AND"
                                + " \"locationId\" = ? AND \"quantityChange\" = 0 AND \"valuationComponent\" IS NULL AND"
                                + " \"createdAt\" > ? AND NOT (coalesce(\"referenceType\", '') = ? AND"
                                + " coalesce(\"referenceId\", '') = ?)")
                        .params(pool[0], pool[1], pool[2], pool[3], pool[4], sourceType, id).query(BigDecimal.class)
                        .single();
                if (changed.signum() != 0) {
                    throw ApiException.badRequest("The cost of " + product.get("name") + " has changed since the"
                            + " revaluation; post a new revaluation instead");
                }
            }
        }
        String reversal = null;
        if (d.get("journalEntryId") != null) {
            Map<String, Object> rev = reversals.reverse((String) d.get("journalEntryId"),
                    "Reversal of " + d.get("number"));
            reversal = (String) rev.get("id");
        }
        for (int i = 0; i < docLines.size(); i++) {
            Map<String, Object> l = docLines.get(i);
            String productId = (String) l.get("productId");
            if (!nrv && SqlRows.dec(l, "quantity").signum() > 0) {
                setUnitCost(products.get(i), productId, (String) l.get("variantId"), (String) l.get("locationId"),
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

    /**
     * h-02 F5: the approval engine gate of a posting (no-op unless the organisation runs an approval workflow for
     * {@code inventory_valuation}). The amount is what the document would post now, from the current stock.
     */
    private void gateApproval(Map<String, Object> d, List<Map<String, Object>> docLines, boolean nrv) {
        BigDecimal amount = BigDecimal.ZERO;
        for (Map<String, Object> l : docLines) {
            String productId = (String) l.get("productId");
            String variantId = (String) l.get("variantId");
            String locationId = (String) l.get("locationId");
            Carrying c = carrying(product(productId), productId, variantId, locationId);
            if (c.qty().signum() <= 0) {
                continue;
            }
            if (nrv) {
                BigDecimal gap = c.unit().subtract(SqlRows.dec(l, "netRealisableValue")).max(BigDecimal.ZERO);
                amount = amount.add(gap.multiply(c.qty()).negate().subtract(openAllowance(productId, variantId,
                        locationId)).abs());
            } else {
                amount = amount.add(c.qty().multiply(SqlRows.dec(l, "unitCostAfter")).subtract(c.value()).abs());
            }
        }
        String id = (String) d.get("id");
        Map<String, Object> snapshot = Obj.of("amount", JsNumbers.js(amount.setScale(2, RoundingMode.HALF_UP)),
                "number", d.get("number"), "kind", d.get("kind"), "makerIds", d.get("createdBy") == null ? ""
                        : d.get("createdBy"));
        Map<String, Object> gate = newTx.execute(s -> approvals.checkOrRequestApproval(
                SegregationOfDutiesService.INVENTORY_VALUATION, id, snapshot));
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            throw ApiException.badRequest("Approval required before posting " + (nrv ? "NRV review " : "revaluation ")
                    + d.get("number") + ". Pending approval request " + gate.get("requestId") + ".");
        }
    }

    /** Refused for a revaluation: STANDARD items are revalued through a new standard, serials keep their own cost. */
    private static void assertRevaluable(Map<String, Object> product, String method) {
        if ("STANDARD".equals(method)) {
            throw ApiException.badRequest(product.get("name") + " is carried at standard cost; revalue it by"
                    + " activating a new standard cost");
        }
        if (Boolean.TRUE.equals(product.get("serialTracking"))) {
            throw ApiException.badRequest(product.get("name") + " is serial-tracked; serials keep their own cost");
        }
    }

    private static String poolKey(String productId, String variantId, String locationId) {
        return productId + "|" + (variantId == null ? "" : variantId) + "|" + locationId;
    }

    private static ApiException duplicate(Map<String, Object> product) {
        return ApiException.badRequest(product.get("name") + " is listed more than once at the same location; use one"
                + " line per product, variant and location");
    }

    /** Lines in the stock engine's lock order: product, variant, location. */
    private static List<Map<String, Object>> inPoolOrder(List<Map<String, Object>> lines) {
        List<Map<String, Object>> out = new ArrayList<>(lines);
        out.sort(Comparator.comparing((Map<String, Object> l) -> (String) l.get("productId"))
                .thenComparing(l -> Objects.toString(l.get("variantId"), ""))
                .thenComparing(l -> (String) l.get("locationId")));
        return out;
    }

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

    /**
     * Quantity, carrying unit cost (6 dp) and exact carrying value of one pool, as the valuation counts it; STANDARD
     * includes its open price difference per unit.
     */
    Carrying carrying(Map<String, Object> product, String productId, String variantId, String locationId) {
        String variantKey = variantId == null ? "" : variantId;
        Map<String, Object> q = SqlRows.one(jdbc.sql("SELECT coalesce(sum(quantity), 0) AS qty,"
                        + " coalesce(max(\"runningAverageCost\"), 0) AS avg FROM \"StockItem\" WHERE \"organizationId\" = ?"
                        + " AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ?")
                .params(org(), productId, variantKey, locationId));
        BigDecimal qty = SqlRows.dec(q, "qty");
        if (qty.signum() <= 0) {
            return new Carrying(qty, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        String method = (String) product.get("method");
        BigDecimal value;
        if ("STANDARD".equals(method)) {
            BigDecimal diff = jdbc.sql("SELECT coalesce(sum(\"totalValue\"), 0) FROM \"InventoryLedger\" WHERE"
                            + " \"organizationId\" = ? AND \"productId\" = ? AND coalesce(\"variantId\", '') = ? AND"
                            + " \"locationId\" = ? AND \"valuationComponent\" = 'price_difference'")
                    .params(org(), productId, variantKey, locationId).query(BigDecimal.class).single();
            value = SqlRows.dec(product, "costPrice").multiply(qty).add(diff);
        } else if ("FIFO".equals(method) || Boolean.TRUE.equals(product.get("batchTracking"))) {
            Map<String, Object> lot = SqlRows.one(jdbc.sql("SELECT coalesce(sum(quantity), 0) AS qty,"
                            + " coalesce(sum(quantity * coalesce(\"unitCost\", 0)), 0) AS value FROM \"InventoryBatch\""
                            + " WHERE \"organizationId\" = ? AND \"productId\" = ? AND coalesce(\"variantId\", '') = ? AND"
                            + " \"locationId\" = ? AND \"isActive\" = true AND quantity > 0")
                    .params(org(), productId, variantKey, locationId));
            BigDecimal lotQty = SqlRows.dec(lot, "qty");
            BigDecimal avg = SqlRows.dec(q, "avg");
            value = SqlRows.dec(lot, "value").add(qty.subtract(lotQty).max(BigDecimal.ZERO).multiply(avg));
        } else {
            value = qty.multiply(SqlRows.dec(q, "avg"));
        }
        return new Carrying(qty, value.divide(qty, 6, RoundingMode.HALF_UP), value);
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
