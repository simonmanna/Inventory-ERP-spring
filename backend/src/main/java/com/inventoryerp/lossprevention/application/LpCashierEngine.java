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
 * Cashier and kitchen rules C01–C10 on recorded facts only: voids, cancellations, refunds, overrides, discounts, cash
 * sessions and kitchen tickets of one branch on one business day.
 */
@Component
public class LpCashierEngine implements LpEngine {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String code() {
        return LpRuleCatalog.CASHIER;
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(LpEventTypes.SALE_COMPLETED, LpEventTypes.SALE_LINE_VOIDED, LpEventTypes.ORDER_CANCELLED,
                LpEventTypes.REFUND_ISSUED, LpEventTypes.OVERRIDE_APPROVED, LpEventTypes.CASH_SESSION_CLOSED,
                LpEventTypes.KITCHEN_STAGE);
    }

    @Override
    public Map<String, Integer> lookbackDays() {
        return Map.of(LpEventTypes.OVERRIDE_APPROVED, 30, LpEventTypes.CASH_SESSION_CLOSED, 60);
    }

    @Override
    public List<Finding> evaluate(Input in) {
        List<LedgerEvent> day = in.events().stream().filter(e -> in.businessDate().equals(e.businessDate())).toList();
        List<Finding> out = new ArrayList<>();
        voidsAndCancellations(in, day, out);
        refunds(in, day, out);
        overrides(in, day, out);
        discounts(in, day, out);
        cashSessions(in, day, out);
        kitchen(in, day, out);
        return out;
    }

    private static List<LedgerEvent> ofType(List<LedgerEvent> events, String type) {
        return events.stream().filter(e -> type.equals(e.type())).toList();
    }

    private static String keyOf(String preferred, LedgerEvent e) {
        return preferred != null ? preferred : e.id();
    }

    // ---------------------------------------------------------------- C01, C02

    private static void voidsAndCancellations(Input in, List<LedgerEvent> day, List<Finding> out) {
        List<LedgerEvent> cancelled = ofType(day, LpEventTypes.ORDER_CANCELLED);
        Set<String> cancelledOrders = new HashSet<>();
        cancelled.forEach(e -> cancelledOrders.add(e.orderId()));

        if (in.active("C01") != null) {
            // A line voided in steps arrives once per step, each with the quantity voided so far: keep the last.
            Map<String, LedgerEvent> latest = new LinkedHashMap<>();
            for (LedgerEvent e : ofType(day, LpEventTypes.SALE_LINE_VOIDED)) {
                latest.merge(keyOf(e.lineId(), e), e, (a, b) -> LpNumbers.zeroIfNull(b.num("quantity"))
                        .compareTo(LpNumbers.zeroIfNull(a.num("quantity"))) >= 0 ? b : a);
            }
            latest.forEach((key, e) -> {
                BigDecimal fired = LpNumbers.zeroIfNull(e.num("firedQuantity"));
                // A whole order cancelled is one finding (C02), not one per line.
                if (fired.signum() <= 0 || (e.orderId() != null && cancelledOrders.contains(e.orderId()))) {
                    return;
                }
                BigDecimal quantity = LpNumbers.zeroIfNull(e.num("quantity"));
                out.add(new Finding("C01", key, "Item voided after it was sent to the kitchen", "actor", e.actorId(),
                        BigDecimal.ZERO, quantity, quantity, "qty", e.num("amount"),
                        e.num("amount") == null ? null : "Voided quantity × unit price",
                        Obj.of("description", e.text("description"), "reason", e.text("reason"), "firedQuantity",
                                fired, "approvedById", e.text("approvedById"), "orderId", e.orderId()),
                        List.of(e.id())));
            });
        }
        if (in.active("C02") != null) {
            for (LedgerEvent e : cancelled) {
                if (!(e.payload().get("firedLines") instanceof List<?> lines) || lines.isEmpty()) {
                    continue;
                }
                BigDecimal fired = BigDecimal.ZERO;
                for (Object line : lines) {
                    if (line instanceof Map<?, ?> m) {
                        fired = fired.add(LpNumbers.zeroIfNull(LpNumbers.decimal(m.get("firedQuantity"))));
                    }
                }
                out.add(new Finding("C02", keyOf(e.orderId(), e), "Order cancelled after items were sent to the"
                        + " kitchen", "actor", e.actorId(), BigDecimal.ZERO, fired, fired, "qty",
                        e.num("firedAmount"), e.num("firedAmount") == null ? null : "Fired quantity × unit price",
                        Obj.of("reason", e.text("reason"), "firedLines", lines, "orderId", e.orderId()),
                        List.of(e.id())));
            }
        }
    }

