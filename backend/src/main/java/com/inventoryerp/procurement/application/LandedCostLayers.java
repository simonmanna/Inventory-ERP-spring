package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;

import com.inventoryerp.inventory.application.AvcoLineageCalculator;
import com.inventoryerp.inventory.application.AvcoLineageCalculator.Distribution;
import com.inventoryerp.inventory.application.CostLineageService;
import com.inventoryerp.inventory.application.CostLineageService.AdjustmentInput;
import com.inventoryerp.inventory.application.LineageFollower;
import com.inventoryerp.inventory.application.LineageFollower.Followed;
import com.inventoryerp.inventory.application.LineageFollower.Mode;
import com.inventoryerp.inventory.application.LineageFollower.Placement;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Re-costing of the stock one goods receipt put away (shared by the legacy landed cost and the ADR-S007 voucher). The
 * share of a receipt movement still on hand raises its cost layer — the lot, the receipt's serials or the AVCO
 * running average — and the rest has already been consumed (→ COGS). STANDARD items are never rebased. With
 * {@code write = false} nothing is changed (allocation preview).
 *
 * <p>CM-1 (ADR-S031): with a source, every write also records the value change in the stock ledger (value-only row)
 * and as a {@code CostValueAdjustment}. STANDARD items are no longer expensed whole: the charge is split over the
 * receipt's lineage (AD-2b) — still on hand (or transferred) → inventory price-difference adjunct, consumed by an open
 * production order → WIP, consumed otherwise → cost of sales.
 */
@Component
class LandedCostLayers {

    private static final int SIX = 6;

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    /** One receipt movement: what the receipt put into stock for (product, variant, location, lot). */
    record Group(Map<String, Object> product, String productId, String variantId, String locationId, String batchId,
            BigDecimal[] qty, BigDecimal[] value) {}

    /**
     * Share capitalised into the layer (for STANDARD: into the price-difference adjunct), quantity it was spread over,
     * unit cost before / after, and the share that went into open WIP (STANDARD only).
     */
    record Applied(BigDecimal capitalized, BigDecimal onHand, BigDecimal unitBefore, BigDecimal unitAfter,
            BigDecimal wip, Map<String, Object> disposition) {
        static final Applied NONE = new Applied(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO);

        Applied(BigDecimal capitalized, BigDecimal onHand, BigDecimal unitBefore, BigDecimal unitAfter) {
            this(capitalized, onHand, unitBefore, unitAfter, Money.ZERO, null);
        }

        Applied(BigDecimal capitalized, BigDecimal onHand, BigDecimal unitBefore, BigDecimal unitAfter,
                BigDecimal wip) {
            this(capitalized, onHand, unitBefore, unitAfter, wip, null);
        }

        /** LC-1: a disposition amount (0 when absent): parked (1396), returned (policy account). */
        BigDecimal part(String key) {
            if (disposition == null || !(disposition.get(key) instanceof BigDecimal b)) {
                return Money.ZERO;
            }
            return b;
        }
    }

    /** The document posting the charge (stock-ledger reference of the value rows). */
    record Source(String sourceType, String sourceId) {}

    private final JdbcClient jdbc;
    private final CostLineageService lineage;
    private final LineageFollower follower;
    private final SettingResolverService settings;

    LandedCostLayers(JdbcClient jdbc, CostLineageService lineage, LineageFollower follower,
            SettingResolverService settings) {
        this.jdbc = jdbc;
        this.lineage = lineage;
        this.follower = follower;
        this.settings = settings;
    }

    private static BigDecimal mul(BigDecimal a, BigDecimal b) {
        return a.multiply(b, Money.DECIMAL_JS);
    }

    private static BigDecimal dp6(BigDecimal v) {
        return v.setScale(SIX, RoundingMode.HALF_UP);
    }

    // ================================================================ LC-1 lineage mode (vouchers)

    /** The landed-cost return policy of the organisation (ADR-S053 D2). */
    String returnPolicy() {
        String v = settings.resolveString("costing.landedCost.returnPolicy", SettingContext.EMPTY);
        return v == null || v.isBlank() ? "expense" : v;
    }

