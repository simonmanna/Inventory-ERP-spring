package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.inventoryerp.fleet.application.WeighbridgeTicketService;
import com.inventoryerp.fleet.application.WeighbridgeTicketService.CompletedTicket;
import com.inventoryerp.fleet.web.weighbridge.WeighbridgeDispatchController;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Fleet 1.4b weighbridge ↔ dispatch: trip picker / pre-fill, link validation (FKs, open trip, one live ticket per
 * trip), multi-product trips fed by kg over planned tonnage, a feed that fails (no weight conversion) and its retry,
 * the "weighbridge required for dispatch" rules (organization / branch, all / bulk / categories with sub-categories,
 * validation), a live ticket blocking dispatch even when the weighbridge is optional, concurrent dispatches of a
 * ticket-fed trip, the public API, tenant isolation and the route matrix.
 */
class FleetWeighbridgeDispatchIT extends FleetWeighDispatchSupport {

    @Autowired
    WeighbridgeTicketService tickets;

    @Test
    @DisplayName("trip picker + pre-fill; links: unknown trip, other delivery order, non-sale subject, second live"
            + " ticket, dispatched trip; ticket shows its trip / delivery order; public API by trip / delivery order")
    void prefillAndLinks() {
        JsonNode order = deliveryOrder(branch, aggregate, 12, sand, 5);
        String doId = order.get("id").asString();
        String tripId = plan(order, truck(30), driver());
        JsonNode t = trip(tripId);
        String code = t.get("code").asString();
        JsonNode picker = call("GET", "/fleet/weighbridge/lookups/trips?search=" + code, null, operator).body();
        assertThat(picker).hasSize(1);
        assertThat(picker.get(0).get("products")).hasSize(2);
        assertThat(picker.get(0).path("liveTicket").isNull() || picker.get(0).path("liveTicket").isMissingNode())
                .isTrue();
        // Two products: no single product, the planned tonnage (12 t + 5 m³ × 1.6 t) in TON.
        JsonNode pre = call("GET", "/fleet/weighbridge/lookups/trips/" + tripId, null, operator).body();
        assertThat(pre.path("productId").isNull() || pre.path("productId").isMissingNode()).isTrue();
        assertThat(dec(pre.get("orderedQty"))).isEqualByComparingTo("20");
        assertThat(pre.get("orderedUomCode").asString()).isEqualTo("TON");
        assertThat(pre.get("deliveryOrderId").asString()).isEqualTo(doId);
        assertThat(pre.get("partnerId").asString()).isEqualTo(customer);
        assertThat(pre.get("referenceNumber").asString()).contains(code);
        assertThat(pre.get("weighbridge").get("required").asBoolean()).isTrue();
        assertThat(call("GET", "/fleet/weighbridge/lookups/trips/" + UUID.randomUUID(), null, operator).status())
                .isEqualTo(404);

        Res unknown = call("POST", "/fleet/weighbridge/tickets", Map.of("stationId", station, "subjectType",
                "sale_delivery", "registrationPlate", plate(), "tripId", UUID.randomUUID().toString()), operator);
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(msg(unknown)).isEqualTo("Trip not found");
        String otherDo = deliveryOrder(branch, aggregate, 1).get("id").asString();
        Res wrongDo = call("POST", "/fleet/weighbridge/tickets", Map.of("stationId", station, "subjectType",
                "sale_delivery", "tripId", tripId, "deliveryOrderId", otherDo), operator);
        assertThat(msg(wrongDo)).isEqualTo("Trip " + code + " belongs to another delivery order than "
                + ok(200, "GET", "/fleet/delivery-orders/" + otherDo, null).get("code").asString());
        Res noDo = call("POST", "/fleet/weighbridge/tickets", Map.of("stationId", station, "subjectType",
                "sale_delivery", "registrationPlate", plate(), "deliveryOrderId", "DO-FAKE"), operator);
        assertThat(msg(noDo)).isEqualTo("Delivery order not found");
        Res internal = call("POST", "/fleet/weighbridge/tickets", Map.of("stationId", station, "subjectType",
                "internal_haulage", "registrationPlate", plate(), "tripId", tripId), operator);
        assertThat(msg(internal)).isEqualTo("Only sale deliveries reference a sales order or delivery order");

        JsonNode ticket = ticketFor(tripId);
        assertThat(ticket.get("trip").get("id").asString()).isEqualTo(tripId);
        assertThat(ticket.get("deliveryOrder").get("code").asString()).isEqualTo(order.get("code").asString());
        assertThat(ticket.path("productId").isNull()).isTrue();
        assertThat(dec(ticket.get("expectedKg"))).isEqualByComparingTo("20000");
        Res second = call("POST", "/fleet/weighbridge/tickets", Map.of("stationId", station, "subjectType",
                "sale_delivery", "registrationPlate", plate(), "tripId", tripId), operator);
        assertThat(second.status()).isEqualTo(409);
        assertThat(msg(second)).isEqualTo("Trip " + code + " is already on live weighbridge ticket "
                + ticket.get("ticketNumber").asString());
        assertThat(call("GET", "/fleet/weighbridge/lookups/trips?search=" + code, null, operator).body().get(0)
                .get("liveTicket").get("id").asString()).isEqualTo(ticket.get("id").asString());

        // Multi-product: the net kg is spread by planned tonnage (12 t aggregate, 8 t sand) → 20 080 kg.
        JsonNode done = weighed(ticket.get("id").asString(), 20080);
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(done.get("tripFeedStatus").asString()).isEqualTo("fed");
        JsonNode fed = trip(tripId);
        BigDecimal agg = BigDecimal.ZERO;
        BigDecimal sandQty = BigDecimal.ZERO;
        for (JsonNode l : fed.get("lines")) {
            if (aggregate.equals(l.get("productId").asString())) {
                agg = dec(l.get("loadedQty"));
            } else {
                sandQty = dec(l.get("loadedQty"));
            }
        }
        assertThat(agg).isEqualByComparingTo("12.048");
        assertThat(sandQty).isEqualByComparingTo("5.02");
        assertThat(dec(fed.get("loadedTonnes"))).isCloseTo(new BigDecimal("20.08"), within(new BigDecimal("0.002")));
        assertThat(fed.get("weighedUom").asString()).isEqualTo("kg");

        List<CompletedTicket> byTrip = TenantContext.run(TenantStore.user(demoOrg, adminId),
                () -> tickets.completedTicketsForTrip(tripId));
        assertThat(byTrip).extracting(CompletedTicket::id).containsExactly(ticket.get("id").asString());
        assertThat(TenantContext.run(TenantStore.user(demoOrg, adminId),
                () -> tickets.completedTicketsForDeliveryOrder(doId))).hasSize(1);

        // A dispatched trip cannot be weighed any more.
        dispatchOk(tripId);
        Res late = call("POST", "/fleet/weighbridge/tickets", Map.of("stationId", station, "subjectType",
                "sale_delivery", "tripId", tripId), operator);
        assertThat(late.status()).isEqualTo(400);
        assertThat(msg(late)).isEqualTo("Trip " + code + " is dispatched: only planned, loading or weighed trips can"
                + " be weighed");
    }