    // ---------------------------------------------------------------- C03, C04

    private static void refunds(Input in, List<LedgerEvent> day, List<Finding> out) {
        List<LedgerEvent> refunds = ofType(day, LpEventTypes.REFUND_ISSUED);
        RuleConfig c03 = in.active("C03");
        if (c03 != null && c03.num("amount") != null) {
            BigDecimal threshold = c03.num("amount");
            for (LedgerEvent e : refunds) {
                BigDecimal amount = LpNumbers.zeroIfNull(e.num("amount"));
                if (amount.compareTo(threshold) >= 0) {
                    out.add(new Finding("C03", e.id(), "Refund at or above the threshold", "actor", e.actorId(),
                            threshold, amount, amount.subtract(threshold), "amount", amount, "Refund amount",
                            Obj.of("reason", e.text("reason"), "approvedById", e.text("approvedById"),
                                    "transactionId", e.transactionId(), "originalActorId", e.text("originalActorId")),
                            List.of(e.id())));
                }
            }
        }
        RuleConfig c04 = in.active("C04");
        if (c04 != null) {
            aboveBranch(c04, "C04", "Refunds by one cashier well above the branch", refunds, activeCashiers(day),
                    out);
        }
    }

    /** Everybody who sold, refunded or was given an override that day: the population a cashier is compared with. */
    private static Set<String> activeCashiers(List<LedgerEvent> day) {
        Set<String> actors = new HashSet<>();
        for (LedgerEvent e : day) {
            if (e.actorId() != null && (LpEventTypes.SALE_COMPLETED.equals(e.type())
                    || LpEventTypes.REFUND_ISSUED.equals(e.type())
                    || LpEventTypes.OVERRIDE_APPROVED.equals(e.type()))) {
                actors.add(e.actorId());
            }
        }
        return actors;
    }

    /** One finding per cashier whose count of {@code events} is over the minimum and over multiplier × median. */
    private static void aboveBranch(RuleConfig rule, String code, String title, List<LedgerEvent> events,
            Set<String> cashiers, List<Finding> out) {
        Map<String, List<LedgerEvent>> byActor = new LinkedHashMap<>();
        for (LedgerEvent e : events) {
            if (e.actorId() != null) {
                byActor.computeIfAbsent(e.actorId(), k -> new ArrayList<>()).add(e);
            }
        }
        List<BigDecimal> counts = new ArrayList<>();
        for (String cashier : cashiers) {
            counts.add(BigDecimal.valueOf(byActor.getOrDefault(cashier, List.of()).size()));
        }
        BigDecimal median = LpNumbers.median(counts);
        BigDecimal limit = median.multiply(LpNumbers.zeroIfNull(rule.num("multiplier")));
        int minCount = rule.intValue("minCount", 1);
        byActor.forEach((actor, list) -> {
            BigDecimal count = BigDecimal.valueOf(list.size());
            if (list.size() >= minCount && count.compareTo(limit) > 0) {
                out.add(new Finding(code, actor, title, "actor", actor, LpNumbers.round(limit), count,
                        LpNumbers.round(count.subtract(limit)), "count", null, null,
                        Obj.of("branchMedian", median, "cashiersCompared", cashiers.size()),
                        list.stream().map(LedgerEvent::id).toList()));
            }
        });
    }

    // ---------------------------------------------------------------- C05, C06

