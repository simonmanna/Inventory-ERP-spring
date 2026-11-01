package com.inventoryerp.costing.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.costing.application.AllocationCalculator.Receiver;
import com.inventoryerp.costing.application.AllocationCalculator.Rounding;
import com.inventoryerp.inventory.application.AvcoLineageCalculator.Distribution;
import com.inventoryerp.inventory.application.AvcoLineageCalculator.Share;
import com.inventoryerp.inventory.application.CostLineageService;
import com.inventoryerp.inventory.application.CostLineageService.AdjustmentInput;
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
    public record Part(Layer layer, String target, String accountId, BigDecimal amount, Distribution distribution) {}

    private final JdbcClient jdbc;
    private final CostLineageService lineage;
    private final AccountDeterminationService determination;

    public LineageCapitalisation(JdbcClient jdbc, CostLineageService lineage,
            AccountDeterminationService determination) {
        this.jdbc = jdbc;
        this.lineage = lineage;
        this.determination = determination;
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

    /** Split {@code amount} (signed) over the layers by value and each share over its lineage. */
    public List<Part> spread(BigDecimal amount, List<Layer> layers) {
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
            Distribution d = signed(lineage.distribute(layer.ledgerId(), s.amount().abs()), s.amount().signum());
            lineage.byTarget(d).forEach((target, part) -> {
                if (part.signum() != 0) {
                    out.add(new Part(layer, target, account(target), part, d));
                }
            });
        }
        return out;
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
        for (Part p : parts) {
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
    }

    /** Refuse when part of a source's adjunct was already released (call before reversing its journal). */
    public void assertUnreleased(String sourceType, String sourceId) {
        for (Map<String, Object> adj : open(sourceType, sourceId)) {
            if (SqlRows.dec(adj, "openAmount").compareTo(SqlRows.dec(adj, "onHandAmount")) != 0) {
                throw ApiException.badRequest("Part of this amount was already released to cost of sales; post a"
                        + " correcting document instead");
            }
        }
    }

    private List<Map<String, Object>> open(String sourceType, String sourceId) {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"CostValueAdjustment\" WHERE \"organizationId\" = ? AND"
                + " \"sourceType\" = ? AND \"sourceId\" = ? AND \"reversedAt\" IS NULL").params(org(), sourceType,
                sourceId));
    }

    private String account(String target) {
        return switch (target) {
            case "on_hand", "transfer" -> lineage.priceDifferenceAccount();
            case "wip" -> determination.mapped("wip");
            case "scrap" -> determination.mapped("stock_adjustment_expense");
            default -> determination.mapped("cogs");
        };
    }

    private static Distribution signed(Distribution d, int sign) {
        if (sign >= 0) {
            return d;
        }
        return new Distribution(d.negativeStock().negate(), d.consumed().stream().map(s -> new Share(s.ledgerId(),
                s.moveType(), s.referenceType(), s.referenceId(), s.amount().negate())).toList(), d.onHand().negate(),
                d.survival());
    }
}
