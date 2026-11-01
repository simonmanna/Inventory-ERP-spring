package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.inventory.application.AvcoLineageCalculator.Distribution;
import com.inventoryerp.inventory.application.AvcoLineageCalculator.Event;
import com.inventoryerp.inventory.application.AvcoLineageCalculator.Share;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory valuation authority for cost-only changes and cost lineage (ADR-S031, AD-1/AD-2/AD-2a/AD-2b).
 *
 * <ul>
 *   <li><b>Value-only stock ledger rows</b> ({@code cost_adjustment}, quantity 0, signed {@code totalValue}) for every
 *       value change that moves no quantity, so the historical valuation and the GL tie-out by source include it.</li>
 *   <li><b>Lineage</b>: the stock ledger is the event chain of each pool (product, variant, location). A cost layer
 *       is a receipt row (its {@code unitCost} is never rewritten); consumptions are the outbound rows after it.
 *       {@link #distribute} splits a later adjustment between each consumption and what is still on hand
 *       ({@link AvcoLineageCalculator}).</li>
 *   <li><b>Adjunct components</b>: amounts carried <em>beside</em> the quant's unit cost — the price difference of a
 *       STANDARD item, the IAS 2 NRV allowance — are value-only rows tagged {@code valuationComponent}; they stay
 *       open on a {@link #recordAdjustment adjustment} and are released to cost of sales as the stock is consumed
 *       ({@link #release}).</li>
 * </ul>
 */
@Service
public class CostLineageService {

    /** Money scale of distributed shares (GL amounts). */
    static final int SCALE = 2;

    /** One adjustment to record. {@code shares} may be empty (whole amount on hand). */
    public record AdjustmentInput(String adjustmentType, String sourceType, String sourceId, String productId,
            String variantId, String locationId, String batchId, String layerLedgerId, BigDecimal amount,
            String valuationComponent, String journalEntryId, Distribution distribution) {}

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final AccountResolverService accounts;
    private final AuditService audit;

    public CostLineageService(JdbcClient jdbc, SequenceService seq, PostingService posting,
            AccountDeterminationService determination, AccountResolverService accounts, AuditService audit) {
        this.jdbc = jdbc;
        this.seq = seq;
        this.posting = posting;
        this.determination = determination;
        this.accounts = accounts;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    // ---- accounts ---------------------------------------------------------------------------------------------------

    /** Balance-sheet adjunct to stock holding STANDARD items' price differences still on hand (1396). */
    public String priceDifferenceAccount() {
        return accounts.ensureByCode("1396", "Inventory Price Differences", "inventory", "inventory_price_difference");
    }

    /** Contra-inventory allowance for IAS 2 write-downs to net realisable value (1397). */
    public String nrvAllowanceAccount() {
        return accounts.ensureByCode("1397", "Inventory NRV Allowance", "contra_asset", "inventory_nrv_allowance");
    }

    /** P&amp;L: write-down of inventory to NRV and its reversal (5975). */
    public String nrvExpenseAccount() {
        return accounts.ensureByCode("5975", "Inventory Write-down to NRV", "cost_of_goods_sold",
                "inventory_nrv_expense");
    }

    /** P&amp;L offset of a manual inventory revaluation (5968). */
    public String revaluationAccount() {
        return accounts.ensureByCode("5968", "Inventory Revaluation", "cost_of_goods_sold", "inventory_revaluation");
    }

    // ---- value-only rows ------------------------------------------------------------------------------------------

    /**
     * Write one value-only stock ledger row (quantity unchanged). {@code amount} is signed: + raises the carrying amount.
     * Returns the ledger row id, or null for a zero amount.
     */
    public String recordValueOnly(String productId, String variantId, String locationId, String batchId,
            BigDecimal amount, String referenceType, String referenceId, String valuationComponent, String notes) {
        if (amount == null || amount.signum() == 0) {
            return null;
        }
        if (locationId == null) {
            throw ApiException.badRequest("A value adjustment needs a stock location");
        }
        BigDecimal qty = jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                        + " \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ?")
                .params(org(), productId, variantId == null ? "" : variantId, locationId).query(BigDecimal.class)
                .single();
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLedger\" (id, \"organizationId\", \"ledgerCode\", \"productId\", \"variantId\","
                        + " \"locationId\", \"batchId\", type, \"qtyBefore\", \"quantityChange\", \"balanceAfter\","
                        + " \"unitCost\", \"totalValue\", \"referenceType\", \"referenceId\", notes, \"performedBy\","
                        + " \"valuationComponent\", \"createdAt\") VALUES (?, ?, ?, ?, ?, ?, ?, 'cost_adjustment', ?, 0, ?, 0,"
                        + " ?, ?, ?, ?, ?, ?, date_trunc('milliseconds', clock_timestamp())::timestamp(3))")
                .params(id, org(), seq.next("stock_move", "STK/", 6), productId, variantId, locationId, batchId, qty, qty,
                        amount.setScale(6, RoundingMode.HALF_UP), referenceType, referenceId, notes,
                        TenantContext.userId(), valuationComponent)
                .update();
        return id;
    }

    // ---- lineage ----------------------------------------------------------------------------------------------------

    /** The stock ledger row of a receipt into a pool (goods receipt by id or number), or null. */
    public Map<String, Object> receiptLayer(String grnId, String productId, String variantId, String locationId,
            String batchId) {
        return SqlRows.one(jdbc.sql("SELECT l.* FROM \"InventoryLedger\" l WHERE l.\"organizationId\" = ? AND"
                        + " l.\"productId\" = ? AND coalesce(l.\"variantId\", '') = ? AND l.\"locationId\" = ? AND"
                        + " l.\"quantityChange\" > 0 AND l.\"referenceType\" = 'goods_receipt' AND (l.\"referenceId\" = ? OR"
                        + " l.\"referenceId\" = (SELECT \"receiptNumber\" FROM \"GoodsReceiptNote\" WHERE id = ?))"
                        + (batchId == null ? "" : " AND l.\"batchId\" = ?")
                        + " ORDER BY l.\"createdAt\", l.\"ledgerCode\" LIMIT 1")
                .params(batchId == null ? List.of(org(), productId, key(variantId), locationId, grnId, grnId)
                        : List.of(org(), productId, key(variantId), locationId, grnId, grnId, batchId)));
    }

    private static String key(Object variantId) {
        return variantId == null ? "" : variantId.toString();
    }

    private Map<String, Object> ledgerRow(String ledgerId) {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT * FROM \"InventoryLedger\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(ledgerId, org()));
        if (row == null) {
            throw ApiException.notFound("Stock ledger entry not found");
        }
        return row;
    }

    /** The pool's movements after a ledger row, in posting order (value-only rows excluded). */
    List<Event> eventsAfter(Map<String, Object> row) {
        List<Event> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, type::text AS type, \"qtyBefore\","
                        + " \"quantityChange\", \"referenceType\", \"referenceId\" FROM \"InventoryLedger\" WHERE"
                        + " \"organizationId\" = ? AND \"productId\" = ? AND coalesce(\"variantId\", '') = ? AND"
                        + " \"locationId\" = ? AND \"quantityChange\" <> 0 AND (\"createdAt\", \"ledgerCode\") > (?, ?)"
                        + " ORDER BY \"createdAt\", \"ledgerCode\"")
                .params(org(), row.get("productId"), key(row.get("variantId")), row.get("locationId"),
                        tsOf(row.get("createdAt")), row.get("ledgerCode")))) {
            out.add(new Event((String) r.get("id"), (String) r.get("type"), SqlRows.dec(r, "qtyBefore"),
                    SqlRows.dec(r, "quantityChange"), (String) r.get("referenceType"), (String) r.get("referenceId")));
        }
        return out;
    }

    /** Distribute {@code amount} adjusting the layer {@code layerLedgerId} over its lineage (no writes). */
    @Transactional(readOnly = true)
    public Distribution distribute(String layerLedgerId, BigDecimal amount) {
        Map<String, Object> layer = ledgerRow(layerLedgerId);
        return distribute(layer, amount);
    }

    Distribution distribute(Map<String, Object> layer, BigDecimal amount) {
        return AvcoLineageCalculator.distribute(amount, SqlRows.dec(layer, "quantityChange"),
                SqlRows.dec(layer, "qtyBefore"), eventsAfter(layer), SCALE);
    }

    /** True while the production order a consumption fed is still open (its cost sits in WIP). */
    boolean openProductionOrder(String referenceType, String referenceId) {
        if (referenceId == null || referenceType == null || !referenceType.startsWith("production")) {
            return false;
        }
        String status = jdbc.sql("SELECT status::text FROM \"ProductionOrder\" WHERE \"organizationId\" = ? AND (id = ? OR"
                        + " \"orderCode\" = ?) LIMIT 1").params(org(), referenceId, referenceId).query(String.class)
                .optional().orElse(null);
        return status != null && !List.of("completed", "cancelled", "closed", "reversed").contains(status);
    }

    /** Target of one consumption share. */
    public String target(Share s) {
        return AvcoLineageCalculator.target(s.moveType(), openProductionOrder(s.referenceType(), s.referenceId()));
    }

    /** Σ per target of a distribution: on_hand, cogs, wip, scrap, transfer, negative_stock. */
    public Map<String, BigDecimal> byTarget(Distribution d) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        out.put("on_hand", d.onHand());
        for (Share s : d.consumed()) {
            out.merge(target(s), s.amount(), BigDecimal::add);
        }
        if (d.negativeStock().signum() != 0) {
            out.merge("negative_stock", d.negativeStock(), BigDecimal::add);
        }
        return out;
    }

    /** The last ledger row of a pool (anchor for an open adjunct amount). */
    String lastPoolRow(String productId, String variantId, String locationId) {
        return jdbc.sql("SELECT id FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND"
                        + " coalesce(\"variantId\", '') = ? AND \"locationId\" = ? ORDER BY \"createdAt\" DESC,"
                        + " \"ledgerCode\" DESC LIMIT 1").params(org(), productId, key(variantId), locationId)
                .query(String.class).optional().orElse(null);
    }

    /**
     * Record an adjustment: header, one line per consumption share (unique per consumption — a re-run is a no-op) and
     * the on-hand line. When {@code valuationComponent} is set the on-hand amount stays open as an adjunct, anchored at
     * the pool's current last row, and a value-only row carries it.
     */
    public String recordAdjustment(AdjustmentInput in) {
        String id = UUID.randomUUID().toString();
        Distribution d = in.distribution();
        BigDecimal onHand = d == null ? in.amount() : d.onHand();
        BigDecimal consumed = in.amount().subtract(onHand);
        boolean adjunct = in.valuationComponent() != null;
        String anchor = adjunct ? lastPoolRow(in.productId(), in.variantId(), in.locationId()) : null;
        jdbc.sql("INSERT INTO \"CostValueAdjustment\" (id, \"organizationId\", \"adjustmentType\", \"sourceType\","
                        + " \"sourceId\", \"productId\", \"variantId\", \"locationId\", \"batchId\", \"layerLedgerId\","
                        + " amount, \"onHandAmount\", \"consumedAmount\", \"valuationComponent\", \"openAmount\","
                        + " \"anchorLedgerId\", \"journalEntryId\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), in.adjustmentType(), in.sourceType(), in.sourceId(), in.productId(), in.variantId(),
                        in.locationId(), in.batchId(), in.layerLedgerId(), in.amount(), onHand, consumed,
                        in.valuationComponent(), adjunct ? onHand : BigDecimal.ZERO, anchor, in.journalEntryId(),
                        TenantContext.userId())
                .update();
        if (d != null) {
            for (Share s : d.consumed()) {
                line(id, s.ledgerId(), target(s), s.referenceId(), s.amount());
            }
            if (d.negativeStock().signum() != 0) {
                line(id, null, "negative_stock", null, d.negativeStock());
            }
        }
        line(id, null, "on_hand", in.locationId(), onHand);
        return id;
    }

    private void line(String adjustmentId, String consumptionLedgerId, String target, String ref, BigDecimal amount) {
        if (amount.signum() == 0 && !"on_hand".equals(target)) {
            return;
        }
        jdbc.sql("INSERT INTO \"CostValueAdjustmentLine\" (id, \"organizationId\", \"adjustmentId\","
                        + " \"consumptionLedgerId\", target, \"targetRef\", amount) VALUES (?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT DO NOTHING")
                .params(UUID.randomUUID().toString(), org(), adjustmentId, consumptionLedgerId, target, ref, amount)
                .update();
    }

    /** Close an adjustment's open adjunct (reversal of its source): open amount to 0, marked reversed. */
    public BigDecimal closeAdjustments(String sourceType, String sourceId) {
        BigDecimal open = jdbc.sql("SELECT coalesce(sum(\"openAmount\"), 0) FROM \"CostValueAdjustment\" WHERE"
                + " \"organizationId\" = ? AND \"sourceType\" = ? AND \"sourceId\" = ? AND \"reversedAt\" IS NULL")
                .params(org(), sourceType, sourceId).query(BigDecimal.class).single();
        jdbc.sql("UPDATE \"CostValueAdjustment\" SET \"openAmount\" = 0, \"reversedAt\" = now() WHERE \"organizationId\""
                + " = ? AND \"sourceType\" = ? AND \"sourceId\" = ? AND \"reversedAt\" IS NULL")
                .params(org(), sourceType, sourceId).update();
        return open;
    }

    /** Adjustments of one source with their lines (trace). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> adjustments(String sourceType, String sourceId) {
        List<Map<String, Object>> out = SqlRows.list(jdbc.sql("SELECT * FROM \"CostValueAdjustment\" WHERE"
                + " \"organizationId\" = ? AND \"sourceType\" = ? AND \"sourceId\" = ? ORDER BY \"createdAt\"")
                .params(org(), sourceType, sourceId));
        for (Map<String, Object> a : out) {
            a.put("lines", SqlRows.list(jdbc.sql("SELECT * FROM \"CostValueAdjustmentLine\" WHERE \"adjustmentId\" = ?"
                    + " ORDER BY target, \"createdAt\"").param(a.get("id"))));
        }
        return out;
    }

    /**
     * Trace one stock ledger row: the row, the adjustments applied to it as a layer (with their consumption shares), and
     * the adjustment shares it received as a consumption.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> trace(String ledgerId) {
        Map<String, Object> row = ledgerRow(ledgerId);
        List<Map<String, Object>> asLayer = SqlRows.list(jdbc.sql("SELECT * FROM \"CostValueAdjustment\" WHERE"
                + " \"organizationId\" = ? AND \"layerLedgerId\" = ? ORDER BY \"createdAt\"").params(org(), ledgerId));
        for (Map<String, Object> a : asLayer) {
            a.put("lines", SqlRows.list(jdbc.sql("SELECT * FROM \"CostValueAdjustmentLine\" WHERE \"adjustmentId\" = ?"
                    + " ORDER BY target, \"createdAt\"").param(a.get("id"))));
        }
        List<Map<String, Object>> received = SqlRows.list(jdbc.sql("SELECT l.*, a.\"adjustmentType\", a.\"sourceType\","
                + " a.\"sourceId\" FROM \"CostValueAdjustmentLine\" l JOIN \"CostValueAdjustment\" a ON a.id ="
                + " l.\"adjustmentId\" WHERE l.\"organizationId\" = ? AND l.\"consumptionLedgerId\" = ? ORDER BY"
                + " l.\"createdAt\"").params(org(), ledgerId));
        BigDecimal adjusted = asLayer.stream().filter(a -> a.get("reversedAt") == null)
                .map(a -> SqlRows.dec(a, "amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Obj.of("ledger", row, "originalUnitCost", row.get("unitCost"), "adjustmentsAsLayer", asLayer,
                "adjustedTotal", adjusted, "sharesReceived", received);
    }

    // ---- adjunct balances and release ---------------------------------------------------------------------------

    /** Open adjunct amounts (price differences, NRV allowances) per product and location. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> openAdjuncts() {
        return SqlRows.list(jdbc.sql("SELECT a.\"productId\", p.name AS \"productName\", a.\"locationId\","
                + " a.\"valuationComponent\", sum(a.\"openAmount\") AS \"openAmount\", count(*) AS adjustments FROM"
                + " \"CostValueAdjustment\" a JOIN \"Product\" p ON p.id = a.\"productId\" WHERE a.\"organizationId\" = ?"
                + " AND a.\"openAmount\" <> 0 AND a.\"adjustmentType\" <> 'ml_price_diff' GROUP BY 1, 2, 3, 4"
                + " ORDER BY 2, 3").param(org()));
    }

    /**
     * Release the consumed part of every open adjunct (STANDARD price differences, NRV allowances) to cost of sales /
     * WIP: for each open amount, the lineage since its anchor gives the share consumed; one journal entry, value-only
     * rows per pool, and the open amounts re-anchored. Material-ledger amounts are carried by the material ledger
     * itself and are not released here.
     */
    @Transactional
    public Map<String, Object> release() {
        List<Map<String, Object>> open = SqlRows.list(jdbc.sql("SELECT * FROM \"CostValueAdjustment\" WHERE"
                + " \"organizationId\" = ? AND \"openAmount\" <> 0 AND \"reversedAt\" IS NULL AND \"adjustmentType\" <>"
                + " 'ml_price_diff' ORDER BY \"createdAt\" FOR UPDATE").param(org()));
        String runId = UUID.randomUUID().toString();
        String number = seq.next("cost_release", "PDR/", 5);
        Map<String, BigDecimal> byAccountDebit = new LinkedHashMap<>();
        List<Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int released = 0;
        for (Map<String, Object> a : open) {
            String anchorId = (String) a.get("anchorLedgerId");
            if (anchorId == null) {
                continue;
            }
            Map<String, Object> anchor = SqlRows.one(jdbc.sql("SELECT * FROM \"InventoryLedger\" WHERE id = ?")
                    .param(anchorId));
            if (anchor == null) {
                continue;
            }
            BigDecimal openAmount = SqlRows.dec(a, "openAmount");
            BigDecimal poolQty = SqlRows.dec(anchor, "balanceAfter");
            List<Event> events = eventsAfter(anchor);
            if (events.isEmpty()) {
                continue;
            }
            Distribution d = poolQty.signum() <= 0
                    ? new Distribution(BigDecimal.ZERO, List.of(), BigDecimal.ZERO, BigDecimal.ZERO)
                    : AvcoLineageCalculator.distribute(openAmount, poolQty, BigDecimal.ZERO, events, SCALE);
            BigDecimal out = poolQty.signum() <= 0 ? openAmount : openAmount.subtract(d.onHand());
            if (out.signum() == 0) {
                continue;
            }
            String component = (String) a.get("valuationComponent");
            String adjunctAccount = "nrv_allowance".equals(component) ? nrvAllowanceAccount() : priceDifferenceAccount();
            String productId = (String) a.get("productId");
            String variantId = (String) a.get("variantId");
            String locationId = (String) a.get("locationId");
            Map<String, BigDecimal> targets = new LinkedHashMap<>();
            for (Share s : d.consumed()) {
                targets.merge(target(s), s.amount(), BigDecimal::add);
            }
            if (poolQty.signum() <= 0) {
                targets.put("cogs", openAmount);
            }
            for (Map.Entry<String, BigDecimal> t : targets.entrySet()) {
                if (t.getValue().signum() == 0) {
                    continue;
                }
                String account = switch (t.getKey()) {
                    case "wip" -> determination.mapped("wip");
                    case "transfer" -> adjunctAccount; // stays in inventory (re-anchored below at the source pool)
                    default -> "nrv_allowance".equals(component) ? nrvExpenseAccount() : determination.mapped("cogs");
                };
                if (!account.equals(adjunctAccount)) {
                    byAccountDebit.merge(account, t.getValue(), BigDecimal::add);
                    byAccountDebit.merge(adjunctAccount, t.getValue().negate(), BigDecimal::add);
                }
            }
            BigDecimal transferred = targets.getOrDefault("transfer", BigDecimal.ZERO);
            BigDecimal releasedHere = out.subtract(transferred);
            recordValueOnly(productId, variantId, locationId, null, releasedHere.negate(), "cost_release", number,
                    component, "Released to cost of sales as consumed");
            String newAnchor = lastPoolRow(productId, variantId, locationId);
            jdbc.sql("UPDATE \"CostValueAdjustment\" SET \"openAmount\" = ?, \"anchorLedgerId\" = ? WHERE id = ?")
                    .params(openAmount.subtract(releasedHere), newAnchor, a.get("id")).update();
            total = total.add(releasedHere);
            released++;
        }
        String entryId = null;
        for (Map.Entry<String, BigDecimal> e : byAccountDebit.entrySet()) {
            BigDecimal v = e.getValue();
            if (v.signum() > 0) {
                lines.add(Line.debit(e.getKey(), JsNumbers.dec(v), "Price differences / NRV released " + number));
            } else if (v.signum() < 0) {
                lines.add(Line.credit(e.getKey(), JsNumbers.dec(v.negate()), "Price differences / NRV released "
                        + number));
            }
        }
        if (lines.size() >= 2) {
            Map<String, Object> je = posting.post(PostingRequest.of("INV", Instant.now(), "Inventory adjunct release "
                    + number, "cost_release", number, "inventory:cost_release:" + org() + ":" + runId, lines));
            entryId = je == null ? null : (String) je.get("id");
        }
        audit.recordInTx(AuditInput.of("CostValueAdjustment", runId, "post", null, Obj.of("number", number,
                "adjustments", released, "total", total, "journalEntryId", entryId)));
        return Obj.of("number", number, "adjustments", released, "released", total, "journalEntryId", entryId);
    }

    static Timestamp ts(Instant i) {
        return SqlRows.ts(i);
    }

    /** A JDBC timestamp parameter from whatever the row mapper returned (Instant, LocalDateTime, Timestamp). */
    public static Timestamp tsOf(Object v) {
        if (v == null || v instanceof Timestamp) {
            return (Timestamp) v;
        }
        if (v instanceof Instant i) {
            return SqlRows.ts(i);
        }
        if (v instanceof java.time.LocalDateTime l) {
            return Timestamp.valueOf(l);
        }
        if (v instanceof java.time.OffsetDateTime o) {
            return SqlRows.ts(o.toInstant());
        }
        return Timestamp.valueOf(v.toString().replace('T', ' ').replace("Z", ""));
    }
}
