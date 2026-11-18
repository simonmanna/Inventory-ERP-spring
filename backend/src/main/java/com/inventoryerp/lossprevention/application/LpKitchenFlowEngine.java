package com.inventoryerp.lossprevention.application;

import static com.inventoryerp.lossprevention.application.LpEventTypes.KITCHEN_STAGE;
import static com.inventoryerp.lossprevention.application.LpEventTypes.KITCHEN_TICKET_CREATED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.ORDER_CANCELLED;
import static com.inventoryerp.lossprevention.application.LpEventTypes.SALE_COMPLETED;

import com.inventoryerp.kernel.common.Obj;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The kitchen's staged flow (LP4, docs/phases/LP4-restaurant-cafe.md), per business day of a branch, without cameras.
 *
 * <ul>
 *   <li>K01: a ticket served from the kitchen with no sale recorded for its order, that day or the next.</li>
 *   <li>K02: remakes at one station above the threshold.</li>
 * </ul>
 *
 * <p>Stage evidence is ticket-level and inferred (ADR-S077 §3): the findings are about tickets and stations, never
 * about a person.
 */
@Component
public class LpKitchenFlowEngine implements LpEngine {

    public static final String VERSION = "1";

    private static final Set<String> TYPES = Set.of(KITCHEN_TICKET_CREATED, KITCHEN_STAGE, SALE_COMPLETED,
            ORDER_CANCELLED);
    /** Stages that mean the food left the kitchen. */
    private static final Set<String> SERVED = Set.of("dispatched", "delivered");

