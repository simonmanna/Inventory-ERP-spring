package com.inventoryerp.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ABC (Pareto) classification by consumption value (roadmap 2.1, ADR-S026). Items are sorted by value, highest first;
 * an item is {@code A} while the cumulative share of the items before it is below {@code aPercent}, {@code B} while it
 * is below {@code bPercent}, else {@code C}. The first item with any value is therefore always {@code A}; items without
 * consumption are always {@code C}.
 */
public final class AbcClassifier {

    /** One input row. */
    public record Item(String id, BigDecimal value) {}

    /** One classified row; {@code cumulativeShare} is the percentage reached including this item. */
    public record Classified(String id, BigDecimal value, char abcClass, BigDecimal cumulativeShare) {}

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private AbcClassifier() {}

    public static List<Classified> classify(List<Item> items, BigDecimal aPercent, BigDecimal bPercent) {
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing((Item i) -> i.value() == null ? BigDecimal.ZERO : i.value()).reversed()
                .thenComparing(Item::id));
        BigDecimal total = sorted.stream().map(i -> i.value() == null ? BigDecimal.ZERO : i.value().max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Classified> out = new ArrayList<>(sorted.size());
        BigDecimal running = BigDecimal.ZERO;
        for (Item item : sorted) {
            BigDecimal value = item.value() == null ? BigDecimal.ZERO : item.value().max(BigDecimal.ZERO);
            if (total.signum() == 0 || value.signum() == 0) {
                out.add(new Classified(item.id(), value, 'C', total.signum() == 0 ? BigDecimal.ZERO
                        : running.multiply(HUNDRED).divide(total, 4, RoundingMode.HALF_UP)));
                continue;
            }
            BigDecimal before = running.multiply(HUNDRED).divide(total, 4, RoundingMode.HALF_UP);
            running = running.add(value);
            BigDecimal after = running.multiply(HUNDRED).divide(total, 4, RoundingMode.HALF_UP);
            char cls = before.compareTo(aPercent) < 0 ? 'A' : before.compareTo(bPercent) < 0 ? 'B' : 'C';
            out.add(new Classified(item.id(), value, cls, after));
        }
        return out;
    }
}
