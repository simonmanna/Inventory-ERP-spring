package com.inventoryerp.costing.application;

import com.inventoryerp.shared.costing.AllocationCalculator;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.shared.costing.AllocationCalculator.Receiver;
import com.inventoryerp.shared.costing.AllocationCalculator.Rounding;
import com.inventoryerp.inventory.application.AvcoLineageCalculator.Distribution;
import com.inventoryerp.inventory.application.AvcoLineageCalculator.Share;
import com.inventoryerp.inventory.application.CostLineageService;
import com.inventoryerp.inventory.application.CostLineageService.AdjustmentInput;
import com.inventoryerp.inventory.application.LineageFollower;
import com.inventoryerp.inventory.application.LineageFollower.Followed;
import com.inventoryerp.inventory.application.LineageFollower.Mode;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Capitalises a later cost into specific receipt layers through their lineage (Cost Management CM-5…CM-7, AD-2a): the
 * amount is split over the layers by value, each share follows its layer's consumption (on hand → price-difference
 * adjunct 1396 with a value-only stock-ledger row, sold → cost of sales, consumed by an open order → WIP, scrapped →
 * stock adjustment expense). Older layers are never touched. The caller posts the journal (debits from
 * {@link #debits}) and then {@link #record records} the lineage; {@link #reverse} undoes it while nothing has been
 * released yet.
 */
@Component
public class LineageCapitalisation {

    /** One receipt layer (stock-ledger row) that receives a share. */
    public record Layer(String ledgerId, String productId, String variantId, String locationId, BigDecimal value) {}

    /** One posted part: a layer's share at one lineage target, or a fallback with no layer (cost of sales). */
    public record Part(Layer layer, String target, String accountId, BigDecimal amount, Distribution distribution,
            Followed followed) {

        public Part(Layer layer, String target, String accountId, BigDecimal amount, Distribution distribution) {
            this(layer, target, accountId, amount, distribution, null);
        }
    }

    private final JdbcClient jdbc;
    private final CostLineageService lineage;
    private final AccountDeterminationService determination;
    private final LineageFollower follower;

    public LineageCapitalisation(JdbcClient jdbc, CostLineageService lineage,
            AccountDeterminationService determination, LineageFollower follower) {
        this.jdbc = jdbc;
        this.lineage = lineage;
        this.determination = determination;
        this.follower = follower;
    }

    /**
     * LC-1 (ADR-S053), landed-cost accruals: as {@link #spread} but every share follows its goods through transfers
     * ({@link LineageFollower}) — on hand at the destination pool → adjunct there, consumption at the destination →
     * its target, unpaired lineage → parked in 1396 with an exception. {@link #record} writes it per pool.
     */
    public List<Part> spreadFollowing(BigDecimal amount, List<Layer> layers) {
        List<Part> out = new ArrayList<>();
        if (amount.signum() == 0) {
            return out;
        }
        if (layers.isEmpty()) {
            out.add(new Part(null, "cogs", determination.mapped("cogs"), amount, null));
            return out;
        }
        Map<String, Layer> byId = new LinkedHashMap<>();
        List<Receiver> receivers = new ArrayList<>();
        for (Layer l : layers) {
            byId.put(l.ledgerId(), l);
            receivers.add(new Receiver(l.ledgerId(), l.value().max(BigDecimal.ONE.movePointLeft(6))));
        }
        for (AllocationCalculator.Share s : AllocationCalculator.allocate(amount, receivers, Rounding.LARGEST_REMAINDER,
                2)) {
            Layer layer = byId.get(s.key());
            Followed f = follower.follow(layer.ledgerId(), s.amount().abs());
            if (s.amount().signum() < 0) {
                f = f.negate();
            }
            Followed ff = f;
            f.byTarget().forEach((target, part) -> {
                if (part.signum() != 0) {
                    out.add(new Part(layer, target, accountFor(target), part, null, ff));
                }
            });
        }
        return out;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Positive receipt rows of the given stock-ledger reference, in posting order. */
    public List<Layer> receipts(String referenceType, List<String> referenceIds) {
        List<Layer> out = new ArrayList<>();
        if (referenceIds.isEmpty()) {
            return out;
        }
        List<Object> params = new ArrayList<>(List.of(org(), referenceType));
        params.addAll(referenceIds);
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, \"productId\", \"variantId\", \"locationId\","
                + " \"totalValue\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"referenceType\" = ? AND"
                + " \"quantityChange\" > 0 AND \"referenceId\" IN (" + String.join(", ", java.util.Collections.nCopies(
                        referenceIds.size(), "?")) + ") ORDER BY \"createdAt\", \"ledgerCode\"").params(params))) {
            out.add(new Layer((String) r.get("id"), (String) r.get("productId"), (String) r.get("variantId"),
                    (String) r.get("locationId"), SqlRows.dec(r, "totalValue").abs()));
        }
        return out;
    }

    /**
     * Split {@code amount} (signed) over the layers by value and each share over its lineage, following the goods
     * through transfers. Review 2026-11-17 (ACR-9; deviation): a share whose goods had moved on to another location
     * used to ride on the source pool's 1396 adjunct — released against the wrong goods, or to cost of sales once the
     * source pool was empty, while the goods were still on hand at the destination. It now lands where the goods are
     * (on hand there → adjunct of that pool; consumed there → its target), as landed-cost accruals already did.
     */
    public List<Part> spread(BigDecimal amount, List<Layer> layers) {
        return spreadFollowing(amount, layers);
    }

    /** Σ per account of the parts (positive = debit). */
    public Map<String, BigDecimal> debits(List<Part> parts) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Part p : parts) {
            out.merge(p.accountId(), p.amount(), BigDecimal::add);
        }
        return out;
    }

    /**
     * Write the value-only rows of the on-hand shares and one lineage adjustment per layer share. Returns the
     * adjustment id of each part (null for a fallback part).
     */
    public Map<Part, String> record(List<Part> parts, String adjustmentType, String sourceType, String sourceId,
            String journalEntryId, String note) {
        Map<Distribution, String> done = new IdentityHashMap<>();
        Map<Part, String> out = new IdentityHashMap<>();
        Map<Followed, String> followedDone = new IdentityHashMap<>();
        for (Part p : parts) {
            if (p.followed() != null) {
                if (!followedDone.containsKey(p.followed())) {
                    List<String> ids = follower.record(p.followed(), adjustmentType, sourceType, sourceId,
                            journalEntryId, Mode.ADJUNCT, note);
                    followedDone.put(p.followed(), ids.isEmpty() ? null : ids.getFirst());
                }
                out.put(p, followedDone.get(p.followed())); // every part of a layer share → its root adjustment
                continue;
            }
            if (p.distribution() == null) {
                continue;
            }
            String id = done.get(p.distribution());
            if (id == null) {
                Layer l = p.layer();
                BigDecimal onHand = p.distribution().onHand().add(p.distribution().consumed().stream()
                        .filter(s -> "transfer_out".equals(s.moveType())).map(Share::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
                lineage.recordValueOnly(l.productId(), l.variantId(), l.locationId(), null, onHand, sourceType,
                        sourceId, "price_difference", note);
                id = lineage.recordAdjustment(new AdjustmentInput(adjustmentType, sourceType, sourceId, l.productId(),
                        l.variantId(), l.locationId(), null, l.ledgerId(), p.distribution().total(), "price_difference",
                        journalEntryId, p.distribution()));
                done.put(p.distribution(), id);
            }
            out.put(p, id);
        }
        return out;
    }

    /**
     * Undo the lineage of a source after its journal was reversed: negative value-only rows for what is still on hand,
     * adjustments closed. Refused once any adjunct share has been released to cost of sales.
     */
    public void reverse(String sourceType, String sourceId, String originalJournalEntryId, String note) {
        List<Map<String, Object>> adjustments = open(sourceType, sourceId);
        for (Map<String, Object> adj : adjustments) {
            lineage.recordValueOnly((String) adj.get("productId"), (String) adj.get("variantId"),
                    (String) adj.get("locationId"), null, SqlRows.dec(adj, "onHandAmount").negate(), "reversal",
                    originalJournalEntryId, "price_difference", note);
        }
        lineage.closeAdjustments(sourceType, sourceId);
        // a share parked for an unpaired or unfinished transfer leaves with its source (as a landed-cost voucher's does)
        jdbc.sql("UPDATE \"LineageException\" SET status = 'resolved', resolution = 'source reversed', \"resolvedBy\" = ?,"
                + " \"resolvedAt\" = now() WHERE \"organizationId\" = ? AND \"sourceType\" = ? AND \"sourceId\" = ? AND"
                + " status = 'open'").params(TenantContext.userId(), org(), sourceType, sourceId).update();
    }

    /**
     * Review 2026-11-17 (ACR-9): refuse to undo a source once a share of it parked in a lineage exception was resolved
     * (assigned or written off) — that share is no longer where the reversal would take it from.
     */
    public void assertNoResolvedExceptions(String sourceType, String sourceId) {
        if (jdbc.sql("SELECT count(*) FROM \"LineageException\" WHERE \"organizationId\" = ? AND \"sourceType\" = ? AND"
                + " \"sourceId\" = ? AND status <> 'open'").params(org(), sourceType, sourceId).query(Long.class).single()
                > 0) {
            throw ApiException.badRequest("Part of this amount was resolved from a lineage exception; post a correcting"
                    + " document instead");
        }
    }

    /** Refuse when part of a source's adjunct was already released (call before reversing its journal). */
    public void assertUnreleased(String sourceType, String sourceId) {
        assertNotCascaded(sourceType, sourceId);
        assertNoResolvedExceptions(sourceType, sourceId);
        for (Map<String, Object> adj : open(sourceType, sourceId)) {
            if (SqlRows.dec(adj, "openAmount").compareTo(SqlRows.dec(adj, "onHandAmount")) != 0) {
                throw ApiException.badRequest("Part of this amount was already released to cost of sales; post a"
                        + " correcting document instead");
            }
        }
    }

    /**
     * Review 2026-11-17 (ACR-8): refuse to undo a source while a posted cost adjustment run has moved one of its WIP
     * shares on to the finished order's output — the reversal would credit WIP a second time for an amount that is no
     * longer in it. Reversing that run first puts the share back in WIP. (The landed-cost voucher reversal has the
     * same check of its own.)
     */
    public void assertNotCascaded(String sourceType, String sourceId) {
        String run = jdbc.sql("SELECT r.number FROM \"CostAdjustmentRunLine\" rl JOIN \"CostAdjustmentRun\" r ON r.id ="
                + " rl.\"runId\" LEFT JOIN \"CostValueAdjustmentLine\" l ON l.id = rl.\"adjustmentLineId\" LEFT JOIN"
                + " \"CostValueAdjustmentRelease\" x ON x.id = rl.\"releaseId\" JOIN \"CostValueAdjustment\" a ON a.id ="
                + " coalesce(l.\"adjustmentId\", x.\"adjustmentId\") WHERE a.\"organizationId\" = ? AND a.\"sourceType\" = ?"
                + " AND a.\"sourceId\" = ? AND a.\"reversedAt\" IS NULL AND rl.status = 'posted' ORDER BY r.\"createdAt\""
                + " DESC LIMIT 1").params(org(), sourceType, sourceId).query(String.class).optional().orElse(null);
        if (run != null) {
            throw ApiException.badRequest("Part of this amount was cascaded from WIP by cost adjustment run " + run
                    + "; reverse that run first");
        }
    }

    /**
     * Output receipt layers of a production order ({@code orderRef} = its id or code): the order's own receipts and
     * those of its stage postings (ADR-S060) — where a late WIP share of a completed order is carried on to.
     */
    public List<Layer> orderOutputs(String orderRef) {
        String code = jdbc.sql("SELECT \"orderCode\" FROM \"ProductionOrder\" WHERE \"organizationId\" = ? AND (id = ? OR"
                + " \"orderCode\" = ?) LIMIT 1").params(org(), orderRef, orderRef).query(String.class).optional()
                .orElse(orderRef);
        List<Layer> layers = new ArrayList<>(receipts("production_order", List.of(code)));
        layers.addAll(receipts("production_stage", jdbc.sql("SELECT p.id FROM \"ProductionStagePosting\" p JOIN"
                + " \"ProductionOrder\" o ON o.id = p.\"orderId\" WHERE p.\"organizationId\" = ? AND o.\"orderCode\" = ?"
                + " AND p.\"reversedAt\" IS NULL").params(org(), code).query(String.class).list()));
        return layers;
    }

    private List<Map<String, Object>> open(String sourceType, String sourceId) {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"CostValueAdjustment\" WHERE \"organizationId\" = ? AND"
                + " \"sourceType\" = ? AND \"sourceId\" = ? AND \"reversedAt\" IS NULL").params(org(), sourceType,
                sourceId));
    }

    /** GL account of a lineage target: on hand / transfer / in transit → adjunct, else as consumed (ADR-S055). */
    public String accountFor(String target) {
        return switch (target) {
            case "on_hand", "transfer", "in_transit" -> lineage.priceDifferenceAccount();
            default -> lineage.consumptionAccount(target);
        };
    }

}