    @Override
    public String code() {
        return LpRuleCatalog.KITCHEN_FLOW;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> eventTypes() {
        return TYPES;
    }

    @Override
    public Map<String, Integer> lookbackDays() {
        // A ticket created before midnight and served after; a sale paid up front the day before.
        return Map.of(KITCHEN_TICKET_CREATED, 1, SALE_COMPLETED, 1);
    }

    @Override
    public Map<String, Integer> lookaheadDays() {
        // A tab paid after the business day ended.
        return Map.of(SALE_COMPLETED, 1, ORDER_CANCELLED, 1, KITCHEN_STAGE, 1);
    }

    /** A ticket as the day's events describe it. */
    private static final class Ticket {
        final String id;
        LedgerEvent created;
        LedgerEvent served;
        String orderId;
        String transactionId;
        String station;
        boolean cancelled;
        boolean remade;
        final List<String> eventIds = new ArrayList<>();

        Ticket(String id) {
            this.id = id;
        }
    }

    @Override
    public List<Finding> evaluate(Input in) {
        Map<String, Ticket> tickets = new LinkedHashMap<>();
        Set<String> paidOrders = new HashSet<>();
        Set<String> paidTransactions = new HashSet<>();
        Set<String> cancelledOrders = new HashSet<>();
        for (LedgerEvent e : in.events()) {
            switch (e.type()) {
                case SALE_COMPLETED -> {
                    if (e.orderId() != null) {
                        paidOrders.add(e.orderId());
                    }
                    if (e.transactionId() != null) {
                        paidTransactions.add(e.transactionId());
                    }
                }
                case ORDER_CANCELLED -> {
                    if (e.orderId() != null) {
                        cancelledOrders.add(e.orderId());
                    }
                }
                case KITCHEN_TICKET_CREATED, KITCHEN_STAGE -> {
                    if (e.ticketId() == null) {
                        continue;
                    }
                    Ticket t = tickets.computeIfAbsent(e.ticketId(), Ticket::new);
                    t.eventIds.add(e.id());
                    t.orderId = e.orderId() != null ? e.orderId() : t.orderId;
                    t.transactionId = e.transactionId() != null ? e.transactionId() : t.transactionId;
                    t.station = e.text("station") != null ? e.text("station") : t.station;
                    if (KITCHEN_TICKET_CREATED.equals(e.type())) {
                        t.created = e;
                        continue;
                    }
                    String stage = e.text("stage");
                    if ("cancelled".equals(stage)) {
                        t.cancelled = true;
                    } else if ("remade".equals(stage) && in.businessDate().equals(e.businessDate())) {
                        t.remade = true;
                    } else if (SERVED.contains(stage) && in.businessDate().equals(e.businessDate())
                            && (t.served == null || e.occurredAt().isBefore(t.served.occurredAt()))) {
                        t.served = e;
                    }
                }
                default -> {
                    // not read
                }
            }
        }
        List<Finding> out = new ArrayList<>();
        servedWithoutSale(in, tickets, paidOrders, paidTransactions, cancelledOrders, out);
        remakes(in, tickets, out);
        return out;
    }

    // ---------------------------------------------------------------- K01

    private static void servedWithoutSale(Input in, Map<String, Ticket> tickets, Set<String> paidOrders,
            Set<String> paidTransactions, Set<String> cancelledOrders, List<Finding> out) {
        if (in.active("K01") == null) {
            return;
        }
        for (Ticket t : tickets.values()) {
            if (t.served == null || t.cancelled || t.orderId == null && t.transactionId == null) {
                continue;
            }
            if (t.orderId != null && (paidOrders.contains(t.orderId) || cancelledOrders.contains(t.orderId))
                    || t.transactionId != null && paidTransactions.contains(t.transactionId)) {
                continue;
            }
            BigDecimal items = itemCount(t);
            out.add(new Finding("K01", t.id, "Food served from the kitchen with no sale recorded", "ticket", t.id,
                    BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE.negate(), "count", null, null,
                    Obj.of("station", t.station, "ticketNo", t.created == null ? null : t.created.text("ticketNo"),
                            "orderId", t.orderId, "transactionId", t.transactionId, "itemCount", items, "orderType",
                            t.created == null ? null : t.created.text("orderType"), "servedAt",
                            t.served.occurredAt().toString(), "stage", t.served.text("stage")),
                    List.copyOf(new LinkedHashSet<>(t.eventIds))));
        }
    }

    private static BigDecimal itemCount(Ticket t) {
        BigDecimal fromServed = t.served == null ? null : t.served.num("itemCount");
        return fromServed != null ? fromServed : t.created == null ? null : t.created.num("itemCount");
    }

    // ---------------------------------------------------------------- K02

    private static void remakes(Input in, Map<String, Ticket> tickets, List<Finding> out) {
        RuleConfig rule = in.active("K02");
        if (rule == null) {
            return;
        }
        int minCount = Math.max(1, rule.intValue("minCount", 3));
        BigDecimal maxRate = LpNumbers.zeroIfNull(rule.num("maxRatePercent"));
        Map<String, Integer> created = new LinkedHashMap<>();
        Map<String, List<String>> remade = new LinkedHashMap<>();
        for (Ticket t : tickets.values()) {
            String station = t.station == null ? "" : t.station;
            if (t.created != null && in.businessDate().equals(t.created.businessDate())) {
                created.merge(station, 1, Integer::sum);
            }
            if (t.remade) {
                remade.computeIfAbsent(station, k -> new ArrayList<>()).add(t.id);
            }
        }
        for (Map.Entry<String, List<String>> r : remade.entrySet()) {
            int count = r.getValue().size();
            int total = Math.max(count, created.getOrDefault(r.getKey(), 0));
            BigDecimal rate = BigDecimal.valueOf(count * 100L).divide(BigDecimal.valueOf(total), 1,
                    RoundingMode.HALF_UP);
            if (count < minCount || rate.compareTo(maxRate) <= 0) {
                continue;
            }
            List<String> evidence = new ArrayList<>();
            r.getValue().forEach(id -> evidence.addAll(tickets.get(id).eventIds));
            out.add(new Finding("K02", r.getKey(), "Remakes at one station above the threshold", "station",
                    r.getKey(), maxRate, rate, rate.subtract(maxRate), "percent", null, null,
                    Obj.of("station", r.getKey(), "remadeTickets", count, "tickets", total, "maxRatePercent", maxRate,
                            "ticketIds", r.getValue()),
                    evidence.stream().distinct().limit(50).toList()));
        }
    }
}