    @Test
    @DisplayName("feed failure: no weight conversion → ticket completed, trip not fed (failed + reason) → feed-trip"
            + " refused → kg-per-unit setting → feed-trip feeds (idempotent)")
    void feedFailureAndRetry() {
        String bag = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", code, name, category, factor, ratio,"
                + " \"roundingPrecision\", \"updatedAt\") VALUES (?, ?, ?, 'Bag', 'unit', 1, 1, 1, now())")
                .params(bag, demoOrg, "BAGW-" + sfx).update();
        String lime = product("Bagged lime", bag);
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new org.springframework.transaction.support
                .TransactionTemplate(txm).executeWithoutResult(s -> stock.receive(com.inventoryerp.inventory
                        .application.StockMove.of(lime, yard, new BigDecimal("100")).unitCost(new BigDecimal("900")))));
        String tripId = plan(deliveryOrder(branch, lime, 40), truck(30), driver());
        JsonNode t = trip(tripId);
        assertThat(t.get("weighbridge").get("required").asBoolean()).isFalse();
        JsonNode ticket = ticketFor(tripId);
        assertThat(ticket.get("productId").asString()).isEqualTo(lime);
        assertThat(ticket.path("orderedQty").isNull()).isTrue();
        String id = ticket.get("id").asString();
        JsonNode done = weighed(id, 2000);
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(done.get("tripFeedStatus").asString()).isEqualTo("failed");
        assertThat(done.get("tripFeedMessage").asString()).startsWith("No weight conversion for Bagged lime");
        assertThat(trip(tripId).get("status").asString()).isEqualTo("planned");
        Res refused = call("POST", "/fleet/weighbridge/tickets/" + id + "/feed-trip", null, operator);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(msg(refused)).startsWith("No weight conversion for Bagged lime");

        ok(200, "PUT", "/fleet/weighbridge/product-settings/" + lime, Map.of("kgPerUnit", 50));
        Res fed = call("POST", "/fleet/weighbridge/tickets/" + id + "/feed-trip", null, operator);
        assertThat(fed.status()).as(fed.raw().body()).isEqualTo(201);
        assertThat(fed.body().get("tripFeedStatus").asString()).isEqualTo("fed");
        JsonNode weighed = trip(tripId);
        assertThat(dec(line(weighed, 0).get("loadedQty"))).isEqualByComparingTo("40");
        assertThat(call("POST", "/fleet/weighbridge/tickets/" + id + "/feed-trip", null, operator).status())
                .isEqualTo(201);
        // With a weighbridge product setting the product now counts as bulk: dispatch needs (and has) the ticket.
        assertThat(weighed.get("weighbridge").get("required").asBoolean()).isTrue();
        BigDecimal before = onHand(lime);
        dispatchOk(tripId);
        assertThat(onHand(lime)).isEqualByComparingTo(before.subtract(new BigDecimal("40")));
        Res notLinked = call("POST", "/fleet/weighbridge/tickets/" + UUID.randomUUID() + "/feed-trip", null, operator);
        assertThat(notLinked.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("rules: validation, branch rule over organization rule, categories incl. sub-categories, all / off,"
            + " delete → fallback; a non-covered trip dispatches without a ticket")
    void requirementRules() {
        Res noCats = as("PUT", "/fleet/weighbridge/dispatch-requirements", Map.of("branchId", branch, "mode",
                "categories"));
        assertThat(msg(noCats)).isEqualTo("Choose at least one product category");
        assertThat(msg(as("PUT", "/fleet/weighbridge/dispatch-requirements", Map.of("branchId", "nope", "mode",
                "all")))).isEqualTo("Branch not found");
        assertThat(msg(as("PUT", "/fleet/weighbridge/dispatch-requirements", Map.of("branchId", branch, "mode",
                "categories", "categoryIds", List.of("nope"))))).isEqualTo("Product category not found");
        assertThat(as("PUT", "/fleet/weighbridge/dispatch-requirements", Map.of("mode", "sometimes")).status())
                .isEqualTo(400);

        String branch2 = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(branch2, demoOrg, "DEP-" + sfx, "Depot branch " + sfx).update();
        String parent = category("Aggregates", null);
        String child = category("Crushed stone", parent);
        String other = category("Hardware", null);
        String unit = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'UNIT'")
                .param(demoOrg).query(String.class).single();
        String stone = product("Crushed stone 10mm", unit);
        String nails = product("Nails box", unit);
        for (String p : List.of(stone, nails)) {
            jdbc.sql("UPDATE \"Product\" SET \"categoryId\" = ? WHERE id = ?").params(p.equals(stone) ? child : other, p)
                    .update();
            TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new org.springframework.transaction.support
                    .TransactionTemplate(txm).executeWithoutResult(s -> stock.receive(com.inventoryerp.inventory
                            .application.StockMove.of(p, yard, new BigDecimal("50")).unitCost(BigDecimal.TEN))));
        }
        JsonNode rule = ok(200, "PUT", "/fleet/weighbridge/dispatch-requirements", Map.of("branchId", branch2, "mode",
                "categories", "categoryIds", List.of(parent, parent)));
        assertThat(rule.get("categories")).hasSize(1);
        assertThat(rule.get("branchName").asString()).isEqualTo("Depot branch " + sfx);
        JsonNode overview = ok(200, "GET", "/fleet/weighbridge/dispatch-requirements", null);
        assertThat(overview.get("rules").toString()).contains(rule.get("id").asString(), bulkRule);
        assertThat(overview.get("branches").toString()).contains(branch2);
        assertThat(overview.get("categories").toString()).contains(child);
        // Same scope again = update in place.
        assertThat(ok(200, "PUT", "/fleet/weighbridge/dispatch-requirements", Map.of("branchId", branch2, "mode",
                "categories", "categoryIds", List.of(parent), "notes", "Aggregates only")).get("id").asString())
                .isEqualTo(rule.get("id").asString());

        String stoneTrip = plan(deliveryOrder(branch2, stone, 5), truck(30), driver());
        JsonNode st = trip(stoneTrip);
        assertThat(st.get("weighbridge").get("required").asBoolean()).isTrue();
        assertThat(st.get("weighbridge").get("reason").asString()).contains("(category Aggregates " + sfx + ")")
                .endsWith("at branch Depot branch " + sfx);
        assertThat(msg(money("POST", "/fleet/trips/" + stoneTrip + "/dispatch", null)))
                .contains("needs a completed weighbridge ticket before dispatch");
        String nailsTrip = plan(deliveryOrder(branch2, nails, 5), truck(30), driver());
        assertThat(trip(nailsTrip).get("weighbridge").get("required").asBoolean()).isFalse();
        ok(201, "POST", "/fleet/trips/" + nailsTrip + "/loaded-quantity", Map.of("lines", List.of(Map.of(
                "loadLineId", line(trip(nailsTrip), 0).get("id").asString(), "quantity", 4))));
        assertThat(dec(line(dispatchOk(nailsTrip), 0).get("dispatchedQty"))).isEqualByComparingTo("4");

        ok(200, "PUT", "/fleet/weighbridge/dispatch-requirements", Map.of("branchId", branch2, "mode", "all"));
        String allTrip = plan(deliveryOrder(branch2, nails, 2), truck(30), driver());
        assertThat(trip(allTrip).get("weighbridge").get("reason").asString())
                .startsWith("the weighbridge is required for all products");
        ok(200, "PUT", "/fleet/weighbridge/dispatch-requirements", Map.of("branchId", branch2, "mode", "off"));
        assertThat(trip(allTrip).get("weighbridge").get("required").asBoolean()).isFalse();

        // Deleting the branch rule falls back to the organization rule (none in DEMO → not required).
        assertThat(ok(200, "DELETE", "/fleet/weighbridge/dispatch-requirements/" + rule.get("id").asString(), null)
                .get("deleted").asBoolean()).isTrue();
        assertThat(as("DELETE", "/fleet/weighbridge/dispatch-requirements/" + rule.get("id").asString(), null)
                .status()).isEqualTo(404);
        assertThat(trip(stoneTrip).get("weighbridge").get("required").asBoolean()).isFalse();
        dispatchOk(stoneTrip);
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'FleetWeighbridgeRequirement'"
                + " AND \"entityId\" = ?").param(rule.get("id").asString()).query(Long.class).single())
                .isGreaterThanOrEqualTo(4);
    }

    String category(String name, String parentId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ProductCategory\" (id, \"organizationId\", name, \"parentId\", \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, now())").params(id, demoOrg, name + " " + sfx, parentId).update();
        return id;
    }

    @Test
    @DisplayName("optional weighbridge: a live ticket still blocks dispatch; cancelling it frees the trip; the manual"
            + " loaded quantity stays allowed")
    void liveTicketBlocksDispatch() {
        String tripId = plan(deliveryOrder(null, aggregate, 10), truck(30), driver());
        JsonNode t = trip(tripId);
        assertThat(t.get("weighbridge").get("required").asBoolean()).isFalse();
        JsonNode ticket = ticketFor(tripId);
        weigh(ticket.get("id").asString(), "first", 12000);
        Res blocked = money("POST", "/fleet/trips/" + tripId + "/dispatch", null);
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(msg(blocked)).isEqualTo("Trip " + t.get("code").asString() + " is on weighbridge ticket "
                + ticket.get("ticketNumber").asString() + " (waiting for the second weighing): complete or cancel the"
                + " ticket before dispatch");
        assertThat(trip(tripId).get("weighbridge").get("liveTicket").get("status").asString())
                .isEqualTo("first_weighed");
        ok(201, "POST", "/fleet/weighbridge/tickets/" + ticket.get("id").asString() + "/cancel", Map.of("reason",
                "Truck sent to the other gate"));
        ok(201, "POST", "/fleet/trips/" + tripId + "/loaded-quantity", Map.of("lines", List.of(Map.of("loadLineId",
                line(t, 0).get("id").asString(), "quantity", 9.5))));
        assertThat(dec(line(dispatchOk(tripId), 0).get("dispatchedQty"))).isEqualByComparingTo("9.5");
    }

    @Test
    @DisplayName("concurrency: five simultaneous dispatches of a ticket-fed trip issue the weighed stock once")
    void concurrentDispatch() throws Exception {
        String tripId = aggregateTrip(10);
        weighed(ticketFor(tripId).get("id").asString(), 10020);
        BigDecimal before = onHand(aggregate);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return call("POST", "/fleet/trips/" + tripId + "/dispatch", null, keyed(adminToken)).status();
                }));
            }
            go.countDown();
            int okCount = 0;
            for (Future<Integer> f : results) {
                if (f.get() == 200) {
                    okCount++;
                }
            }
            assertThat(okCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(onHand(aggregate)).isEqualByComparingTo(before.subtract(new BigDecimal("10.02")));
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"referenceType\" = 'fleet_trip' AND"
                + " \"referenceId\" = ?").param(tripId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("tenant isolation: another organization cannot see, pre-fill, feed or change DEMO's links and rules")
    void tenantIsolation() {
        String tripId = aggregateTrip(5);
        String ticketId = ticketFor(tripId).get("id").asString();
        assertThat(call("GET", "/fleet/weighbridge/lookups/trips", null, otherToken).body().toString())
                .doesNotContain(tripId);
        assertThat(call("GET", "/fleet/weighbridge/lookups/trips/" + tripId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/fleet/weighbridge/tickets/" + ticketId + "/feed-trip", null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/fleet/weighbridge/dispatch-requirements", null, otherToken).body().toString())
                .doesNotContain(bulkRule).doesNotContain(branch);
        assertThat(call("DELETE", "/fleet/weighbridge/dispatch-requirements/" + bulkRule, null, otherToken).status())
                .isEqualTo(404);
        assertThat(msg(call("PUT", "/fleet/weighbridge/dispatch-requirements", Map.of("branchId", branch, "mode",
                "all"), otherToken))).isEqualTo("Branch not found");
        assertThat(ok(200, "GET", "/fleet/weighbridge/dispatch-requirements", null).get("rules").toString())
                .contains(bulkRule);
    }

    @Test
    @DisplayName("authorization: 401 / 403 on the six 1.4b routes; settings need fleet:settings")
    void authorization() {
        assertThat(assertAuthorizationMatrix(WeighbridgeDispatchController.class)).isEqualTo(6);
        String reader = tokenWith("fleet_dispatch:read", "weighbridge:read");
        assertThat(call("GET", "/fleet/weighbridge/dispatch-requirements", null, reader).status()).isEqualTo(200);
        Res put = call("PUT", "/fleet/weighbridge/dispatch-requirements", Map.of("mode", "all"), reader);
        assertThat(put.status()).isEqualTo(403);
        assertThat(msg(put)).isEqualTo("Missing required permission(s): fleet:settings");
        assertThat(call("POST", "/fleet/weighbridge/tickets/x/feed-trip", null, reader).status()).isEqualTo(403);
    }
}
