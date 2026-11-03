package com.inventoryerp.inventory.application;

import com.inventoryerp.inventory.application.AvcoLineageCalculator.Distribution;
import com.inventoryerp.inventory.application.AvcoLineageCalculator.Share;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Landed cost LC-1 (ADR-S053): distributes a later cost of one stock layer over its lineage <em>and follows the goods
 * through transfers</em>. A transfer is not consumption: the share of a {@code transfer_out} row is split over the
 * {@code transfer_in} rows that received its units ({@code InventoryTransferLineage}), pro rata by quantity, and each
 * part is distributed again over the destination pool from its in row — recursively (A → transit → B → C).
 *
 * <ul>
 *   <li>Units dispatched but not yet received (open lineage remainder) and transfers with no lineage are parked as
 *       {@code in_transit} (1396 adjunct), the latter with a {@code unpaired_transfer} exception.</li>
 *   <li>A ledger row met twice on one path is a {@code cycle}; deeper than {@link #MAX_DEPTH} hops is {@code depth}.
 *       Both are parked with an exception — a posting is never silently wrong.</li>
 *   <li>Consumption targets: {@code return_to_supplier} → {@code return}; production consumption → {@code wip} tagged
 *       with the order (an order completed since is cascaded onto its output by the CM-10 run); cancelled orders →
 *       {@code cogs}; waste / expiry / internal use → {@code scrap}; everything else → {@code cogs}.</li>
 * </ul>
 *
 * <p>Identity: Σ placements = amount exactly (every split carries its own residual).
 */
@Component
public class LineageFollower {

    /** Maximum transfer hops followed from the root layer. */
    public static final int MAX_DEPTH = 20;

    /** One final destination of part of the amount. {@code layerLedgerId} is the pool row the part was distributed on. */
    public record Placement(String target, String productId, String variantId, String locationId, String layerLedgerId,
            String consumptionLedgerId, String moveType, String referenceId, BigDecimal amount, String exceptionKind,
            int nodeIndex) {}

    /**
     * One pool visited: the layer row (receipt, or the transfer_in row that brought the units), the share it received,
     * its distribution and its parent node (−1 for the root).
     */
    public record Node(Map<String, Object> layer, BigDecimal amount, Distribution distribution, int parent,
            String viaTransferOutId) {

        public String ledgerId() {
            return (String) layer.get("id");
        }

        public String productId() {
            return (String) layer.get("productId");
        }

        public String variantId() {
            return (String) layer.get("variantId");
        }

        public String locationId() {
            return (String) layer.get("locationId");
        }
    }

    /** The whole followed distribution. */
    public record Followed(List<Node> nodes, List<Placement> placements) {

        /** Σ per target. */
        public Map<String, BigDecimal> byTarget() {
            Map<String, BigDecimal> out = new LinkedHashMap<>();
            for (Placement p : placements) {
                out.merge(p.target(), p.amount(), BigDecimal::add);
            }
            return out;
        }

        /** Σ of every placement (always the followed amount). */
        public BigDecimal total() {
            return placements.stream().map(Placement::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** Placements of one node. */
        public List<Placement> of(int node) {
            return placements.stream().filter(p -> p.nodeIndex() == node).toList();
        }

        /** Negated copy (credit vouchers, reversals). */
        public Followed negate() {
            List<Placement> neg = placements.stream().map(p -> new Placement(p.target(), p.productId(), p.variantId(),
                    p.locationId(), p.layerLedgerId(), p.consumptionLedgerId(), p.moveType(), p.referenceId(),
                    p.amount().negate(), p.exceptionKind(), p.nodeIndex())).toList();
            List<Node> nn = nodes.stream().map(n -> new Node(n.layer(), n.amount().negate(), n.distribution(),
                    n.parent(), n.viaTransferOutId())).toList();
            return new Followed(nn, neg);
        }
    }

    private final JdbcClient jdbc;
    private final CostLineageService lineage;

    public LineageFollower(JdbcClient jdbc, CostLineageService lineage) {
        this.jdbc = jdbc;
        this.lineage = lineage;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Follow {@code amount} (≥ 0) adjusting the layer {@code layerLedgerId} (no writes). */
    public Followed follow(String layerLedgerId, BigDecimal amount) {
        Map<String, Object> layer = row(layerLedgerId);
        if (layer == null) {
            throw ApiException.notFound("Stock ledger entry not found");
        }
        return follow(layer, SqlRows.dec(layer, "quantityChange"), SqlRows.dec(layer, "qtyBefore"), amount);
    }

    /**
     * Follow {@code amount} over a layer of {@code layerQty} units that arrived when the pool held {@code qtyBefore}.
     * A value-only anchor row is followed with {@code layerQty} = the pool quantity and {@code qtyBefore} = 0.
     */
    public Followed follow(Map<String, Object> layer, BigDecimal layerQty, BigDecimal qtyBefore, BigDecimal amount) {
        List<Node> nodes = new ArrayList<>();
        List<Placement> placements = new ArrayList<>();
        Set<String> path = new HashSet<>();
        visit(layer, layerQty, qtyBefore, amount, -1, null, 0, path, nodes, placements);
        return new Followed(List.copyOf(nodes), List.copyOf(placements));
    }

    private void visit(Map<String, Object> layer, BigDecimal layerQty, BigDecimal qtyBefore, BigDecimal amount,
            int parent, String via, int depth, Set<String> path, List<Node> nodes, List<Placement> out) {
        String layerId = (String) layer.get("id");
        path.add(layerId);
        Distribution d = AvcoLineageCalculator.distribute(amount, layerQty, qtyBefore, lineage.eventsAfter(layer),
                CostLineageService.SCALE);
        int me = nodes.size();
        nodes.add(new Node(layer, amount, d, parent, via));
        String product = (String) layer.get("productId");
        String variant = (String) layer.get("variantId");
        String location = (String) layer.get("locationId");
        if (d.onHand().signum() != 0) {
            out.add(new Placement("on_hand", product, variant, location, layerId, null, null, null, d.onHand(), null,
                    me));
        }
        if (d.negativeStock().signum() != 0) {
            out.add(new Placement("negative_stock", product, variant, location, layerId, null, null, null,
                    d.negativeStock(), null, me));
        }
        for (Share s : d.consumed()) {
            if (s.amount().signum() == 0) {
                continue;
            }
            if (!"transfer_out".equals(s.moveType())) {
                out.add(new Placement(target(s), product, variant, location, layerId, s.ledgerId(), s.moveType(),
                        s.referenceId(), s.amount(), null, me));
                continue;
            }
            followTransfer(s, product, variant, location, layerId, me, depth, path, nodes, out);
        }
        path.remove(layerId);
    }

    private void followTransfer(Share s, String product, String variant, String location, String layerId, int me,
            int depth, Set<String> path, List<Node> nodes, List<Placement> out) {
        Map<String, Object> outRow = row(s.ledgerId());
        BigDecimal sent = outRow == null ? BigDecimal.ZERO : SqlRows.dec(outRow, "quantityChange").negate();
        List<Map<String, Object>> links = SqlRows.list(jdbc.sql("SELECT \"destinationLedgerId\", quantity FROM"
                + " \"InventoryTransferLineage\" WHERE \"organizationId\" = ? AND \"sourceLedgerId\" = ? ORDER BY"
                + " \"createdAt\", id").params(org(), s.ledgerId()));
        if (links.isEmpty() || sent.signum() <= 0) {
            out.add(new Placement("in_transit", product, variant, location, layerId, s.ledgerId(), s.moveType(),
                    s.referenceId(), s.amount(), "unpaired_transfer", me));
            return;
        }
        BigDecimal linked = links.stream().map(l -> SqlRows.dec(l, "quantity")).reduce(BigDecimal.ZERO,
                BigDecimal::add);
        BigDecimal open = sent.subtract(linked).max(BigDecimal.ZERO);
        // Pro rata by lineage quantity; the open remainder (still in transit) or the last link takes the residual.
        List<BigDecimal> parts = new ArrayList<>();
        BigDecimal given = BigDecimal.ZERO;
        BigDecimal base = linked.add(open);
        for (int i = 0; i < links.size(); i++) {
            boolean last = i == links.size() - 1 && open.signum() == 0;
            BigDecimal part = last ? s.amount().subtract(given)
                    : s.amount().multiply(SqlRows.dec(links.get(i), "quantity")).divide(base, CostLineageService.SCALE,
                            RoundingMode.HALF_UP);
            parts.add(part);
            given = given.add(part);
        }
        if (open.signum() > 0) {
            out.add(new Placement("in_transit", product, variant, location, layerId, s.ledgerId(), s.moveType(),
                    s.referenceId(), s.amount().subtract(given), null, me));
        }
        for (int i = 0; i < links.size(); i++) {
            BigDecimal part = parts.get(i);
            if (part.signum() == 0) {
                continue;
            }
            String destId = (String) links.get(i).get("destinationLedgerId");
            Map<String, Object> dest = row(destId);
            String problem = dest == null ? "unpaired_transfer" : path.contains(destId) ? "cycle"
                    : depth + 1 > MAX_DEPTH ? "depth" : null;
            if (problem != null) {
                out.add(new Placement("in_transit", product, variant, location, layerId, s.ledgerId(), s.moveType(),
                        s.referenceId(), part, problem, me));
                continue;
            }
            BigDecimal qty = SqlRows.dec(links.get(i), "quantity");
            // The units of this link arrived on top of what the destination pool held before the in row.
            BigDecimal before = SqlRows.dec(dest, "qtyBefore")
                    .add(SqlRows.dec(dest, "quantityChange").subtract(qty).max(BigDecimal.ZERO));
            visit(dest, qty, before, part, me, s.ledgerId(), depth + 1, path, nodes, out);
        }
    }

    /**
     * Where a landed share of one consumption goes (LC-1): returns are their own target, production consumption is WIP
     * of its order unless the order was cancelled or reversed (a completed order's WIP share cascades to its output).
     */
    public String target(Share s) {
        String type = s.moveType();
        if ("return_to_supplier".equals(type)) {
            return "return";
        }
        if ("production_consume".equals(type)) {
            return productionOrderLive(s.referenceType(), s.referenceId()) ? "wip" : "cogs";
        }
        return AvcoLineageCalculator.target(type, false);
    }

    private boolean productionOrderLive(String referenceType, String referenceId) {
        if (referenceId == null || referenceType == null || !referenceType.startsWith("production")) {
            return false;
        }
        String status = jdbc.sql("SELECT status::text FROM \"ProductionOrder\" WHERE \"organizationId\" = ? AND (id = ?"
                        + " OR \"orderCode\" = ?) LIMIT 1").params(org(), referenceId, referenceId).query(String.class)
                .optional().orElse(null);
        return status != null && !List.of("cancelled", "reversed").contains(status);
    }

    private Map<String, Object> row(String ledgerId) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"InventoryLedger\" WHERE id = ? AND \"organizationId\" = ?")
                .params(ledgerId, org()));
    }

    /** How the on-hand share of each pool is carried. */
    public enum Mode {
        /** Into the quant's cost (AVCO running average) — value-only row without a component. */
        AVERAGE,
        /** Beside the quant's cost as an open 1396 adjunct (STANDARD price difference, accruals). */
        ADJUNCT
    }

    /**
     * AVERAGE mode: an on-hand share of a pool that holds no stock any more cannot raise its average; it goes to
     * {@code negative_stock} (cost of sales) instead. Returns the adjusted followed distribution.
     */
    public Followed retargetEmptyPools(Followed f) {
        List<Placement> out = new ArrayList<>();
        for (Placement p : f.placements()) {
            if ("on_hand".equals(p.target()) && poolQty(p.productId(), p.variantId(), p.locationId()).signum() <= 0) {
                out.add(new Placement("negative_stock", p.productId(), p.variantId(), p.locationId(), p.layerLedgerId(),
                        null, null, null, p.amount(), null, p.nodeIndex()));
            } else {
                out.add(p);
            }
        }
        return new Followed(f.nodes(), List.copyOf(out));
    }

    private BigDecimal poolQty(String productId, String variantId, String locationId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                        + " \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ?")
                .params(org(), productId, variantId == null ? "" : variantId, locationId).query(BigDecimal.class)
                .single();
    }

    /**
     * Write the stock side of a followed distribution: per pool a value-only row for its on-hand share (AVERAGE: and
     * the running average raised), one {@code CostValueAdjustment} per pool linked to its parent and root with a line
     * per target, and every parked share as an open 1396 adjunct (value-only row tagged {@code price_difference}) with
     * its {@code LineageException}. Returns the adjustment id per node.
     */
    public List<String> record(Followed f, String adjustmentType, String sourceType, String sourceId,
            String journalEntryId, Mode mode, String note) {
        List<String> ids = new ArrayList<>();
        String rootLedger = f.nodes().isEmpty() ? null : f.nodes().getFirst().ledgerId();
        for (int i = 0; i < f.nodes().size(); i++) {
            Node n = f.nodes().get(i);
            List<Placement> mine = f.of(i);
            BigDecimal onHand = mine.stream().filter(p -> "on_hand".equals(p.target())).map(Placement::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean adjunct = mode == Mode.ADJUNCT;
            String valueRow = null;
            if (onHand.signum() != 0) {
                valueRow = lineage.recordValueOnly(n.productId(), n.variantId(), n.locationId(), null, onHand,
                        sourceType, sourceId, adjunct ? "price_difference" : null, note);
                if (!adjunct) {
                    raiseAverage(n.productId(), n.variantId(), n.locationId(), onHand);
                }
            }
            String id = UUID.randomUUID().toString();
            // ADJUNCT: anchored at the pool's last row for release; AVERAGE: the value row (reversal follows it).
            String anchor = onHand.signum() == 0 ? null : adjunct
                    ? lineage.lastPoolRow(n.productId(), n.variantId(), n.locationId()) : valueRow;
            jdbc.sql("INSERT INTO \"CostValueAdjustment\" (id, \"organizationId\", \"adjustmentType\", \"sourceType\","
                            + " \"sourceId\", \"productId\", \"variantId\", \"locationId\", \"layerLedgerId\", amount,"
                            + " \"onHandAmount\", \"consumedAmount\", \"valuationComponent\", \"openAmount\","
                            + " \"anchorLedgerId\", \"journalEntryId\", \"createdBy\", \"parentAdjustmentId\","
                            + " \"rootAdjustmentId\", \"sourceReceiptLedgerId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                            + " ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(id, org(), adjustmentType, sourceType, sourceId, n.productId(), n.variantId(),
                            n.locationId(), n.ledgerId(), n.amount(), onHand, n.amount().subtract(onHand),
                            adjunct ? "price_difference" : null, adjunct ? onHand : BigDecimal.ZERO, anchor,
                            journalEntryId, TenantContext.userId(), n.parent() < 0 ? null : ids.get(n.parent()),
                            ids.isEmpty() ? id : ids.getFirst(), rootLedger)
                    .update();
            ids.add(id);
            Map<String, Object[]> lines = new LinkedHashMap<>();
            for (Placement p : mine) {
                String ref = "on_hand".equals(p.target()) ? p.locationId() : p.referenceId();
                lines.merge(p.consumptionLedgerId() + "|" + p.target(), new Object[] {p.consumptionLedgerId(),
                        p.target(), ref, p.amount()}, (a, b) -> new Object[] {a[0], a[1], a[2],
                        ((BigDecimal) a[3]).add((BigDecimal) b[3])});
            }
            for (int c = i + 1; c < f.nodes().size(); c++) {
                Node child = f.nodes().get(c);
                if (child.parent() == i) {
                    lines.merge(child.viaTransferOutId() + "|transfer", new Object[] {child.viaTransferOutId(),
                            "transfer", child.ledgerId(), child.amount()}, (a, b) -> new Object[] {a[0], a[1], a[2],
                            ((BigDecimal) a[3]).add((BigDecimal) b[3])});
                }
            }
            if (!lines.containsKey("null|on_hand")) {
                lines.put("null|on_hand", new Object[] {null, "on_hand", n.locationId(), BigDecimal.ZERO});
            }
            for (Object[] l : lines.values()) {
                jdbc.sql("INSERT INTO \"CostValueAdjustmentLine\" (id, \"organizationId\", \"adjustmentId\","
                                + " \"consumptionLedgerId\", target, \"targetRef\", amount) VALUES (?, ?, ?, ?, ?, ?, ?)"
                                + " ON CONFLICT DO NOTHING")
                        .params(UUID.randomUUID().toString(), org(), id, l[0], l[1], l[2], l[3]).update();
            }
            for (Placement p : mine) {
                if ("in_transit".equals(p.target())) {
                    String parked = park(p.productId(), p.variantId(), p.locationId(), p.consumptionLedgerId(),
                            p.amount(), adjustmentType, sourceType, sourceId, journalEntryId, id, ids.getFirst(),
                            rootLedger, note + " · parked (lineage)");
                    raiseException(p.exceptionKind() == null ? new Placement(p.target(), p.productId(), p.variantId(),
                            p.locationId(), p.layerLedgerId(), p.consumptionLedgerId(), p.moveType(), p.referenceId(),
                            p.amount(), "in_transit", p.nodeIndex()) : p, parked, sourceType, sourceId);
                }
            }
        }
        return ids;
    }

    /**
     * Park an amount in the 1396 adjunct: value-only row tagged {@code price_difference} at the pool, and an open
     * adjustment with no anchor (never released automatically — only through resolution). Returns its id.
     */
    public String park(String productId, String variantId, String locationId, String ledgerId, BigDecimal amount,
            String adjustmentType, String sourceType, String sourceId, String journalEntryId, String parentId,
            String rootId, String rootLedgerId, String note) {
        lineage.recordValueOnly(productId, variantId, locationId, null, amount, sourceType, sourceId,
                "price_difference", note);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CostValueAdjustment\" (id, \"organizationId\", \"adjustmentType\", \"sourceType\","
                        + " \"sourceId\", \"productId\", \"variantId\", \"locationId\", \"layerLedgerId\", amount,"
                        + " \"onHandAmount\", \"consumedAmount\", \"valuationComponent\", \"openAmount\", \"journalEntryId\","
                        + " \"createdBy\", \"parentAdjustmentId\", \"rootAdjustmentId\", \"sourceReceiptLedgerId\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'price_difference', ?, ?, ?, ?, ?, ?)")
                .params(id, org(), adjustmentType, sourceType, sourceId, productId, variantId, locationId, ledgerId,
                        amount, amount, amount, journalEntryId, TenantContext.userId(), parentId, rootId, rootLedgerId)
                .update();
        jdbc.sql("INSERT INTO \"CostValueAdjustmentLine\" (id, \"organizationId\", \"adjustmentId\","
                        + " \"consumptionLedgerId\", target, \"targetRef\", amount) VALUES (?, ?, ?, ?, 'in_transit', ?, ?)")
                .params(UUID.randomUUID().toString(), org(), id, ledgerId, locationId, amount).update();
        return id;
    }

    /** AVCO quant: same advisory lock as the stock engine, then raise the running average by {@code amount}. */
    public void raiseAverage(String productId, String variantId, String locationId, BigDecimal amount) {
        String variantKey = variantId == null ? "" : variantId;
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("avco:" + org() + ":" + productId + ":"
                + variantKey + ":" + locationId).query().listOfRows();
        Map<String, Object> item = SqlRows.one(jdbc.sql("SELECT id, quantity, \"runningAverageCost\" FROM"
                        + " \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ? AND"
                        + " \"locationId\" = ? FOR UPDATE").params(org(), productId, variantKey, locationId));
        if (item == null || SqlRows.dec(item, "quantity").signum() <= 0) {
            throw ApiException.badRequest("Cannot capitalise into an empty stock pool");
        }
        BigDecimal qty = SqlRows.dec(item, "quantity");
        BigDecimal after = SqlRows.dec(item, "runningAverageCost").add(amount.divide(qty, 10, RoundingMode.HALF_UP));
        jdbc.sql("UPDATE \"StockItem\" SET \"runningAverageCost\" = ?, \"updatedAt\" = " + StockService.NOW
                + " WHERE id = ?").params(after, item.get("id")).update();
    }

    /** Record a lineage exception for a parked placement; returns its id. */
    public String raiseException(Placement p, String adjustmentId, String sourceType, String sourceId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"LineageException\" (id, \"organizationId\", kind, \"ledgerId\", \"productId\","
                        + " \"locationId\", \"adjustmentId\", \"sourceType\", \"sourceId\", amount, details) VALUES (?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?, jsonb_build_object('layerLedgerId', ?::text))")
                .params(id, org(), p.exceptionKind(), p.consumptionLedgerId(), p.productId(), p.locationId(),
                        adjustmentId, sourceType, sourceId, p.amount(), p.layerLedgerId())
                .update();
        return id;
    }
}