    private static void overrides(Input in, List<LedgerEvent> day, List<Finding> out) {
        List<LedgerEvent> today = ofType(day, LpEventTypes.OVERRIDE_APPROVED);
        RuleConfig c05 = in.active("C05");
        if (c05 != null) {
            aboveBranch(c05, "C05", "Overrides for one cashier well above the branch", today, activeCashiers(day),
                    out);
        }
        RuleConfig c06 = in.active("C06");
        if (c06 == null) {
            return;
        }
        int days = Math.max(1, c06.intValue("days", 7));
        int minCount = c06.intValue("minCount", 5);
        BigDecimal share = LpNumbers.zeroIfNull(c06.num("sharePercent"));
        Set<String> cashiersToday = new HashSet<>();
        today.forEach(e -> cashiersToday.add(e.actorId()));
        Map<String, List<LedgerEvent>> byCashier = new LinkedHashMap<>();
        for (LedgerEvent e : ofType(in.events(), LpEventTypes.OVERRIDE_APPROVED)) {
            if (e.actorId() != null && cashiersToday.contains(e.actorId())
                    && e.businessDate().isAfter(in.businessDate().minusDays(days))) {
                byCashier.computeIfAbsent(e.actorId(), k -> new ArrayList<>()).add(e);
            }
        }
        byCashier.forEach((cashier, list) -> {
            if (list.size() < minCount) {
                return;
            }
            Map<String, List<LedgerEvent>> byManager = new LinkedHashMap<>();
            for (LedgerEvent e : list) {
                String manager = e.text("approverId");
                if (manager != null) {
                    byManager.computeIfAbsent(manager, k -> new ArrayList<>()).add(e);
                }
            }
            byManager.forEach((manager, approved) -> {
                BigDecimal percent = LpNumbers.divide(BigDecimal.valueOf(approved.size()).multiply(HUNDRED),
                        BigDecimal.valueOf(list.size()));
                if (percent.compareTo(share) >= 0) {
                    out.add(new Finding("C06", cashier + ":" + manager, "One manager approves most of a cashier's"
                            + " overrides", "actor", cashier, share, percent, percent.subtract(share), "percent",
                            null, null, Obj.of("approverId", manager, "overrides", list.size(), "approvedByManager",
                                    approved.size(), "days", days),
                            approved.stream().map(LedgerEvent::id).toList()));
                }
            });
        });
    }

    // ---------------------------------------------------------------- C07

    private static void discounts(Input in, List<LedgerEvent> day, List<Finding> out) {
        RuleConfig rule = in.active("C07");
        if (rule == null) {
            return;
        }
        int minSales = rule.intValue("minSales", 10);
        Map<String, List<LedgerEvent>> byCashier = new LinkedHashMap<>();
        for (LedgerEvent e : ofType(day, LpEventTypes.SALE_COMPLETED)) {
            if (e.actorId() != null) {
                byCashier.computeIfAbsent(e.actorId(), k -> new ArrayList<>()).add(e);
            }
        }
        Map<String, BigDecimal[]> totals = new LinkedHashMap<>();
        List<BigDecimal> rates = new ArrayList<>();
        byCashier.forEach((cashier, sales) -> {
            if (sales.size() < minSales) {
                return;
            }
            BigDecimal discount = BigDecimal.ZERO;
            BigDecimal gross = BigDecimal.ZERO;
            for (LedgerEvent s : sales) {
                BigDecimal d = LpNumbers.zeroIfNull(s.num("discountTotal"));
                discount = discount.add(d);
                gross = gross.add(LpNumbers.zeroIfNull(s.num("total"))).add(d);
            }
            BigDecimal rate = LpNumbers.divide(discount.multiply(HUNDRED), gross);
            totals.put(cashier, new BigDecimal[] {discount, gross, rate});
            rates.add(rate);
        });
        BigDecimal median = LpNumbers.median(rates);
        BigDecimal limit = median.multiply(LpNumbers.zeroIfNull(rule.num("multiplier")));
        BigDecimal minRate = LpNumbers.zeroIfNull(rule.num("minRatePercent"));
        totals.forEach((cashier, t) -> {
            if (t[2].compareTo(limit) > 0 && t[2].compareTo(minRate) >= 0) {
                BigDecimal atMedian = t[1].multiply(median).divide(HUNDRED, LpNumbers.SCALE,
                        java.math.RoundingMode.HALF_UP);
                out.add(new Finding("C07", cashier, "Discount rate of one cashier well above the branch", "actor",
                        cashier, LpNumbers.round(limit), t[2], LpNumbers.round(t[2].subtract(limit)), "percent",
                        LpNumbers.round(t[0].subtract(atMedian).max(BigDecimal.ZERO)),
                        "Discount given above the branch median rate",
                        Obj.of("discountTotal", t[0], "grossSales", t[1], "branchMedianPercent", median, "sales",
                                byCashier.get(cashier).size()),
                        List.of()));
            }
        });
    }