    /**
     * LC-1 (ADR-S053): the charge follows the receipt's goods wherever they are now. AVCO / FIFO pools and STANDARD
     * items go through {@link LineageFollower} (transfers followed, on hand raised in the pool where the goods are,
     * consumption to WIP / COGS / scrap / return, unpaired lineage parked in 1396); lots follow their batch number
     * across locations; serials their receipt. The returned {@link Applied#disposition()} has the amount per target:
     * {@code stock} (capitalised), {@code wip}, {@code cogs} (incl. scrap and negative stock), {@code parked} (1396),
     * {@code returned} (return-policy account) plus the method used, for the voucher's journal and its reversal.
     */
    Applied applyLineage(String grnId, Group g, BigDecimal allocated, boolean write, Source source) {
        BigDecimal qty = g.qty()[0];
        if (allocated.signum() == 0 || qty.signum() <= 0) {
            return disposed(Applied.NONE, allocated, "none", Map.of());
        }
        if (Boolean.TRUE.equals(g.product().get("serialTracking"))) {
            Applied a = apply(grnId, g, allocated, write, source);
            return disposed(a, allocated, "serial", Map.of());
        }
        if (g.batchId() != null) {
            return lotLineage(g, allocated, write, source);
        }
        boolean standard = "STANDARD".equals(g.product().get("costingMethod"));
        Map<String, Object> layer = lineage.receiptLayer(grnId, g.productId(), g.variantId(), g.locationId(), null);
        BigDecimal std = standard ? jdbc.sql("SELECT coalesce(\"costPrice\", 0) FROM \"Product\" WHERE id = ?")
                .param(g.productId()).query(BigDecimal.class).optional().orElse(Money.ZERO) : null;
        BigDecimal before = standard ? std : average(g.productId(), g.variantId(), g.locationId());
        if (layer == null) {
            return disposed(new Applied(Money.ZERO, Money.ZERO, before, before), allocated, standard ? "standard"
                    : "average", Map.of());
        }
        // A credit (negative amount) follows the same lineage with the opposite sign.
        Followed f = follower.follow((String) layer.get("id"), allocated.abs());
        if (allocated.signum() < 0) {
            f = f.negate();
        }
        if (!standard) {
            f = follower.retargetEmptyPools(f);
        }
        Map<String, BigDecimal> t = f.byTarget();
        BigDecimal onHand = t.getOrDefault("on_hand", Money.ZERO);
        BigDecimal wip = t.getOrDefault("wip", Money.ZERO);
        BigDecimal parked = t.getOrDefault("in_transit", Money.ZERO);
        BigDecimal returned = t.getOrDefault("return", Money.ZERO);
        String policy = returnPolicy();
        BigDecimal rootOnHand = f.of(0).stream().filter(p -> "on_hand".equals(p.target())).map(Placement::amount)
                .reduce(Money.ZERO, BigDecimal::add);
        BigDecimal rootQty = poolQty(g.productId(), g.variantId(), g.locationId());
        BigDecimal after = standard || rootQty.signum() <= 0 ? before : before.add(Money.div(rootOnHand, rootQty));
        BigDecimal onHandQty = Money.dec(layer.get("quantityChange")).multiply(f.nodes().getFirst().distribution()
                .survival()).setScale(SIX, RoundingMode.HALF_UP);
        if (write && source != null) {
            follower.record(f, standard ? "std_price_difference" : "landed_cost", source.sourceType(),
                    source.sourceId(), null, standard ? Mode.ADJUNCT : Mode.AVERAGE, standard
                            ? "Landed cost on a standard-costed item (price difference)" : "Landed cost capitalised");
            if ("recover_from_supplier".equals(policy)) {
                for (Placement p : f.placements()) {
                    if ("return".equals(p.target())) {
                        follower.park(p.productId(), p.variantId(), p.locationId(), p.consumptionLedgerId(),
                                p.amount(), "landed_cost", source.sourceType(), source.sourceId(), null, null, null,
                                (String) layer.get("id"), "Landed cost on returned goods · awaiting supplier recovery");
                    }
                }
            }
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("returnPolicy", policy);
        extra.put("pools", f.nodes().size());
        return disposed(new Applied(onHand, onHandQty, before, after, wip), allocated, standard ? "standard"
                : "average", extra, parked, returned, policy);
    }

    private Applied disposed(Applied a, BigDecimal allocated, String mode, Map<String, Object> extra) {
        return disposed(a, allocated, mode, extra, Money.ZERO, Money.ZERO, returnPolicy());
    }

    private Applied disposed(Applied a, BigDecimal allocated, String mode, Map<String, Object> extra,
            BigDecimal parked, BigDecimal returned, String policy) {
        Map<String, Object> d = new LinkedHashMap<>(extra);
        d.put("mode", mode);
        d.put("stock", a.capitalized());
        d.put("wip", a.wip());
        d.put("parked", parked);
        // A recovery is parked in 1396 until the supplier's credit, otherwise expensed on the returns account.
        boolean recover = "recover_from_supplier".equals(policy);
        d.put("returned", recover ? Money.ZERO : returned);
        d.put("returnParked", recover ? returned : Money.ZERO);
        d.put("cogs", allocated.subtract(a.capitalized()).subtract(a.wip()).subtract(parked).subtract(returned));
        return new Applied(a.capitalized(), a.onHand(), a.unitBefore(), a.unitAfter(), a.wip(), d);
    }

    private BigDecimal average(String productId, String variantId, String locationId) {
        return jdbc.sql("SELECT coalesce(max(\"runningAverageCost\"), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                        + " AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ?")
                .params(org(), productId, variantId == null ? "" : variantId, locationId).query(BigDecimal.class)
                .single();
    }

    private BigDecimal poolQty(String productId, String variantId, String locationId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                        + " AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ?")
                .params(org(), productId, variantId == null ? "" : variantId, locationId).query(BigDecimal.class)
                .single();
    }

    /**
     * Lot (F2): transfers move a lot by creating batch rows with the same batch number at the destination, so the
     * receipt's units still on hand are that batch number's quantity at every location (at most the receipt quantity).
     * Each such batch row's unit cost rises by the charge per received unit; the rest was consumed and is classified
     * by the lot's own issue rows (production → WIP of its order, waste → scrap, return → return, else COGS).
     */
    private Applied lotLineage(Group g, BigDecimal allocated, boolean write, Source source) {
        String orgId = org();
        String number = jdbc.sql("SELECT \"batchNumber\" FROM \"InventoryBatch\" WHERE id = ? AND \"organizationId\" = ?")
                .params(g.batchId(), orgId).query(String.class).optional().orElse(null);
        if (number == null) {
            return disposed(Applied.NONE, allocated, "lot", Map.of());
        }
        List<Map<String, Object>> batches = SqlRows.list(jdbc.sql("SELECT id, \"locationId\", quantity, \"unitCost\""
                        + " FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"variantId\""
                        + " IS NOT DISTINCT FROM ? AND \"batchNumber\" = ? AND quantity > 0 AND \"isActive\" = true ORDER BY"
                        + " (id = ?) DESC, \"receivedAt\", id" + (write ? " FOR UPDATE" : ""))
                .params(orgId, g.productId(), g.variantId(), number, g.batchId()));
        BigDecimal qty = g.qty()[0];
        BigDecimal perUnit = Money.div(allocated, qty);
        BigDecimal remaining = qty;
        BigDecimal capitalized = Money.ZERO;
        BigDecimal onHand = Money.ZERO;
        List<Map<String, Object>> touched = new ArrayList<>();
        BigDecimal before = Money.ZERO;
        BigDecimal after = Money.ZERO;
        for (Map<String, Object> b : batches) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal bq = SqlRows.dec(b, "quantity");
            BigDecimal units = Money.min(bq, remaining);
            BigDecimal amount = dp2(mul(perUnit, units));
            remaining = remaining.subtract(units);
            onHand = onHand.add(units);
            capitalized = capitalized.add(amount);
            BigDecimal unitBefore = Money.dec(b.get("unitCost"));
            BigDecimal unitAfter = unitBefore.add(Money.div(amount, bq));
            if (touched.isEmpty()) {
                before = unitBefore;
                after = unitAfter;
            }
            touched.add(Map.of("batchId", b.get("id"), "locationId", b.get("locationId"), "units", units, "amount",
                    amount));
            if (write && amount.signum() != 0) {
                jdbc.sql("UPDATE \"InventoryBatch\" SET \"unitCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?"
                        + " AND \"organizationId\" = ?").params(unitAfter, b.get("id"), orgId).update();
                if (source != null) {
                    lineage.recordValueOnly(g.productId(), g.variantId(), (String) b.get("locationId"),
                            (String) b.get("id"), amount, source.sourceType(), source.sourceId(), null,
                            "Landed cost capitalised (lot " + number + ")");
                    lineage.recordAdjustment(new AdjustmentInput("landed_cost", source.sourceType(), source.sourceId(),
                            g.productId(), g.variantId(), (String) b.get("locationId"), (String) b.get("id"), null,
                            amount, null, null, new Distribution(Money.ZERO, List.of(), amount, BigDecimal.ONE)));
                }
            }
        }
        // Consumed part, classified by the lot's own outbound rows (transfers are not consumption).
        BigDecimal consumed = allocated.subtract(capitalized);
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        BigDecimal weightTotal = Money.ZERO;
        if (consumed.signum() != 0) {
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.id, l.type::text AS type, l.\"referenceType\","
                            + " l.\"referenceId\", -l.\"quantityChange\" AS q FROM \"InventoryLedger\" l JOIN"
                            + " \"InventoryBatch\" b ON b.id = l.\"batchId\" WHERE l.\"organizationId\" = ? AND"
                            + " b.\"productId\" = ? AND b.\"batchNumber\" = ? AND l.\"quantityChange\" < 0 AND l.type <>"
                            + " 'transfer_out'").params(orgId, g.productId(), number))) {
                String target = follower.target(new AvcoLineageCalculator.Share((String) r.get("id"),
                        (String) r.get("type"), (String) r.get("referenceType"), (String) r.get("referenceId"),
                        Money.ZERO));
                weights.merge(target, SqlRows.dec(r, "q"), BigDecimal::add);
                weightTotal = weightTotal.add(SqlRows.dec(r, "q"));
            }
        }
        BigDecimal wip = Money.ZERO;
        BigDecimal returned = Money.ZERO;
        if (weightTotal.signum() > 0) {
            wip = dp2(Money.div(mul(consumed, weights.getOrDefault("wip", Money.ZERO)), weightTotal));
            returned = dp2(Money.div(mul(consumed, weights.getOrDefault("return", Money.ZERO)), weightTotal));
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("batchNumber", number);
        extra.put("perUnit", perUnit);
        extra.put("batches", touched);
        String policy = returnPolicy();
        if (write && source != null && returned.signum() != 0 && "recover_from_supplier".equals(policy)) {
            follower.park(g.productId(), g.variantId(), g.locationId(), null, returned, "landed_cost",
                    source.sourceType(), source.sourceId(), null, null, null, null,
                    "Landed cost on returned goods · awaiting supplier recovery");
        }
        return disposed(new Applied(capitalized, onHand, before, after, wip), allocated, "lot", extra, Money.ZERO,
                returned, policy);
    }

    /**
     * LC-1 reversal of a lineage-mode source, mirroring its posting. For each pool share the part still on hand now —
     * followed through any transfer since posting — is taken out of that pool; the part consumed since stays where the
     * consumption put it and is returned per product and target, for the caller to reclass (Dr stock or adjunct / Cr
     * the target). Lots are unwound per batch number; open adjuncts (STANDARD, parked) by their open amount.
     * Refused when a WIP share was already cascaded by a cost adjustment run, or a parked share was resolved.
     */
    Map<String, Map<String, BigDecimal>> reverseLineage(String sourceType, String sourceId, String originalEntryId,
            List<Map<String, Object>> allocations) {
        String orgId = org();
        Integer cascaded = jdbc.sql("SELECT count(*)::int FROM \"CostAdjustmentRunLine\" r JOIN \"CostValueAdjustmentLine\" l"
                + " ON l.id = r.\"adjustmentLineId\" JOIN \"CostValueAdjustment\" a ON a.id = l.\"adjustmentId\" WHERE"
                + " a.\"organizationId\" = ? AND a.\"sourceType\" = ? AND a.\"sourceId\" = ? AND r.status = 'posted'")
                .params(orgId, sourceType, sourceId).query(Integer.class).single();
        if (cascaded > 0) {
            throw ApiException.badRequest("Part of this landed cost was cascaded from WIP by a cost adjustment run;"
                    + " reverse that run first");
        }
        Integer resolved = jdbc.sql("SELECT count(*)::int FROM \"LineageException\" WHERE \"organizationId\" = ? AND"
                + " \"sourceType\" = ? AND \"sourceId\" = ? AND status <> 'open'").params(orgId, sourceType, sourceId)
                .query(Integer.class).single();
        if (resolved > 0) {
            throw ApiException.badRequest("Part of this landed cost was resolved from a lineage exception; post a credit"
                    + " voucher instead");
        }
        Map<String, Map<String, BigDecimal>> unwound = new LinkedHashMap<>();
        List<Map<String, Object>> adjustments = SqlRows.list(jdbc.sql("SELECT * FROM \"CostValueAdjustment\" WHERE"
                + " \"organizationId\" = ? AND \"sourceType\" = ? AND \"sourceId\" = ? AND \"reversedAt\" IS NULL"
                + " ORDER BY \"createdAt\", id").params(orgId, sourceType, sourceId));
        String note = "Landed cost " + sourceId + " reversed";
        for (Map<String, Object> a : adjustments) {
            String product = (String) a.get("productId");
            String variant = (String) a.get("variantId");
            String location = (String) a.get("locationId");
            if (a.get("valuationComponent") != null) {
                BigDecimal open = SqlRows.dec(a, "openAmount");
                BigDecimal carried = SqlRows.dec(a, "onHandAmount");
                lineage.recordValueOnly(product, variant, location, null, open.negate(), "reversal", originalEntryId,
                        (String) a.get("valuationComponent"), note);
                add(unwound, product, "cogs", carried.subtract(open));
                continue;
            }
            if (a.get("batchId") != null) {
                continue; // lots: unwound per batch number below
            }
            BigDecimal onHand = SqlRows.dec(a, "onHandAmount");
            if (onHand.signum() == 0 || a.get("anchorLedgerId") == null) {
                continue;
            }
            Map<String, Object> anchor = SqlRows.one(jdbc.sql("SELECT * FROM \"InventoryLedger\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(a.get("anchorLedgerId"), orgId));
            BigDecimal poolQty = anchor == null ? Money.ZERO : SqlRows.dec(anchor, "balanceAfter");
            if (poolQty.signum() <= 0) {
                add(unwound, product, "cogs", onHand);
                continue;
            }
            Followed f = follower.retargetEmptyPools(follower.follow(anchor, poolQty, Money.ZERO, onHand));
            for (Placement p : f.placements()) {
                if ("on_hand".equals(p.target())) {
                    lineage.recordValueOnly(p.productId(), p.variantId(), p.locationId(), null, p.amount().negate(),
                            "reversal", originalEntryId, null, note);
                    follower.raiseAverage(p.productId(), p.variantId(), p.locationId(), p.amount().negate());
                } else {
                    add(unwound, product, switch (p.target()) {
                        case "wip", "return" -> p.target();
                        default -> "cogs";
                    }, p.amount());
                }
            }
        }
        for (Map<String, Object> alloc : allocations) {
            unwindLot(alloc, originalEntryId, note, unwound);
        }
        lineage.closeAdjustments(sourceType, sourceId);
        jdbc.sql("UPDATE \"LineageException\" SET status = 'resolved', resolution = 'source reversed', \"resolvedBy\" = ?,"
                + " \"resolvedAt\" = " + NOW + " WHERE \"organizationId\" = ? AND \"sourceType\" = ? AND \"sourceId\" = ?"
                + " AND status = 'open'").params(TenantContext.userId(), orgId, sourceType, sourceId).update();
        return unwound;
    }

    private void unwindLot(Map<String, Object> alloc, String originalEntryId, String note,
            Map<String, Map<String, BigDecimal>> unwound) {
        Map<String, Object> d = disposition(alloc);
        if (d == null || !"lot".equals(d.get("mode")) || d.get("batchNumber") == null) {
            return;
        }
        String product = (String) alloc.get("productId");
        BigDecimal perUnit = Money.dec(d.get("perUnit"));
        BigDecimal units = Money.ZERO;
        BigDecimal capitalized = Money.ZERO;
        if (d.get("batches") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    units = units.add(Money.dec(m.get("units")));
                    capitalized = capitalized.add(Money.dec(m.get("amount")));
                }
            }
        }
        BigDecimal remaining = units;
        BigDecimal removed = Money.ZERO;
        for (Map<String, Object> b : SqlRows.list(jdbc.sql("SELECT id, \"locationId\", \"variantId\", quantity,"
                        + " \"unitCost\" FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND"
                        + " \"batchNumber\" = ? AND quantity > 0 AND \"isActive\" = true ORDER BY \"receivedAt\", id FOR UPDATE")
                .params(org(), product, d.get("batchNumber")))) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal bq = SqlRows.dec(b, "quantity");
            BigDecimal take = Money.min(bq, remaining);
            BigDecimal amount = Money.min(capitalized.subtract(removed), dp2(mul(perUnit, take)));
            remaining = remaining.subtract(take);
            if (amount.signum() <= 0) {
                continue;
            }
            removed = removed.add(amount);
            jdbc.sql("UPDATE \"InventoryBatch\" SET \"unitCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(Money.max(Money.ZERO, Money.dec(b.get("unitCost")).subtract(Money.div(amount, bq))),
                            b.get("id")).update();
            lineage.recordValueOnly(product, (String) b.get("variantId"), (String) b.get("locationId"),
                    (String) b.get("id"), amount.negate(), "reversal", originalEntryId, null, note);
        }
        add(unwound, product, "cogs", capitalized.subtract(removed));
        jdbc.sql("UPDATE \"LandedCostAllocation\" SET \"reversedAmount\" = ? WHERE id = ?").params(removed,
                alloc.get("id")).update();
    }

    /** The LC-1 disposition stored on an allocation, or null for a legacy allocation. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> disposition(Map<String, Object> alloc) {
        Object raw = alloc.get("disposition");
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return (Map<String, Object>) JSON.readValue(raw.toString(), Map.class);
    }

    private static void add(Map<String, Map<String, BigDecimal>> m, String product, String target, BigDecimal v) {
        if (v.signum() == 0) {
            return;
        }
        m.computeIfAbsent(product, k -> new LinkedHashMap<>()).merge(target, v, BigDecimal::add);
    }

    private static BigDecimal dp2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** Raise the cost of whatever part of one receipt movement is still on hand; returns the capitalised share. */
    Applied apply(String grnId, Group g, BigDecimal allocated, boolean write) {
        return apply(grnId, g, allocated, write, null);
    }

    /** As {@link #apply(String, Group, BigDecimal, boolean)}, recording the value change against {@code source}. */
    Applied apply(String grnId, Group g, BigDecimal allocated, boolean write, Source source) {
        Applied applied = applyLayer(grnId, g, allocated, write, source);
        if (write && source != null && applied.capitalized().signum() != 0
                && !"STANDARD".equals(g.product().get("costingMethod"))) {
            lineage.recordValueOnly(g.productId(), g.variantId(), g.locationId(), g.batchId(), applied.capitalized(),
                    source.sourceType(), source.sourceId(), null, "Landed cost capitalised");
            lineage.recordAdjustment(new AdjustmentInput("landed_cost", source.sourceType(), source.sourceId(),
                    g.productId(), g.variantId(), g.locationId(), g.batchId(), null, allocated, null, null,
                    new Distribution(Money.ZERO, List.of(), applied.capitalized(), BigDecimal.ONE)));
        }
        return applied;
    }

    private Applied applyLayer(String grnId, Group g, BigDecimal allocated, boolean write, Source source) {
        BigDecimal qty = g.qty()[0];
        if (allocated.signum() <= 0 || qty.signum() <= 0) {
            return Applied.NONE;
        }
        String orgId = org();
        String variantKey = g.variantId() == null ? "" : g.variantId();
        String lock = write ? " FOR UPDATE" : "";

        if (Boolean.TRUE.equals(g.product().get("serialTracking"))) {
            List<Map<String, Object>> serials = jdbc.sql("SELECT id, \"unitCost\" FROM \"InventorySerial\""
                            + " WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"receiptRef\" = ? AND status = 'in_stock'"
                            + lock)
                    .params(orgId, g.productId(), "goods_receipt:" + grnId).query().listOfRows();
            BigDecimal perUnit = Money.div(allocated, qty);
            BigDecimal onHand = Money.min(BigDecimal.valueOf(serials.size()), qty);
            BigDecimal first = serials.isEmpty() ? Money.ZERO : Money.dec(serials.getFirst().get("unitCost"));
            int n = (int) Math.min(serials.size(), Math.max(0, Math.floor(onHand.doubleValue())));
            if (write) {
                for (Map<String, Object> s : serials.subList(0, n)) {
                    jdbc.sql("UPDATE \"InventorySerial\" SET \"unitCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?"
                                    + " AND \"organizationId\" = ?")
                            .params(Money.dec(s.get("unitCost")).add(perUnit), s.get("id"), orgId).update();
                }
            }
            return new Applied(dp6(mul(perUnit, onHand)), onHand, first, first.add(perUnit));
        }

        if (g.batchId() != null) {
            List<Map<String, Object>> found = jdbc.sql("SELECT id, quantity, \"unitCost\" FROM \"InventoryBatch\" WHERE"
                    + " id = ? AND \"organizationId\" = ?" + lock).params(g.batchId(), orgId).query().listOfRows();
            if (found.isEmpty()) {
                return Applied.NONE;
            }
            Map<String, Object> batch = found.getFirst();
            BigDecimal batchQty = Money.dec(batch.get("quantity"));
            BigDecimal before = Money.dec(batch.get("unitCost"));
            BigDecimal onHand = Money.max(Money.ZERO, Money.min(batchQty, qty));
            if (onHand.signum() <= 0) {
                return new Applied(Money.ZERO, Money.ZERO, before, before);
            }
            BigDecimal capitalized = dp6(Money.div(mul(allocated, onHand), qty));
            BigDecimal after = before.add(Money.div(capitalized, batchQty));
            if (write) {
                jdbc.sql("UPDATE \"InventoryBatch\" SET \"unitCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?"
                        + " AND \"organizationId\" = ?").params(after, batch.get("id"), orgId).update();
            }
            return new Applied(capitalized, onHand, before, after);
        }

        if ("STANDARD".equals(g.product().get("costingMethod"))) {
            return standardSplit(grnId, g, allocated, write, source);
        }
        return bumpAverage(g, variantKey, allocated, write);
    }

    /**
     * STANDARD (AD-2b): the standard unit cost never changes; the charge follows the receipt's lineage. On hand and
     * transferred → price-difference adjunct (capitalised), open WIP → WIP, the rest → cost of sales. Without a receipt
     * ledger row the whole charge goes to cost of sales (pre-CM-1 behaviour).
     */
    private Applied standardSplit(String grnId, Group g, BigDecimal allocated, boolean write, Source source) {
        Map<String, Object> layer = lineage.receiptLayer(grnId, g.productId(), g.variantId(), g.locationId(), null);
        BigDecimal std = jdbc.sql("SELECT coalesce(\"costPrice\", 0) FROM \"Product\" WHERE id = ?").param(g.productId())
                .query(BigDecimal.class).optional().orElse(Money.ZERO);
        if (layer == null) {
            return new Applied(Money.ZERO, Money.ZERO, std, std);
        }
        Distribution d = lineage.distribute((String) layer.get("id"), allocated);
        Map<String, BigDecimal> t = lineage.byTarget(d);
        BigDecimal adjunct = t.getOrDefault("on_hand", Money.ZERO).add(t.getOrDefault("transfer", Money.ZERO));
        BigDecimal wip = t.getOrDefault("wip", Money.ZERO);
        BigDecimal onHandQty = Money.dec(layer.get("quantityChange")).multiply(d.survival())
                .setScale(SIX, RoundingMode.HALF_UP);
        if (write && source != null) {
            lineage.recordValueOnly(g.productId(), g.variantId(), g.locationId(), null, adjunct, source.sourceType(),
                    source.sourceId(), "price_difference", "Landed cost on a standard-costed item (price difference)");
            lineage.recordAdjustment(new AdjustmentInput("std_price_difference", source.sourceType(),
                    source.sourceId(), g.productId(), g.variantId(), g.locationId(), null, (String) layer.get("id"),
                    allocated, "price_difference", null, d));
        }
        return new Applied(adjunct, onHandQty, std, std, wip);
    }

    /** AVCO quant: same advisory lock as the stock engine, then raise the running average. */
    private Applied bumpAverage(Group g, String variantKey, BigDecimal amount, boolean write) {
        String orgId = org();
        if (write) {
            String lockKey = "avco:" + orgId + ":" + g.productId() + ":" + variantKey + ":" + g.locationId();
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param(lockKey).query().listOfRows();
        }
        List<Map<String, Object>> found = jdbc.sql("SELECT id, quantity, \"runningAverageCost\" FROM \"StockItem\""
                        + " WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ?"
                        + (write ? " FOR UPDATE" : ""))
                .params(orgId, g.productId(), variantKey, g.locationId()).query().listOfRows();
        if (found.isEmpty()) {
            return Applied.NONE;
        }
        Map<String, Object> item = found.getFirst();
        BigDecimal qty = Money.dec(item.get("quantity"));
        BigDecimal before = Money.dec(item.get("runningAverageCost"));
        if (qty.signum() <= 0) {
            return new Applied(Money.ZERO, Money.ZERO, before, before);
        }
        // Units of this receipt assumed still on hand: at most what is on hand now.
        BigDecimal onHand = Money.min(qty, g.qty()[0]);
        BigDecimal capitalized = dp6(Money.div(mul(amount, onHand), g.qty()[0]));
        BigDecimal after = before.add(Money.div(capitalized, qty));
        if (write) {
            jdbc.sql("UPDATE \"StockItem\" SET \"runningAverageCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(after, item.get("id")).update();
        }
        return new Applied(capitalized, onHand, before, after);
    }

    /**
     * Undo one allocation's capitalisation as far as the goods are still on hand. The per-unit increment it added is
     * {@code unitCostAfter − unitCostBefore}; it is taken back from what is left of the layer. Returns the amount
     * removed from stock (≤ {@code capitalized}); the remainder was consumed since and is credited back to COGS.
     */
    BigDecimal remove(Map<String, Object> alloc, Map<String, Object> product, String grnId) {
        return remove(alloc, product, grnId, null);
    }

    /**
     * As {@link #remove(Map, Map, String)}; for STANDARD items the removable amount is what is still open of the
     * source's price-difference adjunct for that product and location (the rest was released to cost of sales).
     */
    BigDecimal remove(Map<String, Object> alloc, Map<String, Object> product, String grnId, Source source) {
        if ("STANDARD".equals(product.get("costingMethod")) && source != null) {
            String where = " WHERE \"organizationId\" = ? AND \"sourceType\" = ? AND \"sourceId\" = ? AND"
                    + " \"productId\" = ? AND \"locationId\" = ? AND \"reversedAt\" IS NULL";
            List<Object> params = List.of(org(), source.sourceType(), source.sourceId(), alloc.get("productId"),
                    alloc.get("locationId"));
            BigDecimal open = jdbc.sql("SELECT coalesce(sum(\"openAmount\"), 0) FROM \"CostValueAdjustment\"" + where)
                    .params(params).query(BigDecimal.class).single();
            jdbc.sql("UPDATE \"CostValueAdjustment\" SET \"openAmount\" = 0, \"reversedAt\" = now()" + where)
                    .params(params).update();
            return Money.min(Money.dec(alloc.get("capitalizedAmount")), open);
        }
        return removeLayer(alloc, product, grnId);
    }

    private BigDecimal removeLayer(Map<String, Object> alloc, Map<String, Object> product, String grnId) {
        BigDecimal capitalized = Money.dec(alloc.get("capitalizedAmount"));
        BigDecimal increment = Money.dec(alloc.get("unitCostAfter")).subtract(Money.dec(alloc.get("unitCostBefore")));
        BigDecimal onHandAtPost = Money.dec(alloc.get("onHandQty"));
        if (capitalized.signum() <= 0 || increment.signum() <= 0) {
            return Money.ZERO;
        }
        String orgId = org();
        String productId = (String) alloc.get("productId");
        if (Boolean.TRUE.equals(product.get("serialTracking"))) {
            List<Map<String, Object>> serials = jdbc.sql("SELECT id, \"unitCost\" FROM \"InventorySerial\" WHERE"
                            + " \"organizationId\" = ? AND \"productId\" = ? AND \"receiptRef\" = ? AND status = 'in_stock'"
                            + " FOR UPDATE")
                    .params(orgId, productId, "goods_receipt:" + grnId).query().listOfRows();
            int n = (int) Math.min(serials.size(), Math.floor(onHandAtPost.doubleValue()));
            for (Map<String, Object> s : serials.subList(0, n)) {
                jdbc.sql("UPDATE \"InventorySerial\" SET \"unitCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                        .params(Money.max(Money.ZERO, Money.dec(s.get("unitCost")).subtract(increment)), s.get("id"))
                        .update();
            }
            return Money.min(capitalized, dp6(mul(increment, BigDecimal.valueOf(n))));
        }
        if (alloc.get("batchId") != null) {
            List<Map<String, Object>> found = jdbc.sql("SELECT id, quantity, \"unitCost\" FROM \"InventoryBatch\" WHERE"
                    + " id = ? AND \"organizationId\" = ? FOR UPDATE").params(alloc.get("batchId"), orgId).query()
                    .listOfRows();
            if (found.isEmpty()) {
                return Money.ZERO;
            }
            BigDecimal q = Money.dec(found.getFirst().get("quantity"));
            if (q.signum() <= 0) {
                return Money.ZERO;
            }
            BigDecimal removed = Money.min(capitalized, dp6(mul(increment, q)));
            jdbc.sql("UPDATE \"InventoryBatch\" SET \"unitCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(Money.max(Money.ZERO, Money.dec(found.getFirst().get("unitCost")).subtract(
                            Money.div(removed, q))), found.getFirst().get("id"))
                    .update();
            return removed;
        }
        if ("STANDARD".equals(product.get("costingMethod"))) {
            return Money.ZERO;
        }
        String variantKey = alloc.get("variantId") == null ? "" : alloc.get("variantId").toString();
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("avco:" + orgId + ":" + productId + ":"
                + variantKey + ":" + alloc.get("locationId")).query().listOfRows();
        List<Map<String, Object>> found = jdbc.sql("SELECT id, quantity, \"runningAverageCost\" FROM \"StockItem\""
                        + " WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ?"
                        + " FOR UPDATE")
                .params(orgId, productId, variantKey, alloc.get("locationId")).query().listOfRows();
        if (found.isEmpty()) {
            return Money.ZERO;
        }
        BigDecimal q = Money.dec(found.getFirst().get("quantity"));
        BigDecimal avg = Money.dec(found.getFirst().get("runningAverageCost"));
        if (q.signum() <= 0) {
            return Money.ZERO;
        }
        // At most the capitalised amount, at most what the remaining quantity carries.
        BigDecimal removed = Money.min(capitalized, Money.min(dp6(mul(avg, q)), dp6(mul(increment, q))));
        jdbc.sql("UPDATE \"StockItem\" SET \"runningAverageCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(avg.subtract(Money.div(removed, q)), found.getFirst().get("id")).update();
        return removed;
    }
}
