package com.inventoryerp.pos.application;

import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One rule for "may these items leave this order?" (port of {@code order/order-mutation-policy.ts}): every path
 * that drops or reduces a line the kitchen already has demands the authority of a per-line void.
 */
@Component
public class OrderMutationPolicy {

    /** A line the kitchen has already been told to cook. */
    public record FiredLine(String id, String description, double quantity, double kitchenPrintedQty) {}

    private static final Set<String> NON_REASONS = Set.of("", "order emptied", "cleared", "n/a", "na", "-", "none", "test");

    private final PricingPolicy pricing;
    private final PosOverridesService overrides;

    public OrderMutationPolicy(PricingPolicy pricing, PosOverridesService overrides) {
        this.pricing = pricing;
        this.overrides = overrides;
    }

    /** A reason a manager could actually review later. */
    public static boolean isMeaningfulReason(String reason) {
        String r = reason == null ? "" : reason.trim();
        return r.length() >= 3 && !NON_REASONS.contains(r.toLowerCase());
    }

    /** Active lines of the order already sent to the kitchen. */
    public static List<FiredLine> firedLinesOn(JdbcClient jdbc, String orderId) {
        List<FiredLine> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("SELECT id, description, quantity, \"kitchenPrintedQty\" FROM \"OrderItem\""
                + " WHERE \"orderId\" = ? AND cancelled = false ORDER BY \"lineNumber\" ASC").param(orderId).query().listOfRows()) {
            double fired = PosSql.num(r.get("kitchenPrintedQty"));
            if (fired > 0) {
                out.add(new FiredLine((String) r.get("id"), (String) r.get("description"), PosSql.num(r.get("quantity")),
                        fired));
            }
        }
        return out;
    }

    /** A fired line may not be removed, nor cut below what was fired. */
    public static void assertNoFiredItemLoss(List<Map<String, Object>> removed, List<Map<String, Object>> cut) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> r : removed) {
            if (PosSql.num(r.get("kitchenPrintedQty")) > 0) {
                names.add((String) r.get("description"));
            }
        }
        if (names.isEmpty() && cut.isEmpty()) {
            return;
        }
        for (Map<String, Object> c : cut) {
            names.add((String) c.get("description"));
        }
        throw ApiException.conflict("Already sent to the kitchen: " + String.join(", ", names)
                + ". Void the item (reason + manager PIN) instead of removing it.");
    }

    /** Whole-order cancellation gate once the kitchen holds food; returns the fired lines. */
    public List<FiredLine> assertOrderCancellationAllowed(JdbcClient jdbc, String orderId, String reason,
            String overrideById, String overridePin) {
        List<FiredLine> fired = firedLinesOn(jdbc, orderId);
        if (fired.isEmpty()) {
            return fired;
        }
        String names = String.join(", ", fired.stream().map(FiredLine::description).toList());
        List<String> permissions = pricing.currentPermissions(PosSql.user());
        if (!permissions.contains("pos:void")) {
            throw ApiException.forbidden("This order has items the kitchen has already cooked (" + names
                    + "). Cancelling it requires the void permission.");
        }
        if (!isMeaningfulReason(reason)) {
            throw ApiException.badRequest("This order has items the kitchen has already cooked (" + names
                    + "). Give a reason for cancelling it.");
        }
        if (overrideById == null || overrideById.isEmpty()) {
            throw ApiException.forbidden("This order has items the kitchen has already cooked (" + names
                    + "). A manager approval and PIN are required to cancel it.");
        }
        overrides.verifyOperationApproval(overrideById, overridePin, "void");
        return fired;
    }
}