    // ---------------------------------------------------------------- C08, C09

    private static void cashSessions(Input in, List<LedgerEvent> day, List<Finding> out) {
        // A reopened session closes again: the last close of a session is the one that counts.
        Map<String, LedgerEvent> closes = new LinkedHashMap<>();
        for (LedgerEvent e : ofType(in.events(), LpEventTypes.CASH_SESSION_CLOSED)) {
            closes.merge(keyOf(e.sessionId(), e), e, (a, b) -> b.occurredAt().isBefore(a.occurredAt()) ? a : b);
        }
        List<LedgerEvent> today = closes.values().stream()
                .filter(e -> in.businessDate().equals(e.businessDate())).toList();

        RuleConfig c08 = in.active("C08");
        if (c08 != null && c08.num("amount") != null) {
            BigDecimal tolerance = c08.num("amount");
            for (LedgerEvent e : today) {
                BigDecimal difference = LpNumbers.zeroIfNull(e.num("difference"));
                if (difference.abs().compareTo(tolerance) > 0) {
                    out.add(new Finding("C08", keyOf(e.sessionId(), e), "Cash session difference above tolerance",
                            "actor", e.actorId(), e.num("expected"), e.num("counted"), difference, "amount",
                            difference.signum() < 0 ? difference.abs() : null,
                            difference.signum() < 0 ? "Cash short at close" : null,
                            Obj.of("tolerance", tolerance, "varianceReason", e.text("varianceReason"), "sessionId",
                                    e.sessionId()),
                            List.of(e.id())));
                }
            }
        }
        RuleConfig c09 = in.active("C09");
        if (c09 == null) {
            return;
        }
        int sessions = Math.max(1, c09.intValue("sessions", 10));
        int minShort = c09.intValue("minShort", 4);
        for (LedgerEvent close : today) {
            if (close.actorId() == null || LpNumbers.zeroIfNull(close.num("difference")).signum() >= 0) {
                continue;
            }
            List<LedgerEvent> recent = closes.values().stream()
                    .filter(e -> close.actorId().equals(e.actorId()) && !e.occurredAt().isAfter(close.occurredAt()))
                    .sorted(Comparator.comparing(LedgerEvent::occurredAt).reversed()).limit(sessions).toList();
            List<LedgerEvent> shortOnes = recent.stream()
                    .filter(e -> LpNumbers.zeroIfNull(e.num("difference")).signum() < 0).toList();
            if (shortOnes.size() >= minShort) {
                BigDecimal total = shortOnes.stream().map(e -> e.num("difference").abs())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                out.add(new Finding("C09", close.actorId() + ":" + keyOf(close.sessionId(), close), "Repeated short"
                        + " cash sessions for one cashier", "actor", close.actorId(), BigDecimal.valueOf(minShort),
                        BigDecimal.valueOf(shortOnes.size()), BigDecimal.valueOf(shortOnes.size() - minShort),
                        "count", total, "Sum of the short sessions counted",
                        Obj.of("sessionsCompared", recent.size(), "sessionId", close.sessionId()),
                        shortOnes.stream().map(LedgerEvent::id).toList()));
            }
        }
    }

    // ---------------------------------------------------------------- C10

    private static void kitchen(Input in, List<LedgerEvent> day, List<Finding> out) {
        if (in.active("C10") == null) {
            return;
        }
        for (LedgerEvent e : ofType(day, LpEventTypes.KITCHEN_STAGE)) {
            String stage = e.text("stage");
            if (!("remade".equals(stage) || "cancelled".equals(stage))
                    || !Boolean.parseBoolean(String.valueOf(e.payload().get("afterReady")))) {
                continue;
            }
            out.add(new Finding("C10", keyOf(e.ticketId(), e) + ":" + stage + ":" + e.text("recallCount"),
                    "Kitchen ticket " + ("remade".equals(stage) ? "recalled" : "cancelled") + " after it was ready",
                    "ticket", e.ticketId(), null, null, null, null, null, null,
                    Obj.of("stage", stage, "station", e.text("station"), "recallReason", e.text("recallReason"),
                            "orderId", e.orderId(), "evidenceLevel", e.evidenceLevel(), "observationStatus",
                            e.observationStatus()),
                    List.of(e.id())));
        }
    }
}
