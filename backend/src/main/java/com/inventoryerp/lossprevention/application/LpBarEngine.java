package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.common.Obj;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Bar rules B01–B03 on stock-count lines. The count's own variance stays the authoritative figure; the engine decides
 * whether it is explained, repeated, or cannot be trusted because the movements do not tie.
 */
@Component
public class LpBarEngine implements LpEngine {

    /** Cause codes that leave a variance unexplained. Any other code is the counter's explanation. */
    static final Set<String> UNEXPLAINED = Set.of("unexplained", "unrecorded_sale");

    @Override
    public String code() {
        return LpRuleCatalog.BAR;
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(LpEventTypes.STOCK_COUNT_LINE);
    }

    @Override
    public Map<String, Integer> lookbackDays() {
        return Map.of(LpEventTypes.STOCK_COUNT_LINE, 120);
    }

    @Override
    public List<Finding> evaluate(Input in) {
        // A line sent again (a corrected count) replaces the earlier copy.
        Map<String, LedgerEvent> lines = new LinkedHashMap<>();
        for (LedgerEvent e : in.events()) {
            if (LpEventTypes.STOCK_COUNT_LINE.equals(e.type())) {
                lines.put(e.lineId() != null ? e.lineId() : e.id(), e);
            }
        }
        List<LedgerEvent> day = lines.values().stream().filter(e -> in.businessDate().equals(e.businessDate()))
                .toList();
        List<Finding> out = new ArrayList<>();
        Set<String> untrusted = new HashSet<>();

        RuleConfig b03 = in.active("B03");
        if (b03 != null) {
            BigDecimal rounding = LpNumbers.zeroIfNull(b03.num("roundingMl"));
            for (LedgerEvent e : day) {
                BigDecimal opening = e.num("openingMl");
                BigDecimal net = e.num("ledgerNetMl");
                BigDecimal system = e.num("systemMl");
                if (opening == null || net == null || system == null) {
                    continue;
                }
                BigDecimal expected = opening.add(net);
                BigDecimal gap = system.subtract(expected);
                if (gap.abs().compareTo(rounding) > 0) {
                    untrusted.add(e.id());
                    out.add(new Finding("B03", key(e), "Stock movements do not tie to system stock", "product",
                            e.text("productId"), expected, system, gap, "ml", null, null,
                            Obj.of("productName", e.text("productName"), "openingMl", opening, "ledgerNetMl", net,
                                    "movements", e.payload().get("movements"), "sessionId", e.sessionId()),
                            List.of(e.id())));
                }
            }
        }

        RuleConfig b01 = in.active("B01");
        if (b01 != null) {
            for (LedgerEvent e : day) {
                // A line whose movements do not tie is a data question first, not a loss question.
                if (untrusted.contains(e.id()) || !overTolerance(e, b01) || explained(e)) {
                    continue;
                }
                BigDecimal variance = e.num("varianceMl");
                BigDecimal cost = e.num("costPerMl");
                boolean shortage = variance.signum() < 0;
                out.add(new Finding("B01", key(e), "Unexplained bottle variance above tolerance", "product",
                        e.text("productId"), e.num("systemMl"), e.num("countedMl"), variance, "ml",
                        shortage && cost != null ? LpNumbers.round(variance.abs().multiply(cost)) : null,
                        shortage && cost != null ? "Shortage in ml × average cost per ml" : null,
                        Obj.of("productName", e.text("productName"), "toleranceMl", tolerance(e, b01), "reasonCode",
                                e.text("reasonCode"), "soldMl", e.num("soldMl"), "openingMl", e.num("openingMl"),
                                "movements", e.payload().get("movements"), "sessionId", e.sessionId()),
                        List.of(e.id())));
            }
        }

        RuleConfig b02 = in.active("B02");
        if (b02 != null) {
            int counts = Math.max(1, b02.intValue("counts", 4));
            int minRepeats = b02.intValue("minRepeats", 3);
            for (LedgerEvent e : day) {
                String reason = e.text("reasonCode");
                String product = e.text("productId");
                if (reason == null || product == null || UNEXPLAINED.contains(reason) || !overTolerance(e, b02)) {
                    continue;
                }
                List<LedgerEvent> recent = lines.values().stream()
                        .filter(o -> product.equals(o.text("productId")) && !o.occurredAt().isAfter(e.occurredAt()))
                        .sorted(Comparator.comparing(LedgerEvent::occurredAt).reversed()).limit(counts).toList();
                List<LedgerEvent> same = recent.stream()
                        .filter(o -> reason.equals(o.text("reasonCode")) && overTolerance(o, b02)).toList();
                if (same.size() >= minRepeats) {
                    BigDecimal total = same.stream().map(o -> o.num("varianceMl")).reduce(BigDecimal.ZERO,
                            BigDecimal::add);
                    out.add(new Finding("B02", product + ":" + reason + ":" + key(e), "Same cause given for one"
                            + " product in consecutive counts", "product", product, BigDecimal.valueOf(minRepeats),
                            BigDecimal.valueOf(same.size()), BigDecimal.valueOf(same.size() - minRepeats), "count",
                            null, null, Obj.of("productName", e.text("productName"), "reasonCode", reason,
                                    "countsCompared", recent.size(), "varianceMlTotal", total),
                            same.stream().map(LedgerEvent::id).toList()));
                }
            }
        }
        return out;
    }

    private static String key(LedgerEvent e) {
        return e.lineId() != null ? e.lineId() : e.id();
    }

    private static boolean explained(LedgerEvent e) {
        String reason = e.text("reasonCode");
        return reason != null && !UNEXPLAINED.contains(reason);
    }

    /** The line's own tolerance when the sender gave one, else the rule's. */
    private static BigDecimal tolerance(LedgerEvent e, RuleConfig rule) {
        BigDecimal own = e.num("toleranceMl");
        if (own != null && own.signum() > 0) {
            return own;
        }
        BigDecimal fromRule = rule.num("toleranceMl");
        return fromRule != null ? fromRule : BigDecimal.valueOf(30);
    }

    private static boolean overTolerance(LedgerEvent e, RuleConfig rule) {
        BigDecimal variance = e.num("varianceMl");
        return variance != null && variance.abs().compareTo(tolerance(e, rule)) > 0;
    }
}
