package com.inventoryerp.manufacturing.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Demand ranking and netting (ADR-S017), pure. Firm customer demand (delivery schedule, confirmed sales orders,
 * proformas) comes before forecast, forecast before stock policy; within a class higher priority, then the earlier due
 * date, then the reference (stable). Supply is handed out in that order, so an urgent contractor delivery gets the
 * stock before a normal order and the shortfall lands on the least important demand.
 */
public final class DemandNetting {

    public static final int LOW = 1;
    public static final int NORMAL = 2;
    public static final int HIGH = 3;
    public static final int URGENT = 4;

    private DemandNetting() {}

    /** One requirement for a product; {@code priority} 1 low … 4 urgent. */
    public record Demand(String type, String id, String lineId, String ref, String productId, String partnerId,
            String siteId, Instant dueAt, int priority, BigDecimal qty) {}

    /** How much of a requirement the supply covers. */
    public record Allocation(Demand demand, BigDecimal covered, BigDecimal uncovered) {}

    /** Delivery order priority word to its rank (anything unknown is normal). */
    public static int priority(String word) {
        if (word == null) {
            return NORMAL;
        }
        return switch (word) {
            case "low" -> LOW;
            case "high" -> HIGH;
            case "urgent" -> URGENT;
            default -> NORMAL;
        };
    }

    static int typeClass(String type) {
        return switch (type) {
            case "delivery_order", "sales_order", "proforma" -> 0;
            case "forecast" -> 1;
            default -> 2;
        };
    }

    /** The planning sequence of a product's demand. */
    public static final Comparator<Demand> RANK = Comparator.<Demand>comparingInt(d -> typeClass(d.type()))
            .thenComparingInt(d -> -d.priority())
            .thenComparing(Demand::dueAt)
            .thenComparing(d -> d.ref() == null ? "" : d.ref());

    /** Hand {@code supply} (never negative in effect) to {@code demand} in {@link #RANK} order. */
    public static List<Allocation> allocate(BigDecimal supply, List<Demand> demand) {
        List<Demand> ranked = new ArrayList<>(demand);
        ranked.sort(RANK);
        BigDecimal left = supply == null ? BigDecimal.ZERO : supply.max(BigDecimal.ZERO);
        List<Allocation> out = new ArrayList<>();
        for (Demand d : ranked) {
            BigDecimal covered = d.qty().min(left);
            left = left.subtract(covered);
            out.add(new Allocation(d, covered, d.qty().subtract(covered)));
        }
        return out;
    }
}
