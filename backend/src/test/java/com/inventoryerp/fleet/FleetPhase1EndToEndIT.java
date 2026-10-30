package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Phase 1 exit: sales order → delivery order → loads → truck + driver (eligibility) → weighbridge ticket through the
 * simulator indicator → completion feeds the trip → dispatch issues the weighed stock exactly once → proof of delivery
 * → customer invoice with the transport charge → trip closed with distance → every journal of the flow balanced and
 * stock on hand down by the weighed quantity. Branches: out-of-tolerance (exception → second-user approval → feeds the
 * trip) and void before dispatch (trip back to loading → reissue feeds it again → void after dispatch refused).
 */
class FleetPhase1EndToEndIT extends FleetWeighDispatchSupport {

    /** Journal entries whose source is one of the flow's documents (trip, invoice). */
    List<String> journalsOf(List<String> sourceIds) {
        return jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceId\" = ANY(?::text[])")
                .params(demoOrg, sourceIds.toArray(String[]::new)).query(String.class).list();
    }

    @Test
    @DisplayName("sales order → DO → plan → assign (eligibility) → weighbridge ticket → dispatch once from the ticket →"
            + " POD → invoice + transport charge → close with distance → GL balanced, stock down by the net weight")
    void salesOrderToInvoiceThroughTheWeighbridge() {
        // 1. Confirmed back-office sales order of the quarry branch → delivery order.
        String salesOrder = ok(200, "POST", "/orders", Map.of("partnerId", customer, "orderType", "delivery",
                "branchId", branch, "lines", List.of(Map.of("productId", aggregate, "quantity", 20, "unitPrice",
                        45000)))).get("id").asString();
        JsonNode doc = ok(201, "POST", "/fleet/delivery-orders/from-sales-order", Map.of("sourceId", salesOrder,
                "siteId", site, "locationId", yard, "requestedDate", today));
        String doId = doc.get("id").asString();
        assertThat(doc.get("branchId").asString()).isEqualTo(branch);
        assertThat(doc.get("stockMode").asString()).isEqualTo("dispatch");
        assertThat(doc.get("lines").get(0).get("uomId").asString()).isEqualTo(ton);
        ok(200, "POST", "/orders/" + salesOrder + "/cancel", Map.of("reason", "Delivered through fleet dispatch"));

        // 2. Plan the load on a 30 t tipper with an eligible driver (an ineligible truck is refused).
        String tipper = truck(30);
        String small = truck(10);
        String drv = driver();
        JsonNode check = ok(200, "GET", "/fleet/delivery-orders/" + doId + "/load-plan?vehicleId=" + tipper, null);
        assertThat(check.get("loads")).hasSize(1);
        String tripId = plan(doc, tipper, drv);
        JsonNode tooSmall = ok(200, "GET", "/fleet/trips/" + tripId + "/assignment-check?vehicleId=" + small
                + "&driverId=" + drv, null);
        assertThat(tooSmall.get("eligible").asBoolean()).isFalse();
        assertThat(ok(200, "GET", "/fleet/trips/" + tripId + "/assignment-check?vehicleId=" + tipper + "&driverId="
                + drv, null).get("eligible").asBoolean()).isTrue();
        JsonNode planned = trip(tripId);
        assertThat(planned.get("weighbridge").get("required").asBoolean()).isTrue();
        assertThat(planned.get("weighbridge").get("reason").asString()).contains("is a bulk product");
        ok(201, "POST", "/fleet/trips/" + tripId + "/start-loading", Map.of("odometer", 1010));

        // 3. Dispatch without a ticket is refused (bulk rule of the branch); so is a hand-entered loaded quantity.
        Res noTicket = money("POST", "/fleet/trips/" + tripId + "/dispatch", null);
        assertThat(noTicket.status()).isEqualTo(400);
        assertThat(msg(noTicket)).contains("needs a completed weighbridge ticket before dispatch");
        Res manual = as("POST", "/fleet/trips/" + tripId + "/loaded-quantity", Map.of("lines", List.of(Map.of(
                "loadLineId", line(planned, 0).get("id").asString(), "quantity", 20))));
        assertThat(manual.status()).isEqualTo(400);
        assertThat(msg(manual)).contains("must be weighed on the weighbridge");

        // 4. Weighbridge ticket from the trip (pre-filled), simulator tare 12 000 kg → gross 32 050 kg.
        JsonNode prefill = call("GET", "/fleet/weighbridge/lookups/trips/" + tripId, null, operator).body();
        assertThat(prefill.get("vehicleId").asString()).isEqualTo(tipper);
        assertThat(dec(prefill.get("orderedQty"))).isEqualByComparingTo("20");
        JsonNode ticket = ticketFor(tripId);
        String ticketId = ticket.get("id").asString();
        assertThat(ticket.get("vehicleId").asString()).isEqualTo(tipper);
        assertThat(ticket.get("driverId").asString()).isEqualTo(drv);
        assertThat(ticket.get("partnerId").asString()).isEqualTo(customer);
        assertThat(ticket.get("deliveryOrderId").asString()).isEqualTo(doId);
        assertThat(ticket.get("salesOrderId").asString()).isEqualTo(salesOrder);
        assertThat(ticket.get("productId").asString()).isEqualTo(aggregate);
        assertThat(ticket.get("orderedUomId").asString()).isEqualTo(ton);
        assertThat(dec(ticket.get("expectedKg"))).isEqualByComparingTo("20000");
        assertThat(ticket.get("trip").get("code").asString()).isEqualTo(planned.get("code").asString());
        // While the ticket is live the trip cannot leave.
        Res live = money("POST", "/fleet/trips/" + tripId + "/dispatch", null);
        assertThat(msg(live)).contains("is on weighbridge ticket " + ticket.get("ticketNumber").asString());
        JsonNode done = weighed(ticketId, 20050);
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(dec(done.get("netQty"))).isEqualByComparingTo("20.05");
        assertThat(done.get("tripFeedStatus").asString()).isEqualTo("fed");

        // 5. Completion fed the trip: loaded 20.05 t from the ticket.
        JsonNode weighedTrip = trip(tripId);
        assertThat(weighedTrip.get("status").asString()).isEqualTo("weighed");
        assertThat(weighedTrip.get("weighSourceType").asString()).isEqualTo("weighbridge_ticket");
        assertThat(weighedTrip.get("weighSourceId").asString()).isEqualTo(ticketId);
        assertThat(dec(line(weighedTrip, 0).get("loadedQty"))).isEqualByComparingTo("20.05");
        assertThat(dec(weighedTrip.get("loadedTonnes"))).isEqualByComparingTo("20.05");
        assertThat(weighedTrip.get("weighbridge").get("fedByTicket").asBoolean()).isTrue();
        JsonNode linked = weighedTrip.get("weighbridgeTickets").get(0);
        assertThat(linked.get("ticketNumber").asString()).isEqualTo(ticket.get("ticketNumber").asString());
        assertThat(dec(linked.get("netKg"))).isEqualByComparingTo("20050");
        assertThat(dec(linked.get("varianceKg"))).isEqualByComparingTo("50");
        assertThat(linked.get("isManual").asBoolean()).isFalse();
        assertThat(ok(200, "GET", "/fleet/delivery-orders/" + doId, null).get("weighbridgeTickets").get(0).get("id")
                .asString()).isEqualTo(ticketId);

        // 6. Dispatch issues the weighed quantity exactly once (replay with the same key, second key refused).
        BigDecimal before = onHand(aggregate);
        Map<String, String> k = keyed(adminToken);
        Res first = call("POST", "/fleet/trips/" + tripId + "/dispatch", null, k);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        assertThat(dec(line(first.body(), 0).get("dispatchedQty"))).isEqualByComparingTo("20.05");
        assertThat(call("POST", "/fleet/trips/" + tripId + "/dispatch", null, k).status()).isEqualTo(200);
        assertThat(money("POST", "/fleet/trips/" + tripId + "/dispatch", null).status()).isEqualTo(400);
        assertThat(onHand(aggregate)).isEqualByComparingTo(before.subtract(new BigDecimal("20.05")));
        Map<String, Object> ledger = jdbc.sql("SELECT count(*) AS n, SUM(\"quantityChange\") AS q, MIN(\"productId\")"
                + " AS p FROM \"InventoryLedger\" WHERE \"referenceType\" = 'fleet_trip' AND \"referenceId\" = ?")
                .param(tripId).query().singleRow();
        assertThat(((Number) ledger.get("n")).longValue()).isEqualTo(1);
        assertThat(((BigDecimal) ledger.get("q")).abs()).isEqualByComparingTo("20.05");
        assertThat(ledger.get("p")).isEqualTo(aggregate);

        // 7. Proof of delivery in full, close with the odometer distance.
        ok(201, "POST", "/fleet/trips/" + tripId + "/depart", null);
        Res pod = money("POST", "/fleet/trips/" + tripId + "/proof-of-delivery", Map.of("receiverName", "Site agent",
                "lines", List.of(Map.of("loadLineId", line(weighedTrip, 0).get("id").asString(), "deliveredQty",
                        20.05))));
        assertThat(pod.status()).as(pod.raw().body()).isEqualTo(200);
        assertThat(pod.body().get("status").asString()).isEqualTo("delivered");
        JsonNode closed = ok(201, "POST", "/fleet/trips/" + tripId + "/close", Map.of("endOdometer", 1052));
        assertThat(closed.get("status").asString()).isEqualTo("closed");
        assertThat(dec(closed.get("distanceKm"))).isEqualByComparingTo("42");
        // After dispatch the ticket can no longer be voided.
        Res voidLate = call("POST", "/fleet/weighbridge/tickets/" + ticketId + "/void", Map.of("reason", "late"),
                adminToken);
        assertThat(voidLate.status()).isEqualTo(409);

        // 8. Invoice the delivered goods + the transport charge (per tonne delivered).
        ok(201, "POST", "/fleet/delivery-charge-rules", Map.of("name", "Quarry per t " + sfx, "partnerId", customer,
                "basis", "per_tonne", "rate", 2000));
        Res invoiced = money("POST", "/fleet/delivery-orders/" + doId + "/invoice", null);
        assertThat(invoiced.status()).as(invoiced.raw().body()).isEqualTo(200);
        String invoiceId = invoiced.body().get("invoice").get("id").asString();
        JsonNode inv = ok(200, "GET", "/invoices/" + invoiceId, null);
        assertThat(inv.get("lines")).hasSize(2);
        BigDecimal charge = BigDecimal.ZERO;
        for (JsonNode l : inv.get("lines")) {
            if (l.path("productId").isNull() || l.path("productId").isMissingNode()) {
                charge = dec(l.get("subtotal"));
            } else {
                assertThat(l.get("productId").asString()).isEqualTo(aggregate);
                assertThat(dec(l.get("quantity"))).isEqualByComparingTo("20.05");
            }
        }
        assertThat(charge).isEqualByComparingTo("40100");
        BigDecimal beforePost = onHand(aggregate);
        JsonNode posted = ok(200, "POST", "/invoices/" + invoiceId + "/post", null);
        assertThat(posted.get("status").asString()).isEqualTo("posted");
        assertThat(onHand(aggregate)).isEqualByComparingTo(beforePost);

        // 9. Every journal of the flow balanced, and in total.
        List<String> journals = journalsOf(List.of(tripId, invoiceId));
        assertThat(journals).hasSizeGreaterThanOrEqualTo(2).contains(posted.get("journalEntryId").asString());
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (String j : journals) {
            BigDecimal[] dc = assertBalanced(j);
            debit = debit.add(dc[0]);
            credit = credit.add(dc[1]);
        }
        assertThat(debit).isEqualByComparingTo(credit);
        // The COGS of the dispatch is the weighed 20.05 t at the 30 000 average cost.
        BigDecimal cogs = jdbc.sql("SELECT COALESCE(SUM(debit), 0) FROM \"JournalLine\" WHERE \"journalEntryId\" ="
                + " ANY(?::text[])").param(journalsOf(List.of(tripId)).toArray(String[]::new))
                .query(BigDecimal.class).single();
        assertThat(cogs).isEqualByComparingTo("601500");
        assertThat(ok(200, "GET", "/fleet/delivery-orders/" + doId, null).get("status").asString())
                .isIn("delivered", "closed", "invoiced");
    }

    @Test
    @DisplayName("out of tolerance: exception keeps the trip unfed and undispatchable → the operator cannot approve →"
            + " a supervisor approves → the approved net feeds the trip and is what dispatch issues")
    void outOfToleranceNeedsASecondUser() {
        String tripId = aggregateTrip(20);
        JsonNode ticket = ticketFor(tripId);
        String ticketId = ticket.get("id").asString();
        JsonNode exception = weighed(ticketId, 18800);
        assertThat(exception.get("status").asString()).isEqualTo("exception");
        assertThat(exception.path("tripFeedStatus").isNull()).isTrue();
        JsonNode unfed = trip(tripId);
        assertThat(unfed.get("status").asString()).isEqualTo("planned");
        assertThat(line(unfed, 0).path("loadedQty").isNull()).isTrue();
        assertThat(unfed.get("weighbridge").get("liveTicket").get("status").asString()).isEqualTo("exception");
        Res blocked = money("POST", "/fleet/trips/" + tripId + "/dispatch", null);
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(msg(blocked)).contains("out of tolerance, waiting for approval");

        String exceptionId = exception.get("exceptions").get(0).get("id").asString();
        // The operator who weighed the truck cannot decide the exception; a supervisor (second user) does.
        Res selfApprove = call("POST", "/fleet/weighbridge/exceptions/" + exceptionId + "/approve", Map.of(),
                operator);
        assertThat(selfApprove.status()).isEqualTo(403);
        Res approved = call("POST", "/fleet/weighbridge/exceptions/" + exceptionId + "/approve", Map.of("notes",
                "Short load accepted by the customer"), supervisor);
        assertThat(approved.status()).as(approved.raw().body()).isEqualTo(201);
        assertThat(approved.body().get("status").asString()).isEqualTo("completed");
        assertThat(approved.body().get("tripFeedStatus").asString()).isEqualTo("fed");

        JsonNode fed = trip(tripId);
        assertThat(fed.get("status").asString()).isEqualTo("weighed");
        assertThat(dec(line(fed, 0).get("loadedQty"))).isEqualByComparingTo("18.8");
        BigDecimal before = onHand(aggregate);
        JsonNode dispatched = dispatchOk(tripId);
        assertThat(dec(line(dispatched, 0).get("dispatchedQty"))).isEqualByComparingTo("18.8");
        assertThat(onHand(aggregate)).isEqualByComparingTo(before.subtract(new BigDecimal("18.8")));
        List<String> issue = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"sourceType\" = 'fleet_trip' AND"
                + " \"sourceId\" = ?").param(tripId).query(String.class).list();
        assertThat(issue).hasSize(1);
        assertBalanced(issue.getFirst());
        // The 1.2 t not loaded is plannable again.
        assertThat(dec(ok(200, "GET", "/fleet/delivery-orders/" + fed.get("deliveryOrderId").asString(), null)
                .get("lines").get(0).get("remainingQty"))).isEqualByComparingTo("1.2");
    }

    @Test
    @DisplayName("void before dispatch: the trip goes back to loading with its weight cleared → a new ticket feeds it"
            + " → void + reissue feeds it from the reissued ticket → dispatch issues that weight → void refused after")
    void voidBeforeDispatch() {
        String tripId = aggregateTrip(20);
        String a = ticketFor(tripId).get("id").asString();
        weighed(a, 19950);
        assertThat(dec(line(trip(tripId), 0).get("loadedQty"))).isEqualByComparingTo("19.95");

        JsonNode voided = ok(201, "POST", "/fleet/weighbridge/tickets/" + a + "/void", Map.of("reason",
                "Wrong truck on the platform"));
        assertThat(voided.get("voided").get("status").asString()).isEqualTo("void");
        assertThat(voided.get("voided").get("tripFeedStatus").asString()).isEqualTo("cleared");
        JsonNode cleared = trip(tripId);
        assertThat(cleared.get("status").asString()).isEqualTo("loading");
        assertThat(line(cleared, 0).path("loadedQty").isNull()).isTrue();
        assertThat(cleared.path("loadedTonnes").isNull()).isTrue();
        assertThat(cleared.path("weighSourceId").isNull()).isTrue();
        assertThat(msg(money("POST", "/fleet/trips/" + tripId + "/dispatch", null)))
                .contains("needs a completed weighbridge ticket before dispatch");

        String b = ticketFor(tripId).get("id").asString();
        weighed(b, 20010);
        assertThat(trip(tripId).get("weighSourceId").asString()).isEqualTo(b);

        JsonNode reissue = ok(201, "POST", "/fleet/weighbridge/tickets/" + b + "/void", Map.of("reason",
                "Driver name misspelt", "reissue", true, "corrections", Map.of("driverName", "J. Okello")));
        String c = reissue.get("reissued").get("id").asString();
        assertThat(reissue.get("reissued").get("status").asString()).isEqualTo("completed");
        assertThat(reissue.get("reissued").get("tripFeedStatus").asString()).isEqualTo("fed");
        JsonNode refed = trip(tripId);
        assertThat(refed.get("status").asString()).isEqualTo("weighed");
        assertThat(refed.get("weighSourceId").asString()).isEqualTo(c);
        assertThat(dec(line(refed, 0).get("loadedQty"))).isEqualByComparingTo("20.01");
        List<String> statuses = new ArrayList<>();
        refed.get("weighbridgeTickets").forEach(n -> statuses.add(n.get("status").asString()));
        assertThat(statuses).containsExactly("void", "void", "completed");

        BigDecimal before = onHand(aggregate);
        dispatchOk(tripId);
        assertThat(onHand(aggregate)).isEqualByComparingTo(before.subtract(new BigDecimal("20.01")));
        Res late = call("POST", "/fleet/weighbridge/tickets/" + c + "/void", Map.of("reason", "too late"), adminToken);
        assertThat(late.status()).isEqualTo(409);
        assertThat(msg(late)).contains("which is dispatched: its stock is issued");
        assertThat(ok(200, "GET", "/fleet/weighbridge/tickets/" + c, null).get("status").asString())
                .isEqualTo("completed");
    }
}
